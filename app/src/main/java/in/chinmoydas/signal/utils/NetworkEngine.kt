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

    // [BREACH PROTOCOL] DNS Cache to prevent "Stutter" during audio streaming
    private val ipCache = ConcurrentHashMap<String, InetAddress>()

    // Queue for outgoing packets (Increased buffer for Burst Mode)
    private val sendQueue = LinkedBlockingQueue<DatagramPacket>(512)

    private val sendExecutor = Executors.newSingleThreadExecutor()

    fun start(onPacketReceived: (DatagramPacket) -> Unit): Boolean {
        if (isRunning.getAndSet(true)) return true

        try {
            socket = DatagramSocket(port).apply {
                reuseAddress = true
                receiveBufferSize = 1024 * 1024 // 1MB Buffer (High Perf)
                broadcast = true
                soTimeout = 0
                trafficClass = 0x10 // IPTOS_LOWDELAY
            }
        } catch (e: Exception) {
            isRunning.set(false)
            return false
        }

        // 1. Sender Loop
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

        // 2. Receiver Loop
        Thread {
            while (isRunning.get()) {
                try {
                    val buffer = ByteArray(4096)
                    val packet = DatagramPacket(buffer, buffer.size)

                    socket?.receive(packet)

                    val dataCopy = packet.data.copyOf(packet.length)

                    if (StunClient.isStunResponse(dataCopy)) {
                        onStunPacket(dataCopy)
                    } else {
                        onPacketReceived(packet)
                    }
                } catch (e: Exception) {
                    // Ignore receive errors
                }
            }
        }.start()

        return true
    }

    fun sendRawPacket(packet: DatagramPacket) {
        if (isRunning.get()) sendQueue.offer(packet)
    }

    /**
     * [BREACH PROTOCOL] - BURST MODE
     * Sends the data to the target.
     * @param isMobileTarget If TRUE, we enable "Port Spraying" (P, P+1, P-1)
     * @param burstCount How many copies to send (Redundancy)
     */
    fun sendBurst(data: ByteArray, targets: List<String>, targetPort: Int, isMobileTarget: Boolean = false, burstCount: Int = 1) {
        if (!isRunning.get() || targets.isEmpty()) return

        sendExecutor.execute {
            targets.forEach { ip ->
                try {
                    // Check Cache first
                    var address = ipCache[ip]
                    if (address == null) {
                        address = InetAddress.getByName(ip)
                        ipCache[ip] = address // Cache it!
                    }

                    if (address != null) {
                        // 1. Standard Target
                        queuePacket(data, address, targetPort, burstCount)

                        // 2. Port Spraying (Only for Mobile Targets to beat Symmetric NAT)
                        if (isMobileTarget) {
                            queuePacket(data, address, targetPort + 1, 1) // Try Next Port
                            if (targetPort > 1024) {
                                queuePacket(data, address, targetPort - 1, 1) // Try Prev Port
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Bad IP, ignore
                }
            }
        }
    }

    // Helper to queue packets
    private fun queuePacket(data: ByteArray, address: InetAddress, port: Int, count: Int) {
        repeat(count) {
            val packet = DatagramPacket(data, data.size, address, port)
            // Drop oldest if queue full (Real-time priority)
            if (!sendQueue.offer(packet)) {
                sendQueue.poll()
                sendQueue.offer(packet)
            }
        }
    }

    // Legacy support for existing calls (defaults to 1 copy, no spray)
    fun send(data: ByteArray, targets: List<String>, targetPort: Int) {
        sendBurst(data, targets, targetPort, isMobileTarget = false, burstCount = 1)
    }

    fun stop() {
        isRunning.set(false)
        try { socket?.close() } catch (e: Exception) {}
        sendQueue.clear()
        ipCache.clear()
    }

    fun isBound(): Boolean = isRunning.get() && socket != null && !socket!!.isClosed
}