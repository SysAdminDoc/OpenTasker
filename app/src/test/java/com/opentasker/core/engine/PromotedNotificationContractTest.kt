package com.opentasker.core.engine

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertTrue
import org.junit.Test

class PromotedNotificationContractTest {
    private val repoRoot: Path = listOf(Path.of("."), Path.of("app"))
        .first { Files.isDirectory(it.resolve("src/main")) }

    @Test
    fun manifestAndServiceDeclareThePromotedNotificationPath() {
        val manifest = repoRoot.resolve("src/main/AndroidManifest.xml").readText()
        val service = repoRoot.resolve("src/main/java/com/opentasker/core/engine/AutomationService.kt").readText()
        val progress = repoRoot.resolve("src/main/java/com/opentasker/core/actions/BuiltInActions.kt").readText()

        assertTrue("manifest must request promoted notifications", "android.permission.POST_PROMOTED_NOTIFICATIONS" in manifest)
        assertTrue("service must use the shared promotion eligibility path", "PromotedOngoingNotificationSupport.build" in service)
        assertTrue("service must track active tasks", "withTaskPresence" in service)
        assertTrue("progress notifications must use the shared promotion eligibility path", "PromotedOngoingNotificationSupport.build" in progress)
        assertTrue("service channel must not use IMPORTANCE_MIN", "ENGINE_CHANNEL_IMPORTANCE" in service)
    }

    @Test
    fun setupOffersThePlatformPromotionSettingsScreen() {
        val setup = listOf("PermissionOnboardingScreen.kt", "SetupRows.kt")
            .joinToString("\n") { repoRoot.resolve("src/main/java/com/opentasker/ui/screens/$it").readText() }

        assertTrue("Setup must detect promoted notification access", "canPostPromotedNotifications" in setup)
        assertTrue("Setup must link to Android's promotion settings", "ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS" in setup)
        assertTrue("Setup must keep promotion optional", "optional = true" in setup)
    }
}
