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
    private data class Catalog(val account: String, val systems: Set<String>, val expires: Long, val roms: Map<String, List<RomDto>>)
    private data class RomHash(val length: Long, val modified: Long, val hash: String)

    private val syncedVersions = context.getSharedPreferences("save_sync_common_versions", Context.MODE_PRIVATE)
    private val saveVersions = context.getSharedPreferences("save_sync_local_versions", Context.MODE_PRIVATE)
    private val preferences = context.getSharedPreferences("save_sync_device", Context.MODE_PRIVATE)
    private var catalog: Catalog? = null
    private val romHashes = mutableMapOf<String, RomHash>()

    private val scanLock = Mutex()

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
        watch(if (batteryEnabled || statesEnabled) folders.values.mapNotNull { it.watchFolder() }.toSet() else emptySet())
        if ((!batteryEnabled && !statesEnabled) || folders.isEmpty()) return SaveSyncResult(0, 0)

        val sessionAuth = auth.state.value
        check(sessionAuth.isLoggedIn) { "Sign in to RomM to sync saves." }
        val serverUrl = checkNotNull(server.config.value.baseUrl)
        val remoteUrl = server.config.value.remoteUrl
        val user = api.me().jsonObject.getValue("id").jsonPrimitive.content
        val account = digest("$serverUrl\n$remoteUrl\n$user".encodeToByteArray(), "SHA-256")
        val device = sessionAuth.deviceId ?: device(account)
        fun sameAccount() {
            val current = server.config.value
            check(current.baseUrl == serverUrl && current.remoteUrl == remoteUrl && auth.state.value == sessionAuth) {
                "RomM account changed during sync."
            }
        }

        val stateFolders = launches.groupBy { it.packageName }
            .mapValues { (_, rows) -> rows.mapNotNull { folders[it.states] }.distinct() }
        fun emulatorExited(launch: EsdeLaunch): Boolean {
            val pkg = launch.packageName ?: return false
            return retroArchExit.canRestore(pkg, {
                stateFolders[pkg].orEmpty().flatMap { folder -> folder.list() }
                    .filter { !it.isDirectory && it.name.endsWith(".state.auto") }.map { it.lastModified }
            })
        }

        val games = matchGames(launches, loadCatalog(account, launches.map { it.system }.toSet()))
        var result = SaveSyncResult(folders.size, games.size)
        val filesByDirectory = folders.mapValues { (path, folder) ->
            val users = launches.filter { it.saves == path || it.states == path }.distinctBy { it.packageName }
            if (users.isNotEmpty() && users.all(::emulatorExited)) folder.recover()
            folder.list().filterNot { it.isDirectory }.map { folder.file(it.name) }
        }
        // An ambiguous local output name must not associate one save with multiple games.
        val uniqueGames = games.filter { candidate ->
            games.none { other ->
                other !== candidate && candidate.stem == other.stem &&
                    (candidate.launch.saves != null && candidate.launch.saves == other.launch.saves ||
                     candidate.launch.states != null && candidate.launch.states == other.launch.states)
            }
        }
        val uniqueIds = uniqueGames.groupBy { it.rom.id }.values.mapNotNull { it.singleOrNull() }
        val stagedFiles = mutableSetOf<File>()
        val stageDir = File(context.cacheDir, "save-sync/$account")
        for (batch in uniqueIds.chunked(100)) {
            sameAccount()
            val gameById = batch.associateBy { it.rom.id }
            val local = mutableMapOf<Pair<Int, String>, LocalVersion>()
            val batteryRestores = mutableMapOf<Pair<Int, String>, SaveSyncOperation>()
            val histories = mutableMapOf<Int, List<SaveSyncDetails>>()
            suspend fun slotHistory(game: Game, slot: String) =
                histories.getOrPut(game.rom.id) { saves.history(game.rom.id) }.filter {
                    it.slot == slot && it.emulator == game.emulator && it.hash != null
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
                    val common = syncedVersions.getString("$account:$device:${game.rom.id}:$slot", null)
                    if (!suffix.startsWith(".state")) {
                        val remote = batteryRestoreVersion(slotHistory(game, slot), common, snapshot.hash)
                        if (remote != null) {
                            batteryRestores[slotKey] = SaveSyncOperation(
                                "download", game.rom.id, remote.id, slot, game.emulator, remote.hash,
                            )
                            return@mapNotNull null
                        }
                    }
                    val version = localVersionTime(account, game.rom.id, slot, snapshot)
                    SaveSyncFile(game.rom.id, file.name, slot, game.emulator, snapshot.hash,
                        Instant.ofEpochMilli(version).toString(), snapshot.bytes.size.toLong())
                }
            }
            val session = saves.negotiate(SaveSyncManifest(device, gameById.keys.toList(), manifest,
                batch.map { it.emulator }.distinct(), restoreUnlisted = true))
            var completed = 0
            var failed = 0
            val operations = session.operations.filterNot { it.romId to it.slot in batteryRestores }
            for (operation in operations + batteryRestores.values) {
                sameAccount()
                val game = gameById[operation.romId] ?: continue
                val slot = operation.slot ?: continue
                val prefix = "${game.emulator}:"
                if (!slot.startsWith(prefix) || operation.emulator != game.emulator) continue
                val suffix = "." + slot.removePrefix(prefix)
                if (!isSaveSuffix(suffix) || !enabled(suffix)) continue
                val battery = !suffix.startsWith(".state")
                val previous = local[game.rom.id to slot]
                suspend fun currentServerVersion() = slotHistory(game, slot).let { versions ->
                    if (battery) versions.latestBatteryVersion() ?: versions.maxByOrNull { it.updatedAt.orEmpty() }
                    else versions.maxByOrNull { it.updatedAt.orEmpty() }
                }
                val key = "$account:$device:${game.rom.id}:$slot"
                val common = syncedVersions.getString(key, null)
                fun remember(hash: String) { syncedVersions.edit().putString(key, hash).apply() }
                try {
                    when (operation.action) {
                        "upload" -> {
                            val current = previous?.let { snapshot(it.file) }
                            if (current == null || current.hash != previous.hash) {
                                failed++
                                continue
                            }
                            if (common != null && current.hash != common) {
                                val remote = currentServerVersion()
                                if (bothSaveVersionsChanged(common, current.hash, remote?.hash)) {
                                    saveRecoveryCopy(account, game.rom.id, current, "local")
                                    result = result.copy(conflicts = result.conflicts + 1)
                                    continue
                                }
                            }
                            val part = MultipartBody.Part.createFormData("saveFile", current.name,
                                current.bytes.toRequestBody("application/octet-stream".toMediaType()))
                            saves.upload(game.rom.id, device, session.sessionId, slot, game.emulator, previous.hash, part)
                            remember(current.hash)
                            result = result.copy(uploaded = result.uploaded + 1)
                            completed++
                        }
                        "download" -> {
                            val target = folders[game.destination(suffix)]?.file(game.stem + suffix) ?: continue
                            val batteryRestore = game.rom.id to slot in batteryRestores
                            // The server may offer a replayed version when the local file is missing.
                            val latest = if (battery && !batteryRestore) currentServerVersion() else null
                            val download = if (latest != null && latest.id != operation.saveId) {
                                operation.copy(saveId = latest.id, hash = latest.hash)
                            } else operation
                            if (battery && previous != null && previous.hash == download.hash) {
                                remember(previous.hash)
                                completed++
                                continue
                            }
                            val sessionId = session.sessionId.takeUnless { batteryRestore || download != operation }
                            val downloaded = stage(download, device, sessionId, stageDir)
                            stagedFiles += downloaded
                            val unknownLocal = !batteryRestore && common == null && previous != null &&
                                previous.hash != download.hash && slotHistory(game, slot).none { it.hash == previous.hash }
                            if (unknownLocal || (!batteryRestore && bothSaveVersionsChanged(common, previous?.hash, download.hash))) {
                                snapshot(target)?.let { saveRecoveryCopy(account, game.rom.id, it, "local") }
                                snapshot(downloaded)?.let { saveRecoveryCopy(account, game.rom.id, it, "server", target.name) }
                                result = result.copy(conflicts = result.conflicts + 1)
                                continue
                            }
                            val manualApply = apply && ProcessLifecycleOwner.get().lifecycle.currentState
                                .isAtLeast(Lifecycle.State.RESUMED)
                            val autoApply = !manualApply && emulatorExited(game.launch)
                            if (manualApply || autoApply) sameAccount()
                            val applied = (manualApply || autoApply) &&
                                applySave(target, previous, downloaded, download, device, account) {
                                    manualApply || emulatorExited(game.launch)
                                }
                            if (applied) {
                                download.hash?.let { hash ->
                                    remember(hash)
                                    if (battery) rememberServerVersion(account, game, slot, hash)
                                }
                                result = result.copy(applied = result.applied + 1)
                                if (!batteryRestore) completed++
                            } else {
                                result = result.copy(staged = result.staged + 1, waitingForExit = result.waitingForExit + 1)
                            }
                        }
                        "conflict", "delete" -> {
                            previous?.let { snapshot(it.file) }?.let { saveRecoveryCopy(account, game.rom.id, it, "local") }
                            result = result.copy(conflicts = result.conflicts + 1)
                        }
                        "no_op" -> {
                            if (previous != null && previous.hash != common) {
                                val remote = currentServerVersion()
                                if (remote?.hash == previous.hash) {
                                    remember(previous.hash)
                                } else if (bothSaveVersionsChanged(common, previous.hash, remote?.hash)) {
                                    snapshot(previous.file)?.takeIf { it.hash == previous.hash }?.let {
                                        saveRecoveryCopy(account, game.rom.id, it, "local")
                                    }
                                    result = result.copy(conflicts = result.conflicts + 1)
                                    continue
                                }
                            }
                            completed++
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.w("SaveSync", "Could not complete ${operation.action} for ROM ${operation.romId}", e)
                    if (operation.action == "upload" && e is ApiException && e.httpCode == 409 && previous != null) {
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
        val games = mutableListOf<Game>()
        for ((system, rows) in launches.groupBy { it.system }) {
            val default = rows.firstOrNull { it.game == null } ?: continue
            val root = default.romDirectory?.let { resolveSyncFolder(context, it, writable = false) } ?: continue
            val candidates = catalog[system].orEmpty().groupBy { it.fsName }
            val overrides = rows.filter { it.game != null }.associateBy { it.game?.removePrefix("./") }
            for (entry in root.list()) {
                if (entry.isDirectory) continue
                val file = root.file(entry.name)
                val options = candidates[file.name].orEmpty()
                if (options.isEmpty()) continue
                val launch = overrides[file.name] ?: default
                if (launch.core == null || (launch.saves == null && launch.states == null)) continue
                val sha1 = romHash(file, entry) ?: continue
                val rom = options.singleOrNull { it.sha1Hash.equals(sha1, ignoreCase = true) } ?: continue
                games += Game(rom, file.name.substringBeforeLast('.'), launch)
            }
        }
        return games
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
