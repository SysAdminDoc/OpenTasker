package com.opentasker.core.diagnostics

import android.app.job.JobScheduler
import android.app.usage.UsageStatsManager
import android.content.pm.ServiceInfo
import androidx.work.WorkInfo
import com.opentasker.core.engine.EngineExitCorrelation
import com.opentasker.core.engine.EngineExitCorrelationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineHealthReaderTest {
    @Test
    fun rareAndRestrictedBucketsAreThrottled() {
        assertTrue(EngineHealthReader.isThrottledStandbyBucket(UsageStatsManager.STANDBY_BUCKET_RARE))
        assertTrue(EngineHealthReader.isThrottledStandbyBucket(UsageStatsManager.STANDBY_BUCKET_RESTRICTED))
    }

    @Test
    fun activeWorkingSetAndFrequentBucketsAreNotThrottled() {
        assertFalse(EngineHealthReader.isThrottledStandbyBucket(UsageStatsManager.STANDBY_BUCKET_ACTIVE))
        assertFalse(EngineHealthReader.isThrottledStandbyBucket(UsageStatsManager.STANDBY_BUCKET_WORKING_SET))
        assertFalse(EngineHealthReader.isThrottledStandbyBucket(UsageStatsManager.STANDBY_BUCKET_FREQUENT))
    }

    @Test
    fun foregroundServiceTypesAreHumanReadable() {
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION

        assertEquals("special use, location", EngineHealthReader.foregroundServiceTypeLabel(types))
        assertEquals("None recorded", EngineHealthReader.foregroundServiceTypeLabel(0))
    }

    @Test
    fun workerStopReasonsAreHumanReadable() {
        assertEquals("Timed out", EngineHealthReader.workerStopReasonLabel(WorkInfo.STOP_REASON_TIMEOUT))
        assertEquals(
            "Timed out; job was abandoned after the app did not respond",
            EngineHealthReader.workerStopReasonLabel(EngineHealthReader.STOP_REASON_TIMEOUT_ABANDONED),
        )
        assertEquals("App standby bucket", EngineHealthReader.workerStopReasonLabel(WorkInfo.STOP_REASON_APP_STANDBY))
        assertEquals("Reason 9876", EngineHealthReader.workerStopReasonLabel(9876))
    }

    @Test
    fun pendingJobReasonsAreHumanReadable() {
        assertEquals("App standby bucket", EngineHealthReader.pendingJobReasonLabel(JobScheduler.PENDING_JOB_REASON_APP_STANDBY))
        assertEquals("No connectivity", EngineHealthReader.pendingJobReasonLabel(JobScheduler.PENDING_JOB_REASON_CONSTRAINT_CONNECTIVITY))
        assertEquals("Out of run quota", EngineHealthReader.pendingJobReasonLabel(JobScheduler.PENDING_JOB_REASON_QUOTA))
        assertEquals("Constraint 4242", EngineHealthReader.pendingJobReasonLabel(4242))
    }

    @Test
    fun nonActionablePendingJobReasonsAreFilteredOut() {
        // A running job, or one with no holding constraint, is not something the user can act on.
        assertFalse(EngineHealthReader.isReportablePendingJobReason(JobScheduler.PENDING_JOB_REASON_EXECUTING))
        assertFalse(EngineHealthReader.isReportablePendingJobReason(JobScheduler.PENDING_JOB_REASON_UNDEFINED))
        assertTrue(EngineHealthReader.isReportablePendingJobReason(JobScheduler.PENDING_JOB_REASON_APP_STANDBY))
    }

    @Test
    fun standbyBucketsExplainDeliveryConsequences() {
        assertTrue(EngineHealthReader.standbyConsequenceLabel(UsageStatsManager.STANDBY_BUCKET_RARE).contains("may be delayed"))
        assertTrue(EngineHealthReader.standbyConsequenceLabel(UsageStatsManager.STANDBY_BUCKET_RESTRICTED).contains("heavily delayed"))
        assertTrue(EngineHealthReader.standbyConsequenceLabel(UsageStatsManager.STANDBY_BUCKET_ACTIVE).contains("minimal restrictions"))
        assertEquals("Rare. Time triggers and workers may be delayed.", EngineHealthReader.standbyConsequenceLabel(UsageStatsManager.STANDBY_BUCKET_RARE))
    }

    @Test
    fun aMatchedProcessExitReadsAsSentences() {
        val matched = EngineExitCorrelation(
            state = EngineExitCorrelationState.MATCHED,
            reason = "Low memory",
            description = "killed by the system",
            gapMillis = 180_000L,
        )

        assertEquals("Reason: Low memory (killed by the system). Heartbeat gap: 3m", EngineHealthReader.processExitReason(matched))
    }

    @Test
    fun pendingTimeDurationsAreReadableAndBounded() {
        assertEquals("less than a second", EngineHealthReader.durationLabel(0L))
        assertEquals("1m 5s", EngineHealthReader.durationLabel(65_000L))
        assertEquals("2h 3m", EngineHealthReader.durationLabel(7_398_000L))
    }

    @Test
    fun unavailableScheduledDiagnosticsSayTheyAreUnavailable() {
        val diagnostics = ScheduledJobDiagnostics.unavailable()

        assertFalse(diagnostics.currentAvailable)
        assertTrue(EngineHealthReader.scheduledJobSignalReason(diagnostics).contains("unavailable"))
    }
}
