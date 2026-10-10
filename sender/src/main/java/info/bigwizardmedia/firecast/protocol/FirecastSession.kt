package info.bigwizardmedia.firecast.protocol

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * WebSocket control channel implementing PROTOCOL.md.
 *
 * Lifecycle: [connect] → [hello] → (maybe [pair]) → commands… → [close].
 * Incoming receiver events update [status]; command replies complete the
 * matching [sendCommand] call by id.
 */
class FirecastSession(
    parentScope: CoroutineScope,
    private val clientName: String = "FireCast Android",
    private val clientVersion: String = "0.2.0",
) {
    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, PAIRING_REQUIRED, FAILED }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .pingInterval(FirecastProtocol.KEEPALIVE_SECONDS, TimeUnit.SECONDS)
        .build()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()

    private var conn: WsConnection? = null
    private var keepaliveJob: Job? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _status = MutableStateFlow(ReceiverStatus())
    val status: StateFlow<ReceiverStatus> = _status.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    @Volatile
    var fingerprint: String = ""

    /**
     * Client mode: dial out to a native receiver's WebSocket URL.
     */
    fun connectTo(wsUrl: String, fingerprint: String) {
        this.fingerprint = fingerprint
        _connectionState.value = ConnectionState.CONNECTING
        val request = Request.Builder().url(wsUrl).build()
        val ws = client.newWebSocket(request, Listener())
        conn = object : WsConnection {
            override fun send(text: String): Boolean = try {
                ws.send(text)
            } catch (_: Exception) {
                false
            }

            override fun close() {
                try {
                    ws.close(1000, "bye")
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * Server mode: adopt a WebSocket the receiver opened to us
     * (web receiver via CastControlServer). The protocol flow after this
     * point is identical to client mode: we still send `hello` first.
     */
    fun accept(incoming: WsConnection, fingerprint: String) {
        this.fingerprint = fingerprint
        conn = incoming
        onConnected()
    }

    suspend fun hello(): JsonObject? {
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("client", clientName)
            put("client_version", clientVersion)
            put("fingerprint", fingerprint)
            put("protocol", FirecastProtocol.VERSION)
        }
        return sendCommand("hello", payload)
    }

    suspend fun pair(pin: String): Boolean {
        val payload = kotlinx.serialization.json.buildJsonObject { put("pin", pin) }
        val result = sendCommand("pair", payload) ?: return false
        return result["ok"]?.jsonPrimitive?.booleanOrNull == true
    }

    suspend fun play(url: String, title: String?, mime: String?, position: Double = 0.0): Boolean {
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("url", url)
            title?.let { put("title", it) }
            mime?.let { put("mime", it) }
            put("position", position)
        }
        return okOf(sendCommand("play", payload))
    }

    suspend fun pause(): Boolean = okOf(sendCommand("pause"))
    suspend fun resume(): Boolean = okOf(sendCommand("resume"))
    suspend fun stop(): Boolean = okOf(sendCommand("stop"))

    suspend fun seek(position: Double): Boolean {
        val payload = kotlinx.serialization.json.buildJsonObject { put("position", position) }
        return okOf(sendCommand("seek", payload))
    }

    suspend fun setVolume(level: Float): Boolean {
        val payload = kotlinx.serialization.json.buildJsonObject { put("level", level.coerceIn(0f, 1f)) }
        return okOf(sendCommand("volume", payload))
    }

    suspend fun setMuted(muted: Boolean): Boolean {
        val payload = kotlinx.serialization.json.buildJsonObject { put("muted", muted) }
        return okOf(sendCommand("mute", payload))
    }

    suspend fun refreshStatus() {
        sendCommand("get_status")
    }

    private suspend fun sendCommand(
        type: String,
        payload: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.buildJsonObject {},
    ): JsonObject? {
        val (id, text) = newCommand(type, payload)
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        val sent = conn?.send(text) == true
        if (!sent) {
            pending.remove(id)
            return null
        }
        return withTimeoutOrNull(FirecastProtocol.COMMAND_TIMEOUT_MS) {
            deferred.await()
        }.also { pending.remove(id) }
    }

    private fun okOf(result: JsonObject?): Boolean =
        result?.get("ok")?.jsonPrimitive?.booleanOrNull == true

    fun close() {
        keepaliveJob?.cancel()
        try {
            conn?.close()
        } catch (_: Exception) {
        }
        conn = null
        pending.values.forEach { it.cancel() }
        pending.clear()
        _connectionState.value = ConnectionState.DISCONNECTED
        scope.cancel()
    }

    /** Called when the underlying transport reports the channel is open. */
    private fun onConnected() {
        _connectionState.value = ConnectionState.CONNECTED
        startKeepalive()
    }

    /** Called with each incoming text frame. */
    fun onText(text: String) {
        scope.launch { handleMessage(text) }
    }

    /** Called when the transport closes or fails. */
    private fun onTransportClosed(failed: Boolean, error: Throwable? = null) {
        if (failed) {
            _lastError.value = error?.message ?: "connection failed"
        }
        if (_connectionState.value == ConnectionState.CONNECTING) {
            _connectionState.value = ConnectionState.FAILED
        } else {
            _connectionState.value = ConnectionState.DISCONNECTED
        }
        pending.values.forEach { it.cancel() }
        pending.clear()
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            onConnected()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            onText(text)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            onTransportClosed(failed = true, error = t)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            onTransportClosed(failed = false)
        }
    }

    private suspend fun handleMessage(text: String) {
        val obj = try {
            json.parseToJsonElement(text).jsonObject
        } catch (_: Exception) {
            return // Not JSON — ignore per forward-compatibility rule.
        }
        val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return
        val id = obj["id"]?.jsonPrimitive?.contentOrNull

        // Command reply: complete the waiter.
        if (id != null && type.endsWith("_result")) {
            val payload = obj["payload"]?.jsonObject ?: JsonObject(emptyMap())
            if (type == "get_status_result") {
                _status.value = parseStatus(payload)
            }
            pending.remove(id)?.complete(payload)
            return
        }

        // Unsolicited events (no id).
        when (type) {
            "ping" -> conn?.send(newEvent("pong"))
            "status" -> obj["payload"]?.jsonObject?.let { _status.value = parseStatus(it) }
            "position" -> {
                val p = obj["payload"]?.jsonObject ?: return
                val cur = _status.value
                _status.value = cur.copy(
                    position = p["position"]?.jsonPrimitive?.doubleOrNull ?: cur.position,
                    duration = p["duration"]?.jsonPrimitive?.doubleOrNull ?: cur.duration,
                )
            }
            "ended" -> _status.value = _status.value.copy(state = PlaybackState.ENDED)
            "error" -> {
                val p = obj["payload"]?.jsonObject
                _status.value = _status.value.copy(
                    state = PlaybackState.ERROR,
                    error = p?.get("message")?.jsonPrimitive?.contentOrNull,
                )
            }
            // Unknown event types are ignored (forward compatibility).
        }
    }

    private fun parseStatus(p: JsonObject): ReceiverStatus = ReceiverStatus(
        state = PlaybackState.fromWire(p["state"]?.jsonPrimitive?.contentOrNull),
        position = p["position"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
        duration = p["duration"]?.jsonPrimitive?.doubleOrNull ?: -1.0,
        volume = p["volume"]?.jsonPrimitive?.floatOrNull ?: 1f,
        muted = p["muted"]?.jsonPrimitive?.booleanOrNull ?: false,
        title = p["title"]?.jsonPrimitive?.contentOrNull,
    )

    private fun startKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = scope.launch {
            while (true) {
                delay(FirecastProtocol.KEEPALIVE_SECONDS * 1000)
                val s = conn ?: break
                try {
                    s.send(newEvent("ping"))
                } catch (_: Exception) {
                    break
                }
            }
        }
    }
}
