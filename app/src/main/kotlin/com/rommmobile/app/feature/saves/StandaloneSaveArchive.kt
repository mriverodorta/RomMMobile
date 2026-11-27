package com.rommmobile.app.feature.saves

import com.rommmobile.app.core.storage.DirectFileGateway
import com.rommmobile.app.core.storage.SafFileGateway
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val MAX_ENTRIES = 256
private const val MAX_DEPTH = 12

internal data class SaveArchiveEntry(val path: String, val data: ByteArray?)

private fun SyncFolder.allSaveEntries() = checkNotNull(when (val source = gateway) {
    is DirectFileGateway -> source.listSaveEntries(relative)
    is SafFileGateway -> source.listSaveEntries(relative)
    else -> null
}) { "Cannot list all files in the save directory." }

internal fun snapshotSaveDirectory(folder: SyncFolder): ByteArray? {
    val members = mutableListOf<SaveArchiveEntry>()
    var remaining = MAX_SAVE_BYTES
    fun collect(current: SyncFolder, prefix: String, depth: Int) {
        check(depth <= MAX_DEPTH)
        val before = current.allSaveEntries().sortedBy { it.name }
        for (entry in before) {
            check(safeSaveComponent(entry.name) && members.size < MAX_ENTRIES)
            val name = prefix + entry.name
            if (entry.isDirectory) {
                members.add(SaveArchiveEntry("$name/", null))
                collect(current.child(entry.name), "$name/", depth + 1)
            } else {
                check(entry.sizeBytes in 0L..remaining.toLong())
                val bytes = checkNotNull(current.file(entry.name).open()).use { input ->
                    val bytes = checkNotNull(input.readAt(0, entry.sizeBytes.toInt()))
                    check(input.read() == -1) { "Save folder changed during scan." }
                    bytes
                }
                remaining -= bytes.size
                members.add(SaveArchiveEntry(name, bytes))
            }
        }
        check(current.allSaveEntries().sortedBy { it.name } == before) { "Save folder changed during scan." }
    }
    collect(folder, "", 0)
    if (members.none { it.data != null }) return null

    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zip ->
        for ((path, data) in members.sortedBy { it.path }) {
            val bytes = data ?: ByteArray(0)
            zip.putNextEntry(ZipEntry(path).apply {
                // Keep the same bytes and content hash across devices regardless of time zone.
                time = java.time.LocalDateTime.of(1980, 1, 2, 0, 0)
                    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                method = ZipEntry.STORED
                size = bytes.size.toLong()
                crc = CRC32().apply { update(bytes) }.value
            })
            zip.write(bytes)
            zip.closeEntry()
        }
    }
    return output.toByteArray().also { check(it.size <= MAX_SAVE_BYTES) }
}

internal fun parseSaveArchive(bytes: ByteArray): List<SaveArchiveEntry> {
    require(bytes.size in 1..MAX_SAVE_BYTES)
    val members = linkedMapOf<String, SaveArchiveEntry>()
    var remaining = MAX_SAVE_BYTES
    ZipInputStream(bytes.inputStream()).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            val path = entry.name.trimEnd('/')
            val components = path.split('/')
            require(components.size <= MAX_DEPTH && components.all(::safeSaveComponent))
            val key = path.lowercase()
            require(!members.containsKey(key) && members.size < MAX_ENTRIES)
            val buffer = ByteArray(8192)
            val out = ByteArrayOutputStream()
            while (true) {
                val size = zip.read(buffer)
                if (size < 0) break
                remaining -= size
                require(remaining >= 0 && !entry.isDirectory)
                out.write(buffer, 0, size)
            }
            members[key] = SaveArchiveEntry(entry.name, if (entry.isDirectory) null else out.toByteArray())
            zip.closeEntry()
        }
    }
    require(members.values.any { it.data != null })
    for ((name, entry) in members) {
        val parent = name.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) {
            val parentEntry = requireNotNull(members[parent])
            require(parentEntry.data == null && parentEntry.path.trimEnd('/') == entry.path.trimEnd('/').substringBeforeLast('/'))
        }
    }
    return members.values.toList()
}

/** RomM hashes the paths and contents inside a zip, rather than the container bytes. */
internal fun rommSaveArchiveHash(bytes: ByteArray): String = rommSaveArchiveHash(parseSaveArchive(bytes))

private fun rommSaveArchiveHash(entries: List<SaveArchiveEntry>): String {
    val contents = entries.filter { it.data != null }.sortedWith { left, right ->
        val lhs = left.path.codePoints().toArray()
        val rhs = right.path.codePoints().toArray()
        val difference = (0 until minOf(lhs.size, rhs.size)).firstOrNull { lhs[it] != rhs[it] }
        if (difference == null) lhs.size.compareTo(rhs.size) else lhs[difference].compareTo(rhs[difference])
    }.joinToString("\n") { "${it.path}:${digest(checkNotNull(it.data), "MD5")}" }
    return digest(contents.encodeToByteArray(), "MD5")
}

internal class StandaloneDirectory(private val parent: SyncFolder, private val name: String) {
    private val incoming = parent.child(".rommmobiles-dir-incoming")
    private val previous = parent.child(".rommmobiles-dir-previous")
    private val target = parent.child(name)

    fun exists() = parent.gateway.exists(parent.relative, name)

    fun snapshot(): ByteArray? = if (exists()) snapshotSaveDirectory(target) else null

    fun recover(mayWrite: () -> Boolean) {
        val gateway = parent.gateway
        if (gateway.exists(previous.relative, name) && !exists() && mayWrite()) {
            check(gateway.moveWithin(previous.relative, name, parent.relative)) { "Could not recover the original save directory." }
        }
    }

    fun replace(bytes: ByteArray, mayWrite: () -> Boolean): Boolean {
        val entries = parseSaveArchive(bytes)
        val expectedMembers = entries.mapTo(mutableSetOf()) { it.path }
        val expectedHash = rommSaveArchiveHash(entries)
        fun verified(folder: SyncFolder): Boolean {
            val published = snapshotSaveDirectory(folder)?.let(::parseSaveArchive) ?: return false
            return published.mapTo(mutableSetOf()) { it.path } == expectedMembers && rommSaveArchiveHash(published) == expectedHash
        }
        val gateway = parent.gateway
        val staged = incoming.child(name)
        check(!gateway.exists(previous.relative, name)) { "A previous directory replacement needs recovery." }
        // The recovery copy remains private. This staging directory is never a live emulator target.
        if (gateway.exists(incoming.relative, name)) check(gateway.delete(incoming.relative, name))
        check(gateway.ensureDir(staged.relative))
        for (entry in entries.sortedBy { it.path }) {
            val path = listOf(staged.relative, entry.path.trimEnd('/')).joinToString("/")
            if (entry.data == null) {
                check(gateway.ensureDir(path))
            } else {
                gateway.openOutput(path.substringBeforeLast('/'), path.substringAfterLast('/')).use { it.write(entry.data) }
            }
        }
        check(verified(staged)) { "Could not verify the staged save directory." }
        if (!mayWrite()) {
            gateway.delete(incoming.relative, name)
            return false
        }
        val hadOriginal = exists()
        if (hadOriginal) {
            check(gateway.moveWithin(parent.relative, name, previous.relative)) { "Could not move the original save directory." }
        }
        try {
            check(gateway.moveWithin(incoming.relative, name, parent.relative)) { "Could not publish the save directory." }
            check(verified(target)) { "Could not verify the restored save directory." }
        } catch (error: Exception) {
            recover { true }
            throw error
        }
        if (hadOriginal) check(gateway.delete(previous.relative, name))
        return true
    }
}
