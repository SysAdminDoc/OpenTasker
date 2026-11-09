package com.opentasker.core.external

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A scene button and a Locale plugin fire reach the task runner through a broadcast that names
 * [AutomationTargetContract.PERMISSION] as the receiver permission. Android checks that against
 * the receiving package's granted permissions, with no pass for a sender in the same app, so
 * OpenTasker has to request the permission it declares or its own broadcast is dropped.
 */
class AutomationPermissionManifestContractTest {
    private fun names(tag: String): Set<String> {
        val manifest = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(
                listOf(
                    File("src/main/AndroidManifest.xml"),
                    File("app/src/main/AndroidManifest.xml"),
                ).first { it.exists() },
            )
            .documentElement
        val nodes = manifest.getElementsByTagName(tag)
        return (0 until nodes.length)
            .mapNotNull { nodes.item(it).attributes.getNamedItem("android:name")?.nodeValue }
            .toSet()
    }

    @Test
    fun theAppHoldsThePermissionItsOwnRunRequestsRequire() {
        assertTrue(AutomationTargetContract.PERMISSION in names("permission"))
        assertTrue(AutomationTargetContract.PERMISSION in names("uses-permission"))
    }
}
