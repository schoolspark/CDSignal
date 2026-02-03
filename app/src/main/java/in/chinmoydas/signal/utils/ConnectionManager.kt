package `in`.chinmoydas.signal.utils

import android.util.Log
import `in`.chinmoydas.signal.data.MainRepository
import kotlinx.coroutines.*
import java.net.Inet4Address
import java.net.NetworkInterface

class ConnectionManager(
    private val repository: MainRepository,
    private val networkEngine: NetworkEngine
) {
    private val tag = "ConnectionManager"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var heartbeatJob: Job? = null

    // [MISSION CRITICAL] Redundant STUN Servers
    private val STUN_SERVERS = listOf(
        Pair("stun.l.google.com", 19302),
        Pair("stun.services.mozilla.com", 3478)
    )

    @Volatile private var identifiedPublicPort: Int = 50005
    @Volatile private var pendingHeartbeatTrigger = false
    @Volatile var isEcoMode: Boolean = false // [NEW] Eco Mode Toggle

    private var activeChannel: String? = null
    private var activeKey: String? = null

    fun updateEcoMode(enabled: Boolean) {
        isEcoMode = enabled
        if (enabled) {
            heartbeatJob?.cancel()
            Log.d(tag, "Eco Mode ON: Heartbeat Stopped")
        } else {
            triggerImmediateHeartbeat()
        }
    }

    fun triggerImmediateHeartbeat() {
        startHeartbeatLoop(activeChannel, activeKey, immediate = true)
    }

    fun startHeartbeatLoop(currentChannel: String?, channelKey: String?, immediate: Boolean = true) {
        activeChannel = currentChannel
        activeKey = channelKey
        heartbeatJob?.cancel()

        // If Eco Mode is ON, we do not run the loop. We only ping if 'immediate' is requested.
        if (isEcoMode && !immediate) return

        heartbeatJob = scope.launch {
            if (immediate) performHeartbeatSequence()

            // In Eco Mode, we stop here. No background looping.
            if (isEcoMode) return@launch

            while (isActive) {
                delay(45_000) // 45s Interval
                performHeartbeatSequence()
            }
        }
    }

    private suspend fun performHeartbeatSequence() {
        pendingHeartbeatTrigger = true
        STUN_SERVERS.forEach { server ->
            val stunReq = StunClient.createBindRequest(server.first, server.second)
            if (stunReq != null) networkEngine.sendRawPacket(stunReq)
            delay(50)
        }
        delay(500)
        if (pendingHeartbeatTrigger) {
            // If STUN failed, send with last known port
            sendHeartbeatToServer()
            pendingHeartbeatTrigger = false
        }
    }

    fun handleStunResponse(data: ByteArray) {
        val result = StunClient.parseResponse(data)
        if (result != null) {
            if (identifiedPublicPort != result.publicPort) {
                Log.i(tag, "NAT MAPPING CHANGED: Old=$identifiedPublicPort -> New=${result.publicPort}")
                identifiedPublicPort = result.publicPort
            }
            if (pendingHeartbeatTrigger) {
                pendingHeartbeatTrigger = false
                scope.launch { sendHeartbeatToServer() }
            }
        }
    }

    private suspend fun sendHeartbeatToServer() {
        try {
            val token = repository.getToken()
            if (!token.isNullOrBlank() && token != "OFFLINE_TOKEN") {
                val localIp = getLocalIpAddress()
                repository.sendHeartbeat(token, identifiedPublicPort, localIp, activeChannel, activeKey)
            }
        } catch (e: Exception) { }
    }

    fun stop() {
        heartbeatJob?.cancel()
        scope.cancel()
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: ""
                    }
                }
            }
        } catch (ex: Exception) { }
        return ""
    }
}