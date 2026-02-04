package `in`.chinmoydas.signal.utils

import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.Random

object StunClient {

    data class StunResult(val ip: String, val port: Int)

    fun isStunResponse(data: ByteArray): Boolean {
        // RFC 5389: First byte 0, 1st bit 0. Magic Cookie 0x2112A442 at offset 4
        // 0x0101 is Binding Response
        return data.size >= 20 && data[0] == 0x01.toByte() && data[1] == 0x01.toByte()
    }

    // [FIX] Returns ByteArray (not DatagramPacket) to work with ConnectionManager
    fun buildRequest(): ByteArray {
        val request = ByteArray(20)
        // Message Type: Binding Request (0x0001)
        request[0] = 0x00
        request[1] = 0x01
        // Message Length: 0
        request[2] = 0x00
        request[3] = 0x00
        // Magic Cookie (0x2112A442)
        request[4] = 0x21
        request[5] = 0x12
        request[6] = 0xA4.toByte()
        request[7] = 0x42
        // Transaction ID (12 bytes random)
        val random = Random()
        for (i in 8 until 20) {
            request[i] = random.nextInt(256).toByte()
        }
        return request
    }

    fun parseResponse(data: ByteArray): StunResult? {
        try {
            if (!isStunResponse(data)) return null

            // Skip Header (20 bytes)
            var pos = 20
            val len = data.size

            while (pos + 4 <= len) {
                // Parse Type and Length
                val type = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
                val attrLen = ((data[pos + 2].toInt() and 0xFF) shl 8) or (data[pos + 3].toInt() and 0xFF)
                pos += 4

                if (pos + attrLen > len) break

                // 0x0020: XOR-MAPPED-ADDRESS (Preferred)
                if (type == 0x0020) {
                    // Skip 1 byte (0x00) + 1 byte (Family 0x01 for IPv4)
                    val portRaw = ((data[pos + 2].toInt() and 0xFF) shl 8) or (data[pos + 3].toInt() and 0xFF)
                    val port = portRaw xor 0x2112 // XOR with Magic Cookie

                    val ipBytes = ByteArray(4)
                    ipBytes[0] = (data[pos + 4].toInt() xor 0x21).toByte()
                    ipBytes[1] = (data[pos + 5].toInt() xor 0x12).toByte()
                    ipBytes[2] = (data[pos + 6].toInt() xor 0xA4).toByte()
                    ipBytes[3] = (data[pos + 7].toInt() xor 0x42).toByte()

                    val ip = InetAddress.getByAddress(ipBytes).hostAddress ?: return null
                    return StunResult(ip, port)
                }
                // 0x0001: MAPPED-ADDRESS (Fallback)
                else if (type == 0x0001) {
                    val port = ((data[pos + 2].toInt() and 0xFF) shl 8) or (data[pos + 3].toInt() and 0xFF)
                    val ipBytes = ByteArray(4)
                    System.arraycopy(data, pos + 4, ipBytes, 0, 4)

                    val ip = InetAddress.getByAddress(ipBytes).hostAddress ?: return null
                    return StunResult(ip, port)
                }

                pos += attrLen
            }
        } catch (e: Exception) { e.printStackTrace() }
        return null
    }
}