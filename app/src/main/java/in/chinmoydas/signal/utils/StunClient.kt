package `in`.chinmoydas.signal.utils

import java.net.DatagramPacket
import java.net.InetAddress
import java.nio.ByteBuffer

object StunClient {
    // Keep defaults for reference, but we will override them
    private const val DEFAULT_SERVER = "stun.l.google.com"
    private const val DEFAULT_PORT = 19302

    data class StunResult(val publicIp: String, val publicPort: Int)

    fun isStunResponse(data: ByteArray): Boolean {
        if (data.size < 20) return false
        return data[0] == 0x01.toByte() && data[1] == 0x01.toByte()
    }

    // [UPGRADE] Now accepts host and port arguments
    fun createBindRequest(host: String = DEFAULT_SERVER, port: Int = DEFAULT_PORT): DatagramPacket? {
        try {
            val address = InetAddress.getByName(host)
            val header = ByteArray(20)
            header[0] = 0x00; header[1] = 0x01 // Binding Request
            header[2] = 0x00; header[3] = 0x00 // Length 0
            header[4] = 0x21.toByte(); header[5] = 0x12.toByte()
            header[6] = 0xA4.toByte(); header[7] = 0x42.toByte() // Magic Cookie
            for (i in 8 until 20) header[i] = (0..255).random().toByte() // Transaction ID
            return DatagramPacket(header, header.size, address, port)
        } catch (e: Exception) { return null }
    }

    fun parseResponse(data: ByteArray): StunResult? {
        try {
            var pos = 20
            while (pos < data.size - 4) {
                val type = ByteBuffer.wrap(data, pos, 2).short
                val length = ByteBuffer.wrap(data, pos + 2, 2).short.toInt()
                if (type == 0x0020.toShort()) { // XOR-MAPPED-ADDRESS
                    val portRaw = ByteBuffer.wrap(data, pos + 6, 2).short.toInt() and 0xFFFF
                    val ipRaw = data.copyOfRange(pos + 8, pos + 12)

                    val magicCookie = 0x2112A442
                    val xorPort = portRaw xor (magicCookie shr 16)
                    val xorIp = IntArray(4)
                    xorIp[0] = (ipRaw[0].toInt() and 0xFF) xor 0x21
                    xorIp[1] = (ipRaw[1].toInt() and 0xFF) xor 0x12
                    xorIp[2] = (ipRaw[2].toInt() and 0xFF) xor 0xA4
                    xorIp[3] = (ipRaw[3].toInt() and 0xFF) xor 0x42

                    return StunResult("${xorIp[0]}.${xorIp[1]}.${xorIp[2]}.${xorIp[3]}", xorPort)
                }
                pos += 4 + length
            }
        } catch (e: Exception) { }
        return null
    }
}