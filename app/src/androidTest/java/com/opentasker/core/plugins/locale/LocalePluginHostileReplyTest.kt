package com.opentasker.core.plugins.locale

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ObjectInputStream
import java.io.Serializable
import java.util.concurrent.atomic.AtomicBoolean

private val serializableBuilt = AtomicBoolean(false)
private val parcelableBuilt = AtomicBoolean(false)

/** Stands in for a class packed in the app whose readObject does something when it's built. */
class PlantedSerializable : Serializable {
    private fun readObject(input: ObjectInputStream) {
        input.defaultReadObject()
        serializableBuilt.set(true)
    }

    private companion object {
        const val serialVersionUID = 1L
    }
}

class PlantedParcelable : Parcelable {
    override fun describeContents() = 0

    override fun writeToParcel(dest: Parcel, flags: Int) = Unit

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<PlantedParcelable> {
            override fun createFromParcel(source: Parcel): PlantedParcelable {
                parcelableBuilt.set(true)
                return PlantedParcelable()
            }

            override fun newArray(size: Int) = arrayOfNulls<PlantedParcelable>(size)
        }
    }
}

/**
 * A plugin's answer arrives as a parcel from another process. Each reader gets one, sent through a
 * Parcel and handed the app's own class loader the way the system delivers it, with a planted
 * Serializable and Parcelable next to ordinary values. Neither object may be built. On Android 13
 * and newer those keys are refused by name and the rest is kept; older versions read every value at
 * once, so there the whole set is refused instead.
 */
@RunWith(AndroidJUnit4::class)
class LocalePluginHostileReplyTest {
    private val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    @Before
    fun reset() {
        serializableBuilt.set(false)
        parcelableBuilt.set(false)
    }

    private fun variables() = Bundle().apply {
        putString("%out", "hello")
        putStringArray("%arr", arrayOf("a", "b"))
        putStringArrayList("%list", arrayListOf("c"))
        putSerializable("%planted", PlantedSerializable())
        putParcelable("%parcel", PlantedParcelable())
    }

    /** Through a Parcel, then given the loader that can build the planted classes, as the system does. */
    private fun <T : Parcelable> delivered(value: T, creator: Parcelable.Creator<T>): T {
        val parcel = Parcel.obtain()
        val received = try {
            value.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            creator.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
        val appLoader = javaClass.classLoader
        when (received) {
            is Intent -> received.setExtrasClassLoader(appLoader)
            is Bundle -> received.classLoader = appLoader
        }
        return received
    }

    private fun assertNothingBuilt() {
        assertFalse("the Serializable was built", serializableBuilt.get())
        assertFalse("the Parcelable was built", parcelableBuilt.get())
    }

    private fun assertOnlyThePlantedKeysAreRefused(reply: LocalePluginSettingReply) {
        assertEquals(LocalePluginContract.RESULT_CODE_OK, reply.resultCode)
        assertEquals(mapOf("out" to "hello"), reply.variables.values)
        assertEquals(mapOf("arr" to listOf("a", "b"), "list" to listOf("c")), reply.variables.arrays)
        assertEquals(listOf("%planted (couldn't be read)", "%parcel (couldn't be read)").sorted(), reply.variables.refused.sorted())
        assertNothingBuilt()
    }

    private fun completion(variables: Bundle) = Intent("com.opentasker.app.PLUGIN_FINISHED.test")
        .putExtra(LocalePluginContract.EXTRA_RESULT_CODE, LocalePluginContract.RESULT_CODE_OK)
        .putExtra(LocalePluginContract.EXTRA_VARIABLES, variables)

    @Test
    fun aCompletionIntentWithPlantedValuesKeepsTheRest() {
        assumeTrue(modern)
        val sent = completion(variables()).putExtra("planted.top", PlantedSerializable())

        assertOnlyThePlantedKeysAreRefused(LocalePluginCompletion.reply(delivered(sent, Intent.CREATOR)))
    }

    @Test
    fun orderedResultExtrasWithPlantedValuesKeepTheRest() {
        assumeTrue(modern)
        val sent = Bundle().apply {
            putBundle(LocalePluginContract.EXTRA_VARIABLES, variables())
            putParcelable("planted.top", PlantedParcelable())
        }

        assertOnlyThePlantedKeysAreRefused(
            LocalePluginCompletion.reply(LocalePluginContract.RESULT_CODE_OK, delivered(sent, Bundle.CREATOR)),
        )
    }

    @Test
    fun aConfigurationWithAPlantedValueIsRefusedByKeyWithoutBuildingIt() {
        assumeTrue(modern)
        val bundle = Bundle().apply {
            putString("text", "hi")
            putSerializable("planted", PlantedSerializable())
        }
        val sent = Intent().putExtra(LocalePluginContract.EXTRA_BUNDLE, bundle).putExtra("planted.top", PlantedParcelable())

        val refused = runCatching { LocalePluginConfigurationResultParser.parse(Activity.RESULT_OK, delivered(sent, Intent.CREATOR)) }
            .exceptionOrNull()

        assertTrue(refused.toString(), refused is IllegalStateException && refused.message == "Plugin bundle value for planted couldn't be read.")
        assertNothingBuilt()

        val plain = Intent().putExtra(LocalePluginContract.EXTRA_BUNDLE, Bundle().apply { putString("text", "hi") })
        assertEquals(
            mapOf("text" to LocaleBundleValue.Text("hi")),
            LocalePluginBundleCodec.decodeBundleForEditing(LocalePluginConfigurationResultParser.parse(Activity.RESULT_OK, delivered(plain, Intent.CREATOR)).bundleJson),
        )
    }

    @Test
    fun beforeAndroid13APlantedVariableDropsTheVariablesButKeepsTheCode() {
        assumeTrue(!modern)

        val reply = LocalePluginCompletion.reply(delivered(completion(variables()), Intent.CREATOR))

        assertEquals(LocalePluginContract.RESULT_CODE_OK, reply.resultCode)
        assertEquals(emptyMap<String, String>(), reply.variables.values)
        assertTrue(reply.variables.refused.toString(), reply.variables.refused.single().startsWith("the plugin's variables couldn't be read"))
        assertNothingBuilt()
    }

    @Test
    fun beforeAndroid13APlantedTopLevelValueFailsTheAnswerWithoutBuildingIt() {
        assumeTrue(!modern)
        val sent = completion(Bundle().apply { putString("%out", "hello") }).putExtra("planted.top", PlantedSerializable())

        val reply = LocalePluginCompletion.reply(delivered(sent, Intent.CREATOR))

        assertEquals(LocalePluginContract.RESULT_CODE_FAILED, reply.resultCode)
        assertNothingBuilt()
    }

    @Test
    fun beforeAndroid13APlantedConfigurationIsRefusedWithoutBuildingIt() {
        assumeTrue(!modern)
        val sent = Intent().putExtra(LocalePluginContract.EXTRA_BUNDLE, Bundle().apply { putSerializable("planted", PlantedSerializable()) })

        val refused = runCatching { LocalePluginConfigurationResultParser.parse(Activity.RESULT_OK, delivered(sent, Intent.CREATOR)) }
            .exceptionOrNull()

        assertTrue(refused.toString(), refused is IllegalStateException)
        assertNothingBuilt()
    }

    @Test
    fun aValueNestedDeepEnoughToRunOutOfStackIsRefusedWithoutACrash() {
        var nested = ArrayList<Any?>()
        repeat(DEPTH) { nested = arrayListOf<Any?>(nested) }
        val deep = nested
        // Writing recurses as deep as reading, so the plugin's side gets a stack that fits it.
        val sent = onThreadWithStack(WRITER_STACK_BYTES) {
            delivered(
                completion(Bundle().apply {
                    putString("%out", "hello")
                    putSerializable("%deep", deep)
                }),
                Intent.CREATOR,
            )
        }

        val reply = onThreadWithStack(READER_STACK_BYTES) { LocalePluginCompletion.reply(sent) }

        assertEquals(LocalePluginContract.RESULT_CODE_OK, reply.resultCode)
        if (modern) {
            assertEquals(mapOf("out" to "hello"), reply.variables.values)
            assertEquals(listOf("%deep (couldn't be read)"), reply.variables.refused)
        } else {
            assertTrue(reply.variables.refused.toString(), reply.variables.refused.single().startsWith("the plugin's variables couldn't be read"))
        }
    }

    private fun <T> onThreadWithStack(stackBytes: Long, block: () -> T): T {
        var result: Result<T>? = null
        val thread = Thread(null, { result = runCatching(block) }, "stack-$stackBytes", stackBytes)
        thread.start()
        thread.join()
        return checkNotNull(result).getOrThrow()
    }

    private companion object {
        const val DEPTH = 50_000
        const val WRITER_STACK_BYTES = 256L * 1024 * 1024
        const val READER_STACK_BYTES = 256L * 1024
    }
}
