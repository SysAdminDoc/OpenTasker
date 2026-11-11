package com.opentasker.core.engine

import com.opentasker.core.contexts.ContextEvent
import com.opentasker.core.model.ContextBooleanOperator
import com.opentasker.core.model.ContextExpressionNode
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntheticTriggerSimulationTest {
    @Test
    fun templatesCoverEveryContextFamilyAndSatisfyValidPredicates() {
        val specs = listOf(
            ContextSpec(ContextType.APPLICATION, mapOf("package" to "com.example.target")),
            ContextSpec(ContextType.TIME, mapOf("start" to "09:00", "end" to "17:00")),
            ContextSpec(ContextType.DAY, mapOf("days" to "MON,WED")),
            ContextSpec(
                ContextType.LOCATION,
                mapOf(
                    "latitude" to "40.7580",
                    "longitude" to "-73.9855",
                    "radiusMeters" to "150",
                    "maxAccuracyMeters" to "25",
                    "dwellSeconds" to "5",
                ),
            ),
            ContextSpec(ContextType.STATE, mapOf("predicate" to "battery_level>=80")),
            ContextSpec(ContextType.EVENT, mapOf("event" to "notification", "title" to "Build")),
            ContextSpec(ContextType.PLUGIN, mapOf("package" to "com.example.plugin", "bundleJson" to "{}")),
        )

        val simulation = SyntheticTriggerSimulator.simulate(
            profile = Profile(id = 7L, name = "Every family", enterTaskId = 11L, contexts = specs),
            nowMs = 1_000_000L,
        )

        assertEquals(ContextType.entries.toSet(), simulation.contexts.map { it.spec.type }.toSet())
        assertTrue(simulation.contexts.all { it.template.issue == null })
        assertTrue(simulation.contexts.all { it.effectiveMatched })
        assertTrue(simulation.profileMatched)
        assertTrue(simulation.sideEffectsSuppressed)
    }

    @Test
    fun aTimeWindowBetweenGlobalsSimulatesWithStandInValues() {
        val simulation = SyntheticTriggerSimulator.simulate(
            Profile(
                id = 9L,
                name = "Daylight",
                enterTaskId = 13L,
                contexts = listOf(ContextSpec(ContextType.TIME, mapOf("start" to "%Sunrise", "end" to "%Sunset"))),
            ),
            nowMs = 1_000_000L,
        )
        val context = simulation.contexts.single()

        assertEquals(null, context.template.issue)
        assertEquals("12:00", context.template.event.metadata["time"])
        assertTrue(context.template.description, context.template.description.contains("%Sunrise as 06:00"))
        assertTrue(context.effectiveMatched)
    }

    @Test
    fun aPinnedEventWithNoTimeForAGlobalKeepsAnInvertedWindowOff() {
        val profile = Profile(
            id = 10L,
            name = "Night",
            enterTaskId = 14L,
            contexts = listOf(ContextSpec(ContextType.TIME, mapOf("start" to "%Sunrise", "end" to "%Sunset"), invert = true)),
        )

        val context = SyntheticTriggerSimulator.simulate(
            profile,
            nowMs = 1_000_000L,
            pinnedEvents = mapOf(0 to ContextEvent("time", true, mapOf("time" to "22:00"))),
        ).contexts.single()

        assertFalse(context.effectiveMatched)
        assertTrue(context.explanation, context.explanation.contains("no value"))
    }

    @Test
    fun malformedTemplatesAreVisibleAsBlockedPredicates() {
        val simulation = SyntheticTriggerSimulator.simulate(
            Profile(
                id = 8L,
                name = "Malformed",
                enterTaskId = 12L,
                contexts = listOf(
                    ContextSpec(ContextType.APPLICATION),
                    ContextSpec(ContextType.EVENT),
                    ContextSpec(ContextType.STATE, mapOf("predicate" to "battery_level>=unknown")),
                ),
            ),
        )

        assertFalse(simulation.profileMatched)
        assertTrue(simulation.contexts.all { it.status == SyntheticContextStatus.BLOCKED })
        assertTrue(simulation.contexts.all { it.explanation.isNotBlank() })
    }

    @Test
    fun pinnedEventsUseTheProductionEvaluatorAndCanBlockAValidTemplate() {
        val profile = Profile(
            id = 9L,
            name = "Pinned",
            enterTaskId = 13L,
            contexts = listOf(
                ContextSpec(ContextType.APPLICATION, mapOf("package" to "com.example.target")),
            ),
        )

        val simulation = SyntheticTriggerSimulator.simulate(
            profile = profile,
            pinnedEvents = mapOf(
                0 to ContextEvent(
                    type = "app",
                    matched = true,
                    metadata = mapOf("foreground" to "com.example.other"),
                ),
            ),
        )

        assertEquals(1, simulation.pinnedContextCount)
        assertFalse(simulation.contexts.single().rawMatched)
        assertFalse(simulation.profileMatched)
        assertTrue(simulation.contexts.single().displayMetadata["foreground"] == "com.example.other")
    }

    @Test
    fun nestedContextLogicHonorsPinnedOrBranches() {
        val contexts = listOf(
            ContextSpec(ContextType.APPLICATION, mapOf("package" to "com.example.first")),
            ContextSpec(ContextType.APPLICATION, mapOf("package" to "com.example.second")),
            ContextSpec(ContextType.STATE, mapOf("key" to "charging", "value" to "true")),
        )
        val expression = ContextExpressionNode.group(
            ContextBooleanOperator.AND,
            listOf(
                ContextExpressionNode.group(
                    ContextBooleanOperator.OR,
                    listOf(ContextExpressionNode.leaf(0), ContextExpressionNode.leaf(1)),
                ),
                ContextExpressionNode.leaf(2),
            ),
        )
        val profile = Profile(
            id = 10L,
            name = "OR profile",
            enterTaskId = 14L,
            contexts = contexts,
            contextExpression = expression,
        )
        val blockedFirstBranch = ContextEvent("app", true, mapOf("foreground" to "com.example.nope"))
        val matchingSecondBranch = ContextEvent("app", true, mapOf("foreground" to "com.example.second"))

        val simulation = SyntheticTriggerSimulator.simulate(
            profile = profile,
            pinnedEvents = mapOf(0 to blockedFirstBranch, 1 to matchingSecondBranch),
        )

        assertTrue(simulation.profileMatched)
        assertFalse(simulation.contexts[0].effectiveMatched)
        assertTrue(simulation.contexts[1].effectiveMatched)
        assertTrue(simulation.contexts[2].effectiveMatched)
    }

    @Test
    fun cooldownAndAdmissionGatesRemainSeparateFromPredicateMatching() {
        val profile = Profile(
            id = 11L,
            name = "Gated",
            enterTaskId = 15L,
            cooldownSec = 30,
            contexts = listOf(ContextSpec(ContextType.DAY, mapOf("days" to "MON,TUE,WED,THU,FRI,SAT,SUN"))),
        )

        val simulation = SyntheticTriggerSimulator.simulate(
            profile = profile,
            cooldown = SyntheticGateResult.block("Cooldown has 10 seconds remaining."),
            admission = SyntheticGateResult.pass("Admission available."),
        )

        assertTrue(simulation.profileMatched)
        assertFalse(simulation.wouldTrigger)
        assertFalse(simulation.cooldown.accepted)
        assertTrue(simulation.admission.accepted)
    }

    @Test
    fun simulationEnvelopeIsMarkedAndReplayRestoresProductionMode() {
        val task = com.opentasker.core.model.Task(id = 16L, name = "Diagnostic task")
        val production = ExecutionEnvelope.create(task, "Manual run", executionId = "simulation-production")
        val simulation = production.forSimulation(nowMs = 42L)
        val replay = simulation.forReplay(nowMs = 43L)

        assertEquals(ExecutionMode.PRODUCTION, production.mode)
        assertEquals(ExecutionMode.SIMULATION, simulation.mode)
        assertNotNull(simulation.executionId)
        assertEquals(ExecutionMode.PRODUCTION, replay.mode)
        assertEquals(simulation.executionId, replay.replayOf)
    }
}
