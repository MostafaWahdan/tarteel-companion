package com.tarteelcompanion.quran

import android.content.Context
import java.io.IOException
import java.io.InputStream

/** Reads the bundled dataset from `assets/quran/` on device. */
class AndroidQuranAssetReader(private val context: Context) : QuranAssetReader {

    override fun open(path: String): InputStream = context.assets.open("$ASSET_ROOT/$path")

    override fun exists(path: String): Boolean = try {
        context.assets.open("$ASSET_ROOT/$path").use { true }
    } catch (_: IOException) {
        false
    }

    private companion object {
        const val ASSET_ROOT = "quran"
    }
}
