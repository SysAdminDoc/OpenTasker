package com.opentasker.core.permissions

import android.app.AppOpsManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageAccessTest {
    @Test
    fun theSettingsSwitchGrantsAccessWithoutThePermission() {
        assertTrue(UsageAccess.grantsUsageAccess(AppOpsManager.MODE_ALLOWED) { false })
    }

    @Test
    fun anOpLeftAtDefaultFollowsAnAdbPermissionGrant() {
        assertTrue(UsageAccess.grantsUsageAccess(AppOpsManager.MODE_DEFAULT) { true })
        assertFalse(UsageAccess.grantsUsageAccess(AppOpsManager.MODE_DEFAULT) { false })
    }

    @Test
    fun switchingAccessOffInSettingsWinsOverTheGrant() {
        assertFalse(UsageAccess.grantsUsageAccess(AppOpsManager.MODE_IGNORED) { true })
        assertFalse(UsageAccess.grantsUsageAccess(AppOpsManager.MODE_ERRORED) { true })
    }
}
