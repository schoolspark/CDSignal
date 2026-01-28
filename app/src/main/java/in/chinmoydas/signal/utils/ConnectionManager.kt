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

    // Default to local port until STUN proves otherwise
    // Volatile ensures all threads see the latest port immediately
    @Volatile private var identifiedPublicPort: Int = 50005

    fun startHeartbeatLoop(currentChannel: String?, channelKey: String?) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                // 1. Send STUN Request to Google (Fire & Forget)
                // The response will come back via handleStunResponse() below
                val stunReq = StunClient.createBindRequest()
                if (stunReq != null) {
                    networkEngine.sendRawPacket(stunReq)
                }

                // Wait 1 sec to allow STUN response to update 'identifiedPublicPort'
                delay(1000)

                // 2. Send Heartbeat to PHP with the CORRECT Port
                try {
                    val token = repository.getToken()
                    if (!token.isNullOrBlank() && token != "OFFLINE_TOKEN") {
                        val localIp = getLocalIpAddress()

                        // Robustness: Log what we are telling the server
                        Log.d(tag, "Sending Heartbeat -> IP: $localIp, Port: $identifiedPublicPort")

                        repository.sendHeartbeat(
                            token = token,
                            port = identifiedPublicPort, // <--- The Fix: Using discovered port
                            localIp = localIp,
                            channel = currentChannel,
                            key = channelKey
                        )
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Heartbeat failed: ${e.message}")
                }

                // 3. Robust Interval: 50 Seconds
                // Safe zone: Less than Server Timeout (120s) but slow enough to avoid battery drain.
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
                // Optional: We could trigger an immediate heartbeat here,
                // but the next loop cycle will catch it in <50s.
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