package com.opentasker.core.transfer

import com.opentasker.core.data.TextOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A regex condition that keeps its spaces, or holds a `||`, is written inside a `(?:...)` group,
 * four characters longer than typed. The note about the length cap has to count those, or a
 * pattern just under the cap imports with no note and then never matches.
 */
class TaskerRegexWrapLengthTest {

    private fun importCondition(rhs: String) = TaskerXmlImporter.parse(
        rawXml = """
            <TaskerData>
                <Task sr="task1">
                    <id>1</id><nme>Regex</nme>
                    <Action sr="act0">
                        <code>37</code>
                        <ConditionList sr="if">
                            <Condition sr="c0"><lhs>%pa_url</lhs><op>4</op><rhs>$rhs</rhs></Condition>
                        </ConditionList>
                    </Action>
                </Task>
            </TaskerData>
        """.trimIndent(),
        appVersion = "test",
        importedAtEpochMs = 123L,
    )

    private fun TaskerXmlImportReport.lengthNote() = lossyWarnings.any { it.contains("longer than ${TextOps.MAX_PATTERN_CHARS}") }

    @Test
    fun aGroupThatTakesThePatternPastTheCapGetsTheNote() {
        val spaced = importCondition(" " + "a".repeat(253))
        assertEquals("%pa_url ~R (?: ${"a".repeat(253)})", spaced.bundle.tasks.single().actions.single().condition)
        assertTrue(spaced.lossyWarnings.toString(), spaced.lengthNote())

        val alternated = importCondition("a".repeat(250) + "||b")
        assertTrue(alternated.lossyWarnings.toString(), alternated.lengthNote())
    }

    @Test
    fun aGroupThatStillFitsGetsNoNote() {
        val spaced = importCondition(" " + "a".repeat(251))
        assertEquals(TextOps.MAX_PATTERN_CHARS, spaced.bundle.tasks.single().actions.single().condition!!.substringAfter("~R ").length)
        assertTrue(spaced.lossyWarnings.toString(), !spaced.lengthNote())
    }
}
