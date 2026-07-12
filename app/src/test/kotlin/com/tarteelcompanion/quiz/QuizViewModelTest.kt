package com.tarteelcompanion.quiz

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tarteelcompanion.TestData
import com.tarteelcompanion.data.AppDatabase
import com.tarteelcompanion.data.Detection
import com.tarteelcompanion.data.MistakeRepository
import com.tarteelcompanion.data.entity.encodeWordRef
import com.tarteelcompanion.data.model.MistakeType
import com.tarteelcompanion.data.model.ReviewGrade
import com.tarteelcompanion.data.model.ReviewKind
import com.tarteelcompanion.quran.WordRef
import com.tarteelcompanion.srs.SchedulingPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/** State-machine coverage for the cold-recall quiz (review finding: zero VM tests). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class QuizViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: MistakeRepository
    private lateinit var policy: SchedulingPolicy

    private val refA = WordRef(2, 10, 4)
    private val refB = WordRef(3, 5, 2)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = MistakeRepository(db)
        policy = SchedulingPolicy(db)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun viewModel(cap: Int = 30): QuizViewModel {
        assumeTrue("dataset not fetched", TestData.quran != null)
        return QuizViewModel({ TestData.quran!! }, repo, policy, sessionCap = cap)
    }

    private inline fun <reified T> awaitState(vm: QuizViewModel): T =
        TestData.awaitNonNull { vm.state.value as? T }

    private fun awaitPhase(vm: QuizViewModel, phase: QuizUiState.Question.Phase): QuizUiState.Question =
        TestData.awaitNonNull { (vm.state.value as? QuizUiState.Question)?.takeIf { it.phase == phase } }

    /** Seeds a spot studied ~26 hours ago so it is quiz-pending on the real clock. */
    private fun seedQuizPending(ref: WordRef) = runBlocking {
        val yesterday = LocalDate.now().toEpochDay() - 1
        val studiedAt = System.currentTimeMillis() - 26 * 3_600_000L
        repo.recordImport(
            "hash-$ref", 42, listOf(Detection(ref, MistakeType.WRONG_WORD)),
            yesterday, studiedAt - 3_600_000L,
        )
        policy.studyGrade(encodeWordRef(ref), ReviewGrade.GOOD, yesterday, studiedAt)
    }

    @Test
    fun `nothing pending shows the empty state`() {
        val vm = viewModel()
        awaitState<QuizUiState.Empty>(vm)
    }

    @Test
    fun `full quiz flow counts passed and returned spots`() {
        seedQuizPending(refA)
        seedQuizPending(refB)
        val vm = viewModel()

        var question = awaitPhase(vm, QuizUiState.Question.Phase.FRONT)
        assertEquals(2, question.total)

        vm.reveal()
        awaitPhase(vm, QuizUiState.Question.Phase.REVEALED)
        vm.grade(ReviewGrade.GOOD)
        awaitPhase(vm, QuizUiState.Question.Phase.GRADED)
        vm.next()

        question = awaitPhase(vm, QuizUiState.Question.Phase.FRONT)
        assertEquals(2, question.index)
        vm.reveal()
        awaitPhase(vm, QuizUiState.Question.Phase.REVEALED)
        vm.grade(ReviewGrade.AGAIN)
        awaitPhase(vm, QuizUiState.Question.Phase.GRADED)
        vm.next()

        val summary = awaitState<QuizUiState.Summary>(vm)
        assertEquals(2, summary.total)
        assertEquals(1, summary.passed)
    }

    @Test
    fun `session cap truncates the pending queue oldest first`() {
        seedQuizPending(refA)
        seedQuizPending(refB)
        val vm = viewModel(cap = 1)
        assertEquals(1, awaitPhase(vm, QuizUiState.Question.Phase.FRONT).total)
    }

    @Test
    fun `double-tapped quiz grade records exactly one quiz review`() {
        seedQuizPending(refA)
        val vm = viewModel()
        awaitPhase(vm, QuizUiState.Question.Phase.FRONT)
        vm.reveal()
        awaitPhase(vm, QuizUiState.Question.Phase.REVEALED)

        vm.grade(ReviewGrade.GOOD)
        vm.grade(ReviewGrade.GOOD) // in-flight double tap
        awaitPhase(vm, QuizUiState.Question.Phase.GRADED)
        vm.grade(ReviewGrade.GOOD) // tap again on the GRADED phase

        runBlocking {
            val quizLogs = db.reviewLogDao().forSpot(encodeWordRef(refA)).filter { it.kind == ReviewKind.QUIZ }
            assertEquals(1, quizLogs.size)
        }
    }

    @Test
    fun `failed spot returns to the study queue`() {
        seedQuizPending(refA)
        val vm = viewModel()
        awaitPhase(vm, QuizUiState.Question.Phase.FRONT)
        vm.reveal()
        awaitPhase(vm, QuizUiState.Question.Phase.REVEALED)
        vm.grade(ReviewGrade.AGAIN)
        awaitPhase(vm, QuizUiState.Question.Phase.GRADED)

        runBlocking {
            val today = LocalDate.now().toEpochDay()
            assertTrue(
                policy.studyQueue(today, System.currentTimeMillis()).any { it.id == encodeWordRef(refA) },
            )
        }
    }
}
