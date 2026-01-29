package `in`.chinmoydas.signal.utils

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

class NetworkEngine(
    private val port: Int,
    private val onStunPacket: (ByteArray) -> Unit
) {
    private val tag = "NetworkEngine"
    private var socket: DatagramSocket? = null
    private val isRunning = AtomicBoolean(false)

    // [MISSION CRITICAL] Queue Size Limit
    // 200 packets @ 40ms = 8 seconds of audio buffer max.
    private val sendQueue = LinkedBlockingQueue<DatagramPacket>(200)

    fun start(onPacketReceived: (DatagramPacket) -> Unit): Boolean {
        if (isRunning.getAndSet(true)) return true

        // [AUDIT FIX] Bind Retry Mechanism
        // Sometimes Android holds the port for a few ms after a network switch.
        // We try 3 times before failing.
        var attempt = 0
        var bound = false

        while (attempt < 3 && !bound) {
            try {
                socket = DatagramSocket(port).apply {
                    reuseAddress = true
                    receiveBufferSize = 256 * 1024 // Increased buffer for stability
                    soTimeout = 0
                }
                bound = true
            } catch (e: Exception) {
                Log.w(tag, "Bind attempt $attempt failed: ${e.message}")
                attempt++
                try { Thread.sleep(300) } catch (x: Exception) {}
            }
        }

        if (!bound) {
            Log.e(tag, "FATAL: Could not bind UDP port $port after 3 attempts.")
            isRunning.set(false)
            return false
        }

        // Sender Thread
        Thread {
            while (isRunning.get()) {
                try {
                    val packet = sendQueue.take()
                    socket?.send(packet)
                } catch (e: Exception) {
                    // Socket closed or network error
                }
            }
        }.start()

        // Receiver Thread
        Thread {
            val buffer = ByteArray(4096) // Large enough for any MTU
            val packet = DatagramPacket(buffer, buffer.size)

            while (isRunning.get()) {
                try {
                    // Reset packet length for next read
                    packet.length = buffer.size
                    socket?.receive(packet)

                    val dataCopy = packet.data.copyOf(packet.length)

                    // Traffic Splitter: STUN vs Audio/Data
                    if (StunClient.isStunResponse(dataCopy)) {
                        onStunPacket(dataCopy)
                    } else {
                        val forwardingPacket = DatagramPacket(dataCopy, dataCopy.size, packet.address, packet.port)
                        onPacketReceived(forwardingPacket)
                    }
                } catch (e: Exception) {
                    if (isRunning.get()) Log.w(tag, "UDP Receive Error: ${e.message}")
                }
            }
        }.start()

        Log.i(tag, "Network Engine Started on Port $port")
        return true
    }

    // Allow sending raw packets (needed for STUN requests)
    fun sendRawPacket(packet: DatagramPacket) {
        queuePacketSafe(packet)
    }

    fun send(data: ByteArray, targets: List<String>, targetPort: Int) {
        if (!isRunning.get()) return
        Thread {
            targets.forEach { ip ->
                try {
                    val address = InetAddress.getByName(ip)
                    val packet = DatagramPacket(data, data.size, address, targetPort)
                    queuePacketSafe(packet)
                } catch (e: Exception) { }
            }
        }.start()
    }

    // [MISSION CRITICAL] Safe Queue Logic
    // If queue is full (network congestion), Drop the Oldest packet.
    // This favors "Real-Time" audio over "Complete" audio.
    private fun queuePacketSafe(packet: DatagramPacket) {
        if (!sendQueue.offer(packet)) {
            sendQueue.poll() // Discard oldest packet (Latency preservation)
            sendQueue.offer(packet) // Add new packet
        }
    }

    fun stop() {
        isRunning.set(false)
        try { socket?.close() } catch (e: Exception) {}
        sendQueue.clear()
    }

    fun isBound(): Boolean = isRunning.get() && socket != null && !socket!!.isClosed
}