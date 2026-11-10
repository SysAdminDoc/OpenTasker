package com.opentasker.core.contexts

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NfcDeliveryGateTest {
    @Test
    fun aTagTheNfcServiceDeliversThroughTheAliasIsHandled() {
        assertTrue(
            NfcDeliveryGate.isFreshSystemDelivery(
                NfcDeliveryGate.DISPATCH_ALIAS,
                Intent.FLAG_ACTIVITY_NEW_TASK,
                restored = false,
            ),
        )
    }

    @Test
    fun aTagIntentAddressedToMainActivityItselfIsIgnored() {
        // Another app's forged start names the exported MainActivity, since the alias refuses it.
        assertFalse(NfcDeliveryGate.isFreshSystemDelivery("com.opentasker.app.MainActivity", 0, restored = false))
        assertFalse(NfcDeliveryGate.isFreshSystemDelivery(null, 0, restored = false))
    }

    @Test
    fun anOldTagIntentHandedBackOnRestoreOrFromRecentsIsIgnored() {
        assertFalse(NfcDeliveryGate.isFreshSystemDelivery(NfcDeliveryGate.DISPATCH_ALIAS, 0, restored = true))
        assertFalse(
            NfcDeliveryGate.isFreshSystemDelivery(
                NfcDeliveryGate.DISPATCH_ALIAS,
                Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY,
                restored = false,
            ),
        )
    }
}
