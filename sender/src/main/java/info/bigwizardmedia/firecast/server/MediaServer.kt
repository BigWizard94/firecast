package info.bigwizardmedia.firecast.server

import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Embedded LAN HTTP server implementing PROTOCOL.md §7.
 *
 * Local files are shared at `http://<phone-lan-ip>:<port>/media/<token>`,
 * where the token is a random 128-bit hex string valid for the session.
 * Range requests are honored so receivers can seek (206 Partial Content).
 */
class MediaServer(port: Int = 8080) : NanoHTTPD(port) {

    private val files = ConcurrentHashMap<String, File>()
    private val random = SecureRandom()

    /** Registers [file] and returns its one-time token. */
    fun share(file: File): String {
        val token = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        files[token] = file
        return token
    }

    fun unshare(token: String) {
        files.remove(token)
    }

    fun clear() = files.clear()

    /** Full media URL for a token, using this device's LAN IP. */
    fun mediaUrl(token: String): String = "http://${lanIp()}:$listeningPort/media/$token"

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        if (!uri.startsWith("/media/")) {
            return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "not found")
        }
        val token = uri.removePrefix("/media/").substringBefore("/").substringBefore("?")
        val file = files[token]
        if (file == null || !file.isFile || !file.canRead()) {
            return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "not found")
        }
        val mime = mimeTypeFor(file)
        val length = file.length()

        val range = session.headers["range"]
        if (range != null && range.startsWith("bytes=")) {
            val parsed = parseRange(range.removePrefix("bytes="), length)
            if (parsed != null) {
                val (start, end) = parsed
                val count = end - start + 1
                val stream = FileInputStream(file).apply { skip(start) }
                val res = newFixedLengthResponse(Status.PARTIAL_CONTENT, mime, stream, count)
                res.addHeader("Content-Range", "bytes $start-$end/$length")
                res.addHeader("Accept-Ranges", "bytes")
                res.addHeader("Content-Length", count.toString())
                return res
            }
            val res = newFixedLengthResponse(Status.REQUESTED_RANGE_NOT_SATISFIABLE, "text/plain", "")
            res.addHeader("Content-Range", "bytes */$length")
            return res
        }

        val res = newChunkedResponse(Status.OK, mime, FileInputStream(file))
        res.addHeader("Accept-Ranges", "bytes")
        res.addHeader("Content-Length", length.toString())
        return res
    }

    private fun parseRange(spec: String, length: Long): Pair<Long, Long>? {
        // "500-999", "500-", "-500"
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val startStr = spec.substring(0, dash).trim()
        val endStr = spec.substring(dash + 1).trim()
        return try {
            when {
                startStr.isEmpty() -> { // suffix: last N bytes
                    val n = endStr.toLong().coerceAtMost(length)
                    if (n <= 0) return null
                    (length - n) to (length - 1)
                }
                else -> {
                    val start = startStr.toLong().coerceAtLeast(0)
                    val end = if (endStr.isEmpty()) length - 1 else endStr.toLong().coerceAtMost(length - 1)
                    if (start > end || start >= length) return null
                    start to end
                }
            }
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun mimeTypeFor(file: File): String = when (file.extension.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "3gp" -> "video/3gpp"
        "ts", "m2ts" -> "video/mp2t"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "ogg", "oga" -> "audio/ogg"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        else -> "application/octet-stream"
    }

    companion object {
        /** Best-guess LAN IPv4 address (prefers site-local, skips loopback). */
        fun lanIp(): String {
            val candidates = mutableListOf<String>()
            try {
                val ifaces = NetworkInterface.getNetworkInterfaces()
                while (ifaces.hasMoreElements()) {
                    val iface = ifaces.nextElement()
                    if (!iface.isUp || iface.isLoopback) continue
                    val addrs = iface.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (addr.isLoopbackAddress || addr is java.net.Inet6Address) continue
                        val ip = addr.hostAddress ?: continue
                        if (addr.isSiteLocalAddress) return ip // 192.168.x.x etc.
                        candidates += ip
                    }
                }
            } catch (_: Exception) {
            }
            return candidates.firstOrNull() ?: "127.0.0.1"
        }
    }
}
