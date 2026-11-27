package com.rommmobile.app.feature.saves

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rommmobile.app.R
import com.rommmobile.app.core.design.PillShape
import com.rommmobile.app.core.design.RommTheme
import com.rommmobile.app.core.design.SectionHeader
import com.rommmobile.app.core.design.SettingRow
import com.rommmobile.app.core.design.gamepadFocusRing
import com.rommmobile.app.core.esde.EsdeDetection
import com.rommmobile.app.core.esde.EsdeDetector
import com.rommmobile.app.core.esde.EsdeLaunch
import com.rommmobile.app.core.storage.StorageLocations
import com.rommmobile.app.core.storage.StorageRoot
import com.rommmobile.app.data.prefs.Launcher
import com.rommmobile.app.data.prefs.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class EmulatorAccessViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: SettingsStore,
    private val detector: EsdeDetector,
    private val sync: SaveSyncControl,
) : ViewModel() {
    private val _detection = MutableStateFlow<EsdeDetection?>(null)
    val detection = _detection.asStateFlow()
    private val _folderError = MutableStateFlow(false)
    val folderError = _folderError.asStateFlow()
    private var refreshJob: Job? = null
    val promptDismissed = settings.emulatorFolderPromptDismissed

    fun dismissPrompt() = viewModelScope.launch { settings.dismissEmulatorFolderPrompt() }

    fun refresh(root: StorageRoot?) {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _detection.value = withContext(Dispatchers.IO) {
                val config = settings.snapshot()
                val source = root ?: config.storageRoot
                if (source == null || (root == null && config.launcher != Launcher.ESDE)) null
                else detector.detect(source, settings.esdeDataTree())
            }
        }
    }

    fun selectFolder(uri: Uri, root: StorageRoot?) = viewModelScope.launch {
        _folderError.value = false
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            settings.setEsdeDataTree(uri.toString())
            refresh(root)
        } catch (_: SecurityException) {
            _folderError.value = true
        }
    }

    private val _saveFolderError = MutableStateFlow(false)
    val saveFolderError = _saveFolderError.asStateFlow()

    fun grantEmulatorFolder(uri: Uri, root: StorageRoot?) = viewModelScope.launch {
        StorageLocations.persistTree(context, uri)
        _saveFolderError.value = !context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
        if (!_saveFolderError.value) {
            refresh(root)
            if (sync.enabled.first()) sync.syncNow()
        }
    }

    fun useDetectedFolder(key: String, state: Boolean, path: String, root: StorageRoot?) =
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { sync.selectEmulatorFolder(key, state, path) }
            updateSelection(saved, root)
        }

    private suspend fun updateSelection(saved: Boolean, root: StorageRoot?) {
        _saveFolderError.value = !saved
        if (saved) {
            refresh(root)
            if (sync.enabled.first()) sync.syncNow()
        }
    }

    fun chooseSaveFolder(key: String, state: Boolean, uri: Uri, root: StorageRoot?) =
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                val path = StorageLocations.saveTreePath(uri)
                val dolphinFolder = when (key) {
                    "org.dolphinemu.dolphinemu\ndolphin-gc" -> if (state) "StateSaves" else "GC"
                    "org.dolphinemu.dolphinemu\ndolphin-wii" -> if (state) "StateSaves" else "00010000"
                    else -> null
                }
                path != null &&
                    (uri.authority != StorageLocations.DOLPHIN_DOCUMENTS || path.substringAfterLast('/') == dolphinFolder) &&
                    StorageLocations.persistTree(context, uri) &&
                    sync.selectEmulatorFolder(key, state, path)
            }
            updateSelection(saved, root)
        }
}

@Composable
fun EmulatorAccess(
    root: StorageRoot? = null,
    prompt: Boolean = false,
    showEsdeSettings: Boolean = false,
    excludeRetroArch: Boolean = false,
) {
    val vm: EmulatorAccessViewModel = hiltViewModel()
    if (prompt) {
        val dismissed by vm.promptDismissed.collectAsStateWithLifecycle(initialValue = true)
        if (dismissed) return
    }
    val detection by vm.detection.collectAsStateWithLifecycle()
    val folderError by vm.folderError.collectAsStateWithLifecycle()
    LaunchedEffect(root) { vm.refresh(root) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh(root) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { vm.selectFolder(it, root) }
    }

    val saveFolderError by vm.saveFolderError.collectAsStateWithLifecycle()
    val emulatorPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { vm.grantEmulatorFolder(it, root) }
    }
    var pickingKey by rememberSaveable { mutableStateOf<String?>(null) }
    var pickingState by rememberSaveable { mutableStateOf(false) }
    val savePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val key = pickingKey
        pickingKey = null
        if (key != null && uri != null) vm.chooseSaveFolder(key, pickingState, uri, root)
    }

    if (showEsdeSettings) {
        SectionHeader(stringResource(R.string.diag_esde))
        SettingRow(
            title = stringResource(R.string.diag_esde_folder),
            subtitle = detection?.folder ?: stringResource(
                if (detection?.error == true) R.string.diag_esde_error else R.string.diag_esde_choose,
            ),
            onClick = { folderPicker.launch(null) },
        )
        SettingRow(
            title = stringResource(R.string.save_sync_choose_emulator_folder),
            subtitle = stringResource(R.string.save_sync_choose_emulator_folder_hint),
            onClick = { emulatorPicker.launch(null) },
        )
        detection?.retroArchConfigRead?.let { read ->
            Text(
                stringResource(if (read) R.string.diag_esde_config_read else R.string.diag_esde_config_unreadable),
                modifier = Modifier.padding(horizontal = 14.dp),
                color = RommTheme.colors.onSurfaceMuted,
            )
        }
        if (folderError) Text(stringResource(R.string.emulator_access_folder_error),
            modifier = Modifier.padding(horizontal = 14.dp), color = MaterialTheme.colorScheme.error)
    }
    val detected = detection ?: return
    val profiles = detected.launches
        .filter { it.folderSelectionKey != null && !(excludeRetroArch && it.isRetroArch) }
        .distinctBy { it.folderSelectionKey }
    val choose: (EsdeLaunch, Boolean) -> Unit = { launch, state ->
        pickingKey = launch.folderSelectionKey
        pickingState = state
        savePicker.launch(null)
    }
    var editingKey by rememberSaveable { mutableStateOf<String?>(null) }
    val useDetected: (EsdeLaunch, Boolean) -> Unit = { launch, state ->
        val path = if (state) launch.states else launch.saves
        val key = launch.folderSelectionKey
        if (path != null && key != null) {
            editingKey = key
            vm.useDetectedFolder(key, state, path, root)
        }
    }
    val promptProfiles = profiles.filter { !it.saveFoldersVerified || it.folderSelectionKey == editingKey }
    val dismiss = { vm.dismissPrompt(); Unit }

    if (prompt) {
        if (promptProfiles.isEmpty()) return
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(stringResource(R.string.emulator_access_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    EmulatorFolders(promptProfiles, saveFolderError, useDetected) { launch, state ->
                        editingKey = launch.folderSelectionKey
                        choose(launch, state)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = dismiss, modifier = Modifier.gamepadFocusRing(PillShape)) {
                    Text(stringResource(R.string.emulator_access_later))
                }
            },
        )
        return
    }

    val needsFolder = root != null && detected.folder == null
    if (!showEsdeSettings && !needsFolder && profiles.isEmpty() && root == null) return
    val layout = RommTheme.layout
    Column(Modifier.padding(
        top = layout.padding,
        start = if (showEsdeSettings) 14.dp else 0.dp,
        end = if (showEsdeSettings) 14.dp else 0.dp,
    )) {
        Text(stringResource(R.string.emulator_access_title), style = MaterialTheme.typography.titleMedium)
        if (needsFolder) {
            Text(stringResource(R.string.emulator_access_esde_folder))
            TextButton(onClick = { folderPicker.launch(null) }, modifier = Modifier.gamepadFocusRing(PillShape)) {
                Text(stringResource(R.string.diag_esde_folder))
            }
        } else if (profiles.isNotEmpty()) {
            EmulatorFolders(profiles, saveFolderError, useDetected, choose)
        } else {
            Text(stringResource(R.string.emulator_access_no_folders))
        }
        if (folderError && !showEsdeSettings) Text(stringResource(R.string.emulator_access_folder_error), color = MaterialTheme.colorScheme.error)
    }
    if (showEsdeSettings && detected.launches.isNotEmpty()) {
        var expanded by rememberSaveable { mutableStateOf(false) }
        SettingRow(
            title = stringResource(R.string.save_sync_emulator_details),
            onClick = { expanded = !expanded },
        )
        if (expanded) {
            for (launch in detected.launches) {
                val target = listOfNotNull(launch.label.takeIf { it.isNotBlank() }, launch.packageName, launch.core)
                SettingRow(
                    title = listOfNotNull(launch.system, launch.game).joinToString(" · "),
                    subtitle = listOf(
                        target.joinToString(" · "),
                        stringResource(R.string.diag_esde_saves, launch.saves ?: stringResource(R.string.diag_esde_unverified)),
                        stringResource(R.string.diag_esde_states, launch.states ?: stringResource(R.string.diag_esde_unverified)),
                    ).joinToString("\n"),
                )
            }
        }
    }
}

@Composable
private fun EmulatorFolders(
    launches: List<EsdeLaunch>,
    error: Boolean,
    useDetected: (EsdeLaunch, Boolean) -> Unit,
    choose: (EsdeLaunch, Boolean) -> Unit,
) {
    Text(stringResource(R.string.emulator_access_choose_body))
    if (error) Text(stringResource(R.string.emulator_access_choose_error), color = MaterialTheme.colorScheme.error)
    for (launch in launches) {
        Column(Modifier.padding(top = RommTheme.layout.padding)) {
            Text(launch.label, style = MaterialTheme.typography.titleSmall)
            if (launch.standalone != null) {
                Text(stringResource(R.string.standalone_save_folder_hint))
                if (!launch.saveFoldersVerified) Text(stringResource(R.string.standalone_save_folder_unselected))
            }
            launch.saves?.let { Text(stringResource(if (launch.savesAutoDetected) R.string.emulator_access_default_saves else R.string.diag_esde_saves, it)) }
            launch.states?.let { Text(stringResource(if (launch.statesAutoDetected) R.string.emulator_access_default_states else R.string.diag_esde_states, it)) }
            if (launch.standalone != null && (launch.savesAutoDetected || launch.statesAutoDetected)) {
                Row {
                    if (launch.savesAutoDetected) {
                        TextButton(onClick = { useDetected(launch, false) }, modifier = Modifier.gamepadFocusRing(PillShape)) {
                            Text(stringResource(R.string.standalone_use_detected_saves))
                        }
                    }
                    if (launch.statesAutoDetected) {
                        TextButton(onClick = { useDetected(launch, true) }, modifier = Modifier.gamepadFocusRing(PillShape)) {
                            Text(stringResource(R.string.standalone_use_detected_states))
                        }
                    }
                }
            }
            Row {
                TextButton(onClick = { choose(launch, false) }, modifier = Modifier.gamepadFocusRing(PillShape)) {
                    Text(stringResource(R.string.emulator_access_choose_saves))
                }
                TextButton(onClick = { choose(launch, true) }, modifier = Modifier.gamepadFocusRing(PillShape)) {
                    Text(stringResource(R.string.emulator_access_choose_states))
                }
            }
        }
    }
}
