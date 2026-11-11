package com.opentasker.core.capabilities

import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task
import kotlinx.serialization.Serializable

/** User-reviewable powers that an action can exercise under OpenTasker's app-wide grants. */
@Serializable
enum class AutomationPower {
    DATA_ACCESS,
    EXTERNAL_TRANSMISSION,
    DEVICE_CONTROL,
    DESTRUCTIVE,
}

data class ActionSensitivityClassification(
    val actionId: String,
    val powers: Set<AutomationPower>,
    val known: Boolean,
)

data class DataToExternalChain(
    val sourceActionId: String,
    val sinkActionId: String,
)

data class AutomationRiskSummary(
    val powers: Set<AutomationPower>,
    val sensitiveActionIds: Set<String>,
    val unknownActionIds: Set<String>,
    val dataToExternalChains: List<DataToExternalChain>,
)

/**
 * Explicit sensitivity registry for every built-in action. There is intentionally no permissive
 * default: an action absent from these sets is treated as unknown and receives every power until
 * it is reviewed and classified.
 */
object AutomationSensitivityRegistry {
    private val localOnlyActionIds = setOf(
        "var.set",
        "var.persist",
        "data.read",
        "datetime.format",
        "datetime.parse",
        "datetime.add",
        "text.match",
        "text.replace",
        "text.split",
        "text.join",
        "text.substring",
        "var.split",
        "var.join",
        "var.convert",
        "array.push",
        "array.pop",
        "array.clear",
        "array.process",
        "flow.wait",
        "task.run",
        "flow.if",
        "flow.else",
        "flow.endif",
        "flow.foreach",
        "flow.endfor",
        "flow.try",
        "flow.catch",
        "flow.endtry",
        "flow.stop",
        "tasker.unsupported",
        "macrodroid.unsupported",
        "log",
    )

    private val dataAccessActionIds = setOf(
        "clipboard.get",
        "contacts.lookup",
        "plugin.locale.query",
        "script.termux.run",
        "script.shizuku.run",
        "screenshot.take",
        "file.read",
        "file.list",
    )

    private val externalTransmissionActionIds = setOf(
        "intent.launch",
        "plugin.locale.fire",
        "plugin.locale.query",
        "script.termux.run",
        "script.shizuku.run",
        "app.launch",
        "url.open",
        "sms.send",
        "http.request",
        "http.get",
        "http.post",
        "integration.home_assistant.webhook",
        "mqtt.publish",
        "ping",
        "download",
        "wol",
    )

    private val deviceControlActionIds = setOf(
        "clipboard.set",
        "notify.show",
        "notify.cancel",
        "notify.progress",
        "tts.speak",
        "intent.launch",
        "plugin.locale.fire",
        "plugin.locale.query",
        "script.termux.run",
        "script.shizuku.run",
        "wifi.toggle",
        "bluetooth.toggle",
        "brightness.set",
        "volume.set",
        "airplane.toggle",
        "aod.set",
        "wifi.scan",
        "vpn.tunnel",
        "mobile.toggle",
        "hotspot.toggle",
        "screen.timeout",
        "settings.write",
        "dnd.set",
        "zen.rule.set",
        "zen.rule.clear",
        "ringer.set",
        "torch.set",
        "tile.set",
        "wallpaper.set",
        "state.temporary",
        "ime.info",
        "ime.set",
        "app.launch",
        "shortcut.publish",
        "app.kill",
        "app.archive",
        "app.unarchive",
        "home.go",
        "url.open",
        "sms.send",
        "screenshot.take",
        "file.write",
        "file.append",
        "file.delete",
        "download",
        "wol",
        "sound.play",
        "sound.beep",
        "sound.stop",
        "sound.pause",
        "track.next",
        "track.previous",
        "media.mute",
        "vibrate",
        "reboot",
        "lock",
        "screen.off",
        "wake",
    )

    private val destructiveActionIds = setOf(
        "script.termux.run",
        "script.shizuku.run",
        "app.kill",
        "app.archive",
        "file.write",
        "file.delete",
        "download",
        "reboot",
        // Writes a named Android setting. Listed here rather than only as device control because
        // the access behind it is granted once over a cable and then persists, so an imported
        // profile inherits it. The action refuses the settings that hand another app a role or a
        // privilege, but that list is a denylist and a denylist is never provably complete, so an
        // import carrying this deserves to be called out on its own rather than folded into the
        // same acknowledgement as changing the screen brightness.
        "settings.write",
    )

    private val explicitActionIds = localOnlyActionIds +
        dataAccessActionIds +
        externalTransmissionActionIds +
        deviceControlActionIds +
        destructiveActionIds

    fun classifiedActionIds(): Set<String> = explicitActionIds

    fun isKnown(actionId: String): Boolean = actionId in explicitActionIds

    fun classify(actionId: String): ActionSensitivityClassification {
        if (!isKnown(actionId)) {
            return ActionSensitivityClassification(
                actionId = actionId,
                powers = AutomationPower.entries.toSet(),
                known = false,
            )
        }
        return ActionSensitivityClassification(
            actionId = actionId,
            powers = buildSet {
                if (actionId in dataAccessActionIds) add(AutomationPower.DATA_ACCESS)
                if (actionId in externalTransmissionActionIds) add(AutomationPower.EXTERNAL_TRANSMISSION)
                if (actionId in deviceControlActionIds) add(AutomationPower.DEVICE_CONTROL)
                if (actionId in destructiveActionIds) add(AutomationPower.DESTRUCTIVE)
            },
            known = true,
        )
    }

    fun summarize(task: Task): AutomationRiskSummary {
        val powers = linkedSetOf<AutomationPower>()
        val sensitiveActionIds = linkedSetOf<String>()
        val unknownActionIds = linkedSetOf<String>()
        val chains = linkedSetOf<DataToExternalChain>()
        var latestDataSource: String? = null

        task.actions.forEach { action ->
            val classification = classify(action.type)
            powers += classification.powers
            if (classification.powers.isNotEmpty()) sensitiveActionIds += action.type
            if (!classification.known) unknownActionIds += action.type

            val readsData = AutomationPower.DATA_ACCESS in classification.powers
            val transmits = AutomationPower.EXTERNAL_TRANSMISSION in classification.powers
            if (readsData) latestDataSource = action.type
            if (transmits && latestDataSource != null) {
                chains += DataToExternalChain(latestDataSource, action.type)
            }
        }

        return AutomationRiskSummary(powers, sensitiveActionIds, unknownActionIds, chains.toList())
    }

    fun reachableTasks(profile: Profile, tasks: List<Task>): List<Task> {
        val byId = tasks.associateBy(Task::id)
        val byName = tasks.groupBy { it.name.lowercase() }
        val queued = ArrayDeque<Task>()
        val visited = linkedSetOf<Long>()

        listOfNotNull(profile.enterTaskId, profile.exitTaskId).forEach { taskId ->
            byId[taskId]?.let(queued::addLast)
        }
        while (queued.isNotEmpty()) {
            val task = queued.removeFirst()
            if (!visited.add(task.id)) continue
            task.actions.filter { it.type == "task.run" }.forEach { action ->
                val reference = listOf("task", "name", "id")
                    .firstNotNullOfOrNull { key -> action.args[key]?.trim()?.takeIf(String::isNotBlank) }
                    ?: return@forEach
                val targets = when {
                    '%' in reference || "{{" in reference -> tasks
                    reference.toLongOrNull() != null -> listOfNotNull(byId[reference.toLong()])
                    else -> byName[reference.lowercase()].orEmpty()
                }
                targets.forEach(queued::addLast)
            }
        }
        return visited.mapNotNull(byId::get)
    }

    fun summarize(profile: Profile, tasks: List<Task>): AutomationRiskSummary {
        val summaries = reachableTasks(profile, tasks).map(::summarize)
        val powers = summaries.flatMapTo(linkedSetOf()) { it.powers }
        val sensitiveActionIds = summaries.flatMapTo(linkedSetOf()) { it.sensitiveActionIds }
        val unknownActionIds = summaries.flatMapTo(linkedSetOf()) { it.unknownActionIds }
        val chains = summaries.flatMapTo(linkedSetOf()) { it.dataToExternalChains }

        if (
            chains.isEmpty() &&
            AutomationPower.DATA_ACCESS in powers &&
            AutomationPower.EXTERNAL_TRANSMISSION in powers
        ) {
            val source = sensitiveActionIds.firstOrNull { AutomationPower.DATA_ACCESS in classify(it).powers }
            val sink = sensitiveActionIds.firstOrNull { AutomationPower.EXTERNAL_TRANSMISSION in classify(it).powers }
            if (source != null && sink != null) chains += DataToExternalChain(source, sink)
        }
        return AutomationRiskSummary(powers, sensitiveActionIds, unknownActionIds, chains.toList())
    }
}

data class ImportedProfileEnableReview(
    val risk: AutomationRiskSummary,
    val feedbackLoopRisks: List<FeedbackLoopRisk>,
    val lintFindings: List<AutomationLintFinding> = emptyList(),
    val unsupportedActionIds: Set<String>,
    val missingTaskIds: Set<Long>,
    val requiresAcknowledgement: Boolean,
) {
    val canAcknowledge: Boolean
        get() = unsupportedActionIds.isEmpty() &&
            risk.unknownActionIds.isEmpty() &&
            missingTaskIds.isEmpty() &&
            lintFindings.none { it.severity == AutomationLintSeverity.BLOCKING }
}

object ImportedProfileEnablePolicy {
    fun review(
        profile: Profile,
        tasks: List<Task>,
        otherProfiles: List<Profile> = emptyList(),
        strings: AutomationLintStrings = AutomationLintStrings.English,
    ): ImportedProfileEnableReview {
        val reachable = AutomationSensitivityRegistry.reachableTasks(profile, tasks)
        val unsupported = reachable
            .flatMap { it.actions }
            .map { it.type }
            .filterNot { ActionCapabilityRegistry.get(it).canAdd }
            .toSortedSet()
        val taskIds = tasks.mapTo(hashSetOf(), Task::id)
        val missingTaskIds = listOfNotNull(profile.enterTaskId, profile.exitTaskId)
            .filterNot(taskIds::contains)
            .toSortedSet()
        return ImportedProfileEnableReview(
            risk = AutomationSensitivityRegistry.summarize(profile, tasks),
            feedbackLoopRisks = AutomationFeedbackRiskAnalyzer.analyze(profile, tasks),
            lintFindings = AutomationLint.analyze(
                profile.copy(enabled = true),
                tasks,
                otherProfiles,
                strings,
            ).forProfile(profile.id),
            unsupportedActionIds = unsupported,
            missingTaskIds = missingTaskIds,
            requiresAcknowledgement = profile.requiresRiskAcknowledgement,
        )
    }
}
