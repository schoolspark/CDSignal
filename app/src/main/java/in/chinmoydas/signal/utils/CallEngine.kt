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
import java.nio.ByteBuffer
import java.util.Arrays
import javax.crypto.spec.SecretKeySpec

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

    // [FIX] Volatile Target Address for Roaming Support
    @Volatile private var targetAddress: InetAddress? = null

    // [FIX] Encryption Key
    private var activeSecretKey: SecretKeySpec? = null

    private var recordJob: Job? = null
    private var playJob: Job? = null

    // UI State
    private val _muteStatus = MutableStateFlow(false)
    val muteStatus: StateFlow<Boolean> = _muteStatus.asStateFlow()

    @SuppressLint("MissingPermission")
    fun startCall(ip: String, secretKey: SecretKeySpec? = null) {
        if (isCallActive) return
        Log.d(TAG, "Starting Secure Call Engine to $ip")

        // [FIX] Update Address immediately
        updateTargetIp(ip)
        activeSecretKey = secretKey

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

    // [FIX] Dynamic Roaming Support
    fun updateTargetIp(newIp: String) {
        try {
            targetAddress = InetAddress.getByName(newIp)
            Log.d(TAG, "Call Target Updated: $newIp")
        } catch (e: Exception) {
            Log.e(TAG, "Invalid IP Update: $newIp")
        }
    }

    private fun startSendingLoop(bufSize: Int) {
        recordJob = CoroutineScope(Dispatchers.IO).launch {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

            val buffer = ByteArray(bufSize)
            var seqNumber = 0

            while (isActive && isCallActive) {
                val address = targetAddress // Local copy for thread safety
                if (address == null) {
                    delay(100)
                    continue
                }

                if (_muteStatus.value) {
                    // Send small keep-alive packet (Encrypted if key exists)
                    val dummy = ByteArray(10)
                    sendPacket(dummy, seqNumber++, address)
                    Thread.sleep(100)
                } else {
                    val read = audioRecord?.read(buffer, 0, bufSize) ?: 0
                    if (read > 0) {
                        val payload = buffer.copyOfRange(0, read)
                        sendPacket(payload, seqNumber++, address)
                    }
                }
            }
        }
    }

    private fun sendPacket(data: ByteArray, seq: Int, address: InetAddress) {
        try {
            val key = activeSecretKey
            val packetData = if (key != null) {
                CryptoEngine.encrypt(data, seq, key) ?: data
            } else {
                data
            }

            val finalBuf = ByteBuffer.allocate(4 + packetData.size)
                .putInt(seq)
                .put(packetData)
                .array()

            callSocket?.send(DatagramPacket(finalBuf, finalBuf.size, address, CALL_PORT))
        } catch (e: Exception) {
            if (isCallActive) Log.w(TAG, "Send error: ${e.message}")
        }
    }

    private fun startReceivingLoop(bufSize: Int) {
        playJob = CoroutineScope(Dispatchers.IO).launch {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

            val receiveBuffer = ByteArray(bufSize + 100)
            val packet = DatagramPacket(receiveBuffer, receiveBuffer.size)

            while (isActive && isCallActive) {
                try {
                    callSocket?.receive(packet)
                    if (packet.length < 4) continue

                    val wrapped = ByteBuffer.wrap(packet.data, 0, packet.length)
                    val seq = wrapped.int

                    val payloadLen = packet.length - 4
                    val payload = ByteArray(payloadLen)
                    wrapped.get(payload)

                    val key = activeSecretKey
                    val audioData = if (key != null) {
                        CryptoEngine.decrypt(payload, seq, key)
                    } else {
                        payload
                    }

                    if (audioData != null) {
                        audioTrack?.write(audioData, 0, audioData.size)
                    }
                } catch (e: Exception) { }
            }
        }
    }

    fun toggleMute() {
        _muteStatus.value = !_muteStatus.value
    }

    fun stopCall() {
        if (!isCallActive) return
        Log.d(TAG, "Stopping Call Engine")

        isCallActive = false
        _muteStatus.value = false
        targetAddress = null
        activeSecretKey = null

        recordJob?.cancel()
        playJob?.cancel()

        try { callSocket?.close() } catch (e: Exception) {}
        callSocket = null

        try { audioRecord?.stop(); audioRecord?.release() } catch (e: Exception) {}
        audioRecord = null

        try { audioTrack?.stop(); audioTrack?.release() } catch (e: Exception) {}
        audioTrack = null

        try {
            aec?.release(); aec = null
            ns?.release(); ns = null
            agc?.release(); agc = null
        } catch (e: Exception) {}
    }
}