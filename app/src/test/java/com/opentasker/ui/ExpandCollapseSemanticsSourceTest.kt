package com.opentasker.ui

import com.opentasker.ProductionSources
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * A disclosure row built from a bare `Modifier.clickable { expanded = !expanded }` announces only
 * "double tap to activate": no role, and nothing that says whether the section is already open, so
 * the same words are read whether the next tap opens or closes it. Seven cards shipped that way.
 *
 * The fix is `Modifier.expandCollapseToggle`, which carries the role, the state and the click
 * label. This gate keeps the bare form from coming back, because the difference is invisible on
 * screen and only a screen reader notices.
 */
class ExpandCollapseSemanticsSourceTest {

    private val screensDirectory = ProductionSources.path("com/opentasker/ui/screens")

    private fun screenSources(): List<Pair<String, String>> =
        Files.list(screensDirectory).use { stream ->
            stream.filter { it.name.endsWith(".kt") }
                .map { it.name to it.readText() }
                .toList()
        }

    /**
     * Every spelling that toggles an expanded flag from a click modifier: the trailing-lambda form,
     * the `onClick = { ... }` form, and combinedClickable. An earlier version of this regex only
     * allowed an EMPTY paren group, so `.clickable(onClick = { expanded = !expanded })` and
     * `.combinedClickable { ... }` both walked straight past the gate whose entire job is catching
     * them. [theGateRecognisesEverySpellingOfABareToggle] pins that.
     */
    private val bareToggle = Regex(
        """\.(?:combined)?[cC]lickable\s*(?:\([^{}]*?(?:onClick\s*=\s*)?)?\s*\{\s*(\w*[eE]xpanded)\s*=\s*!\1\s*}""",
    )

    /**
     * A `clickable` that declares a role is not the defect. The run log's variable-change inspector
     * spells the semantics out by hand, including a `contentDescription` the shared helper does not
     * take, and it is the site the helper was extracted from. Matching on shape alone flagged it.
     */
    private fun Regex.bareTogglesIn(source: String): List<String> =
        findAll(source).map { it.value }.filterNot { it.contains("role") }.map { it.trim() }.toList()

    @Test
    fun noScreenTogglesAnExpandedFlagFromABareClickable() {
        val offenders = screenSources().flatMap { (name, source) ->
            bareToggle.bareTogglesIn(source).map { "$name: $it" }
        }

        assertTrue(
            "A disclosure row must use Modifier.expandCollapseToggle so a screen reader gets the " +
                "role and the expanded state. Bare toggles found: $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun theGateRecognisesEverySpellingOfABareToggle() {
        // The gate is a regex over source text, so its blind spots are invisible until something
        // slips through in review. These are the forms an adversarial pass found it missing.
        val mustCatch = listOf(
            ".clickable { expanded = !expanded }",
            ".clickable { lintExpanded = !lintExpanded }",
            ".clickable() { expanded = !expanded }",
            ".clickable(onClick = { expanded = !expanded })",
            ".combinedClickable { expanded = !expanded }",
            ".combinedClickable(onClick = { detailsExpanded = !detailsExpanded })",
        )
        val mustIgnore = listOf(
            ".expandCollapseToggle(expanded) { expanded = !expanded }",
            ".clickable(role = Role.Button, onClickLabel = label) { onOpen() }",
            ".clickable { selected = !selected }",
        )

        // The real spelling in RunLogScreenContent's variable-change inspector: correct semantics
        // written out by hand, which must not be reported as a bare toggle.
        val handWrittenButCorrect = """
            .clickable(
                role = Role.Button,
                onClickLabel = actionLabel,
            ) { expanded = !expanded }
        """.trimIndent()

        mustCatch.forEach { assertTrue("gate must catch: $it", bareToggle.bareTogglesIn(it).isNotEmpty()) }
        mustIgnore.forEach { assertTrue("gate must not flag: $it", bareToggle.bareTogglesIn(it).isEmpty()) }
        assertTrue(
            "a hand-written toggle that declares its role is not the defect",
            bareToggle.bareTogglesIn(handWrittenButCorrect).isEmpty(),
        )
    }

    @Test
    fun theSharedToggleCarriesRoleStateAndClickLabel() {
        // The helper is the only thing standing behind the gate above, so its own contents are the
        // real assertion: if it stopped setting any of the three, every call site would go quiet
        // again and the regex gate would still pass.
        val helper = ProductionSources.read("com/opentasker/ui/utils/ExpandCollapseSemantics.kt")

        assertTrue("the toggle must set a state description", helper.contains("stateDescription = stateLabel"))
        assertTrue("the toggle must declare the button role", helper.contains("role = Role.Button"))
        assertTrue("the toggle must label the click", helper.contains("onClickLabel = actionLabel"))
        assertTrue("expanded and collapsed must be distinct strings", helper.contains("R.string.a11y_expanded"))
        assertTrue("expanded and collapsed must be distinct strings", helper.contains("R.string.a11y_collapsed"))
    }

    @Test
    fun asyncResultSurfacesAnnounceThemselves() {
        // Each of these replaces its own text when work the user started finishes, without moving
        // focus, so without a live region the completion is silent.
        val surfaces = mapOf(
            "com/opentasker/ui/screens/PermissionOnboardingScreen.kt" to "the backup banner",
            "com/opentasker/ui/screens/ImportReviewDialogs.kt" to "the import and export stage label",
            "com/opentasker/ui/screens/PreflightReviewDialog.kt" to "the preflight report title",
        )

        surfaces.forEach { (path, description) ->
            val source = ProductionSources.read(path)
            assertTrue(
                "$description must be a polite live region so a finished run is announced",
                source.contains("liveRegion = LiveRegionMode.Polite"),
            )
        }
    }
}
