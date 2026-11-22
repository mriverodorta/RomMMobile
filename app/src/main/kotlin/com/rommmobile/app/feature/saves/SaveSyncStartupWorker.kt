package com.rommmobile.app.feature.saves

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rommmobile.app.core.util.FileLogger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** Restore saves after boot and cover an unavailable foreground watcher. */
@HiltWorker
class SaveSyncStartupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val control: SaveSyncControl,
    private val engine: SaveSyncEngine,
    private val log: FileLogger,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!control.enabled.first()) return Result.success()
        if (SaveSyncControl.PERIODIC_WORK in tags && control.status.value.running) return Result.success()
        return try {
            val scan = engine.run(apply = false) {}
            if (scan.failed > 0 && runAttemptCount < 3) Result.retry() else Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w("SaveSync", "Startup save sync failed", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
