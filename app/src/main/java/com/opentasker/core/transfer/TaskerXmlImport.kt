package com.opentasker.core.transfer

import com.opentasker.core.actions.IntentDispatchParseResult
import com.opentasker.core.actions.IntentDispatchPolicy
import com.opentasker.core.contexts.BootContextEvents
import com.opentasker.core.contexts.DaySchedule
import com.opentasker.core.contexts.WIFI_CONNECTED_WORDS
import com.opentasker.core.contexts.WIFI_DISCONNECTED_WORDS
import com.opentasker.core.data.TextOps
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
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
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

private fun taskerAlarmStreamArg(stream: Int?): Map<String, String> =
    if (stream == TASKER_ALARM_STREAM) mapOf("stream" to "alarm") else emptyMap()

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

        val tasks = parseTasks(doc, mappedActions, unsupportedActions, lossyWarnings)
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
            val actions = element.directChildren("Action").mapIndexed { actionIndex, actionElement ->
                val parsed = parseAction(name, actionElement, actionIndex)
                parsed.mapped?.let(mappedActions::add)
                parsed.unsupported?.let(unsupportedActions::add)
                parsed.lossyWarning?.let { lossyWarnings += "Task '$name' action ${actionIndex + 1}: $it" }
                parsed.action
            }
            Task(id = id, name = name, actions = actions)
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
            val enterTaskId = element.childText("mid0", "task", "tid", "enterTaskId").toLongOrNull()
            if (enterTaskId == null || enterTaskId !in taskIds) {
                lossyWarnings += "Profile '$name' was skipped because its entry task could not be mapped."
                return@mapIndexedNotNull null
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
                    exitTaskId = element.childText("mid1", "exitTaskId").toLongOrNull()?.takeIf { it in taskIds },
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
        val start = element.childText("from", "start").ifBlank {
            clockFromParts(element.childText("fh").toIntOrNull(), element.childText("fm").toIntOrNull())
        }
        val end = element.childText("to", "end").ifBlank {
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
            "888" -> taskerVariableAdd(code, byIndex, intsByIndex, intVariables)
            "549" -> taskerVariableClear(code, byIndex, intsByIndex)
            "597" -> taskerVariableSection(code, byIndex, intsByIndex, intVariables)
            "598" -> taskerSearchReplace(code, element, byIndex, intsByIndex)
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
                    args = mapOf("text" to strings.getOrElse(0) { "" }) + taskerAlarmStreamArg(intsByIndex[2]),
                ),
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
            "810", "brightness", "brightness.set" -> {
                val level = intVariables[0]?.let { taskerNumberField("Display Brightness", it) }
                ActionWithLoss(
                    ActionSpec(
                        type = "brightness.set",
                        label = "Tasker brightness",
                        args = mapOf("brightness" to (level?.value ?: taskerScalar(strings, intsByIndex, "auto"))),
                    ),
                    lossyWarning = level?.warning,
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
            "192", "play", "sound.play" -> {
                // Play Ringtone's arg1 is the sound. Older exports from this app wrote the path at arg0.
                val path = byIndex[1].orEmptyIfBlank() ?: strings.firstOrNull().orEmpty()
                ActionWithLoss(
                    action = ActionSpec(
                        type = "sound.play",
                        label = "Tasker sound",
                        args = mapOf("path" to path) + taskerAlarmStreamArg(intsByIndex[2]),
                    ),
                    lossyWarning = "Tasker Play Ringtone \"$path\" names a system sound, not a file, so it needs a file here"
                        .takeIf { '/' !in path && !path.startsWith("%") },
                )
            }
            "445" -> ActionWithLoss(
                ActionSpec(
                    type = "sound.play",
                    label = "Tasker music play",
                    args = mapOf("path" to byIndex[0].orEmpty()) + taskerAlarmStreamArg(intsByIndex[3]),
                ),
                lossyWarning = listOfNotNull(
                    "Start".takeIf { (intsByIndex[1] ?: 0) != 0 },
                    "Loop".takeIf { (intsByIndex[2] ?: 0) != 0 },
                ).takeIf { it.isNotEmpty() }?.let { "Tasker Music Play fields not imported: ${it.joinToString(", ")}" },
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
                        args = mapOf(
                            "list" to (array ?: items),
                            "var" to (byIndex[0].orEmptyIfBlank() ?: "item"),
                        ),
                    ),
                    lossyWarning = "Tasker For over \"$items\" was kept as written; only a whole array like %name() loops here"
                        .takeIf { array == null && items.isNotEmpty() },
                )
            }
            "40", "endfor", "flow.endfor" -> ActionWithLoss(ActionSpec(type = "flow.endfor", label = "Tasker end for"))
            "137", "stop", "flow.stop" -> {
                val otherTask = byIndex[1].orEmptyIfBlank()
                if (otherTask != null) {
                    // flow.stop ends the running task, so importing this as one would stop the wrong task.
                    taskerPlaceholder(code, "Tasker Stop names another task to stop: $otherTask")
                } else {
                    ActionWithLoss(
                        ActionSpec(type = "flow.stop", label = "Tasker stop"),
                        lossyWarning = "Tasker Stop's With Error option was not imported".takeIf { (intsByIndex[0] ?: 0) != 0 },
                    )
                }
            }
            "126" -> taskerReturn(code, byIndex, intsByIndex)
            "130", "perform.task", "task.run" -> taskerPerformTask(element, strings, byIndex, intsByIndex)
            "log" -> ActionWithLoss(
                action = ActionSpec(
                    type = "log",
                    label = "Tasker log",
                    args = mapOf("message" to strings.getOrElse(0) { "Imported Tasker log action" }),
                ),
            )
            else -> ActionWithLoss(unsupportedAction(code))
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
        val action = if (importedCondition != null) {
            val args = if (actionWithLoss.action.args.containsKey("condition")) {
                actionWithLoss.action.args + ("condition" to importedCondition)
            } else {
                actionWithLoss.action.args
            }
            actionWithLoss.action.copy(condition = importedCondition, args = args)
        } else {
            actionWithLoss.action
        }
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
     * [factor] converts the field's unit, so `%x` seconds can arrive as milliseconds.
     */
    private fun taskerNumberTemplate(text: String, factor: Long = 1L): String? {
        val trimmed = text.trim()
        val filters = mutableListOf<String>()
        val name = if (TASKER_PLAIN_VARIABLE.matches(trimmed)) {
            trimmed.removePrefix("%")
        } else {
            val sum = TASKER_SIMPLE_SUM.matchEntire(trimmed) ?: return null
            filters += "${TASKER_SUM_FILTERS.getValue(sum.groupValues[2])}:${sum.groupValues[3]}"
            sum.groupValues[1]
        }
        if (factor != 1L) filters += "mul:$factor"
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
            else -> taskerNumberTemplate(raw)?.let { ImportedField(it) }
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
                "Tasker Media Control's ${TASKER_MEDIA_COMMAND_NAMES[command] ?: variables[0] ?: command} command has no match here",
            )
        return ActionWithLoss(
            ActionSpec(type = type, label = "Tasker media control"),
            lossyWarning = element.actionAppPackage(2)
                ?.let { "Tasker Media Control aimed at $it was not imported; the key goes to whatever is playing" },
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
        val returnVariable = byIndex[parameterStart + 2].orEmptyIfBlank()
        val stops = !olderLayout && (ints[5] ?: 0) != 0
        return ActionWithLoss(
            ActionSpec(
                type = "task.run",
                label = "Tasker perform task",
                args = buildMap {
                    put("task", strings.firstOrNull().orEmpty().ifBlank { ints[0]?.toString().orEmpty() })
                    byIndex[parameterStart].orEmptyIfBlank()?.let { put("par1", it) }
                    byIndex[parameterStart + 1].orEmptyIfBlank()?.let { put("par2", it) }
                },
            ),
            lossyWarning = joinWarnings(
                returnVariable?.let { "Tasker Perform Task's Return Value Variable was not imported: $it" },
                "Tasker Perform Task's Stop option was not imported".takeIf { stops },
            ),
        )
    }

    /** Variable Add counts from 0 when the variable isn't set yet, the way Tasker's does. */
    private fun taskerVariableAdd(
        code: String,
        byIndex: Map<Int, String>,
        ints: Map<Int, Int>,
        variables: Map<Int, String>,
    ): ActionWithLoss {
        val name = byIndex[0].orEmpty()
        val refusal = when {
            !TASKER_PLAIN_VARIABLE.matches(name) -> "Tasker Variable Add's name $name isn't one plain variable"
            1 in variables -> "Tasker Variable Add adds ${variables[1]}, and this app only adds a fixed number"
            2 in variables || (ints[2] ?: 0) != 0 -> "Tasker Variable Add's Wrap Around has no match here"
            else -> null
        }
        if (refusal != null) return taskerPlaceholder(code, refusal)
        return ActionWithLoss(
            ActionSpec(
                type = "var.set",
                label = "Tasker variable add",
                args = mapOf("name" to name, "value" to "{{ ${name.removePrefix("%")} | default:0 | add:${ints[1] ?: 1} }}"),
            ),
        )
    }

    /** Clearing sets the variable empty, which this app's Is Set checks read as unset. */
    private fun taskerVariableClear(code: String, byIndex: Map<Int, String>, ints: Map<Int, Int>): ActionWithLoss {
        val name = byIndex[0].orEmpty()
        val refusal = when {
            (ints[3] ?: 0) != 0 -> "Tasker Variable Clear's Clear All Variables has no match here"
            !TASKER_PLAIN_VARIABLE.matches(name) -> "Tasker Variable Clear clears $name, and this app clears one variable at a time"
            (ints[2] ?: 0) != 0 && name.any(Char::isUpperCase) ->
                "Tasker Variable Clear only clears local variables there, so it leaves $name alone"
            else -> null
        }
        if (refusal != null) return taskerPlaceholder(code, refusal)
        return ActionWithLoss(
            ActionSpec(type = "var.set", label = "Tasker variable clear", args = mapOf("name" to name, "value" to "")),
        )
    }

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
     * Search Replace runs a regular expression over a variable and writes the result back. Search
     * and Replace With are read untrimmed, because a space there is part of the pattern.
     */
    private fun taskerSearchReplace(code: String, element: Element, byIndex: Map<Int, String>, ints: Map<Int, Int>): ActionWithLoss {
        val name = byIndex[0].orEmpty()
        val search = element.rawActionString(1)
        val replaceWith = element.rawActionString(7)
        val flags = buildString {
            if ((ints[2] ?: 0) != 0) append('i')
            if ((ints[3] ?: 0) != 0) append('m')
        }
        val pattern = if (flags.isEmpty()) search else "(?$flags)$search"
        // A pattern holding a variable is only known when the step runs, so it's checked then.
        val checkable = !TASKER_VARIABLE_REFERENCE.containsMatchIn(pattern)
        val refusal = when {
            !TASKER_PLAIN_VARIABLE.matches(name) -> "Tasker Search Replace's variable $name isn't one plain variable"
            (ints[6] ?: 0) == 0 -> "Tasker Search Replace with Replace Matches off only collects matches, which has no match here"
            (ints[4] ?: 0) != 0 -> "Tasker Search Replace's One Match Only has no match here"
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
                args = mapOf(
                    "source" to "{{ ${name.removePrefix("%")} }}",
                    "pattern" to pattern,
                    "replacement" to replaceWith,
                    "var" to name,
                ),
            ),
            lossyWarning = joinWarnings(
                byIndex[5].orEmptyIfBlank()?.let { "Tasker Search Replace's Store Matches In Array was not imported: $it" },
                "Tasker Search Replace writes \\% for a plain percent sign, and here a variable with that name is still filled in"
                    .takeIf { "\\%" in search || "\\%" in replaceWith },
                "Tasker Search Replace's $ also matches just before a final line break, and here it matches only at the very end"
                    .takeIf { hasEndAnchor(search, multilineFlag = 'm' in flags) },
            ),
        )
    }

    /**
     * True when [pattern] has a `$` that isn't escaped, quoted in `\Q...\E` or inside a character
     * class, read the way Java and RE2 both read it: a `]` straight after `[` or `[^` is a literal.
     * A `$` under an inline `m` flag already matches at every line end, so the pattern behaves the
     * same. The flag holds from a `(?m)` to the end of its group, or inside a `(?m:...)` group, and
     * Multi-Line ([multilineFlag]) sets it for the whole pattern until a `(?-m)` turns it off. A
     * `[:name:]` inside a class is a POSIX class to RE2 and a nested class to Java, so its `]`
     * doesn't end the outer one.
     */
    private fun hasEndAnchor(pattern: String, multilineFlag: Boolean): Boolean {
        val outerMultiline = ArrayDeque<Boolean>()
        var multiline = multilineFlag
        var index = 0
        var inClass = false
        while (index < pattern.length) {
            val char = pattern[index]
            when {
                char == '\\' && pattern.startsWith("Q", index + 1) -> {
                    val end = pattern.indexOf("\\E", index + 2)
                    if (end < 0) return false
                    index = end + 1
                }
                char == '\\' -> index++
                inClass && char == '[' && pattern.startsWith(":", index + 1) -> {
                    val end = pattern.indexOf(":]", index + 2)
                    if (end >= 0) index = end + 1
                }
                inClass -> if (char == ']') inClass = false
                char == '[' -> {
                    inClass = true
                    if (pattern.startsWith("^", index + 1)) index++
                    if (pattern.startsWith("]", index + 1)) index++
                }
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

    /** A called task already runs on its own here, so stopping it hands control back to the caller. */
    private fun taskerReturn(code: String, byIndex: Map<Int, String>, ints: Map<Int, Int>): ActionWithLoss {
        if ((ints[1] ?: 1) == 0) return taskerPlaceholder(code, "Tasker Return with Stop off has no match here")
        return ActionWithLoss(
            ActionSpec(type = "flow.stop", label = "Tasker return"),
            lossyWarning = byIndex[0].orEmptyIfBlank()?.let { "Tasker Return's value $it isn't passed back to the calling task" },
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
     * Only the single-condition case is handled: every real sample examined had exactly one
     * `<Condition>` per list. Tasker does support AND/OR chains of multiple conditions via extra
     * `<Condition>`/`<Bool>` siblings; a multi-condition list degrades to using only the first
     * condition (rather than silently producing "true" the way every ConditionList case did before
     * this fix) and reports that degradation via [ImportedCondition.warning]. Likewise, an `<op>`
     * code outside the known Tasker set (0-9, 12, 13) yields a null expression -- which for flow.if
     * specifically falls back to the old literal-"true" behavior -- but is now reported instead of
     * silently reproduced. Ops 4/5 are Tasker's "Matches Regex"/"Doesn't Match Regex" (verified
     * against Map-Tasker's operator table); this evaluator has no regex condition operator, so
     * they degrade to the plain wildcard match and that approximation is reported the same way.
     */
    private fun Element.parseImportedCondition(): ImportedCondition {
        val conditionList = directChildren("ConditionList").firstOrNull() ?: return ImportedCondition(null, null)
        val conditions = conditionList.directChildren("Condition")
        val condition = conditions.firstOrNull() ?: return ImportedCondition(null, null)
        val lhs = condition.childText("lhs")
        if (lhs.isBlank()) return ImportedCondition(null, null)
        val multiConditionWarning = if (conditions.size > 1) {
            "a multi-condition \"Run only if\" guard was reduced to just its first condition"
        } else {
            null
        }
        val op = condition.childText("op")
        val expression = when (op) {
            "12" -> "$lhs is_set"
            "13" -> "$lhs not_set"
            else -> {
                val token = TASKER_CONDITION_OP_TOKENS[op]
                    ?: return ImportedCondition(
                        expression = null,
                        warning = listOfNotNull(
                            multiConditionWarning,
                            "a \"Run only if\" guard used an unsupported Tasker comparison (op $op) and was dropped",
                        ).joinToString("; "),
                    )
                "$lhs $token ${condition.childText("rhs")}"
            }
        }
        val regexApproximationWarning = if (op == "4" || op == "5") {
            "a regex \"Run only if\" comparison (op $op) was imported as a plain wildcard match"
        } else {
            null
        }
        return ImportedCondition(
            expression,
            listOfNotNull(multiConditionWarning, regexApproximationWarning)
                .joinToString("; ")
                .ifBlank { null },
        )
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

    private val TASKER_SETTING_TABLES = mapOf(0 to "global", 1 to "secure", 2 to "system")

    private val TASKER_MEDIA_COMMANDS = mapOf(
        0 to "track.next",
        1 to "sound.pause",
        2 to "track.previous",
        4 to "sound.stop",
    )

    private val TASKER_MEDIA_COMMAND_NAMES = mapOf(
        3 to "Toggle Pause",
        5 to "Play",
        6 to "Rewind",
        7 to "Fast Forward",
    )

    private val TASKER_PLAIN_VARIABLE = Regex("""%[A-Za-z][A-Za-z0-9_]*""")

    /** Anywhere Tasker would fill in a variable when the action runs. */
    private val TASKER_VARIABLE_REFERENCE = Regex("""%[A-Za-z]""")

    private val TASKER_INTERRUPT_MODES = mapOf(0 to "total_silence", 1 to "priority", 2 to "off", 3 to "alarms")

    private val TASKER_INTERRUPT_MODE_NAMES = mapOf(4 to "Custom", 5 to "Query")

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

    private val TASKER_SIMPLE_SUM = Regex("""%([A-Za-z][A-Za-z0-9_]*)\s*([-+*/])\s*(\d+(?:\.\d+)?)""")

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
    // 3-9 don't appear in that corpus but are included for completeness. 8/9 are the "(Numeric)"
    // variants of =/!=; this evaluator has no numeric-aware equality distinct from string
    // equality, so they map to the same tokens as 0/1. 4/5 are the regex variants of 2/3
    // ("Matches Regex"/"Doesn't Match Regex"); this evaluator has no regex condition operator,
    // so they map to the wildcard tokens and parseImportedCondition reports the approximation
    // as a lossy warning. 12/13 (Is Set/Not Set) are unary and handled separately in
    // parseImportedCondition, not through this map.
    private val TASKER_CONDITION_OP_TOKENS = mapOf(
        "0" to "==",
        "1" to "!=",
        "2" to "~",
        "3" to "!~",
        "4" to "~",
        "5" to "!~",
        "6" to "<",
        "7" to ">",
        "8" to "==",
        "9" to "!=",
    )
}
