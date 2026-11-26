package com.simpleefb.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.simpleefb.EfbApp
import com.simpleefb.store.Settings
import java.util.concurrent.TimeUnit

class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = (applicationContext as EfbApp).graph
        // Long downloads (offline charts) run as a foreground job when Android allows it.
        try {
            setForeground(foregroundInfo("Updating charts…"))
        } catch (e: Exception) {
            // Not allowed from the background on newer Android versions; continue as a regular job.
        }
        val ok = graph.updater.run()
        return if (ok) Result.success() else Result.retry()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Updating charts…")

    private fun foregroundInfo(text: String): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Chart updates", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("SimpleEFB")
            .setContentText(text)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL = "updates"
        private const val NOTIFICATION_ID = 42
        private const val PERIODIC = "auto-update"
        private const val NOW = "update-now"

        /** (Re)schedules the twice-daily background update according to the settings. */
        fun schedule(context: Context, settings: Settings) {
            val wm = WorkManager.getInstance(context)
            if (!settings.autoUpdate.value) {
                wm.cancelUniqueWork(PERIODIC)
                return
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (settings.wifiOnly.value) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresStorageNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(12, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /** Runs an update as soon as there is any network connection. */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<UpdateWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
        }
    }
}
