package com.rommmobile.app.feature.saves

import com.rommmobile.app.core.esde.StandaloneEmulator
import java.util.Base64

internal data class StandaloneAsset(val path: String, val state: Boolean, val directory: Boolean = false) {
    val name: String get() = path.substringAfterLast('/')
    val parts: List<String> get() = path.split('/')
    val token: String get() = Base64.getUrlEncoder().withoutPadding().encodeToString(path.encodeToByteArray())
    val slot: String get() = "rms1.$token"
    val extension: String get() = if (directory) "zip" else name.substringAfterLast('.')

    fun cloudName(version: String? = null): String = buildString {
        append("rms1.").append(token)
        if (version != null) append('.').append(version)
        append('.').append(extension)
    }
}

private val cloudFile = Regex("""^rms1\.([A-Za-z0-9_-]{1,190})(?:\.[a-f0-9-]{36})?(?: \[(?:v\d+|\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2})])?\.[A-Za-z0-9]+$""")

internal fun standaloneCloudPath(name: String): String? {
    val token = cloudFile.matchEntire(name)?.groupValues?.get(1) ?: return null
    return runCatching {
        Base64.getUrlDecoder().decode(token).decodeToString(throwOnInvalidSequence = true)
    }.getOrNull()?.takeIf { path -> path.split('/').all(::safeSaveComponent) }
}

internal fun safeSaveComponent(name: String): Boolean =
    name.isNotEmpty() && name.encodeToByteArray().size <= 180 && name != "." && name != ".." &&
        !name.startsWith(".rommmobiles-") && name.none { it.isISOControl() || it == '/' || it == '\\' || it == ':' }

internal fun standaloneAsset(
    emulator: StandaloneEmulator,
    gameId: String,
    path: String,
    state: Boolean,
): StandaloneAsset? {
    if (emulator.normalizeGameId(gameId) != gameId) return null
    val parts = path.split('/')
    if (parts.size > 4 || !parts.all(::safeSaveComponent)) return null
    val name = parts.last()
    val id = Regex.escape(gameId)
    val directory = !state && emulator in setOf(StandaloneEmulator.DOLPHIN_WII, StandaloneEmulator.LIME3DS, StandaloneEmulator.PPSSPP)
    val valid = if (state) {
        val pattern = when (emulator) {
            StandaloneEmulator.DUCKSTATION -> "${id}_(?:[0-9]{1,2}|resume)\\.sav"
            StandaloneEmulator.DOLPHIN_GC, StandaloneEmulator.DOLPHIN_WII -> "$id\\.s[0-9]{2}"
            StandaloneEmulator.LIME3DS -> "$id\\.[0-9]{2}\\.cst"
            StandaloneEmulator.PPSSPP -> "${id}_[0-9.]{1,8}_[0-9]{1,2}\\.ppst"
        }
        parts.size == 1 && Regex(pattern, RegexOption.IGNORE_CASE).matches(name)
    } else when (emulator) {
        StandaloneEmulator.DUCKSTATION -> parts.size == 1 && Regex("${id}_[12]\\.mcd", RegexOption.IGNORE_CASE).matches(name)
        StandaloneEmulator.DOLPHIN_GC -> {
            // GCI identity is checked separately against the six bytes in the card's header.
            parts.size == 3 && parts[0] in setOf("USA", "EUR", "JAP", "KOR") &&
                parts[1] in setOf("Card A", "Card B") && name.endsWith(".gci", ignoreCase = true)
        }
        StandaloneEmulator.DOLPHIN_WII -> {
            val title = gameId.take(4).encodeToByteArray().joinToString("") { "%02x".format(it) }
            path == "$title/data"
        }
        StandaloneEmulator.LIME3DS -> parts.size == 2 && parts[0].equals(gameId.takeLast(8), true) && name == "data"
        StandaloneEmulator.PPSSPP -> parts.size == 1 && Regex("$id[A-Za-z0-9._-]{0,23}").matches(name)
    }
    return StandaloneAsset(path, state, directory).takeIf { valid && it.token.length <= 190 }
}

internal fun standaloneLocalAssets(
    emulator: StandaloneEmulator,
    gameId: String,
    folder: SyncFolder,
    state: Boolean,
): List<StandaloneAsset> {
    val paths = when {
        state || emulator == StandaloneEmulator.DUCKSTATION || emulator == StandaloneEmulator.PPSSPP ->
            folder.list().filter { it.isDirectory == (!state && emulator == StandaloneEmulator.PPSSPP) }.map { it.name }
        emulator == StandaloneEmulator.DOLPHIN_GC ->
            folder.list().filter { it.isDirectory && it.name in setOf("USA", "EUR", "JAP", "KOR") }.flatMap { region ->
                val parent = folder.child(region.name)
                parent.list().filter { it.isDirectory && it.name in setOf("Card A", "Card B") }.flatMap { card ->
                    val directory = parent.child(card.name)
                    directory.list().filter { !it.isDirectory && it.name.endsWith(".gci", true) }.mapNotNull { entry ->
                        val header = directory.file(entry.name).open()?.use { it.readAt(0, 6) }
                        "${region.name}/${card.name}/${entry.name}".takeIf {
                            header?.decodeToString() == gameId
                        }
                    }
                }
            }
        else -> folder.list().filter { it.isDirectory }.map { "${it.name}/data" }
    }
    return paths.mapNotNull { standaloneAsset(emulator, gameId, it, state) }
}
