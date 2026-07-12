package com.tarteelcompanion.quran

/**
 * Read-only access to the bundled Quran dataset: word-indexed text, mushaf page layout,
 * and mutashabihat groups. Built once at startup via [load]; all queries are in-memory.
 *
 * Verse text shown anywhere in the app must come from here — never from extraction
 * output or an LLM (plan R4, "unchanged invariants").
 */
class QuranRepository private constructor(
    val pages: List<MushafPage>,
    private val pageByAyah: Map<AyahRef, Int>,
    private val wordsByAyah: Map<AyahRef, List<QuranWord>>,
    private val ayahOrder: List<AyahRef>,
    private val ayahOrdinal: Map<AyahRef, Int>,
    private val mutashabihatByAyah: Map<AyahRef, List<MutashabihatGroup>>,
    val mutashabihatGroups: List<MutashabihatGroup>,
    private val surahNames: Map<Int, String>,
) {

    /** Surah display name as rendered on the mushaf header line (e.g. "سُورَةُ ٱلْفَاتِحَةِ"). */
    fun surahName(surah: Int): String? = surahNames[surah]

    val ayahCount: Int get() = ayahOrder.size
    val wordCount: Int by lazy { wordsByAyah.values.sumOf { it.size } }

    fun page(pageNumber: Int): MushafPage? = pages.getOrNull(pageNumber - 1)

    fun pageNumberOf(ayah: AyahRef): Int? = pageByAyah[ayah]

    /** Words of an ayah in recitation order. Empty if the ref is invalid. */
    fun wordsOf(ayah: AyahRef): List<QuranWord> = wordsByAyah[ayah].orEmpty()

    /** The word at a canonical position, or null if out of range. */
    fun wordAt(ref: WordRef): QuranWord? =
        wordsByAyah[ref.ayahRef]?.getOrNull(ref.word - 1)

    /** Full ayah text joined from its words (includes the trailing ayah-number glyph). */
    fun ayahText(ayah: AyahRef): String? =
        wordsByAyah[ayah]?.joinToString(" ") { it.text }?.ifEmpty { null }

    /**
     * The ayah preceding [ayah] within the same surah, or null at a surah start
     * (card fronts show the surah name only there — plan R18).
     */
    fun precedingAyahInSurah(ayah: AyahRef): AyahRef? {
        if (ayah.ayah <= 1) return null
        val prev = AyahRef(ayah.surah, ayah.ayah - 1)
        return if (wordsByAyah.containsKey(prev)) prev else null
    }

    /** Absolute ayah number (1..6236) → canonical ref; null if out of range. */
    fun ayahRefOf(absoluteAyah: Int): AyahRef? = ayahOrder.getOrNull(absoluteAyah - 1)

    /** Mutashabihat groups this ayah belongs to (as source or as a similar member). */
    fun mutashabihatFor(ayah: AyahRef): List<MutashabihatGroup> =
        mutashabihatByAyah[ayah].orEmpty()

    companion object {

        /**
         * Parses all 604 pages plus the mutashabihat file. Call off the main thread —
         * takes on the order of a second or two on device.
         */
        fun load(reader: QuranAssetReader): QuranRepository {
            val pages = (1..QuranDataParser.PAGE_COUNT).map { n ->
                val name = "pages/page-%03d.json".format(n)
                reader.open(name).use(QuranDataParser::parsePage)
            }

            val pageByAyah = mutableMapOf<AyahRef, Int>()
            val wordsByAyah = linkedMapOf<AyahRef, MutableList<QuranWord>>()
            val surahNames = mutableMapOf<Int, String>()
            for (page in pages) {
                for (line in page.lines) {
                    if (line.type == LineType.SURAH_HEADER && line.surahNumber != null) {
                        surahNames.putIfAbsent(line.surahNumber, line.headerText.orEmpty())
                    }
                    for (word in line.words) {
                        val ayah = word.ref.ayahRef
                        pageByAyah.putIfAbsent(ayah, page.pageNumber)
                        wordsByAyah.getOrPut(ayah) { mutableListOf() }.add(word)
                    }
                }
            }
            // Mushaf order is recitation order; LinkedHashMap preserved insertion order.
            val ayahOrder = wordsByAyah.keys.toList()
            val ayahOrdinal = ayahOrder.withIndex().associate { (i, ref) -> ref to i }

            val groups = if (reader.exists("mutashabiha_data.json")) {
                reader.open("mutashabiha_data.json").use { stream ->
                    QuranDataParser.parseMutashabihat(stream) { abs -> ayahOrder.getOrNull(abs - 1) }
                }
            } else {
                emptyList()
            }
            val byAyah = mutableMapOf<AyahRef, MutableList<MutashabihatGroup>>()
            for (group in groups) {
                for (member in group.members) {
                    byAyah.getOrPut(member) { mutableListOf() }.add(group)
                }
            }

            return QuranRepository(
                pages = pages,
                pageByAyah = pageByAyah,
                wordsByAyah = wordsByAyah.mapValues { it.value.toList() },
                ayahOrder = ayahOrder,
                ayahOrdinal = ayahOrdinal,
                mutashabihatByAyah = byAyah,
                mutashabihatGroups = groups,
                surahNames = surahNames,
            )
        }
    }
}
