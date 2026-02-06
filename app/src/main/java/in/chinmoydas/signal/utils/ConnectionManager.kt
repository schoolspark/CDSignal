package `in`.chinmoydas.signal.utils

import android.util.Log
import `in`.chinmoydas.signal.data.MainRepository
import kotlinx.coroutines.*
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface

class ConnectionManager(
    private val repository: MainRepository,
    private val networkEngine: NetworkEngine
) {
    private val tag = "ConnectionManager"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // [OPTIMIZATION] Single Job to rule them all
    private var maintenanceJob: Job? = null

    @Volatile var isEcoMode = false
    @Volatile private var lastPublicIp: String = ""
    @Volatile private var lastPublicPort: Int = 0

    // [NEW] Track Activity to prevent unnecessary pings
    @Volatile private var lastActivityTime = System.currentTimeMillis()
    private var lastHeartbeatTime = 0L

    // [STATE] Store connection details for auto-restart
    private var activeChannel: String? = null
    private var activeKey: String? = null
    private var activeTargetIp: String? = null

    private val localPort = 50005

    private val stunServers = listOf(
        "stun.l.google.com" to 19302,
        "stun1.l.google.com" to 19302
    )

    fun updateEcoMode(enabled: Boolean) {
        isEcoMode = enabled
        Log.d(tag, "Eco Mode: $enabled. Restarting Logic.")
        restartHeartbeat()
    }

    // [CRITICAL] Called by VoiceService when Audio/Data flows
    fun notifyNetworkActivity() {
        lastActivityTime = System.currentTimeMillis()
    }

    private fun restartHeartbeat() {
        stop()
        startHeartbeatLoop(activeChannel, activeKey, activeTargetIp)
    }

    fun startHeartbeatLoop(channel: String?, key: String?, targetIp: String? = null) {
        stop()
        activeChannel = channel
        activeKey = key
        activeTargetIp = targetIp

        Log.d(tag, "Starting Smart Maintenance Loop. Target: $targetIp")

        maintenanceJob = scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val timeSinceActivity = now - lastActivityTime

                // Eco: Allow 25s silence. Normal: Allow 15s silence.
                val silenceThreshold = if (isEcoMode) 25_000L else 15_000L

                if (timeSinceActivity > silenceThreshold) {
                    // 1. Keep Public Port Open (STUN)
                    try {
                        val server = stunServers.random()
                        val request = StunClient.buildRequest()
                        networkEngine.sendRaw(request, listOf(server.first), server.second)
                    } catch (e: Exception) {}

                    // 2. Keep P2P Tunnel Open (Target Ping)
                    if (!targetIp.isNullOrBlank() && targetIp != "SERVER_LINK") {
                        try {
                            networkEngine.send(
                                NetworkEngine.TYPE_KEEP_ALIVE,
                                ByteArray(0),
                                listOf(targetIp),
                                localPort
                            )
                        } catch (e: Exception) {}
                    }

                    // 3. Keep Server Updated (Heartbeat)
                    val heartbeatInterval = if (isEcoMode) 30_000L else 10_000L
                    if (now - lastHeartbeatTime > heartbeatInterval) {
                        sendHeartbeat(channel, key)
                        lastHeartbeatTime = now
                    }
                }

                delay(5000)
            }
        }
    }

    fun handleStunResponse(data: ByteArray) {
        try {
            val result = StunClient.parseResponse(data)
            if (result != null) {
                val (ip, port) = result
                if (ip.isNotEmpty() && port > 0) {
                    if (lastPublicPort != port) {
                        Log.i(tag, "NAT Port Changed: $lastPublicPort -> $port")
                        triggerImmediateHeartbeat()
                    }
                    lastPublicIp = ip
                    lastPublicPort = port
                }
            }
        } catch (e: Exception) { }
    }

    private suspend fun sendHeartbeat(channel: String?, key: String?) {
        val token = repository.getToken() ?: return
        if (token == "OFFLINE_TOKEN") return

        val portToSend = if (lastPublicPort > 0) lastPublicPort else localPort

        // Note: We don't forcefully send our local IP here if STUN works,
        // but the 'getLocalIp()' helper is used if the server needs it.

        try {
            repository.sendHeartbeat(token, portToSend, getLocalIp(), channel, key)
        } catch (e: Exception) { Log.e(tag, "Heartbeat Failed", e) }
    }

    fun triggerImmediateHeartbeat() {
        scope.launch { sendHeartbeat(activeChannel, activeKey) }
    }

    fun stop() {
        maintenanceJob?.cancel()
    }

    // [CRITICAL UPDATE] IPv6-Aware IP Detection
    private fun getLocalIp(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            var ipv4 = ""
            var ipv6 = ""

            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                // Skip Loopback and inactive interfaces
                if (iface.isLoopback || !iface.isUp) continue

                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()

                    if (!addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: ""
                        // 1. Check for IPv6 (ignore link-local fe80::)
                        if (addr is Inet6Address && !ip.startsWith("fe80")) {
                            // If we find a Global IPv6 address, this is gold for 4G networks
                            ipv6 = ip
                        }
                        // 2. Check for IPv4
                        else if (addr is Inet4Address) {
                            ipv4 = ip
                        }
                    }
                }
            }

            // Prefer IPv6 if available (crucial for Jio/Mobile), otherwise fall back to IPv4
            return if (ipv6.isNotEmpty()) ipv6 else ipv4.ifEmpty { "127.0.0.1" }

        } catch (e: Exception) { }
        return "127.0.0.1"
    }
}