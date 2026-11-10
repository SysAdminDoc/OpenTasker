package com.opentasker.docs

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseTruthContractTest {
    private val repoRoot: Path = listOf(Path.of("."), Path.of(".."))
        .first { Files.exists(it.resolve("README.md")) && Files.exists(it.resolve("app/build.gradle.kts")) }
        .toAbsolutePath()
        .normalize()

    @Test
    fun releaseMatricesMatchGradleCatalogAndFdroidMetadata() {
        val readme = read("README.md")
        val gradle = read("app/build.gradle.kts")
        val versions = read("gradle/libs.versions.toml")
        val wrapper = read("gradle/wrapper/gradle-wrapper.properties")
        val fdroidMetadata = read("fdroid/metadata/com.opentasker.app.yml")

        val versionName = gradleValue(gradle, """val\s+appVersionName\s*=\s*"([^"]+)"""")
        val versionCode = gradleValue(gradle, """val\s+appVersionCode\s*=\s*(\d+)""")
        val minSdk = gradleValue(gradle, """minSdk\s*=\s*(\d+)""")
        val compileSdk = gradleValue(gradle, """compileSdk\s*=\s*(\d+)""")
        val targetSdk = gradleValue(gradle, """targetSdk\s*=\s*(\d+)""")
        val buildTools = gradleValue(gradle, """buildToolsVersion\s*=\s*"([^"]+)"""")
        val gradleVersion = gradleValue(wrapper, """gradle-([0-9.]+)-""")

        assertTrue(readme.contains("version-$versionName-blue.svg"))
        assertTableValue(readme, "Kotlin", catalogVersion(versions, "kotlin"))
        assertTableValue(readme, "Gradle", gradleVersion)
        assertTableValue(readme, "AGP", catalogVersion(versions, "agp"))
        assertTableValue(readme, "KSP", catalogVersion(versions, "ksp"))
        assertTableValue(readme, "Build Tools", buildTools)
        assertTableValue(readme, "Min SDK", "$minSdk (Android 8.0)")
        assertTableValue(readme, "Compile SDK", compileSdk)
        assertTableValue(readme, "Target SDK", targetSdk)
        assertTableValue(readme, "Room", catalogVersion(versions, "room"))
        assertTableValue(readme, "Compose BOM", catalogVersion(versions, "composeBom"))
        assertTableValue(readme, "WorkManager", catalogVersion(versions, "work"))
        // The Macrobenchmark row was asserted by nothing until 2026-09-03, so the rc01 to rc02
        // bump could have shipped a stale README claim in silence.
        assertTableValue(readme, "Macrobenchmark", catalogVersion(versions, "benchmark"))

        val dependencyDoc = optionalCurrentDoc("docs/DEPENDENCY_MODERNIZATION.md", "## Batch Log")
        if (dependencyDoc != null) {
            assertDocTableValue(dependencyDoc, "Gradle wrapper", gradleVersion)
            assertDocTableValue(dependencyDoc, "Android Gradle Plugin", catalogVersion(versions, "agp"))
            assertDocTableValue(dependencyDoc, "Compile SDK / Build Tools", "$compileSdk / $buildTools")
            assertDocTableValue(dependencyDoc, "Target SDK", targetSdk)
            assertDocTableValue(dependencyDoc, "Kotlin / Compose compiler plugin", catalogVersion(versions, "kotlin"))
            assertDocTableValue(dependencyDoc, "KSP", catalogVersion(versions, "ksp"))
            assertDocTableValue(dependencyDoc, "Compose BOM", catalogVersion(versions, "composeBom"))
            assertDocTableValue(dependencyDoc, "Room", catalogVersion(versions, "room"))
            assertDocTableValue(dependencyDoc, "WorkManager", catalogVersion(versions, "work"))
            assertDocTableValue(dependencyDoc, "OkHttp / MockWebServer", catalogVersion(versions, "okhttp"))
            assertDocTableValue(dependencyDoc, "Shizuku API / Provider", catalogVersion(versions, "shizuku"))
        }

        assertMetadataValue(fdroidMetadata, "versionName", versionName)
        assertMetadataValue(fdroidMetadata, "versionCode", versionCode)
        assertMetadataValue(fdroidMetadata, "CurrentVersion", versionName)
        assertMetadataValue(fdroidMetadata, "CurrentVersionCode", versionCode)
        assertFalse(fdroidMetadata.contains("Termux script readiness", ignoreCase = true))

        optionalDoc("docs/FDROID_READINESS.md")?.let { fdroidDoc ->
            val commit = metadataValue(fdroidMetadata, "commit")
            listOf(versionName, versionCode, commit, buildTools, "Android SDK $compileSdk", gradleVersion).forEach { claim ->
                assertTrue("docs/FDROID_READINESS.md should contain $claim", fdroidDoc.contains(claim))
            }
        }
    }

    @Test
    fun gradleBootstrapMatchesPinnedOfficialChecksums() {
        val wrapper = read("gradle/wrapper/gradle-wrapper.properties")
        val gate = read("tools/verify-local-release.ps1")
        val wrapperJar = Files.readAllBytes(repoRoot.resolve("gradle/wrapper/gradle-wrapper.jar"))
        val wrapperJarHash = MessageDigest.getInstance("SHA-256")
            .digest(wrapperJar)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

        val distributionHash = gradleValue(
            wrapper,
            """(?m)^distributionSha256Sum=([0-9a-f]{64})$""",
        )
        val pinnedJarHash = gradleValue(
            gate,
            """\${'$'}ExpectedGradleWrapperJarSha256 = "([0-9a-f]{64})""",
        )

        assertEquals(OFFICIAL_GRADLE_9_9_1_BIN_SHA256, distributionHash)
        assertEquals(OFFICIAL_GRADLE_9_9_1_WRAPPER_JAR_SHA256, pinnedJarHash)
        assertEquals(pinnedJarHash, wrapperJarHash)
        assertTrue(gate.contains("\$ExpectedGradleDistributionSha256 = \"$distributionHash\""))
    }

    /**
     * AppVerifier users compare an installed APK against the certificate the project publishes, so
     * the README is the published claim. Staging refuses an APK signed by any other certificate,
     * which keeps the claim and the release key from drifting apart (A-376, Discussion #18).
     */
    @Test
    fun readmePublishesTheReleaseCertificateThatStagingEnforces() {
        val readme = read("README.md")
        val gradle = read("app/build.gradle.kts")

        assertTrue(
            "README.md must publish the release certificate under the package name, as AppVerifier reads it",
            readme.contains("```\ncom.opentasker.app\n$RELEASE_CERTIFICATE_SHA256\n```"),
        )
        assertTrue(
            "stageReleaseAsset must compare the APK's certificate with the one README.md publishes",
            gradle.contains("expectedCertificateSha256.set(") && gradle.contains("publishedCertificateSha256(readme)"),
        )
    }

    @Test
    fun generatedReleaseTruthManifestOwnsArtifactAndCapabilityClaims() {
        val truth = read("tools/release-truth.json")
        val gradle = read("app/build.gradle.kts")
        val versions = read("gradle/libs.versions.toml")
        val wrapper = read("gradle/wrapper/gradle-wrapper.properties")
        val fdroid = read("fdroid/metadata/com.opentasker.app.yml")
        val actionCatalog = read("app/src/main/java/com/opentasker/core/actions/ActionCatalog.kt")
        val contextSpec = com.opentasker.ProductionSources.read("com/opentasker/core/model/ContextSpec.kt")
        val flowStructure = com.opentasker.ProductionSources.read("com/opentasker/core/engine/FlowStructure.kt")
        val taskRunner = com.opentasker.ProductionSources.read("com/opentasker/core/engine/TaskRunner.kt")
        val database = com.opentasker.ProductionSources.read("com/opentasker/core/storage/AppDatabase.kt")
        val bundle = read("app/src/main/java/com/opentasker/core/transfer/OpenTaskerBundle.kt")
        val generator = read("tools/generate-release-truth.ps1")
        val artifactCommit = jsonValue(truth, "requiredArtifactCommit")
        val versionName = jsonValue(truth, "versionName")
        val releaseTag = jsonValue(truth, "releaseTag")
        val releaseTagCommit = Regex("\"releaseTagCommit\"\\s*:\\s*\"([^\"]*)\"").find(truth)?.groupValues?.get(1)
            ?: error("Missing release truth value for releaseTagCommit")

        assertEquals("1", jsonValue(truth, "schemaVersion"))
        assertEquals(gradleValue(gradle, """val\s+appVersionName\s*=\s*"([^"]+)"""), jsonValue(truth, "versionName"))
        assertEquals(gradleValue(gradle, """val\s+appVersionCode\s*=\s*(\d+)"""), jsonValue(truth, "versionCode"))
        assertEquals(gradleValue(gradle, "minSdk\\s*=\\s*(\\d+)"), jsonValue(truth, "minSdk"))
        assertEquals(gradleValue(gradle, "compileSdk\\s*=\\s*(\\d+)"), jsonValue(truth, "compileSdk"))
        assertEquals(gradleValue(gradle, "targetSdk\\s*=\\s*(\\d+)"), jsonValue(truth, "targetSdk"))
        assertEquals(gradleValue(gradle, "buildToolsVersion\\s*=\\s*\"([^\"]+)\""), jsonValue(truth, "buildTools"))
        assertEquals(catalogVersion(versions, "kotlin"), jsonValue(truth, "kotlin"))
        assertEquals(gradleValue(wrapper, """gradle-([0-9.]+)-"""), jsonValue(truth, "gradle"))
        assertEquals(catalogVersion(versions, "agp"), jsonValue(truth, "agp"))
        assertEquals(catalogVersion(versions, "ksp"), jsonValue(truth, "ksp"))
        assertEquals(catalogVersion(versions, "room"), jsonValue(truth, "room"))
        assertEquals(catalogVersion(versions, "composeBom"), jsonValue(truth, "composeBom"))
        assertEquals(catalogVersion(versions, "work"), jsonValue(truth, "work"))
        assertEquals(
            Regex("(?m)^\\s+const val OPEN_TASKER_BUNDLE_SCHEMA_VERSION\\s*=\\s*(\\d+)")
                .find(bundle)?.groupValues?.get(1),
            jsonValue(truth, "bundleSchemaVersion"),
        )
        assertEquals(
            Regex("(?m)^const val OPEN_TASKER_DATABASE_SCHEMA_VERSION\\s*=\\s*(\\d+)")
                .find(database)?.groupValues?.get(1),
            jsonValue(truth, "roomSchemaVersion"),
        )
        // The bundle compatibility promise lives in three places - the codec, the generated release
        // truth, and the published format document. A codec change that moves the accepted range
        // has to move all three.
        val minimumBundleSchema = Regex("(?m)^\\s*const val MIN_SUPPORTED_OPEN_TASKER_BUNDLE_SCHEMA_VERSION\\s*=\\s*(\\d+)")
            .find(bundle)?.groupValues?.get(1)
        val currentBundleSchema = jsonValue(truth, "bundleSchemaVersion")
        assertEquals(
            "$minimumBundleSchema..$currentBundleSchema",
            jsonValue(truth, "bundleSupportedSchemaVersions"),
        )
        // Gitignored like everything else under the docs directory, so it is only checked where it
        // exists. An unconditional read here failed the whole suite on a clean clone.
        optionalDoc("docs/OPEN_JSON_BUNDLE.md")?.let { formatDoc ->
            assertTrue(
                "docs/OPEN_JSON_BUNDLE.md must publish the same supported import range",
                formatDoc.contains("Supported for import: `$minimumBundleSchema..$currentBundleSchema`"),
            )
        }
        assertEquals(
            Regex("(?m)^\\s*define\\(\\\"").findAll(actionCatalog).count().toString(),
            jsonValue(truth, "registeredActions"),
        )
        val flowBody = gradleValue(flowStructure, """(?s)\bval\s+ALL\s*=\s*setOf\(([^)]*)\)""")
        val flowCount = Regex("\\b[A-Z][A-Z0-9_]*\\b").findAll(flowBody).map { it.value }.toSet().size
        val engineCount = flowCount + if ("const val SUB_TASK_ACTION_ID" in taskRunner) 1 else 0
        assertEquals(engineCount.toString(), jsonValue(truth, "engineHandledActions"))
        val contextBody = gradleValue(contextSpec, """(?s)enum class ContextType\s*\{(.*?)\}""")
        assertEquals(
            Regex("(?m)^\\s+[A-Z][A-Z_]+\\s*(,|//)").findAll(contextBody).count().toString(),
            jsonValue(truth, "contextFamilies"),
        )
        assertTrue("verifyReleaseTruth" in gradle)
        // F-Droid builds the release tag; checkupdates writes every later entry the same way.
        assertEquals(releaseTag, metadataValue(fdroid, "commit"))
        assertTrue(Regex("[0-9a-f]{40}").matches(artifactCommit))
        assertEquals("v$versionName", releaseTag)
        // Empty from a version bump until the tag is cut. The generator's -RequireReleaseTag is
        // what insists on it when the release is recorded.
        assertTrue(releaseTagCommit.isEmpty() || Regex("[0-9a-f]{40}").matches(releaseTagCommit))
        assertTrue(Files.exists(repoRoot.resolve("tools/generate-release-truth.ps1")))
        assertTrue("\$flowControlIds.Count" in generator)
        assertTrue("SUB_TASK_ACTION_ID" in generator)
        assertTrue("OPEN_TASKER_DATABASE_SCHEMA_VERSION" in generator)
        assertTrue("ActionCatalog.kt" in generator)
        assertTrue(
            "release-truth generator must read ContextSpec from its owning module",
            """core\model\src\main\kotlin\com\opentasker\core\model\ContextSpec.kt""" in generator,
        )
        assertTrue(
            "release-truth generator must read AppDatabase from its owning module",
            """core\storage\src\main\kotlin\com\opentasker\core\storage\AppDatabase.kt""" in generator,
        )
        assertTrue("RequireReleaseTag" in generator)
        assertTrue("releaseTagCommit" in generator)
        assertTrue("ConvertTo-Json -Depth 6).Replace(\"`r`n\", \"`n\")" in generator)
        assertTrue("releaseTruthJson + \"`n\"" in generator)
        assertFalse("[Environment]::NewLine" in generator)
        assertFalse("engineHandledActions = 7" in generator)
    }

    @Test
    fun capabilityDocumentsMatchStatesDerivedFromShippedSource() {
        requireSourceEvidence(
            "app/src/main/java/com/opentasker/ui/screens/SceneEditorCanvas.kt",
            "SceneAlignmentGuides.findGuides",
            "onResizeElement",
            "selectedIndices",
        )
        requireSourceEvidence(
            "app/src/main/java/com/opentasker/core/scenes/SceneOverlayService.kt",
            "class SceneOverlayService",
            "fireRunTask",
        )
        requireSourceEvidence(
            "app/src/main/java/com/opentasker/ui/screens/AutomationFlowScreen.kt",
            "rememberTransformableState",
            "flow_subflow",
            "flow_branch",
            "onAddContext",
        )
        requireSourceEvidence(
            "app/src/main/java/com/opentasker/core/power/ShizukuPowerBackend.kt",
            "Shizuku.requestPermission",
            "killSwitchEnabled",
            "ShizukuShellRunner.initialize",
            "ShizukuShellRunner.shutdown",
        )
        requireSourceEvidence(
            "app/src/main/java/com/opentasker/core/power/ShizukuShellRunner.kt",
            "ShizukuCommandPolicy",
            "bindUserService",
            "ordinary app processes are never used as a fallback",
        )
        requireSourceEvidence(
            "app/src/main/java/com/opentasker/core/actions/ScriptActions.kt",
            "class TermuxScriptAction",
            "Termux script completed",
        )
        requireSourceEvidence(
            "app/src/main/java/com/opentasker/core/contexts/LocalePluginConditionContextSource.kt",
            "class LocalePluginConditionContextSource",
            "POLL_INTERVAL_MS = 30_000L",
        )
        requireSourceEvidence(
            "app/src/main/java/com/opentasker/core/transfer/TaskerXmlExport.kt",
            "object TaskerXmlExporter",
            "TaskerXmlExportReport",
        )

        documentRules().forEach(::assertDocumentTruthWhenPresent)
    }

    @Test
    fun staleExampleProvesTheDocumentGateFailsClosed() {
        val rule = DocumentTruthRule(
            path = "example.md",
            required = listOf("zoom/pan", "picker-backed add commands"),
            forbidden = listOf("read-only visual flow baseline"),
        )
        val staleExample = "The read-only visual flow baseline renders a graph."

        assertEquals(
            listOf(
                "example.md is missing current claim: zoom/pan",
                "example.md is missing current claim: picker-backed add commands",
                "example.md contains stale claim: read-only visual flow baseline",
            ),
            documentTruthViolations(staleExample, rule),
        )
    }

    /** Documents that are committed, so a missing one is a defect rather than a local absence. */
    private val REQUIRED_DOCUMENTS = setOf("README.md")

    private fun documentRules(): List<DocumentTruthRule> = listOf(
        DocumentTruthRule(
            "README.md",
            required = listOf(
                "Scene element editor with drag-to-move, resize handles, multi-select, alignment guides",
                "Flow graphs with zoom/pan canvas previews, edge routing, branch/subflow markers",
                "persisted default-on kill switch",
                "eight elevated actions run through a separately bound AIDL user service",
                "Termux 0.109+ `RUN_COMMAND` integration",
                "Locale/Tasker condition context",
            ),
            forbidden = listOf(
                "gated Termux script run (not yet wired)",
                "Power-user readiness (detection only)",
                "Read-only flow graphs",
                "Scene multi-select layout edits, alignment guides, and overlay launch",
                "Visual flow editor authoring",
            ),
        ),
        DocumentTruthRule(
            "docs/ARCHITECTURE.md",
            required = listOf(
                "zoom/pan",
                "picker-backed Add Context/Add Step commands",
                "SceneOverlayService",
                "multi-select",
                "alignment guides",
                "links the Shizuku API/provider",
                "default-on kill switch",
                "versioned AIDL user service",
                "Termux scripting boundary dispatches",
                "ContextType.PLUGIN",
                "every 30 seconds",
                "Tasker XML import and bounded best-effort export",
            ),
            forbidden = listOf(
                "Optional Shizuku manager readiness detection",
                "Optional Termux script bridge readiness detection",
                "The read-only visual flow baseline",
                "without launching overlay windows",
                "The Shizuku readiness baseline",
                "does not link the Shizuku API",
                "first-class condition-context picker row",
                "Tasker XML export remains roadmap work",
            ),
        ),
        DocumentTruthRule(
            "docs/SCENES.md",
            required = listOf("SceneOverlayService", "multi-select", "alignment guides"),
            forbidden = listOf("Overlay window launch.", "Resize handles and multi-select layout editing."),
        ),
        DocumentTruthRule(
            "docs/VISUAL_FLOW.md",
            required = listOf("zoom/pan", "edge routing", "branch/subflow markers", "picker-backed add commands"),
            forbidden = listOf("keeps the graph layout read-only", "No branching editor.", "Add zoom gestures only if"),
        ),
        DocumentTruthRule(
            "docs/SHIZUKU.md",
            required = listOf("Shizuku API/provider", "AIDL user service", "allowlisted", "kill switch", "fail closed"),
            forbidden = listOf("readiness baseline", "does not execute privileged work", "No Shizuku API dependency is linked", "All elevated candidates remain `Unsupported`"),
        ),
        DocumentTruthRule(
            "docs/TERMUX_SCRIPTING.md",
            required = listOf("RUN_COMMAND", "TermuxScriptAction", "dispatch", "SHA-256"),
            forbidden = listOf("readiness baseline", "not a script runner yet", "No Termux `RUN_COMMAND` service intent is dispatched"),
        ),
        DocumentTruthRule(
            "docs/LOCALE_PLUGIN_HOST.md",
            required = listOf(
                "Supported in v0.2.75",
                "LocaleSettingEditActivity",
                "LocalePluginConditionContextSource",
                "every 30 seconds",
            ),
            forbidden = listOf(
                "Supported in v0.2.54",
                "Persisted plugin-backed Condition context rows that automatically re-query on `REQUEST_QUERY`.",
            ),
        ),
    )

    private fun assertDocumentTruthWhenPresent(rule: DocumentTruthRule) {
        val text = optionalDoc(rule.path)
        if (text == null) {
            // A rule for a document that is not there protects nothing. Committed documents must
            // exist, otherwise deleting one silently deletes its stale-claim protection with it.
            // The docs tree is gitignored, so only committed paths are required.
            assertFalse(
                "${rule.path} is required but missing; its truth rules were silently skipped",
                rule.path in REQUIRED_DOCUMENTS,
            )
            return
        }
        val violations = documentTruthViolations(text, rule)
        assertTrue(violations.joinToString("\n"), violations.isEmpty())
    }

    private fun requireSourceEvidence(path: String, vararg tokens: String) {
        val text = read(path)
        tokens.forEach { token ->
            assertTrue("$path no longer proves shipped state: $token", text.contains(token, ignoreCase = true))
        }
    }

    private fun optionalCurrentDoc(path: String, historicalHeader: String): String? =
        optionalDoc(path)?.substringBefore(historicalHeader)

    private fun optionalDoc(path: String): String? =
        repoRoot.resolve(path).takeIf(Files::exists)?.readText()

    private fun read(path: String): String = repoRoot.resolve(path).readText()

    private fun gradleValue(text: String, pattern: String): String =
        Regex(pattern).find(text)?.groupValues?.get(1)
            ?: error("Missing value for pattern $pattern")

    private fun catalogVersion(text: String, key: String): String =
        gradleValue(text, """(?m)^$key\s*=\s*"([^"]+)"""")

    private fun metadataValue(text: String, key: String): String =
        text.lineSequence()
            .map(String::trim)
            .map { line -> line.removePrefix("- ") }
            .firstOrNull { line -> line.startsWith("$key:") }
            ?.substringAfter(':')
            ?.trim()
            ?.trim('"')
            ?: error("Missing F-Droid metadata value for $key")

    private fun jsonValue(text: String, key: String): String =
        Regex("\\\"$key\\\"\\s*:\\s*(?:\\\"([^\\\"]+)\\\"|(\\d+))")
            .find(text)
            ?.let { match -> match.groupValues[1].ifBlank { match.groupValues[2] } }
            ?: error("Missing release truth value for $key")

    private fun assertMetadataValue(text: String, key: String, expected: String) {
        assertEquals("F-Droid $key", expected, metadataValue(text, key))
    }

    private fun assertTableValue(text: String, property: String, expected: String) {
        assertTrue("README $property should be $expected", text.contains("| $property | $expected |"))
    }

    private fun assertDocTableValue(text: String, property: String, expected: String) {
        assertTrue("Dependency doc $property should be $expected", text.contains("| $property | $expected |"))
    }

    private companion object {
        const val OFFICIAL_GRADLE_9_9_1_BIN_SHA256 =
            "d8a18a7a841dd5df825d35a685fad6f2d7f12ac0c280e57b67c27b4a520ba228"
        const val OFFICIAL_GRADLE_9_9_1_WRAPPER_JAR_SHA256 =
            "621008afc4f5d59357fcf06ffa644dd5aade5e091bdc363742710445f73cdaf8"

        /** `apksigner verify --print-certs` on the v0.2.93 and v0.2.94 release assets. */
        const val RELEASE_CERTIFICATE_SHA256 =
            "DB:A1:AA:88:E3:7B:90:15:5F:CA:31:35:CA:3B:78:1D:E9:2C:22:51:07:E4:7C:98:06:E7:5B:F8:80:55:FD:D8"
    }
}

internal data class DocumentTruthRule(
    val path: String,
    val required: List<String>,
    val forbidden: List<String>,
)

internal fun documentTruthViolations(text: String, rule: DocumentTruthRule): List<String> = buildList {
    rule.required.forEach { claim ->
        if (!text.contains(claim, ignoreCase = true)) add("${rule.path} is missing current claim: $claim")
    }
    rule.forbidden.forEach { claim ->
        if (text.contains(claim, ignoreCase = true)) add("${rule.path} contains stale claim: $claim")
    }
}
