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
import info.bigwizardmedia.firecast.discovery.ReceiverDevice
import info.bigwizardmedia.firecast.protocol.FirecastSession
import info.bigwizardmedia.firecast.protocol.PlaybackState
import info.bigwizardmedia.firecast.protocol.ReceiverStatus
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

    // ---------- session ----------

    fun connect(device: ReceiverDevice) {
        if (_connectedDevice.value?.id == device.id &&
            _connectionState.value == FirecastSession.ConnectionState.CONNECTED
        ) return
        disconnect()
        connectingDevice = device
        viewModelScope.launch {
            // If the receiver exposes DIAL, make sure the app is running first.
            device.dialAppUrl?.let { appUrl ->
                val state = DialClient.getAppState(appUrl)
                if (state != null && !state.equals("running", ignoreCase = true)) {
                    DialClient.launch(appUrl)
                    // Give the receiver a moment to boot its WebSocket.
                    withTimeoutOrNull(10_000) {
                        while (DialClient.getAppState(appUrl) != "running") delay(1000)
                    }
                }
            }
            val s = FirecastSession(device.wsUrl, this)
            session = s
            sessionJob?.cancel()
            sessionJob = launch {
                s.connectionState.collect { _connectionState.value = it }
            }
            launch {
                s.status.collect { _status.value = it }
            }
            launch {
                s.lastError.collect { it?.let { err -> _notice.value = err } }
            }
            s.connect(ServiceLocator.fingerprint())
            // Wait for the socket, then say hello.
            withTimeoutOrNull(10_000) {
                while (s.connectionState.value == FirecastSession.ConnectionState.CONNECTING) delay(200)
            }
            if (s.connectionState.value != FirecastSession.ConnectionState.CONNECTED) {
                _notice.value = "Could not reach ${device.name}."
                return@launch
            }
            val hello = s.hello()
            val paired = hello?.get("paired")
                ?.let { (it as? JsonPrimitive)?.booleanOrNull }
                ?: true
            if (!paired) {
                _pinRequired.value = true // TV is showing the PIN; ask the user.
            } else {
                onPaired(device)
            }
        }
    }

    fun submitPin(pin: String) {
        val s = session ?: return
        viewModelScope.launch {
            if (s.pair(pin.trim())) {
                _pinRequired.value = false
                connectingDevice?.let { onPaired(it) }
            } else {
                _notice.value = "Wrong PIN — check the TV screen and try again."
            }
        }
    }

    private fun onPaired(device: ReceiverDevice) {
        connectingDevice = null
        _connectedDevice.value = device
        viewModelScope.launch {
            session?.refreshStatus()
            // Auto-play anything that arrived via share-sheet while picking a device.
            pendingMedia?.let { playPending(it) }
        }
    }

    fun disconnect() {
        sessionJob?.cancel()
        session?.close()
        session = null
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

    private fun ensureServer() {
        if (!serverStarted) {
            try {
                mediaServer.start()
                serverStarted = true
            } catch (e: Exception) {
                _notice.value = "Could not start the local media server: ${e.message}"
            }
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
        super.onCleared()
    }
}
