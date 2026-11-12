package com.opentasker.core.actions

import androidx.annotation.StringRes
import com.opentasker.app.R
import com.opentasker.core.plugins.locale.LocalePluginContract

/**
 * Metadata describing the arguments required/optional for an Action.
 * Used to build dynamic forms in the UI.
 */
data class ActionField(
    val key: String,                    // argument key in ActionSpec.args
    @get:StringRes val labelRes: Int,   // localized UI label
    val fieldType: FieldType = FieldType.TEXT,
    val required: Boolean = false,
    @get:StringRes val hintRes: Int? = null,
    /**
     * Explicit display sensitivity for this argument. `null` defers to the shared argument-name
     * heuristic in [ActionArgumentSensitivity], so unknown keys still fail closed; `false` marks a
     * structurally useful field the heuristic would over-mask (a variable name, a file path).
     */
    val sensitive: Boolean? = null,
    val options: List<ActionFieldOption> = emptyList(),
    val numberRule: ActionNumberRule? = null,
    val fileRule: ActionFileRule? = null,
    val inputType: ActionValueType? = null,
)

data class ActionFieldOption(
    val value: String,
    @get:StringRes val labelRes: Int,
)

data class ActionNumberRule(
    val kind: ActionNumberKind = ActionNumberKind.INTEGER,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val allowedLiterals: Set<String> = emptySet(),
)

enum class ActionNumberKind { INTEGER, DECIMAL }

data class ActionFileRule(
    val scope: ActionFileScope = ActionFileScope.OPENTASKER,
    /** What Browse offers for a device file; the typed path is never checked against it. */
    val pickerMimeTypes: List<String> = listOf("*/*"),
)

enum class ActionFileScope { OPENTASKER, DEVICE_OR_URI }

enum class FieldType {
    TEXT,           // plain text input
    NUMBER,         // numeric input
    DROPDOWN,       // select from predefined values
    CHECKBOX,       // boolean toggle
    MULTILINE,      // multi-line text area
    TASK,           // stable task-ID picker
    APP,            // validated package input with scoped installed-app picker
    FILE,           // file/URI input with a dedicated picker
}

data class ActionMetadata(
    val id: String,                     // e.g. "notify.show"
    @get:StringRes val nameRes: Int,
    @get:StringRes val descriptionRes: Int,
    @get:StringRes val categoryRes: Int,
    val fields: List<ActionField> = emptyList(),
    val pickerVisible: Boolean = true,
    val outputs: List<ActionOutputDefinition> = emptyList(),
    @get:StringRes val summaryRes: Int? = null,
)

/**
 * Registry of action metadata for UI form generation.
 */
object ActionMetadataRegistry {
    fun register(metadata: ActionMetadata) {
        val definition = ActionCatalog.require(metadata.id)
        val summaryRes = metadata.summaryRes ?: declaredActionSummaryRes(metadata.id)
        definition.bindMetadata(metadata.copy(
            outputs = metadata.outputs.ifEmpty { declaredActionOutputs(metadata.id) },
            summaryRes = summaryRes,
        ))
    }

    fun get(id: String): ActionMetadata? = ActionCatalog.get(id)?.metadataOrNull()

    fun all(): Collection<ActionMetadata> = ActionCatalog.allDefinitions.mapNotNull { it.metadataOrNull() }

    fun byCategory(@StringRes categoryRes: Int): List<ActionMetadata> =
        all().filter { it.categoryRes == categoryRes }
}

/**
 * Completeness declaration for built-in action summaries. Keep this exhaustive so adding a new
 * metadata registration without a summary fails at startup and in the source guard test.
 */
private fun declaredActionSummaryRes(actionId: String): Int = when (actionId) {
    "notify.show",
    "notify.progress",
    "notify.cancel",
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
    "text.length",
    "var.split",
    "var.join",
    "var.convert",
    "array.push",
    "array.pop",
    "array.clear",
    "array.process",
    "tts.speak",
    "flow.wait",
    "profile.set",
    "task.run",
    "task.stop",
    "flow.if",
    "flow.else",
    "flow.endif",
    "flow.foreach",
    "flow.endfor",
    "flow.stop",
    "intent.launch",
    "plugin.locale.fire",
    "plugin.locale.query",
    "script.termux.run",
    "script.shizuku.run",
    "tasker.unsupported",
    "macrodroid.unsupported",
    "wifi.toggle",
    "bluetooth.toggle",
    "brightness.set",
    "volume.set",
    "airplane.toggle",
    "mobile.toggle",
    "hotspot.toggle",
    "aod.set",
    "wifi.scan",
    "vpn.tunnel",
    "screen.timeout",
    "settings.write",
    "dnd.set",
    "ringer.set",
    "torch.set",
    "tile.set",
    "wallpaper.set",
    "app.launch",
    "app.kill",
    "home.go",
    "url.open",
    "sms.send",
    "screenshot.take",
    "file.read",
    "file.write",
    "file.append",
    "file.delete",
    "file.list",
    "http.request",
    "zen.rule.set",
    "zen.rule.clear",
    "app.archive",
    "app.unarchive",
    "shortcut.publish",
    "flow.try",
    "flow.catch",
    "flow.endtry",
    "flow.goto",
    "flow.anchor",
    "state.temporary",
    "ime.info",
    "ime.set",
    "clipboard.get",
    "clipboard.set",
    "contacts.lookup",
    "integration.home_assistant.webhook",
    "mqtt.publish",
    "http.get",
    "http.post",
    "ping",
    "download",
    "wol",
    "sound.play",
    "sound.beep",
    "sound.stop",
    "sound.pause",
    "track.next",
    "track.previous",
    "sound.resume",
    "sound.toggle",
    "track.rewind",
    "track.fastforward",
    "media.mute",
    "vibrate",
    "reboot",
    "lock",
    "screen.off",
    "wake",
    "log",
    -> R.string.action_parameter_summary
    else -> error("No action summary resource declared for $actionId")
}

private fun option(value: String, @StringRes labelRes: Int) = ActionFieldOption(value, labelRes)

private val settingsTableOptions = listOf(
    option("global", R.string.catalog_action_settings_write_table_global),
    option("secure", R.string.catalog_action_settings_write_table_secure),
    option("system", R.string.catalog_action_settings_write_table_system),
)

private val toggleOptions = listOf(
    option("on", R.string.label_on),
    option("off", R.string.label_off),
    option("toggle", R.string.action_option_toggle),
)

private val audioStreamOptions = listOf(
    option("music", R.string.action_option_stream_music),
    option("alarm", R.string.action_option_stream_alarm),
    option("ring", R.string.action_option_stream_ring),
    option("notification", R.string.action_option_stream_notification),
    option("system", R.string.action_option_stream_system),
    option("voice", R.string.action_option_stream_voice),
)

private val audioOutputOptions = listOf(
    option("music", R.string.action_option_stream_music),
    option("alarm", R.string.action_option_stream_alarm),
)

private val temporaryStateTargetOptions = listOf(
    option("brightness.set", R.string.catalog_action_brightness_set_name),
    option("volume.set", R.string.catalog_action_volume_set_name),
    option("ringer.set", R.string.catalog_action_ringer_set_name),
    option("dnd.set", R.string.catalog_action_dnd_set_name),
)

private fun integerRule(
    minimum: Long? = null,
    maximum: Long? = null,
    vararg allowedLiterals: String,
) = ActionNumberRule(
    minimum = minimum?.toDouble(),
    maximum = maximum?.toDouble(),
    allowedLiterals = allowedLiterals.toSet(),
)

private fun decimalRule(minimum: Double? = null, maximum: Double? = null) =
    ActionNumberRule(ActionNumberKind.DECIMAL, minimum, maximum)

private val openTaskerFileRule = ActionFileRule(ActionFileScope.OPENTASKER)
private val deviceFileRule = ActionFileRule(ActionFileScope.DEVICE_OR_URI)
private val deviceImageRule = ActionFileRule(ActionFileScope.DEVICE_OR_URI, pickerMimeTypes = listOf("image/*"))

private val wallpaperTargetOptions = listOf(
    option("both", R.string.action_option_wallpaper_both),
    option("home", R.string.action_option_wallpaper_home),
    option("lock", R.string.action_option_wallpaper_lock),
)

// ============ Built-in Action Metadata ============

fun registerActionMetadata() {
    // Built-in actions
    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "notify.show",
            nameRes = R.string.catalog_action_notify_show_name,
            descriptionRes = R.string.catalog_action_notify_show_description,
            categoryRes = R.string.catalog_category_notification,
            fields = listOf(
                ActionField("title", R.string.catalog_action_notify_show_field_title_label, required = true, hintRes = R.string.catalog_action_notify_show_field_title_hint),
                ActionField("text", R.string.catalog_action_notify_show_field_text_label, FieldType.MULTILINE, hintRes = R.string.catalog_action_notify_show_field_text_hint),
                ActionField("channel", R.string.catalog_action_notify_show_field_channel_label, hintRes = R.string.catalog_action_notify_show_field_channel_hint),
                ActionField("persistent", R.string.catalog_action_notify_show_field_persistent_label, FieldType.CHECKBOX, hintRes = R.string.catalog_action_notify_show_field_persistent_hint),
                ActionField("tag", R.string.catalog_action_notify_show_field_tag_label, hintRes = R.string.catalog_action_notify_show_field_tag_hint),
                ActionField("id", R.string.catalog_action_notify_show_field_id_label, FieldType.NUMBER, hintRes = R.string.catalog_action_notify_show_field_id_hint, numberRule = integerRule(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())),
                ActionField("button1_label", R.string.catalog_action_notify_show_field_button1_label_label, hintRes = R.string.catalog_action_notify_show_field_button1_label_hint),
                ActionField("button1_task_id", R.string.catalog_action_notify_show_field_button1_task_id_label, FieldType.TASK, hintRes = R.string.catalog_action_notify_show_field_button1_task_id_hint),
                ActionField("button1_url", R.string.catalog_action_notify_show_field_button1_url_label, hintRes = R.string.catalog_action_notify_show_field_button1_url_hint),
                ActionField("button2_label", R.string.catalog_action_notify_show_field_button2_label_label, hintRes = R.string.catalog_action_notify_show_field_button2_label_hint),
                ActionField("button2_task_id", R.string.catalog_action_notify_show_field_button2_task_id_label, FieldType.TASK, hintRes = R.string.catalog_action_notify_show_field_button2_task_id_hint),
                ActionField("button2_url", R.string.catalog_action_notify_show_field_button2_url_label, hintRes = R.string.catalog_action_notify_show_field_button2_url_hint),
                ActionField("button3_label", R.string.catalog_action_notify_show_field_button3_label_label, hintRes = R.string.catalog_action_notify_show_field_button3_label_hint),
                ActionField("button3_task_id", R.string.catalog_action_notify_show_field_button3_task_id_label, FieldType.TASK, hintRes = R.string.catalog_action_notify_show_field_button3_task_id_hint),
                ActionField("button3_url", R.string.catalog_action_notify_show_field_button3_url_label, hintRes = R.string.catalog_action_notify_show_field_button3_url_hint),
            )
        )
    )
    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "notify.progress",
            nameRes = R.string.catalog_action_notify_progress_name,
            descriptionRes = R.string.catalog_action_notify_progress_description,
            categoryRes = R.string.catalog_category_notification,
            fields = listOf(
                ActionField("title", R.string.catalog_action_notify_progress_field_title_label, required = true, hintRes = R.string.catalog_action_notify_progress_field_title_hint),
                ActionField("text", R.string.catalog_action_notify_progress_field_text_label, FieldType.MULTILINE, hintRes = R.string.catalog_action_notify_progress_field_text_hint),
                ActionField("progress", R.string.catalog_action_notify_progress_field_progress_label, FieldType.NUMBER, required = true, hintRes = R.string.catalog_action_notify_progress_field_progress_hint, numberRule = integerRule(0, 100)),
                ActionField("segments", R.string.catalog_action_notify_progress_field_segments_label, hintRes = R.string.catalog_action_notify_progress_field_segments_hint),
                ActionField("channel", R.string.catalog_action_notify_progress_field_channel_label, hintRes = R.string.catalog_action_notify_progress_field_channel_hint),
                ActionField("tag", R.string.catalog_action_notify_progress_field_tag_label, hintRes = R.string.catalog_action_notify_progress_field_tag_hint),
                ActionField("id", R.string.catalog_action_notify_progress_field_id_label, FieldType.NUMBER, hintRes = R.string.catalog_action_notify_progress_field_id_hint, numberRule = integerRule(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())),
            ),
        ),
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "notify.cancel",
            nameRes = R.string.catalog_action_notify_cancel_name,
            descriptionRes = R.string.catalog_action_notify_cancel_description,
            categoryRes = R.string.catalog_category_notification,
            fields = listOf(
                ActionField("tag", R.string.catalog_action_notify_cancel_field_tag_label, hintRes = R.string.catalog_action_notify_cancel_field_tag_hint),
                ActionField("id", R.string.catalog_action_notify_cancel_field_id_label, FieldType.NUMBER, hintRes = R.string.catalog_action_notify_cancel_field_id_hint, numberRule = integerRule(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "var.set",
            nameRes = R.string.catalog_action_var_set_name,
            descriptionRes = R.string.catalog_action_var_set_description,
            categoryRes = R.string.catalog_category_variable,
            fields = listOf(
                ActionField("name", R.string.catalog_action_var_set_field_name_label, required = true, hintRes = R.string.catalog_action_var_set_field_name_hint),
                ActionField("value", R.string.catalog_action_var_set_field_value_label, required = true, hintRes = R.string.catalog_action_var_set_field_value_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "var.persist",
            nameRes = R.string.catalog_action_var_persist_name,
            descriptionRes = R.string.catalog_action_var_persist_description,
            categoryRes = R.string.catalog_category_variable,
            fields = listOf(
                ActionField("name", R.string.catalog_action_var_persist_field_name_label, required = true, hintRes = R.string.catalog_action_var_persist_field_name_hint),
                ActionField("global_name", R.string.catalog_action_var_persist_field_global_name_label, hintRes = R.string.catalog_action_var_persist_field_global_name_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "data.read",
            nameRes = R.string.catalog_action_data_read_name,
            descriptionRes = R.string.catalog_action_data_read_description,
            categoryRes = R.string.catalog_category_variable,
            fields = listOf(
                ActionField("source", R.string.catalog_action_data_read_field_source_label, required = true, hintRes = R.string.catalog_action_data_read_field_source_hint),
                ActionField("format", R.string.catalog_action_data_read_field_format_label, FieldType.DROPDOWN, hintRes = R.string.catalog_action_data_read_field_format_hint, options = listOf(option("json", R.string.action_option_json), option("csv", R.string.action_option_csv), option("xml", R.string.action_option_xml), option("html", R.string.action_option_html))),
                ActionField("path", R.string.catalog_action_data_read_field_path_label, hintRes = R.string.catalog_action_data_read_field_path_hint),
                ActionField("var", R.string.catalog_action_data_read_field_var_label, hintRes = R.string.catalog_action_data_read_field_var_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "datetime.format",
            nameRes = R.string.catalog_action_datetime_format_name,
            descriptionRes = R.string.catalog_action_datetime_format_description,
            categoryRes = R.string.catalog_category_variable,
            fields = listOf(
                ActionField("time", R.string.catalog_action_datetime_format_field_time_label, hintRes = R.string.catalog_action_datetime_format_field_time_hint),
                ActionField("format", R.string.catalog_action_datetime_format_field_format_label, hintRes = R.string.catalog_action_datetime_format_field_format_hint),
                ActionField("zone", R.string.catalog_action_datetime_format_field_zone_label, hintRes = R.string.catalog_action_datetime_format_field_zone_hint),
                ActionField("var", R.string.catalog_action_datetime_format_field_var_label, hintRes = R.string.catalog_action_datetime_format_field_var_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "datetime.parse",
            nameRes = R.string.catalog_action_datetime_parse_name,
            descriptionRes = R.string.catalog_action_datetime_parse_description,
            categoryRes = R.string.catalog_category_variable,
            fields = listOf(
                ActionField("text", R.string.catalog_action_datetime_parse_field_text_label, required = true, hintRes = R.string.catalog_action_datetime_parse_field_text_hint),
                ActionField("format", R.string.catalog_action_datetime_parse_field_format_label, required = true, hintRes = R.string.catalog_action_datetime_parse_field_format_hint),
                ActionField("zone", R.string.catalog_action_datetime_parse_field_zone_label, hintRes = R.string.catalog_action_datetime_parse_field_zone_hint),
                ActionField("var", R.string.catalog_action_datetime_parse_field_var_label, hintRes = R.string.catalog_action_datetime_parse_field_var_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "datetime.add",
            nameRes = R.string.catalog_action_datetime_add_name,
            descriptionRes = R.string.catalog_action_datetime_add_description,
            categoryRes = R.string.catalog_category_variable,
            fields = listOf(
                ActionField("time", R.string.catalog_action_datetime_add_field_time_label, hintRes = R.string.catalog_action_datetime_add_field_time_hint),
                ActionField("amount", R.string.catalog_action_datetime_add_field_amount_label, FieldType.NUMBER, required = true, hintRes = R.string.catalog_action_datetime_add_field_amount_hint, numberRule = integerRule()),
                ActionField("unit", R.string.catalog_action_datetime_add_field_unit_label, FieldType.DROPDOWN, required = true, hintRes = R.string.catalog_action_datetime_add_field_unit_hint, options = listOf(option("seconds", R.string.action_option_seconds), option("minutes", R.string.action_option_minutes), option("hours", R.string.action_option_hours), option("days", R.string.action_option_days), option("weeks", R.string.action_option_weeks), option("months", R.string.action_option_months), option("years", R.string.action_option_years))),
                ActionField("var", R.string.catalog_action_datetime_add_field_var_label, hintRes = R.string.catalog_action_datetime_add_field_var_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "text.match",
            nameRes = R.string.catalog_action_text_match_name,
            descriptionRes = R.string.catalog_action_text_match_description,
            categoryRes = R.string.catalog_category_variable,
            fields = listOf(
                ActionField("source", R.string.catalog_action_text_match_field_source_label, required = true),
                ActionField("pattern", R.string.catalog_action_text_match_field_pattern_label, required = true, hintRes = R.string.catalog_action_text_match_field_pattern_hint),
                ActionField("var", R.string.catalog_action_text_match_field_var_label, hintRes = R.string.catalog_action_text_match_field_var_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "text.replace",
            nameRes = R.string.catalog_action_text_replace_name,
            descriptionRes = R.string.catalog_action_text_replace_description,
            categoryRes = R.string.catalog_category_variable,
            fields = listOf(
                ActionField("source", R.string.catalog_action_text_replace_field_source_label, required = true),
                ActionField("pattern", R.string.catalog_action_text_replace_field_pattern_label, required = true),
                ActionField("replacement", R.string.catalog_action_text_replace_field_replacement_label, hintRes = R.string.catalog_action_text_replace_field_replacement_hint),
                ActionField("var", R.string.catalog_action_text_replace_field_var_label, hintRes = R.string.catalog_action_text_replace_field_var_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "text.split",
            nameRes = R.string.catalog_action_text_split_name,
            descriptionRes = R.string.catalog_action_text_split_description,
            categoryRes = R.string.catalog_category_variable,
            fields = listOf(
                ActionField("source", R.string.catalog_action_text_split_field_source_label, required = true),
                ActionField("delimiter", R.string.catalog_action_text_split_field_delimiter_label, hintRes = R.string.catalog_action_text_split_field_delimiter_hint),
                ActionField("pattern", R.string.catalog_action_text_split_field_pattern_label, hintRes = R.string.catalog_action_text_split_field_pattern_hint),
                ActionField("var", R.string.catalog_action_text_split_field_var_label, hintRes = R.string.catalog_action_text_split_field_var_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "text.join",
            nameRes = R.string.catalog_action_text_join_name,
            descriptionRes = R.string.catalog_action_text_join_description,
            categoryRes = R.string.catalog_category_variable,
            fields = listOf(
                ActionField("array", R.string.catalog_action_text_join_field_array_label, required = true, hintRes = R.string.catalog_action_text_join_field_array_hint, inputType = ActionValueType.ARRAY),
                ActionField("delimiter", R.string.catalog_action_text_join_field_delimiter_label, hintRes = R.string.catalog_action_text_join_field_delimiter_hint),
                ActionField("var", R.string.catalog_action_text_join_field_var_label, hintRes = R.string.catalog_action_text_join_field_var_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "text.substring",
            nameRes = R.string.catalog_action_text_substring_name,
            descriptionRes = R.string.catalog_action_text_substring_description,
            categoryRes = R.string.catalog_category_variable,
            fields = listOf(
                ActionField("source", R.string.catalog_action_text_substring_field_source_label, required = true),
                ActionField("start", R.string.catalog_action_text_substring_field_start_label, FieldType.NUMBER, required = true, hintRes = R.string.catalog_action_text_substring_field_start_hint, numberRule = integerRule(0)),
                ActionField("end", R.string.catalog_action_text_substring_field_end_label, FieldType.NUMBER, hintRes = R.string.catalog_action_text_substring_field_end_hint, numberRule = integerRule(0)),
                ActionField("var", R.string.catalog_action_text_substring_field_var_label, hintRes = R.string.catalog_action_text_substring_field_var_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "text.length",
            nameRes = R.string.catalog_action_text_length_name,
            descriptionRes = R.string.catalog_action_text_length_description,
            categoryRes = R.string.catalog_category_variable,
            fields = listOf(
                ActionField("source", R.string.catalog_action_text_length_field_source_label),
                ActionField("var", R.string.catalog_action_text_length_field_var_label, hintRes = R.string.catalog_action_text_length_field_var_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "var.split",
            nameRes = R.string.catalog_action_var_split_name,
            descriptionRes = R.string.catalog_action_var_split_description,
            categoryRes = R.string.catalog_category_variable,
            fields = listOf(
                ActionField("name", R.string.catalog_action_var_split_field_name_label, required = true, hintRes = R.string.catalog_action_var_split_field_name_hint),
                ActionField("values", R.string.catalog_action_var_split_field_values_label, hintRes = R.string.catalog_action_var_split_field_values_hint),
                ActionField("splitter", R.string.catalog_action_var_split_field_splitter_label, hintRes = R.string.catalog_action_var_split_field_splitter_hint),
                ActionField("regex", R.string.catalog_action_var_split_field_regex_label, FieldType.CHECKBOX),
                ActionField("delete_base", R.string.catalog_action_var_split_field_delete_base_label, FieldType.CHECKBOX),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "var.join",
            nameRes = R.string.catalog_action_var_join_name,
            descriptionRes = R.string.catalog_action_var_join_description,
            categoryRes = R.string.catalog_category_variable,
            fields = listOf(
                ActionField("name", R.string.catalog_action_var_join_field_name_label, required = true, hintRes = R.string.catalog_action_var_join_field_name_hint),
                ActionField("joiner", R.string.catalog_action_var_join_field_joiner_label),
                ActionField("delete_parts", R.string.catalog_action_var_join_field_delete_parts_label, FieldType.CHECKBOX),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "var.convert",
            nameRes = R.string.catalog_action_var_convert_name,
            descriptionRes = R.string.catalog_action_var_convert_description,
            categoryRes = R.string.catalog_category_variable,
            fields = listOf(
                ActionField("name", R.string.catalog_action_var_convert_field_name_label, required = true, hintRes = R.string.catalog_action_var_convert_field_name_hint),
                ActionField(
                    "function",
                    R.string.catalog_action_var_convert_field_function_label,
                    FieldType.DROPDOWN,
                    required = true,
                    options = listOf(
                        option("bytes_to_kilobytes", R.string.action_option_convert_bytes_to_kilobytes),
                        option("bytes_to_megabytes", R.string.action_option_convert_bytes_to_megabytes),
                        option("bytes_to_gigabytes", R.string.action_option_convert_bytes_to_gigabytes),
                        option("date_time_to_seconds", R.string.action_option_convert_date_time_to_seconds),
                        option("seconds_to_date_time", R.string.action_option_convert_seconds_to_date_time),
                        option("seconds_to_medium_date_time", R.string.action_option_convert_seconds_to_medium_date_time),
                        option("seconds_to_long_date_time", R.string.action_option_convert_seconds_to_long_date_time),
                        option("html_to_text", R.string.action_option_convert_html_to_text),
                        option("celsius_to_fahrenheit", R.string.action_option_convert_celsius_to_fahrenheit),
                        option("fahrenheit_to_celsius", R.string.action_option_convert_fahrenheit_to_celsius),
                        option("centimetres_to_inches", R.string.action_option_convert_centimetres_to_inches),
                        option("inches_to_centimetres", R.string.action_option_convert_inches_to_centimetres),
                        option("metres_to_feet", R.string.action_option_convert_metres_to_feet),
                        option("feet_to_metres", R.string.action_option_convert_feet_to_metres),
                        option("kilograms_to_pounds", R.string.action_option_convert_kilograms_to_pounds),
                        option("pounds_to_kilograms", R.string.action_option_convert_pounds_to_kilograms),
                        option("kilometres_to_miles", R.string.action_option_convert_kilometres_to_miles),
                        option("miles_to_kilometres", R.string.action_option_convert_miles_to_kilometres),
                        option("url_decode", R.string.action_option_convert_url_decode),
                        option("url_encode", R.string.action_option_convert_url_encode),
                        option("binary_to_decimal", R.string.action_option_convert_binary_to_decimal),
                        option("decimal_to_binary", R.string.action_option_convert_decimal_to_binary),
                        option("hex_to_decimal", R.string.action_option_convert_hex_to_decimal),
                        option("decimal_to_hex", R.string.action_option_convert_decimal_to_hex),
                        option("base64_encode", R.string.action_option_convert_base64_encode),
                        option("base64_decode", R.string.action_option_convert_base64_decode),
                        option("md5_digest", R.string.action_option_convert_md5_digest),
                        option("sha1_digest", R.string.action_option_convert_sha1_digest),
                        option("lower_case", R.string.action_option_convert_lower_case),
                        option("upper_case", R.string.action_option_convert_upper_case),
                        option("upper_case_first", R.string.action_option_convert_upper_case_first),
                        option("json_encode", R.string.action_option_convert_json_encode),
                    ),
                ),
                ActionField("var", R.string.catalog_action_var_convert_field_var_label, hintRes = R.string.catalog_action_var_convert_field_var_hint),
                ActionField("url_safe", R.string.catalog_action_var_convert_field_url_safe_label, FieldType.CHECKBOX),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "array.push",
            nameRes = R.string.catalog_action_array_push_name,
            descriptionRes = R.string.catalog_action_array_push_description,
            categoryRes = R.string.catalog_category_variable,
            fields = listOf(
                ActionField("array", R.string.catalog_action_array_push_field_array_label, required = true, hintRes = R.string.catalog_action_array_push_field_array_hint),
                ActionField("position", R.string.catalog_action_array_push_field_position_label, FieldType.NUMBER, hintRes = R.string.catalog_action_array_push_field_position_hint, numberRule = integerRule(1)),
                ActionField("value", R.string.catalog_action_array_push_field_value_label),
                ActionField("fill_spaces", R.string.catalog_action_array_push_field_fill_spaces_label, FieldType.CHECKBOX, hintRes = R.string.catalog_action_array_push_field_fill_spaces_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "array.pop",
            nameRes = R.string.catalog_action_array_pop_name,
            descriptionRes = R.string.catalog_action_array_pop_description,
            categoryRes = R.string.catalog_category_variable,
            fields = listOf(
                ActionField("array", R.string.catalog_action_array_pop_field_array_label, required = true, hintRes = R.string.catalog_action_array_pop_field_array_hint),
                ActionField("position", R.string.catalog_action_array_pop_field_position_label, FieldType.NUMBER, hintRes = R.string.catalog_action_array_pop_field_position_hint, numberRule = integerRule(1)),
                ActionField("to_var", R.string.catalog_action_array_pop_field_to_var_label, hintRes = R.string.catalog_action_array_pop_field_to_var_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "array.clear",
            nameRes = R.string.catalog_action_array_clear_name,
            descriptionRes = R.string.catalog_action_array_clear_description,
            categoryRes = R.string.catalog_category_variable,
            fields = listOf(
                ActionField("array", R.string.catalog_action_array_clear_field_array_label, required = true, hintRes = R.string.catalog_action_array_clear_field_array_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "array.process",
            nameRes = R.string.catalog_action_array_process_name,
            descriptionRes = R.string.catalog_action_array_process_description,
            categoryRes = R.string.catalog_category_variable,
            fields = listOf(
                ActionField("array", R.string.catalog_action_array_process_field_array_label, required = true, hintRes = R.string.catalog_action_array_process_field_array_hint),
                ActionField(
                    "type",
                    R.string.catalog_action_array_process_field_type_label,
                    FieldType.DROPDOWN,
                    required = true,
                    options = listOf(
                        option("remove_duplicates", R.string.action_option_array_remove_duplicates),
                        option("reverse", R.string.action_option_array_reverse),
                        option("rotate_left", R.string.action_option_array_rotate_left),
                        option("rotate_right", R.string.action_option_array_rotate_right),
                        option("shuffle", R.string.action_option_array_shuffle),
                        option("sort_alpha", R.string.action_option_array_sort_alpha),
                        option("sort_alpha_reverse", R.string.action_option_array_sort_alpha_reverse),
                        option("sort_alpha_caseless", R.string.action_option_array_sort_alpha_caseless),
                        option("sort_alpha_caseless_reverse", R.string.action_option_array_sort_alpha_caseless_reverse),
                        option("sort_shortest_first", R.string.action_option_array_sort_shortest_first),
                        option("sort_longest_first", R.string.action_option_array_sort_longest_first),
                        option("sort_numeric_integer", R.string.action_option_array_sort_numeric_integer),
                        option("sort_numeric_float", R.string.action_option_array_sort_numeric_float),
                        option("squash", R.string.action_option_array_squash),
                    ),
                ),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "tts.speak",
            nameRes = R.string.catalog_action_tts_speak_name,
            descriptionRes = R.string.catalog_action_tts_speak_description,
            categoryRes = R.string.catalog_category_notification,
            fields = listOf(
                ActionField("text", R.string.catalog_action_tts_speak_field_text_label, FieldType.MULTILINE, required = true),
                ActionField("stream", R.string.catalog_action_tts_speak_field_stream_label, FieldType.DROPDOWN, hintRes = R.string.catalog_action_tts_speak_field_stream_hint, options = audioOutputOptions),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "flow.wait",
            nameRes = R.string.catalog_action_flow_wait_name,
            descriptionRes = R.string.catalog_action_flow_wait_description,
            categoryRes = R.string.catalog_category_flow,
            fields = listOf(
                ActionField("millis", R.string.catalog_action_flow_wait_field_millis_label, FieldType.NUMBER, required = true, hintRes = R.string.catalog_action_flow_wait_field_millis_hint, numberRule = integerRule(0, 1_800_000)),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "profile.set",
            nameRes = R.string.catalog_action_profile_set_name,
            descriptionRes = R.string.catalog_action_profile_set_description,
            categoryRes = R.string.catalog_category_flow,
            fields = listOf(
                ActionField("profile", R.string.catalog_action_profile_set_field_profile_label, required = true, hintRes = R.string.catalog_action_profile_set_field_profile_hint),
                ActionField("state", R.string.catalog_action_profile_set_field_state_label, FieldType.DROPDOWN, required = true, options = toggleOptions),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "task.run",
            nameRes = R.string.catalog_action_task_run_name,
            descriptionRes = R.string.catalog_action_task_run_description,
            categoryRes = R.string.catalog_category_flow,
            fields = listOf(
                ActionField("task", R.string.catalog_action_task_run_field_task_label, FieldType.TASK, required = true, hintRes = R.string.catalog_action_task_run_field_task_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "task.stop",
            nameRes = R.string.catalog_action_task_stop_name,
            descriptionRes = R.string.catalog_action_task_stop_description,
            categoryRes = R.string.catalog_category_flow,
            fields = listOf(
                ActionField("task", R.string.catalog_action_task_stop_field_task_label, FieldType.TASK, required = true, hintRes = R.string.catalog_action_task_stop_field_task_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "flow.if",
            nameRes = R.string.catalog_action_flow_if_name,
            descriptionRes = R.string.catalog_action_flow_if_description,
            categoryRes = R.string.catalog_category_flow,
            fields = listOf(
                ActionField("condition", R.string.catalog_action_flow_if_field_condition_label, required = true, hintRes = R.string.catalog_action_flow_if_field_condition_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "flow.else",
            nameRes = R.string.catalog_action_flow_else_name,
            descriptionRes = R.string.catalog_action_flow_else_description,
            categoryRes = R.string.catalog_category_flow,
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "flow.endif",
            nameRes = R.string.catalog_action_flow_endif_name,
            descriptionRes = R.string.catalog_action_flow_endif_description,
            categoryRes = R.string.catalog_category_flow,
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "flow.foreach",
            nameRes = R.string.catalog_action_flow_foreach_name,
            descriptionRes = R.string.catalog_action_flow_foreach_description,
            categoryRes = R.string.catalog_category_flow,
            fields = listOf(
                ActionField("list", R.string.catalog_action_flow_foreach_field_list_label, required = true, hintRes = R.string.catalog_action_flow_foreach_field_list_hint, inputType = ActionValueType.ARRAY),
                ActionField("var", R.string.catalog_action_flow_foreach_field_var_label, hintRes = R.string.catalog_action_flow_foreach_field_var_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "flow.endfor",
            nameRes = R.string.catalog_action_flow_endfor_name,
            descriptionRes = R.string.catalog_action_flow_endfor_description,
            categoryRes = R.string.catalog_category_flow,
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "flow.stop",
            nameRes = R.string.catalog_action_flow_stop_name,
            descriptionRes = R.string.catalog_action_flow_stop_description,
            categoryRes = R.string.catalog_category_flow,
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "intent.launch",
            nameRes = R.string.catalog_action_intent_launch_name,
            descriptionRes = R.string.catalog_action_intent_launch_description,
            categoryRes = R.string.catalog_category_app,
            fields = listOf(
                ActionField("package", R.string.catalog_action_intent_launch_field_package_label, FieldType.APP, required = true, hintRes = R.string.catalog_action_intent_launch_field_package_hint),
                ActionField(
                    "mode",
                    R.string.catalog_action_intent_launch_field_mode_label,
                    FieldType.DROPDOWN,
                    hintRes = R.string.catalog_action_intent_launch_field_mode_hint,
                    options = listOf(
                        option("activity", R.string.action_option_intent_activity),
                        option("broadcast", R.string.action_option_intent_broadcast),
                        option("service", R.string.action_option_intent_service),
                    ),
                ),
                ActionField("component", R.string.catalog_action_intent_launch_field_component_label, hintRes = R.string.catalog_action_intent_launch_field_component_hint),
                ActionField("action", R.string.catalog_action_intent_launch_field_action_label, hintRes = R.string.catalog_action_intent_launch_field_action_hint),
                ActionField("category", R.string.catalog_action_intent_launch_field_category_label, hintRes = R.string.catalog_action_intent_launch_field_category_hint),
                ActionField("uri", R.string.catalog_action_intent_launch_field_uri_label, hintRes = R.string.catalog_action_intent_launch_field_uri_hint),
                ActionField("mime_type", R.string.catalog_action_intent_launch_field_mime_label, hintRes = R.string.catalog_action_intent_launch_field_mime_hint),
                ActionField("flags", R.string.catalog_action_intent_launch_field_flags_label, hintRes = R.string.catalog_action_intent_launch_field_flags_hint),
                ActionField("extras", R.string.catalog_action_intent_launch_field_extras_label, FieldType.MULTILINE, hintRes = R.string.catalog_action_intent_launch_field_extras_hint, sensitive = true),
                ActionField("result_variable", R.string.catalog_action_intent_launch_field_result_variable_label, hintRes = R.string.catalog_action_intent_launch_field_result_variable_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "plugin.locale.fire",
            nameRes = R.string.catalog_action_plugin_locale_fire_name,
            descriptionRes = R.string.catalog_action_plugin_locale_fire_description,
            categoryRes = R.string.catalog_category_plugin,
            fields = listOf(
                ActionField("package", R.string.catalog_action_plugin_locale_fire_field_package_label, FieldType.APP, required = true, hintRes = R.string.catalog_action_plugin_locale_fire_field_package_hint),
                ActionField("bundleJson", R.string.catalog_action_plugin_locale_fire_field_bundlejson_label, FieldType.MULTILINE, hintRes = R.string.catalog_action_plugin_locale_fire_field_bundlejson_hint, sensitive = true),
                ActionField("blurb", R.string.catalog_action_plugin_locale_fire_field_blurb_label, hintRes = R.string.catalog_action_plugin_locale_fire_field_blurb_hint),
                ActionField("timeoutMs", R.string.catalog_action_plugin_locale_fire_field_timeoutms_label, FieldType.NUMBER, hintRes = R.string.catalog_action_plugin_locale_fire_field_timeoutms_hint, numberRule = integerRule(0, LocalePluginContract.MAX_SETTING_WAIT_MS)),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "plugin.locale.query",
            nameRes = R.string.catalog_action_plugin_locale_query_name,
            descriptionRes = R.string.catalog_action_plugin_locale_query_description,
            categoryRes = R.string.catalog_category_plugin,
            fields = listOf(
                ActionField("package", R.string.catalog_action_plugin_locale_query_field_package_label, FieldType.APP, required = true, hintRes = R.string.catalog_action_plugin_locale_query_field_package_hint),
                ActionField("bundleJson", R.string.catalog_action_plugin_locale_query_field_bundlejson_label, FieldType.MULTILINE, hintRes = R.string.catalog_action_plugin_locale_query_field_bundlejson_hint, sensitive = true),
                ActionField("blurb", R.string.catalog_action_plugin_locale_query_field_blurb_label, hintRes = R.string.catalog_action_plugin_locale_query_field_blurb_hint),
                ActionField("timeoutMs", R.string.catalog_action_plugin_locale_query_field_timeoutms_label, FieldType.NUMBER, hintRes = R.string.catalog_action_plugin_locale_query_field_timeoutms_hint, numberRule = integerRule(1_000, 30_000)),
                ActionField("resultVariable", R.string.catalog_action_plugin_locale_query_field_resultvariable_label, hintRes = R.string.catalog_action_plugin_locale_query_field_resultvariable_hint),
                ActionField("requireSatisfied", R.string.catalog_action_plugin_locale_query_field_requiresatisfied_label, FieldType.CHECKBOX),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "script.shizuku.run",
            nameRes = R.string.catalog_action_script_shizuku_run_name,
            descriptionRes = R.string.catalog_action_script_shizuku_run_description,
            categoryRes = R.string.catalog_category_script,
            fields = listOf(
                ActionField("path", R.string.catalog_action_script_shizuku_run_field_path_label, required = true, hintRes = R.string.catalog_action_script_shizuku_run_field_path_hint),
                ActionField("arguments", R.string.catalog_action_script_shizuku_run_field_arguments_label, FieldType.MULTILINE, hintRes = R.string.catalog_action_script_shizuku_run_field_arguments_hint),
                ActionField("capturePrefix", R.string.catalog_action_script_shizuku_run_field_captureprefix_label, hintRes = R.string.catalog_action_script_shizuku_run_field_captureprefix_hint),
                ActionField("timeoutMs", R.string.catalog_action_script_shizuku_run_field_timeoutms_label, FieldType.NUMBER, hintRes = R.string.catalog_action_script_shizuku_run_field_timeoutms_hint, numberRule = integerRule(1_000, 120_000)),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "script.termux.run",
            nameRes = R.string.catalog_action_script_termux_run_name,
            descriptionRes = R.string.catalog_action_script_termux_run_description,
            categoryRes = R.string.catalog_category_script,
            fields = listOf(
                ActionField("executable", R.string.catalog_action_script_termux_run_field_executable_label, required = true, hintRes = R.string.catalog_action_script_termux_run_field_executable_hint),
                ActionField("arguments", R.string.catalog_action_script_termux_run_field_arguments_label, FieldType.MULTILINE, hintRes = R.string.catalog_action_script_termux_run_field_arguments_hint),
                ActionField("workingDirectory", R.string.catalog_action_script_termux_run_field_workingdirectory_label, hintRes = R.string.catalog_action_script_termux_run_field_workingdirectory_hint),
                ActionField("stdin", R.string.catalog_action_script_termux_run_field_stdin_label, FieldType.MULTILINE, hintRes = R.string.catalog_action_script_termux_run_field_stdin_hint, sensitive = true),
                ActionField("capturePrefix", R.string.catalog_action_script_termux_run_field_captureprefix_label, hintRes = R.string.catalog_action_script_termux_run_field_captureprefix_hint),
                ActionField("timeoutMs", R.string.catalog_action_script_termux_run_field_timeoutms_label, FieldType.NUMBER, hintRes = R.string.catalog_action_script_termux_run_field_timeoutms_hint, numberRule = integerRule(1_000, 120_000)),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "tasker.unsupported",
            nameRes = R.string.catalog_action_tasker_unsupported_name,
            descriptionRes = R.string.catalog_action_tasker_unsupported_description,
            categoryRes = R.string.catalog_category_import,
            fields = listOf(
                ActionField("taskerCode", R.string.catalog_action_tasker_unsupported_field_taskercode_label, required = true),
                ActionField("summary", R.string.catalog_action_tasker_unsupported_field_summary_label, FieldType.MULTILINE),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "macrodroid.unsupported",
            nameRes = R.string.catalog_action_macrodroid_unsupported_name,
            descriptionRes = R.string.catalog_action_macrodroid_unsupported_description,
            categoryRes = R.string.catalog_category_import,
            fields = listOf(
                ActionField("classType", R.string.catalog_action_macrodroid_unsupported_field_classtype_label, required = true),
                ActionField("summary", R.string.catalog_action_macrodroid_unsupported_field_summary_label, FieldType.MULTILINE),
            )
        )
    )

    // Settings actions
    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "wifi.toggle",
            nameRes = R.string.catalog_action_wifi_toggle_name,
            descriptionRes = R.string.catalog_action_wifi_toggle_description,
            categoryRes = R.string.catalog_category_settings,
            fields = listOf(
                ActionField("state", R.string.catalog_action_wifi_toggle_field_state_label, FieldType.DROPDOWN, required = true, hintRes = R.string.catalog_action_wifi_toggle_field_state_hint, options = toggleOptions),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "bluetooth.toggle",
            nameRes = R.string.catalog_action_bluetooth_toggle_name,
            descriptionRes = R.string.catalog_action_bluetooth_toggle_description,
            categoryRes = R.string.catalog_category_settings,
            fields = listOf(
                ActionField("state", R.string.catalog_action_bluetooth_toggle_field_state_label, FieldType.DROPDOWN, required = true, hintRes = R.string.catalog_action_bluetooth_toggle_field_state_hint, options = toggleOptions),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "brightness.set",
            nameRes = R.string.catalog_action_brightness_set_name,
            descriptionRes = R.string.catalog_action_brightness_set_description,
            categoryRes = R.string.catalog_category_settings,
            fields = listOf(
                ActionField("brightness", R.string.catalog_action_brightness_set_field_brightness_label, FieldType.NUMBER, required = true, numberRule = integerRule(0, 255, "auto")),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "volume.set",
            nameRes = R.string.catalog_action_volume_set_name,
            descriptionRes = R.string.catalog_action_volume_set_description,
            categoryRes = R.string.catalog_category_settings,
            fields = listOf(
                ActionField("stream", R.string.catalog_action_volume_set_field_stream_label, FieldType.DROPDOWN, required = true, hintRes = R.string.catalog_action_volume_set_field_stream_hint, options = audioStreamOptions),
                ActionField("level", R.string.catalog_action_volume_set_field_level_label, FieldType.NUMBER, required = true, numberRule = integerRule(0, allowedLiterals = arrayOf("mute", "unmute"))),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "airplane.toggle",
            nameRes = R.string.catalog_action_airplane_toggle_name,
            descriptionRes = R.string.catalog_action_airplane_toggle_description,
            categoryRes = R.string.catalog_category_settings,
            fields = listOf(
                ActionField("state", R.string.catalog_action_airplane_toggle_field_state_label, FieldType.DROPDOWN, required = true, hintRes = R.string.catalog_action_airplane_toggle_field_state_hint, options = toggleOptions),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "hotspot.toggle",
            nameRes = R.string.catalog_action_hotspot_toggle_name,
            descriptionRes = R.string.catalog_action_hotspot_toggle_description,
            categoryRes = R.string.catalog_category_settings,
            fields = listOf(
                ActionField("state", R.string.catalog_action_hotspot_toggle_field_state_label, FieldType.DROPDOWN, required = true, hintRes = R.string.catalog_action_hotspot_toggle_field_state_hint, options = toggleOptions),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "vpn.tunnel",
            nameRes = R.string.catalog_action_vpn_tunnel_name,
            descriptionRes = R.string.catalog_action_vpn_tunnel_description,
            categoryRes = R.string.catalog_category_network,
            fields = listOf(
                ActionField(
                    "client",
                    R.string.catalog_action_vpn_tunnel_field_client_label,
                    FieldType.DROPDOWN,
                    required = true,
                    hintRes = R.string.catalog_action_vpn_tunnel_field_client_hint,
                    options = listOf(
                        option("wireguard", R.string.action_option_vpn_wireguard),
                        option("tailscale", R.string.action_option_vpn_tailscale),
                    ),
                ),
                ActionField(
                    "state",
                    R.string.catalog_action_vpn_tunnel_field_state_label,
                    FieldType.DROPDOWN,
                    required = true,
                    hintRes = R.string.catalog_action_vpn_tunnel_field_state_hint,
                    options = listOf(
                        option("up", R.string.action_option_vpn_up),
                        option("down", R.string.action_option_vpn_down),
                    ),
                ),
                ActionField("tunnel", R.string.catalog_action_vpn_tunnel_field_tunnel_label, hintRes = R.string.catalog_action_vpn_tunnel_field_tunnel_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "wifi.scan",
            nameRes = R.string.catalog_action_wifi_scan_name,
            descriptionRes = R.string.catalog_action_wifi_scan_description,
            categoryRes = R.string.catalog_category_settings,
            fields = listOf(
                ActionField("var", R.string.catalog_action_wifi_scan_field_var_label, FieldType.TEXT, required = false, hintRes = R.string.catalog_action_wifi_scan_field_var_hint),
                ActionField("limit", R.string.catalog_action_wifi_scan_field_limit_label, FieldType.NUMBER, required = false, hintRes = R.string.catalog_action_wifi_scan_field_limit_hint, numberRule = integerRule(1, WifiScanAction.MAX_RESULTS.toLong())),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "aod.set",
            nameRes = R.string.catalog_action_aod_set_name,
            descriptionRes = R.string.catalog_action_aod_set_description,
            categoryRes = R.string.catalog_category_settings,
            fields = listOf(
                ActionField("state", R.string.catalog_action_aod_set_field_state_label, FieldType.DROPDOWN, required = true, hintRes = R.string.catalog_action_aod_set_field_state_hint, options = toggleOptions),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "settings.write",
            nameRes = R.string.catalog_action_settings_write_name,
            descriptionRes = R.string.catalog_action_settings_write_description,
            categoryRes = R.string.catalog_category_settings,
            fields = listOf(
                ActionField(
                    "table",
                    R.string.catalog_action_settings_write_field_table_label,
                    FieldType.DROPDOWN,
                    required = true,
                    hintRes = R.string.catalog_action_settings_write_field_table_hint,
                    options = settingsTableOptions,
                ),
                ActionField(
                    "key",
                    R.string.catalog_action_settings_write_field_key_label,
                    FieldType.TEXT,
                    required = true,
                    hintRes = R.string.catalog_action_settings_write_field_key_hint,
                    sensitive = false,
                ),
                ActionField(
                    "value",
                    R.string.catalog_action_settings_write_field_value_label,
                    FieldType.TEXT,
                    required = true,
                    hintRes = R.string.catalog_action_settings_write_field_value_hint,
                ),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "mobile.toggle",
            nameRes = R.string.catalog_action_mobile_toggle_name,
            descriptionRes = R.string.catalog_action_mobile_toggle_description,
            categoryRes = R.string.catalog_category_settings,
            fields = listOf(
                ActionField("state", R.string.catalog_action_mobile_toggle_field_state_label, FieldType.DROPDOWN, required = true, hintRes = R.string.catalog_action_mobile_toggle_field_state_hint, options = toggleOptions),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "screen.timeout",
            nameRes = R.string.catalog_action_screen_timeout_name,
            descriptionRes = R.string.catalog_action_screen_timeout_description,
            categoryRes = R.string.catalog_category_settings,
            fields = listOf(
                ActionField("millis", R.string.catalog_action_screen_timeout_field_millis_label, FieldType.NUMBER, required = true, hintRes = R.string.catalog_action_screen_timeout_field_millis_hint, numberRule = integerRule(1_000, 1_800_000)),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "dnd.set",
            nameRes = R.string.catalog_action_dnd_set_name,
            descriptionRes = R.string.catalog_action_dnd_set_description,
            categoryRes = R.string.catalog_category_settings,
            fields = listOf(
                ActionField("mode", R.string.catalog_action_dnd_set_field_mode_label, FieldType.DROPDOWN, required = true, hintRes = R.string.catalog_action_dnd_set_field_mode_hint, options = listOf(option("off", R.string.label_off), option("priority", R.string.action_option_dnd_priority), option("alarms", R.string.action_option_dnd_alarms), option("total_silence", R.string.action_option_dnd_total_silence))),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "ringer.set",
            nameRes = R.string.catalog_action_ringer_set_name,
            descriptionRes = R.string.catalog_action_ringer_set_description,
            categoryRes = R.string.catalog_category_settings,
            fields = listOf(
                ActionField("mode", R.string.catalog_action_ringer_set_field_mode_label, FieldType.DROPDOWN, required = true, hintRes = R.string.catalog_action_ringer_set_field_mode_hint, options = listOf(option("normal", R.string.action_option_ringer_normal), option("vibrate", R.string.action_option_ringer_vibrate), option("silent", R.string.action_option_ringer_silent))),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "torch.set",
            nameRes = R.string.catalog_action_torch_set_name,
            descriptionRes = R.string.catalog_action_torch_set_description,
            categoryRes = R.string.catalog_category_settings,
            fields = listOf(
                ActionField("state", R.string.catalog_action_torch_set_field_state_label, FieldType.DROPDOWN, required = true, hintRes = R.string.catalog_action_torch_set_field_state_hint, options = toggleOptions),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "tile.set",
            nameRes = R.string.catalog_action_tile_set_name,
            descriptionRes = R.string.catalog_action_tile_set_description,
            categoryRes = R.string.catalog_category_settings,
            fields = listOf(
                ActionField("slot", R.string.catalog_action_tile_set_field_slot_label, FieldType.NUMBER, required = true, hintRes = R.string.catalog_action_tile_set_field_slot_hint, numberRule = integerRule(1, 4)),
                ActionField("state", R.string.catalog_action_tile_set_field_state_label, FieldType.DROPDOWN, required = true, hintRes = R.string.catalog_action_tile_set_field_state_hint, options = listOf(option("active", R.string.action_option_active), option("inactive", R.string.action_option_inactive))),
                ActionField("label", R.string.catalog_action_tile_set_field_label_label, required = false, hintRes = R.string.catalog_action_tile_set_field_label_hint),
                ActionField("subtitle", R.string.catalog_action_tile_set_field_subtitle_label, required = false, hintRes = R.string.catalog_action_tile_set_field_subtitle_hint),
                ActionField("icon", R.string.catalog_action_tile_set_field_icon_label, required = false, hintRes = R.string.catalog_action_tile_set_field_icon_hint, options = listOf(option("play", R.string.action_option_tile_icon_play), option("star", R.string.action_option_tile_icon_star), option("settings", R.string.action_option_tile_icon_settings), option("bolt", R.string.action_option_tile_icon_bolt))),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "wallpaper.set",
            nameRes = R.string.catalog_action_wallpaper_set_name,
            descriptionRes = R.string.catalog_action_wallpaper_set_description,
            categoryRes = R.string.catalog_category_settings,
            fields = listOf(
                ActionField("path", R.string.catalog_action_wallpaper_set_field_path_label, FieldType.FILE, required = true, hintRes = R.string.catalog_action_wallpaper_set_field_path_hint, fileRule = deviceImageRule),
                ActionField("target", R.string.catalog_action_wallpaper_set_field_target_label, FieldType.DROPDOWN, hintRes = R.string.catalog_action_wallpaper_set_field_target_hint, options = wallpaperTargetOptions),
            )
        )
    )

    // App actions
    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "app.launch",
            nameRes = R.string.catalog_action_app_launch_name,
            descriptionRes = R.string.catalog_action_app_launch_description,
            categoryRes = R.string.catalog_category_app,
            fields = listOf(
                ActionField("package", R.string.catalog_action_app_launch_field_package_label, FieldType.APP, required = true, hintRes = R.string.catalog_action_app_launch_field_package_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "app.kill",
            nameRes = R.string.catalog_action_app_kill_name,
            descriptionRes = R.string.catalog_action_app_kill_description,
            categoryRes = R.string.catalog_category_app,
            fields = listOf(
                ActionField("package", R.string.catalog_action_app_kill_field_package_label, FieldType.APP, required = true),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "home.go",
            nameRes = R.string.catalog_action_home_go_name,
            descriptionRes = R.string.catalog_action_home_go_description,
            categoryRes = R.string.catalog_category_app,
            fields = emptyList()
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "url.open",
            nameRes = R.string.catalog_action_url_open_name,
            descriptionRes = R.string.catalog_action_url_open_description,
            categoryRes = R.string.catalog_category_app,
            fields = listOf(
                ActionField("url", R.string.catalog_action_url_open_field_url_label, required = true, hintRes = R.string.catalog_action_url_open_field_url_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "sms.send",
            nameRes = R.string.catalog_action_sms_send_name,
            descriptionRes = R.string.catalog_action_sms_send_description,
            categoryRes = R.string.catalog_category_app,
            fields = listOf(
                ActionField("number", R.string.catalog_action_sms_send_field_number_label, required = true),
                ActionField("message", R.string.catalog_action_sms_send_field_message_label, FieldType.MULTILINE, required = true, sensitive = true),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "screenshot.take",
            nameRes = R.string.catalog_action_screenshot_take_name,
            descriptionRes = R.string.catalog_action_screenshot_take_description,
            categoryRes = R.string.catalog_category_app,
            fields = listOf(
                ActionField("path", R.string.catalog_action_screenshot_take_field_path_label, FieldType.FILE, hintRes = R.string.catalog_action_screenshot_take_field_path_hint, fileRule = deviceFileRule),
            )
        )
    )

    // File actions
    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "file.read",
            nameRes = R.string.catalog_action_file_read_name,
            descriptionRes = R.string.catalog_action_file_read_description,
            categoryRes = R.string.catalog_category_file,
            fields = listOf(
                ActionField("path", R.string.catalog_action_file_read_field_path_label, FieldType.FILE, required = true, fileRule = openTaskerFileRule),
                ActionField("var", R.string.catalog_action_file_read_field_var_label, required = true, hintRes = R.string.catalog_action_file_read_field_var_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "file.write",
            nameRes = R.string.catalog_action_file_write_name,
            descriptionRes = R.string.catalog_action_file_write_description,
            categoryRes = R.string.catalog_category_file,
            fields = listOf(
                ActionField("path", R.string.catalog_action_file_write_field_path_label, FieldType.FILE, required = true, fileRule = openTaskerFileRule),
                ActionField("text", R.string.catalog_action_file_write_field_text_label, FieldType.MULTILINE, required = true),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "file.append",
            nameRes = R.string.catalog_action_file_append_name,
            descriptionRes = R.string.catalog_action_file_append_description,
            categoryRes = R.string.catalog_category_file,
            fields = listOf(
                ActionField("path", R.string.catalog_action_file_append_field_path_label, FieldType.FILE, required = true, fileRule = openTaskerFileRule),
                ActionField("text", R.string.catalog_action_file_append_field_text_label, FieldType.MULTILINE, required = true),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "file.delete",
            nameRes = R.string.catalog_action_file_delete_name,
            descriptionRes = R.string.catalog_action_file_delete_description,
            categoryRes = R.string.catalog_category_file,
            fields = listOf(
                ActionField("path", R.string.catalog_action_file_delete_field_path_label, FieldType.FILE, required = true, fileRule = openTaskerFileRule),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "file.list",
            nameRes = R.string.catalog_action_file_list_name,
            descriptionRes = R.string.catalog_action_file_list_description,
            categoryRes = R.string.catalog_category_file,
            fields = listOf(
                ActionField("path", R.string.catalog_action_file_list_field_path_label, FieldType.FILE, required = true, fileRule = openTaskerFileRule),
                ActionField("var", R.string.catalog_action_file_list_field_var_label, required = true, hintRes = R.string.catalog_action_file_list_field_var_hint),
                ActionField("pattern", R.string.catalog_action_file_list_field_pattern_label, hintRes = R.string.catalog_action_file_list_field_pattern_hint),
            )
        )
    )

    // Network actions
    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "http.request",
            nameRes = R.string.catalog_action_http_request_name,
            descriptionRes = R.string.catalog_action_http_request_description,
            categoryRes = R.string.catalog_category_network,
            fields = listOf(
                ActionField("method", R.string.catalog_action_http_request_field_method_label, FieldType.DROPDOWN, hintRes = R.string.catalog_action_http_request_field_method_hint, options = listOf(option("GET", R.string.action_option_http_get), option("HEAD", R.string.action_option_http_head), option("POST", R.string.action_option_http_post), option("PUT", R.string.action_option_http_put), option("PATCH", R.string.action_option_http_patch), option("DELETE", R.string.action_option_http_delete), option("OPTIONS", R.string.action_option_http_options))),
                ActionField("url", R.string.catalog_action_http_request_field_url_label, required = true),
                ActionField("query", R.string.catalog_action_http_request_field_query_label, FieldType.MULTILINE, hintRes = R.string.catalog_action_http_request_field_query_hint, sensitive = true),
                ActionField("headers", R.string.catalog_action_http_request_field_headers_label, FieldType.MULTILINE, hintRes = R.string.catalog_action_http_request_field_headers_hint, sensitive = true),
                ActionField("authorization", R.string.catalog_action_http_request_field_authorization_label, hintRes = R.string.catalog_action_http_request_field_authorization_hint, sensitive = true),
                ActionField("body", R.string.catalog_action_http_request_field_body_label, FieldType.MULTILINE, sensitive = true),
                ActionField("body_file", R.string.catalog_action_http_request_field_body_file_label, FieldType.FILE, hintRes = R.string.catalog_action_http_request_field_body_file_hint, sensitive = false, fileRule = openTaskerFileRule),
                ActionField("content_type", R.string.catalog_action_http_request_field_content_type_label, hintRes = R.string.catalog_action_http_request_field_content_type_hint),
                ActionField("response_var", R.string.catalog_action_http_request_field_response_var_label, hintRes = R.string.catalog_action_http_request_field_response_var_hint),
                ActionField("status_var", R.string.catalog_action_http_request_field_status_var_label, hintRes = R.string.catalog_action_http_request_field_status_var_hint),
                ActionField("headers_var", R.string.catalog_action_http_request_field_headers_var_label, hintRes = R.string.catalog_action_http_request_field_headers_var_hint, sensitive = false),
                ActionField("output_file", R.string.catalog_action_http_request_field_output_file_label, FieldType.FILE, hintRes = R.string.catalog_action_http_request_field_output_file_hint, fileRule = openTaskerFileRule),
                ActionField("max_response_bytes", R.string.catalog_action_http_request_field_max_response_bytes_label, FieldType.NUMBER, hintRes = R.string.catalog_action_http_request_field_max_response_bytes_hint, numberRule = integerRule(1, 52_428_800)),
                ActionField("redirects", R.string.catalog_action_http_request_field_redirects_label, FieldType.DROPDOWN, hintRes = R.string.catalog_action_http_request_field_redirects_hint, options = listOf(option("none", R.string.label_none), option("same_origin", R.string.action_option_same_origin))),
                ActionField("network", R.string.catalog_action_http_request_field_network_label, FieldType.DROPDOWN, hintRes = R.string.catalog_action_http_request_field_network_hint, options = listOf(option("any", R.string.action_option_network_any), option("wifi", R.string.action_option_network_wifi), option("cellular", R.string.action_option_network_cellular), option("unmetered", R.string.action_option_network_unmetered))),
                ActionField("allow_http", R.string.catalog_action_http_request_field_allow_http_label, FieldType.CHECKBOX, hintRes = R.string.catalog_action_http_request_field_allow_http_hint),
                ActionField("timeout_sec", R.string.catalog_action_http_request_field_timeout_label, FieldType.NUMBER, hintRes = R.string.catalog_action_http_request_field_timeout_hint, numberRule = integerRule(1, 120)),
                ActionField("connect_timeout_sec", R.string.catalog_action_http_request_field_connect_timeout_label, FieldType.NUMBER, numberRule = integerRule(1, 120)),
                ActionField("read_timeout_sec", R.string.catalog_action_http_request_field_read_timeout_label, FieldType.NUMBER, numberRule = integerRule(1, 120)),
                ActionField("write_timeout_sec", R.string.catalog_action_http_request_field_write_timeout_label, FieldType.NUMBER, numberRule = integerRule(1, 120)),
                ActionField("call_timeout_sec", R.string.catalog_action_http_request_field_call_timeout_label, FieldType.NUMBER, numberRule = integerRule(1, 120)),
            ),
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "zen.rule.set",
            nameRes = R.string.catalog_action_zen_rule_set_name,
            descriptionRes = R.string.catalog_action_zen_rule_set_description,
            categoryRes = R.string.catalog_category_settings,
            fields = listOf(
                ActionField("id", R.string.catalog_action_zen_rule_set_field_id_label, required = true, hintRes = R.string.catalog_action_zen_rule_set_field_id_hint),
                ActionField("name", R.string.catalog_action_zen_rule_set_field_name_label, required = true, hintRes = R.string.catalog_action_zen_rule_set_field_name_hint),
                ActionField("mode", R.string.catalog_action_zen_rule_set_field_mode_label, FieldType.DROPDOWN, required = true, hintRes = R.string.catalog_action_zen_rule_set_field_mode_hint, options = listOf(option("off", R.string.label_off), option("priority", R.string.action_option_dnd_priority), option("alarms", R.string.action_option_dnd_alarms), option("total_silence", R.string.action_option_dnd_total_silence))),
                ActionField("enabled", R.string.catalog_action_zen_rule_set_field_enabled_label, FieldType.CHECKBOX, hintRes = R.string.catalog_action_zen_rule_set_field_enabled_hint),
                ActionField("grayscale", R.string.catalog_action_zen_rule_set_field_grayscale_label, FieldType.CHECKBOX),
                ActionField("dim_wallpaper", R.string.catalog_action_zen_rule_set_field_dim_wallpaper_label, FieldType.CHECKBOX),
                ActionField("night_mode", R.string.catalog_action_zen_rule_set_field_night_mode_label, FieldType.CHECKBOX),
            ),
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "zen.rule.clear",
            nameRes = R.string.catalog_action_zen_rule_clear_name,
            descriptionRes = R.string.catalog_action_zen_rule_clear_description,
            categoryRes = R.string.catalog_category_settings,
            fields = listOf(
                ActionField("id", R.string.catalog_action_zen_rule_clear_field_id_label, required = true, hintRes = R.string.catalog_action_zen_rule_clear_field_id_hint),
            ),
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "app.archive",
            nameRes = R.string.catalog_action_app_archive_name,
            descriptionRes = R.string.catalog_action_app_archive_description,
            categoryRes = R.string.catalog_category_app,
            fields = listOf(
                ActionField("package", R.string.catalog_action_app_launch_field_package_label, FieldType.APP, required = true, hintRes = R.string.catalog_action_app_launch_field_package_hint),
            ),
        ),
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "app.unarchive",
            nameRes = R.string.catalog_action_app_unarchive_name,
            descriptionRes = R.string.catalog_action_app_unarchive_description,
            categoryRes = R.string.catalog_category_app,
            fields = listOf(
                ActionField("package", R.string.catalog_action_app_launch_field_package_label, FieldType.APP, required = true, hintRes = R.string.catalog_action_app_launch_field_package_hint),
            ),
        ),
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "shortcut.publish",
            nameRes = R.string.catalog_action_shortcut_publish_name,
            descriptionRes = R.string.catalog_action_shortcut_publish_description,
            categoryRes = R.string.catalog_category_app,
            fields = listOf(
                ActionField("id", R.string.catalog_action_shortcut_publish_field_id_label, required = true, hintRes = R.string.catalog_action_shortcut_publish_field_id_hint),
                ActionField("task_id", R.string.catalog_action_shortcut_publish_field_task_id_label, FieldType.TASK, required = true, hintRes = R.string.catalog_action_shortcut_publish_field_task_id_hint, numberRule = integerRule(1)),
                ActionField("label", R.string.catalog_action_shortcut_publish_field_label_label, required = true, hintRes = R.string.catalog_action_shortcut_publish_field_label_hint),
                ActionField(
                    "mode",
                    R.string.catalog_action_shortcut_publish_field_mode_label,
                    FieldType.DROPDOWN,
                    required = true,
                    hintRes = R.string.catalog_action_shortcut_publish_field_mode_hint,
                    options = listOf(
                        option("dynamic", R.string.action_option_shortcut_dynamic),
                        option("pinned", R.string.action_option_shortcut_pinned),
                    ),
                ),
            ),
        ),
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "flow.try",
            nameRes = R.string.catalog_action_flow_try_name,
            descriptionRes = R.string.catalog_action_flow_try_description,
            categoryRes = R.string.catalog_category_flow,
            fields = listOf(
                ActionField("max_attempts", R.string.catalog_action_flow_try_field_max_attempts_label, FieldType.NUMBER, hintRes = R.string.catalog_action_flow_try_field_max_attempts_hint, numberRule = integerRule(1, 5)),
                ActionField("backoff_ms", R.string.catalog_action_flow_try_field_backoff_label, FieldType.NUMBER, hintRes = R.string.catalog_action_flow_try_field_backoff_hint, numberRule = integerRule(0, 60_000)),
            ),
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "flow.catch",
            nameRes = R.string.catalog_action_flow_catch_name,
            descriptionRes = R.string.catalog_action_flow_catch_description,
            categoryRes = R.string.catalog_category_flow,
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "flow.endtry",
            nameRes = R.string.catalog_action_flow_endtry_name,
            descriptionRes = R.string.catalog_action_flow_endtry_description,
            categoryRes = R.string.catalog_category_flow,
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "flow.goto",
            nameRes = R.string.catalog_action_flow_goto_name,
            descriptionRes = R.string.catalog_action_flow_goto_description,
            categoryRes = R.string.catalog_category_flow,
            fields = listOf(
                ActionField("step", R.string.catalog_action_flow_goto_field_step_label, hintRes = R.string.catalog_action_flow_goto_field_step_hint),
                ActionField("label", R.string.catalog_action_flow_goto_field_label_label, hintRes = R.string.catalog_action_flow_goto_field_label_hint),
            ),
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "flow.anchor",
            nameRes = R.string.catalog_action_flow_anchor_name,
            descriptionRes = R.string.catalog_action_flow_anchor_description,
            categoryRes = R.string.catalog_category_flow,
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "state.temporary",
            nameRes = R.string.catalog_action_state_temporary_name,
            descriptionRes = R.string.catalog_action_state_temporary_description,
            categoryRes = R.string.catalog_category_settings,
            fields = listOf(
                ActionField("target_action", R.string.catalog_action_state_temporary_field_target_action_label, FieldType.DROPDOWN, required = true, options = temporaryStateTargetOptions),
                ActionField("target_args", R.string.catalog_action_state_temporary_field_target_args_label, FieldType.MULTILINE, required = true, hintRes = R.string.catalog_action_state_temporary_field_target_args_hint, sensitive = true),
                ActionField("key", R.string.catalog_action_state_temporary_field_key_label, required = true, hintRes = R.string.catalog_action_state_temporary_field_key_hint, sensitive = false),
                ActionField("duration_sec", R.string.catalog_action_state_temporary_field_duration_label, FieldType.NUMBER, required = true, hintRes = R.string.catalog_action_state_temporary_field_duration_hint, numberRule = integerRule(1, 604_800)),
            ),
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "ime.info",
            nameRes = R.string.catalog_action_ime_info_name,
            descriptionRes = R.string.catalog_action_ime_info_description,
            categoryRes = R.string.catalog_category_settings,
            fields = listOf(
                ActionField("var", R.string.catalog_action_ime_info_field_var_label, hintRes = R.string.catalog_action_ime_info_field_var_hint),
            ),
        ),
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "ime.set",
            nameRes = R.string.catalog_action_ime_set_name,
            descriptionRes = R.string.catalog_action_ime_set_description,
            categoryRes = R.string.catalog_category_settings,
            fields = listOf(
                ActionField("ime_id", R.string.catalog_action_ime_set_field_ime_id_label, required = true, hintRes = R.string.catalog_action_ime_set_field_ime_id_hint),
            ),
        ),
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "clipboard.get",
            nameRes = R.string.catalog_action_clipboard_get_name,
            descriptionRes = R.string.catalog_action_clipboard_get_description,
            categoryRes = R.string.catalog_category_variable,
            fields = listOf(
                ActionField("var", R.string.catalog_action_clipboard_get_field_var_label, hintRes = R.string.catalog_action_clipboard_get_field_var_hint),
            ),
        )
    )
    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "clipboard.set",
            nameRes = R.string.catalog_action_clipboard_set_name,
            descriptionRes = R.string.catalog_action_clipboard_set_description,
            categoryRes = R.string.catalog_category_system,
            fields = listOf(
                ActionField("text", R.string.catalog_action_clipboard_set_field_text_label, FieldType.MULTILINE, required = true, hintRes = R.string.catalog_action_clipboard_set_field_text_hint, sensitive = true),
            ),
        )
    )
    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "contacts.lookup",
            nameRes = R.string.catalog_action_contacts_lookup_name,
            descriptionRes = R.string.catalog_action_contacts_lookup_description,
            categoryRes = R.string.catalog_category_variable,
            fields = listOf(
                ActionField("query", R.string.catalog_action_contacts_lookup_field_query_label, required = true, hintRes = R.string.catalog_action_contacts_lookup_field_query_hint, sensitive = true),
                ActionField("mode", R.string.catalog_action_contacts_lookup_field_mode_label, FieldType.DROPDOWN, hintRes = R.string.catalog_action_contacts_lookup_field_mode_hint, options = listOf(option("picker", R.string.action_option_contacts_picker), option("permission", R.string.action_option_contacts_permission))),
                ActionField("var", R.string.catalog_action_contacts_lookup_field_var_label, hintRes = R.string.catalog_action_contacts_lookup_field_var_hint),
            ),
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "integration.home_assistant.webhook",
            nameRes = R.string.catalog_action_home_assistant_webhook_name,
            descriptionRes = R.string.catalog_action_home_assistant_webhook_description,
            categoryRes = R.string.catalog_category_network,
            fields = listOf(
                ActionField("url", R.string.catalog_action_home_assistant_webhook_field_url_label, required = true, hintRes = R.string.catalog_action_home_assistant_webhook_field_url_hint, sensitive = true),
                ActionField("payload", R.string.catalog_action_home_assistant_webhook_field_payload_label, FieldType.MULTILINE, hintRes = R.string.catalog_action_home_assistant_webhook_field_payload_hint, sensitive = true),
                ActionField("message", R.string.catalog_action_home_assistant_webhook_field_message_label, hintRes = R.string.catalog_action_home_assistant_webhook_field_message_hint),
                ActionField("data", R.string.catalog_action_home_assistant_webhook_field_data_label, FieldType.MULTILINE, hintRes = R.string.catalog_action_home_assistant_webhook_field_data_hint, sensitive = true),
                ActionField("timeout_sec", R.string.catalog_action_home_assistant_webhook_field_timeout_label, FieldType.NUMBER, hintRes = R.string.catalog_action_home_assistant_webhook_field_timeout_hint, numberRule = integerRule(1, 30)),
                ActionField("retries", R.string.catalog_action_home_assistant_webhook_field_retries_label, FieldType.NUMBER, hintRes = R.string.catalog_action_home_assistant_webhook_field_retries_hint, numberRule = integerRule(0, 3)),
                ActionField("backoff_ms", R.string.catalog_action_home_assistant_webhook_field_backoff_label, FieldType.NUMBER, hintRes = R.string.catalog_action_home_assistant_webhook_field_backoff_hint, numberRule = integerRule(100, 5_000)),
                ActionField("allow_http", R.string.catalog_action_home_assistant_webhook_field_allow_http_label, FieldType.CHECKBOX, hintRes = R.string.catalog_action_home_assistant_webhook_field_allow_http_hint),
            ),
        ),
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "mqtt.publish",
            nameRes = R.string.catalog_action_mqtt_publish_name,
            descriptionRes = R.string.catalog_action_mqtt_publish_description,
            categoryRes = R.string.catalog_category_network,
            fields = listOf(
                ActionField("host", R.string.catalog_action_mqtt_publish_field_host_label, required = true, hintRes = R.string.catalog_action_mqtt_publish_field_host_hint),
                ActionField("port", R.string.catalog_action_mqtt_publish_field_port_label, FieldType.NUMBER, hintRes = R.string.catalog_action_mqtt_publish_field_port_hint, numberRule = integerRule(1, 65_535)),
                ActionField("tls", R.string.catalog_action_mqtt_publish_field_tls_label, FieldType.CHECKBOX, hintRes = R.string.catalog_action_mqtt_publish_field_tls_hint),
                ActionField("topic", R.string.catalog_action_mqtt_publish_field_topic_label, required = true, hintRes = R.string.catalog_action_mqtt_publish_field_topic_hint),
                ActionField("payload", R.string.catalog_action_mqtt_publish_field_payload_label, FieldType.MULTILINE, hintRes = R.string.catalog_action_mqtt_publish_field_payload_hint, sensitive = true),
                ActionField("qos", R.string.catalog_action_mqtt_publish_field_qos_label, FieldType.NUMBER, hintRes = R.string.catalog_action_mqtt_publish_field_qos_hint, numberRule = integerRule(0, 1)),
                ActionField("retain", R.string.catalog_action_mqtt_publish_field_retain_label, FieldType.CHECKBOX, hintRes = R.string.catalog_action_mqtt_publish_field_retain_hint),
                ActionField("username", R.string.catalog_action_mqtt_publish_field_username_label, hintRes = R.string.catalog_action_mqtt_publish_field_username_hint),
                ActionField("password", R.string.catalog_action_mqtt_publish_field_password_label, hintRes = R.string.catalog_action_mqtt_publish_field_password_hint, sensitive = true),
                ActionField("timeout_sec", R.string.catalog_action_mqtt_publish_field_timeout_label, FieldType.NUMBER, hintRes = R.string.catalog_action_mqtt_publish_field_timeout_hint, numberRule = integerRule(1, 30)),
            ),
        ),
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "http.get",
            nameRes = R.string.catalog_action_http_get_name,
            descriptionRes = R.string.catalog_action_http_get_description,
            categoryRes = R.string.catalog_category_network,
            fields = listOf(
                ActionField("url", R.string.catalog_action_http_get_field_url_label, required = true),
                ActionField("var", R.string.catalog_action_http_get_field_var_label, hintRes = R.string.catalog_action_http_get_field_var_hint),
                ActionField("allow_http", R.string.catalog_action_http_get_field_allow_http_label, FieldType.CHECKBOX, hintRes = R.string.catalog_action_http_get_field_allow_http_hint),
            ),
            pickerVisible = false,
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "http.post",
            nameRes = R.string.catalog_action_http_post_name,
            descriptionRes = R.string.catalog_action_http_post_description,
            categoryRes = R.string.catalog_category_network,
            fields = listOf(
                ActionField("url", R.string.catalog_action_http_post_field_url_label, required = true),
                ActionField("data", R.string.catalog_action_http_post_field_data_label, FieldType.MULTILINE, sensitive = true),
                ActionField("var", R.string.catalog_action_http_post_field_var_label, hintRes = R.string.catalog_action_http_post_field_var_hint),
                ActionField("allow_http", R.string.catalog_action_http_post_field_allow_http_label, FieldType.CHECKBOX, hintRes = R.string.catalog_action_http_post_field_allow_http_hint),
            ),
            pickerVisible = false,
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "ping",
            nameRes = R.string.catalog_action_ping_name,
            descriptionRes = R.string.catalog_action_ping_description,
            categoryRes = R.string.catalog_category_network,
            fields = listOf(
                ActionField("host", R.string.catalog_action_ping_field_host_label, required = true),
                ActionField("timeout_sec", R.string.catalog_action_ping_field_timeout_label, FieldType.NUMBER, hintRes = R.string.catalog_action_ping_field_timeout_hint, numberRule = integerRule(1, 30)),
                ActionField("var", R.string.catalog_action_ping_field_var_label, hintRes = R.string.catalog_action_ping_field_var_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "download",
            nameRes = R.string.catalog_action_download_name,
            descriptionRes = R.string.catalog_action_download_description,
            categoryRes = R.string.catalog_category_network,
            fields = listOf(
                ActionField("url", R.string.catalog_action_download_field_url_label, required = true),
                ActionField("path", R.string.catalog_action_download_field_path_label, FieldType.FILE, required = true, fileRule = openTaskerFileRule),
                ActionField("allow_http", R.string.catalog_action_download_field_allow_http_label, FieldType.CHECKBOX, hintRes = R.string.catalog_action_download_field_allow_http_hint),
                ActionField("timeout_sec", R.string.catalog_action_download_field_timeout_label, FieldType.NUMBER, hintRes = R.string.catalog_action_download_field_timeout_hint, numberRule = integerRule(1, 120)),
                ActionField("max_bytes", R.string.catalog_action_download_field_max_bytes_label, FieldType.NUMBER, hintRes = R.string.catalog_action_download_field_max_bytes_hint, numberRule = integerRule(1, 52_428_800)),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "wol",
            nameRes = R.string.catalog_action_wol_name,
            descriptionRes = R.string.catalog_action_wol_description,
            categoryRes = R.string.catalog_category_network,
            fields = listOf(
                ActionField("mac", R.string.catalog_action_wol_field_mac_label, required = true, hintRes = R.string.catalog_action_wol_field_mac_hint),
                ActionField("broadcast", R.string.catalog_action_wol_field_broadcast_label, hintRes = R.string.catalog_action_wol_field_broadcast_hint),
                ActionField("port", R.string.catalog_action_wol_field_port_label, FieldType.NUMBER, hintRes = R.string.catalog_action_wol_field_port_hint, numberRule = integerRule(1, 65_535)),
            )
        )
    )

    // Media actions
    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "sound.play",
            nameRes = R.string.catalog_action_sound_play_name,
            descriptionRes = R.string.catalog_action_sound_play_description,
            categoryRes = R.string.catalog_category_media,
            fields = listOf(
                ActionField("path", R.string.catalog_action_sound_play_field_path_label, FieldType.FILE, required = true, fileRule = deviceFileRule),
                ActionField("volume", R.string.catalog_action_sound_play_field_volume_label, FieldType.NUMBER, hintRes = R.string.catalog_action_sound_play_field_volume_hint, numberRule = decimalRule(0.0, 100.0)),
                ActionField("stream", R.string.catalog_action_sound_play_field_stream_label, FieldType.DROPDOWN, hintRes = R.string.catalog_action_sound_play_field_stream_hint, options = audioOutputOptions),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "sound.beep",
            nameRes = R.string.catalog_action_sound_beep_name,
            descriptionRes = R.string.catalog_action_sound_beep_description,
            categoryRes = R.string.catalog_category_media,
            fields = listOf(
                ActionField("frequency", R.string.catalog_action_sound_beep_field_frequency_label, FieldType.NUMBER, required = true, hintRes = R.string.catalog_action_sound_beep_field_frequency_hint, numberRule = decimalRule(0.0, 10_000.0)),
                ActionField("duration", R.string.catalog_action_sound_beep_field_duration_label, FieldType.NUMBER, required = true, numberRule = integerRule(1, 10_000)),
                ActionField("amplitude", R.string.catalog_action_sound_beep_field_amplitude_label, FieldType.NUMBER, hintRes = R.string.catalog_action_sound_beep_field_amplitude_hint, numberRule = integerRule(0, 100)),
                ActionField("stream", R.string.catalog_action_sound_play_field_stream_label, FieldType.DROPDOWN, hintRes = R.string.catalog_action_sound_play_field_stream_hint, options = audioOutputOptions),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "sound.stop",
            nameRes = R.string.catalog_action_sound_stop_name,
            descriptionRes = R.string.catalog_action_sound_stop_description,
            categoryRes = R.string.catalog_category_media,
            fields = emptyList()
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "sound.pause",
            nameRes = R.string.catalog_action_sound_pause_name,
            descriptionRes = R.string.catalog_action_sound_pause_description,
            categoryRes = R.string.catalog_category_media,
            fields = emptyList()
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "track.next",
            nameRes = R.string.catalog_action_track_next_name,
            descriptionRes = R.string.catalog_action_track_next_description,
            categoryRes = R.string.catalog_category_media,
            fields = emptyList()
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "track.previous",
            nameRes = R.string.catalog_action_track_previous_name,
            descriptionRes = R.string.catalog_action_track_previous_description,
            categoryRes = R.string.catalog_category_media,
            fields = emptyList()
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "sound.resume",
            nameRes = R.string.catalog_action_sound_resume_name,
            descriptionRes = R.string.catalog_action_sound_resume_description,
            categoryRes = R.string.catalog_category_media,
            fields = emptyList()
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "sound.toggle",
            nameRes = R.string.catalog_action_sound_toggle_name,
            descriptionRes = R.string.catalog_action_sound_toggle_description,
            categoryRes = R.string.catalog_category_media,
            fields = emptyList()
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "track.rewind",
            nameRes = R.string.catalog_action_track_rewind_name,
            descriptionRes = R.string.catalog_action_track_rewind_description,
            categoryRes = R.string.catalog_category_media,
            fields = emptyList()
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "track.fastforward",
            nameRes = R.string.catalog_action_track_fastforward_name,
            descriptionRes = R.string.catalog_action_track_fastforward_description,
            categoryRes = R.string.catalog_category_media,
            fields = emptyList()
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "media.mute",
            nameRes = R.string.catalog_action_media_mute_name,
            descriptionRes = R.string.catalog_action_media_mute_description,
            categoryRes = R.string.catalog_category_media,
            fields = listOf(
                ActionField("stream", R.string.catalog_action_media_mute_field_stream_label, FieldType.DROPDOWN, hintRes = R.string.catalog_action_media_mute_field_stream_hint, options = audioStreamOptions),
            )
        )
    )

    // System actions
    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "vibrate",
            nameRes = R.string.catalog_action_vibrate_name,
            descriptionRes = R.string.catalog_action_vibrate_description,
            categoryRes = R.string.catalog_category_system,
            fields = listOf(
                ActionField("millis", R.string.catalog_action_vibrate_field_millis_label, FieldType.NUMBER, required = true, numberRule = integerRule(1, 10_000)),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "reboot",
            nameRes = R.string.catalog_action_reboot_name,
            descriptionRes = R.string.catalog_action_reboot_description,
            categoryRes = R.string.catalog_category_system,
            fields = emptyList()
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "lock",
            nameRes = R.string.catalog_action_lock_name,
            descriptionRes = R.string.catalog_action_lock_description,
            categoryRes = R.string.catalog_category_system,
            fields = emptyList()
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "screen.off",
            nameRes = R.string.catalog_action_screen_off_name,
            descriptionRes = R.string.catalog_action_screen_off_description,
            categoryRes = R.string.catalog_category_system,
            fields = emptyList()
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "wake",
            nameRes = R.string.catalog_action_wake_name,
            descriptionRes = R.string.catalog_action_wake_description,
            categoryRes = R.string.catalog_category_system,
            fields = emptyList()
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "log",
            nameRes = R.string.catalog_action_log_name,
            descriptionRes = R.string.catalog_action_log_description,
            categoryRes = R.string.catalog_category_system,
            fields = listOf(
                ActionField("message", R.string.catalog_action_log_field_message_label, FieldType.MULTILINE, required = true),
            )
        )
    )
}
