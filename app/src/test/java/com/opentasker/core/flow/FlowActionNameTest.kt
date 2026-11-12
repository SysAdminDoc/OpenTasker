package com.opentasker.core.flow

import com.opentasker.app.R
import com.opentasker.core.actions.registerActionMetadata
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class FlowActionNameTest {
    @Before
    fun registerMetadata() {
        // The app registers action names at startup; without it every type would fall back to its id.
        registerActionMetadata()
    }

    private val names = mapOf(R.string.catalog_action_brightness_set_name to "Set Brightness")

    private fun nameOf(res: Int): String = names[res] ?: "unexpected resource $res"

    @Test
    fun registeredTypeIsNamedLikeTasks() {
        assertEquals("Set Brightness", flowActionName("brightness.set", ::nameOf))
    }

    @Test
    fun unknownTypeKeepsItsId() {
        assertEquals("future.action", flowActionName("future.action", ::nameOf))
    }
}
