package com.tarteelcompanion.extraction

import com.tarteelcompanion.data.Detection
import com.tarteelcompanion.data.model.MistakeType
import com.tarteelcompanion.quran.QuranRepository

/**
 * The real U4 pipeline (spike verdict b, width-model revision): segment the text band
 * into ink runs, align against a KNOWN page (user-entered on the first screenshot of a
 * batch, or the batch's previous page neighborhood), then map colored mistake glyphs
 * to dataset words through their run's token range. Deterministic; anything that
 * doesn't fit routes to NEEDS_MANUAL (R2/R6 — the gate never guesses).
 */
class AnchoringPipeline(quran: QuranRepository) : ExtractionPipeline {

    private val matcher = PageMatcher(quran)

    /**
     * Batch flow: adjacent pages score too similarly for a safe auto-pick (corpus
     * finding), so the hint — usually the batch's previous page — anchors DIRECTLY and
     * the confirm screen is the authority: the page number is shown prominently and a
     * correction re-anchors via [anchorAt].
     */
    override fun extract(pixels: IntArray, width: Int, height: Int, pageHint: Int?): ExtractionResult {
        if (pageHint == null) {
            return ExtractionResult.NeedsManual("page unknown — enter the page number from Tarteel's header")
        }
        return anchorAt(pixels, width, height, pageHint)
    }

    override fun anchorAt(pixels: IntArray, width: Int, height: Int, page: Int): ExtractionResult {
        val grid = PixelGrid(pixels, width, height)
        val runs = Segmentation.segment(grid)
        if (runs.size < 20) return ExtractionResult.NeedsManual("too little text found")
        val alignment = matcher.alignToPage(runs, page)
            ?: return ExtractionResult.NeedsManual("page $page does not fit this screenshot")
        return anchored(grid, runs, alignment)
    }

    private fun anchored(
        grid: PixelGrid,
        runs: List<Segmentation.WordCluster>,
        alignment: PageMatcher.PageAlignment,
    ): ExtractionResult {
        val top = (grid.height * Segmentation.BAND_TOP_FRACTION).toInt()
        val bottom = (grid.height * Segmentation.BAND_BOTTOM_FRACTION).toInt()
        val colored = ColorClassifier.findClusters(grid, top, bottom)

        val detections = mutableListOf<Detection>()
        for (c in colored) {
            val type: MistakeType = ColorClassifier.classify(c) ?: continue // olive glow etc.

            var runIdx = -1
            var bestOverlap = 0
            runs.forEachIndexed { idx, run ->
                if (c.centerY < run.lineTop || c.centerY > run.lineBottom) return@forEachIndexed
                val overlap = minOf(c.maxX, run.maxX) - maxOf(c.minX, run.minX)
                if (overlap > bestOverlap) {
                    bestOverlap = overlap
                    runIdx = idx
                }
            }
            if (runIdx < 0) continue

            // Tokens flow right-to-left within the run; slot by proportional units.
            val run = runs[runIdx]
            val range = alignment.runTokenRanges[runIdx]
            val tokens = range.map { alignment.tokens[it] }
            val totalUnits = tokens.sumOf { it.units }
            val frac = (run.maxX - c.centerX).toDouble() / run.width

            var cumulative = 0.0
            var chosen: Int? = null
            for (token in tokens) {
                cumulative += token.units / totalUnits
                if (frac <= cumulative) {
                    chosen = token.wordIndex ?: tokens.firstNotNullOfOrNull { it.wordIndex }
                    break
                }
            }
            val wordIndex = chosen ?: tokens.lastOrNull { !it.isMarker }?.wordIndex ?: continue
            val word = alignment.words.getOrNull(wordIndex) ?: continue
            detections.add(Detection(word.ref, type))
        }

        return ExtractionResult.Extracted(alignment.pageNumber, detections.distinctBy { it.ref })
    }
}
