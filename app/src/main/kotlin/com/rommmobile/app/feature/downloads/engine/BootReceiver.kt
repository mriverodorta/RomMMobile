package com.rommmobile.app.feature.downloads.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.rommmobile.app.di.ApplicationScope
import com.rommmobile.app.feature.saves.SaveSyncControl
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/** After a reboot or an app update, anything still queued goes back to work. */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var engine: DownloadEngine
    @Inject lateinit var saveSync: SaveSyncControl
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, "android.intent.action.QUICKBOOT_POWERON" -> {
                engine.resumeAfterProcessStart()
                val pending = goAsync()
                appScope.launch {
                    try {
                        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                            saveSync.startIfEnabled()
                        } else {
                            saveSync.startAfterBoot()
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}
