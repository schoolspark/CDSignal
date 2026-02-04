package `in`.chinmoydas.signal.utils

import android.util.Log
import `in`.chinmoydas.signal.data.MainRepository
import kotlinx.coroutines.*

class ConnectionManager(
    private val repository: MainRepository,
    private val networkEngine: NetworkEngine
) {
    private val tag = "ConnectionManager"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var heartbeatJob: Job? = null
    private var stunJob: Job? = null

    @Volatile var isEcoMode = false
    @Volatile private var lastPublicIp: String = ""
    @Volatile private var lastPublicPort: Int = 0

    // [FIX] Store state to allow restarting
    private var activeChannel: String? = null
    private var activeKey: String? = null

    private val localPort = 50005

    // STUN Servers (Lightweight)
    private val stunServers = listOf(
        "stun.l.google.com" to 19302,
        "stun1.l.google.com" to 19302,
        "stun2.l.google.com" to 19302
    )

    fun updateEcoMode(enabled: Boolean) {
        isEcoMode = enabled
        // [FIX] Now calls the internal restart function
        restartHeartbeat()
    }

    // [FIX] Added missing function
    private fun restartHeartbeat() {
        stop()
        if (activeChannel != null || activeKey != null) {
            startHeartbeatLoop(activeChannel, activeKey)
        }
    }

    fun startHeartbeatLoop(channel: String?, key: String?) {
        heartbeatJob?.cancel()
        stunJob?.cancel()

        // Save for restart logic
        activeChannel = channel
        activeKey = key

        // 1. STUN Loop (Keep NAT Open)
        stunJob = scope.launch {
            while (isActive) {
                try {
                    val server = stunServers.random()
                    // [Requirement] StunClient.buildRequest() must exist
                    val request = StunClient.buildRequest()
                    networkEngine.send(request, listOf(server.first), server.second)
                } catch (e: Exception) { Log.e(tag, "STUN send failed", e) }
                delay(if (isEcoMode) 25000 else 15000)
            }
        }

        // 2. Heartbeat Loop (Tell Server where I am)
        heartbeatJob = scope.launch {
            while (isActive) {
                if (lastPublicPort == 0) delay(1000)

                sendHeartbeat(channel, key)
                delay(if (isEcoMode) 10000 else 2000)
            }
        }
    }

    fun handleStunResponse(data: ByteArray) {
        try {
            // [FIX] Safe Call ?.let to handle nullable result
            val result = StunClient.parseResponse(data)
            if (result != null) {
                val (ip, port) = result
                if (ip.isNotEmpty() && port > 0) {
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
        val ipToSend = if (lastPublicIp.isNotEmpty()) lastPublicIp else "0.0.0.0"

        try {
            repository.sendHeartbeat(token, portToSend, getLocalIp(), channel, key)
        } catch (e: Exception) { Log.e(tag, "Heartbeat Failed", e) }
    }

    fun triggerImmediateHeartbeat() {
        scope.launch { sendHeartbeat(null, null) }
    }

    fun stop() {
        heartbeatJob?.cancel()
        stunJob?.cancel()
    }

    private fun getLocalIp(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: ""
                    }
                }
            }
        } catch (e: Exception) { }
        return "127.0.0.1"
    }
}