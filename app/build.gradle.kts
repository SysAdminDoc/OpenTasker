import java.net.URLEncoder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.testing.jacoco.tasks.JacocoReport
import com.opentasker.build.VerifyLocaleResourcesTask
import com.opentasker.build.VerifyReleaseTruthTask
import com.opentasker.build.VerifyRoomSchemaTask
import com.opentasker.build.publishedCertificateSha256

// Kept close under the current count. A floor far below it lets a large batch of tests be
// deleted silently; the headroom only absorbs intentional consolidation. The count spans the core
// modules as well as :app (1,518 passing on 2026-09-26, 41 of them in the module suites). The
// floor cannot be what protects those 41, since :app alone soon clears any fixed number, so the
// task also requires every suite in JVM_TEST_MODULES to report passing tests of its own.
private val JVM_TEST_FLOOR = 1490

/** The modules that own JVM tests `:app:testDebugUnitTest` does not run. */
private val JVM_TEST_MODULES = listOf(":core:storage", ":core:engine", ":core:common")

private fun deriveSourceValue(file: java.io.File, pattern: String, name: String): String =
    Regex(pattern).find(file.readText())?.groupValues?.get(1)
        ?: error("Could not derive $name from ${file.path}.")

private fun deriveRegisteredActionCount(file: java.io.File): Int =
    Regex("(?m)^\\s*define\\(\\\"").findAll(file.readText()).count()

private fun deriveContextFamilyCount(file: java.io.File): Int {
    val body = deriveSourceValue(
        file,
        "(?s)enum class ContextType\\s*\\{(.*?)\\}",
        "context type enum",
    )
    return Regex("(?m)^\\s+[A-Z][A-Z_]+\\s*(,|//)").findAll(body).count()
}

private val LOCALE_COMPLETION_THRESHOLD = 0.80

abstract class VerifyDocumentationTruthTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val readmeFile: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val currentDocumentation: ConfigurableFileCollection

    @get:Input
    abstract val versionName: Property<String>

    @get:Input
    abstract val actionCount: Property<Int>

    @get:Input
    abstract val contextFamilyCount: Property<Int>

    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    @TaskAction
    fun verify() {
        val readme = readmeFile.get().asFile.readText()
        check("version-${versionName.get()}-blue.svg" in readme) {
            "README version badge does not match the current application version."
        }
        check("**${actionCount.get()} built-in actions**" in readme && "**${contextFamilyCount.get()} context families**" in readme) {
            "README capability counts do not match the current release contract."
        }

        // A relative README link only resolves on github.com if the target is tracked. Most of this
        // repository's markdown is deliberately ignored, so a link into docs/ reads fine locally and
        // 404s for everyone else - which is how the only documentation link in the README stayed
        // broken.
        val root = repositoryRoot.get().asFile
        val untrackedLinks = Regex("""\[[^\]]*]\((?!https?://|#|mailto:)([^)#]+)[^)]*\)""")
            .findAll(readme)
            .map { it.groupValues[1].trim() }
            .distinct()
            .filter { target ->
                val file = root.resolve(target)
                !file.exists() || !isTrackedByGit(root, target)
            }
            .toList()
        check(untrackedLinks.isEmpty()) {
            "README links to paths that are missing or untracked, so they 404 on github.com: " +
                untrackedLinks.joinToString()
        }

        val currentFiles = currentDocumentation.files.filter { it != readmeFile.get().asFile }
        currentFiles.forEach { file ->
            val relative = file.relativeTo(repositoryRoot.get().asFile)
            val text = file.readText()
            if (file.name == "CHANGELOG.md") {
                check("## v${versionName.get()}" in text) {
                    "$relative has no '## v${versionName.get()}' section for the current release."
                }
            }
            check(versionName.get() in text) {
                "$relative does not mention the current version ${versionName.get()}."
            }
        }

        println("Documentation truth passed: current release claims are checked.")
    }

    private fun isTrackedByGit(root: File, path: String): Boolean {
        if (!root.resolve(".git").exists()) return true
        val process = ProcessBuilder("git", "ls-files", "--error-unmatch", "--", path)
            .directory(root)
            .redirectErrorStream(true)
            .start()
        process.inputStream.bufferedReader().readText()
        return process.waitFor() == 0
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.baselineprofile)
    alias(libs.plugins.screenshot)
    jacoco
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

val releaseKeystorePath = System.getenv("OPEN_TASKER_RELEASE_KEYSTORE")
val releaseKeystorePassword = System.getenv("OPEN_TASKER_RELEASE_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("OPEN_TASKER_RELEASE_KEY_ALIAS")
val releaseKeyPassword = System.getenv("OPEN_TASKER_RELEASE_KEY_PASSWORD")
val appVersionCode = 98
val appVersionName = "0.2.96"
// F-Droid store listing limits, from the F-Droid build metadata reference.
val FDROID_SHORT_DESCRIPTION_MAX_CHARS = 80
val FDROID_CHANGELOG_MAX_CHARS = 500
val FDROID_MIN_SCREENSHOTS = 4
/** Store metadata files are LF regardless of the build host. */
val NEWLINE = 10.toChar().toString()
val allowedDistributions = setOf("standard", "fdroid", "play")
val selectedDistribution = providers.gradleProperty("openTaskerDistribution")
    .orElse("standard")
    .get()
    .lowercase()
require(selectedDistribution in allowedDistributions) {
    "Unsupported OpenTasker distribution '$selectedDistribution'. Expected one of: ${allowedDistributions.joinToString()}."
}
/**
 * F-Droid's build server has no signing material, so the F-Droid distribution packages unsigned by
 * default. The release flow builds the same distribution once more with the release key
 * (`-PopenTaskerSignFdroid=true`) and publishes it next to the standard APK. F-Droid rebuilds it from
 * the tag, copies that signature onto its own unsigned build, and ships our signed APK when the two
 * match byte for byte (`Binaries:` and `AllowedAPKSigningKeys:` in the metadata). That keeps one
 * signing identity across GitHub, Obtainium and F-Droid installs.
 *
 * This also decides the artifact filename AGP produces, which the build recipe and reproducibility
 * harness both name, so the signing config and the expected output path derive from this one value.
 */
val signFdroidBuild = providers.gradleProperty("openTaskerSignFdroid")
    .orElse("false")
    .get()
    .toBoolean()
require(!signFdroidBuild || selectedDistribution == "fdroid") {
    "-PopenTaskerSignFdroid=true only applies to -PopenTaskerDistribution=fdroid."
}
val releaseBuildIsUnsigned = selectedDistribution == "fdroid" && !signFdroidBuild
/** What F-Droid's server builds (unsigned); the metadata recipe's `output:` must name this path. */
val fdroidRecipeApkPath = "app/build/outputs/apk/release/app-release-unsigned.apk"
val expectedReleaseApkPath = if (releaseBuildIsUnsigned) {
    fdroidRecipeApkPath
} else {
    "app/build/outputs/apk/release/app-release.apk"
}
/** Both published APKs sit on one release page, so the F-Droid build carries a suffix. */
val releaseAssetFileName = if (selectedDistribution == "fdroid") {
    "OpenTasker-v$appVersionName-fdroid.apk"
} else {
    "OpenTasker-v$appVersionName.apk"
}
val releaseAssetDirectoryName = if (selectedDistribution == "fdroid") {
    "outputs/release-assets-fdroid"
} else {
    "outputs/release-assets"
}
val smsActionAvailable = selectedDistribution != "play"
val smsReceiveAvailable = selectedDistribution != "play"
val hasReleaseSigning = listOf(
    releaseKeystorePath,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() }
/**
 * Opt out of the packaging-time signing guard for a build whose APK is thrown away, which is what
 * the local gate's Play lane does when it only wants the merged manifest checked. This never
 * reaches a release page: `stageReleaseAsset` verifies the artifact really is signed and has no
 * corresponding opt-out.
 */
val allowUnsignedRelease = providers.gradleProperty("openTaskerAllowUnsignedRelease")
    .orElse("false")
    .get()
    .toBoolean()

android {
    namespace = "com.opentasker.app"
    compileSdk = 37
    buildToolsVersion = "36.0.0"
    experimentalProperties["android.experimental.enableScreenshotTest"] = true

    defaultConfig {
        applicationId = "com.opentasker.app"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "DISTRIBUTION", "\"$selectedDistribution\"")
        buildConfigField("Boolean", "SMS_ACTION_AVAILABLE", smsActionAvailable.toString())
        buildConfigField("Boolean", "SMS_RECEIVE_AVAILABLE", smsReceiveAvailable.toString())
        manifestPlaceholders["smsPermissionName"] = if (smsActionAvailable) "android.permission.SEND_SMS" else "android.permission.INTERNET"
        manifestPlaceholders["smsReceivePermissionName"] = if (smsReceiveAvailable) "android.permission.RECEIVE_SMS" else "android.permission.INTERNET"
        manifestPlaceholders["smsMmsPermissionName"] = if (smsReceiveAvailable) "android.permission.RECEIVE_MMS" else "android.permission.INTERNET"
        manifestPlaceholders["smsWapPushPermissionName"] = if (smsReceiveAvailable) "android.permission.RECEIVE_WAP_PUSH" else "android.permission.INTERNET"
        manifestPlaceholders["smsTriggerEnabled"] = smsReceiveAvailable.toString()
        manifestPlaceholders["phoneStatePermissionName"] = if (smsActionAvailable) "android.permission.READ_PHONE_STATE" else "android.permission.ACCESS_NETWORK_STATE"
        manifestPlaceholders["callLogPermissionName"] = if (smsActionAvailable) "android.permission.READ_CALL_LOG" else "android.permission.ACCESS_NETWORK_STATE"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            isPseudoLocalesEnabled = true
        }
    release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // F-Droid's server builds the fdroid distribution with no key, which is also what
            // makes AGP name the artifact `app-release-unsigned.apk`, the path the build recipe and
            // the reproducibility harness both expect. Every other build requires external signing.
            signingConfig = if (releaseBuildIsUnsigned) {
                null
            } else {
                signingConfigs.findByName("release")
            }
            // AGP writes the build's Git commit into META-INF/version-control-info.textproto. The
            // release APK is built at the version-bump commit and the tag lands on the metadata
            // sync commit after it, so F-Droid's rebuild from the tag would differ by that hash
            // alone and fail its reproducibility check.
            vcsInfo {
                include = false
            }
        }
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    // AGP otherwise puts an encrypted dependency list (DEPENDENCY_INFO_BLOCK) in the APK signing
    // block. Only Google Play can read it, IzzyOnDroid's scanner flags it, and F-Droid asks for it
    // to be off because nobody can audit what it contains.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        abortOnError = true
        // :app:lintDebug analysed :app only, so core/storage - Room, SQLCipher, the Keystore key
        // and the backup and restore paths - was never linted at all. checkDependencies pulls the
        // library modules into the same run.
        checkDependencies = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        aidl = true
        compose = true
        buildConfig = true
    }

    androidResources {
        generateLocaleConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // Every native library here is a prebuilt from a dependency. Stripping them runs the NDK's
        // llvm-strip, so the APK would differ by NDK version, or with no NDK at all, and F-Droid's
        // rebuild couldn't match ours. Only DataStore's tiny counter lib carries symbols (~10 KB).
        jniLibs.keepDebugSymbols += "**/*.so"
    }

    sourceSets {
        getByName("androidTest").assets.directories.add("$projectDir/schemas")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    // Gradle resolves the highest stdlib any dependency asks for, so a single transitive bump
    // would silently move off the verified Kotlin version without touching the catalog or the dependency
    // verification file. `strictly` turns that into a resolution failure instead.
    constraints {
        implementation("org.jetbrains.kotlin:kotlin-stdlib") {
            version { strictly(libs.versions.kotlin.get()) }
            because("The stdlib must match the pinned Kotlin version and its recorded checksum.")
        }
        // Compose UI 1.11.4 depended on androidx.transition and pulled the instrumentation graph
        // up to 1.6.0. Compose 1.12.0 dropped that dependency, so the only remaining requester is
        // an old accessibility-test-framework chain asking for 1.2.0 - a 2019 artifact published
        // before Google Maven signed or checksummed anything. Hold the graph where it already was.
        androidTestImplementation(libs.androidx.transition) {
            version { require(libs.versions.androidxTransition.get()) }
            because("Instrumentation must not silently fall back to an unsigned 2019 transition build.")
        }
    }
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:storage"))
    implementation(project(":core:engine"))
    implementation(project(":feature:automation"))
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.sqlcipher.android)
    ksp(libs.androidx.room.compiler)

    implementation(libs.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.profileinstaller)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    implementation(libs.re2j)
    implementation(libs.jsoup)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.aidl)
    implementation(libs.shizuku.provider)
    implementation(libs.unifiedpush.connector)
    baselineProfile(project(":baselineprofile"))

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.bouncycastle.bcpkix)
    testImplementation(libs.work.testing)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4-accessibility")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    screenshotTestImplementation(libs.screenshot.validation.api)
    screenshotTestImplementation(libs.androidx.compose.ui.tooling)
}

// Fuzzing is deliberately isolated from every Android and release configuration. The target
// source lives under src/fuzzTest, the dependency is resolved only by these opt-in tasks, and the
// normal localQualityGate never depends on either task.
val fuzzRuntimeClasspath = configurations.create("fuzzRuntimeClasspath") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    add(fuzzRuntimeClasspath.name, libs.jazzer)
    add(fuzzRuntimeClasspath.name, libs.junit)
}

val debugKotlinClasses = layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")
val debugJavaClasses = layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes")
val unitTestKotlinClasses = layout.buildDirectory.dir("intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes")
val unitTestJavaClasses = layout.buildDirectory.dir("intermediates/javac/debugUnitTest/compileDebugUnitTestJavaWithJavac/classes")
val fuzzSourceDirectory = layout.projectDirectory.dir("src/fuzzTest/java")
val fuzzClassesDirectory = layout.buildDirectory.dir("intermediates/fuzzTest/classes")
val fuzzCorpusDirectory = layout.projectDirectory.dir("src/fuzzTest/corpus/external-decoders")
val fuzzRegressionDirectory = layout.projectDirectory.dir("src/fuzzTest/regression/external-decoders")
val fuzzWorkingCorpusDirectory = layout.buildDirectory.dir("fuzz/corpus")
val fuzzClasspath = files(
    debugKotlinClasses,
    debugJavaClasses,
    unitTestKotlinClasses,
    unitTestJavaClasses,
    fuzzRuntimeClasspath,
)

val compileFuzzTestJava = tasks.register<JavaCompile>("compileFuzzTestJava") {
    group = "verification"
    description = "Compiles the opt-in JVM fuzz target without adding it to an Android variant."
    dependsOn(
        "compileDebugKotlin",
        "compileDebugJavaWithJavac",
        "compileDebugUnitTestKotlin",
        "compileDebugUnitTestJavaWithJavac",
    )
    source(fileTree(fuzzSourceDirectory))
    destinationDirectory.set(fuzzClassesDirectory)
    classpath = fuzzClasspath
    doFirst {
        classpath = files(
            fuzzClasspath,
            tasks.named<JavaCompile>("compileDebugJavaWithJavac").get().classpath,
        )
    }
    options.encoding = "UTF-8"
    options.release.set(17)
}

val fuzzSeconds = providers.gradleProperty("fuzzSeconds").orElse("30")
val fuzzArtifactsDirectory = layout.buildDirectory.dir("fuzz/artifacts")
val fuzzStderrFile = layout.buildDirectory.file("fuzz/stderr.log")
val fuzzJavaExecClasspath = files(fuzzClassesDirectory, fuzzClasspath)
val prepareFuzzCorpus = tasks.register<Sync>("prepareFuzzCorpus") {
    group = "verification"
    description = "Copies the checked-in decoder seeds into the ignored fuzz workspace."
    from(fuzzCorpusDirectory)
    into(fuzzWorkingCorpusDirectory)
}

tasks.register<JavaExec>("fuzzExternalDecoders") {
    group = "verification"
    description = "Runs the opt-in coverage-guided fuzz target for external decoders."
    dependsOn(compileFuzzTestJava, prepareFuzzCorpus)
    mainClass.set("com.code_intelligence.jazzer.Jazzer")
    classpath = fuzzJavaExecClasspath
    doFirst {
        fuzzArtifactsDirectory.get().asFile.mkdirs()
        fuzzStderrFile.get().asFile.parentFile.mkdirs()
        errorOutput = fuzzStderrFile.get().asFile.outputStream()
        classpath = files(
            fuzzJavaExecClasspath,
            tasks.named<JavaCompile>("compileDebugJavaWithJavac").get().classpath,
        )
    }
    workingDir = rootProject.projectDir
    args(
        "--target_class=com.opentasker.fuzz.ExternalDecoderFuzzTarget",
        "--instrumentation_includes=com.opentasker.**",
        "-max_total_time=${fuzzSeconds.get()}",
        "-artifact_prefix=${fuzzArtifactsDirectory.get().asFile.absolutePath}${File.separator}",
        fuzzWorkingCorpusDirectory.get().asFile.absolutePath,
    )
}

tasks.register<JavaExec>("fuzzExternalDecoderRegression") {
    group = "verification"
    description = "Runs the checked-in deterministic external-decoder crash regression corpus."
    dependsOn(compileFuzzTestJava)
    mainClass.set("org.junit.runner.JUnitCore")
    classpath = fuzzJavaExecClasspath
    doFirst {
        check(fuzzRegressionDirectory.asFile.isDirectory) {
            "Missing decoder regression corpus at ${fuzzRegressionDirectory.asFile}"
        }
        classpath = files(
            fuzzJavaExecClasspath,
            tasks.named<JavaCompile>("compileDebugJavaWithJavac").get().classpath,
        )
    }
    workingDir = rootProject.projectDir
    args("com.opentasker.fuzz.ExternalDecoderRegressionTest")
}

abstract class VerifyResolvedDependencyPolicyTask : org.gradle.api.DefaultTask() {
    @get:org.gradle.api.tasks.Input
    abstract val components: org.gradle.api.provider.ListProperty<String>

    @get:org.gradle.api.tasks.InputFile
    @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    abstract val versionCatalog: org.gradle.api.file.RegularFileProperty

    @get:org.gradle.api.tasks.InputFile
    @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    abstract val moduleBuildFile: org.gradle.api.file.RegularFileProperty

    @get:org.gradle.api.tasks.InputFile
    @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    abstract val settingsFile: org.gradle.api.file.RegularFileProperty

    @get:org.gradle.api.tasks.InputFile
    @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    abstract val verificationMetadata: org.gradle.api.file.RegularFileProperty

    @org.gradle.api.tasks.TaskAction
    fun verify() {
        val resolved = components.get().distinct().sorted()
        check(resolved.isNotEmpty()) { "Release runtime dependency graph is empty." }

        val forbiddenNetty = resolved.filter { coordinate ->
            coordinate.startsWith("io.netty:") || coordinate.startsWith("io.grpc:grpc-netty:")
        }
        check(forbiddenNetty.isEmpty()) {
            "Release runtime must not ship Netty or grpc-netty components: ${forbiddenNetty.joinToString()}"
        }

        val invalidResolved = resolved.filter { coordinate ->
            val version = coordinate.split(':', limit = 3).getOrNull(2).orEmpty()
            version.isBlank() || version.isDynamicVersion()
        }
        check(invalidResolved.isEmpty()) {
            "Resolved dependency graph contains missing or dynamic versions: ${invalidResolved.joinToString()}"
        }

        val catalogVersions = Regex("""(?m)^\s*[A-Za-z0-9_.-]+\s*=\s*"([^"]+)"\s*$""")
            .findAll(versionCatalog.get().asFile.readText())
            .map { match -> match.groupValues[1] }
        val buildVersions = Regex("""["'](?:[A-Za-z0-9_.-]+):(?:[A-Za-z0-9_.-]+):([^"']+)["']""")
            .findAll(moduleBuildFile.get().asFile.readText())
            .map { match -> match.groupValues[1] }
        val invalidDeclared = (catalogVersions + buildVersions)
            .filter { version -> version.isDynamicVersion() }
            .distinct()
            .sorted()
            .toList()
        check(invalidDeclared.isEmpty()) {
            "Dynamic or snapshot dependency declarations are forbidden: ${invalidDeclared.joinToString()}"
        }

        val settings = settingsFile.get().asFile.readText()
        check("RepositoriesMode.FAIL_ON_PROJECT_REPOS" in settings) {
            "Dependency repositories must remain centralized with FAIL_ON_PROJECT_REPOS."
        }
        val repositoryBlocks = Regex(
            """repositories\s*\{([^{}]*)}""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        ).findAll(settings).map { match -> match.groupValues[1] }.toList()
        check(repositoryBlocks.size == 2) {
            "Expected exactly the centralized plugin and dependency repository blocks."
        }
        val allowedRepositoryCalls = setOf("google()", "mavenCentral()", "gradlePluginPortal()")
        val repositoryCalls = repositoryBlocks.flatMap { block ->
            block.lineSequence()
                .map { line -> line.substringBefore("//").trim() }
                .filter(String::isNotEmpty)
                .toList()
        }
        val forbiddenRepositoryCalls = repositoryCalls.filterNot { call -> call in allowedRepositoryCalls }
        check(forbiddenRepositoryCalls.isEmpty()) {
            "Only google(), mavenCentral(), and gradlePluginPortal() repositories are allowed; found ${forbiddenRepositoryCalls.joinToString()}."
        }

        val verification = verificationMetadata.get().asFile.readText()
        check("<verify-metadata>true</verify-metadata>" in verification && "<sha256 value=" in verification) {
            "Gradle dependency verification metadata must enforce SHA-256 checksums."
        }
        check("<verify-signatures>true</verify-signatures>" in verification) {
            "Gradle dependency verification metadata must enforce signature verification."
        }
        check("<trusted-key " in verification && "<trusted-artifacts" !in verification) {
            "Dependency verification must use an explicit trusted-key set without blanket trust."
        }
        val checksumCount = Regex("<sha256\\b").findAll(verification).count()
        val provenanceCount = Regex("<sha256\\b[^>]*\\borigin=\"([^\"]+)\"").findAll(verification).count()
        check(checksumCount > 0 && checksumCount == provenanceCount && "Generated by Gradle" !in verification) {
            "Every dependency checksum must carry reviewed upstream provenance; Gradle-generated origins are forbidden."
        }
        println("Resolved dependency policy passed for ${resolved.size} release runtime components.")
    }

    private fun String.isDynamicVersion(): Boolean {
        val normalized = lowercase()
        return '+' in this || normalized.startsWith("latest.") || normalized.endsWith("-snapshot") ||
            startsWith('[') || startsWith('(')
    }
}

abstract class GenerateCycloneDxSbomTask : org.gradle.api.DefaultTask() {
    @get:org.gradle.api.tasks.Input
    abstract val components: org.gradle.api.provider.ListProperty<String>

    @get:org.gradle.api.tasks.Input
    abstract val applicationVersion: org.gradle.api.provider.Property<String>

    @get:org.gradle.api.tasks.OutputFile
    abstract val outputFile: org.gradle.api.file.RegularFileProperty

    @org.gradle.api.tasks.TaskAction
    fun generate() {
        val entries = components.get()
            .distinct()
            .sorted()
            .map { coordinate ->
                val (group, name, version) = coordinate.split(':', limit = 3)
                val purl = "pkg:maven/$group/$name@${URLEncoder.encode(version, Charsets.UTF_8.name()).replace("+", "%20")}"
                """    {"type":"library","bom-ref":${purl.json()},"group":${group.json()},"name":${name.json()},"version":${version.json()},"purl":${purl.json()}}"""
            }
        val version = applicationVersion.get()
        val appPurl = "pkg:generic/OpenTasker@$version"
        val output = outputFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            buildString {
                appendLine("{")
                appendLine("  \"\$schema\": \"https://cyclonedx.org/schema/bom-1.6.schema.json\",")
                appendLine("  \"bomFormat\": \"CycloneDX\",")
                appendLine("  \"specVersion\": \"1.6\",")
                appendLine("  \"version\": 1,")
                appendLine("  \"metadata\": {\"component\": {\"type\":\"application\",\"bom-ref\":${appPurl.json()},\"group\":\"com.opentasker\",\"name\":\"OpenTasker\",\"version\":${version.json()},\"purl\":${appPurl.json()}}},")
                appendLine("  \"components\": [")
                appendLine(entries.joinToString(",\n"))
                appendLine("  ]")
                appendLine("}")
            }
        )
        println("CycloneDX SBOM wrote ${entries.size} components to ${output.absolutePath}")
    }

    private fun String.json(): String = buildString {
        append('"')
        this@json.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }
}

abstract class VerifyJvmTestCountTask : org.gradle.api.DefaultTask() {
    /**
     * Every module's JVM results, not just `:app`'s. The gate read one directory, so the three
     * core-module suites counted for nothing and deleting all of them would have moved the floor by
     * zero. Each directory must report passing tests of its own (see the check below): an aggregate
     * floor alone could not protect them, because `:app` keeps growing and soon clears it without
     * them.
     */
    @get:org.gradle.api.tasks.InputFiles
    @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    abstract val resultsDirectories: org.gradle.api.file.ConfigurableFileCollection

    @get:org.gradle.api.tasks.Input
    abstract val minimumTests: org.gradle.api.provider.Property<Int>

    @get:org.gradle.api.tasks.OutputFile
    abstract val reportFile: org.gradle.api.file.RegularFileProperty

    @org.gradle.api.tasks.TaskAction
    fun verify() {
        val suites = resultsDirectories.files.map { directory ->
            directory to directory.listFiles { file ->
                file.isFile && file.name.startsWith("TEST-") && file.extension == "xml"
            }.orEmpty().asList()
        }
        fun List<java.io.File>.total(attribute: String): Int = sumOf { report ->
            Regex("""\b$attribute="(\d+)"""").find(report.readText())?.groupValues?.get(1)?.toInt() ?: 0
        }
        // A suite that reports nothing fails here by name, whatever the total says: its tests
        // deleted (the task goes NO-SOURCE and clears its old results) or every one skipped. A
        // task excluded with -x is only caught on a clean build directory, because Gradle keeps
        // the results of its last run and they are read like new ones. The gate excludes nothing.
        val silent = suites.filter { (_, reports) -> reports.total("tests") - reports.total("skipped") <= 0 }
        check(silent.isEmpty()) {
            "No passing JVM tests reported from: ${silent.joinToString { it.first.path }}"
        }
        val reports = suites.flatMap { it.second }
        check(reports.isNotEmpty()) { "No JVM test XML reports were produced." }
        fun count(attribute: String): Int = reports.total(attribute)
        // JUnit's tests= attribute includes skipped tests, so an assumption-skip or @Ignore could
        // satisfy the floor while asserting nothing.
        val tests = count("tests") - count("skipped")
        val failures = count("failures")
        val errors = count("errors")
        val floor = minimumTests.get()
        val report = reportFile.get().asFile
        report.parentFile.mkdirs()
        report.writeText(
            """{
  "schemaVersion": 1,
  "observedTests": $tests,
  "configuredFloor": $floor,
  "failures": $failures,
  "errors": $errors,
  "status": "${if (failures == 0 && errors == 0 && tests >= floor) "passed" else "failed"}"
}
""",
        )
        check(failures == 0 && errors == 0) { "JVM tests reported $failures failure(s) and $errors error(s)." }
        check(tests >= floor) {
            "JVM test floor regressed: observed $tests, configured floor $floor."
        }
        println("JVM test gate passed: observed $tests tests, configured floor $floor, 0 failures, 0 errors.")
    }
}

abstract class VerifyCoverageFloorTask : org.gradle.api.DefaultTask() {
    @get:org.gradle.api.tasks.InputFile
    @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    abstract val reportFile: org.gradle.api.file.RegularFileProperty

    @get:org.gradle.api.tasks.Input
    abstract val areaFloors: org.gradle.api.provider.MapProperty<String, Double>

    @org.gradle.api.tasks.TaskAction
    fun verify() {
        val report = reportFile.get().asFile
        check(report.isFile) { "JaCoCo XML report is missing: ${report.absolutePath}" }

        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val document = factory.newDocumentBuilder().parse(report)
        val packages = document.getElementsByTagName("package")

        areaFloors.get().forEach { (area, minimumPercent) ->
            var missed = 0L
            var covered = 0L
            var matched = false
            for (index in 0 until packages.length) {
                val packageNode = packages.item(index)
                val packageName = packageNode.attributes?.getNamedItem("name")?.nodeValue ?: continue
                if (packageName != area && !packageName.startsWith("$area/")) continue
                matched = true
                val counters = packageNode.childNodes
                for (counterIndex in 0 until counters.length) {
                    val counter = counters.item(counterIndex)
                    if (counter.nodeName != "counter" ||
                        counter.attributes?.getNamedItem("type")?.nodeValue != "INSTRUCTION"
                    ) continue
                    missed += counter.attributes?.getNamedItem("missed")?.nodeValue?.toLongOrNull() ?: 0L
                    covered += counter.attributes?.getNamedItem("covered")?.nodeValue?.toLongOrNull() ?: 0L
                }
            }
            check(matched) { "JaCoCo report contains no classes for coverage area '$area'." }
            val total = missed + covered
            check(total > 0L) { "JaCoCo report contains no instructions for coverage area '$area'." }
            val percent = covered.toDouble() * 100.0 / total
            check(percent + 0.000_001 >= minimumPercent) {
                "Coverage floor regressed for $area: %.2f%%, expected at least %.2f%%.".format(
                    percent,
                    minimumPercent,
                )
            }
            println("Coverage floor passed: $area %.2f%% >= %.2f%%.".format(percent, minimumPercent))
        }
    }
}

abstract class VerifyQualityGateSeedTask : org.gradle.api.DefaultTask() {
    @get:org.gradle.api.tasks.Input
    abstract val seedFailure: org.gradle.api.provider.Property<Boolean>

    @org.gradle.api.tasks.TaskAction
    fun verify() {
        check(!seedFailure.get()) { "Seeded local quality-gate failure." }
    }
}

abstract class VerifyPackagedTypeCompletenessTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apks: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        val apkFiles = apks.files.sortedBy { it.name }
        check(apkFiles.isNotEmpty()) { "No APKs were supplied for packaged-type verification." }

        apkFiles.forEach { apkFile ->
            check(apkFile.isFile) {
                "APK for packaged-type verification is missing: ${apkFile.absolutePath}"
            }
            val referenced = linkedSetOf<String>()
            val defined = linkedSetOf<String>()
            var dexCount = 0

            ZipFile(apkFile).use { archive ->
                archive.entries().asSequence()
                    .filter { entry -> DEX_ENTRY_PATTERN.matches(entry.name) }
                    .sortedBy { entry -> entry.name }
                    .forEach { entry ->
                        check(entry.size in 1..MAX_DEX_BYTES) {
                            "${apkFile.name}/${entry.name} has an invalid audit size: ${entry.size} bytes"
                        }
                        val inventory = dexTypeInventory(
                            archive.getInputStream(entry).use { stream -> stream.readBytes() },
                            "${apkFile.name}/${entry.name}",
                        )
                        referenced += inventory.referenced
                        defined += inventory.defined
                        dexCount += 1
                    }
            }

            check(dexCount > 0) { "${apkFile.name} contains no classes*.dex entries." }
            val missing = (referenced - defined).sorted()
            check(missing.isEmpty()) {
                buildString {
                    appendLine("${apkFile.name} references OpenTasker types that it does not define:")
                    missing.forEach { descriptor -> appendLine("  $descriptor") }
                    append("Every Lcom/opentasker/ reference must be packaged in the same APK.")
                }
            }
            println(
                "Packaged-type completeness passed for ${apkFile.name}: " +
                    "$dexCount dex file(s), ${defined.size} OpenTasker type(s) defined.",
            )
        }
    }

    private fun dexTypeInventory(bytes: ByteArray, source: String): DexTypeInventory {
        check(bytes.size >= DEX_HEADER_BYTES) { "$source is too small to be a DEX file." }
        check(bytes[0] == 'd'.code.toByte() && bytes[1] == 'e'.code.toByte() &&
            bytes[2] == 'x'.code.toByte() && bytes[3] == '\n'.code.toByte()) {
            "$source does not use the standard DEX format."
        }

        fun uint(offset: Int): Long {
            check(offset >= 0 && offset + 4 <= bytes.size) { "$source has a truncated DEX integer." }
            return (bytes[offset].toLong() and 0xff) or
                ((bytes[offset + 1].toLong() and 0xff) shl 8) or
                ((bytes[offset + 2].toLong() and 0xff) shl 16) or
                ((bytes[offset + 3].toLong() and 0xff) shl 24)
        }

        fun table(sizeOffset: Int, dataOffset: Int, width: Int, label: String): Pair<Int, Int> {
            val count = uint(sizeOffset)
            val offset = uint(dataOffset)
            check(count <= Int.MAX_VALUE && offset <= Int.MAX_VALUE) {
                "$source has an oversized $label table."
            }
            check(offset + count * width <= bytes.size.toLong()) {
                "$source has a truncated $label table."
            }
            return count.toInt() to offset.toInt()
        }

        val (stringCount, stringOffset) = table(56, 60, 4, "string-id")
        val (typeCount, typeOffset) = table(64, 68, 4, "type-id")
        val (classCount, classOffset) = table(96, 100, 32, "class-def")
        val stringCache = arrayOfNulls<String>(stringCount)

        fun stringAt(index: Int): String {
            check(index in 0 until stringCount) { "$source references invalid string index $index." }
            stringCache[index]?.let { return it }
            val dataOffset = uint(stringOffset + index * 4)
            check(dataOffset < bytes.size) { "$source references a string outside the DEX file." }
            var cursor = dataOffset.toInt()
            var lengthBytes = 0
            var value: Int
            do {
                check(cursor < bytes.size && lengthBytes < 5) {
                    "$source has a malformed string length."
                }
                value = bytes[cursor].toInt() and 0xff
                cursor += 1
                lengthBytes += 1
            } while (value and 0x80 != 0)
            var end = cursor
            while (end < bytes.size && bytes[end] != 0.toByte()) end += 1
            check(end < bytes.size) { "$source has an unterminated DEX string." }
            return String(bytes, cursor, end - cursor, Charsets.UTF_8).also { stringCache[index] = it }
        }

        val typeDescriptors = ArrayList<String>(typeCount)
        repeat(typeCount) { index ->
            val descriptorIndex = uint(typeOffset + index * 4)
            check(descriptorIndex <= Int.MAX_VALUE) { "$source has an oversized descriptor index." }
            typeDescriptors += stringAt(descriptorIndex.toInt())
        }
        val referenced = typeDescriptors.mapNotNullTo(linkedSetOf(), ::openTaskerDescriptor)
        val defined = linkedSetOf<String>()
        repeat(classCount) { index ->
            val classIndex = uint(classOffset + index * 32)
            check(classIndex < typeDescriptors.size) { "$source has an invalid class type index." }
            openTaskerDescriptor(typeDescriptors[classIndex.toInt()])?.let(defined::add)
        }
        return DexTypeInventory(referenced = referenced, defined = defined)
    }

    private fun openTaskerDescriptor(descriptor: String): String? {
        val component = descriptor.dropWhile { character -> character == '[' }
        return component.takeIf { value -> value.startsWith(OPEN_TASKER_DESCRIPTOR_PREFIX) }
    }

    private data class DexTypeInventory(
        val referenced: Set<String>,
        val defined: Set<String>,
    )

    private companion object {
        val DEX_ENTRY_PATTERN = Regex("classes(?:\\d+)?\\.dex")
        const val DEX_HEADER_BYTES = 112
        const val MAX_DEX_BYTES = 256L * 1024 * 1024
        const val OPEN_TASKER_DESCRIPTOR_PREFIX = "Lcom/opentasker/"
    }
}

/**
 * Extracts every regex literal declared in production source into an asset the instrumented suite
 * compiles on-device.
 *
 * Android's regex engine is ICU. It rejects patterns that desktop `java.util.regex` accepts, and
 * this project has shipped that defect three separate times. A pattern in a `companion object` is
 * the worst case: an ICU rejection there throws from the class initializer, so the entire enclosing
 * class becomes unusable rather than one call failing. A JVM suite structurally cannot see any of
 * it, because it runs against the desktop engine.
 *
 * Patterns containing a `$` template are recorded as skipped rather than dropped, because the build
 * cannot resolve them and silently omitting them would make the corpus look complete.
 */
abstract class GenerateRegexCorpusTask : org.gradle.api.DefaultTask() {
    @get:org.gradle.api.tasks.InputFiles
    @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    abstract val sources: org.gradle.api.file.ConfigurableFileCollection

    @get:org.gradle.api.tasks.OutputDirectory
    abstract val outputDirectory: org.gradle.api.file.DirectoryProperty

    @org.gradle.api.tasks.TaskAction
    fun generate() {
        val extracted = linkedSetOf<String>()
        var skipped = 0

        sources.files.filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.forEach { file ->
            val text = file.readText()
            forEachConstructorCall(text) { argumentStart ->
                when (val literal = readStringLiteral(text, argumentStart)) {
                    // The argument is a variable, a concatenation, or an interpolated template, so
                    // the build cannot know the pattern. Counted, never silently dropped.
                    null -> skipped++
                    else -> extracted += literal
                }
            }
        }

        val output = outputDirectory.get().asFile
        output.mkdirs()
        output.resolve("production-regex-patterns.txt").writeText(
            extracted.joinToString(separator = "\n", postfix = "\n") { it.replace("\n", "\\n") },
        )
        logger.lifecycle(
            "Regex corpus: ${extracted.size} literal patterns, $skipped non-literal patterns skipped.",
        )
    }

    /** Invokes [block] with the index of the first argument character of each regex construction. */
    private fun forEachConstructorCall(text: String, block: (Int) -> Unit) {
        listOf("Regex(", "Pattern.compile(").forEach { token ->
            var index = text.indexOf(token)
            while (index >= 0) {
                // "MyRegex(" and "SafePattern.compile(" are different symbols.
                val previous = text.getOrNull(index - 1)
                val ownToken = previous == null ||
                    (!previous.isLetterOrDigit() && previous != '_' && previous != '.')
                if (ownToken) {
                    var cursor = index + token.length
                    while (cursor < text.length && text[cursor].isWhitespace()) cursor++
                    block(cursor)
                }
                index = text.indexOf(token, index + 1)
            }
        }
    }

    /**
     * Reads the Kotlin string literal starting at [start], or null when the argument is not a
     * literal this build can resolve. Raw strings interpolate too, so a template in either form
     * means the pattern is assembled at runtime.
     */
    private fun readStringLiteral(text: String, start: Int): String? {
        val triple = "\"\"\""
        if (text.startsWith(triple, start)) {
            val end = text.indexOf(triple, start + triple.length)
            if (end < 0) return null
            val body = text.substring(start + triple.length, end)
            return if (body.indices.any { isTemplateStart(body, it) }) null else body
        }
        if (text.getOrNull(start) != '"') return null

        val builder = StringBuilder()
        var index = start + 1
        while (index < text.length) {
            when (val character = text[index]) {
                '"' -> return builder.toString()
                '\n' -> return null
                // A bare '$' is a regex end-anchor. Only '${'$'}name' and '${'$'}{...}' are templates,
                // and treating every '$' as one silently dropped every anchored pattern.
                '$' -> if (isTemplateStart(text, index)) return null else { builder.append(character); index++ }
                '\\' -> {
                    val escape = text.getOrNull(index + 1) ?: return null
                    when (escape) {
                        'n' -> { builder.append('\n'); index += 2 }
                        't' -> { builder.append('\t'); index += 2 }
                        'r' -> { builder.append('\r'); index += 2 }
                        'b' -> { builder.append('\b'); index += 2 }
                        'u' -> {
                            if (index + 6 > text.length) return null
                            builder.append(text.substring(index + 2, index + 6).toInt(16).toChar())
                            index += 6
                        }
                        // For a regex the backslash is significant, so \\ \" \' each stand for
                        // the escaped character itself.
                        else -> { builder.append(escape); index += 2 }
                    }
                }
                else -> { builder.append(character); index++ }
            }
        }
        return null
    }

    /** True when the '$' at [index] begins a Kotlin string template rather than a regex anchor. */
    private fun isTemplateStart(text: String, index: Int): Boolean {
        if (text[index] != '$') return false
        val next = text.getOrNull(index + 1) ?: return false
        return next == '{' || next.isLetter() || next == '_'
    }
}

/**
 * Release assets are what a user actually sees on the GitHub release page, and Obtainium matches
 * them by name. AGP's default output is `app-release.apk`, which is indistinguishable from an
 * unsigned CI artifact and identical across every version, so uploading it directly leaves users
 * and update clients with nothing to sort or filter on. The staging directory below is the only
 * thing a release is uploaded from, and this task is what decides the name is right.
 */
abstract class VerifyReleaseAssetNameTask : org.gradle.api.DefaultTask() {
    @get:org.gradle.api.tasks.InputDirectory
    @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    abstract val stagingDirectory: org.gradle.api.file.DirectoryProperty

    @get:org.gradle.api.tasks.InputFile
    @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    abstract val truthFile: org.gradle.api.file.RegularFileProperty

    @get:org.gradle.api.tasks.Input
    abstract val versionName: org.gradle.api.provider.Property<String>

    @get:org.gradle.api.tasks.Input
    abstract val assetFileName: org.gradle.api.provider.Property<String>

    @org.gradle.api.tasks.TaskAction
    fun verify() {
        val truth = truthFile.get().asFile
        check(truth.isFile) { "Missing release truth at ${truth.absolutePath}" }
        val recordedVersion = Regex("\"versionName\"\\s*:\\s*\"([^\"]+)\"")
            .find(truth.readText())
            ?.groupValues
            ?.get(1)
        check(recordedVersion == versionName.get()) {
            "tools/release-truth.json records versionName '$recordedVersion' but the build declares " +
                "'${versionName.get()}'. The release asset name is derived from the version, so " +
                "these must agree before anything is published."
        }

        val expectedName = assetFileName.get()
        check(expectedName.startsWith("OpenTasker-v${versionName.get()}")) {
            "Release asset name '$expectedName' does not carry version ${versionName.get()}."
        }
        val staged = stagingDirectory.get().asFile.listFiles().orEmpty()
            .filter { it.isFile }
            .sortedBy { it.name }
        check(staged.isNotEmpty()) {
            "No staged release asset found in ${stagingDirectory.get().asFile.absolutePath}. " +
                "Run :app:stageReleaseAsset first."
        }
        // A leftover from a previous version would otherwise be uploaded alongside the current
        // one, which is exactly the sort-order hazard this check exists to prevent.
        check(staged.size == 1) {
            "Expected exactly one staged release asset, found ${staged.size}: " +
                staged.joinToString { it.name }
        }
        val actualName = staged.single().name
        check(actualName != "app-release.apk" && actualName != "app-release-unsigned.apk") {
            "Staged release asset is still AGP's default output name '$actualName'. Publish it as " +
                "$expectedName so update clients and humans can tell releases apart."
        }
        check(actualName == expectedName) {
            "Staged release asset is named '$actualName' but the documented pattern is '$expectedName'."
        }
        println("Release asset name check passed: $actualName")
    }
}

abstract class VerifyNativePageAlignmentTask : org.gradle.api.DefaultTask() {
    @get:org.gradle.api.tasks.InputFile
    @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    abstract val apk: org.gradle.api.file.RegularFileProperty

    @org.gradle.api.tasks.TaskAction
    fun verify() {
        val apkFile = apk.get().asFile
        check(apkFile.isFile) { "APK for native page-alignment audit is missing: ${apkFile.absolutePath}" }

        ZipFile(apkFile).use { archive ->
            val nativeEntries = archive.entries().asSequence()
                .filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }
                .toList()
            if (nativeEntries.isEmpty()) {
                println("Native page-alignment/read-only audit passed: APK contains no native libraries.")
                return
            }

            // APK-backed native libraries are loaded from immutable package storage. A compressed
            // entry would be extracted to a mutable file before loading and cannot satisfy Android
            // 17's System.load read-only requirement without an explicit setReadOnly() step.
            val readOnlyViolations = nativeEntries
                .filter { entry -> entry.method != ZipEntry.STORED }
                .map { entry -> "${entry.name}: compressed native entry is not guaranteed read-only" }
            check(readOnlyViolations.isEmpty()) {
                "Android 17 read-only native-library audit failed:\n${readOnlyViolations.joinToString("\n")}"
            }

            val violations = nativeEntries.mapNotNull { entry ->
                check(entry.size <= MAX_NATIVE_LIBRARY_BYTES) {
                    "Native library ${entry.name} exceeds the audit size limit"
                }
                val bytes = archive.getInputStream(entry).use { it.readBytes() }
                val alignment = elfLoadAlignment(bytes)
                if (alignment < REQUIRED_PAGE_ALIGNMENT) {
                    "${entry.name}: PT_LOAD p_align=$alignment, required >= $REQUIRED_PAGE_ALIGNMENT"
                } else {
                    null
                }
            }
            check(violations.isEmpty()) {
                "16 KB native page-alignment audit failed:\n${violations.joinToString("\n")}"
            }
            println(
                "Native page-alignment/read-only audit passed: ${nativeEntries.size} libraries, " +
                    "all APK entries stored read-only, minimum PT_LOAD alignment >= " +
                    "$REQUIRED_PAGE_ALIGNMENT bytes.",
            )
        }
    }

    private fun elfLoadAlignment(bytes: ByteArray): Long {
        check(bytes.size >= 64 && bytes[0] == 0x7f.toByte() && bytes[1] == 'E'.code.toByte() &&
            bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte()) {
            "Native library is not a valid ELF object"
        }
        val elfClass = bytes[4].toInt()
        val littleEndian = bytes[5].toInt() == 1
        check(elfClass == 1 || elfClass == 2) { "Unsupported ELF class: $elfClass" }
        check(littleEndian || bytes[5].toInt() == 2) { "Unsupported ELF byte order" }

        fun number(offset: Int, width: Int): Long {
            var value = 0L
            if (littleEndian) {
                repeat(width) { index -> value = value or ((bytes[offset + index].toLong() and 0xff) shl (index * 8)) }
            } else {
                repeat(width) { index -> value = (value shl 8) or (bytes[offset + index].toLong() and 0xff) }
            }
            return value
        }

        val programHeaderOffset = number(if (elfClass == 1) 28 else 32, if (elfClass == 1) 4 else 8)
        val entrySize = number(if (elfClass == 1) 42 else 54, 2).toInt()
        val entryCount = number(if (elfClass == 1) 44 else 56, 2).toInt()
        val alignmentOffset = if (elfClass == 1) 28 else 48
        val typeOffset = 0
        var minimumAlignment = Long.MAX_VALUE
        var loadSegments = 0
        repeat(entryCount) { index ->
            val header = programHeaderOffset + index.toLong() * entrySize
            check(header + entrySize <= bytes.size) { "ELF program header table is truncated" }
            if (number(header.toInt() + typeOffset, 4) == PT_LOAD) {
                loadSegments += 1
                minimumAlignment = minOf(
                    minimumAlignment,
                    number(header.toInt() + alignmentOffset, if (elfClass == 1) 4 else 8),
                )
            }
        }
        check(loadSegments > 0) { "ELF object has no PT_LOAD segments" }
        return minimumAlignment
    }

    private companion object {
        const val REQUIRED_PAGE_ALIGNMENT = 16 * 1024L
        const val MAX_NATIVE_LIBRARY_BYTES = 100L * 1024 * 1024
        const val PT_LOAD = 1L
    }
}

abstract class VerifyFuzzDependencyIsolationTask : DefaultTask() {
    @get:Input
    abstract val coordinates: ListProperty<String>

    @TaskAction
    fun verify() {
        check(coordinates.get().none { it.startsWith("com.code-intelligence:") }) {
            "Jazzer must not appear in the release runtime dependency graph."
        }
        println("Fuzz dependency isolation passed: Jazzer is absent from release runtime resolution.")
    }
}

abstract class VerifyPerformanceEvidenceTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val profileFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val baselineSource: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val macrobenchmarkSource: RegularFileProperty

    @get:Input
    abstract val versionCode: Property<Int>

    @TaskAction
    fun verify() {
        val profile = profileFile.get().asFile.readLines().map(String::trim).filter(String::isNotEmpty)
        check(profile.any { it == "Lcom/opentasker/app/MainActivity;" }) {
            "Baseline profile must include the launcher activity class rule."
        }
        check(profile.any { it == "Lcom/opentasker/app/OpenTaskerApp_NoHilt;" }) {
            "Baseline profile must include the application class rule."
        }

        val baselineSourceText = baselineSource.get().asFile.readText()
        check("BaselineProfileRule" in baselineSourceText && "startActivityAndWait" in baselineSourceText) {
            "Baseline profile generator must exercise cold start."
        }
        val macrobenchmarkSourceText = macrobenchmarkSource.get().asFile.readText()
        check("StartupTimingMetric" in macrobenchmarkSourceText && "FrameTimingMetric" in macrobenchmarkSourceText) {
            "Macrobenchmark suite must cover startup and first-navigation timing."
        }

        val capturedAt = profileFile.get().asFile.resolveSibling("baseline-prof-captured-at-version-code.txt")
        check(capturedAt.isFile) {
            "Missing ${capturedAt.name}; regenerate the baseline profile " +
                "(:app:generateBaselineProfile on an API 35+ device) and record the version code."
        }
        val recorded = capturedAt.readText().trim()
        check(recorded == versionCode.get().toString()) {
            "The baseline profile was captured at version code $recorded but this release is " +
                "${versionCode.get()}. Regenerate it so the shipped profile matches the shipped code."
        }
        println(
            "Performance evidence harness passed: ${profile.size} profile rules; " +
                "API 35+ device evidence is collected explicitly with :app:generateBaselineProfile " +
                "and :baselineprofile:connectedBenchmarkReleaseAndroidTest.",
        )
    }
}

abstract class StageReleaseAssetTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceApk: RegularFileProperty

    @get:OutputDirectory
    abstract val stagingDirectory: DirectoryProperty

    /** `OpenTasker-v<versionName>.apk`, or `OpenTasker-v<versionName>-fdroid.apk` for F-Droid. */
    @get:Input
    abstract val assetFileName: Property<String>

    /**
     * False for F-Droid's own unsigned build. Staging exists to publish a signed GitHub release
     * asset, so the unsigned lane has nothing to stage; the signed F-Droid lane
     * (`-PopenTaskerSignFdroid=true`) does. This refuses the whole task rather than relaxing the
     * signature check, which would otherwise copy `app-release-unsigned.apk` to a published name.
     */
    @get:Input
    abstract val stagingIsSupported: Property<Boolean>

    /** apksigner from the pinned build tools; resolved at configuration time for the cache. */
    @get:Input
    abstract val apkSignerPath: Property<String>

    /**
     * The certificate README.md publishes for AppVerifier, as 64 lowercase hex digits. Staging
     * refuses an APK signed by anything else, so the published fingerprint and the key that signs
     * releases cannot drift apart without a failed release.
     */
    @get:Input
    abstract val expectedCertificateSha256: Property<String>

    @TaskAction
    fun stage() {
        check(stagingIsSupported.get()) {
            "stageReleaseAsset publishes a signed GitHub release asset, and this F-Droid build is " +
                "unsigned, so it has nothing to stage. Add -PopenTaskerSignFdroid=true to build the " +
                "signed F-Droid asset, or build the standard distribution."
        }
        val source = sourceApk.get().asFile
        check(source.isFile) { "Release APK is missing: ${source.absolutePath}" }
        // Clear first. A failed signature check below must not leave a previously staged asset
        // sitting in the directory a release is uploaded from.
        val destinationDirectory = stagingDirectory.get().asFile
        check(!destinationDirectory.exists() || destinationDirectory.deleteRecursively()) {
            "Could not clear stale release assets from ${destinationDirectory.absolutePath}"
        }
        // The staging copy renames whatever it is given to the published asset name, so an
        // unsigned APK would otherwise arrive on a release page wearing the right filename. The
        // name check downstream cannot see this: it only reads the name this task chose.
        check(apkIsSigned(source)) {
            "Release APK ${source.name} carries no APK signing block and no v1 signature, so it " +
                "is unsigned. Set OPEN_TASKER_RELEASE_KEYSTORE, " +
                "OPEN_TASKER_RELEASE_KEYSTORE_PASSWORD, OPEN_TASKER_RELEASE_KEY_ALIAS and " +
                "OPEN_TASKER_RELEASE_KEY_PASSWORD and rebuild before staging anything."
        }
        val signers = signerCertificateDigests(source)
        check(signers == listOf(expectedCertificateSha256.get())) {
            "Release APK ${source.name} is signed by ${signers.ifEmpty { listOf("no certificate apksigner could read") }.joinToString()}, " +
                "but README.md publishes ${expectedCertificateSha256.get()}. Sign with the release key, or " +
                "update the README fingerprint only if the release key really changed."
        }
        check(destinationDirectory.mkdirs()) {
            "Could not create release asset directory ${destinationDirectory.absolutePath}"
        }
        source.copyTo(destinationDirectory.resolve(assetFileName.get()), overwrite = true)
    }

    /**
     * Asks apksigner, which is the only thing that actually validates a signature. Reading the
     * container by hand looked adequate and was not: a crafted archive comment can present a
     * fabricated end-of-central-directory record, so a hand-rolled scan can be talked into
     * reporting an unsigned file as signed.
     */
    private fun apkIsSigned(apk: File): Boolean {
        val signer = File(apkSignerPath.get())
        check(signer.isFile) {
            "apksigner is missing at ${signer.absolutePath}. It ships with the pinned Android " +
                "build tools, so install that build-tools version before staging a release."
        }
        val process = ProcessBuilder(signer.absolutePath, "verify", apk.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            logger.lifecycle("apksigner rejected ${apk.name}: $output")
        }
        return exitCode == 0
    }

    /** Every distinct signer certificate apksigner reports, as lowercase SHA-256 hex. */
    private fun signerCertificateDigests(apk: File): List<String> {
        val process = ProcessBuilder(File(apkSignerPath.get()).absolutePath, "verify", "--print-certs", apk.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return Regex("certificate SHA-256 digest: ([0-9a-f]{64})").findAll(output)
            .map { it.groupValues[1] }
            .distinct()
            .toList()
    }
}

val releaseRuntimeCoordinates = providers.provider {
    val configuration = configurations.single { candidate ->
        candidate.isCanBeResolved && candidate.name.equals("releaseRuntimeClasspath", ignoreCase = true)
    }
    val resolution = configuration.incoming.resolutionResult
    // allComponents silently omits anything that failed to resolve, so every gate built on this
    // list would describe a graph the build cannot actually produce and report success. A seeded
    // version conflict failed the build at packaging while this check still passed.
    val unresolved = resolution.allDependencies
        .filterIsInstance<org.gradle.api.artifacts.result.UnresolvedDependencyResult>()
        .map { it.requested.displayName }
        .distinct()
        .sorted()
    check(unresolved.isEmpty()) {
        "The release runtime graph does not resolve, so nothing can be verified about it. " +
            "Unresolved: ${unresolved.joinToString()}"
    }
    resolution.allComponents.mapNotNull { component ->
        val id = component.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier
        id?.let { "${it.group}:${it.module}:${it.version}" }
    }
}

tasks.register<VerifyFuzzDependencyIsolationTask>("verifyFuzzDependencyIsolation") {
    group = "verification"
    description = "Checks that the opt-in fuzzing dependency stays out of release runtime resolution."
    coordinates.set(releaseRuntimeCoordinates)
}

val verifyResolvedDependencyPolicy = tasks.register<VerifyResolvedDependencyPolicyTask>("verifyResolvedDependencyPolicy") {
    group = "verification"
    description = "Checks the resolved release graph, fixed versions, repositories, and checksum policy."
    components.set(releaseRuntimeCoordinates)
    versionCatalog.set(rootProject.layout.projectDirectory.file("gradle/libs.versions.toml"))
    moduleBuildFile.set(layout.projectDirectory.file("build.gradle.kts"))
    settingsFile.set(rootProject.layout.projectDirectory.file("settings.gradle.kts"))
    verificationMetadata.set(rootProject.layout.projectDirectory.file("gradle/verification-metadata.xml"))
}

val generateCycloneDxSbom = tasks.register<GenerateCycloneDxSbomTask>("generateCycloneDxSbom") {
    group = "reporting"
    description = "Writes a deterministic CycloneDX SBOM from the resolved release runtime graph."
    components.set(releaseRuntimeCoordinates)
    applicationVersion.set(appVersionName)
    outputFile.set(rootProject.layout.buildDirectory.file("reports/opentasker/sbom.cdx.json"))
}

val verifyJvmTestCount = tasks.register<VerifyJvmTestCountTask>("verifyJvmTestCount") {
    group = "verification"
    description = "Fails if the passing JVM test count drops below the release floor."
    dependsOn("testDebugUnitTest")
    JVM_TEST_MODULES.forEach { dependsOn("$it:testDebugUnitTest") }
    resultsDirectories.from(layout.buildDirectory.dir("test-results/testDebugUnitTest"))
    JVM_TEST_MODULES.forEach { module ->
        resultsDirectories.from(project(module).layout.buildDirectory.dir("test-results/testDebugUnitTest"))
    }
    reportFile.set(rootProject.layout.buildDirectory.file("reports/opentasker/jvm-test-count.json"))
    minimumTests.set(JVM_TEST_FLOOR)
}

val debugCoverageXml = layout.buildDirectory.file("reports/jacoco/debugCoverage/debugCoverage.xml")
val generateDebugCoverage = tasks.register<JacocoReport>("generateDebugCoverage") {
    group = "verification"
    description = "Produces the deterministic debug JVM JaCoCo coverage report."
    dependsOn("testDebugUnitTest")
    executionData.setFrom(layout.buildDirectory.file("jacoco/testDebugUnitTest.exec"))

    classDirectories.setFrom(
        layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes").map { directory ->
            fileTree(directory.asFile) {
                exclude("**/R.class", "**/R\$*.class", "**/BuildConfig.*", "**/Manifest*.*")
            }
        },
        layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes").map { directory ->
            fileTree(directory.asFile) {
                exclude("**/R.class", "**/R\$*.class", "**/BuildConfig.*", "**/Manifest*.*")
            }
        },
    )
    sourceDirectories.setFrom(files("src/main/java", "src/main/kotlin"))
    reports {
        xml.required.set(true)
        xml.outputLocation.set(debugCoverageXml)
        html.required.set(true)
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/debugCoverage/html"))
        csv.required.set(false)
    }
}

val verifyCoverageFloor = tasks.register<VerifyCoverageFloorTask>("verifyCoverageFloor") {
    group = "verification"
    description = "Fails if coverage regresses in the previously untested runtime areas."
    dependsOn(generateDebugCoverage)
    reportFile.set(debugCoverageXml)
    areaFloors.set(
        mapOf(
            "com/opentasker/core/scheduling" to 50.0,
            // VariableExpander feeds runtime action arguments through VariableStore; silent
            // regressions here can corrupt every profile that composes or transforms variables.
            "com/opentasker/core/engine/variables" to 70.0,
            "com/opentasker/automation/receiver" to 15.0,
            "com/opentasker/ui/utils" to 15.0,
        ),
    )
}

val verifyLocaleResources = tasks.register<VerifyLocaleResourcesTask>("verifyLocaleResources") {
    group = "verification"
    description = "Fails if an alternate locale is below the release translation threshold."
    resourcesDirectory.set(layout.projectDirectory.dir("src/main/res"))
    completionThreshold.set(LOCALE_COMPLETION_THRESHOLD)
}

val qualityGateSeedFailure = providers.gradleProperty("openTaskerQualityGateSeedFailure")
    .map(String::toBoolean)
    .orElse(false)
val verifyQualityGateSeed = tasks.register<VerifyQualityGateSeedTask>("verifyQualityGateSeed") {
    group = "verification"
    description = "Provides an explicit seeded failure used to prove the local gate exits nonzero."
    seedFailure.set(qualityGateSeedFailure)
}

tasks.register<VerifyRoomSchemaTask>("verifyRoomSchema") {
    group = "verification"
    description = "Checks that all Room schema versions up to the current are exported and tracked."

    // core:storage owns the entities now, so its KSP run is what exports the schema. It still
    // writes into app/schemas, which is where the androidTest migration assets are read from.
    dependsOn(":core:storage:kspDebugKotlin")
    schemaDirectory.set(layout.projectDirectory.dir("schemas/com.opentasker.core.storage.AppDatabase"))
    databaseFile.set(rootProject.layout.projectDirectory.file(
        "core/storage/src/main/kotlin/com/opentasker/core/storage/AppDatabase.kt",
    ))
}

val verifyPerformanceEvidence = tasks.register<VerifyPerformanceEvidenceTask>("verifyPerformanceEvidence") {
    group = "verification"
    description = "Checks the baseline-profile artifact and macrobenchmark evidence harness."
    dependsOn("compileReleaseArtProfile", ":baselineprofile:compileBenchmarkReleaseKotlin")

    profileFile.set(layout.projectDirectory.file("src/main/baseline-prof.txt"))
    baselineSource.set(rootProject.layout.projectDirectory.file(
        "baselineprofile/src/main/java/com/opentasker/baselineprofile/OpenTaskerBaselineProfile.kt",
    ))
    macrobenchmarkSource.set(rootProject.layout.projectDirectory.file(
        "baselineprofile/src/main/java/com/opentasker/baselineprofile/OpenTaskerMacrobenchmark.kt",
    ))
    versionCode.set(appVersionCode)

    if (providers.gradleProperty("openTaskerRequirePerformanceRun").orNull?.toBoolean() == true) {
        dependsOn("generateBaselineProfile")
    }
}

val verifyDocumentationTruth = tasks.register<VerifyDocumentationTruthTask>("verifyDocumentationTruth") {
    group = "verification"
    description = "Checks current release claims and reports stale local historical documentation claims."

    val repositoryRootPath = rootProject.layout.projectDirectory.asFile
    val readmeFilePath = repositoryRootPath.resolve("README.md")
    val actionCatalogFilePath = projectDir.resolve("src/main/java/com/opentasker/core/actions/ActionCatalog.kt")
    val contextSpecFilePath = rootProject.layout.projectDirectory.asFile
        .resolve("core/model/src/main/kotlin/com/opentasker/core/model/ContextSpec.kt")
    val currentDocumentationPaths = listOf(
        readmeFilePath,
        repositoryRootPath.resolve("CHANGELOG.md"),
        repositoryRootPath.resolve("tools/release-truth.json"),
    )
    readmeFile.set(readmeFilePath)
    currentDocumentation.from(currentDocumentationPaths)
    versionName.set(appVersionName)
    actionCount.set(deriveRegisteredActionCount(actionCatalogFilePath))
    contextFamilyCount.set(deriveContextFamilyCount(contextSpecFilePath))
    repositoryRoot.set(repositoryRootPath)
}

tasks.register<VerifyReleaseTruthTask>("verifyReleaseTruth") {
    truthFile.set(rootProject.layout.projectDirectory.file("tools/release-truth.json"))
    readmeFile.set(rootProject.layout.projectDirectory.file("README.md"))
    metadataFile.set(rootProject.layout.projectDirectory.file("fdroid/metadata/com.opentasker.app.yml"))
    moduleBuildFile.set(layout.projectDirectory.file("build.gradle.kts"))
    actionCatalogFile.set(layout.projectDirectory.file("src/main/java/com/opentasker/core/actions/ActionCatalog.kt"))
    contextSpecFile.set(rootProject.layout.projectDirectory.file("core/model/src/main/kotlin/com/opentasker/core/model/ContextSpec.kt"))
    bundleFile.set(layout.projectDirectory.file("src/main/java/com/opentasker/core/transfer/OpenTaskerBundle.kt"))
    versionCatalogFile.set(rootProject.layout.projectDirectory.file("gradle/libs.versions.toml"))
    wrapperFile.set(rootProject.layout.projectDirectory.file("gradle/wrapper/gradle-wrapper.properties"))
    flowControlFile.set(layout.projectDirectory.file("src/main/java/com/opentasker/core/engine/FlowStructure.kt"))
    taskRunnerFile.set(layout.projectDirectory.file("src/main/java/com/opentasker/core/engine/TaskRunner.kt"))
    databaseFile.set(rootProject.layout.projectDirectory.file("core/storage/src/main/kotlin/com/opentasker/core/storage/AppDatabase.kt"))
    changelogFile.set(rootProject.layout.projectDirectory.file("CHANGELOG.md"))
    repositoryDirectory.set(rootProject.layout.projectDirectory)
}

tasks.register("verifyFdroidReadiness") {
    group = "verification"
    description = "Checks the F-Droid distribution profile for known proprietary dependency families."

    doLast {
        val forbiddenGroups = setOf(
            "com.google.android.gms",
            "com.google.firebase",
            "com.android.billingclient",
            "com.facebook.android",
            "com.adjust.sdk",
        )
        val forbiddenNames = setOf(
            "play-services",
            "firebase",
            "billingclient",
            "crashlytics",
            "appsflyer",
        )
        val forbidden = releaseRuntimeCoordinates.get()
            .mapNotNull { coordinate ->
                val (group, name) = coordinate.split(':', limit = 3)
                val blockedGroup = forbiddenGroups.any { forbiddenGroup ->
                    group == forbiddenGroup || group.startsWith("$forbiddenGroup.")
                }
                val blocked = blockedGroup || forbiddenNames.any { token -> token in name.lowercase() }
                coordinate.takeIf { blocked }
            }
            .distinct()
            .sorted()

        check(forbidden.isEmpty()) {
            "F-Droid profile includes dependencies that need policy review: ${forbidden.joinToString()}"
        }
        println("F-Droid readiness check passed for distribution=$selectedDistribution")
    }
}

tasks.register("verifyFdroidMetadata") {
    group = "verification"
    description = "Checks that draft fdroiddata metadata matches the current release contract."
    dependsOn("verifyReleaseTruth")

    val metadataFile = rootProject.file("fdroid/metadata/com.opentasker.app.yml")
    val readmeFile = rootProject.file("README.md")
    val listing = rootProject.file("fastlane/metadata/android/en-US")
    val sourceRoot = rootProject.rootDir
    inputs.file(metadataFile)
    inputs.file(readmeFile)
    inputs.file(project.buildFile)

    // Copied into locals so the action holds plain values instead of the build script, which the
    // configuration cache can't serialize.
    val distribution = selectedDistribution
    val versionName = appVersionName
    val versionCode = appVersionCode
    val recipeApkPath = fdroidRecipeApkPath
    val assetFileName = releaseAssetFileName
    val shortDescriptionMaxChars = FDROID_SHORT_DESCRIPTION_MAX_CHARS
    val changelogMaxChars = FDROID_CHANGELOG_MAX_CHARS
    val minScreenshots = FDROID_MIN_SCREENSHOTS

    doLast {
        val publishedReleaseCertificate = publishedCertificateSha256(readmeFile.readText())
        check(metadataFile.isFile) {
            "Missing F-Droid metadata at fdroid/metadata/com.opentasker.app.yml"
        }

        // This task describes the artifact the F-Droid distribution produces, so running it against
        // any other distribution previously printed "F-Droid metadata check passed" for a build
        // whose output does not match the recipe at all.
        check(distribution == "fdroid") {
            "verifyFdroidMetadata checks the F-Droid build recipe; run it with " +
                "-PopenTaskerDistribution=fdroid (current distribution: $distribution)"
        }

        val metadata = metadataFile.readText()
        fun valuesFor(key: String): List<String> =
            Regex("""(?m)^\s*(?:-\s*)?$key:\s*(.+?)\s*$""")
                .findAll(metadata)
                .map { match -> match.groupValues[1].trim().trim('"', '\'') }
                .toList()

        fun requireValue(key: String, expected: String) {
            val values = valuesFor(key)
            check(expected in values) {
                "F-Droid metadata key '$key' expected '$expected' but found ${values.ifEmpty { listOf("<missing>") }}"
            }
        }

        requireValue("versionName", versionName)
        requireValue("versionCode", versionCode.toString())
        requireValue("CurrentVersion", versionName)
        requireValue("CurrentVersionCode", versionCode.toString())
        requireValue("Changelog", "https://github.com/SysAdminDoc/OpenTasker/releases")

        val commits = valuesFor("commit")
        check(commits.size == 1) {
            "F-Droid metadata must contain exactly one release commit, found ${commits.size}"
        }
        // The tag, not a SHA: checkupdates writes every later build entry as the tag it found, and
        // the release tag lands on the metadata sync commit, which adds nothing the APK is built
        // from (version-control info is off, see the release build type).
        val releaseCommit = commits.single()
        check(releaseCommit == "v$versionName") {
            "F-Droid metadata commit must be the release tag v$versionName, found '$releaseCommit'"
        }
        check("openTaskerDistribution=fdroid" in metadata) {
            "F-Droid metadata must build with gradleprops openTaskerDistribution=fdroid"
        }
        check(":app:verifyFdroidReadiness" in metadata) {
            "F-Droid metadata must run :app:verifyFdroidReadiness before assembly"
        }
        // F-Droid's server builds without a key, so the recipe names the unsigned artifact even
        // when this check runs inside the signed lane of a release.
        check(recipeApkPath in metadata) {
            "F-Droid metadata must point to $recipeApkPath, the artifact F-Droid's unsigned " +
                "build of distribution=fdroid produces"
        }

        // Reproducible-build wiring. F-Droid downloads Binaries, copies its signature onto the APK
        // it built, and publishes ours only if that verifies under one of the allowed keys. A URL
        // that doesn't match the staged asset name 404s, and a key that isn't the release key
        // makes F-Droid refuse every build.
        requireValue(
            "Binaries",
            "https://github.com/SysAdminDoc/OpenTasker/releases/download/v%v/OpenTasker-v%v-fdroid.apk",
        )
        check(assetFileName == "OpenTasker-v$versionName-fdroid.apk") {
            "The Binaries URL names OpenTasker-v%v-fdroid.apk but this lane stages $assetFileName"
        }
        requireValue("AllowedAPKSigningKeys", publishedReleaseCertificate)
        // checkupdates can't read versionCode = appVersionCode, so UpdateCheckData points it at the
        // vals: codeFile|codeRegex|nameFile|nameRegex. Run both regexes here the way checkupdates
        // does, so renaming or reformatting a val fails this check instead of silently stopping
        // F-Droid from noticing new releases. The value is read raw because valuesFor trims the
        // closing quote off "(.*)". rewritemeta wraps a long value onto the next line.
        val updateCheckData = Regex("""(?m)^UpdateCheckData:[ \t]*(?:\r?\n[ \t]+)?(\S.*?)[ \t]*$""")
            .find(metadata)
            ?.groupValues?.get(1)
            ?: error("F-Droid metadata needs UpdateCheckData pointing at appVersionCode and appVersionName")
        val updateCheckParts = updateCheckData.split('|')
        check(updateCheckParts.size == 4) {
            "UpdateCheckData must be codeFile|codeRegex|nameFile|nameRegex, found '$updateCheckData'"
        }
        fun updateCheckCapture(fileIndex: Int): String? {
            val source = sourceRoot.resolve(updateCheckParts[fileIndex])
            check(source.isFile) { "UpdateCheckData names ${updateCheckParts[fileIndex]}, which doesn't exist" }
            return Regex(updateCheckParts[fileIndex + 1]).find(source.readText())?.groupValues?.get(1)
        }
        check(updateCheckCapture(0) == versionCode.toString()) {
            "UpdateCheckData's versionCode regex doesn't find $versionCode in ${updateCheckParts[0]}"
        }
        check(updateCheckCapture(2) == versionName) {
            "UpdateCheckData's versionName regex doesn't find $versionName in ${updateCheckParts[2]}"
        }

        // The store listing is part of the release contract: F-Droid renders whatever is in
        // fastlane/, so a listing that lags the build is what users actually see.
        check(listing.isDirectory) {
            "Missing F-Droid store listing at fastlane/metadata/android/en-US"
        }
        listOf("title.txt", "short_description.txt", "full_description.txt").forEach { name ->
            val file = listing.resolve(name)
            check(file.isFile && file.readText().isNotBlank()) {
                "F-Droid store listing is missing a non-empty $name"
            }
        }
        val shortDescription = listing.resolve("short_description.txt").readText().trim()
        check(shortDescription.length <= shortDescriptionMaxChars) {
            "F-Droid short_description.txt is ${shortDescription.length} characters; the limit is " +
                "$shortDescriptionMaxChars"
        }

        val changelog = listing.resolve("changelogs/$versionCode.txt")
        check(changelog.isFile && changelog.readText().isNotBlank()) {
            "Missing F-Droid changelog for version code $versionCode. Run " +
                ":app:generateFdroidChangelog after bumping the version."
        }
        check(changelog.readText().trim().length <= changelogMaxChars) {
            "F-Droid changelog $versionCode.txt exceeds $changelogMaxChars characters"
        }

        // IzzyOnDroid reads the Fastlane tree directly and will not list an app whose icon or
        // feature graphic is absent, so the listing assets are part of the release contract rather
        // than something to discover at submission time. Dimensions are checked because a wrongly
        // sized graphic is rejected just as hard as a missing one.
        listOf(
            Triple("images/icon.png", 512, 512),
            Triple("images/featureGraphic.png", 1024, 500),
        ).forEach { (path, expectedWidth, expectedHeight) ->
            val image = listing.resolve(path)
            check(image.isFile) {
                "F-Droid store listing is missing $path; IzzyOnDroid requires it to list the app"
            }
            val decoded = javax.imageio.ImageIO.read(image)
            checkNotNull(decoded) { "F-Droid store listing asset $path is not a readable image" }
            check(decoded.width == expectedWidth && decoded.height == expectedHeight) {
                "F-Droid store listing asset $path is ${decoded.width}x${decoded.height}; expected " +
                    "${expectedWidth}x$expectedHeight"
            }
        }

        // Screenshots are captured per release. Pinning the capture to a version code is what makes
        // a stale listing fail the build instead of quietly showing an old UI on the store page.
        val screenshotDir = listing.resolve("images/phoneScreenshots")
        val screenshots = screenshotDir.listFiles { file -> file.extension.lowercase() == "png" }
            .orEmpty()
            .sortedBy { it.name }
        check(screenshots.size >= minScreenshots) {
            "F-Droid listing needs at least $minScreenshots phone screenshots, found ${screenshots.size}"
        }
        val capturedAt = screenshotDir.resolve("captured-at-version-code.txt")
        check(capturedAt.isFile) {
            "Missing ${capturedAt.name}; re-capture the store screenshots and record the version code"
        }
        check(capturedAt.readText().trim() == versionCode.toString()) {
            "Store screenshots were captured at version code ${capturedAt.readText().trim()} but this " +
                "release is $versionCode. Re-capture them so the listing matches the build."
        }

        // The tag itself is created after the gate passes, so its existence and position are
        // checked by tools/verify-fdroid-release.ps1 once the release is tagged.
        println("F-Droid metadata check passed for v$versionName ($versionCode)")
    }
}

tasks.register("verifyPlayManifestPolicy") {
    group = "verification"
    description = "Checks that the Play distribution merged manifest omits SMS and phone-state permissions."
    dependsOn("processReleaseMainManifest")

    doLast {
        check(selectedDistribution == "play") {
            "Run this task with -PopenTaskerDistribution=play"
        }
        val manifest = layout.buildDirectory
            .file("intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml")
            .get()
            .asFile
        check(manifest.isFile) {
            "Release merged manifest not found at ${manifest.relativeTo(projectDir)}"
        }
        val manifestText = manifest.readText()
        check("android.permission.SEND_SMS" !in manifestText) {
            "Play distribution merged manifest must not contain SEND_SMS"
        }
        check("android.permission.READ_PHONE_STATE" !in manifestText) {
            "Play distribution merged manifest must not contain READ_PHONE_STATE"
        }
        check("android.permission.READ_CALL_LOG" !in manifestText) {
            "Play distribution merged manifest must not contain READ_CALL_LOG"
        }
        println("Play manifest policy check passed: SMS/phone-state permissions are absent.")
    }
}

val verifyPackagedTypeCompleteness = tasks.register<VerifyPackagedTypeCompletenessTask>(
    "verifyPackagedTypeCompleteness",
) {
    group = "verification"
    description = "Checks debug and release DEX files for referenced OpenTasker types missing from the APK."
    dependsOn("packageDebug", "packageRelease")
    apks.from(
        layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"),
        rootProject.layout.projectDirectory.file(expectedReleaseApkPath),
    )
}

/**
 * The directory a GitHub release asset is uploaded from: `outputs/release-assets` for the standard
 * APK, `outputs/release-assets-fdroid` for the signed F-Droid one, so each lane can clear its own
 * stale files without deleting the other's. Staging is a copy rather than an AGP output rename
 * because the F-Droid recipe, the reproducibility harness, and verifyPackagedTypeCompleteness all
 * name the AGP path directly.
 */
val releaseAssetStagingDirectory = layout.buildDirectory.dir(releaseAssetDirectoryName)

val stageReleaseAsset = tasks.register<StageReleaseAssetTask>("stageReleaseAsset") {
    group = "release"
    description = "Copies the signed release APK to $releaseAssetDirectoryName under the published asset name."
    dependsOn("packageRelease")
    sourceApk.set(rootProject.layout.projectDirectory.file(expectedReleaseApkPath))
    stagingDirectory.set(releaseAssetStagingDirectory)
    assetFileName.set(releaseAssetFileName)
    stagingIsSupported.set(!releaseBuildIsUnsigned)
    expectedCertificateSha256.set(
        providers.fileContents(rootProject.layout.projectDirectory.file("README.md")).asText
            .map { readme -> publishedCertificateSha256(readme) },
    )
    // Read outside the lambda: touching `android` inside it would capture the build script, which
    // the configuration cache can't store.
    val buildToolsVersion = android.buildToolsVersion
    apkSignerPath.set(
        androidComponents.sdkComponents.sdkDirectory.map { sdk ->
            val toolsDir = sdk.dir("build-tools/$buildToolsVersion").asFile
            val windowsTool = toolsDir.resolve("apksigner.bat")
            val tool = if (windowsTool.isFile) windowsTool else toolsDir.resolve("apksigner")
            tool.absolutePath
        },
    )
}

/**
 * Removing the in-repo keystore in v0.2.93 made an unsigned build a silent outcome: with any of the
 * four signing variables missing, `signingConfigs.findByName("release")` returns null and AGP
 * packages `app-release-unsigned.apk` for a distribution that is supposed to be signed. Only the
 * F-Droid distribution is unsigned on purpose. Fail the packaging step rather than let a release
 * lane discover this later as a confusing missing-artifact error.
 */
tasks.matching { it.name == "packageRelease" }.configureEach {
    val signingRequired = !releaseBuildIsUnsigned && !allowUnsignedRelease
    val signingConfigured = hasReleaseSigning
    val distribution = selectedDistribution
    doFirst {
        check(!signingRequired || signingConfigured) {
            "Release signing is not configured for distribution=$distribution, so this build would " +
                "produce an unsigned APK. Set OPEN_TASKER_RELEASE_KEYSTORE, " +
                "OPEN_TASKER_RELEASE_KEYSTORE_PASSWORD, OPEN_TASKER_RELEASE_KEY_ALIAS and " +
                "OPEN_TASKER_RELEASE_KEY_PASSWORD, or build the F-Droid profile with " +
                "-PopenTaskerDistribution=fdroid, which is unsigned because F-Droid signs it."
        }
    }
}

val verifyReleaseAssetName = tasks.register<VerifyReleaseAssetNameTask>("verifyReleaseAssetName") {
    group = "verification"
    description = "Checks the staged release asset matches OpenTasker-v<versionName>.apk."
    dependsOn(stageReleaseAsset)
    stagingDirectory.set(releaseAssetStagingDirectory)
    truthFile.set(rootProject.layout.projectDirectory.file("tools/release-truth.json"))
    versionName.set(appVersionName)
    assetFileName.set(releaseAssetFileName)
}

// Where production Kotlin lives. Source-scanning gates that walk a tree must walk all of these:
// a gate pointed only at app/ silently stopped covering the 44 files the core modules now own.
val productionSourceRoots: List<Directory> = listOf(
    layout.projectDirectory.dir("src/main/java"),
    rootProject.layout.projectDirectory.dir("core/model/src/main/kotlin"),
    rootProject.layout.projectDirectory.dir("core/common/src/main/kotlin"),
    rootProject.layout.projectDirectory.dir("core/storage/src/main/kotlin"),
    rootProject.layout.projectDirectory.dir("core/engine/src/main/kotlin"),
    rootProject.layout.projectDirectory.dir("feature/automation/src/main/kotlin"),
).filter { it.asFile.isDirectory }

val generateRegexCorpus = tasks.register<GenerateRegexCorpusTask>("generateRegexCorpus") {
    group = "verification"
    description = "Extracts production regex literals for the on-device ICU compilation test."
    // Every production source root, not just app's: a regex that moved into a core module is
    // exactly as capable of failing to compile on Android's ICU engine as one that did not.
    productionSourceRoots.forEach { root ->
        sources.from(root.asFileTree.matching { include("**/*.kt") })
    }
    outputDirectory.set(layout.buildDirectory.dir("generated/regexCorpus"))
}

// Registering the directory through the variant API is what carries the task dependency. Adding it
// to the static androidTest source set instead leaves lint and packaging reading a directory they
// never declared they consume, which Gradle rejects as an implicit dependency.
androidComponents {
    onVariants { variant ->
        variant.androidTest?.sources?.assets?.addGeneratedSourceDirectory(
            generateRegexCorpus,
            GenerateRegexCorpusTask::outputDirectory,
        )
    }
}

tasks.register("localQualityGate") {
    group = "verification"
    description = "Runs the deterministic local debug-quality and dependency-report gate."
    dependsOn(
        verifyReleaseAssetName,
        ":core:common:testDebugUnitTest",
        ":core:storage:testDebugUnitTest",
        ":core:engine:testDebugUnitTest",
        "lintDebug",
        "compileDebugAndroidTestKotlin",
        "connectedDebugAndroidTest",
        "verifyRoomSchema",
        "verifyReleaseTruth",
        verifyResolvedDependencyPolicy,
        generateCycloneDxSbom,
        verifyJvmTestCount,
        verifyCoverageFloor,
        verifyLocaleResources,
        verifyQualityGateSeed,
        verifyPackagedTypeCompleteness,
        "verifyNativePageAlignment",
        "verifyFuzzDependencyIsolation",
        verifyPerformanceEvidence,
        verifyDocumentationTruth,
        "validateDebugScreenshotTest",
    )
}

tasks.register<VerifyNativePageAlignmentTask>("verifyNativePageAlignment") {
    group = "verification"
    description = "Checks that packaged native ELFs are read-only and have 16 KB PT_LOAD alignment."
    dependsOn("packageDebug")
    apk.set(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
}

/**
 * The same audit against the artifact that actually ships. Packaging is identical across build
 * types today, so the debug check transfers - but that is a coincidence the release must not rely
 * on, and a release-only packaging change would otherwise escape the 16 KB gate entirely.
 */
tasks.register<VerifyNativePageAlignmentTask>("verifyReleaseNativePageAlignment") {
    group = "verification"
    description = "Checks 16 KB PT_LOAD alignment in the release APK that ships."
    dependsOn("packageRelease")
    // Same single source of truth the build recipe and reproducibility harness use.
    apk.set(rootProject.layout.projectDirectory.file(expectedReleaseApkPath))
}

/**
 * Writes the F-Droid per-version changelog from the current CHANGELOG section.
 *
 * F-Droid renders one file per version code and truncates hard, so the section is condensed to its
 * bullet leads rather than copied verbatim. Review the result before releasing; this produces a
 * starting point, it does not replace judgement.
 */
tasks.register("generateFdroidChangelog") {
    group = "release"
    description = "Writes fastlane/metadata/android/en-US/changelogs/<versionCode>.txt from CHANGELOG.md."

    val changelogFile = rootProject.file("CHANGELOG.md")
    val versionName = appVersionName
    val versionCode = appVersionCode
    val outputFile = rootProject.file("fastlane/metadata/android/en-US/changelogs/$versionCode.txt")
    inputs.file(changelogFile)
    outputs.file(outputFile)

    doLast {
        // Skip the rest of the heading line: "## v1.2.3 - 2026-01-01" leaves " - 2026-01-01", which
        // trims to "- 2026-01-01" and would otherwise be read as the first entry.
        val section = changelogFile.readText()
            .substringAfter("## v$versionName", "")
            .substringAfter(NEWLINE, "")
            .substringBefore(NEWLINE + "## ")
        check(section.isNotBlank()) {
            "CHANGELOG.md has no '## v$versionName' section to generate a store changelog from"
        }
        // An entry runs from its "- " line to the next blank line, heading or entry. CHANGELOG.md
        // wraps its entries, and reading only the first line cut a wrapped entry off mid-sentence.
        // A wrapped line counts however it is indented, tabs or none, as Markdown reads it.
        val entries = mutableListOf<String>()
        var inEntry = false
        for (line in section.lines()) {
            val text = line.trim()
            when {
                text.startsWith("- ") -> {
                    entries += text.removePrefix("- ")
                    inEntry = true
                }
                text.isEmpty() || text.startsWith("#") -> inEntry = false
                inEntry -> entries[entries.lastIndex] += " $text"
            }
        }
        val bullets = entries.map { it.replace(Regex("[`*]"), "") }
        check(bullets.isNotEmpty()) { "The '## v$versionName' CHANGELOG section has no entries" }

        val body = StringBuilder()
        for (bullet in bullets) {
            val line = bullet.substringBefore(". ").trimEnd('.') + "."
            if (body.length + line.length + 1 > FDROID_CHANGELOG_MAX_CHARS) break
            if (body.isNotEmpty()) body.append(NEWLINE)
            body.append(line)
        }
        outputFile.parentFile.mkdirs()
        outputFile.writeText(body.toString().trim() + NEWLINE)
        println("Wrote ${outputFile.relativeTo(rootProject.projectDir)} (${outputFile.readText().length} chars)")
    }
}
