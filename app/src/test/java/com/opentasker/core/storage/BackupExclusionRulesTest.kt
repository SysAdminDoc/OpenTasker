package com.opentasker.core.storage

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards that the app database and shared preferences (which will hold secrets) are excluded from
 * both cloud backup and device-to-device transfer. `allowBackup="false"` does not stop D2D transfer
 * on Android 12+, so these exclusions are the real protection.
 */
class BackupExclusionRulesTest {
    private fun resFile(name: String): File =
        listOf(File("src/main/res/xml/$name"), File("app/src/main/res/xml/$name")).first { it.exists() }

    private fun excludes(node: org.w3c.dom.Node): Boolean {
        val children = node.childNodes
        var hasDb = false
        var hasSharedPref = false
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeName != "exclude") continue
            val domain = child.attributes?.getNamedItem("domain")?.nodeValue
            val path = child.attributes?.getNamedItem("path")?.nodeValue
            if (domain == "database" && path == "opentasker.db") hasDb = true
            if (domain == "sharedpref" && (path == null || path.isEmpty())) hasSharedPref = true
        }
        return hasDb && hasSharedPref
    }

    private fun parse(name: String) =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(resFile(name)).documentElement

    @Test
    fun dataExtractionRulesExcludeDbAndPrefsFromCloudAndTransfer() {
        val root = parse("data_extraction_rules.xml")
        val cloud = root.getElementsByTagName("cloud-backup").item(0)
        val transfer = root.getElementsByTagName("device-transfer").item(0)
        assertTrue("cloud-backup must exclude db + prefs", excludes(cloud))
        assertTrue("device-transfer must exclude db + prefs", excludes(transfer))
    }

    private fun excludedFileDirs(node: org.w3c.dom.Node): Set<String> {
        val children = node.childNodes
        return (0 until children.length)
            .map { children.item(it) }
            .filter { it.nodeName == "exclude" && it.attributes?.getNamedItem("domain")?.nodeValue == "file" }
            .mapNotNull { it.attributes?.getNamedItem("path")?.nodeValue }
            .toSet()
    }

    /**
     * A local backup is the database again, sealed with a key that never leaves the phone, and a
     * crash log is written before redaction. Neither is any use on another device.
     */
    @Test
    fun localBackupsAndCrashLogsStayOnThePhone() {
        val root = parse("data_extraction_rules.xml")
        val wanted = setOf("backups", "crash_logs")

        assertTrue(excludedFileDirs(root.getElementsByTagName("device-transfer").item(0)).containsAll(wanted))
        assertTrue(excludedFileDirs(root.getElementsByTagName("cloud-backup").item(0)).containsAll(wanted))
        assertTrue(excludedFileDirs(parse("backup_rules.xml")).containsAll(wanted))
    }

    @Test
    fun legacyFullBackupContentExcludesDbAndPrefs() {
        val root = parse("backup_rules.xml")
        assertTrue("full-backup-content must exclude db + prefs", excludes(root))
    }
}
