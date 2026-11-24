package com.rommmobile.app

import com.google.common.truth.Truth.assertThat
import com.rommmobile.app.data.api.SaveSyncDetails
import com.rommmobile.app.feature.saves.batteryRestoreVersion
import com.rommmobile.app.feature.saves.bothSaveVersionsChanged
import com.rommmobile.app.feature.saves.canRestoreBeforeFirstLaunch
import com.rommmobile.app.feature.saves.hasExitWrite
import com.rommmobile.app.feature.saves.negotiatedBatteryDownload
import org.junit.Test

class SaveSyncTest {
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
