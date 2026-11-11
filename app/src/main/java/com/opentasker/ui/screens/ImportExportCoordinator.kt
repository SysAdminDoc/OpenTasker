package com.opentasker.ui.screens

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The step a transfer is on, so a long import can say more than "working". */
enum class TransferStage {
    Preflight,
    Decode,
    Plan,
    Write,
}

data class TransferProgress(val stage: TransferStage, val fraction: Float? = null)

/** Reading a picked or pasted automation file: check it, then decode it. */
internal val FILE_PREVIEW_STEPS = listOf(TransferStage.Preflight, TransferStage.Decode)

/**
 * How far along [stage] is when a lane runs [steps] in order: the share of steps already done. A
 * lane with one step, or a stage outside its list, has nothing to measure, so the bar stays
 * indeterminate rather than sitting at a made-up number.
 */
internal fun stepFraction(steps: List<TransferStage>, stage: TransferStage): Float? {
    if (steps.size < 2) return null
    val done = steps.indexOf(stage).takeIf { it >= 0 } ?: return null
    return done.toFloat() / steps.size
}

/**
 * [runCatching] for a lane's body that lets Stop through. Plain runCatching caught the
 * CancellationException Stop throws too, so stopping an import showed "Couldn't read" and logged
 * an error for something the user asked for.
 */
internal inline fun <T> runTransferCatching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (stopped: CancellationException) {
    throw stopped
} catch (error: Throwable) {
    Result.failure(error)
}

/**
 * Owns one import/export lane: its busy flag, its progress, and the job running it.
 *
 * Two things made these operations feel stuck. Nothing held the job, so a large Tasker or
 * MacroDroid backup could not be cancelled, and the only feedback was a relabelled button. Worse,
 * the busy flag was cleared on the last line of the coroutine, so anything that ended early left
 * the UI permanently busy. Clearing it in a `finally` is what makes cancellation safe: a cancelled
 * coroutine still runs its finally blocks.
 *
 * Cancelling never has to undo a write, because every importer decodes and reviews before the
 * database transaction; stopping mid-decode simply means no transaction was ever started.
 */
class ImportExportCoordinator(private val scope: CoroutineScope) {
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _progress = MutableStateFlow<TransferProgress?>(null)
    val progress: StateFlow<TransferProgress?> = _progress.asStateFlow()

    private var job: Job? = null

    /**
     * Runs [block] unless this lane is already busy. The block reports the stage it has reached so
     * the UI can show something more useful than a spinner, and when the caller names its [steps]
     * the bar shows how many are done. Returns false when the lane was busy and nothing was started.
     */
    fun launch(
        steps: List<TransferStage> = emptyList(),
        block: suspend (report: (TransferStage) -> Unit) -> Unit,
    ): Boolean {
        if (_busy.value) return false
        _busy.value = true
        job = scope.launch {
            try {
                block { stage -> _progress.value = TransferProgress(stage, stepFraction(steps, stage)) }
            } finally {
                // Progress first, so whoever sees the lane free never sees a stage left over.
                _progress.value = null
                _busy.value = false
            }
        }
        return true
    }

    /** Stops the running transfer. The lane returns to idle through the block's finally. */
    fun cancel() {
        job?.cancel()
    }
}
