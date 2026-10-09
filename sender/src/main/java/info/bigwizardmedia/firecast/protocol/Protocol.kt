package info.bigwizardmedia.firecast.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * FireCast wire protocol v1 constants and message builders.
 * Full spec: PROTOCOL.md at the repo root.
 */
object FirecastProtocol {
    const val VERSION = 1
    const val DIAL_APP_NAME = "FireCast"
    const val MDNS_SERVICE_TYPE = "_firecast._tcp"
    const val WS_PATH = "/firecast"
    const val MEDIA_HTTP_PORT = 8080
    const val KEEPALIVE_SECONDS = 30L
    const val COMMAND_TIMEOUT_MS = 10_000L
}

/** Builds a command envelope; returns (id, jsonString). */
fun newCommand(type: String, payload: JsonObject = buildJsonObject {}): Pair<String, String> {
    val id = UUID.randomUUID().toString()
    val json = buildJsonObject {
        put("v", FirecastProtocol.VERSION)
        put("id", id)
        put("type", type)
        put("payload", payload)
    }.toString()
    return id to json
}

/** Builds an unsolicited event envelope (no id). */
fun newEvent(type: String, payload: JsonObject = buildJsonObject {}): String =
    buildJsonObject {
        put("v", FirecastProtocol.VERSION)
        put("type", type)
        put("payload", payload)
    }.toString()

enum class PlaybackState {
    IDLE, LOADING, PLAYING, PAUSED, BUFFERING, ENDED, ERROR, UNKNOWN;

    companion object {
        fun fromWire(value: String?): PlaybackState =
            values().firstOrNull { it.name.equals(value, ignoreCase = true) } ?: UNKNOWN
    }
}

data class ReceiverStatus(
    val state: PlaybackState = PlaybackState.UNKNOWN,
    val position: Double = 0.0,
    /** Seconds; -1 for live/unknown. */
    val duration: Double = -1.0,
    val volume: Float = 1f,
    val muted: Boolean = false,
    val title: String? = null,
    val error: String? = null,
)
