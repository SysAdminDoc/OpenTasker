package com.opentasker.ui.screens

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.opentasker.app.R
import com.opentasker.core.sharing.ProfileShareDraft
import com.opentasker.core.sharing.ShareFindingSeverity
import com.opentasker.core.sharing.ShareSafetyFinding
import com.opentasker.ui.theme.DesignSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ProfileShareReviewDialog(
    state: ProfileShareReviewState,
    busy: Boolean,
    onDismiss: () -> Unit,
    onDraftChanged: (ProfileShareDraft) -> Unit,
    onAttachScreenshots: () -> Unit,
    onRemoveScreenshot: (String) -> Unit,
    onContinueImportReview: () -> Unit,
) {
    val draft = state.draft
    val manifest = state.manifest
    val planWarnings = (draft.bundle.metadata.warnings + state.plan.warnings + state.plan.lossyWarnings).distinct()
    val canContinue = state.draftError == null && !manifest.hasBlockingFindings && state.plan.canImport
    val blockerTitle = stringResource(R.string.profile_share_blockers)
    val warningTitle = stringResource(R.string.profile_share_warnings)
    val blockerColor = MaterialTheme.colorScheme.error
    val warningColor = MaterialTheme.colorScheme.tertiary
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.dialog_profile_share_review)) },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 620.dp),
            ) {
                item {
                    Text(
                        stringResource(R.string.profile_share_unverified_notice),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    OutlinedTextField(
                        value = draft.slug,
                        onValueChange = { onDraftChanged(draft.copy(slug = it)) },
                        label = { Text(stringResource(R.string.profile_share_slug)) },
                        supportingText = { Text(stringResource(R.string.profile_share_slug_hint)) },
                        singleLine = true,
                        isError = state.draftError != null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    OutlinedTextField(
                        value = draft.title,
                        onValueChange = { onDraftChanged(draft.copy(title = it)) },
                        label = { Text(stringResource(R.string.profile_share_title)) },
                        singleLine = true,
                        isError = state.draftError != null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    OutlinedTextField(
                        value = draft.summary,
                        onValueChange = { onDraftChanged(draft.copy(summary = it)) },
                        label = { Text(stringResource(R.string.profile_share_summary)) },
                        minLines = 2,
                        maxLines = 4,
                        isError = state.draftError != null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    OutlinedTextField(
                        value = draft.author,
                        onValueChange = { onDraftChanged(draft.copy(author = it)) },
                        label = { Text(stringResource(R.string.profile_share_author)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    OutlinedTextField(
                        value = draft.sourceUrl,
                        onValueChange = { onDraftChanged(draft.copy(sourceUrl = it)) },
                        label = { Text(stringResource(R.string.profile_share_source_url)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                state.draftError?.let { error ->
                    item {
                        InlineNotice(
                            title = stringResource(R.string.profile_share_invalid_details),
                            body = error,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        SummaryMetric("${manifest.profileCount}", stringResource(R.string.import_count_profiles), Modifier.weight(1f))
                        SummaryMetric("${manifest.taskCount}", stringResource(R.string.import_count_tasks), Modifier.weight(1f))
                        SummaryMetric("${manifest.actionCount}", stringResource(R.string.label_actions), Modifier.weight(1f))
                    }
                }
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        SummaryMetric("${manifest.contextCount}", stringResource(R.string.import_count_contexts), Modifier.weight(1f))
                        SummaryMetric("${manifest.variableCount}", stringResource(R.string.import_count_variables), Modifier.weight(1f))
                        SummaryMetric("${manifest.sceneCount}", stringResource(R.string.import_count_scenes), Modifier.weight(1f))
                    }
                }
                item {
                    InlineNotice(
                        title = stringResource(R.string.profile_share_trust_title),
                        body = stringResource(
                            R.string.profile_share_trust_body,
                            stringResource(shareTrustLevelLabelRes(manifest.trustLevel)),
                            manifest.submissionChannel,
                        ),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm)) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.profile_share_screenshots), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(
                                    R.string.profile_share_screenshot_count,
                                    manifest.screenshotCount,
                                    PROFILE_SHARE_MAX_SCREENSHOTS,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(
                            onClick = onAttachScreenshots,
                            enabled = !busy && draft.screenshots.size < PROFILE_SHARE_MAX_SCREENSHOTS,
                        ) {
                            Text(stringResource(R.string.profile_share_attach_screenshots))
                        }
                        if (draft.screenshots.isNotEmpty()) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm)) {
                                items(draft.screenshots, key = { it }) { uri ->
                                    ShareScreenshotThumbnail(uri = uri, onRemove = { onRemoveScreenshot(uri) })
                                }
                            }
                        }
                    }
                }
                findingsSection(
                    findings = manifest.findings.filter { it.severity == ShareFindingSeverity.Blocker },
                    title = blockerTitle,
                    color = blockerColor,
                )
                findingsSection(
                    findings = manifest.findings.filter { it.severity == ShareFindingSeverity.Warning },
                    title = warningTitle,
                    color = warningColor,
                )
                item {
                    Text(stringResource(R.string.profile_share_import_plan), style = MaterialTheme.typography.titleMedium)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = DesignSystem.Spacing.sm),
                    ) {
                        SummaryMetric("${state.plan.variableConflicts.size}", stringResource(R.string.import_variable_conflicts), Modifier.weight(1f))
                        SummaryMetric("${manifest.capabilityRequirements.size}", stringResource(R.string.import_count_setup_notes), Modifier.weight(1f))
                        SummaryMetric("${planWarnings.size}", stringResource(R.string.import_count_warnings), Modifier.weight(1f))
                    }
                }
                if (planWarnings.isNotEmpty()) {
                    item {
                        TaskerImportListSectionForShare(
                            title = stringResource(R.string.import_warnings),
                            values = planWarnings,
                            color = if (state.plan.canImport) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = canContinue && !busy,
                onClick = onContinueImportReview,
            ) {
                Text(
                    if (busy) stringResource(R.string.status_importing)
                    else stringResource(R.string.profile_share_review_import),
                )
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/**
 * The review for a bundle opened from a file or pasted in. It skips the share form, since nobody
 * importing a file is writing a share, but a bundle from anywhere is still an unverified share:
 * whatever the share check blocks on blocks the import too.
 */
internal fun ProfileShareReviewState.asImportReview(): OpenTaskerBundleReviewState {
    val blockers = manifest.findings.filter { it.severity == ShareFindingSeverity.Blocker }.map(ShareSafetyFinding::message)
    val gated = if (blockers.isEmpty()) plan else plan.copy(canImport = false, warnings = (plan.warnings + blockers).distinct())
    return OpenTaskerBundleReviewState(bundle = draft.bundle, plan = gated)
}

private fun androidx.compose.foundation.lazy.LazyListScope.findingsSection(
    findings: List<ShareSafetyFinding>,
    title: String,
    color: androidx.compose.ui.graphics.Color,
) {
    if (findings.isNotEmpty()) {
        item {
            TaskerImportListSectionForShare(
                title = title,
                values = findings.map(ShareSafetyFinding::message),
                color = color,
            )
        }
    }
}

@Composable
private fun TaskerImportListSectionForShare(
    title: String,
    values: List<String>,
    color: androidx.compose.ui.graphics.Color,
) {
    InlineNotice(
        title = title,
        body = values.take(6).joinToString("\n") + if (values.size > 6) {
            "\n" + pluralStringResource(R.plurals.import_review_more, values.size - 6, values.size - 6)
        } else {
            ""
        },
        color = color,
    )
}

@Composable
private fun ShareScreenshotThumbnail(uri: String, onRemove: () -> Unit) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(initialValue = null, key1 = uri) {
        value = withContext(Dispatchers.IO) { loadShareScreenshot(context, uri) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.xs)) {
        image?.let {
            Image(
                bitmap = it,
                contentDescription = stringResource(R.string.profile_share_screenshot_description),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(132.dp)
                    .clip(MaterialTheme.shapes.medium),
            )
        } ?: Text(
            stringResource(R.string.profile_share_screenshot_unavailable),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.size(132.dp),
        )
        TextButton(onClick = onRemove, modifier = Modifier.size(132.dp)) {
            Text(stringResource(R.string.action_remove_share_screenshot))
        }
    }
}

private fun loadShareScreenshot(context: Context, uriString: String): ImageBitmap? {
    val uri = Uri.parse(uriString)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, bounds)
    } ?: return null
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val sample = generateSequence(1) { it * 2 }
        .takeWhile { bounds.outWidth / it > 360 || bounds.outHeight / it > 240 }
        .lastOrNull() ?: 1
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return context.contentResolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, options)?.asImageBitmap()
    }
}
