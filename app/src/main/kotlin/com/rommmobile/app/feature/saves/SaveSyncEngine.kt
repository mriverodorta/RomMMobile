package com.rommmobile.app.feature.saves

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.rommmobile.app.BuildConfig
import com.rommmobile.app.core.esde.EsdeDetector
import com.rommmobile.app.core.esde.EsdeLaunch
import com.rommmobile.app.core.network.ApiException
import com.rommmobile.app.core.network.AuthStore
import com.rommmobile.app.core.network.ServerStore
import com.rommmobile.app.core.storage.FileInfo
import com.rommmobile.app.core.storage.normalizeFolderName
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
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

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
    private data class Game(val rom: RomDto, val stem: String, val launch: EsdeLaunch) {
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
    private data class Catalog(val account: String, val systems: Set<String>, val expires: Long, val roms: Map<String, List<RomDto>>)
    private data class RomHash(val length: Long, val modified: Long, val hash: String)

    private val syncedVersions = context.getSharedPreferences("save_sync_common_versions", Context.MODE_PRIVATE)
    private val saveVersions = context.getSharedPreferences("save_sync_local_versions", Context.MODE_PRIVATE)
    private val preferences = context.getSharedPreferences("save_sync_device", Context.MODE_PRIVATE)
    private var catalog: Catalog? = null
    private val romHashes = mutableMapOf<String, RomHash>()

    private val scanLock = Mutex()
    private var auditedAccount: String? = null
    private val auditedBattery = mutableMapOf<Pair<Int, String>, SaveSyncOperation>()

    internal suspend fun run(apply: Boolean, watch: (Set<SyncWatchFolder>) -> Unit): SaveSyncResult =
        scanLock.withLock { scan(apply, watch) }

    private suspend fun scan(apply: Boolean, watch: (Set<SyncWatchFolder>) -> Unit): SaveSyncResult {
        val config = settings.snapshot()
        val detected = detector.detect(config.storageRoot, settings.esdeDataTree())
        // A guessed directory is useful for diagnostics but cannot be a sync source.
        val launches = detected.launches.map { launch ->
            if (launch.saveFoldersVerified) launch else launch.copy(saves = null, states = null)
        }
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
        watch((folders.values + runtimeLogs.values.flatten()).mapNotNull { it.watchFolder() }.toSet())
        fun emulatorExited(launch: EsdeLaunch): Boolean {
            val pkg = launch.packageName ?: return false
            return retroArchExit.canRestore(pkg, {
                val states = stateFolders[pkg].orEmpty().flatMap { it.list() }
                    .filter { !it.isDirectory && it.name.endsWith(".state.auto") }
                val logs = runtimeLogs[pkg].orEmpty().flatMap { it.list() }
                    .filter { !it.isDirectory && it.name.endsWith(".lrtl") }
                (states + logs).map { it.lastModified }
            })
        }

        val games = matchGames(launches, loadCatalog(account, launches.map { it.system }.toSet()))
        var result = SaveSyncResult(folders.size, games.size)
        val filesByDirectory = folders.mapValues { (path, folder) ->
            val users = launches.filter { it.saves == path || it.states == path }.distinctBy { it.packageName }
            if (users.isNotEmpty() && users.all(::emulatorExited)) folder.recover()
            folder.list().filterNot { it.isDirectory }.map { folder.file(it.name) }
        }
        val uniqueIds = games.groupBy { it.rom.id }.values.mapNotNull { it.singleOrNull() }
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

    private suspend fun loadCatalog(account: String, systems: Set<String>): Map<String, List<RomDto>> {
        val now = System.currentTimeMillis()
        catalog?.takeIf { it.account == account && it.systems == systems && now < it.expires }?.let { return it.roms }
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
        val byPlatform = mutableMapOf<Int, List<RomDto>>()
        for (platform in bySystem.values.flatten().toSet()) {
            val entries = mutableListOf<RomDto>()
            do {
                val page = api.roms(platformIds = listOf(platform), limit = 200, offset = entries.size,
                    groupByMetaId = false, withCharIndex = false, withTotal = false).items
                entries += page
            } while (page.size == 200)
            byPlatform[platform] = entries.filter { !it.missingFromFs && it.hasSimpleSingleFile && !it.sha1Hash.isNullOrBlank() }
        }
        val result = bySystem.mapValues { (_, ids) -> ids.flatMap(byPlatform::getValue) }
        catalog = Catalog(account, systems, now + 30 * 60_000, result)
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
                        if (launch.core != null && (launch.saves != null || launch.states != null)) {
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
        val byName = catalog.mapValues { (_, roms) -> roms.groupBy { it.fsName } }
        return local.mapNotNull { (file, entry, launch) ->
            val stem = entry.name.substringBeforeLast('.')
            val name = stem.lowercase()
            if (entry.isDirectory || (saveTargets[launch.saves to name] ?: 0) > 1 ||
                (stateTargets[launch.states to name] ?: 0) > 1) return@mapNotNull null
            val options = byName[launch.system]?.get(entry.name) ?: return@mapNotNull null
            val sha1 = romHash(file, entry) ?: return@mapNotNull null
            val rom = options.singleOrNull { it.sha1Hash.equals(sha1, ignoreCase = true) } ?: return@mapNotNull null
            Game(rom, stem, launch)
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
        val bytes = saves.download(id, device, session).use { body ->
            body.byteStream().use { input ->
                val result = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(result.size() + count <= MAX_SAVE_BYTES) { "Save exceeds the local sync size limit." }
                    result.write(buffer, 0, count)
                }
                result.toByteArray()
            }
        }
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
        val current = snapshot(target)
        check(if (previous == null) !target.exists() else current?.hash == previous.hash) {
            "The local save changed while sync was running."
        }
        if (current != null) saveRecoveryCopy(account, operation.romId, current, "previous")
        if (!target.replace(incoming.bytes) {
                mayWrite() && if (previous == null) !target.exists() else snapshot(target)?.hash == previous.hash
            }) return false
        // Shared storage can return stale metadata immediately after the atomic rename.
        repeat(5) { attempt ->
            val matches = runCatching {
                target.open()?.buffered()?.use { input ->
                    incoming.bytes.all { input.read() == (it.toInt() and 0xff) } && input.read() == -1
                }
            }.getOrNull() == true
            if (matches) {
                saves.confirmDownload(checkNotNull(operation.saveId), SaveSyncConfirmation(device, incoming.hash))
                return true
            }
            if (attempt < 4) delay(100)
        }
        error("Could not verify the restored save on shared storage.")
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

private fun digest(data: ByteArray, algorithm: String): String =
    MessageDigest.getInstance(algorithm).digest(data).hex()

private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
