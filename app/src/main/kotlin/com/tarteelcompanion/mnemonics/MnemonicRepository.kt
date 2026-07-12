package com.tarteelcompanion.mnemonics

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.tarteelcompanion.quran.AyahRef
import com.tarteelcompanion.quran.MutashabihatGroup
import com.tarteelcompanion.quran.QuranRepository

enum class MnemonicStatus { PENDING, READY, FAILED }

enum class MnemonicSource { LLM, USER }

/**
 * A mnemonic anchoring one target ayah's wording within a mutashabihat group (R12).
 * Keyed by (group source ayah, target ayah). User-authored text always wins and is
 * never overwritten by generation.
 */
@Entity(
    tableName = "mnemonics",
    indices = [Index(value = ["groupSurah", "groupAyah", "targetSurah", "targetAyah"], unique = true)],
)
data class MnemonicEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupSurah: Int,
    val groupAyah: Int,
    val targetSurah: Int,
    val targetAyah: Int,
    val text: String? = null,
    val status: MnemonicStatus = MnemonicStatus.PENDING,
    val source: MnemonicSource = MnemonicSource.LLM,
    val failureReason: String? = null,
)

@Dao
interface MnemonicDao {
    @Query(
        "SELECT * FROM mnemonics WHERE groupSurah = :gs AND groupAyah = :ga " +
            "AND targetSurah = :ts AND targetAyah = :ta",
    )
    suspend fun find(gs: Int, ga: Int, ts: Int, ta: Int): MnemonicEntity?

    @Query("SELECT * FROM mnemonics WHERE status = 'PENDING'")
    suspend fun pending(): List<MnemonicEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(m: MnemonicEntity): Long

    @Query(
        "UPDATE mnemonics SET text = :text, status = :status, source = :source, " +
            "failureReason = :reason WHERE id = :id",
    )
    suspend fun updateContent(id: Long, text: String?, status: MnemonicStatus, source: MnemonicSource, reason: String?)

    @Query("UPDATE mnemonics SET status = 'PENDING', failureReason = NULL WHERE id = :id AND source = 'LLM'")
    suspend fun requeue(id: Long)
}

/**
 * Mnemonic lifecycle (plan U10): ensure a PENDING row when a card first needs one;
 * the worker fills LLM rows; user edits persist as USER and cancel generation (R12);
 * generation failures surface a reason and can be manually re-queued.
 */
class MnemonicRepository(
    private val dao: MnemonicDao,
    private val keyStore: ApiKeyProvider,
    private val client: LlmClient,
) {

    suspend fun mnemonicFor(group: MutashabihatGroup, target: AyahRef): MnemonicEntity {
        dao.insertIgnore(
            MnemonicEntity(
                groupSurah = group.source.surah,
                groupAyah = group.source.ayah,
                targetSurah = target.surah,
                targetAyah = target.ayah,
            ),
        )
        return dao.find(group.source.surah, group.source.ayah, target.surah, target.ayah)!!
    }

    /** User text always wins: saved as USER and never overwritten by later generation. */
    suspend fun saveUserText(entity: MnemonicEntity, text: String) {
        dao.updateContent(entity.id, text.trim(), MnemonicStatus.READY, MnemonicSource.USER, null)
    }

    suspend fun retryFailed(entity: MnemonicEntity) = dao.requeue(entity.id)

    /**
     * Processes all pending rows. Returns true when everything pending was handled
     * (generated or terminally failed); false when work remains (no key / retryable),
     * letting the WorkManager worker back off and retry (R13 graceful degradation).
     */
    suspend fun generatePending(quran: QuranRepository): Boolean {
        val pending = dao.pending()
        if (pending.isEmpty()) return true
        val apiKey = keyStore.load() ?: return false // no key = waiting state, not failure

        var allHandled = true
        for (entity in pending) {
            // Re-check: a user edit may have landed since pending() was read.
            val fresh = dao.find(entity.groupSurah, entity.groupAyah, entity.targetSurah, entity.targetAyah)
            if (fresh == null || fresh.source == MnemonicSource.USER || fresh.status != MnemonicStatus.PENDING) continue

            when (val result = client.generate(apiKey, buildPrompt(quran, fresh))) {
                is LlmResult.Success ->
                    dao.updateContent(fresh.id, result.text, MnemonicStatus.READY, MnemonicSource.LLM, null)
                is LlmResult.Failed ->
                    dao.updateContent(fresh.id, null, MnemonicStatus.FAILED, MnemonicSource.LLM, result.reason)
                is LlmResult.Retryable -> allHandled = false
            }
        }
        return allHandled
    }

    /**
     * Arabic-only prompt (R22). Verse text comes verbatim from the bundled dataset —
     * never from the LLM (R4) — and nothing personal is ever sent (R13).
     */
    private fun buildPrompt(quran: QuranRepository, m: MnemonicEntity): String {
        val target = AyahRef(m.targetSurah, m.targetAyah)
        val targetText = quran.ayahText(target).orEmpty()
        val group = quran.mutashabihatFor(target)
            .firstOrNull { it.source.surah == m.groupSurah && it.source.ayah == m.groupAyah }
        val others = group?.members.orEmpty().filter { it != target }.take(4)
            .joinToString("\n") { "- ${quran.surahName(it.surah)} ($it): ${quran.ayahText(it).orEmpty()}" }

        return """
            أنت مساعد لحافظ قرآن يخلط بين الآيات المتشابهات. الآية المستهدفة:
            ${quran.surahName(target.surah)} ($target): $targetText

            الآيات المتشابهة معها:
            $others

            اكتب باللغة العربية فقط جملة أو جملتين قصيرتين تساعد الحافظ على تثبيت اللفظ الدقيق للآية المستهدفة وتمييزها عن المتشابهات، مع ذكر وجه التمييز (مثل ارتباط اللفظ بسياق السورة أو قاعدة ربط بسيطة). لا تكتب أي مقدمات أو شروح إضافية.
        """.trimIndent()
    }
}
