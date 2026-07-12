package com.tarteelcompanion.srs

import com.tarteelcompanion.data.AppDatabase
import com.tarteelcompanion.data.entity.ReviewLogEntity
import com.tarteelcompanion.data.entity.SpotEntity
import com.tarteelcompanion.data.model.ReviewGrade
import com.tarteelcompanion.data.model.ReviewKind
import com.tarteelcompanion.data.model.SpotState

/**
 * The single authority over FSRS state (plan U7 / System-Wide Impact): study (U8) and
 * quiz (U11) both route grades through here — no other code mutates scheduling fields.
 *
 * Invariants (plan U7):
 * 1. One scheduling-effective review per study→quiz cycle: a quiz grade replays from
 *    the pre-study snapshot on that cycle's study ReviewLog row.
 * 2. Quiz Again/Hard = lapse back to the study queue; Good/Easy = confirm.
 * 3. Graduation: quiz Good+ while the replay base's interval ≥ [GRADUATION_INTERVAL_DAYS].
 * 4. A new occurrence lapses the spot; during an open cycle it re-bases the pending quiz.
 * 5. Review-ahead is allowed; grades apply normally.
 */
class SchedulingPolicy(
    private val db: AppDatabase,
    private val minQuizGapHours: Long = DEFAULT_MIN_QUIZ_GAP_HOURS,
) {

    companion object {
        const val GRADUATION_INTERVAL_DAYS = 21L

        /** Closes the midnight loophole: 23:50 study is not "cold" at 00:10 (plan U11). */
        const val DEFAULT_MIN_QUIZ_GAP_HOURS = 10L

        private const val HOUR_MILLIS = 3_600_000L
    }

    /** Spots due for study today, excluding quiz-pending spots (quiz-first rule, I6). */
    suspend fun studyQueue(todayEpochDay: Long, nowEpochMillis: Long): List<SpotEntity> =
        db.spotDao().dueOn(todayEpochDay).filterNot { isQuizPending(it.id, todayEpochDay, nowEpochMillis) }

    /** Active spots eligible for a cold-recall quiz right now (R15 + hours floor), oldest study first. */
    suspend fun quizQueue(todayEpochDay: Long, nowEpochMillis: Long): List<SpotEntity> {
        val pending = db.spotDao().byState(SpotState.ACTIVE)
            .filter { isQuizPending(it.id, todayEpochDay, nowEpochMillis) }
        return pending.map { it to latestStudyMillis(it.id) }
            .sortedBy { it.second }
            .map { it.first }
    }

    /**
     * Quiz-pending: studied, not yet quizzed since that study (a re-study supersedes any
     * older pending quiz — I5), next calendar day reached, and the minimum hours gap passed.
     */
    suspend fun isQuizPending(spotId: Long, todayEpochDay: Long, nowEpochMillis: Long): Boolean {
        val study = db.reviewLogDao().latestStudy(spotId) ?: return false
        val quizzedAfter = db.reviewLogDao().forSpot(spotId).any {
            it.kind == ReviewKind.QUIZ && it.reviewedAtEpochMillis > study.reviewedAtEpochMillis
        }
        if (quizzedAfter) return false
        val studyDay = study.reviewedAtEpochMillis / 86_400_000L
        val gapOk = nowEpochMillis - study.reviewedAtEpochMillis >= minQuizGapHours * HOUR_MILLIS
        return todayEpochDay > studyDay && gapOk
    }

    /** Self-graded study review (U8). Review-ahead is allowed — grades apply normally. */
    suspend fun studyGrade(spotId: Long, grade: ReviewGrade, todayEpochDay: Long, nowEpochMillis: Long) {
        var spot = db.spotDao().byId(spotId) ?: return
        if (spot.pendingLapse) spot = applyOccurrenceLapse(spotId, todayEpochDay) ?: return

        val base = spot.fsrsState()
        logReview(spotId, ReviewKind.STUDY, grade, nowEpochMillis, base)
        persist(spot, Fsrs.grade(base, grade, todayEpochDay))
    }

    /**
     * Self-graded cold-quiz review (U11). Replays from the pre-study snapshot so the
     * study grade never double-counts — unless a lapse occurred after the study, in
     * which case the lapsed state is the new baseline (live failure outranks the quiz).
     */
    suspend fun quizGrade(spotId: Long, grade: ReviewGrade, todayEpochDay: Long, nowEpochMillis: Long) {
        var spot = db.spotDao().byId(spotId) ?: return
        if (spot.pendingLapse) spot = applyOccurrenceLapse(spotId, todayEpochDay) ?: return

        // A lapse "after the study" re-bases the cycle. Occurrences are day-granular,
        // so compare using the source import's millisecond timestamp — an evening
        // Tarteel failure after a morning study must not be erased by the next-day quiz.
        val study = db.reviewLogDao().latestStudy(spotId)
        val lapsedAfterStudy = study != null && db.occurrenceDao().latestForSpot(spotId)?.let { occ ->
            db.importDao().byId(occ.importId)?.importedAtEpochMillis?.let { it > study.reviewedAtEpochMillis }
        } == true

        val base = if (study != null && !lapsedAfterStudy) {
            Fsrs.State(
                stability = study.preStability,
                difficulty = study.preDifficulty,
                phase = study.prePhase,
                dueEpochDay = study.preDueEpochDay,
                lastReviewEpochDay = study.preLastReviewEpochDay,
            )
        } else {
            spot.fsrsState() // re-based cycle: the lapse is the new pre-study baseline
        }

        logReview(spotId, ReviewKind.QUIZ, grade, nowEpochMillis, base)
        val next = Fsrs.grade(base, grade, todayEpochDay)

        when (grade) {
            ReviewGrade.AGAIN, ReviewGrade.HARD -> {
                // Lapse back to the study queue today (M6: Again/Hard return to study).
                persist(
                    spot,
                    next.copy(phase = Fsrs.Phase.RELEARNING, dueEpochDay = todayEpochDay),
                )
            }
            ReviewGrade.GOOD, ReviewGrade.EASY -> {
                val matured = base.scheduledIntervalDays >= GRADUATION_INTERVAL_DAYS
                persist(spot, next, graduate = matured, todayEpochDay = todayEpochDay)
            }
        }
    }

    /**
     * Converts a new real-world occurrence into an FSRS lapse (called by the import
     * flow for lapsed/reactivated spots, and defensively before any grading).
     */
    suspend fun applyOccurrenceLapse(spotId: Long, todayEpochDay: Long): SpotEntity? {
        val spot = db.spotDao().byId(spotId) ?: return null
        val next = Fsrs.occurrenceLapse(spot.fsrsState(), todayEpochDay)
        val updated = spot.copy(
            pendingLapse = false,
            stability = next.stability,
            difficulty = next.difficulty,
            phase = next.phase,
            dueEpochDay = next.dueEpochDay,
            lastReviewEpochDay = next.lastReviewEpochDay,
        )
        db.spotDao().update(updated)
        return updated
    }

    private suspend fun logReview(
        spotId: Long,
        kind: ReviewKind,
        grade: ReviewGrade,
        nowEpochMillis: Long,
        pre: Fsrs.State,
    ) {
        db.reviewLogDao().insert(
            ReviewLogEntity(
                spotId = spotId,
                kind = kind,
                grade = grade,
                reviewedAtEpochMillis = nowEpochMillis,
                preStability = pre.stability,
                preDifficulty = pre.difficulty,
                prePhase = pre.phase,
                preDueEpochDay = pre.dueEpochDay,
                preLastReviewEpochDay = pre.lastReviewEpochDay,
            ),
        )
    }

    private suspend fun latestStudyMillis(spotId: Long): Long =
        db.reviewLogDao().latestStudy(spotId)?.reviewedAtEpochMillis ?: Long.MAX_VALUE

    private suspend fun persist(
        spot: SpotEntity,
        next: Fsrs.State,
        graduate: Boolean = false,
        todayEpochDay: Long? = null,
    ) {
        db.spotDao().update(
            spot.copy(
                stability = next.stability,
                difficulty = next.difficulty,
                phase = next.phase,
                dueEpochDay = next.dueEpochDay,
                lastReviewEpochDay = next.lastReviewEpochDay,
                state = if (graduate) SpotState.GRADUATED else spot.state,
                graduatedAtEpochDay = if (graduate) todayEpochDay else spot.graduatedAtEpochDay,
                pendingLapse = false,
            ),
        )
    }
}

private fun SpotEntity.fsrsState(): Fsrs.State =
    Fsrs.State(stability, difficulty, phase, dueEpochDay, lastReviewEpochDay)
