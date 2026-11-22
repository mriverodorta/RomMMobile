package com.rommmobile.app.feature.saves

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

fun hasEmulatorUsageAccess(context: Context): Boolean =
    context.getSystemService(AppOpsManager::class.java)
        .checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) ==
        AppOpsManager.MODE_ALLOWED

fun openEmulatorUsageSettings(context: Context) {
    val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
    try {
        context.startActivity(intent.setData(Uri.parse("package:${context.packageName}")))
    } catch (_: android.content.ActivityNotFoundException) {
        context.startActivity(intent.setData(null))
    }
}

internal fun canRestoreBeforeFirstLaunch(now: Long, boot: Long, startup: Long, resumed: Long, stopped: Long) =
    resumed == 0L && stopped == 0L && startup > 0L && startup >= boot - 1_000L && now - startup >= 3_000L

@Singleton
class RetroArchExit @Inject constructor(@param:ApplicationContext private val context: Context) {
    private val confirmedStops = mutableMapOf<String, Long>()

    fun canRestore(packageName: String, autoSaveTimes: () -> List<Long>): Boolean {
        if (!packageName.startsWith("com.retroarch") || !hasEmulatorUsageAccess(context)) return false
        val now = System.currentTimeMillis()
        val boot = now - SystemClock.elapsedRealtime()
        val events = try {
            context.getSystemService(UsageStatsManager::class.java)
                .queryEvents(maxOf(0L, boot - 1_000L), now)
        } catch (_: SecurityException) {
            null
        } ?: return false
        val event = UsageEvents.Event()
        var resumed = 0L
        var stopped = 0L
        var startup = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.DEVICE_STARTUP) startup = event.timeStamp
            if (event.packageName != packageName ||
                !event.className.orEmpty().startsWith("com.retroarch.browser.retroactivity.RetroActivity")
            ) continue
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> resumed = event.timeStamp
                UsageEvents.Event.ACTIVITY_STOPPED -> stopped = event.timeStamp
            }
        }
        // A startup event establishes coverage before RetroArch's first launch.
        if (resumed == 0L) return canRestoreBeforeFirstLaunch(now, boot, startup, resumed, stopped)
        if (stopped < resumed || now - stopped < 3_000) return false
        if (confirmedStops[packageName] == stopped) return true

        // STOPPED also follows Home. Require the exit autosave from this same activity session.
        val firstWrite = maxOf(resumed, stopped - 5_000)
        val lastWrite = stopped + 2_000
        val autosaved = autoSaveTimes().any { it in firstWrite..lastWrite }
        if (autosaved) confirmedStops[packageName] = stopped
        return autosaved
    }
}
