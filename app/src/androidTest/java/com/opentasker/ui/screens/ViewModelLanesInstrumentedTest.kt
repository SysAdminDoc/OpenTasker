package com.opentasker.ui.screens

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opentasker.app.R
import com.opentasker.core.model.RunLogEntry
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.DEFAULT_RUN_LOG_PAGE_SIZE
import com.opentasker.core.storage.DatabaseBackupManager
import com.opentasker.core.storage.toEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The run log, backup and diagnostics lanes still load, report and recover after leaving the view model (A-353, A-360). */
@RunWith(AndroidJUnit4::class)
class ViewModelLanesInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val events = Channel<UiMessage>(Channel.BUFFERED)
    private lateinit var db: AppDatabase
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        cleanup()
        db = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DATABASE).build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        cleanup()
    }

    @Test
    fun theRunLogLaneLoadsFiltersAndClearsItsPage() = runBlocking<Unit> {
        db.runLogDao().insert(RunLogEntry(taskId = 1, taskName = "Morning", durationMs = 5, success = true, message = "Done").toEntity())
        db.runLogDao().insert(RunLogEntry(taskId = 2, taskName = "Evening", durationMs = 5, success = false, message = "Stopped").toEntity())
        val runLog = RunLogController(db, context, scope, events)

        val loaded = withTimeout(TIMEOUT_MS) { runLog.runLogPage.first { !it.loading && it.totalCount == 2 } }
        assertEquals(setOf("Morning", "Evening"), loaded.entries.map { it.taskName }.toSet())

        runLog.updateRunLogFilters(RunLogFilterState(status = RunLogStatusFilter.Failed))
        val failedOnly = withTimeout(TIMEOUT_MS) { runLog.runLogPage.first { !it.loading && it.totalCount == 1 } }
        assertEquals(listOf("Evening"), failedOnly.entries.map { it.taskName })

        runLog.updateRunLogFilters(RunLogFilterState())
        runLog.clearRunLog()
        assertEquals(R.string.run_log_cleared, withTimeout(TIMEOUT_MS) { events.receive() }.resId)
        withTimeout(TIMEOUT_MS) { runLog.runLogPage.first { !it.loading && it.totalCount == 0 } }
    }

    @Test
    fun aRunThatLandsJoinsTheFirstPageWithoutARefresh() = runBlocking<Unit> {
        db.runLogDao().insert(entry("Morning"))
        val runLog = RunLogController(db, context, scope, events)
        withTimeout(TIMEOUT_MS) { runLog.runLogPage.first { !it.loading && it.totalCount == 1 } }

        db.runLogDao().insert(entry("Evening"))

        val updated = withTimeout(TIMEOUT_MS) { runLog.runLogPage.first { !it.loading && it.totalCount == 2 } }
        assertEquals(listOf("Evening", "Morning"), updated.entries.map { it.taskName })
    }

    @Test
    fun aListPagedPastTheFirstPageStaysPutWhenARunLands() = runBlocking<Unit> {
        repeat(DEFAULT_RUN_LOG_PAGE_SIZE + 5) { db.runLogDao().insert(entry("Run $it")) }
        val runLog = RunLogController(db, context, scope, events)
        withTimeout(TIMEOUT_MS) { runLog.runLogPage.first { !it.loading && it.hasMore } }
        runLog.loadNextRunLogPage()
        val paged = withTimeout(TIMEOUT_MS) {
            runLog.runLogPage.first { !it.loading && it.entries.size == DEFAULT_RUN_LOG_PAGE_SIZE + 5 }
        }

        db.runLogDao().insert(entry("Late"))
        // Well past the live refresh's debounce and a page load.
        delay(2_000)

        val after = runLog.runLogPage.value
        assertEquals(paged.entries, after.entries)
        assertEquals(paged.totalCount, after.totalCount)
    }

    @Test
    fun theBackupLaneWritesABackupAndNamesItInSetup() = runBlocking<Unit> {
        val backup = BackupController(context, scope, events, DatabaseBackupManager(context, db, TEST_DATABASE))

        backup.createDatabaseBackup()

        val message = withTimeout(TIMEOUT_MS) { events.receive() }
        assertEquals(R.string.ui_message_backup_created, message.resId)
        val state = withTimeout(TIMEOUT_MS) { backup.backupSetupState.first { !it.busy && it.latestBackupName != null } }
        assertEquals(message.args.single(), state.latestBackupName)
    }

    @Test
    fun theDiagnosticsLaneMarksAFailedReadAndLetsRetryThrough() = runBlocking<Unit> {
        // Every query on a closed database throws, standing in for a health read that fails (A-360).
        db.close()
        val diagnostics = DiagnosticsController(db, context, scope, events)

        withTimeout(TIMEOUT_MS) { diagnostics.diagnosticsState.first { it.loadFailed } }
        assertEquals(R.string.ui_error_diagnostics_refresh, withTimeout(TIMEOUT_MS) { events.receive() }.resId)

        // Retry after a failure starts a new read instead of being dropped by the in-flight guard.
        diagnostics.refreshDiagnostics()
        assertEquals(R.string.ui_error_diagnostics_refresh, withTimeout(TIMEOUT_MS) { events.receive() }.resId)
    }

    private fun entry(taskName: String) =
        RunLogEntry(taskId = 1, taskName = taskName, durationMs = 5, success = true, message = "Done").toEntity()

    private fun cleanup() {
        context.deleteDatabase(TEST_DATABASE)
        DatabaseBackupManager.pendingRestoreFile(context, TEST_DATABASE).delete()
        context.filesDir.resolve("backups")
            .listFiles { file -> file.name.startsWith(TEST_DATABASE.removeSuffix(".db")) }
            ?.forEach { it.delete() }
    }

    private companion object {
        const val TEST_DATABASE = "opentasker-lanes-test.db"
        const val TIMEOUT_MS = 15_000L
    }
}
