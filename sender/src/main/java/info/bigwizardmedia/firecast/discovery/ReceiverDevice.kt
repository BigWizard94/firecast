package info.bigwizardmedia.firecast.discovery

import info.bigwizardmedia.firecast.protocol.FirecastProtocol

enum class DiscoverySource { DIAL, MDNS, MANUAL }

/**
 * A FireCast receiver found on the LAN (or entered manually).
 * The control channel is always the WebSocket URL from PROTOCOL.md §1.
 */
data class ReceiverDevice(
    val name: String,
    val host: String,
    val wsPort: Int = 8090,
    /** DIAL Application-URL for the FireCast app, when discovered via DIAL. */
    val dialAppUrl: String? = null,
    val source: DiscoverySource,
) {
    val wsUrl: String get() = "ws://$host:$wsPort${FirecastProtocol.WS_PATH}"
    val id: String get() = "$host:$wsPort"
}
