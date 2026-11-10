package com.opentasker.core.actions

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnTunnelPolicyTest {
    private fun valid(vararg args: Pair<String, String>): VpnTunnelRequest =
        (VpnTunnelPolicy.parse(mapOf(*args)) as VpnTunnelParse.Valid).request

    private fun invalid(vararg args: Pair<String, String>): String =
        (VpnTunnelPolicy.parse(mapOf(*args)) as VpnTunnelParse.Invalid).message

    @Test
    fun wireguardBroadcastNamesTheTunnelAndLetsTheFilterPickTheReceiver() {
        val up = VpnTunnelPolicy.broadcast(valid("client" to "wireguard", "state" to "up", "tunnel" to "home-1"))
        assertEquals("com.wireguard.android.action.SET_TUNNEL_UP", up.action)
        assertEquals("com.wireguard.android", up.packageName)
        assertNull(up.componentClass)
        assertEquals(mapOf("tunnel" to "home-1"), up.extras)

        val down = VpnTunnelPolicy.broadcast(valid("client" to "WireGuard", "state" to "DOWN", "tunnel" to "home-1"))
        assertEquals("com.wireguard.android.action.SET_TUNNEL_DOWN", down.action)
    }

    @Test
    fun tailscaleBroadcastTargetsItsReceiverAndIgnoresATunnelName() {
        val up = VpnTunnelPolicy.broadcast(valid("client" to "tailscale", "state" to "up", "tunnel" to "ignored"))
        assertEquals("com.tailscale.ipn.CONNECT_VPN", up.action)
        assertEquals("com.tailscale.ipn", up.packageName)
        assertEquals("com.tailscale.ipn.IPNReceiver", up.componentClass)
        assertTrue(up.extras.isEmpty())

        val down = VpnTunnelPolicy.broadcast(valid("client" to "tailscale", "state" to "down"))
        assertEquals("com.tailscale.ipn.DISCONNECT_VPN", down.action)
    }

    @Test
    fun parseRefusesAnythingItCouldNotSend() {
        assertTrue(invalid("state" to "up").contains("wireguard or tailscale"))
        assertTrue(invalid("client" to "openvpn", "state" to "up").contains("wireguard or tailscale"))
        assertTrue(invalid("client" to "tailscale").contains("up or down"))
        assertTrue(invalid("client" to "tailscale", "state" to "on").contains("up or down"))
        assertTrue(invalid("client" to "wireguard", "state" to "up").contains("tunnel name"))
        assertTrue(invalid("client" to "wireguard", "state" to "up", "tunnel" to "  ").contains("tunnel name"))
        assertTrue(invalid("client" to "wireguard", "state" to "up", "tunnel" to "my tunnel").contains("my tunnel"))
        assertTrue(invalid("client" to "wireguard", "state" to "up", "tunnel" to "a".repeat(16)).contains("aaaa"))
    }

    @Test
    fun setupGapsNameTheClientOrThePermission() {
        val wireguard = valid("client" to "wireguard", "state" to "up", "tunnel" to "home")
        val tailscale = valid("client" to "tailscale", "state" to "up")

        val notInstalled = VpnTunnelPolicy.missingSetup(wireguard, isInstalled = { false }, isGranted = { true })
        assertTrue(notInstalled!!.contains("WireGuard isn't installed"))
        assertTrue(
            VpnTunnelPolicy.missingSetup(tailscale, isInstalled = { false }, isGranted = { true })!!
                .contains("Tailscale isn't installed"),
        )

        val asked = mutableListOf<String>()
        val noPermission = VpnTunnelPolicy.missingSetup(
            wireguard,
            isInstalled = { true },
            isGranted = {
                asked += it
                false
            },
        )
        assertTrue(noPermission!!.contains("Setup"))
        assertEquals(listOf(VpnTunnelPolicy.WIREGUARD_CONTROL_PERMISSION), asked)

        assertNull(VpnTunnelPolicy.missingSetup(wireguard, isInstalled = { true }, isGranted = { true }))
        assertNull(
            "Tailscale has no permission of its own to grant",
            VpnTunnelPolicy.missingSetup(tailscale, isInstalled = { true }, isGranted = { false }),
        )
    }

    @Test
    fun aRequestTheClientSilentlyIgnoredSaysWhereToLook() {
        val wireguard = VpnTunnelPolicy.noChangeMessage(valid("client" to "wireguard", "state" to "up", "tunnel" to "home"))
        assertTrue(wireguard.contains("go up"))
        assertTrue(wireguard.contains("'home'"))
        assertTrue(wireguard.contains("Allow remote control apps"))

        val tailscale = VpnTunnelPolicy.noChangeMessage(valid("client" to "tailscale", "state" to "down"))
        assertTrue(tailscale.contains("go down"))
        assertTrue(tailscale.contains("Tailscale"))
    }

    @Test
    fun waitReturnsAtOnceWhenTheVpnIsAlreadyThere() = runBlocking {
        val paused = mutableListOf<Long>()
        assertTrue(awaitVpnState(wantUp = true, isVpnUp = { true }, pause = { paused += it }))
        assertTrue(awaitVpnState(wantUp = false, isVpnUp = { false }, pause = { paused += it }))
        assertTrue(paused.isEmpty())
    }

    @Test
    fun waitSeesTheVpnComeUpPartWayThrough() = runBlocking {
        var reads = 0
        val reached = awaitVpnState(
            wantUp = true,
            isVpnUp = { ++reads >= 3 },
            timeoutMs = 2_000,
            pollMs = 500,
            pause = {},
        )
        assertTrue(reached)
        assertEquals(3, reads)
    }

    @Test
    fun waitGivesUpInsteadOfHangingWhenNothingChanges() = runBlocking {
        var waited = 0L
        val reached = awaitVpnState(
            wantUp = false,
            isVpnUp = { true },
            timeoutMs = 2_000,
            pollMs = 500,
            pause = { waited += it },
        )
        assertFalse(reached)
        assertEquals(2_000L, waited)
    }

    @Test
    fun aVpnThatWasAlreadyUpIsNotCreditedToTheClient() {
        val wireguard = valid("client" to "wireguard", "state" to "up", "tunnel" to "home-1")
        val tailscale = valid("client" to "tailscale", "state" to "up")

        assertEquals("VPN up through WireGuard", VpnTunnelPolicy.reachedStateMessage(wireguard, vpnWasUpBefore = false))
        assertTrue("can't confirm it's 'home-1'" in VpnTunnelPolicy.reachedStateMessage(wireguard, vpnWasUpBefore = true))
        assertTrue("can't confirm it's Tailscale's" in VpnTunnelPolicy.reachedStateMessage(tailscale, vpnWasUpBefore = true))
    }

    @Test
    fun aTunnelThatWentDownSaysSoWhateverWasUpBefore() {
        val down = valid("client" to "tailscale", "state" to "down")

        assertEquals("VPN down through Tailscale", VpnTunnelPolicy.reachedStateMessage(down, vpnWasUpBefore = true))
    }

    @Test
    fun disconnectingWithNothingUpDoesNotClaimATunnelWentDown() {
        val down = valid("client" to "tailscale", "state" to "down")

        assertEquals(
            "No VPN was connected, so Tailscale had nothing to bring down",
            VpnTunnelPolicy.reachedStateMessage(down, vpnWasUpBefore = false),
        )
    }

    @Test
    fun theActionRefusesABadRequestBeforeTouchingTheDevice() = runBlocking {
        val result = VpnTunnelAction().run(
            com.opentasker.core.engine.ActionContext(
                android.content.ContextWrapper(null),
                com.opentasker.core.engine.VariableStore(),
                logger = {},
            ),
            mapOf("client" to "openvpn", "state" to "up"),
        )
        assertTrue(result is com.opentasker.core.engine.ActionResult.Failure)
    }
}
