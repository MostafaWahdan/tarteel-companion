package com.tarteelcompanion.extraction

import android.graphics.BitmapFactory
import com.tarteelcompanion.data.model.MistakeType
import com.tarteelcompanion.quran.QuranRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Golden tests over the real screenshot corpus (skip when samples/ or the dataset are
 * absent). Ground truth from the U3 spike: the 15:52 batch shows page 163 (Al-A'raf
 * 7:96–104), the 15:54 batch page 60 (Ali 'Imran 3:78–83). The pipeline anchors
 * against a KNOWN page (user-entered / batch hint — recorded U4 finding: blind page ID
 * from geometry is not achievable); the verification bar is zero silently-wrong
 * anchors: results outside the known page's surah are failures, poor fits must gate.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnchoringPipelineTest {

    private val quran: QuranRepository? get() = com.tarteelcompanion.TestData.quran

    private fun pipeline(): AnchoringPipeline {
        assumeTrue("dataset not fetched", quran != null)
        return AnchoringPipeline(quran!!)
    }

    private fun sampleFiles(): List<File> {
        val files = com.tarteelcompanion.TestData.sampleFiles()
        assumeTrue("samples/ not present", files.isNotEmpty())
        return files
    }

    private fun pixelsOf(file: File): Triple<IntArray, Int, Int> {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)!!
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return Triple(pixels, bitmap.width, bitmap.height)
    }

    private fun truePageOf(file: File) = if (file.name.contains("-15-52-")) 163 else 60

    @Test
    fun `anchoring at the known page yields in-surah detections for the whole corpus`() {
        val files = sampleFiles()
        var anchored = 0
        var totalDetections = 0
        val report = StringBuilder()

        for (file in files) {
            val truePage = truePageOf(file)
            val expectedSurah = if (truePage == 163) 7 else 3
            val (pixels, w, h) = pixelsOf(file)

            when (val result = pipeline().anchorAt(pixels, w, h, truePage)) {
                is ExtractionResult.Extracted -> {
                    anchored++
                    assertEquals(truePage, result.pageNumber)
                    for (d in result.detections) {
                        assertEquals(
                            "${file.name}: anchor ${d.ref} outside the known page's surah",
                            expectedSurah,
                            d.ref.surah,
                        )
                    }
                    totalDetections += result.detections.size
                    report.appendLine("${file.name}: page ${result.pageNumber}, ${result.detections.size} detections")
                    result.detections.forEach { report.appendLine("  ${it.ref} ${it.type}") }
                }
                is ExtractionResult.NeedsManual -> {
                    report.appendLine("${file.name}: NEEDS_MANUAL (${result.reason})")
                }
            }
        }

        File(com.tarteelcompanion.TestData.samplesDir!!.parentFile, "app/build/anchoring-report.txt")
            .writeText(report.toString())

        // Plan verification bar: ≥95% of the corpus anchors given the correct page.
        val rate = anchored.toDouble() / files.size
        assertTrue(
            "anchor rate ${"%.0f".format(rate * 100)}% below bar — see app/build/anchoring-report.txt",
            rate >= 0.95,
        )
        assertTrue("expected mistake detections across the corpus", totalDetections > 20)
    }

    @Test
    fun `known mistakes on page 60 include the yellow words of ayah 79`() {
        val file = sampleFiles().first { it.name.contains("-15-54-17") }
        val (pixels, w, h) = pixelsOf(file)
        val result = pipeline().anchorAt(pixels, w, h, 60)
        assertTrue("gated to manual unexpectedly", result is ExtractionResult.Extracted)
        result as ExtractionResult.Extracted
        assertTrue(
            "expected a pronunciation mark in 3:79, got ${result.detections}",
            result.detections.any { it.ref.surah == 3 && it.ref.ayah == 79 && it.type == MistakeType.PRONUNCIATION },
        )
    }

    @Test
    fun `batch hint from the previous page auto-anchors or gates - never misanchors`() {
        val file = sampleFiles().first { it.name.contains("-15-52-") }
        val (pixels, w, h) = pixelsOf(file)
        when (val result = pipeline().extract(pixels, w, h, pageHint = 163)) {
            is ExtractionResult.Extracted -> assertEquals(163, result.pageNumber)
            is ExtractionResult.NeedsManual -> {} // acceptable: ambiguity gates
        }
    }

    @Test
    fun `no page hint routes to manual page entry`() {
        val file = sampleFiles().first()
        val (pixels, w, h) = pixelsOf(file)
        assertTrue(pipeline().extract(pixels, w, h, pageHint = null) is ExtractionResult.NeedsManual)
    }

    @Test
    fun `non-tarteel image trips the gate even with a page`() {
        assumeTrue(quran != null)
        val w = 1080
        val h = 2400
        val pixels = IntArray(w * h) { 0xFF888888.toInt() }
        assertTrue(pipeline().anchorAt(pixels, w, h, 163) is ExtractionResult.NeedsManual)
    }

    @Test
    fun `wildly wrong page numbers are rejected`() {
        val file = sampleFiles().first() // page 163 content
        val (pixels, w, h) = pixelsOf(file)
        // Page 1 (al-Fatiha, 7 short ayat) cannot explain a full 15-line page.
        assertTrue(pipeline().anchorAt(pixels, w, h, 1) is ExtractionResult.NeedsManual)
    }
}
