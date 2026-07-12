package com.tarteelcompanion.extraction

import com.tarteelcompanion.data.model.MistakeType

/**
 * Colored mistake-mark detection, calibrated by the U3 spike against real screenshots
 * (docs/solutions/extraction-spike-findings.md). Mistake words are colored GLYPHS on
 * the session view (R21); olive history-glow blobs are excluded by hue + fill ratio.
 */
object ColorClassifier {

    data class ColoredCluster(
        val minX: Int, val maxX: Int, val minY: Int, val maxY: Int,
        val pixelCount: Int, val avgR: Int, val avgG: Int, val avgB: Int,
    ) {
        val width: Int get() = maxX - minX + 1
        val height: Int get() = maxY - minY + 1
        val fill: Double get() = pixelCount.toDouble() / (width.toDouble() * height)
        val centerX: Int get() = (minX + maxX) / 2
        val centerY: Int get() = (minY + maxY) / 2
    }

    /** Spike-measured pixel gate: saturated enough on either observed theme. */
    fun isColored(argb: Int): Boolean {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val max = maxOf(r, g, b)
        return max - minOf(r, g, b) > 42 && max > 60
    }

    /**
     * Spike-measured bucketing on the (R−G, G−B) plane:
     * red text ≈ (150–170, 85–100, 85–105); yellow ≈ (170–200, 145–165, 70–80);
     * brown ≈ (120–135, 77–83, 63–74); olive glow is green-dominant → null.
     */
    fun classify(cluster: ColoredCluster): MistakeType? {
        val r = cluster.avgR
        val g = cluster.avgG
        val b = cluster.avgB
        return when {
            g >= r -> null // olive/green history glow
            g - b >= 45 && r > 150 -> MistakeType.PRONUNCIATION // yellow-orange
            r >= 140 && r - g >= 50 -> MistakeType.WRONG_WORD // brick red/pink
            r < 140 && r - g in 30..55 -> MistakeType.PROMPT_NEEDED // darker brown
            r - g >= 40 -> MistakeType.WRONG_WORD // red-ish fallback
            else -> null
        }
    }

    /**
     * Finds colored clusters within [top, bottom). Small satellite clusters (dotted
     * underlines, colored diacritics) survive; they merge into words by overlap later.
     */
    fun findClusters(grid: PixelGrid, top: Int, bottom: Int, minPixels: Int = 40): List<ColoredCluster> {
        val w = grid.width
        val visited = BooleanArray(grid.pixels.size)
        val clusters = ArrayList<ColoredCluster>()
        val stack = ArrayDeque<Int>()

        for (y in top until bottom) {
            for (x in 0 until w) {
                val start = y * w + x
                if (visited[start] || !isColored(grid.pixels[start])) continue
                var minX = x; var maxX = x; var minY = y; var maxY = y
                var count = 0; var sr = 0L; var sg = 0L; var sb = 0L
                visited[start] = true
                stack.addLast(start)
                while (stack.isNotEmpty()) {
                    val p = stack.removeLast()
                    val px = p % w
                    val py = p / w
                    val c = grid.pixels[p]
                    count++
                    sr += (c shr 16) and 0xFF
                    sg += (c shr 8) and 0xFF
                    sb += c and 0xFF
                    if (px < minX) minX = px
                    if (px > maxX) maxX = px
                    if (py < minY) minY = py
                    if (py > maxY) maxY = py
                    if (px + 1 < w) visit(p + 1, grid, visited, stack)
                    if (px > 0) visit(p - 1, grid, visited, stack)
                    if (py + 1 < bottom) visit(p + w, grid, visited, stack)
                    if (py > top) visit(p - w, grid, visited, stack)
                }
                if (count >= minPixels) {
                    clusters.add(
                        ColoredCluster(
                            minX, maxX, minY, maxY, count,
                            (sr / count).toInt(), (sg / count).toInt(), (sb / count).toInt(),
                        ),
                    )
                }
            }
        }
        return clusters
    }

    private fun visit(i: Int, grid: PixelGrid, visited: BooleanArray, stack: ArrayDeque<Int>) {
        if (!visited[i] && isColored(grid.pixels[i])) {
            visited[i] = true
            stack.addLast(i)
        }
    }
}
