package com.rommmobile.app

import com.google.common.truth.Truth.assertThat
import com.rommmobile.app.feature.saves.bothSaveVersionsChanged
import com.rommmobile.app.feature.saves.canRestoreBeforeFirstLaunch
import org.junit.Test

class SaveSyncTest {
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
}
