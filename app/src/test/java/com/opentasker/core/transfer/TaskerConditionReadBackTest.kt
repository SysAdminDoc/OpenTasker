package com.opentasker.core.transfer

import com.opentasker.core.engine.VariableStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Tasker condition holds its right side as typed (decompiled 6.7.8 `vi` and `t1`), so a `||`, a
 * second comparison sign or spaces at its ends are part of it. The importer writes a condition only
 * when the condition grammar reads it back as the same check, puts a regex in a `(?:...)` group when
 * that keeps it whole, and writes a blank right side the way Tasker settles one (`Expr.b`, `Expr.k`).
 */
class TaskerConditionReadBackTest {
    private fun importGuard(vararg conditions: Pair<String, String>, joiner: String = "And"): Pair<String?, List<String>> {
        val conditionXml = conditions.mapIndexed { index, (op, rhs) ->
            """<Condition sr="c$index" ve="3"><lhs>%x</lhs><op>$op</op><rhs>$rhs</rhs></Condition>"""
        }.joinToString("")
        val joinerXml = (0 until conditions.size - 1).joinToString("") { "<bool$it>$joiner</bool$it>" }
        val report = TaskerXmlImporter.parse(
            rawXml = """
                <TaskerData>
                    <Task sr="task1">
                        <id>1</id><nme>Guarded</nme>
                        <Action sr="act0" ve="7">
                            <code>547</code>
                            <Str sr="arg0" ve="3">%OUT</Str>
                            <Str sr="arg1" ve="3">yes</Str>
                            <ConditionList sr="if">$joinerXml$conditionXml</ConditionList>
                        </Action>
                    </Task>
                </TaskerData>
            """.trimIndent(),
            appVersion = "test",
            importedAtEpochMs = 0L,
        )
        return report.bundle.tasks.single().actions.single().condition to report.lossyWarnings
    }

    private fun holds(guard: String, x: String?): Boolean =
        VariableStore().apply { if (x != null) set("x", x) }.evaluateCondition(guard)

    @Test
    fun aRegexHoldingAComparisonSignStaysOneRegex() {
        val (guard, warnings) = importGuard("4" to "a&lt;b")

        assertEquals("%x ~R a<b", guard)
        assertTrue(warnings.toString(), warnings.none { "Run only if" in it })
        assertTrue(holds(guard!!, "1a<b2"))
        assertFalse(holds(guard, "ab"))
    }

    @Test
    fun aRegexHoldingAJoinerStaysOneRegex() {
        val (guard, _) = importGuard("5" to "x&amp;&amp;y")

        assertEquals("%x !~R (?:x&&y)", guard)
        assertFalse(holds(guard!!, "ax&&yb"))
        assertTrue(holds(guard, "x"))
    }

    @Test
    fun aRegexThatReadsBackAsItIsKeepsNoGroup() {
        val (guard, warnings) = importGuard("4" to "^a|b\$")

        assertEquals("%x ~R ^a|b\$", guard)
        assertTrue(warnings.toString(), warnings.none { "Run only if" in it })
    }

    @Test
    fun aPatternOrTextTheGrammarWouldSplitIsDroppedWithANote() {
        listOf("2" to "yes||no", "0" to "a == b", "3" to "a &amp;&amp; b", "2" to "{{ name }}").forEach { (op, rhs) ->
            val (guard, warnings) = importGuard(op to rhs)

            assertNull("$op $rhs", guard)
            assertTrue(warnings.toString(), warnings.any { "(op $op) was dropped" in it && "second comparison sign" in it })
        }
    }

    @Test
    fun anEmptyRightSideHoldsTheWayTaskerSettlesIt() {
        val always = setOf("1", "3", "5")
        listOf("0", "1", "2", "3", "4", "5", "6", "7").forEach { op ->
            val (guard, warnings) = importGuard(op to "")

            assertTrue(warnings.toString(), warnings.none { "Run only if" in it })
            listOf(null, "", "on", "5").forEach { x ->
                assertEquals("op $op x=$x -> $guard", op in always, holds(guard!!, x))
            }
        }
    }

    @Test
    fun spacesAroundAPatternAreTrimmedWithANote() {
        val (guard, warnings) = importGuard("2" to " home ")

        assertEquals("%x ~ home", guard)
        assertTrue(warnings.toString(), warnings.any { "(op 2) has spaces at the start or end of its right side" in it })
    }

    @Test
    fun onlySpacesOnTheRightSettleLikeAnEmptySideWithANote() {
        listOf("0" to false, "1" to true, "2" to false, "3" to true).forEach { (op, expected) ->
            val (guard, warnings) = importGuard(op to "  ")

            assertEquals("op $op", expected, holds(guard!!, "home"))
            assertTrue(warnings.toString(), warnings.any { "(op $op) has spaces" in it })
        }
    }

    @Test
    fun aRegexKeepsItsSpacesInAGroup() {
        val (guard, warnings) = importGuard("4" to "foo ")

        assertEquals("%x ~R (?:foo )", guard)
        assertTrue(warnings.toString(), warnings.none { "has spaces" in it })
        assertTrue(holds(guard!!, "a foo b"))
        assertFalse(holds(guard, "foo"))
    }

    @Test
    fun aJoinedListKeepsABracketedPatternHoldingAJoiner() {
        val (guard, warnings) = importGuard("2" to "(a||b)", "4" to "a&lt;b", joiner = "Or")

        assertEquals("%x ~ (a||b) || %x ~R a<b", guard)
        assertTrue(warnings.toString(), warnings.none { "Run only if" in it })
        assertTrue(holds(guard!!, "(A||B)"))
        assertTrue(holds(guard, "za<b"))
        assertFalse(holds(guard, "a"))
    }

    @Test
    fun aJoinedListWithASideTheGrammarWouldSplitIsDropped() {
        val (guard, warnings) = importGuard("2" to "yes||no", "0" to "1", joiner = "Or")

        assertNull(guard)
        assertTrue(warnings.toString(), warnings.any { "joining 2 conditions was dropped" in it && "second comparison sign" in it })
    }

    @Test
    fun aJoinedListWritesABlankSideAsTheCheckTaskerSettlesItTo() {
        val (guard, _) = importGuard("3" to "", "0" to "on")

        assertTrue(holds(guard!!, "on"))
        assertFalse(holds(guard, "off"))
    }
}
