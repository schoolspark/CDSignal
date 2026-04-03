package `in`.chinmoydas.signal.utils

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.util.Log
import android.widget.Toast
import `in`.chinmoydas.signal.VoiceService
import kotlinx.coroutines.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

object AudioDiagnostics {

    private const val TAG = "AudioDiag"
    // [FIX] Guard to prevent running multiple tests simultaneously
    private val isTesting = AtomicBoolean(false)

    @SuppressLint("MissingPermission")
    fun runAudioTest(context: Context, service: VoiceService?) {
        // Prevent concurrent tests
        if (!isTesting.compareAndSet(false, true)) {
            Toast.makeText(context, "Diagnostic already running...", Toast.LENGTH_SHORT).show()
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Pre-check: Don't run if already transmitting or in a call
                if (service?.voiceServiceState?.value?.isTransmitting == true || CallEngine.isCallActive) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Cannot test while the radio is active!", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "🎙️ Audio Diagnostics Started", Toast.LENGTH_SHORT).show()
                }

                // 1. Instructions
                service?.speakText("Diagnostic Mode. Please speak after the beep.")
                delay(3500)

                // 2. Beep Signal
                val tone = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 100)
                tone.startTone(ToneGenerator.TONE_PROP_BEEP)
                delay(500)
                tone.release()

                // 3. Record (3 Seconds)
                val tempFile = File(context.cacheDir, "diag_temp.pcm")
                recordAudio(tempFile)

                // 4. Playback Instructions
                service?.speakText("Recording stopped. Playing back now.")
                delay(3000)

                // 5. Playback
                if (tempFile.exists() && tempFile.length() > 0) {
                    playAudio(tempFile)
                } else {
                    throw Exception("Microphone captured no audio.")
                }

                // 6. Conclusion
                delay(1000)
                service?.speakText("Test Complete. If you heard your voice, the system is operational.")

                // Cleanup
                if (tempFile.exists()) tempFile.delete()

            } catch (e: Exception) {
                Log.e(TAG, "Test Failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Test Failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
                service?.speakText("Diagnostic Failed. Please check microphone permissions.")
            } finally {
                // [FIX] Release the lock no matter what happens
                isTesting.set(false)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun recordAudio(file: File) = withContext(Dispatchers.IO) {
        // [FIX] Matched to AudioEngine parameters exactly (16kHz)
        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, // Matched AudioEngine
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )

        // [FIX] Safely check if hardware actually initialized
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw Exception("Microphone is blocked by another app.")
        }

        val data = ByteArray(bufferSize)

        try {
            // [FIX] Safe resource handling with .use
            FileOutputStream(file).use { os ->
                recorder.startRecording()
                val startTime = System.currentTimeMillis()

                // Record for exactly 3 seconds
                while (System.currentTimeMillis() - startTime < 3000) {
                    val read = recorder.read(data, 0, bufferSize)
                    if (read > 0) {
                        os.write(data, 0, read)
                    }
                }
            }
        } finally {
            try { recorder.stop() } catch (e: Exception) {}
            recorder.release()
        }
    }

    private suspend fun playAudio(file: File) = withContext(Dispatchers.IO) {
        // [FIX] Matched to AudioEngine parameters exactly (16kHz)
        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_OUT_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2

        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION) // Matched AudioEngine
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(audioFormat)
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .build())
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        val data = ByteArray(bufferSize)

        try {
            // [FIX] Safe resource handling with .use
            FileInputStream(file).use { fis ->
                track.play()
                var read: Int
                while (fis.read(data).also { read = it } != -1) {
                    track.write(data, 0, read)
                }
                track.stop()
            }
        } finally {
            track.release()
        }
    }
}