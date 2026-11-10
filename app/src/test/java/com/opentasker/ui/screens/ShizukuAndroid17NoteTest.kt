package com.opentasker.ui.screens

import com.opentasker.core.power.ShizukuPowerBackend
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Upstream Shizuku doesn't start reliably on Android 17, and Setup used to send people to its
 * guide with no hint that a fork exists or that wireless debugging can go off at every reboot.
 */
class ShizukuAndroid17NoteTest {
    private fun noteShows(
        sdkInt: Int = 37,
        managerInstalled: Boolean = true,
        killSwitchEnabled: Boolean = false,
        serviceRunning: Boolean = false,
        permissionGranted: Boolean = false,
    ) = shizukuNeedsAndroid17Note(
        ShizukuPowerBackend.statusFor(managerInstalled, killSwitchEnabled, serviceRunning, permissionGranted),
        sdkInt,
    )

    @Test
    fun showsOnAndroid17WhileTheServiceIsNotRunning() {
        assertTrue(noteShows(managerInstalled = false))
        assertTrue(noteShows())
        // Power mode starts off, and a stopped service under it is who the note is for.
        assertTrue(noteShows(killSwitchEnabled = true))
    }

    @Test
    fun aRunningServiceNeedsNoNote() {
        assertFalse(noteShows(serviceRunning = true))
        assertFalse(noteShows(serviceRunning = true, killSwitchEnabled = true))
        assertFalse(noteShows(serviceRunning = true, permissionGranted = true))
        // Sui and the forks can run with no manager package.
        assertFalse(noteShows(managerInstalled = false, serviceRunning = true))
    }

    @Test
    fun neverShowsBeforeAndroid17() {
        for (installed in listOf(true, false)) for (off in listOf(true, false)) for (running in listOf(true, false)) {
            assertFalse(noteShows(sdkInt = 36, managerInstalled = installed, killSwitchEnabled = off, serviceRunning = running))
        }
    }
}
