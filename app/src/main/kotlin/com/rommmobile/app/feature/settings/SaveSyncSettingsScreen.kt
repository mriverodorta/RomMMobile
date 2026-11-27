package com.rommmobile.app.feature.settings

import android.content.Context
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rommmobile.app.R
import com.rommmobile.app.core.design.RommTopBar
import com.rommmobile.app.core.design.SettingRow
import com.rommmobile.app.core.network.ServerStore
import com.rommmobile.app.core.input.rightStickScroll
import com.rommmobile.app.core.storage.StorageLocations
import com.rommmobile.app.data.prefs.SaveSyncMethod
import com.rommmobile.app.feature.saves.EmulatorAccess
import com.rommmobile.app.feature.saves.SaveSyncControl
import com.rommmobile.app.feature.saves.hasEmulatorUsageAccess
import com.rommmobile.app.feature.saves.openEmulatorUsageSettings
import com.rommmobile.app.feature.saves.retroArchCloudSyncUrl
import com.rommmobile.app.feature.saves.retroArchLaunchIntent
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SaveSyncSettingsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val saveSync: SaveSyncControl,
    server: ServerStore,
) : ViewModel() {
    val serverConfig = server.config
    val syncMethod = saveSync.method
    val batteryEnabled = saveSync.batteryEnabled
    val statesEnabled = saveSync.statesEnabled
    val excludeRetroArch = saveSync.excludeRetroArch

    fun setExcludeRetroArch(excluded: Boolean) = viewModelScope.launch {
        saveSync.setExcludeRetroArch(excluded)
    }

    fun setSaveTypeEnabled(state: Boolean, enabled: Boolean) = viewModelScope.launch {
        saveSync.setSaveTypeEnabled(state, enabled)
    }

    fun exportRecovery(uri: Uri) = viewModelScope.launch {
        val message = withContext(Dispatchers.IO) {
            runCatching {
                val directory = File(context.filesDir, "save-sync-backups")
                val files = directory.walkTopDown().filter(File::isFile).toList()
                val output = checkNotNull(context.contentResolver.openOutputStream(uri))
                ZipOutputStream(output).use { zip ->
                    for (file in files) {
                        zip.putNextEntry(ZipEntry(file.relativeTo(directory).invariantSeparatorsPath))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
                if (files.isEmpty()) R.string.save_sync_recovery_empty else R.string.save_sync_recovery_saved
            }.getOrDefault(R.string.save_sync_recovery_failed)
        }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
    val syncStatus = saveSync.status

    fun setSyncMethod(method: SaveSyncMethod) = viewModelScope.launch {
        saveSync.setMethod(method)
    }

    fun syncNow(apply: Boolean = false) = viewModelScope.launch { saveSync.syncNow(apply) }
}

@Composable
fun SaveSyncSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: SaveSyncSettingsViewModel = hiltViewModel()
    val scroll = rememberScrollState()
    val method by vm.syncMethod.collectAsStateWithLifecycle(initialValue = null)
    var chooseMethod by rememberSaveable { mutableStateOf(false) }
    val exportRecovery = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let(vm::exportRecovery)
    }

    Column(Modifier.fillMaxSize()) {
        RommTopBar(title = stringResource(R.string.settings_save_sync), onBack = onBack)
        Column(Modifier.weight(1f).verticalScroll(scroll).rightStickScroll(scroll).padding(bottom = 24.dp)) {
            method?.let { selected ->
                SettingRow(stringResource(R.string.save_sync_method), subtitle = syncMethodLabel(selected), onClick = { chooseMethod = true })
                if (chooseMethod) {
                    val methods = SaveSyncMethod.entries
                    ChoiceDialog(
                        title = stringResource(R.string.save_sync_method),
                        options = methods.map {
                            syncMethodLabel(it) to if (it == SaveSyncMethod.ROMMMOBILES) stringResource(R.string.save_sync_description) else null
                        },
                        selected = methods.indexOf(selected),
                        onDismiss = { chooseMethod = false },
                    ) {
                        chooseMethod = false
                        vm.setSyncMethod(methods[it])
                    }
                }
                when (selected) {
                    SaveSyncMethod.RETROARCH -> RetroArchCloudSyncSettings(vm)
                    SaveSyncMethod.ROMMMOBILES -> {
                        val excludeRetroArch by vm.excludeRetroArch.collectAsStateWithLifecycle(initialValue = false)
                        SaveSyncSettings(vm, excludeRetroArch)
                        EmulatorAccess(showEsdeSettings = true, excludeRetroArch = excludeRetroArch)
                        if (excludeRetroArch) RetroArchCloudSyncSettings(vm)
                    }
                    SaveSyncMethod.OFF -> Unit
                }
                if (remember(context) { StorageLocations.canOpenTree(context) }) {
                    SettingRow(
                        title = stringResource(R.string.save_sync_recovery_export),
                        subtitle = stringResource(R.string.save_sync_recovery_hint),
                        onClick = { exportRecovery.launch("romm-save-recovery.zip") },
                    )
                }
            }
        }
    }
}

@Composable
private fun syncMethodLabel(method: SaveSyncMethod) = stringResource(when (method) {
    SaveSyncMethod.ROMMMOBILES -> R.string.save_sync_method_rommmobiles
    SaveSyncMethod.RETROARCH -> R.string.save_sync_method_retroarch
    SaveSyncMethod.OFF -> R.string.save_sync_method_off
})

@Composable
private fun RetroArchCloudSyncSettings(vm: SaveSyncSettingsViewModel) {
    val context = LocalContext.current
    val server by vm.serverConfig.collectAsStateWithLifecycle()
    val url = retroArchCloudSyncUrl(server.activeUrl, server.baseUrl, server.remoteUrl)
    fun showUnavailable() = Toast.makeText(context, R.string.save_sync_retroarch_unavailable, Toast.LENGTH_SHORT).show()
    url?.let { address ->
        SettingRow(stringResource(R.string.save_sync_retroarch_copy), subtitle = address, onClick = {
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("RomM WebDAV", address))
            Toast.makeText(context, R.string.save_sync_retroarch_copied, Toast.LENGTH_SHORT).show()
        })
    }
    Text(stringResource(R.string.save_sync_retroarch_steps),
        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
    SettingRow(stringResource(R.string.save_sync_retroarch_open), onClick = {
        val intent = retroArchLaunchIntent(context)
        if (intent == null) showUnavailable() else try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            showUnavailable()
        }
    })
}

@Composable
private fun SaveSyncSettings(vm: SaveSyncSettingsViewModel, excludeRetroArch: Boolean) {
    val context = LocalContext.current
    val sync by vm.syncStatus.collectAsStateWithLifecycle()
    val batteryEnabled by vm.batteryEnabled.collectAsStateWithLifecycle(initialValue = true)
    val statesEnabled by vm.statesEnabled.collectAsStateWithLifecycle(initialValue = false)
    var allFiles by rememberSaveable { mutableStateOf(StorageLocations.hasAllFilesAccess(context)) }
    var usageGranted by rememberSaveable { mutableStateOf(hasEmulatorUsageAccess(context)) }
    var confirm by rememberSaveable { mutableStateOf(false) }
    val power = context.getSystemService(PowerManager::class.java)
    var batteryExempt by rememberSaveable { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val granted = StorageLocations.hasAllFilesAccess(context)
        if (granted != allFiles) vm.syncNow()
        allFiles = granted
        usageGranted = hasEmulatorUsageAccess(context)
        batteryExempt = power.isIgnoringBatteryOptimizations(context.packageName)
    }
    SettingRow(
        title = stringResource(R.string.save_sync_exclude_retroarch),
        subtitle = stringResource(R.string.save_sync_exclude_retroarch_hint),
        onClick = { vm.setExcludeRetroArch(!excludeRetroArch) },
        trailing = { Switch(checked = excludeRetroArch, onCheckedChange = null) },
    )
    if (!excludeRetroArch) {
        Text(stringResource(R.string.save_sync_use_one_method),
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
    }
    SettingRow(
        title = stringResource(R.string.save_sync_battery_files),
        subtitle = stringResource(R.string.save_sync_battery_files_hint),
        onClick = { vm.setSaveTypeEnabled(false, !batteryEnabled) },
        trailing = { Switch(checked = batteryEnabled, onCheckedChange = null) },
    )
    SettingRow(
        title = stringResource(R.string.save_sync_state_files),
        subtitle = stringResource(R.string.save_sync_state_files_hint),
        onClick = { vm.setSaveTypeEnabled(true, !statesEnabled) },
        trailing = { Switch(checked = statesEnabled, onCheckedChange = null) },
    )
    StorageLocations.allFilesAccessIntent(context)?.let { intent ->
        SettingRow(
            title = stringResource(R.string.save_sync_broad_storage),
            subtitle = stringResource(if (allFiles) R.string.ob_storage_allfiles_ok else R.string.ob_storage_allfiles_missing),
            onClick = { context.startActivity(intent) },
        )
    }
    SettingRow(
        stringResource(R.string.save_sync_battery),
        subtitle = stringResource(if (batteryExempt) R.string.save_sync_battery_exempt else R.string.save_sync_battery_hint),
        onClick = {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)))
        },
    )
    SettingRow(
        title = stringResource(R.string.save_sync_usage_title),
        subtitle = stringResource(if (usageGranted) R.string.save_sync_usage_granted else R.string.save_sync_usage_needed),
        onClick = { openEmulatorUsageSettings(context) },
    )
    val result = sync.result
    val summary = when {
        !batteryEnabled && !statesEnabled -> stringResource(R.string.save_sync_types_off)
        sync.error != null -> sync.error
        result == null && !sync.running -> stringResource(R.string.save_sync_paused)
        result == null -> stringResource(R.string.save_sync_watching)
        result.watchedFolders == 0 -> stringResource(R.string.save_sync_no_folders)
        else -> stringResource(R.string.save_sync_summary, result.watchedFolders, result.games,
            result.uploaded, result.staged, result.applied, result.conflicts, result.failed)
    }
    val waitHint = result?.waitingForExit?.takeIf { it > 0 }?.let { stringResource(R.string.save_sync_waiting_for_exit) }
    SettingRow(
        stringResource(R.string.save_sync_now),
        subtitle = listOfNotNull(summary, waitHint).joinToString("\n"),
        onClick = { vm.syncNow() },
    )
    SettingRow(
        stringResource(R.string.save_sync_apply),
        subtitle = stringResource(R.string.save_sync_apply_hint),
        onClick = { confirm = true },
    )
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.save_sync_apply)) },
            text = { Text(stringResource(R.string.save_sync_apply_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    vm.syncNow(apply = true)
                }) { Text(stringResource(R.string.save_sync_apply_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}
