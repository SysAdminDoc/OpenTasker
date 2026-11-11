package com.opentasker.core.transfer

import com.opentasker.core.engine.VariableStore
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tasker joins the conditions of one "If" or "Run only if" with `<bool0>`, `<bool1>` and on. The
 * expected truth values come from [taskerTruth], a model of how Tasker 6.7.8 walks the list
 * (decompiled `u1`), not from the importer: it splits at the loosest joiner present, with Xor
 * loosest, then Or, then And, and every `2` form tighter than all three, and stops reading at the
 * first joiner that isn't there.
 */
class TaskerConditionJoinersTest {
    /** The layout real exports use: every `<boolN>` first, then the conditions. */
    private fun conditionList(joiners: List<String?>, conditionCount: Int = joiners.size + 1, op: String = "0"): String = buildString {
        appendLine("""<ConditionList sr="if">""")
        joiners.forEachIndexed { index, joiner -> joiner?.let { appendLine("<bool$index>$it</bool$index>") } }
        repeat(conditionCount) { index ->
            appendLine("""<Condition sr="c$index" ve="3"><lhs>%v$index</lhs><op>$op</op><rhs>1</rhs></Condition>""")
        }
        append("</ConditionList>")
    }

    private fun importGuard(conditionListXml: String): Pair<String?, List<String>> {
        val report = TaskerXmlImporter.parse(
            rawXml = """
                <TaskerData>
                    <Task sr="task1">
                        <id>1</id><nme>Joined</nme>
                        <Action sr="act0" ve="7">
                            <code>547</code>
                            <Str sr="arg0" ve="3">%OUT</Str>
                            <Str sr="arg1" ve="3">yes</Str>
                            $conditionListXml
                        </Action>
                    </Task>
                </TaskerData>
            """.trimIndent(),
            appVersion = "test",
            importedAtEpochMs = 0L,
        )
        return report.bundle.tasks.single().actions.single().condition to report.lossyWarnings
    }

    private fun holds(guard: String, values: List<Boolean>): Boolean {
        val variables = VariableStore()
        values.forEachIndexed { index, value -> variables.set("v$index", if (value) "1" else "0") }
        return variables.evaluateCondition(guard)
    }

    private fun assignments(count: Int): List<List<Boolean>> =
        (0 until (1 shl count)).map { bits -> List(count) { index -> bits and (1 shl index) != 0 } }

    private fun assertMatchesTasker(joiners: List<String>) {
        val (guard, warnings) = importGuard(conditionList(joiners))
        requireNotNull(guard) { "$joiners dropped: $warnings" }
        assertTrue(warnings.toString(), warnings.none { "Run only if" in it })
        for (values in assignments(joiners.size + 1)) {
            assertEquals("$joiners $values -> $guard", taskerTruth(values, joiners), holds(guard, values))
        }
    }

    @Test
    fun andAndOrJoinTheirConditions() {
        assertEquals("%v0 == 1 && %v1 == 1", importGuard(conditionList(listOf("And"))).first)
        assertEquals("%v0 == 1 || %v1 == 1", importGuard(conditionList(listOf("Or"))).first)
        assertMatchesTasker(listOf("And"))
        assertMatchesTasker(listOf("Or"))
        assertMatchesTasker(listOf("And2"))
        assertMatchesTasker(listOf("Or2"))
    }

    @Test
    fun aTwoFormBindsTighterThanThePlainOnes() {
        // (v0 || v1) && v2: true for v0 false, v1 and v2 true, and false for v0 true, v1 and v2 false,
        // where reading left to right as v0 || v1 && v2 would say true.
        val (guard, _) = importGuard(conditionList(listOf("Or2", "And")))
        requireNotNull(guard)
        assertTrue(guard, holds(guard, listOf(false, true, true)))
        assertFalse(guard, holds(guard, listOf(true, false, false)))
    }

    @Test
    fun everyMixSeenInRealExportsMatchesTasker() {
        listOf(
            listOf("And", "And"),
            listOf("And", "Or2", "And"),
            listOf("And", "And", "Or2"),
            listOf("Or2", "And"),
            listOf("Or2", "And", "Or"),
            listOf("And", "And", "And"),
            listOf("And", "Or2"),
            listOf("Or", "And", "And"),
            listOf("Or", "Or", "Or"),
            listOf("Or", "Or"),
            listOf("Or", "And2", "Or2", "And"),
            listOf("And2", "Or", "Or2", "And2", "And"),
        ).forEach(::assertMatchesTasker)
    }

    @Test
    fun conditionsPastAMissingJoinerAreLeftOutWithANote() {
        val (guard, warnings) = importGuard(conditionList(listOf("And"), conditionCount = 3))

        assertEquals("%v0 == 1 && %v1 == 1", guard)
        assertTrue(warnings.toString(), warnings.any { "reduced to its first 2 conditions" in it })
    }

    @Test
    fun conditionsPastAGapInTheNumberingAreLeftOutWithANote() {
        val (guard, warnings) = importGuard(
            """
                <ConditionList sr="if">
                <bool0>And</bool0>
                <bool1>And</bool1>
                <bool2>And</bool2>
                <Condition sr="c0" ve="3"><lhs>%v0</lhs><op>0</op><rhs>1</rhs></Condition>
                <Condition sr="c1" ve="3"><lhs>%v1</lhs><op>0</op><rhs>1</rhs></Condition>
                <Condition sr="c3" ve="3"><lhs>%v3</lhs><op>0</op><rhs>1</rhs></Condition>
                </ConditionList>
            """.trimIndent(),
        )

        assertEquals("%v0 == 1 && %v1 == 1", guard)
        assertTrue(warnings.toString(), warnings.any { "reduced to its first 2 conditions" in it })

        val noFirst = importGuard(
            """
                <ConditionList sr="if">
                <bool0>And</bool0>
                <Condition sr="c1" ve="3"><lhs>%v1</lhs><op>0</op><rhs>1</rhs></Condition>
                <Condition sr="c2" ve="3"><lhs>%v2</lhs><op>0</op><rhs>1</rhs></Condition>
                </ConditionList>
            """.trimIndent(),
        )
        assertNull(noFirst.first)
        assertTrue(noFirst.second.toString(), noFirst.second.any { "dropped" in it && "c0" in it })
    }

    @Test
    fun xorAndUnknownJoinersDropTheGuardWithANote() {
        for (joiner in listOf("Xor", "Xor2", "Nand")) {
            val (guard, warnings) = importGuard(conditionList(listOf("And", joiner)))
            assertNull(joiner, guard)
            assertTrue(warnings.toString(), warnings.any { joiner in it && "dropped" in it })
        }
    }

    @Test
    fun aConditionThatCantBeJoinedDropsTheGuardWithANote() {
        val unsupported = importGuard(conditionList(listOf("Or"), op = "99"))
        assertNull(unsupported.first)
        assertTrue(unsupported.second.toString(), unsupported.second.any { "op 99" in it && "dropped" in it })

        // Each of these would change where the joined guard splits, or what it groups.
        for (rhs in listOf("a||b", "x &amp;&amp; y", "(x", ":)", "{{ x }}")) {
            val split = importGuard(
                """
                    <ConditionList sr="if">
                    <Condition sr="c0" ve="3"><lhs>%v0</lhs><op>2</op><rhs>$rhs</rhs></Condition>
                    <bool0>And</bool0>
                    <Condition sr="c1" ve="3"><lhs>%v1</lhs><op>0</op><rhs>1</rhs></Condition>
                    </ConditionList>
                """.trimIndent(),
            )
            assertNull(rhs, split.first)
            assertTrue(split.second.toString(), split.second.any { "dropped" in it })
        }
    }

    @Test
    fun conditionsComeInTheirTaskerOrderWhateverTheFileOrder() {
        val (guard, _) = importGuard(
            """
                <ConditionList sr="if">
                <bool0>And</bool0>
                <Condition sr="c1" ve="3"><lhs>%v1</lhs><op>0</op><rhs>1</rhs></Condition>
                <Condition sr="c0" ve="3"><lhs>%v0</lhs><op>0</op><rhs>1</rhs></Condition>
                </ConditionList>
            """.trimIndent(),
        )

        assertEquals("%v0 == 1 && %v1 == 1", guard)
    }

    @Test
    fun aLoneConditionWithAJoinerAfterItIsJustThatCondition() {
        val (guard, warnings) = importGuard(conditionList(listOf("And"), conditionCount = 1))

        assertEquals("%v0 == 1", guard)
        assertTrue(warnings.toString(), warnings.none { "Run only if" in it })
    }

    private fun exportGuard(guard: String): TaskerXmlExportReport =
        TaskerXmlExporter.export(
            emptyList(),
            listOf(Task(id = 1, name = "Joined", actions = listOf(ActionSpec(type = "var.set", args = mapOf("name" to "%OUT", "value" to "yes"), condition = guard)))),
        )

    private fun exportedJoiners(xml: String): List<String> =
        Regex("<bool(\\d+)>(\\w+)</bool\\1>").findAll(xml).map { it.groupValues[2] }.toList()

    @Test
    fun aJoinedGuardGoesOutWithItsJoinersAndComesBackTheSame() {
        listOf(
            "%v0 == 1 && %v1 == 1" to listOf("And"),
            "%v0 == 1 || %v1 == 1 && %v2 == 1" to listOf("Or", "And"),
            "(%v0 == 1 || %v1 == 1) && %v2 == 1" to listOf("Or2", "And"),
            "%v0 == 1 && (%v1 == 1 || (%v2 == 1 && %v3 == 1))" to listOf("And", "Or2", "And2"),
            "%v0 == 1 || (%v1 == 1 || %v2 == 1)" to listOf("Or", "Or"),
            "%v0 == 1 || %v1 == 1 || %v2 == 1" to listOf("Or", "Or"),
            "%v0 == 1 && (%v1 == 1 && (%v2 == 1 && %v3 == 1))" to listOf("And", "And", "And"),
            "(%v0 == 1 && %v1 == 1) && (%v2 == 1 && (%v3 == 1 || %v4 == 1))" to listOf("And", "And", "And", "Or2"),
        ).forEach { (guard, joiners) ->
            val report = exportGuard(guard)
            assertEquals(guard, joiners, exportedJoiners(report.xml))
            assertTrue(report.warnings.toString(), report.warnings.none { "run-only-if" in it })

            val back = TaskerXmlImporter.parse(report.xml, appVersion = "test", importedAtEpochMs = 0L)
                .bundle.tasks.single().actions.single().condition
            requireNotNull(back) { guard }
            for (values in assignments(joiners.size + 1)) {
                assertEquals("$guard $values -> $back", holds(guard, values), holds(back, values))
            }
            assertEquals(guard, joiners, exportedJoiners(exportGuard(back).xml))
        }
    }

    @Test
    fun anOperatorInsideBracketsStaysPartOfItsSide() {
        val guard = "%x ~ (a == b) && %y == 1"
        val report = exportGuard(guard)

        assertTrue(report.xml, "<lhs>%x</lhs><op>2</op><rhs>(a == b)</rhs>" in report.xml.replace(Regex(">\\s+<"), "><"))
        val back = TaskerXmlImporter.parse(report.xml, appVersion = "test", importedAtEpochMs = 0L)
            .bundle.tasks.single().actions.single().condition
        assertEquals(guard, back)
    }

    @Test
    fun aTaskerPatternHoldingAnotherOperatorStillExportsAsOneCondition() {
        val guard = "%html ~ *<b>*"
        val report = exportGuard(guard)

        assertTrue(report.xml, "<lhs>%html</lhs><op>2</op><rhs>*&lt;b&gt;*</rhs>" in report.xml.replace(Regex(">\\s+<"), "><"))
        val back = TaskerXmlImporter.parse(report.xml, appVersion = "test", importedAtEpochMs = 0L)
            .bundle.tasks.single().actions.single().condition
        assertEquals(guard, back)
    }

    @Test
    fun aGuardNestedDeeperThanTaskerCanGroupIsStillDropped() {
        val report = exportGuard("%v0 == 1 && (%v1 == 1 || (%v2 == 1 && (%v3 == 1 || %v4 == 1)))")

        assertFalse(report.xml, "<ConditionList" in report.xml)
        assertTrue(report.warnings.toString(), report.warnings.any { "no Tasker ConditionList equivalent" in it })
    }

    private companion object {
        val TASKER_PRECEDENCE = listOf("Xor", "Or", "And", "Xor2", "Or2", "And2")

        fun taskerTruth(values: List<Boolean>, joiners: List<String>, level: Int = 0): Boolean {
            if (values.size == 1) return values.single()
            val op = TASKER_PRECEDENCE[level]
            if (op !in joiners) return taskerTruth(values, joiners, level + 1)
            val pieces = mutableListOf<Boolean>()
            var start = 0
            joiners.forEachIndexed { index, joiner ->
                if (joiner == op) {
                    pieces += taskerTruth(values.subList(start, index + 1), joiners.subList(start, index), level + 1)
                    start = index + 1
                }
            }
            pieces += taskerTruth(values.subList(start, values.size), joiners.subList(start, joiners.size), level + 1)
            return when (op.removeSuffix("2")) {
                "And" -> pieces.all { it }
                "Or" -> pieces.any { it }
                else -> pieces.count { it } == 1
            }
        }
    }
}
