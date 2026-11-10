package com.opentasker.core.contexts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Manifest NFC dispatch delivers a tag by starting the filtered component. With the default
 * standard launch mode that means a brand new MainActivity on top of the task, so onNewIntent is
 * never called and each tap stacks another copy of the app: the armed tag-write editor stays in
 * the buried instance while the write result is delivered to an instance the user cannot see.
 *
 * singleTop is therefore a correctness requirement of the activity behind the NFC filter, not a
 * preference. The filter itself sits on an alias only the NFC service may start, so another app
 * can't hand MainActivity a forged tag (NfcDeliveryGate checks the alias on the way in).
 */
class NfcDispatchManifestContractTest {
    private val components = loadMainManifest().let { root ->
        listOf("activity", "activity-alias").flatMap { tag ->
            val nodes = root.getElementsByTagName(tag)
            (0 until nodes.length).map { nodes.item(it) as Element }
        }
    }

    @Test
    fun theOnlyNfcEntryIsAnAliasOnlyTheNfcServiceCanStart() {
        val nfcEntries = components.filter { it.filtersAny(NFC_ACTIONS) }

        assertEquals("exactly one component should filter NFC tag discovery", 1, nfcEntries.size)
        val entry = nfcEntries.single()
        assertEquals("activity-alias", entry.tagName)
        assertEquals(NfcDeliveryGate.DISPATCH_ALIAS, qualified(entry.getAttribute("android:name")))
        assertEquals("android.permission.DISPATCH_NFC_MESSAGE", entry.getAttribute("android:permission"))
        assertEquals("the system can only deliver tags to an exported entry", "true", entry.getAttribute("android:exported"))
    }

    @Test
    fun theActivityBehindTheNfcAliasIsSingleTop() {
        val alias = components.single { it.filtersAny(NFC_ACTIONS) }
        val target = qualified(alias.getAttribute("android:targetActivity"))
        val activity = components.single { it.tagName == "activity" && qualified(it.getAttribute("android:name")) == target }

        assertTrue(
            "the activity NFC tags start must set android:launchMode=\"singleTop\" so onNewIntent " +
                "receives the tag instead of a new activity instance being stacked",
            activity.getAttribute("android:launchMode") in setOf("singleTop", "singleTask"),
        )
    }

    private fun Element.filtersAny(actions: Set<String>): Boolean {
        val filters = getElementsByTagName("intent-filter")
        return (0 until filters.length).any { i ->
            val actionNodes = (filters.item(i) as Element).getElementsByTagName("action")
            (0 until actionNodes.length).any { (actionNodes.item(it) as Element).getAttribute("android:name") in actions }
        }
    }

    // The manifest has no package attribute; relative names resolve against the Gradle namespace.
    private fun qualified(name: String): String = if (name.startsWith(".")) NAMESPACE + name else name

    private fun loadMainManifest() =
        DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(
                listOf(
                    File("src/main/AndroidManifest.xml"),
                    File("app/src/main/AndroidManifest.xml"),
                ).first { it.exists() },
            )
            .documentElement

    private companion object {
        const val NAMESPACE = "com.opentasker.app"
        val NFC_ACTIONS = setOf(
            "android.nfc.action.TAG_DISCOVERED",
            "android.nfc.action.TECH_DISCOVERED",
            "android.nfc.action.NDEF_DISCOVERED",
        )
    }
}
