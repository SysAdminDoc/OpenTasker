package com.opentasker.core.templates

import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task
import com.opentasker.core.model.VariableNamePolicy
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlinx.serialization.Serializable

@Serializable
enum class TemplateAvailability {
    Ready,
    RequiresSetup,
    Planned,
}

/** The input widget/validator contract carried by a reusable automation blueprint. */
@Serializable
enum class BlueprintSelectorKind {
    TEXT,
    APP,
    WIFI_SSID,
    LOCATION,
    TASK_REFERENCE,
    VARIABLE,
    DURATION,
    INTEGER,
    DECIMAL,
    TIME,
    CALLER_SEARCH_URL,
}

@Serializable
data class BlueprintInput(
    val key: String,
    val label: String,
    val defaultValue: String,
    val required: Boolean = true,
    val hint: String? = null,
    val selector: BlueprintSelectorKind = BlueprintSelectorKind.TEXT,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val section: String = "General",
)

/** Source-compatible name retained for the existing guided-template UI. */
typealias TemplateSlot = BlueprintInput

@Serializable
data class TemplateAction(
    val type: String,
    val label: String,
    val args: Map<String, String> = emptyMap(),
)

@Serializable
data class TemplateContext(
    val type: ContextType,
    val config: Map<String, String> = emptyMap(),
    val invert: Boolean = false,
)

data class AppliedProfileTemplate(
    val task: Task,
    val profile: Profile,
    val exitTask: Task? = null,
)

/**
 * A local, versioned blueprint. Inputs are typed selectors rather than anonymous text fields;
 * the same definition drives validation, the picker, and instantiation.
 */
@Serializable
data class AutomationBlueprint(
    val id: String,
    val version: Int = 1,
    val title: String,
    val summary: String,
    val category: String,
    val availability: TemplateAvailability,
    val safetyNote: String,
    val inputs: List<BlueprintInput>,
    val contexts: List<TemplateContext>,
    val actions: List<TemplateAction>,
    val enabledByDefault: Boolean = false,
    /**
     * Repeat guard for blueprints whose trigger is a window rather than an instant. A window
     * context is re-observed while it lasts, so without this an hour-long calendar event ran its
     * task once a minute.
     */
    val cooldownSec: Int = 0,
    /**
     * Placeholders the user can type into an input that are filled when the task runs, so a search
     * address can say {number} instead of a template expression. They aren't inputs of their own.
     */
    val runtimePlaceholders: Map<String, String> = emptyMap(),
    /** What undoes the rule when its context stops matching, such as leaving the home Wi-Fi. */
    val exitActions: List<TemplateAction> = emptyList(),
) {
    /** Compatibility view for the existing guided-template screen. */
    val slots: List<BlueprintInput>
        get() = inputs

    val installable: Boolean
        get() = availability != TemplateAvailability.Planned

    fun defaults(): Map<String, String> = inputs.associate { it.key to it.defaultValue }

    fun instantiate(slotValues: Map<String, String>): AppliedProfileTemplate {
        require(installable) { "Blueprint '$title' is not installable yet." }

        val unknownKeys = slotValues.keys - inputs.mapTo(linkedSetOf()) { it.key }
        require(unknownKeys.isEmpty()) {
            "Invalid blueprint values: unknown input(s) ${unknownKeys.sorted().joinToString()}"
        }
        val values = defaults() + slotValues.mapValues { it.value.trim() }
        val missing = inputs.filter { it.required && values[it.key].isNullOrBlank() }
        require(missing.isEmpty()) {
            "Missing blueprint values: ${missing.joinToString { it.label }}"
        }
        val invalid = inputs.mapNotNull { input ->
            val value = values[input.key].orEmpty()
            input.validationError(value)?.let { "${input.label}: $it" }
        }
        require(invalid.isEmpty()) {
            "Invalid blueprint values: ${invalid.joinToString()}"
        }

        val expansions = values + runtimePlaceholders
        val taskName = expand("$title Task", expansions)
        val task = Task(
            name = taskName,
            actions = actionSpecs(actions, expansions),
        )
        val exitTask = exitActions.takeIf { it.isNotEmpty() }?.let { exit ->
            Task(name = expand("$title Exit Task", expansions), actions = actionSpecs(exit, expansions))
        }
        val profile = Profile(
            name = expand(title, expansions),
            // Every blueprint installation is a review artifact. Enabling is an explicit user
            // action after capability, lint, and safety checks; a definition cannot bypass that
            // gate through metadata.
            enabled = false,
            enterTaskId = 0,
            cooldownSec = cooldownSec,
            contexts = contexts.map { context ->
                ContextSpec(
                    type = context.type,
                    config = context.config.mapValues { (_, value) -> expand(value, expansions) },
                    invert = context.invert,
                )
            },
        )
        return AppliedProfileTemplate(task = task, profile = profile, exitTask = exitTask)
    }

    private fun actionSpecs(source: List<TemplateAction>, expansions: Map<String, String>): List<ActionSpec> =
        source.map { action ->
            ActionSpec(
                type = action.type,
                label = expand(action.label, expansions),
                args = action.args.mapValues { (_, value) -> expand(value, expansions) },
            )
        }

    private fun expand(value: String, slotValues: Map<String, String>): String {
        var expanded = value
        repeat(2) {
            expanded = slotValues.entries.fold(expanded) { current, (key, replacement) ->
                current.replace("{$key}", replacement)
            }
        }
        return expanded
    }
}

/** Source-compatible alias while consumers migrate from `ProfileTemplate` terminology. */
typealias ProfileTemplate = AutomationBlueprint

fun BlueprintInput.validationError(value: String): String? {
    if (value.isBlank() && !required) return null
    return when (selector) {
        BlueprintSelectorKind.TEXT -> null
        BlueprintSelectorKind.APP -> if (value.matches(APP_ID_PATTERN)) null else "enter an Android package name"
        BlueprintSelectorKind.WIFI_SSID -> when {
            value.length > 32 -> "must be at most 32 characters"
            value.any(Char::isISOControl) -> "cannot contain control characters"
            else -> null
        }
        BlueprintSelectorKind.LOCATION -> {
            val coordinates = value.split(',', limit = 2).map { it.trim().toDoubleOrNull() }
            if (coordinates.size == 2 && coordinates.all { it != null } &&
                coordinates[0]!! in -90.0..90.0 && coordinates[1]!! in -180.0..180.0
            ) null else "use latitude,longitude"
        }
        BlueprintSelectorKind.TASK_REFERENCE -> {
            if (value.toLongOrNull()?.let { it > 0L } == true) null else "choose a positive task id"
        }
        BlueprintSelectorKind.VARIABLE -> {
            if (VariableNamePolicy.normalizeForScope(value, isGlobal = false) != null) null
            else "use a valid variable name"
        }
        BlueprintSelectorKind.DURATION -> numericError(value, wholeNumber = true, defaultMaximum = 86_400_000.0)
        BlueprintSelectorKind.INTEGER -> numericError(value, wholeNumber = true)
        BlueprintSelectorKind.DECIMAL -> numericError(value, wholeNumber = false)
        BlueprintSelectorKind.TIME -> try {
            LocalTime.parse(value, DateTimeFormatter.ofPattern("HH:mm"))
            null
        } catch (_: DateTimeParseException) {
            "use HH:mm"
        }
        BlueprintSelectorKind.CALLER_SEARCH_URL -> when {
            !value.startsWith("https://", ignoreCase = true) && !value.startsWith("http://", ignoreCase = true) ->
                "must start with https://"
            "{number}" !in value -> "must include {number} where the caller's number goes"
            value.any { it.isWhitespace() || it.isISOControl() } -> "cannot contain spaces"
            else -> null
        }
    }
}

private fun BlueprintInput.numericError(value: String, wholeNumber: Boolean, defaultMaximum: Double? = null): String? {
    val number = (if (wholeNumber) value.toLongOrNull()?.toDouble() else value.toDoubleOrNull())
        ?: return "must be numeric"
    val upperBound = maximum ?: defaultMaximum
    return when {
        minimum != null && number < minimum -> "must be at least ${minimum.stripTrailingZero()}"
        upperBound != null && number > upperBound -> "must be at most ${upperBound.stripTrailingZero()}"
        else -> null
    }
}

private fun Double.stripTrailingZero(): String = toString().removeSuffix(".0")

private val APP_ID_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

/** Encoded because a number's leading + would otherwise reach the search site as a space. */
private val CALLER_NUMBER_PLACEHOLDER = mapOf("number" to "{{ event.incoming_number | urlencode }}")

private fun callerSearchUrlInput() = TemplateSlot(
    "searchUrl",
    "Search URL",
    "https://duckduckgo.com/?q={number}",
    hint = "{number} becomes the caller's number",
    selector = BlueprintSelectorKind.CALLER_SEARCH_URL,
    section = "Search",
)

/** Only a checked stranger. A hidden caller or an unchecked number reads "unknown", not "false". */
private fun incomingCallFromStranger() = TemplateContext(
    ContextType.EVENT,
    mapOf("event" to "incoming_call", "knownContact" to "false"),
)

private fun unknownCallerNotification() = TemplateAction(
    "notify.show",
    "Show the caller's number",
    mapOf(
        "title" to "Unknown caller",
        "text" to "{{ event.incoming_number }}",
        "channel" to "urgent",
        "button1_label" to "Search",
        "button1_url" to "{searchUrl}",
    ),
)

object ProfileTemplateCatalog {
    val all: List<ProfileTemplate> = listOf(
        ProfileTemplate(
            id = "work-hours-focus",
            title = "Work-hours focus",
            summary = "Lower notification volume and log when the work window starts.",
            category = "Focus",
            availability = TemplateAvailability.RequiresSetup,
            safetyNote = "Creates a disabled profile. Review DND/volume access before enabling.",
            inputs = listOf(
                TemplateSlot("start", "Start time", "09:00", hint = "HH:mm", selector = BlueprintSelectorKind.TIME),
                TemplateSlot("end", "End time", "17:00", hint = "HH:mm", selector = BlueprintSelectorKind.TIME),
                TemplateSlot("level", "Notification volume", "20", hint = "0-100", selector = BlueprintSelectorKind.INTEGER, minimum = 0.0, maximum = 100.0),
            ),
            contexts = listOf(
                TemplateContext(ContextType.TIME, mapOf("start" to "{start}", "end" to "{end}")),
            ),
            actions = listOf(
                TemplateAction("volume.set", "Lower notification volume", mapOf("stream" to "notification", "level" to "{level}")),
                TemplateAction("log", "Log focus profile", mapOf("message" to "Work-hours focus active from {start} to {end}")),
            ),
        ),
        ProfileTemplate(
            id = "headphones-media",
            title = "Headphones media",
            summary = "Set a safe media volume when headphones connect.",
            category = "Media",
            availability = TemplateAvailability.RequiresSetup,
            safetyNote = "Creates a disabled profile and may need DND/volume access.",
            inputs = listOf(
                TemplateSlot("level", "Media volume", "55", hint = "0-100", selector = BlueprintSelectorKind.INTEGER, minimum = 0.0, maximum = 100.0),
            ),
            contexts = listOf(
                TemplateContext(ContextType.STATE, mapOf("key" to "headphones", "value" to "true")),
            ),
            actions = listOf(
                TemplateAction("volume.set", "Set media volume", mapOf("stream" to "music", "level" to "{level}")),
                TemplateAction("log", "Log headphones profile", mapOf("message" to "Headphones connected; media volume set to {level}")),
            ),
        ),
        ProfileTemplate(
            id = "low-battery-saver",
            title = "Low-battery saver",
            summary = "Dim brightness and log when battery drops under a chosen threshold.",
            category = "Battery",
            availability = TemplateAvailability.RequiresSetup,
            safetyNote = "Creates a disabled profile and needs Write Settings access for brightness.",
            inputs = listOf(
                TemplateSlot("threshold", "Battery threshold", "20", hint = "Percent", selector = BlueprintSelectorKind.INTEGER, minimum = 0.0, maximum = 100.0),
                TemplateSlot("brightness", "Brightness", "48", hint = "0-255", selector = BlueprintSelectorKind.INTEGER, minimum = 0.0, maximum = 255.0),
            ),
            contexts = listOf(
                TemplateContext(ContextType.STATE, mapOf("key" to "battery_level", "operator" to "<=", "value" to "{threshold}")),
            ),
            actions = listOf(
                TemplateAction("brightness.set", "Dim screen", mapOf("level" to "{brightness}")),
                TemplateAction("log", "Log low battery", mapOf("message" to "Battery saver template triggered below {threshold}%")),
            ),
        ),
        ProfileTemplate(
            id = "wifi-arrival",
            title = "WiFi arrival",
            summary = "Run a notification/log pattern when a trusted SSID is detected.",
            category = "Location-lite",
            availability = TemplateAvailability.RequiresSetup,
            safetyNote = "Creates a disabled profile and may need nearby WiFi/location permission.",
            inputs = listOf(
                TemplateSlot("ssid", "WiFi SSID", "Home WiFi", hint = "Exact SSID", selector = BlueprintSelectorKind.WIFI_SSID),
                TemplateSlot("message", "Message", "Arrived on {ssid}"),
            ),
            contexts = listOf(
                TemplateContext(ContextType.STATE, mapOf("key" to "wifi", "value" to "{ssid}")),
            ),
            actions = listOf(
                TemplateAction("notify.show", "Show arrival notification", mapOf("title" to "OpenTasker", "text" to "{message}")),
                TemplateAction("log", "Log WiFi arrival", mapOf("message" to "{message}")),
            ),
        ),
        ProfileTemplate(
            id = "location-evidence-log",
            title = "Location evidence log",
            summary = "Log when the device enters a configured test radius for Location verification.",
            category = "Location",
            availability = TemplateAvailability.RequiresSetup,
            safetyNote = "Creates a disabled profile. Requires foreground/background location permissions and device Location before enabling.",
            inputs = listOf(
                TemplateSlot("latitude", "Latitude", "40.7580", hint = "Decimal degrees", selector = BlueprintSelectorKind.DECIMAL, minimum = -90.0, maximum = 90.0),
                TemplateSlot("longitude", "Longitude", "-73.9855", hint = "Decimal degrees", selector = BlueprintSelectorKind.DECIMAL, minimum = -180.0, maximum = 180.0),
                TemplateSlot("radiusMeters", "Radius meters", "150", hint = "Meters", selector = BlueprintSelectorKind.INTEGER, minimum = 1.0, maximum = 100_000.0),
                TemplateSlot("maxAccuracyMeters", "Max accuracy meters", "100", hint = "Meters", selector = BlueprintSelectorKind.INTEGER, minimum = 1.0, maximum = 100_000.0),
                TemplateSlot("dwellSeconds", "Dwell seconds", "0", hint = "0 disables dwell", selector = BlueprintSelectorKind.DURATION, minimum = 0.0, maximum = 86_400.0),
            ),
            contexts = listOf(
                TemplateContext(
                    ContextType.LOCATION,
                    mapOf(
                        "latitude" to "{latitude}",
                        "longitude" to "{longitude}",
                        "radiusMeters" to "{radiusMeters}",
                        "maxAccuracyMeters" to "{maxAccuracyMeters}",
                        "dwellSeconds" to "{dwellSeconds}",
                    ),
                ),
            ),
            actions = listOf(
                TemplateAction(
                    "log",
                    "Log location evidence",
                    mapOf("message" to "Location evidence matched {latitude},{longitude} within {radiusMeters}m"),
                ),
            ),
        ),
        ProfileTemplate(
            id = "app-usage-reminder",
            title = "App usage reminder",
            summary = "Wait after an app opens, then show a reminder notification.",
            category = "Habits",
            availability = TemplateAvailability.RequiresSetup,
            safetyNote = "Creates a disabled profile and requires Usage Access plus notification permission.",
            inputs = listOf(
                TemplateSlot("package", "App package", "com.android.chrome", hint = "com.example.app", selector = BlueprintSelectorKind.APP),
                TemplateSlot("delayMillis", "Delay milliseconds", "900000", hint = "900000 = 15 minutes", selector = BlueprintSelectorKind.DURATION, minimum = 0.0),
                TemplateSlot("message", "Reminder", "Time check: {package} has been open long enough for a break."),
            ),
            contexts = listOf(
                TemplateContext(ContextType.APPLICATION, mapOf("package" to "{package}")),
            ),
            actions = listOf(
                TemplateAction("flow.wait", "Wait before reminder", mapOf("millis" to "{delayMillis}")),
                TemplateAction("notify.show", "Show app reminder", mapOf("title" to "OpenTasker reminder", "text" to "{message}")),
            ),
        ),
        ProfileTemplate(
            id = "find-my-phone",
            title = "Find my phone",
            summary = "Pattern for a future external trigger that vibrates and raises volume.",
            category = "Safety",
            availability = TemplateAvailability.Planned,
            safetyNote = "Blocked until external trigger intents are exposed safely.",
            inputs = listOf(
                TemplateSlot("trigger", "Trigger action", "com.opentasker.intent.FIND_PHONE", selector = BlueprintSelectorKind.TEXT),
            ),
            contexts = listOf(
                TemplateContext(ContextType.EVENT, mapOf("event" to "intent", "filter" to "{trigger}")),
            ),
            actions = listOf(
                TemplateAction("volume.set", "Raise media volume", mapOf("stream" to "music", "level" to "100")),
                TemplateAction("vibrate", "Vibrate", mapOf("millis" to "1500")),
                TemplateAction("notify.show", "Show finder alert", mapOf("title" to "Find my phone", "text" to "Finder pattern triggered")),
            ),
        ),
        ProfileTemplate(
            id = "meeting-mode-calendar",
            title = "Meeting mode from calendar",
            summary = "Lower notification volume while a named calendar has a busy event.",
            category = "Calendar",
            availability = TemplateAvailability.RequiresSetup,
            safetyNote = "Creates a disabled profile. Requires Calendar access plus DND/volume access before enabling.",
            inputs = listOf(
                TemplateSlot("calendar", "Calendar name", "Work", selector = BlueprintSelectorKind.TEXT, section = "Calendar"),
            ),
            contexts = listOf(
                TemplateContext(ContextType.EVENT, mapOf("event" to "calendar", "state" to "during", "calendar" to "{calendar}")),
            ),
            actions = listOf(
                TemplateAction("volume.set", "Lower notification volume", mapOf("stream" to "notification", "level" to "0")),
            ),
            // A meeting is a window, so belt and braces with the event's stable identity: even if
            // an occurrence is re-observed, the task will not run again within the hour.
            cooldownSec = 3_600,
        ),
        ProfileTemplate(
            id = "nightstand-nfc-sleep",
            title = "Nightstand NFC sleep mode",
            summary = "Dim screen and lower media volume when a known NFC tag is scanned.",
            category = "Sleep",
            availability = TemplateAvailability.RequiresSetup,
            safetyNote = "Creates a disabled profile. Requires NFC hardware and Write Settings/volume access before enabling.",
            inputs = listOf(
                TemplateSlot("tagId", "NFC tag ID", "04AABBCC", hint = "Scan a tag and copy its ID from Inspector", selector = BlueprintSelectorKind.TEXT, section = "NFC"),
            ),
            contexts = listOf(
                TemplateContext(ContextType.EVENT, mapOf("event" to "nfc", "tagId" to "{tagId}")),
            ),
            actions = listOf(
                TemplateAction("brightness.set", "Dim screen", mapOf("level" to "24")),
                TemplateAction("volume.set", "Lower media volume", mapOf("stream" to "music", "level" to "15")),
            ),
        ),
        ProfileTemplate(
            id = "search-incoming-numbers",
            title = "Search incoming phone numbers",
            summary = "Search unknown incoming phone numbers using a configurable search engine.",
            category = "Calls",
            availability = TemplateAvailability.RequiresSetup,
            safetyNote = "Creates a disabled profile. Needs Phone state, Call log, Contacts and notifications before enabling. " +
                "The number leaves your phone only when you tap Search. Hidden callers and your contacts are never searched.",
            inputs = listOf(callerSearchUrlInput()),
            contexts = listOf(incomingCallFromStranger()),
            actions = listOf(unknownCallerNotification()),
            runtimePlaceholders = CALLER_NUMBER_PLACEHOLDER,
        ),
        ProfileTemplate(
            id = "search-incoming-numbers-automatic",
            title = "Search incoming phone numbers automatically",
            summary = "Open a search for unknown incoming phone numbers as soon as the phone rings.",
            category = "Calls",
            availability = TemplateAvailability.RequiresSetup,
            safetyNote = "Creates a disabled profile. Needs Phone state, Call log, Contacts, notifications and Display over other apps before enabling. " +
                "Every unknown caller's number goes to the search site without asking. Without Display over other apps Android won't open the browser, " +
                "so the notification stays as the way to search by hand. Hidden callers and your contacts are never searched.",
            inputs = listOf(callerSearchUrlInput()),
            contexts = listOf(incomingCallFromStranger()),
            // The notification comes first: a blocked url.open fails the task, and it has to stay behind.
            actions = listOf(
                unknownCallerNotification(),
                TemplateAction("url.open", "Search the number", mapOf("url" to "{searchUrl}")),
            ),
            runtimePlaceholders = CALLER_NUMBER_PLACEHOLDER,
        ),
        ProfileTemplate(
            id = "vpn-off-at-home-wireguard",
            title = "WireGuard off at home",
            summary = "Turn a WireGuard tunnel off on your home Wi-Fi and back on when you leave it.",
            category = "Network",
            availability = TemplateAvailability.RequiresSetup,
            safetyNote = "Creates a disabled profile. Needs location access to read the Wi-Fi name, WireGuard tunnel control in Setup, " +
                "and Allow remote control apps turned on in the WireGuard settings. It only changes the VPN as you join or leave that network.",
            inputs = listOf(
                homeWifiInput(),
                TemplateSlot("tunnel", "Tunnel name", "wg0", hint = "Exactly as WireGuard shows it"),
            ),
            contexts = listOf(onHomeWifi()),
            actions = listOf(TemplateAction("vpn.tunnel", "Turn the VPN off", wireGuardArgs("down"))),
            exitActions = listOf(TemplateAction("vpn.tunnel", "Turn the VPN on", wireGuardArgs("up"))),
        ),
        ProfileTemplate(
            id = "vpn-off-at-home-tailscale",
            title = "Tailscale off at home",
            summary = "Disconnect Tailscale on your home Wi-Fi and reconnect when you leave it.",
            category = "Network",
            availability = TemplateAvailability.RequiresSetup,
            safetyNote = "Creates a disabled profile. Needs location access to read the Wi-Fi name, and you need to be logged in to Tailscale. " +
                "It only changes the VPN as you join or leave that network.",
            inputs = listOf(homeWifiInput()),
            contexts = listOf(onHomeWifi()),
            actions = listOf(TemplateAction("vpn.tunnel", "Turn the VPN off", mapOf("client" to "tailscale", "state" to "down"))),
            exitActions = listOf(TemplateAction("vpn.tunnel", "Turn the VPN on", mapOf("client" to "tailscale", "state" to "up"))),
        ),
    )

    private fun homeWifiInput() =
        TemplateSlot("ssid", "Home Wi-Fi name", "Home WiFi", hint = "Exact SSID", selector = BlueprintSelectorKind.WIFI_SSID)

    private fun onHomeWifi() = TemplateContext(ContextType.STATE, mapOf("key" to "wifi", "value" to "{ssid}"))

    private fun wireGuardArgs(state: String) = mapOf("client" to "wireguard", "state" to state, "tunnel" to "{tunnel}")

    fun get(id: String): ProfileTemplate? = all.firstOrNull { it.id == id }
}
