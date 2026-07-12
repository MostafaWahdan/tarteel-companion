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
        /** Transient auto-detect failure notice; existing marks are preserved. */
        val detectMessage: String? = null,
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
    private val mistakes: MistakeRepository,
    private val policy: SchedulingPolicy,
    private val pipelineProvider: suspend () -> ExtractionPipeline,
) : ViewModel() {

    /** Batch context: the last confirmed page seeds the next screenshot's hint. */
    private var lastConfirmedPage: Int? = null

    /** Guards save/discard/autoDetect against double taps and stale completions. */
    private var actionInFlight = false

    private val _state = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    val state: StateFlow<ImportUiState> = _state

    private val queue = ArrayDeque<Uri>()
    private var batchTotal = 0
    private var processedCount = 0
    private var saved = 0
    private var discarded = 0
    private var duplicates = 0

    companion object {
        private const val TAG = "ImportViewModel"

        /** Decode-bounds cap: reject absurd inputs before full decode (security FYI). */
        const val MAX_DIMENSION_PX = 8_000

        /** Raw-stream ceiling for shared images — screenshots are well under this. */
        const val MAX_IMPORT_BYTES = 50L * 1_000_000

        /** Working size for extraction/display; screenshots are downsampled to fit. */
        const val TARGET_MAX_PX = 2_000

        fun factory(app: TarteelApp, pipelineProvider: suspend () -> ExtractionPipeline) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ImportViewModel(app, app.mistakes, app.policy, pipelineProvider) as T
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
            val item = withContext(Dispatchers.IO) { load(uri, lastConfirmedPage) }
            if (item == null) {
                discarded++
                processNext()
            } else {
                _state.value = ImportUiState.Confirming(item, processedCount, batchTotal)
            }
        }
    }

    /**
     * Re-anchors the current screenshot against a user-entered page number (U4 flow).
     * A failed detection must NOT destroy an existing extraction or reset the page
     * field — it only surfaces a message (review finding ADV-5). Stale completions
     * are dropped when the screen has moved on to another item (ADV-7).
     */
    fun autoDetect(page: Int) {
        val current = _state.value as? ImportUiState.Confirming ?: return
        if (actionInFlight) return
        actionInFlight = true
        viewModelScope.launch {
            try {
                val pipeline = pipelineProvider()
                val item = current.item
                val result = withContext(Dispatchers.IO) {
                    val pixels = IntArray(item.bitmap.width * item.bitmap.height)
                    item.bitmap.getPixels(pixels, 0, item.bitmap.width, 0, 0, item.bitmap.width, item.bitmap.height)
                    pipeline.anchorAt(pixels, item.bitmap.width, item.bitmap.height, page)
                }
                val latest = _state.value
                if (latest !is ImportUiState.Confirming || latest.item.hash != item.hash) return@launch
                _state.value = when (result) {
                    is ExtractionResult.Extracted ->
                        latest.copy(item = item.copy(extraction = result), detectMessage = null)
                    is ExtractionResult.NeedsManual ->
                        latest.copy(detectMessage = "Auto-detect: ${result.reason}")
                }
            } finally {
                actionInFlight = false
            }
        }
    }

    /** Save the (possibly corrected) detections for the current screenshot (R5). */
    fun save(item: ImportItem, pageNumber: Int?, detections: List<Detection>) {
        if (actionInFlight) return
        val current = _state.value
        if (current !is ImportUiState.Confirming || current.item.hash != item.hash) return
        actionInFlight = true
        viewModelScope.launch {
            try {
                saveInternal(item, pageNumber, detections)
            } finally {
                actionInFlight = false
            }
        }
    }

    private suspend fun saveInternal(item: ImportItem, pageNumber: Int?, detections: List<Detection>) {
        run {
            val today = LocalDate.now().toEpochDay()
            val result = mistakes.recordImport(
                contentHash = item.hash,
                pageNumber = pageNumber,
                detections = detections,
                epochDay = today,
                nowEpochMillis = System.currentTimeMillis(),
                thumbnailPath = withContext(Dispatchers.IO) { saveThumbnail(item) },
            )
            if (pageNumber != null) lastConfirmedPage = pageNumber
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
        if (_state.value !is ImportUiState.Confirming) return // double-tap guard
        discarded++
        processNext()
    }

    private suspend fun load(uri: Uri, pageHint: Int?): ImportItem? {
        return try {
            loadUnsafe(uri, pageHint)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "screenshot load failed for $uri", e)
            null
        }
    }

    private suspend fun loadUnsafe(uri: Uri, pageHint: Int?): ImportItem? {
        // Bounded read: the share target accepts streams from any app, and readBytes()
        // on an unbounded stream would buffer it fully before any size check (OOM).
        val bytes = appContext.contentResolver.openInputStream(uri)?.use { stream ->
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = stream.read(chunk)
                if (read < 0) break
                total += read
                if (total > MAX_IMPORT_BYTES) {
                    android.util.Log.w(TAG, "stream exceeds ${MAX_IMPORT_BYTES / 1_000_000}MB for $uri")
                    return null
                }
                buffer.write(chunk, 0, read)
            }
            buffer.toByteArray()
        }
        if (bytes == null) {
            android.util.Log.w(TAG, "no stream for $uri")
            return null
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..MAX_DIMENSION_PX || bounds.outHeight !in 1..MAX_DIMENSION_PX) {
            android.util.Log.w(TAG, "implausible dimensions ${bounds.outWidth}x${bounds.outHeight} for $uri")
            return null // not a plausible screenshot; reject before full decode
        }

        val sample = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / TARGET_MAX_PX)
        val bitmap = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
        if (bitmap == null) {
            android.util.Log.w(TAG, "bitmap decode failed for $uri")
            return null
        }

        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        val extraction = pipelineProvider().extract(pixels, bitmap.width, bitmap.height, pageHint)
        return ImportItem(uri, hash, bitmap, extraction)
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
