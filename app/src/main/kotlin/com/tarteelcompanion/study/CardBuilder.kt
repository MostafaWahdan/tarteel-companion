package com.tarteelcompanion.study

import com.tarteelcompanion.data.model.MistakeType
import com.tarteelcompanion.quran.AyahRef
import com.tarteelcompanion.quran.MutashabihatGroup
import com.tarteelcompanion.quran.QuranRepository
import com.tarteelcompanion.quran.QuranWord
import com.tarteelcompanion.quran.WordRef

/** One due spot rendered on a card: position plus its effective mistake type (R14). */
data class CardSpot(val ref: WordRef, val type: MistakeType)

/**
 * A full-passage recall card (R8/R18/R19). The front shows [surahName], the first
 * ayah number, and [leadIn]; the passage text appears only on reveal.
 */
data class StudyCard(
    val ayat: List<AyahRef>,
    val surahName: String,
    /** Lead-in cue: tail of the ayah preceding the passage; null at a surah start. */
    val leadIn: List<QuranWord>?,
    /** Reveal-side passage, in recitation order, grouped by ayah. */
    val passage: List<Pair<AyahRef, List<QuranWord>>>,
    val spots: List<CardSpot>,
    /** Similar-verse groups touching this passage (reveal-side aids — R11). */
    val mutashabihat: List<MutashabihatGroup>,
) {
    val primaryAyah: AyahRef get() = ayat.first()
}

/**
 * Builds cards from due spots: one card per ayah, adjacent due ayat merged while the
 * passage stays under the word cap, per-spot scheduling preserved by the caller's
 * grade fan-out (plan U8; flow-analysis I7/I8 passage rules).
 */
class CardBuilder(private val quran: QuranRepository) {

    companion object {
        /** Passage size cap in words for merged cards (I8; tunable at implementation). */
        const val MAX_PASSAGE_WORDS = 50

        /** Default lead-in length: last N words of the preceding ayah (R18). */
        const val LEAD_IN_WORDS = 5
    }

    fun build(dueSpots: List<CardSpot>): List<StudyCard> {
        if (dueSpots.isEmpty()) return emptyList()

        // One bucket per ayah (R19), in mushaf order.
        val byAyah = dueSpots.groupBy { it.ref.ayahRef }.toSortedMap()

        // Merge runs of consecutive due ayat within the same surah under the word cap (I8).
        val runs = mutableListOf<MutableList<AyahRef>>()
        for (ayah in byAyah.keys) {
            val current = runs.lastOrNull()
            val last = current?.last()
            val wordsIfMerged = current?.sumOf { quran.wordsOf(it).size }?.plus(quran.wordsOf(ayah).size)
            if (last != null && last.surah == ayah.surah && ayah.ayah == last.ayah + 1 &&
                wordsIfMerged != null && wordsIfMerged <= MAX_PASSAGE_WORDS
            ) {
                current.add(ayah)
            } else {
                runs.add(mutableListOf(ayah))
            }
        }

        return runs.map { ayat ->
            val spots = ayat.flatMap { byAyah.getValue(it) }
            StudyCard(
                ayat = ayat,
                surahName = quran.surahName(ayat.first().surah).orEmpty(),
                leadIn = leadInFor(ayat.first(), spots),
                passage = ayat.map { it to quran.wordsOf(it) },
                spots = spots,
                mutashabihat = ayat.flatMap { quran.mutashabihatFor(it) }.distinct(),
            )
        }
    }

    /**
     * Lead-in = tail of the preceding ayah; null at a surah start (R18). Brown spots
     * (needed prompting) get the full preceding ayah — the continuation context is the
     * whole point of that card (I7).
     */
    private fun leadInFor(first: AyahRef, spots: List<CardSpot>): List<QuranWord>? {
        val preceding = quran.precedingAyahInSurah(first) ?: return null
        val words = quran.wordsOf(preceding)
        val needsFullContext = spots.any { it.type == MistakeType.PROMPT_NEEDED }
        return if (needsFullContext) words else words.takeLast(LEAD_IN_WORDS)
    }
}
