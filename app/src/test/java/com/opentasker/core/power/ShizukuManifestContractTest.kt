package com.opentasker.core.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ShizukuManifestContractTest {
    @Test
    fun manifestQueriesShizukuManagerPackage() {
        val manifest = loadMainManifest()
        val queries = manifest.getElementsByTagName("queries")
        assertTrue("manifest must declare package visibility queries", queries.length > 0)

        val packages = manifest.getElementsByTagName("package")
        val queriedPackages = (0 until packages.length)
            .asSequence()
            .mapNotNull { packages.item(it).attributes.getNamedItem("android:name")?.nodeValue }

        assertTrue(
            "manifest must query Shizuku manager package",
            ShizukuPowerBackend.MANAGER_PACKAGE in queriedPackages,
        )
    }

    @Test
    fun manifestDeclaresTheProviderThatDeliversTheBinder() {
        val providers = loadMainManifest().getElementsByTagName("provider")
        val provider = (0 until providers.length)
            .map { providers.item(it).attributes }
            .firstOrNull { it.getNamedItem("android:name")?.nodeValue == "rikka.shizuku.ShizukuProvider" }

        assertNotNull("Shizuku and Sui can only hand over their binder through ShizukuProvider", provider)
        fun attribute(name: String) = provider!!.getNamedItem("android:$name")?.nodeValue
        // The server looks the provider up by "<package>.shizuku", and the library refuses to start
        // when it is not exported or is multiprocess.
        assertEquals("\${applicationId}.shizuku", attribute("authorities"))
        assertEquals("true", attribute("exported"))
        assertEquals("true", attribute("enabled"))
        assertEquals("false", attribute("multiprocess"))
        assertEquals("android.permission.INTERACT_ACROSS_USERS_FULL", attribute("permission"))
    }

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
}
