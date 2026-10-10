package info.bigwizardmedia.firecast.protocol

/**
 * A single established WebSocket connection, regardless of who dialed.
 *
 * Client mode (native receivers): wraps an OkHttp WebSocket the sender opened.
 * Server mode (web receiver): wraps a Java-WebSocket connection the receiver opened.
 */
interface WsConnection {
    /** Sends a text frame; returns false if the send failed. */
    fun send(text: String): Boolean

    /** Closes the connection quietly. */
    fun close()
}
