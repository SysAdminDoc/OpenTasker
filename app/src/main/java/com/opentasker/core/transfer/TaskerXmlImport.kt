package com.opentasker.core.transfer

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

            val contexts = parseContexts(element, name, lossyWarnings)
            if (contexts.isEmpty()) {
                lossyWarnings += "Profile '$name' was skipped because it has no supported Tasker contexts."
                return@mapIndexedNotNull null
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
                    enabled = !element.childText("off").equals("true", ignoreCase = true),
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

    private fun parseContexts(
        profile: Element,
        profileName: String,
        lossyWarnings: MutableList<String>,
    ): List<ContextSpec> =
        profile.directChildElements().mapNotNull { child ->
            when (child.tagName.lowercase()) {
                "time" -> parseTimeContext(child, profileName, lossyWarnings)
                "day" -> child.childText("days", "day")
                    .takeIf { it.isNotBlank() }
                    ?.let { ContextSpec(ContextType.DAY, mapOf("days" to it)) }
                "application", "app" -> child.childText("package", "pkg", "app")
                    .takeIf { it.isNotBlank() }
                    ?.let { ContextSpec(ContextType.APPLICATION, mapOf("package" to it)) }
                "state" -> parseKeyValueContext(ContextType.STATE, child)
                "event" -> parseKeyValueContext(ContextType.EVENT, child)
                else -> {
                    if (child.tagName !in PROFILE_SCALAR_TAGS) {
                        lossyWarnings += "Profile '$profileName' has unsupported Tasker context '${child.tagName}'."
                    }
                    null
                }
            }
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

    private fun parseKeyValueContext(type: ContextType, element: Element): ContextSpec? {
        val config = buildMap {
            element.childText("event", "name", "key").takeIf { it.isNotBlank() }?.let { key ->
                if (type == ContextType.EVENT) put("event", key) else put("key", key)
            }
            element.childText("value", "val").takeIf { it.isNotBlank() }?.let { put("value", it) }
            element.childText("filter").takeIf { it.isNotBlank() }?.let { put("filter", it) }
        }
        return config.takeIf { it.isNotEmpty() }?.let { ContextSpec(type, it) }
    }

    private fun parseAction(taskName: String, element: Element, actionIndex: Int): ParsedTaskerAction {
        val code = element.childText("code").ifBlank { element.getAttribute("code") }.ifBlank { "unknown" }
        val strings = element.actionStrings()
        val intsByIndex = element.actionIntsByIndex()
        val normalized = code.lowercase()
        val actionWithLoss = when (normalized) {
            "523", "notify", "notify.show" -> ActionWithLoss(
                action = ActionSpec(
                type = "notify.show",
                label = "Tasker notification",
                args = mapOf(
                    "title" to strings.getOrElse(0) { "Tasker notification" },
                    "text" to strings.getOrElse(1) { strings.getOrElse(0) { "Imported from Tasker" } },
                ),
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
            "547", "var.set", "variable.set" -> ActionWithLoss(
                ActionSpec(
                    type = "var.set",
                    label = "Tasker variable set",
                    args = mapOf(
                        "name" to strings.getOrElse(0) { "%IMPORTED" },
                        "value" to strings.getOrElse(1) { "" },
                    ),
                ),
                lossyWarning = null,
            )
            "30", "wait", "flow.wait" -> ActionWithLoss(
                action = ActionSpec(
                type = "flow.wait",
                label = "Tasker wait",
                args = mapOf("millis" to waitMillis(strings, intsByIndex).toString()),
                ),
            )
            "559", "say", "tts.speak" -> ActionWithLoss(
                action = ActionSpec(
                    type = "tts.speak",
                    label = "Tasker speech",
                    args = mapOf("text" to strings.getOrElse(0) { "" }),
                ),
            )
            "61", "vibrate" -> ActionWithLoss(
                action = ActionSpec(
                    type = "vibrate",
                    label = "Tasker vibrate",
                    args = mapOf("millis" to waitMillis(strings, intsByIndex).coerceIn(1L, 10_000L).toString()),
                ),
            )
            "303", "304", "305", "307", "308", "volume.set" -> ActionWithLoss(
                action = ActionSpec(
                    type = "volume.set",
                    label = "Tasker volume",
                    args = mapOf(
                        "stream" to taskerVolumeStream(normalized),
                        "level" to taskerLevel(strings, intsByIndex),
                    ),
                ),
                lossyWarning = "Tasker volume display/sound flags were not imported",
            )
            "810", "brightness", "brightness.set" -> ActionWithLoss(
                ActionSpec(
                    type = "brightness.set",
                    label = "Tasker brightness",
                    args = mapOf("brightness" to taskerScalar(strings, intsByIndex, "auto")),
                ),
            )
            "812", "screen.timeout" -> ActionWithLoss(
                ActionSpec(
                    type = "screen.timeout",
                    label = "Tasker screen timeout",
                    args = mapOf("millis" to waitMillis(strings, intsByIndex).coerceIn(1_000L, 1_800_000L).toString()),
                ),
            )
            "511", "torch", "torch.set" -> ActionWithLoss(
                action = ActionSpec(
                    type = "torch.set",
                    label = "Tasker torch",
                    args = mapOf("state" to taskerOnOff(strings, intsByIndex)),
                ),
            )
            "192", "play", "sound.play" -> ActionWithLoss(
                action = ActionSpec(
                    type = "sound.play",
                    label = "Tasker sound",
                    args = mapOf("path" to strings.getOrElse(0) { "" }),
                ),
            )
            "449", "sound.stop" -> ActionWithLoss(ActionSpec(type = "sound.stop", label = "Tasker stop sound"))
            "451", "track.next" -> ActionWithLoss(ActionSpec(type = "track.next", label = "Tasker next track"))
            "453", "track.previous" -> ActionWithLoss(ActionSpec(type = "track.previous", label = "Tasker previous track"))
            "15", "lock" -> ActionWithLoss(ActionSpec(type = "lock", label = "Tasker lock device"))
            "20", "launch", "app.launch" -> ActionWithLoss(
                ActionSpec(type = "app.launch", label = "Tasker launch app", args = mapOf("package" to strings.getOrElse(0) { "" })),
            )
            "25", "home", "home.go" -> ActionWithLoss(ActionSpec(type = "home.go", label = "Tasker go home"))
            "104", "browse", "url.open" -> ActionWithLoss(
                ActionSpec(type = "url.open", label = "Tasker open URL", args = mapOf("url" to strings.getOrElse(0) { "" })),
            )
            "176", "screenshot", "screenshot.take" -> ActionWithLoss(ActionSpec(type = "screenshot.take", label = "Tasker screenshot"))
            "37", "if", "flow.if" -> ActionWithLoss(
                ActionSpec(type = "flow.if", label = "Tasker if", args = mapOf("condition" to strings.joinToString(" ").ifBlank { "true" })),
            )
            "43", "else", "flow.else" -> ActionWithLoss(ActionSpec(type = "flow.else", label = "Tasker else"))
            "38", "endif", "flow.endif" -> ActionWithLoss(ActionSpec(type = "flow.endif", label = "Tasker end if"))
            "39", "for", "flow.foreach" -> ActionWithLoss(
                ActionSpec(
                    type = "flow.foreach",
                    label = "Tasker for",
                    args = mapOf(
                        "list" to strings.getOrElse(0) { "" },
                        "var" to strings.getOrElse(1) { "item" },
                    ),
                ),
            )
            "40", "endfor", "flow.endfor" -> ActionWithLoss(ActionSpec(type = "flow.endfor", label = "Tasker end for"))
            "137", "stop", "flow.stop" -> ActionWithLoss(ActionSpec(type = "flow.stop", label = "Tasker stop"))
            "130", "perform.task", "task.run" -> ActionWithLoss(
                ActionSpec(
                    type = "task.run",
                    label = "Tasker perform task",
                    args = mapOf("task" to strings.firstOrNull().orEmpty().ifBlank { intsByIndex[0]?.toString().orEmpty() }),
                ),
            )
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

    private fun unsupportedAction(code: String): ActionSpec =
        ActionSpec(
            type = TASKER_UNSUPPORTED_ACTION_ID,
            label = "Unsupported Tasker action $code",
            args = mapOf(
                "taskerCode" to code,
                "summary" to "This Tasker action was preserved as an unsupported placeholder during import.",
            ),
        )

    private fun taskerVolumeStream(code: String): String = when (code) {
        "303" -> "alarm"
        "304" -> "ring"
        "305" -> "notification"
        "308" -> "system"
        else -> "music"
    }

    private fun taskerLevel(strings: List<String>, intsByIndex: Map<Int, Int>): String =
        taskerScalar(strings, intsByIndex, "0")

    private fun taskerScalar(strings: List<String>, intsByIndex: Map<Int, Int>, fallback: String): String =
        strings.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
            ?: intsByIndex[0]?.toString()
            ?: fallback

    private fun taskerOnOff(strings: List<String>, intsByIndex: Map<Int, Int>): String {
        val text = strings.firstOrNull()?.trim()?.lowercase()
        if (text == "on" || text == "off" || text == "toggle") return text
        return if ((intsByIndex[0] ?: 0) == 0) "off" else "on"
    }

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

    /** Int args keyed by their Tasker `sr` argument index, so fixed-position fields keep their unit. */
    private fun Element.actionIntsByIndex(): Map<Int, Int> =
        directChildren("Int").mapNotNull { element ->
            val index = element.argIndex().takeIf { it != Int.MAX_VALUE } ?: return@mapNotNull null
            val value = element.getAttribute("val").ifBlank { element.textContent.orEmpty() }.trim().toIntOrNull()
                ?: return@mapNotNull null
            index to value
        }.toMap()

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
     * silently reproduced.
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
        return ImportedCondition(expression, multiConditionWarning)
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

    private val PROFILE_SCALAR_TAGS = setOf(
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
    // equality, so they map to the same tokens as 0/1. 12/13 (Is Set/Not Set) are unary and
    // handled separately in parseImportedCondition, not through this map.
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
