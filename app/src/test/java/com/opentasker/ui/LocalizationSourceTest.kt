package com.opentasker.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.readText

class LocalizationSourceTest {
    private val moduleRoot: Path = listOf(Path.of("."), Path.of("app"))
        .first { Files.isDirectory(it.resolve("src/main")) }
    private val sourceRoot: Path = moduleRoot.resolve("src/main/java")
    private val resRoot: Path = moduleRoot.resolve("src/main/res")

    @Test
    fun presentationSurfacesUseStringResourcesForVisibleCopy() {
        val localizedFiles = listOf(
            "com/opentasker/ui/screens/ActiveAutomationLists.kt",
            "com/opentasker/ui/screens/PreflightReviewDialog.kt",
            "com/opentasker/ui/screens/ActionEditorDialogs.kt",
            "com/opentasker/ui/screens/AutomationFlowScreen.kt",
            "com/opentasker/ui/screens/ContextEditorDialogs.kt",
            "com/opentasker/ui/screens/EditorDialogs.kt",
            "com/opentasker/ui/screens/ImportedProfileRiskDialog.kt",
            "com/opentasker/ui/screens/ImportReviewDialogs.kt",
            "com/opentasker/ui/screens/PermissionOnboardingScreen.kt",
            "com/opentasker/ui/screens/SetupRows.kt",
            "com/opentasker/ui/screens/SceneEditorCanvas.kt",
            "com/opentasker/ui/screens/SceneEditorDialogs.kt",
            "com/opentasker/ui/screens/SceneLibraryScreen.kt",
            "com/opentasker/ui/screens/SceneLibraryCards.kt",
            "com/opentasker/ui/screens/SceneOverlayControls.kt",
            "com/opentasker/ui/screens/VariablesScreen.kt",
            "com/opentasker/widget/TaskWidgetConfigActivity.kt",
            "com/opentasker/ui/screens/ActiveAutomationUi.kt",
            "com/opentasker/ui/screens/ActiveAutomationViewModel.kt",
            "com/opentasker/ui/screens/ContextInspectorScreen.kt",
            "com/opentasker/ui/screens/RunLogScreenContent.kt",
            "com/opentasker/ui/screens/DiagnosticsScreen.kt",
            "com/opentasker/ui/screens/RunLogFilters.kt",
            "com/opentasker/core/flow/AutomationFlowGraph.kt",
        )
        val forbiddenPatterns = mapOf(
            "Text literal" to Regex("""\bText\s*\(\s*""" + "\""),
            // Text(text = "...") is the same defect wearing a named argument; the
            // positional-only pattern above never matched it.
            "Text named-argument literal" to Regex("""Text\s*\(\s*text\s*=\s*""" + "\""),
            "supportingText literal" to Regex("""supportingText\s*=\s*\{\s*Text\s*\(\s*""" + "\""),
            "Button text literal" to Regex("""\bButton\s*\([^)]*\)\s*\{\s*Text\s*\(\s*""" + "\"", RegexOption.DOT_MATCHES_ALL),
            "contentDescription literal" to Regex("""contentDescription\s*=\s*""" + "\""),
            "label text literal" to Regex("""label\s*=\s*\{\s*Text\s*\(\s*""" + "\""),
            "placeholder text literal" to Regex("""placeholder\s*=\s*\{\s*Text\s*\(\s*""" + "\""),
            "body argument literal" to Regex("""\bbody\s*=\s*""" + "\""),
            "values argument literal" to Regex("""\bvalues\s*=\s*""" + "\""),
            // Helpers like InspectorStatusPill(label = "...") never matched Text("...").
            "label assignment literal" to Regex("""\blabel\s*=\s*""" + "\""),
            "share-sheet subject literal" to Regex("""EXTRA_SUBJECT\s*,\s*""" + "\""),
            "share-sheet chooser literal" to Regex("""createChooser\s*\([^)]*,\s*""" + "\""),
        )

        // Every presentation file under ui/screens is scanned, not just a hand-written list:
        // a list-scoped gate silently certifies whatever is not on it, which is how
        // SyntheticTriggerSimulationDialog.kt shipped with hardcoded copy and how
        // RunLogRetentionPreviewDialog.kt lost coverage when it moved out of a covered file.
        val screensDir = sourceRoot.resolve("com/opentasker/ui/screens")
        val screenFiles = Files.list(screensDir).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
                .map { "com/opentasker/ui/screens/" + it.fileName }
                .toList()
        }
        val scannedFiles = (localizedFiles + screenFiles).distinct().sorted()

        assertTrue(
            "Expected the screens package to be discovered on disk",
            screenFiles.isNotEmpty(),
        )

        val offenders = scannedFiles.flatMap { relativePath ->
            val source = sourceRoot.resolve(relativePath).readText()
            forbiddenPatterns.mapNotNull { (name, pattern) ->
                if (pattern.containsMatchIn(source)) "$relativePath: $name" else null
            }
        }

        assertTrue(
            "Hardcoded user-facing Compose strings found; use stringResource/R.string instead: $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun presentationSurfacesDoNotRenderInternalEnumNamesOrThrowableMessages() {
        val screensDir = sourceRoot.resolve("com/opentasker/ui/screens")
        val screenFiles = Files.list(screensDir).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
                .toList()
        }
        val forbiddenPatterns = linkedMapOf(
            "enum name" to Regex(
                """\b(?:automationMode|collisionMode|lifetime|overflowPolicy|trustLevel|riskLevel|level)\.name\b""",
            ),
            "setup enum formatting" to Regex("""\.name\.lowercase\(\)\.replace\('_', ' '\)"""),
            "exception message" to Regex("""\b(?:error|ex|exception|throwable)\.(?:message|localizedMessage)\b"""),
        )
        val offenders = screenFiles.flatMap { file ->
            val source = file.readText()
            forbiddenPatterns.mapNotNull { (name, pattern) ->
                if (pattern.containsMatchIn(source)) "${screensDir.relativize(file)}: $name" else null
            }
        }

        assertTrue(
            "Presentation code must map enums and failures before rendering: $offenders",
            offenders.isEmpty(),
        )

        val viewModel = sourceRoot.resolve("com/opentasker/ui/screens/ActiveAutomationViewModel.kt").readText()
        assertTrue("UI failures must be logged with their raw throwable", "AppLogger.error" in viewModel)
        // Asserted across the package rather than one file: the failure-to-copy mapping lives in
        // UiMessages.kt, and pinning it to a filename made an extraction look like a regression.
        val screenSources = screenFiles.joinToString("\n") { it.readText() }
        assertTrue(
            "Known corrupt records must map to a generic resource",
            "R.string.ui_error_corrupt_record" in screenSources,
        )
    }

    @Test
    fun secondarySurfaceViewModelMessagesResolveFromResourcesAtTheCollector() {
        val viewModel = sourceRoot.resolve("com/opentasker/ui/screens/ActiveAutomationViewModel.kt").readText()
        val ui = sourceRoot.resolve("com/opentasker/ui/screens/ActiveAutomationUi.kt").readText()

        assertTrue("Snackbar channel must carry resource IDs", "Channel<UiMessage>" in viewModel)
        assertFalse("ViewModel must not emit raw snackbar literals", Regex("events\\.send\\(\\s*\"").containsMatchIn(viewModel))
        assertTrue("Compose collector must resolve the message in the current locale", "message.resolve(context)" in ui)
        assertTrue("Undo-capable messages must expose a snackbar action", "message.action?.let" in ui)
    }

    @Test
    fun setupPlatformStateIsLoadedByAnIoBackedViewModel() {
        val setup = sourceRoot.resolve("com/opentasker/ui/screens/PermissionOnboardingScreen.kt").readText()
        val screen = setup.substringAfter("fun PermissionOnboardingScreen(")
            .substringBefore("@Composable\nprivate fun GlobalFallbackTaskCard")
        val viewModel = setup.substringAfter("private class PermissionOnboardingViewModel")

        assertTrue("Setup permission snapshots must be produced on Dispatchers.IO", ".flowOn(Dispatchers.IO)" in viewModel)
        assertTrue("Setup must collect permission snapshots from a ViewModel", "setupViewModel.permissionItems.collectAsState()" in screen)
        listOf(
            "buildPermissionItems(context, permissionHistory)",
            "DirectBootTriggerStore.observe(context)",
            "ThemePreference.observe(context)",
            "CompanionDeviceAssociation.list(context)",
            "PushTriggerTokenStore(context).token()",
            "LocaleGrantStore(context).grants()",
        ).forEach { call ->
            assertFalse("Blocking setup call must not remain in the composable body: $call", call in screen)
        }
    }

    @Test
    fun successMessagesUseResourceIdsAtEveryCallSite() {
        val viewModel = sourceRoot.resolve("com/opentasker/ui/screens/ActiveAutomationViewModel.kt").readText()
        val ui = sourceRoot.resolve("com/opentasker/ui/screens/ActiveAutomationUi.kt").readText()
        val sceneScreen = sourceRoot.resolve("com/opentasker/ui/screens/SceneLibraryScreen.kt").readText()
        val sceneCards = sourceRoot.resolve("com/opentasker/ui/screens/SceneLibraryCards.kt").readText()
        val variables = sourceRoot.resolve("com/opentasker/ui/screens/VariablesScreen.kt").readText()

        assertFalse("The raw success-message translation table must not return", "legacyMessage" in viewModel)
        assertFalse("Success helpers must not receive user-facing literals", Regex("launchWithMessage\\(\\s*\"").containsMatchIn(viewModel))
        assertFalse("Success callbacks must carry message resources, not resolved text", "successMessage: String" in viewModel)
        assertFalse("Variable edits must stay resource-backed until collection", Regex("val (created|updated|deleted)Msg = stringResource").containsMatchIn(variables))
        assertTrue("Profile toggle must choose a resource at its call site", "R.string.ui_message_profile_enabled" in ui)
        assertTrue("Profile toggle must localize the disabled branch", "R.string.ui_message_profile_disabled" in ui)
        assertTrue("Scene edits must pass resource IDs", "onUpdateScene: (Scene, Int)" in sceneScreen && "onUpdateScene: (Scene, Int)" in sceneCards)

        val resources = defaultStringResourceNames()
        listOf(
            "ui_message_profile_enabled",
            "ui_message_profile_disabled",
            "ui_message_action_removed",
            "ui_message_context_removed",
            "ui_message_element_moved",
            "ui_message_element_resized",
            "ui_message_element_removed",
        ).forEach { resource -> assertTrue("Missing success resource: $resource", resource in resources) }
    }

    @Test
    fun flowGraphUsesTheCurrentResourceBundleForGeneratedCopy() {
        val flow = sourceRoot.resolve("com/opentasker/ui/screens/AutomationFlowScreen.kt").readText()
        val graph = sourceRoot.resolve("com/opentasker/core/flow/AutomationFlowGraph.kt").readText()

        assertTrue("Flow screen must pass localized copy into graph construction", "AutomationFlowStrings.from(resources)" in flow)
        assertTrue("Flow graph must accept localized presentation copy", "AutomationFlowStrings" in graph)
    }

    @Test
    fun newFeatureCopyResolvesThroughResourceBackedAdapters() {
        val lint = sourceRoot.resolve("com/opentasker/core/capabilities/AutomationLintStrings.kt").readText()
        val lifecycle = sourceRoot.resolve("com/opentasker/core/model/ProfileLifecycleStrings.kt").readText()
        val admission = sourceRoot.resolve("com/opentasker/core/engine/ExecutionAdmissionStrings.kt").readText()
        val duplicate = sourceRoot.resolve("com/opentasker/core/references/AutomationDuplicateStrings.kt").readText()
        val diff = sourceRoot.resolve("com/opentasker/core/diff/SemanticDiffStrings.kt").readText()
        val diffUi = sourceRoot.resolve("com/opentasker/ui/screens/SemanticDiffDialogs.kt").readText()
        val statusVm = sourceRoot.resolve("com/opentasker/ui/screens/ActiveAutomationViewModel.kt").readText()

        assertTrue("lint findings must have a resource-backed adapter", "ResourceAutomationLintStrings" in lint)
        assertTrue("lifecycle reasons must have a resource-backed adapter", "ResourceProfileLifecycleStrings" in lifecycle)
        assertTrue("admission reasons must have a resource-backed adapter", "ResourceExecutionAdmissionStrings" in admission)
        assertTrue("duplicate names must have a resource-backed adapter", "ResourceAutomationDuplicateStrings" in duplicate)
        assertTrue("semantic diff labels must have a resource-backed adapter", "ResourceSemanticDiffStrings" in diff)
        assertTrue("semantic diff UI must resolve labels through the adapter", "strings.path(change.path)" in diffUi)
        assertTrue("run statuses must resolve through resource IDs", "R.string.ui_run_status_held" in statusVm)
        val resources = defaultStringResourceNames()
        assertTrue("lint resource IDs must exist", "automation_lint_missing_reversal_title" in resources)
        assertTrue("lifecycle resource IDs must exist", "profile_lifecycle_expired" in resources)
        assertTrue("admission resource IDs must exist", "admission_reason_counts" in resources)
        assertTrue("duplicate resource IDs must exist", "automation_duplicate_copy_suffix" in resources)
        assertTrue("semantic diff resource IDs must exist", "semantic_diff_value_until_date" in resources)
    }

    @Test
    fun dynamicActionAndContextCatalogsUseCompleteResourceIds() {
        val metadata = sourceRoot.resolve("com/opentasker/core/actions/ActionMetadata.kt").readText()
        val contextEditor = sourceRoot.resolve("com/opentasker/ui/screens/ContextEditorDialogs.kt").readText()
        val catalogReferences = Regex("""R\.string\.(catalog_[a-z0-9_]+)""")
            .findAll(metadata)
            .map { it.groupValues[1] }
            .toSet()
        val resourceNames = defaultStringResourceNames()
        val catalogResources = resourceNames.filter { it.startsWith("catalog_") }.toSet()

        assertTrue("Action catalog should expose resource-backed action names", "nameRes = R.string.catalog_" in metadata)
        assertTrue("Action catalog should expose resource-backed descriptions", "descriptionRes = R.string.catalog_" in metadata)
        assertTrue("Action catalog should expose resource-backed categories", "categoryRes = R.string.catalog_" in metadata)
        assertFalse("Action metadata must not retain presentation string keys", Regex("""(?:nameRes|descriptionRes|categoryRes|hintRes)\s*=\s*\"""").containsMatchIn(metadata))
        assertEquals("Catalog resources and compile-time references must stay in lockstep", catalogResources, catalogReferences)
        assertEquals("Expected every built-in action name to be resource backed", 88, Regex("""nameRes = R\.string\.catalog_action_""").findAll(metadata).count())
        assertEquals("Expected every action field to be resource backed", 222, Regex("""ActionField\(\s*\"""").findAll(metadata).count())
        assertFalse("Context field labels must use resource IDs", Regex("""ActionField\(\s*\"[^\"]+\"\s*,\s*\"""").containsMatchIn(contextEditor))
        assertTrue("Context type names must be resource backed", "contextTitleRes" in contextEditor)
        assertTrue("Context descriptions must be resource backed", "contextDescriptionRes" in contextEditor)
    }

    @Test
    fun widgetsOverlaysAndCapabilityDiagnosticsAreResourceBacked() {
        val widget = sourceRoot.resolve("com/opentasker/widget/TaskWidgetConfigActivity.kt").readText()
        val provider = sourceRoot.resolve("com/opentasker/widget/TaskWidgetProvider.kt").readText()
        val overlay = sourceRoot.resolve("com/opentasker/core/scenes/SceneOverlayService.kt").readText()
        val actionEditor = sourceRoot.resolve("com/opentasker/ui/screens/ActionEditorDialogs.kt").readText()
        val widgetLayout = resRoot.resolve("layout/widget_task.xml").readText()

        assertTrue("Widget quantities must use Android plurals", "pluralStringResource(R.plurals.widget_action_count" in widget)
        assertTrue("Widget summary quantities must use Android plurals", "pluralStringResource(R.plurals.widget_saved_task_count" in widget)
        assertTrue("Widget provider fallback must use the app-name resource", "context.getString(R.string.app_name)" in provider)
        assertFalse("Widget layout contains hardcoded visible copy", Regex("""android:(?:text|contentDescription)=\"(?!@)[^\"]+\"""").containsMatchIn(widgetLayout))
        assertTrue("Overlay button fallback must be localized", "getString(R.string.scene_overlay_default_button)" in overlay)
        assertTrue("Overlay notification title must be localized", "getString(R.string.scene_overlay_notification_title)" in overlay)
        assertTrue("Overlay notification channel must be localized", "getString(R.string.scene_overlay_channel_name)" in overlay)
        assertFalse("Overlay service contains hardcoded view or notification copy", Regex("""(?:text\s*=|setContentTitle\()\s*\"[A-Za-z\[]""").containsMatchIn(overlay))
        assertTrue("Action capability diagnostics must resolve through resources", "stringResource(capability.reasonRes)" in actionEditor)
    }

    @Test
    fun setupPermissionAndBackupCopyUsesResources() {
        // Both halves of Setup: the row definitions moved to SetupRows.kt, and they are exactly
        // what the title/body/actionLabel/requiredFor patterns below exist to police.
        val setup = listOf("PermissionOnboardingScreen.kt", "SetupRows.kt")
            .joinToString("\n") { sourceRoot.resolve("com/opentasker/ui/screens/$it").readText() }
        val forbiddenPatterns = mapOf(
            "permission title" to Regex("""title\s*=\s*\""""),
            "permission body" to Regex("""body\s*=\s*\""""),
            "permission action" to Regex("""actionLabel\s*=\s*\""""),
            "permission requirement" to Regex("""requiredFor\s*=\s*\""""),
            "message" to Regex("""onMessage\(\s*\""""),
            "dynamic paragraph" to Regex("""append\(\s*\"[A-Za-z]"""),
        )
        val offenders = forbiddenPatterns.filterValues { it.containsMatchIn(setup) }.keys

        assertTrue("Setup contains hardcoded permission/backup presentation copy: $offenders", offenders.isEmpty())
        assertTrue("Setup must resolve non-Compose permission cards through resources", "context.getString(R.string.setup_notifications_card_title)" in setup)
        assertTrue("Setup must localize dynamic Shizuku status", "setup_shizuku_status_transport_unavailable" in setup)
        assertTrue("Setup must localize dynamic Termux status", "setup_termux_status_permission_needed" in setup)
    }

    /**
     * `%%` collapses to one `%` only when the string goes through `String.format`. A string with
     * no placeholder is read with the no-argument `stringResource(id)`, which returns the raw
     * text, so the user sees the escape. That is how the "Run only if" helper came to tell people
     * to type `%%armed == true`, which is exactly the syntax that does not work.
     */
    @Test
    fun anEscapedPercentOnlyAppearsInAStringThatIsActuallyFormatted() {
        val offenders = Files.list(resRoot.resolve("values")).use { paths ->
            paths
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".xml") }
                .toList()
        }.flatMap { file ->
            // <string> and every <item> under <plurals> or <string-array>: a quantity string is
            // read with pluralStringResource and has exactly the same escaping rule.
            val document = newDocumentBuilderFactory().newDocumentBuilder().parse(file.toFile())
            listOf("string", "item").flatMap { tag ->
                val nodes = document.getElementsByTagName(tag)
                (0 until nodes.length).map { index -> nodes.item(index) }
            }.filter { node ->
                val value = node.textContent.trim()
                "%%" in value && !Regex("""%\d+\$""").containsMatchIn(value)
            }.map { node ->
                val name = node.attributes?.getNamedItem("name")?.nodeValue
                    ?: node.parentNode?.attributes?.getNamedItem("name")?.nodeValue
                    ?: node.nodeName
                "${file.fileName}/$name"
            }
        }

        assertEquals(
            "These strings escape a percent but take no format argument, so the user sees %%. " +
                "Use a single % with formatted=\"false\", or give the string a placeholder.",
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * "Blocked" is a promise that something was refused, so only the screen that refuses may say it.
     *
     * The Inspector lists lint findings for profiles that already exist and already run: nothing
     * there is withheld, and one screen over the invariant panel reads "Nothing is blocked". The
     * imported-profile review is the screen that really does withhold the enable button.
     */
    @Test
    fun onlyTheScreenThatWithholdsAnEnableSaysSomethingIsBlocked() {
        val inspector = sourceRoot.resolve("com/opentasker/ui/screens/ContextInspectorScreen.kt").readText()
        val importReview = sourceRoot.resolve("com/opentasker/ui/screens/ImportedProfileRiskDialog.kt").readText()
        val strings = stringResourceValues(resRoot.resolve("values/strings.xml"))

        assertFalse(
            "the Inspector refuses nothing, so it must not use the blocked prefix",
            "automation_lint_blocked_prefix" in inspector,
        )
        assertTrue(
            "a blocking finding still has to outrank a warning in the Inspector",
            "automation_lint_conflict_prefix" in inspector,
        )
        assertTrue(
            "the imported-profile review does withhold the enable button, so it keeps the prefix",
            "automation_lint_blocked_prefix" in importReview,
        )
        assertTrue(
            "the blocked prefix must name what is blocked: ${strings["automation_lint_blocked_prefix"]}",
            strings["automation_lint_blocked_prefix"].orEmpty().contains("enabl", ignoreCase = true),
        )
        // Pinning the id alone let the wording move underneath it: renaming the resource, or
        // pointing the Inspector at a new one whose value is "Blocked:", would have passed.
        assertFalse(
            "the Inspector's prefix must not claim anything is blocked: " +
                "${strings["automation_lint_conflict_prefix"]}",
            strings["automation_lint_conflict_prefix"].orEmpty().contains("block", ignoreCase = true),
        )
        val inspectorPrefixes = Regex("""R\.string\.(automation_lint_\w+)""").findAll(inspector)
            .map { it.groupValues[1] }
            .toSet()
        inspectorPrefixes.forEach { name ->
            assertFalse(
                "the Inspector renders $name, which reads \"${strings[name]}\"",
                strings[name].orEmpty().contains("block", ignoreCase = true),
            )
        }
    }

    @Test
    fun debugBuildGeneratesAndroidPseudoLocales() {
        val buildFile = moduleRoot.resolve("build.gradle.kts").readText()
        assertTrue(
            "Debug builds must enable Android en-XA/ar-XB pseudo locales",
            Regex("""getByName\(\"debug\"\)\s*\{[^}]*isPseudoLocalesEnabled\s*=\s*true""", RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(buildFile),
        )
    }

    @Test
    fun localeDirectoriesMeetTheReleaseCompletionThreshold() {
        val buildFile = moduleRoot.resolve("build.gradle.kts").readText()
        assertTrue(
            "Per-app language selection must be generated by AGP",
            "generateLocaleConfig = true" in buildFile,
        )
        assertTrue(
            "The default locale must be explicit for generated locale config",
            "unqualifiedResLocale=en-US" in resRoot.resolve("resources.properties").readText(),
        )
        val threshold = Regex("""LOCALE_COMPLETION_THRESHOLD\s*=\s*(0\.\d+)""")
            .find(buildFile)
            ?.groupValues
            ?.get(1)
            ?.toDouble()
            ?: error("Locale completion threshold is not declared in the build script")
        val defaultValueFiles = Files.list(resRoot.resolve("values")).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".xml") }.toList()
        }
        assertTrue("Default value resources are missing", defaultValueFiles.isNotEmpty())

        // `values-*` also covers non-locale qualifiers such as values-night, values-land, and
        // values-v31. Treating those as locales made any resource override in one of them read as
        // a 0%-translated language.
        val localeDirectories = Files.list(resRoot).use { paths ->
            paths
                .filter { Files.isDirectory(it) && isLocaleValuesDirectory(it.fileName.toString()) }
                .toList()
        }
        val defaultStrings = defaultValueFiles.flatMap { stringResourceValues(it).entries }.associate { it.toPair() }
        val failures = localeDirectories.mapNotNull { directory ->
            val localeFiles = Files.list(directory).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".xml") }.toList()
            }
            if (localeFiles.isEmpty()) return@mapNotNull "${directory.fileName} contains no XML resources"
            val invalidFiles = localeFiles.mapNotNull { file ->
                runCatching {
                    val root = newDocumentBuilderFactory().newDocumentBuilder().parse(file.toFile()).documentElement.nodeName
                    if (root == "resources") null else "${resRoot.relativize(file)} root=$root"
                }.getOrElse { error -> "${resRoot.relativize(file)} ${error.message}" }
            }
            if (invalidFiles.isNotEmpty()) return@mapNotNull "Invalid XML: $invalidFiles"
            val localeStrings = localeFiles
                .flatMap { stringResourceValues(it).entries }
                .associate { it.toPair() }
            val unknownNames = localeStrings.keys - defaultStrings.keys
            if (unknownNames.isNotEmpty()) {
                return@mapNotNull "${directory.fileName} defines unknown strings: $unknownNames"
            }
            val translated = defaultStrings.count { (name, english) ->
                localeStrings[name] != null && localeStrings[name] != english
            }
            val completion = translated.toDouble() / defaultStrings.size
            if (completion < threshold) {
                "${directory.fileName} is below ${(threshold * 100).toInt()}%: $translated/${defaultStrings.size}"
            } else {
                null
            }
        }

        assertTrue("Locale completeness failures: $failures", failures.isEmpty())
    }

    @Test
    fun localeGateRejectsAnEmptyLocaleDirectoryByName() {
        val gateSource = moduleRoot.toAbsolutePath().normalize().parent
            .resolve("buildSrc/src/main/kotlin/com/opentasker/build/VerifyResourceTasks.kt")
            .readText()

        assertFalse(
            "The release gate must not skip empty locale directories",
            ".filter { localeXmlFiles(it).isNotEmpty() }" in gateSource,
        )
        assertTrue(
            "An empty locale directory must be reported by name",
            "${'$'}{directory.name} contains no XML resources." in gateSource,
        )
    }

    @Test
    fun releaseLocaleGateIgnoresNonLocaleResourceQualifiers() {
        val gateSource = moduleRoot.toAbsolutePath().normalize().parent
            .resolve("buildSrc/src/main/kotlin/com/opentasker/build/VerifyResourceTasks.kt")
            .readText()

        assertTrue(
            "The release gate must filter values directories by locale qualifier",
            "isLocaleValuesDirectory(it.name)" in gateSource,
        )
        assertTrue(
            "The release gate must recognize BCP-47 and Android locale qualifiers",
            "LOCALE_QUALIFIER" in gateSource,
        )
        assertFalse(
            "values-night must not be treated as a translated locale",
            isLocaleValuesDirectory("values-night"),
        )
        assertTrue(isLocaleValuesDirectory("values-en"))
        assertTrue(isLocaleValuesDirectory("values-pt-rBR"))
        assertTrue(isLocaleValuesDirectory("values-b+zh+Hans"))
    }

    /**
     * True only for a `values-<locale>` directory: a two- or three-letter language, optionally with
     * an `-rXX` region, or a BCP-47 `b+` qualifier.
     */
    private fun isLocaleValuesDirectory(name: String): Boolean {
        val qualifier = name.removePrefix("values-").takeIf { it != name } ?: return false
        return LOCALE_QUALIFIER.matches(qualifier)
    }

    private fun defaultStringResourceNames(): Set<String> =
        Files.list(resRoot.resolve("values")).use { paths ->
            paths
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".xml") }
                .flatMap { file ->
                    val document = newDocumentBuilderFactory().newDocumentBuilder().parse(file.toFile())
                    val strings = document.getElementsByTagName("string")
                    (0 until strings.length).map { index ->
                        strings.item(index).attributes.getNamedItem("name").nodeValue
                    }.stream()
                }
                .toList()
                .toSet()
        }

    private fun newDocumentBuilderFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }

    private fun stringResourceValues(file: Path): Map<String, String> {
        val document = newDocumentBuilderFactory().newDocumentBuilder().parse(file.toFile())
        val strings = document.getElementsByTagName("string")
        return (0 until strings.length).associate { index ->
            val item = strings.item(index)
            item.attributes.getNamedItem("name").nodeValue to item.textContent.trim()
        }
    }

    private companion object {
        val LOCALE_QUALIFIER = Regex("""^(b\+[A-Za-z0-9+]+|[a-z]{2,3}(-r[A-Z]{2})?)$""")
    }
}
