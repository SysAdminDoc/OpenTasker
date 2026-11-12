package com.opentasker.core.capabilities

import androidx.annotation.StringRes
import com.opentasker.app.BuildConfig
import com.opentasker.app.R
import com.opentasker.core.actions.ActionCatalog
import com.opentasker.core.platform.AndroidAudioHardening
import com.opentasker.core.platform.AndroidAudioHardening.ANDROID_17_API
import com.opentasker.core.power.ShizukuPowerBackend
import com.opentasker.core.power.WifiHotspotPolicy
import com.opentasker.core.scripting.ShizukuScriptPolicy
import com.opentasker.core.scripting.TermuxScriptBackend

enum class CapabilityLevel {
    Supported,
    RequiresSetup,
    Unsupported,
}

data class ActionCapability(
    val level: CapabilityLevel,
    val reason: String,
    @get:StringRes val reasonRes: Int,
) {
    val canAdd: Boolean
        get() = level != CapabilityLevel.Unsupported
}

object ActionCapabilityRegistry {
    private val supported = ActionCapability(CapabilityLevel.Supported, "Ready", R.string.capability_ready)
    private val unknown = ActionCapability(
        CapabilityLevel.Unsupported,
        "Unknown actions are not classified and cannot be added, imported, or enabled.",
        R.string.capability_unknown_action,
    )

    /**
     * Actions reviewed as ordinary: they run under the app's own manifest permissions with no
     * special access, no distribution gate, and no permanent platform block.
     *
     * This set exists so [get] has no permissive default. An action that is registered but appears
     * in neither this set nor [capabilities] resolves to [unknown] and cannot be added, imported,
     * or executed — the contract fails closed while it is unreviewed instead of silently
     * advertising itself as Ready.
     */
    private val ordinaryActionIds = setOf(
        // Variables, text, and date-time: pure in-process transforms.
        "var.set",
        "var.set_multiple",
        "var.persist",
        "var.clear",
        "clipboard.get",
        "clipboard.set",
        "data.read",
        "datetime.format",
        "datetime.parse",
        "datetime.add",
        "text.match",
        "text.replace",
        "text.split",
        "text.join",
        "text.substring",
        "text.length",
        "var.split",
        "var.join",
        "var.convert",
        "array.push",
        "array.pop",
        "array.clear",
        "array.process",
        // Flow control: engine-handled markers plus the runtime wait.
        "flow.wait",
        // Switches one of the app's own profiles, which needs no access from Android.
        "profile.set",
        "task.run",
        "task.stop",
        "flow.if",
        "flow.else",
        "flow.endif",
        "flow.foreach",
        "flow.endfor",
        "flow.try",
        "flow.catch",
        "flow.endtry",
        "flow.stop",
        "flow.goto",
        "flow.anchor",
        // App and intent dispatch: uses ordinary intent resolution.
        "intent.launch",
        "app.launch",
        "shortcut.publish",
        "home.go",
        "url.open",
        // Scoped file access inside the app's own sandbox.
        "file.read",
        "file.write",
        "file.append",
        "file.delete",
        "file.list",
        // Network: INTERNET is a normal permission. Private-LAN destinations are gated at
        // runtime by the ACCESS_LOCAL_NETWORK policy, which fails closed with a Setup pointer.
        "http.request",
        "http.get",
        "http.post",
        "integration.home_assistant.webhook",
        "mqtt.publish",
        "ping",
        "download",
        // Device feedback covered by normal manifest permissions.
        "vibrate",
        "log",
        // SET_WALLPAPER is a normal permission, so there is nothing for Setup to grant.
        "wallpaper.set",
    )

    private val capabilities = mapOf(
        "notify.show" to ActionCapability(CapabilityLevel.RequiresSetup, "Requires notification permission on Android 13+.", R.string.capability_notification_permission),
        "notify.cancel" to ActionCapability(CapabilityLevel.RequiresSetup, "Cancels a posted notification by tag and/or ID. Requires notification permission on Android 13+.", R.string.capability_notification_cancel_permission),
        "notify.progress" to ActionCapability(CapabilityLevel.RequiresSetup, "Requires notification permission on Android 13+; uses ProgressStyle on Android 16+ and requests optional promoted ongoing treatment while progress is active, with a standard progress bar fallback.", R.string.capability_notification_progress),
        "contacts.lookup" to ActionCapability(CapabilityLevel.RequiresSetup, "Requires an explicit Contacts permission for unattended lookup, or Android 17 field-scoped picker mode.", R.string.capability_contacts_permission),
        "plugin.locale.fire" to ActionCapability(CapabilityLevel.RequiresSetup, "Requires an installed Locale-compatible plugin; requests are dispatched only to an explicit package.", R.string.capability_locale_fire_setup),
        "plugin.locale.query" to ActionCapability(CapabilityLevel.RequiresSetup, "Requires an installed Locale-compatible condition plugin; queries are explicit ordered broadcasts with timeout handling.", R.string.capability_locale_query_setup),
        "wifi.toggle" to ActionCapability(CapabilityLevel.Unsupported, "Android 10+ blocks direct WiFi toggles for normal apps.", R.string.capability_wifi_unsupported),
        "bluetooth.toggle" to bluetoothCapability(),
        "brightness.set" to ActionCapability(CapabilityLevel.RequiresSetup, "Requires Write Settings special access.", R.string.capability_write_settings),
        "screen.timeout" to ActionCapability(CapabilityLevel.RequiresSetup, "Requires Write Settings special access.", R.string.capability_write_settings),
        // RequiresSetup, not Unsupported: unlike the elevated actions this one has a route a user
        // can actually take without root or Shizuku, a single ADB grant that then persists.
        "settings.write" to ActionCapability(
            CapabilityLevel.RequiresSetup,
            "Global and Secure need WRITE_SECURE_SETTINGS, granted once over ADB; System uses Write Settings.",
            R.string.capability_settings_write_setup,
        ),
        "app.kill" to ActionCapability(CapabilityLevel.Unsupported, "Force-stopping another app requires privileged app-management access that no normal app can hold.", R.string.capability_app_kill_unsupported),
        "app.archive" to packageArchiveCapability("Archive installed packages while retaining their user data."),
        "app.unarchive" to packageArchiveCapability("Request restoration of an archived package through its responsible installer."),
        "wol" to wakeOnLanCapability(),
        "volume.set" to volumeCapability("May be blocked by Do Not Disturb policy access."),
        "dnd.set" to ActionCapability(CapabilityLevel.RequiresSetup, "Requires Do Not Disturb access.", R.string.capability_dnd_access),
        "zen.rule.set" to ActionCapability(CapabilityLevel.RequiresSetup, "Requires Do Not Disturb access; uses Android 15+ owned Zen rules and falls back to transient DND below Android 15.", R.string.capability_zen_rule_access),
        "zen.rule.clear" to ActionCapability(CapabilityLevel.RequiresSetup, "Requires Do Not Disturb access; removes an OpenTasker-owned Zen rule or clears transient DND below Android 15.", R.string.capability_zen_rule_access),
        "ringer.set" to volumeCapability("May require Do Not Disturb access on some devices when switching to silent mode."),
        "torch.set" to ActionCapability(CapabilityLevel.Supported, "Uses camera flashlight.", R.string.capability_torch_ready),
        "airplane.toggle" to elevatedUnsupported("airplane.toggle", "Airplane mode changes require system or device-owner privileges.", R.string.capability_airplane_unsupported),
        "mobile.toggle" to elevatedUnsupported("mobile.toggle", "Mobile data changes require carrier, system, or device-owner privileges.", R.string.capability_mobile_data_unsupported),
        "hotspot.toggle" to hotspotCapabilityForSdk(android.os.Build.VERSION.SDK_INT),
        "aod.set" to elevatedUnsupported("aod.set", "Always-on display is a protected secure setting, and not every build honours it.", R.string.capability_aod_unsupported),
        "vpn.tunnel" to ActionCapability(CapabilityLevel.RequiresSetup, "Requires WireGuard or Tailscale. WireGuard also needs its control permission and Allow remote control apps turned on.", R.string.capability_vpn_tunnel_setup),
        "wifi.scan" to ActionCapability(CapabilityLevel.RequiresSetup, "Requires location access, and Nearby Wi-Fi devices on Android 13+. Android rate-limits scans, so results may be a cached scan.", R.string.capability_wifi_scan_setup),
        "sun.times" to ActionCapability(CapabilityLevel.RequiresSetup, "Needs location access only when latitude or longitude is left blank.", R.string.capability_sun_times_setup),
        "sms.send" to smsCapability(),
        "screenshot.take" to elevatedUnsupported("screenshot.take", "Screenshots require MediaProjection consent or privileged shell access.", R.string.capability_screenshot_unsupported),
        "sound.play" to audioOutputCapability("Plays audio from a file path or content URI."),
        "sound.beep" to audioOutputCapability("Plays a generated tone."),
        "sound.stop" to mediaKeyCapability("Stop playback via media key dispatch."),
        "sound.pause" to mediaKeyCapability("Pause playback via media key dispatch."),
        "track.next" to mediaKeyCapability("Next track via media key dispatch."),
        "track.previous" to mediaKeyCapability("Previous track via media key dispatch."),
        "sound.resume" to mediaKeyCapability("Resume playback via media key dispatch."),
        "sound.toggle" to mediaKeyCapability("Toggle play and pause via media key dispatch."),
        "track.rewind" to mediaKeyCapability("Rewind via media key dispatch."),
        "track.fastforward" to mediaKeyCapability("Fast forward via media key dispatch."),
        "media.mute" to volumeCapability("Mutes a stream. May be blocked by Do Not Disturb policy."),
        "tts.speak" to audioOutputCapability("Uses Android TTS engine to speak text aloud."),
        "reboot" to elevatedUnsupported("reboot", "Reboot requires privileged device-owner or system app access.", R.string.capability_reboot_unsupported),
        "lock" to ActionCapability(CapabilityLevel.RequiresSetup, "Requires the Lock screen device admin, activated from Setup.", R.string.capability_lock_setup),
        "tile.set" to ActionCapability(CapabilityLevel.Supported, "Updates a configured OpenTasker Quick Settings tile.", R.string.capability_tile_ready),
        "state.temporary" to ActionCapability(CapabilityLevel.Supported, "Applies a reversible setting and schedules a durable restore.", R.string.capability_temporary_state_ready),
        "ime.info" to ActionCapability(CapabilityLevel.Supported, "Reports the current and enabled input methods.", R.string.capability_ime_info_ready),
        "ime.set" to ActionCapability(CapabilityLevel.Unsupported, "Android requires user selection before a normal app can switch the active input method.", R.string.capability_ime_set_unsupported),
        "screen.off" to elevatedUnsupported("screen.off", "Screen-off requires privileged power management access.", R.string.capability_screen_off_unsupported),
        "wake" to elevatedUnsupported("wake", "Wake requires a foreground activity or privileged wake flow.", R.string.capability_wake_unsupported),
        ShizukuScriptPolicy.ACTION_ID to ActionCapability(
            CapabilityLevel.RequiresSetup,
            "Shizuku access and an approved script hash are required.",
            R.string.capability_shizuku_script_setup,
        ),
        TermuxScriptBackend.ACTION_ID to ActionCapability(
            CapabilityLevel.RequiresSetup,
            TermuxScriptBackend.hintForAction(TermuxScriptBackend.ACTION_ID)?.message
                ?: "Termux 0.109+, RUN_COMMAND permission, and an approved script hash are required.",
            R.string.capability_termux_setup,
        ),
        "tasker.unsupported" to ActionCapability(CapabilityLevel.Unsupported, "Imported Tasker action could not be mapped to a supported OpenTasker action.", R.string.capability_tasker_import_unsupported),
        "macrodroid.unsupported" to ActionCapability(CapabilityLevel.Unsupported, "Imported MacroDroid action could not be mapped to a supported OpenTasker action.", R.string.capability_macrodroid_import_unsupported),
    )

    /**
     * Resolves a shipped action through its canonical declaration. The policy table below remains
     * deliberately private to this resolver; callers cannot accidentally bypass the catalogue by
     * reading a second public registry.
     */
    fun get(actionId: String): ActionCapability = ActionCatalog.get(actionId)
        ?.capability
        ?.invoke()
        ?: resolveDeclared(actionId)

    internal fun resolveDeclared(actionId: String): ActionCapability = capabilities[actionId]
        ?: if (actionId in ordinaryActionIds) supported else unknown

    /** Every action id with an explicit contract, used by the contract completeness test. */
    internal fun contractedActionIds(): Set<String> = capabilities.keys + ordinaryActionIds

    private fun bluetoothCapability(): ActionCapability =
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            ActionCapability(CapabilityLevel.Unsupported, "Android 13+ blocks direct Bluetooth enable/disable for normal apps.", R.string.capability_bluetooth_unsupported)
        } else {
            ActionCapability(CapabilityLevel.RequiresSetup, "Requires Bluetooth permission.", R.string.capability_bluetooth_permission)
        }

    /**
     * Not [CapabilityLevel.Supported]: OpenTasker is never the installer of record, so Android
     * answers every archive request with STATUS_PENDING_USER_ACTION and a confirmation the app has
     * to show. That needs a visible app, which a background automation cannot promise.
     */
    private fun packageArchiveCapability(reason: String): ActionCapability =
        if (android.os.Build.VERSION.SDK_INT >= 35) {
            ActionCapability(CapabilityLevel.RequiresSetup, reason, R.string.capability_app_archive_ready)
        } else {
            ActionCapability(CapabilityLevel.Unsupported, "Package archive APIs require Android 15 (API 35) or newer.", R.string.capability_app_archive_unsupported)
        }

    /**
     * Wake-on-LAN only ever targets a private address, so on Android 17+ it is permanently gated
     * on ACCESS_LOCAL_NETWORK rather than failing at run time like the general HTTP actions.
     */
    private fun wakeOnLanCapability(): ActionCapability =
        if (android.os.Build.VERSION.SDK_INT >= ANDROID_17_API) {
            ActionCapability(
                CapabilityLevel.RequiresSetup,
                "Android 17+ requires local network access for the LAN broadcast; grant it in Setup.",
                R.string.capability_local_network_setup,
            )
        } else {
            ActionCapability(CapabilityLevel.Supported, "Sends a magic packet on the local network.", R.string.capability_ready)
        }

    private fun smsCapability(): ActionCapability =
        if (BuildConfig.SMS_ACTION_AVAILABLE) {
            ActionCapability(CapabilityLevel.RequiresSetup, "Requires SMS permission; Play builds omit SMS actions for policy compliance.", R.string.capability_sms_permission)
        } else {
            ActionCapability(CapabilityLevel.Unsupported, "SMS action is unavailable in this distribution because SMS and phone-state permissions are omitted for Play policy compliance.", R.string.capability_sms_distribution_unsupported)
        }

    internal fun hotspotCapabilityForSdk(sdkInt: Int): ActionCapability =
        if (sdkInt < WifiHotspotPolicy.MIN_SDK) {
            ActionCapability(
                CapabilityLevel.Unsupported,
                "The Wi-Fi hotspot action needs Android 11 or newer.",
                R.string.capability_hotspot_android_version,
            )
        } else {
            elevatedUnsupported(
                "hotspot.toggle",
                "Turning the Wi-Fi hotspot on or off requires privileged tethering access.",
                R.string.capability_hotspot_unsupported,
            )
        }

    internal fun audioOutputCapabilityForSdk(sdkInt: Int, reason: String): ActionCapability =
        if (AndroidAudioHardening.isRestricted(sdkInt)) {
            ActionCapability(CapabilityLevel.RequiresSetup, AndroidAudioHardening.outputCapabilityReason(reason), R.string.capability_audio_output_restricted)
        } else {
            ActionCapability(CapabilityLevel.Supported, reason, R.string.capability_audio_output_ready)
        }

    internal fun mediaKeyCapabilityForSdk(sdkInt: Int, reason: String): ActionCapability =
        if (AndroidAudioHardening.isRestricted(sdkInt)) {
            ActionCapability(CapabilityLevel.RequiresSetup, AndroidAudioHardening.mediaKeyCapabilityReason(reason), R.string.capability_media_key_restricted)
        } else {
            ActionCapability(CapabilityLevel.Supported, reason, R.string.capability_media_key_ready)
        }

    internal fun volumeCapabilityForSdk(sdkInt: Int, reason: String): ActionCapability =
        if (AndroidAudioHardening.isRestricted(sdkInt)) {
            ActionCapability(CapabilityLevel.RequiresSetup, AndroidAudioHardening.volumeCapabilityReason(reason), R.string.capability_volume_restricted)
        } else {
            ActionCapability(CapabilityLevel.RequiresSetup, reason, R.string.capability_volume_policy)
        }

    private fun audioOutputCapability(reason: String): ActionCapability =
        audioOutputCapabilityForSdk(android.os.Build.VERSION.SDK_INT, reason)

    private fun mediaKeyCapability(reason: String): ActionCapability =
        mediaKeyCapabilityForSdk(android.os.Build.VERSION.SDK_INT, reason)

    private fun volumeCapability(reason: String): ActionCapability =
        volumeCapabilityForSdk(android.os.Build.VERSION.SDK_INT, reason)

    private fun elevatedUnsupported(actionId: String, reason: String, @StringRes reasonRes: Int): ActionCapability =
        ActionCapability(
            CapabilityLevel.RequiresSetup,
            "$reason ${ShizukuPowerBackend.hintForAction(actionId)?.message ?: "No privileged backend is available."}",
            reasonRes,
        )
}
