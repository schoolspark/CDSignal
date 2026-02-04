package `in`.chinmoydas.signal.utils

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

class NetworkEngine(
    private val port: Int,
    private val onStunPacket: (ByteArray) -> Unit
) {
    private val tag = "NetworkEngine"
    private var socket: DatagramSocket? = null
    private val isRunning = AtomicBoolean(false)

    // DNS Cache to prevent "Stutter"
    private val ipCache = ConcurrentHashMap<String, InetAddress>()

    // Queue for outgoing packets
    private val sendQueue = LinkedBlockingQueue<DatagramPacket>(128)
    private val sendExecutor = Executors.newSingleThreadExecutor()

    fun start(onPacketReceived: (DatagramPacket) -> Unit): Boolean {
        if (isRunning.getAndSet(true)) return true

        try {
            socket = DatagramSocket(port).apply {
                reuseAddress = true
                receiveBufferSize = 512 * 1024 // Increased Buffer
                broadcast = true
                soTimeout = 0
            }

            Thread {
                val buffer = ByteArray(4096)
                while (isRunning.get()) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket?.receive(packet)

                        if (packet.length > 0) {
                            val data = packet.data.copyOf(packet.length)
                            if (StunClient.isStunResponse(data)) {
                                onStunPacket(data)
                            } else {
                                onPacketReceived(packet)
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
                    } catch (e: Exception) {
                        // Queue error or socket closed
                    }
                }
            }.start()

            return true
        } catch (e: Exception) {
            Log.e(tag, "Bind Failed: ${e.message}")
            isRunning.set(false)
            return false
        }
    }

    fun send(data: ByteArray, targets: List<String>, targetPort: Int) {
        if (!isRunning.get() || targets.isEmpty()) return

        sendExecutor.execute {
            targets.forEach { ip ->
                try {
                    var address = ipCache[ip]
                    if (address == null) {
                        address = InetAddress.getByName(ip)
                        ipCache[ip] = address
                    }

                    if (address != null) {
                        val packet = DatagramPacket(data, data.size, address, targetPort)
                        sendQueue.offer(packet)
                    }
                } catch (e: Exception) { }
            }
        }
    }

    // [NEW] CRITICAL FOR BREACH PROTOCOL
    // Sends the same packet multiple times to ensure it punches through NAT
    fun sendBurst(data: ByteArray, targets: List<String>, targetPort: Int, isMobileTarget: Boolean, burstCount: Int) {
        if (!isRunning.get() || targets.isEmpty()) return

        sendExecutor.execute {
            targets.forEach { ip ->
                try {
                    var address = ipCache[ip]
                    if (address == null) {
                        address = InetAddress.getByName(ip)
                        ipCache[ip] = address
                    }

                    if (address != null) {
                        repeat(burstCount) {
                            val packet = DatagramPacket(data, data.size, address, targetPort)
                            sendQueue.offer(packet)
                            // Small delay for mobile targets to prevent buffer overflow on their end
                            if (isMobileTarget) Thread.sleep(5)
                        }
                    }
                } catch (e: Exception) { }
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        try { socket?.close() } catch (e: Exception) {}
        sendQueue.clear()
        ipCache.clear()
    }

    fun isBound(): Boolean = isRunning.get() && socket != null && !socket!!.isClosed
}