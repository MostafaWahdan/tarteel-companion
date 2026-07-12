package com.tarteelcompanion.mnemonics

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tarteelcompanion.TarteelApp
import java.util.concurrent.TimeUnit

/**
 * Fills PENDING mnemonics via the LLM. Retryable conditions (offline, 429, 5xx, no key
 * yet) back off and retry; non-retryable ones were already marked FAILED with a visible
 * reason by the repository, so they never loop (plan U10 error classification).
 */
class GenerationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as TarteelApp
        val done = app.mnemonicRepo.generatePending(app.quran.await())
        return if (done) Result.success() else Result.retry()
    }

    companion object {
        private const val WORK_NAME = "mnemonic-generation"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<GenerationWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
