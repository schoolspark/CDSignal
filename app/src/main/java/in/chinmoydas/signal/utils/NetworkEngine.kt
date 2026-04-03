package `in`.chinmoydas.signal.utils

import android.util.Log
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CD Signal - Reflector-Optimized Network Engine
 * Architecture: Hybrid LAN P2P + 0-RTT Fast Relay (Port 443 QUIC Disguise)
 */
class NetworkEngine(
    private val port: Int
) {
    private val tag = "NetworkEngine"
    private var socket: DatagramSocket? = null
    private val isRunning = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Routing Callbacks
    var onSignalPacket: ((ByteArray, String) -> Unit)? = null
    var onVoipPacket: ((ByteArray, String) -> Unit)? = null

    companion object {
        const val TYPE_TEXT_SIGNAL: Byte = 0x10
        const val TYPE_PTT_AUDIO: Byte = 0x11
        const val TYPE_VOIP_CALL: Byte = 0x12
        const val TYPE_KEEP_ALIVE: Byte = 0x13

        private const val RELAY_SECRET = "8c2d1c7942f06aa6bcddb33134c09b73a532b4fda729affe351f36162d41aa2b"
    }

    private val ipCache = ConcurrentHashMap<String, InetAddress>()
    private val sendQueue = LinkedBlockingQueue<DatagramPacket>(1024)

    private var isPremium = false
    private var reflectorIp: String = "signal.schoolspark.in"
    private val reflectorPort = 443
    private var myUsername: String = ""

    fun setPremiumStatus(status: Boolean, ip: String, username: String) {
        this.isPremium = status
        this.myUsername = username
        if (ip.isNotEmpty()) this.reflectorIp = ip

        if (isPremium && myUsername.isNotEmpty() && isBound()) {
            registerWithMothership()
        }
    }

    fun getLocalPort(): Int = socket?.localPort ?: port

    fun registerWithMothership() {
        if (!isRunning.get() || myUsername.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            try {
                val pingStr = "PING:$myUsername"
                val buffer = pingStr.toByteArray(Charsets.UTF_8)
                val address = InetAddress.getByName(reflectorIp)
                val packet = DatagramPacket(buffer, buffer.size, address, reflectorPort)
                socket?.send(packet)
                Log.i(tag, "Reflector Auth: Registered $myUsername on Port 443")
            } catch (e: Exception) {
                Log.e(tag, "Reflector Registration Failed: ${e.message}")
            }
        }
    }

    fun start(): Boolean {
        if (isRunning.getAndSet(true)) return true
        try {
            val dualStackAddress = InetSocketAddress(InetAddress.getByName("::0"), port)
            socket = DatagramSocket(null).apply {
                reuseAddress = true
                receiveBufferSize = 2 * 1024 * 1024
                bind(dualStackAddress)
            }

            Log.i(tag, "Radio Engine Live: Port ${getLocalPort()}")

            // Receiver Thread
            Thread {
                val buffer = ByteArray(4096)
                while (isRunning.get()) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket?.receive(packet)

                        if (packet.length > 0) {
                            val data = packet.data.copyOf(packet.length)
                            val senderIp = packet.address.hostAddress ?: ""

                            val stringHeader = String(data, 0, minOf(4, data.size), Charsets.UTF_8)
                            if (stringHeader == "PONG") {
                                Log.v(tag, "Mothership Link Stable")
                                continue
                            }

                            handleMultiplexedPacket(data, senderIp)
                        }
                    } catch (e: Exception) {
                        if (isRunning.get()) Log.e(tag, "Receive Error: ${e.message}")
                    }
                }
            }.apply { priority = Thread.MAX_PRIORITY }.start()

            // Sender Thread
            Thread {
                while (isRunning.get()) {
                    try {
                        val packet = sendQueue.take()
                        socket?.send(packet)
                    } catch (e: Exception) { }
                }
            }.apply { priority = Thread.MAX_PRIORITY }.start()

            return true
        } catch (e: Exception) {
            isRunning.set(false)
            return false
        }
    }

    private fun handleMultiplexedPacket(data: ByteArray, senderIp: String) {
        if (data.isEmpty()) return
        val type = data[0]
        val payload = if (data.size > 1) data.copyOfRange(1, data.size) else ByteArray(0)

        when (type) {
            TYPE_TEXT_SIGNAL -> onSignalPacket?.invoke(payload, senderIp)
            TYPE_PTT_AUDIO -> onSignalPacket?.invoke(payload, senderIp)
            TYPE_VOIP_CALL -> onVoipPacket?.invoke(payload, senderIp)
            else -> onSignalPacket?.invoke(data, senderIp)
        }
    }

    fun send(type: Byte, data: ByteArray, targetIp: String, targetPort: Int, targetUsername: String) {
        if (!isRunning.get()) return
        val packetData = ByteArray(1 + data.size)
        packetData[0] = type
        System.arraycopy(data, 0, packetData, 1, data.size)
        scope.launch(Dispatchers.IO) { sendDirectly(packetData, targetIp, targetPort, targetUsername) }
    }

    fun sendRaw(data: ByteArray, ip: String, port: Int) {
        if (!isRunning.get()) return
        createPacket(data, ip, port)?.let { sendQueue.offer(it) }
    }

    fun sendBurst(type: Byte, data: ByteArray, targetIp: String, targetPort: Int, targetUsername: String, burstCount: Int) {
        if (!isRunning.get()) return
        val packetData = ByteArray(1 + data.size)
        packetData[0] = type
        System.arraycopy(data, 0, packetData, 1, data.size)
        scope.launch {
            repeat(burstCount) {
                sendDirectly(packetData, targetIp, targetPort, targetUsername)
                delay(15)
            }
        }
    }

    private fun sendDirectly(data: ByteArray, targetIp: String, targetPort: Int, targetUsername: String) {
        try {
            // 1. LAN Path
            createPacket(data, targetIp, targetPort)?.let { sendQueue.offer(it) }

            // 2. Premium Reflector Path
            if (isPremium && !isLocalOrBroadcast(targetIp) && targetUsername.isNotEmpty()) {
                val headerStr = "RELAY:$RELAY_SECRET:$targetUsername|"
                val headerBytes = headerStr.toByteArray(Charsets.UTF_8)
                val combined = ByteArray(headerBytes.size + data.size)
                System.arraycopy(headerBytes, 0, combined, 0, headerBytes.size)
                System.arraycopy(data, 0, combined, headerBytes.size, data.size)

                createPacket(combined, reflectorIp, reflectorPort)?.let { sendQueue.offer(it) }
            }
        } catch (e: Exception) { }
    }

    private fun isLocalOrBroadcast(ip: String): Boolean =
        ip.startsWith("192.168.") || ip.startsWith("10.") || ip == "255.255.255.255"

    private fun createPacket(data: ByteArray, ip: String, port: Int): DatagramPacket? {
        return try {
            val address = ipCache.getOrPut(ip) { InetAddress.getByName(ip) }
            DatagramPacket(data, data.size, address, port)
        } catch (e: Exception) { null }
    }

    fun stop() {
        isRunning.set(false)
        socket?.close()
        sendQueue.clear()
        ipCache.clear()
        scope.cancel()
    }

    fun isBound(): Boolean = isRunning.get() && socket != null && !socket!!.isClosed
}