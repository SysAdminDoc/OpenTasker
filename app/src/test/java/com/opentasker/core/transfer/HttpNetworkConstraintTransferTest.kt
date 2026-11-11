package com.opentasker.core.transfer

import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The connection restriction has to survive an OpenTasker bundle round trip, and has to be
 * announced as lost on the way to Tasker, which has no equivalent field.
 */
class HttpNetworkConstraintTransferTest {

    private val restricted = Task(
        id = 1L,
        name = "Sync over Wi-Fi",
        actions = listOf(
            ActionSpec(
                id = 1L,
                type = "http.request",
                args = mapOf("url" to "https://example.test/sync", "network" to "wifi"),
            ),
        ),
    )

    @Test
    fun `an openTasker bundle keeps the connection restriction`() {
        val bundle = OpenTaskerBundle(appVersion = "0.0.0", exportedAtEpochMs = 0L, tasks = listOf(restricted))

        val decoded = OpenTaskerBundleCodec.decode(OpenTaskerBundleCodec.encode(bundle))

        assertEquals("wifi", decoded.tasks.single().actions.single().args["network"])
    }

    /** Tasker's HTTP Request (339) carries the request, and has no field for the restriction. */
    @Test
    fun `exporting to Tasker keeps the request and says the restriction stays behind`() {
        val result = TaskerXmlExporter.export(profiles = emptyList(), tasks = listOf(restricted))

        assertTrue(result.skippedActions.toString(), result.skippedActions.isEmpty())
        assertTrue("<code>339</code>" in result.xml)
        assertTrue(result.warnings.toString(), result.warnings.any { "only over Wi-Fi" in it })
    }
}
