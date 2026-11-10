package app.familycloud.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.familycloud.FamilyCloudApp
import app.familycloud.R
import app.familycloud.data.db.SyncPairEntity
import java.util.concurrent.TimeUnit

class CancelWorkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val workId = intent?.getStringExtra(WORK_ID) ?: return
        WorkManager.getInstance(context).cancelWorkById(java.util.UUID.fromString(workId))
    }

    companion object {
        const val WORK_ID = "work_id"
    }
}

abstract class CloudWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    protected suspend fun tryForeground(info: ForegroundInfo): Boolean = try {
        setForeground(info)
        true
    } catch (_: IllegalStateException) {
        false
    }
}

class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CloudWorker(context, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo {
        return foreground(applicationContext.getString(R.string.sync_working))
    }

    override suspend fun doWork(): Result {
        val app = applicationContext as FamilyCloudApp
        app.container.settings.load()
        val pairId = inputData.getLong(PAIR, -1L).takeIf { it >= 0L }
        val bypass = inputData.getBoolean(BYPASS, false)
        var foregroundAllowed = true
        val failure = app.container.sync.runSync(pairId, bypass) { done, total ->
            if (foregroundAllowed) {
                val info = foreground(applicationContext.getString(R.string.sync_progress, done, total))
                foregroundAllowed = tryForeground(info)
            }
        }
        return if (failure?.retryable == true) Result.retry() else Result.success()
    }

    private fun foreground(text: String): ForegroundInfo {
        val notification = syncNotification(applicationContext, text, id, cancellable = inputData.getBoolean(BYPASS, false))
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val PAIR = "pair"
        const val BYPASS = "bypass"
        private const val NOTIFICATION_ID = 41
    }
}

class UploadWorker(
    context: Context,
    params: WorkerParameters,
) : CloudWorker(context, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo = foreground(applicationContext.getString(R.string.sync_working))

    override suspend fun doWork(): Result {
        val app = applicationContext as FamilyCloudApp
        app.container.settings.load()
        var foregroundAllowed = true
        val failure = app.container.sync.runUploads { done, total ->
            if (foregroundAllowed) {
                foregroundAllowed = tryForeground(foreground(applicationContext.getString(R.string.sync_progress, done, total)))
            }
        }
        return if (failure?.retryable == true) Result.retry() else Result.success()
    }

    private fun foreground(text: String): ForegroundInfo {
        val notification = syncNotification(applicationContext, text, id, cancellable = true)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(42, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(42, notification)
        }
    }
}

class MediaWatchWorker(
    context: Context,
    params: WorkerParameters,
) : CloudWorker(context, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo {
        return foreground(applicationContext.getString(R.string.sync_working))
    }

    override suspend fun doWork(): Result {
        val app = applicationContext as FamilyCloudApp
        app.container.settings.load()
        var foregroundAllowed = true
        val failure = try {
            app.container.sync.runSync(null, bypassConstraints = false) { done, total ->
                if (foregroundAllowed) {
                    foregroundAllowed = tryForeground(foreground(applicationContext.getString(R.string.sync_progress, done, total)))
                }
            }
        } finally {
            SyncScheduler.watchMedia(applicationContext, ExistingWorkPolicy.APPEND)
        }
        return if (failure?.retryable == true) Result.retry() else Result.success()
    }

    private fun foreground(text: String): ForegroundInfo {
        val notification = syncNotification(applicationContext, text, id, cancellable = false)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(43, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(43, notification)
        }
    }
}

object SyncScheduler {
    fun refresh(context: Context, pairs: List<SyncPairEntity>) {
        val manager = WorkManager.getInstance(context)
        pairs.forEach { pair ->
            val name = "sync-${pair.id}"
            if (!pair.enabled) {
                manager.cancelUniqueWork(name)
                return@forEach
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (pair.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresCharging(pair.chargingOnly)
                .build()
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setInputData(workDataOf(SyncWorker.PAIR to pair.id, SyncWorker.BYPASS to false))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            manager.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
        watchMedia(context, ExistingWorkPolicy.KEEP)
    }

    fun watchMedia(context: Context, policy: ExistingWorkPolicy) {
        val constraints = Constraints.Builder()
            .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
            .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
            .setTriggerContentUpdateDelay(java.time.Duration.ofSeconds(30))
            .setTriggerContentMaxDelay(java.time.Duration.ofMinutes(2))
            .build()
        val request = OneTimeWorkRequestBuilder<MediaWatchWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("media-watch", policy, request)
    }

    fun syncNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf(SyncWorker.BYPASS to true))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("sync-now", ExistingWorkPolicy.REPLACE, request)
    }

    fun enqueueUploads(context: Context) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("uploads", ExistingWorkPolicy.KEEP, request)
    }
}

internal fun syncNotification(
    context: Context,
    text: String,
    workId: java.util.UUID,
    cancellable: Boolean,
): android.app.Notification {
    val channelId = "sync"
    val manager = context.getSystemService(NotificationManager::class.java)
    val channel = NotificationChannel(
        channelId,
        context.getString(R.string.sync_channel),
        NotificationManager.IMPORTANCE_LOW,
    )
    manager?.createNotificationChannel(channel)
    val builder = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(R.drawable.ic_stat_cloud)
        .setContentTitle(context.getString(R.string.sync_notification_title))
        .setContentText(text)
        .setOngoing(true)
    if (cancellable) {
        val cancel = PendingIntent.getBroadcast(
            context,
            workId.hashCode(),
            Intent(context, CancelWorkReceiver::class.java).putExtra(CancelWorkReceiver.WORK_ID, workId.toString()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        builder.addAction(0, context.getString(R.string.cancel), cancel)
    }
    return builder.build()
}
