package com.tarteelcompanion.importflow

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tarteelcompanion.TarteelApp
import com.tarteelcompanion.data.Detection
import com.tarteelcompanion.data.ImportResult
import com.tarteelcompanion.data.MistakeRepository
import com.tarteelcompanion.data.entity.encodeWordRef
import com.tarteelcompanion.extraction.ExtractionPipeline
import com.tarteelcompanion.extraction.ExtractionResult
import com.tarteelcompanion.quran.QuranRepository
import com.tarteelcompanion.srs.SchedulingPolicy
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate

/** One screenshot moving through the batch (F1). */
data class ImportItem(
    val uri: Uri,
    val hash: String,
    val bitmap: Bitmap,
    val extraction: ExtractionResult,
)

sealed interface ImportUiState {
    data object Idle : ImportUiState

    /** Sequential batch progress ("Processing 3 of 10") — design review finding 15. */
    data class Processing(val current: Int, val total: Int) : ImportUiState

    /** Confirm/correct for one screenshot; manual tagging when extraction declined. */
    data class Confirming(
        val item: ImportItem,
        val index: Int,
        val total: Int,
    ) : ImportUiState

    data class BatchDone(val saved: Int, val discarded: Int, val duplicates: Int) : ImportUiState

    data class Error(val message: String) : ImportUiState
}

/**
 * F1 orchestration (U6): sequential batch, save-per-screenshot (abandoning mid-batch
 * keeps confirmed items — I4), duplicate-hash rejection, discard and wrong-page exits,
 * lapse signals forwarded to the scheduler after each save.
 */
class ImportViewModel(
    private val appContext: Context,
    private val quranDeferred: Deferred<QuranRepository>,
    private val mistakes: MistakeRepository,
    private val policy: SchedulingPolicy,
    private val pipeline: ExtractionPipeline,
) : ViewModel() {

    private val _state = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    val state: StateFlow<ImportUiState> = _state

    private val queue = ArrayDeque<Uri>()
    private var batchTotal = 0
    private var processedCount = 0
    private var saved = 0
    private var discarded = 0
    private var duplicates = 0

    companion object {
        /** Decode-bounds cap: reject absurd inputs before full decode (security FYI). */
        const val MAX_DIMENSION_PX = 8_000

        /** Working size for extraction/display; screenshots are downsampled to fit. */
        const val TARGET_MAX_PX = 2_000

        fun factory(app: TarteelApp, pipeline: ExtractionPipeline) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ImportViewModel(app, app.quran, app.mistakes, app.policy, pipeline) as T
        }
    }

    /** Entry from the picker or the share sheet; new share intents queue behind the batch (I4). */
    fun enqueue(uris: List<Uri>) {
        if (uris.isEmpty()) return
        queue.addAll(uris)
        batchTotal += uris.size
        if (_state.value is ImportUiState.Idle || _state.value is ImportUiState.BatchDone) {
            saved = 0; discarded = 0; duplicates = 0; processedCount = 0
            batchTotal = queue.size
            processNext()
        }
    }

    private fun processNext() {
        val uri = queue.removeFirstOrNull()
        if (uri == null) {
            _state.value = ImportUiState.BatchDone(saved, discarded, duplicates)
            return
        }
        processedCount++
        _state.value = ImportUiState.Processing(processedCount, batchTotal)

        viewModelScope.launch {
            val item = withContext(Dispatchers.IO) { load(uri) }
            if (item == null) {
                discarded++
                processNext()
            } else {
                _state.value = ImportUiState.Confirming(item, processedCount, batchTotal)
            }
        }
    }

    /** Save the (possibly corrected) detections for the current screenshot (R5). */
    fun save(item: ImportItem, pageNumber: Int?, detections: List<Detection>) {
        viewModelScope.launch {
            val today = LocalDate.now().toEpochDay()
            val result = mistakes.recordImport(
                contentHash = item.hash,
                pageNumber = pageNumber,
                detections = detections,
                epochDay = today,
                nowEpochMillis = System.currentTimeMillis(),
                thumbnailPath = withContext(Dispatchers.IO) { saveThumbnail(item) },
            )
            when (result) {
                ImportResult.DuplicateImage -> duplicates++
                is ImportResult.Saved -> {
                    saved++
                    // Convert lapse signals into FSRS lapses right away (U7 invariant 4).
                    for (ref in result.lapsedSpots + result.reactivatedSpots) {
                        policy.applyOccurrenceLapse(encodeWordRef(ref), today)
                    }
                }
            }
            processNext()
        }
    }

    /** "Discard — not a Tarteel page" exit (I3). */
    fun discard() {
        discarded++
        processNext()
    }

    private fun load(uri: Uri): ImportItem? {
        return try {
            loadUnsafe(uri)
        } catch (_: Exception) {
            null
        }
    }

    private fun loadUnsafe(uri: Uri): ImportItem? {
        val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..MAX_DIMENSION_PX || bounds.outHeight !in 1..MAX_DIMENSION_PX) {
            return null // not a plausible screenshot; reject before full decode
        }

        val sample = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / TARGET_MAX_PX)
        val bitmap = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null

        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        return ImportItem(uri, hash, bitmap, pipeline.extract(pixels, bitmap.width, bitmap.height))
    }

    /** Keep a small thumbnail for history; the full image is discarded after confirm (M3). */
    private fun saveThumbnail(item: ImportItem): String? = try {
        val dir = File(appContext.filesDir, "thumbs").apply { mkdirs() }
        val file = File(dir, "${item.hash}.jpg")
        val scale = 200f / maxOf(item.bitmap.width, item.bitmap.height)
        val thumb = Bitmap.createScaledBitmap(
            item.bitmap,
            (item.bitmap.width * scale).toInt().coerceAtLeast(1),
            (item.bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        file.outputStream().use { thumb.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        file.absolutePath
    } catch (_: Exception) {
        null
    }
}
