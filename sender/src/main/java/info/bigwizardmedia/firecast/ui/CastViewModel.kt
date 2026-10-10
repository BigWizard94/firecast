package info.bigwizardmedia.firecast.ui

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import info.bigwizardmedia.firecast.ServiceLocator
import info.bigwizardmedia.firecast.discovery.DialClient
import info.bigwizardmedia.firecast.discovery.DiscoveryManager
import info.bigwizardmedia.firecast.discovery.DiscoverySource
import info.bigwizardmedia.firecast.discovery.ReceiverDevice
import info.bigwizardmedia.firecast.protocol.FirecastSession
import info.bigwizardmedia.firecast.protocol.PlaybackState
import info.bigwizardmedia.firecast.protocol.ReceiverStatus
import info.bigwizardmedia.firecast.protocol.WsConnection
import info.bigwizardmedia.firecast.server.CastControlServer
import info.bigwizardmedia.firecast.server.MediaServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** Media waiting to be cast: either a remote URL or a local content Uri. */
sealed interface PendingMedia {
    data class RemoteUrl(val url: String, val title: String?) : PendingMedia
    data class LocalFile(val uri: Uri, val title: String?, val mime: String?) : PendingMedia
}

class CastViewModel(app: Application) : AndroidViewModel(app) {

    private val discovery: DiscoveryManager = ServiceLocator.discovery
    private val mediaServer get() = ServiceLocator.mediaServer
    private val controlServer get() = ServiceLocator.controlServer

    private val _hosting = MutableStateFlow(false)
    val hosting: StateFlow<Boolean> = _hosting.asStateFlow()

    /** LAN URL of the TV receiver page, e.g. http://192.168.1.5:8080/ */
    private val _receiverUrl = MutableStateFlow<String?>(null)
    val receiverUrl: StateFlow<String?> = _receiverUrl.asStateFlow()

    private val _devices = MutableStateFlow<List<ReceiverDevice>>(emptyList())
    val devices: StateFlow<List<ReceiverDevice>> = _devices.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _connectedDevice = MutableStateFlow<ReceiverDevice?>(null)
    val connectedDevice: StateFlow<ReceiverDevice?> = _connectedDevice.asStateFlow()

    private val _connectionState =
        MutableStateFlow(FirecastSession.ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<FirecastSession.ConnectionState> = _connectionState.asStateFlow()

    private val _status = MutableStateFlow(ReceiverStatus())
    val status: StateFlow<ReceiverStatus> = _status.asStateFlow()

    private val _pinRequired = MutableStateFlow(false)
    val pinRequired: StateFlow<Boolean> = _pinRequired.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** Media received via share-sheet / open-with, played right after connecting. */
    var pendingMedia: PendingMedia? = null

    private var session: FirecastSession? = null
    private var connectingDevice: ReceiverDevice? = null
    private var sessionJob: Job? = null
    private var scanJob: Job? = null
    private var serverStarted = false

    // Web-receiver hosting (server mode): the TV dials in to us.
    private var hostSession: FirecastSession? = null
    private val hostChannels = mutableMapOf<WsConnection, FirecastSession>()

    // ---------- discovery ----------

    fun startScan() {
        if (_scanning.value) return
        _scanning.value = true
        _devices.value = emptyList()
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            // mDNS stream
            val mdnsJob = launch {
                discovery.discoverViaMdns()
                    .catch { /* mDNS unavailable — SSDP still runs */ }
                    .collect { addDevice(it) }
            }
            // SSDP/DIAL round (one-shot)
            launch {
                runCatching { discovery.discoverViaDial() }
                    .getOrDefault(emptyList())
                    .forEach { addDevice(it) }
            }
            // Bound the scan window; mDNS keeps trickling until cancelled.
            delay(12_000)
            mdnsJob.cancel()
            _scanning.value = false
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        _scanning.value = false
    }

    fun addManualDevice(host: String, port: String) {
        val device = discovery.manualDevice(host, port)
        if (device == null) {
            _notice.value = "Enter a valid IP address or hostname."
        } else {
            addDevice(device)
            connect(device)
        }
    }

    private fun addDevice(device: ReceiverDevice) {
        _devices.update { current ->
            if (current.any { it.id == device.id }) current else current + device
        }
    }

    // ---------- web-receiver hosting (server mode) ----------

    /**
     * Starts the LAN servers the TV's browser receiver needs:
     * media + receiver page over HTTP (8080) and the control channel
     * WebSocket server (8081). Idempotent.
     */
    fun startHosting() {
        if (_hosting.value) return
        viewModelScope.launch { ensureHosting() }
    }

    /** Starts hosting if needed; returns true when both servers are up. */
    private suspend fun ensureHosting(): Boolean {
        if (_hosting.value) return true
        return try {
            val html = getApplication<Application>().assets
                .open("receiver/index.html").bufferedReader().use { it.readText() }
            mediaServer.receiverPageHtml = html
            if (!ensureServer()) return false
            controlServer.onOpen = { conn -> onReceiverOpen(conn) }
            controlServer.onMessage = { conn, text -> onReceiverMessage(conn, text) }
            controlServer.onClose = { conn -> onReceiverClose(conn) }
            controlServer.listen()
            _receiverUrl.value = mediaServer.receiverPageUrl()
            _hosting.value = true
            true
        } catch (e: Exception) {
            _notice.value = "Could not start TV receiver hosting: ${e.message}"
            false
        }
    }

    /** Device name to use for the next dial-in (set when DIAL-launching). */
    private var pendingDialName: String? = null

    private fun onReceiverOpen(conn: WsConnection) {
        // Single-receiver v1: the newest TV wins.
        hostSession?.close()
        val s = FirecastSession(viewModelScope)
        hostSession = s
        hostChannels[conn] = s
        s.accept(conn, ServiceLocator.fingerprint())
        val name = pendingDialName ?: "TV Receiver"
        pendingDialName = null
        beginProtocol(s, displayName = name, device = null)
    }

    private fun onReceiverMessage(conn: WsConnection, text: String) {
        hostChannels[conn]?.onText(text)
    }

    private fun onReceiverClose(conn: WsConnection) {
        val s = hostChannels.remove(conn) ?: return
        if (hostSession === s) {
            hostSession = null
            if (session === s) disconnect()
        } else {
            s.close()
        }
    }

    // ---------- session ----------

    fun connect(device: ReceiverDevice) {
        if (_connectedDevice.value?.id == device.id &&
            _connectionState.value == FirecastSession.ConnectionState.CONNECTED
        ) return
        disconnect()
        connectingDevice = device
        viewModelScope.launch {
            // Native receiver via DIAL: it dials in to our control server.
            // Ensure hosting is up, then launch it with our WS URL as payload.
            device.dialAppUrl?.let { appUrl ->
                if (!ensureHosting()) {
                    _notice.value = "Could not start receiver hosting."
                    return@launch
                }
                pendingDialName = device.name
                val payload = "{\"ws_url\":\"${controlServer.wsUrl()}\"}"
                // (Re-)launch to deliver the payload; the receiver connects back.
                if (!DialClient.launch(appUrl, payload)) {
                    _notice.value = "Could not launch ${device.name}."
                    pendingDialName = null
                    return@launch
                }
                _notice.value = "Launched ${device.name} — waiting for it to connect…"
                // The session begins when onReceiverOpen fires.
                return@launch
            }
            // Manual/mDNS device: classic dial-out to the receiver's WS.
            val s = FirecastSession(viewModelScope)
            s.connectTo(device.wsUrl, ServiceLocator.fingerprint())
            beginProtocol(s, displayName = device.name, device = device)
        }
    }

    /**
     * Shared post-connect flow for both modes: wire state collectors, say
     * hello, and either ask for the TV's PIN or mark the receiver paired.
     */
    private fun beginProtocol(
        s: FirecastSession,
        displayName: String,
        device: ReceiverDevice?,
    ) {
        session = s
        sessionJob?.cancel()
        sessionJob = viewModelScope.launch {
            s.connectionState.collect { _connectionState.value = it }
        }
        viewModelScope.launch {
            s.status.collect { _status.value = it }
        }
        viewModelScope.launch {
            s.lastError.collect { it?.let { err -> _notice.value = err } }
        }
        viewModelScope.launch {
            // Wait for the channel, then say hello.
            withTimeoutOrNull(10_000) {
                while (s.connectionState.value == FirecastSession.ConnectionState.CONNECTING) delay(200)
            }
            if (s.connectionState.value != FirecastSession.ConnectionState.CONNECTED) {
                _notice.value = "Could not reach $displayName."
                return@launch
            }
            val hello = s.hello()
            val paired = hello?.get("paired")
                ?.let { (it as? JsonPrimitive)?.booleanOrNull }
                ?: true
            if (!paired) {
                _pinRequired.value = true // TV is showing the PIN; ask the user.
            } else {
                onPaired(device, displayName)
            }
        }
    }

    fun submitPin(pin: String) {
        val s = session ?: return
        viewModelScope.launch {
            if (s.pair(pin.trim())) {
                _pinRequired.value = false
                val dev = connectingDevice
                onPaired(dev, dev?.name ?: "TV Receiver")
            } else {
                _notice.value = "Wrong PIN — check the TV screen and try again."
            }
        }
    }

    private fun onPaired(device: ReceiverDevice?, displayName: String) {
        connectingDevice = null
        // Web receiver dialed in to us: synthesize a device entry for the UI.
        _connectedDevice.value = device ?: ReceiverDevice(
            name = displayName,
            host = MediaServer.lanIp(),
            wsPort = controlServer.port,
            source = DiscoverySource.MANUAL,
        )
        viewModelScope.launch {
            session?.refreshStatus()
            // Auto-play anything that arrived via share-sheet while pairing.
            pendingMedia?.let { playPending(it) }
        }
    }

    fun disconnect() {
        sessionJob?.cancel()
        session?.close()
        session = null
        hostSession = null
        hostChannels.clear()
        connectingDevice = null
        _connectedDevice.value = null
        _pinRequired.value = false
        _status.value = ReceiverStatus()
        _connectionState.value = FirecastSession.ConnectionState.DISCONNECTED
    }

    fun clearNotice() {
        _notice.value = null
    }

    // ---------- playback ----------

    fun playRemoteUrl(url: String, title: String? = null) {
        val s = session ?: run {
            pendingMedia = PendingMedia.RemoteUrl(url, title)
            _notice.value = "Pick a receiver first — the link will play on connect."
            return
        }
        viewModelScope.launch {
            val mime = guessMime(url)
            if (!s.play(url, title ?: url.substringAfterLast('/'), mime)) {
                _notice.value = "The receiver refused to play that URL."
            }
        }
    }

    fun playLocalUri(uri: Uri) {
        viewModelScope.launch {
            val s = session
            if (s == null) {
                pendingMedia = PendingMedia.LocalFile(uri, displayName(uri), contentType(uri))
                _notice.value = "Pick a receiver first — the file will play on connect."
                return@launch
            }
            playPending(PendingMedia.LocalFile(uri, displayName(uri), contentType(uri)))
        }
    }

    private suspend fun playPending(media: PendingMedia) {
        pendingMedia = null
        when (media) {
            is PendingMedia.RemoteUrl -> playRemoteUrl(media.url, media.title)
            is PendingMedia.LocalFile -> {
                val s = session ?: return
                ensureServer()
                val file = copyToCache(media.uri, media.title) ?: run {
                    _notice.value = "Could not read that file."
                    return
                }
                val token = mediaServer.share(file)
                val url = mediaServer.mediaUrl(token)
                val ok = s.play(url, media.title ?: file.name, media.mime ?: guessMime(file.name))
                if (!ok) _notice.value = "The receiver refused to play that file."
            }
        }
    }

    fun togglePlayPause() {
        val s = session ?: return
        viewModelScope.launch {
            val playing = _status.value.state == PlaybackState.PLAYING
            if (playing) s.pause() else s.resume()
        }
    }

    fun seekTo(position: Double) {
        val s = session ?: return
        viewModelScope.launch { s.seek(position) }
    }

    fun setVolume(level: Float) {
        val s = session ?: return
        viewModelScope.launch { s.setVolume(level) }
    }

    fun toggleMute() {
        val s = session ?: return
        viewModelScope.launch { s.setMuted(!_status.value.muted) }
    }

    fun stop() {
        val s = session ?: return
        viewModelScope.launch { s.stop() }
    }

    // ---------- local plumbing ----------

    private fun ensureServer(): Boolean {
        if (serverStarted) return true
        return try {
            mediaServer.start()
            serverStarted = true
            true
        } catch (e: Exception) {
            _notice.value = "Could not start the local media server: ${e.message}"
            false
        }
    }

    private fun copyToCache(uri: Uri, title: String?): File? {
        return try {
            val name = title?.takeIf { it.isNotBlank() } ?: "shared_${System.currentTimeMillis()}.mp4"
            val out = File(getApplication<Application>().cacheDir, "cast").apply { mkdirs() }
                .resolve(sanitize(name))
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { input.copyTo(it) }
            } ?: return null
            out
        } catch (_: Exception) {
            null
        }
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80)

    private fun displayName(uri: Uri): String? {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return uri.lastPathSegment
        return try {
            getApplication<Application>().contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0) else uri.lastPathSegment
            }
        } catch (_: Exception) {
            uri.lastPathSegment
        }
    }

    private fun contentType(uri: Uri): String? = try {
        getApplication<Application>().contentResolver.getType(uri)
    } catch (_: Exception) {
        null
    }

    private fun guessMime(name: String): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            else -> null
        }
    }

    override fun onCleared() {
        stopScan()
        disconnect()
        if (_hosting.value) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    controlServer.stop()
                } catch (_: Exception) {
                }
                try {
                    mediaServer.stop()
                } catch (_: Exception) {
                }
            }
        }
        super.onCleared()
    }
}
