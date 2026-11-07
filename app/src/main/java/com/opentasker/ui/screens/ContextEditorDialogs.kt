package com.opentasker.ui.screens

import android.os.Build
import android.annotation.SuppressLint
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.opentasker.app.BuildConfig
import com.opentasker.app.R
import com.opentasker.core.actions.ActionField
import com.opentasker.core.actions.ActionFieldPolicy
import com.opentasker.core.actions.FieldType
import com.opentasker.core.contexts.CalendarSunEventPresets
import com.opentasker.core.contexts.ApplicationContextEvents
import com.opentasker.core.contexts.ApplicationComponentMatcher
import com.opentasker.core.contexts.BluetoothEventPresets
import com.opentasker.core.contexts.BroadcastContextEvents
import com.opentasker.core.contexts.BroadcastEventPresets
import com.opentasker.core.contexts.CallContextEvents
import com.opentasker.core.contexts.CallEventPresets
import com.opentasker.core.contexts.DaySchedule
import com.opentasker.core.contexts.EventContextPreset
import com.opentasker.core.contexts.NfcTagWriteSession
import com.opentasker.core.contexts.ScreenRecordingEventPresets
import com.opentasker.core.contexts.BT_NEAR_STATE_KEY
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.apps.PackageNamePolicy
import com.opentasker.ui.theme.DesignSystem
import com.opentasker.ui.theme.selectedContainerColor

internal data class LocalizedContextType(
    val type: ContextType,
    val title: String,
    val description: String,
)

internal fun filterContextPickerItems(
    items: List<LocalizedContextType>,
    query: String,
): List<LocalizedContextType> {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isEmpty()) return items
    return items.filter { item ->
        item.title.contains(normalizedQuery, ignoreCase = true) ||
            item.description.contains(normalizedQuery, ignoreCase = true) ||
            item.type.name.contains(normalizedQuery, ignoreCase = true)
    }
}

@Composable
@SuppressLint("LocalContextGetResourceValueCall")
internal fun ContextTypePickerDialog(onDismiss: () -> Unit, onSelect: (ContextType) -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    var query by rememberSaveable { mutableStateOf("") }
    val localizedTypes = remember(configuration) {
        ContextType.entries.map { type ->
            LocalizedContextType(
                type = type,
                title = context.getString(contextTitleRes(type)),
                description = context.getString(contextDescriptionRes(type)),
            )
        }
    }
    val filteredTypes = remember(localizedTypes, query) { filterContextPickerItems(localizedTypes, query) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_add_context)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.context_picker_search_label)) },
                    placeholder = { Text(stringResource(R.string.context_picker_search_hint)) },
                    trailingIcon = if (query.isNotEmpty()) {
                        {
                            TextButton(onClick = { query = "" }) {
                                Text(stringResource(R.string.context_picker_clear_search))
                            }
                        }
                    } else {
                        null
                    },
                    singleLine = true,
                )
                if (filteredTypes.isEmpty()) {
                    Text(
                        stringResource(R.string.context_picker_no_results),
                        modifier = Modifier.padding(vertical = 24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm),
                    ) {
                        items(filteredTypes, key = { it.type.name }) { localized ->
                            val type = localized.type
                            Card(
                                onClick = { onSelect(type) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.64f)),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.44f)),
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.xs)) {
                                    Text(localized.title, style = MaterialTheme.typography.titleSmall)
                                    Text(localized.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

@Composable
internal fun ContextConfigDialog(
    state: ContextEditState,
    onDismiss: () -> Unit,
    onSave: (ContextSpec) -> Unit,
    onSimulate: ((ContextSpec) -> Unit)? = null,
) {
    var invert by rememberSaveable(state.profile.id, state.index, state.type) { mutableStateOf(state.existing?.invert ?: false) }
    var config by rememberSaveable(state.profile.id, state.index, state.type) {
        mutableStateOf(defaultContextConfig(state.type) + canonicalContextConfig(state.type, state.existing?.config ?: emptyMap()))
    }
    var nfcWriteMessage by rememberSaveable(state.profile.id, state.index, state.type) { mutableStateOf<String?>(null) }
    val fields = contextFields(state.type)
    val saveConfig = contextConfigForSave(state.type, config)
    val missingRequired = fields.any { it.required && config[it.key].isNullOrBlank() } ||
        (state.type == ContextType.DAY && saveConfig["days"].isNullOrBlank())
    // Block saving contexts that parse to a spec that can never match: a garbled TIME window
    // or an out-of-range coordinate would otherwise save silently and fail only at runtime.
    val invalidFields = contextInvalidFields(state.type, config)
    val hasInvalidValues = invalidFields.isNotEmpty()
    val onLabel = stringResource(R.string.label_on)
    val offLabel = stringResource(R.string.label_off)

    LaunchedEffect(Unit) {
        NfcTagWriteSession.results.collect { result ->
            nfcWriteMessage = result.message
        }
    }
    // Cancel any armed one-time NFC write when this editor leaves composition (dismiss or
    // save), so a forgotten armed write can't overwrite/format an unrelated tag later.
    DisposableEffect(Unit) {
        onDispose { NfcTagWriteSession.disarm() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        // Back and Cancel still dismiss in one tap; only a stray tap on the scrim is refused.
        // These forms carry many fields, and discarding them has no undo because nothing was
        // saved yet.
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(contextTitleRes(state.type))) },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md),
            ) {
                item {
                    Text(stringResource(contextDescriptionRes(state.type)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
                        shape = RoundedCornerShape(DesignSystem.Radii.lg),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = invert,
                                role = Role.Switch,
                                onValueChange = { invert = it },
                            )
                            .semantics {
                                stateDescription = if (invert) onLabel else offLabel
                            },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.context_invert_match), style = MaterialTheme.typography.labelLarge)
                                Text(
                                    stringResource(R.string.context_invert_helper),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = invert, onCheckedChange = null)
                        }
                    }
                    HorizontalDivider()
                }
                if (state.type == ContextType.DAY) {
                    item("day-schedule") {
                        DayScheduleInput(
                            value = config["days"].orEmpty(),
                            onChange = { value -> config = config + ("days" to value) },
                        )
                    }
                } else {
                    items(fields, key = { it.key }) { field ->
                        ActionFieldInput(
                            field = field,
                            value = config[field.key].orEmpty(),
                            onChange = { value -> config = config + (field.key to value) },
                            issue = invalidFields[field.key],
                            suggestedPackage = if (state.type == ContextType.APPLICATION && field.key == "package") {
                                ApplicationContextEvents.latestObservedPackage()
                            } else {
                                null
                            },
                        )
                    }
                    if (state.type == ContextType.STATE) {
                        item("state-presets") {
                            StateContextPresetRow(
                                presets = StateContextPresets.all,
                                onApply = { preset -> config = StateContextPresets.apply(config, preset) },
                            )
                        }
                    }
                    if (state.type == ContextType.EVENT && config["event"].equals("nfc", ignoreCase = true)) {
                        item("nfc-write-helper") {
                            NfcWriteHelperCard(
                                tagId = config["tagId"].orEmpty(),
                                message = nfcWriteMessage,
                                onArm = { label ->
                                    nfcWriteMessage = NfcTagWriteSession.armTextRecord(label).message
                                },
                            )
                        }
                    }
                    if (state.type == ContextType.EVENT && config["event"].equals("sms_received", ignoreCase = true)) {
                        item("sms-api-37-note") {
                            Text(
                                text = stringResource(
                                    if (BuildConfig.SMS_RECEIVE_AVAILABLE) {
                                        R.string.context_sms_received_android_17_note
                                    } else {
                                        R.string.context_sms_received_unavailable_note
                                    },
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (state.type == ContextType.EVENT &&
                        config["event"].equals(CallContextEvents.EVENT_INCOMING_CALL, ignoreCase = true)
                    ) {
                        item("incoming-call-note") {
                            Text(
                                text = stringResource(R.string.context_incoming_call_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (state.type == ContextType.EVENT &&
                        config["event"].equals(BroadcastContextEvents.EVENT_BROADCAST, ignoreCase = true)
                    ) {
                        // Warn rather than refuse. A runtime receiver genuinely can hear some
                        // platform actions, so a hard block would be wrong; a profile that waits
                        // forever with no explanation is the failure worth preventing.
                        BroadcastContextEvents.normalizeAction(config["action"])
                            ?.takeIf(BroadcastContextEvents::isPlatformAction)
                            ?.let {
                                item("broadcast-platform-action-note") {
                                    Text(
                                        text = stringResource(R.string.context_broadcast_platform_action_note),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        if (config["sender"].orEmpty().isNotBlank()) {
                            // Two different truths. Below API 34 there is no sender to read at all,
                            // and above it there is one only when the sender chose to share it,
                            // which almost nothing does. Either way the filter refuses an unknown
                            // sender, so saying only "needs Android 14" would mislead.
                            val senderNote = if (Build.VERSION.SDK_INT < BroadcastContextEvents.SENDER_IDENTITY_API) {
                                R.string.context_broadcast_sender_unavailable_note
                            } else {
                                R.string.context_broadcast_sender_optin_note
                            }
                            item("broadcast-sender-note") {
                                Text(
                                    text = stringResource(senderNote),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                    val eventPresets = if (state.type == ContextType.EVENT) {
                        CalendarSunEventPresets.presetsFor(config["event"].orEmpty()) +
                            BluetoothEventPresets.allPresets() +
                            BroadcastEventPresets.allPresets() +
                            CallEventPresets.allPresets() +
                            ScreenRecordingEventPresets.presetsFor(config["event"].orEmpty())
                    } else {
                        emptyList()
                    }
                    if (eventPresets.isNotEmpty()) {
                        item("event-presets") {
                            EventPresetRow(
                                presets = eventPresets,
                                onApply = { preset ->
                                    config = CalendarSunEventPresets.applyPreset(config, preset)
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !missingRequired && !hasInvalidValues,
                onClick = { onSave(ContextSpec(state.type, saveConfig, invert)) },
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            Row {
                if (onSimulate != null) {
                    TextButton(
                        enabled = !missingRequired && !hasInvalidValues,
                        onClick = { onSimulate(ContextSpec(state.type, saveConfig, invert)) },
                    ) {
                        Text(stringResource(R.string.context_simulate_trigger))
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

/**
 * True when a filled-in context config parses to values that can never match, so the save
 * button should stay disabled (mirrors the DAY context's canonicalize-or-block behavior).
 * Only non-blank values are checked; required-but-blank is handled by [missingRequired].
 */
internal fun contextHasInvalidValues(type: ContextType, config: Map<String, String>): Boolean =
    contextInvalidFields(type, config).isNotEmpty()

/**
 * What is wrong with each field of a context, by config key. It used to be one Boolean, so a value
 * like `25:00` greyed out Save and Simulate while every field looked fine and nothing said which
 * one to fix (A-328).
 */
internal fun contextInvalidFields(type: ContextType, config: Map<String, String>): Map<String, ActionFieldPolicy.Issue> {
    val issues = linkedMapOf<String, ActionFieldPolicy.Issue>()
    fun checkClock(key: String) {
        val raw = config[key]?.trim().orEmpty()
        if (raw.isBlank()) return
        val parts = raw.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull()
        val minute = parts.getOrNull(1)?.toIntOrNull()
        if (parts.size != 2 || hour == null || minute == null || hour !in 0..23 || minute !in 0..59) {
            issues[key] = ActionFieldPolicy.Issue(ActionFieldPolicy.Error.INVALID_TIME)
        }
    }
    fun checkRange(key: String, min: Double, max: Double) {
        val raw = config[key]?.trim().orEmpty()
        if (raw.isBlank()) return
        val value = raw.toDoubleOrNull()
        issues[key] = when {
            value == null -> ActionFieldPolicy.Issue(ActionFieldPolicy.Error.INVALID_NUMBER)
            value < min -> ActionFieldPolicy.Issue(ActionFieldPolicy.Error.BELOW_MINIMUM, min)
            value > max -> ActionFieldPolicy.Issue(ActionFieldPolicy.Error.ABOVE_MAXIMUM, max)
            else -> return
        }
    }
    val packageName = config["package"]?.trim().orEmpty()
    if (packageName.isNotBlank() &&
        type in setOf(ContextType.APPLICATION, ContextType.EVENT, ContextType.PLUGIN) &&
        !PackageNamePolicy.isValid(packageName)
    ) {
        issues["package"] = ActionFieldPolicy.Issue(ActionFieldPolicy.Error.INVALID_APP)
    }
    val component = config["component"]?.trim().orEmpty()
    if (type == ContextType.APPLICATION && component.isNotBlank() && !ApplicationComponentMatcher.isValidPattern(component)) {
        issues["component"] = ActionFieldPolicy.Issue(ActionFieldPolicy.Error.INVALID_COMPONENT)
    }
    when (type) {
        ContextType.TIME -> {
            checkClock("start")
            checkClock("end")
        }
        ContextType.LOCATION -> {
            checkRange("latitude", -90.0, 90.0)
            checkRange("longitude", -180.0, 180.0)
            checkRange("radiusMeters", 0.0, Double.MAX_VALUE)
        }
        ContextType.EVENT -> {
            checkRange("latitude", -90.0, 90.0)
            checkRange("longitude", -180.0, 180.0)
        }
        else -> Unit
    }
    return issues
}

private fun contextFields(type: ContextType): List<ActionField> = when (type) {
    ContextType.APPLICATION -> listOf(
        ActionField("package", R.string.context_field_application_package_label, FieldType.APP, required = true, hintRes = R.string.context_field_application_package_hint),
        ActionField("component", R.string.context_field_application_component_label, hintRes = R.string.context_field_application_component_hint),
    )
    ContextType.TIME -> listOf(
        ActionField("start", R.string.context_field_time_start_label, required = true, hintRes = R.string.context_field_time_start_hint),
        ActionField("end", R.string.context_field_time_end_label, required = true, hintRes = R.string.context_field_time_end_hint),
    )
    ContextType.DAY -> listOf(ActionField("days", R.string.context_field_day_days_label, required = true, hintRes = R.string.context_field_day_days_hint))
    ContextType.LOCATION -> listOf(
        ActionField("latitude", R.string.context_field_location_latitude_label, FieldType.NUMBER, required = true),
        ActionField("longitude", R.string.context_field_location_longitude_label, FieldType.NUMBER, required = true),
        ActionField("radiusMeters", R.string.context_field_location_radius_label, FieldType.NUMBER, required = true, hintRes = R.string.context_field_location_radius_hint),
        ActionField("maxAccuracyMeters", R.string.context_field_location_accuracy_label, FieldType.NUMBER, hintRes = R.string.context_field_location_accuracy_hint),
        ActionField("dwellSeconds", R.string.context_field_location_dwell_label, FieldType.NUMBER, hintRes = R.string.context_field_location_dwell_hint),
        ActionField("outside", R.string.context_field_location_outside_label, FieldType.CHECKBOX, hintRes = R.string.context_field_location_outside_hint),
    )
    ContextType.STATE -> listOf(
        ActionField("key", R.string.context_field_state_key_label, required = true, hintRes = R.string.context_field_state_key_hint),
        ActionField("operator", R.string.context_field_state_operator_label, hintRes = R.string.context_field_state_operator_hint),
        ActionField("value", R.string.context_field_state_value_label, required = true, hintRes = R.string.context_field_state_value_hint),
    )
    ContextType.EVENT -> listOf(
        ActionField("event", R.string.context_field_event_type_label, required = true, hintRes = R.string.context_field_event_type_hint),
        ActionField("state", R.string.context_field_event_state_label, hintRes = R.string.context_field_event_state_hint),
        ActionField("calendar", R.string.context_field_event_calendar_label, hintRes = R.string.context_field_event_calendar_hint),
        ActionField("beforeMinutes", R.string.context_field_event_before_label, FieldType.NUMBER, hintRes = R.string.context_field_event_before_hint),
        ActionField("package", R.string.context_field_event_package_label, FieldType.APP, hintRes = R.string.context_field_event_package_hint),
        ActionField("sender", R.string.context_field_event_sender_label, hintRes = R.string.context_field_event_sender_hint),
        ActionField("action", R.string.context_field_event_action_label, hintRes = R.string.context_field_event_action_hint),
        ActionField("extraKey", R.string.context_field_event_extra_key_label, hintRes = R.string.context_field_event_extra_key_hint),
        ActionField("extraValue", R.string.context_field_event_extra_value_label, hintRes = R.string.context_field_event_extra_value_hint),
        ActionField("number", R.string.context_field_event_number_label, hintRes = R.string.context_field_event_number_hint),
        ActionField("knownContact", R.string.context_field_event_known_contact_label, hintRes = R.string.context_field_event_known_contact_hint),
        ActionField("tagId", R.string.context_field_event_tag_label, hintRes = R.string.context_field_event_tag_hint),
        ActionField("latitude", R.string.context_field_event_latitude_label, FieldType.NUMBER, hintRes = R.string.context_field_event_latitude_hint),
        ActionField("longitude", R.string.context_field_event_longitude_label, FieldType.NUMBER, hintRes = R.string.context_field_event_longitude_hint),
        ActionField("offsetMinutes", R.string.context_field_event_offset_label, FieldType.NUMBER, hintRes = R.string.context_field_event_offset_hint),
        ActionField("windowMinutes", R.string.context_field_event_window_label, FieldType.NUMBER, hintRes = R.string.context_field_event_window_hint),
        ActionField("topic", R.string.context_field_event_topic_label, hintRes = R.string.context_field_event_topic_hint),
        ActionField("eventId", R.string.context_field_event_id_label, hintRes = R.string.context_field_event_id_hint),
        ActionField("title", R.string.context_field_event_title_label, hintRes = R.string.context_field_event_title_hint),
        ActionField("body", R.string.context_field_event_body_label, hintRes = R.string.context_field_event_body_hint),
        ActionField("filter", R.string.context_field_event_filter_label, hintRes = R.string.context_field_event_filter_hint),
        ActionField("regex", R.string.context_field_event_regex_label, FieldType.CHECKBOX),
    )
    ContextType.PLUGIN -> listOf(
        ActionField("package", R.string.context_field_plugin_package_label, FieldType.APP, required = true, hintRes = R.string.context_field_plugin_package_hint),
        ActionField("bundleJson", R.string.context_field_plugin_bundle_label, hintRes = R.string.context_field_plugin_bundle_hint),
        ActionField("blurb", R.string.context_field_plugin_blurb_label, hintRes = R.string.context_field_plugin_blurb_hint),
        ActionField("timeoutMs", R.string.context_field_plugin_timeout_label, FieldType.NUMBER, hintRes = R.string.context_field_plugin_timeout_hint),
    )
}

private fun contextConfigForSave(type: ContextType, config: Map<String, String>): Map<String, String> {
    val nonBlank = canonicalContextConfig(type, config).filterValues { it.isNotBlank() }.toMutableMap()
    config["package"]?.trim()?.takeIf(String::isNotBlank)?.let { nonBlank["package"] = it }
    if (type == ContextType.DAY) {
        val canonicalDays = DaySchedule.canonicalize(config["days"].orEmpty()).orEmpty()
        return if (canonicalDays.isBlank()) {
            nonBlank - "days"
        } else {
            nonBlank + ("days" to canonicalDays)
        }
    }
    if (type == ContextType.PLUGIN) {
        val result = nonBlank.toMutableMap()
        val bundle = result["bundleJson"]?.trim().orEmpty()
        if (bundle.isBlank() || bundle == "{}") {
            result.remove("bundleJson")
        }
        val timeout = result["timeoutMs"]?.toLongOrNull()?.coerceIn(1_000, 30_000)
        if (timeout != null) {
            result["timeoutMs"] = timeout.toString()
        } else {
            result.remove("timeoutMs")
        }
        return result
    }
    return nonBlank
}

/** Canonicalize aliases used by imported bundles before the editor persists a context. */
private fun canonicalContextConfig(type: ContextType, config: Map<String, String>): Map<String, String> {
    if (type != ContextType.APPLICATION || config["component"].orEmpty().isNotBlank()) return config
    val alias = listOf("activity", "class").firstNotNullOfOrNull { key ->
        config[key]?.trim()?.takeIf(String::isNotBlank)
    } ?: return config
    return config + ("component" to alias)
}

private fun defaultContextConfig(type: ContextType): Map<String, String> = when (type) {
    ContextType.TIME -> mapOf("start" to "09:00", "end" to "17:00")
    ContextType.DAY -> mapOf("days" to "MON,TUE,WED,THU,FRI")
    ContextType.LOCATION -> mapOf("radiusMeters" to "100")
    ContextType.PLUGIN -> mapOf("timeoutMs" to "5000")
    else -> emptyMap()
}

@Composable
internal fun DayScheduleInput(value: String, onChange: (String) -> Unit) {
    val selected = DaySchedule.parse(value)
    val canonical = DaySchedule.canonicalize(selected).orEmpty()
    val allDays = DaySchedule.orderedDays.toSet()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.context_day_schedule), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm), modifier = Modifier.fillMaxWidth()) {
            DayPresetButton(
                label = stringResource(R.string.context_daily),
                selected = selected == allDays,
                onClick = { onChange(DaySchedule.canonicalize(allDays).orEmpty()) },
                modifier = Modifier.weight(1f),
            )
            DayPresetButton(
                label = stringResource(R.string.context_weekdays),
                selected = selected == DaySchedule.weekdays,
                onClick = { onChange(DaySchedule.canonicalize(DaySchedule.weekdays).orEmpty()) },
                modifier = Modifier.weight(1f),
            )
            DayPresetButton(
                label = stringResource(R.string.context_weekend),
                selected = selected == DaySchedule.weekends,
                onClick = { onChange(DaySchedule.canonicalize(DaySchedule.weekends).orEmpty()) },
                modifier = Modifier.weight(1f),
            )
        }
        listOf(
            listOf("MON" to R.string.context_day_mon, "TUE" to R.string.context_day_tue, "WED" to R.string.context_day_wed),
            listOf("THU" to R.string.context_day_thu, "FRI" to R.string.context_day_fri, "SAT" to R.string.context_day_sat, "SUN" to R.string.context_day_sun),
        ).forEach { rowDays ->
            Row(horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                rowDays.forEach { (day, dayLabelRes) ->
                    DayPresetButton(
                        label = stringResource(dayLabelRes),
                        selected = day in selected,
                        onClick = {
                            val next = if (day in selected) selected - day else selected + day
                            onChange(DaySchedule.canonicalize(next).orEmpty())
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        OutlinedTextField(
            value = value,
            onValueChange = { onChange(it) },
            label = { Text(stringResource(R.string.context_days_label)) },
            placeholder = { Text(stringResource(R.string.context_days_hint)) },
            supportingText = {
                Text(
                    when {
                        value.isBlank() -> stringResource(R.string.context_days_select_one)
                        canonical.isBlank() -> stringResource(R.string.context_days_invalid_helper)
                        else -> DaySchedule.displayLabel(value)
                    },
                )
            },
            isError = value.isNotBlank() && canonical.isBlank(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun DayPresetButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) selectedContainerColor() else Color.Transparent,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.62f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.62f),
        ),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(label, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

@Composable
internal fun NfcWriteHelperCard(
    tagId: String,
    message: String?,
    onArm: (String) -> Unit,
) {
    val label = if (tagId.isBlank()) {
        stringResource(R.string.context_nfc_trigger_label)
    } else {
        stringResource(R.string.context_nfc_trigger_label_with_tag, tagId)
    }

    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.32f),
        shape = RoundedCornerShape(DesignSystem.Radii.lg),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm),
            ) {
                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.action_edit), tint = MaterialTheme.colorScheme.secondary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.context_nfc_write_helper), style = MaterialTheme.typography.labelLarge)
                    Text(
                        stringResource(R.string.context_nfc_write_helper_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = { onArm(label) }) {
                    Text(stringResource(R.string.action_arm))
                }
            }
            message?.takeIf { it.isNotBlank() }?.let { value ->
                Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun EventPresetRow(
    presets: List<EventContextPreset>,
    onApply: (EventContextPreset) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm)) {
        Text(stringResource(R.string.context_presets), style = MaterialTheme.typography.labelLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm)) {
            items(presets, key = { it.id }) { preset ->
                OutlinedButton(onClick = { onApply(preset) }) {
                    Text(stringResource(preset.labelRes))
                }
            }
        }
    }
}

internal data class StateContextPreset(
    val id: String,
    @StringRes val labelRes: Int,
    val config: Map<String, String>,
)

internal object StateContextPresets {
    /**
     * Roaming and call state need READ_PHONE_STATE, which the Play distribution deliberately does
     * not declare - and its Setup screen has no Phone row either. Offering those presets there
     * produced a context that fails closed while telling the user to grant a permission that can
     * never appear in Setup, the same trap as the missing DND declaration.
     */
    private val phoneStateAvailable: Boolean get() = BuildConfig.SMS_ACTION_AVAILABLE

    val all: List<StateContextPreset>
        get() = buildList {
            add(StateContextPreset("orientation", R.string.context_state_preset_orientation, mapOf("key" to "orientation", "operator" to "=", "value" to "portrait")))
            add(StateContextPreset("proximity", R.string.context_state_preset_proximity, mapOf("key" to "proximity", "operator" to "=", "value" to "near")))
            add(StateContextPreset("activity", R.string.context_state_preset_activity, mapOf("key" to "activity", "operator" to "=", "value" to "walking")))
            add(StateContextPreset("speed", R.string.context_state_preset_speed, mapOf("key" to "speed", "operator" to ">=", "value" to "10")))
            if (phoneStateAvailable) {
                add(StateContextPreset("roaming", R.string.context_state_preset_roaming, mapOf("key" to "roaming", "operator" to "=", "value" to "true")))
            }
            add(StateContextPreset("bt_near", R.string.context_state_preset_bt_near, mapOf("key" to BT_NEAR_STATE_KEY, "operator" to "=", "value" to "")))
            add(StateContextPreset("tethering", R.string.context_state_preset_tethering, mapOf("key" to "tethering", "operator" to "=", "value" to "true")))
            if (phoneStateAvailable) {
                add(StateContextPreset("call", R.string.context_state_preset_call, mapOf("key" to "call_state", "operator" to "=", "value" to "ringing")))
            }
        }

    fun apply(current: Map<String, String>, preset: StateContextPreset): Map<String, String> =
        current + preset.config
}

@Composable
internal fun StateContextPresetRow(
    presets: List<StateContextPreset>,
    onApply: (StateContextPreset) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm)) {
        Text(stringResource(R.string.context_presets), style = MaterialTheme.typography.labelLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm)) {
            items(presets, key = { it.id }) { preset ->
                OutlinedButton(onClick = { onApply(preset) }) {
                    Text(stringResource(preset.labelRes))
                }
            }
        }
    }
}

@StringRes
internal fun contextTitleRes(type: ContextType): Int = when (type) {
    ContextType.APPLICATION -> R.string.context_type_application_title
    ContextType.TIME -> R.string.context_type_time_title
    ContextType.DAY -> R.string.context_type_day_title
    ContextType.LOCATION -> R.string.context_type_location_title
    ContextType.STATE -> R.string.context_type_state_title
    ContextType.EVENT -> R.string.context_type_event_title
    ContextType.PLUGIN -> R.string.context_type_plugin_title
}

@StringRes
internal fun contextDescriptionRes(type: ContextType): Int = when (type) {
    ContextType.APPLICATION -> R.string.context_type_application_description
    ContextType.TIME -> R.string.context_type_time_description
    ContextType.DAY -> R.string.context_type_day_description
    ContextType.LOCATION -> R.string.context_type_location_description
    ContextType.STATE -> R.string.context_type_state_description
    ContextType.EVENT -> R.string.context_type_event_description
    ContextType.PLUGIN -> R.string.context_type_plugin_description
}
