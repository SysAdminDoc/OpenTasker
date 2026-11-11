package com.opentasker.core.actions

import com.opentasker.core.capabilities.ActionCapability
import com.opentasker.core.capabilities.ActionCapabilityRegistry
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionRetrySafety

typealias DeclaredAction = com.opentasker.core.engine.DeclaredAction

/**
 * The single runtime catalogue for built-in actions.
 *
 * Each entry owns the stable action identity, runtime category, retry contract, implementation
 * factory, and capability resolver. UI metadata is registered against the same identity and is
 * exposed through [ActionDefinition.metadata], so the runtime, editor, capability, and release
 * count surfaces can no longer invent independent action lists.
 */
class ActionDefinition(
    val id: String,
    val category: ActionCategory,
    val retrySafety: ActionRetrySafety,
    val factory: () -> Action,
    val capability: () -> ActionCapability = { ActionCapabilityRegistry.resolveDeclared(id) },
) {
    private var boundMetadata: ActionMetadata? = null

    val metadata: ActionMetadata
        get() = requireNotNull(boundMetadata) {
            "No UI metadata declared for action $id"
        }

    internal fun bindMetadata(metadata: ActionMetadata) {
        require(metadata.id == id) {
            "Metadata id ${metadata.id} does not match action declaration $id"
        }
        boundMetadata = metadata
    }

    internal fun metadataOrNull(): ActionMetadata? = boundMetadata
}

object ActionCatalog {
    val all: List<ActionDefinition> = listOf(
        define("notify.show", ActionCategory.NOTIFICATION, ActionRetrySafety.NEVER, ::NotifyAction),
        define("notify.cancel", ActionCategory.NOTIFICATION, ActionRetrySafety.IDEMPOTENT, ::NotifyCancelAction),
        define("notify.progress", ActionCategory.NOTIFICATION, ActionRetrySafety.NEVER, ::ProgressNotificationAction),
        define("var.set", ActionCategory.VARIABLE, ActionRetrySafety.IDEMPOTENT, ::SetVariableAction),
        define("var.persist", ActionCategory.VARIABLE, ActionRetrySafety.IDEMPOTENT, ::PersistVariableAction),
        define("clipboard.get", ActionCategory.VARIABLE, ActionRetrySafety.IDEMPOTENT, ::ClipboardGetAction),
        define("clipboard.set", ActionCategory.SYSTEM, ActionRetrySafety.IDEMPOTENT, ::ClipboardSetAction),
        define("contacts.lookup", ActionCategory.VARIABLE, ActionRetrySafety.IDEMPOTENT, ::ContactsLookupAction),
        define("data.read", ActionCategory.VARIABLE, ActionRetrySafety.IDEMPOTENT, ::DataReadAction),
        define("datetime.format", ActionCategory.VARIABLE, ActionRetrySafety.IDEMPOTENT, ::DateTimeFormatAction),
        define("datetime.parse", ActionCategory.VARIABLE, ActionRetrySafety.IDEMPOTENT, ::DateTimeParseAction),
        define("datetime.add", ActionCategory.VARIABLE, ActionRetrySafety.IDEMPOTENT, ::DateTimeAddAction),
        define("text.match", ActionCategory.VARIABLE, ActionRetrySafety.IDEMPOTENT, ::TextMatchAction),
        define("text.replace", ActionCategory.VARIABLE, ActionRetrySafety.IDEMPOTENT, ::TextReplaceAction),
        define("text.split", ActionCategory.VARIABLE, ActionRetrySafety.IDEMPOTENT, ::TextSplitAction),
        define("text.join", ActionCategory.VARIABLE, ActionRetrySafety.IDEMPOTENT, ::TextJoinAction),
        define("text.substring", ActionCategory.VARIABLE, ActionRetrySafety.IDEMPOTENT, ::TextSubstringAction),
        // Split and Join can unset their source, and Push and Pop move elements, so a retry
        // doesn't repeat the first try. Clearing twice clears the same elements.
        define("var.split", ActionCategory.VARIABLE, ActionRetrySafety.NEVER, ::VarSplitAction),
        define("var.join", ActionCategory.VARIABLE, ActionRetrySafety.NEVER, ::VarJoinAction),
        define("var.convert", ActionCategory.VARIABLE, ActionRetrySafety.NEVER, ::VarConvertAction),
        define("array.push", ActionCategory.VARIABLE, ActionRetrySafety.NEVER, ::ArrayPushAction),
        define("array.pop", ActionCategory.VARIABLE, ActionRetrySafety.NEVER, ::ArrayPopAction),
        define("array.clear", ActionCategory.VARIABLE, ActionRetrySafety.IDEMPOTENT, ::ArrayClearAction),
        define("array.process", ActionCategory.VARIABLE, ActionRetrySafety.NEVER, ::ArrayProcessAction),
        define("tts.speak", ActionCategory.NOTIFICATION, ActionRetrySafety.NEVER, ::SayAction),
        define("flow.wait", ActionCategory.FLOW, ActionRetrySafety.NEVER, ::WaitAction),
        // NEVER, not IDEMPOTENT: "toggle" reads the profile again, so a retry flips it back.
        define("profile.set", ActionCategory.FLOW, ActionRetrySafety.NEVER, ::ProfileStatusAction),
        define("intent.launch", ActionCategory.APP, ActionRetrySafety.NEVER, ::LaunchIntentAction),
        define("wifi.toggle", ActionCategory.SETTINGS, ActionRetrySafety.NEVER, ::WiFiToggleAction),
        // IDEMPOTENT: it only reads. Retrying re-reads the same cache and rewrites the same
        // variables, and the platform decides whether a fresh scan happens either way.
        define("wifi.scan", ActionCategory.SETTINGS, ActionRetrySafety.IDEMPOTENT, ::WifiScanAction),
        define("bluetooth.toggle", ActionCategory.SETTINGS, ActionRetrySafety.NEVER, ::BluetoothToggleAction),
        define("brightness.set", ActionCategory.SETTINGS, ActionRetrySafety.IDEMPOTENT, ::BrightnessAction),
        define("volume.set", ActionCategory.SETTINGS, ActionRetrySafety.IDEMPOTENT, ::VolumeAction),
        define("airplane.toggle", ActionCategory.SETTINGS, ActionRetrySafety.NEVER, ::AirplaneModeAction),
        define("mobile.toggle", ActionCategory.SETTINGS, ActionRetrySafety.NEVER, ::MobileDataAction),
        define("hotspot.toggle", ActionCategory.SETTINGS, ActionRetrySafety.NEVER, ::WifiHotspotAction),
        // NEVER, not IDEMPOTENT: "toggle" recomputes from the live value, so a retry flips back.
        define("aod.set", ActionCategory.SETTINGS, ActionRetrySafety.NEVER, ::AlwaysOnDisplayAction),
        define("screen.timeout", ActionCategory.SETTINGS, ActionRetrySafety.IDEMPOTENT, ::ScreenTimeoutAction),
        // IDEMPOTENT: the same name and value written twice leaves the same setting.
        define("settings.write", ActionCategory.SETTINGS, ActionRetrySafety.IDEMPOTENT, ::SettingsWriteAction),
        define("dnd.set", ActionCategory.SETTINGS, ActionRetrySafety.IDEMPOTENT, ::DoNotDisturbAction),
        define("zen.rule.set", ActionCategory.SETTINGS, ActionRetrySafety.NEVER, ::ZenRuleSetAction),
        define("zen.rule.clear", ActionCategory.SETTINGS, ActionRetrySafety.IDEMPOTENT, ::ZenRuleClearAction),
        define("ringer.set", ActionCategory.SETTINGS, ActionRetrySafety.IDEMPOTENT, ::RingerModeAction),
        define("torch.set", ActionCategory.SETTINGS, ActionRetrySafety.NEVER, ::TorchAction),
        define("tile.set", ActionCategory.SETTINGS, ActionRetrySafety.IDEMPOTENT, ::TileStateAction),
        define("wallpaper.set", ActionCategory.SETTINGS, ActionRetrySafety.IDEMPOTENT, ::SetWallpaperAction),
        define("state.temporary", ActionCategory.SETTINGS, ActionRetrySafety.NEVER, ::TemporaryStateAction),
        define("app.launch", ActionCategory.APP, ActionRetrySafety.NEVER, ::LaunchAppAction),
        define("app.archive", ActionCategory.APP, ActionRetrySafety.IDEMPOTENT, ::AppArchiveAction),
        define("app.unarchive", ActionCategory.APP, ActionRetrySafety.IDEMPOTENT, ::AppUnarchiveAction),
        define("shortcut.publish", ActionCategory.APP, ActionRetrySafety.NEVER, ::ShortcutPublishAction),
        define("plugin.locale.fire", ActionCategory.APP, ActionRetrySafety.NEVER, ::LocalePluginSettingAction),
        define("plugin.locale.query", ActionCategory.PLUGIN, ActionRetrySafety.IDEMPOTENT, ::LocalePluginConditionQueryAction),
        define("app.kill", ActionCategory.APP, ActionRetrySafety.NEVER, ::KillAppAction),
        define("home.go", ActionCategory.APP, ActionRetrySafety.NEVER, ::GoHomeAction),
        define("url.open", ActionCategory.APP, ActionRetrySafety.NEVER, ::OpenUrlAction),
        define("sms.send", ActionCategory.APP, ActionRetrySafety.NEVER, ::SendSmsAction),
        define("screenshot.take", ActionCategory.APP, ActionRetrySafety.NEVER, ::ScreenshotAction),
        define("file.read", ActionCategory.FILE, ActionRetrySafety.IDEMPOTENT, ::ReadFileAction),
        define("file.write", ActionCategory.FILE, ActionRetrySafety.IDEMPOTENT, ::WriteFileAction),
        define("file.append", ActionCategory.FILE, ActionRetrySafety.NEVER, ::AppendFileAction),
        define("file.delete", ActionCategory.FILE, ActionRetrySafety.IDEMPOTENT, ::DeleteFileAction),
        define("file.list", ActionCategory.FILE, ActionRetrySafety.IDEMPOTENT, ::ListFilesAction),
        define("http.request", ActionCategory.NET, ActionRetrySafety.NEVER, ::HttpRequestAction),
        define("http.get", ActionCategory.NET, ActionRetrySafety.IDEMPOTENT, ::HttpGetAction),
        define("http.post", ActionCategory.NET, ActionRetrySafety.NEVER, ::HttpPostAction),
        define("ime.info", ActionCategory.SETTINGS, ActionRetrySafety.IDEMPOTENT, ::ImeInfoAction),
        define("ime.set", ActionCategory.SETTINGS, ActionRetrySafety.NEVER, ::ImeSetAction),
        define("integration.home_assistant.webhook", ActionCategory.NET, ActionRetrySafety.NEVER, ::HomeAssistantWebhookAction),
        define("mqtt.publish", ActionCategory.NET, ActionRetrySafety.NEVER, ::MqttPublishAction),
        define("ping", ActionCategory.NET, ActionRetrySafety.IDEMPOTENT, ::PingAction),
        define("download", ActionCategory.NET, ActionRetrySafety.IDEMPOTENT, ::DownloadAction),
        define("wol", ActionCategory.NET, ActionRetrySafety.IDEMPOTENT, ::WakeOnLanAction),
        define("sound.play", ActionCategory.MEDIA, ActionRetrySafety.NEVER, ::PlaySoundAction),
        define("sound.beep", ActionCategory.MEDIA, ActionRetrySafety.NEVER, ::BeepAction),
        define("sound.stop", ActionCategory.MEDIA, ActionRetrySafety.IDEMPOTENT, ::StopSoundAction),
        define("sound.pause", ActionCategory.MEDIA, ActionRetrySafety.IDEMPOTENT, ::PauseSoundAction),
        define("track.next", ActionCategory.MEDIA, ActionRetrySafety.NEVER, ::NextTrackAction),
        define("track.previous", ActionCategory.MEDIA, ActionRetrySafety.NEVER, ::PreviousTrackAction),
        define("media.mute", ActionCategory.MEDIA, ActionRetrySafety.IDEMPOTENT, ::MuteAction),
        define("vibrate", ActionCategory.NOTIFICATION, ActionRetrySafety.NEVER, ::VibrateAction),
        define("reboot", ActionCategory.SYSTEM, ActionRetrySafety.NEVER, ::RebootAction),
        define("lock", ActionCategory.SYSTEM, ActionRetrySafety.IDEMPOTENT, ::LockDeviceAction),
        define("screen.off", ActionCategory.SETTINGS, ActionRetrySafety.IDEMPOTENT, ::ScreenOffAction),
        define("wake", ActionCategory.SETTINGS, ActionRetrySafety.IDEMPOTENT, ::WakeAction),
        define("log", ActionCategory.SYSTEM, ActionRetrySafety.NEVER, ::LogAction),
        define("vpn.tunnel", ActionCategory.NET, ActionRetrySafety.IDEMPOTENT, ::VpnTunnelAction),
        define("script.termux.run", ActionCategory.PLUGIN, ActionRetrySafety.NEVER, ::TermuxScriptAction),
        define("script.shizuku.run", ActionCategory.PLUGIN, ActionRetrySafety.NEVER, ::ShizukuScriptAction),
        define("tasker.unsupported", ActionCategory.SYSTEM, ActionRetrySafety.NEVER, ::TaskerUnsupportedAction),
        define("macrodroid.unsupported", ActionCategory.SYSTEM, ActionRetrySafety.NEVER, ::MacroDroidUnsupportedAction),
    )

    /** Engine-handled flow markers have metadata but no runtime Action implementation. */
    private val engineHandled = listOf(
        defineEngine("task.run", ActionCategory.FLOW),
        defineEngine("flow.if", ActionCategory.FLOW),
        defineEngine("flow.else", ActionCategory.FLOW),
        defineEngine("flow.endif", ActionCategory.FLOW),
        defineEngine("flow.foreach", ActionCategory.FLOW),
        defineEngine("flow.endfor", ActionCategory.FLOW),
        defineEngine("flow.stop", ActionCategory.FLOW),
        defineEngine("flow.try", ActionCategory.FLOW),
        defineEngine("flow.catch", ActionCategory.FLOW),
        defineEngine("flow.endtry", ActionCategory.FLOW),
        defineEngine("flow.goto", ActionCategory.FLOW),
        defineEngine("flow.anchor", ActionCategory.FLOW),
    )

    val allDefinitions: List<ActionDefinition> = all + engineHandled

    private val byId = allDefinitions.associateBy(ActionDefinition::id)

    init {
        require(byId.size == allDefinitions.size) { "Duplicate action declaration in ActionCatalog" }
    }

    fun get(id: String): ActionDefinition? = byId[id]

    fun require(id: String): ActionDefinition = requireNotNull(get(id)) {
        "No action declaration for $id"
    }

    private fun define(
        id: String,
        category: ActionCategory,
        retrySafety: ActionRetrySafety,
        factory: () -> Action,
    ) = ActionDefinition(id, category, retrySafety, factory)

    private fun defineEngine(
        id: String,
        category: ActionCategory,
    ) = ActionDefinition(
        id = id,
        category = category,
        retrySafety = ActionRetrySafety.NEVER,
        factory = { error("$id is handled by the task engine, not an Action implementation") },
    )
}
