package `in`.chinmoydas.signal.utils

object G711 {
    private const val ULAW_BIAS = 0x84
    private const val ULAW_CLIP = 32635

    // [MATCHES AudioEngine] Accepts ShortArray directly
    fun encode(pcm16: ShortArray, length: Int): ByteArray {
        val ulaw = ByteArray(length)
        for (i in 0 until length) {
            ulaw[i] = linearToUlaw(pcm16[i])
        }
        return ulaw
    }

    // [RENAMED] Changed from decodeToPcmBytes -> decode to match AudioEngine
    fun decode(ulaw: ByteArray, length: Int): ByteArray {
        val pcm = ByteArray(length * 2)
        for (i in 0 until length) {
            val s = ulawToLinear(ulaw[i]).toInt()
            // Pack into Little-Endian PCM16 bytes
            pcm[2 * i] = (s and 0xFF).toByte()
            pcm[2 * i + 1] = ((s ushr 8) and 0xFF).toByte()
        }
        return pcm
    }

    private fun linearToUlaw(pcm16: Short): Byte {
        var sample = pcm16.toInt()

        var sign = 0
        if (sample < 0) {
            sign = 0x80
            sample = -sample
        }

        if (sample > ULAW_CLIP) sample = ULAW_CLIP
        sample += ULAW_BIAS

        // Find exponent/segment without mutating the sample.
        var exponent = 7
        var expMask = 0x4000
        while (exponent > 0 && (sample and expMask) == 0) {
            exponent--
            expMask = expMask shr 1
        }

        val mantissa = (sample shr (exponent + 3)) and 0x0F
        val ulaw = (sign or (exponent shl 4) or mantissa).inv() and 0xFF
        return ulaw.toByte()
    }

    private fun ulawToLinear(ulawByte: Byte): Short {
        var u = ulawByte.toInt() and 0xFF
        u = u.inv() and 0xFF

        val sign = u and 0x80
        val exponent = (u ushr 4) and 0x07
        val mantissa = u and 0x0F

        var linear = ((mantissa shl 3) + ULAW_BIAS) shl exponent
        linear -= ULAW_BIAS

        return if (sign != 0) (-linear).toShort() else linear.toShort()
    }
}