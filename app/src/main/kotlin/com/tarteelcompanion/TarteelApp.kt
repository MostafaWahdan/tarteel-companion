package com.tarteelcompanion

import android.app.Application
import android.net.Uri
import com.tarteelcompanion.data.AppDatabase
import com.tarteelcompanion.data.MistakeRepository
import com.tarteelcompanion.mnemonics.ApiKeyStore
import com.tarteelcompanion.mnemonics.GeminiClient
import com.tarteelcompanion.mnemonics.MnemonicRepository
import com.tarteelcompanion.quran.AndroidQuranAssetReader
import com.tarteelcompanion.quran.QuranRepository
import com.tarteelcompanion.srs.SchedulingPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow

/** Composition root: single instances of the DB, repositories, and scheduler. */
class TarteelApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: AppDatabase by lazy { AppDatabase.get(this) }
    val mistakes: MistakeRepository by lazy { MistakeRepository(database) }
    val policy: SchedulingPolicy by lazy { SchedulingPolicy(database) }
    val apiKeyStore: ApiKeyStore by lazy { ApiKeyStore(this) }

    /** Screenshots arriving via the share sheet, consumed by the Import screen (I4). */
    val pendingShares = MutableStateFlow<List<Uri>>(emptyList())
    val mnemonicRepo: MnemonicRepository by lazy {
        MnemonicRepository(database.mnemonicDao(), apiKeyStore, GeminiClient())
    }

    private val quranLock = Any()
    private var quranDeferred: Deferred<QuranRepository>? = null

    @Volatile
    private var pipelineCache: com.tarteelcompanion.extraction.AnchoringPipeline? = null

    /**
     * One pipeline instance per process: PageMatcher tokenizes all 604 pages lazily,
     * and rebuilding it per screenshot re-paid that cost on every image in a batch
     * (review finding perf-1).
     */
    suspend fun extractionPipeline(): com.tarteelcompanion.extraction.ExtractionPipeline =
        pipelineCache ?: com.tarteelcompanion.extraction.AnchoringPipeline(quran().await())
            .also { pipelineCache = it }

    /**
     * The Quran dataset parses once, off the main thread, at first request. A failed
     * parse is NOT cached: retrying returns a fresh attempt instead of rethrowing the
     * poisoned Deferred forever (review finding REL-1).
     */
    fun quran(): Deferred<QuranRepository> = synchronized(quranLock) {
        val current = quranDeferred
        val failed = current != null && current.isCompleted && current.getCompletionExceptionOrNull() != null
        if (current != null && !failed) return current
        appScope.async { QuranRepository.load(AndroidQuranAssetReader(this@TarteelApp)) }
            .also { quranDeferred = it }
    }
}
