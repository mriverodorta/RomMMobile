package com.rommmobile.app.feature.saves

import com.rommmobile.app.data.api.SaveSyncDetails
import java.time.Instant
import java.util.UUID

private val rommVersion = Regex("""^v?(\d+)\.(\d+)""")
private val stateExtension = Regex("""\.state(?:\d+|\.auto)?$""", RegexOption.IGNORE_CASE)

internal fun usesRommSaveLayout(version: String?): Boolean {
    val fields = rommVersion.find(version.orEmpty()) ?: return false
    val major = fields.groupValues[1].toIntOrNull() ?: return false
    val minor = fields.groupValues[2].toIntOrNull() ?: return false
    return major > 5 || major == 5 && minor >= 4
}

internal fun rommSaveCore(core: String): String = when (core.lowercase()) {
    "beetle_psx", "beetle psx" -> "mednafen_psx"
    "beetle_psx_hw", "beetle psx hw" -> "mednafen_psx_hw"
    "beetle_saturn", "beetle saturn" -> "mednafen_saturn"
    "beetle_pce", "beetle pce" -> "mednafen_pce"
    "beetle_pce_fast", "beetle pce fast" -> "mednafen_pce_fast"
    "mupen64plus_next_gles3", "mupen64plus_next_gles2", "mupen64plus-next" -> "mupen64plus_next"
    "pcsx-rearmed" -> "pcsx_rearmed"
    "genesis plus gx" -> "genesis_plus_gx"
    "parallel n64" -> "parallel_n64"
    "dosbox-pure" -> "dosbox_pure"
    "vba next" -> "vba_next"
    "vba-m" -> "vbam"
    "fb alpha" -> "fbalpha"
    "tgb dual" -> "tgbdual"
    "mesen_s" -> "mesen-s"
    else -> core
}

internal fun rommStateSuffix(fileName: String): String? = stateExtension.find(fileName)?.value?.lowercase()

internal fun stateVersionFileName(core: String, suffix: String): String {
    require(rommStateSuffix(suffix) == suffix)
    val coreName = core.replace(Regex("[^A-Za-z0-9_-]"), "_").take(64)
    return "RomMMobileS-$coreName-${UUID.randomUUID()}$suffix"
}

private val remoteRecency = compareBy<SaveSyncDetails> {
    runCatching { Instant.parse(it.updatedAt ?: it.createdAt) }.getOrNull()
}.thenBy { it.id }

internal fun rommSaveHistory(
    entries: List<SaveSyncDetails>,
    stem: String,
    core: String,
    suffix: String,
): List<SaveSyncDetails> {
    val candidates = entries.filter { entry ->
        !entry.missing && entry.emulator?.let(::rommSaveCore).equals(core, ignoreCase = true) &&
            when {
                suffix.startsWith(".state") -> rommStateSuffix(entry.fileName) == suffix
                suffix == ".srm" && entry.slot == "autosave" -> entry.fileName.endsWith(suffix, ignoreCase = true)
                else -> entry.slot == null && entry.fileName.equals(stem + suffix, ignoreCase = true)
            }
    }
    val autosaves = candidates.filter { it.slot == "autosave" }
    return (autosaves.ifEmpty { candidates }).sortedWith(remoteRecency.reversed())
}

internal enum class SaveTransfer { SKIP, MATCH, UPLOAD, DOWNLOAD, CONFLICT }

internal fun saveTransfer(
    local: String?,
    remote: String?,
    common: String?,
    knownOlderBattery: Boolean,
): SaveTransfer = when {
    local == null && remote == null -> SaveTransfer.SKIP
    local == remote -> SaveTransfer.MATCH
    local == null -> SaveTransfer.DOWNLOAD
    remote == null -> if (common == null) SaveTransfer.UPLOAD else SaveTransfer.CONFLICT
    local == common -> SaveTransfer.DOWNLOAD
    knownOlderBattery && (common == null || common == remote) -> SaveTransfer.DOWNLOAD
    remote == common -> SaveTransfer.UPLOAD
    else -> SaveTransfer.CONFLICT
}
