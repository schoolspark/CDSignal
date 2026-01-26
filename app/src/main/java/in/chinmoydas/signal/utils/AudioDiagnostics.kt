package `in`.chinmoydas.signal.utils

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.widget.Toast
import `in`.chinmoydas.signal.VoiceService
import kotlinx.coroutines.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object AudioDiagnostics {

    @SuppressLint("MissingPermission")
    fun runAudioTest(context: Context, service: VoiceService?) {
        // Use IO Dispatcher for audio work
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Pre-check: Don't run if already transmitting
                if (service?.voiceServiceState?.value?.isTransmitting == true) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Stop transmitting first!", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "🎙️ Audio Diagnostics Started", Toast.LENGTH_SHORT).show()
                }

                // 1. Instructions
                service?.speakText("Diagnostic Mode. Please speak after the beep.")
                delay(3500) // Wait for TTS

                // 2. Beep Signal
                val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
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
                playAudio(tempFile)

                // 6. Conclusion
                delay(1000)
                service?.speakText("Test Complete. If you heard your voice, the system is operational.")

                // Cleanup
                if (tempFile.exists()) tempFile.delete()

            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Test Failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun recordAudio(file: File) = withContext(Dispatchers.IO) {
        val sampleRate = 44100
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )

        val data = ByteArray(bufferSize)
        val os = FileOutputStream(file)

        try {
            recorder.startRecording()
            val startTime = System.currentTimeMillis()

            // Record for exactly 3 seconds
            while (System.currentTimeMillis() - startTime < 3000) {
                val read = recorder.read(data, 0, bufferSize)
                if (read > 0) {
                    os.write(data, 0, read)
                }
            }
        } finally {
            try { recorder.stop() } catch (e: Exception) {}
            recorder.release()
            os.close()
        }
    }

    private suspend fun playAudio(file: File) = withContext(Dispatchers.IO) {
        val sampleRate = 44100
        val channelConfig = AudioFormat.CHANNEL_OUT_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
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
        val fis = FileInputStream(file)

        try {
            track.play()
            var read: Int
            while (fis.read(data).also { read = it } != -1) {
                track.write(data, 0, read)
            }
            track.stop()
        } finally {
            track.release()
            fis.close()
        }
    }
}