package com.opentasker.core.transfer

import com.opentasker.core.actions.HttpNetworkConstraint
import com.opentasker.core.contexts.BootContextEvents
import com.opentasker.core.contexts.DaySchedule
import com.opentasker.core.contexts.TimeContextBounds
import com.opentasker.core.contexts.WIFI_CONNECTED_WORDS
import com.opentasker.core.contexts.WIFI_DISCONNECTED_WORDS
import com.opentasker.core.contexts.normalizeStateExpectedValue
import com.opentasker.core.contexts.normalizeStateKey
import com.opentasker.core.contexts.parseStatePredicate
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.ProfileLifetime
import com.opentasker.core.model.ProfileOverflowPolicy
import com.opentasker.core.model.Task
import com.opentasker.core.model.Variable
import com.opentasker.core.diagnostics.ExportRedactionPolicy
import com.opentasker.core.engine.SUB_TASK_REF_KEYS

data class TaskerXmlExportReport(
    val xml: String,
    val exportedTaskCount: Int,
    val exportedProfileCount: Int,
    val exportedVariableCount: Int,
    val skippedActions: List<SkippedExportAction>,
    val warnings: List<String>,
    val redactedActionFieldCount: Int = 0,
)

data class SkippedExportAction(
    val taskName: String,
    val actionType: String,
    val reason: String,
)

object TaskerXmlExporter {

    // Codes and field layouts come from Tasker's own published action table, which Map-Tasker vendors
    // as maptasker/assets/json/task_all_actions.json. There's no entry for "log": Tasker has no such
    // action, and the code once written for it, 905, is Location Mode.
    private val EXPORTED_CONTEXT_KEYS = mapOf(
        ContextType.TIME to setOf("start", "end"),
        ContextType.DAY to setOf("days"),
        ContextType.APPLICATION to setOf("package"),
        ContextType.STATE to setOf("key", "operator", "value", "predicate"),
        // regex only changes how title, body and filter compare, and those keep an event out of the file.
        ContextType.EVENT to setOf("event", "package", "regex"),
    )

    private val TASKER_WEEKDAY_NUMBERS = mapOf(
        "SUN" to 1,
        "MON" to 2,
        "TUE" to 3,
        "WED" to 4,
        "THU" to 5,
        "FRI" to 6,
        "SAT" to 7,
    )

    private val REVERSE_ACTION_MAP: Map<String, String> = mapOf(
        "notify.show" to "523",
        "notify.cancel" to "779",
        "flow.wait" to "30",
        "var.set" to "547",
        "tts.speak" to "559",
        "vibrate" to "61",
        "torch.set" to "511",
        "wifi.toggle" to "425",
        "bluetooth.toggle" to "294",
        "airplane.toggle" to "333",
        "hotspot.toggle" to "113",
        "sound.play" to "445",
        "sound.stop" to "443",
        "sound.pause" to "443",
        "track.next" to "443",
        "track.previous" to "443",
        "home.go" to "25",
        "lock" to "16",
        "app.launch" to "20",
        "app.kill" to "18",
        "url.open" to "104",
        "screenshot.take" to "176",
        "settings.write" to "235",
        "sms.send" to "41",
        "file.read" to "417",
        "file.write" to "410",
        "file.append" to "410",
        "clipboard.set" to "105",
        "flow.if" to "37",
        "flow.else" to "43",
        "flow.endif" to "38",
        "flow.foreach" to "39",
        "flow.endfor" to "40",
        "flow.stop" to "137",
        "task.run" to "130",
        "brightness.set" to "810",
        "screen.timeout" to "812",
        "http.request" to "339",
        "http.get" to "339",
        "http.post" to "339",
        "var.split" to "590",
        "var.join" to "592",
        "array.push" to "355",
        "array.pop" to "356",
        "array.clear" to "357",
    )

    fun export(
        profiles: List<Profile>,
        tasks: List<Task>,
        variables: List<Variable> = emptyList(),
    ): TaskerXmlExportReport {
        val warnings = mutableListOf<String>()
        val skipped = mutableListOf<SkippedExportAction>()
        val redactedActionFields = mutableListOf<String>()
        val taskMap = tasks.associateBy { it.id }
        val redactionContext = ExportRedactionPolicy.Context(
            secretNames = variables.filter(Variable::isSecret).mapTo(linkedSetOf()) { it.name },
            secretValues = variables.filter(Variable::isSecret).mapTo(linkedSetOf()) { it.value },
        )

        val sb = StringBuilder()
        sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.appendLine("""<TaskerData sr="" dvi="1" tv="6.3.13">""")

        tasks.forEach { task ->
            appendTask(sb, task, skipped, redactionContext, redactedActionFields, warnings)
        }

        var exportedProfileCount = 0
        profiles.forEach { profile ->
            if (appendProfile(sb, profile, taskMap, warnings)) exportedProfileCount++
        }

        // These two go first because the file is unusable without acting on them.
        val omittedSecretCount = variables.count { it.isSecret }
        if (redactedActionFields.isNotEmpty()) {
            warnings.add(0, ExportRedactionPolicy.SENSITIVE_ACTION_WARNING)
        }
        if (omittedSecretCount > 0) {
            warnings.add(0, "$omittedSecretCount secret variable(s) were omitted and must be re-entered after import.")
        }
        variables.filterNot { it.isSecret }.forEach { variable ->
            appendVariable(sb, variable)
        }

        sb.appendLine("</TaskerData>")

        return TaskerXmlExportReport(
            xml = sb.toString(),
            exportedTaskCount = tasks.size,
            exportedProfileCount = exportedProfileCount,
            exportedVariableCount = variables.count { !it.isSecret },
            skippedActions = skipped,
            warnings = warnings,
            redactedActionFieldCount = redactedActionFields.size,
        )
    }

    private fun appendTask(
        sb: StringBuilder,
        task: Task,
        skipped: MutableList<SkippedExportAction>,
        redactionContext: ExportRedactionPolicy.Context,
        redactedActionFields: MutableList<String>,
        warnings: MutableList<String>,
    ) {
        sb.appendLine("""  <Task sr="task${task.id}">""")
        sb.appendLine("    <cdate>${System.currentTimeMillis()}</cdate>")
        sb.appendLine("    <id>${task.id}</id>")
        sb.appendLine("    <nme>${escapeXml(task.name)}</nme>")
        sb.appendLine("    <pri>${task.priority}</pri>")

        task.actions.forEachIndexed { index, action ->
            val taskerCode = taskerCodeFor(action)
            if (taskerCode != null) {
                appendAction(sb, index, taskerCode, action, redactionContext, redactedActionFields, warnings)
            } else {
                skipped += SkippedExportAction(
                    taskName = task.name,
                    actionType = action.type,
                    reason = "No Tasker equivalent for action type '${action.type}'",
                )
            }
        }

        sb.appendLine("  </Task>")
    }

    private fun appendAction(
        sb: StringBuilder,
        index: Int,
        code: String,
        action: ActionSpec,
        redactionContext: ExportRedactionPolicy.Context,
        redactedActionFields: MutableList<String>,
        warnings: MutableList<String>,
    ) {
        val sanitized = ExportRedactionPolicy.sanitizeActionArguments(action.type, action.args, redactionContext)
        sanitized.redactedFields.forEach { field -> redactedActionFields += "${action.type}.$field" }
        val args = sanitized.args
        sb.appendLine("""    <Action sr="act$index" ve="7">""")
        sb.appendLine("      <code>$code</code>")

        when (action.type) {
            "notify.show", "notify.cancel" -> {
                if (action.type == "notify.show") {
                    appendStr(sb, 0, args["title"] ?: "Notification")
                    appendStr(sb, 1, args["text"] ?: "")
                    if (!args["tag"].isNullOrBlank()) {
                        warnings += "A notification tagged '${args["tag"]}' lost its tag, because Tasker's Notify has no place for one. A later cancel by that tag won't find it."
                    }
                } else {
                    appendStr(sb, 0, args["tag"] ?: "")
                    args["id"]?.let { appendStr(sb, 1, it) }
                }
            }
            "flow.wait" -> appendWait(sb, args["millis"].orEmpty())
            "var.set" -> {
                appendStr(sb, 0, args["name"] ?: "%VAR")
                appendStr(sb, 1, args["value"] ?: "")
            }
            "tts.speak" -> {
                appendStr(sb, 0, args["text"] ?: "")
                if (args["stream"] == "alarm") {
                    appendStr(sb, 1, "")
                    appendInt(sb, 2, TASKER_ALARM_STREAM.toString())
                }
            }
            "vibrate" -> appendInt(sb, 0, args["millis"] ?: "100")
            "volume.set" -> {
                val level = args["level"] ?: "0"
                if (level == "mute" || level == "unmute") appendStr(sb, 0, level) else appendInt(sb, 0, level)
            }
            "torch.set", "wifi.toggle", "bluetooth.toggle", "airplane.toggle", "hotspot.toggle" ->
                appendSetField(sb, action.type, args["state"] ?: "toggle", warnings)
            "sound.play" -> {
                appendStr(sb, 0, args["path"] ?: "")
                appendInt(sb, 1, "0")
                appendInt(sb, 2, "0")
                if (args["stream"] == "alarm") appendInt(sb, 3, TASKER_ALARM_STREAM.toString())
                if (args["volume"] != null) {
                    warnings += "Tasker's Music Play has no volume of its own, so the volume on 'sound.play' was left out."
                }
            }
            "sound.stop", "sound.pause", "track.next", "track.previous" -> {
                appendInt(sb, 0, TASKER_MEDIA_COMMANDS.getValue(action.type))
                appendInt(sb, 1, "1")
            }
            "app.launch", "app.kill" -> appendApp(sb, 0, args["package"] ?: "")
            "url.open" -> appendStr(sb, 0, args["url"] ?: "")
            "screenshot.take" -> {
                appendStr(sb, 0, args["path"] ?: args["filename"] ?: "")
                appendInt(sb, 1, "0")
            }
            "settings.write" -> {
                val table = args["table"].orEmpty().trim()
                val tableIndex = TASKER_SETTING_TABLE_INDEXES[table.lowercase()]
                if (tableIndex == null) {
                    warnings += "Tasker's Custom Setting only knows global, secure and system, not '$table'."
                }
                appendInt(sb, 0, tableIndex ?: table)
                appendStr(sb, 1, args["key"] ?: "")
                appendStr(sb, 2, args["value"] ?: "")
                appendInt(sb, 3, "0")
                appendStr(sb, 4, "")
            }
            "sms.send" -> {
                appendStr(sb, 0, args["number"] ?: "")
                appendStr(sb, 1, args["message"] ?: "")
                appendInt(sb, 2, "0")
                appendStr(sb, 3, "")
                appendInt(sb, 4, "0")
            }
            "file.read" -> {
                appendStr(sb, 0, args["path"] ?: "")
                appendStr(sb, 1, taskerVariableName(args["var"], "result"))
            }
            "file.write", "file.append" -> {
                appendStr(sb, 0, args["path"] ?: "")
                appendStr(sb, 1, args["text"] ?: args["content"] ?: "")
                appendInt(sb, 2, if (action.type == "file.append") "1" else "0")
            }
            "clipboard.set" -> {
                appendStr(sb, 0, args["text"] ?: "")
                appendInt(sb, 1, "0")
            }
            "flow.if" -> appendStr(sb, 0, args["condition"] ?: "true")
            "flow.foreach" -> {
                val list = args["list"].orEmpty()
                val items = taskerForItems(list)
                if (items == null && list.isNotBlank()) {
                    warnings += "Tasker's For reads '$list' as a list of values, and here it needs a whole array."
                }
                appendStr(sb, 0, taskerVariableName(args["var"], "item"))
                appendStr(sb, 1, items ?: list)
            }
            "task.run" -> {
                appendStr(sb, 0, args["task"] ?: args["name"] ?: args["id"] ?: "")
                appendInt(sb, 1, "%priority")
                appendStr(sb, 2, args["par1"] ?: "")
                appendStr(sb, 3, args["par2"] ?: "")
                appendStr(sb, 4, "")
                appendInt(sb, 5, "0")
                val otherInputs = args.keys - SUB_TASK_REF_KEYS - setOf("par1", "par2")
                if (otherInputs.isNotEmpty()) {
                    warnings += "Tasker's Perform Task only passes par1 and par2, so these inputs were left out: " +
                        otherInputs.sorted().joinToString(", ")
                }
            }
            "brightness.set" -> {
                val level = args["brightness"] ?: args["level"] ?: "auto"
                if (level.trim().equals("auto", ignoreCase = true)) {
                    appendStr(sb, 0, "auto")
                    warnings += "Automatic brightness is a separate action in Tasker, so 'brightness.set' auto won't do anything there."
                } else {
                    appendInt(sb, 0, level)
                }
            }
            "screen.timeout" -> appendDisplayTimeout(sb, args["millis"].orEmpty(), warnings)
            "http.request" -> appendHttpRequest(sb, args, warnings)
            // The older actions run as a GET or a POST through http.request, so they export the same way.
            "http.get" -> appendHttpRequest(
                sb,
                args + mapOf("method" to "GET", "response_var" to (args["var"] ?: args["variable"] ?: "result")),
                warnings,
            )
            "http.post" -> appendHttpRequest(
                sb,
                args + mapOf(
                    "method" to "POST",
                    "body" to (args["data"] ?: args["body"].orEmpty()),
                    "response_var" to (args["var"] ?: args["variable"] ?: "result"),
                ),
                warnings,
            )
            "var.split" -> {
                appendStr(sb, 0, taskerVariableName(args["name"], "VAR"))
                appendStr(sb, 1, args["splitter"].orEmpty())
                appendInt(sb, 2, taskerFlag(args["delete_base"]))
                appendInt(sb, 3, taskerFlag(args["regex"]))
            }
            "var.join" -> {
                appendStr(sb, 0, taskerVariableName(args["name"], "VAR"))
                appendStr(sb, 1, args["joiner"].orEmpty())
                appendInt(sb, 2, taskerFlag(args["delete_parts"]))
            }
            "array.push" -> {
                appendStr(sb, 0, taskerVariableName(args["array"], "arr"))
                appendInt(sb, 1, args["position"]?.ifBlank { null } ?: "1")
                appendStr(sb, 2, args["value"].orEmpty())
                appendInt(sb, 3, taskerFlag(args["fill_spaces"]))
            }
            "array.pop" -> {
                appendStr(sb, 0, taskerVariableName(args["array"], "arr"))
                appendInt(sb, 1, args["position"]?.ifBlank { null } ?: "1")
                appendStr(sb, 2, args["to_var"]?.ifBlank { null }?.let { taskerVariableName(it, it) }.orEmpty())
            }
            "array.clear" -> appendStr(sb, 0, taskerVariableName(args["array"], "arr"))
        }

        appendConditionList(sb, action, redactionContext, warnings)

        sb.appendLine("    </Action>")
    }

    /**
     * Writes the action's "Run only if" guard (ActionSpec.condition) back as the sibling
     * `<ConditionList>` element real Tasker exports use for the same concept, so an imported
     * guard survives an export/import round trip on any action instead of silently becoming
     * unconditional. Guards this app's richer condition syntax cannot squeeze into a single
     * Tasker lhs/op/rhs triple (boolean chains, template expressions, <=/>=) are dropped with a
     * warning rather than exported wrong. A flow.if whose guard differs from its own
     * args["condition"] test is also warned and dropped: on import the ConditionList overwrites
     * that arg, so exporting the guard would replace the if's actual test.
     */
    private fun appendConditionList(
        sb: StringBuilder,
        action: ActionSpec,
        redactionContext: ExportRedactionPolicy.Context,
        warnings: MutableList<String>,
    ) {
        val guard = action.condition?.trim()?.takeIf { it.isNotBlank() } ?: return
        val ifTest = action.args["condition"]?.trim()?.takeIf { it.isNotBlank() }
        if (action.type == "flow.if" && ifTest != null && guard != ifTest) {
            warnings += "A run-only-if guard on a flow.if differs from the if's own test; " +
                "Tasker XML holds one condition per action, so the guard was dropped."
            return
        }
        if (ExportRedactionPolicy.redactText(guard, redactionContext.secretValues) != guard) {
            warnings += "A run-only-if guard on '${action.type}' contains a secret value and was omitted; " +
                "re-enter it after import."
            return
        }
        val condition = taskerConditionFor(guard)
        if (condition == null) {
            warnings += "The run-only-if guard on '${action.type}' ('$guard') has no Tasker " +
                "ConditionList equivalent and was dropped."
            return
        }
        sb.appendLine("""      <ConditionList sr="if">""")
        sb.appendLine("""        <Condition sr="c0" ve="3">""")
        sb.appendLine("          <lhs>${escapeXml(condition.lhs)}</lhs>")
        sb.appendLine("          <op>${condition.op}</op>")
        sb.appendLine("          <rhs>${escapeXml(condition.rhs)}</rhs>")
        sb.appendLine("        </Condition>")
        sb.appendLine("      </ConditionList>")
    }

    private data class TaskerCondition(val lhs: String, val op: String, val rhs: String)

    /**
     * Reverse of TaskerXmlImport.parseImportedCondition: turns this app's condition string back
     * into a single Tasker lhs/op/rhs triple when it has that shape. Anything the triple cannot
     * express (&&/|| chains, template expressions, unsupported operators) returns null and the
     * caller reports the loss.
     */
    private fun taskerConditionFor(condition: String): TaskerCondition? {
        if (condition.contains("&&") || condition.contains("||") || condition.contains("{{")) return null
        // Binary comparisons win over the unary is_set/not_set suffix, matching the evaluator:
        // "%status == is_set" is an equality check against the literal word, not an existence
        // check, so it must export as op 0 rather than op 12.
        for ((token, op) in BINARY_CONDITION_OPS) {
            val marker = " $token "
            val index = condition.indexOf(marker)
            if (index > 0) {
                val lhs = condition.substring(0, index).trim()
                val rhs = condition.substring(index + marker.length).trim()
                if (lhs.isEmpty()) return null
                return TaskerCondition(lhs, op, rhs)
            }
        }
        for ((suffix, op) in UNARY_CONDITION_OPS) {
            if (condition.endsWith(suffix, ignoreCase = true)) {
                val lhs = condition.dropLast(suffix.length).trim()
                if (lhs.isNotEmpty()) return TaskerCondition(lhs, op, "")
            }
        }
        return null
    }

    // App condition operators -> Tasker Condition <op> codes (the import table's canonical
    // inverse: regex ops 4/5 and numeric-equality ops 8/9 already collapsed on import). Two-char
    // tokens are matched before their one-char substrings; every token is whitespace-bounded, so
    // "a <= b" matches nothing here and is reported as unrepresentable instead of exported as "<".
    private val BINARY_CONDITION_OPS = listOf(
        "==" to "0",
        "!=" to "1",
        "!~" to "3",
        "~" to "2",
        "<" to "6",
        ">" to "7",
    )

    private val UNARY_CONDITION_OPS = listOf(
        " is_set" to "12",
        " not_set" to "13",
    )

    /** Writes [profile] and returns true, or returns false when it has no context Tasker would take. */
    private fun appendProfile(
        sb: StringBuilder,
        profile: Profile,
        taskMap: Map<Long, Task>,
        warnings: MutableList<String>,
    ): Boolean {
        // Tasker numbers contexts densely, so one left out doesn't leave a hole.
        val contextXml = StringBuilder()
        val contextWarnings = mutableListOf<String>()
        var written = 0
        var droppedAny = false
        profile.contexts.forEach { context ->
            val exported = exportContext(context, written)
            if (exported != null) {
                written++
                contextXml.appendLine(exported)
                val lost = contextSettingsLost(context)
                if (lost.isNotEmpty()) {
                    contextWarnings += "Profile '${profile.name}' context ${context.type.name} lost settings Tasker XML has no place for: ${lost.joinToString(", ")}."
                }
            } else {
                droppedAny = true
                val settings = context.config.keys.filterNot { it.startsWith("_") }.sorted()
                val inverted = if (context.invert) " (inverted)" else ""
                contextWarnings += "Profile '${profile.name}' context ${context.type.name}$inverted has no Tasker equivalent" +
                    if (settings.isEmpty()) "." else ", so it was left out with its settings: ${settings.joinToString(", ")}."
            }
        }
        // Tasker 6.7.8 fails the whole import ("no Profile found") on a profile with no context.
        if (written == 0) {
            warnings += contextWarnings
            warnings += "Profile '${profile.name}' was left out, since Tasker won't import a profile without a context" +
                if (droppedAny) " and none of this one's has a Tasker equivalent." else "."
            return false
        }

        if (profile.contextExpression != null) {
            warnings += "Profile '${profile.name}' uses nested context grouping; Tasker XML cannot represent the grouping, so only leaf contexts were exported."
        }
        // Tasker needs every context of a profile, so "Monday or Saturday" would read as both at once.
        if (profile.contexts.mapNotNull(ContextSpec::orGroup).groupingBy { it }.eachCount().values.any { it > 1 }) {
            warnings += "Profile '${profile.name}' fires when any one context of a group matches; Tasker profiles need all of them, so the copy fires only when every one does."
        }
        warnings += contextWarnings
        if (droppedAny && profile.enabled) {
            warnings += "Profile '${profile.name}' went out switched off, since on its other contexts alone it would run more often in Tasker than it does here."
        }

        sb.appendLine("""  <Profile sr="prof${profile.id}" ve="2">""")
        sb.appendLine("    <cdate>${System.currentTimeMillis()}</cdate>")
        sb.appendLine("    <id>${profile.id}</id>")
        // Tasker's own mark for a profile that's switched off, read as present or not.
        if (!profile.enabled || droppedAny) sb.appendLine("    <limit>true</limit>")
        sb.appendLine("    <mid0>${profile.enterTaskId}</mid0>")
        profile.exitTaskId?.let {
            sb.appendLine("    <mid1>$it</mid1>")
        }
        sb.appendLine("    <nme>${escapeXml(profile.name)}</nme>")
        if (profile.priority != 0) sb.appendLine("    <priority>${profile.priority}</priority>")
        if (profile.gracePeriodSec > 0) sb.appendLine("    <gracePeriodSec>${profile.gracePeriodSec}</gracePeriodSec>")
        if (profile.lifetime != ProfileLifetime.NEVER) {
            sb.appendLine("    <lifetime>${profile.lifetime.name}</lifetime>")
            profile.expiresAtMs?.let { sb.appendLine("    <expiresAtMs>$it</expiresAtMs>") }
        }
        profile.maxActiveExecutions?.let { sb.appendLine("    <maxActiveExecutions>$it</maxActiveExecutions>") }
        profile.burstLimit?.let { sb.appendLine("    <burstLimit>$it</burstLimit>") }
        if (profile.overflowPolicy != ProfileOverflowPolicy.LOG) {
            sb.appendLine("    <overflowPolicy>${profile.overflowPolicy.name}</overflowPolicy>")
        }
        sb.append(contextXml)
        sb.appendLine("  </Profile>")
        return true
    }

    private fun contextSettingsLost(context: ContextSpec): List<String> {
        val written = EXPORTED_CONTEXT_KEYS[context.type].orEmpty()
        // The matcher reads a blank setting as no filter at all, so it loses nothing.
        return context.config
            .filter { (key, value) ->
                key !in written && !key.startsWith("_") && value.isNotBlank() && !(key == "operator" && value.trim() == "=")
            }
            .keys
            .sorted()
    }

    private fun StringBuilder.appendInvert(context: ContextSpec) {
        if (context.invert) appendLine("      <pin>true</pin>")
    }

    private fun exportContext(context: ContextSpec, index: Int): String? {
        return when (context.type) {
            // Tasker's Time has no invert, so an inverted window stays out.
            ContextType.TIME -> {
                if (context.invert) return null
                val start = timeBoundXml(context.config["start"], "fh", "fm", "fromvar") ?: return null
                val end = timeBoundXml(context.config["end"], "th", "tm", "tovar") ?: return null
                buildString {
                    appendLine("""    <Time sr="con$index">""")
                    start.forEach { appendLine("      $it") }
                    end.forEach { appendLine("      $it") }
                    append("    </Time>")
                }
            }
            // Tasker numbers weekdays from Sunday as 1, one wdayN field per day.
            // Tasker's Day has no invert either, so "not Monday" goes out as the other six days.
            ContextType.DAY -> {
                val chosen = DaySchedule.parse(context.config["days"].orEmpty()).mapNotNull(TASKER_WEEKDAY_NUMBERS::get).toSet()
                if (chosen.isEmpty()) return null
                val days = (if (context.invert) TASKER_WEEKDAY_NUMBERS.values.toSet() - chosen else chosen).sorted()
                if (days.isEmpty()) return null
                buildString {
                    appendLine("""    <Day sr="con$index" ve="2">""")
                    days.forEachIndexed { i, day -> appendLine("      <wday$i>$day</wday$i>") }
                    append("    </Day>")
                }
            }
            // Tasker keeps a label beside each package; the package is all this context has, so it's both.
            // An activity filter would widen to the whole app, so that context stays out.
            ContextType.APPLICATION -> {
                if (contextSettingsLost(context).isNotEmpty()) return null
                val packages = packageList(context)
                if (packages.isEmpty()) return null
                buildString {
                    appendLine("""    <App sr="con$index" ve="2">""")
                    appendInvert(context)
                    appendLine("      <flags>2</flags>")
                    packages.forEachIndexed { i, pkg -> appendLine("      <label$i>${escapeXml(pkg)}</label$i>") }
                    packages.forEachIndexed { i, pkg -> appendLine("      <pkg$i>${escapeXml(pkg)}</pkg$i>") }
                    append("    </App>")
                }
            }
            ContextType.STATE -> taskerState(context)?.let { codedContext("State", index, context, it) }
            ContextType.EVENT -> taskerEvent(context)?.let { codedContext("Event", index, context, it) }
            else -> null
        }
    }

    /** Split the way the matcher splits a package list. */
    private fun packageList(context: ContextSpec): List<String> =
        context.config["package"].orEmpty().split(',', ';').map(String::trim).filter(String::isNotEmpty)

    /** A Tasker state or event: its code, its argument fields, and whether Invert says "not". */
    private data class TaskerContextCode(val code: Int, val args: List<String> = emptyList(), val negated: Boolean = false)

    private fun codedContext(tag: String, index: Int, context: ContextSpec, coded: TaskerContextCode): String = buildString {
        appendLine("""    <$tag sr="con$index" ve="2">""")
        appendLine("      <code>${coded.code}</code>")
        if (context.invert != coded.negated) appendLine("      <pin>true</pin>")
        coded.args.forEach { appendLine("      $it") }
        append("    </$tag>")
    }

    /**
     * The states Tasker has a field for, read the way the matcher reads them. Anything Tasker would
     * hold differently (another comparison, a Wi-Fi name it would take for a pattern) stays out.
     */
    private fun taskerState(context: ContextSpec): TaskerContextCode? {
        if (contextSettingsLost(context).isNotEmpty()) return null
        // A predicate the matcher can't parse never matches, so the key and value beside it don't count.
        val predicate = context.config["predicate"]?.takeIf(String::isNotBlank)
        val (rawKey, rawOperator, rawValue) = if (predicate != null) {
            parseStatePredicate(predicate) ?: return null
        } else {
            Triple(context.config["key"].orEmpty(), context.config["operator"].orEmpty(), context.config["value"].orEmpty())
        }
        val key = normalizeStateKey(rawKey)
        val operator = rawOperator.trim().ifEmpty { "=" }
        val value = rawValue.trim()
        if (value.isEmpty()) return null
        if (key == "battery_level") {
            val level = value.toIntOrNull() ?: return null
            val (from, to) = when (operator) {
                ">=" -> level to 100
                ">" -> level + 1 to 100
                "<=" -> 0 to level
                "<" -> 0 to level - 1
                // The matcher compares "=" as text, so "050" never equals a level of 50.
                "=" -> if (value == level.toString()) level to level else return null
                else -> return null
            }
            if (from !in 0..100 || to !in 0..100 || from > to) return null
            return TaskerContextCode(140, listOf("""<Int sr="arg0" val="$from"/>""", """<Int sr="arg1" val="$to"/>"""))
        }
        if (operator != "=") return null
        return when (key) {
            // Power and Headset Plugged with their source left at Any. Power and "charging" both
            // count a phone that's plugged in but holding its charge.
            "charging", "headphones" -> {
                val expected = normalizeStateExpectedValue(key, value) ?: return null
                TaskerContextCode(if (key == "charging") 10 else 30, listOf("""<Int sr="arg0" val="0"/>"""), negated = expected == "false")
            }
            "screen" -> {
                val expected = normalizeStateExpectedValue(key, value) ?: return null
                TaskerContextCode(123, listOf("""<Int sr="arg0" val="${if (expected == "on") 1 else 0}"/>"""))
            }
            // Wifi Connected with no SSID means any network; Active 2 is Any.
            "wifi" -> {
                val word = value.lowercase()
                val ssid = if (word in WIFI_CONNECTED_WORDS || word in WIFI_DISCONNECTED_WORDS) "" else value
                if (ssid.startsWith("!") || ssid.any { it in "*+/%" }) return null
                TaskerContextCode(
                    160,
                    listOf(
                        """<Str sr="arg0" ve="3">${escapeXml(ssid)}</Str>""",
                        """<Str sr="arg1" ve="3"/>""",
                        """<Str sr="arg2" ve="3"/>""",
                        """<Int sr="arg3" val="2"/>""",
                    ),
                    negated = word in WIFI_DISCONNECTED_WORDS,
                )
            }
            else -> null
        }
    }

    /** Device Boot, and Notification from one app or any app. A filter Tasker can't hold keeps the event out. */
    private fun taskerEvent(context: ContextSpec): TaskerContextCode? {
        if (contextSettingsLost(context).isNotEmpty()) return null
        val packages = packageList(context)
        return when (context.config["event"]?.trim()?.lowercase()) {
            BootContextEvents.EVENT_BOOT_COMPLETED -> TaskerContextCode(411).takeIf { packages.isEmpty() }
            "notification" -> {
                if (packages.size > 1) return null
                val owner = packages.singleOrNull()?.let {
                    """<App sr="arg0"><appPkg>${escapeXml(it)}</appPkg><label>${escapeXml(it)}</label></App>"""
                } ?: """<App sr="arg0"/>"""
                TaskerContextCode(461, listOf(owner) + (1..6).map { """<Str sr="arg$it" ve="3"/>""" } + """<Int sr="arg7" val="0"/>""")
            }
            else -> null
        }
    }

    private fun appendVariable(sb: StringBuilder, variable: Variable) {
        sb.appendLine("  <Variable sr=\"var\">")
        sb.appendLine("    <n>${escapeXml("%" + variable.name)}</n>")
        sb.appendLine("    <v>${escapeXml(variable.value)}</v>")
        sb.appendLine("  </Variable>")
    }

    private fun appendStr(sb: StringBuilder, index: Int, value: String) {
        sb.appendLine("""      <Str sr="arg$index" ve="3">${escapeXml(value)}</Str>""")
    }

    /** A number field holding a variable or a sum is written the way Tasker writes it, in a `<var>`. */
    private fun appendInt(sb: StringBuilder, index: Int, value: String) {
        val trimmed = value.trim()
        if (trimmed.toIntOrNull() != null) {
            sb.appendLine("""      <Int sr="arg$index" val="$trimmed"/>""")
        } else {
            sb.appendLine("""      <Int sr="arg$index"><var>${escapeXml(trimmed)}</var></Int>""")
        }
    }

    private fun appendApp(sb: StringBuilder, index: Int, packageName: String) {
        sb.appendLine("""      <App sr="arg$index">""")
        sb.appendLine("""        <appPkg>${escapeXml(packageName)}</appPkg>""")
        sb.appendLine("""        <label>${escapeXml(packageName)}</label>""")
        sb.appendLine("""      </App>""")
    }

    /** Wait's fields are milliseconds, seconds, minutes, hours and days, each within its own range. */
    private fun appendWait(sb: StringBuilder, raw: String) {
        val text = raw.trim().ifBlank { "1000" }
        val millis = text.toLongOrNull()
        if (millis == null) {
            appendInt(sb, 0, text)
            (1..4).forEach { appendInt(sb, it, "0") }
            return
        }
        appendInt(sb, 0, (millis % 1000).toString())
        appendInt(sb, 1, (millis / 1000 % 60).toString())
        appendInt(sb, 2, (millis / 60_000 % 60).toString())
        appendInt(sb, 3, (millis / 3_600_000 % 24).toString())
        appendInt(sb, 4, (millis / 86_400_000).toString())
    }

    /** Display Timeout's fields are seconds, minutes and hours. */
    private fun appendDisplayTimeout(sb: StringBuilder, raw: String, warnings: MutableList<String>) {
        val text = raw.trim().ifBlank { "60000" }
        val seconds = text.toLongOrNull()?.div(1000)
        if (seconds == null) {
            warnings += "'screen.timeout' holds milliseconds in $text, and Tasker's Display Timeout reads that field as seconds."
            appendInt(sb, 0, text)
            appendInt(sb, 1, "0")
            appendInt(sb, 2, "0")
            return
        }
        appendInt(sb, 0, (seconds % 60).toString())
        appendInt(sb, 1, (seconds / 60 % 60).toString())
        appendInt(sb, 2, (seconds / 3600).toString())
    }

    /**
     * HTTP Request in the fields the Tasker import reads back. Tasker always answers in %http_data,
     * %http_response_code and %http_headers(), and has no setting for the network to wait for.
     */
    private fun appendHttpRequest(sb: StringBuilder, args: Map<String, String>, warnings: MutableList<String>) {
        val method = args["method"].orEmpty().trim().ifBlank { "GET" }.uppercase()
        val methodIndex = TASKER_HTTP_METHOD_INDEXES[method]
        if (methodIndex == null) {
            warnings += "Tasker's HTTP Request has no '$method' method, so it went out as a GET."
        }
        val headers = buildList {
            addAll(args["headers"].orEmpty().lines().map(String::trim).filter(String::isNotEmpty))
            val contentType = args["content_type"]?.trim().orEmpty()
            if (contentType.isNotEmpty() && none { it.startsWith("content-type:", ignoreCase = true) }) {
                add("Content-Type: $contentType")
            }
            args["authorization"]?.trim()?.takeIf(String::isNotEmpty)?.let { add("Authorization: $it") }
        }
        val query = args["query"].orEmpty().lines().map(String::trim).filter(String::isNotEmpty)
        val followsRedirects = args["redirects"]?.trim().equals("same_origin", ignoreCase = true)

        appendInt(sb, 1, (methodIndex ?: 0).toString())
        appendStr(sb, 2, args["url"].orEmpty())
        appendStr(sb, 3, headers.joinToString("\n"))
        appendStr(sb, 4, query.joinToString("\n") { it.replaceFirst("=", ":") })
        appendStr(sb, 5, args["body"].orEmpty())
        appendStr(sb, 6, "")
        appendStr(sb, 7, "")
        appendInt(sb, 8, args["timeout_sec"]?.trim()?.ifBlank { null } ?: "30")
        appendInt(sb, 9, "0")
        appendInt(sb, 10, if (followsRedirects) "1" else "0")
        appendInt(sb, 11, "0")

        val network = when (HttpNetworkConstraint.parse(args["network"])) {
            HttpNetworkConstraint.ANY -> null
            HttpNetworkConstraint.WIFI -> "Wi-Fi"
            HttpNetworkConstraint.CELLULAR -> "mobile data"
            HttpNetworkConstraint.UNMETERED -> "an unmetered network"
        }
        if (network != null) {
            warnings += "Tasker's HTTP Request has no setting to send only over $network, so Tasker sends it over whatever connection the phone has."
        }
        if (followsRedirects) {
            warnings += "Tasker's HTTP Request follows a redirect to any site, where this one only followed redirects that stayed on the same site."
        }
        val responseVariable = args["response_var"]?.trim()?.takeIf(String::isNotEmpty)
            ?: "result".takeIf { args["output_file"].isNullOrBlank() }
        val moved = listOfNotNull(
            responseVariable?.let { taskerVariableName(it, it) }?.takeIf { it != "%http_data" }?.let { "the response from $it to %http_data" },
            args["status_var"]?.trim()?.takeIf(String::isNotEmpty)?.let { taskerVariableName(it, it) }
                ?.takeIf { it != "%http_response_code" }?.let { "the status from $it to %http_response_code" },
            args["headers_var"]?.trim()?.takeIf(String::isNotEmpty)?.let { "the headers from ${taskerVariableName(it, it)} to %http_headers()" },
        )
        if (moved.isNotEmpty()) {
            warnings += "Tasker's HTTP Request puts its results in its own variables, so steps that read them need changing: ${moved.joinToString(", ")}."
        }
        val leftOut = HTTP_ARGS_TASKER_CANNOT_HOLD.filter { !args[it].isNullOrBlank() }
        if (leftOut.isNotEmpty()) {
            warnings += "Tasker's HTTP Request has no place for these, so they were left out: ${leftOut.joinToString(", ")}"
        }
    }

    private fun appendSetField(sb: StringBuilder, type: String, state: String, warnings: MutableList<String>) {
        val value = TASKER_SET_VALUES[state.trim().lowercase()]
        if (value == null) {
            warnings += "Tasker's Set field takes off, on or toggle, so '$type' with '$state' may not work there."
        }
        appendInt(sb, 0, value ?: state)
    }

    private fun taskerVariableName(name: String?, fallback: String): String {
        val trimmed = name?.trim().orEmpty().ifBlank { fallback }
        return if (trimmed.startsWith("%")) trimmed else "%$trimmed"
    }

    /** A checkbox the way Tasker stores one, as 1 or 0. */
    private fun taskerFlag(value: String?): String = if (value?.trim()?.toBooleanStrictOrNull() == true) "1" else "0"

    /** Tasker's For loops over `%name()`. Null when the list isn't one whole array. */
    private fun taskerForItems(list: String): String? {
        val trimmed = list.trim()
        val name = (TEMPLATE_ARRAY.matchEntire(trimmed) ?: BARE_ARRAY.matchEntire(trimmed))?.groupValues?.get(1)
        return name?.let { "%$it()" }
    }

    private val TASKER_SET_VALUES = mapOf("off" to "0", "on" to "1", "toggle" to "2")

    /** Tasker's HTTP Request method spinner, the reverse of the import's table. */
    private val TASKER_HTTP_METHOD_INDEXES = mapOf(
        "GET" to 0,
        "POST" to 1,
        "HEAD" to 2,
        "PUT" to 3,
        "PATCH" to 4,
        "DELETE" to 5,
        "OPTIONS" to 6,
    )

    /** Tasker's file fields take a path anywhere on the phone, where these are inside OpenTasker's own files. */
    private val HTTP_ARGS_TASKER_CANNOT_HOLD = listOf(
        "body_file",
        "output_file",
        "max_response_bytes",
        "call_timeout_sec",
        "connect_timeout_sec",
        "read_timeout_sec",
        "write_timeout_sec",
    )

    private val TASKER_SETTING_TABLE_INDEXES = mapOf("global" to "0", "secure" to "1", "system" to "2")

    private val TASKER_MEDIA_COMMANDS = mapOf(
        "track.next" to "0",
        "sound.pause" to "1",
        "track.previous" to "2",
        "sound.stop" to "4",
    )

    /** Tasker has one volume action per stream, and the stream names are the ones volume.set takes. */
    private val TASKER_VOLUME_CODES = mapOf(
        "alarm" to "303",
        "ring" to "304",
        "ringer" to "304",
        "notification" to "305",
        "voice" to "306",
        "call" to "306",
        "music" to "307",
        "media" to "307",
        "system" to "308",
    )

    private val TEMPLATE_ARRAY = Regex("""\{\{\s*array\.([A-Za-z][A-Za-z0-9_]*)\s*\}\}""")

    private val BARE_ARRAY = Regex("""%?([A-Za-z][A-Za-z0-9_]*)(?:\(\))?""")

    private fun taskerCodeFor(action: ActionSpec): String? {
        if (action.type == "volume.set") {
            return TASKER_VOLUME_CODES[(action.args["stream"] ?: "music").lowercase()]
        }
        return REVERSE_ACTION_MAP[action.type]
    }

    /** One end of a Time window as Tasker writes it: hour and minute fields, or the global it reads (`fromvar`). */
    private fun timeBoundXml(raw: String?, hourTag: String, minuteTag: String, variableTag: String): List<String>? {
        if (raw == null) return null
        TimeContextBounds.variableName(raw)?.let { name -> return listOf("<$variableTag>%$name</$variableTag>") }
        val (hour, minute) = parseClockParts(raw) ?: return null
        return listOf("<$hourTag>$hour</$hourTag>", "<$minuteTag>$minute</$minuteTag>")
    }

    private fun parseClockParts(clock: String): Pair<Int, Int>? {
        val parts = clock.split(":")
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        return hour to minute
    }

    private fun escapeXml(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
}
