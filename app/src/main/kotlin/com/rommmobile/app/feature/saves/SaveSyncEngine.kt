package com.rommmobile.app.feature.saves

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.rommmobile.app.BuildConfig
import com.rommmobile.app.core.esde.EsdeDetector
import com.rommmobile.app.core.esde.EsdeLaunch
import com.rommmobile.app.core.esde.StandaloneEmulator
import com.rommmobile.app.core.network.ApiException
import com.rommmobile.app.core.network.AuthStore
import com.rommmobile.app.core.network.ServerStore
import com.rommmobile.app.core.storage.FileInfo
import com.rommmobile.app.core.storage.normalizeFolderName
import com.rommmobile.app.core.util.FileLogger
import com.rommmobile.app.data.api.RomDto
import com.rommmobile.app.data.api.RommApi
import com.rommmobile.app.data.api.RommSaveApi
import com.rommmobile.app.data.api.SaveSyncCompletion
import com.rommmobile.app.data.api.SaveSyncConfirmation
import com.rommmobile.app.data.api.SaveSyncDetails
import com.rommmobile.app.data.api.SaveSyncDevice
import com.rommmobile.app.data.api.SaveSyncFile
import com.rommmobile.app.data.api.SaveSyncManifest
import com.rommmobile.app.data.api.SaveSyncOperation
import com.rommmobile.app.data.mapping.PlatformMapLoader
import com.rommmobile.app.data.prefs.SettingsStore
import com.rommmobile.app.data.prefs.SaveSyncMethod
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// Keep per-game overrides even when their folders are excluded, so the system default cannot claim them.
internal fun EsdeLaunch.forBackgroundSync(excludeRetroArch: Boolean): EsdeLaunch =
    when {
        !saveFoldersVerified || (excludeRetroArch && isRetroArch) ->
            copy(saves = null, states = null, runtimeLogRoot = null)
        standalone != null -> copy(
            saves = saves.takeUnless { savesAutoDetected },
            states = states.takeUnless { statesAutoDetected },
        )
        else -> this
    }

internal fun syncRomFileName(rom: RomDto): String? = when {
    rom.hasSimpleSingleFile -> rom.fsName
    rom.hasNestedSingleFile -> rom.files.filter { it.category == "game" }.singleOrNull()
        ?.takeIf { it.sha1Hash != null && it.sha1Hash.equals(rom.sha1Hash, ignoreCase = true) }
        ?.fileName
    else -> null
}

private val standaloneRecency = compareBy<SaveSyncDetails> { it.updatedAt ?: it.createdAt }.thenBy { it.id }

internal fun standaloneIdKey(profile: String, romHash: String) = "$profile:${romHash.lowercase()}"

internal const val MAX_SAVE_BYTES = 32 * 1024 * 1024
private val saveSuffix = Regex("""\.(?:srm|sav|rtc|state(?:[0-9]+|\.auto)?)""")
private val md5Pattern = Regex("[a-f0-9]{32}")

internal fun isSaveSuffix(suffix: String) = saveSuffix.matches(suffix)
internal fun saveSlot(core: String, suffix: String) = "retroarch-$core:${suffix.removePrefix(".")}"

// Re-uploading identical bytes can change updated_at without creating a new save.
private fun List<SaveSyncDetails>.latestBatteryVersion(): SaveSyncDetails? =
    filter { it.createdAt != null && it.hash != null }
        .maxWithOrNull(compareBy<SaveSyncDetails> { it.createdAt }.thenBy { it.id })

internal fun batteryRestoreVersion(
    versions: List<SaveSyncDetails>,
    commonHash: String?,
    localHash: String?,
): SaveSyncDetails? {
    val latest = versions.latestBatteryVersion() ?: return null
    if (localHash == null) return latest
    if (latest.hash == localHash) return null
    if (commonHash == localHash) return latest
    // A state load can put a previously synced battery save back into SRAM.
    val knownOlderCopy = versions.any { it.hash == localHash && it.createdAt != null }
    if (!knownOlderCopy || (commonHash != null && commonHash != latest.hash)) return null
    return latest
}

internal fun bothSaveVersionsChanged(common: String?, local: String?, remote: String?): Boolean =
    common != null && local != null && remote != null && local != remote &&
        local != common && remote != common

internal fun negotiatedBatteryDownload(
    action: String,
    history: List<SaveSyncDetails>,
    common: String?,
    local: String?,
): SaveSyncDetails? = when (action) {
    "download" -> history.latestBatteryVersion()
    "upload", "no_op" -> batteryRestoreVersion(history, common, local)
    else -> null
}

data class SaveSyncResult(
    val watchedFolders: Int,
    val games: Int,
    val uploaded: Int = 0,
    val staged: Int = 0,
    val applied: Int = 0,
    val conflicts: Int = 0,
    val failed: Int = 0,
    val waitingForExit: Int = 0,
)

@Singleton
class SaveSyncEngine @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val detector: EsdeDetector,
    private val settings: SettingsStore,
    private val api: RommApi,
    private val saves: RommSaveApi,
    private val auth: AuthStore,
    private val server: ServerStore,
    private val platformMap: PlatformMapLoader,
    private val log: FileLogger,
    private val retroArchExit: RetroArchExit,
) {
    private data class Game(
        val rom: RomDto,
        val stem: String,
        val launch: EsdeLaunch,
        val standaloneId: String?,
    ) {
        val core: String get() = checkNotNull(launch.core)
        val emulator: String get() = "retroarch-$core"

        fun destination(suffix: String): String? {
            val root = (if (suffix.startsWith(".state")) launch.states else launch.saves) ?: return null
            return root
        }
    }

    private data class LocalVersion(val file: SyncFile, val hash: String)
    private data class LocalRom(val file: SyncFile, val entry: FileInfo, val launch: EsdeLaunch) {
        val stem: String get() = entry.name.substringBeforeLast('.')
    }
    private data class Catalog(
        val account: String,
        val systems: Set<String>,
        val standaloneSystems: Set<String>,
        val expires: Long,
        val roms: Map<String, List<RomDto>>,
    )
    private data class RomHash(val length: Long, val modified: Long, val hash: String)

    private val syncedVersions = context.getSharedPreferences("save_sync_common_versions", Context.MODE_PRIVATE)
    private val saveVersions = context.getSharedPreferences("save_sync_local_versions", Context.MODE_PRIVATE)
    private val preferences = context.getSharedPreferences("save_sync_device", Context.MODE_PRIVATE)
    private val standaloneIds = context.getSharedPreferences("standalone_game_ids", Context.MODE_PRIVATE)
    private var catalog: Catalog? = null
    private val romHashes = mutableMapOf<String, RomHash>()

    private val scanLock = Mutex()
    private var auditedAccount: String? = null
    private val auditedBattery = mutableMapOf<Pair<Int, String>, SaveSyncOperation>()
    private var syncLayoutAccount: String? = null
    private var syncLayoutCheckedAt: Long = 0L
    private var useCompatibleLayout = false
    private val stateHashes = object : LinkedHashMap<Int, Pair<String, String>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Pair<String, String>>?) = size > 256
    }

    internal suspend fun run(apply: Boolean, watch: (Set<SyncWatchFolder>) -> Unit): SaveSyncResult =
        scanLock.withLock {
            if (settings.saveSyncEnabled.first()) scan(apply, watch) else SaveSyncResult(0, 0)
        }

    internal suspend fun setMethod(method: SaveSyncMethod) = scanLock.withLock {
        settings.setSaveSyncMethod(method)
    }

    internal suspend fun setExcludeRetroArch(excluded: Boolean) = scanLock.withLock {
        if (settings.saveSyncEnabled.first()) settings.setExcludeRetroArch(excluded)
    }

    internal suspend fun selectEmulatorFolder(key: String, state: Boolean, path: String): Boolean =
        scanLock.withLock { detector.selectSharedFolder(key, state, path) }

    internal fun gameIdOverride(profile: String, hash: String): String? =
        standaloneIds.getString(standaloneIdKey(profile, hash), null)

    internal suspend fun setGameIdOverride(profile: String, hash: String, id: String?) = scanLock.withLock {
        val key = standaloneIdKey(profile, hash)
        standaloneIds.edit().apply {
            if (id == null) remove(key) else putString(key, id)
        }.apply()
    }

    private suspend fun scan(apply: Boolean, watch: (Set<SyncWatchFolder>) -> Unit): SaveSyncResult {
        val config = settings.snapshot()
        val detected = detector.detect(config.storageRoot, settings.esdeDataTree())
        val excludeRetroArch = settings.excludeRetroArch.first()
        val launches = detected.launches.map { it.forBackgroundSync(excludeRetroArch) }
        val batteryEnabled = settings.syncBatterySaves.first()
        val statesEnabled = settings.syncSaveStates.first()
        fun enabled(suffix: String) = if (suffix.startsWith(".state")) statesEnabled else batteryEnabled

        val folders = launches.flatMap { listOfNotNull(it.saves, it.states) }.distinct()
            .mapNotNull { path -> resolveSyncFolder(context, path, writable = true)?.let { path to it } }.toMap()
        if ((!batteryEnabled && !statesEnabled) || folders.isEmpty()) {
            watch(emptySet())
            return SaveSyncResult(0, 0)
        }

        val sessionAuth = auth.state.value
        check(sessionAuth.isLoggedIn) { "Sign in to RomM to sync saves." }
        val serverUrl = checkNotNull(server.config.value.baseUrl)
        val remoteUrl = server.config.value.remoteUrl
        val user = api.me().jsonObject.getValue("id").jsonPrimitive.content
        val account = digest("$serverUrl\n$remoteUrl\n$user".encodeToByteArray(), "SHA-256")
        if (auditedAccount != account) {
            auditedBattery.clear()
            stateHashes.clear()
            auditedAccount = account
        }
        val device = sessionAuth.deviceId ?: device(account)
        fun sameAccount() {
            val current = server.config.value
            check(current.baseUrl == serverUrl && current.remoteUrl == remoteUrl && auth.state.value == sessionAuth) {
                "RomM account changed during sync."
            }
        }

        val stateFolders = launches.groupBy { it.packageName }
            .mapValues { (_, rows) -> rows.mapNotNull { folders[it.states] }.distinct() }
        // Snapshot a force-stop before any watcher or read binds Dolphin's document provider.
        if (launches.any { it.packageName == "org.dolphinemu.dolphinemu" && (it.saves != null || it.states != null) }) {
            retroArchExit.canRestoreStandalone("org.dolphinemu.dolphinemu")
        }
        val runtimeLogs = launches.filter { it.runtimeLogRoot != null && it.core != null }
            .groupBy { it.packageName }
            .mapValues { (_, rows) ->
                rows.groupBy { it.runtimeLogRoot }.flatMap { (path, profiles) ->
                    val root = resolveSyncFolder(context, checkNotNull(path), writable = false) ?: return@flatMap emptyList()
                    val coreNames = profiles.flatMap { listOfNotNull(it.core, it.label, it.saves?.substringAfterLast('/')) }
                        .map(::normalizeFolderName).toSet()
                    root.list().filter { it.isDirectory && normalizeFolderName(it.name) in coreNames }
                        .map { root.child(it.name) }
                }
            }
        val rootWatches = (folders.values + runtimeLogs.values.flatten()).mapNotNull { it.watchFolder() }.toSet()
        watch(rootWatches)
        fun emulatorExited(launch: EsdeLaunch): Boolean {
            val pkg = launch.packageName ?: return false
            if (launch.standalone != null) return retroArchExit.canRestoreStandalone(pkg)
            return retroArchExit.canRestore(pkg, {
                val states = stateFolders[pkg].orEmpty().flatMap { it.list() }
                    .filter { !it.isDirectory && it.name.endsWith(".state.auto") }
                val logs = runtimeLogs[pkg].orEmpty().flatMap { it.list() }
                    .filter { !it.isDirectory && it.name.endsWith(".lrtl") }
                (states + logs).map { it.lastModified }
            })
        }

        val standaloneSystems = launches.filter { it.standalone != null && (it.saves != null || it.states != null) }
            .map { it.system }.toSet()
        val games = matchGames(launches, loadCatalog(account, launches.map { it.system }.toSet(), standaloneSystems))
        var result = SaveSyncResult(folders.size, games.size)
        val filesByDirectory = folders.mapValues { (path, folder) ->
            val users = launches.filter { it.saves == path || it.states == path }.distinctBy { it.packageName }
            if (users.isNotEmpty() && users.all(::emulatorExited)) folder.recover()
            folder.list().filterNot { it.isDirectory }.map { folder.file(it.name) }
        }
        val uniqueGames = games.groupBy { it.rom.id }.values.mapNotNull { it.singleOrNull() }
        val uniqueIds = uniqueGames.filter { it.launch.isRetroArch }
        val standaloneGames = uniqueGames.filter { it.standaloneId != null }
            .groupBy { it.launch.standalone to it.standaloneId }.values.mapNotNull { it.singleOrNull() }
        if (uniqueGames.isEmpty()) return result
        val now = android.os.SystemClock.elapsedRealtime()
        if (syncLayoutAccount != account || now - syncLayoutCheckedAt >= 300_000L) {
            val version = api.heartbeat().system?.version ?: server.config.value.serverVersion
            sameAccount()
            useCompatibleLayout = usesRommSaveLayout(version)
            syncLayoutAccount = account
            syncLayoutCheckedAt = now
        }
        if (useCompatibleLayout) {
            fun mayRestore(launch: EsdeLaunch) =
                apply && ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
                    emulatorExited(launch)

            result = syncStandaloneSaves(standaloneGames, folders, account, device, result,
                batteryEnabled, statesEnabled, ::sameAccount, ::mayRestore) { watch(rootWatches + it) }
            return syncCompatibleSaves(
                uniqueIds, folders, filesByDirectory, account, device, result, ::enabled, ::sameAccount,
            ) { game -> mayRestore(game.launch) }
        }
        if (standaloneGames.isNotEmpty()) result = result.copy(failed = result.failed + standaloneGames.size)
        val stagedFiles = mutableSetOf<File>()
        val stageDir = File(context.cacheDir, "save-sync/$account")
        for (batch in uniqueIds.chunked(100)) {
            sameAccount()
            val gameById = batch.associateBy { it.rom.id }
            val local = mutableMapOf<Pair<Int, String>, LocalVersion>()
            val histories = mutableMapOf<Int, List<SaveSyncDetails>>()
            suspend fun slotHistory(game: Game, slot: String, fresh: Boolean = false): List<SaveSyncDetails> {
                if (fresh) histories.remove(game.rom.id)
                val versions = histories.getOrPut(game.rom.id) {
                    sameAccount()
                    withTimeoutOrNull(8_000) { saves.history(game.rom.id) }
                        ?: throw java.io.IOException("Timed out checking save history.")
                }
                return versions.filter { it.slot == slot && it.emulator == game.emulator && it.hash != null }
            }
            val manifest = batch.flatMap { game ->
                val dirs = setOfNotNull(game.launch.saves, game.launch.states)
                dirs.flatMap { filesByDirectory[it].orEmpty() }.mapNotNull { file ->
                    val stem = game.stem
                    if (!file.name.startsWith(stem)) return@mapNotNull null
                    val suffix = file.name.removePrefix(stem)
                    if (!isSaveSuffix(suffix) || !enabled(suffix) || game.destination(suffix) != file.folder.path) return@mapNotNull null
                    val snapshot = snapshot(file) ?: return@mapNotNull null
                    val slot = saveSlot(game.core, suffix)
                    val slotKey = game.rom.id to slot
                    local[slotKey] = LocalVersion(file, snapshot.hash)
                    val version = localVersionTime(account, game.rom.id, slot, snapshot)
                    SaveSyncFile(game.rom.id, file.name, slot, game.emulator, snapshot.hash,
                        Instant.ofEpochMilli(version).toString(), snapshot.bytes.size.toLong())
                }
            }
            val session = saves.negotiate(SaveSyncManifest(device, gameById.keys.toList(), manifest,
                batch.map { it.emulator }.distinct(), restoreUnlisted = true))
            var completed = 0
            var failed = 0
            // Handle active transfers before checking unchanged battery saves for replayed revisions.
            for (operation in session.operations.sortedBy { it.action == "no_op" }) {
                sameAccount()
                val game = gameById[operation.romId] ?: continue
                val slot = operation.slot ?: continue
                val prefix = "${game.emulator}:"
                if (!slot.startsWith(prefix) || operation.emulator != game.emulator) continue
                val suffix = "." + slot.removePrefix(prefix)
                if (!isSaveSuffix(suffix) || !enabled(suffix)) continue
                val battery = !suffix.startsWith(".state")
                val slotKey = game.rom.id to slot
                val previous = local[slotKey]
                suspend fun currentServerVersion(fresh: Boolean = false) = slotHistory(game, slot, fresh).let { versions ->
                    if (battery) versions.latestBatteryVersion() else versions.maxByOrNull { it.updatedAt.orEmpty() }
                }
                val key = "$account:$device:${game.rom.id}:$slot"
                val common = syncedVersions.getString(key, null)
                fun remember(hash: String) { syncedVersions.edit().putString(key, hash).apply() }
                var action = operation.action
                try {
                    val audit = battery && action == "no_op" && operation.hash != null &&
                        auditedBattery[slotKey] != operation
                    val history = if (battery && (action == "download" || audit ||
                            (action == "upload" && operation.saveId != null))) slotHistory(game, slot) else emptyList()
                    val recovery = if (history.isEmpty()) null else batteryRestoreVersion(history, common, previous?.hash)
                    val batteryVersion = negotiatedBatteryDownload(action, history, common, previous?.hash)
                    val download = when {
                        action == "download" && battery -> {
                            val latest = checkNotNull(batteryVersion) { "RomM no longer lists this battery save." }
                            operation.copy(saveId = latest.id, hash = latest.hash)
                        }
                        action == "download" -> operation
                        batteryVersion != null -> operation.copy(action = "download", saveId = batteryVersion.id, hash = batteryVersion.hash)
                        else -> null
                    }
                    if (download != null) action = "download"
                    when (action) {
                        "upload" -> {
                            val current = previous?.let { snapshot(it.file) }
                            if (current == null || current.hash != previous.hash) {
                                failed++
                                continue
                            }
                            if (operation.saveId != null && current.hash != operation.hash) {
                                val remote = currentServerVersion()
                                if (common == null || bothSaveVersionsChanged(common, current.hash, remote?.hash)) {
                                    saveRecoveryCopy(account, game.rom.id, current, "local")
                                    result = result.copy(conflicts = result.conflicts + 1)
                                    continue
                                }
                            }
                            sameAccount()
                            val part = MultipartBody.Part.createFormData("saveFile", current.name,
                                current.bytes.toRequestBody("application/octet-stream".toMediaType()))
                            saves.upload(game.rom.id, device, session.sessionId, slot, game.emulator, previous.hash, part)
                            remember(current.hash)
                            result = result.copy(uploaded = result.uploaded + 1)
                            completed++
                        }
                        "download" -> {
                            val target = folders[game.destination(suffix)]?.file(game.stem + suffix) ?: continue
                            val selected = checkNotNull(download)
                            if (previous != null && previous.hash == selected.hash) {
                                remember(previous.hash)
                                completed++
                                continue
                            }
                            val sessionId = session.sessionId.takeIf { selected == operation }
                            val downloaded = stage(selected, device, sessionId, stageDir)
                            stagedFiles += downloaded
                            val unknownLocal = recovery == null && common == null && previous != null &&
                                slotHistory(game, slot).none { it.hash == previous.hash }
                            if (unknownLocal || (recovery == null && bothSaveVersionsChanged(common, previous?.hash, selected.hash))) {
                                snapshot(target)?.let { saveRecoveryCopy(account, game.rom.id, it, "local") }
                                snapshot(downloaded)?.let { saveRecoveryCopy(account, game.rom.id, it, "server", target.name) }
                                result = result.copy(conflicts = result.conflicts + 1)
                                continue
                            }
                            val manualApply = apply && ProcessLifecycleOwner.get().lifecycle.currentState
                                .isAtLeast(Lifecycle.State.RESUMED)
                            val autoApply = !manualApply && emulatorExited(game.launch)
                            if ((manualApply || autoApply) && battery) {
                                val latest = currentServerVersion(fresh = true)
                                if (latest?.id != selected.saveId || latest?.hash != selected.hash) {
                                    failed++
                                    continue
                                }
                            }
                            if (manualApply || autoApply) sameAccount()
                            val applied = (manualApply || autoApply) &&
                                applySave(target, previous, downloaded, selected, device, account) {
                                    manualApply || emulatorExited(game.launch)
                                }
                            if (applied) {
                                selected.hash?.let { hash ->
                                    remember(hash)
                                    if (battery) rememberServerVersion(account, game, slot, hash)
                                }
                                result = result.copy(applied = result.applied + 1)
                                completed++
                            } else {
                                result = result.copy(staged = result.staged + 1, waitingForExit = result.waitingForExit + 1)
                            }
                        }
                        "conflict", "delete" -> {
                            previous?.let { snapshot(it.file) }?.let { saveRecoveryCopy(account, game.rom.id, it, "local") }
                            result = result.copy(conflicts = result.conflicts + 1)
                        }
                        "no_op" -> {
                            if (audit) auditedBattery[slotKey] = operation
                            if (previous != null && operation.hash == previous.hash) {
                                remember(previous.hash)
                            } else if (previous != null && bothSaveVersionsChanged(common, previous.hash, operation.hash)) {
                                snapshot(previous.file)?.takeIf { it.hash == previous.hash }?.let {
                                    saveRecoveryCopy(account, game.rom.id, it, "local")
                                }
                                result = result.copy(conflicts = result.conflicts + 1)
                                continue
                            }
                            completed++
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.w("SaveSync", "Could not complete ${operation.action} for ROM ${operation.romId}", e)
                    if (action == "upload" && e is ApiException && e.httpCode == 409 && previous != null) {
                        if (!rememberServerVersion(account, game, slot, previous.hash)) {
                            snapshot(previous.file)?.let { saveRecoveryCopy(account, game.rom.id, it, "local") }
                            result = result.copy(conflicts = result.conflicts + 1)
                            continue
                        }
                    }
                    failed++
                }
            }
            sameAccount()
            saves.complete(session.sessionId, SaveSyncCompletion(completed, failed))
            result = result.copy(failed = result.failed + failed)
        }
        stageDir.listFiles()?.filter { it.isFile && it !in stagedFiles }?.forEach { it.delete() }
        return result
    }

    private suspend fun syncStandaloneSaves(
        games: List<Game>,
        folders: Map<String, SyncFolder>,
        account: String,
        device: String,
        initial: SaveSyncResult,
        batteryEnabled: Boolean,
        statesEnabled: Boolean,
        sameAccount: () -> Unit,
        emulatorExited: (EsdeLaunch) -> Boolean,
        watch: (Set<SyncWatchFolder>) -> Unit,
    ): SaveSyncResult {
        var result = initial
        val subfolders = mutableSetOf<SyncWatchFolder>()
        for (batch in games.chunked(100)) {
            sameAccount()
            val ids = batch.map { it.rom.id }
            val batteryRows = if (batteryEnabled) saves.saveVersions(ids).groupBy { it.romId } else emptyMap()
            val stateRows = if (statesEnabled) saves.stateVersions(ids).groupBy { it.romId } else emptyMap()
            for (game in batch) {
                val emulator = checkNotNull(game.launch.standalone)
                val gameId = checkNotNull(game.standaloneId)
                val packageName = checkNotNull(game.launch.packageName)
                val stateTag = standaloneStateTag(packageName, emulator.id)
                val batteryTag = "rms-${emulator.id}"
                for (state in listOf(false, true)) {
                    if (state && (!statesEnabled || stateTag == null) || !state && !batteryEnabled) continue
                    val folder = folders[if (state) game.launch.states else game.launch.saves] ?: continue
                    val tag = if (state) checkNotNull(stateTag) else batteryTag
                    val rows = (if (state) stateRows else batteryRows)[game.rom.id].orEmpty()
                    fun compatible(row: SaveSyncDetails): StandaloneAsset? {
                        if (row.missing || row.emulator != tag) return null
                        val path = standaloneCloudPath(row.fileName) ?: return null
                        val asset = standaloneAsset(emulator, gameId, path, state) ?: return null
                        return asset.takeIf { state || row.slot == asset.slot }
                    }
                    val history = rows.mapNotNull { row -> compatible(row)?.let { it to row } }.groupBy({ it.first }, { it.second })
                    val localAssets = try {
                        standaloneLocalAssets(emulator, gameId, folder, state)
                    } catch (e: Exception) {
                        log.w("SaveSync", "Could not read ${emulator.id} save directory", e)
                        result = result.copy(failed = result.failed + 1)
                        continue
                    }
                    for (asset in (localAssets + history.keys).distinct()) {
                        val parent = asset.parts.dropLast(1).fold(folder) { base, name -> base.child(name) }
                        val target = parent.file(asset.name)
                        val directory = if (asset.directory) StandaloneDirectory(parent, asset.name) else null
                        val versions = history[asset].orEmpty()
                        val remote = versions.maxWithOrNull(standaloneRecency)
                        val key = "$account:$device:${game.rom.id}:$tag:${asset.slot}"
                        fun mayRestore(): Boolean = emulatorExited(game.launch)
                        fun snapshotAsset(): Snapshot? {
                            if (directory == null) return snapshot(target)
                            val data = directory.snapshot() ?: return null
                            return Snapshot(asset.name + ".zip", data, rommSaveArchiveHash(data), 0)
                        }
                        fun exists() = directory?.exists() ?: target.exists()
                        fun checkPayload(bytes: ByteArray) {
                            if (!state && emulator == StandaloneEmulator.DOLPHIN_GC) {
                                check(bytes.size >= 64 && bytes.copyOfRange(0, 6).decodeToString() == gameId)
                            }
                            if (!state && emulator == StandaloneEmulator.DUCKSTATION) {
                                check(bytes.size == 128 * 1024 && bytes[0] == 'M'.code.toByte() && bytes[1] == 'C'.code.toByte())
                            }
                            if (!state && emulator == StandaloneEmulator.PPSSPP) {
                                val sfo = parseSaveArchive(bytes).firstOrNull { it.path == "PARAM.SFO" }?.data
                                check(sfo != null && paramSfo(sfo)["SAVEDATA_DIRECTORY"] == asset.name) {
                                    "PPSSPP save data does not identify this save directory."
                                }
                            }
                        }
                        try {
                            sameAccount()
                            directory?.recover(::mayRestore)
                            parent.watchFolder()?.let(subfolders::add)
                            if (directory != null) parent.child(asset.name).watchFolder()?.let(subfolders::add)
                            val local = snapshotAsset()
                            if (local == null && exists()) continue
                            if (local != null) checkPayload(local.bytes)
                            val remoteHash = remote?.let { row ->
                                (row.hash ?: if (state) stateHash(row) else null)?.lowercase()
                                    ?.takeIf(md5Pattern::matches) ?: error("RomM did not provide a valid save hash.")
                            }
                            val common = syncedVersions.getString(key, null)
                            suspend fun unchangedRemote(): Boolean {
                                sameAccount()
                                val current = if (state) saves.stateVersions(listOf(game.rom.id)) else saves.history(game.rom.id)
                                return current.filter { compatible(it) == asset }.maxWithOrNull(standaloneRecency) == remote
                            }
                            suspend fun readRemote(): Snapshot {
                                sameAccount()
                                val id = checkNotNull(remote).id
                                val bytes = readSave(if (state) saves.downloadState(id) else saves.download(id, device, null))
                                val hash = if (directory != null) rommSaveArchiveHash(bytes) else digest(bytes, "MD5")
                                check(hash == remoteHash)
                                checkPayload(bytes)
                                return Snapshot(if (directory != null) asset.name + ".zip" else asset.name, bytes, hash, 0)
                            }
                            fun remember(hash: String) { syncedVersions.edit().putString(key, hash).apply() }
                            when (saveTransfer(local?.hash, remoteHash, common, false)) {
                                SaveTransfer.SKIP -> Unit
                                SaveTransfer.MATCH -> {
                                    val hash = checkNotNull(local).hash
                                    if (!state && common != hash) {
                                        saves.confirmDownload(checkNotNull(remote).id, SaveSyncConfirmation(device, hash))
                                    }
                                    remember(hash)
                                }
                                SaveTransfer.UPLOAD -> {
                                    val version = checkNotNull(local)
                                    if (!unchangedRemote() || snapshotAsset()?.hash != version.hash) continue
                                    sameAccount()
                                    val body = version.bytes.toRequestBody("application/octet-stream".toMediaType())
                                    val uploaded = if (state) {
                                        val name = asset.cloudName(java.util.UUID.randomUUID().toString())
                                        saves.uploadState(game.rom.id, tag, MultipartBody.Part.createFormData("stateFile", name, body))
                                    } else {
                                        saves.upload(game.rom.id, device, null, asset.slot, tag, version.hash,
                                            MultipartBody.Part.createFormData("saveFile", asset.cloudName(), body))
                                    }
                                    check((uploaded.hash ?: if (state) stateHash(uploaded) else null)
                                        .equals(version.hash, ignoreCase = true))
                                    remember(version.hash)
                                    result = result.copy(uploaded = result.uploaded + 1)
                                }
                                SaveTransfer.DOWNLOAD -> {
                                    if (!mayRestore()) {
                                        result = result.copy(waitingForExit = result.waitingForExit + 1)
                                        continue
                                    }
                                    val incoming = readRemote()
                                    if (!unchangedRemote()) continue
                                    sameAccount()
                                    val restored = if (directory != null) {
                                        local?.let { saveRecoveryCopy(account, game.rom.id, it, "previous") }
                                        directory.replace(incoming.bytes) {
                                            mayRestore() && if (local == null) !exists() else snapshotAsset()?.hash == local.hash
                                        }
                                    } else if (parent.gateway.ensureDir(parent.relative)) {
                                        restoreFile(target, local?.hash, incoming, account, game.rom.id, ::mayRestore)
                                    } else false
                                    if (restored) {
                                        if (!state) {
                                            saves.confirmDownload(checkNotNull(remote).id, SaveSyncConfirmation(device, incoming.hash))
                                        }
                                        remember(incoming.hash)
                                        result = result.copy(applied = result.applied + 1)
                                    } else {
                                        result = result.copy(waitingForExit = result.waitingForExit + 1)
                                    }
                                }
                                SaveTransfer.CONFLICT -> {
                                    local?.let { saveRecoveryCopy(account, game.rom.id, it, "local") }
                                    remote?.let { saveRecoveryCopy(account, game.rom.id, readRemote(), "server") }
                                    result = result.copy(conflicts = result.conflicts + 1)
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            log.w("SaveSync", "Could not sync ${emulator.id} file for ROM ${game.rom.id}", e)
                            result = result.copy(failed = result.failed + 1)
                        }
                    }
                }
            }
        }
        if (subfolders.isNotEmpty()) watch(subfolders)
        return result
    }

    private fun standaloneStateTag(packageName: String, profile: String): String? {
        val version = try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0).let { "${it.versionCode}:${it.versionName}" }
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            return null
        }
        return "rms-$profile-${digest(version.encodeToByteArray(), "SHA-256").take(16)}"
    }

    private suspend fun syncCompatibleSaves(
        games: List<Game>,
        folders: Map<String, SyncFolder>,
        filesByDirectory: Map<String, List<SyncFile>>,
        account: String,
        device: String,
        initial: SaveSyncResult,
        enabled: (String) -> Boolean,
        sameAccount: () -> Unit,
        mayRestore: (Game) -> Boolean,
    ): SaveSyncResult {
        var result = initial
        for (batch in games.chunked(100)) {
            sameAccount()
            val romIds = batch.map { it.rom.id }
            val saveRows = saves.saveVersions(romIds).groupBy { it.romId }
            val stateRows = if (enabled(".state")) saves.stateVersions(romIds).groupBy { it.romId } else emptyMap()
            for (game in batch) {
                val core = rommSaveCore(game.core)
                val knownSaves = saveRows[game.rom.id].orEmpty()
                val knownStates = stateRows[game.rom.id].orEmpty()
                val suffixes = setOfNotNull(game.launch.saves, game.launch.states)
                    .flatMap { path -> filesByDirectory[path].orEmpty() }
                    .mapNotNullTo(mutableSetOf()) { file ->
                        if (!file.name.startsWith(game.stem)) return@mapNotNullTo null
                        val suffix = file.name.removePrefix(game.stem)
                        suffix.takeIf { isSaveSuffix(it) && game.destination(it) == file.folder.path }
                    }
                suffixes.addAll(listOf(".srm", ".sav", ".rtc"))
                knownStates.mapNotNullTo(suffixes) { rommStateSuffix(it.fileName) }
                knownSaves.mapNotNullTo(suffixes) { row ->
                    row.slot?.takeIf { it.startsWith("${game.emulator}:") }
                        ?.substringAfter(':')?.let { ".$it" }?.takeIf(::isSaveSuffix)
                }

                for (suffix in suffixes.filter(enabled)) {
                    val target = folders[game.destination(suffix)]?.file(game.stem + suffix) ?: continue
                    val state = suffix.startsWith(".state")
                    val key = "$account:$device:${game.rom.id}:romm:$core:$suffix"
                    val legacySlot = saveSlot(game.core, suffix)
                    val legacyKey = "$account:$device:${game.rom.id}:$legacySlot"
                    val common = syncedVersions.getString(key, null)
                    val legacy = knownSaves.filter { !it.missing && it.slot == legacySlot && it.emulator == game.emulator }
                    val legacyHead = if (suffix == ".srm") legacy.latestBatteryVersion() else
                        legacy.maxWithOrNull(compareBy<SaveSyncDetails> { it.updatedAt }.thenBy { it.id })
                    val history = rommSaveHistory(if (state) knownStates else knownSaves, game.stem, core, suffix)
                    val canonicalHead = history.firstOrNull()
                    val fromLegacy = canonicalHead == null && common == null && legacyHead != null
                    val remote = canonicalHead ?: legacyHead.takeIf { fromLegacy }
                    val baseline = common ?: syncedVersions.getString(legacyKey, null).takeIf { fromLegacy }
                    try {
                        sameAccount()
                        val local = snapshot(target)
                        if (local == null && target.exists()) continue
                        val remoteHash = remote?.let { asset ->
                            val hash = (asset.hash ?: if (state && !fromLegacy) stateHash(asset) else null)
                                ?.lowercase()
                            checkNotNull(hash) { "RomM did not provide a save hash." }
                            check(md5Pattern.matches(hash)) { "Invalid RomM save content hash." }
                            hash
                        }
                        val knownOlderBattery = suffix == ".srm" && (history + legacy).any { it.hash == local?.hash }
                        var transfer = saveTransfer(local?.hash, remoteHash, baseline, knownOlderBattery)
                        if (transfer == SaveTransfer.MATCH && fromLegacy) transfer = SaveTransfer.UPLOAD
                        suspend fun unchangedRemote(): Boolean {
                            sameAccount()
                            val rows = if (state) saves.stateVersions(listOf(game.rom.id)) else saves.history(game.rom.id)
                            val head = rommSaveHistory(rows, game.stem, core, suffix).firstOrNull()
                            if (head?.id != canonicalHead?.id || head?.updatedAt != canonicalHead?.updatedAt ||
                                head?.hash != canonicalHead?.hash) return false
                            if (!fromLegacy) return true
                            val freshLegacy = (if (state) saves.history(game.rom.id) else rows).filter {
                                !it.missing && it.slot == legacySlot && it.emulator == game.emulator
                            }
                            val newest = if (suffix == ".srm") freshLegacy.latestBatteryVersion() else
                                freshLegacy.maxWithOrNull(compareBy<SaveSyncDetails> { it.updatedAt }.thenBy { it.id })
                            return newest?.id == legacyHead?.id && newest?.hash == legacyHead?.hash
                        }
                        suspend fun readRemote(): Snapshot {
                            val asset = checkNotNull(remote)
                            val body = if (state && !fromLegacy) saves.downloadState(asset.id) else saves.download(asset.id, device, null)
                            val bytes = readSave(body)
                            val hash = digest(bytes, "MD5")
                            check(hash == remoteHash) { "RomM save hash did not match." }
                            return Snapshot(target.name, bytes, hash, 0)
                        }
                        fun remember(hash: String) { syncedVersions.edit().putString(key, hash).apply() }

                        when (transfer) {
                            SaveTransfer.SKIP -> Unit
                            SaveTransfer.MATCH -> {
                                val hash = checkNotNull(local).hash
                                if (common != hash && suffix == ".srm") {
                                    saves.confirmDownload(checkNotNull(remote).id, SaveSyncConfirmation(device, hash))
                                }
                                remember(hash)
                            }
                            SaveTransfer.UPLOAD -> {
                                val version = checkNotNull(local)
                                if (!unchangedRemote()) continue
                                if (remote != null && suffix in setOf(".sav", ".rtc") && !fromLegacy) {
                                    saveRecoveryCopy(account, game.rom.id, readRemote(), "server")
                                }
                                sameAccount()
                                if (snapshot(target)?.hash != version.hash) continue
                                val body = version.bytes.toRequestBody("application/octet-stream".toMediaType())
                                val saved = if (state) {
                                    val name = stateVersionFileName(core, suffix)
                                    saves.uploadState(game.rom.id, core, MultipartBody.Part.createFormData("stateFile", name, body))
                                } else {
                                    val slot = "autosave".takeIf { suffix == ".srm" }
                                    val emulator = canonicalHead?.emulator?.takeIf { slot == null } ?: core
                                    saves.upload(game.rom.id, device, null, slot, emulator, version.hash,
                                        MultipartBody.Part.createFormData("saveFile", target.name, body))
                                }
                                val savedHash = saved.hash ?: if (state) stateHash(saved) else null
                                check(savedHash.equals(version.hash, ignoreCase = true)) { "RomM did not confirm the uploaded save hash." }
                                remember(version.hash)
                                result = result.copy(uploaded = result.uploaded + 1)
                            }
                            SaveTransfer.DOWNLOAD -> {
                                if (!mayRestore(game)) {
                                    result = result.copy(waitingForExit = result.waitingForExit + 1)
                                    continue
                                }
                                val incoming = readRemote()
                                if (!unchangedRemote()) continue
                                sameAccount()
                                if (!restoreFile(target, local?.hash, incoming, account, game.rom.id) { mayRestore(game) }) {
                                    result = result.copy(waitingForExit = result.waitingForExit + 1)
                                    continue
                                }
                                if (!fromLegacy) {
                                    if (suffix == ".srm") saves.confirmDownload(checkNotNull(remote).id, SaveSyncConfirmation(device, incoming.hash))
                                    remember(incoming.hash)
                                }
                                result = result.copy(applied = result.applied + 1)
                            }
                            SaveTransfer.CONFLICT -> {
                                local?.let { saveRecoveryCopy(account, game.rom.id, it, "local") }
                                remote?.let { saveRecoveryCopy(account, game.rom.id, readRemote(), "server") }
                                result = result.copy(conflicts = result.conflicts + 1)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.w("SaveSync", "Could not sync $suffix for ROM ${game.rom.id}", e)
                        result = if (e is ApiException && e.httpCode == 409) {
                            snapshot(target)?.let { saveRecoveryCopy(account, game.rom.id, it, "local") }
                            result.copy(conflicts = result.conflicts + 1)
                        } else result.copy(failed = result.failed + 1)
                    }
                }
            }
        }
        return result
    }

    private suspend fun stateHash(state: SaveSyncDetails): String {
        val revision = state.updatedAt
        val cached = stateHashes[state.id]
        if (revision != null && cached?.first == revision) return cached.second
        val hash = digest(readSave(saves.downloadState(state.id)), "MD5")
        if (revision != null) stateHashes[state.id] = revision to hash
        return hash
    }

    private fun localVersionTime(account: String, romId: Int, slot: String, snapshot: Snapshot): Long {
        val key = "$account:$romId:$slot"
        val saved = saveVersions.getString(key, null)
        if (saved?.substringBefore(':') == snapshot.hash) {
            saved.substringAfter(':').toLongOrNull()?.let { return it }
        }
        saveVersions.edit().putString(key, "${snapshot.hash}:${snapshot.updatedAt}").apply()
        return snapshot.updatedAt
    }

    private suspend fun rememberServerVersion(account: String, game: Game, slot: String, hash: String): Boolean {
        try {
            val known = saves.history(game.rom.id).filter {
                it.slot == slot && it.emulator == game.emulator && it.hash.equals(hash, ignoreCase = true)
            }.mapNotNull { it.updatedAt?.let { value -> runCatching { Instant.parse(value).toEpochMilli() }.getOrNull() } }
                .maxOrNull() ?: return false
            saveVersions.edit().putString("$account:${game.rom.id}:$slot", "$hash:$known").apply()
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w("SaveSync", "Could not verify earlier ROM save content", e)
            return false
        }
    }

    private fun saveRecoveryCopy(
        account: String, romId: Int, snapshot: Snapshot, origin: String,
        name: String = snapshot.name,
    ) {
        val directory = File(context.filesDir, "save-sync-backups/$account/$romId/$origin-${snapshot.hash}")
        val file = File(directory, name)
        if (!file.exists()) {
            directory.mkdirs()
            writeAtomically(file, snapshot.bytes)
        }
    }

    private suspend fun device(account: String): String {
        preferences.getString("device-$account", null)?.let { return it }
        val hostname = preferences.getString("hostname", null) ?: ("rommmobile-" + UUID.randomUUID()).also {
            check(preferences.edit().putString("hostname", it).commit())
        }
        val id = saves.register(SaveSyncDevice(Build.MODEL, hostname, "android", "rommmobile",
            BuildConfig.VERSION_NAME, "api")).deviceId
        check(preferences.edit().putString("device-$account", id).commit())
        return id
    }

    private suspend fun loadCatalog(
        account: String,
        systems: Set<String>,
        standaloneSystems: Set<String>,
    ): Map<String, List<RomDto>> {
        val now = System.currentTimeMillis()
        catalog?.takeIf {
            it.account == account && it.systems == systems && it.standaloneSystems == standaloneSystems && now < it.expires
        }?.let { return it.roms }
        val platforms = api.platforms()
        val mapping = platformMap.load()
        val bySystem = systems.mapNotNull { system ->
            val normalized = normalizeFolderName(system)
            val candidates = platforms.filter {
                normalized in mapping.aliasKeys(it.slug) || it.fsSlug?.let(::normalizeFolderName) == normalized
            }
            val exact = candidates.filter { it.slug == system }
            // RomM can expose several platform IDs for one slug. Match the ROM across them.
            val compatible = exact.ifEmpty { candidates }
                .takeIf { rows -> rows.map { it.slug }.distinct().size == 1 }.orEmpty()
            compatible.takeIf { it.isNotEmpty() }?.let { system to it.map { row -> row.id } }
        }.toMap()
        val standalonePlatforms = standaloneSystems.flatMap { bySystem[it].orEmpty() }.toSet()
        val byPlatform = mutableMapOf<Int, List<RomDto>>()
        for (platform in bySystem.values.flatten().toSet()) {
            val entries = mutableListOf<RomDto>()
            do {
                val page = api.roms(platformIds = listOf(platform), limit = 200, offset = entries.size,
                    groupByMetaId = false, withCharIndex = false, withTotal = false, withFiles = platform in standalonePlatforms).items
                entries += page
            } while (page.size == 200)
            byPlatform[platform] = entries.filter {
                !it.missingFromFs && !it.sha1Hash.isNullOrBlank() &&
                    (it.hasSimpleSingleFile || (platform in standalonePlatforms && it.hasNestedSingleFile))
            }
        }
        val result = bySystem.mapValues { (_, ids) -> ids.flatMap(byPlatform::getValue) }
        catalog = Catalog(account, systems, standaloneSystems, now + 30 * 60_000, result)
        return result
    }

    private fun matchGames(launches: List<EsdeLaunch>, catalog: Map<String, List<RomDto>>): List<Game> {
        val local = mutableListOf<LocalRom>()
        for ((system, rows) in launches.groupBy { it.system }) {
            val default = rows.firstOrNull { it.game == null } ?: continue
            val root = default.romDirectory?.let { resolveSyncFolder(context, it, writable = false) } ?: continue
            val overrides = rows.filter { it.game != null }.associateBy { it.game?.removePrefix("./") }
            val pending = ArrayDeque<Pair<SyncFolder, String>>()
            pending.add(root to "")
            while (pending.isNotEmpty()) {
                val (folder, prefix) = pending.removeLast()
                for (entry in folder.list()) {
                    val relative = prefix + entry.name
                    if (default.romExtensions.any { entry.name.endsWith(it, ignoreCase = true) }) {
                        val launch = overrides[relative] ?: default
                        if ((launch.isRetroArch && launch.core != null || launch.standalone != null) &&
                            (launch.saves != null || launch.states != null)
                        ) {
                            local += LocalRom(folder.file(entry.name), entry, launch)
                        }
                    } else if (entry.isDirectory) {
                        pending.add(folder.child(entry.name) to "$relative/")
                    }
                }
            }
        }
        // Every launchable ROM claims its output filenames, including ROMs unknown to RomM.
        val saveTargets = local.mapNotNull { it.launch.saves?.let { folder -> folder to it.stem.lowercase() } }
            .groupingBy { it }.eachCount()
        val stateTargets = local.mapNotNull { it.launch.states?.let { folder -> folder to it.stem.lowercase() } }
            .groupingBy { it }.eachCount()
        val byName = catalog.mapValues { (_, roms) ->
            roms.mapNotNull { rom -> syncRomFileName(rom)?.let { it to rom } }.groupBy({ it.first }, { it.second })
        }
        return local.mapNotNull { (file, entry, launch) ->
            val stem = entry.name.substringBeforeLast('.')
            val name = stem.lowercase()
            if (entry.isDirectory || (saveTargets[launch.saves to name] ?: 0) > 1 ||
                (stateTargets[launch.states to name] ?: 0) > 1) return@mapNotNull null
            val options = byName[launch.system]?.get(entry.name) ?: return@mapNotNull null
            val sha1 = romHash(file, entry) ?: return@mapNotNull null
            val emulator = launch.standalone
            val rom = options.singleOrNull {
                (emulator != null || it.hasSimpleSingleFile) && it.sha1Hash.equals(sha1, ignoreCase = true)
            } ?: return@mapNotNull null
            val id = if (emulator == null) null else {
                val override = gameIdOverride(emulator.id, sha1)
                emulator.normalizeGameId(override.orEmpty()) ?: runCatching {
                    standaloneGameId(emulator, entry.name.substringAfterLast('.').lowercase()) { offset, size ->
                        if (offset >= 0 && offset + size <= entry.sizeBytes) file.open()?.use { it.readAt(offset, size) } else null
                    }
                }.getOrNull() ?: rom.titleId?.let(emulator::normalizeGameId) ?: return@mapNotNull null
            }
            Game(rom, stem, launch, id)
        }
    }

    private fun romHash(file: SyncFile, info: FileInfo): String? {
        val modified = info.lastModified
        val size = info.sizeBytes
        romHashes[file.path]?.takeIf { it.length == size && it.modified == modified }?.let { return it.hash }
        val hash = runCatching {
            val md = MessageDigest.getInstance("SHA-1")
            file.open()?.buffered()?.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    md.update(buffer, 0, count)
                }
            } ?: return null
            md.digest().hex()
        }.getOrNull() ?: return null
        if (file.info() != info) return null
        romHashes[file.path] = RomHash(size, modified, hash)
        return hash
    }

    private suspend fun stage(operation: SaveSyncOperation, device: String, session: Int?, dir: File): File {
        val hash = checkNotNull(operation.hash).lowercase()
        check(md5Pattern.matches(hash))
        val id = checkNotNull(operation.saveId)
        val file = File(dir, "$id-$hash")
        if (snapshot(file)?.hash == hash) return file
        val bytes = readSave(saves.download(id, device, session))
        check(bytes.isNotEmpty() && digest(bytes, "MD5") == hash) { "RomM save hash did not match." }
        dir.mkdirs()
        writeAtomically(file, bytes)
        return file
    }

    private suspend fun applySave(
        target: SyncFile,
        previous: LocalVersion?,
        staged: File,
        operation: SaveSyncOperation,
        device: String,
        account: String,
        mayWrite: () -> Boolean,
    ): Boolean {
        val incoming = checkNotNull(snapshot(staged))
        check(incoming.hash.equals(operation.hash, ignoreCase = true))
        if (!restoreFile(target, previous?.hash, incoming, account, operation.romId, mayWrite)) return false
        saves.confirmDownload(checkNotNull(operation.saveId), SaveSyncConfirmation(device, incoming.hash))
        return true
    }

    private suspend fun restoreFile(
        target: SyncFile,
        previous: String?,
        incoming: Snapshot,
        account: String,
        romId: Int,
        mayWrite: () -> Boolean,
    ): Boolean {
        val current = snapshot(target)
        check(if (previous == null) !target.exists() else current?.hash == previous) {
            "The local save changed while sync was running."
        }
        if (current != null) saveRecoveryCopy(account, romId, current, "previous")
        if (!target.replace(incoming.bytes) {
                mayWrite() && if (previous == null) !target.exists() else snapshot(target)?.hash == previous
            }) return false
        // Shared storage can return stale metadata immediately after the atomic rename.
        repeat(5) { attempt ->
            val matches = runCatching {
                target.open()?.buffered()?.use { input ->
                    incoming.bytes.all { input.read() == (it.toInt() and 0xff) } && input.read() == -1
                }
            }.getOrNull() == true
            if (matches) return true
            if (attempt < 4) delay(100)
        }
        error("Could not verify the restored save on shared storage.")
    }
}

private fun readSave(body: ResponseBody): ByteArray = body.use {
    it.byteStream().use { input ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            check(output.size() + count <= MAX_SAVE_BYTES) { "Save exceeds the local sync size limit." }
            output.write(buffer, 0, count)
        }
        output.toByteArray().also { bytes -> check(bytes.isNotEmpty()) { "RomM returned an empty save." } }
    }
}

private data class Snapshot(val name: String, val bytes: ByteArray, val hash: String, val updatedAt: Long)

private fun snapshot(file: SyncFile): Snapshot? {
    val info = file.info() ?: return null
    return snapshot(file.name, info.sizeBytes, info.lastModified, file::open) { file.info() == info }
}

private fun snapshot(file: File): Snapshot? {
    if (!file.isFile) return null
    val size = file.length()
    val modified = file.lastModified()
    return snapshot(file.name, size, modified, file::inputStream) {
        file.lastModified() == modified && file.length() == size
    }
}

private fun snapshot(
    name: String,
    size: Long,
    modified: Long,
    open: () -> java.io.InputStream?,
    unchanged: () -> Boolean,
): Snapshot? {
    if (size !in 1L..MAX_SAVE_BYTES.toLong()) return null
    val bytes = runCatching {
        ByteArray(size.toInt()).also { data ->
            (open() ?: return null).use { input ->
                var offset = 0
                while (offset < data.size) {
                    val count = input.read(data, offset, data.size - offset)
                    if (count < 0) return null
                    offset += count
                }
                if (input.read() != -1) return null
            }
        }
    }.getOrNull() ?: return null
    if (!unchanged()) return null
    return Snapshot(name, bytes, digest(bytes, "MD5"), modified)
}

internal fun writeAtomically(file: File, data: ByteArray) {
    val atomic = AtomicFile(file)
    val output = atomic.startWrite()
    try {
        output.write(data)
        atomic.finishWrite(output)
    } catch (e: Exception) {
        atomic.failWrite(output)
        throw e
    }
}

internal fun digest(data: ByteArray, algorithm: String): String =
    MessageDigest.getInstance(algorithm).digest(data).hex()

private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
