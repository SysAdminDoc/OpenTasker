package com.opentasker.core.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationLinkButtonTest {
    private fun parse(url: String?, label: String? = null, index: Int = 1) = NotificationLinkButton.parse(
        buildMap {
            url?.let { put("button${index}_url", it) }
            label?.let { put("button${index}_label", it) }
        },
        index,
    )

    @Test
    fun aButtonWithNoAddressIsNotALinkButton() {
        assertNull(parse(null))
        assertNull(parse("   "))
    }

    @Test
    fun anAddressOpensWithTheChosenLabel() {
        assertEquals(
            NotificationLinkButton.Link("https://duckduckgo.com/?q=%2B1555", "Search"),
            parse(" https://duckduckgo.com/?q=%2B1555 ", "Search"),
        )
    }

    @Test
    fun aLinkWithNoLabelShowsTheSiteRatherThanNothing() {
        assertEquals(
            NotificationLinkButton.Link("https://search.brave.com/search?q=1", "search.brave.com"),
            parse("https://search.brave.com/search?q=1"),
        )
        assertEquals(
            NotificationLinkButton.Link("https://example.com?q=1", "example.com"),
            parse("https://example.com?q=1", "  "),
        )
    }

    @Test
    fun theSameSchemesAsOpenUrlAreAllowed() {
        OpenUrlAction.allowedSchemes().forEach { scheme ->
            assertEquals(scheme, true, parse("$scheme:something") is NotificationLinkButton.Link)
        }
        assertEquals(true, parse("HTTPS://example.com") is NotificationLinkButton.Link)
    }

    @Test
    fun anythingElseIsBlockedBeforeItReachesAnIntent() {
        listOf(
            "intent://scan/#Intent;scheme=zxing;end" to "intent",
            "javascript:alert(1)" to "javascript",
            "file:///sdcard/secret.txt" to "file",
            "content://sms/inbox" to "content",
            "//example.com/path" to "",
            "no scheme at all" to "",
        ).forEach { (url, scheme) ->
            assertEquals(url, NotificationLinkButton.Blocked(scheme), parse(url))
        }
    }
}
