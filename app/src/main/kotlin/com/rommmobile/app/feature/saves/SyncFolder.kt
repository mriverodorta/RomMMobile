package com.rommmobile.app.feature.saves

import android.content.Context
import android.net.Uri
import com.rommmobile.app.core.storage.DirectFileGateway
import com.rommmobile.app.core.storage.FileGateway
import com.rommmobile.app.core.storage.FileInfo
import com.rommmobile.app.core.storage.SafFileGateway
import com.rommmobile.app.core.storage.StorageLocations
import com.rommmobile.app.core.storage.StorageRoot
import java.io.File

internal data class SyncWatchFolder(val path: String? = null, val uri: Uri? = null)

internal class SyncFolder(
    val path: String,
    val gateway: FileGateway,
    val relative: String,
) {
    fun list(): List<FileInfo> = gateway.list(relative)
    fun file(name: String) = SyncFile(this, name)
    fun child(name: String) = SyncFolder("$path/$name", gateway, listOf(relative, name).filter(String::isNotEmpty).joinToString("/"))
    fun recover() {
        (gateway as? SafFileGateway)?.recoverSaveReplacements(relative)
    }

    fun watchFolder(): SyncWatchFolder? = when (gateway) {
        is SafFileGateway -> gateway.saveDirectoryObserverUri(relative)?.let { SyncWatchFolder(uri = it) }
        else -> SyncWatchFolder(path = path)
    }
}

internal data class SyncFile(val folder: SyncFolder, val name: String) {
    val path get() = "${folder.path}/$name"
    fun info() = folder.list().firstOrNull { it.name == name && !it.isDirectory }
    fun exists() = folder.gateway.exists(folder.relative, name)
    fun open() = folder.gateway.openInput(folder.relative, name)
    fun replace(bytes: ByteArray, mayReplace: () -> Boolean): Boolean {
        val gateway = folder.gateway
        if (gateway is SafFileGateway) return gateway.replaceSave(folder.relative, name, bytes, mayReplace)
        if (!mayReplace()) return false
        writeAtomically(File(path), bytes)
        return true
    }
}

internal fun resolveSyncFolder(context: Context, path: String, writable: Boolean): SyncFolder? {
    if (StorageLocations.hasAllFilesAccess(context) && File(path).isDirectory) {
        return SyncFolder(path, DirectFileGateway(StorageRoot.Direct(path)), "")
    }
    val (root, relative) = StorageLocations.treeCovering(context, path, writable) ?: return null
    return SyncFolder(path, SafFileGateway(context, root), relative)
}
