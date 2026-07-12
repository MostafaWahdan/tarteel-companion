package com.tarteelcompanion.extraction

import android.graphics.BitmapFactory
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * U3 spike harness: measures the real Tarteel screenshots in samples/ and writes
 * build/spike-report.txt with calibration data for the U4 pipeline. Skips when the
 * sample corpus is absent (samples/ is gitignored personal data). Uses Robolectric
 * NATIVE graphics so BitmapFactory really decodes the JPEGs on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SpikeHarnessTest {

    private fun samplesDir(): File? {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            val candidate = File(dir, "samples")
            val hasImages = candidate.isDirectory &&
                candidate.listFiles()?.any { it.extension.lowercase() in setOf("jpg", "jpeg", "png") } == true
            if (hasImages) return candidate
            dir = dir.parentFile
        }
        return null
    }

    private class Cluster(
        var minX: Int, var maxX: Int, var minY: Int, var maxY: Int,
        var pixels: Int, var sumR: Long, var sumG: Long, var sumB: Long,
    ) {
        val width: Int get() = maxX - minX + 1
        val height: Int get() = maxY - minY + 1
        val fill: Double get() = pixels.toDouble() / (width.toDouble() * height)
        val avgR: Int get() = (sumR / pixels).toInt()
        val avgG: Int get() = (sumG / pixels).toInt()
        val avgB: Int get() = (sumB / pixels).toInt()

        val hueBucket: String
            get() = when {
                avgR > avgG && avgG > avgB && (avgG - avgB) >= (avgR - avgG) -> "yellow"
                avgR > avgG && avgR > avgB -> "red"
                avgG >= avgR && avgG > avgB -> "green"
                else -> "other"
            }
    }

    @Test
    fun `measure sample corpus and write spike report`() {
        val dir = samplesDir()
        assumeTrue("samples/ not present - spike skipped", dir != null)
        val files = dir!!.listFiles()!!
            .filter { it.extension.lowercase() in setOf("jpg", "jpeg", "png") }
            .sortedBy { it.name }

        val report = StringBuilder()
        report.append("U3 SPIKE REPORT - ").append(files.size).append(" samples\n")
        var totalTextClusters = 0

        for (file in files) {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: continue
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

            val top = (h * 0.13).toInt()
            val bottom = (h * 0.88).toInt()

            var bgLum = 0
            for (y in top until top + 50) bgLum += lum(pixels[y * w + 10])
            bgLum /= 50

            val visited = BooleanArray(w * h)
            val clusters = ArrayList<Cluster>()
            val stack = ArrayDeque<Int>()

            for (yy in top until bottom) {
                for (xx in 0 until w) {
                    val start = yy * w + xx
                    if (visited[start] || !isColored(pixels[start])) continue
                    val cl = Cluster(xx, xx, yy, yy, 0, 0L, 0L, 0L)
                    visited[start] = true
                    stack.addLast(start)
                    while (stack.isNotEmpty()) {
                        val p = stack.removeLast()
                        val px = p % w
                        val py = p / w
                        val c = pixels[p]
                        cl.pixels += 1
                        cl.sumR += (c shr 16) and 0xFF
                        cl.sumG += (c shr 8) and 0xFF
                        cl.sumB += c and 0xFF
                        if (px < cl.minX) cl.minX = px
                        if (px > cl.maxX) cl.maxX = px
                        if (py < cl.minY) cl.minY = py
                        if (py > cl.maxY) cl.maxY = py
                        // 4-neighbourhood
                        if (px + 1 < w) tryVisit(p + 1, pixels, visited, stack)
                        if (px - 1 >= 0) tryVisit(p - 1, pixels, visited, stack)
                        if (py + 1 < bottom) tryVisit(p + w, pixels, visited, stack)
                        if (py - 1 >= top) tryVisit(p - w, pixels, visited, stack)
                    }
                    if (cl.pixels >= 40) clusters.add(cl)
                }
            }

            val textClusters = clusters.filter { it.fill < 0.45 && it.height in 20..220 && it.hueBucket != "green" }
            val blobs = clusters.filter { it !in textClusters }
            totalTextClusters += textClusters.size

            report.append('\n').append(file.name)
                .append(": ").append(w).append('x').append(h)
                .append(" bgLum=").append(bgLum)
                .append(" clusters=").append(clusters.size).append('\n')
            for (c in textClusters) {
                report.append("  TEXT ").append(c.hueBucket)
                    .append(" rgb=(").append(c.avgR).append(',').append(c.avgG).append(',').append(c.avgB)
                    .append(") box=").append(c.width).append('x').append(c.height)
                    .append(" fill=").append("%.2f".format(c.fill))
                    .append(" y=").append(c.minY).append('\n')
            }
            for (c in blobs.take(6)) {
                report.append("  blob ").append(c.hueBucket)
                    .append(" rgb=(").append(c.avgR).append(',').append(c.avgG).append(',').append(c.avgB)
                    .append(") box=").append(c.width).append('x').append(c.height)
                    .append(" fill=").append("%.2f".format(c.fill)).append('\n')
            }
        }

        report.append("\nTOTAL text-mistake clusters across corpus: ").append(totalTextClusters).append('\n')
        val out = File(dir.parentFile, "app/build/spike-report.txt")
        out.parentFile.mkdirs()
        out.writeText(report.toString())

        assertTrue("expected colored mistake clusters in the corpus", totalTextClusters > 0)
    }

    private fun tryVisit(i: Int, pixels: IntArray, visited: BooleanArray, stack: ArrayDeque<Int>) {
        if (!visited[i] && isColored(pixels[i])) {
            visited[i] = true
            stack.addLast(i)
        }
    }

    private fun lum(c: Int): Int =
        (((c shr 16) and 0xFF) * 3 + ((c shr 8) and 0xFF) * 6 + (c and 0xFF)) / 10

    private fun isColored(c: Int): Boolean {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        return max - min > 42 && max > 60
    }
}
