package com.tarteelcompanion.extraction

import kotlin.math.abs

/**
 * Geometric segmentation of the session view's text band (spike verdict b): lines via
 * ink projection with per-row background (gray current-ayah bands shift the local
 * background), then word clusters via column-gap analysis, ordered RTL.
 *
 * Ink is theme-directional glyph detection: glyphs are bright-on-dark or dark-on-light
 * relative to the row's own background, OR saturated red-dominant (colored mistake
 * words). Olive history-glow blobs are green-dominant and deliberately NOT ink — they
 * otherwise fill word gaps and merge whole lines (diagnosed on the real corpus).
 */
object Segmentation {

    data class LineBand(val top: Int, val bottom: Int) {
        val height: Int get() = bottom - top
    }

    data class WordCluster(
        val line: Int,
        val minX: Int, val maxX: Int,
        val lineTop: Int, val lineBottom: Int,
    ) {
        val width: Int get() = maxX - minX + 1
        val aspect: Double get() = width.toDouble() / (lineBottom - lineTop)
    }

    /** Fractions of screen height bounding the mushaf text band (from the corpus). */
    const val BAND_TOP_FRACTION = 0.10
    const val BAND_BOTTOM_FRACTION = 0.88

    const val DEFAULT_WORD_GAP_FRACTION = 0.13

    private const val LUM_DELTA = 45
    private const val LINE_MERGE_GAP = 8
    private const val MIN_LINE_HEIGHT_FRACTION = 0.02 // of width

    private class Band(val grid: PixelGrid, val top: Int, val bottom: Int) {
        /** Per-row background luminance (median of 16 spaced samples). */
        val rowBg = IntArray(grid.height)

        /** True when the page background is dark (glyphs are bright). */
        var darkTheme = true

        init {
            val samples = IntArray(16)
            val all = ArrayList<Int>(bottom - top)
            for (y in top until bottom) {
                for (s in samples.indices) {
                    samples[s] = PixelGrid.luminance(grid[(grid.width - 1) * s / 15, y])
                }
                samples.sort()
                rowBg[y] = samples[8]
                all.add(samples[8])
            }
            all.sort()
            darkTheme = all[all.size / 2] < 128
        }

        /**
         * Glyph detection. Two conditions must hold:
         * 1. Local contrast — glyphs are thin high-frequency strokes; smooth color
         *    fills (history blobs, ayah-band overlays, band edges that end mid-row)
         *    have none and previously merged whole lines into single clusters.
         * 2. Departure from the row background (either direction, so both themes work)
         *    OR red-dominant saturation (colored mistake glyphs on the light theme sit
         *    close to the background in luminance).
         */
        fun isInk(x: Int, y: Int): Boolean {
            val c = grid[x, y]
            val lum = PixelGrid.luminance(c)

            val contrast = maxOf(
                abs(lum - PixelGrid.luminance(grid[maxOf(0, x - 3), y])),
                abs(lum - PixelGrid.luminance(grid[minOf(grid.width - 1, x + 3), y])),
                abs(lum - PixelGrid.luminance(grid[x, maxOf(0, y - 3)])),
                abs(lum - PixelGrid.luminance(grid[x, minOf(grid.height - 1, y + 3)])),
            )
            if (contrast <= CONTRAST_DELTA) return false

            if (abs(lum - rowBg[y]) > LUM_DELTA) return true
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            return (maxOf(r, g, b) - minOf(r, g, b) > 42) && r > g
        }

        /** Rows dominated by ink are band-edge/overlay artifacts — text never covers this much. */
        fun isArtifactRow(y: Int, xStart: Int, xEnd: Int): Boolean {
            var ink = 0
            for (x in xStart until xEnd) if (isInk(x, y)) ink++
            return ink > (xEnd - xStart) * 55 / 100
        }
    }

    private const val CONTRAST_DELTA = 25

    /** Horizontal inset excluding scrollbar/edge artifacts (fraction of width). */
    private const val X_INSET_FRACTION = 0.03

    fun findLines(grid: PixelGrid): List<LineBand> {
        val top = (grid.height * BAND_TOP_FRACTION).toInt()
        val bottom = (grid.height * BAND_BOTTOM_FRACTION).toInt()
        val band = Band(grid, top, bottom)
        val xStart = (grid.width * X_INSET_FRACTION).toInt()
        val xEnd = grid.width - xStart

        val threshold = (xEnd - xStart) / 50
        val bands = mutableListOf<LineBand>()
        var start = -1
        for (y in top until bottom) {
            var ink = 0
            for (x in xStart until xEnd) {
                if (band.isInk(x, y)) ink++
            }
            val isText = ink > threshold && ink <= (xEnd - xStart) * 55 / 100
            if (isText && start == -1) start = y
            if (!isText && start != -1) {
                bands.add(LineBand(start, y))
                start = -1
            }
        }
        if (start != -1) bands.add(LineBand(start, bottom))

        val merged = mutableListOf<LineBand>()
        for (b in bands) {
            val last = merged.lastOrNull()
            if (last != null && b.top - last.bottom <= LINE_MERGE_GAP) {
                merged[merged.size - 1] = LineBand(last.top, b.bottom)
            } else {
                merged.add(b)
            }
        }
        val minHeight = (grid.width * MIN_LINE_HEIGHT_FRACTION).toInt()
        return merged.filter { it.height >= minHeight }
    }

    /**
     * Words within a line: column ink profile (soft depth threshold to shrug off JPEG
     * speckle), runs merged across intra-word letter gaps, RTL output.
     */
    fun findWords(
        grid: PixelGrid,
        line: LineBand,
        lineIndex: Int,
        wordGapFraction: Double = DEFAULT_WORD_GAP_FRACTION,
    ): List<WordCluster> {
        val band = Band(grid, line.top, line.bottom)
        val xStart = (grid.width * X_INSET_FRACTION).toInt()
        val xEnd = grid.width - xStart
        val colInk = IntArray(grid.width)
        for (y in line.top until line.bottom) {
            if (band.isArtifactRow(y, xStart, xEnd)) continue // band-edge/overlay rows
            for (x in xStart until xEnd) {
                if (band.isInk(x, y)) colInk[x]++
            }
        }

        val depthThreshold = maxOf(2, line.height / 20)
        data class Run(val start: Int, val end: Int)
        val runs = mutableListOf<Run>()
        var runStart = -1
        for (x in 0 until grid.width) {
            val hasInk = colInk[x] >= depthThreshold
            if (hasInk && runStart == -1) runStart = x
            if (!hasInk && runStart != -1) {
                runs.add(Run(runStart, x - 1))
                runStart = -1
            }
        }
        if (runStart != -1) runs.add(Run(runStart, grid.width - 1))

        // Runs are the segmentation primitive (words may touch in justified rendering;
        // the PageMatcher's width-model alignment maps runs to words). Only close the
        // tiny anti-aliasing gaps here.
        val words = mutableListOf<WordCluster>()
        for (run in runs) {
            val last = words.lastOrNull()
            if (last != null && run.start - last.maxX <= RUN_MERGE_PX) {
                words[words.size - 1] = last.copy(maxX = run.end)
            } else {
                words.add(WordCluster(lineIndex, run.start, run.end, line.top, line.bottom))
            }
        }
        return words.filter { it.width >= MIN_RUN_PX }.sortedByDescending { it.minX } // RTL
    }

    private const val RUN_MERGE_PX = 3
    private const val MIN_RUN_PX = 8

    /** Full text band in recitation order: lines top-to-bottom, words right-to-left. */
    fun segment(grid: PixelGrid, wordGapFraction: Double = DEFAULT_WORD_GAP_FRACTION): List<WordCluster> =
        findLines(grid).mapIndexed { index, line -> findWords(grid, line, index, wordGapFraction) }.flatten()

    /** Test-only visibility into the raw column ink profile of a line. */
    fun columnProfileForDiagnostics(grid: PixelGrid, line: LineBand): IntArray {
        val band = Band(grid, line.top, line.bottom)
        val xStart = (grid.width * X_INSET_FRACTION).toInt()
        val xEnd = grid.width - xStart
        val colInk = IntArray(grid.width)
        for (y in line.top until line.bottom) {
            if (band.isArtifactRow(y, xStart, xEnd)) continue
            for (x in xStart until xEnd) {
                if (band.isInk(x, y)) colInk[x]++
            }
        }
        return colInk
    }
}
