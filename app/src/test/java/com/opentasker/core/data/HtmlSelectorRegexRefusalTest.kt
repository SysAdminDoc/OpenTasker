package com.opentasker.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

/**
 * Jsoup evaluates `:matches()` and `[attr~=regex]` with `java.util.regex`, which backtracks. Against
 * a body of up to a million characters a pattern like `(a+)+$` runs for effectively forever, inside
 * a plain loop that `TaskRunner`'s `withTimeout` cannot interrupt, so the task hangs instead of
 * failing. These selectors are refused before the pattern can reach jsoup.
 */
class HtmlSelectorRegexRefusalTest {

    private val catastrophic = "(a+)+$"

    @Test
    fun everyRegexBearingSelectorIsRefusedByName() {
        val refused = listOf(
            "p:matches($catastrophic)",
            "p:matchesOwn($catastrophic)",
            "p:matchesWholeText($catastrophic)",
            "p:matchesWholeOwnText($catastrophic)",
            "a[href~=$catastrophic]",
        )

        refused.forEach { selector ->
            val reason = StructuredDataReader.unsupportedHtmlSelectorReason(selector)
            assertNotNull("must refuse: $selector", reason)
            assertTrue(
                "the refusal must say why, not just that it failed: $reason",
                reason!!.contains("backtracking regular expression"),
            )
        }
    }

    @Test
    fun theCheckIsCaseInsensitiveSoCasingCannotSlipPast() {
        assertNotNull(StructuredDataReader.unsupportedHtmlSelectorReason("p:MATCHES($catastrophic)"))
        assertNotNull(StructuredDataReader.unsupportedHtmlSelectorReason("p:MatchesOwn($catastrophic)"))
    }

    @Test
    fun ordinaryCssSelectorsStillWork() {
        val allowed = listOf(
            "div.item > p",
            "a[href]",
            "a[href^=https]",
            "a[href$=.pdf]",
            "a[href*=example]",
            "li:nth-child(2)",
            "p:contains(hello)",
            "div ~ p",
        )

        allowed.forEach { selector ->
            assertNull("must not refuse ordinary CSS: $selector", StructuredDataReader.unsupportedHtmlSelectorReason(selector))
        }
    }

    @Test
    fun aSiblingCombinatorIsNotMistakenForTheAttributeRegexOperator() {
        // `~` outside an attribute selector is the general sibling combinator and is harmless.
        assertNull(StructuredDataReader.unsupportedHtmlSelectorReason("h2 ~ p"))
        assertNull(StructuredDataReader.unsupportedHtmlSelectorReason("h2~p"))
    }

    @Test
    fun readRefusesARegexSelectorEvenWhenThePatternIsHarmless() {
        // The deterministic half of the guard's proof. `:matches(first)` is a perfectly cheap
        // pattern, so without the refusal in readHtml jsoup answers quickly with a real (here
        // single-element) result. That makes this assertion fail fast and unambiguously if the
        // guard is removed, where the catastrophic case below would instead hang and prove
        // nothing. The two tests together cover "the guard exists" and "the hazard is real".
        val body = "<div><p>first</p><p>second</p></div>"

        assertNull(StructuredDataReader.read("html", body, "p:matches(first)"))
        assertNull(StructuredDataReader.read("html", body, "p[class~=any]"))
        // ...while the same document reads fine through a selector that carries no regex.
        assertEquals(listOf("first", "second"), StructuredDataReader.read("html", body, "p")?.values)
    }

    @Test
    fun readFailsClosedRatherThanHandingThePatternToJsoup() {
        // The trailing 'b' is the whole point. `(a+)+$` against a run of only 'a' matches on the
        // first greedy pass and costs nothing; it is the character that cannot match which forces
        // the exponential backtrack. Thirty 'a's is about 2^30 steps if this ever reaches jsoup,
        // which is tens of seconds, so the elapsed-time assertion is what proves the guard is
        // load-bearing rather than decorative.
        val body = "<p>" + "a".repeat(30) + "b</p>"
        var result: StructuredDataReader.ReadResult? = null
        val elapsed = measureTimeMillis {
            result = StructuredDataReader.read("html", body, "p:matches($catastrophic)")
        }

        assertNull("a refused selector must fail closed", result)
        assertTrue("refusal must be immediate, took ${elapsed}ms", elapsed < 2_000)
    }

    @Test
    fun anEquivalentSafeSelectorStillReadsTheSameDocument() {
        val body = "<div><p class='x'>first</p><p class='x'>second</p></div>"

        val result = StructuredDataReader.read("html", body, "p.x")

        assertEquals(listOf("first", "second"), result?.values)
    }
}
