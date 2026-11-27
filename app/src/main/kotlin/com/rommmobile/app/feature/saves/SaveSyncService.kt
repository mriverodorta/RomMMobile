package com.rommmobile.app.feature.saves

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.FileObserver
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.rommmobile.app.MainActivity
import com.rommmobile.app.R
import com.rommmobile.app.core.network.ApiException
import com.rommmobile.app.core.network.ApiErrorKind
import com.rommmobile.app.core.util.FileLogger
import com.rommmobile.app.data.prefs.SettingsStore
import com.rommmobile.app.data.prefs.SaveSyncMethod
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class SaveSyncStatus(val running: Boolean = false, val result: SaveSyncResult? = null, val error: String? = null)

@Singleton
class SaveSyncControl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: SettingsStore,
    private val engine: SaveSyncEngine,
    private val log: FileLogger,
) {
    private val _status = MutableStateFlow(SaveSyncStatus())
    val status = _status.asStateFlow()
    val method = settings.saveSyncMethod
    val enabled = settings.saveSyncEnabled
    val batteryEnabled = settings.syncBatterySaves
    val statesEnabled = settings.syncSaveStates
    val excludeRetroArch = settings.excludeRetroArch

    suspend fun setExcludeRetroArch(excluded: Boolean) = methodLock.withLock {
        engine.setExcludeRetroArch(excluded)
        syncNow()
    }

    suspend fun setSaveTypeEnabled(state: Boolean, on: Boolean) {
        settings.setSaveTypeEnabled(state, on)
        syncNow()
    }

    internal suspend fun selectEmulatorFolder(key: String, state: Boolean, path: String): Boolean =
        engine.selectEmulatorFolder(key, state, path)

    internal fun gameIdOverride(profile: String, hash: String): String? = engine.gameIdOverride(profile, hash)

    internal suspend fun setGameIdOverride(profile: String, hash: String, id: String?) {
        engine.setGameIdOverride(profile, hash, id)
        syncNow()
    }

    suspend fun romDownloaded() {
        syncNow()
    }

    internal fun report(status: SaveSyncStatus) { _status.value = status }
    private val workManager get() = WorkManager.getInstance(context)
    private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    private val methodLock = Mutex()

    suspend fun setMethod(value: SaveSyncMethod) = methodLock.withLock {
        engine.setMethod(value)
        if (value == SaveSyncMethod.ROMMMOBILES) {
            startIfEnabled()
        } else {
            context.stopService(Intent(context, SaveSyncService::class.java))
            workManager.cancelUniqueWork(STARTUP_WORK)
            workManager.cancelUniqueWork(PERIODIC_WORK)
            report(SaveSyncStatus())
        }
    }

    suspend fun startAfterBoot() {
        if (!enabled.first()) return
        val request = OneTimeWorkRequestBuilder<SaveSyncStartupWorker>()
            .setConstraints(connected)
            .setInitialDelay(3, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(STARTUP_WORK, ExistingWorkPolicy.KEEP, request)
        scheduleFallback()
        start()
    }

    suspend fun startIfEnabled() {
        if (!enabled.first()) return
        scheduleFallback()
        start()
    }

    private fun scheduleFallback() {
        val request = PeriodicWorkRequestBuilder<SaveSyncStartupWorker>(15, TimeUnit.MINUTES)
            .setConstraints(connected)
            .setInitialDelay(15, TimeUnit.MINUTES)
            .addTag(PERIODIC_WORK)
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    suspend fun syncNow(apply: Boolean = false) {
        if (enabled.first()) start(if (apply) SaveSyncService.APPLY else SaveSyncService.SCAN)
    }

    companion object {
        private const val STARTUP_WORK = "save-sync-startup"
        internal const val PERIODIC_WORK = "save-sync-periodic"
    }

    private fun start(action: String? = null) {
        try {
            ContextCompat.startForegroundService(context, Intent(context, SaveSyncService::class.java).setAction(action))
        } catch (e: IllegalStateException) {
            log.w("SaveSync", "Android did not allow the background service to start", e)
            report(SaveSyncStatus(error = context.getString(R.string.save_sync_start_failed)))
        }
    }
}

@AndroidEntryPoint
class SaveSyncService : Service() {
    @Inject lateinit var engine: SaveSyncEngine
    @Inject lateinit var control: SaveSyncControl
    @Inject lateinit var log: FileLogger

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val scans = Channel<Unit>(Channel.CONFLATED)
    private val observers = mutableMapOf<String, FileObserver>()
    private val documentObservers = mutableMapOf<Uri, ContentObserver>()
    private val applyNext = java.util.concurrent.atomic.AtomicBoolean(false)
    private lateinit var notifications: NotificationManager
    private var watchJob: Job? = null
    private lateinit var connectivity: ConnectivityManager
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scans.trySend(Unit)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        connectivity = getSystemService(ConnectivityManager::class.java)
        connectivity.registerDefaultNetworkCallback(networkCallback)
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.save_sync_title), NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android's battery settings can remove foreground status while keeping this instance alive.
        // Each foreground start request must promote it again, including a restart from onResume.
        ServiceCompat.startForeground(this, NOTIFICATION, notification(getString(R.string.save_sync_watching)),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        if (intent?.action == APPLY) applyNext.set(true)
        scans.trySend(Unit)
        if (watchJob?.isActive != true) watchJob = scope.launch { watchLoop() }
        return START_STICKY
    }

    private suspend fun watchLoop() {
        while (scope.isActive && control.enabled.first()) {
            control.report(SaveSyncStatus(running = true, result = control.status.value.result))
            try {
                val result = engine.run(applyNext.getAndSet(false), ::replaceWatchers)
                control.report(SaveSyncStatus(running = true, result = result))
                notifications.notify(NOTIFICATION, notification(getString(R.string.save_sync_notification,
                    result.watchedFolders, result.staged, result.conflicts)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w("SaveSync", "Save sync will retry", e)
                val message = when {
                    e is ApiException && e.kind == ApiErrorKind.FORBIDDEN -> getString(R.string.save_sync_missing_scopes)
                    e is ApiException -> e.userMessage(this)
                    else -> e.message ?: getString(R.string.save_sync_start_failed)
                }
                control.report(SaveSyncStatus(running = true, error = message))
                notifications.notify(NOTIFICATION, notification(message))
            }
            // Events request a scan. A periodic scan also finds missed events and server changes.
            withTimeoutOrNull(120_000) { scans.receive() }
            delay(4_000)
            while (scans.tryReceive().isSuccess) Unit
        }
        stopSelf()
    }

    @Synchronized
    @Suppress("DEPRECATION")
    private fun replaceWatchers(watched: Set<SyncWatchFolder>) {
        val uris = watched.mapNotNull { it.uri }.toSet()
        for (removed in documentObservers.keys - uris) {
            documentObservers.remove(removed)?.let(contentResolver::unregisterContentObserver)
        }
        for (uri in uris - documentObservers.keys) {
            val observer = object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) {
                    scans.trySend(Unit)
                }
            }
            try {
                contentResolver.registerContentObserver(uri, true, observer)
                documentObservers[uri] = observer
            } catch (e: SecurityException) {
                log.w("SaveSync", "Could not observe selected folder; scans remain enabled", e)
            }
        }
        val folders = watched.mapNotNull { it.path }.toSet()
        for (removed in observers.keys - folders) observers.remove(removed)?.stopWatching()
        for (folder in folders - observers.keys) {
            val watcher = object : FileObserver(folder, CREATE or CLOSE_WRITE or MOVED_TO or MOVED_FROM or DELETE or DELETE_SELF or MOVE_SELF) {
                override fun onEvent(event: Int, path: String?) {
                    if (event and (DELETE_SELF or MOVE_SELF) != 0) {
                        synchronized(this@SaveSyncService) {
                            if (observers[folder] === this) observers.remove(folder)
                        }
                        stopWatching()
                    }
                    if (path == null || !path.startsWith(".")) scans.trySend(Unit)
                }
            }
            observers[folder] = watcher
            watcher.startWatching()
        }
    }

    private fun notification(message: String) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setContentTitle(getString(R.string.save_sync_title))
        .setContentText(message)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .build()

    override fun onDestroy() {
        connectivity.unregisterNetworkCallback(networkCallback)
        scope.cancel()
        replaceWatchers(emptySet())
        control.report(SaveSyncStatus())
        super.onDestroy()
    }

    companion object {
        internal const val APPLY = "com.rommmobile.save_sync.APPLY"
        internal const val SCAN = "com.rommmobile.save_sync.SCAN"
        private const val CHANNEL = "romm-save-sync"
        private const val NOTIFICATION = 105
    }
}
