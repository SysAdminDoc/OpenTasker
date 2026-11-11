package com.opentasker.core.engine

import com.opentasker.core.expressions.TemplateExpansionTrace
import com.opentasker.core.expressions.TemplateValueSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A step that sets a variable to nothing keeps that change on its own row of the run log. */
class RunLogBlankVariableTest {
    @Test
    fun aVariableSetToNothingStaysWithItsStep() {
        val plugin = ActionExecutionTrace(
            index = 0,
            actionType = "plugin.locale.fire",
            label = "Run script",
            durationMs = 420,
            status = ActionTraceStatus.SUCCESS,
            message = "Plugin com.termux/.FireReceiver finished.",
            variableChanges = listOf(
                ActionVariableChange(VariableChangeScope.TASK, "stdout", "hello", added = true),
                ActionVariableChange(VariableChangeScope.TASK, "stderr", "", added = true),
                ActionVariableChange(VariableChangeScope.TASK, "result", "0", added = true),
            ),
        )
        val next = ActionExecutionTrace(
            index = 1,
            actionType = "var.set",
            label = "Clear",
            durationMs = 1,
            status = ActionTraceStatus.SUCCESS,
            message = "ok",
            variableChanges = listOf(ActionVariableChange(VariableChangeScope.GLOBAL, "Last", "", added = false)),
        )

        val diagnostics = listOf(plugin, next).toRunLogMessage().toRunLogDiagnostics()

        assertEquals(
            listOf(
                listOf(Triple("stdout", "hello", true), Triple("stderr", "", true), Triple("result", "0", true)),
                listOf(Triple("Last", "", false)),
            ),
            diagnostics.traces.map { step -> step.variableChanges.map { Triple(it.name, it.value, it.added) } },
        )
        assertTrue(diagnostics.detailLines.toString(), diagnostics.detailLines.none { it.startsWith("Var:") })
    }

    @Test
    fun aTemplateThatExpandedToNothingStaysWithItsStep() {
        val step = ActionExecutionTrace(
            index = 0,
            actionType = "notify.show",
            label = "Show errors",
            durationMs = 3,
            status = ActionTraceStatus.SUCCESS,
            message = "ok",
            argumentExpansions = listOf(
                ActionArgumentExpansionTrace(
                    argName = "text",
                    rawValue = "%stderr",
                    expandedValue = "",
                    expressions = listOf(TemplateExpansionTrace("%stderr", "%stderr", "", TemplateValueSource.TASK, "stderr")),
                    warnings = emptyList(),
                ),
            ),
        )

        val diagnostics = listOf(step).toRunLogMessage().toRunLogDiagnostics()

        assertEquals(
            listOf(RunLogTemplateDiagnostic(argName = "text", source = "task", expression = "%stderr", value = "")),
            diagnostics.traces.single().templateExpressions,
        )
        assertTrue(diagnostics.detailLines.toString(), diagnostics.detailLines.none { it.startsWith("Template:") })
    }

    @Test
    fun aVariableLineMissingAColumnIsntReadShifted() {
        val step = listOf(
            ActionExecutionTrace(
                index = 0,
                actionType = "var.set",
                label = "Set",
                durationMs = 1,
                status = ActionTraceStatus.SUCCESS,
                message = "ok",
            ),
        ).toRunLogMessage()

        for (broken in listOf("Var:\ttask\tupdated\thello", "Var:\tstderr\tadded\tx")) {
            val diagnostics = "$step\n$broken".toRunLogDiagnostics()

            assertEquals(broken, emptyList<RunLogVariableChange>(), diagnostics.traces.single().variableChanges)
        }
    }
}
