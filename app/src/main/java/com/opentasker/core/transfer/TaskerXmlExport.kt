package com.opentasker.core.transfer

import com.opentasker.core.actions.ArrayProcessAction
import com.opentasker.core.actions.BeepTone
import com.opentasker.core.actions.HttpNetworkConstraint
import com.opentasker.core.contexts.BootContextEvents
import com.opentasker.core.contexts.DaySchedule
import com.opentasker.core.contexts.TimeContextBounds
import com.opentasker.core.contexts.WIFI_CONNECTED_WORDS
import com.opentasker.core.contexts.WIFI_DISCONNECTED_WORDS
import com.opentasker.core.contexts.normalizeStateExpectedValue
import com.opentasker.core.contexts.normalizeStateKey
import com.opentasker.core.contexts.parseStatePredicate
import com.opentasker.core.data.VariableConvert
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.ProfileLifetime
import com.opentasker.core.model.ProfileOverflowPolicy
import com.opentasker.core.model.Task
import com.opentasker.core.model.Variable
import com.opentasker.core.model.enterTaskIdOrNull
import com.opentasker.core.diagnostics.ExportRedactionPolicy
import com.opentasker.core.engine.SUB_TASK_REF_KEYS
import com.opentasker.core.engine.SUB_TASK_RETURN_KEY
import com.opentasker.core.engine.variables.VariableExpander
import kotlin.math.roundToLong

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
        "sound.beep" to "171",
        "profile.set" to "159",
        "sound.stop" to "443",
        "sound.pause" to "443",
        "track.next" to "443",
        "track.previous" to "443",
        "sound.toggle" to "443",
        "sound.resume" to "443",
        "track.rewind" to "443",
        "track.fastforward" to "443",
        "home.go" to "25",
        "lock" to "16",
        "reboot" to "59",
        "wake" to "806",
        "app.launch" to "20",
        "app.kill" to "18",
        "url.open" to "104",
        "screenshot.take" to "176",
        "settings.write" to "235",
        "sms.send" to "41",
        "file.read" to "417",
        "file.write" to "410",
        "file.append" to "410",
        "file.delete" to "406",
        "clipboard.set" to "105",
        "flow.if" to "37",
        "flow.else" to "43",
        "flow.endif" to "38",
        "flow.foreach" to "39",
        "flow.endfor" to "40",
        "flow.stop" to "137",
        "flow.goto" to "135",
        "flow.anchor" to "300",
        "task.run" to "130",
        "task.stop" to "137",
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
        "array.process" to "369",
        "var.convert" to "596",
        "text.length" to "345",
        "ringer.set" to "313",
        "clipboard.get" to "402",
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
        val taskNames = tasks.mapTo(mutableSetOf()) { it.name }
        val redactionContext = ExportRedactionPolicy.Context(
            secretNames = variables.filter(Variable::isSecret).mapTo(linkedSetOf()) { it.name },
            secretValues = variables.filter(Variable::isSecret).mapTo(linkedSetOf()) { it.value },
        )

        val sb = StringBuilder()
        sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.appendLine("""<TaskerData sr="" dvi="1" tv="6.3.13">""")

        tasks.forEach { task ->
            appendTask(sb, task, TaskRefs(taskMap, taskNames, task.name), skipped, redactionContext, redactedActionFields, warnings)
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
        taskRefs: TaskRefs,
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

        val firstLabelled = task.actions.withIndex()
            .mapNotNull { (index, action) -> action.label?.trim()?.takeIf(String::isNotEmpty)?.let { it to index } }
            .distinctBy { it.first }
            .toMap()
        val labels = task.actions.filter { it.type == "flow.goto" }
            .mapNotNullTo(mutableSetOf()) { it.args["label"]?.trim()?.takeIf(String::isNotEmpty) }
        val layout = GotoLayout(
            // Tasker numbers only the steps it gets, so a step left out moves every number after it.
            taskerSteps = task.actions.indices.filter { taskerCodeFor(task.actions[it]) != null }
                .withIndex().associate { (taskerIndex, index) -> index to taskerIndex + 1 },
            stepCount = task.actions.size,
            // A label read from a variable could be any of them.
            labels = if (labels.any { VARIABLE_REFERENCE.containsMatchIn(it) }) firstLabelled.keys else labels,
            firstLabelled = firstLabelled,
        )
        task.actions.forEachIndexed { index, action ->
            val taskerCode = taskerCodeFor(action)
            if (taskerCode != null) {
                appendAction(sb, index, taskerCode, action, layout, taskRefs, redactionContext, redactedActionFields, warnings)
            } else {
                skipped += SkippedExportAction(
                    taskName = task.name,
                    actionType = action.type,
                    reason = if (action.type == "task.stop") {
                        "A Stop Task step with no task named would come back from Tasker as a plain Stop"
                    } else {
                        "No Tasker equivalent for action type '${action.type}'"
                    },
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
        layout: GotoLayout,
        taskRefs: TaskRefs,
        redactionContext: ExportRedactionPolicy.Context,
        redactedActionFields: MutableList<String>,
        warnings: MutableList<String>,
    ) {
        val sanitized = ExportRedactionPolicy.sanitizeActionArguments(action.type, action.args, redactionContext)
        sanitized.redactedFields.forEach { field -> redactedActionFields += "${action.type}.$field" }
        val args = sanitized.args
        // Numbered without gaps, the way Tasker writes them, so the step numbers gotos use line up.
        sb.appendLine("""    <Action sr="act${layout.taskerSteps.getValue(index) - 1}" ve="7">""")
        sb.appendLine("      <code>$code</code>")
        // Only a label a goto lands on goes out, on the first step with it, which is where it lands.
        // The rest are mostly the names an import gave each step.
        action.label?.trim()?.takeIf { it in layout.labels && layout.firstLabelled[it] == index }
            ?.let { sb.appendLine("      <label>${escapeXml(it)}</label>") }
        if (!action.enabled) sb.appendLine("      <on>false</on>")

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
                taskerOutputStream(args["stream"])?.let { stream ->
                    appendStr(sb, 1, "")
                    appendInt(sb, 2, stream.toString())
                }
            }
            "vibrate" -> appendInt(sb, 0, args["millis"] ?: "100")
            "volume.set" -> {
                val level = args["level"] ?: "0"
                if (level == "mute" || level == "unmute") appendStr(sb, 0, level) else appendInt(sb, 0, level)
            }
            "torch.set", "wifi.toggle", "bluetooth.toggle", "airplane.toggle", "hotspot.toggle" ->
                appendSetField(sb, action.type, args["state"] ?: "toggle", warnings)
            "profile.set" -> {
                appendStr(sb, 0, args["profile"] ?: "")
                appendSetField(sb, action.type, args["state"] ?: "toggle", warnings, index = 1)
            }
            "sound.play" -> {
                appendStr(sb, 0, args["path"] ?: "")
                appendInt(sb, 1, "0")
                appendInt(sb, 2, "0")
                taskerOutputStream(args["stream"])?.let { appendInt(sb, 3, it.toString()) }
                if (args["volume"] != null) {
                    warnings += "Tasker's Music Play has no volume of its own, so the volume on 'sound.play' was left out."
                }
            }
            "sound.beep" -> {
                appendInt(sb, 0, taskerBeepFrequency(args["frequency"].orEmpty(), warnings))
                appendInt(sb, 1, args["duration"] ?: "")
                appendInt(sb, 2, args["amplitude"].orEmpty().ifBlank { "50" })
                appendInt(sb, 3, (taskerOutputStream(args["stream"]) ?: TASKER_MUSIC_STREAM).toString())
                appendStr(sb, 4, "")
            }
            "sound.stop", "sound.pause", "track.next", "track.previous",
            "sound.toggle", "sound.resume", "track.rewind", "track.fastforward" -> {
                appendInt(sb, 0, TASKER_MEDIA_COMMANDS.getValue(action.type))
                appendInt(sb, 1, "1")
            }
            "app.launch", "app.kill" -> appendApp(sb, 0, args["package"] ?: "")
            "url.open" -> appendStr(sb, 0, args["url"] ?: "")
            // Tasker's type 0 is Normal, the only reboot the step does.
            "reboot" -> appendInt(sb, 0, "0")
            // Tasker's default Block Time.
            "wake" -> appendInt(sb, 0, "500")
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
            "file.delete" -> {
                appendStr(sb, 0, args["path"] ?: "")
                appendInt(sb, 1, "0")
                appendInt(sb, 2, "0")
            }
            "clipboard.set" -> {
                appendStr(sb, 0, args["text"] ?: "")
                appendInt(sb, 1, "0")
            }
            "flow.if" -> appendStr(sb, 0, args["condition"] ?: "true")
            "flow.goto" -> appendGoto(sb, args, layout, warnings)
            "flow.foreach" -> {
                // The same keys, in the same order, the runner reads For Each's array from.
                val list = listOf("list", "in", "array", "items").firstNotNullOfOrNull { args[it]?.takeIf(String::isNotBlank) }.orEmpty()
                val items = taskerForItems(list)
                if (items == null && list.isNotBlank()) {
                    warnings += "Tasker's For reads '$list' as a list of values, and here it needs a whole array."
                }
                appendStr(sb, 0, taskerVariableName(args["var"], "item"))
                appendStr(sb, 1, items ?: list.ifBlank { taskerForValues(args["values"].orEmpty(), warnings) })
            }
            "task.stop" -> {
                appendInt(sb, 0, "0")
                appendStr(sb, 1, taskRefs.taskerName(taskRef(args).orEmpty(), warnings))
            }
            // A Stop with a value is Tasker's Return, with Stop on and no local variables passed back.
            "flow.stop" -> if (code == "126") {
                appendStr(sb, 0, args["value"].orEmpty().trim())
                appendInt(sb, 1, "1")
                appendInt(sb, 2, "0")
                appendInt(sb, 3, "0")
                appendStr(sb, 4, "")
            }
            "task.run" -> {
                appendStr(sb, 0, taskRefs.taskerName(taskRef(args).orEmpty(), warnings))
                appendInt(sb, 1, "%priority")
                appendStr(sb, 2, args["par1"] ?: "")
                appendStr(sb, 3, args["par2"] ?: "")
                appendStr(sb, 4, args[SUB_TASK_RETURN_KEY]?.takeIf(String::isNotBlank)?.let { taskerVariableName(it, "") }.orEmpty())
                appendInt(sb, 5, "0")
                val otherInputs = args.keys - SUB_TASK_REF_KEYS - setOf("par1", "par2", SUB_TASK_RETURN_KEY)
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
            // With Values it's Tasker's Array Set, which splits on plain text and leaves %name alone.
            "var.split" -> if (args["values"] != null) {
                if (taskerFlag(args["regex"]) == "1" || taskerFlag(args["delete_base"]) == "1") {
                    warnings += "Tasker's Array Set splits on plain text and keeps the variable, so the regex and delete settings are dropped."
                }
                appendStr(sb, 0, taskerVariableName(args["name"], "arr"))
                appendStr(sb, 1, args["values"].orEmpty())
                appendStr(sb, 2, args["splitter"].orEmpty())
            } else {
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
            "array.process" -> {
                val type = args["type"].orEmpty().trim()
                val ordinal = ArrayProcessAction.TYPES.indexOf(type).takeIf { it >= 0 }
                if (ordinal == null) {
                    warnings += "Tasker's Array Process has no '$type' operation."
                }
                appendStr(sb, 0, taskerVariableName(args["array"], "arr"))
                appendInt(sb, 1, ordinal?.toString() ?: type)
            }
            "var.convert" -> {
                val function = args["function"].orEmpty().trim()
                val ordinal = VariableConvert.FUNCTIONS.indexOf(function).takeIf { it >= 0 }
                if (ordinal == null) {
                    warnings += "Tasker's Variable Convert has no '$function' conversion."
                }
                appendStr(sb, 0, taskerVariableName(args["name"], "VAR"))
                appendInt(sb, 1, ordinal?.toString() ?: function)
                appendStr(sb, 2, args["var"]?.ifBlank { null }?.let { taskerVariableName(it, it) }.orEmpty())
                appendInt(sb, 3, taskerFlag(args["url_safe"]))
            }
            "text.length" -> {
                appendInt(sb, 0, "0")
                appendStr(sb, 1, args["source"].orEmpty())
                appendStr(sb, 2, taskerVariableName(args["var"] ?: args["variable"], "length"))
            }
            // Ignore DND goes out on, since this app's ringer step leaves Do Not Disturb as
            // Android left it, the way Tasker's does with that box ticked.
            "ringer.set" -> {
                val mode = args["mode"].orEmpty()
                val value = TASKER_SOUND_MODE_VALUES[mode.trim().lowercase()]
                if (value == null) {
                    warnings += "Tasker's Sound Mode takes silent, vibrate or normal, so 'ringer.set' with '$mode' may not work there."
                }
                appendInt(sb, 0, value ?: mode)
                appendInt(sb, 1, "1")
            }
            // Tasker's Get Clipboard has no settings. Its one argument lists the variables it fills
            // for Tasker's variable picker, written here with the one this app's step fills.
            "clipboard.get" -> {
                val name = taskerVariableName(args["var"] ?: args["result"], "Clipboard")
                if (name != "%cl_text") {
                    warnings += "Tasker's Get Clipboard always fills %cl_text, so 'clipboard.get' into '$name' fills %cl_text there instead."
                }
                sb.appendLine("""      <Bundle sr="arg0">""")
                sb.appendLine("""        <Vals sr="val">""")
                sb.appendLine("""          <net.dinglisch.android.tasker.RELEVANT_VARIABLES>&lt;StringArray sr=""&gt;&lt;_array_net.dinglisch.android.tasker.RELEVANT_VARIABLES0&gt;%cl_text""")
                sb.appendLine("Text")
                sb.appendLine("""Current clipboard text&lt;/_array_net.dinglisch.android.tasker.RELEVANT_VARIABLES0&gt;&lt;/StringArray&gt;</net.dinglisch.android.tasker.RELEVANT_VARIABLES>""")
                sb.appendLine("""          <net.dinglisch.android.tasker.RELEVANT_VARIABLES-type>[Ljava.lang.String;</net.dinglisch.android.tasker.RELEVANT_VARIABLES-type>""")
                sb.appendLine("""        </Vals>""")
                sb.appendLine("""      </Bundle>""")
            }
        }

        appendConditionList(sb, action, redactionContext, warnings)

        sb.appendLine("    </Action>")
    }

    /**
     * Writes the action's "Run only if" guard (ActionSpec.condition) back as the sibling
     * `<ConditionList>` element real Tasker exports use for the same concept, so an imported
     * guard survives an export/import round trip on any action instead of silently becoming
     * unconditional. A guard joined with `&&` and `||` goes out as several conditions with Tasker's
     * joiners ([taskerConditionChain]). Guards this can't write as Tasker's list (template
     * expressions, <= and >=, which aren't written as a pair of Tasker conditions, and brackets
     * alternating && and || deeper than its four joiner levels) are dropped with a warning rather
     * than exported wrong. A flow.if whose guard differs from its own
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
        val chain = taskerConditionChain(guard)
        if (chain == null) {
            warnings += "The run-only-if guard on '${action.type}' ('$guard') has no Tasker " +
                "ConditionList equivalent and was dropped."
            return
        }
        // Tasker writes its joiners ahead of the conditions they join.
        sb.appendLine("""      <ConditionList sr="if">""")
        chain.joiners.forEachIndexed { index, joiner -> sb.appendLine("        <bool$index>$joiner</bool$index>") }
        chain.conditions.forEachIndexed { index, condition ->
            sb.appendLine("""        <Condition sr="c$index" ve="3">""")
            sb.appendLine("          <lhs>${escapeXml(condition.lhs)}</lhs>")
            sb.appendLine("          <op>${condition.op}</op>")
            sb.appendLine("          <rhs>${escapeXml(condition.rhs)}</rhs>")
            sb.appendLine("        </Condition>")
        }
        sb.appendLine("      </ConditionList>")
    }

    /**
     * What a task's gotos need: each exported step's Tasker number, the labels they jump to and
     * the first step carrying each label.
     */
    private data class GotoLayout(
        val taskerSteps: Map<Int, Int>,
        val stepCount: Int,
        val labels: Set<String>,
        val firstLabelled: Map<String, Int>,
    )

    /**
     * Tasker's Goto: arg0 0 jumps to the step number in arg1, 1 to the label in arg2, and 2 to 4 to
     * the top or end of the loop, or the End If, the step sits in.
     */
    private fun appendGoto(sb: StringBuilder, args: Map<String, String>, layout: GotoLayout, warnings: MutableList<String>) {
        val to = args["to"]?.trim().orEmpty()
        if (to.isNotEmpty()) {
            val kind = TASKER_GOTO_BLOCK_JUMPS.entries.firstOrNull { it.value == to }?.key
            if (kind != null) {
                appendInt(sb, 0, kind.toString())
                appendInt(sb, 1, "1")
                appendStr(sb, 2, "")
                return
            }
            warnings += "A goto to '$to' has no Tasker match, so it went out with its step number or label."
        }
        val label = args["label"]?.trim().orEmpty()
        if (label.isNotEmpty()) {
            val target = layout.firstLabelled[label]
            when {
                VARIABLE_REFERENCE.containsMatchIn(label) -> Unit
                target == null -> warnings += "A goto to the label '$label' has no step with that label to land on."
                target !in layout.taskerSteps -> warnings += "A goto to the label '$label' lands on a step Tasker didn't get, so Tasker won't find it."
            }
            appendInt(sb, 0, "1")
            appendInt(sb, 1, "1")
            appendStr(sb, 2, label)
            return
        }
        val raw = args["step"]?.trim().orEmpty()
        val number = raw.toIntOrNull()
        val shifted = layout.taskerSteps.size < layout.stepCount
        val step = when {
            number == null -> {
                when {
                    raw.isEmpty() -> warnings += "A goto with no step number or label went out empty, so Tasker won't know where to jump."
                    shifted -> warnings += "A goto to step '$raw' kept its number, but some steps in that task have no Tasker match, so Tasker counts them differently."
                }
                raw
            }
            number !in 1..layout.stepCount -> {
                warnings += "A goto to step $number points outside its task, which has ${layout.stepCount} steps."
                raw
            }
            else -> layout.taskerSteps[number - 1]?.toString() ?: run {
                warnings += "A goto to step $number lands on a step Tasker didn't get, so it was kept as $number."
                raw
            }
        }
        appendInt(sb, 0, "0")
        appendInt(sb, 1, step)
        appendStr(sb, 2, "")
    }

    private data class TaskerCondition(val lhs: String, val op: String, val rhs: String)

    /** Tasker conditions in list order, and the joiner between each one and the next. */
    private data class TaskerConditionChain(val conditions: List<TaskerCondition>, val joiners: List<String>)

    /**
     * Reverse of TaskerXmlImport's joining: writes [guard]'s `||` and `&&` as Tasker joiners, each at
     * the loosest of Or, And, Or2 and And2 that's no looser than the group around it, because
     * Tasker splits its list at the loosest joiner present and each piece at the next. Null when a
     * comparison has no Tasker triple or the brackets alternate deeper than those four levels.
     */
    private fun taskerConditionChain(guard: String, enclosingLevel: Int = -1): TaskerConditionChain? {
        val (operator, parts) = conditionGrammar.conditionParts(guard)
        if (operator == null) {
            return taskerConditionFor(parts.single())?.let { TaskerConditionChain(listOf(it), emptyList()) }
        }
        // A group joined the same way as the one around it shares its level: a && (b && c) is And, And.
        val level = TASKER_JOINERS.indices.firstOrNull { it >= enclosingLevel && TASKER_JOINERS[it].second == operator } ?: return null
        val chains = parts.map { taskerConditionChain(it, level) ?: return null }
        return TaskerConditionChain(
            conditions = chains.flatMap { it.conditions },
            joiners = chains.flatMapIndexed { index, chain ->
                if (index == 0) chain.joiners else listOf(TASKER_JOINERS[level].first) + chain.joiners
            },
        )
    }

    /**
     * Reverse of TaskerXmlImport.parseImportedCondition for one condition: turns this app's
     * comparison back into a Tasker lhs/op/rhs triple when it has that shape. Anything the triple
     * cannot express (&&/|| left in it, template expressions, unsupported operators) returns null
     * and the caller reports the loss.
     */
    private fun taskerConditionFor(condition: String): TaskerCondition? {
        if (condition.contains("&&") || condition.contains("||") || condition.contains("{{")) return null
        // Read the way the evaluator reads it: one operator outside brackets, so `%x ~ (a == b)`
        // matches the pattern `(a == b)`. A comparison wins over the unary is_set/not_set suffix:
        // "%status == is_set" is an equality check against the literal word, so it must export as
        // op 0 rather than op 12.
        conditionGrammar.comparisonParts(condition)?.let { (lhs, token, rhs) ->
            return BINARY_CONDITION_OPS.firstOrNull { it.first == token }?.let { (_, op) -> TaskerCondition(lhs, op, rhs) }
        }
        // The evaluator finds no single operator in an imported pattern like `%html ~ *<b>*`, which
        // Tasker reads as one comparison, so the first space-bounded operator is taken instead.
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
    // inverse: numeric-equality ops 8/9 already collapsed on import). <= and >= have no code, so a
    // guard using one is reported as unrepresentable. For the space-bounded fallback, longer
    // tokens come before their substrings, so "a <= b" matches nothing there instead of "<".
    private val BINARY_CONDITION_OPS = listOf(
        "==" to "0",
        "!=" to "1",
        "!~R" to "5",
        "~R" to "4",
        "!~" to "3",
        "~" to "2",
        "<" to "6",
        ">" to "7",
    )

    private val UNARY_CONDITION_OPS = listOf(
        " is_even" to "10",
        " is_odd" to "11",
        " is_set" to "12",
        " not_set" to "13",
    )

    // Tasker's joiners from loosest to tightest (decompiled 6.7.8, u1), with the operator each one
    // reads as here. Xor and Xor2 have no operator here, so nothing exports as them.
    private val TASKER_JOINERS = listOf("Or" to "||", "And" to "&&", "Or2" to "||", "And2" to "&&")

    /** Reads a guard the way the engine evaluates it. */
    private val conditionGrammar = VariableExpander()

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
        // Like Tasker, a profile with only an exit task has no mid0.
        profile.enterTaskIdOrNull?.let { sb.appendLine("    <mid0>$it</mid0>") }
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

    /**
     * Tasker's Beep plays a quarter of the frequency it stores, so a pitch goes out times 4. The
     * template an import makes from a Tasker variable goes back to that variable.
     */
    private fun taskerBeepFrequency(value: String, warnings: MutableList<String>): String {
        val trimmed = value.trim()
        trimmed.toDoubleOrNull()?.let { hz ->
            if (hz !in 0.0..BeepTone.MAX_FREQUENCY_HZ) {
                warnings += "The frequency '$trimmed' on 'sound.beep' is outside the 0 to 10000 Hz a beep can play."
            }
            // Tasker keeps the pitch in an int, so NaN, infinity or anything past an int stays as typed.
            val pitch = hz * TASKER_BEEP_PITCH_DIVISOR
            return if (pitch in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) pitch.roundToLong().toString() else trimmed
        }
        BEEP_PITCH_TEMPLATE.matchEntire(trimmed)?.let { return "%" + it.groupValues[1] }
        warnings += "Tasker's Beep plays a quarter of the frequency it's given, so the frequency '$trimmed' on 'sound.beep' needs multiplying by 4 in Tasker."
        return trimmed
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

    private fun appendSetField(sb: StringBuilder, type: String, state: String, warnings: MutableList<String>, index: Int = 0) {
        val value = TASKER_SET_VALUES[state.trim().lowercase()]
        if (value == null) {
            warnings += "Tasker's Set field takes off, on or toggle, so '$type' with '$state' may not work there."
        }
        appendInt(sb, index, value ?: state)
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

    /** For Each's values as Tasker's For items, with the chips' `{{ array.x }}` as `%x()` and `{{ x }}` as `%x`. */
    private fun taskerForValues(values: String, warnings: MutableList<String>): String {
        val arrays = TEMPLATE_ARRAY.replace(values.trim()) { "%${it.groupValues[1]}()" }
        // {{ true }} and {{ false }} are the template's own words, not variables.
        val written = TEMPLATE_VARIABLE.replace(arrays) { match ->
            val name = match.groupValues[1]
            if ("global." !in match.value && name in setOf("true", "false")) match.value else "%$name"
        }
        if ("{{" in written) {
            warnings += "Tasker's For reads '$written' as written, so its {{ }} parts won't be filled in."
        }
        return written
    }

    private val TASKER_SET_VALUES = mapOf("off" to "0", "on" to "1", "toggle" to "2")

    private val TASKER_SOUND_MODE_VALUES = mapOf("silent" to "0", "vibrate" to "1", "normal" to "2", "ring" to "2")

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
        "sound.toggle" to "3",
        "sound.stop" to "4",
        "sound.resume" to "5",
        "track.rewind" to "6",
        "track.fastforward" to "7",
    )

    /** Tasker's number for a sound step's stream, or null for Music, which Say and Music Play leave unset. */
    private fun taskerOutputStream(stream: String?): Int? {
        val name = stream?.trim()?.lowercase() ?: return null
        return TASKER_OUTPUT_STREAMS.entries.firstOrNull { it.value == name }?.key
    }

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

    private val BEEP_PITCH_TEMPLATE = Regex("""\{\{\s*([A-Za-z][A-Za-z0-9_]*)\s*\|\s*div:4\s*\}\}""")

    private val TEMPLATE_ARRAY = Regex("""\{\{\s*array\.([A-Za-z][A-Za-z0-9_]*)\s*\}\}""")

    private val TEMPLATE_VARIABLE = Regex("""\{\{\s*(?:global\.)?([A-Za-z][A-Za-z0-9_]*)\s*\}\}""")

    private val BARE_ARRAY = Regex("""%?([A-Za-z][A-Za-z0-9_]*)(?:\(\))?""")

    private val VARIABLE_REFERENCE = Regex("""%[A-Za-z]""")

    private fun taskerCodeFor(action: ActionSpec): String? {
        if (action.type == "volume.set") {
            return TASKER_VOLUME_CODES[(action.args["stream"] ?: "music").lowercase()]
        }
        if (action.type == "var.split" && action.args["values"] != null) return "354"
        if (action.type == "flow.stop" && !action.args["value"].isNullOrBlank()) return "126"
        // Tasker's Stop with no task named ends the task it's in, which isn't what this step does.
        if (action.type == "task.stop" && taskRef(action.args) == null) return null
        return REVERSE_ACTION_MAP[action.type]
    }

    private fun taskRef(args: Map<String, String>): String? =
        SUB_TASK_REF_KEYS.firstNotNullOfOrNull { args[it]?.trim()?.takeIf(String::isNotEmpty) }

    /**
     * The tasks in an export, for writing the task a Perform Task or Stop names. Tasker finds that
     * task by name, so a step that names one here by id would send Tasker looking for a task called
     * "12". Like a run here, a number is read as an id first and then as a name. [caller] is the task
     * whose steps these are, for the warning.
     */
    private class TaskRefs(private val byId: Map<Long, Task>, private val names: Set<String>, private val caller: String) {
        fun taskerName(ref: String, warnings: MutableList<String>): String {
            val id = ref.toLongOrNull() ?: return ref
            byId[id]?.let { return it.name }
            if (ref !in names) {
                warnings += "A step in '$caller' names task id $id, which isn't in this export, so Tasker will look for a task called '$ref'."
            }
            return ref
        }
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
