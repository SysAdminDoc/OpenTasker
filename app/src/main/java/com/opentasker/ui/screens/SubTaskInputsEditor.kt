package com.opentasker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.opentasker.app.R
import com.opentasker.core.actions.SubTaskInput
import com.opentasker.core.actions.SubTaskInputIssue
import com.opentasker.ui.theme.DesignSystem

/** The Inputs section of the Run Task editor: named values the sub-task reads as variables (A-354). */
@Composable
internal fun SubTaskInputsEditor(
    inputs: List<SubTaskInput>,
    issues: Map<Int, SubTaskInputIssue>,
    onChange: (List<SubTaskInput>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm)) {
        Text(stringResource(R.string.sub_task_inputs_title), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.sub_task_inputs_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        inputs.forEachIndexed { index, input ->
            val issue = issues[index]
            Column(verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.xs)) {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(
                        value = input.name,
                        onValueChange = { name -> onChange(inputs.replacedAt(index, input.copy(name = name))) },
                        label = { Text(stringResource(R.string.sub_task_input_name)) },
                        isError = issue != null,
                        supportingText = issue?.let { { Text(stringResource(it.messageRes)) } },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { onChange(inputs.filterIndexed { position, _ -> position != index }) }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(
                                R.string.sub_task_input_remove,
                                input.name.ifBlank { stringResource(R.string.sub_task_input_unnamed) },
                            ),
                        )
                    }
                }
                OutlinedTextField(
                    value = input.value,
                    onValueChange = { value -> onChange(inputs.replacedAt(index, input.copy(value = value))) },
                    label = { Text(stringResource(R.string.sub_task_input_value)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        OutlinedButton(onClick = { onChange(inputs + SubTaskInput("", "")) }) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text(stringResource(R.string.sub_task_input_add))
        }
    }
}

private val SubTaskInputIssue.messageRes: Int
    get() = when (this) {
        SubTaskInputIssue.InvalidName -> R.string.sub_task_input_error_name
        SubTaskInputIssue.PicksTheTask -> R.string.sub_task_input_error_reserved
        SubTaskInputIssue.Duplicate -> R.string.sub_task_input_error_duplicate
        SubTaskInputIssue.HoldsTheReturnVariable -> R.string.sub_task_input_error_return_variable
        SubTaskInputIssue.HoldsTheResetSwitch -> R.string.sub_task_input_error_reset_return_variable
    }

private fun List<SubTaskInput>.replacedAt(index: Int, input: SubTaskInput): List<SubTaskInput> =
    toMutableList().apply { set(index, input) }
