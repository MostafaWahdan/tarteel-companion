package com.tarteelcompanion.study

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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/**
 * State-machine coverage for the study session (review finding: zero VM tests).
 * ViewModels hop from Main to real IO/Room executors, so tests await state
 * transitions by polling (TestData.awaitNonNull) rather than scheduler advancing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class StudyViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: MistakeRepository
    private lateinit var policy: SchedulingPolicy

    private val ref = WordRef(2, 10, 4)

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

    private fun viewModel(): StudyViewModel {
        assumeTrue("dataset not fetched", TestData.quran != null)
        return StudyViewModel({ TestData.quran!! }, repo, policy)
    }

    private inline fun <reified T> awaitState(vm: StudyViewModel): T =
        TestData.awaitNonNull { vm.state.value as? T }

    private fun seedDueSpot() = runBlocking {
        repo.recordImport(
            "hash-due", 42, listOf(Detection(ref, MistakeType.WRONG_WORD)),
            LocalDate.now().toEpochDay(), System.currentTimeMillis(),
        )
    }

    @Test
    fun `first launch with no spots shows the import-pointing empty state`() {
        val vm = viewModel()
        assertEquals(StudyUiState.Empty(hasAnyActiveSpots = false), awaitState<StudyUiState.Empty>(vm))
    }

    @Test
    fun `due spot renders a card and grading completes the session`() {
        seedDueSpot()
        val vm = viewModel()

        val reviewing = awaitState<StudyUiState.Reviewing>(vm)
        assertEquals(1, reviewing.total)
        assertTrue(!reviewing.revealed)

        vm.reveal()
        TestData.awaitNonNull { (vm.state.value as? StudyUiState.Reviewing)?.takeIf { it.revealed } }

        vm.grade(ReviewGrade.GOOD)
        assertEquals(StudyUiState.SessionDone(1), awaitState<StudyUiState.SessionDone>(vm))
        runBlocking {
            assertTrue(db.spotDao().byId(encodeWordRef(ref))!!.stability > 0.0)
        }
    }

    @Test
    fun `double-tapped grade applies exactly once`() {
        seedDueSpot()
        val vm = viewModel()
        awaitState<StudyUiState.Reviewing>(vm)
        vm.reveal()
        TestData.awaitNonNull { (vm.state.value as? StudyUiState.Reviewing)?.takeIf { it.revealed } }

        vm.grade(ReviewGrade.GOOD)
        vm.grade(ReviewGrade.GOOD) // second tap while the first is in flight
        awaitState<StudyUiState.SessionDone>(vm)

        runBlocking {
            assertEquals(1, db.reviewLogDao().forSpot(encodeWordRef(ref)).size)
        }
    }

    @Test
    fun `nothing due offers review-ahead which loads the active spot`() {
        seedDueSpot()
        runBlocking {
            val id = encodeWordRef(ref)
            db.spotDao().update(db.spotDao().byId(id)!!.copy(dueEpochDay = LocalDate.now().toEpochDay() + 10))
        }

        val vm = viewModel()
        assertEquals(StudyUiState.Empty(hasAnyActiveSpots = true), awaitState<StudyUiState.Empty>(vm))

        vm.loadQueue(ahead = true)
        val reviewing = awaitState<StudyUiState.Reviewing>(vm)
        assertTrue(reviewing.reviewAhead)
        assertEquals(1, reviewing.total)
    }

    @Test
    fun `deleting the only spot ends the session`() {
        seedDueSpot()
        val vm = viewModel()
        awaitState<StudyUiState.Reviewing>(vm)

        vm.deleteSpot(ref)
        assertEquals(StudyUiState.SessionDone(0), awaitState<StudyUiState.SessionDone>(vm))
        runBlocking { assertNull(repo.spot(ref)) }
    }
}
