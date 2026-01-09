package `in`.chinmoydas.signal.utils

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Robust Network Engine for UDP communication.
 * Enhanced with Address Caching to prevent micro-stutters during multi-user broadcasts.
 */
class NetworkEngine(private val port: Int) {
    private val tag = "NetworkEngine"
    private var socket: DatagramSocket? = null
    private val isRunning = AtomicBoolean(false)
    private val sendQueue = LinkedBlockingQueue<DatagramPacket>(200)

    // Cache for InetAddresses to avoid frequent DNS/Address lookups
    private val addressCache = ConcurrentHashMap<String, InetAddress>()

    private var senderThread: Thread? = null
    private var receiverThread: Thread? = null

    fun start(onPacketReceived: (DatagramPacket) -> Unit) {
        if (isRunning.getAndSet(true)) return

        try {
            socket = DatagramSocket(port).apply {
                reuseAddress = true
                receiveBufferSize = 128 * 1024
                soTimeout = 0
            }
            Log.d(tag, "Socket started on port $port")
        } catch (e: SocketException) {
            Log.e(tag, "Could not start socket: ${e.message}")
            isRunning.set(false)
            return
        }

        senderThread = Thread({
            try {
                while (isRunning.get()) {
                    val packet = sendQueue.poll(500, TimeUnit.MILLISECONDS)
                    if (packet != null) {
                        try {
                            socket?.send(packet)
                        } catch (e: Exception) {
                            if (isRunning.get()) Log.e(tag, "Send failed: ${e.message}")
                        }
                    }
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                Log.e(tag, "Sender thread crashed: ${e.message}")
            }
        }, "NetworkSender").apply { start() }

        receiverThread = Thread({
            val buffer = ByteArray(4096)
            try {
                while (isRunning.get()) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket?.receive(packet)
                        val dataCopy = packet.data.copyOf(packet.length)
                        val receivePacket = DatagramPacket(dataCopy, dataCopy.size, packet.address, packet.port)
                        onPacketReceived(receivePacket)
                    } catch (e: Exception) {
                        if (isRunning.get()) Log.e(tag, "Receive error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) Log.e(tag, "Receiver thread crashed: ${e.message}")
            }
        }, "NetworkReceiver").apply { start() }
    }

    fun send(data: ByteArray, targets: List<String>, targetPort: Int) {
        if (!isRunning.get()) return

        for (ip in targets) {
            try {
                // Use cache to avoid blocking the audio recording thread with lookups
                val address = addressCache.getOrPut(ip) { InetAddress.getByName(ip) }
                val packet = DatagramPacket(data, data.size, address, targetPort)
                if (!sendQueue.offer(packet)) {
                    Log.w(tag, "Send queue full, dropping packet to $ip")
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to queue packet for $ip: ${e.message}")
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        socket?.close()
        socket = null
        senderThread?.interrupt()
        receiverThread?.interrupt()
        sendQueue.clear()
        addressCache.clear()
    }
}