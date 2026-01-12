package `in`.chinmoydas.signal.utils

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresPermission
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class AudioEngine(context: Context) {
    private val tag = "AudioEngine"

    // [Golden Build] Standard Wideband Voice (16kHz)
    private val sampleRate = 16000

    // --- AUDIO HARDWARE ---
    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var audioRecord: AudioRecord? = null

    // --- AUDIO EFFECTS ---
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null

    // --- THREADING ---
    private var recordingThread: Thread? = null
    private var playbackThread: Thread? = null
    private val isRecording = AtomicBoolean(false)
    private val isPlaying = AtomicBoolean(false)

    // --- JITTER BUFFER ---
    private data class AudioPacket(val seq: Int, val data: ByteArray) : Comparable<AudioPacket> {
        override fun compareTo(other: AudioPacket) = this.seq - other.seq
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as AudioPacket
            return seq == other.seq && data.contentEquals(other.data)
        }
        override fun hashCode(): Int {
            var result = seq
            result = 31 * result + data.contentHashCode()
            return result
        }
    }
    private val jitterBuffer = PriorityQueue<AudioPacket>()
    private var lastPlayedSeq = -1
    private val BUFFER_THRESHOLD = 3

    // --- CONFIG ---
    // 640 shorts = 40ms at 16kHz (Standard PTT Frame)
    private val FRAME_SIZE = 640
    private val NOISE_GATE_THRESHOLD = 50
    private val GAIN_LIMIT_THRESHOLD = 30000

    // =========================================================================
    // PLAYBACK LOGIC
    // =========================================================================

    fun startPlayback() {
        if (isPlaying.get() || audioTrack != null) return

        try {
            val minBufSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(minBufSize * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
            startPlaybackThread()
            Log.d(tag, "Playback started at $sampleRate Hz.")
        } catch (e: Exception) {
            Log.e(tag, "Playback initialization failed", e)
        }
    }

    private fun startPlaybackThread() {
        if (!isPlaying.compareAndSet(false, true)) return

        synchronized(jitterBuffer) { jitterBuffer.clear() }
        lastPlayedSeq = -1

        playbackThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            try {
                while (isPlaying.get()) {
                    var packetToPlay: AudioPacket? = null
                    synchronized(jitterBuffer) {
                        if (jitterBuffer.isNotEmpty()) {
                            if (lastPlayedSeq != -1 || jitterBuffer.size >= BUFFER_THRESHOLD) {
                                packetToPlay = jitterBuffer.poll()
                            }
                        }
                    }

                    if (packetToPlay != null) {
                        if (lastPlayedSeq != -1 && (packetToPlay.seq < lastPlayedSeq || packetToPlay.seq > lastPlayedSeq + 500)) {
                            lastPlayedSeq = packetToPlay.seq - 1
                        }

                        if (packetToPlay.seq > lastPlayedSeq) {
                            val data = packetToPlay.data
                            audioTrack?.write(data, 0, data.size)
                            lastPlayedSeq = packetToPlay.seq
                        }
                    } else {
                        Thread.sleep(2)
                    }
                }
            } catch (e: Exception) {
                // Ignore interruption
            }
        }, "AudioPlaybackThread").apply { start() }
    }

    fun writeAudio(seq: Int, data: ByteArray) {
        if (!isPlaying.get()) return
        synchronized(jitterBuffer) {
            if (jitterBuffer.size > 50) jitterBuffer.clear()
            jitterBuffer.offer(AudioPacket(seq, data))
        }
    }

    // =========================================================================
    // RECORDING LOGIC
    // =========================================================================

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startRecording(onDataReady: (ByteArray) -> Unit) {
        if (isRecording.get() || audioRecord != null) return

        try {
            val minBufSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            @SuppressLint("MissingPermission")
            audioRecord = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBufSize * 2)

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(tag, "AudioRecord failed to initialize")
                return
            }

            setupAudioEffects()
            audioRecord?.startRecording()

            startRecordingThread(FRAME_SIZE, onDataReady)
            Log.d(tag, "Recording started ($sampleRate Hz).")
        } catch (e: Exception) {
            Log.e(tag, "Recording initialization failed", e)
            stopRecording()
        }
    }

    private fun startRecordingThread(frameSizeShorts: Int, onDataReady: (ByteArray) -> Unit) {
        if (!isRecording.compareAndSet(false, true)) return
        recordingThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val pcmBuffer = ShortArray(frameSizeShorts)

            try {
                while (isRecording.get()) {
                    val recorder = audioRecord ?: break

                    var readSize = 0
                    while (readSize < frameSizeShorts && isRecording.get()) {
                        val result = recorder.read(pcmBuffer, readSize, frameSizeShorts - readSize)
                        if (result > 0) {
                            readSize += result
                        } else {
                            break // Error or stopped
                        }
                    }

                    if (readSize == frameSizeShorts) {
                        var maxAmplitude = 0
                        for (i in 0 until readSize) {
                            val absValue = abs(pcmBuffer[i].toInt())
                            if (absValue > maxAmplitude) maxAmplitude = absValue
                            if (absValue > GAIN_LIMIT_THRESHOLD) {
                                pcmBuffer[i] = (if (pcmBuffer[i] > 0) GAIN_LIMIT_THRESHOLD else -GAIN_LIMIT_THRESHOLD).toShort()
                            }
                        }

                        if (maxAmplitude > NOISE_GATE_THRESHOLD) {
                            val rawBytes = ShortToByte(pcmBuffer, readSize)
                            onDataReady(rawBytes) // Direct PCM Transmission
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Recording thread stopped: ${e.message}")
            }
        }, "AudioRecordingThread").apply { start() }
    }

    fun stopRecording() {
        if (!isRecording.compareAndSet(true, false)) return

        try {
            Log.d(tag, "Releasing Microphone Hardware...")

            val recorder = audioRecord
            audioRecord = null

            try {
                recorder?.stop()
            } catch (e: IllegalStateException) {
                // Ignore if not initialized
            }
            recorder?.release()

            // Release effects
            aec?.release(); aec = null
            ns?.release(); ns = null
            agc?.release(); agc = null

            recordingThread?.interrupt()
            recordingThread?.join(100)
            recordingThread = null

        } catch (e: Exception) {
            Log.e(tag, "Error freeing microphone", e)
        }
        Log.d(tag, "Microphone released.")
    }

    private fun ShortToByte(shorts: ShortArray, readSize: Int): ByteArray {
        val bytes = ByteArray(readSize * 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(shorts, 0, readSize)
        return bytes
    }

    private fun setupAudioEffects() {
        val sessionId = audioRecord?.audioSessionId ?: 0
        if (sessionId == 0) return
        if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
        if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
        if (AutomaticGainControl.isAvailable()) agc = AutomaticGainControl.create(sessionId)?.apply { enabled = true }
    }

    fun stopPlayback() {
        if (!isPlaying.compareAndSet(true, false)) return
        try {
            synchronized(jitterBuffer) { jitterBuffer.clear() }

            playbackThread?.interrupt()
            playbackThread?.join(100)
            playbackThread = null

            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.release()
            audioTrack = null

        } catch (e: Exception) {
            Log.e(tag, "Error stopping playback", e)
        }
        Log.d(tag, "Playback stopped.")
    }

    fun shutdown() {
        stopRecording()
        stopPlayback()
    }
}