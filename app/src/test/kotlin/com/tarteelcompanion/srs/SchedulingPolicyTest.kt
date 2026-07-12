package com.tarteelcompanion.srs

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tarteelcompanion.data.AppDatabase
import com.tarteelcompanion.data.Detection
import com.tarteelcompanion.data.MistakeRepository
import com.tarteelcompanion.data.entity.encodeWordRef
import com.tarteelcompanion.data.model.MistakeType
import com.tarteelcompanion.data.model.ReviewGrade
import com.tarteelcompanion.data.model.SpotState
import com.tarteelcompanion.quran.WordRef
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SchedulingPolicyTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: MistakeRepository
    private lateinit var policy: SchedulingPolicy

    private val ref = WordRef(2, 10, 4)
    private val spotId = encodeWordRef(ref)

    /** Simulated clock: day D at [hour] o'clock, as epoch millis. */
    private fun at(day: Long, hour: Long = 12) = day * 86_400_000L + hour * 3_600_000L

    private val day0 = 20_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = MistakeRepository(db)
        policy = SchedulingPolicy(db, minQuizGapHours = 10)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seedSpot(day: Long = day0) {
        repo.recordImport("hash-$day", 42, listOf(Detection(ref, MistakeType.WRONG_WORD)), day, at(day))
    }

    private suspend fun spot() = db.spotDao().byId(spotId)!!

    @Test
    fun `study good grows the interval`() = runTest {
        seedSpot()
        policy.studyGrade(spotId, ReviewGrade.GOOD, day0, at(day0))

        val s = spot()
        assertTrue(s.dueEpochDay!! > day0)
        assertTrue(s.stability > 0.0)
    }

    @Test
    fun `next-day quiz good is confirming not double-counting`() = runTest {
        seedSpot()
        policy.studyGrade(spotId, ReviewGrade.GOOD, day0, at(day0))
        val afterStudy = spot()

        policy.quizGrade(spotId, ReviewGrade.GOOD, day0 + 1, at(day0 + 1))
        val afterQuiz = spot()

        // The quiz replayed from the pre-study (new-card) snapshot: one scheduling-
        // effective review. Stability must equal a single GOOD from new, not two
        // stacked GOODs.
        val singleReview = Fsrs.grade(Fsrs.newCard(), ReviewGrade.GOOD, day0 + 1)
        assertEquals(singleReview.stability, afterQuiz.stability, 1e-9)
        assertTrue(afterQuiz.stability < Fsrs.grade(afterStudy.let {
            Fsrs.State(it.stability, it.difficulty, it.phase, it.dueEpochDay, it.lastReviewEpochDay)
        }, ReviewGrade.GOOD, day0 + 1).stability)
        assertEquals(SpotState.ACTIVE, afterQuiz.state) // interval < 21d: no graduation
    }

    @Test
    fun `quiz again after study good nets out to a lapse in the study queue`() = runTest {
        seedSpot()
        policy.studyGrade(spotId, ReviewGrade.GOOD, day0, at(day0))
        policy.quizGrade(spotId, ReviewGrade.AGAIN, day0 + 1, at(day0 + 1))

        val s = spot()
        assertEquals(day0 + 1, s.dueEpochDay) // back in today's study queue
        assertEquals(Fsrs.Phase.RELEARNING, s.phase)
        assertTrue(policy.studyQueue(day0 + 1, at(day0 + 1, 13)).any { it.id == spotId })
    }

    @Test
    fun `quiz good at mature interval graduates the spot`() = runTest {
        seedSpot()
        // Seed a mature review-phase state directly: last reviewed 30 days ago, 30-day interval.
        db.spotDao().update(
            spot().copy(
                stability = 30.0, difficulty = 5.0, phase = Fsrs.Phase.REVIEW,
                dueEpochDay = day0 + 30, lastReviewEpochDay = day0, pendingLapse = false,
            ),
        )
        policy.studyGrade(spotId, ReviewGrade.GOOD, day0 + 30, at(day0 + 30))
        policy.quizGrade(spotId, ReviewGrade.GOOD, day0 + 31, at(day0 + 31))

        val s = spot()
        assertEquals(SpotState.GRADUATED, s.state)
        assertEquals(day0 + 31, s.graduatedAtEpochDay)
    }

    @Test
    fun `occurrence during open cycle re-bases the pending quiz`() = runTest {
        seedSpot()
        db.spotDao().update(
            spot().copy(
                stability = 30.0, difficulty = 5.0, phase = Fsrs.Phase.REVIEW,
                dueEpochDay = day0 + 30, lastReviewEpochDay = day0, pendingLapse = false,
            ),
        )
        // Study on day 30, then the same mistake happens live in Tarteel that evening.
        policy.studyGrade(spotId, ReviewGrade.GOOD, day0 + 30, at(day0 + 30))
        repo.recordImport(
            "evening", 42, listOf(Detection(ref, MistakeType.WRONG_WORD)),
            day0 + 30, at(day0 + 30, 20),
        )
        policy.applyOccurrenceLapse(spotId, day0 + 30)
        val lapsedStability = spot().stability

        // Next-day quiz GOOD must build on the lapsed baseline — no graduation, no
        // pre-lapse interval confirmation (adversarial finding 2).
        policy.quizGrade(spotId, ReviewGrade.GOOD, day0 + 31, at(day0 + 31))

        val s = spot()
        assertEquals(SpotState.ACTIVE, s.state)
        assertTrue("stability ${s.stability} should stay near lapsed baseline", s.stability < 30.0)
        assertTrue(s.stability >= lapsedStability)
    }

    @Test
    fun `occurrence import on a far-future spot makes it due now with reduced stability`() = runTest {
        seedSpot()
        db.spotDao().update(
            spot().copy(
                stability = 40.0, difficulty = 4.0, phase = Fsrs.Phase.REVIEW,
                dueEpochDay = day0 + 30, lastReviewEpochDay = day0, pendingLapse = false,
            ),
        )
        repo.recordImport("relapse", 42, listOf(Detection(ref, MistakeType.WRONG_WORD)), day0 + 5, at(day0 + 5))
        policy.applyOccurrenceLapse(spotId, day0 + 5)

        val s = spot()
        assertEquals(day0 + 5, s.dueEpochDay)
        assertEquals(Fsrs.Phase.RELEARNING, s.phase)
        assertTrue(s.stability < 40.0)
        assertFalse(s.pendingLapse)
    }

    @Test
    fun `quiz eligibility respects next-day and minimum-gap floor`() = runTest {
        seedSpot()
        // Study at 23:50 on day0.
        policy.studyGrade(spotId, ReviewGrade.GOOD, day0, day0 * 86_400_000L + 23 * 3_600_000L + 50 * 60_000L)

        // 00:10 next day: calendar day passed but the 10h floor has not.
        assertFalse(policy.isQuizPending(spotId, day0 + 1, at(day0 + 1, 0) + 10 * 60_000L))
        // 10:30 next day: both conditions met.
        assertTrue(policy.isQuizPending(spotId, day0 + 1, at(day0 + 1, 10) + 30 * 60_000L))
    }

    @Test
    fun `re-study supersedes the pending quiz`() = runTest {
        seedSpot()
        policy.studyGrade(spotId, ReviewGrade.GOOD, day0, at(day0))
        assertTrue(policy.isQuizPending(spotId, day0 + 3, at(day0 + 3)))

        // Re-studied on day 3 → the old pending quiz is superseded; day 3's study owes
        // exactly one new quiz from day 4 (flow-analysis I5).
        policy.studyGrade(spotId, ReviewGrade.GOOD, day0 + 3, at(day0 + 3))
        assertFalse(policy.isQuizPending(spotId, day0 + 3, at(day0 + 3, 23)))
        assertTrue(policy.isQuizPending(spotId, day0 + 4, at(day0 + 4)))
    }

    @Test
    fun `quiz-pending spot is withheld from the study queue`() = runTest {
        seedSpot()
        policy.studyGrade(spotId, ReviewGrade.AGAIN, day0, at(day0)) // due today, relearning
        // Manually reviewed again to set a study log; then day+1 it is both due and quiz-pending.
        policy.studyGrade(spotId, ReviewGrade.HARD, day0, at(day0, 13)) // still due soon

        val pending = policy.isQuizPending(spotId, day0 + 1, at(day0 + 1))
        assertTrue(pending)
        assertFalse(policy.studyQueue(day0 + 1, at(day0 + 1)).any { it.id == spotId })
        assertTrue(policy.quizQueue(day0 + 1, at(day0 + 1)).any { it.id == spotId })
    }

    @Test
    fun `review-ahead grades apply without corrupting ordering`() = runTest {
        seedSpot()
        policy.studyGrade(spotId, ReviewGrade.EASY, day0, at(day0))
        val dueAfterEasy = spot().dueEpochDay!!

        // Grade again before due (review-ahead, M4): state updates, due stays sane.
        policy.studyGrade(spotId, ReviewGrade.GOOD, day0 + 1, at(day0 + 1))
        val s = spot()
        assertTrue(s.dueEpochDay!! >= day0 + 1)
        assertTrue(dueAfterEasy > day0)
    }

    @Test
    fun `fsrs determinism same inputs same schedule`() {
        val a = Fsrs.grade(Fsrs.newCard(), ReviewGrade.GOOD, 100L)
        val b = Fsrs.grade(Fsrs.newCard(), ReviewGrade.GOOD, 100L)
        assertEquals(a, b)
        // Multi-step timeline determinism.
        var s1 = Fsrs.newCard(); var s2 = Fsrs.newCard()
        for (day in longArrayOf(100, 101, 105, 120)) {
            s1 = Fsrs.grade(s1, ReviewGrade.GOOD, day)
            s2 = Fsrs.grade(s2, ReviewGrade.GOOD, day)
        }
        assertEquals(s1, s2)
    }

    @Test
    fun `stability grows across a multi-week good streak`() {
        var state = Fsrs.grade(Fsrs.newCard(), ReviewGrade.GOOD, 0L)
        var previous = state.stability
        var day = 0L
        repeat(5) {
            day = state.dueEpochDay!!
            state = Fsrs.grade(state, ReviewGrade.GOOD, day)
            assertTrue("stability should grow: ${state.stability} vs $previous", state.stability > previous)
            previous = state.stability
        }
        assertTrue("mature interval after streak", state.scheduledIntervalDays >= 21)
    }
}
