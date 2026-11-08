package com.opentasker.core.flow

import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.AutomationMode
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationFlowGraphTest {
    @Test
    fun buildCreatesProfileContextTaskAndActionChain() {
        val enterTask = Task(
            id = 10,
            name = "Arrive home",
            priority = 7,
            actions = listOf(
                ActionSpec(type = "notify.show", label = "Welcome", args = mapOf("title" to "Home")),
                ActionSpec(type = "flow.wait", args = mapOf("millis" to "5000")),
            ),
        )
        val profile = Profile(
            id = 1,
            name = "Home arrival",
            enabled = true,
            contexts = listOf(ContextSpec(ContextType.STATE, mapOf("wifiSsid" to "Home"))),
            enterTaskId = enterTask.id,
            automationMode = AutomationMode.RESTART,
        )

        val graph = AutomationFlowGraphBuilder.build(profile, listOf(enterTask))

        assertEquals("Home arrival", graph.title)
        assertEquals(1, graph.contextNodes.size)
        assertEquals(AutomationFlowTarget.Profile(1), graph.nodes.first { it.kind == AutomationFlowNodeKind.PROFILE }.target)
        assertEquals(AutomationFlowTarget.Context(1, 0), graph.contextNodes.single().target)
        assertEquals("Arrive home", graph.enterTaskNode?.title)
        assertEquals(AutomationFlowTarget.Task(10), graph.enterTaskNode?.target)
        assertEquals(listOf("Welcome", "Step 2: flow.wait"), graph.actionNodesFor("enter-task:10").map { it.title })
        assertEquals(
            listOf(
                AutomationFlowTarget.Action(10, 0),
                AutomationFlowTarget.Action(10, 1),
            ),
            graph.actionNodesFor("enter-task:10").map { it.target },
        )
        assertTrue(graph.edges.any { it.fromId == "profile:1:context:0" && it.toId == "profile:1" })
        assertTrue(graph.edges.any { it.fromId == "enter-task:10" && it.toId == "enter-task:10:action:0" })
        assertTrue(graph.warnings.isEmpty())
    }

    @Test
    fun detailLinesSeparateTheirPartsWithADot() {
        val enterTask = Task(
            id = 30,
            name = "Dim",
            priority = 5,
            actions = listOf(ActionSpec(type = "brightness.set", args = mapOf("level" to "48"), continueOnError = true)),
        )
        val profile = Profile(
            id = 3,
            name = "Low battery",
            enabled = true,
            contexts = listOf(ContextSpec(ContextType.STATE, mapOf("key" to "battery_level"), invert = true)),
            enterTaskId = enterTask.id,
            automationMode = AutomationMode.SINGLE,
        )

        val graph = AutomationFlowGraphBuilder.build(profile, listOf(enterTask))

        assertEquals("Enabled · Mode single · Cooldown 0s", graph.nodes.first { it.kind == AutomationFlowNodeKind.PROFILE }.detail)
        assertEquals("1 action · priority 5", graph.enterTaskNode?.detail)
        assertTrue(graph.contextNodes.single().detail.orEmpty().startsWith("Inverted · "))
        val actionDetail = graph.actionNodesFor("enter-task:30").single().detail.orEmpty()
        assertEquals("brightness.set", actionDetail.substringBefore(FLOW_DETAIL_SEPARATOR).substringBefore(" with "))
        assertTrue(actionDetail.endsWith(" · continues after error"))
        graph.nodes.forEach { node ->
            assertFalse("${node.id} detail '${node.detail}'", node.detail.orEmpty().contains(" - "))
        }
    }

    @Test
    fun subTaskActionsAreFlaggedStructurallyNotByDetailText() {
        val enterTask = Task(
            id = 20,
            name = "Runner",
            actions = listOf(
                ActionSpec(type = "task.run", args = mapOf("task" to "Cleanup")),
                ActionSpec(type = "notify.show", args = mapOf("title" to "Done")),
            ),
        )
        val profile = Profile(
            id = 3,
            name = "Chain",
            contexts = listOf(ContextSpec(ContextType.STATE, mapOf("wifiSsid" to "Home"))),
            enterTaskId = enterTask.id,
        )

        val actions = AutomationFlowGraphBuilder.build(profile, listOf(enterTask)).actionNodesFor("enter-task:20")

        assertTrue("task.run node must be flagged as a sub-task", actions[0].isSubTask)
        assertTrue("non task.run node must not be flagged", !actions[1].isSubTask)
    }

    @Test
    fun buildReportsMissingTasksAndEmptyContexts() {
        val profile = Profile(
            id = 2,
            name = "Broken profile",
            contexts = emptyList(),
            enterTaskId = 404,
            exitTaskId = 405,
        )

        val graph = AutomationFlowGraphBuilder.build(profile, emptyList())

        assertEquals(2, graph.nodes.count { it.kind == AutomationFlowNodeKind.MISSING })
        assertEquals(
            listOf(AutomationFlowTarget.Profile(2), AutomationFlowTarget.Profile(2)),
            graph.nodes.filter { it.kind == AutomationFlowNodeKind.MISSING }.map { it.target },
        )
        assertTrue(graph.warnings.contains("Profile has no contexts."))
        assertTrue(graph.warnings.contains("Enter task 404 is missing."))
        assertTrue(graph.warnings.contains("Exit task 405 is missing."))
    }

    @Test
    fun buildKeepsExitTaskLaneSeparateFromEnterTaskLane() {
        val enterTask = Task(id = 10, name = "Enable mode", actions = listOf(ActionSpec(type = "dnd.set")))
        val exitTask = Task(id = 11, name = "Restore mode", actions = listOf(ActionSpec(type = "dnd.set")))
        val profile = Profile(
            id = 3,
            name = "Meeting",
            contexts = listOf(ContextSpec(ContextType.EVENT, mapOf("event" to "calendar"))),
            enterTaskId = enterTask.id,
            exitTaskId = exitTask.id,
        )

        val graph = AutomationFlowGraphBuilder.build(profile, listOf(enterTask, exitTask))

        assertEquals("Enable mode", graph.enterTaskNode?.title)
        assertEquals("Restore mode", graph.exitTaskNode?.title)
        assertEquals(listOf("Step 1: dnd.set"), graph.actionNodesFor("enter-task:10").map { it.title })
        assertEquals(listOf("Step 1: dnd.set"), graph.actionNodesFor("exit-task:11").map { it.title })
    }

    @Test
    fun buildMarksConditionalActionsAndIncomingEdges() {
        val enterTask = Task(
            id = 20,
            name = "Battery guard",
            actions = listOf(
                ActionSpec(type = "notify.show", label = "Battery warning", condition = "%battery < 20"),
                ActionSpec(type = "flow.wait", args = mapOf("millis" to "500")),
            ),
        )
        val profile = Profile(
            id = 4,
            name = "Low battery",
            contexts = listOf(ContextSpec(ContextType.STATE, mapOf("key" to "battery"))),
            enterTaskId = enterTask.id,
        )

        val graph = AutomationFlowGraphBuilder.build(profile, listOf(enterTask))
        val actionNodes = graph.actionNodesFor("enter-task:20")

        assertEquals("%battery < 20", actionNodes.first().condition)
        assertEquals("if %battery < 20", graph.incomingEdgeLabel("enter-task:20:action:0"))
        assertEquals("then", graph.incomingEdgeLabel("enter-task:20:action:1"))
    }

    @Test
    fun accessibilitySummaryIncludesLaneCountsAndConditionalNodeState() {
        val enterTask = Task(
            id = 21,
            name = "Guarded task",
            actions = listOf(ActionSpec(type = "notify.show", condition = "%armed = true")),
        )
        val profile = Profile(
            id = 5,
            name = "Armed profile",
            contexts = listOf(ContextSpec(ContextType.STATE, mapOf("key" to "armed"))),
            enterTaskId = enterTask.id,
        )

        val graph = AutomationFlowGraphBuilder.build(profile, listOf(enterTask))
        val actionNode = graph.actionNodesFor("enter-task:21").single()

        assertEquals(
            "Armed profile: 1 context, 1 action, enter task Guarded task, exit task no exit task, no warnings.",
            graph.accessibilitySummary(),
        )
        assertEquals(
            "action. Step 1: notify.show. notify.show with the configured values. condition if %armed = true",
            actionNode.accessibilityLabel(),
        )
    }

    @Test
    fun changedNodeKeysMarkProfileContextTaskAndActionNodes() {
        val task = Task(id = 21, name = "Changed task", actions = listOf(ActionSpec(type = "notify.show")))
        val profile = Profile(
            id = 5,
            name = "Changed profile",
            contexts = listOf(ContextSpec(ContextType.STATE, mapOf("key" to "armed"))),
            enterTaskId = task.id,
        )

        val graph = AutomationFlowGraphBuilder.build(
            profile,
            listOf(task),
            changedNodeKeys = setOf("profile:5:context:0", "task:21:action:0"),
        )

        assertFalse(graph.nodes.first { it.kind == AutomationFlowNodeKind.PROFILE }.changed)
        assertTrue(graph.contextNodes.single().changed)
        assertFalse(graph.enterTaskNode?.changed == true)
        assertTrue(graph.actionNodesFor("enter-task:21").single().changed)
    }

    @Test
    fun complexRealWorldFixtureValidatesBranchesSubflowsAndRepairTarget() {
        val enterTask = Task(
            id = 100,
            name = "Prepare evening mode",
            actions = listOf(
                ActionSpec(
                    type = "dnd.set",
                    condition = "%calendar = meeting",
                    args = mapOf("state" to "enabled"),
                ),
                ActionSpec(type = "task.run", args = mapOf("task" to "Restore evening defaults")),
                ActionSpec(
                    type = "notify.show",
                    args = mapOf("title" to "Evening mode"),
                    continueOnError = true,
                ),
            ),
        )
        val childTask = Task(
            id = 101,
            name = "Restore evening defaults",
            actions = listOf(ActionSpec(type = "brightness.set", args = mapOf("value" to "20"))),
        )
        val profile = Profile(
            id = 42,
            name = "Evening meeting mode",
            enabled = true,
            contexts = listOf(
                ContextSpec(ContextType.EVENT, mapOf("event" to "calendar")),
                ContextSpec(ContextType.STATE, mapOf("key" to "battery"), invert = true),
            ),
            enterTaskId = enterTask.id,
            exitTaskId = 999,
        )

        val graph = AutomationFlowGraphBuilder.build(profile, listOf(enterTask, childTask))
        val actions = graph.actionNodesFor("enter-task:100")

        assertEquals(2, graph.contextNodes.size)
        assertEquals(
            listOf("if %calendar = meeting", "then", "then"),
            actions.map { graph.incomingEdgeLabel(it.id) },
        )
        assertTrue(actions[1].isSubTask)
        assertTrue(actions[2].detail.orEmpty().contains("continues after error"))
        assertEquals(AutomationFlowTarget.Profile(42), graph.nodes.single { it.kind == AutomationFlowNodeKind.MISSING }.target)
        assertTrue(graph.warnings.any { it.contains("Exit task 999") })
        assertTrue(graph.accessibilitySummary().contains("2 context"))
        assertTrue(graph.accessibilitySummary().contains("3 action"))
        assertTrue(actions[0].accessibilityLabel().contains("condition if %calendar = meeting"))
    }

    @Test
    fun contextNodesShowStructuralKeysButStillMaskPluginSecrets() {
        // Seen on a device: every State context read "key=<redacted>" because the field that names
        // the state trips the credential heuristic (A-370).
        val task = Task(id = 7, name = "Guard", actions = listOf(ActionSpec(type = "log", args = mapOf("message" to "x"))))
        val profile = Profile(
            id = 8,
            name = "Low battery guard",
            enabled = true,
            contexts = listOf(
                ContextSpec(ContextType.STATE, mapOf("key" to "battery_level", "operator" to "<=", "value" to "20")),
                ContextSpec(ContextType.EVENT, mapOf("event" to "broadcast", "extraKey" to "msg")),
                ContextSpec(ContextType.PLUGIN, mapOf("package" to "com.example.plugin", "api_key" to "sk-live-123")),
            ),
            enterTaskId = task.id,
        )

        val details = AutomationFlowGraphBuilder.build(profile, listOf(task)).contextNodes.map { it.detail.orEmpty() }

        assertEquals("key=battery_level, operator=<=, value=20", details[0])
        assertTrue(details[1], details[1].contains("extraKey=msg"))
        assertTrue(details[2], details[2].contains("api_key=<redacted>"))
        assertFalse(details[2], details[2].contains("sk-live-123"))
    }
}
