package com.opentasker.ui.screens

import android.content.Context
import android.net.Uri
import com.opentasker.app.R
import com.opentasker.core.diagnostics.RunLogExportFormat
import com.opentasker.core.diagnostics.RunLogExporter
import com.opentasker.core.model.RunLogEntry
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.DEFAULT_RUN_LOG_PAGE_SIZE
import com.opentasker.core.storage.RunLogQuery
import com.opentasker.core.storage.RunLogRetentionPolicy
import com.opentasker.core.storage.RunLogRetentionSettings
import com.opentasker.core.storage.RunLogTaskOption
import com.opentasker.core.storage.applyRetention
import com.opentasker.core.storage.loadPage
import com.opentasker.core.storage.minimumTimestamp
import com.opentasker.core.storage.normalized
import com.opentasker.core.storage.openSnapshot
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Run Log lane of [ActiveAutomationViewModel]: the live list, the filtered and paged
 * snapshot, export, retention and clearing. It runs on the view model's scope and reports
 * through the view model's message channel, so it lives and dies with the screen (A-353).
 */
internal class RunLogController(
    private val db: AppDatabase,
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val events: SendChannel<UiMessage>,
) {
    private val runLogRetentionSettings = RunLogRetentionSettings(appContext)

    val runLogs: StateFlow<ImmutableList<RunLogEntry>> = db.runLogDao()
        .getRecentFlow()
        .map { entities -> entities.map { it.toDomain() }.toImmutableList() }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), persistentListOf())

    private val _runLogFilters = MutableStateFlow(RunLogFilterState())
    val runLogFilters: StateFlow<RunLogFilterState> = _runLogFilters.asStateFlow()

    private val _runLogPage = MutableStateFlow(RunLogPageUiState())
    val runLogPage: StateFlow<RunLogPageUiState> = _runLogPage.asStateFlow()

    val runLogTaskOptions: StateFlow<ImmutableList<RunLogTaskOption>> = db.runLogDao()
        .getTaskOptionsFlow()
        .map { it.toImmutableList() }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), persistentListOf())

    private var runLogPageJob: Job? = null
    private var runLogQueryDebounceJob: Job? = null

    private val _runLogRetentionPolicy = MutableStateFlow(runLogRetentionSettings.load())
    val runLogRetentionPolicy: StateFlow<RunLogRetentionPolicy> = _runLogRetentionPolicy.asStateFlow()

    private val _runLogRetentionPreview = MutableStateFlow<RunLogRetentionPreview?>(null)
    val runLogRetentionPreview: StateFlow<RunLogRetentionPreview?> = _runLogRetentionPreview.asStateFlow()

    init {
        refreshRunLogPage()
        scope.launch {
            runCatching { pruneRunLogs(_runLogRetentionPolicy.value) }
        }
        // A run that lands while only the first page is loaded joins it without a Refresh. Once more
        // pages are loaded the list stays put, since reloading would drop them under the reader.
        scope.launch {
            db.runLogDao().maximumIdFlow().distinctUntilChanged().collectLatest { newestId ->
                delay(RUN_LOG_LIVE_DEBOUNCE_MS)
                // A load already running may have taken its snapshot before this run landed.
                val page = _runLogPage.first { !it.loading }
                val snapshot = page.snapshot ?: return@collectLatest
                if (newestId != null && newestId > snapshot.snapshotMaxId &&
                    page.entries.size <= DEFAULT_RUN_LOG_PAGE_SIZE
                ) {
                    refreshRunLogPage()
                }
            }
        }
    }

    fun updateRunLogFilters(filters: RunLogFilterState) {
        val previous = _runLogFilters.value
        if (previous == filters) return
        _runLogFilters.value = filters
        runLogQueryDebounceJob?.cancel()
        // Typing changes only the query, and each character otherwise cost a snapshot, a count and
        // a page query. Everything else (status, task, date) is a discrete choice and reloads at
        // once.
        if (filters.copy(query = previous.query) == previous) {
            runLogQueryDebounceJob = scope.launch {
                delay(RUN_LOG_QUERY_DEBOUNCE_MS)
                refreshRunLogPage()
            }
        } else {
            refreshRunLogPage()
        }
    }

    fun refreshRunLogPage() {
        runLogPageJob?.cancel()
        // Keep what is on screen while reloading. Replacing it with an empty state made every
        // refresh - including one per keystroke in the search field - blank the list and flash the
        // loading state.
        _runLogPage.value = _runLogPage.value.copy(loading = true, failed = false)
        val filters = _runLogFilters.value
        runLogPageJob = scope.launch {
            try {
                val (snapshot, page) = withContext(Dispatchers.IO) {
                    val opened = db.runLogDao().openSnapshot(filters.toStorageQuery())
                    opened to db.runLogDao().loadPage(opened)
                }
                _runLogPage.value = RunLogPageUiState(
                    entries = page.entries.map { it.toDomain() }.toImmutableList(),
                    totalCount = snapshot.totalCount,
                    hasMore = page.hasMore,
                    loading = false,
                    snapshot = snapshot,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _runLogPage.value = _runLogPage.value.copy(loading = false, failed = true)
                events.send(loggedUiErrorMessage(error, R.string.ui_error_run_logs_load))
            }
        }
    }

    fun loadNextRunLogPage() {
        val current = _runLogPage.value
        val snapshot = current.snapshot ?: return
        if (current.loading || !current.hasMore) return
        val cursor = current.entries.lastOrNull()?.let { com.opentasker.core.storage.RunLogKey(it.timestamp, it.id) }
            ?: return
        _runLogPage.value = current.copy(loading = true)
        runLogPageJob = scope.launch {
            try {
                val page = withContext(Dispatchers.IO) { db.runLogDao().loadPage(snapshot, cursor) }
                val existingIds = current.entries.mapTo(mutableSetOf()) { it.id }
                val appended = page.entries.map { it.toDomain() }.filterNot { it.id in existingIds }
                _runLogPage.value = current.copy(
                    entries = (current.entries + appended).toImmutableList(),
                    hasMore = page.hasMore,
                    loading = false,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _runLogPage.value = current.copy(loading = false)
                events.send(loggedUiErrorMessage(error, R.string.ui_error_run_logs_more))
            }
        }
    }

    fun exportRunLogs(uri: Uri, format: RunLogExportFormat, allRetained: Boolean = false) {
        scope.launch {
            try {
                val exported = withContext(Dispatchers.IO) {
                    val snapshot = if (allRetained) {
                        db.runLogDao().openSnapshot(RunLogQuery())
                    } else {
                        _runLogPage.value.snapshot ?: db.runLogDao().openSnapshot(_runLogFilters.value.toStorageQuery())
                    }
                    val output = appContext.contentResolver.openOutputStream(uri, "w")
                        ?: error("Could not open the export destination")
                    output.use { RunLogExporter(db.runLogDao()).export(snapshot, format, it) }
                }
                events.send(uiPluralMessage(R.plurals.ui_message_run_logs_exported, exported, exported))
            } catch (error: Exception) {
                events.send(loggedUiErrorMessage(error, R.string.ui_error_run_log_export))
            }
        }
    }

    fun requestRunLogRetention(policy: RunLogRetentionPolicy) {
        scope.launch {
            val normalized = policy.normalized()
            runCatching {
                withContext(Dispatchers.IO) {
                    val dao = db.runLogDao()
                    RunLogRetentionPreview(
                        policy = normalized,
                        storedCount = dao.count(),
                        prunableCount = dao.countPrunable(
                            maxEntries = normalized.maxEntries,
                            minimumTimestamp = normalized.minimumTimestamp(System.currentTimeMillis()),
                        ),
                        oldestTimestamp = dao.oldestTimestamp(),
                    )
                }
            }.onSuccess { preview ->
                if (preview.prunableCount == 0) updateRunLogRetention(preview.policy)
                else _runLogRetentionPreview.value = preview
            }.onFailure { events.send(loggedUiErrorMessage(it, R.string.ui_error_retention_preview)) }
        }
    }

    fun dismissRunLogRetentionPreview() {
        _runLogRetentionPreview.value = null
    }

    fun confirmRunLogRetention() {
        val preview = _runLogRetentionPreview.value ?: return
        _runLogRetentionPreview.value = null
        updateRunLogRetention(preview.policy)
    }

    fun updateRunLogRetention(policy: RunLogRetentionPolicy) {
        scope.launch {
            val normalized = policy.normalized()
            runCatching {
                runLogRetentionSettings.save(normalized)
                _runLogRetentionPolicy.value = normalized
                pruneRunLogs(normalized)
            }
                .onSuccess { deleted ->
                    events.send(
                        if (deleted > 0) {
                            uiPluralMessage(R.plurals.ui_message_retention_updated_pruned, deleted, deleted)
                        } else {
                            uiMessage(R.string.ui_message_retention_updated)
                        },
                    )
                    refreshRunLogPage()
                }
                .onFailure { events.send(loggedUiErrorMessage(it, R.string.ui_error_retention_update)) }
        }
    }

    /**
     * Deletes ordinary run history on request. Pinned rows and held rows waiting to be replayed
     * survive, because a log purge has no Undo and those are the rows a user cannot recreate.
     */
    fun clearRunLog() {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { db.runLogDao().clearUnpinned() }
            }
                .onSuccess { deleted ->
                    refreshRunLogPage()
                    events.send(uiMessage(R.string.run_log_cleared, deleted))
                }
                .onFailure { events.send(loggedUiErrorMessage(it, R.string.ui_error_retention_update)) }
        }
    }

    private suspend fun pruneRunLogs(policy: RunLogRetentionPolicy): Int =
        db.runLogDao().applyRetention(policy, System.currentTimeMillis())

    fun setRunLogStarred(entry: RunLogEntry, starred: Boolean = !entry.starred) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { db.runLogDao().setStarred(entry.id, starred) }
            }.onSuccess { refreshRunLogPage() }
                .onFailure { events.send(loggedUiErrorMessage(it, R.string.ui_error_generic)) }
        }
    }
}

private const val RUN_LOG_QUERY_DEBOUNCE_MS = 300L
private const val RUN_LOG_LIVE_DEBOUNCE_MS = 300L
