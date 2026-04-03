package `in`.chinmoydas.signal.utils

import java.net.InetAddress
import java.security.SecureRandom

object StunClient {

    data class StunResult(val ip: String, val port: Int)

    fun isStunResponse(data: ByteArray): Boolean {
        // RFC 5389: Binding Response is 0x0101. Magic Cookie at offset 4.
        if (data.size < 20) return false
        val isResponse = data[0] == 0x01.toByte() && data[1] == 0x01.toByte()
        val hasMagicCookie = data[4] == 0x21.toByte() && data[5] == 0x12.toByte() &&
                data[6] == 0xA4.toByte() && data[7] == 0x42.toByte()
        return isResponse && hasMagicCookie
    }

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

        // [FIXED] Faster, cryptographically secure 12-byte Transaction ID
        val random = SecureRandom()
        val txId = ByteArray(12)
        random.nextBytes(txId)
        System.arraycopy(txId, 0, request, 8, 12)

        return request
    }

    fun parseResponse(data: ByteArray): StunResult? {
        try {
            if (!isStunResponse(data)) return null

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
                    val portRaw = ((data[pos + 2].toInt() and 0xFF) shl 8) or (data[pos + 3].toInt() and 0xFF)
                    val port = portRaw xor 0x2112

                    val ipBytes = ByteArray(4)
                    // [CRITICAL FIX] Stripping the Kotlin sign-extension with 'and 0xFF' before XOR
                    ipBytes[0] = ((data[pos + 4].toInt() and 0xFF) xor 0x21).toByte()
                    ipBytes[1] = ((data[pos + 5].toInt() and 0xFF) xor 0x12).toByte()
                    ipBytes[2] = ((data[pos + 6].toInt() and 0xFF) xor 0xA4).toByte()
                    ipBytes[3] = ((data[pos + 7].toInt() and 0xFF) xor 0x42).toByte()

                    val ip = InetAddress.getByAddress(ipBytes).hostAddress ?: return null
                    return StunResult(ip, port)
                }
                // 0x0001: MAPPED-ADDRESS (Fallback)
                else if (type == 0x0001) {
                    val port = ((data[pos + 2].toInt() and 0xFF) shl 8) or (data[pos + 3].toInt() and 0xFF)
                    val ipBytes = ByteArray(4)
                    // System.arraycopy is safe from the sign-extension bug
                    System.arraycopy(data, pos + 4, ipBytes, 0, 4)

                    val ip = InetAddress.getByAddress(ipBytes).hostAddress ?: return null
                    return StunResult(ip, port)
                }

                // Skip attributes we don't care about (e.g., SOFTWARE, FINGERPRINT)
                val padding = (4 - (attrLen % 4)) % 4
                pos += attrLen + padding
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
}