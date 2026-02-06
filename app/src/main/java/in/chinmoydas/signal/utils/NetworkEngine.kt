package `in`.chinmoydas.signal.utils

import android.util.Log
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
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

    // Packet Type Headers (Magic Bytes)
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
            // [CRITICAL] Single Socket for Everything
            socket = DatagramSocket(port).apply {
                reuseAddress = true
                receiveBufferSize = 1024 * 1024 // 1MB Buffer to prevent overflow
                broadcast = true
                soTimeout = 0
            }

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

                            // [ROUTING LOGIC]
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

            // Sender Thread (Standard Queue Consumer)
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
            TYPE_PTT_AUDIO -> onSignalPacket?.invoke(payload, senderIp) // PTT handled by VoiceService
            TYPE_VOIP_CALL -> onVoipPacket?.invoke(payload, senderIp)   // VoIP handled by CallEngine
            TYPE_KEEP_ALIVE -> { /* Just a ping to keep NAT open */ }
            else -> {
                // Legacy Fallback (No Header) -> Assume Signal/PTT for backward compatibility
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
                // [FIX] Non-blocking delay allowing other traffic to flow
                delay(10)
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