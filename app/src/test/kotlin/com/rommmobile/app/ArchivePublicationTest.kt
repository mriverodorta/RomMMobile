package com.rommmobile.app

import com.rommmobile.app.core.storage.DirectFileGateway
import com.rommmobile.app.core.storage.FileGateway
import com.rommmobile.app.core.storage.StorageRoot
import com.rommmobile.app.feature.downloads.engine.Extractor
import com.rommmobile.app.feature.downloads.engine.publishFirmware
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchivePublicationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `an archive collision preserves unrelated ROMs and publishes nothing`() = runTest {
        val roms = temporary.newFolder("roms")
        val existing = roms.resolve("snes/shared/existing.sfc")
        assertTrue(existing.parentFile.mkdirs())
        existing.writeText("existing")
        val archive = temporary.newFile("download.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            for (name in listOf("first.sfc", "shared/incoming.sfc")) {
                zip.putNextEntry(ZipEntry(name))
                zip.write("incoming".toByteArray())
                zip.closeEntry()
            }
        }
        val extractor = Extractor(DirectFileGateway(StorageRoot.Direct(roms.absolutePath)))

        val error = runCatching { extractor.extractZip(archive, "snes", ".stage", { false }) {} }.exceptionOrNull()

        assertTrue(error is IOException)
        assertEquals("existing", existing.readText())
        assertFalse(roms.resolve("snes/first.sfc").exists())
        assertFalse(roms.resolve("snes/.stage").exists())
    }

    @Test
    fun `verified firmware reaches the selected folder`() {
        val folder = temporary.newFolder("system")
        val part = temporary.newFile("download.part").apply { writeText("new firmware") }
        val gateway = DirectFileGateway(StorageRoot.Direct(folder.absolutePath))

        assertTrue(publishFirmware(gateway, part, "bios.bin", 1))
        assertEquals("new firmware", folder.resolve("bios.bin").readText())
        assertFalse(folder.resolve(".romm-firmware-1.tmp").exists())
        assertFalse(part.exists())
    }

    @Test
    fun `firmware appearing during publication is preserved`() {
        val folder = temporary.newFolder("system")
        val part = temporary.newFile("download.part").apply { writeText("incoming") }
        val direct = DirectFileGateway(StorageRoot.Direct(folder.absolutePath))
        val gateway = object : FileGateway by direct {
            override fun moveWithin(fromRelDir: String, name: String, toRelDir: String): Boolean {
                folder.resolve(name).writeText("existing")
                return direct.moveWithin(fromRelDir, name, toRelDir)
            }
        }

        assertTrue(publishFirmware(gateway, part, "bios.bin", 1))
        assertEquals("existing", folder.resolve("bios.bin").readText())
        assertFalse(folder.resolve(".romm-firmware-1.tmp").exists())
    }
}
