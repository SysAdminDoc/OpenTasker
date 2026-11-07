package com.opentasker.core.transfer

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

        profiles.forEach { profile ->
            appendProfile(sb, profile, taskMap, warnings)
        }

        val omittedSecretCount = variables.count { it.isSecret }
        if (omittedSecretCount > 0) {
            warnings += "$omittedSecretCount secret variable(s) were omitted and must be re-entered after import."
        }
        if (redactedActionFields.isNotEmpty()) {
            warnings += ExportRedactionPolicy.SENSITIVE_ACTION_WARNING
        }
        variables.filterNot { it.isSecret }.forEach { variable ->
            appendVariable(sb, variable)
        }

        sb.appendLine("</TaskerData>")

        return TaskerXmlExportReport(
            xml = sb.toString(),
            exportedTaskCount = tasks.size,
            exportedProfileCount = profiles.size,
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

    private fun appendProfile(
        sb: StringBuilder,
        profile: Profile,
        taskMap: Map<Long, Task>,
        warnings: MutableList<String>,
    ) {
        sb.appendLine("""  <Profile sr="prof${profile.id}" ve="2">""")
        sb.appendLine("    <cdate>${System.currentTimeMillis()}</cdate>")
        sb.appendLine("    <id>${profile.id}</id>")
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

        if (profile.contextExpression != null) {
            warnings += "Profile '${profile.name}' uses nested context grouping; Tasker XML cannot represent the grouping, so only leaf contexts were exported."
        }

        profile.contexts.forEachIndexed { index, context ->
            val exported = exportContext(context, index)
            if (exported != null) {
                sb.appendLine(exported)
            } else {
                warnings += "Profile '${profile.name}' context ${context.type.name} has no Tasker equivalent."
            }
        }

        sb.appendLine("  </Profile>")
    }

    private fun exportContext(context: ContextSpec, index: Int): String? {
        return when (context.type) {
            ContextType.TIME -> {
                val start = context.config["start"] ?: return null
                val end = context.config["end"] ?: return null
                val (fh, fm) = parseClockParts(start) ?: return null
                val (th, tm) = parseClockParts(end) ?: return null
                buildString {
                    appendLine("""    <Time sr="con$index">""")
                    appendLine("      <fh>$fh</fh>")
                    appendLine("      <fm>$fm</fm>")
                    appendLine("      <th>$th</th>")
                    appendLine("      <tm>$tm</tm>")
                    append("    </Time>")
                }
            }
            ContextType.DAY -> {
                val days = context.config["days"] ?: return null
                buildString {
                    appendLine("""    <Day sr="con$index">""")
                    appendLine("      <days>${escapeXml(days)}</days>")
                    append("    </Day>")
                }
            }
            ContextType.APPLICATION -> {
                val pkg = context.config["package"] ?: return null
                buildString {
                    appendLine("""    <Application sr="con$index">""")
                    appendLine("      <package>${escapeXml(pkg)}</package>")
                    append("    </Application>")
                }
            }
            ContextType.STATE -> {
                val key = context.config["key"] ?: return null
                buildString {
                    appendLine("""    <State sr="con$index">""")
                    appendLine("      <name>${escapeXml(key)}</name>")
                    context.config["value"]?.let { appendLine("      <value>${escapeXml(it)}</value>") }
                    append("    </State>")
                }
            }
            ContextType.EVENT -> {
                val event = context.config["event"] ?: return null
                buildString {
                    appendLine("""    <Event sr="con$index">""")
                    appendLine("      <event>${escapeXml(event)}</event>")
                    context.config["value"]?.let { appendLine("      <value>${escapeXml(it)}</value>") }
                    append("    </Event>")
                }
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

    /** Tasker's For loops over `%name()`. Null when the list isn't one whole array. */
    private fun taskerForItems(list: String): String? {
        val trimmed = list.trim()
        val name = (TEMPLATE_ARRAY.matchEntire(trimmed) ?: BARE_ARRAY.matchEntire(trimmed))?.groupValues?.get(1)
        return name?.let { "%$it()" }
    }

    private val TASKER_SET_VALUES = mapOf("off" to "0", "on" to "1", "toggle" to "2")

    private val TASKER_SETTING_TABLE_INDEXES = mapOf("global" to "0", "secure" to "1", "system" to "2")

    private val TASKER_MEDIA_COMMANDS = mapOf(
        "track.next" to "0",
        "sound.pause" to "1",
        "track.previous" to "2",
        "sound.stop" to "4",
    )

    private val TEMPLATE_ARRAY = Regex("""\{\{\s*array\.([A-Za-z][A-Za-z0-9_]*)\s*\}\}""")

    private val BARE_ARRAY = Regex("""%?([A-Za-z][A-Za-z0-9_]*)(?:\(\))?""")

    private fun taskerCodeFor(action: ActionSpec): String? {
        if (action.type == "volume.set") {
            return if (action.args["stream"].orEmpty().lowercase() in setOf("music", "media")) "307" else null
        }
        return REVERSE_ACTION_MAP[action.type]
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
