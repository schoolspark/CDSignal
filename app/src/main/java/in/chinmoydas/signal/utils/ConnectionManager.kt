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
    // If Google is blocked (e.g. some firewalls), fall back to Mozilla
    private val STUN_SERVERS = listOf(
        Pair("stun.l.google.com", 19302),
        Pair("stun.services.mozilla.com", 3478)
    )

    // Volatile ensures all threads see the latest port immediately
    @Volatile private var identifiedPublicPort: Int = 50005

    // State persistence for restarts
    private var activeChannel: String? = null
    private var activeKey: String? = null

    // [REQUIRED] Called by VoiceService via Intent "CMD_HEARTBEAT"
    fun triggerImmediateHeartbeat() {
        Log.d(tag, "Forcing immediate heartbeat...")
        // Restarting the loop triggers an immediate ping at the start of the block
        startHeartbeatLoop(activeChannel, activeKey)
    }

    fun startHeartbeatLoop(currentChannel: String?, channelKey: String?) {
        // Update state
        activeChannel = currentChannel
        activeKey = channelKey

        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            var stunIndex = 0

            while (isActive) {
                // 1. Send STUN Request (Round Robin)
                val server = STUN_SERVERS[stunIndex % STUN_SERVERS.size]
                val stunReq = StunClient.createBindRequest(server.first, server.second)

                if (stunReq != null) {
                    networkEngine.sendRawPacket(stunReq)
                }

                // Wait 1.5s for STUN response to update 'identifiedPublicPort'
                delay(1500)

                // 2. Send Heartbeat to PHP with the CORRECT Port
                try {
                    val token = repository.getToken()
                    if (!token.isNullOrBlank() && token != "OFFLINE_TOKEN") {
                        val localIp = getLocalIpAddress()

                        repository.sendHeartbeat(
                            token = token,
                            port = identifiedPublicPort,
                            localIp = localIp,
                            channel = activeChannel,
                            key = activeKey
                        )
                        Log.d(tag, "Heartbeat sent. Port: $identifiedPublicPort via ${server.first}")
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Heartbeat failed: ${e.message}")
                    // If failed, switch STUN server for next time
                    stunIndex++
                }

                // 3. Robust Interval: 50 Seconds
                // Keep-Alive usually expires at 60s, so 50s is safe and battery efficient.
                delay(50_000)
            }
        }
    }

    // Called by NetworkEngine when a STUN packet arrives
    fun handleStunResponse(data: ByteArray) {
        val result = StunClient.parseResponse(data)
        if (result != null) {
            if (identifiedPublicPort != result.publicPort) {
                Log.i(tag, "NAT MAPPING CHANGED: Old=$identifiedPublicPort -> New=${result.publicPort}")
                identifiedPublicPort = result.publicPort
            }
        }
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