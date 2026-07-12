package com.tarteelcompanion.extraction

import com.tarteelcompanion.data.Detection

/**
 * The deterministic anchoring pipeline's contract (plan U4, revised by the corpus
 * finding that pages cannot be identified blind from geometry). Input is a plain
 * pixel array (never android.graphics types — the golden suites run on plain JVM).
 *
 * Flow: with a page hint (previous screenshot in the batch), [extract] tries the
 * hint's neighborhood automatically. Without one, the confirm screen asks the user
 * for the page number — visible in Tarteel's own header — and [anchorAt] does the
 * rest: aligning the page's words to the ink runs and pre-marking colored mistakes.
 */
interface ExtractionPipeline {
    fun extract(pixels: IntArray, width: Int, height: Int, pageHint: Int? = null): ExtractionResult

    /** Anchors against a KNOWN page (user-entered or batch context). */
    fun anchorAt(pixels: IntArray, width: Int, height: Int, page: Int): ExtractionResult
}

sealed interface ExtractionResult {
    /** Page anchored and highlights mapped; the user still confirms (R5). */
    data class Extracted(val pageNumber: Int, val detections: List<Detection>) : ExtractionResult

    /**
     * The gate declined to guess (no page context, poor alignment fit, too little
     * text) — the user picks the page / tags manually on the canonical page (R6).
     */
    data class NeedsManual(val reason: String) : ExtractionResult
}

/** Fallback pipeline: everything routes to the manual/confirm surface. */
class ManualOnlyPipeline : ExtractionPipeline {
    override fun extract(pixels: IntArray, width: Int, height: Int, pageHint: Int?): ExtractionResult =
        ExtractionResult.NeedsManual("manual pipeline")

    override fun anchorAt(pixels: IntArray, width: Int, height: Int, page: Int): ExtractionResult =
        ExtractionResult.NeedsManual("manual pipeline")
}
