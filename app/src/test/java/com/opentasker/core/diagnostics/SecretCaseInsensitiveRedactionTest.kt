package com.opentasker.core.diagnostics

import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.core.transfer.OpenTaskerBundleCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A secret retyped with different capitalisation is the same credential, but redaction matched it
 * case-sensitively: Kotlin's two-argument `String.replace` and `String.contains` both default to
 * `ignoreCase = false`. A user whose stored secret was `sk-live-abc123` and who typed
 * `sk-Live-ABC123` into an argument, a run-only-if guard or an action label shipped that string in
 * the clear in the bundle, the paste text, a shared profile and the diagnostic export.
 */
class SecretCaseInsensitiveRedactionTest {

    private val stored = "sk-live-abc123"
    private val retyped = "sk-Live-ABC123"

    @Test
    fun redactTextCatchesACopyThatDiffersOnlyInCase() {
        val redacted = ExportRedactionPolicy.redactText("token is $retyped", setOf(stored))

        assertFalse("a differently-cased copy must not survive: $redacted", redacted.contains(retyped))
        assertFalse(redacted.contains(retyped, ignoreCase = true))
        assertTrue("the field must still be readable around the placeholder", redacted.startsWith("token is "))
    }

    @Test
    fun everyExportedFieldDropsADifferentlyCasedSecret() {
        val bundle = OpenTaskerBundleCodec.build(
            appVersion = "0.0.0",
            exportedAtEpochMs = 0L,
            profiles = emptyList(),
            tasks = listOf(
                Task(
                    id = 1,
                    name = "Publish",
                    actions = listOf(
                        ActionSpec(
                            type = "log",
                            label = "send $retyped",
                            condition = "%Pin == $retyped",
                            args = mapOf("message" to "token is $retyped"),
                        ),
                    ),
                ),
            ),
            variables = emptyList(),
            scenes = emptyList(),
            projects = emptyList(),
        )

        val exported = OpenTaskerBundleCodec.sanitizeForExport(
            bundle,
            secretVariableNames = setOf("ApiToken"),
            secretVariableValues = setOf(stored),
        )

        val action = exported.tasks.single().actions.single()
        assertFalse("argument leaked the secret", action.args.getValue("message").contains(retyped, ignoreCase = true))
        assertFalse("guard leaked the secret", action.condition.orEmpty().contains(retyped, ignoreCase = true))
        assertFalse("label leaked the secret", action.label.orEmpty().contains(retyped, ignoreCase = true))
    }

    @Test
    fun theExactCaseCopyIsStillRedacted() {
        // The original behaviour has to survive the widening.
        val redacted = ExportRedactionPolicy.redactText("token is $stored", setOf(stored))

        assertFalse(redacted.contains(stored))
    }

    @Test
    fun textWithNoSecretIsLeftAlone() {
        // Guards against a widening that redacts by accident: nothing here resembles the secret.
        val text = "ordinary label about a live service"

        assertEquals(text, ExportRedactionPolicy.redactText(text, setOf(stored)))
    }
}
