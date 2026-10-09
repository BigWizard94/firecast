package info.bigwizardmedia.firecast.discovery

import info.bigwizardmedia.firecast.protocol.FirecastProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal DIAL 2.x client: read receiver state and launch the FireCast app
 * with a payload. See PROTOCOL.md §1a.
 */
object DialClient {

    /** GETs the DIAL device description and returns the Application-URL base. */
    suspend fun getApplicationUrl(descriptionUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(descriptionUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            conn.connect()
            // DIAL puts the app base URL in a response header…
            conn.getHeaderField("Application-URL")?.trim()?.takeIf { it.isNotEmpty() }
                // …or it can be derived from the description URL per the DIAL spec.
                ?: descriptionUrl.substringBeforeLast("/") + "/apps"
        } catch (_: Exception) {
            null
        }
    }

    /** GETs {appUrl}/FireCast and returns the <state> element (running|stopped|…). */
    suspend fun getAppState(appUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            val conn = URL("$appUrl/${FirecastProtocol.DIAL_APP_NAME}").openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "text/xml")
            conn.connect()
            if (conn.responseCode !in 200..299) return@withContext null
            val body = conn.inputStream.bufferedReader().readText()
            Regex("<state>([^<]+)</state>").find(body)?.groupValues?.get(1)?.trim()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * POSTs to {appUrl}/FireCast to launch the receiver. The optional payload
     * is passed as text/plain (e.g. the sender's WebSocket URL or a pairing token).
     */
    suspend fun launch(appUrl: String, payload: String? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            val conn = URL("$appUrl/${FirecastProtocol.DIAL_APP_NAME}").openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            if (payload != null) {
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload) }
            } else {
                conn.outputStream.close()
            }
            conn.responseCode in 200..299
        } catch (_: Exception) {
            false
        }
    }

    /** DELETEs {appUrl}/FireCast/run to stop a running receiver. */
    suspend fun stop(appUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val conn = URL("$appUrl/${FirecastProtocol.DIAL_APP_NAME}/run").openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "DELETE"
            conn.responseCode in 200..299
        } catch (_: Exception) {
            false
        }
    }
}
