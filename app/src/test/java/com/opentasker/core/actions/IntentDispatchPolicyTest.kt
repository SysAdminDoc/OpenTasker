package com.opentasker.core.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentDispatchPolicyTest {
    @Test
    fun parsesBoundedActivityPayloadAndPrimitiveExtras() {
        val result = IntentDispatchPolicy.parse(
            mapOf(
                "package" to "com.example.target",
                "action" to "android.intent.action.VIEW",
                "component" to ".MainActivity",
                "uri" to "https://example.com/item",
                "mime_type" to "text/plain",
                "flags" to "activity_clear_top,grant_read_uri",
                "extras" to "title=string:hello\ncount=int:7\nenabled=bool:true",
            ),
        )

        val plan = (result as IntentDispatchParseResult.Valid).plan
        assertEquals(IntentDispatchMode.ACTIVITY, plan.mode)
        assertEquals("com.example.target.MainActivity", plan.componentClassName)
        assertEquals(3, plan.extras.size)
        assertTrue(IntentDispatchFlag.GRANT_READ_URI in plan.flags)
        assertEquals(IntentExtraType.INT, plan.extras[1].type)
    }

    @Test
    fun rejectsUnboundedOrUnsafeDispatchInputs() {
        val fileUri = IntentDispatchPolicy.parse(
            mapOf("package" to "com.example.target", "uri" to "file:///sdcard/secret.txt"),
        )
        val broadcastWithoutComponent = IntentDispatchPolicy.parse(
            mapOf("package" to "com.example.target", "mode" to "broadcast", "action" to "com.example.EVENT"),
        )
        val unknownFlag = IntentDispatchPolicy.parse(
            mapOf("package" to "com.example.target", "flags" to "activity_no_user_action"),
        )

        assertTrue(fileUri is IntentDispatchParseResult.Invalid && fileUri.message.contains("file://"))
        assertTrue(broadcastWithoutComponent is IntentDispatchParseResult.Invalid)
        assertTrue(unknownFlag is IntentDispatchParseResult.Invalid)
    }

    @Test
    fun rejectsNonPrimitiveOrOversizedExtras() {
        val parcelableLike = IntentDispatchPolicy.parse(
            mapOf("package" to "com.example.target", "extras" to "payload=json:{}"),
        )
        val oversized = IntentDispatchPolicy.parse(
            mapOf("package" to "com.example.target", "extras" to "payload=string:${"x".repeat(513)}"),
        )

        assertTrue(parcelableLike is IntentDispatchParseResult.Invalid)
        assertTrue(oversized is IntentDispatchParseResult.Invalid)
    }

    @Test
    fun rejectsUriBearingDispatchWithoutAnExplicitGrant() {
        val missingGrant = IntentDispatchPolicy.parse(
            mapOf(
                "package" to "com.example.target",
                "component" to ".MainActivity",
                "uri" to "content://com.example.files/document/42",
            ),
        )
        val explicitRead = IntentDispatchPolicy.parse(
            mapOf(
                "package" to "com.example.target",
                "component" to ".MainActivity",
                "uri" to "content://com.example.files/document/42",
                "flags" to "grant_read_uri",
            ),
        )

        assertTrue(missingGrant is IntentDispatchParseResult.Invalid)
        assertTrue((missingGrant as IntentDispatchParseResult.Invalid).message.contains("explicit"))
        assertTrue(explicitRead is IntentDispatchParseResult.Valid)
    }

    @Test
    fun aResultVariableWrittenAsPercentNameNamesThatVariable() {
        val result = IntentDispatchPolicy.parse(
            mapOf(
                "package" to "com.example.target",
                "component" to ".EventReceiver",
                "mode" to "broadcast",
                "action" to "com.example.EVENT",
                "result_variable" to " %res ",
            ),
        )

        assertEquals("res", (result as IntentDispatchParseResult.Valid).plan.resultVariable)
    }

    @Test
    fun aResultVariableTheStoreCouldNotKeepStopsTheStep() {
        fun parse(name: String) = IntentDispatchPolicy.parse(
            mapOf(
                "package" to "com.example.target",
                "component" to ".EventReceiver",
                "mode" to "broadcast",
                "action" to "com.example.EVENT",
                "result_variable" to name,
            ),
        )

        // A bare % used to read as no result variable at all, and _res parsed but was never stored.
        listOf("%", " % ", "%_res", "1res").forEach { name ->
            assertEquals(name, IntentDispatchParseResult.Invalid("invalid result variable"), parse(name))
        }
        assertEquals("res", (parse("res") as IntentDispatchParseResult.Valid).plan.resultVariable)
        assertEquals("res-x", (parse("%res-x") as IntentDispatchParseResult.Valid).plan.resultVariable)
        assertEquals(null, (parse("  ") as IntentDispatchParseResult.Valid).plan.resultVariable)
    }
}
