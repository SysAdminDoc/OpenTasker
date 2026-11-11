package com.opentasker.core.platform

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.contexts.BroadcastContextEvents
import com.opentasker.core.contexts.PushContextEvents
import com.opentasker.core.contexts.ShareContextEvents
import com.opentasker.core.external.AutomationTargetContract
import com.opentasker.core.model.Profile
import com.opentasker.core.plugins.locale.LocaleConditionQueryReceiver
import com.opentasker.core.plugins.locale.LocalePluginContract
import com.opentasker.core.plugins.locale.LocalePluginRequestQueryEvents
import com.opentasker.core.plugins.locale.LocalePluginTarget
import com.opentasker.core.plugins.locale.LocaleSettingFireReceiver
import com.opentasker.core.scripting.TermuxCommandBroker
import com.opentasker.core.storage.toEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ObjectInputStream
import java.io.Serializable
import java.util.concurrent.atomic.AtomicBoolean

private val serializableBuilt = AtomicBoolean(false)
private val parcelableBuilt = AtomicBoolean(false)

/** Stands in for a class packed in the app whose readObject does something when it's built. */
class SmuggledSerializable : Serializable {
    private fun readObject(input: ObjectInputStream) {
        input.defaultReadObject()
        serializableBuilt.set(true)
    }

    private companion object {
        const val serialVersionUID = 1L
    }
}

class SmuggledParcelable : Parcelable {
    override fun describeContents() = 0

    override fun writeToParcel(dest: Parcel, flags: Int) = Unit

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<SmuggledParcelable> {
            override fun createFromParcel(source: Parcel): SmuggledParcelable {
                parcelableBuilt.set(true)
                return SmuggledParcelable()
            }

            override fun newArray(size: Int) = arrayOfNulls<SmuggledParcelable>(size)
        }
    }
}

/**
 * Each entry point that reads what another app sent gets an intent with a planted Serializable and
 * Parcelable among ordinary extras. The direct readers get it through a Parcel and handed the app's
 * own class loader, the way the system delivers it; the two manifest receivers get a real ordered
 * broadcast. Neither object may be built. On Android 13 and newer each value is read on its own, so
 * the ordinary extras are kept. Older versions read every value at once, so there a reader gets
 * none of them: a broadcast context still fires without its extras, and the rest refuse the intent.
 */
@RunWith(AndroidJUnit4::class)
class ForeignExtrasReadersTest {
    private val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun reset() {
        serializableBuilt.set(false)
        parcelableBuilt.set(false)
    }

    private fun Intent.planted(): Intent = this
        .putExtra("planted.serializable", SmuggledSerializable())
        .putExtra("planted.parcelable", SmuggledParcelable())

    private fun Bundle.planted(): Bundle = apply {
        putSerializable("planted.serializable", SmuggledSerializable())
        putParcelable("planted.parcelable", SmuggledParcelable())
    }

    /** Through a Parcel, then given the loader that can build the planted classes, as the system does. */
    private fun delivered(sent: Intent): Intent {
        val parcel = Parcel.obtain()
        val received = try {
            sent.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            Intent.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
        return received.apply { setExtrasClassLoader(this@ForeignExtrasReadersTest.javaClass.classLoader) }
    }

    private fun assertNothingBuilt() {
        assertFalse("the Serializable was built", serializableBuilt.get())
        assertFalse("the Parcelable was built", parcelableBuilt.get())
    }

    @Test
    fun aBroadcastContextKeepsItsOrdinaryExtras() {
        val sent = Intent("com.example.PING").putExtra("mode", "night").putExtra("level", 3).planted()

        val extras = BroadcastContextEvents.readExtras(delivered(sent))

        assertNothingBuilt()
        assertTrue(extras.lossy)
        if (modern) {
            assertEquals(mapOf("level" to "3", "mode" to "night"), extras.values)
        } else {
            assertEquals(emptyMap<String, String>(), extras.values)
        }
    }

    private fun requestQuery(selection: Bundle) = Intent(LocalePluginContract.ACTION_REQUEST_QUERY)
        .putExtra(LocalePluginContract.EXTRA_STRING_ACTIVITY_CLASS_NAME, "com.example.plugin.EditActivity")
        .putExtra(LocalePluginContract.EXTRA_BUNDLE, selection)

    @Test
    fun aLocaleRequestQueryKeepsItsSelection() {
        val sent = requestQuery(Bundle().apply { putString("mode", "night") }).planted()

        val event = LocalePluginRequestQueryEvents.buildEventFromIntent(delivered(sent))

        assertNothingBuilt()
        if (modern) {
            assertEquals("com.example.plugin.EditActivity", event?.metadata?.get("activityClass"))
            assertTrue(event?.metadata?.get("bundleJson").orEmpty().contains("night"))
        } else {
            assertNull(event)
        }
    }

    @Test
    fun aLocaleRequestQueryWithAPlantedSelectionIsRefused() {
        val sent = requestQuery(Bundle().apply { putString("mode", "night") }.planted())

        assertNull(LocalePluginRequestQueryEvents.buildEventFromIntent(delivered(sent)))
        assertNothingBuilt()
    }

    @Test
    fun aLocaleConditionQueryKeepsItsSelection() {
        val sent = Intent(LocalePluginContract.ACTION_QUERY_CONDITION)
            .putExtra(LocalePluginContract.EXTRA_BUNDLE, Bundle().apply { putString("mode", "night") })
            .planted()

        val query = LocaleConditionQueryReceiver.readQuery(delivered(sent))

        assertNothingBuilt()
        if (modern) assertEquals(mapOf("mode" to "night"), query) else assertNull(query)
    }

    @Test
    fun aShareKeepsItsText() {
        val sent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "hello there").planted()

        val event = ShareContextEvents.parseIntent(delivered(sent), nowMs = 1L)

        assertNothingBuilt()
        if (modern) assertEquals("hello there", event?.metadata?.get("text")) else assertNull(event)
    }

    @Test
    fun aShareWithAPlantedStreamIsRefused() {
        val sent = Intent(Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(Intent.EXTRA_TEXT, "hello there")
            .putExtra(Intent.EXTRA_STREAM, SmuggledParcelable())
            .putExtra("planted.serializable", SmuggledSerializable())

        assertNull(ShareContextEvents.parseIntent(delivered(sent), nowMs = 1L))
        assertNothingBuilt()
    }

    @Test
    fun aTermuxResultKeepsItsOutput() {
        val requestId = "request-1"
        val sent = Intent("com.opentasker.action.TERMUX_COMMAND_RESULT")
            .putExtra("com.opentasker.extra.TERMUX_REQUEST_ID", requestId)
            .putExtra("result", Bundle().apply {
                putString("stdout", "done")
                putInt("exitCode", 0)
            }.planted())
            .planted()

        val result = TermuxCommandBroker.readResult(delivered(sent), requestId)

        assertNothingBuilt()
        if (modern) {
            assertEquals("done", result?.stdout)
            assertEquals(0, result?.exitCode)
        } else {
            assertNull("an unreadable result is left to time out", result)
        }
    }

    @Test
    fun aPushDeliveryKeepsItsOrdinaryExtras() {
        val token = "push-token"
        // Planted under keys the reader asks for, so Android 13 and newer would build them too.
        val sent = Intent(PushContextEvents.ACTION_PUSH_EVENT)
            .putExtra(PushContextEvents.EXTRA_TOKEN, token)
            .putExtra(PushContextEvents.EXTRA_TOPIC, SmuggledSerializable())
            .putExtra(PushContextEvents.NTFY_EXTRA_TOPIC, "doorbell")
            .putExtra(PushContextEvents.EXTRA_EVENT_ID, "event-1")
            .putExtra(PushContextEvents.NTFY_EXTRA_MESSAGE_BYTES, SmuggledParcelable())

        val event = PushContextEvents.parseIntent(delivered(sent), expectedToken = token, nowMs = 1L)

        assertNothingBuilt()
        if (modern) assertEquals("doorbell", event?.metadata?.get("topic")) else assertNull(event)
    }

    private suspend fun sendOrdered(intent: Intent, permission: String?): Pair<Int, Bundle?> {
        val result = CompletableDeferred<Pair<Int, Bundle?>>()
        context.sendOrderedBroadcast(
            intent,
            permission,
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

    @Test
    fun aLocaleFireBuildsNothing() = runBlocking {
        val saved = LocalePluginTarget.buildResultBundle(987_654_321L, "Nothing", "not-a-grant").planted()
        val sent = Intent(LocalePluginContract.ACTION_FIRE_SETTING)
            .setClass(context, LocaleSettingFireReceiver::class.java)
            .putExtra(LocalePluginContract.EXTRA_BUNDLE, saved)
            .planted()

        val (code, _) = sendOrdered(sent, null)

        assertEquals("the fire receiver sets no result", UNTOUCHED_CODE, code)
        assertNothingBuilt()
    }

    @Test
    fun anAutomationQueryKeepsItsOrdinaryExtras() = runBlocking {
        // Switched off, so nothing runs it. Finding it shows the name extra was read.
        val name = "planted query ${System.nanoTime()}"
        val profiles = OpenTaskerApp_NoHilt.db.profileDao()
        val id = profiles.insert(Profile(name = name, enabled = false, enterTaskId = 0L).toEntity())
        try {
            val sent = Intent(AutomationTargetContract.ACTION_QUERY_STATUS)
                .setPackage(context.packageName)
                .putExtra(AutomationTargetContract.EXTRA_PROFILE_NAME, name)
                .planted()

            val (code, extras) = sendOrdered(sent, AutomationTargetContract.PERMISSION)

            assertNothingBuilt()
            if (modern) {
                assertEquals(Activity.RESULT_OK, code)
                assertTrue(extras?.getBoolean(AutomationTargetContract.EXTRA_PROFILE_FOUND, false) ?: false)
            } else {
                assertEquals(Activity.RESULT_CANCELED, code)
            }
        } finally {
            profiles.getById(id)?.let { profiles.delete(it) }
        }
    }

    private companion object {
        const val UNTOUCHED_CODE = 4242
        const val DELIVERY_TIMEOUT_MS = 10_000L
    }
}
