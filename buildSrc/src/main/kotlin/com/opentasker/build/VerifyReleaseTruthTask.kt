package com.opentasker.build

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class VerifyReleaseTruthTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val truthFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val readmeFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val metadataFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val moduleBuildFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val actionCatalogFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val contextSpecFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val bundleFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val versionCatalogFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val wrapperFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val flowControlFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val taskRunnerFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val databaseFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val changelogFile: RegularFileProperty

    @get:Internal
    abstract val repositoryDirectory: DirectoryProperty

    @TaskAction
    fun verify() {
        val truth = parseTruth(truthFile.get().asFile.readText())
        val expectedKeys = setOf(
            "schemaVersion",
            "requiredArtifactCommit",
            "releaseTag",
            "releaseTagCommit",
            "versionName",
            "versionCode",
            "minSdk",
            "compileSdk",
            "targetSdk",
            "buildTools",
            "kotlin",
            "gradle",
            "agp",
            "ksp",
            "room",
            "composeBom",
            "work",
            "registeredActions",
            "engineHandledActions",
            "contextFamilies",
            "bundleSchemaVersion",
            "bundleSupportedSchemaVersions",
            "roomSchemaVersion",
        )
        check(truth.keys == expectedKeys) {
            "Release truth keys differ from the verified contract. " +
                "Missing=${expectedKeys - truth.keys}; unexpected=${truth.keys - expectedKeys}."
        }

        val moduleBuild = moduleBuildFile.get().asFile.readText()
        val versions = versionCatalogFile.get().asFile.readText()
        val wrapper = wrapperFile.get().asFile.readText()
        val actionCatalog = actionCatalogFile.get().asFile.readText()
        val contextSpec = contextSpecFile.get().asFile.readText()
        val bundle = bundleFile.get().asFile.readText()
        val flowControl = flowControlFile.get().asFile.readText()
        val taskRunner = taskRunnerFile.get().asFile.readText()
        val database = databaseFile.get().asFile.readText()
        val changelog = changelogFile.get().asFile.readText()

        val flowBody = sourceValue(
            flowControl,
            Regex("(?s)\\bval\\s+ALL\\s*=\\s*setOf\\(([^)]*)\\)"),
            "FlowControl.ALL",
        )
        val flowControlIds = Regex("\\b[A-Z][A-Z0-9_]*\\b")
            .findAll(flowBody)
            .map { it.value }
            .toSet()
        check(flowControlIds.isNotEmpty()) { "FlowControl.ALL must contain engine-handled actions." }
        check(Regex("(?m)^\\s*const val SUB_TASK_ACTION_ID\\s*=").containsMatchIn(taskRunner)) {
            "TaskRunner must declare SUB_TASK_ACTION_ID as an engine-handled action."
        }

        val expected = mapOf(
            "schemaVersion" to "1",
            "versionName" to sourceValue(moduleBuild, Regex("val\\s+appVersionName\\s*=\\s*\"([^\"]+)\""), "version name"),
            "versionCode" to sourceValue(moduleBuild, Regex("val\\s+appVersionCode\\s*=\\s*(\\d+)"), "version code"),
            "minSdk" to sourceValue(moduleBuild, Regex("(?m)^\\s*minSdk\\s*=\\s*(\\d+)"), "minimum SDK"),
            "compileSdk" to sourceValue(moduleBuild, Regex("(?m)^\\s*compileSdk\\s*=\\s*(\\d+)"), "compile SDK"),
            "targetSdk" to sourceValue(moduleBuild, Regex("(?m)^\\s*targetSdk\\s*=\\s*(\\d+)"), "target SDK"),
            "buildTools" to sourceValue(moduleBuild, Regex("(?m)^\\s*buildToolsVersion\\s*=\\s*\"([^\"]+)\""), "build tools"),
            "kotlin" to catalogValue(versions, "kotlin"),
            "gradle" to sourceValue(wrapper, Regex("gradle-([0-9.]+)-"), "Gradle version"),
            "agp" to catalogValue(versions, "agp"),
            "ksp" to catalogValue(versions, "ksp"),
            "room" to catalogValue(versions, "room"),
            "composeBom" to catalogValue(versions, "composeBom"),
            "work" to catalogValue(versions, "work"),
            "registeredActions" to Regex("(?m)^\\s*define\\(\\\"")
                .findAll(actionCatalog)
                .count()
                .toString(),
            // Counted like registeredActions, since task.run is no longer the only engine step
            // outside FlowControl.
            "engineHandledActions" to Regex("(?m)^\\s*defineEngine\\(\\\"")
                .findAll(actionCatalog)
                .count()
                .toString(),
            "contextFamilies" to sourceValue(contextSpec, Regex("(?s)enum class ContextType\\s*\\{(.*?)\\}"), "context type enum")
                .let { body -> Regex("(?m)^\\s+[A-Z][A-Z_]+\\s*(,|//)").findAll(body).count().toString() },
            "bundleSchemaVersion" to sourceValue(
                bundle,
                Regex("(?m)^\\s*const val OPEN_TASKER_BUNDLE_SCHEMA_VERSION\\s*=\\s*(\\d+)"),
                "bundle schema version",
            ),
            // The accepted range is a compatibility promise to anything that writes a bundle, so the
            // gate owns the whole range and not just the current version.
            "bundleSupportedSchemaVersions" to listOf(
                sourceValue(
                    bundle,
                    Regex("(?m)^\\s*const val MIN_SUPPORTED_OPEN_TASKER_BUNDLE_SCHEMA_VERSION\\s*=\\s*(\\d+)"),
                    "minimum supported bundle schema version",
                ),
                sourceValue(
                    bundle,
                    Regex("(?m)^\\s*const val OPEN_TASKER_BUNDLE_SCHEMA_VERSION\\s*=\\s*(\\d+)"),
                    "bundle schema version",
                ),
            ).joinToString(".."),
            "roomSchemaVersion" to sourceValue(
                database,
                Regex("(?m)^const val OPEN_TASKER_DATABASE_SCHEMA_VERSION\\s*=\\s*(\\d+)"),
                "Room schema version",
            ),
        )
        expected.forEach { (key, value) ->
            check(truth.getValue(key) == value) {
                "Release truth '$key' expected '$value' but found '${truth.getValue(key)}'."
            }
        }

        val versionName = truth.getValue("versionName")
        val releaseTag = truth.getValue("releaseTag")
        check(releaseTag == "v$versionName") {
            "Release truth tag '$releaseTag' does not match version $versionName."
        }
        val currentTagCommit = verifyAnnotatedReleaseTag(releaseTag, versionName)
        check(truth.getValue("releaseTagCommit") == currentTagCommit) {
            "Release truth releaseTagCommit does not match $releaseTag ($currentTagCommit)."
        }
        verifyVersionedChangelogTags(changelog)

        val artifactCommit = truth.getValue("requiredArtifactCommit")
        check(Regex("[0-9a-f]{40}").matches(artifactCommit)) {
            "Release truth requiredArtifactCommit must be a full lowercase SHA-1."
        }
        val artifactGradle = git("show", "$artifactCommit:app/build.gradle.kts")
        check(sourceValue(artifactGradle, Regex("val\\s+appVersionName\\s*=\\s*\"([^\"]+)\""), "artifact version") == truth.getValue("versionName")) {
            "Required artifact commit has a different version."
        }
        check(sourceValue(artifactGradle, Regex("val\\s+appVersionCode\\s*=\\s*(\\d+)"), "artifact version code") == truth.getValue("versionCode")) {
            "Required artifact commit has a different version code."
        }

        val readme = readmeFile.get().asFile.readText()
        check("version-${truth.getValue("versionName")}-blue.svg" in readme) {
            "README version badge is stale."
        }
        check("### Actions (${truth.getValue("registeredActions")} registered + ${truth.getValue("engineHandledActions")} engine-handled)" in readme) {
            "README action count is stale."
        }
        check("**${truth.getValue("registeredActions")} built-in actions**" in readme) {
            "README built-in action count is stale."
        }
        check("- **${truth.getValue("contextFamilies")} context families**" in readme) {
            "README context-family count is stale."
        }

        val metadata = metadataFile.get().asFile.readText()
        check(metadataValue(metadata, "versionName") == truth.getValue("versionName")) {
            "F-Droid versionName is stale."
        }
        check(metadataValue(metadata, "versionCode") == truth.getValue("versionCode")) {
            "F-Droid versionCode is stale."
        }
        // F-Droid builds the tag. The tag sits on the sync commit after the artifact commit, which
        // only changes metadata, and the APK carries no commit hash, so both build the same bytes.
        check(metadataValue(metadata, "commit") == releaseTag) {
            "F-Droid commit must be the release tag $releaseTag."
        }
        println(
            "Release truth passed for v$versionName (${truth.getValue("versionCode")}); " +
                "tag $releaseTag at $currentTagCommit; artifact $artifactCommit",
        )
    }

    /**
     * The pre-0.2.58 changelog is a historical development ledger: those headings predate the
     * versioned application build and have no release sync commit to tag. From 0.2.58 onward each
     * version has a version-bearing sync commit, so those are the release identities this gate can
     * verify without inventing provenance for the old scaffold notes.
     */
    private fun verifyVersionedChangelogTags(changelog: String) {
        val versions = Regex("(?m)^##\\s+v(\\d+\\.\\d+\\.\\d+)(?:\\s|$)")
            .findAll(changelog)
            .map { it.groupValues[1] }
            .filter(::isVersionedApplicationRelease)
            .distinct()
            .toList()
        check(versions.isNotEmpty()) { "CHANGELOG.md has no versioned release headings." }

        versions.forEach { version ->
            val tagCommit = verifyAnnotatedReleaseTag("v$version", version)
            val taggedBuild = git("show", "$tagCommit:app/build.gradle.kts")
            val taggedVersion = sourceValue(
                taggedBuild,
                Regex("(?m)^\\s*(?:val\\s+appVersionName|versionName)\\s*=\\s*\"([^\"]+)\""),
                "version name for v$version",
            )
            check(taggedVersion == version) {
                "Tag v$version points to $tagCommit, whose application version is $taggedVersion."
            }
        }
    }

    private fun isVersionedApplicationRelease(version: String): Boolean {
        val parts = version.split('.').map(String::toInt)
        return parts[0] > 0 || parts[1] > 2 || (parts[1] == 2 && parts[2] >= 58)
    }

    private fun verifyAnnotatedReleaseTag(tagName: String, expectedVersion: String): String {
        val ref = "refs/tags/$tagName"
        val type = try {
            git("cat-file", "-t", ref)
        } catch (_: IllegalStateException) {
            ""
        }
        check(type == "tag") {
            "Release $expectedVersion requires an annotated Git tag at $tagName."
        }

        val commit = git("rev-list", "-n", "1", ref)
        check(commit.isNotBlank()) { "Release tag $tagName does not resolve to a commit." }
        git("merge-base", "--is-ancestor", commit, "HEAD")
        return commit
    }

    private fun parseTruth(text: String): Map<String, String> {
        val values = linkedMapOf<String, String>()
        val pattern = Regex("\\\"([A-Za-z][A-Za-z0-9]*)\\\"\\s*:\\s*(?:\\\"([^\\\"]*)\\\"|(\\d+))")
        pattern.findAll(text).forEach { match ->
            val key = match.groupValues[1]
            check(values.put(key, match.groupValues[2].ifBlank { match.groupValues[3] }) == null) {
                "Release truth contains duplicate key '$key'."
            }
        }
        check(values.isNotEmpty()) { "Release truth manifest is empty or invalid." }
        return values
    }

    private fun sourceValue(text: String, pattern: Regex, name: String): String =
        pattern.find(text)?.groupValues?.get(1)
            ?: error("Could not derive $name from shipped source.")

    private fun catalogValue(text: String, key: String): String =
        sourceValue(text, Regex("(?m)^$key\\s*=\\s*\"([^\"]+)\""), "$key version")

    private fun metadataValue(text: String, key: String): String =
        Regex("(?m)^\\s*(?:-\\s*)?$key:\\s*(.+?)\\s*$")
            .find(text)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.trim('"', '\'')
            ?: error("F-Droid metadata is missing '$key'.")

    private fun git(vararg args: String): String {
        val process = ProcessBuilder("git", *args)
            .directory(repositoryDirectory.get().asFile)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor() == 0) { "Git ${args.joinToString(" ")} failed: $output" }
        return output
    }
}
