package com.rommmobile.app

import com.google.common.truth.Truth.assertThat
import com.rommmobile.app.core.esde.EsdeLaunch
import com.rommmobile.app.data.api.SaveSyncDetails
import com.rommmobile.app.data.prefs.SaveSyncMethod
import com.rommmobile.app.feature.saves.batteryRestoreVersion
import com.rommmobile.app.feature.saves.bothSaveVersionsChanged
import com.rommmobile.app.feature.saves.canRestoreBeforeFirstLaunch
import com.rommmobile.app.feature.saves.forBackgroundSync
import com.rommmobile.app.feature.saves.hasExitWrite
import com.rommmobile.app.feature.saves.negotiatedBatteryDownload
import com.rommmobile.app.feature.saves.SaveTransfer
import com.rommmobile.app.feature.saves.rommSaveCore
import com.rommmobile.app.feature.saves.rommSaveHistory
import com.rommmobile.app.feature.saves.rommStateSuffix
import com.rommmobile.app.feature.saves.saveTransfer
import com.rommmobile.app.feature.saves.stateVersionFileName
import com.rommmobile.app.feature.saves.usesRommSaveLayout
import com.rommmobile.app.feature.saves.retroArchCloudSyncUrl
import org.junit.Test

class SaveSyncTest {
    @Test
    fun `excluding RetroArch clears its watcher paths while preserving an ES-DE game override`() {
        val launch = EsdeLaunch(
            system = "psx", game = "./Game.chd", label = "SwanStation", packageName = "com.retroarch.aarch64",
            core = "swanstation", saves = "/saves", states = "/states", saveFoldersVerified = true,
            runtimeLogRoot = "/logs",
        )
        assertThat(launch.forBackgroundSync(excludeRetroArch = false)).isEqualTo(launch)
        assertThat(launch.forBackgroundSync(excludeRetroArch = true)).isEqualTo(
            launch.copy(saves = null, states = null, runtimeLogRoot = null),
        )
        val standalone = launch.copy(packageName = "com.github.stenzek.duckstation", core = null)
        assertThat(standalone.forBackgroundSync(excludeRetroArch = true)).isEqualTo(standalone)
        assertThat(standalone.copy(saveFoldersVerified = false).forBackgroundSync(excludeRetroArch = true).saves).isNull()
    }

    @Test
    fun `the sync method keeps prior choices and defaults to RetroArch for a TV without a picker`() {
        assertThat(SaveSyncMethod.restore(null, true, true)).isEqualTo(SaveSyncMethod.ROMMMOBILES)
        assertThat(SaveSyncMethod.restore(null, false, true)).isEqualTo(SaveSyncMethod.OFF)
        assertThat(SaveSyncMethod.restore(null, null, true)).isEqualTo(SaveSyncMethod.RETROARCH)
        assertThat(SaveSyncMethod.restore(null, null, false)).isEqualTo(SaveSyncMethod.OFF)
        assertThat(SaveSyncMethod.restore("retroarch", true, false)).isEqualTo(SaveSyncMethod.RETROARCH)
    }

    @Test
    fun `the WebDAV address prefers HTTPS and never includes credentials or query parameters`() {
        assertThat(retroArchCloudSyncUrl("http://romm.lan", null, "https://name:password@romm.example/romm/?token=secret#info"))
            .isEqualTo("https://romm.example/romm/api/sync/retroarch/")
        assertThat(retroArchCloudSyncUrl(null, "http://romm.lan:8080", null))
            .isEqualTo("http://romm.lan:8080/api/sync/retroarch/")
        assertThat(retroArchCloudSyncUrl(null, null, null)).isNull()
    }

    @Test
    fun `native save layout selects RomM WebDAV battery heads and excludes old named slots`() {
        val earlier = "2026-01-01T12:00:00Z"
        val later = "2026-01-01T12:01:00Z"
        val head = SaveSyncDetails(1, earlier, "head", earlier, "autosave", "snes9x", fileName = "Test [v1].srm")
        val rows = listOf(
            head,
            head.copy(id = 2, updatedAt = later, slot = "retroarch-snes9x:srm"),
            head.copy(id = 3, updatedAt = later, emulator = "bsnes"),
            head.copy(id = 4, updatedAt = later, slot = null, fileName = "Test.srm"),
            head.copy(id = 5, updatedAt = later, missing = true),
        )
        assertThat(rommSaveHistory(rows, "Test", "snes9x", ".srm")).containsExactly(head)
        assertThat(usesRommSaveLayout("5.4.1")).isTrue()
        assertThat(usesRommSaveLayout("5.3.0")).isFalse()
        assertThat(rommSaveCore("mupen64plus_next_gles3")).isEqualTo("mupen64plus_next")
    }

    @Test
    fun `native state versions preserve auto and numbered slots without replacing older rows`() {
        val first = stateVersionFileName("snes9x", ".state.auto")
        val second = stateVersionFileName("snes9x", ".state.auto")
        assertThat(first).isNotEqualTo(second)
        assertThat(rommStateSuffix(first)).isEqualTo(".state.auto")
        assertThat(rommStateSuffix(stateVersionFileName("snes9x", ".state96"))).isEqualTo(".state96")
        assertThat(rommStateSuffix("Test.state1.png")).isNull()
    }

    @Test
    fun `native sync preserves divergent files and restores one sided changes`() {
        assertThat(saveTransfer("local", "remote", "base", false)).isEqualTo(SaveTransfer.CONFLICT)
        assertThat(saveTransfer("base", "remote", "base", false)).isEqualTo(SaveTransfer.DOWNLOAD)
        assertThat(saveTransfer("local", "base", "base", false)).isEqualTo(SaveTransfer.UPLOAD)
        assertThat(saveTransfer("old", "base", "base", true)).isEqualTo(SaveTransfer.DOWNLOAD)
        assertThat(saveTransfer("local", null, "base", false)).isEqualTo(SaveTransfer.CONFLICT)
    }

    @Test
    fun `a replay of the old battery save cannot hide newly uploaded progress`() {
        val old = SaveSyncDetails(1, "2026-01-01T12:00:00Z", "old", "2026-01-01T12:10:00Z")
        val newer = SaveSyncDetails(2, "2026-01-01T12:05:00Z", "new", "2026-01-01T12:05:00Z")
        val history = listOf(newer, old)
        assertThat(batteryRestoreVersion(history, "old", "old")).isEqualTo(newer)
        assertThat(batteryRestoreVersion(history, null, "old")).isEqualTo(newer)
        assertThat(batteryRestoreVersion(history, null, null)).isEqualTo(newer)
        assertThat(batteryRestoreVersion(history, "new", "old")).isEqualTo(newer)
        assertThat(batteryRestoreVersion(history, "other", "old")).isNull()
        assertThat(batteryRestoreVersion(history, "new", "edited")).isNull()
        assertThat(batteryRestoreVersion(history, "old", "edited")).isNull()
        assertThat(batteryRestoreVersion(history, null, "unknown")).isNull()
        assertThat(batteryRestoreVersion(history, "new", "new")).isNull()
    }

    @Test
    fun `before first launch requires current boot history without a gameplay session`() {
        val boot = 1_000_000L
        val now = boot + 25_000L
        assertThat(canRestoreBeforeFirstLaunch(now, boot, boot + 5_000, 0, 0)).isTrue()
        assertThat(canRestoreBeforeFirstLaunch(now, boot, 0, 0, 0)).isFalse()
        assertThat(canRestoreBeforeFirstLaunch(now, boot, boot - 100_000, 0, 0)).isFalse()
        assertThat(canRestoreBeforeFirstLaunch(now, boot, boot + 5_000, boot + 8_000, 0)).isFalse()
        assertThat(canRestoreBeforeFirstLaunch(now, boot, boot + 5_000, 0, boot + 8_000)).isFalse()
    }

    @Test
    fun `two offline branches conflict while a one sided change can sync`() {
        assertThat(bothSaveVersionsChanged("base", "local", "remote")).isTrue()
        assertThat(bothSaveVersionsChanged("base", "base", "remote")).isFalse()
        assertThat(bothSaveVersionsChanged("base", "local", "base")).isFalse()
        assertThat(bothSaveVersionsChanged("base", "same", "same")).isFalse()
    }

    @Test
    fun `battery reconciliation respects delete and conflict decisions`() {
        val old = SaveSyncDetails(1, "2026-01-01T12:00:00Z", "old")
        val latest = SaveSyncDetails(2, "2026-01-01T12:01:00Z", "latest")
        val history = listOf(old, latest)
        assertThat(negotiatedBatteryDownload("conflict", history, "old", "old")).isNull()
        assertThat(negotiatedBatteryDownload("delete", history, "old", "old")).isNull()
        assertThat(negotiatedBatteryDownload("download", history, "old", "old")).isEqualTo(latest)
        assertThat(negotiatedBatteryDownload("no_op", history, "old", "old")).isEqualTo(latest)
    }

    @Test
    fun `an unload write confirms exit but a backgrounded or resumed activity does not`() {
        assertThat(hasExitWrite(30_000, 10_000, 25_000) { listOf(24_000L) }).isTrue()
        assertThat(hasExitWrite(30_000, 10_000, 25_000) { emptyList() }).isFalse()
        assertThat(hasExitWrite(30_000, 28_000, 25_000) { listOf(24_000L) }).isFalse()
        assertThat(hasExitWrite(30_000, 10_000, 25_000) { listOf(9_000L) }).isFalse()
    }
}
