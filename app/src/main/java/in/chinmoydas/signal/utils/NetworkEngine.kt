package `in`.chinmoydas.signal.utils

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

class NetworkEngine(
    private val port: Int,
    private val onStunPacket: (ByteArray) -> Unit // [NEW] Callback for STUN
) {
    private val tag = "NetworkEngine"
    private var socket: DatagramSocket? = null
    private val isRunning = AtomicBoolean(false)
    private val sendQueue = LinkedBlockingQueue<DatagramPacket>(200)

    fun start(onPacketReceived: (DatagramPacket) -> Unit): Boolean {
        if (isRunning.getAndSet(true)) return true

        try {
            socket = DatagramSocket(port).apply {
                reuseAddress = true
                receiveBufferSize = 64 * 1024
                soTimeout = 0
            }
        } catch (e: Exception) {
            isRunning.set(false)
            return false
        }

        // Sender Thread (Unchanged)
        Thread {
            while (isRunning.get()) {
                try {
                    val packet = sendQueue.take()
                    socket?.send(packet)
                } catch (e: Exception) { /* Ignore */ }
            }
        }.start()

        // Receiver Thread (Upgraded Logic)
        Thread {
            val buffer = ByteArray(4096)
            while (isRunning.get()) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)

                    val dataCopy = packet.data.copyOf(packet.length)

                    // [CRITICAL FIX] Traffic Splitter
                    if (StunClient.isStunResponse(dataCopy)) {
                        // 1. It's a STUN response -> Send to ConnectionManager
                        onStunPacket(dataCopy)
                    } else {
                        // 2. It's Audio/Data -> Send to VoiceService
                        // We must reconstruct the packet to preserve Sender IP info
                        val forwardingPacket = DatagramPacket(dataCopy, dataCopy.size, packet.address, packet.port)
                        onPacketReceived(forwardingPacket)
                    }
                } catch (e: Exception) { /* Ignore */ }
            }
        }.start()
        return true
    }

    // Allow sending raw packets (needed for STUN requests)
    fun sendRawPacket(packet: DatagramPacket) {
        sendQueue.offer(packet)
    }

    fun send(data: ByteArray, targets: List<String>, targetPort: Int) {
        if (!isRunning.get()) return
        Thread {
            targets.forEach { ip ->
                try {
                    val address = InetAddress.getByName(ip)
                    sendQueue.offer(DatagramPacket(data, data.size, address, targetPort))
                } catch (e: Exception) { }
            }
        }.start()
    }

    fun stop() {
        isRunning.set(false)
        socket?.close()
    }

    fun isBound(): Boolean = isRunning.get() && socket != null && !socket!!.isClosed
}