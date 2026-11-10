package com.opentasker.ui.screens

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.core.content.ContextCompat
import com.opentasker.app.BuildConfig
import com.opentasker.app.R
import com.opentasker.core.apps.TargetSignerGuard
import com.opentasker.core.apps.TargetSignerState
import com.opentasker.core.contexts.bluetoothScanPermission
import com.opentasker.core.actions.VpnClient
import com.opentasker.core.actions.VpnTunnelPolicy
import com.opentasker.core.actions.hasWriteSecureSettings
import com.opentasker.core.actions.secureSettingsGrantCommand
import com.opentasker.core.permissions.OemBatteryGuidance
import com.opentasker.core.permissions.RuntimePermissionRequestHistory
import com.opentasker.core.permissions.UsageAccess
import com.opentasker.core.power.ShizukuPowerBackend
import com.opentasker.core.power.ShizukuPowerState
import com.opentasker.core.power.ShizukuPowerStatus
import com.opentasker.core.scheduling.ExactAlarmSupport
import com.opentasker.core.scripting.TermuxScriptBackend
import com.opentasker.core.scripting.TermuxScriptState
import com.opentasker.core.capabilities.SetupRequirement
import com.opentasker.core.platform.LockDeviceAdminReceiver
import com.opentasker.core.platform.PromotedOngoingNotificationSupport
import com.opentasker.core.logging.AppLogger

/**
 * The Setup screen's row catalogue: what each permission row is, how it reads its granted
 * state, and what tapping its action does.
 *
 * This lived in PermissionOnboardingScreen.kt until that file reached 2,390 lines against a
 * 2,400-line ceiling, which meant the next capability added to Setup would have failed the
 * build. The screen keeps layout and the state machine; the rows and their platform probes
 * live here.
 */

internal fun buildPermissionItems(
    context: Context,
    permissionHistory: RuntimePermissionRequestHistory,
): List<PermissionSetupItem> {
    val shizukuStatus = ShizukuPowerBackend.inspect(context)
    val shizukuActionLabel = context.getString(when (shizukuStatus.state) {
        ShizukuPowerState.NotInstalled -> R.string.setup_action_open_setup_guide
        ShizukuPowerState.ManagerInstalled -> R.string.setup_action_open_shizuku_settings
        ShizukuPowerState.PermissionNeeded -> R.string.setup_action_request_permission
        ShizukuPowerState.BackendUnavailable,
        ShizukuPowerState.Ready,
        -> R.string.setup_action_disable_power_mode
        ShizukuPowerState.Disabled -> R.string.setup_action_enable_power_mode
    })
    val shizukuSummary = context.getString(when (shizukuStatus.state) {
        ShizukuPowerState.NotInstalled -> R.string.setup_shizuku_status_not_installed
        ShizukuPowerState.ManagerInstalled -> R.string.setup_shizuku_status_manager_stopped
        ShizukuPowerState.PermissionNeeded -> R.string.setup_shizuku_status_permission_needed
        ShizukuPowerState.BackendUnavailable -> R.string.setup_shizuku_status_transport_unavailable
        ShizukuPowerState.Ready -> R.string.setup_shizuku_status_ready
        ShizukuPowerState.Disabled -> R.string.setup_shizuku_status_disabled
    })
    val shizukuAction = when (shizukuStatus.state) {
        ShizukuPowerState.NotInstalled -> PermissionAction.SettingsIntent(
            Intent(Intent.ACTION_VIEW, Uri.parse(ShizukuPowerBackend.SETUP_URL)),
        )
        // The service is started from inside Shizuku, so App info would be a dead end.
        ShizukuPowerState.ManagerInstalled -> PermissionAction.SettingsIntent(
            context.packageManager.getLaunchIntentForPackage(ShizukuPowerBackend.MANAGER_PACKAGE)
                ?: packageDetailsIntent(ShizukuPowerBackend.MANAGER_PACKAGE),
        )
        ShizukuPowerState.PermissionNeeded -> PermissionAction.ShizukuPermission
        ShizukuPowerState.BackendUnavailable,
        ShizukuPowerState.Ready,
        -> PermissionAction.ShizukuKillSwitch(enabled = true)
        ShizukuPowerState.Disabled -> PermissionAction.ShizukuKillSwitch(enabled = false)
    }
    val termuxStatus = TermuxScriptBackend.inspect(context)
    val termuxSummary = context.getString(when (termuxStatus.state) {
        TermuxScriptState.TermuxMissing -> R.string.setup_termux_status_missing
        TermuxScriptState.VersionUnsupported -> R.string.setup_termux_status_version_unsupported
        TermuxScriptState.PermissionRequired -> R.string.setup_termux_status_permission_needed
        TermuxScriptState.Ready -> R.string.setup_termux_status_ready
    })
    val oem = OemBatteryGuidance.forDevice(Build.MANUFACTURER, Build.BRAND)
    // Every platform check below runs through probes, so one that throws on some OEM build marks
    // its own row "Couldn't check" instead of crashing Setup and Settings (#20).
    val probes = SetupProbes(::logFailedCheck)
    val exactAlarms = probes.granted("exact alarms") { ExactAlarmSupport.canScheduleExactAlarms(context) }
    val battery = probes.granted("battery optimization") { ignoresBatteryOptimizations(context) }
    val usageAccess = probes.granted("usage access") { UsageAccess.hasUsageStatsAccess(context) }
    val notificationAccess = probes.granted("notification access") { hasNotificationListenerAccess(context) }
    val overlay = probes.granted("display over other apps") { Settings.canDrawOverlays(context) }
    val writeSettings = probes.granted("modify system settings") { Settings.System.canWrite(context) }
    val deviceAdmin = probes.granted("device admin") { LockDeviceAdminReceiver.isActive(context) }
    val dndAccess = probes.granted("Do Not Disturb access") { hasNotificationPolicyAccess(context) }
    val secureSettings = probes.granted("secure settings") { hasWriteSecureSettings(context) }
    val request = context.getString(R.string.setup_action_request)
    val openSettings = context.getString(R.string.setup_action_open_settings)
    val promotedSupported = PromotedOngoingNotificationSupport.isPlatformSupported()
    val promotedGranted = !promotedSupported || probes.read("promoted notifications", false) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager != null && PromotedOngoingNotificationSupport.canPostPromotedNotifications(manager)
    }
    return listOfNotNull(
        PermissionSetupItem(
            title = context.getString(R.string.setup_notifications_card_title),
            body = context.getString(R.string.setup_notifications_card_body),
            granted = Build.VERSION.SDK_INT < 33 || hasPermission(context, Manifest.permission.POST_NOTIFICATIONS),
            actionLabel = request,
            action = if (Build.VERSION.SDK_INT >= 33) {
                PermissionAction.RuntimePermission(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                PermissionAction.None
            },
            requiredFor = context.getString(R.string.setup_notifications_required_for),
            requirements = setOf(SetupRequirement.POST_NOTIFICATIONS),
        ),
        if (promotedSupported) PermissionSetupItem(
            title = context.getString(R.string.setup_promoted_notifications_title),
            body = context.getString(R.string.setup_promoted_notifications_body),
            granted = promotedGranted,
            actionLabel = openSettings,
            action = PermissionAction.SettingsIntent(
                Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            ),
            requiredFor = context.getString(R.string.setup_promoted_notifications_required_for),
            optional = true,
            section = SetupSection.RELIABILITY,
        ) else null,
        PermissionSetupItem(
            title = context.getString(R.string.setup_exact_alarm_card_title),
            body = context.getString(R.string.setup_exact_alarm_card_body),
            granted = exactAlarms == true,
            unavailable = exactAlarms == null,
            actionLabel = openSettings,
            action = PermissionAction.SettingsIntent(ExactAlarmSupport.settingsIntent(context)),
            requiredFor = context.getString(R.string.setup_exact_alarm_required_for),
        ),
        PermissionSetupItem(
            title = context.getString(R.string.setup_battery_title),
            body = context.getString(R.string.setup_battery_card_body, oem.summary),
            granted = battery == true,
            unavailable = battery == null,
            actionLabel = openSettings,
            action = PermissionAction.SettingsIntent(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)),
            requiredFor = context.getString(R.string.setup_battery_required_for),
            section = SetupSection.RELIABILITY,
        ),
        if (oem.needsExtraSteps) PermissionSetupItem(
            title = context.getString(R.string.setup_oem_guidance_title, oem.oemName),
            body = context.getString(
                R.string.setup_oem_guidance_body,
                oem.oemName,
                context.getString(oem.riskLevel.labelRes()),
                oem.steps.mapIndexed { index, step -> "${index + 1}. $step" }.joinToString("\n"),
                oem.dontKillMyAppUrl,
            ),
            granted = false,
            actionLabel = if (oem.settingsTargets.isNotEmpty()) {
                context.getString(R.string.setup_action_open_oem_settings, oem.oemName)
            } else {
                context.getString(R.string.setup_action_open_dontkillmyapp)
            },
            action = PermissionAction.OemSettings(oem.settingsTargets, oem.dontKillMyAppUrl),
            requiredFor = context.getString(R.string.setup_oem_required_for, oem.oemName),
            optional = true,
            section = SetupSection.RELIABILITY,
        ) else null,
        PermissionSetupItem(
            title = context.getString(R.string.setup_usage_card_title),
            body = context.getString(R.string.setup_usage_card_body),
            granted = usageAccess == true,
            unavailable = usageAccess == null,
            actionLabel = openSettings,
            action = PermissionAction.SettingsIntent(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)),
            requiredFor = context.getString(R.string.setup_usage_required_for),
            section = SetupSection.NEEDED,
            requirements = setOf(SetupRequirement.USAGE_ACCESS),
        ),
        PermissionSetupItem(
            title = context.getString(R.string.setup_notification_access_title),
            body = context.getString(R.string.setup_notification_access_body),
            granted = notificationAccess == true,
            unavailable = notificationAccess == null,
            actionLabel = openSettings,
            action = PermissionAction.SettingsIntent(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)),
            requiredFor = context.getString(R.string.setup_notification_access_required_for),
            section = SetupSection.NEEDED,
            requirements = setOf(SetupRequirement.NOTIFICATION_ACCESS),
        ),
        PermissionSetupItem(
            title = context.getString(R.string.setup_calendar_access_title),
            body = context.getString(R.string.setup_calendar_access_body),
            granted = hasPermission(context, Manifest.permission.READ_CALENDAR),
            actionLabel = request,
            action = PermissionAction.RuntimePermission(Manifest.permission.READ_CALENDAR),
            requiredFor = context.getString(R.string.setup_calendar_access_required_for),
            section = SetupSection.NEEDED,
            requirements = setOf(SetupRequirement.CALENDAR),
        ),
        PermissionSetupItem(
            title = context.getString(R.string.setup_contacts_access_title),
            body = context.getString(R.string.setup_contacts_access_body),
            granted = hasPermission(context, Manifest.permission.READ_CONTACTS),
            actionLabel = request,
            action = PermissionAction.RuntimePermission(Manifest.permission.READ_CONTACTS),
            requiredFor = context.getString(R.string.setup_contacts_access_required_for),
            section = SetupSection.NEEDED,
            requirements = setOf(SetupRequirement.CONTACTS),
        ),
        PermissionSetupItem(
            title = context.getString(R.string.setup_overlay_access_title),
            body = context.getString(R.string.setup_overlay_access_body),
            granted = overlay == true,
            unavailable = overlay == null,
            actionLabel = openSettings,
            action = PermissionAction.SettingsIntent(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
            ),
            requiredFor = context.getString(R.string.setup_overlay_access_required_for),
            section = SetupSection.NEEDED,
            requirements = setOf(SetupRequirement.OVERLAY),
        ),
        PermissionSetupItem(
            title = context.getString(R.string.setup_write_settings_title),
            body = context.getString(R.string.setup_write_settings_body),
            granted = writeSettings == true,
            unavailable = writeSettings == null,
            actionLabel = openSettings,
            action = PermissionAction.SettingsIntent(
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")),
            ),
            requiredFor = context.getString(R.string.setup_write_settings_required_for),
            optional = true,
            section = SetupSection.NEEDED,
            requirements = setOf(SetupRequirement.WRITE_SETTINGS),
        ),
        PermissionSetupItem(
            title = context.getString(R.string.setup_device_admin_title),
            body = context.getString(R.string.setup_device_admin_body),
            granted = deviceAdmin == true,
            unavailable = deviceAdmin == null,
            actionLabel = if (deviceAdmin == true) {
                context.getString(R.string.setup_device_admin_turn_off)
            } else {
                context.getString(R.string.setup_device_admin_turn_on)
            },
            action = if (deviceAdmin == true) {
                PermissionAction.RemoveDeviceAdmin
            } else {
                PermissionAction.SettingsIntent(
                    Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                        .putExtra(
                            DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                            LockDeviceAdminReceiver.component(context),
                        )
                        .putExtra(
                            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                            context.getString(R.string.setup_device_admin_explanation),
                        ),
                )
            },
            requiredFor = context.getString(R.string.setup_device_admin_required_for),
            optional = true,
            section = SetupSection.NEEDED,
            requirements = setOf(SetupRequirement.DEVICE_ADMIN),
        ),
        PermissionSetupItem(
            title = context.getString(R.string.setup_foreground_location_title),
            body = context.getString(
                if (!hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) &&
                    hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
                ) {
                    R.string.setup_foreground_location_body_approximate
                } else {
                    R.string.setup_foreground_location_body
                },
            ),
            // Precise only: approximate location cannot read a Wi-Fi name or hold a small geofence,
            // and counting it as ready left those contexts failing behind a green row.
            granted = hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION),
            actionLabel = request,
            // Android 12+ ignores a request for fine location that does not also ask for coarse.
            action = PermissionAction.RuntimePermission(
                Manifest.permission.ACCESS_FINE_LOCATION,
                requestedWith = listOf(Manifest.permission.ACCESS_COARSE_LOCATION),
            ),
            requiredFor = context.getString(R.string.setup_foreground_location_required_for),
            section = SetupSection.NEEDED,
            requirements = setOf(SetupRequirement.FOREGROUND_LOCATION),
        ),
        if (Build.VERSION.SDK_INT >= 29) PermissionSetupItem(
            title = context.getString(R.string.setup_activity_recognition_title),
            body = context.getString(R.string.setup_activity_recognition_body),
            granted = hasPermission(context, Manifest.permission.ACTIVITY_RECOGNITION),
            actionLabel = request,
            action = PermissionAction.RuntimePermission(Manifest.permission.ACTIVITY_RECOGNITION),
            requiredFor = context.getString(R.string.setup_activity_recognition_required_for),
            section = SetupSection.NEEDED,
            requirements = setOf(SetupRequirement.PHYSICAL_ACTIVITY),
        ) else null,
        PermissionSetupItem(
            title = context.getString(R.string.setup_nearby_wifi_title),
            body = context.getString(R.string.setup_nearby_wifi_body),
            granted = Build.VERSION.SDK_INT < 33 || hasPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES),
            actionLabel = request,
            action = if (Build.VERSION.SDK_INT >= 33) {
                PermissionAction.RuntimePermission(Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {
                PermissionAction.None
            },
            requiredFor = context.getString(R.string.setup_nearby_wifi_required_for),
            section = SetupSection.NEEDED,
            requirements = setOf(SetupRequirement.NEARBY_WIFI),
        ),
        PermissionSetupItem(
            title = context.getString(R.string.setup_background_location_title),
            body = context.getString(if (Build.VERSION.SDK_INT >= 30) R.string.setup_background_location_body_modern else R.string.setup_background_location_body_legacy),
            granted = Build.VERSION.SDK_INT < 29 || hasPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            actionLabel = context.getString(R.string.action_open_app_settings),
            action = PermissionAction.SettingsIntent(appDetailsIntent(context)),
            requiredFor = context.getString(R.string.setup_background_location_required_for),
            section = SetupSection.NEEDED,
            requirements = setOf(SetupRequirement.BACKGROUND_LOCATION),
        ),
        PermissionSetupItem(
            title = context.getString(R.string.setup_bluetooth_title),
            body = context.getString(R.string.setup_bluetooth_body),
            granted = Build.VERSION.SDK_INT < 31 || hasPermission(context, Manifest.permission.BLUETOOTH_CONNECT),
            actionLabel = request,
            action = if (Build.VERSION.SDK_INT >= 31) {
                PermissionAction.RuntimePermission(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                PermissionAction.None
            },
            requiredFor = context.getString(R.string.setup_bluetooth_required_for),
            section = SetupSection.NEEDED,
            requirements = setOf(SetupRequirement.BLUETOOTH),
        ),
        PermissionSetupItem(
            title = context.getString(R.string.setup_bluetooth_scan_title),
            body = context.getString(R.string.setup_bluetooth_scan_body),
            granted = hasPermission(context, bluetoothScanPermission()),
            actionLabel = request,
            action = PermissionAction.RuntimePermission(bluetoothScanPermission()),
            requiredFor = context.getString(R.string.setup_bluetooth_scan_required_for),
            section = SetupSection.NEEDED,
            requirements = setOf(SetupRequirement.BLUETOOTH_SCAN),
        ),
        // Below Android 15 there is nothing to grant: the trigger can't fire at all. Listing it as
        // Ready said the opposite, so the row is left out and Setup's platform card names the
        // profiles that wait on it instead.
        if (Build.VERSION.SDK_INT >= 35) PermissionSetupItem(
            title = context.getString(R.string.setup_screen_recording_title),
            body = context.getString(R.string.setup_screen_recording_body),
            granted = hasPermission(context, Manifest.permission.DETECT_SCREEN_RECORDING),
            actionLabel = context.getString(R.string.status_ready),
            action = PermissionAction.None,
            requiredFor = context.getString(R.string.setup_screen_recording_required_for),
            optional = true,
            section = SetupSection.OPTIONAL,
            requirements = setOf(SetupRequirement.SCREEN_RECORDING),
        ) else null,
        if (Build.VERSION.SDK_INT >= ANDROID_17_API) PermissionSetupItem(
            title = context.getString(R.string.setup_local_network_title),
            body = context.getString(R.string.setup_local_network_body),
            granted = hasPermission(context, "android.permission.ACCESS_LOCAL_NETWORK"),
            actionLabel = request,
            action = PermissionAction.RuntimePermission("android.permission.ACCESS_LOCAL_NETWORK"),
            requiredFor = context.getString(R.string.setup_local_network_required_for),
            section = SetupSection.NEEDED,
            requirements = setOf(SetupRequirement.LOCAL_NETWORK),
        ) else null,
        if (BuildConfig.SMS_ACTION_AVAILABLE) PermissionSetupItem(
            title = context.getString(R.string.setup_sms_title),
            body = context.getString(R.string.setup_sms_body),
            granted = hasPermission(context, Manifest.permission.SEND_SMS),
            actionLabel = request,
            action = PermissionAction.RuntimePermission(Manifest.permission.SEND_SMS),
            requiredFor = context.getString(R.string.setup_sms_required_for),
            section = SetupSection.NEEDED,
            requirements = setOf(SetupRequirement.SMS),
        ) else null,
        if (BuildConfig.SMS_RECEIVE_AVAILABLE) PermissionSetupItem(
            title = context.getString(R.string.setup_sms_receive_title),
            body = context.getString(R.string.setup_sms_receive_body),
            granted = hasPermission(context, Manifest.permission.RECEIVE_SMS),
            actionLabel = request,
            action = PermissionAction.RuntimePermission(Manifest.permission.RECEIVE_SMS),
            requiredFor = context.getString(R.string.setup_sms_receive_required_for),
            section = SetupSection.NEEDED,
            requirements = setOf(SetupRequirement.SMS_RECEIVE),
        ) else null,
        if (BuildConfig.SMS_ACTION_AVAILABLE) PermissionSetupItem(
            title = context.getString(R.string.setup_phone_state_title),
            body = context.getString(R.string.setup_phone_state_body),
            granted = hasPermission(context, Manifest.permission.READ_PHONE_STATE),
            actionLabel = request,
            action = PermissionAction.RuntimePermission(Manifest.permission.READ_PHONE_STATE),
            requiredFor = context.getString(R.string.setup_phone_state_required_for),
            section = SetupSection.NEEDED,
            requirements = setOf(SetupRequirement.PHONE_STATE),
        ) else null,
        if (BuildConfig.SMS_ACTION_AVAILABLE) PermissionSetupItem(
            title = context.getString(R.string.setup_call_log_title),
            body = context.getString(R.string.setup_call_log_body),
            granted = hasPermission(context, Manifest.permission.READ_CALL_LOG),
            actionLabel = request,
            action = PermissionAction.RuntimePermission(Manifest.permission.READ_CALL_LOG),
            requiredFor = context.getString(R.string.setup_call_log_required_for),
            section = SetupSection.NEEDED,
            requirements = setOf(SetupRequirement.CALL_LOG),
        ) else null,
        PermissionSetupItem(
            title = context.getString(R.string.setup_dnd_title),
            body = context.getString(R.string.setup_dnd_body),
            granted = dndAccess == true,
            unavailable = dndAccess == null,
            actionLabel = openSettings,
            action = PermissionAction.SettingsIntent(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)),
            requiredFor = context.getString(R.string.setup_dnd_required_for),
            section = SetupSection.NEEDED,
            requirements = setOf(SetupRequirement.DND),
        ),
        PermissionSetupItem(
            title = context.getString(R.string.setup_secure_settings_title),
            body = context.getString(
                R.string.setup_secure_settings_body,
                secureSettingsGrantCommand(context.packageName),
            ),
            granted = secureSettings == true,
            unavailable = secureSettings == null,
            // There is nothing to open: no settings page exposes this, and no runtime dialog can
            // ask for it. Handing over the exact command is the whole of what this row can do.
            actionLabel = context.getString(R.string.setup_secure_settings_copy),
            action = PermissionAction.CopyText(
                context.getString(R.string.setup_secure_settings_title),
                secureSettingsGrantCommand(context.packageName),
            ),
            requiredFor = context.getString(R.string.setup_secure_settings_required_for),
            optional = true,
            // No allowActionWhenGranted: the granted row renders a chevron labelled "Review app
            // settings", which is wrong here, and a command to grant something already granted is
            // not worth offering.
            section = SetupSection.OPTIONAL,
            requirements = setOf(SetupRequirement.SECURE_SETTINGS),
        ),
        PermissionSetupItem(
            title = context.getString(R.string.setup_shizuku_title),
            body = shizukuSummary,
            granted = shizukuStatus.isReady,
            actionLabel = shizukuActionLabel,
            action = shizukuAction,
            requiredFor = context.getString(R.string.setup_shizuku_required_for),
            optional = true,
            allowActionWhenGranted = true,
            section = SetupSection.OPTIONAL,
            note = context.getString(R.string.setup_shizuku_android17_note)
                .takeIf { shizukuNeedsAndroid17Note(shizukuStatus, Build.VERSION.SDK_INT) },
        ),
        if (VpnClient.WIREGUARD.isInstalled(context)) PermissionSetupItem(
            title = context.getString(R.string.setup_wireguard_title),
            body = context.getString(R.string.setup_wireguard_body),
            granted = hasPermission(context, VpnTunnelPolicy.WIREGUARD_CONTROL_PERMISSION),
            actionLabel = request,
            action = PermissionAction.RuntimePermission(VpnTunnelPolicy.WIREGUARD_CONTROL_PERMISSION),
            requiredFor = context.getString(R.string.setup_wireguard_required_for),
            optional = true,
            section = SetupSection.OPTIONAL,
        ) else null,
        PermissionSetupItem(
            title = context.getString(R.string.setup_termux_title),
            body = context.getString(R.string.setup_termux_body, termuxSummary),
            granted = termuxStatus.isReady,
            actionLabel = when (termuxStatus.state) {
                TermuxScriptState.PermissionRequired -> request
                TermuxScriptState.Ready -> context.getString(R.string.action_open_app_settings)
                TermuxScriptState.TermuxMissing,
                TermuxScriptState.VersionUnsupported,
                -> context.getString(R.string.setup_action_open_setup_guide)
            },
            action = when (termuxStatus.state) {
                TermuxScriptState.PermissionRequired -> PermissionAction.RuntimePermission(TermuxScriptBackend.RUN_COMMAND_PERMISSION)
                TermuxScriptState.Ready -> PermissionAction.SettingsIntent(packageDetailsIntent(TermuxScriptBackend.TERMUX_PACKAGE))
                TermuxScriptState.TermuxMissing,
                TermuxScriptState.VersionUnsupported,
                -> PermissionAction.SettingsIntent(Intent(Intent.ACTION_VIEW, Uri.parse(TermuxScriptBackend.SETUP_URL)))
            },
            requiredFor = context.getString(R.string.setup_termux_required_for),
            optional = true,
            section = SetupSection.OPTIONAL,
        ),
        PermissionSetupItem(
            title = context.getString(R.string.setup_app_visibility_title),
            body = context.getString(R.string.setup_app_visibility_body),
            granted = true,
            actionLabel = context.getString(R.string.status_ready),
            action = PermissionAction.SettingsIntent(appDetailsIntent(context)),
            requiredFor = context.getString(R.string.setup_app_visibility_required_for),
            allowActionWhenGranted = true,
        ),
    ).plus(targetSignerRows(context, probes))
        .map { item -> item.withRuntimePermissionRecovery(context, permissionHistory) }
}

/**
 * One row per plugin or Termux whose signing certificate no longer matches its pin. OpenTasker
 * sends it nothing until the user re-approves, and this row is the way back. The title carries
 * the package name because rows are keyed by title.
 */
private fun targetSignerRows(context: Context, probes: SetupProbes): List<PermissionSetupItem> =
    probes.read("pinned app signing certificates", emptyList()) {
        TargetSignerGuard.forContext(context).statuses().filter { it.state == TargetSignerState.MISMATCH }
    }.map { status ->
        val packageName = status.pin.packageName
        val explanation = context.getString(R.string.setup_target_signer_body, packageName)
        PermissionSetupItem(
            title = context.getString(R.string.setup_target_signer_title, packageName),
            body = explanation,
            granted = false,
            actionLabel = context.getString(R.string.setup_target_signer_reapprove),
            action = PermissionAction.ReapproveTargetSigner(packageName),
            requiredFor = context.getString(R.string.setup_target_signer_required_for),
            section = SetupSection.OPTIONAL,
            note = explanation,
        )
    }

/** What Setup still lists as missing, a row that couldn't be checked included. */
internal fun pendingSetupRequirements(context: Context): Set<SetupRequirement> =
    buildPermissionItems(context, RuntimePermissionRequestHistory(context))
        .filter { !it.granted || it.unavailable }
        .flatMapTo(mutableSetOf()) { it.requirements }

/** Everything Setup doesn't list as missing, the same split a task's Needs setup pill reads. */
internal fun grantedSetupRequirements(pending: Set<SetupRequirement>): Set<SetupRequirement> =
    SetupRequirement.entries.toSet() - pending

/**
 * A Needs setup pill adds its rows to Setup's focus. Replacing it would drop a template's
 * context rows, such as Calendar, and no pill could bring those back.
 */
internal fun withSetupFocus(focus: List<String>, needed: Set<SetupRequirement>): List<String> =
    (focus + needed.map(SetupRequirement::name)).distinct()

/**
 * Upstream Shizuku hasn't changed since 2025-06 and doesn't start reliably on Android 17, so a
 * row still waiting on its service names the fork and the reboot limit. A running service needs neither.
 * Power mode starts off, so a stopped service usually reads as Disabled and the state alone misses it.
 */
internal fun shizukuNeedsAndroid17Note(status: ShizukuPowerStatus, sdkInt: Int): Boolean =
    sdkInt >= ANDROID_17_API && !status.serviceRunning

private fun PermissionSetupItem.withRuntimePermissionRecovery(
    context: Context,
    history: RuntimePermissionRequestHistory,
): PermissionSetupItem {
    val runtimePermission = action as? PermissionAction.RuntimePermission ?: return this
    if (granted) {
        history.clear(runtimePermission.permission)
        return this
    }
    if (!history.requiresSettings(runtimePermission.permission)) return this
    return copy(
        body = context.getString(R.string.setup_body_with_recovery, body, context.getString(R.string.permission_denied_settings_body)),
        actionLabel = context.getString(R.string.action_open_app_settings),
        action = PermissionAction.SettingsIntent(appDetailsIntent(context)),
    )
}

private const val ANDROID_17_API = 37
internal const val SHIZUKU_PERMISSION_REQUEST_CODE = 4107

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

internal fun logFailedCheck(name: String, error: Throwable) {
    AppLogger.warn("OpenTasker.Setup", "Setup couldn't check $name on this device", error)
}

private fun ignoresBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(PowerManager::class.java)
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

private fun hasNotificationListenerAccess(context: Context): Boolean {
    val enabledListeners = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    return enabledListeners?.contains(context.packageName, ignoreCase = true) == true
}

private fun hasNotificationPolicyAccess(context: Context): Boolean {
    val notificationManager = context.getSystemService(NotificationManager::class.java)
    return notificationManager.isNotificationPolicyAccessGranted
}

private fun appDetailsIntent(context: Context): Intent =
    packageDetailsIntent(context.packageName)

private fun packageDetailsIntent(packageName: String): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Opens a public project link in a browser.
 *
 * Deliberately not [openSettingsIntent]: that reports a missing handler as "Settings screen is
 * unavailable on this device", which is the wrong sentence entirely when what failed was a link
 * to the repository.
 */
internal fun openExternalLink(context: Context, url: String, onMessage: (String) -> Unit) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (ex: ActivityNotFoundException) {
        AppLogger.warn("OpenTasker.Setup", "No activity can open an external link", ex)
        onMessage(context.getString(R.string.setup_link_unavailable))
    } catch (ex: SecurityException) {
        AppLogger.warn("OpenTasker.Setup", "Opening an external link was denied", ex)
        onMessage(context.getString(R.string.setup_link_unavailable))
    }
}

internal fun openSettingsIntent(context: Context, intent: Intent, onMessage: (String) -> Unit) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (ex: ActivityNotFoundException) {
        AppLogger.warn("OpenTasker.Setup", "Settings activity is unavailable", ex)
        onMessage(context.getString(R.string.setup_settings_unavailable, context.getString(R.string.setup_error_no_handler)))
    } catch (ex: SecurityException) {
        AppLogger.warn("OpenTasker.Setup", "Settings activity was denied", ex)
        onMessage(context.getString(R.string.setup_settings_open_failed, context.getString(R.string.setup_error_permission_denied)))
    }
}

private fun OemBatteryGuidance.RiskLevel.labelRes(): Int = when (this) {
    OemBatteryGuidance.RiskLevel.LOW -> R.string.setup_risk_low
    OemBatteryGuidance.RiskLevel.MEDIUM -> R.string.setup_risk_medium
    OemBatteryGuidance.RiskLevel.HIGH -> R.string.setup_risk_high
    OemBatteryGuidance.RiskLevel.SEVERE -> R.string.setup_risk_severe
}

/**
 * Try each OEM autostart/background settings component in order. OEM component names are fragile and
 * vary across versions, so every failure falls through to the next candidate and finally to the
 * device's dontkillmyapp.com page in a browser.
 */
internal fun openOemSettings(context: Context, action: PermissionAction.OemSettings, onMessage: (String) -> Unit) {
    for (target in action.targets) {
        val intent = Intent().apply {
            setClassName(target.packageName, target.className)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
            // Component not present on this build; try the next candidate.
        } catch (_: SecurityException) {
            // Some OEM screens are not exported; try the next candidate.
        }
    }
    val fallback = Intent(Intent.ACTION_VIEW, Uri.parse(action.fallbackUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(fallback)
        if (action.targets.isNotEmpty()) {
            onMessage(context.getString(R.string.setup_oem_fallback_opened))
        }
    } catch (ex: ActivityNotFoundException) {
        AppLogger.warn("OpenTasker.Setup", "OEM guide activity is unavailable", ex)
        onMessage(context.getString(R.string.setup_oem_guide_unavailable, context.getString(R.string.setup_error_no_handler)))
    }
}
