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

    // [FIX 1] DNS Cache to prevent "Stutter"
    private val ipCache = ConcurrentHashMap<String, InetAddress>()

    // Queue for outgoing packets
    private val sendQueue = LinkedBlockingQueue<DatagramPacket>(128)

    // Single thread is enough if DNS is cached. No need for 4 threads.
    private val sendExecutor = Executors.newSingleThreadExecutor()

    fun start(onPacketReceived: (DatagramPacket) -> Unit): Boolean {
        if (isRunning.getAndSet(true)) return true

        try {
            socket = DatagramSocket(port).apply {
                reuseAddress = true
                receiveBufferSize = 256 * 1024 // 256KB Buffer
                broadcast = true // Enable Broadcast for discovery
                soTimeout = 0
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
            // [FIX 2] Use a fresh buffer for every packet to prevent data corruption
            while (isRunning.get()) {
                try {
                    val buffer = ByteArray(4096) // Max MTU safe size
                    val packet = DatagramPacket(buffer, buffer.size)

                    socket?.receive(packet)

                    // Create a precise copy of the data
                    val dataCopy = packet.data.copyOf(packet.length)

                    if (StunClient.isStunResponse(dataCopy)) {
                        onStunPacket(dataCopy)
                    } else {
                        // Pass the packet up (Address/Port preserved in the packet object)
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

    fun send(data: ByteArray, targets: List<String>, targetPort: Int) {
        if (!isRunning.get() || targets.isEmpty()) return

        // [FIX 3] High-Performance Send (Non-Blocking)
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
                        val packet = DatagramPacket(data, data.size, address, targetPort)
                        // Drop oldest if queue full (Real-time priority)
                        if (!sendQueue.offer(packet)) {
                            sendQueue.poll()
                            sendQueue.offer(packet)
                        }
                    }
                } catch (e: Exception) {
                    // Bad IP, ignore
                }
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