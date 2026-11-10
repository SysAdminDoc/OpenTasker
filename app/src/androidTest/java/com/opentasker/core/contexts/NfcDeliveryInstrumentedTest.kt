package com.opentasker.core.contexts

import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.Parcel
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.Collections

/**
 * The tag entry point on a phone. MainActivity is exported for the launcher, so a forged tag
 * addressed to it must publish nothing, while the same tag through the NfcTagDispatch alias must
 * publish an event. The test runs in the app's own uid, which passes the alias's permission check
 * the way the NFC service does, and the alias start is the positive control that the forged
 * start wasn't missed for lack of a listener.
 */
class NfcDeliveryInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @After
    fun closeTheApp() = finishOpenActivities()

    @Test
    fun aColdStartThroughTheAliasPublishesTheTag() = runBlocking {
        finishOpenActivities()
        withTagIds { seen ->
            context.startActivity(tagIntent(ALIAS_TAG, NfcDeliveryGate.DISPATCH_ALIAS))
            awaitTag(seen, ALIAS_TAG)
        }
    }

    @Test
    fun aForgedTagSentToMainActivityPublishesNothing() = runBlocking {
        withTagIds { seen ->
            context.startActivity(tagIntent(FORGED_TAG, "com.opentasker.app.MainActivity"))
            delay(QUIET_MS)
            assertFalse("a forged tag reached the NFC trigger", NfcContextEvents.tagIdHex(FORGED_TAG) in seen)
            // MainActivity is on top now, so this one goes through onNewIntent.
            context.startActivity(tagIntent(ALIAS_TAG, NfcDeliveryGate.DISPATCH_ALIAS))
            awaitTag(seen, ALIAS_TAG)
            assertFalse(NfcContextEvents.tagIdHex(FORGED_TAG) in seen)
        }
    }

    private suspend fun withTagIds(block: suspend (List<String>) -> Unit) = coroutineScope {
        val seen = Collections.synchronizedList(mutableListOf<String>())
        val subscribed = CompletableDeferred<Unit>()
        val collector = launch(Dispatchers.Default) {
            NfcContextEvents.events
                .onSubscription { subscribed.complete(Unit) }
                .collect { event -> event.metadata["tagId"]?.let(seen::add) }
        }
        subscribed.await()
        try {
            block(seen)
        } finally {
            collector.cancel()
        }
    }

    private suspend fun awaitTag(seen: List<String>, id: ByteArray) {
        val hex = NfcContextEvents.tagIdHex(id)
        withTimeout(TIMEOUT_MS) {
            while (hex !in seen) delay(100)
        }
    }

    private fun tagIntent(id: ByteArray, component: String) =
        Intent(NfcAdapter.ACTION_TAG_DISCOVERED)
            .setClassName(context.packageName, component)
            .putExtra(NfcAdapter.EXTRA_TAG, mockTag(id))
            .putExtra(NfcAdapter.EXTRA_ID, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // Tag.writeToParcel's layout for a tag with no tag service behind it, which is what any app
    // can build. Android 14 added the cookie.
    private fun mockTag(id: ByteArray): Tag {
        val parcel = Parcel.obtain()
        try {
            parcel.writeInt(id.size)
            parcel.writeByteArray(id)
            parcel.writeInt(1)
            parcel.writeIntArray(intArrayOf(NFC_A))
            parcel.writeTypedArray(arrayOf(Bundle()), 0)
            parcel.writeInt(0)
            if (Build.VERSION.SDK_INT >= 34) parcel.writeLong(0L)
            parcel.writeInt(1)
            parcel.setDataPosition(0)
            return Tag.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }

    private fun finishOpenActivities() {
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            listOf(Stage.PRE_ON_CREATE, Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.STOPPED, Stage.RESTARTED)
                .flatMap { monitor.getActivitiesInStage(it) }
                .forEach { it.finish() }
        }
        instrumentation.waitForIdleSync()
    }

    private companion object {
        const val NFC_A = 1
        const val QUIET_MS = 3_000L
        const val TIMEOUT_MS = 10_000L
        val ALIAS_TAG = byteArrayOf(0x0A, 0x0B, 0x0C, 0x0D)
        val FORGED_TAG = byteArrayOf(0x0F, 0x0E, 0x0D, 0x0C)
    }
}
