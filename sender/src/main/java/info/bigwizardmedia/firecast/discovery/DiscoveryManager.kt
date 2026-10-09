package info.bigwizardmedia.firecast.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import info.bigwizardmedia.firecast.protocol.FirecastProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Finds FireCast receivers on the LAN via three mechanisms (PROTOCOL.md §1):
 * 1. SSDP M-SEARCH for DIAL, then resolve the DIAL device description.
 * 2. mDNS for _firecast._tcp (receivers without DIAL).
 * 3. Manual IP/hostname entry (handled by the caller via [manualDevice]).
 */
class DiscoveryManager(private val context: Context) {

    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val nsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager

    // ---------- SSDP / DIAL ----------

    /**
     * Sends one SSDP M-SEARCH round and returns parsed LOCATION URLs of
     * devices advertising the DIAL service.
     */
    suspend fun ssdpSearch(timeoutMs: Int = 4000): List<String> = withContext(Dispatchers.IO) {
        val lock = wifiManager.createMulticastLock("firecast-ssdp").apply {
            setReferenceCounted(true)
            acquire()
        }
        val found = linkedSetOf<String>()
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 1000
                val group = InetAddress.getByName("239.255.255.250")
                val query = buildString {
                    append("M-SEARCH * HTTP/1.1\r\n")
                    append("HOST: 239.255.255.250:1900\r\n")
                    append("MAN: \"ns=01\"; ns=01\r\n")
                    append("ST: urn:dial-multiscreen-org:service:dial:1\r\n")
                    append("MX: 3\r\n")
                    append("\r\n")
                }
                val bytes = query.toByteArray(Charsets.UTF_8)
                socket.send(DatagramPacket(bytes, bytes.size, group, 1900))

                val deadline = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        socket.receive(packet)
                        val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        parseHeader(text, "LOCATION")?.trim()?.takeIf { it.isNotEmpty() }?.let(found::add)
                    } catch (_: java.net.SocketTimeoutException) {
                        // keep listening until the deadline
                    }
                }
            }
        } catch (_: Exception) {
            // No multicast / no wifi — caller treats as empty.
        } finally {
            try {
                lock.release()
            } catch (_: Exception) {
            }
        }
        found.toList()
    }

    private fun parseHeader(response: String, name: String): String? =
        response.lineSequence()
            .firstOrNull { it.trim().startsWith("$name:", ignoreCase = true) }
            ?.substringAfter(":")?.trim()

    /**
     * Full DIAL discovery: SSDP → device description → Application-URL.
     * Returns one [ReceiverDevice] per responding DIAL device.
     */
    suspend fun discoverViaDial(): List<ReceiverDevice> {
        val devices = mutableListOf<ReceiverDevice>()
        for (location in ssdpSearch()) {
            val appUrl = DialClient.getApplicationUrl(location) ?: continue
            val host = try {
                java.net.URL(location).host
            } catch (_: Exception) {
                continue
            }
            // Friendly name from the description XML when available.
            val name = fetchFriendlyName(location) ?: host
            devices += ReceiverDevice(
                name = name,
                host = host,
                dialAppUrl = appUrl,
                source = DiscoverySource.DIAL,
            )
        }
        return devices
    }

    private suspend fun fetchFriendlyName(descriptionUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            val conn = java.net.URL(descriptionUrl).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val body = conn.inputStream.bufferedReader().readText()
            Regex("<friendlyName>([^<]+)</friendlyName>").find(body)?.groupValues?.get(1)?.trim()
        } catch (_: Exception) {
            null
        }
    }

    // ---------- mDNS ----------

    /**
     * Discovers `_firecast._tcp` services; emits each resolved receiver.
     * The returned Flow completes when the caller cancels collection.
     */
    fun discoverViaMdns(): Flow<ReceiverDevice> = callbackFlow {
        val resolved = mutableSetOf<String>()
        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = close()
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(service: NsdServiceInfo) {
                nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = info.host?.hostAddress ?: return
                        val key = "$host:${info.port}"
                        if (!resolved.add(key)) return
                        val attrs = info.attributes
                        val name = attrs["name"]?.let { String(it, Charsets.UTF_8) }
                            ?: info.serviceName
                        val announced = attrs["v"]?.let { String(it, Charsets.UTF_8) }?.toIntOrNull()
                        if (announced != null && announced != FirecastProtocol.VERSION) return
                        trySend(
                            ReceiverDevice(
                                name = name,
                                host = host,
                                wsPort = info.port,
                                source = DiscoverySource.MDNS,
                            )
                        )
                    }
                })
            }

            override fun onServiceLost(service: NsdServiceInfo) = Unit
        }
        try {
            nsdManager.discoverServices(
                "${FirecastProtocol.MDNS_SERVICE_TYPE}.local.",
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener,
            )
        } catch (_: Exception) {
            close()
        }
        awaitClose {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener)
            } catch (_: Exception) {
            }
        }
    }

    // ---------- Manual ----------

    /** Builds a device from user-typed host/IP and optional port. */
    fun manualDevice(hostInput: String, portInput: String): ReceiverDevice? {
        val host = hostInput.trim().removePrefix("ws://").removePrefix("http://")
            .substringBefore("/").substringBefore(":")
            .takeIf { it.isNotEmpty() } ?: return null
        val port = portInput.trim().toIntOrNull() ?: 8090
        return ReceiverDevice(
            name = host,
            host = host,
            wsPort = port,
            source = DiscoverySource.MANUAL,
        )
    }
}
