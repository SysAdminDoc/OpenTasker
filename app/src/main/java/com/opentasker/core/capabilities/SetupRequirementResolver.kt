package com.opentasker.core.capabilities

import com.opentasker.core.contexts.BT_NEAR_STATE_KEY
import com.opentasker.core.contexts.stateContextKey
import com.opentasker.core.contexts.stateSpecReadsWifiSsid
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task

/**
 * The smallest set of platform capabilities required by enabled automations.
 *
 * This stays pure so Setup can be tested without an Android device. Unknown action/context
 * payloads conservatively keep their family requirement; an empty workspace produces no
 * automation-specific requirements.
 */
enum class SetupRequirement {
    USAGE_ACCESS,
    NOTIFICATION_ACCESS,
    CALENDAR,
    OVERLAY,
    WRITE_SETTINGS,
    DEVICE_ADMIN,
    FOREGROUND_LOCATION,
    BACKGROUND_LOCATION,
    NEARBY_WIFI,
    BLUETOOTH,
    BLUETOOTH_SCAN,
    LOCAL_NETWORK,
    SMS,
    DND,
    CONTACTS,
    SCREEN_RECORDING,
    PHYSICAL_ACTIVITY,
    PHONE_STATE,
    CALL_LOG,
}

object SetupRequirementResolver {
    fun resolve(profiles: List<Profile>, tasks: List<Task>): Set<SetupRequirement> {
        val enabled = profiles.filter { it.enabled && !it.requiresRiskAcknowledgement }
        if (enabled.isEmpty()) return emptySet()

        val requirements = linkedSetOf<SetupRequirement>()
        enabled.flatMap { it.contexts }.forEach { context ->
            requirements += contextRequirements(context)
        }

        val byId = tasks.associateBy(Task::id)
        val byName = tasks.groupBy { it.name.trim().lowercase() }
        val visited = hashSetOf<Long>()
        val queue = ArrayDeque<Task>()
        enabled.flatMap { listOfNotNull(it.enterTaskId, it.exitTaskId) }
            .mapNotNullTo(queue) { byId[it] }
        while (queue.isNotEmpty()) {
            val task = queue.removeFirst()
            if (!visited.add(task.id)) continue
            task.actions.forEach { action ->
                requirements += actionRequirements(action.type)
                if (action.type != "task.run") return@forEach
                val reference = listOf("task", "name", "id")
                    .firstNotNullOfOrNull { key -> action.args[key]?.trim()?.takeIf(String::isNotBlank) }
                    ?: return@forEach
                val targets = reference.toLongOrNull()?.let { listOfNotNull(byId[it]) }
                    ?: byName[reference.lowercase()].orEmpty()
                queue.addAll(targets)
            }
        }
        return requirements
    }

    /**
     * What a template needs, before any of it exists as a stored profile. Onboarding uses this to
     * point Setup at the grants a freshly installed template is waiting on.
     *
     * Contexts matter as much as actions here. A calendar trigger that only turns the volume down
     * needs calendar access, and an app-usage trigger needs usage access, while neither action
     * requires anything at all. Reading actions alone sent exactly those templates to Profiles, or
     * worse, showed a checklist that omitted the one grant the trigger depended on.
     */
    fun resolveForTemplate(
        actionTypes: List<String>,
        contexts: List<ContextSpec>,
    ): Set<SetupRequirement> {
        val requirements = linkedSetOf<SetupRequirement>()
        contexts.forEach { context -> requirements += contextRequirements(context) }
        actionTypes.forEach { type -> requirements += actionRequirements(type) }
        return requirements
    }

    private fun contextRequirements(context: ContextSpec): Set<SetupRequirement> {
        val tokens = context.config.entries
            .flatMap { (key, value) -> listOf(key, value) }
            .joinToString(" ")
            .lowercase()
        return buildSet {
            when (context.type) {
                ContextType.APPLICATION -> add(SetupRequirement.USAGE_ACCESS)
                ContextType.LOCATION -> {
                    add(SetupRequirement.FOREGROUND_LOCATION)
                    add(SetupRequirement.BACKGROUND_LOCATION)
                }
                ContextType.STATE -> {
                    // The value is a device name here, so the word matching below would read
                    // "Sam's Phone" as a phone-state context and "Home Network" as a Wi-Fi one.
                    if (stateContextKey(context) == BT_NEAR_STATE_KEY) {
                        add(SetupRequirement.BLUETOOTH)
                        add(SetupRequirement.BLUETOOTH_SCAN)
                        return@buildSet
                    }
                    if (tokens.containsAny("wifi", "ssid", "network")) add(SetupRequirement.NEARBY_WIFI)
                    // Android withholds a network's name without precise location, and withholds it
                    // from a backgrounded app without "allow all the time", so an SSID profile that
                    // skipped these grants read Unknown forever (issue #17).
                    if (stateSpecReadsWifiSsid(context)) {
                        add(SetupRequirement.FOREGROUND_LOCATION)
                        add(SetupRequirement.BACKGROUND_LOCATION)
                    }
                    if (tokens.containsAny("bluetooth", "bt")) add(SetupRequirement.BLUETOOTH)
                    if (tokens.containsAny("activity", "physical_activity", "motion")) {
                        add(SetupRequirement.PHYSICAL_ACTIVITY)
                    }
                    if (tokens.containsAny("speed", "velocity")) add(SetupRequirement.FOREGROUND_LOCATION)
                    if (tokens.containsAny("roaming", "phone_call", "call_state", "phone", "call")) {
                        add(SetupRequirement.PHONE_STATE)
                    }
                }
                ContextType.EVENT -> when {
                    // Android withholds a ringing call's number without the Call log permission, and
                    // only Contacts can say the caller is a stranger.
                    tokens.contains("incoming_call") -> {
                        add(SetupRequirement.PHONE_STATE)
                        add(SetupRequirement.CALL_LOG)
                        add(SetupRequirement.CONTACTS)
                    }
                    tokens.contains("screen_recording") -> add(SetupRequirement.SCREEN_RECORDING)
                    tokens.contains("sms_received") -> add(SetupRequirement.SMS)
                    tokens.containsAny("notification", "notify") -> add(SetupRequirement.NOTIFICATION_ACCESS)
                    tokens.contains("calendar") -> add(SetupRequirement.CALENDAR)
                }
                ContextType.PLUGIN,
                ContextType.TIME,
                ContextType.DAY,
                -> Unit
            }
        }
    }

    private fun actionRequirements(type: String): Set<SetupRequirement> = buildSet {
        when (type) {
            "brightness.set", "screen.timeout" -> add(SetupRequirement.WRITE_SETTINGS)
            "lock" -> add(SetupRequirement.DEVICE_ADMIN)
            // A profile runs in the background, where Android silently drops the browser launch
            // unless the app can draw over others.
            "url.open" -> add(SetupRequirement.OVERLAY)
            "sms.send" -> add(SetupRequirement.SMS)
            "contacts.lookup" -> add(SetupRequirement.CONTACTS)
            "dnd.set", "zen.rule.set", "zen.rule.clear", "ringer.set", "volume.set", "media.mute" -> add(SetupRequirement.DND)
            "http.request", "http.get", "http.post", "integration.home_assistant.webhook", "mqtt.publish", "ping", "download", "wol" -> add(SetupRequirement.LOCAL_NETWORK)
            // Termux and Shizuku are optional integrations, not permission blockers.
            "script.termux.run", "script.shizuku.run" -> Unit
            "scene.show", "scene.overlay" -> add(SetupRequirement.OVERLAY)
        }
    }

    private fun String.containsAny(vararg values: String): Boolean = values.any(::contains)
}
