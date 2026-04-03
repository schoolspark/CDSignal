package `in`.chinmoydas.signal.utils

import android.util.Log
import `in`.chinmoydas.signal.RetrofitClient
import kotlinx.coroutines.*
import `in`.chinmoydas.signal.data.MainRepository

/**
 * CD Signal - Reflector-Optimized Connection Manager
 * Mothership v2: Pure Reflector Path (No STUN/CoTURN)
 */
class ConnectionManager(
    private val repository: MainRepository,
    private val networkEngine: NetworkEngine
) {
    private val tag = "ConnectionManager"

    private var heartbeatJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile var isEcoMode = false
        private set

    @Volatile private var currentChannel: String? = null
    @Volatile private var currentKey: String? = null
    @Volatile private var targetIp: String? = null

    @Volatile private var lastActivityTime: Long = 0L

    fun updateEcoMode(enabled: Boolean) {
        if (isEcoMode != enabled) {
            isEcoMode = enabled
            Log.d(tag, "Eco Mode: $enabled. Adjusting Reflector Heartbeat.")
            startHeartbeatLoop(currentChannel, currentKey, targetIp)
        }
    }

    /**
     * Smart Maintenance Loop.
     * Keeps the UDP Reflector hole open and syncs presence with the API.
     */
    fun startHeartbeatLoop(channel: String? = null, key: String? = null, ip: String? = null) {
        currentChannel = channel
        currentKey = key
        targetIp = ip

        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            Log.d(tag, "Reflector Sync Active. Target: $targetIp")
            while (isActive) {
                try {
                    performMaintenanceCycle()
                } catch (e: Exception) {
                    Log.e(tag, "Maintenance cycle failed: ${e.message}")
                }

                // 25s for High Performance, 55s for Eco Mode.
                // Mobile NATs usually close UDP holes after 30-60 seconds.
                val delayMs = if (isEcoMode) 55_000L else 25_000L
                delay(delayMs)
            }
        }
    }

    fun triggerImmediateHeartbeat() {
        scope.launch { performMaintenanceCycle() }
    }

    fun notifyNetworkActivity() {
        lastActivityTime = System.currentTimeMillis()
    }

    private suspend fun performMaintenanceCycle() {
        // 1. Hole Punching: Keep the hole open on Port 443 (The Mothership)
        // This is 200% more reliable than STUN for your setup.
        networkEngine.registerWithMothership()

        // 2. Database Sync: Update heartbeat.php so others can find our LAN IP
        val token = repository.getToken()
        if (!token.isNullOrBlank() && token != "OFFLINE_TOKEN") {
            try {
                val currentPort = networkEngine.getLocalPort()
                val localIp = getLocalIpAddress()

                // If no packets moved in 2 mins, mark as away
                val status = if (System.currentTimeMillis() - lastActivityTime < 120_000) "online" else "away"

                RetrofitClient.api.sendHeartbeat(
                    "Bearer $token",
                    currentPort,
                    localIp,
                    currentChannel,
                    currentKey,
                    status
                )
            } catch (e: Exception) {
                Log.e(tag, "PHP API Sync Failed")
            }
        }

        // 3. Peer Keep-Alive: Only if on LAN (Optional)
        targetIp?.let { ip ->
            if (isLocal(ip)) {
                sendLanPing(ip)
            }
        }
    }

    private fun sendLanPing(ip: String) {
        val pingPacket = "__PING__".toByteArray()
        val buf = java.nio.ByteBuffer.allocate(5 + pingPacket.size)
            .put(pingPacket.size.toByte())
            .put(pingPacket)
            .putInt(0)
            .array()
        networkEngine.sendRaw(buf, ip, 50005)
    }

    private fun isLocal(ip: String): Boolean {
        return ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                val addrs = intf.inetAddresses
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: ""
                    }
                }
            }
        } catch (e: Exception) {}
        return ""
    }

    fun stop() {
        heartbeatJob?.cancel()
    }
}