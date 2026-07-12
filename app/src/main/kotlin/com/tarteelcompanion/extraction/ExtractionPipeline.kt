package com.tarteelcompanion.extraction

import com.tarteelcompanion.data.Detection

/**
 * The deterministic anchoring pipeline's contract (plan U4). Input is a plain pixel
 * array (never android.graphics types — the golden suites run on plain JVM).
 */
interface ExtractionPipeline {
    fun extract(pixels: IntArray, width: Int, height: Int): ExtractionResult
}

sealed interface ExtractionResult {
    /** Page identified and every highlight anchored; user still confirms (R5). */
    data class Extracted(val pageNumber: Int, val detections: List<Detection>) : ExtractionResult

    /**
     * The validation gate declined to guess (count mismatch, low-confidence page ID,
     * force-fit distortion) — the user tags manually on the canonical page (R6).
     */
    data class NeedsManual(val reason: String) : ExtractionResult
}

/**
 * Placeholder until the U3 spike calibrates real swatches and the U4 pipeline lands:
 * every screenshot routes to manual tagging, which is the R6 fallback path and keeps
 * the full import→study→quiz loop usable in the meantime.
 */
class ManualOnlyPipeline : ExtractionPipeline {
    override fun extract(pixels: IntArray, width: Int, height: Int): ExtractionResult =
        ExtractionResult.NeedsManual("automated extraction pending the U3/U4 spike")
}
