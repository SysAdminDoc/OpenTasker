package com.opentasker.core.contexts

import android.content.Intent

/**
 * Decides whether an intent MainActivity received is a tag the system just read.
 *
 * MainActivity stays exported for the launcher, so any installed app can start it with
 * TAG_DISCOVERED and a forged Tag parcel. Real tags arrive through the [DISPATCH_ALIAS]
 * activity-alias instead, which requires DISPATCH_NFC_MESSAGE: the NFC service (android.uid.nfc)
 * requests it on every Android release from 8 on, and a third-party app can't hold it. The alias
 * shows up as the intent's component, so a start addressed to MainActivity itself is never a tag.
 *
 * A restored activity or a relaunch from Recents hands back the old tag intent, which would fire
 * the profile or retry the write a second time without a tap.
 */
object NfcDeliveryGate {
    const val DISPATCH_ALIAS = "com.opentasker.app.NfcTagDispatch"

    fun isFreshSystemDelivery(componentClassName: String?, intentFlags: Int, restored: Boolean): Boolean =
        componentClassName == DISPATCH_ALIAS &&
            !restored &&
            (intentFlags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0
}
