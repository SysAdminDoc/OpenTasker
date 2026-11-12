package com.opentasker.core.transfer

import com.opentasker.core.actions.ActionValueType
import com.opentasker.core.actions.ArrayProcessAction
import com.opentasker.core.actions.IntentDispatchParseResult
import com.opentasker.core.actions.IntentDispatchPolicy
import com.opentasker.core.actions.declaredActionOutputs
import com.opentasker.core.apps.PackageNamePolicy
import com.opentasker.core.contexts.BootContextEvents
import com.opentasker.core.contexts.DaySchedule
import com.opentasker.core.contexts.TimeContextBounds
import com.opentasker.core.contexts.WIFI_CONNECTED_WORDS
import com.opentasker.core.contexts.WIFI_DISCONNECTED_WORDS
import com.opentasker.core.data.TextOps
import com.opentasker.core.data.VariableConvert
import com.opentasker.core.engine.FlowControl
import com.opentasker.core.engine.STOP_TASK_ACTION_ID
import com.opentasker.core.engine.SUB_TASK_ACTION_ID
import com.opentasker.core.engine.SUB_TASK_RESET_RETURN_KEY
import com.opentasker.core.engine.SUB_TASK_RETURN_KEY
import com.opentasker.core.engine.variables.TaskerBuiltIns
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.ProfileConcurrencyPolicy
import com.opentasker.core.model.ProfileLifetime
import com.opentasker.core.model.ProfileLifecyclePolicy
import com.opentasker.core.model.ProfileOverflowPolicy
import com.opentasker.core.model.Task
import com.opentasker.core.model.Variable
import com.opentasker.core.model.VariableNamePolicy
import com.opentasker.core.plugins.locale.LocaleBundleValue
import com.opentasker.core.plugins.locale.LocalePluginBundleCodec
import com.opentasker.core.plugins.locale.LocalePluginContract
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import java.io.StringReader
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

data class TaskerXmlImportReport(
    val bundle: OpenTaskerBundle,
    val sourceTaskCount: Int,
    val sourceProfileCount: Int,
    val sourceVariableCount: Int,
    val sourceSceneCount: Int,
    val mappedActions: List<TaskerMappedAction>,
    val unsupportedActions: List<TaskerUnsupportedAction>,
    val warnings: List<String> = emptyList(),
    val lossyWarnings: List<String> = emptyList(),
)

data class TaskerMappedAction(
    val taskName: String,
    val taskerCode: String,
    val openTaskerActionId: String,
)

data class TaskerUnsupportedAction(
    val taskName: String,
    val taskerCode: String,
    val actionIndex: Int,
)

/** Tasker stores an audio stream as Android's own number, and 4 is AudioManager.STREAM_ALARM. */
internal const val TASKER_ALARM_STREAM = 4

/** AudioManager.STREAM_MUSIC, the stream Tasker's Beep plays on unless it's told otherwise. */
internal const val TASKER_MUSIC_STREAM = 3

/** Tasker's Beep plays a tone at a quarter of the frequency number it stores. */
internal const val TASKER_BEEP_PITCH_DIVISOR = 4L

/**
 * The streams besides Music that Beep, Say, Play Ringtone and Music Play play on here, keyed by
 * Android's stream number: 4 Alarm, 2 Ringer, 5 Notification.
 */
internal val TASKER_OUTPUT_STREAMS = mapOf(TASKER_ALARM_STREAM to "alarm", 2 to "ring", 5 to "notification")

/** Tasker Goto's arg0 kinds besides a step number (0) and a label (1): Top of Loop, End of Loop, End of If. */
internal val TASKER_GOTO_BLOCK_JUMPS = mapOf(
    2 to FlowControl.GOTO_LOOP_TOP,
    3 to FlowControl.GOTO_LOOP_END,
    4 to FlowControl.GOTO_IF_END,
)

private fun taskerOutputStreamArg(stream: Int?): Map<String, String> =
    TASKER_OUTPUT_STREAMS[stream]?.let { mapOf("stream" to it) }.orEmpty()

/** AudioManager's names for the other stream numbers a Tasker export can hold. */
private val TASKER_STREAM_NAMES = mapOf(
    0 to "Call", 1 to "System", 6 to "Bluetooth", 8 to "Dial tones", 10 to "Accessibility",
)

/**
 * Beep, Say, Play Ringtone and Music Play have Music, Alarm, Ringer and Notification here, so a
 * sound Tasker sends anywhere else, or to a stream held in a variable, comes over on Music and says so.
 */
private fun taskerStreamWarning(actionName: String, stream: Int?, variable: String?, plays: String = "plays"): String? = when {
    variable?.contains('%') == true ->
        "Tasker $actionName picks its stream from $variable, which this app can't, so it $plays on Music"
    variable != null -> "Tasker $actionName's stream \"$variable\" isn't a stream number, so it $plays on Music"
    stream != null && stream != TASKER_MUSIC_STREAM && stream !in TASKER_OUTPUT_STREAMS -> {
        val name = TASKER_STREAM_NAMES[stream]?.let { " ($it)" }.orEmpty()
        "Tasker $actionName plays on stream $stream$name, which this app doesn't offer, so it $plays on Music"
    }
    else -> null
}

/** Names the Tasker built-ins a task reads that this app leaves as written. */
private fun taskerBuiltInWarning(taskName: String, names: List<String>): String {
    val listed = names.map { "%$it" }
    return if (listed.size == 1) {
        "Task '$taskName' reads ${listed.single()}, a Tasker built-in this app doesn't fill in yet, so it stays as written."
    } else {
        "Task '$taskName' reads ${listed.dropLast(1).joinToString(", ")} and ${listed.last()}, " +
            "Tasker built-ins this app doesn't fill in yet, so they stay as written."
    }
}

object TaskerXmlImporter {
    fun parse(
        rawXml: String,
        appVersion: String,
        importedAtEpochMs: Long = System.currentTimeMillis(),
    ): TaskerXmlImportReport = parse(rawXml, appVersion, importedAtEpochMs, ImportResourceBudget.Default)

    internal fun parse(
        rawXml: String,
        appVersion: String,
        importedAtEpochMs: Long,
        budget: ImportResourceBudget,
    ): TaskerXmlImportReport {
        val sanitizedXml = ImportResourceGuard.sanitizeTaskerXml(rawXml)
        ImportResourceGuard.requireXmlPreflight(sanitizedXml, budget)
        val doc = parseDocument(sanitizedXml)
        val warnings = mutableListOf<String>()
        val lossyWarnings = mutableListOf<String>()
        val mappedActions = mutableListOf<TaskerMappedAction>()
        val unsupportedActions = mutableListOf<TaskerUnsupportedAction>()

        val tasks = pinNumericTaskNames(parseTasks(doc, mappedActions, unsupportedActions, lossyWarnings))
        val variables = parseVariables(doc, lossyWarnings)
        val profiles = parseProfiles(doc, tasks.map { it.id }.toSet(), lossyWarnings)
        val sceneCount = doc.elementsByTagName("Scene").size
        if (sceneCount > 0) {
            lossyWarnings += "Tasker scenes are not imported yet; $sceneCount scene(s) were excluded."
        }

        val bundle = OpenTaskerBundleCodec.build(
            appVersion = appVersion,
            exportedAtEpochMs = importedAtEpochMs,
            profiles = profiles,
            tasks = tasks,
            variables = variables,
            name = "Tasker XML Import",
            description = "Converted from a Tasker XML export. Review warnings before enabling imported profiles.",
        )
        ImportResourceGuard.requireBundle(bundle, budget)
        val mergedWarnings = (bundle.metadata.warnings + warnings + lossyWarnings).distinct()
        val reportBundle = bundle.copy(
            metadata = bundle.metadata.copy(warnings = mergedWarnings),
        )

        return TaskerXmlImportReport(
            bundle = reportBundle,
            sourceTaskCount = doc.elementsByTagName("Task").size,
            sourceProfileCount = doc.elementsByTagName("Profile").size,
            sourceVariableCount = doc.elementsByTagName("Variable").size,
            sourceSceneCount = sceneCount,
            mappedActions = mappedActions,
            unsupportedActions = unsupportedActions,
            warnings = warnings.distinct(),
            lossyWarnings = lossyWarnings.distinct(),
        )
    }

    private fun parseTasks(
        doc: Document,
        mappedActions: MutableList<TaskerMappedAction>,
        unsupportedActions: MutableList<TaskerUnsupportedAction>,
        lossyWarnings: MutableList<String>,
    ): List<Task> {
        val usedIds = mutableSetOf<Long>()
        return doc.elementsByTagName("Task").mapIndexed { index, element ->
            val id = uniqueId(sourceId(element, index), usedIds)
            val name = element.childText("nme", "name").ifBlank { "Tasker Task $id" }
            // Tasker writes a task's steps in text order of their sr (act0, act1, act10, act11, act2),
            // and runs them in number order.
            val actions = element.directChildren("Action").sortedBy { it.argIndex() }.mapIndexed { actionIndex, actionElement ->
                val parsed = parseAction(name, actionElement, actionIndex)
                parsed.mapped?.let(mappedActions::add)
                parsed.unsupported?.let(unsupportedActions::add)
                parsed.lossyWarning?.let { lossyWarnings += "Task '$name' action ${actionIndex + 1}: $it" }
                parsed.action
            }
            // A built-in the task's own steps set here, like %HTTPD from HTTP Request, is filled in.
            val written = actions.flatMap { action ->
                declaredActionOutputs(action.type).filter { it.type != ActionValueType.ARRAY }.mapNotNull { it.resolveName(action.args) }
            }.toSet()
            val builtIns = TaskerBuiltIns.unsupportedReads(element.directChildren("Action").flatMap { it.textNodes() }.joinToString("\n")) - written
            if (builtIns.isNotEmpty()) lossyWarnings += taskerBuiltInWarning(name, builtIns)
            Task(id = id, name = name, actions = actions)
        }
    }

    /**
     * Tasker's Perform Task and Stop find their task by name, and a run here reads a number as an id
     * first, so a call to a task named "5" would land on whichever task ends up with id 5. Such a call
     * is pinned to the id of the task in this file with that name, which a bundle import then moves to
     * the task's new id along with every other id reference.
     */
    private fun pinNumericTaskNames(tasks: List<Task>): List<Task> {
        val idByNumericName = buildMap {
            tasks.forEach { task -> task.name.trim().takeIf { it.toLongOrNull() != null }?.let { putIfAbsent(it, task.id) } }
        }
        if (idByNumericName.isEmpty()) return tasks
        return tasks.map { task ->
            task.copy(
                actions = task.actions.map { action ->
                    val id = action.args["task"]?.trim()?.let(idByNumericName::get)
                    val callsTask = action.type == SUB_TASK_ACTION_ID || action.type == STOP_TASK_ACTION_ID
                    if (id == null || !callsTask) action else action.copy(args = action.args + ("task" to id.toString()))
                },
            )
        }
    }

    private fun parseProfiles(
        doc: Document,
        taskIds: Set<Long>,
        lossyWarnings: MutableList<String>,
    ): List<Profile> {
        val usedIds = mutableSetOf<Long>()
        return doc.elementsByTagName("Profile").mapIndexedNotNull { index, element ->
            val id = uniqueId(sourceId(element, index), usedIds)
            val name = element.childText("nme", "name").ifBlank { "Tasker Profile $id" }
            val enterTaskTags = arrayOf("mid0", "task", "tid", "enterTaskId")
            val sourceEnterTaskId = element.childText(*enterTaskTags).toLongOrNull()
            val sourceExitTaskId = element.childText("mid1", "exitTaskId").toLongOrNull()
            val exitTaskId = sourceExitTaskId?.takeIf { it in taskIds }
            // Tasker writes no mid0 for a profile that only has an exit task. A mid0 that is there
            // but unreadable is a broken enter task, not a missing one.
            val hasEnterTaskTag = enterTaskTags.any { element.directChildren(it).isNotEmpty() }
            val enterTaskId = when {
                !hasEnterTaskTag && sourceExitTaskId != null -> exitTaskId?.let { 0L } ?: run {
                    lossyWarnings += "Profile '$name' was skipped because its exit task could not be mapped."
                    return@mapIndexedNotNull null
                }
                sourceEnterTaskId == null || sourceEnterTaskId !in taskIds -> {
                    lossyWarnings += "Profile '$name' was skipped because its entry task could not be mapped."
                    return@mapIndexedNotNull null
                }
                else -> sourceEnterTaskId
            }

            val parsedContexts = parseContexts(element, name, lossyWarnings)
            val contexts = parsedContexts.contexts
            if (contexts.isEmpty()) {
                lossyWarnings += "Profile '$name' was skipped because it has no supported Tasker contexts."
                return@mapIndexedNotNull null
            }
            if (parsedContexts.droppedAny) {
                lossyWarnings += "Profile '$name' was imported turned off because a context it needs couldn't be read, and without it the profile would run more often than it did in Tasker."
            }
            if (element.directChildren("limit").isNotEmpty()) {
                element.repeatsLeft()?.let { runs ->
                    lossyWarnings += "Profile '$name' was set to switch itself off in Tasker after $runs more ${if (runs == 1) "run" else "runs"}; that limit has no place here, so it was imported without one."
                }
            }

            val rawLifetime = element.childText("lifetime").uppercase(Locale.US)
            val importedExpiry = element.childText("expiresAtMs", "expiresAt").toLongOrNull()?.takeIf { it > 0L }
            val rawMaxActive = element.childText("maxActiveExecutions", "maxActive")
            val importedMaxActive = rawMaxActive.toIntOrNull()?.takeIf {
                it in ProfileConcurrencyPolicy.MIN_MAX_ACTIVE..ProfileConcurrencyPolicy.MAX_MAX_ACTIVE
            }
            if (rawMaxActive.isNotBlank() && importedMaxActive == null) {
                lossyWarnings += "Profile '$name' had an invalid active execution limit; it was reset to inherit the engine default."
            }
            val rawBurstLimit = element.childText("burstLimit", "profileBurstLimit")
            val importedBurstLimit = rawBurstLimit.toIntOrNull()?.takeIf {
                it in ProfileConcurrencyPolicy.MIN_BURST_LIMIT..ProfileConcurrencyPolicy.MAX_BURST_LIMIT
            }
            if (rawBurstLimit.isNotBlank() && importedBurstLimit == null) {
                lossyWarnings += "Profile '$name' had an invalid burst limit; it was reset to inherit the engine default."
            }
            val rawOverflowPolicy = element.childText("overflowPolicy").uppercase(Locale.US)
            val importedOverflowPolicy = runCatching { ProfileOverflowPolicy.valueOf(rawOverflowPolicy) }
                .getOrElse {
                    if (rawOverflowPolicy.isNotBlank()) {
                        lossyWarnings += "Profile '$name' had an unknown overflow policy '$rawOverflowPolicy'; it was reset to log rejections."
                    }
                    ProfileOverflowPolicy.LOG
                }
            val importedLifetime = when (rawLifetime) {
                ProfileLifetime.ONCE.name -> ProfileLifetime.ONCE
                ProfileLifetime.UNTIL_DATE.name -> if (importedExpiry != null) {
                    ProfileLifetime.UNTIL_DATE
                } else {
                    lossyWarnings += "Profile '$name' had a date lifetime without a valid expiry; it was reset to always available."
                    ProfileLifetime.NEVER
                }
                ProfileLifetime.NEVER.name, "" -> ProfileLifetime.NEVER
                else -> {
                    lossyWarnings += "Profile '$name' had an unknown lifetime '$rawLifetime'; it was reset to always available."
                    ProfileLifetime.NEVER
                }
            }
            ProfileLifecyclePolicy.normalize(
                Profile(
                    id = id,
                    name = name,
                    enabled = element.isSwitchedOnInTasker() && !parsedContexts.droppedAny,
                    enterTaskId = enterTaskId,
                    exitTaskId = exitTaskId,
                    contexts = contexts,
                    priority = element.childText("priority").toIntOrNull() ?: 0,
                    gracePeriodSec = element.childText("gracePeriodSec", "grace").toIntOrNull() ?: 0,
                    lifetime = importedLifetime,
                    expiresAtMs = importedExpiry,
                    maxActiveExecutions = importedMaxActive,
                    burstLimit = importedBurstLimit,
                    overflowPolicy = importedOverflowPolicy,
                ),
            )
        }
    }

    /**
     * `<n>`/`<v>` are what Tasker's own project exports use, and what [TaskerXmlExporter] writes.
     * Reading only `nme`/`val` meant a file this app exported and then re-imported lost every
     * variable, reported as "skipped because it had no name".
     */
    private fun parseVariables(doc: Document, lossyWarnings: MutableList<String>): List<Variable> =
        doc.elementsByTagName("Variable").mapNotNull { element ->
            val name = element.childText("nme", "name", "n").ifBlank {
                lossyWarnings += "A Tasker variable was skipped because it had no name."
                return@mapNotNull null
            }
            Variable(
                name = name,
                value = element.childText("val", "value", "v"),
                isGlobal = VariableNamePolicy.isGlobal(name),
            )
        }

    private data class ParsedProfileContexts(val contexts: List<ContextSpec>, val droppedAny: Boolean)

    private fun parseContexts(
        profile: Element,
        profileName: String,
        lossyWarnings: MutableList<String>,
    ): ParsedProfileContexts {
        var droppedAny = false
        val contexts = profile.directChildElements().mapNotNull { child ->
            val parsed = when (child.tagName.lowercase()) {
                "time" -> parseTimeContext(child, profileName, lossyWarnings)
                "day" -> parseDayContext(child, profileName, lossyWarnings)
                "application", "app" -> parseApplicationContext(child, profileName, lossyWarnings)
                "state" -> parseKeyValueContext(ContextType.STATE, child, profileName, lossyWarnings)
                "event" -> parseKeyValueContext(ContextType.EVENT, child, profileName, lossyWarnings)
                else -> {
                    if (child.tagName !in PROFILE_SCALAR_TAGS) {
                        lossyWarnings += "Profile '$profileName' has unsupported Tasker context '${child.tagName}'."
                    }
                    null
                }
            }
            if (parsed == null && child.isTaskerContext()) droppedAny = true
            // Tasker's own word for a context's Invert switch.
            parsed?.copy(invert = child.childText("pin").equals("true", ignoreCase = true))
        }
        return ParsedProfileContexts(contexts, droppedAny)
    }

    /**
     * Tasker switches a profile off by setting its limit with no repeats left, and writes that as
     * `<limit>true</limit>` with no `<repeats>`. Its importer only asks whether `<limit>` is there,
     * and a `<repeats>` above 0 keeps the profile on until those runs are used up.
     */
    private fun Element.isSwitchedOnInTasker(): Boolean {
        if (childText("off").equals("true", ignoreCase = true)) return false
        return directChildren("limit").isEmpty() || repeatsLeft() != null
    }

    private fun Element.repeatsLeft(): Int? = childText("repeats").toIntOrNull()?.takeIf { it > 0 }

    private fun Element.isTaskerContext(): Boolean =
        getAttribute("sr").startsWith("con") || tagName.lowercase() in TASKER_CONTEXT_TAGS

    /**
     * A real export lists weekdays as `wday0`, `wday1`... with Sunday as 1. Days of the month and
     * months have nowhere to go in a Day context here.
     */
    private fun parseDayContext(
        element: Element,
        profileName: String,
        lossyWarnings: MutableList<String>,
    ): ContextSpec? {
        val named = element.childText("days", "day")
        if (named.isNotBlank()) return ContextSpec(ContextType.DAY, mapOf("days" to named))

        val fields = element.directChildElements()
        val weekdays = fields
            .filter { it.tagName.startsWith("wday") }
            .mapNotNull { TASKER_WEEKDAYS[it.textContent.trim().toIntOrNull()] }
            .toSet()
        val usesCalendarDates = fields.any { it.tagName.startsWith("mday") || it.tagName.startsWith("mnth") }
        val days = DaySchedule.canonicalize(weekdays)
        if (days == null || usesCalendarDates) {
            lossyWarnings += "Profile '$profileName' has a Tasker Day context that uses dates of the month, which can't be imported."
            return null
        }
        return ContextSpec(ContextType.DAY, mapOf("days" to days))
    }

    /** A real export lists one app per `pkg0`, `pkg1`... */
    private fun parseApplicationContext(
        element: Element,
        profileName: String,
        lossyWarnings: MutableList<String>,
    ): ContextSpec? {
        val packages = element.childText("package", "pkg", "app").ifBlank {
            element.directChildElements()
                .filter { it.tagName.startsWith("pkg") }
                .map { it.textContent.trim() }
                .filter { it.isNotBlank() }
                .joinToString(",")
        }
        if (packages.isBlank()) {
            lossyWarnings += "Profile '$profileName' has a Tasker App context that names no app."
            return null
        }
        return ContextSpec(ContextType.APPLICATION, mapOf("package" to packages))
    }

    private fun parseTimeContext(
        element: Element,
        profileName: String,
        lossyWarnings: MutableList<String>,
    ): ContextSpec? {
        // Tasker writes a From or To that names a variable (%Sunrise) as fromvar/tovar. Only a
        // global can bound a context, since a profile has no task to hold a local.
        val startVariable = element.childText("fromvar")
        val endVariable = element.childText("tovar")
        val invalidVariable = listOf(startVariable, endVariable)
            .firstOrNull { it.isNotBlank() && TimeContextBounds.variableName(it) == null }
        if (invalidVariable != null) {
            lossyWarnings += "Profile '$profileName' has a Tasker Time context bounded by $invalidVariable, " +
                "which isn't a global variable."
            return null
        }
        val start = TimeContextBounds.variableName(startVariable)?.let { "%$it" }
            ?: element.childText("from", "start").ifBlank {
                clockFromParts(element.childText("fh").toIntOrNull(), element.childText("fm").toIntOrNull())
            }
        val end = TimeContextBounds.variableName(endVariable)?.let { "%$it" }
            ?: element.childText("to", "end").ifBlank {
                clockFromParts(element.childText("th").toIntOrNull(), element.childText("tm").toIntOrNull())
            }
        if (start.isBlank() || end.isBlank()) {
            lossyWarnings += "Profile '$profileName' has a Tasker Time context without a supported start/end window."
            return null
        }
        return ContextSpec(ContextType.TIME, mapOf("start" to start, "end" to end))
    }

    private fun parseKeyValueContext(
        type: ContextType,
        element: Element,
        profileName: String,
        lossyWarnings: MutableList<String>,
    ): ContextSpec? {
        taskerCodedContext(type, element)?.let { return it }
        val config = buildMap {
            element.childText("event", "name", "key").takeIf { it.isNotBlank() }?.let { key ->
                if (type == ContextType.EVENT) put("event", key) else put("key", key)
            }
            element.childText("value", "val").takeIf { it.isNotBlank() }?.let { put("value", it) }
            element.childText("filter").takeIf { it.isNotBlank() }?.let { put("filter", it) }
        }
        if (config.isEmpty()) {
            val code = element.childText("code").ifBlank { "unknown" }
            lossyWarnings += "Profile '$profileName' has a Tasker ${element.tagName} context (code $code) that can't be imported."
            return null
        }
        return ContextSpec(type, config)
    }

    /**
     * The Tasker states and events with an exact match here, read from Tasker's own fields. A field
     * set to something this app can't hold (a power source, a battery range closed at both ends, a
     * variable) leaves the context to the can't-be-imported warning.
     */
    private fun taskerCodedContext(type: ContextType, element: Element): ContextSpec? {
        if (element.actionIntVariablesByIndex().isNotEmpty()) return null
        val ints = element.actionIntsByIndex()
        val strings = element.actionStringsByIndex()
        fun state(key: String, value: String, operator: String = "=") = ContextSpec(
            ContextType.STATE,
            if (operator == "=") mapOf("key" to key, "value" to value) else mapOf("key" to key, "operator" to operator, "value" to value),
        )
        return when (type) {
            ContextType.STATE -> when (element.childText("code")) {
                "10" -> state("charging", "true").takeIf { ints[0] == 0 }
                "30" -> state("headphones", "true").takeIf { ints[0] == 0 }
                "123" -> when (ints[0]) {
                    1 -> state("screen", "on")
                    0 -> state("screen", "off")
                    else -> null
                }
                "140" -> {
                    val from = ints[0] ?: return null
                    val to = ints[1] ?: return null
                    when {
                        from > to -> null
                        from == to -> state("battery_level", from.toString())
                        to == 100 -> state("battery_level", from.toString(), ">=")
                        from == 0 -> state("battery_level", to.toString(), "<=")
                        else -> null
                    }
                }
                "160" -> {
                    val ssid = strings[0].orEmpty()
                    val anyNetwork = strings[1].isNullOrBlank() && strings[2].isNullOrBlank() && ints[3] == 2
                    // A network named "Off" or "connected" would read here as Wi-Fi's on/off state.
                    val reservedWord = ssid.lowercase().let { it in WIFI_CONNECTED_WORDS || it in WIFI_DISCONNECTED_WORDS }
                    when {
                        !anyNetwork || reservedWord || ssid.startsWith("!") || ssid.any { it in "*+/%" } -> null
                        ssid.isEmpty() -> state("wifi", "connected")
                        else -> state("wifi", ssid)
                    }
                }
                else -> null
            }
            ContextType.EVENT -> when (element.childText("code")) {
                "411" -> ContextSpec(ContextType.EVENT, mapOf("event" to BootContextEvents.EVENT_BOOT_COMPLETED))
                "461" -> {
                    if ((1..6).any { !strings[it].isNullOrBlank() } || (ints[7] ?: 0) != 0) return null
                    val owner = element.actionAppPackage(0)
                    ContextSpec(ContextType.EVENT, listOfNotNull("event" to "notification", owner?.let { "package" to it }).toMap())
                }
                else -> null
            }
            else -> null
        }
    }

    private fun parseAction(taskName: String, element: Element, actionIndex: Int): ParsedTaskerAction {
        val code = element.childText("code").ifBlank { element.getAttribute("code") }.ifBlank { "unknown" }
        val strings = element.actionStrings()
        val byIndex = element.actionStringsByIndex()
        val intsByIndex = element.actionIntsByIndex()
        val intVariables = element.actionIntVariablesByIndex()
        val normalized = code.lowercase()
        val actionWithLoss = when (normalized) {
            "523", "notify", "notify.show" -> ActionWithLoss(
                action = ActionSpec(
                type = "notify.show",
                label = "Tasker notification",
                // Tasker's Notify Cancel finds a notification by its title, so the title is the tag too.
                args = buildMap {
                    put("title", strings.getOrElse(0) { "Tasker notification" })
                    put("text", strings.getOrElse(1) { strings.getOrElse(0) { "Imported from Tasker" } })
                    strings.getOrNull(0)?.takeIf(String::isNotBlank)?.let { put("tag", it) }
                },
                ),
                lossyWarning = "notification icon, priority, and channel settings were not imported",
            )
            // Tasker's Notify Sound: arg0 title, arg1 text, arg2 icon, arg3 number, arg4 sound file,
            // arg5 priority and arg6 repeat alert. The notification comes over, its sound doesn't.
            "538" -> ActionWithLoss(
                ActionSpec(
                    type = "notify.show",
                    label = "Tasker notification",
                    args = buildMap {
                        put("title", byIndex[0].orEmptyIfBlank() ?: "Tasker notification")
                        put("text", byIndex[1].orEmptyIfBlank() ?: byIndex[0].orEmptyIfBlank() ?: "Imported from Tasker")
                        byIndex[0].orEmptyIfBlank()?.let { put("tag", it) }
                    },
                ),
                lossyWarning = "Tasker Notify Sound's sound, icon, number, priority and repeat settings weren't imported",
            )
            "779", "notify.cancel" -> ActionWithLoss(
                ActionSpec(
                    type = "notify.cancel",
                    label = "Tasker cancel notification",
                    args = buildMap {
                        strings.getOrNull(0)?.takeIf(String::isNotBlank)?.let { put("tag", it) }
                        strings.getOrNull(1)?.takeIf(String::isNotBlank)?.let { put("id", it) }
                    },
                ),
            )
            "548", "flash", "toast" -> ActionWithLoss(
                action = ActionSpec(
                type = "notify.show",
                label = "Tasker flash",
                args = mapOf(
                    "title" to "Tasker",
                    "text" to strings.getOrElse(0) { "Imported Tasker flash action" },
                ),
                ),
                lossyWarning = "flash styling was represented as a standard notification",
            )
            "547", "var.set", "variable.set" -> taskerVariableSet(strings, intsByIndex)
            "888" -> taskerVariableAddOrSubtract(code, subtract = false, byIndex, intsByIndex, intVariables)
            "890" -> taskerVariableAddOrSubtract(code, subtract = true, byIndex, intsByIndex, intVariables)
            "549" -> taskerVariableClear(code, byIndex, intsByIndex, intVariables)
            "596" -> taskerVariableConvert(code, byIndex, intsByIndex, intVariables)
            "597" -> taskerVariableSection(code, byIndex, intsByIndex, intVariables)
            "345" -> taskerTestVariable(code, element, byIndex, intsByIndex, intVariables)
            "598" -> taskerSearchReplace(code, element, byIndex, intsByIndex, intVariables)
            "590", "592", "354", "355", "356", "357", "369" -> taskerArrayAction(code, element, byIndex, intsByIndex, intVariables)
            "30", "wait", "flow.wait" -> {
                val duration = taskerDuration(
                    actionName = "Wait",
                    plainMillis = waitMillis(strings, intsByIndex).toString(),
                    ints = intsByIndex,
                    variables = intVariables,
                    units = TASKER_WAIT_UNITS,
                )
                ActionWithLoss(
                    action = ActionSpec(
                        type = "flow.wait",
                        label = "Tasker wait",
                        args = mapOf("millis" to duration.value),
                    ),
                    lossyWarning = duration.warning,
                )
            }
            "559", "say", "tts.speak" -> ActionWithLoss(
                action = ActionSpec(
                    type = "tts.speak",
                    label = "Tasker speech",
                    args = mapOf("text" to strings.getOrElse(0) { "" }) + taskerOutputStreamArg(intsByIndex[2]),
                ),
                lossyWarning = taskerStreamWarning("Say", intsByIndex[2], intVariables[2], plays = "speaks"),
            )
            "61", "vibrate" -> {
                val duration = taskerDuration(
                    actionName = "Vibrate",
                    plainMillis = waitMillis(strings, intsByIndex).coerceIn(1L, 10_000L).toString(),
                    ints = intsByIndex,
                    variables = intVariables,
                    units = TASKER_VIBRATE_UNITS,
                )
                ActionWithLoss(
                    action = ActionSpec(
                        type = "vibrate",
                        label = "Tasker vibrate",
                        args = mapOf("millis" to duration.value),
                    ),
                    lossyWarning = duration.warning,
                )
            }
            "303", "304", "305", "306", "307", "308", "volume.set" -> {
                val level = intVariables[0]?.let { taskerNumberField("Volume", it) }
                ActionWithLoss(
                    action = ActionSpec(
                        type = "volume.set",
                        label = "Tasker volume",
                        args = mapOf(
                            "stream" to taskerVolumeStream(normalized),
                            "level" to (level?.value ?: taskerLevel(strings, intsByIndex)),
                        ),
                    ),
                    lossyWarning = joinWarnings(level?.warning, "Tasker volume display/sound flags were not imported"),
                )
            }
            "312" -> taskerInterruptMode(code, byIndex, intsByIndex, intVariables)
            "313" -> taskerSoundMode(code, intsByIndex, intVariables)
            "808", "822" -> taskerSystemSwitch(code, intsByIndex, intVariables)
            "810", "brightness", "brightness.set" -> {
                val level = intVariables[0]?.let { taskerNumberField("Display Brightness", it) }
                val brightness = level?.value ?: taskerScalar(strings, intsByIndex, "auto")
                ActionWithLoss(
                    ActionSpec(
                        type = "brightness.set",
                        label = "Tasker brightness",
                        args = mapOf("brightness" to brightness),
                    ),
                    // Tasker writes only the level and warns that Auto Brightness gets in its way.
                    lossyWarning = joinWarnings(
                        level?.warning,
                        "Tasker Display Brightness leaves Auto Brightness as it is, and this step turns it off to set the level"
                            .takeIf { code == "810" && !brightness.equals("auto", ignoreCase = true) },
                    ),
                )
            }
            "812", "screen.timeout" -> {
                val total = strings.firstOrNull()?.trim()?.toLongOrNull() ?: literalMillis(intsByIndex, TASKER_TIMEOUT_UNITS)
                if (total <= 0L && intVariables.isEmpty()) {
                    // The shortest timeout this app can set is one second, which nobody wants by accident.
                    taskerPlaceholder(code, "Tasker Display Timeout has no time set")
                } else {
                    val duration = taskerDuration(
                        actionName = "Display Timeout",
                        plainMillis = total.coerceIn(1_000L, TASKER_MAX_SCREEN_TIMEOUT_MS).toString(),
                        ints = intsByIndex,
                        variables = intVariables,
                        units = TASKER_TIMEOUT_UNITS,
                    )
                    ActionWithLoss(
                        ActionSpec(
                            type = "screen.timeout",
                            label = "Tasker screen timeout",
                            args = mapOf("millis" to duration.value),
                        ),
                        lossyWarning = joinWarnings(
                            duration.warning,
                            "Tasker Display Timeout was capped at 30 minutes, the longest this app sets"
                                .takeIf { total > TASKER_MAX_SCREEN_TIMEOUT_MS },
                        ),
                    )
                }
            }
            "511", "torch", "torch.set" -> taskerSetField(
                code, "Torch", "torch.set", strings, intsByIndex, intVariables,
                warning = "Tasker Torch Level was not imported".takeIf { byIndex[1].orEmptyIfBlank() != null },
            )
            "294" -> taskerSetField(code, "Bluetooth", "bluetooth.toggle", strings, intsByIndex, intVariables)
            "333" -> taskerSetField(code, "Airplane Mode", "airplane.toggle", strings, intsByIndex, intVariables)
            "425" -> taskerSetField(code, "WiFi", "wifi.toggle", strings, intsByIndex, intVariables)
            "113" -> taskerSetField(
                code, "WiFi Tether", "hotspot.toggle", strings, intsByIndex, intVariables,
                warning = "Tasker WiFi Tether's Keep Wi-Fi when turning on was not imported".takeIf { (intsByIndex[1] ?: 0) != 0 },
            )
            "171" -> taskerBeep(byIndex, intsByIndex, intVariables)
            "159" -> taskerProfileStatus(code, byIndex, intsByIndex, intVariables)
            "192", "play", "sound.play" -> {
                // Play Ringtone's arg1 is the sound. Older exports from this app wrote the path at arg0.
                val path = byIndex[1].orEmptyIfBlank() ?: strings.firstOrNull().orEmpty()
                ActionWithLoss(
                    action = ActionSpec(
                        type = "sound.play",
                        label = "Tasker sound",
                        args = mapOf("path" to path) + taskerOutputStreamArg(intsByIndex[2]),
                    ),
                    lossyWarning = joinWarnings(
                        "Tasker Play Ringtone \"$path\" names a system sound, not a file, so it needs a file here"
                            .takeIf { '/' !in path && !path.startsWith("%") },
                        taskerStreamWarning("Play Ringtone", intsByIndex[2], intVariables[2]),
                    ),
                )
            }
            "445" -> ActionWithLoss(
                ActionSpec(
                    type = "sound.play",
                    label = "Tasker music play",
                    args = mapOf("path" to byIndex[0].orEmpty()) + taskerOutputStreamArg(intsByIndex[3]),
                ),
                lossyWarning = joinWarnings(
                    listOfNotNull(
                        "Start".takeIf { (intsByIndex[1] ?: 0) != 0 },
                        "Loop".takeIf { (intsByIndex[2] ?: 0) != 0 },
                    ).takeIf { it.isNotEmpty() }?.let { "Tasker Music Play fields not imported: ${it.joinToString(", ")}" },
                    taskerStreamWarning("Music Play", intsByIndex[3], intVariables[3]),
                ),
            )
            "443" -> taskerMediaControl(code, element, intsByIndex, intVariables)
            "449", "sound.stop" -> ActionWithLoss(
                ActionSpec(type = "sound.stop", label = "Tasker stop sound"),
                lossyWarning = taskerMusicPlayerWarning("Music Stop").takeIf { normalized == "449" },
            )
            "451", "track.next" -> ActionWithLoss(
                ActionSpec(type = "track.next", label = "Tasker next track"),
                lossyWarning = taskerMusicPlayerWarning("Music Skip").takeIf { normalized == "451" },
            )
            "453", "track.previous" -> ActionWithLoss(
                ActionSpec(type = "track.previous", label = "Tasker previous track"),
                lossyWarning = taskerMusicPlayerWarning("Music Back").takeIf { normalized == "453" },
            )
            "15", "lock" -> ActionWithLoss(
                ActionSpec(type = "lock", label = "Tasker lock device"),
                lossyWarning = "Tasker Lock's code entry screen was not imported; this uses the device's own lock screen"
                    .takeIf { normalized == "15" },
            )
            "16" -> ActionWithLoss(ActionSpec(type = "lock", label = "Tasker lock device"))
            "59" -> taskerReboot(code, intsByIndex, intVariables)
            "820" -> taskerStayOn(code, intsByIndex, intVariables)
            "806" -> taskerTurnOn(intsByIndex, intVariables)
            "20", "launch", "app.launch" -> {
                // Tasker keeps the app in an <App> element and arg1 is its Data field, so the only
                // <Str> that can be a package is arg0, which older exports from this app wrote.
                val app = element.actionAppPackage(0)
                ActionWithLoss(
                    ActionSpec(
                        type = "app.launch",
                        label = "Tasker launch app",
                        args = mapOf("package" to (app ?: byIndex[0].orEmpty())),
                    ),
                    lossyWarning = byIndex[1].orEmptyIfBlank()?.let { "Tasker Launch App's Data field was not imported: $it" },
                )
            }
            "18" -> ActionWithLoss(
                ActionSpec(
                    type = "app.kill",
                    label = "Tasker kill app",
                    args = mapOf("package" to element.actionAppPackage(0).orEmpty()),
                ),
                lossyWarning = "Tasker Kill App's Use Root option was not imported".takeIf { (intsByIndex[1] ?: 0) != 0 },
            )
            "25", "home", "home.go" -> ActionWithLoss(ActionSpec(type = "home.go", label = "Tasker go home"))
            "104", "browse", "url.open" -> ActionWithLoss(
                ActionSpec(type = "url.open", label = "Tasker open URL", args = mapOf("url" to strings.getOrElse(0) { "" })),
                lossyWarning = element.actionAppPackage(1)
                    ?.let { "Tasker Browse URL's app $it was not imported; the default app opens the link" },
            )
            "877" -> taskerSendIntent(code, byIndex, intsByIndex, intVariables)
            "176", "screenshot", "screenshot.take" -> ActionWithLoss(
                ActionSpec(
                    type = "screenshot.take",
                    label = "Tasker screenshot",
                    args = buildMap { byIndex[0].orEmptyIfBlank()?.let { put("path", it) } },
                ),
                lossyWarning = "Tasker Take Screenshot's Insert In Gallery option was not imported"
                    .takeIf { (intsByIndex[1] ?: 0) != 0 },
            )
            "235" -> taskerCustomSetting(code, byIndex, intsByIndex)
            "41" -> ActionWithLoss(
                ActionSpec(
                    type = "sms.send",
                    label = "Tasker send SMS",
                    args = mapOf("number" to byIndex[0].orEmpty(), "message" to byIndex[1].orEmpty()),
                ),
                lossyWarning = listOfNotNull(
                    "Store In Messaging App".takeIf { (intsByIndex[2] ?: 0) != 0 },
                    "SIM Card".takeIf { byIndex[3].orEmptyIfBlank() != null },
                ).takeIf { it.isNotEmpty() }?.let { "Tasker Send SMS fields not imported: ${it.joinToString(", ")}" },
            )
            "406" -> taskerDeleteFile(byIndex, intsByIndex, intVariables)
            "417" -> ActionWithLoss(
                ActionSpec(
                    type = "file.read",
                    label = "Tasker read file",
                    args = buildMap {
                        put("path", byIndex[0].orEmpty())
                        byIndex[1].orEmptyIfBlank()?.let { put("var", it) }
                    },
                ),
            )
            "339", "http.request" -> taskerHttpRequest(byIndex, intsByIndex)
            "118" -> taskerHttpGetOrPost(post = false, byIndex, intsByIndex)
            "116" -> taskerHttpGetOrPost(post = true, byIndex, intsByIndex)
            "105", "clipboard.set" -> ActionWithLoss(
                ActionSpec(
                    type = "clipboard.set",
                    label = "Tasker set clipboard",
                    args = mapOf("text" to byIndex[0].orEmpty()),
                ),
                lossyWarning = "Tasker's clipboard \"Add\" option was not imported; the clipboard is replaced"
                    .takeIf { (intsByIndex[1] ?: 0) != 0 },
            )
            // Tasker's Get Clipboard has no settings, only the list of variables it fills.
            "402" -> ActionWithLoss(
                ActionSpec(type = "clipboard.get", label = "Tasker get clipboard", args = mapOf("var" to "%cl_text")),
                lossyWarning = "Tasker Get Clipboard's %cl_text comes over, but its %cl_html_text, %cl_uri, %cl_image_uri, " +
                    "%cl_mimetypes() and %cl_extras aren't set here, and on Android 10 and newer the clipboard can only be " +
                    "read while OpenTasker is open and in front",
            )
            "410", "file.write" -> {
                // Tasker's Write File stores arg2=Append and arg3=Add Newline. Append maps onto this
                // app's separate file.append action rather than a flag, so an appending Tasker action
                // must not import as a truncating write.
                val appending = (intsByIndex[2] ?: 0) != 0
                ActionWithLoss(
                    ActionSpec(
                        type = if (appending) "file.append" else "file.write",
                        label = if (appending) "Tasker append to file" else "Tasker write file",
                        args = mapOf(
                            "path" to byIndex[0].orEmpty(),
                            "text" to byIndex[1].orEmpty(),
                        ),
                    ),
                    lossyWarning = "Tasker's \"Add Newline\" option was not imported"
                        .takeIf { (intsByIndex[3] ?: 0) != 0 },
                )
            }
            "37", "if", "flow.if" -> ActionWithLoss(
                ActionSpec(type = "flow.if", label = "Tasker if", args = mapOf("condition" to strings.joinToString(" ").ifBlank { "true" })),
            )
            "43", "else", "flow.else" -> ActionWithLoss(ActionSpec(type = "flow.else", label = "Tasker else"))
            "38", "endif", "flow.endif" -> ActionWithLoss(ActionSpec(type = "flow.endif", label = "Tasker end if"))
            "39", "for", "flow.foreach" -> {
                val items = byIndex[1].orEmpty().trim()
                val array = TASKER_WHOLE_ARRAY.matchEntire(items)?.groupValues?.get(1)
                ActionWithLoss(
                    ActionSpec(
                        type = "flow.foreach",
                        label = "Tasker for",
                        // A whole array loops by name, anything else as Tasker's values and ranges.
                        args = mapOf(
                            if (array != null) "list" to array else "values" to items,
                            "var" to (byIndex[0].orEmptyIfBlank() ?: "item"),
                        ),
                    ),
                )
            }
            "40", "endfor", "flow.endfor" -> ActionWithLoss(ActionSpec(type = "flow.endfor", label = "Tasker end for"))
            "137", "stop", "flow.stop" -> {
                val otherTask = byIndex[1].orEmptyIfBlank()
                ActionWithLoss(
                    // A named task goes to task.stop, which also ends this run when the name is its own.
                    if (otherTask != null) {
                        ActionSpec(type = "task.stop", label = "Tasker stop", args = mapOf("task" to otherTask))
                    } else {
                        ActionSpec(type = "flow.stop", label = "Tasker stop")
                    },
                    lossyWarning = "Tasker Stop's With Error option was not imported".takeIf { (intsByIndex[0] ?: 0) != 0 },
                )
            }
            "126" -> taskerReturn(code, byIndex, intsByIndex)
            "135" -> taskerGoto(code, byIndex, intsByIndex, intVariables)
            "300" -> ActionWithLoss(ActionSpec(type = "flow.anchor", label = "Tasker anchor"))
            "130", "perform.task", "task.run" -> taskerPerformTask(element, strings, byIndex, intsByIndex)
            "log" -> ActionWithLoss(
                action = ActionSpec(
                    type = "log",
                    label = "Tasker log",
                    args = mapOf("message" to strings.getOrElse(0) { "Imported Tasker log action" }),
                ),
            )
            else -> taskerPluginAction(code, element, byIndex, intsByIndex)
                ?: ActionWithLoss(unsupportedAction(code, TASKER_KEPT_PLACEHOLDER_REASONS[code]))
        }
        // Real Tasker exports carry a "Run only if" guard as a sibling <ConditionList>, not as
        // <Str> action args -- this applies to any action type, not just flow-control If/Else If.
        // For most actions "condition" isn't a real arg key, so the parsed value only needs to
        // land on the generic action.condition field. flow.if is the exception: its own args map
        // already carries the old flat-<Str> fallback under "condition" (see the "37"/"if" branch
        // above), and that same key is what both TaskRunner's stepControl and the action editor's
        // existingActionArgValue() read as the if's actual test expression -- so when this action
        // type already has an args["condition"] entry, overwrite it with the real parsed condition
        // instead of dropping it, or the editor shows a blank required field for a working import.
        val (importedCondition, conditionWarning) = element.parseImportedCondition()
        val guarded = if (importedCondition != null) {
            val args = if (actionWithLoss.action.args.containsKey("condition")) {
                actionWithLoss.action.args + ("condition" to importedCondition)
            } else {
                actionWithLoss.action.args
            }
            actionWithLoss.action.copy(condition = importedCondition, args = args)
        } else {
            actionWithLoss.action
        }
        // The step's own Tasker label, which a Goto can jump to by name.
        val labelled = element.childText("label").orEmptyIfBlank()?.let { guarded.copy(label = it) } ?: guarded
        // Tasker writes <on>false</on> for a step the user switched off, and nothing while it's on.
        val action = if (element.childText("on").equals("false", ignoreCase = true)) labelled.copy(enabled = false) else labelled
        val unsupported = if (action.type == TASKER_UNSUPPORTED_ACTION_ID) {
            TaskerUnsupportedAction(taskName = taskName, taskerCode = code, actionIndex = actionIndex)
        } else {
            null
        }
        val mapped = if (unsupported == null) {
            TaskerMappedAction(taskName = taskName, taskerCode = code, openTaskerActionId = action.type)
        } else {
            null
        }
        return ParsedTaskerAction(
            action = action,
            mapped = mapped,
            unsupported = unsupported,
            lossyWarning = listOfNotNull(actionWithLoss.lossyWarning, conditionWarning)
                .joinToString("; ")
                .ifBlank { null },
        )
    }

    private fun unsupportedAction(code: String, reason: String? = null): ActionSpec =
        ActionSpec(
            type = TASKER_UNSUPPORTED_ACTION_ID,
            label = "Unsupported Tasker action $code",
            args = mapOf(
                "taskerCode" to code,
                "summary" to (reason ?: "This Tasker action was preserved as an unsupported placeholder during import."),
            ),
        )

    /** A placeholder refuses to run, for the cases where running a near match would do the wrong thing. */
    private fun taskerPlaceholder(code: String, reason: String): ActionWithLoss =
        ActionWithLoss(unsupportedAction(code, reason), lossyWarning = reason)

    private fun joinWarnings(vararg warnings: String?): String? =
        warnings.filterNotNull().joinToString("; ").ifBlank { null }

    private fun taskerMusicPlayerWarning(actionName: String): String =
        "Tasker $actionName steers Tasker's own music player; here it presses the media key for whatever is playing"

    /**
     * A number field holding a variable. A plain `%x` needs nothing because actions expand it when
     * they run, and `%x + 1` becomes a template. A null result is anything harder than that.
     * [factor] converts the field's unit, so `%x` seconds can arrive as milliseconds, and [divisor]
     * scales it down the same way. [unsetAsZero] reads an unset or empty variable as 0, the way
     * Tasker's Do Maths does.
     */
    private fun taskerNumberTemplate(
        text: String,
        factor: Long = 1L,
        divisor: Long = 1L,
        unsetAsZero: Boolean = false,
    ): String? {
        val trimmed = text.trim()
        val filters = mutableListOf<String>()
        if (unsetAsZero) filters += "default:0"
        val name = if (TASKER_PLAIN_VARIABLE.matches(trimmed)) {
            trimmed.removePrefix("%")
        } else {
            val sum = TASKER_SIMPLE_SUM.matchEntire(trimmed) ?: return null
            filters += "${TASKER_SUM_FILTERS.getValue(sum.groupValues[2])}:${sum.groupValues[3]}"
            sum.groupValues[1]
        }
        if (factor != 1L) filters += "mul:$factor"
        if (divisor != 1L) filters += "div:$divisor"
        return if (filters.isEmpty()) "%$name" else "{{ $name | ${filters.joinToString(" | ")} }}"
    }

    /**
     * Anything too hard for a template stays as written and is named. It isn't a number when the
     * action runs, so the action fails where it can be seen instead of muting someone's phone.
     */
    private fun taskerNumberField(actionName: String, text: String): ImportedField =
        taskerNumberTemplate(text)?.let { ImportedField(it) }
            ?: ImportedField(text, "Tasker $actionName holds a sum this app doesn't work out: $text")

    private fun literalMillis(ints: Map<Int, Int>, units: List<Long>): Long =
        units.withIndex().sumOf { (index, unit) -> (ints[index] ?: 0).toLong() * unit }

    /**
     * A duration whose fields each have a unit. One variable with every other field at zero keeps
     * working at run time. Anything else falls back on [plainMillis] and names what was left out,
     * because a template can't add several fields together.
     */
    private fun taskerDuration(
        actionName: String,
        plainMillis: String,
        ints: Map<Int, Int>,
        variables: Map<Int, String>,
        units: List<Long>,
    ): ImportedField {
        val used = variables.filterKeys { it < units.size }
        if (used.isEmpty()) return ImportedField(plainMillis)
        val only = used.entries.singleOrNull()
        if (only != null && literalMillis(ints, units) == 0L) {
            return taskerNumberTemplate(only.value, units[only.key])?.let { ImportedField(it) }
                ?: ImportedField(only.value, "Tasker $actionName holds a sum this app doesn't work out: ${only.value}")
        }
        return ImportedField(
            plainMillis,
            "Tasker $actionName adds variables to its other fields, and only the plain numbers were imported: " +
                used.values.joinToString(", "),
        )
    }

    private fun taskerVariableSet(strings: List<String>, ints: Map<Int, Int>): ActionWithLoss {
        val name = strings.getOrElse(0) { "%IMPORTED" }
        val raw = strings.getOrElse(1) { "" }
        val recurse = (ints[2] ?: 0) != 0
        val doMaths = (ints[3] ?: 0) != 0
        val append = (ints[4] ?: 0) != 0
        val maths = when {
            !doMaths -> ImportedField(raw)
            raw.trim().toDoubleOrNull() != null -> ImportedField(raw.trim())
            else -> taskerNumberTemplate(raw, unsetAsZero = true)?.let { ImportedField(it) }
                ?: ImportedField(raw, "Tasker Variable Set's Do Maths isn't worked out here, so the text is set as written: $raw")
        }
        // "%apps" followed by more text would read as one longer variable name, so the old value
        // comes from a template. An unset variable adds nothing, the same as Tasker's Append.
        val appendTo = name.trim().takeIf { append && TASKER_PLAIN_VARIABLE.matches(it) }?.removePrefix("%")
        return ActionWithLoss(
            ActionSpec(
                type = "var.set",
                label = "Tasker variable set",
                args = mapOf(
                    "name" to name,
                    "value" to (appendTo?.let { "{{ $it }}${maths.value}" } ?: maths.value),
                ),
            ),
            lossyWarning = joinWarnings(
                maths.warning,
                "Tasker Variable Set's Append was not imported for $name".takeIf { append && appendTo == null },
                "Tasker Variable Set's Recurse Variables was not imported for $name".takeIf { recurse },
            ),
        )
    }

    /** Tasker's on/off actions share one Set field: 0 off, 1 on, 2 toggle. Older exports from this app wrote the word. */
    private fun taskerSetField(
        code: String,
        actionName: String,
        type: String,
        strings: List<String>,
        ints: Map<Int, Int>,
        variables: Map<Int, String>,
        warning: String? = null,
    ): ActionWithLoss {
        val text = strings.firstOrNull()?.trim()?.lowercase()
        val state = when {
            text == "on" || text == "off" || text == "toggle" -> text
            0 in variables -> null
            else -> TASKER_SET_STATES[ints[0] ?: 0]
        } ?: return taskerPlaceholder(
            code,
            "Tasker $actionName's Set field holds ${variables[0] ?: ints[0]}, which isn't off, on or toggle",
        )
        return ActionWithLoss(
            ActionSpec(type = type, label = "Tasker $actionName", args = mapOf("state" to state)),
            lossyWarning = warning,
        )
    }

    /**
     * Profile Status names its profile in arg0, which can be a variable, and keeps Set in arg1 like
     * Tasker's other switches. A Set held in a variable would hold Tasker's number, not a word.
     */
    private fun taskerProfileStatus(
        code: String,
        byIndex: Map<Int, String>,
        ints: Map<Int, Int>,
        variables: Map<Int, String>,
    ): ActionWithLoss {
        val name = byIndex[0].orEmpty().trim()
        val state = TASKER_SET_STATES[ints[1] ?: 0].takeIf { 1 !in variables }
        val refusal = when {
            name.isEmpty() -> "Tasker Profile Status names no profile"
            state == null -> "Tasker Profile Status's Set field holds ${variables[1] ?: ints[1]}, which isn't off, on or toggle"
            else -> null
        }
        if (refusal != null || state == null) return taskerPlaceholder(code, refusal.orEmpty())
        return ActionWithLoss(
            ActionSpec(type = "profile.set", label = "Tasker profile status", args = mapOf("profile" to name, "state" to state)),
        )
    }

    /**
     * A plugin step keeps the plugin's settings in `<Bundle sr="arg0">`, each value next to a
     * `<key>-type` element naming its Java type, with the plugin's package in arg1, its settings
     * screen in arg2 and how long Tasker waits for it in arg3. No built-in action has a package in
     * arg1, which is what tells a plugin step apart, whatever its code. Here it fires through the
     * Locale host, which waits for the plugin the way Tasker does, so arg3 carries over as the
     * step's wait (0 sends the settings and goes on) and the plugin's variables come back.
     */
    private fun taskerPluginAction(
        code: String,
        element: Element,
        byIndex: Map<Int, String>,
        ints: Map<Int, Int>,
    ): ActionWithLoss? {
        val bundle = element.directChildren("Bundle").firstOrNull { it.argIndex() == 0 } ?: return null
        val packageName = byIndex[1].orEmpty()
        if (!PackageNamePolicy.isValid(packageName) || byIndex[2].isNullOrBlank()) return null
        val values = try {
            LocalePluginBundleCodec.sanitizeTypedValues(
                bundle.directChildren("Vals").firstOrNull()?.let(::taskerBundleValues).orEmpty(),
            )
        } catch (error: IllegalArgumentException) {
            return taskerPlaceholder(code, "Tasker plugin $packageName: ${error.message}")
        } catch (error: IllegalStateException) {
            return taskerPlaceholder(code, "Tasker plugin $packageName: ${error.message}")
        }
        val blurb = (values[LocalePluginContract.EXTRA_BLURB] as? LocaleBundleValue.Text)?.value.orEmpty()
        val waitSeconds = ints[3]?.takeIf { it >= 0 }
        val waitMs = waitSeconds?.let { (it * 1_000L).coerceAtMost(LocalePluginContract.MAX_SETTING_WAIT_MS) }
        return ActionWithLoss(
            ActionSpec(
                type = "plugin.locale.fire",
                label = "Tasker plugin",
                args = buildMap {
                    put("package", packageName)
                    put("bundleJson", LocalePluginBundleCodec.encodeBundle(values - LocalePluginContract.EXTRA_BLURB))
                    if (blurb.isNotBlank()) put("blurb", blurb.take(120))
                    waitMs?.let { put("timeoutMs", it.toString()) }
                },
            ),
            lossyWarning = waitSeconds?.takeIf { it * 1_000L > LocalePluginContract.MAX_SETTING_WAIT_MS }?.let {
                "Tasker waits up to $it seconds for plugin $packageName to finish; a plugin step here waits at most 30 minutes"
            },
        )
    }

    /**
     * A plugin bundle's values as the Java values Tasker saved: `<null>` is a null string, and a
     * string array is itself XML inside the value, one `<_array_KEYn>` element per item. A type
     * a plugin bundle here can't hold is refused, naming the key, rather than sent as text.
     */
    private fun taskerBundleValues(vals: Element): Map<String, Any?> {
        val children = vals.directChildElements()
        val values = linkedMapOf<String, Any?>()
        var index = 0
        while (index < children.size) {
            val key = children[index].tagName
            // Tasker writes each value right before its `<key-type>`, so a key that itself ends in -type still pairs up.
            val type = children.getOrNull(index + 1)?.takeIf { it.tagName == "$key-type" }?.textContent?.trim()
                ?: throw IllegalArgumentException("$key has no type")
            val text = children[index].textContent.orEmpty()
            fun invalid(): Nothing = throw IllegalArgumentException("$key isn't a valid $type")
            values[key] = when (type) {
                "java.lang.String" -> text.takeUnless { it == "<null>" }
                "java.lang.Boolean" -> text.trim().toBooleanStrictOrNull() ?: invalid()
                "java.lang.Integer" -> text.trim().toIntOrNull() ?: invalid()
                "java.lang.Long" -> text.trim().toLongOrNull() ?: invalid()
                "java.lang.Float" -> text.trim().toFloatOrNull() ?: invalid()
                "java.lang.Double" -> text.trim().toDoubleOrNull() ?: invalid()
                "[Ljava.lang.String;" -> taskerStringArray(key, text) ?: invalid()
                else -> throw IllegalArgumentException("$key is of type $type, which a plugin bundle here can't hold")
            }
            index += 2
        }
        return values
    }

    /** Items of a string array value, in their numbered order, or null when the value isn't one. */
    private fun taskerStringArray(key: String, text: String): Array<String>? {
        if (text.isBlank()) return emptyArray()
        // The outer document had its doctype checked; this nested one gets no doctype or entity at all,
        // and its own budget, since a plugin bundle holds at most 16 KB and items never nest.
        if ("<!" in text) return null
        val root = try {
            ImportResourceGuard.requireXmlPreflight(text, STRING_ARRAY_BUDGET)
            parseDocument(text).documentElement
        } catch (_: IllegalArgumentException) {
            return null
        } catch (_: SAXException) {
            return null
        }
        if (root.tagName != "StringArray") return null
        return root.directChildElements()
            // Items are `_array_<key><n>`, so a key ending in digits, like `since1700000000`, still sorts by n.
            .sortedBy { item ->
                item.tagName.removePrefix("_array_$key").toIntOrNull()
                    ?: item.tagName.takeLastWhile(Char::isDigit).toIntOrNull()
                    ?: Int.MAX_VALUE
            }
            .map { it.textContent.orEmpty() }
            .toTypedArray()
    }

    /** Media Control sends a media key, which is exactly what this app's own media actions do. */
    private fun taskerMediaControl(
        code: String,
        element: Element,
        ints: Map<Int, Int>,
        variables: Map<Int, String>,
    ): ActionWithLoss {
        val command = ints[0] ?: 0
        val type = TASKER_MEDIA_COMMANDS[command].takeIf { 0 !in variables }
            ?: return taskerPlaceholder(
                code,
                variables[0]?.let { "Tasker Media Control takes its command from $it, which isn't known until the task runs" }
                    ?: "Tasker Media Control's $command command has no match here",
            )
        return ActionWithLoss(
            ActionSpec(type = type, label = "Tasker media control"),
            lossyWarning = element.actionAppPackage(2)
                ?.let { "Tasker Media Control aimed at $it was not imported; the key goes to the app that's playing or played last" },
        )
    }

    /**
     * Custom Setting's read form leaves Value blank and names a variable, so importing it as a write
     * would wipe the setting. Anything but a complete write stays a placeholder that refuses to run.
     */
    private fun taskerCustomSetting(code: String, byIndex: Map<Int, String>, ints: Map<Int, Int>): ActionWithLoss {
        val table = ints[0]?.let(TASKER_SETTING_TABLES::get)
        val name = byIndex[1].orEmpty().trim()
        val value = byIndex[2].orEmptyIfBlank()
        val readsInto = byIndex[4].orEmptyIfBlank()
        val refusal = when {
            readsInto != null -> "Tasker Custom Setting reads $name into $readsInto, and this app only writes settings"
            table == null -> "Tasker Custom Setting $name uses a settings type this app doesn't know"
            name.isEmpty() -> "Tasker Custom Setting has no setting name"
            value == null -> "Tasker Custom Setting $name has no value to write"
            else -> null
        }
        if (refusal != null || table == null || value == null) return taskerPlaceholder(code, refusal.orEmpty())
        return ActionWithLoss(
            ActionSpec(
                type = "settings.write",
                label = "Tasker custom setting",
                args = mapOf("table" to table, "key" to name, "value" to value),
            ),
            lossyWarning = "Tasker Custom Setting's Use Root option was not imported".takeIf { (ints[3] ?: 0) != 0 },
        )
    }

    /**
     * Perform Task moved its fields once. Older exports have an Int at arg2 and par1, par2 and the
     * return variable one field later. 9 of the 249 real actions examined used the older layout.
     */
    private fun taskerPerformTask(
        element: Element,
        strings: List<String>,
        byIndex: Map<Int, String>,
        ints: Map<Int, Int>,
    ): ActionWithLoss {
        val olderLayout = element.directChildren("Int").any { it.argIndex() == 2 }
        val parameterStart = if (olderLayout) 3 else 2
        val returnVariable = byIndex[parameterStart + 2].orEmptyIfBlank()?.trim()
        val keptReturnVariable = returnVariable?.takeIf(TASKER_PLAIN_VARIABLE::matches)
        val stops = !olderLayout && (ints[5] ?: 0) != 0
        // A called task already reads every local variable of its caller here, the same as
        // Tasker's Local Variable Passthrough with no limit, and a variable it sets is its own.
        val limitsPassthrough = !olderLayout && (ints[6] ?: 0) != 0 && byIndex[7].orEmptyIfBlank() != null
        val resetsReturnVariable = !olderLayout && (ints[8] ?: 0) != 0
        val allowsOverwrite = !olderLayout && (ints[9] ?: 0) != 0
        return ActionWithLoss(
            ActionSpec(
                type = "task.run",
                label = "Tasker perform task",
                args = buildMap {
                    put("task", strings.firstOrNull().orEmpty().ifBlank { ints[0]?.toString().orEmpty() })
                    byIndex[parameterStart].orEmptyIfBlank()?.let { put("par1", it) }
                    byIndex[parameterStart + 1].orEmptyIfBlank()?.let { put("par2", it) }
                    keptReturnVariable?.let { put(SUB_TASK_RETURN_KEY, it) }
                    if (keptReturnVariable != null && resetsReturnVariable) put(SUB_TASK_RESET_RETURN_KEY, "true")
                },
            ),
            lossyWarning = joinWarnings(
                returnVariable?.takeIf { keptReturnVariable == null }
                    ?.let { "Tasker Perform Task's Return Value Variable $it isn't one plain variable, so it was not imported" },
                "Tasker Perform Task's Stop option was not imported".takeIf { stops },
                "Tasker Perform Task's Limit Passthrough To was not imported, so the called task can read all of this task's local variables"
                    .takeIf { limitsPassthrough },
                "Tasker Perform Task's Allow Overwrite Variables option was not imported, so a Set Variable in the called task doesn't change this task's variable of that name"
                    .takeIf { allowsOverwrite },
            ),
        )
    }

    /**
     * Variable Add and Subtract count from 0 when the variable isn't set yet, and round a decimal
     * value to a whole number before they add or subtract, the way Tasker's do.
     */
    private fun taskerVariableAddOrSubtract(
        code: String,
        subtract: Boolean,
        byIndex: Map<Int, String>,
        ints: Map<Int, Int>,
        variables: Map<Int, String>,
    ): ActionWithLoss {
        val action = if (subtract) "Subtract" else "Add"
        val verb = if (subtract) "subtracts" else "adds"
        val name = byIndex[0].orEmpty()
        val refusal = when {
            name.isEmpty() -> "Tasker Variable $action names no variable"
            !TASKER_PLAIN_VARIABLE.matches(name) -> "Tasker Variable $action's name $name isn't one plain variable"
            1 in variables -> "Tasker Variable $action $verb ${variables[1]}, and this app only $verb a fixed number"
            2 in variables || (ints[2] ?: 0) != 0 -> "Tasker Variable $action's Wrap Around has no match here"
            else -> null
        }
        if (refusal != null) return taskerPlaceholder(code, refusal)
        val filter = if (subtract) "sub" else "add"
        return ActionWithLoss(
            ActionSpec(
                type = "var.set",
                label = "Tasker variable ${action.lowercase()}",
                args = mapOf("name" to name, "value" to "{{ ${name.removePrefix("%")} | default:0 | round | $filter:${ints[1] ?: 1} }}"),
            ),
        )
    }

    /**
     * Variable Clear (decompiled 6.7.8, `dq.I`) reads its name as written, never filled in. Pattern
     * Matching clears every name that matches, globals too unless Local Variables Only. No name
     * clears every local, and every global as well with Clear All Variables. Each switch only
     * counts where Tasker reads it, so only those come over.
     */
    private fun taskerVariableClear(
        code: String,
        byIndex: Map<Int, String>,
        ints: Map<Int, Int>,
        variables: Map<Int, String>,
    ): ActionWithLoss {
        val name = byIndex[0].orEmpty().trim()
        val pattern = name.isNotEmpty() && (ints[1] ?: 0) != 0
        val localOnly = pattern && (ints[2] ?: 0) != 0
        val clearAll = name.isEmpty() && (ints[3] ?: 0) != 0
        val refusal = when {
            (1..3).any { it in variables } -> "Tasker Variable Clear takes a switch from a variable, which has no match here"
            name.startsWith("%%") -> "Tasker Variable Clear takes the name to clear from ${name.drop(1)}, which has no match here"
            name.isNotEmpty() && !pattern && !TASKER_PLAIN_VARIABLE.matches(name) ->
                "Tasker Variable Clear clears $name, and this app clears one whole variable or a pattern"
            else -> null
        }
        if (refusal != null) return taskerPlaceholder(code, refusal)
        return ActionWithLoss(
            ActionSpec(
                type = "var.clear",
                label = "Tasker variable clear",
                args = buildMap {
                    put("name", name)
                    if (pattern) put("pattern", "true")
                    if (localOnly) put("local_only", "true")
                    if (clearAll) put("clear_all", "true")
                },
            ),
        )
    }

    /**
     * Test Variable's one type, Length (decompiled 6.7.8, `ro.i`), stores how many characters its
     * Data has once variables are filled in. Data is read untrimmed, because spaces count.
     */
    private fun taskerTestVariable(
        code: String,
        element: Element,
        byIndex: Map<Int, String>,
        ints: Map<Int, Int>,
        variables: Map<Int, String>,
    ): ActionWithLoss {
        val storeIn = byIndex[2].orEmpty()
        val refusal = when {
            0 in variables -> "Tasker Test Variable takes its type from ${variables[0]}, and only Length comes over"
            ints[0] == null -> "Tasker Test Variable has no type"
            ints[0] != 0 -> "Tasker Test Variable's type ${ints[0]} isn't Length, its only type"
            !TASKER_PLAIN_VARIABLE.matches(storeIn) -> "Tasker Test Variable stores into ${storeIn.ifEmpty { "nothing" }}, which isn't one plain variable"
            else -> null
        }
        if (refusal != null) return taskerPlaceholder(code, refusal)
        return ActionWithLoss(
            ActionSpec(
                type = "text.length",
                label = "Tasker test variable",
                args = mapOf("source" to element.rawActionString(1), "var" to storeIn),
            ),
        )
    }

    /**
     * Variable Convert's function is a number in Tasker's own order, which is the order of
     * [VariableConvert.FUNCTIONS]. Mode 1 is URL-safe Base64, and only Base64 Decode reads it.
     */
    private fun taskerVariableConvert(
        code: String,
        byIndex: Map<Int, String>,
        ints: Map<Int, Int>,
        variables: Map<Int, String>,
    ): ActionWithLoss {
        val name = byIndex[0].orEmpty()
        val function = ints[1]?.let(VariableConvert.FUNCTIONS::getOrNull).takeIf { 1 !in variables }
        val storeIn = byIndex[2].orEmptyIfBlank()
        val refusal = when {
            !TASKER_PLAIN_VARIABLE.matches(name) -> "Tasker Variable Convert's name $name isn't one plain variable"
            function == null -> "Tasker Variable Convert's function ${variables[1] ?: ints[1]} isn't one of its 32 conversions"
            storeIn != null && !TASKER_PLAIN_VARIABLE.matches(storeIn) -> "Tasker Variable Convert stores into $storeIn, which isn't one plain variable"
            else -> null
        }
        if (refusal != null || function == null) return taskerPlaceholder(code, refusal.orEmpty())
        return ActionWithLoss(
            ActionSpec(
                type = "var.convert",
                label = "Tasker variable convert",
                args = buildMap {
                    put("name", name)
                    put("function", function)
                    storeIn?.let { put("var", it) }
                    if (ints[3] == 1) put("url_safe", "true")
                },
            ),
        )
    }

    /**
     * Tasker's Beep plays a quarter of the frequency it's given (its sine steps by pi times the
     * frequency over 16000, at 8000 samples a second), so the number comes in divided by 4 and
     * sounds the same here. Missing numbers stay out, so the step names them when it runs.
     */
    private fun taskerBeep(byIndex: Map<Int, String>, ints: Map<Int, Int>, variables: Map<Int, String>): ActionWithLoss {
        val frequency = variables[0]?.let { text ->
            taskerNumberTemplate(text, divisor = TASKER_BEEP_PITCH_DIVISOR)?.let { ImportedField(it) }
                ?: ImportedField(text, "Tasker Beep holds a sum this app doesn't work out: $text")
        } ?: ints[0]?.let { ImportedField(plainDecimal(it / TASKER_BEEP_PITCH_DIVISOR.toDouble())) }
        val duration = variables[1]?.let { taskerNumberField("Beep", it) } ?: ints[1]?.let { ImportedField(it.toString()) }
        val amplitude = variables[2]?.let { taskerNumberField("Beep", it) } ?: ints[2]?.let { ImportedField(it.toString()) }
        val stream = ints[3]
        val doAt = byIndex[4].orEmptyIfBlank()
        return ActionWithLoss(
            ActionSpec(
                type = "sound.beep",
                label = "Tasker beep",
                args = buildMap {
                    frequency?.let { put("frequency", it.value) }
                    duration?.let { put("duration", it.value) }
                    amplitude?.let { put("amplitude", it.value) }
                    putAll(taskerOutputStreamArg(stream))
                },
            ),
            lossyWarning = joinWarnings(
                frequency?.warning,
                duration?.warning,
                amplitude?.warning,
                taskerStreamWarning("Beep", stream, variables[3], plays = "beeps"),
                "Tasker Beep's Do At Time $doAt was not imported, so it beeps right away".takeIf { doAt != null },
            ),
        )
    }

    private fun plainDecimal(value: Double): String =
        if (value == Math.floor(value)) value.toLong().toString() else value.toString()

    /**
     * Tasker counts From from 1. This app's substring always cuts a section short at the end of
     * the text, which is Tasker's Adapt To Fit, so with that off Tasker fails where this doesn't.
     */
    private fun taskerVariableSection(
        code: String,
        byIndex: Map<Int, String>,
        ints: Map<Int, Int>,
        variables: Map<Int, String>,
    ): ActionWithLoss {
        val name = byIndex[0].orEmpty()
        val from = ints[1]
        val length = ints[2]
        val storeIn = byIndex[4].orEmptyIfBlank()
        val refusal = when {
            !TASKER_PLAIN_VARIABLE.matches(name) -> "Tasker Variable Section's name $name isn't one plain variable"
            1 in variables || 2 in variables -> "Tasker Variable Section takes From or Length from a variable, which has no match here"
            from == null || from < 1 -> "Tasker Variable Section starts at ${from ?: "nothing"}, before the first character"
            length == null || length < 0 -> "Tasker Variable Section has no length"
            storeIn != null && !TASKER_PLAIN_VARIABLE.matches(storeIn) -> "Tasker Variable Section stores into $storeIn, which isn't one plain variable"
            else -> null
        }
        if (refusal != null || from == null || length == null) return taskerPlaceholder(code, refusal.orEmpty())
        val start = from - 1
        return ActionWithLoss(
            ActionSpec(
                type = "text.substring",
                label = "Tasker variable section",
                args = mapOf(
                    "source" to "{{ ${name.removePrefix("%")} }}",
                    "start" to start.toString(),
                    "end" to (start.toLong() + length).coerceAtMost(Int.MAX_VALUE.toLong()).toString(),
                    "var" to (storeIn ?: name),
                ),
            ),
            lossyWarning = "Tasker Variable Section's Adapt To Fit was off for $name, so a section past the end is cut short here instead of failing"
                .takeIf { (ints[3] ?: 0) == 0 },
        )
    }

    /**
     * Variable Split and Join and Array Set, Push, Pop, Clear and Process work on Tasker's numbered
     * elements (%arr1, %arr2, ...), which is what the actions they map to work on. Array Set is a
     * Variable Split of its Values, splitting on plain text or, with no Splitter, on white space.
     * Splitter, Joiner, Values and Value are read untrimmed, because a space there is the whole point.
     */
    private fun taskerArrayAction(
        code: String,
        element: Element,
        byIndex: Map<Int, String>,
        ints: Map<Int, Int>,
        variables: Map<Int, String>,
    ): ActionWithLoss {
        val tasker = TASKER_ARRAY_ACTIONS.getValue(code)
        val name = byIndex[0].orEmpty()
        val toVar = byIndex[2].orEmptyIfBlank().takeIf { code == "356" }
        val position = variables[1] ?: (ints[1] ?: 1).toString()
        val regexSplitter = element.rawActionString(1).takeIf { code == "590" && (ints[3] ?: 0) != 0 && it.isNotEmpty() }
        val processType = ints[1]?.let(ArrayProcessAction.TYPES::getOrNull).takeIf { code == "369" && variables[1] == null }
        val refusal = when {
            !TASKER_PLAIN_VARIABLE.matches(name) -> "Tasker ${tasker.first}'s ${if (code.startsWith("59")) "name" else "array"} $name isn't one plain variable"
            code == "369" && processType == null ->
                "Tasker Array Process's type ${variables[1] ?: ints[1]} isn't one of its 14 operations"
            (code == "355" || code == "356") && !(TASKER_PLAIN_VARIABLE.matches(position) || position.toIntOrNull()?.let { it >= 1 } == true) ->
                "Tasker ${tasker.first}'s Position $position isn't a number or one plain variable"
            toVar != null && !TASKER_PLAIN_VARIABLE.matches(toVar) -> "Tasker Array Pop stores into $toVar, which isn't one plain variable"
            // The editor drops a blank field on save, and Variable Split with no Values splits the
            // variable's own value, so empty Values would change meaning after the first edit.
            code == "354" && element.rawActionString(1).isEmpty() -> "Tasker Array Set has no Values"
            // A splitter holding a variable is only known when the step runs, so it's checked then.
            regexSplitter != null && !TASKER_VARIABLE_REFERENCE.containsMatchIn(regexSplitter) && TextOps.match("", regexSplitter) == null ->
                "Tasker Variable Split's splitter $regexSplitter uses regular expression features this app doesn't have"
            else -> null
        }
        if (refusal != null) return taskerPlaceholder(code, refusal)
        fun flag(index: Int) = ((ints[index] ?: 0) != 0).toString()
        val args = when (code) {
            "590" -> mapOf("name" to name, "splitter" to element.rawActionString(1), "delete_base" to flag(2), "regex" to flag(3))
            "592" -> mapOf("name" to name, "joiner" to element.rawActionString(1), "delete_parts" to flag(2))
            "354" -> mapOf("name" to name, "values" to element.rawActionString(1), "splitter" to element.rawActionString(2))
            "355" -> mapOf("array" to name, "position" to position, "value" to element.rawActionString(2), "fill_spaces" to flag(3))
            "356" -> buildMap {
                put("array", name)
                put("position", position)
                toVar?.let { put("to_var", it) }
            }
            "369" -> mapOf("array" to name, "type" to processType.orEmpty())
            else -> mapOf("array" to name)
        }
        return ActionWithLoss(ActionSpec(type = tasker.second, label = "Tasker ${tasker.first.lowercase()}", args = args))
    }

    /**
     * Search Replace runs a regular expression over a variable and writes the result back
     * (decompiled 6.7.8, `ExecuteService.V2`). Store Matches In Array empties that array and fills
     * it with each match, One Match Only keeps and replaces only the first, and with Replace Matches
     * off the variable stays as it is. Search and Replace With are read untrimmed, because a space
     * there is part of the pattern. Replace With is filled in after the matches are stored, so one
     * that reads the array the step fills stays a placeholder.
     */
    private fun taskerSearchReplace(
        code: String,
        element: Element,
        byIndex: Map<Int, String>,
        ints: Map<Int, Int>,
        variables: Map<Int, String>,
    ): ActionWithLoss {
        val name = byIndex[0].orEmpty()
        val search = element.rawActionString(1)
        val matches = byIndex[5].orEmpty().trim()
        val replace = (ints[6] ?: 0) != 0
        val replaceWith = if (replace) element.rawActionString(7) else ""
        val flags = buildString {
            if ((ints[2] ?: 0) != 0) append('i')
            if ((ints[3] ?: 0) != 0) append('m')
        }
        val pattern = if (flags.isEmpty()) search else "(?$flags)$search"
        // A pattern holding a variable is only known when the step runs, so it's checked then.
        val checkable = !TASKER_VARIABLE_REFERENCE.containsMatchIn(pattern)
        val refusal = when {
            !TASKER_PLAIN_VARIABLE.matches(name) -> "Tasker Search Replace's variable $name isn't one plain variable"
            listOf(2, 3, 4, 6).any { it in variables } -> "Tasker Search Replace takes a switch from a variable, which has no match here"
            matches.isNotEmpty() && !TASKER_PLAIN_VARIABLE.matches(matches) ->
                "Tasker Search Replace stores its matches in $matches, which isn't one plain variable"
            // Tasker fills Replace With in after it stores the matches, and here every field is
            // filled in before the step runs, so it would read the array from before the step. A
            // name runs on through letters, digits and underscores, so %found1st is another variable.
            // Behind exactly one backslash it's written as text, in Tasker and here.
            matches.isNotEmpty() &&
                Regex("""(?<!(?<!\\)\\)""" + Regex.escape(matches) + """(?:[0-9]+(?![A-Za-z0-9_])|\()""").containsMatchIn(replaceWith) ->
                "Tasker Search Replace's Replace With reads $matches, which Tasker fills in with this step's matches and this app can't"
            search.isEmpty() -> "Tasker Search Replace has nothing to search for"
            checkable && TextOps.match("", pattern) == null ->
                "Tasker Search Replace's pattern $search uses regular expression features this app doesn't have"
            else -> null
        }
        if (refusal != null) return taskerPlaceholder(code, refusal)
        return ActionWithLoss(
            ActionSpec(
                type = "text.replace",
                label = "Tasker search replace",
                args = buildMap {
                    put("source", "{{ ${name.removePrefix("%")} }}")
                    put("pattern", pattern)
                    if (replace) put("replacement", replaceWith) else put("search_only", "true")
                    put("var", name)
                    if ((ints[4] ?: 0) != 0) put("first_only", "true")
                    if (matches.isNotEmpty()) put("matches", matches)
                },
            ),
            lossyWarning = "Tasker Search Replace's $ also matches just before a final line break, and here it matches only at the very end"
                .takeIf { hasEndAnchor(search, multilineFlag = 'm' in flags) },
        )
    }

    /**
     * True when [pattern] has a `$` that isn't escaped, quoted in `\Q...\E` or inside a character
     * class (see [classEnd]). A `$` under an inline `m` flag already matches at every line end, so
     * the pattern behaves the same. The flag holds from a `(?m)` to the end of its group, or inside
     * a `(?m:...)` group, and Multi-Line ([multilineFlag]) sets it for the whole pattern until a
     * `(?-m)` turns it off.
     */
    private fun hasEndAnchor(pattern: String, multilineFlag: Boolean): Boolean {
        val outerMultiline = ArrayDeque<Boolean>()
        var multiline = multilineFlag
        var index = 0
        while (index < pattern.length) {
            val char = pattern[index]
            when {
                char == '\\' && pattern.startsWith("Q", index + 1) -> {
                    val end = pattern.indexOf("\\E", index + 2)
                    if (end < 0) return false
                    index = end + 1
                }
                char == '\\' -> index++
                char == '[' -> index = classEnd(pattern, index)
                char == '(' -> {
                    val flags = INLINE_FLAGS.find(pattern, index)?.takeIf { it.range.first == index }
                    // A bare (?m) changes the group it sits in. Any other ( opens a group of its own.
                    if (flags == null || flags.value.endsWith(":")) outerMultiline.addLast(multiline)
                    if (flags != null) {
                        val (on, off) = flags.destructured
                        if ('m' in on) multiline = true
                        if ('m' in off) multiline = false
                        index = flags.range.last
                    }
                }
                char == ')' -> multiline = outerMultiline.removeLastOrNull() ?: multiline
                char == '$' && !multiline -> return true
            }
            index++
        }
        return false
    }

    /**
     * The index of the `]` that closes the class opening at [open], or the pattern's length, with
     * the class read item by item the way RE2 (which runs the step) reads it, an escape such as
     * `\pL`, `\x41` or `\101` counting as one item. A `]` first, after
     * `[` or `[^`, is a literal. A `[:name:]` that starts an item is a POSIX class, so its `]`
     * doesn't end the outer class. The character after a range's `-` ends that range even when
     * it's a `[`, so in `[!-[:alpha:]$]` the first `]` closes the class and the `$` is an anchor.
     */
    private fun classEnd(pattern: String, open: Int): Int {
        var index = open + 1
        if (pattern.startsWith("^", index)) index++
        var first = true
        while (index < pattern.length && (pattern[index] != ']' || first)) {
            first = false
            if (pattern.startsWith("[:", index)) {
                val end = pattern.indexOf(":]", index + 2)
                if (end >= 0) {
                    index = end + 2
                    continue
                }
            }
            if (pattern.startsWith("\\Q", index)) {
                val end = pattern.indexOf("\\E", index + 2)
                if (end < 0) return pattern.length
                index = end + 2
                continue
            }
            // \d, \s, \w and \p{...} are classes of their own, so a - after one starts no range.
            val rangeable = pattern[index] != '\\' || pattern.getOrNull(index + 1) !in CLASS_ESCAPES
            index = classItemEnd(pattern, index)
            if (rangeable && pattern.startsWith("-", index) && index + 1 < pattern.length && pattern[index + 1] != ']') {
                index = classItemEnd(pattern, index + 1)
            }
        }
        return index
    }

    /** One class item: a character, or an escape, which can run past two characters (`\pL`, `\x41`, `\101`). */
    private fun classItemEnd(pattern: String, index: Int): Int {
        if (pattern[index] != '\\') return index + 1
        val escape = pattern.getOrNull(index + 1)
        val end = when {
            (escape in UNICODE_CLASS_ESCAPES || escape == 'x') && pattern.startsWith("{", index + 2) ->
                pattern.indexOf('}', index + 3).let { if (it < 0) pattern.length else it + 1 }
            escape in UNICODE_CLASS_ESCAPES -> index + 3
            escape == 'x' -> index + 4
            // Up to three octal digits make one character.
            escape != null && escape in '0'..'7' ->
                (index + 2 until minOf(index + 4, pattern.length)).firstOrNull { pattern[it] !in '0'..'7' } ?: minOf(index + 4, pattern.length)
            else -> index + 2
        }
        return minOf(end, pattern.length)
    }

    private val CLASS_ESCAPES = setOf('d', 'D', 's', 'S', 'w', 'W', 'p', 'P')
    private val UNICODE_CLASS_ESCAPES = setOf('p', 'P')

    private val INLINE_FLAGS = Regex("""\(\?([A-Za-z]*)(?:-([A-Za-z]*))?[):]""")

    /** Tasker's Interrupt Mode numbers its modes from the most silent. Custom and the read-only query have no match here. */
    private fun taskerInterruptMode(
        code: String,
        byIndex: Map<Int, String>,
        ints: Map<Int, Int>,
        variables: Map<Int, String>,
    ): ActionWithLoss {
        // A missing mode would read as 0, the most silent one, so it's refused rather than guessed.
        val index = ints[0]
        val mode = index.takeIf { 0 !in variables }?.let(TASKER_INTERRUPT_MODES::get)
            ?: return taskerPlaceholder(
                code,
                variables[0]?.let { "Tasker Interrupt Mode takes its mode from $it, which has no match here" }
                    ?: index?.let { "Tasker Interrupt Mode's ${TASKER_INTERRUPT_MODE_NAMES[it] ?: it} mode has no match here" }
                    ?: "Tasker Interrupt Mode has no mode",
            )
        return ActionWithLoss(
            ActionSpec(type = "dnd.set", label = "Tasker interrupt mode", args = mapOf("mode" to mode)),
            lossyWarning = joinWarnings(
                "Tasker Interrupt Mode's callers, senders, categories and effects were not imported; the phone's own priority settings apply"
                    .takeIf { mode == "priority" },
                byIndex[6].orEmptyIfBlank()?.let { "Tasker Interrupt Mode's Output Variables were not imported: $it" },
            ),
        )
    }

    /**
     * Tasker's Sound Mode hands its mode straight to Android's ringer: 0 silent, 1 vibrate, 2
     * normal. With Ignore DND off, Tasker puts Do Not Disturb back the way it was afterwards, while
     * this app's ringer step leaves whatever Android changed along with it, which is what Ignore
     * DND on does.
     */
    private fun taskerSoundMode(code: String, ints: Map<Int, Int>, variables: Map<Int, String>): ActionWithLoss {
        // A missing mode would read as 0, silent, so it's refused rather than guessed.
        val index = ints[0]
        val mode = index.takeIf { 0 !in variables }?.let(TASKER_SOUND_MODES::get)
            ?: return taskerPlaceholder(
                code,
                variables[0]?.let { "Tasker Sound Mode takes its mode from $it, which has no match here" }
                    ?: index?.let { "Tasker Sound Mode's mode $it has no match here" }
                    ?: "Tasker Sound Mode has no mode",
            )
        return ActionWithLoss(
            ActionSpec(type = "ringer.set", label = "Tasker sound mode", args = mapOf("mode" to mode)),
            lossyWarning = ("Tasker Sound Mode puts Do Not Disturb back the way it was unless Ignore DND is on, " +
                "and here Android may turn Do Not Disturb on or off along with the sound mode")
                .takeIf { (ints[1] ?: 0) == 0 },
        )
    }

    /**
     * Auto Brightness and Display AutoRotate each switch one system setting, the one Tasker's action
     * table names for the code, so off and on land on the settings write step. Toggle flips whatever
     * the setting holds, which a step that writes one value can't do.
     */
    private fun taskerSystemSwitch(code: String, ints: Map<Int, Int>, variables: Map<Int, String>): ActionWithLoss {
        val (actionName, key) = TASKER_SYSTEM_SWITCHES.getValue(code)
        // A missing Set would read as 0, off, so it's refused rather than guessed.
        val set = ints[0].takeIf { 0 !in variables }
        val refusal = when {
            variables[0] != null -> "Tasker $actionName takes its Set value from ${variables[0]}, which has no match here"
            set == null -> "Tasker $actionName has no Set value"
            set == 2 -> "Tasker $actionName's Toggle flips the setting, and the settings write step only writes the value it's given"
            set != 0 && set != 1 -> "Tasker $actionName's Set value $set isn't off, on or toggle"
            else -> null
        }
        if (refusal != null || set == null) return taskerPlaceholder(code, refusal.orEmpty())
        return ActionWithLoss(
            ActionSpec(
                type = "settings.write",
                label = "Tasker ${actionName.lowercase()}",
                args = mapOf("table" to "system", "key" to key, "value" to set.toString()),
            ),
        )
    }

    /**
     * Tasker's Reboot picks Normal, Recovery, Bootloader or Shutdown in arg0. The reboot step only
     * restarts the phone normally, so the other three stay placeholders.
     */
    private fun taskerReboot(code: String, ints: Map<Int, Int>, variables: Map<Int, String>): ActionWithLoss {
        // A missing type would read as 0, a normal reboot, so it's refused rather than guessed.
        val type = ints[0]
        val mode = type?.let { TASKER_REBOOT_MODES.getOrNull(it) }
        val refusal = when {
            variables[0] != null -> "Tasker Reboot takes its type from ${variables[0]}, which has no match here"
            type == null -> "Tasker Reboot has no type"
            mode == null -> "Tasker Reboot's type $type isn't one Tasker lists"
            mode == "Shutdown" -> "Tasker Reboot's Shutdown turns the phone off, and the reboot step only restarts it"
            mode != "Normal" -> "Tasker Reboot into $mode has no match here, since the reboot step only restarts the phone normally"
            else -> null
        }
        if (refusal != null) return taskerPlaceholder(code, refusal)
        return ActionWithLoss(ActionSpec(type = "reboot", label = "Tasker reboot"))
    }

    /**
     * Tasker's Stay On picks one of eight choices in arg0, from Never to wireless, AC or USB power, in
     * the order of Android's plug mask (AC 1, USB 2, wireless 4). So the index is the value Android
     * keeps in stay_on_while_plugged_in, and it's written there as is.
     */
    private fun taskerStayOn(code: String, ints: Map<Int, Int>, variables: Map<Int, String>): ActionWithLoss {
        // A missing mode would read as 0, Never, so it's refused rather than guessed.
        val mode = ints[0]
        val refusal = when {
            variables[0] != null -> "Tasker Stay On takes its mode from ${variables[0]}, which has no match here"
            mode == null -> "Tasker Stay On has no mode"
            mode !in 0..7 -> "Tasker Stay On's mode $mode isn't one Tasker lists"
            else -> null
        }
        if (refusal != null || mode == null) return taskerPlaceholder(code, refusal.orEmpty())
        return ActionWithLoss(
            ActionSpec(
                type = "settings.write",
                label = "Tasker stay on",
                args = mapOf("table" to "global", "key" to "stay_on_while_plugged_in", "value" to mode.toString()),
            ),
        )
    }

    /** Tasker's Turn On turns the screen on, as the wake step does. Its Block Time has nowhere to go. */
    private fun taskerTurnOn(ints: Map<Int, Int>, variables: Map<Int, String>): ActionWithLoss {
        val blockTime = variables[0] ?: ints[0]?.let { "$it ms" }
        return ActionWithLoss(
            ActionSpec(type = "wake", label = "Tasker turn on"),
            lossyWarning = "Tasker Turn On's Block Time${blockTime?.let { " of $it" }.orEmpty()} wasn't imported. " +
                "The wake step moves on as soon as it sends the wake key",
        )
    }

    /**
     * Tasker's Delete File: arg0 is the file, arg1 Shred Level, arg2 Use Root and arg3 Use Global
     * Namespace, which only matters as root. Like Read File and Write File, the path is passed through.
     */
    private fun taskerDeleteFile(byIndex: Map<Int, String>, ints: Map<Int, Int>, variables: Map<Int, String>): ActionWithLoss {
        val shredLevel = variables[1] ?: ints[1]?.takeIf { it != 0 }?.toString()
        val notImported = listOfNotNull(
            shredLevel?.let { "Shred Level of $it" },
            "Use Root".takeIf { variables[2] != null || (ints[2] ?: 0) != 0 },
        )
        return ActionWithLoss(
            ActionSpec(type = "file.delete", label = "Tasker delete file", args = mapOf("path" to byIndex[0].orEmpty())),
            lossyWarning = notImported.takeIf { it.isNotEmpty() }
                ?.let { "Tasker Delete File fields not imported: ${it.joinToString(", ")}" },
        )
    }

    /** Tasker's Goto: arg0 is the kind of jump, arg1 a step number, arg2 a label. */
    private fun taskerGoto(code: String, byIndex: Map<Int, String>, ints: Map<Int, Int>, variables: Map<Int, String>): ActionWithLoss =
        when (val kind = ints[0] ?: 0) {
            0 -> {
                val step = variables[1]?.let { taskerNumberField("Goto", it) } ?: ImportedField(ints[1]?.toString().orEmpty())
                ActionWithLoss(
                    ActionSpec(type = "flow.goto", label = "Tasker goto", args = mapOf("step" to step.value)),
                    lossyWarning = step.warning,
                )
            }
            1 -> ActionWithLoss(
                ActionSpec(type = "flow.goto", label = "Tasker goto", args = mapOf("label" to byIndex[2].orEmpty())),
            )
            in TASKER_GOTO_BLOCK_JUMPS -> ActionWithLoss(
                ActionSpec(type = "flow.goto", label = "Tasker goto", args = mapOf("to" to TASKER_GOTO_BLOCK_JUMPS.getValue(kind))),
            )
            else -> taskerPlaceholder(code, "Tasker Goto of kind $kind has no match here")
        }

    /**
     * A called task already runs on its own here, so stopping it hands control back to the caller,
     * with the value going to the Perform Task step's Return Value Variable.
     */
    private fun taskerReturn(code: String, byIndex: Map<Int, String>, ints: Map<Int, Int>): ActionWithLoss {
        if ((ints[1] ?: 1) == 0) return taskerPlaceholder(code, "Tasker Return with Stop off has no match here")
        val passesLocals = (ints[2] ?: 0) != 0 || (ints[3] ?: 0) != 0 || byIndex[4].orEmptyIfBlank() != null
        return ActionWithLoss(
            ActionSpec(
                type = "flow.stop",
                label = "Tasker return",
                args = byIndex[0].orEmptyIfBlank()?.let { mapOf("value" to it) }.orEmpty(),
            ),
            lossyWarning = "Tasker Return's Local Variable Passthrough was not imported".takeIf { passesLocals },
        )
    }

    /**
     * Send Intent becomes this app's intent.launch. Fields that hold a variable are checked when the
     * step runs; the rest go through the same rules now, and anything those refuse stays a placeholder.
     */
    private fun taskerSendIntent(
        code: String,
        byIndex: Map<Int, String>,
        ints: Map<Int, Int>,
        variables: Map<Int, String>,
    ): ActionWithLoss {
        val packageName = byIndex[7].orEmptyIfBlank()
            ?: return taskerPlaceholder(code, "Tasker Send Intent names no app, and this app only sends an intent to a named one")
        val targetIndex = ints[9] ?: 0
        val target = TASKER_INTENT_TARGETS[targetIndex].takeIf { 9 !in variables }
            ?: return taskerPlaceholder(code, "Tasker Send Intent's Target ${variables[9] ?: targetIndex} has no match here")
        val categoryIndex = ints[1] ?: 0
        val category = when {
            1 in variables -> return taskerPlaceholder(code, "Tasker Send Intent takes its category from ${variables[1]}, which has no match here")
            categoryIndex == 0 -> null
            else -> TASKER_INTENT_CATEGORIES[categoryIndex]
                ?: return taskerPlaceholder(code, "Tasker Send Intent's category $categoryIndex has no match here")
        }
        val extras = mutableListOf<ImportedExtra>()
        val skipped = mutableListOf<String>()
        (4..6).mapNotNull { byIndex[it].orEmptyIfBlank() }.forEach { extra ->
            val imported = taskerIntentExtra(extra)
            if (imported != null) extras += imported else skipped += extra
        }
        val uri = byIndex[3].orEmptyIfBlank()
        val args = buildMap {
            put("package", packageName)
            put("mode", target)
            byIndex[8].orEmptyIfBlank()?.let { put("component", it) }
            byIndex[0].orEmptyIfBlank()?.let { put("action", it) }
            category?.let { put("category", it) }
            uri?.let { put("uri", it) }
            byIndex[2].orEmptyIfBlank()?.let { put("mime_type", it) }
            if (extras.isNotEmpty()) put("extras", extras.joinToString("\n") { it.line })
        }
        // A URI waits for someone to add a grant flag, which Tasker never asked for, so the policy is
        // checked as if they had. The step itself is imported without one.
        if (args.values.none { TASKER_VARIABLE_REFERENCE.containsMatchIn(it) }) {
            val checked = if (uri == null) args else args + ("flags" to "grant_read_uri")
            val result = IntentDispatchPolicy.parse(checked)
            if (result is IntentDispatchParseResult.Invalid) {
                return taskerPlaceholder(code, "Tasker Send Intent to $packageName can't be sent from this app: ${result.message}")
            }
        }
        return ActionWithLoss(
            ActionSpec(type = "intent.launch", label = "Tasker send intent", args = args),
            lossyWarning = joinWarnings(
                "Tasker Send Intent's Data $uri needs grant_read_uri or grant_write_uri added to the step's flags before it runs"
                    .takeIf { uri != null },
                extras.filter { it.asText }.takeIf { it.isNotEmpty() }
                    ?.let { list -> "Tasker Send Intent extras sent as text: ${list.joinToString(", ") { it.key }}" },
                skipped.takeIf { it.isNotEmpty() }?.let { "Tasker Send Intent extras not imported: ${it.joinToString(", ")}" },
            ),
        )
    }

    private data class ImportedExtra(val key: String, val line: String, val asText: Boolean)

    /**
     * Tasker writes an extra as `key:value` and guesses its type from the value. True or false and
     * a whole number that fits in an int carry over. Anything else, a cast like `(long)5` or a
     * variable among them, is sent as text and named, since Tasker decided those when it ran.
     */
    private fun taskerIntentExtra(extra: String): ImportedExtra? {
        val separator = extra.indexOf(':')
        if (separator <= 0) return null
        val key = extra.substring(0, separator).trim()
        if (!TASKER_INTENT_EXTRA_KEY.matches(key)) return null
        val value = extra.substring(separator + 1)
        val typed = when {
            value.equals("true", ignoreCase = true) || value.equals("false", ignoreCase = true) -> "bool:${value.lowercase()}"
            value.toIntOrNull() != null -> "int:$value"
            else -> null
        }
        val typedWhenRun = value.toDoubleOrNull() != null || value.trimStart().startsWith("(") ||
            TASKER_VARIABLE_REFERENCE.containsMatchIn(value)
        return ImportedExtra(key, "$key=${typed ?: "string:$value"}", asText = typed == null && typedWhenRun)
    }

    /** A Str field exactly as written, for the fields where a leading or trailing space counts. */
    private fun Element.rawActionString(index: Int): String =
        directChildren("Str").firstOrNull { it.argIndex() == index }
            ?.let { if (it.hasAttribute("val")) it.getAttribute("val") else it.textContent.orEmpty() }
            .orEmpty()

    private fun taskerHttpRequest(byIndex: Map<Int, String>, ints: Map<Int, Int>): ActionWithLoss {
        val methodIndex = ints[TASKER_HTTP_METHOD_ARG]
        val method = methodIndex?.let(TASKER_HTTP_METHODS::get)
        val url = byIndex[TASKER_HTTP_URL_ARG].orEmpty()
        val query = byIndex[TASKER_HTTP_QUERY_ARG].orEmptyIfBlank()
        val importedQuery = query?.let(::taskerColonQuery)
        val body = byIndex[TASKER_HTTP_BODY_ARG].orEmptyIfBlank()
        // A GET or HEAD with a body is refused when the request is built, so keeping it would break the action.
        val bodyFits = method != null && method !in TASKER_HTTP_BODYLESS_METHODS
        val followsRedirects = (ints[TASKER_HTTP_REDIRECTS_ARG] ?: 0) != 0
        val skipped = listOfNotNull(
            "query parameters".takeIf { query != null && importedQuery == null },
            "the body, which a GET or HEAD request can't carry".takeIf { body != null && !bodyFits },
            byIndex[TASKER_HTTP_FILE_TO_SEND_ARG].orEmptyIfBlank()?.let { "the file to send" },
            byIndex[TASKER_HTTP_OUTPUT_FILE_ARG].orEmptyIfBlank()?.let { "the output file" },
            "the request method (Tasker index $methodIndex)".takeIf { methodIndex != null && method == null },
            "Trust Any Certificate".takeIf { (ints[TASKER_HTTP_TRUST_ARG] ?: 0) != 0 },
            "Use Cookies".takeIf { (ints[TASKER_HTTP_COOKIES_ARG] ?: 0) != 0 },
        )
        return ActionWithLoss(
            ActionSpec(
                type = "http.request",
                label = "Tasker HTTP request",
                args = buildMap {
                    method?.let { put("method", it) }
                    put("url", url)
                    importedQuery?.let { put("query", it) }
                    byIndex[TASKER_HTTP_HEADERS_ARG].orEmptyIfBlank()?.let { put("headers", it) }
                    if (bodyFits) body?.let { put("body", it) }
                    ints[TASKER_HTTP_TIMEOUT_ARG]?.takeIf { it > 0 }?.let { put("timeout_sec", it.toString()) }
                    if (followsRedirects) put("redirects", "same_origin")
                    put("response_var", "%http_data")
                    put("status_var", "%http_response_code")
                    if (taskerUrlMayBePlainHttp(url)) put("allow_http", "true")
                },
            ),
            lossyWarning = joinWarnings(
                skipped.takeIf { it.isNotEmpty() }?.let { "Tasker HTTP request fields not imported: ${it.joinToString(", ")}" },
                TASKER_SAME_SITE_REDIRECTS_WARNING.takeIf { followsRedirects },
                TASKER_PLAIN_HTTP_WARNING.takeIf { url.startsWith("http://", ignoreCase = true) },
            ),
        )
    }

    /** Tasker's older HTTP Get and HTTP Post, which real exports still use. Their fields line up apart from arg2 and arg6. */
    private fun taskerHttpGetOrPost(post: Boolean, byIndex: Map<Int, String>, ints: Map<Int, Int>): ActionWithLoss {
        val url = taskerHttpUrl(byIndex[0].orEmpty(), byIndex[1].orEmpty())
        val data = byIndex[2].orEmptyIfBlank()
        val type = byIndex[6].orEmptyIfBlank()
        val attributes = data.takeIf { !post }?.lines()?.filter(String::isNotBlank)
        val query = attributes?.takeIf { lines -> lines.all { '=' in it } }?.joinToString("\n") { it.trim() }
        val headers = listOfNotNull(
            byIndex[3].orEmptyIfBlank()?.let { "Cookie: $it" },
            byIndex[4].orEmptyIfBlank()?.let { "User-Agent: $it" },
        )
        val skipped = listOfNotNull(
            "Attributes".takeIf { attributes != null && query == null },
            "Mime Type $type".takeIf { !post && type != null && !type.startsWith("text/", ignoreCase = true) },
            "Output File".takeIf { byIndex[7].orEmptyIfBlank() != null },
            "Trust Any Certificate".takeIf { (ints[8] ?: 0) != 0 },
        )
        return ActionWithLoss(
            ActionSpec(
                type = "http.request",
                label = if (post) "Tasker HTTP post" else "Tasker HTTP get",
                args = buildMap {
                    put("method", if (post) "POST" else "GET")
                    put("url", url)
                    query?.let { put("query", it) }
                    if (post) {
                        data?.let { put("body", it) }
                        type?.let { put("content_type", it) }
                    }
                    if (headers.isNotEmpty()) put("headers", headers.joinToString("\n"))
                    ints[5]?.takeIf { it > 0 }?.let { put("timeout_sec", it.toString()) }
                    put("response_var", "%HTTPD")
                    put("status_var", "%HTTPR")
                    if (taskerUrlMayBePlainHttp(url)) put("allow_http", "true")
                },
            ),
            lossyWarning = joinWarnings(
                skipped.takeIf { it.isNotEmpty() }?.let { "Tasker HTTP fields not imported: ${it.joinToString(", ")}" },
                TASKER_PLAIN_HTTP_WARNING.takeIf { url.startsWith("http://", ignoreCase = true) },
            ),
        )
    }

    /** Tasker's HTTP Request query is `key:value` per line and this app's is `key=value`. Null when a line is neither. */
    private fun taskerColonQuery(text: String): String? {
        val lines = text.lines().filter(String::isNotBlank)
        if (lines.any { ':' !in it }) return null
        return lines.joinToString("\n") { it.trim().replaceFirst(":", "=") }
    }

    /** Tasker's Server:Port assumes http when no scheme is written. A variable can hold anything, so it's left alone. */
    private fun taskerHttpUrl(server: String, path: String): String {
        val host = server.trim()
        val base = if (host.isEmpty() || host.startsWith("%") || "://" in host) host else "http://$host"
        val tail = path.trim()
        return if (tail.isEmpty()) base else "${base.trimEnd('/')}/${tail.trimStart('/')}"
    }

    /** allow_http only ever reaches local network addresses, and a variable might hold one. */
    private fun taskerUrlMayBePlainHttp(url: String): Boolean =
        url.startsWith("%") || url.startsWith("http://", ignoreCase = true)

    private fun taskerVolumeStream(code: String): String = when (code) {
        "303" -> "alarm"
        "304" -> "ring"
        "305" -> "notification"
        "306" -> "voice"
        "308" -> "system"
        else -> "music"
    }

    private fun taskerLevel(strings: List<String>, intsByIndex: Map<Int, Int>): String =
        taskerScalar(strings, intsByIndex, "0")

    private fun taskerScalar(strings: List<String>, intsByIndex: Map<Int, Int>, fallback: String): String =
        strings.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
            ?: intsByIndex[0]?.toString()
            ?: fallback

    private fun parseDocument(rawXml: String): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
            // Best-effort only: Android's Harmony/Expat factories throw for the Apache feature
            // URI, and making it fatal broke every device import (issue #5). Doctypes are
            // stripped or rejected in text by ImportResourceGuard.sanitizeTaskerXml before
            // this parser ever sees the input.
            applyImportHardening()
        }
        return factory.newDocumentBuilder().parse(InputSource(StringReader(rawXml)))
    }

    private fun Element.actionStrings(): List<String> =
        directChildren("Str")
            .sortedBy { it.argIndex() }
            .map { it.getAttribute("val").ifBlank { it.textContent.orEmpty() }.trim() }

    private fun Element.actionInts(): List<Int> =
        directChildren("Int")
            .sortedBy { it.argIndex() }
            .mapNotNull { it.getAttribute("val").ifBlank { it.textContent.orEmpty() }.trim().toIntOrNull() }

    /**
     * Str args keyed by their Tasker `sr` argument index. Tasker writes children in lexicographic
     * `sr` order (arg1, arg10, arg11, arg12, arg2, ...) and omits fields it considers unset, so a
     * dense positional list silently shifts every field after a gap. Fixed-position actions must
     * read by index for the same reason [actionIntsByIndex] exists.
     */
    private fun Element.actionStringsByIndex(): Map<Int, String> =
        directChildren("Str").mapNotNull { element ->
            val index = element.argIndex().takeIf { it != Int.MAX_VALUE } ?: return@mapNotNull null
            index to element.getAttribute("val").ifBlank { element.textContent.orEmpty() }.trim()
        }.toMap()

    private fun String?.orEmptyIfBlank(): String? = this?.takeIf(String::isNotBlank)

    /** Int args keyed by their Tasker `sr` argument index, so fixed-position fields keep their unit. */
    private fun Element.actionIntsByIndex(): Map<Int, Int> =
        directChildren("Int").mapNotNull { element ->
            val index = element.argIndex().takeIf { it != Int.MAX_VALUE } ?: return@mapNotNull null
            val value = element.getAttribute("val").ifBlank { element.textContent.orEmpty() }.trim().toIntOrNull()
                ?: return@mapNotNull null
            index to value
        }.toMap()

    /**
     * Text of `<Int sr="argN"><var>%x</var></Int>`, which is how Tasker stores a variable or a sum in
     * a number field. A `<var>` holding a plain number stays with [actionIntsByIndex].
     */
    private fun Element.actionIntVariablesByIndex(): Map<Int, String> =
        directChildren("Int").mapNotNull { element ->
            val index = element.argIndex().takeIf { it != Int.MAX_VALUE } ?: return@mapNotNull null
            element.getAttribute("val").ifBlank { element.textContent.orEmpty() }.trim()
                .takeIf { it.isNotEmpty() && it.toIntOrNull() == null }
                ?.let { index to it }
        }.toMap()

    /** Package of `<App sr="argN"><appPkg>...</appPkg></App>`, where Tasker keeps a chosen app. */
    private fun Element.actionAppPackage(index: Int): String? =
        directChildren("App").firstOrNull { it.argIndex() == index }?.childText("appPkg")?.takeIf(String::isNotBlank)

    private fun waitMillis(strings: List<String>, intsByIndex: Map<Int, Int>): Long {
        val explicit = strings.firstOrNull()?.trim()?.toLongOrNull()
        if (explicit != null) return explicit
        // Tasker's Wait action (code 30) stores five fixed Int fields by position:
        // arg0=milliseconds, arg1=seconds, arg2=minutes, arg3=hours, arg4=days. Reading by argIndex
        // keeps each field in its own unit regardless of which zero fields Tasker omitted from the
        // export — the previous dense-list heuristic mis-scaled a lone field by up to 1000x.
        val milliseconds = (intsByIndex[0] ?: 0).toLong()
        val seconds = (intsByIndex[1] ?: 0).toLong()
        val minutes = (intsByIndex[2] ?: 0).toLong()
        val hours = (intsByIndex[3] ?: 0).toLong()
        val days = (intsByIndex[4] ?: 0).toLong()
        val total = milliseconds +
            seconds * 1_000L +
            minutes * 60_000L +
            hours * 3_600_000L +
            days * 86_400_000L
        return if (total > 0L) total else 1_000L
    }

    private fun sourceId(element: Element, index: Int): Long =
        element.childText("id").toLongOrNull()
            ?: element.getAttribute("sr").filter(Char::isDigit).toLongOrNull()
            ?: (index + 1L)

    private fun uniqueId(preferred: Long, used: MutableSet<Long>): Long {
        var candidate = preferred.takeIf { it > 0 } ?: 1L
        while (!used.add(candidate)) candidate++
        return candidate
    }

    private fun clockFromParts(hour: Int?, minute: Int?): String =
        if (hour != null && hour in 0..23) "%02d:%02d".format(hour, minute?.coerceIn(0, 59) ?: 0) else ""

    private fun Document.elementsByTagName(name: String): List<Element> =
        getElementsByTagName(name).asElementList()

    private fun Element.directChildren(name: String): List<Element> =
        directChildElements().filter { it.tagName.equals(name, ignoreCase = true) }

    private fun Element.directChildElements(): List<Element> =
        childNodes.asElementList()

    /** Each text node under this one, kept apart: textContent runs `<lhs>%A</lhs><op>6</op>` into `%A6`. */
    private fun Node.textNodes(): List<String> = when (nodeType) {
        Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> listOf(nodeValue.orEmpty())
        else -> (0 until childNodes.length).flatMap { childNodes.item(it).textNodes() }
    }

    private fun Element.childText(vararg names: String): String =
        names.firstNotNullOfOrNull { name ->
            directChildren(name).firstOrNull()?.textContent?.trim()?.takeIf { it.isNotBlank() }
        }.orEmpty()

    private fun Element.argIndex(): Int =
        getAttribute("sr").filter(Char::isDigit).toIntOrNull() ?: Int.MAX_VALUE

    private data class ImportedCondition(val expression: String?, val warning: String?)

    /**
     * Reads a Tasker `<ConditionList sr="if"><Condition><lhs>/<op>/<rhs></Condition></ConditionList>`
     * -- real Tasker exports encode the "Run only if" guard this way on ANY action, not just
     * flow-control If/Else If (measured on a real backup: 85 of 118 ConditionList occurrences were
     * on ordinary actions like Set Variable). Returns a condition string in this app's own syntax,
     * or a null expression if the action has no ConditionList, which is the normal case for most
     * actions.
     *
     * Several conditions are joined by `<bool0>`, `<bool1>` and on, and come in as one expression
     * joined with `&&` and `||`, grouped by [joinTaskerConditions]. Like Tasker (decompiled 6.7.8,
     * u1), the list ends at the first condition number or joiner that isn't there, and conditions
     * past it are left out with a note. A joined list is dropped with a note rather than imported with another meaning
     * when it uses Xor or Xor2, which the condition grammar has no word for, or when a condition
     * would change how the joined text splits ([joinableSide]). Likewise, an `<op>` code outside
     * the known Tasker set (0-13) yields a null expression -- which for flow.if
     * specifically falls back to the old literal-"true" behavior -- but is now reported instead of
     * silently reproduced. Ops 4/5 are Tasker's "Matches Regex"/"Doesn't Match Regex" (verified
     * against Map-Tasker's operator table) and import as `~R`/`!~R`; a pattern RE2 can't compile
     * is reported the same way. Codes from an older Tasker are renumbered first
     * ([taskerConditionOp]).
     */
    private fun Element.parseImportedCondition(): ImportedCondition {
        val conditionList = directChildren("ConditionList").firstOrNull() ?: return ImportedCondition(null, null)
        val conditions = conditionList.directChildren("Condition")
        if (conditions.isEmpty()) return ImportedCondition(null, null)
        // Tasker reads c0, bool0, c1 and on by number and stops at the first one missing. A
        // hand-written list with no numbers at all is read in file order.
        val numbered = if (conditions.all { it.argIndex() == Int.MAX_VALUE }) {
            conditions.withIndex().associate { it.index to it.value }
        } else {
            conditions.reversed().associateBy { it.argIndex() }
        }
        val read = mutableListOf<Element>()
        val joiners = mutableListOf<String>()
        while (true) {
            read += numbered[read.size] ?: break
            joiners += conditionList.directChildren("bool${read.size - 1}").firstOrNull()?.textContent?.trim() ?: break
        }
        if (read.isEmpty()) {
            return ImportedCondition(null, "a \"Run only if\" guard was dropped, since it has no first condition (c0) and Tasker reads none of it")
        }
        if (joiners.size == read.size) joiners.removeAt(joiners.lastIndex)
        val cutWarning = when {
            read.size == conditions.size -> null
            read.size == 1 ->
                "a multi-condition \"Run only if\" guard was reduced to just its first condition, since Tasker reads no condition past a missing number or a missing And or Or"
            else ->
                "a multi-condition \"Run only if\" guard was reduced to its first ${read.size} conditions, since Tasker reads no condition past a missing number or a missing And or Or"
        }
        fun withNotes(expression: String?, vararg notes: String?) =
            ImportedCondition(expression, listOfNotNull(cutWarning, *notes).joinToString("; ").ifBlank { null })

        if (read.size == 1) {
            val condition = read.single()
            if (condition.childText("lhs").isBlank()) return ImportedCondition(null, null)
            val op = condition.taskerConditionOp()
            if (op in TASKER_MATHS_EQUALITY_OPS && !(joinableSide(condition.childText("lhs")) && joinableSide(condition.childText("rhs")))) {
                return withNotes(null, "a \"Run only if\" guard (op $op) was dropped, since a side holds && or ||, a {{ template }} or an unmatched bracket, which this app can't write as a pair of checks")
            }
            val expression = condition.conditionExpression(op)
                ?: return withNotes(null, "a \"Run only if\" guard used an unsupported Tasker comparison (op $op) and was dropped")
            return withNotes(expression, condition.regexApproximationWarning(op))
        }

        fun dropped(reason: String) =
            withNotes(null, "a \"Run only if\" guard joining ${read.size} conditions was dropped, since $reason")
        joiners.firstOrNull { it !in TASKER_JOINER_ORDER }?.let { joiner ->
            return dropped("it joins them with ${joiner.ifBlank { "an empty joiner" }}, which this app has no way to write")
        }
        val approximations = mutableListOf<String>()
        val parts = read.map { condition ->
            val op = condition.taskerConditionOp()
            val lhs = condition.childText("lhs")
            // Tasker ignores the right side of a unary check, and exports often leave an old one there.
            val rhs = if (op in TASKER_UNARY_CONDITION_OPS) "" else condition.childText("rhs")
            when {
                lhs.isBlank() -> return dropped("one of them has nothing to compare")
                !joinableSide(lhs) || !joinableSide(rhs) ->
                    return dropped("one of them holds && or ||, a {{ template }} or an unmatched bracket, which would change how they join")
            }
            condition.regexApproximationWarning(op)?.let { approximations += it }
            condition.conditionExpression(op)
                ?: return dropped("one of them uses an unsupported Tasker comparison (op $op)")
        }
        return withNotes(joinTaskerConditions(parts, joiners), *approximations.toTypedArray())
    }

    /** This Condition in this app's syntax, or null when its [op] has no counterpart here. */
    private fun Element.conditionExpression(op: String): String? {
        val lhs = childText("lhs")
        return when (op) {
            "10" -> "$lhs is_even"
            "11" -> "$lhs is_odd"
            "12" -> "$lhs is_set"
            "13" -> "$lhs not_set"
            // Maths Equals and Not Equals compare numbers, so 5.0 equals 5 and an unset variable is
            // 0. This app's maths comparisons read both sides the same way, and a side that isn't a
            // number leaves both forms false, as it does in Tasker. Tasker settles an empty side
            // first: with the left one filled in as 0 when unset, an empty right side makes Equals
            // false, which the pair already is, and Not Equals true, written as a check that always holds.
            "8" -> childText("rhs").let { rhs -> "($lhs <= $rhs && $lhs >= $rhs)" }
            "9" -> childText("rhs").let { rhs ->
                if (rhs.isEmpty()) "($lhs is_set || $lhs not_set)" else "($lhs < $rhs || $lhs > $rhs)"
            }
            else -> TASKER_CONDITION_OP_TOKENS[op]?.let { token -> "$lhs $token ${childText("rhs")}" }
        }
    }

    /**
     * RE2 runs regex conditions here, and it has no backreferences or lookarounds, which Java's
     * engine in Tasker does. A pattern built from a variable is only known when it runs, and an
     * empty one matches nothing in Tasker too.
     */
    private fun Element.regexApproximationWarning(op: String): String? {
        val rhs = childText("rhs")
        return when {
            op != "4" && op != "5" || rhs.isBlank() || TASKER_VARIABLE_REFERENCE.containsMatchIn(rhs) -> null
            rhs.length > TextOps.MAX_PATTERN_CHARS ->
                "a regex \"Run only if\" comparison (op $op) is longer than ${TextOps.MAX_PATTERN_CHARS} characters, so its pattern never matches here"
            TextOps.match("", rhs) == null ->
                "a regex \"Run only if\" comparison (op $op) uses regular expression features this app doesn't have, so its pattern never matches here: $rhs"
            else -> null
        }
    }

    /**
     * Whether one side of a joined condition reads back as itself once it sits between `&&`, `||`
     * and brackets. The condition grammar splits at a `&&` or `||` outside brackets and skips over
     * `{{ }}`, so a side holding one of those, or a bracket it doesn't close, would move the split.
     */
    private fun joinableSide(text: String): Boolean {
        if ("&&" in text || "||" in text || "{{" in text || "}}" in text) return false
        var depth = 0
        for (char in text) {
            when (char) {
                '(' -> depth++
                ')' -> if (--depth < 0) return false
            }
        }
        return depth == 0
    }

    /**
     * Joins Tasker's conditions the way Tasker 6.7.8 evaluates the list (decompiled u1): it splits at
     * the loosest joiner present, Or, then And, then Or2, then And2, and each piece again at the next
     * one. A piece of several conditions goes in brackets, since the grammar here only knows `||`
     * binding looser than `&&`.
     */
    private fun joinTaskerConditions(parts: List<String>, joiners: List<String>): String {
        if (parts.size == 1) return parts.single()
        val joiner = TASKER_JOINER_ORDER.first { it in joiners }
        val pieces = mutableListOf<String>()
        var start = 0
        fun piece(end: Int) {
            val inner = joinTaskerConditions(parts.subList(start, end + 1), joiners.subList(start, end))
            pieces += if (end > start) "($inner)" else inner
            start = end + 1
        }
        joiners.forEachIndexed { index, it -> if (it == joiner) piece(index) }
        piece(parts.lastIndex)
        return pieces.joinToString(if (joiner.startsWith("Or")) " || " else " && ")
    }

    /**
     * This condition's `<op>` in today's numbering. Tasker renumbered its comparisons twice and
     * records the numbering in the Condition's `ve` attribute: it writes ve="3" now, and older
     * exports carry ve="2", where 4 was a numeric equals and 10 was Matches Regex. Tasker's own
     * loader (decompiled 6.7.8, t1) maps an older code up the same way. A Condition with no `ve`
     * is read as current here, where Tasker would read it as version 1, because every Tasker
     * export seen writes one and hand-written samples without it use today's codes.
     */
    private fun Element.taskerConditionOp(): String {
        val raw = childText("op")
        val version = getAttribute("ve").trim().toIntOrNull() ?: return raw
        var code = raw.trim().toIntOrNull() ?: return raw
        if (version < 2 && code > 0) code -= 1
        if (version < 3) {
            code = when {
                code == 10 -> 4
                code == 11 -> 5
                code < 2 -> code + 2
                else -> code + 4
            }
        }
        return code.toString()
    }

    private fun org.w3c.dom.NodeList.asElementList(): List<Element> =
            (0 until length).mapNotNull { index -> item(index).takeIf { it.nodeType == Node.ELEMENT_NODE } as? Element }

    private data class ParsedTaskerAction(
        val action: ActionSpec,
        val mapped: TaskerMappedAction?,
        val unsupported: TaskerUnsupportedAction?,
        val lossyWarning: String? = null,
    )

    private data class ActionWithLoss(
        val action: ActionSpec,
        val lossyWarning: String? = null,
    )

    private data class ImportedField(val value: String, val warning: String? = null)

    // Tasker's field layouts come from its own published action table, which Map-Tasker vendors as
    // maptasker/assets/json/task_all_actions.json, checked against real exports found on GitHub.
    // HTTP Request (code 339): arg0 Output Variables, arg1 Method, arg2 URL, arg3 Headers, arg4 Query
    // Parameters, arg5 Body, arg6 File To Send, arg7 File To Save, arg8 Timeout in seconds, arg9 Trust
    // Any Certificate, arg10 Automatically Follow Redirects, arg11 Use Cookies, arg12 Structure Output.
    private const val TASKER_HTTP_METHOD_ARG = 1
    private const val TASKER_HTTP_URL_ARG = 2
    private const val TASKER_HTTP_HEADERS_ARG = 3
    private const val TASKER_HTTP_QUERY_ARG = 4
    private const val TASKER_HTTP_BODY_ARG = 5
    private const val TASKER_HTTP_FILE_TO_SEND_ARG = 6
    private const val TASKER_HTTP_OUTPUT_FILE_ARG = 7
    private const val TASKER_HTTP_TIMEOUT_ARG = 8
    private const val TASKER_HTTP_TRUST_ARG = 9
    private const val TASKER_HTTP_REDIRECTS_ARG = 10
    private const val TASKER_HTTP_COOKIES_ARG = 11

    /**
     * Tasker's method spinner, as Map-Tasker lists it. Index 7 is TRACE, which this app doesn't send.
     * An index missing here imports without a method and says so, because turning an unrecognised
     * verb into a GET would send a different request than the user wrote.
     */
    private val TASKER_HTTP_METHODS = mapOf(
        0 to "GET",
        1 to "POST",
        2 to "HEAD",
        3 to "PUT",
        4 to "PATCH",
        5 to "DELETE",
        6 to "OPTIONS",
    )

    private val TASKER_HTTP_BODYLESS_METHODS = setOf("GET", "HEAD")

    private const val TASKER_PLAIN_HTTP_WARNING =
        "A plain http address only reaches local network devices here; public hosts need https"

    private const val TASKER_SAME_SITE_REDIRECTS_WARNING =
        "Tasker's Automatically Follow Redirects imports as following redirects on the same site only"

    private const val TASKER_MAX_SCREEN_TIMEOUT_MS = 1_800_000L

    // Wait is arg0 milliseconds, then seconds, minutes, hours, days. Display Timeout is seconds,
    // minutes, hours. Vibrate has one field.
    private val TASKER_WAIT_UNITS = listOf(1L, 1_000L, 60_000L, 3_600_000L, 86_400_000L)
    private val TASKER_TIMEOUT_UNITS = listOf(1_000L, 60_000L, 3_600_000L)
    private val TASKER_VIBRATE_UNITS = listOf(1L)

    private val TASKER_SET_STATES = mapOf(0 to "off", 1 to "on", 2 to "toggle")

    // A plugin's string array: <StringArray> and its items, nothing deeper.
    private val STRING_ARRAY_BUDGET = ImportResourceBudget.Default.copy(maxXmlChars = 256 * 1024, maxXmlNodes = 16_384, maxNestingDepth = 2)

    private val TASKER_SETTING_TABLES = mapOf(0 to "global", 1 to "secure", 2 to "system")

    private val TASKER_MEDIA_COMMANDS = mapOf(
        0 to "track.next",
        1 to "sound.pause",
        2 to "track.previous",
        3 to "sound.toggle",
        4 to "sound.stop",
        5 to "sound.resume",
        6 to "track.rewind",
        7 to "track.fastforward",
    )

    /** One variable whose name fits [VariableNamePolicy.MAX_LENGTH], so a step can store into it. */
    private val TASKER_PLAIN_VARIABLE = Regex("""%[A-Za-z][A-Za-z0-9_]{0,${VariableNamePolicy.MAX_LENGTH - 1}}""")

    /** Tasker's array actions: their Tasker name and the action each one maps to. */
    private val TASKER_ARRAY_ACTIONS = mapOf(
        "590" to ("Variable Split" to "var.split"),
        "592" to ("Variable Join" to "var.join"),
        "354" to ("Array Set" to "var.split"),
        "355" to ("Array Push" to "array.push"),
        "356" to ("Array Pop" to "array.pop"),
        "357" to ("Array Clear" to "array.clear"),
        "369" to ("Array Process" to "array.process"),
    )

    /** Anywhere Tasker would fill in a variable when the action runs. */
    private val TASKER_VARIABLE_REFERENCE = Regex("""%[A-Za-z]""")

    private val TASKER_INTERRUPT_MODES = mapOf(0 to "total_silence", 1 to "priority", 2 to "off", 3 to "alarms")

    private val TASKER_INTERRUPT_MODE_NAMES = mapOf(4 to "Custom", 5 to "Query")

    private val TASKER_SOUND_MODES = mapOf(0 to "silent", 1 to "vibrate", 2 to "normal")

    // Tasker's reboot_choices, in arg0 order.
    private val TASKER_REBOOT_MODES = listOf("Normal", "Recovery", "Bootloader", "Shutdown")

    // Action name and the system setting it switches, as Tasker's action table pairs them.
    private val TASKER_SYSTEM_SWITCHES = mapOf(
        "808" to ("Auto Brightness" to "screen_brightness_mode"),
        "822" to ("Display AutoRotate" to "accelerometer_rotation"),
    )

    // Codes kept as placeholders on purpose, with the reason the placeholder's import note gives.
    // Scene codes are Tasker's Scene and Scene v2 categories in its action table, plus Edit Scene
    // (143), which Tasker files under its own Tasker category.
    private val TASKER_KEPT_PLACEHOLDER_REASONS: Map<String, String> =
        listOf(
            "46", "47", "48", "49", "50", "51", "53", "54", "55", "56", "57", "58", "60", "63", "64", "65",
            "66", "67", "68", "69", "71", "73", "143", "194", "195", "479", "480", "481", "482", "483", "484",
            "485", "612", "991",
        ).associateWith { "Tasker scenes aren't imported, so there's no scene here for this step to work on" } +
            listOf("474", "664", "665").associateWith {
                "Tasker's Java steps run Java code inside Tasker, and OpenTasker doesn't run code that comes in with an import"
            } +
            listOf("129", "131").associateWith { "OpenTasker has no JavaScript engine to run Tasker's JavaScript steps" } +
            mapOf(
                "101" to "OpenTasker has no camera step, so Tasker's Take Photo can't run here",
                "123" to "Tasker's Run Shell runs the command typed into the step, often as root. OpenTasker only runs " +
                    "script files you've approved in Settings. Saved as a script, the command can run through Run " +
                    "Shizuku Script, which runs it as the shell user unless Shizuku was started with root",
                "328" to "Tasker's Keyboard types into other apps through Tasker's own keyboard, which OpenTasker doesn't have",
                "703" to "Tasker's Button presses a key like Back or Menu, and OpenTasker has no step that presses those keys",
                "245" to "Tasker's Back Button presses Back, and OpenTasker has no step that presses Back",
                "35" to "Tasker's Wait Until waits for its If condition to come true, and the wait step here only waits a set time",
                "319" to "Tasker's Ask Permissions asks for Tasker's own permissions. OpenTasker's are the ones you grant from Setup",
                "139" to "Tasker's Disable turns Tasker itself off, so there's nothing here for it to change",
                "133" to "Tasker's Set Tasker Pref changes one of Tasker's own settings, so there's nothing here for it to change",
                "138" to "Tasker's Set Tasker Icon changes Tasker's own icon, so there's nothing here for it to change",
                "347" to "Tasker's Test Tasker reads Tasker's own state, so there's nothing here for it to read",
                "365" to "Tasker's Tasker Function runs one of Tasker's own functions, which have no match here",
                "490" to "Tasker's Media Button Events lets Tasker catch the media buttons, and OpenTasker has no media button trigger",
                "512" to "Tasker's Status Bar opens or closes the notification shade, and OpenTasker has no step that does",
                "513" to "Tasker's Close System Dialogs closes open system dialogs, and OpenTasker has no step that does",
                "550" to "Tasker's Popup shows a dialog, and OpenTasker has no step that shows one",
                "941" to "Tasker's HTML Popup shows a web page in a dialog, and OpenTasker has no step that shows one",
                "551" to "Tasker's Menu shows a list to pick from, and OpenTasker has no step that shows one",
                "595" to "Tasker's Variable Query asks you to type a value, and OpenTasker has no step that asks for input",
                "903" to "Tasker's Get Voice listens for speech, and OpenTasker has no speech recognition step",
                "697" to "Tasker's Shut Up stops speech that's playing, and OpenTasker has no step that does",
                "62" to "Tasker's Vibrate Pattern vibrates in a pattern, and the vibrate step here takes one length",
                "545" to "Tasker's Variable Randomize picks a random number, and OpenTasker has no step that does",
                "90" to "Tasker's Call dials a number, and OpenTasker has no phone call step",
                "566" to "Tasker's Set Alarm sets an alarm in the clock app, and OpenTasker has no alarm step",
                "165" to "Tasker's Cancel Alarm cancels an alarm in the clock app, and OpenTasker has no alarm step",
                "567" to "Tasker's Calendar Insert adds a calendar event, and OpenTasker has no calendar step",
                "667" to "Tasker's SQL Query runs a query on a database or a content provider, and OpenTasker has no step that does",
                "344" to "Tasker's Test App reads an app's details into a variable, and OpenTasker has no step that does",
                "815" to "Tasker's List Apps reads the installed apps into variables, and OpenTasker has no step that does",
                "366" to "Tasker's Get Location v2 reads a location fix into variables, and OpenTasker has no step that does. " +
                    "Location works as a profile condition here",
                "902" to "Tasker's Get Location reads a location fix into variables, and OpenTasker has no step that does. " +
                    "Location works as a profile condition here",
                "380" to "Tasker's HTTP Response answers a request made to Tasker's own web server, and OpenTasker doesn't run one",
                "421" to "Tasker's Get Screen Info reads the screen as the assistant app, and OpenTasker can't be the assistant",
                "450" to "Tasker's APN Droid drives the APNDroid app, which OpenTasker doesn't talk to",
                "342" to "Tasker's Test File reads a file's details into a variable, and OpenTasker has no step that does",
                "400" to "Tasker's Move moves or renames a file, and OpenTasker has no step that does",
                "412" to "Tasker's List Files fills an array with full paths, and the list files step here puts file names " +
                    "in one variable, one per line",
                "415" to "Tasker's Read Line reads one line of a file, and the read file step here reads the whole file",
                "776" to "Tasker's Read Binary reads a file into a variable as Base64 text, and the read file step here reads a file " +
                    "as plain text",
                "396" to "Tasker's Simple Match/Regex fills %mt_match, %mt_match_found and %mt_groups(), and the match step here " +
                    "fills a variable and an array you name instead",
                "394" to "Tasker's Parse/Format DateTime fills %formatted and a set of %dt_ variables in one step, and the date steps " +
                    "here each fill one variable",
                "392" to "Tasker's Set Variable Structure Type marks a variable's format, like JSON or CSV, for Tasker's reading, " +
                    "and OpenTasker has no such setting",
                "424" to "Tasker's Get Battery Info reads battery details into variables, and OpenTasker has no step that does. " +
                    "The battery level is in %BATT",
                "341" to "Tasker's Test Net reads network details into a variable, and OpenTasker has no step that does",
                "348" to "Tasker's Test Display reads display details into a variable, and OpenTasker has no step that does",
                "248" to "Tasker's Turn Off turns the screen off, and its Dim option has no match here. The screen off step turns " +
                    "it off through Shizuku, and the lock step locks the phone",
                "109" to "Tasker's Set Wallpaper has fitting options like Scale and Crop that the wallpaper step here doesn't have. " +
                    "The wallpaper step sets an image file on the home screen, lock screen or both",
                "311" to "Tasker's BT Voice Volume sets the call volume over Bluetooth, and the volume step here has no Bluetooth " +
                    "call stream",
                "152" to "Tasker's Set Widget Icon changes the icon on one of Tasker's widgets, and OpenTasker has no step that " +
                    "changes a widget",
                "162" to "Tasker's Set up Quick Setting Tile picks the task a tile runs from inside a task. Here you pick a tile's " +
                    "task from the tile in Quick Settings, and the tile step only changes a tile that already has one",
                "158" to "Tasker's Quick Setting Remove takes a tile Tasker added off the Quick Settings panel, and OpenTasker has " +
                    "no step that does",
                "219" to "Tasker's Quick Settings pulls down the Quick Settings panel, and OpenTasker has no step that does",
                "216" to "Tasker's App Settings opens the app list in Settings, or one app's info screen, and OpenTasker has no " +
                    "step made for that",
                "187" to "Tasker's Save Image saves the picture Tasker's image steps were working on, and OpenTasker has no steps " +
                    "that edit images",
                "188" to "Tasker's Load Image loads a picture for Tasker's image steps to edit, and OpenTasker has no steps that " +
                    "edit images",
                "370" to "Tasker's Shortcut runs another app's shortcut, which isn't converted here. The intent step can send the " +
                    "shortcut's action and component, though some apps only open their shortcuts for the launcher",
                "381" to "Tasker's Contact Via App opens a contact in an app like WhatsApp, and OpenTasker has no step that does",
                "740" to "Tasker's Zoom Text sets text on a widget made with the Zoom app, which OpenTasker doesn't talk to",
            )

    private val TASKER_INTENT_TARGETS = mapOf(0 to "broadcast", 1 to "activity", 2 to "service")

    private val TASKER_INTENT_CATEGORIES = mapOf(
        1 to "android.intent.category.DEFAULT",
        2 to "android.intent.category.ALTERNATIVE",
        3 to "android.intent.category.BROWSABLE",
        4 to "android.intent.category.CAR_DOCK",
        5 to "android.intent.category.DESK_DOCK",
        6 to "android.intent.category.HOME",
        7 to "android.intent.category.INFO",
        8 to "android.intent.category.LAUNCHER",
        9 to "android.intent.category.PREFERENCE",
        10 to "android.intent.category.SELECTED_ALTERNATIVE",
        11 to "android.intent.category.TAB",
        12 to "android.intent.category.TEST",
        13 to "com.google.intent.category.CARDBOARD",
    )

    private val TASKER_INTENT_EXTRA_KEY = Regex("""[A-Za-z_][A-Za-z0-9_.-]*""")

    private val TASKER_SIMPLE_SUM =
        Regex("""%([A-Za-z][A-Za-z0-9_]{0,${VariableNamePolicy.MAX_LENGTH - 1}})\s*([-+*/])\s*(\d+(?:\.\d+)?)""")

    private val TASKER_SUM_FILTERS = mapOf("+" to "add", "-" to "sub", "*" to "mul", "/" to "div")

    private val TASKER_WHOLE_ARRAY = Regex("""%([A-Za-z][A-Za-z0-9_]*)\(\)""")

    private val TASKER_CONTEXT_TAGS = setOf("time", "day", "application", "app", "state", "event")
    private val TASKER_WEEKDAYS = mapOf(
        1 to "SUN",
        2 to "MON",
        3 to "TUE",
        4 to "WED",
        5 to "THU",
        6 to "FRI",
        7 to "SAT",
    )
    private val PROFILE_SCALAR_TAGS = setOf(
        "clp",
        "limit",
        "repeats",
        "cldm",
        "cdate",
        "edate",
        "flags",
        "id",
        "mid0",
        "mid1",
        "nme",
        "name",
        "off",
        "pri",
        "priority",
        "gracePeriodSec",
        "grace",
        "lifetime",
        "expiresAtMs",
        "expiresAt",
        "maxActiveExecutions",
        "maxActive",
        "burstLimit",
        "profileBurstLimit",
        "overflowPolicy",
    )

    const val TASKER_UNSUPPORTED_ACTION_ID = "tasker.unsupported"

    // Tasker's numeric Condition <op> codes, sourced from github.com/mctinker/Map-Tasker's
    // IF_CONDITION_OPERATORS table (a mature, independently-verified Tasker XML tool) and
    // cross-checked against a real backup: op values 0/1/2/12/13 all appear and match their
    // documented semantics -- e.g. the one real op=1 instance compares %http_response_code
    // against 200, matching this project's own documented "Doesn't Match 200" HTTP-status check.
    // 3-9 don't appear in that corpus but are included for completeness. 4/5 are the regex
    // variants of 2/3 ("Matches Regex"/"Doesn't Match Regex"), written `~R`/`!~R` the way Tasker
    // shows them. 8/9, the maths =/!=, have no single token here, so conditionExpression spells
    // them out, as it does the unary 10/11 (Is Even/Is Odd) and 12/13 (Is Set/Not Set).
    private val TASKER_CONDITION_OP_TOKENS = mapOf(
        "0" to "==",
        "1" to "!=",
        "2" to "~",
        "3" to "!~",
        "4" to "~R",
        "5" to "!~R",
        "6" to "<",
        "7" to ">",
    )

    // Tasker's condition joiners from loosest to tightest (decompiled 6.7.8, u1). Xor, looser than
    // Or, and Xor2, between And and Or2, are left out: the grammar here has no exclusive or.
    private val TASKER_JOINER_ORDER = listOf("Or", "And", "Or2", "And2")

    // Is Even, Is Odd, Is Set and Not Set, which take no right side.
    private val TASKER_UNARY_CONDITION_OPS = setOf("10", "11", "12", "13")

    // Maths Equals and Not Equals, which conditionExpression writes as a bracketed pair of checks.
    private val TASKER_MATHS_EQUALITY_OPS = setOf("8", "9")
}
