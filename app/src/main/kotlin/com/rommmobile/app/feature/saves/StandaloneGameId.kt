package com.rommmobile.app.feature.saves

import com.rommmobile.app.core.esde.StandaloneEmulator
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

private fun ByteArray.ascii(offset: Int, size: Int): String = String(this, offset, size, Charsets.US_ASCII)
private fun ByteArray.leInt(offset: Int): Int = ByteBuffer.wrap(this, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
private const val ISO_SECTOR = 2048

internal fun standaloneGameId(
    emulator: StandaloneEmulator,
    extension: String,
    read: (Long, Int) -> ByteArray?,
): String? {
    val raw = when (emulator) {
        StandaloneEmulator.DUCKSTATION -> when (extension) {
            "pbp" -> pbpSfo(read)?.get("DISC_ID")
            else -> null
        }
        StandaloneEmulator.DOLPHIN_GC, StandaloneEmulator.DOLPHIN_WII -> {
            val magic = read(0, 4)?.ascii(0, 4)
            val offset = if (magic == "RVZ\u0001" || magic == "WIA\u0001") 0x58L else 0L
            val header = read(offset, 32) ?: return null
            val valid = when (emulator) {
                StandaloneEmulator.DOLPHIN_WII -> header.leInt(0x18) == 0xa39e1c5d.toInt()
                else -> header.leInt(0x1c) == 0x3d9f33c2
            }
            if (!valid) return null
            header.ascii(0, 6)
        }
        StandaloneEmulator.LIME3DS -> {
            if (extension !in setOf("3ds", "cci", "cxi", "app")) return null
            val header = read(0, 0x128) ?: return null
            val ncch = when (header.ascii(0x100, 4)) {
                "NCCH" -> header
                "NCSD" -> {
                    val partition = read(0x120, 4)?.leInt(0)?.takeIf { it > 0 } ?: return null
                    read(partition * 512L, 0x128)?.takeIf { it.ascii(0x100, 4) == "NCCH" } ?: return null
                }
                else -> return null
            }
            ncch.copyOfRange(0x118, 0x120).reversed().joinToString("") { "%02X".format(it) }
        }
        StandaloneEmulator.PPSSPP -> {
            val sfo = when (extension) {
                "pbp" -> pbpSfo(read)
                "iso" -> pspIsoSfo(read)
                else -> null
            }
            sfo?.get("DISC_ID")
        }
    }
    return raw?.let(emulator::normalizeGameId)
}

internal fun InputStream.readAt(offset: Long, size: Int): ByteArray? {
    if (offset < 0 || size < 0) return null
    var remaining = offset
    while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped > 0) remaining -= skipped
        else if (read() < 0) return null
        else remaining--
    }
    val bytes = ByteArray(size)
    var read = 0
    while (read < size) {
        val count = read(bytes, read, size - read)
        if (count <= 0) return null
        read += count
    }
    return bytes
}

private fun pbpSfo(read: (Long, Int) -> ByteArray?): Map<String, String>? {
    val header = read(0, 40) ?: return null
    if (header.ascii(0, 4) != "\u0000PBP") return null
    val start = header.leInt(8).takeIf { it in 40..65536 } ?: return null
    val size = header.leInt(12).takeIf { it in start..(start + 65536) }?.minus(start) ?: return null
    return read(start.toLong(), size)?.let(::paramSfo)
}

private fun pspIsoSfo(read: (Long, Int) -> ByteArray?): Map<String, String>? {
    val descriptor = read(16L * ISO_SECTOR, ISO_SECTOR) ?: return null
    if (descriptor[0].toInt() != 1 || descriptor.ascii(1, 5) != "CD001") return null
    val root = descriptor.readIsoExtent(156, read) ?: return null
    val game = root.isoEntry("PSP_GAME")?.let { root.readIsoExtent(it, read) } ?: return null
    return game.isoEntry("PARAM.SFO")?.let { game.readIsoExtent(it, read) }?.let(::paramSfo)
}

private fun ByteArray.readIsoExtent(offset: Int, read: (Long, Int) -> ByteArray?): ByteArray? {
    if (offset < 0 || offset + 34 > size) return null
    val sector = leInt(offset + 2).takeIf { it > 0 } ?: return null
    val size = leInt(offset + 10).takeIf { it in 1..65536 } ?: return null
    return read(sector.toLong() * ISO_SECTOR, size)
}

private fun ByteArray.isoEntry(name: String): Int? {
    var at = 0
    while (at + 34 <= size) {
        val record = this[at].toInt() and 0xff
        if (record == 0) {
            at = (at / ISO_SECTOR + 1) * ISO_SECTOR
            continue
        }
        if (record < 34 || at + record > size) return null
        val length = this[at + 32].toInt() and 0xff
        if (length + 33 <= record && ascii(at + 33, length).substringBefore(';').equals(name, true)) return at
        at += record
    }
    return null
}

internal fun paramSfo(bytes: ByteArray): Map<String, String> {
    if (bytes.size < 20 || bytes[0] != 0.toByte() || bytes.ascii(1, 3) != "PSF") return emptyMap()
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val keys = buffer.getInt(8)
    val data = buffer.getInt(12)
    val count = buffer.getInt(16)
    if (count !in 1..1000 || keys !in (20 + count * 16)..bytes.size || data !in keys..bytes.size) return emptyMap()
    return buildMap {
        repeat(count) { index ->
            val entry = 20 + index * 16
            val key = keys + (buffer.getShort(entry).toInt() and 0xffff)
            val length = buffer.getInt(entry + 4)
            val start = data.toLong() + buffer.getInt(entry + 12)
            if (key !in keys until data || length !in 1..65536 || start < data || start + length > bytes.size ||
                buffer.getShort(entry + 2).toInt() != 0x0204) return@repeat
            var end = key
            while (end < data && bytes[end] != 0.toByte()) end++
            if (end < data) put(bytes.ascii(key, end - key), bytes.ascii(start.toInt(), length).trimEnd('\u0000'))
        }
    }
}
