package com.opentasker.ui.screens

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.opentasker.app.R
import com.opentasker.core.actions.ActionMetadataRegistry
import com.opentasker.core.capabilities.CapabilityLevel
import com.opentasker.core.capabilities.PlatformSupport
import com.opentasker.core.capabilities.SetupRequirement
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.model.AutomationMode
import com.opentasker.core.model.CollisionMode
import com.opentasker.core.model.ProfileLifetime
import com.opentasker.core.model.ProfileOverflowPolicy
import com.opentasker.core.model.SceneElementType
import com.opentasker.core.permissions.OemBatteryGuidance
import com.opentasker.core.sharing.ShareTrustLevel

@StringRes
internal fun automationModeTitleRes(mode: AutomationMode): Int = when (mode) {
    AutomationMode.SINGLE -> R.string.automation_mode_single_title
    AutomationMode.RESTART -> R.string.automation_mode_restart_title
    AutomationMode.QUEUED -> R.string.automation_mode_queued_title
    AutomationMode.PARALLEL -> R.string.automation_mode_parallel_title
}

@StringRes
internal fun collisionModeTitleRes(mode: CollisionMode): Int = when (mode) {
    CollisionMode.ABORT_NEW -> R.string.collision_mode_abort_new_title
    CollisionMode.ABORT_EXISTING -> R.string.collision_mode_abort_existing_title
    CollisionMode.RUN_BOTH -> R.string.collision_mode_run_both_title
    CollisionMode.WAIT -> R.string.collision_mode_wait_title
}

@StringRes
internal fun profileLifetimeTitleRes(lifetime: ProfileLifetime): Int = when (lifetime) {
    ProfileLifetime.NEVER -> R.string.profile_lifetime_never_title
    ProfileLifetime.UNTIL_DATE -> R.string.profile_lifetime_date_title
    ProfileLifetime.ONCE -> R.string.profile_lifetime_once_title
}

@StringRes
internal fun profileOverflowPolicyTitleRes(policy: ProfileOverflowPolicy): Int = when (policy) {
    ProfileOverflowPolicy.LOG -> R.string.profile_overflow_log_title
    ProfileOverflowPolicy.SILENT -> R.string.profile_overflow_silent_title
}

@StringRes
internal fun setupRequirementLabelRes(requirement: SetupRequirement): Int = when (requirement) {
    SetupRequirement.USAGE_ACCESS -> R.string.setup_usage_card_title
    SetupRequirement.NOTIFICATION_ACCESS -> R.string.setup_notification_access_title
    SetupRequirement.CALENDAR -> R.string.setup_calendar_access_title
    SetupRequirement.OVERLAY -> R.string.setup_overlay_access_title
    SetupRequirement.WRITE_SETTINGS -> R.string.setup_write_settings_title
    SetupRequirement.DEVICE_ADMIN -> R.string.setup_device_admin_title
    SetupRequirement.FOREGROUND_LOCATION -> R.string.setup_foreground_location_title
    SetupRequirement.BACKGROUND_LOCATION -> R.string.setup_background_location_title
    SetupRequirement.NEARBY_WIFI -> R.string.setup_nearby_wifi_title
    SetupRequirement.BLUETOOTH -> R.string.setup_bluetooth_title
    SetupRequirement.BLUETOOTH_SCAN -> R.string.setup_bluetooth_scan_title
    SetupRequirement.LOCAL_NETWORK -> R.string.setup_local_network_title
    SetupRequirement.SMS -> R.string.setup_sms_title
    SetupRequirement.DND -> R.string.setup_dnd_title
    SetupRequirement.CONTACTS -> R.string.setup_contacts_access_title
    SetupRequirement.SCREEN_RECORDING -> R.string.setup_screen_recording_title
    SetupRequirement.PHYSICAL_ACTIVITY -> R.string.setup_activity_recognition_title
    SetupRequirement.PHONE_STATE -> R.string.setup_phone_state_title
    SetupRequirement.CALL_LOG -> R.string.setup_call_log_title
    SetupRequirement.SMS_RECEIVE -> R.string.setup_sms_receive_title
    SetupRequirement.POST_NOTIFICATIONS -> R.string.setup_notifications_card_title
    SetupRequirement.SECURE_SETTINGS -> R.string.setup_secure_settings_title
    SetupRequirement.NFC_TAGS -> R.string.setup_nfc_tags_title
    SetupRequirement.HEALTH_CONNECT -> R.string.setup_health_title
}

@StringRes
internal fun platformFamilyLabelRes(family: PlatformSupport.BlockedFamily): Int = when (family) {
    PlatformSupport.BlockedFamily.CAMERA_MIC -> R.string.platform_family_camera_mic
    PlatformSupport.BlockedFamily.BROADCAST_SENDER -> R.string.platform_family_broadcast_sender
    PlatformSupport.BlockedFamily.SCREEN_RECORDING -> R.string.platform_family_screen_recording
    PlatformSupport.BlockedFamily.ADVANCED_PROTECTION -> R.string.platform_family_advanced_protection
    PlatformSupport.BlockedFamily.BLUETOOTH_SECURITY -> R.string.platform_family_bluetooth_security
}

@StringRes
internal fun capabilityLevelLabelRes(level: CapabilityLevel): Int = when (level) {
    CapabilityLevel.Supported -> R.string.status_ready
    CapabilityLevel.RequiresSetup -> R.string.status_needs_setup
    CapabilityLevel.Unsupported -> R.string.label_unsupported
}

@StringRes
internal fun shareTrustLevelLabelRes(level: ShareTrustLevel): Int = when (level) {
    ShareTrustLevel.LocalDraft -> R.string.profile_share_trust_local_draft
    ShareTrustLevel.CommunityUnverified -> R.string.profile_share_trust_community_unverified
}

@StringRes
internal fun appLogLevelLabelRes(level: AppLogger.Level): Int = when (level) {
    AppLogger.Level.DEBUG -> R.string.diagnostics_log_level_debug
    AppLogger.Level.INFO -> R.string.diagnostics_log_level_info
    AppLogger.Level.WARN -> R.string.diagnostics_log_level_warning
    AppLogger.Level.ERROR -> R.string.diagnostics_log_level_error
}

@StringRes
internal fun oemRiskLevelLabelRes(level: OemBatteryGuidance.RiskLevel): Int = when (level) {
    OemBatteryGuidance.RiskLevel.LOW -> R.string.setup_risk_low
    OemBatteryGuidance.RiskLevel.MEDIUM -> R.string.setup_risk_medium
    OemBatteryGuidance.RiskLevel.HIGH -> R.string.setup_risk_high
    OemBatteryGuidance.RiskLevel.SEVERE -> R.string.setup_risk_severe
}

@StringRes
internal fun sceneElementTypeLabelRes(type: SceneElementType): Int = when (type) {
    SceneElementType.BUTTON -> R.string.scene_element_type_button
    SceneElementType.TEXT -> R.string.scene_element_type_text
    SceneElementType.SLIDER -> R.string.scene_element_type_slider
    SceneElementType.IMAGE -> R.string.scene_element_type_image
}

@Composable
internal fun actionDisplayName(actionId: String): String = stringResource(
    ActionMetadataRegistry.get(actionId)?.nameRes ?: R.string.action_unknown_name,
)
