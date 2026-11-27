package com.rommmobile.app

import com.rommmobile.app.data.api.RomDto
import com.rommmobile.app.data.api.RomFileDto
import com.rommmobile.app.data.repo.RomDownloadSource
import com.rommmobile.app.data.repo.downloadSource
import com.rommmobile.app.data.repo.toEntity
import com.rommmobile.app.data.repo.toModel
import com.rommmobile.app.feature.downloads.engine.downloadFileNameForResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadFilenameTest {
    private val original = "Kirby - Canvas Curse (USA) (Rev 1) [Patch+].nds"
    private val response = """attachment; filename="Kirby - Power Paintbrush.nds""""

    @Test
    fun `preserving the original keeps region revision and patch tags`() {
        assertEquals(original, downloadFileNameForResponse(original, response, hasMd5 = false, preserveOriginal = true))
    }

    @Test
    fun `default naming still accepts the response filename without an MD5`() {
        assertEquals(
            "Kirby - Power Paintbrush.nds",
            downloadFileNameForResponse(original, response, hasMd5 = false, preserveOriginal = false),
        )
    }

    @Test
    fun `default naming still keeps the queued filename with an MD5`() {
        assertEquals(original, downloadFileNameForResponse(original, response, hasMd5 = true, preserveOriginal = false))
    }

    @Test
    fun `preserving an archive keeps the original archive name`() {
        val archive = "Game (Japan) (Disc 1).zip"
        val header = "attachment; filename*=UTF-8''Game%20Title.zip"
        assertEquals(archive, downloadFileNameForResponse(archive, header, hasMd5 = false, preserveOriginal = true))
        assertEquals("Game Title.zip", downloadFileNameForResponse(archive, header, hasMd5 = false, preserveOriginal = false))
    }

    @Test
    fun `a nested ROM uses the physical filename instead of the catalog folder name`() {
        val rom = RomDto(
            id = 1,
            name = "Kirby: Power Paintbrush",
            fsName = "Kirby - Power Paintbrush",
            hasSimpleSingleFile = false,
            hasNestedSingleFile = true,
            files = listOf(RomFileDto(10, fileName = original, category = "game")),
        ).toEntity(Json, 0).toModel(null, Json)

        assertEquals(RomDownloadSource(original, false), rom.downloadSource(preserveOriginal = true))
        assertEquals(RomDownloadSource("Kirby - Power Paintbrush", false), rom.downloadSource(preserveOriginal = false))
    }

    @Test
    fun `a nested ROM with sidecar files still downloads as a generated archive`() {
        val rom = RomDto(
            id = 1,
            fsName = "Kirby - Canvas Curse (USA)",
            hasSimpleSingleFile = false,
            hasNestedSingleFile = true,
            files = listOf(RomFileDto(10, fileName = original), RomFileDto(11, fileName = "Manual.pdf")),
        ).toEntity(Json, 0).toModel(null, Json)

        assertEquals(RomDownloadSource("Kirby - Canvas Curse (USA).zip", true), rom.downloadSource(preserveOriginal = true))
    }

    @Test
    fun `an extensionless queue entry can recover the real name even with an MD5`() {
        val header = "attachment; filename*=UTF-8'en'Kirby%20-%20Canvas%20Curse%20%28USA%29%20%28Rev%201%29%20%5BPatch+%5D.nds"
        assertEquals(
            original,
            downloadFileNameForResponse("Kirby - Power Paintbrush", header, hasMd5 = true, preserveOriginal = true),
        )
    }

    @Test
    fun `a server filename cannot remove a known ROM extension`() {
        val header = """attachment; filename="Kirby - Power Paintbrush""""
        assertEquals(original, downloadFileNameForResponse(original, header, hasMd5 = false, preserveOriginal = true))
    }
}
