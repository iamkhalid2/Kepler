package app.betterbooks.engine.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.betterbooks.core.model.BookId
import app.betterbooks.core.model.NarrationConfig
import app.betterbooks.core.model.NarrationState
import app.betterbooks.core.model.VoiceRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** Schedules the whole-book job. The default implementation uses WorkManager. */
interface NarrationScheduler {
    fun enqueue(bookId: BookId, config: NarrationConfig, fromOrdinal: Int, delaySeconds: Long = 0)
    fun cancel(bookId: BookId)
}

class WorkManagerScheduler(private val context: Context) : NarrationScheduler {
    override fun enqueue(bookId: BookId, config: NarrationConfig, fromOrdinal: Int, delaySeconds: Long) {
        val network = if (config.voice.provider == SystemTtsProvider.ID) NetworkType.NOT_REQUIRED else NetworkType.CONNECTED
        val request = OneTimeWorkRequestBuilder<NarrationWorker>()
            .setInputData(
                workDataOf(
                    NarrationWorker.KEY_BOOK to bookId,
                    NarrationWorker.KEY_PROVIDER to config.voice.provider,
                    NarrationWorker.KEY_VOICE_ID to config.voice.id,
                    NarrationWorker.KEY_VOICE_NAME to config.voice.name,
                    NarrationWorker.KEY_SPEED to config.speed,
                    NarrationWorker.KEY_FROM to fromOrdinal,
                ),
            )
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).setRequiresBatteryNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .addTag(NarrationWorker.TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(uniqueName(bookId), ExistingWorkPolicy.REPLACE, request)
    }

    override fun cancel(bookId: BookId) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueName(bookId))
    }

    companion object {
        fun uniqueName(bookId: BookId) = "narration-$bookId"
    }
}

/**
 * Whole-book generation under a dataSync foreground service. Progress is checkpointed per segment
 * (each finished segment is in the AudioRepository and on disk), so a rerun resumes without repeating
 * requests. When Android 15+ stops the service at its time limit the worker exits cleanly and
 * WorkManager reschedules it.
 */
class NarrationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val bookId: BookId get() = inputData.getLong(KEY_BOOK, -1)

    override suspend fun doWork(): Result {
        val engine = NarrationEngine.instance ?: return Result.retry()
        val config = NarrationConfig(
            voice = VoiceRef(
                inputData.getString(KEY_PROVIDER) ?: return Result.failure(),
                inputData.getString(KEY_VOICE_ID) ?: return Result.failure(),
                inputData.getString(KEY_VOICE_NAME).orEmpty(),
            ),
            speed = inputData.getFloat(KEY_SPEED, 1f),
        )
        // The system may refuse a foreground start (background start limits, exhausted dataSync budget).
        // The job still runs, checkpointed, until WorkManager stops it.
        runCatching { setForeground(foregroundInfo(0, 0)) }

        val from = inputData.getInt(KEY_FROM, 0)
        return try {
            when (val r = engine.runJob(bookId, config, from)) {
                NarrationEngine.JobResult.Done, NarrationEngine.JobResult.Paused -> Result.success()
                is NarrationEngine.JobResult.Failed -> if (r.retry && runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
            }
        } catch (e: CancellationException) {
            when (stopReason) {
                WorkInfo.STOP_REASON_CANCELLED_BY_APP -> Unit
                // Android 15+ dataSync time limit: stop cleanly and come back later. WorkManager may not
                // reschedule a timed-out foreground worker by itself, so queue the continuation explicitly.
                WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT ->
                    engine.onJobInterrupted(bookId, config, from, NarrationEngine.REASON_SYSTEM, TIMEOUT_RESUME_SECONDS)
                else -> engine.onJobInterrupted(bookId, config, from, NarrationEngine.REASON_SYSTEM, null)
            }
            throw e
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val running = NarrationEngine.instance?.state(bookId)?.first() as? NarrationState.Running
        return foregroundInfo(running?.doneSentences ?: 0, running?.totalSentences ?: 0)
    }

    private fun foregroundInfo(done: Int, total: Int): ForegroundInfo {
        val ctx = applicationContext
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, ctx.getString(R.string.narration_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification: Notification = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(ctx.getString(R.string.narration_generating))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(total, done, total == 0)
            .addAction(0, ctx.getString(R.string.narration_stop), WorkManager.getInstance(ctx).createCancelPendingIntent(id))
            .build()
        return ForegroundInfo(NOTIFICATION_BASE + (bookId % 10_000).toInt(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val TAG = "narration"
        const val KEY_BOOK = "bookId"
        const val KEY_PROVIDER = "provider"
        const val KEY_VOICE_ID = "voiceId"
        const val KEY_VOICE_NAME = "voiceName"
        const val KEY_SPEED = "speed"
        const val KEY_FROM = "from"
        private const val CHANNEL = "narration"
        private const val NOTIFICATION_BASE = 41_000
        private const val MAX_RETRIES = 8
        private const val TIMEOUT_RESUME_SECONDS = 15 * 60L
    }
}
