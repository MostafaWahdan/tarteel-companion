package com.tarteelcompanion.extraction

import android.graphics.BitmapFactory
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Throwaway diagnostic: dumps segmentation structure for one sample per page. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SegmentationDiagnosticTest {

    @Test
    fun `dump alignment costs for known pages`() {
        var dir: File? = File(System.getProperty("user.dir"))
        var samples: File? = null
        var assets: File? = null
        while (dir != null) {
            if (samples == null && File(dir, "samples").isDirectory) samples = File(dir, "samples")
            val a = File(dir, "app/src/main/assets/quran")
            if (assets == null && File(a, "pages/page-001.json").exists()) assets = a
            dir = dir.parentFile
        }
        assumeTrue(samples != null && assets != null)
        val quran = com.tarteelcompanion.quran.QuranRepository.load(object : com.tarteelcompanion.quran.QuranAssetReader {
            override fun open(path: String) = File(assets, path).inputStream()
            override fun exists(path: String) = File(assets, path).exists()
        })
        val matcher = PageMatcher(quran)

        val report = StringBuilder()
        val picks = samples!!.listFiles()!!.filter { it.extension == "jpg" }.sortedBy { it.name }
            .let { listOf(it.first(), it.first { f -> f.name.contains("-15-54-") }) }
        for (file in picks) {
            val truePage = if (file.name.contains("-15-52-")) 163 else 60
            val bmp = BitmapFactory.decodeFile(file.absolutePath)!!
            val pixels = IntArray(bmp.width * bmp.height)
            bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            val runs = Segmentation.segment(PixelGrid(pixels, bmp.width, bmp.height))
            val windowRanking = (maxOf(1, truePage - 30)..minOf(604, truePage + 30)).mapNotNull { p ->
                matcher.debugCombinedCost(runs, p)?.let { p to it }
            }.sortedBy { it.second }.take(5)
            report.append(
                "${file.name}: runs=${runs.size} truePage=$truePage " +
                    "window±30 top=${windowRanking.map { "${it.first}:${"%.4f".format(it.second)}" }}\n",
            )
        }
        File(samples.parentFile, "app/build/align-diagnostic.txt").writeText(report.toString())
    }

    @Test
    fun `dump segmentation structure`() {
        var dir: File? = File(System.getProperty("user.dir"))
        var samples: File? = null
        while (dir != null) {
            val c = File(dir, "samples")
            if (c.isDirectory) { samples = c; break }
            dir = dir.parentFile
        }
        assumeTrue(samples != null)

        val report = StringBuilder()
        val picks = samples!!.listFiles()!!.filter { it.extension == "jpg" }.sortedBy { it.name }
            .let { listOf(it.first(), it.first { f -> f.name.contains("-15-54-") }) }

        for (file in picks) {
            val bmp = BitmapFactory.decodeFile(file.absolutePath)!!
            val pixels = IntArray(bmp.width * bmp.height)
            bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            val grid = PixelGrid(pixels, bmp.width, bmp.height)

            val lines = Segmentation.findLines(grid)
            report.append("${file.name}: lines=${lines.size}\n")
            var total = 0
            for ((i, line) in lines.withIndex()) {
                val words = Segmentation.findWords(grid, line, i)
                total += words.size
                report.append(
                    "  line$i y=${line.top}-${line.bottom} words=${words.size} " +
                        "widths=${words.map { it.width }}\n",
                )
            }
            report.append("  TOTAL clusters=$total\n")

            // Row-level dump for the worst line (widest single cluster).
            val worst = lines.withIndex()
                .maxBy { (i, l) -> Segmentation.findWords(grid, l, i).maxOfOrNull { w -> w.width } ?: 0 }
            report.append("  worst line ${worst.index} y=${worst.value.top}-${worst.value.bottom} colInk dump:\n")
            val depths = Segmentation.columnProfileForDiagnostics(grid, worst.value)
            // Compress into runs of zero/nonzero with min..max depth.
            var x = 0
            val parts = StringBuilder()
            while (x < depths.size) {
                val zero = depths[x] == 0
                var end = x
                var lo = depths[x]; var hi = depths[x]
                while (end + 1 < depths.size && (depths[end + 1] == 0) == zero) {
                    end++
                    if (depths[end] < lo) lo = depths[end]
                    if (depths[end] > hi) hi = depths[end]
                }
                parts.append(if (zero) "[gap ${end - x + 1}]" else "(ink ${end - x + 1} d=$lo..$hi)")
                x = end + 1
            }
            report.append("    ").append(parts).append('\n')
        }
        File(samples.parentFile, "app/build/seg-diagnostic.txt").writeText(report.toString())
    }
}
