package com.tarteelcompanion

import android.app.Application
import com.tarteelcompanion.data.AppDatabase
import com.tarteelcompanion.data.MistakeRepository
import com.tarteelcompanion.quran.AndroidQuranAssetReader
import com.tarteelcompanion.quran.QuranRepository
import com.tarteelcompanion.srs.SchedulingPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/** Composition root: single instances of the DB, repositories, and scheduler. */
class TarteelApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: AppDatabase by lazy { AppDatabase.get(this) }
    val mistakes: MistakeRepository by lazy { MistakeRepository(database) }
    val policy: SchedulingPolicy by lazy { SchedulingPolicy(database) }

    /** The Quran dataset parses once, off the main thread, at first request. */
    val quran: Deferred<QuranRepository> by lazy {
        appScope.async { QuranRepository.load(AndroidQuranAssetReader(this@TarteelApp)) }
    }
}
