package com.tarteelcompanion.study

import com.tarteelcompanion.data.model.MistakeType
import com.tarteelcompanion.quran.AyahRef
import com.tarteelcompanion.quran.QuranAssetReader
import com.tarteelcompanion.quran.QuranRepository
import com.tarteelcompanion.quran.WordRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.InputStream

/** Runs against the real fetched dataset; skips when assets are absent (like QuranRepositoryTest). */
class CardBuilderTest {

    companion object {
        private var quran: QuranRepository? = null

        @JvmStatic
        @BeforeClass
        fun loadOnce() {
            var dir: File? = File(System.getProperty("user.dir"))
            while (dir != null) {
                val candidate = File(dir, "app/src/main/assets/quran")
                    .takeIf { File(it, "pages/page-001.json").exists() }
                    ?: File(dir, "src/main/assets/quran").takeIf { File(it, "pages/page-001.json").exists() }
                if (candidate != null) {
                    quran = QuranRepository.load(object : QuranAssetReader {
                        override fun open(path: String): InputStream = File(candidate, path).inputStream()
                        override fun exists(path: String): Boolean = File(candidate, path).exists()
                    })
                    return
                }
                dir = dir.parentFile
            }
        }
    }

    private fun builder(): CardBuilder {
        assumeTrue("dataset not fetched", quran != null)
        return CardBuilder(quran!!)
    }

    @Test
    fun `three spots in one ayah produce one card`() {
        val cards = builder().build(
            listOf(
                CardSpot(WordRef(2, 255, 3), MistakeType.WRONG_WORD),
                CardSpot(WordRef(2, 255, 10), MistakeType.PRONUNCIATION),
                CardSpot(WordRef(2, 255, 20), MistakeType.WRONG_WORD),
            ),
        )
        assertEquals(1, cards.size)
        assertEquals(3, cards.single().spots.size)
        assertEquals(AyahRef(2, 255), cards.single().primaryAyah)
    }

    @Test
    fun `card front carries surah name and preceding-ayah lead-in never target text`() {
        val card = builder().build(listOf(CardSpot(WordRef(2, 2, 3), MistakeType.WRONG_WORD))).single()

        assertTrue(card.surahName.isNotBlank())
        val leadInRefs = card.leadIn!!.map { it.ref.ayahRef }.toSet()
        assertEquals(setOf(AyahRef(2, 1)), leadInRefs) // lead-in from 2:1, not the target 2:2
        assertTrue(card.leadIn!!.size <= CardBuilder.LEAD_IN_WORDS)
    }

    @Test
    fun `surah start card has no lead-in`() {
        val card = builder().build(listOf(CardSpot(WordRef(2, 1, 1), MistakeType.PRONUNCIATION))).single()
        assertNull(card.leadIn)
        assertTrue(card.surahName.isNotBlank())
    }

    @Test
    fun `brown spot gets the full preceding ayah as lead-in`() {
        // 2:5 follows the long 2:4; a PROMPT_NEEDED spot needs the whole continuation context (I7).
        val card = builder().build(listOf(CardSpot(WordRef(2, 5, 1), MistakeType.PROMPT_NEEDED))).single()
        val precedingSize = quran!!.wordsOf(AyahRef(2, 4)).size
        assertEquals(precedingSize, card.leadIn!!.size)
    }

    @Test
    fun `adjacent due ayat merge into one passage under the cap`() {
        val cards = builder().build(
            listOf(
                CardSpot(WordRef(1, 2, 1), MistakeType.WRONG_WORD),
                CardSpot(WordRef(1, 3, 2), MistakeType.PRONUNCIATION),
            ),
        )
        assertEquals(1, cards.size)
        assertEquals(listOf(AyahRef(1, 2), AyahRef(1, 3)), cards.single().ayat)
        assertEquals(2, cards.single().passage.size)
    }

    @Test
    fun `long adjacent ayat do not merge past the word cap`() {
        // 2:282 is the longest ayah in the Quran — merging it with a neighbor must not happen.
        val cards = builder().build(
            listOf(
                CardSpot(WordRef(2, 282, 5), MistakeType.WRONG_WORD),
                CardSpot(WordRef(2, 283, 2), MistakeType.WRONG_WORD),
            ),
        )
        assertEquals(2, cards.size)
    }

    @Test
    fun `non-adjacent spots stay on separate cards`() {
        val cards = builder().build(
            listOf(
                CardSpot(WordRef(2, 10, 1), MistakeType.WRONG_WORD),
                CardSpot(WordRef(3, 10, 1), MistakeType.WRONG_WORD),
            ),
        )
        assertEquals(2, cards.size)
    }

    @Test
    fun `mutashabihat groups attach to cards whose ayah is in a group`() {
        val card = builder().build(listOf(CardSpot(WordRef(2, 2, 3), MistakeType.WRONG_WORD))).single()
        assertTrue(card.mutashabihat.isNotEmpty()) // 2:2 is a known dataset source
    }
}
