package com.outline.notes.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.outline.notes.OutlineApp
import java.util.concurrent.TimeUnit

class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? OutlineApp ?: return Result.failure()
        val settings = app.preferences.getSettings()
        if (!settings.isConfigured) return Result.success()

        return when (app.syncEngine.syncNow(silent = true)) {
            is SyncResult.Success -> Result.success()
            is SyncResult.SkippedOffline -> Result.retry()
            is SyncResult.Error -> {
                if (runAttemptCount < 4) Result.retry() else Result.failure()
            }
        }
    }

    companion object {
        private const val WORK_NAME = "outline_auto_sync"
        private const val IDLE_EDIT_WORK_NAME = "outline_idle_edit_sync"
        private const val PERIODIC_WORK_NAME = "outline_periodic_sync"

        /**
         * Enqueues a sync without cancelling an already-running sync mid-HTTP-push
         * (prevents killing in-flight documents.update before local serverBaseRevision updates).
         */
        fun scheduleWhenConnected(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 20, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }

        /**
         * Debounced idle-edit sync (default 10s after last keystroke) so rapid typing never
         * triggers overlapping uploads against itself, while still auto-uploading if the user pauses.
         */
        fun scheduleIdleEditSync(context: Context, delaySeconds: Long = 10L) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 20, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                IDLE_EDIT_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        fun cancelIdleEditSync(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(IDLE_EDIT_WORK_NAME)
        }

        fun configurePeriodicSync(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(PERIODIC_WORK_NAME)
                return
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val periodic = PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

            wm.enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodic,
            )
        }
    }
}
