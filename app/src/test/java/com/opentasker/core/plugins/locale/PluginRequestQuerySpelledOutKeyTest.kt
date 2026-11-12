package com.opentasker.core.plugins.locale

import com.opentasker.core.contexts.packagesAskingForQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * primitive ftpd asks for a re-query with its edit screen's class under
 * `extra.ACTIVITY_CLASS_NAME`, not the standard `extra.ACTIVITY`. The request used to be dropped,
 * so its profile only caught up on the next 30 second poll.
 */
class PluginRequestQuerySpelledOutKeyTest {

    private val ftpdEditor = "org.primftpd.remotecontrol.TaskerEditConditionActivity"

    @Test
    fun aRequestWithTheClassUnderTheSpelledOutKeyNamesThePlugin() {
        val extras = mapOf(LocalePluginContract.EXTRA_ACTIVITY_CLASS_NAME_SPELLED_OUT to ftpdEditor)

        val requested = LocalePluginRequestQueryEvents.requestedActivityClass(extras::get)
        val event = LocalePluginRequestQueryEvents.buildEvent(requested)

        assertEquals(ftpdEditor, event?.metadata?.get("activityClass"))
        assertEquals(
            setOf("org.primftpd"),
            packagesAskingForQuery(requested, listOf("org.primftpd", "com.example.other")) { pkg ->
                if (pkg == "org.primftpd") setOf(ftpdEditor) else setOf("com.example.other.Edit")
            },
        )
    }

    @Test
    fun theStandardKeyWinsWhenBothAreThere() {
        val extras = mapOf(
            LocalePluginContract.EXTRA_STRING_ACTIVITY_CLASS_NAME to "com.example.plugin.Edit",
            LocalePluginContract.EXTRA_ACTIVITY_CLASS_NAME_SPELLED_OUT to ftpdEditor,
        )

        assertEquals("com.example.plugin.Edit", LocalePluginRequestQueryEvents.requestedActivityClass(extras::get))
    }

    @Test
    fun aBlankStandardKeyFallsBackToTheSpelledOutOne() {
        val extras = mapOf(
            LocalePluginContract.EXTRA_STRING_ACTIVITY_CLASS_NAME to " ",
            LocalePluginContract.EXTRA_ACTIVITY_CLASS_NAME_SPELLED_OUT to ftpdEditor,
        )

        assertEquals(ftpdEditor, LocalePluginRequestQueryEvents.requestedActivityClass(extras::get))
    }

    @Test
    fun aRequestNamingNoClassStillMakesNoEvent() {
        val requested = LocalePluginRequestQueryEvents.requestedActivityClass(emptyMap<String, String>()::get)

        assertEquals("", requested)
        assertNull(LocalePluginRequestQueryEvents.buildEvent(requested))
    }
}
