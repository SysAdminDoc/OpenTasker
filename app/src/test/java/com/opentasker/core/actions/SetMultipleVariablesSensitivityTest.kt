package com.opentasker.core.actions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Multiple Variables Set keeps a secret's value out of previews whether it sits in Values or after its name. */
class SetMultipleVariablesSensitivityTest {

    @Before
    fun setUp() {
        registerActionMetadata()
    }

    private fun sensitive(argName: String, vararg args: Pair<String, String>) =
        ActionArgumentSensitivity.isSensitive("var.set_multiple", argName, mapOf(*args))

    @Test
    fun aSecretWrittenAfterItsNameIsMasked() {
        val args = mapOf("names" to "%user,sam\n%api_token,hunter2")

        assertTrue(sensitive("names", *args.toList().toTypedArray()))
        assertFalse(ActionArgumentSensitivity.summarize("var.set_multiple", args).contains("hunter2"))
    }

    @Test
    fun valuesForASecretNameAreMaskedAndTheNamesStayReadable() {
        val args = arrayOf("names" to "%api_token\n%user", "values" to "hunter2\nsam")

        assertTrue(sensitive("values", *args))
        assertFalse(sensitive("names", *args))
    }

    @Test
    fun namesWithNothingSecretOrNoValueInThemStayReadable() {
        assertFalse(sensitive("names", "names" to "%count,0\n%greeting,hello"))
        assertFalse(sensitive("names", "names" to "%token,%key", "names_splitter" to ","))
        assertFalse(sensitive("names", "names" to "%token\n%key", "values" to "1\n2"))
    }

    @Test
    fun aSecretAfterATypedValuesSplitterIsMaskedAndSoIsALineThatIsNoName() {
        assertTrue(sensitive("names", "names" to "%api_token=hunter2", "values_splitter" to "="))
        // A space isn't the splitter, so the line is one bad name. The failed step names it as
        // written, so the text after the space is masked too.
        assertTrue(sensitive("names", "names" to "%api_token hunter2", "values_splitter" to "="))
    }

    @Test
    fun aValueTypedOnOneLineIsMaskedThoughItReadsAsABareName() {
        assertTrue(sensitive("names", "names" to "%api_token,abc"))
    }
}
