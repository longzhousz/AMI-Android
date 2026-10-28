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
        val account = inputData.getString("account") ?: return@withContext Result.success()
        (applicationContext as AmiApplication).repository.cloud.fillAudioCache(account)
        Result.success()
    }
    companion object {
        private const val NAME = "online-audio-cache"
        internal fun enqueue(context: Context, account: String) {
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<OnlineCacheWorker>()
                    .setInputData(workDataOf("account" to account))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresStorageNotLow(true).build())
                    .build())
        }
        internal fun cancel(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(NAME) }
    }
}
