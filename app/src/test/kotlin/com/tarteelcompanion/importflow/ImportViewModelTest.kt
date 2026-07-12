package com.tarteelcompanion.importflow

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tarteelcompanion.TestData
import com.tarteelcompanion.data.AppDatabase
import com.tarteelcompanion.data.Detection
import com.tarteelcompanion.data.MistakeRepository
import com.tarteelcompanion.data.model.MistakeType
import com.tarteelcompanion.extraction.ExtractionPipeline
import com.tarteelcompanion.extraction.ExtractionResult
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Batch state-machine coverage for the import flow (review finding: zero VM tests). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImportViewModelTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repo: MistakeRepository
    private lateinit var policy: SchedulingPolicy

    private class FakePipeline(
        var onExtract: (Int?) -> ExtractionResult = { ExtractionResult.NeedsManual("no hint") },
        var onAnchor: (Int) -> ExtractionResult = { ExtractionResult.NeedsManual("fake") },
    ) : ExtractionPipeline {
        override fun extract(pixels: IntArray, width: Int, height: Int, pageHint: Int?) = onExtract(pageHint)
        override fun anchorAt(pixels: IntArray, width: Int, height: Int, page: Int) = onAnchor(page)
    }

    private lateinit var pipeline: FakePipeline

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = MistakeRepository(db)
        policy = SchedulingPolicy(db)
        pipeline = FakePipeline()
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun viewModel() = ImportViewModel(context, repo, policy) { pipeline }

    private inline fun <reified T> awaitState(vm: ImportViewModel): T =
        TestData.awaitNonNull { vm.state.value as? T }

    private fun awaitConfirming(vm: ImportViewModel, index: Int): ImportUiState.Confirming =
        TestData.awaitNonNull { (vm.state.value as? ImportUiState.Confirming)?.takeIf { it.index == index } }

    /** Registers a decodable PNG at a fake content URI; [seed] varies the bytes/hash. */
    private fun registerImage(name: String, seed: Int): Uri {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF000000.toInt() or seed)
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = Uri.parse("content://test/$name")
        Shadows.shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes.toByteArray()))
        return uri
    }

    @Test
    fun `two-item batch confirms sequentially and reports the summary`() {
        val vm = viewModel()
        vm.enqueue(listOf(registerImage("a.png", 0x111111), registerImage("b.png", 0x222222)))

        var confirming = awaitConfirming(vm, index = 1)
        assertEquals(2, confirming.total)

        vm.save(confirming.item, 60, listOf(Detection(WordRef(3, 79, 6), MistakeType.PRONUNCIATION)))
        confirming = awaitConfirming(vm, index = 2)

        vm.save(confirming.item, 60, emptyList())
        assertEquals(
            ImportUiState.BatchDone(saved = 2, discarded = 0, duplicates = 0),
            awaitState<ImportUiState.BatchDone>(vm),
        )
        runBlocking { assertNotNull(repo.spot(WordRef(3, 79, 6))) }
    }

    @Test
    fun `re-importing identical bytes counts as a duplicate`() {
        val vm = viewModel()
        vm.enqueue(listOf(registerImage("dup1.png", 0x333333)))
        vm.save(awaitConfirming(vm, 1).item, 60, emptyList())
        awaitState<ImportUiState.BatchDone>(vm)

        // Same bytes at a different URI → same hash → duplicate.
        vm.enqueue(listOf(registerImage("dup2.png", 0x333333)))
        vm.save(awaitConfirming(vm, 1).item, 60, emptyList())

        assertEquals(
            ImportUiState.BatchDone(saved = 0, discarded = 0, duplicates = 1),
            awaitState<ImportUiState.BatchDone>(vm),
        )
    }

    @Test
    fun `discard advances without persisting anything`() {
        val vm = viewModel()
        vm.enqueue(listOf(registerImage("junk.png", 0x444444)))
        awaitConfirming(vm, 1)

        vm.discard()
        vm.discard() // double tap must not skip a phantom second item
        assertEquals(
            ImportUiState.BatchDone(saved = 0, discarded = 1, duplicates = 0),
            awaitState<ImportUiState.BatchDone>(vm),
        )
    }

    @Test
    fun `failed auto-detect preserves the item and surfaces a message`() {
        pipeline.onExtract = {
            ExtractionResult.Extracted(60, listOf(Detection(WordRef(3, 79, 6), MistakeType.PRONUNCIATION)))
        }
        pipeline.onAnchor = { ExtractionResult.NeedsManual("page 61 does not fit") }
        val vm = viewModel()
        vm.enqueue(listOf(registerImage("keep.png", 0x555555)))

        val before = awaitConfirming(vm, 1)
        assertTrue(before.item.extraction is ExtractionResult.Extracted)

        vm.autoDetect(61)
        val after = TestData.awaitNonNull {
            (vm.state.value as? ImportUiState.Confirming)?.takeIf { it.detectMessage != null }
        }
        assertTrue(
            "existing extraction must survive a failed re-detect",
            after.item.extraction is ExtractionResult.Extracted,
        )
    }

    @Test
    fun `unreadable stream is discarded with the batch continuing`() {
        val vm = viewModel()
        val bad = Uri.parse("content://test/missing.png") // no stream registered
        vm.enqueue(listOf(bad, registerImage("good.png", 0x666666)))

        val confirming = awaitConfirming(vm, 2) // first item auto-discarded
        vm.save(confirming.item, 60, emptyList())
        assertEquals(
            ImportUiState.BatchDone(saved = 1, discarded = 1, duplicates = 0),
            awaitState<ImportUiState.BatchDone>(vm),
        )
    }
}
