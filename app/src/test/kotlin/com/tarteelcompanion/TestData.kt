package com.tarteelcompanion

import com.tarteelcompanion.quran.QuranAssetReader
import com.tarteelcompanion.quran.QuranRepository
import java.io.File
import java.io.InputStream

/**
 * Shared test fixtures: locates the gitignored dataset/samples directories by walking
 * up from the working dir, and caches one loaded QuranRepository for the whole JVM
 * (consolidates six previously duplicated bootstrap blocks — review finding MAINT-05).
 */
object TestData {

    val assetRoot: File? by lazy {
        walkUp { dir ->
            listOf(File(dir, "app/src/main/assets/quran"), File(dir, "src/main/assets/quran"))
                .firstOrNull { File(it, "pages/page-001.json").exists() }
        }
    }

    val samplesDir: File? by lazy {
        walkUp { dir ->
            File(dir, "samples").takeIf { candidate ->
                candidate.isDirectory && candidate.listFiles()
                    ?.any { it.extension.lowercase() in setOf("jpg", "jpeg", "png") } == true
            }
        }
    }

    /** Loaded once per JVM; null when the dataset has not been fetched. */
    val quran: QuranRepository? by lazy {
        assetRoot?.let { root ->
            QuranRepository.load(object : QuranAssetReader {
                override fun open(path: String): InputStream = File(root, path).inputStream()
                override fun exists(path: String): Boolean = File(root, path).exists()
            })
        }
    }

    fun sampleFiles(): List<File> = samplesDir?.listFiles()
        ?.filter { it.extension.lowercase() in setOf("jpg", "jpeg", "png") }
        ?.sortedBy { it.name }
        .orEmpty()

    private fun <T> walkUp(probe: (File) -> T?): T? {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            probe(dir)?.let { return it }
            dir = dir.parentFile
        }
        return null
    }

    /**
     * Polls until [supplier] returns non-null. ViewModels hop from the test Main
     * dispatcher to real IO/Room executors, so test-scheduler advancing alone cannot
     * await their state transitions.
     */
    fun <T> awaitNonNull(timeoutMs: Long = 10_000, supplier: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            supplier()?.let { return it }
            Thread.sleep(20)
        }
        throw AssertionError("condition not met within ${timeoutMs}ms")
    }
}
