package com.tarteelcompanion.extraction

/**
 * Plain pixel-array input for the extraction pipeline — zero android.graphics
 * references so the golden suites run on plain JVM (plan key decision).
 */
class PixelGrid(val pixels: IntArray, val width: Int, val height: Int) {

    init {
        require(pixels.size == width * height) { "pixel buffer does not match dimensions" }
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x]

    companion object {
        fun luminance(argb: Int): Int =
            (((argb shr 16) and 0xFF) * 3 + ((argb shr 8) and 0xFF) * 6 + (argb and 0xFF)) / 10

        fun chroma(argb: Int): Int {
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            return maxOf(r, g, b) - minOf(r, g, b)
        }
    }
}
