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

class NetworkEngine(
    private val port: Int,
    private val onStunPacket: (ByteArray) -> Unit
) {
    private val tag = "NetworkEngine"
    private var socket: DatagramSocket? = null
    private val isRunning = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Routing Callbacks
    var onSignalPacket: ((ByteArray, String) -> Unit)? = null // For VoiceService (PTT/Text)
    var onVoipPacket: ((ByteArray, String) -> Unit)? = null   // For CallEngine (VoIP)

    companion object {
        const val TYPE_TEXT_SIGNAL: Byte = 0x10
        const val TYPE_PTT_AUDIO: Byte = 0x11
        const val TYPE_VOIP_CALL: Byte = 0x12
        const val TYPE_KEEP_ALIVE: Byte = 0x13
    }

    // DNS Cache to prevent "Stutter" during frequent lookups
    private val ipCache = ConcurrentHashMap<String, InetAddress>()

    // Outgoing Queue for the Sender Thread
    private val sendQueue = LinkedBlockingQueue<DatagramPacket>(256)

    fun start(): Boolean {
        if (isRunning.getAndSet(true)) return true

        try {
            // [CRITICAL CHANGE] Dual-Stack Socket Binding
            // "::0" is the IPv6 Wildcard address.
            // On Android, binding to this allows receiving BOTH IPv4 and IPv6 packets.
            val dualStackAddress = InetSocketAddress(InetAddress.getByName("::0"), port)

            socket = DatagramSocket(null).apply {
                reuseAddress = true
                receiveBufferSize = 1024 * 1024 // 1MB Buffer
                broadcast = true
                soTimeout = 0
                bind(dualStackAddress) // Explicit Bind
            }

            Log.i(tag, "NetworkEngine Started: Listening on Dual-Stack Port $port")

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

                            // Routing Logic
                            if (StunClient.isStunResponse(data)) {
                                onStunPacket(data)
                            } else {
                                handleMultiplexedPacket(data, senderIp)
                            }
                        }
                    } catch (e: Exception) {
                        if (isRunning.get()) Log.e(tag, "Receive Error: ${e.message}")
                    }
                }
            }.start()

            // Sender Thread
            Thread {
                while (isRunning.get()) {
                    try {
                        val packet = sendQueue.take()
                        socket?.send(packet)
                    } catch (e: Exception) { }
                }
            }.start()

            return true
        } catch (e: Exception) {
            Log.e(tag, "Bind Failed: ${e.message}")
            isRunning.set(false)
            return false
        }
    }

    private fun handleMultiplexedPacket(data: ByteArray, senderIp: String) {
        if (data.isEmpty()) return

        val type = data[0]
        // Strip header byte for processing
        val payload = if (data.size > 1) data.copyOfRange(1, data.size) else ByteArray(0)

        when (type) {
            TYPE_TEXT_SIGNAL -> onSignalPacket?.invoke(payload, senderIp)
            TYPE_PTT_AUDIO -> onSignalPacket?.invoke(payload, senderIp)
            TYPE_VOIP_CALL -> onVoipPacket?.invoke(payload, senderIp)
            TYPE_KEEP_ALIVE -> { /* Just a ping to keep NAT open */ }
            else -> {
                // Legacy Fallback (No Header) -> Assume Signal for backward compatibility
                onSignalPacket?.invoke(data, senderIp)
            }
        }
    }

    // [Standard Send] - Adds the Packet Type Header automatically
    fun send(type: Byte, data: ByteArray, targets: List<String>, targetPort: Int) {
        if (!isRunning.get() || targets.isEmpty()) return

        // Pre-allocate buffer with Header
        val packetData = ByteArray(1 + data.size)
        packetData[0] = type
        System.arraycopy(data, 0, packetData, 1, data.size)

        queuePacket(packetData, targets, targetPort)
    }

    // [Raw Send] - Used for STUN or Legacy packets without our header
    fun sendRaw(data: ByteArray, targets: List<String>, targetPort: Int) {
        queuePacket(data, targets, targetPort)
    }

    // [Burst Send] - Uses Coroutines (Non-blocking)
    fun sendBurst(type: Byte, data: ByteArray, targets: List<String>, targetPort: Int, burstCount: Int) {
        if (!isRunning.get() || targets.isEmpty()) return

        val packetData = ByteArray(1 + data.size)
        packetData[0] = type
        System.arraycopy(data, 0, packetData, 1, data.size)

        scope.launch {
            repeat(burstCount) {
                targets.forEach { ip ->
                    sendDirectly(packetData, ip, targetPort)
                }
                delay(10) // Allow other traffic to interleave
            }
        }
    }

    private fun queuePacket(data: ByteArray, targets: List<String>, targetPort: Int) {
        scope.launch(Dispatchers.IO) {
            targets.forEach { ip ->
                val packet = createPacket(data, ip, targetPort)
                if (packet != null) sendQueue.offer(packet)
            }
        }
    }

    private fun sendDirectly(data: ByteArray, ip: String, port: Int) {
        try {
            val packet = createPacket(data, ip, port)
            if (packet != null) sendQueue.offer(packet)
        } catch (e: Exception) {}
    }

    private fun createPacket(data: ByteArray, ip: String, port: Int): DatagramPacket? {
        return try {
            var address = ipCache[ip]
            if (address == null) {
                // [IPv6 Fix] getByName automatically parses IPv4 and IPv6 literals
                address = InetAddress.getByName(ip)
                ipCache[ip] = address
            }
            DatagramPacket(data, data.size, address, port)
        } catch (e: Exception) { null }
    }

    fun stop() {
        isRunning.set(false)
        try { socket?.close() } catch (e: Exception) {}
        sendQueue.clear()
        ipCache.clear()
        scope.cancel()
    }

    fun isBound(): Boolean = isRunning.get() && socket != null && !socket!!.isClosed
}