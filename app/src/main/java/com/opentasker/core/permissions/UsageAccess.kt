package com.opentasker.core.permissions

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process

object UsageAccess {
    fun hasUsageStatsAccess(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return grantsUsageAccess(mode) {
            context.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Reads the app-op the way UsageStatsManager and Settings do. An op left at its default defers
     * to the permission grant, which is what `pm grant <pkg> android.permission.PACKAGE_USAGE_STATS`
     * gives; counting only MODE_ALLOWED paused app triggers that had working access.
     */
    internal fun grantsUsageAccess(mode: Int, permissionGranted: () -> Boolean): Boolean = when (mode) {
        AppOpsManager.MODE_ALLOWED -> true
        AppOpsManager.MODE_DEFAULT -> permissionGranted()
        else -> false
    }
}
