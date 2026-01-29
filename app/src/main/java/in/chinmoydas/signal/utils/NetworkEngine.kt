package `in`.chinmoydas.signal.utils

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
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

    // [FIX] Limit queue size to prevent memory leaks if network is slow
    private val sendQueue = LinkedBlockingQueue<DatagramPacket>(100)

    // [CRITICAL] Fixed Thread Pool (4 threads).
    // This handles high-speed audio packets without crashing the CPU.
    private val sendExecutor = Executors.newFixedThreadPool(4)

    fun start(onPacketReceived: (DatagramPacket) -> Unit): Boolean {
        if (isRunning.getAndSet(true)) return true

        // Retry logic for port binding (Robustness)
        var attempt = 0
        var bound = false
        while (attempt < 3 && !bound) {
            try {
                socket = DatagramSocket(port).apply {
                    reuseAddress = true
                    receiveBufferSize = 256 * 1024
                    soTimeout = 0
                }
                bound = true
            } catch (e: Exception) {
                attempt++
                try { Thread.sleep(200) } catch (e: Exception) {}
            }
        }

        if (!bound) {
            isRunning.set(false)
            return false
        }

        // 1. Sender Thread (Consumes the queue)
        Thread {
            while (isRunning.get()) {
                try {
                    val packet = sendQueue.take()
                    socket?.send(packet)
                } catch (e: Exception) { /* socket closed */ }
            }
        }.start()

        // 2. Receiver Thread (Listens for data)
        Thread {
            val buffer = ByteArray(4096)
            val packet = DatagramPacket(buffer, buffer.size)

            while (isRunning.get()) {
                try {
                    packet.setData(buffer)
                    socket?.receive(packet)

                    val dataCopy = packet.data.copyOf(packet.length)

                    // Traffic Splitter: STUN vs Audio
                    if (StunClient.isStunResponse(dataCopy)) {
                        onStunPacket(dataCopy)
                    } else {
                        val forwardingPacket = DatagramPacket(dataCopy, dataCopy.size, packet.address, packet.port)
                        onPacketReceived(forwardingPacket)
                    }
                } catch (e: Exception) {
                    if (isRunning.get()) Log.w(tag, "Receive Error: ${e.message}")
                }
            }
        }.start()

        return true
    }

    fun sendRawPacket(packet: DatagramPacket) {
        if (isRunning.get()) sendQueue.offer(packet)
    }

    // [CRITICAL FIX] Efficient Sending
    // Offloads IP resolution to the thread pool so the UI/Audio thread never waits.
    fun send(data: ByteArray, targets: List<String>, targetPort: Int) {
        if (!isRunning.get() || targets.isEmpty()) return

        sendExecutor.execute {
            targets.forEach { ip ->
                try {
                    val address = InetAddress.getByName(ip)
                    val packet = DatagramPacket(data, data.size, address, targetPort)
                    // If queue is full, drop oldest packet to keep audio "real-time"
                    if (!sendQueue.offer(packet)) {
                        sendQueue.poll()
                        sendQueue.offer(packet)
                    }
                } catch (e: Exception) { }
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        try { socket?.close() } catch (e: Exception) {}
        sendQueue.clear()
    }

    fun isBound(): Boolean = isRunning.get() && socket != null && !socket!!.isClosed
}