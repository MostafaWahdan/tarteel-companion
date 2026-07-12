package com.tarteelcompanion.quran

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream

/**
 * Abstraction over where the bundled Quran dataset lives: Android AssetManager in the
 * app, plain files in JVM tests. Paths are relative to the `quran/` asset root.
 */
interface QuranAssetReader {
    fun open(path: String): InputStream
    fun exists(path: String): Boolean
}

/** Parses the bundled dataset (604 page JSONs + mutashabihat JSON) into domain models. */
object QuranDataParser {

    private val json = Json { ignoreUnknownKeys = true }

    const val PAGE_COUNT = 604

    fun parsePage(stream: InputStream): MushafPage {
        val raw = json.decodeFromString<RawPage>(stream.readBytes().decodeToString())
        val lines = raw.lines.map { line ->
            val type = when (line.type) {
                "surah-header" -> LineType.SURAH_HEADER
                "basmala" -> LineType.BASMALA
                else -> LineType.TEXT
            }
            MushafLine(
                lineNumber = line.line,
                type = type,
                words = line.words.orEmpty().map { QuranWord(WordRef.parse(it.location), it.word) },
                surahNumber = line.surah?.toIntOrNull(),
            )
        }
        return MushafPage(raw.page, lines)
    }

    /**
     * Parses the mutashabihat dataset. The file is keyed by juz ("1".."30"); each entry
     * carries absolute ayah numbers (1..6236), converted here via [toAyahRef].
     * Entries whose absolute numbers fall outside the corpus are skipped defensively.
     */
    fun parseMutashabihat(stream: InputStream, toAyahRef: (Int) -> AyahRef?): List<MutashabihatGroup> {
        val root = json.parseToJsonElement(stream.readBytes().decodeToString()).jsonObject
        val groups = mutableListOf<MutashabihatGroup>()
        for ((_, entries) in root) {
            for (entry in entries.jsonArray) {
                val obj = entry.jsonObject
                val sources = obj["src"]?.absoluteAyahs() ?: continue
                val similars = obj["muts"]?.absoluteAyahs() ?: continue
                val similarRefs = similars.mapNotNull(toAyahRef)
                for (src in sources) {
                    val srcRef = toAyahRef(src) ?: continue
                    if (similarRefs.isNotEmpty()) {
                        groups += MutashabihatGroup(srcRef, similarRefs)
                    }
                }
            }
        }
        return groups
    }

    /**
     * `src`/`muts` hold one object or an array of objects; each object's `ayah` is an
     * absolute ayah int or an array of ints (multi-ayah passages).
     */
    private fun kotlinx.serialization.json.JsonElement.absoluteAyahs(): List<Int> = when (this) {
        is JsonArray -> flatMap { (it as? JsonObject)?.ayahInts().orEmpty() }
        is JsonObject -> ayahInts()
        else -> emptyList()
    }

    private fun JsonObject.ayahInts(): List<Int> = when (val ayah = get("ayah")) {
        is JsonArray -> ayah.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.int }
        is kotlinx.serialization.json.JsonPrimitive -> listOf(ayah.int)
        else -> emptyList()
    }
}
