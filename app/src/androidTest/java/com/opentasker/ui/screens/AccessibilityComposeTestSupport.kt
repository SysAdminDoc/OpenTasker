package com.opentasker.ui.screens

import android.app.Activity
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.test.ext.junit.rules.ActivityScenarioRule
import org.junit.Assert.assertTrue

@Suppress("DEPRECATION")
internal fun createAccessibilityComposeRule(): AndroidComposeTestRule<
    ActivityScenarioRule<ComponentActivity>,
    ComponentActivity,
> = createAndroidComposeRule<ComponentActivity>().also { rule ->
    rule.enableAccessibilityChecks()
}

/** The default size, and the largest the system font setting reaches. */
internal val TESTED_FONT_SCALES = listOf(1f, 2f)

/**
 * Renders this activity's text at [scale], as if the phone's font size setting said so. A dialog's
 * window takes its density from these resources, so this reaches dialogs where a density override
 * in the composition wouldn't. Call the result to undo it, because the resources can be shared.
 */
@Suppress("DEPRECATION")
internal fun Activity.useFontScale(scale: Float): () -> Unit {
    val original = Configuration(resources.configuration)
    val scaled = Configuration(original).apply { fontScale = scale }
    resources.updateConfiguration(scaled, resources.displayMetrics)
    return { resources.updateConfiguration(original, resources.displayMetrics) }
}

internal fun AndroidComposeTestRule<*, *>.performAccessibilityChecks() {
    var lastFailure: Throwable? = null
    try {
        waitUntil(timeoutMillis = 10_000) {
            runCatching { onAllNodes(isRoot()).fetchSemanticsNodes().isNotEmpty() }
                .onFailure { lastFailure = it }
                .getOrDefault(false)
        }
    } catch (timeout: ComposeTimeoutException) {
        // The framework only sees a composition while its activity is resumed, so name the state.
        throw AssertionError("No window to check, activity is ${activity.lifecycle.currentState}: $lastFailure", timeout)
    }
    onRoot().tryPerformAccessibilityChecks()
    waitForIdle()
    assertNoClippedText()
    assertNoOverlappingControls()
    assertNoRepeatedReadings()
}

/**
 * A control whose screen-reader reading says the same words twice, like an icon described "Tasks"
 * above a "Tasks" label: TalkBack reads "Tasks, Tasks". The merged tree is what TalkBack reads per
 * focus stop, so a nested control with its own stop (a switch inside a row) counts on its own.
 */
private fun AndroidComposeTestRule<*, *>.assertNoRepeatedReadings() {
    val repeated = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
        .fetchSemanticsNodes(atLeastOneRootRequired = false)
        .mapNotNull { node ->
            val said = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
            val words = said.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            said.joinToString(", ").takeIf { words.size != words.toSet().size }
        }
    assertTrue("Read twice by a screen reader: $repeated", repeated.isEmpty())
}

/**
 * Text cut off by a box too small for it, the way a fixed height breaks at a large font size. The
 * framework checks don't look for this. Text asking for an ellipsis has chosen to shorten itself.
 * The text is measured again inside its node's real bounds, because the layout the semantics action
 * returns is redone at the parent's full width and reports a height far below the drawn one. Text
 * that scrolls itself is skipped: its node is only the viewport, and the rest is a scroll away.
 */
private fun AndroidComposeTestRule<*, *>.assertNoClippedText() {
    val clipped = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
        .fetchSemanticsNodes(atLeastOneRootRequired = false)
        .filterNot { node -> SemanticsActions.ScrollBy in node.config }
        .mapNotNull { node ->
            val layouts = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            layouts.firstOrNull()?.takeIf { it.isClippedIn(node) }?.layoutInput?.text?.text
        }
    assertTrue("Text cut off at this size: $clipped", clipped.isEmpty())
}

private fun TextLayoutResult.isClippedIn(node: SemanticsNode): Boolean {
    val input = layoutInput
    if (input.overflow == TextOverflow.Ellipsis || input.overflow == TextOverflow.Visible) return false
    val density = node.layoutInfo.density
    return TextMeasurer(input.fontFamilyResolver, density, node.layoutInfo.layoutDirection).measure(
        text = input.text,
        style = input.style,
        overflow = TextOverflow.Clip,
        softWrap = input.softWrap,
        maxLines = input.maxLines,
        placeholders = input.placeholders,
        constraints = Constraints(maxWidth = node.size.width, maxHeight = node.size.height),
    ).hasVisualOverflow
}

/** Two controls that aren't nested and share pixels: a tap on the shared part reaches only one. */
private fun AndroidComposeTestRule<*, *>.assertNoOverlappingControls() {
    // A list item scrolled away can stay composed but unplaced, still reporting its old position.
    val controls = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
        .fetchSemanticsNodes(atLeastOneRootRequired = false)
        .filter { node -> node.layoutInfo.isPlaced }
    val overlaps = mutableListOf<String>()
    controls.forEachIndexed { index, first ->
        controls.drop(index + 1).forEach { second ->
            val sameWindow = first.root == second.root
            if (sameWindow && !first.isNestedWith(second) && first.boundsInRoot.sharesPixelsWith(second.boundsInRoot)) {
                overlaps += "${first.boundsInRoot} and ${second.boundsInRoot}"
            }
        }
    }
    assertTrue("Controls overlap at this size: $overlaps", overlaps.isEmpty())
}

private fun SemanticsNode.isNestedWith(other: SemanticsNode): Boolean =
    generateSequence(this) { it.parent }.any { it.id == other.id } ||
        generateSequence(other) { it.parent }.any { it.id == id }

private fun Rect.sharesPixelsWith(other: Rect): Boolean {
    val shared = intersect(other)
    return shared.width > OVERLAP_SLOP_PX && shared.height > OVERLAP_SLOP_PX
}

private const val OVERLAP_SLOP_PX = 2f
