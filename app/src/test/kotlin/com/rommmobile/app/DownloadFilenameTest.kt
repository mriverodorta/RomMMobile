package com.rommmobile.app

import com.rommmobile.app.feature.downloads.engine.downloadFileNameForResponse
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
}
