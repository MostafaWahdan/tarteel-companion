package com.tarteelcompanion.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.tarteelcompanion.data.model.MistakeType
import com.tarteelcompanion.data.model.ReviewGrade
import com.tarteelcompanion.data.model.ReviewKind
import com.tarteelcompanion.data.model.SpotState
import com.tarteelcompanion.quran.WordRef

/**
 * A mistake spot: one canonical word position with mistake history and FSRS state.
 * Keyed by the encoded word position so re-imports update history, never duplicate (R7).
 */
@Entity(tableName = "spots")
data class SpotEntity(
    /** Encoded WordRef — see [encodeWordRef]. Stable across imports. */
    @PrimaryKey val id: Long,
    val surah: Int,
    val ayah: Int,
    val word: Int,
    val state: SpotState = SpotState.ACTIVE,
    /**
     * Set when a new occurrence arrives on an existing spot; the scheduler (U7)
     * consumes it as an FSRS lapse and re-bases any open study→quiz cycle.
     */
    val pendingLapse: Boolean = false,
    // FSRS state (owned by the U7 scheduler; zero/null = new card).
    val stability: Double = 0.0,
    val difficulty: Double = 0.0,
    val phase: Int = 0,
    /** Due date as epoch day; null = never scheduled. */
    val dueEpochDay: Long? = null,
    val lastReviewEpochDay: Long? = null,
    val createdAtEpochDay: Long,
    val graduatedAtEpochDay: Long? = null,
) {
    val wordRef: WordRef get() = WordRef(surah, ayah, word)
}

/**
 * One occurrence of a mistake at a spot on a calendar day. Same-position detections on
 * the same day collapse into one row with a hit count (flow-analysis I1/I2); the type
 * of the most recent detection wins for card emphasis (R14).
 */
@Entity(
    tableName = "occurrences",
    indices = [Index(value = ["spotId", "epochDay"], unique = true), Index("spotId")],
)
data class OccurrenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val spotId: Long,
    val epochDay: Long,
    val type: MistakeType,
    val hitCount: Int = 1,
    val importId: Long,
)

/** An imported screenshot: content hash for exact-duplicate rejection, thumbnail for history. */
@Entity(
    tableName = "imports",
    indices = [Index(value = ["contentHash"], unique = true)],
)
data class ImportScreenshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contentHash: String,
    val pageNumber: Int?,
    val importedAtEpochMillis: Long,
    val thumbnailPath: String? = null,
)

/**
 * One self-graded review. Snapshots the spot's FSRS state as of immediately BEFORE the
 * review so quiz supersession can replay from the pre-study baseline (plan U5/U7 —
 * one scheduling-effective review per study→quiz cycle).
 */
@Entity(
    tableName = "review_log",
    indices = [Index("spotId")],
)
data class ReviewLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val spotId: Long,
    val kind: ReviewKind,
    val grade: ReviewGrade,
    val reviewedAtEpochMillis: Long,
    // Pre-review FSRS snapshot (v1 schema — avoids an early migration). Quiz
    // supersession replays from these fields; preLastReviewEpochDay pins the elapsed
    // interval so the superseding quiz review spans the full pre-cycle interval,
    // not just the study→quiz gap.
    val preStability: Double,
    val preDifficulty: Double,
    val prePhase: Int,
    val preDueEpochDay: Long?,
    val preLastReviewEpochDay: Long?,
)

/** Encodes a word position as a stable long key: SSSAAAWWW (surah, ayah, word). */
fun encodeWordRef(ref: WordRef): Long =
    ref.surah * 1_000_000L + ref.ayah * 1_000L + ref.word

fun decodeWordRef(id: Long): WordRef =
    WordRef((id / 1_000_000L).toInt(), ((id / 1_000L) % 1_000L).toInt(), (id % 1_000L).toInt())
