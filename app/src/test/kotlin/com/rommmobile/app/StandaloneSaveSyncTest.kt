package com.rommmobile.app

import com.google.common.truth.Truth.assertThat
import com.rommmobile.app.core.esde.StandaloneEmulator
import com.rommmobile.app.core.storage.DirectFileGateway
import com.rommmobile.app.core.storage.StorageRoot
import com.rommmobile.app.data.api.RomDto
import com.rommmobile.app.data.api.RomFileDto
import com.rommmobile.app.feature.saves.syncRomFileName
import com.rommmobile.app.feature.saves.StandaloneDirectory
import com.rommmobile.app.feature.saves.SyncFolder
import com.rommmobile.app.feature.saves.parseSaveArchive
import com.rommmobile.app.feature.saves.rommSaveArchiveHash
import com.rommmobile.app.feature.saves.snapshotSaveDirectory
import com.rommmobile.app.feature.saves.standaloneAsset
import com.rommmobile.app.feature.saves.standaloneCloudPath
import com.rommmobile.app.feature.saves.standaloneGameId
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class StandaloneSaveSyncTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `nested ROMs resolve to their exact game filename with a matching hash`() {
        val file = RomFileDto(id = 1, fileName = "Example (USA).cci", category = "game", sha1Hash = "1234")
        val rom = RomDto(
            id = 7, fsName = "Example (USA)", sha1Hash = "1234",
            hasSimpleSingleFile = false, hasNestedSingleFile = true,
            files = listOf(file, file.copy(id = 2, fileName = "Manual.pdf", category = "manual")),
        )
        assertThat(syncRomFileName(rom)).isEqualTo(file.fileName)
        assertThat(syncRomFileName(rom.copy(files = listOf(file.copy(sha1Hash = "5678"))))).isNull()
        assertThat(syncRomFileName(rom.copy(files = listOf(file, file.copy(id = 9))))).isNull()
    }

    @Test
    fun `standalone slot matching distinguishes game files from shared or foreign files`() {
        assertThat(standaloneAsset(StandaloneEmulator.DUCKSTATION, "SLUS-12345", "SLUS-12345_1.mcd", false)).isNotNull()
        assertThat(standaloneAsset(StandaloneEmulator.DUCKSTATION, "SLUS-12345", "shared_card_1.mcd", false)).isNull()
        assertThat(standaloneAsset(StandaloneEmulator.DUCKSTATION, "SLUS-12345", "SLUS-12345_resume.sav", true)).isNotNull()
        assertThat(standaloneAsset(StandaloneEmulator.PPSSPP, "ULUS12345", "ULUS12344DATA00", false)).isNull()
        assertThat(standaloneAsset(StandaloneEmulator.PPSSPP, "ULUS12345", "ULUS12345_1.00_1.ppst", true)).isNotNull()
        assertThat(standaloneAsset(StandaloneEmulator.LIME3DS, "0004000000F00100", "00f00100/data", false)?.directory).isTrue()
        assertThat(standaloneAsset(StandaloneEmulator.DOLPHIN_WII, "RQAE01", "52514145/data", false)?.directory).isTrue()
        assertThat(standaloneAsset(StandaloneEmulator.DOLPHIN_GC, "GQAE01", "GQAE01.s01", true)).isNotNull()
    }

    @Test
    fun `cloud filenames round trip and reject paths escaping selected directories`() {
        val card = checkNotNull(standaloneAsset(StandaloneEmulator.DOLPHIN_GC, "GQAE01", "USA/Card A/Game.gci", false))
        assertThat(standaloneCloudPath(card.cloudName())).isEqualTo(card.path)
        assertThat(standaloneCloudPath(card.cloudName().replace(".gci", " [v8].gci"))).isEqualTo(card.path)
        assertThat(standaloneCloudPath(card.cloudName().replace(".gci", " [2026-11-27_17-38-36].gci"))).isEqualTo(card.path)
        val unsafe = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("../save.gci".encodeToByteArray())
        assertThat(standaloneCloudPath("rms1.$unsafe.gci")).isNull()
    }

    @Test
    fun `standalone archive hash matches RomM content hashing`() {
        val data = temp.newFolder("romm-content")
        File(data, "00000001").mkdir()
        File(data, "00000001/main").writeText("isolated lime3ds save")
        val bytes = checkNotNull(snapshotSaveDirectory(syncFolder(data)))
        assertThat(rommSaveArchiveHash(bytes)).isEqualTo("f8adb79bb556d20b6d28a988faa2c6e4")
    }

    @Test
    fun `portable save archives preserve nested data and reject duplicates and traversal`() {
        val first = temp.newFolder("first")
        File(first, ".game-data").writeText("battery")
        File(first, "folder").mkdir()
        File(first, "folder/extra").writeText("nested")
        val output = temp.newFolder("output")
        val source = syncFolder(first)
        val target = StandaloneDirectory(syncFolder(output), "isolated")
        val bytes = checkNotNull(snapshotSaveDirectory(source))
        assertThat(target.replace(bytes) { true }).isTrue()
        assertThat(target.snapshot()).isEqualTo(bytes)
        assertThat(File(output, "isolated/.game-data").readText()).isEqualTo("battery")
        File(first, ".game-data").writeText("new battery")
        val replacement = checkNotNull(snapshotSaveDirectory(source))
        assertThat(target.replace(replacement) { false }).isFalse()
        assertThat(target.snapshot()).isEqualTo(bytes)
        assertThat(target.replace(replacement) { true }).isTrue()
        assertThat(target.snapshot()).isEqualTo(replacement)
        val compressed = zipOf("save" to "written by another client".encodeToByteArray())
        assertThat(target.replace(compressed) { true }).isTrue()
        assertThat(File(output, "isolated/save").readText()).isEqualTo("written by another client")

        val duplicate = zipOf("save" to byteArrayOf(1), "SAVE" to byteArrayOf(2))
        val outside = zipOf("../outside" to byteArrayOf(3))
        assertThat(runCatching { parseSaveArchive(duplicate) }.isFailure).isTrue()
        assertThat(runCatching { parseSaveArchive(outside) }.isFailure).isTrue()
    }

    @Test
    fun `Dolphin and Lime3DS IDs come from verified binary headers`() {
        val gc = ByteArray(256)
        "GQAE01".encodeToByteArray().copyInto(gc)
        byteArrayOf(0xc2.toByte(), 0x33, 0x9f.toByte(), 0x3d).copyInto(gc, 0x1c)
        assertThat(gameId(StandaloneEmulator.DOLPHIN_GC, "iso", gc)).isEqualTo("GQAE01")
        assertThat(gameId(StandaloneEmulator.DOLPHIN_WII, "iso", gc)).isNull()
        val n3ds = ByteArray(512)
        "NCCH".encodeToByteArray().copyInto(n3ds, 0x100)
        ByteBuffer.wrap(n3ds).order(ByteOrder.LITTLE_ENDIAN).putLong(0x118, 0x0004000000F00100)
        assertThat(gameId(StandaloneEmulator.LIME3DS, "3ds", n3ds)).isEqualTo("0004000000F00100")
        assertThat(gameId(StandaloneEmulator.LIME3DS, "3ds", n3ds.copyOf(4))).isNull()
    }

    private fun syncFolder(directory: File) =
        SyncFolder(directory.path, DirectFileGateway(StorageRoot.Direct(directory.path)), "")

    private fun gameId(profile: StandaloneEmulator, extension: String, bytes: ByteArray) =
        standaloneGameId(profile, extension) { offset, size ->
            if (offset + size <= bytes.size) bytes.copyOfRange(offset.toInt(), offset.toInt() + size) else null
        }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((name, contents) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(contents)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
