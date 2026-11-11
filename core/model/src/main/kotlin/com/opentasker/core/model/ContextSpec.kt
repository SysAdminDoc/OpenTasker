package com.opentasker.core.model

import kotlinx.serialization.Serializable

/**
 * A Context is a trigger condition. Mirrors Tasker's six context families.
 * The [config] map is type-specific; each ContextType.handler knows how to interpret it.
 */
@Serializable
data class ContextSpec(
    val type: ContextType,
    val config: Map<String, String> = emptyMap(),
    val invert: Boolean = false,
    val orGroup: String? = null,
)

/** Boolean operator used by an explicit nested context expression. */
@Serializable
enum class ContextBooleanOperator {
    AND,
    OR,
}

/**
 * A recursive profile-context expression.
 *
 * Leaves point into [Profile.contexts] instead of copying ContextSpec values. That keeps the
 * existing editor, source subscriptions, and legacy storage indices stable while allowing nested
 * AND/OR groups and NOT through [invert]. A node must be either a leaf or an operator group; the
 * evaluator fails closed for malformed nodes.
 */
@Serializable
data class ContextExpressionNode(
    val operator: ContextBooleanOperator? = null,
    val children: List<ContextExpressionNode> = emptyList(),
    val contextIndex: Int? = null,
    val invert: Boolean = false,
) {
    fun isLeaf(): Boolean = contextIndex != null && operator == null && children.isEmpty()

    /**
     * Three-valued (Kleene) evaluation. A null in [leafMatches] is a context whose value is unknown,
     * and the result is null when the answer hangs on one: NOT of unknown stays unknown, so a NOT
     * group over an unset Time bound doesn't turn a profile on. Only a true result matches.
     */
    fun evaluate(leafMatches: List<Boolean?>): Boolean? {
        val raw = when {
            isLeaf() -> {
                val index = contextIndex ?: return false
                if (index in leafMatches.indices) leafMatches[index] else false
            }
            contextIndex != null || operator == null || children.isEmpty() -> false
            else -> {
                val values = children.map { it.evaluate(leafMatches) }
                when (operator) {
                    ContextBooleanOperator.AND -> if (false in values) false else if (null in values) null else true
                    ContextBooleanOperator.OR -> if (true in values) true else if (null in values) null else false
                }
            }
        }
        return if (invert) raw?.not() else raw
    }

    fun leafIndices(): List<Int> = when {
        isLeaf() -> listOfNotNull(contextIndex)
        operator != null && contextIndex == null -> children.flatMap(ContextExpressionNode::leafIndices)
        else -> emptyList()
    }

    fun mapLeaves(transform: (Int) -> ContextExpressionNode?): ContextExpressionNode? = when {
        isLeaf() -> transform(contextIndex ?: return null)
        operator != null && contextIndex == null -> copy(
            children = children.mapNotNull { it.mapLeaves(transform) },
        ).takeIf { it.children.isNotEmpty() }
        else -> null
    }

    fun appendLeaf(index: Int): ContextExpressionNode = if (operator == null || contextIndex != null) {
        group(ContextBooleanOperator.AND, listOf(this, leaf(index)))
    } else {
        copy(children = children + leaf(index))
    }

    fun removeLeaf(indexToRemove: Int): ContextExpressionNode? = mapLeaves { index ->
        when {
            index == indexToRemove -> null
            index > indexToRemove -> leaf(index - 1)
            else -> leaf(index)
        }
    }

    fun groupFirstTwo(operator: ContextBooleanOperator): ContextExpressionNode? {
        if (children.size < 2 || this.operator == null || contextIndex != null) return null
        val nested = group(operator, children.take(2), invert = false)
        return copy(children = listOf(nested) + children.drop(2))
    }

    companion object {
        fun leaf(index: Int): ContextExpressionNode = ContextExpressionNode(contextIndex = index)

        fun group(
            operator: ContextBooleanOperator,
            children: List<ContextExpressionNode>,
            invert: Boolean = false,
        ): ContextExpressionNode = ContextExpressionNode(
            operator = operator,
            children = children,
            invert = invert,
        )

        fun implicitAnd(contextCount: Int): ContextExpressionNode? =
            (0 until contextCount).map(::leaf).takeIf { it.isNotEmpty() }?.let { group(ContextBooleanOperator.AND, it) }
    }
}

fun ContextExpressionNode.isValidForContextCount(contextCount: Int, maxDepth: Int = 64): Boolean {
    fun validate(node: ContextExpressionNode, depth: Int, seen: MutableSet<Int>): Boolean {
        if (depth > maxDepth) return false
        if (node.isLeaf()) {
            val index = node.contextIndex ?: return false
            return index in 0 until contextCount && seen.add(index)
        }
        if (node.operator == null || node.contextIndex != null || node.children.isEmpty()) return false
        return node.children.all { validate(it, depth + 1, seen) }
    }
    val seen = mutableSetOf<Int>()
    return validate(this, 0, seen) && seen.size == contextCount
}

@Serializable
enum class ContextType {
    APPLICATION,   // foreground app(s)
    TIME,          // clock-based window
    DAY,           // weekly schedule
    LOCATION,      // geofence
    STATE,         // device state (battery, headphones, charging, screen, ...)
    EVENT,         // one-shot triggers (boot, notification, NFC, calendar, ...)
    PLUGIN,        // Locale/Tasker condition plugin (polled state)
}
