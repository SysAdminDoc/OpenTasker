package com.opentasker.core.actions

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import kotlinx.coroutines.delay

internal enum class VpnClient(val key: String, val label: String, val packageName: String) {
    WIREGUARD("wireguard", "WireGuard", "com.wireguard.android"),
    TAILSCALE("tailscale", "Tailscale", "com.tailscale.ipn"),
    ;

    fun isInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess
}

internal data class VpnTunnelRequest(val client: VpnClient, val up: Boolean, val tunnel: String?)

internal data class VpnBroadcast(
    val action: String,
    val packageName: String,
    val componentClass: String?,
    val extras: Map<String, String>,
)

internal sealed interface VpnTunnelParse {
    data class Valid(val request: VpnTunnelRequest) : VpnTunnelParse
    data class Invalid(val message: String) : VpnTunnelParse
}

/**
 * What it takes to ask a VPN app to change state. Android gives no app a way to toggle another
 * app's VPN, so this only covers the clients that document a remote-control broadcast.
 */
internal object VpnTunnelPolicy {
    const val ACTION_ID = "vpn.tunnel"
    const val WIREGUARD_CONTROL_PERMISSION = "com.wireguard.android.permission.CONTROL_TUNNELS"

    // The name rule WireGuard applies when a tunnel is created, so a name outside it can't exist.
    private val WIREGUARD_TUNNEL_NAME = Regex("[a-zA-Z0-9_=+.-]{1,15}")

    fun parse(args: Map<String, String>): VpnTunnelParse {
        val clientKey = args["client"]?.trim()?.lowercase().orEmpty()
        val client = VpnClient.entries.firstOrNull { it.key == clientKey }
            ?: return VpnTunnelParse.Invalid("VPN app must be wireguard or tailscale")
        val up = when (args["state"]?.trim()?.lowercase()) {
            "up" -> true
            "down" -> false
            else -> return VpnTunnelParse.Invalid("State must be up or down")
        }
        val tunnel = args["tunnel"]?.trim().orEmpty()
        if (client != VpnClient.WIREGUARD) return VpnTunnelParse.Valid(VpnTunnelRequest(client, up, null))
        if (tunnel.isEmpty()) return VpnTunnelParse.Invalid("WireGuard needs the tunnel name")
        if (!WIREGUARD_TUNNEL_NAME.matches(tunnel)) {
            return VpnTunnelParse.Invalid("'$tunnel' can't be a WireGuard tunnel name")
        }
        return VpnTunnelParse.Valid(VpnTunnelRequest(client, up, tunnel))
    }

    /** Names the first thing stopping the broadcast from being delivered, or null when it can be sent. */
    fun missingSetup(
        request: VpnTunnelRequest,
        isInstalled: (VpnClient) -> Boolean,
        isGranted: (String) -> Boolean,
    ): String? = when {
        !isInstalled(request.client) -> "${request.client.label} isn't installed"
        request.client == VpnClient.WIREGUARD && !isGranted(WIREGUARD_CONTROL_PERMISSION) ->
            "OpenTasker doesn't have permission to control WireGuard tunnels. Grant it in Setup"
        else -> null
    }

    fun broadcast(request: VpnTunnelRequest): VpnBroadcast = when (request.client) {
        VpnClient.WIREGUARD -> VpnBroadcast(
            action = if (request.up) {
                "com.wireguard.android.action.SET_TUNNEL_UP"
            } else {
                "com.wireguard.android.action.SET_TUNNEL_DOWN"
            },
            packageName = request.client.packageName,
            componentClass = null,
            extras = mapOf("tunnel" to request.tunnel.orEmpty()),
        )
        VpnClient.TAILSCALE -> VpnBroadcast(
            action = if (request.up) "com.tailscale.ipn.CONNECT_VPN" else "com.tailscale.ipn.DISCONNECT_VPN",
            packageName = request.client.packageName,
            componentClass = "com.tailscale.ipn.IPNReceiver",
            extras = emptyMap(),
        )
    }

    /**
     * The run log line for a request that ended in the wanted state. Android won't say which app
     * owns a VPN, so a tunnel that was up before the request proves nothing about this client:
     * another app's VPN, or a different WireGuard tunnel, reads exactly the same.
     */
    fun reachedStateMessage(request: VpnTunnelRequest, vpnWasUpBefore: Boolean): String = when {
        request.up && vpnWasUpBefore ->
            "A VPN was already connected before ${request.client.label} was asked, so this can't " +
                "confirm it's ${request.tunnel?.let { "'$it'" } ?: "${request.client.label}'s"}"
        else -> "VPN ${if (request.up) "up" else "down"} through ${request.client.label}"
    }

    /**
     * Both clients ignore a request they can't honor without saying so, which is why the action
     * watches the network afterwards and spells out the likely reasons here.
     */
    fun noChangeMessage(request: VpnTunnelRequest): String {
        val direction = if (request.up) "up" else "down"
        return when (request.client) {
            VpnClient.WIREGUARD ->
                "VPN didn't go $direction. Check the tunnel is named '${request.tunnel}' and that " +
                    "Allow remote control apps is on in WireGuard's settings. WireGuard also needs to have been " +
                    "allowed to create a VPN once"
            VpnClient.TAILSCALE ->
                "VPN didn't go $direction. Check you're logged in to Tailscale and that it has been " +
                    "allowed to create a VPN once"
        }
    }
}

/** Polls until the VPN reaches the wanted state, giving up after [timeoutMs]. */
internal suspend fun awaitVpnState(
    wantUp: Boolean,
    isVpnUp: () -> Boolean,
    timeoutMs: Long = VPN_STATE_TIMEOUT_MS,
    pollMs: Long = VPN_STATE_POLL_MS,
    pause: suspend (Long) -> Unit = { delay(it) },
): Boolean {
    var waited = 0L
    while (true) {
        if (isVpnUp() == wantUp) return true
        if (waited >= timeoutMs) return false
        pause(pollMs)
        waited += pollMs
    }
}

private const val VPN_STATE_TIMEOUT_MS = 8_000L
private const val VPN_STATE_POLL_MS = 500L

/**
 * Connects or disconnects a tunnel in WireGuard or Tailscale.
 *
 * Args:
 *   - "client": wireguard or tailscale
 *   - "state": up or down
 *   - "tunnel": the tunnel's name, WireGuard only
 */
class VpnTunnelAction : DeclaredAction(ActionCatalog.require(VpnTunnelPolicy.ACTION_ID)) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val request = when (val parsed = VpnTunnelPolicy.parse(args)) {
            is VpnTunnelParse.Valid -> parsed.request
            is VpnTunnelParse.Invalid -> return ActionResult.Failure(parsed.message)
        }
        val app = ctx.app
        VpnTunnelPolicy.missingSetup(
            request,
            isInstalled = { client -> client.isInstalled(app) },
            isGranted = { permission -> ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED },
        )?.let { return ActionResult.Failure(it) }

        val manager = app.getSystemService(ConnectivityManager::class.java)
            ?: return ActionResult.Failure("Can't read the network state on this device")
        val broadcast = VpnTunnelPolicy.broadcast(request)
        val intent = Intent(broadcast.action)
            .setPackage(broadcast.packageName)
            // A client that was force-stopped or never opened still has to hear this, and a queued
            // background broadcast could easily outlast the wait below.
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
        broadcast.componentClass?.let { intent.component = ComponentName(broadcast.packageName, it) }
        broadcast.extras.forEach { (key, value) -> intent.putExtra(key, value) }
        return try {
            val vpnWasUpBefore = isAnyVpnUp(manager)
            app.sendBroadcast(intent)
            if (awaitVpnState(request.up, isVpnUp = { isAnyVpnUp(manager) })) {
                ctx.logger(VpnTunnelPolicy.reachedStateMessage(request, vpnWasUpBefore))
                ActionResult.Success
            } else {
                ActionResult.Failure(VpnTunnelPolicy.noChangeMessage(request))
            }
        } catch (ex: SecurityException) {
            ActionResult.Failure("${request.client.label} refused the request: ${ex.message}", ex)
        }
    }

    // activeNetwork alone misses a tunnel that excludes OpenTasker from its allowed apps.
    @Suppress("DEPRECATION")
    private fun isAnyVpnUp(manager: ConnectivityManager): Boolean = manager.allNetworks.any { network ->
        manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }
}
