package com.opentasker.core.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tasker renumbered its condition comparisons twice, and a Condition's `ve` attribute says which
 * numbering its `<op>` uses. Tasker 6.x writes ve="3". Older exports carry ve="2", where op 4 is a
 * numeric equals and op 10 is Matches Regex. Expected values come from Tasker's own loader
 * (decompiled 6.7.8, t1), which maps an older code up before it reads it.
 */
class TaskerConditionNumberingImportTest {

    private fun importCondition(op: String, rhs: String, ve: String?) = TaskerXmlImporter.parse(
        rawXml = """
            <TaskerData>
                <Task sr="task1">
                    <id>1</id><nme>Numbering</nme>
                    <Action sr="act0">
                        <code>37</code>
                        <ConditionList sr="if">
                            <Condition sr="c0"${ve?.let { " ve=\"$it\"" }.orEmpty()}><lhs>%pa_url</lhs><op>$op</op><rhs>$rhs</rhs></Condition>
                        </ConditionList>
                    </Action>
                </Task>
            </TaskerData>
        """.trimIndent(),
        appVersion = "test",
        importedAtEpochMs = 123L,
    )

    private fun importedCondition(op: String, rhs: String, ve: String?): String? =
        importCondition(op, rhs, ve).bundle.tasks.single().actions.single().condition

    @Test
    fun versionTwoCodesImportAsTheComparisonTheyWere() {
        assertEquals("%pa_url ~ home*", importedCondition("0", "home*", "2"))
        assertEquals("%pa_url !~ home*", importedCondition("1", "home*", "2"))
        assertEquals("%pa_url < 5", importedCondition("2", "5", "2"))
        assertEquals("%pa_url > 5", importedCondition("3", "5", "2"))
        assertEquals("%pa_url == 1", importedCondition("4", "1", "2"))
        assertEquals("%pa_url != 1", importedCondition("5", "1", "2"))
        assertEquals("%pa_url is_set", importedCondition("8", "", "2"))
        assertEquals("%pa_url not_set", importedCondition("9", "", "2"))
        assertEquals("%pa_url ~R [a-z]", importedCondition("10", "[a-z]", "2"))
        assertEquals("%pa_url !~R [a-z]", importedCondition("11", "[a-z]", "2"))
    }

    @Test
    fun versionTwoEvenAndOddImportAsParityChecks() {
        // ve="2" ops 6 and 7 are Even and Odd, today's 10 and 11.
        val report = importCondition("6", "", "2")
        assertEquals("%pa_url is_even", report.bundle.tasks.single().actions.single().condition)
        assertTrue(report.lossyWarnings.toString(), report.lossyWarnings.none { it.contains("unsupported Tasker comparison") })
        assertEquals("%pa_url is_odd", importedCondition("7", "", "2"))
    }

    @Test
    fun versionOneCodesAreShiftedDownFirst() {
        assertEquals("%pa_url < 5", importedCondition("3", "5", "1"))
        assertEquals("%pa_url ~R [a-z]", importedCondition("11", "[a-z]", "1"))
    }

    @Test
    fun currentCodesAndAMissingVersionReadAsToday() {
        assertEquals("%pa_url ~R [a-z]", importedCondition("4", "[a-z]", "3"))
        assertEquals("%pa_url ~R [a-z]", importedCondition("4", "[a-z]", null))
        assertEquals("%pa_url < 5", importedCondition("6", "5", null))
    }
}
