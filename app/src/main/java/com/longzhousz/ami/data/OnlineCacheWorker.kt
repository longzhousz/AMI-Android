package com.longzhousz.ami.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.longzhousz.ami.AmiApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One short batch when useful; no periodic wakeups, foreground service or cache UI. */
class OnlineCacheWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as AmiApplication
        val repository = app.repository
        if (!repository.canMaintainCaches) return@withContext Result.success()
        // An ordinary buffer top-up must not throw away a mostly downloaded large file.
        // Only foreground UI, a real playback stall, or import interrupts an active copy.
        inputData.getString("account")?.let { repository.cloud.fillAudioCache(it) { repository.canDownloadCache } }
        Result.success()
    }
    companion object {
        private const val NAME = "online-audio-cache"
        internal fun enqueue(context: Context, account: String) {
            if (!(context.applicationContext as AmiApplication).repository.canMaintainCaches) return
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<OnlineCacheWorker>()
                    .setInputData(workDataOf("account" to account))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresStorageNotLow(true).setRequiresBatteryNotLow(true).build())
                    .build())
        }
        internal fun cancel(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(NAME) }
    }
}
