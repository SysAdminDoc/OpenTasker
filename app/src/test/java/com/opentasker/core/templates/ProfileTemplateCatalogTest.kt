package com.opentasker.core.templates

import com.opentasker.core.actions.VpnTunnelParse
import com.opentasker.core.actions.VpnTunnelPolicy
import com.opentasker.core.capabilities.ActionCapabilityRegistry
import com.opentasker.core.model.ContextType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileTemplateCatalogTest {
    @Test
    fun catalogIncludesRoadmapPatterns() {
        val ids = ProfileTemplateCatalog.all.map { it.id }.toSet()

        assertEquals(
            setOf(
                "work-hours-focus",
                "headphones-media",
                "low-battery-saver",
                "wifi-arrival",
                "location-evidence-log",
                "app-usage-reminder",
                "find-my-phone",
                "meeting-mode-calendar",
                "nightstand-nfc-sleep",
                "search-incoming-numbers",
                "search-incoming-numbers-automatic",
                "vpn-off-at-home-tailscale",
                "vpn-off-at-home-wireguard",
            ),
            ids,
        )
    }

    @Test
    fun installableTemplatesAvoidUnsupportedActions() {
        val unsupported = ProfileTemplateCatalog.all
            .filter { it.installable }
            .flatMap { template -> template.actions.map { template.id to it.type } }
            .filter { (_, actionId) -> !ActionCapabilityRegistry.get(actionId).canAdd }

        assertTrue("Installable templates use unsupported actions: $unsupported", unsupported.isEmpty())
    }

    @Test
    fun installableTemplatesCreateDisabledProfilesAndTasksWithoutUnresolvedSlots() {
        ProfileTemplateCatalog.all.filter { it.installable }.forEach { template ->
            val applied = template.instantiate(template.defaults())

            assertFalse("${template.id} should be created disabled", applied.profile.enabled)
            assertTrue("${template.id} should include at least one context", applied.profile.contexts.isNotEmpty())
            assertTrue("${template.id} should include at least one action", applied.task.actions.isNotEmpty())

            val rendered = buildString {
                append(applied.profile.name)
                applied.profile.contexts.forEach { context ->
                    append(context.config.values.joinToString())
                }
                applied.task.actions.forEach { action ->
                    append(action.label)
                    append(action.args.values.joinToString())
                }
            }
            // A real template expression runs at task time. Only a bare {slot} would be a leftover.
            val slotsOnly = rendered.replace(Regex("""\{\{.*?\}\}"""), "")
            assertFalse("${template.id} left unresolved slots in output: $rendered", slotsOnly.contains('{') || slotsOnly.contains('}'))
        }
    }

    @Test
    fun theVpnTemplatesDropTheTunnelAtHomeAndBringItBackOnLeaving() {
        val wireGuard = ProfileTemplateCatalog.get("vpn-off-at-home-wireguard")!!
            .instantiate(mapOf("ssid" to "Casa", "tunnel" to "wg-home"))
        assertEquals(mapOf("key" to "wifi", "value" to "Casa"), wireGuard.profile.contexts.single().config)
        assertEquals(
            mapOf("client" to "wireguard", "state" to "down", "tunnel" to "wg-home"),
            wireGuard.task.actions.single().args,
        )
        assertEquals(
            mapOf("client" to "wireguard", "state" to "up", "tunnel" to "wg-home"),
            wireGuard.exitTask!!.actions.single().args,
        )

        val tailscale = ProfileTemplateCatalog.get("vpn-off-at-home-tailscale")!!
            .instantiate(mapOf("ssid" to "Casa"))
        assertEquals(mapOf("client" to "tailscale", "state" to "down"), tailscale.task.actions.single().args)
        assertEquals(mapOf("client" to "tailscale", "state" to "up"), tailscale.exitTask!!.actions.single().args)

        val everyAction = listOf(wireGuard, tailscale).flatMap { it.task.actions + it.exitTask!!.actions }
        assertTrue(
            "Each one has to be a request the action accepts",
            everyAction.all { VpnTunnelPolicy.parse(it.args) is VpnTunnelParse.Valid },
        )
        assertTrue("A different task has to run on the way out", wireGuard.task.name != wireGuard.exitTask!!.name)
    }

    @Test
    fun aTemplateWithoutExitActionsHasNoExitTask() {
        assertEquals(null, ProfileTemplateCatalog.get("wifi-arrival")!!.instantiate(emptyMap()).exitTask)
    }

    @Test
    fun aCallerSearchBuildsItsAddressFromTheChosenSiteAndTheEncodedNumber() {
        val encoded = "{{ event.incoming_number | urlencode }}"
        val manual = ProfileTemplateCatalog.get("search-incoming-numbers")!!
            .instantiate(mapOf("searchUrl" to "https://search.brave.com/search?q={number}&source=web"))
        val notification = manual.task.actions.single()

        assertEquals("notify.show", notification.type)
        assertEquals("Search", notification.args["button1_label"])
        assertEquals("https://search.brave.com/search?q=$encoded&source=web", notification.args["button1_url"])
        assertEquals("{{ event.incoming_number }}", notification.args["text"])

        val automatic = ProfileTemplateCatalog.get("search-incoming-numbers-automatic")!!
            .instantiate(emptyMap())
        assertEquals(listOf("url.open", "notify.show"), automatic.task.actions.map { it.type })
        assertEquals("https://duckduckgo.com/?q=$encoded", automatic.task.actions.first().args["url"])
    }

    @Test
    fun aCallerSearchNeverRunsForHiddenCallersOrContactsAndStartsOff() {
        listOf("search-incoming-numbers", "search-incoming-numbers-automatic").forEach { id ->
            val applied = ProfileTemplateCatalog.get(id)!!.instantiate(emptyMap())

            assertFalse(applied.profile.enabled)
            assertEquals(
                mapOf("event" to "incoming_call", "knownContact" to "false"),
                applied.profile.contexts.single().config,
            )
        }
    }

    @Test
    fun aSearchAddressMustBeWebAndSayWhereTheNumberGoes() {
        val template = ProfileTemplateCatalog.get("search-incoming-numbers")!!
        listOf(
            "duckduckgo.com/?q={number}",
            "javascript:alert('{number}')",
            "intent://x/{number}",
            "https://duckduckgo.com/",
            "https://duck duck.go/?q={number}",
        ).forEach { bad ->
            var refused = false
            try {
                template.instantiate(mapOf("searchUrl" to bad))
            } catch (expected: IllegalArgumentException) {
                refused = true
            }
            assertTrue("Accepted $bad", refused)
        }
    }

    @Test
    fun aSearchPlaceholderIsNotAnInputSomeoneCanSet() {
        val template = ProfileTemplateCatalog.get("search-incoming-numbers")!!
        var refused = false
        try {
            template.instantiate(mapOf("number" to "1234"))
        } catch (expected: IllegalArgumentException) {
            refused = true
        }

        assertTrue(refused)
    }

    @Test
    fun plannedTemplatesAreVisibleButNotInstallable() {
        val planned = ProfileTemplateCatalog.all.filter { it.availability == TemplateAvailability.Planned }

        assertEquals(1, planned.size)
        planned.forEach { template ->
            assertFalse(template.installable)
            var failed = false
            try {
                template.instantiate(template.defaults())
            } catch (expected: IllegalArgumentException) {
                failed = true
            }
            assertTrue("${template.id} should reject installation", failed)
        }
    }

    @Test
    fun nfcTemplateInstallsWithTagIdEventContext() {
        val template = ProfileTemplateCatalog.get("nightstand-nfc-sleep")!!

        assertTrue(template.installable)

        val applied = template.instantiate(template.defaults())
        val context = applied.profile.contexts.single()

        assertEquals(ContextType.EVENT, context.type)
        assertEquals("nfc", context.config["event"])
        assertEquals("04AABBCC", context.config["tagId"])
    }

    @Test
    fun calendarTemplateInstallsWithRedactedCalendarEventContext() {
        val template = ProfileTemplateCatalog.get("meeting-mode-calendar")!!

        assertTrue(template.installable)

        val applied = template.instantiate(template.defaults())
        val context = applied.profile.contexts.single()

        assertEquals(ContextType.EVENT, context.type)
        assertEquals("calendar", context.config["event"])
        assertEquals("during", context.config["state"])
        assertEquals("Work", context.config["calendar"])
    }

    @Test
    fun locationEvidenceTemplateInstallsWithLocationContext() {
        val template = ProfileTemplateCatalog.get("location-evidence-log")!!

        assertTrue(template.installable)

        val applied = template.instantiate(template.defaults())
        val context = applied.profile.contexts.single()
        val action = applied.task.actions.single()

        assertEquals(ContextType.LOCATION, context.type)
        assertEquals("40.7580", context.config["latitude"])
        assertEquals("-73.9855", context.config["longitude"])
        assertEquals("150", context.config["radiusMeters"])
        assertEquals("100", context.config["maxAccuracyMeters"])
        assertEquals("0", context.config["dwellSeconds"])
        assertEquals("log", action.type)
        assertTrue(action.args["message"].orEmpty().contains("40.7580,-73.9855"))
    }
}
