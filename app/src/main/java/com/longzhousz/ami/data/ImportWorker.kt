package com.longzhousz.ami.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import com.longzhousz.ami.AmiApplication
import com.longzhousz.ami.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock

class ImportWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    private val repository get() = (applicationContext as AmiApplication).repository
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("import", "曲库导入", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, "import").setSmallIcon(R.drawable.ic_music)
            .setContentTitle("AMI 正在整理曲库").setContentText("可以离开此页面，导入将在后台继续").setOngoing(true)
            .addAction(0, "取消", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)).build()
        return if (android.os.Build.VERSION.SDK_INT >= 29) ForegroundInfo(17, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(17, notification)
    }
    override suspend fun doWork(): Result = repository.importLock.withLock {
        try {
            setForeground(getForegroundInfo())
            repository.importState.value = ImportState(running = true, message = "正在扫描文件夹…")
            withContext(Dispatchers.IO) { LibraryScanner(applicationContext, repository).scan(repository.roots(), repository.lyricRoots()) }
            Result.success()
        } catch (e: CancellationException) {
            repository.importState.value = repository.importState.value.copy(running = false, message = "已取消，已完成的曲目已保存")
            throw e
        } catch (_: Exception) {
            repository.importState.value = ImportState(message = "导入中断，请重新扫描；已完成的曲目已保存")
            Result.failure()
        }
    }
}
