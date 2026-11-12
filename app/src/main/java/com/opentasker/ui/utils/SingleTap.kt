package com.opentasker.ui.utils

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * One gate for every Delete button in a list: an action run through it is dropped when it comes
 * less than [windowMs] after the last one that ran.
 *
 * Deletes run on the tap, with Undo in the snackbar. Once the first delete lands the next row
 * slides up under the finger, so the second tap of a double tap deletes that row too, and its Undo
 * only shows once the first delete's message is gone. A guard on each row can't see that, since
 * the row that slid up has never been tapped. Remember this once for the list and route each row's
 * delete through it.
 *
 * It's a time window, not a one-shot flag, because a delete can be refused (a variable a task
 * still reads stays put) and the next try has to work.
 */
@Composable
fun rememberSingleTap(windowMs: Long = SINGLE_TAP_WINDOW_MS): (() -> Unit) -> Unit {
    val gate = remember(windowMs) { TapGate(windowMs) }
    return remember(gate) { { action -> if (gate.tryPass(SystemClock.uptimeMillis())) action() } }
}

/**
 * The window check behind [rememberSingleTap], kept apart from Compose and the clock so it is
 * tested on the JVM. [tryPass] says whether a tap at [now] runs, and only a tap that runs restarts
 * the window, so a run of quick taps can't keep it shut.
 */
internal class TapGate(private val windowMs: Long) {
    private var lastPassedAt: Long? = null

    fun tryPass(now: Long): Boolean {
        val last = lastPassedAt
        if (last != null && now - last < windowMs) return false
        lastPassedAt = now
        return true
    }
}

/** Long enough to swallow a slow double tap, short enough that a deliberate next delete goes through. */
internal const val SINGLE_TAP_WINDOW_MS = 1_000L
