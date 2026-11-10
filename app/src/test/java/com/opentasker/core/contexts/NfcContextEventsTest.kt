package com.opentasker.core.contexts

import com.opentasker.app.R
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NfcContextEventsTest {
    @Test
    fun tagIdHexUsesStableUppercaseEncoding() {
        assertEquals("000FA0FF", NfcContextEvents.tagIdHex(byteArrayOf(0x00, 0x0F, 0xA0.toByte(), 0xFF.toByte())))
    }

    @Test
    fun normalizeTagIdIgnoresSeparatorsAndCase() {
        assertEquals("04AABBCC", NfcContextEvents.normalizeTagId("04:aa-bb cc"))
    }

    @Test
    fun buildEventIncludesNfcMarkerTagIdAndSortedTechList() {
        val event = NfcContextEvents.buildEvent(
            tagId = byteArrayOf(0x04, 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte()),
            techList = listOf("android.nfc.tech.NfcA", "android.nfc.tech.Ndef"),
        )

        assertEquals("event", event.type)
        assertEquals(true, event.matched)
        assertEquals("nfc", event.metadata["event"])
        assertEquals("04AABBCC", event.metadata["tagId"])
        assertEquals("android.nfc.tech.Ndef,android.nfc.tech.NfcA", event.metadata["techList"])
    }

    @Test
    fun writePlannerNormalizesLabelAndEstimatesPayload() {
        val plan = NfcTagWritePlanner.planTextRecord("  OpenTasker   Desk Tag  ")

        assertEquals("OpenTasker Desk Tag", plan.text)
        assertTrue(plan.estimatedBytes > plan.text.length)
    }

    @Test
    fun writePlannerUsesDefaultForBlankLabel() {
        val plan = NfcTagWritePlanner.planTextRecord("   ")

        assertEquals("OpenTasker NFC trigger", plan.text)
    }

    @Test
    fun aWriteTheTagDidntTakeSaysToHoldItStillAndPointsAtDiagnostics() {
        // What Ndef.writeNdefMessage, connect() and NdefFormatable.format() throw when the tag leaves
        // or refuses the write.
        assertEquals(R.string.nfc_write_io_failed, nfcWriteFailureRes(IOException()))
        assertEquals(R.string.nfc_write_failed, nfcWriteFailureRes(IllegalStateException("Call connect() first!")))
    }
}
