package com.tarteelcompanion.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tarteelcompanion.data.entity.encodeWordRef
import com.tarteelcompanion.data.model.MistakeType
import com.tarteelcompanion.data.model.SpotState
import com.tarteelcompanion.quran.WordRef
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Room semantics run on JVM via Robolectric (no device on the dev machine — plan U5 adaptation). */
@RunWith(RobolectricTestRunner::class)
class MistakeRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: MistakeRepository

    private val ref = WordRef(2, 255, 3)
    private val day0 = 20_000L
    private val day1 = 20_001L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = MistakeRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun import(
        hash: String,
        day: Long,
        vararg detections: Detection,
    ): ImportResult = repo.recordImport(hash, 42, detections.toList(), day, day * 86_400_000L)

    @Test
    fun `import creates spots and re-import next day updates history without duplicating`() = runTest {
        val first = import("h1", day0, Detection(ref, MistakeType.WRONG_WORD))
        assertTrue(first is ImportResult.Saved && first.newSpots == listOf(ref))

        val second = import("h2", day1, Detection(ref, MistakeType.WRONG_WORD))
        assertTrue(second is ImportResult.Saved && (second as ImportResult.Saved).lapsedSpots == listOf(ref))

        assertEquals(2, db.occurrenceDao().forSpot(encodeWordRef(ref)).size)
        assertEquals(SpotState.ACTIVE, repo.spot(ref)!!.state)
        assertTrue(repo.spot(ref)!!.pendingLapse)
    }

    @Test
    fun `same day same position collapses to one occurrence with hit count`() = runTest {
        import("h1", day0, Detection(ref, MistakeType.WRONG_WORD))
        import("h2", day0, Detection(ref, MistakeType.WRONG_WORD))

        val occurrences = db.occurrenceDao().forSpot(encodeWordRef(ref))
        assertEquals(1, occurrences.size)
        assertEquals(2, occurrences.single().hitCount)
    }

    @Test
    fun `identical image hash is rejected as duplicate`() = runTest {
        import("h1", day0, Detection(ref, MistakeType.WRONG_WORD))
        val result = import("h1", day0, Detection(ref, MistakeType.WRONG_WORD))

        assertEquals(ImportResult.DuplicateImage, result)
        assertEquals(1, db.occurrenceDao().forSpot(encodeWordRef(ref)).single().hitCount)
    }

    @Test
    fun `occurrence on graduated spot reactivates it with a lapse flag`() = runTest {
        import("h1", day0, Detection(ref, MistakeType.WRONG_WORD))
        db.spotDao().update(
            repo.spot(ref)!!.copy(state = SpotState.GRADUATED, graduatedAtEpochDay = day0, pendingLapse = false),
        )

        val result = import("h2", day1, Detection(ref, MistakeType.PROMPT_NEEDED))

        assertTrue((result as ImportResult.Saved).reactivatedSpots == listOf(ref))
        val spot = repo.spot(ref)!!
        assertEquals(SpotState.ACTIVE, spot.state)
        assertTrue(spot.pendingLapse)
        assertNull(spot.graduatedAtEpochDay)
        assertEquals(day1, spot.dueEpochDay)
    }

    @Test
    fun `mixed type history resolves to most recent occurrence type`() = runTest {
        import("h1", day0, Detection(ref, MistakeType.WRONG_WORD))
        import("h2", day1, Detection(ref, MistakeType.PROMPT_NEEDED))

        assertEquals(MistakeType.PROMPT_NEEDED, repo.effectiveType(ref))
    }

    @Test
    fun `same day mixed types latest wins on the collapsed occurrence`() = runTest {
        import("h1", day0, Detection(ref, MistakeType.PRONUNCIATION))
        import("h2", day0, Detection(ref, MistakeType.WRONG_WORD))

        val occurrence = db.occurrenceDao().forSpot(encodeWordRef(ref)).single()
        assertEquals(MistakeType.WRONG_WORD, occurrence.type)
        assertEquals(2, occurrence.hitCount)
    }

    @Test
    fun `suspended spot records occurrences but stays suspended`() = runTest {
        import("h1", day0, Detection(ref, MistakeType.WRONG_WORD))
        repo.suspend(ref)

        import("h2", day1, Detection(ref, MistakeType.WRONG_WORD))

        assertEquals(SpotState.SUSPENDED, repo.spot(ref)!!.state)
        assertEquals(2, db.occurrenceDao().forSpot(encodeWordRef(ref)).size)
    }

    @Test
    fun `delete removes spot occurrences and review history`() = runTest {
        import("h1", day0, Detection(ref, MistakeType.WRONG_WORD))
        repo.delete(ref)

        assertNull(repo.spot(ref))
        assertTrue(db.occurrenceDao().forSpot(encodeWordRef(ref)).isEmpty())
    }

    @Test
    fun `word ref encoding round-trips across the full range`() {
        for (r in listOf(WordRef(1, 1, 1), WordRef(2, 286, 128), WordRef(114, 6, 3))) {
            assertEquals(r, com.tarteelcompanion.data.entity.decodeWordRef(encodeWordRef(r)))
        }
    }
}
