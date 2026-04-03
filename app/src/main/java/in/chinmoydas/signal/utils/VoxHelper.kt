package `in`.chinmoydas.signal.utils

import kotlin.math.sqrt

class VoxHelper(
    private val onSpeechStart: () -> Unit,
    private val onSilence: () -> Unit
) {
    private var isTalking = false
    private var silenceStart = 0L

    // Config for Public Version (Sensitivity)
    // 300 is sensitive (whisper), 1000 is loud (shout)
    var sensitivity = 500

    // Hold the line open for 1.2 seconds after speaking stops
    private val HOLD_TIME = 1200L

    fun process(buffer: ByteArray) {
        // [FIX] Guard against division by zero on tiny buffers
        if (buffer.size < 2) return

        val amplitude = calculateRMS(buffer)

        if (amplitude > sensitivity) {
            keepAlive()
        } else if (isTalking) {
            if (silenceStart == 0L) silenceStart = System.currentTimeMillis()

            if (System.currentTimeMillis() - silenceStart > HOLD_TIME) {
                isTalking = false
                onSilence()
            }
        }
    }

    /**
     * [NEW] Allows VoiceService to keep VOX open without processing RMS math.
     * Used when AudioEngine has already verified speech via its own Noise Gate.
     */
    fun keepAlive() {
        silenceStart = 0
        if (!isTalking) {
            isTalking = true
            onSpeechStart()
        }
    }

    private fun calculateRMS(buffer: ByteArray): Double {
        var sum = 0.0
        // Parse 16-bit PCM data
        for (i in 0 until buffer.size step 2) {
            if (i + 1 >= buffer.size) break

            // Convert to 16-bit signed integer
            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i+1].toInt() shl 8)

            // Convert to Double BEFORE squaring to prevent Int Overflow
            val sampleVal = sample.toDouble()
            sum += sampleVal * sampleVal
        }
        return sqrt(sum / (buffer.size / 2))
    }
}