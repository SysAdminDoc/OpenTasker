package com.opentasker.core.diagnostics

import com.opentasker.ProductionSources
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledJobDiagnosticsContractTest {
    private val repoRoot: Path = listOf(Path.of("."), Path.of("app"))
        .first { Files.isDirectory(it.resolve("src/main")) }

    @Test
    fun platformReadersAreGuardedAtThePlatformBoundaries() {
        val source = ProductionSources.path("com/opentasker/core/diagnostics/EngineHealthReader.kt").readText()

        assertTrue("API 36 history must be read", "getPendingJobReasonsHistory" in source)
        assertTrue("API 36 history must be guarded", "Build.VERSION.SDK_INT >= 36" in source)
        assertTrue("API 37 aggregate stats must be read", "getPendingJobReasonStats" in source)
        assertTrue("API 37 aggregate stats must be guarded", "Build.VERSION.SDK_INT >= 37" in source)
        assertTrue("Android 16 abandoned timeout must be explained", "STOP_REASON_TIMEOUT_ABANDONED" in source)
        assertTrue("standby consequences must be surfaced", "standbyConsequence" in source)
    }

    @Test
    fun diagnosticsAndExportExposeAllSchedulerEvidence() {
        val screen = ProductionSources.path("com/opentasker/ui/screens/DiagnosticsScreen.kt").readText()
        val export = ProductionSources.path("com/opentasker/core/diagnostics/DiagnosticExport.kt").readText()

        assertTrue("UI must show pending-job history", "pendingScheduledJobs.history" in screen)
        assertTrue("UI must show aggregate pending time", "pendingScheduledJobs.aggregateStats" in screen)
        assertTrue("export must show pending-job history", "Pending job reason history" in export)
        assertTrue("export must show aggregate pending time", "Pending job duration stats" in export)
    }

    @Test
    fun everyScheduledWorkerRecordsWhyItLastStopped() {
        val workerSources = mapOf(
            "ENGINE_WATCHDOG" to "com/opentasker/core/engine/EngineWatchdogWorker.kt",
            "RUN_LOG_PRUNE" to "com/opentasker/core/engine/RunLogPruneWorker.kt",
            "CONFIGURATION_SNAPSHOT" to "com/opentasker/core/storage/ConfigurationSnapshotWorker.kt",
            "UPDATE_CHECK" to "com/opentasker/core/updates/UpdateCheckWorker.kt",
            "TEMPORARY_STATE_REVERT" to "com/opentasker/core/actions/TemporaryStateAction.kt",
            "HEALTH_CONNECT_POLL" to "com/opentasker/core/health/HealthConnectPollWorker.kt",
        )

        workerSources.forEach { (worker, path) ->
            val source = ProductionSources.read(path)
            assertTrue(
                "$worker must record how its run ended",
                "recordingOutcome(ScheduledWorkerId.$worker)" in source,
            )
        }

        val screen = ProductionSources.path("com/opentasker/ui/screens/DiagnosticsScreen.kt").readText()
        val export = ProductionSources.path("com/opentasker/core/diagnostics/DiagnosticExport.kt").readText()

        assertTrue("Diagnostics must list one row per scheduled worker", "ScheduledWorkerId.entries.forEach" in screen)
        assertTrue("Stop reasons on screen must resolve through resources", "R.string.diagnostics_stop_reason_quota" in screen)
        assertTrue("A stopped worker must be labelled differently from one that finished", "diagnostics_work_outcome_stopped" in screen)
        assertTrue("Rows must carry the time the outcome was recorded", "outcome.timestampMillis" in screen)
        assertTrue("The export must carry the same per-worker outcomes", "Scheduled work \${worker.key}" in export)
        val store = ProductionSources.path("com/opentasker/core/storage/ScheduledWorkOutcomes.kt").readText()
        assertTrue(
            "A platform stop must be recorded with the reason the platform gave",
            "if (isStopped) store.recordStopped(worker, platformStopReason())" in store,
        )
    }
}
