package com.tarteelcompanion.quran

import kotlinx.serialization.Serializable

/** A canonical ayah reference. */
data class AyahRef(val surah: Int, val ayah: Int) : Comparable<AyahRef> {
    override fun compareTo(other: AyahRef): Int =
        compareValuesBy(this, other, { it.surah }, { it.ayah })

    override fun toString(): String = "$surah:$ayah"
}

/** A canonical word position — the anchor unit for mistake spots (plan R4). */
data class WordRef(val surah: Int, val ayah: Int, val word: Int) {
    val ayahRef: AyahRef get() = AyahRef(surah, ayah)

    override fun toString(): String = "$surah:$ayah:$word"

    companion object {
        /** Parses "2:255:3" form used by the dataset's `location` field. */
        fun parse(location: String): WordRef {
            val (s, a, w) = location.split(":").map(String::toInt)
            return WordRef(s, a, w)
        }
    }
}

/** One word on a mushaf page, verse text always verbatim from the bundled dataset. */
data class QuranWord(val ref: WordRef, val text: String)

enum class LineType { SURAH_HEADER, BASMALA, TEXT }

/** One line on a mushaf page. Word list is empty for header/basmala lines. */
data class MushafLine(
    val lineNumber: Int,
    val type: LineType,
    val words: List<QuranWord>,
    /** Surah number for SURAH_HEADER lines; null otherwise. */
    val surahNumber: Int?,
    /** Rendered surah name for SURAH_HEADER lines; null otherwise. */
    val headerText: String? = null,
)

/** One page of the 604-page Madani mushaf. */
data class MushafPage(val pageNumber: Int, val lines: List<MushafLine>)

/**
 * A similar-verses group: a source ayah and the ayat it is commonly confused with.
 * Derived from the huffaz-focused mutashabihat dataset; deterministic, never LLM-inferred (R11).
 */
data class MutashabihatGroup(
    val source: AyahRef,
    val similar: List<AyahRef>,
) {
    /** All members of the group including the source. */
    val members: List<AyahRef> get() = listOf(source) + similar
}

// --- Raw JSON shapes of the bundled dataset (internal to the parser) ---

@Serializable
internal data class RawPage(val page: Int, val lines: List<RawLine>)

@Serializable
internal data class RawLine(
    val line: Int,
    val type: String,
    val text: String? = null,
    val surah: String? = null,
    val verseRange: String? = null,
    val words: List<RawWord>? = null,
)

@Serializable
internal data class RawWord(val location: String, val word: String)
