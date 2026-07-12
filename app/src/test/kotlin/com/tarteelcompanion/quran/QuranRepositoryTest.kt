package com.tarteelcompanion.quran

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.InputStream

/**
 * Runs against the real fetched dataset (scripts/fetch-datasets.sh). Skips gracefully
 * when the dataset is absent (e.g., fresh clone / CI without assets — see plan's
 * Documentation notes).
 */
class QuranRepositoryTest {

    private class FileReader(private val root: File) : QuranAssetReader {
        override fun open(path: String): InputStream = File(root, path).inputStream()
        override fun exists(path: String): Boolean = File(root, path).exists()
    }

    companion object {
        private var repository: QuranRepository? = null

        @JvmStatic
        @BeforeClass
        fun loadOnce() {
            val root = findAssetRoot() ?: return
            repository = QuranRepository.load(FileReader(root))
        }

        /** Walks up from the working dir so the test works from repo root or app/. */
        private fun findAssetRoot(): File? {
            var dir: File? = File(System.getProperty("user.dir"))
            while (dir != null) {
                val candidate = File(dir, "app/src/main/assets/quran")
                if (File(candidate, "pages/page-001.json").exists()) return candidate
                val local = File(dir, "src/main/assets/quran")
                if (File(local, "pages/page-001.json").exists()) return local
                dir = dir.parentFile
            }
            return null
        }
    }

    private fun repo(): QuranRepository {
        assumeTrue("Quran dataset not fetched — run scripts/fetch-datasets.sh", repository != null)
        return repository!!
    }

    @Test
    fun `corpus totals match the standard mushaf`() {
        assertEquals(604, repo().pages.size)
        assertEquals(6236, repo().ayahCount)
        // Markers are merged into last words in this dataset, so ~77.4k word entries.
        assertTrue("unexpected word count ${repo().wordCount}", repo().wordCount in 77_000..78_000)
    }

    @Test
    fun `fatiha basmala has four words`() {
        assertEquals(4, repo().wordsOf(AyahRef(1, 1)).size)
    }

    @Test
    fun `word lookup returns verbatim dataset text`() {
        val word = repo().wordAt(WordRef(1, 1, 1))
        assertNotNull(word)
        assertTrue(word!!.text.startsWith("بِسْمِ"))
    }

    @Test
    fun `page one has a surah header and all of al-fatiha`() {
        val page = repo().page(1)!!
        assertTrue(page.lines.any { it.type == LineType.SURAH_HEADER })
        val ayat = page.lines.flatMap { it.words }.map { it.ref.ayahRef }.toSet()
        assertEquals((1..7).map { AyahRef(1, it) }.toSet(), ayat)
    }

    @Test
    fun `every ayah last word carries its ayah-number marker`() {
        // Corpus invariant (plan U2): this dataset appends the Arabic-Indic ayah numeral
        // to the final word, so no standalone marker entries exist in the word ID space.
        val arabicDigits = '٠'..'٩'
        var checked = 0
        for (surah in intArrayOf(1, 2, 18, 55, 114)) {
            var ayah = 1
            while (true) {
                val words = repo().wordsOf(AyahRef(surah, ayah))
                if (words.isEmpty()) break
                assertTrue(
                    "last word of $surah:$ayah lacks a marker digit",
                    words.last().text.any { it in arabicDigits },
                )
                checked++
                ayah++
            }
        }
        assertTrue(checked > 300)
    }

    @Test
    fun `preceding ayah is null at surah start and previous within surah`() {
        assertNull(repo().precedingAyahInSurah(AyahRef(2, 1)))
        assertEquals(AyahRef(2, 1), repo().precedingAyahInSurah(AyahRef(2, 2)))
    }

    @Test
    fun `absolute ayah numbers map to canonical refs`() {
        assertEquals(AyahRef(1, 1), repo().ayahRefOf(1))
        assertEquals(AyahRef(2, 2), repo().ayahRefOf(9)) // 7 (al-Fatiha) + 2
        assertEquals(AyahRef(114, 6), repo().ayahRefOf(6236))
        assertNull(repo().ayahRefOf(6237))
    }

    @Test
    fun `mutashabihat groups load and are ayah-queryable`() {
        assertTrue("expected >1000 groups, got ${repo().mutashabihatGroups.size}", repo().mutashabihatGroups.size > 1000)
        // 2:2 (absolute 9) is a known source entry in the dataset's first juz bucket.
        val groups = repo().mutashabihatFor(AyahRef(2, 2))
        assertTrue(groups.isNotEmpty())
        assertTrue(groups.all { it.members.size >= 2 })
    }

    @Test
    fun `mutashabihat lookup for invalid ayah is empty not an error`() {
        assertTrue(repo().mutashabihatFor(AyahRef(1, 99)).isEmpty())
    }

    @Test
    fun `every page word range resolves and group sizes stay card-friendly`() {
        // Whole-corpus invariant: every word ref on every page resolves back through
        // the repository, and mutashabihat groups stay small enough to render (U9).
        for (page in repo().pages) {
            for (line in page.lines) {
                for (word in line.words) {
                    assertNotNull("unresolvable ${word.ref}", repo().wordAt(word.ref))
                }
            }
        }
        val oversized = repo().mutashabihatGroups.count { it.members.size > 12 }
        assertTrue(
            "$oversized groups exceed 12 members — U9 needs a cap rule",
            oversized < repo().mutashabihatGroups.size / 20,
        )
    }
}
