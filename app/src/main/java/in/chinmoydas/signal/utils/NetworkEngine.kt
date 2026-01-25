package `in`.chinmoydas.signal.utils

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

class NetworkEngine(private val port: Int) {
    private val tag = "NetworkEngine"
    private var socket: DatagramSocket? = null
    private val isRunning = AtomicBoolean(false)
    private val sendQueue = LinkedBlockingQueue<DatagramPacket>(200)

    private val addressCache = ConcurrentHashMap<String, InetAddress>()
    private var senderThread: Thread? = null
    private var receiverThread: Thread? = null

    fun start(onPacketReceived: (DatagramPacket) -> Unit): Boolean {
        if (isRunning.getAndSet(true)) return true

        try {
            socket = DatagramSocket(port).apply {
                reuseAddress = true
                receiveBufferSize = 64 * 1024
                soTimeout = 0
            }
            Log.d(tag, "Socket started on port $port")
        } catch (e: SocketException) {
            Log.e(tag, "Could not start socket: ${e.message}")
            isRunning.set(false)
            return false
        }

        senderThread = Thread({
            try {
                while (isRunning.get()) {
                    val packet = sendQueue.take()
                    if (packet != null) {
                        try { socket?.send(packet) } catch (e: Exception) { }
                    }
                }
            } catch (e: Exception) { }
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
                    } catch (e: Exception) { }
                }
            } catch (e: Exception) { }
        }, "NetworkReceiver").apply { start() }
        return true
    }

    fun send(data: ByteArray, targets: List<String>, targetPort: Int) {
        if (!isRunning.get()) return
        for (ip in targets) {
            try {
                val address = addressCache.getOrPut(ip) { InetAddress.getByName(ip) }
                val packet = DatagramPacket(data, data.size, address, targetPort)
                sendQueue.offer(packet)
            } catch (e: Exception) { }
        }
    }

    fun sendRawPacket(packet: DatagramPacket) {
        if (!isRunning.get()) return
        try { socket?.send(packet) } catch (e: Exception) { }
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

    fun isBound(): Boolean {
        return isRunning.get() && socket != null && !socket!!.isClosed
    }
}