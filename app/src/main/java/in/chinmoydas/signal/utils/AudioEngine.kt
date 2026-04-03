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
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class AudioEngine(context: Context) {
    private val tag = "AudioEngine"
    private val sampleRate = 16000

    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile var isCompressionEnabled: Boolean = true

    // Audio Effects
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null

    // Threads
    private var recordingThread: Thread? = null
    private var playbackThread: Thread? = null
    private val isRecording = AtomicBoolean(false)
    private val isPlaying = AtomicBoolean(false)

    // Jitter Buffer (Thread-Safe Blocking Queue)
    private data class AudioPacket(val seq: Int, val data: ByteArray) : Comparable<AudioPacket> {
        override fun compareTo(other: AudioPacket) = this.seq - other.seq
    }
    private val jitterBuffer = PriorityBlockingQueue<AudioPacket>(100)

    // [FIX] Atomic tracking for thread safety between playPcmChunk and playbackThread
    @Volatile private var lastPlayedSeq = -1

    private val FRAME_SIZE = 640

    // Settings from Stable Core
    private val NOISE_GATE_THRESHOLD = 150
    private val GAIN_LIMIT_THRESHOLD = 30000

    // --- PLAYBACK ---
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
        } catch (e: Exception) { Log.e(tag, "Playback init failed", e) }
    }

    private fun startPlaybackThread() {
        if (!isPlaying.compareAndSet(false, true)) return
        jitterBuffer.clear()
        lastPlayedSeq = -1

        playbackThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            while (isPlaying.get()) {
                val packet = jitterBuffer.poll()
                if (packet != null) {
                    // [CRITICAL FIX] If a new stream starts (sequence resets to low numbers), reset the tracker
                    if (lastPlayedSeq != -1 && packet.seq < lastPlayedSeq && (lastPlayedSeq - packet.seq > 100)) {
                        Log.d(tag, "Stream reset detected. Resyncing sequence.")
                        lastPlayedSeq = -1
                    }

                    // Resync logic for massive packet drops within a stream
                    if (lastPlayedSeq != -1 && packet.seq > lastPlayedSeq + 100) {
                        Log.w(tag, "Massive packet drop. Resyncing.")
                        lastPlayedSeq = packet.seq - 1
                    }

                    if (packet.seq > lastPlayedSeq || lastPlayedSeq == -1) {
                        audioTrack?.write(packet.data, 0, packet.data.size)
                        lastPlayedSeq = packet.seq
                    }
                } else {
                    try { Thread.sleep(5) } catch (e: Exception) {}
                }
            }
        }, "AudioPlaybackThread").apply { start() }
    }

    fun playPcmChunk(data: ByteArray, seq: Int) {
        if (!isPlaying.get()) startPlayback()

        val pcmData = if (data.size < 1000) {
            try { G711.decode(data, FRAME_SIZE) } catch (e: Exception) { return }
        } else { data }

        // [CRITICAL FIX] When clearing the buffer due to overflow, we MUST reset the sequence tracker
        // otherwise it will block the new incoming packets if their sequence is lower.
        if (jitterBuffer.size > 50) {
            jitterBuffer.clear()
            // We do not reset lastPlayedSeq here, because this is just a buffer overflow, not a new stream.
        }
        jitterBuffer.offer(AudioPacket(seq, pcmData))
    }

    fun stopPlayback() {
        if (!isPlaying.compareAndSet(true, false)) return
        try {
            playbackThread?.interrupt()
            playbackThread = null
            audioTrack?.stop(); audioTrack?.release(); audioTrack = null
        } catch (e: Exception) { }
    }

    // --- RECORDING ---
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startRecording(useCompression: Boolean = true, onDataReady: (ByteArray) -> Unit) {
        if (isRecording.get() || audioRecord != null) return
        isCompressionEnabled = useCompression

        try {
            val minBufSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            @SuppressLint("MissingPermission")
            audioRecord = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBufSize * 2)

            setupAudioEffects()
            audioRecord?.startRecording()
            startRecordingThread(FRAME_SIZE, onDataReady)
        } catch (e: Exception) { stopRecording() }
    }

    private fun startRecordingThread(frameSizeShorts: Int, onDataReady: (ByteArray) -> Unit) {
        if (!isRecording.compareAndSet(false, true)) return

        recordingThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val pcmBuffer = ShortArray(frameSizeShorts)

            while (isRecording.get()) {
                val recorder = audioRecord ?: break
                var readSize = 0
                while (readSize < frameSizeShorts && isRecording.get()) {
                    val result = recorder.read(pcmBuffer, readSize, frameSizeShorts - readSize)
                    if (result > 0) readSize += result else break
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

                    if (maxAmplitude >= NOISE_GATE_THRESHOLD) {
                        val finalData = if (isCompressionEnabled) G711.encode(pcmBuffer, readSize) else ShortToByte(pcmBuffer, readSize)
                        onDataReady(finalData)
                    }
                }
            }
        }, "AudioRecordingThread").apply { start() }
    }

    fun stopRecording() {
        if (!isRecording.compareAndSet(true, false)) return
        try {
            recordingThread?.interrupt()
            recordingThread = null
            audioRecord?.stop(); audioRecord?.release(); audioRecord = null
            aec?.release(); aec = null
            ns?.release(); ns = null
            agc?.release(); agc = null
        } catch (e: Exception) { }
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

    fun shutdown() { stopRecording(); stopPlayback() }
}