package com.opentasker.core.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A chip added to a list field, like For Each's values, becomes one more item. */
class ListFieldChipTest {

    private val array = VariableChipOption(
        name = "names",
        type = ActionValueType.ARRAY,
        scope = VariableChipScope.STEP,
        reference = "{{ array.names }}",
    )

    @Test
    fun aChipJoinsAListWithAComma() {
        assertEquals("{{ array.names }}", insertVariableChip("", array, list = true))
        assertEquals("1:3, {{ array.names }}", insertVariableChip("1:3", array, list = true))
        assertEquals("1:3, {{ array.names }}", insertVariableChip("1:3 ", array, list = true))
        assertEquals("1:3, {{ array.names }}", insertVariableChip("1:3, ", array, list = true))
        assertEquals("1:3, {{ array.names }}", insertVariableChip("1:3,", array, list = true))
    }

    @Test
    fun otherFieldsStillJoinWithASpace() {
        assertEquals("1:3 {{ array.names }}", insertVariableChip("1:3", array))
    }

    @Test
    fun aChipAfterARangesColonEndsTheRange() {
        val last = VariableChipOption(
            name = "LAST",
            type = ActionValueType.NUMBER,
            scope = VariableChipScope.GLOBAL,
            reference = "{{ global.LAST }}",
        )

        assertEquals("1:{{ global.LAST }}", insertVariableChip("1:", last, list = true))
        assertEquals("a, 1:{{ global.LAST }}", insertVariableChip("a, 1: ", last, list = true))
        // An array can't be a range's end, so its chip is still a new item.
        assertEquals("1:, {{ array.names }}", insertVariableChip("1:", array, list = true))
    }

    @Test
    fun forEachsValuesFieldIsTheListField() {
        registerActionMetadata()
        val fields = ActionMetadataRegistry.get("flow.foreach")!!.fields.associateBy { it.key }

        assertTrue(fields.getValue("values").listInput)
        assertEquals(listOf("values"), ActionMetadataRegistry.all().flatMap { it.fields }.filter { it.listInput }.map { it.key })
    }

    @Test
    fun theListFieldOffersArrayChipsAndOtherTextFieldsDoNot() {
        registerActionMetadata()
        val foreach = ActionMetadataRegistry.get("flow.foreach")!!.fields.associateBy { it.key }

        assertTrue(foreach.getValue("values").acceptsVariableType(ActionValueType.ARRAY))
        assertTrue(foreach.getValue("values").acceptsVariableType(ActionValueType.TEXT))
        assertFalse(foreach.getValue("var").acceptsVariableType(ActionValueType.ARRAY))
    }
}
