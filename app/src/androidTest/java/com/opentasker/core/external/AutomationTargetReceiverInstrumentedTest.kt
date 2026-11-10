package com.opentasker.core.external

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The automation target against the real platform broadcast delivery.
 *
 * Scene buttons, the widget and the Quick Settings tile send plain broadcasts; the Locale plugin
 * host sends an ordered one and reads the result. Only a device shows whether the receiver tells
 * the two apart after goAsync(), and whether a plain fire leaves an error in the log.
 */
@RunWith(AndroidJUnit4::class)
class AutomationTargetReceiverInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun anOrderedQueryGetsItsResultCodeAndExtras() = runBlocking {
        val (code, extras) = sendOrderedQuery()

        assertEquals(Activity.RESULT_OK, code)
        assertTrue(extras?.containsKey(AutomationTargetContract.EXTRA_TASK_COUNT) == true)
        assertTrue(extras?.containsKey(AutomationTargetContract.EXTRA_PROFILE_COUNT) == true)
        assertFalse(extras?.getBoolean(AutomationTargetContract.EXTRA_PROFILE_FOUND, true) ?: true)
    }

    @Test
    fun aPlainBroadcastLeavesNoResultErrorInTheLog() = runBlocking {
        val start = "OT_RECEIVER_START_${System.nanoTime()}"
        val end = "OT_RECEIVER_END_${System.nanoTime()}"
        Log.i(MARKER_TAG, start)

        context.sendBroadcast(statusIntent(), AutomationTargetContract.PERMISSION)
        // The ordered query is answered after the plain one was delivered, and the pause covers
        // the plain one's coroutine finishing on another worker.
        sendOrderedQuery()
        delay(SETTLE_MS)
        Log.i(MARKER_TAG, end)

        val log = readOwnLog()
        val window = log.substringAfter(start, missingDelimiterValue = "")
        assertTrue("the start marker must be in the log", window.isNotEmpty())
        assertTrue("the end marker must be in the log", window.contains(end))
        assertFalse(
            "a plain broadcast must not get a result set on it",
            window.substringBefore(end).contains(NON_ORDERED_ERROR),
        )
    }

    private suspend fun sendOrderedQuery(): Pair<Int, Bundle?> {
        val result = CompletableDeferred<Pair<Int, Bundle?>>()
        context.sendOrderedBroadcast(
            statusIntent(),
            AutomationTargetContract.PERMISSION,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    result.complete(resultCode to getResultExtras(false))
                }
            },
            null,
            UNTOUCHED_CODE,
            null,
            null,
        )
        return withTimeout(DELIVERY_TIMEOUT_MS) { result.await() }
    }

    private fun statusIntent() =
        Intent(AutomationTargetContract.ACTION_QUERY_STATUS).setPackage(context.packageName)

    private fun readOwnLog(): String {
        val command = "logcat -d -b main -v brief --pid=${Process.myPid()}"
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }

    private companion object {
        const val MARKER_TAG = "OTReceiverTest"
        const val NON_ORDERED_ERROR = "trying to return result during a non-ordered broadcast"
        const val UNTOUCHED_CODE = 4242
        const val DELIVERY_TIMEOUT_MS = 10_000L
        const val SETTLE_MS = 1_500L
    }
}
