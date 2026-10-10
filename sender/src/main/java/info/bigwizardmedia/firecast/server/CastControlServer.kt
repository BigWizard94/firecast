package info.bigwizardmedia.firecast.server

import info.bigwizardmedia.firecast.protocol.WsConnection
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * WebSocket control server for receiver-initiated connections (PROTOCOL.md §1d).
 *
 * The web receiver (TV browser page) dials `ws://<phone-lan-ip>:8081/firecast`;
 * this server adopts each connection as a [WsConnection] and routes frames to
 * the callbacks below. Signaling runs on Java-WebSocket's own threads — the
 * ViewModel forwards into its coroutine scope.
 */
class CastControlServer(port: Int = 8081) : WebSocketServer(InetSocketAddress(port)) {

    /** A receiver opened the control channel. */
    var onOpen: ((WsConnection) -> Unit)? = null

    /** A text frame arrived from a receiver. */
    var onMessage: ((WsConnection, String) -> Unit)? = null

    /** A receiver's channel closed. */
    var onClose: ((WsConnection) -> Unit)? = null

    private val channels = ConcurrentHashMap<WebSocket, WsConnection>()

    private inner class Channel(private val conn: WebSocket) : WsConnection {
        override fun send(text: String): Boolean = try {
            conn.send(text)
            true
        } catch (_: Exception) {
            false
        }

        override fun close() {
            try {
                conn.close()
            } catch (_: Exception) {
            }
        }
    }

    /** Start listening; safe to call once. */
    fun listen() {
        isReuseAddr = true
        start()
    }

    /** ws:// URL the receiver page should dial (LAN IP of this phone). */
    fun wsUrl(): String = "ws://${MediaServer.lanIp()}:$port/firecast"

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        val ch = Channel(conn)
        channels[conn] = ch
        try {
            onOpen?.invoke(ch)
        } catch (_: Exception) {
        }
    }

    override fun onMessage(conn: WebSocket, message: String) {
        val ch = channels[conn] ?: return
        try {
            onMessage?.invoke(ch, message)
        } catch (_: Exception) {
        }
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        val ch = channels.remove(conn) ?: return
        try {
            onClose?.invoke(ch)
        } catch (_: Exception) {
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        // Transport-level noise (e.g. a failed handshake); per-connection
        // failures surface through onClose. Nothing to do here.
    }

    override fun onStart() {
        // Listening.
    }
}
