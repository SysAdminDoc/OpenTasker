package com.opentasker.baselineprofile

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OpenTaskerMacrobenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartWithBaselineProfile() = benchmarkRule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(StartupTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.COLD,
        compilationMode = CompilationMode.DEFAULT,
        setupBlock = { pressHome() },
        measureBlock = { startActivityAndWait() },
    )

    @Test
    fun firstNavigationToTasks() = benchmarkRule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(FrameTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.COLD,
        compilationMode = CompilationMode.DEFAULT,
        setupBlock = { pressHome() },
        measureBlock = {
            startActivityAndWait()
            device.wait(Until.findObject(By.text("Continue without a template")), ONBOARDING_TIMEOUT_MS)?.click()
            val tasks = device.wait(Until.findObject(By.text("Tasks")), NAVIGATION_TIMEOUT_MS)
            checkNotNull(tasks) {
                "Tasks navigation did not appear after startup."
            }.click()
            device.waitForIdle()
        },
    )

    /**
     * From the automation service's onCreate to its first profile load, as traced by the app.
     * The launch starts the service, so a cold launch measures a cold engine start.
     */
    @OptIn(ExperimentalMetricApi::class)
    @Test
    fun engineStartAfterColdLaunch() = benchmarkRule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(TraceSectionMetric(ENGINE_START_SECTION, TraceSectionMetric.Mode.First)),
        iterations = 5,
        startupMode = StartupMode.COLD,
        compilationMode = CompilationMode.DEFAULT,
        setupBlock = { pressHome() },
        measureBlock = {
            startActivityAndWait()
            // Nothing on screen marks the engine as loaded, so hold the trace open long enough
            // for the section to end. A section that never ends fails the run as missing.
            Thread.sleep(ENGINE_SETTLE_MS)
        },
    )

    private companion object {
        const val PACKAGE_NAME = "com.opentasker.app"
        const val ONBOARDING_TIMEOUT_MS = 2_000L
        const val NAVIGATION_TIMEOUT_MS = 10_000L
        const val ENGINE_START_SECTION = "OpenTasker.engineStart"
        const val ENGINE_SETTLE_MS = 5_000L
    }
}
