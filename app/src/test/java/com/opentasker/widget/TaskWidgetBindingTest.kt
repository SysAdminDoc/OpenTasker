package com.opentasker.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskWidgetBindingTest {
    @Test
    fun aWidgetThatLostItsTaskStillLooksForIt() {
        assertEquals(7L, widgetTaskToLookUp(boundTaskId = -1L, deletedTaskId = 7L))
    }

    @Test
    fun aTaskChosenSinceThenWins() {
        assertEquals(9L, widgetTaskToLookUp(boundTaskId = 9L, deletedTaskId = 7L))
    }

    @Test
    fun aWidgetThatWasNeverSetUpHasNothingToLookFor() {
        assertEquals(-1L, widgetTaskToLookUp(boundTaskId = -1L, deletedTaskId = -1L))
    }
}
