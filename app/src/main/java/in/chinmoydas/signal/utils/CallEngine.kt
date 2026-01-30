package `in`.chinmoydas.signal.utils

import android.annotation.SuppressLint
import android.media.*
import android.media.audiofx.*
import android.os.Process
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Arrays

object CallEngine {

    private const val TAG = "CallEngine"
    private const val SAMPLE_RATE = 16000
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private const val CALL_PORT = 50006

    // [VOLATILE] Critical for thread safety
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var callSocket: DatagramSocket? = null

    // Audio Effects
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null

    // State
    @Volatile var isCallActive = false
        private set

    private var targetIp: String = ""
    private var recordJob: Job? = null
    private var playJob: Job? = null

    // UI State
    private val _muteStatus = MutableStateFlow(false)
    val muteStatus: StateFlow<Boolean> = _muteStatus.asStateFlow()

    @SuppressLint("MissingPermission")
    fun startCall(ip: String) {
        if (isCallActive) return
        Log.d(TAG, "Starting Call Engine to $ip")

        targetIp = ip
        isCallActive = true
        _muteStatus.value = false

        // Launch on IO to avoid blocking Main Thread during hardware init
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. Setup Network (2s timeout to detect dead connection)
                val socket = DatagramSocket(CALL_PORT).apply {
                    soTimeout = 2000
                }
                callSocket = socket

                // [FIX] Safety Check 1: Did user hang up while we were opening the socket?
                if (!isCallActive) {
                    socket.close()
                    return@launch
                }

                // 2. Calculate Buffers
                val minBufRec = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AUDIO_FORMAT) * 2
                val minBufTrack = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AUDIO_FORMAT) * 2

                // 3. Init Audio Hardware
                val track = AudioTrack(
                    AudioManager.STREAM_VOICE_CALL,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AUDIO_FORMAT,
                    minBufTrack,
                    AudioTrack.MODE_STREAM
                )
                audioTrack = track

                val record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AUDIO_FORMAT,
                    minBufRec
                )
                audioRecord = record

                // [FIX] Safety Check 2: Did user hang up while we were creating audio tracks?
                if (!isCallActive) {
                    stopCall() // Triggers full cleanup
                    return@launch
                }

                // [CRITICAL] Verify Hardware Init
                if (record.state != AudioRecord.STATE_INITIALIZED || track.state != AudioTrack.STATE_INITIALIZED) {
                    throw Exception("Audio hardware initialization failed")
                }

                // 4. Setup Effects (if available)
                val sessionId = record.audioSessionId
                if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
                if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
                if (AutomaticGainControl.isAvailable()) agc = AutomaticGainControl.create(sessionId)?.apply { enabled = true }

                // 5. Start Stream
                track.play()
                record.startRecording()

                startSendingLoop(minBufRec)
                startReceivingLoop(minBufTrack)

            } catch (e: Exception) {
                Log.e(TAG, "Engine Start Failed: ${e.message}")
                stopCall()
            }
        }
    }

    private fun startSendingLoop(bufSize: Int) {
        recordJob = CoroutineScope(Dispatchers.IO).launch {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

            // Resolve once to save CPU
            val address = try { InetAddress.getByName(targetIp) } catch (e: Exception) { null }
            if (address == null) { stopCall(); return@launch }

            val buffer = ByteArray(bufSize)

            while (isActive && isCallActive) {
                if (_muteStatus.value) {
                    // Send silence keep-alive (smaller packet)
                    Arrays.fill(buffer, 0)
                    try {
                        callSocket?.send(DatagramPacket(buffer, 10, address, CALL_PORT))
                        Thread.sleep(100) // Lower frequency
                    } catch (e: Exception) {}
                } else {
                    val read = audioRecord?.read(buffer, 0, bufSize) ?: 0
                    if (read > 0) {
                        try {
                            callSocket?.send(DatagramPacket(buffer, read, address, CALL_PORT))
                        } catch (e: Exception) {
                            if (isCallActive) Log.w(TAG, "Send error: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    private fun startReceivingLoop(bufSize: Int) {
        playJob = CoroutineScope(Dispatchers.IO).launch {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

            val buffer = ByteArray(bufSize)
            val packet = DatagramPacket(buffer, buffer.size)

            while (isActive && isCallActive) {
                try {
                    callSocket?.receive(packet)
                    audioTrack?.write(packet.data, 0, packet.length)
                } catch (e: Exception) {
                    // Socket timeout or error - expected loop behavior
                }
            }
        }
    }

    fun toggleMute() {
        _muteStatus.value = !_muteStatus.value
    }

    fun stopCall() {
        if (!isCallActive) return
        Log.d(TAG, "Stopping Call Engine")

        // 1. Set flag first to stop loops
        isCallActive = false
        _muteStatus.value = false

        // 2. Kill Coroutines
        recordJob?.cancel()
        playJob?.cancel()

        // 3. Safe Socket Close
        try { callSocket?.close() } catch (e: Exception) {}
        callSocket = null

        // 4. Safe Audio Release
        try { audioRecord?.stop(); audioRecord?.release() } catch (e: Exception) {}
        audioRecord = null

        try { audioTrack?.stop(); audioTrack?.release() } catch (e: Exception) {}
        audioTrack = null

        // 5. Effects Cleanup
        try {
            aec?.release(); aec = null
            ns?.release(); ns = null
            agc?.release(); agc = null
        } catch (e: Exception) {}
    }
}