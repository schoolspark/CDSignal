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
import javax.crypto.spec.SecretKeySpec

object CallEngine {

    private const val TAG = "CallEngine"
    private const val SAMPLE_RATE = 16000
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    // [BREACH PROTOCOL] Ports to spray to ensure connection
    private const val CALL_PORT = 50006
    private val TARGET_PORTS = listOf(50006, 50005, 50007)

    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var callSocket: DatagramSocket? = null

    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null

    @Volatile var isCallActive = false
        private set

    @Volatile private var targetAddress: InetAddress? = null
    private var activeSecretKey: SecretKeySpec? = null

    private var recordJob: Job? = null
    private var playJob: Job? = null
    private var punchJob: Job? = null

    private val _muteStatus = MutableStateFlow(false)
    val muteStatus: StateFlow<Boolean> = _muteStatus.asStateFlow()

    @SuppressLint("MissingPermission")
    fun startCall(ip: String, secretKey: SecretKeySpec? = null) {
        if (isCallActive) return
        Log.d(TAG, "BREACH: Starting VoIP Engine to $ip")

        updateTargetIp(ip)
        activeSecretKey = secretKey

        isCallActive = true
        _muteStatus.value = false

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. Setup Network (Robust Bind)
                val socket = try {
                    DatagramSocket(CALL_PORT).apply {
                        reuseAddress = true
                        soTimeout = 2000 // 2s read timeout
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Port 50006 busy, trying random port")
                    DatagramSocket().apply { soTimeout = 2000 }
                }
                callSocket = socket

                // [BREACH PROTOCOL] IMMEDIATE HOLE PUNCH
                punchHole(ip)

                // 2. Init Audio Hardware
                val minBufRec = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AUDIO_FORMAT) * 2
                val minBufTrack = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AUDIO_FORMAT) * 2

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

                if (!isCallActive) { stopCall(); return@launch }

                if (record.state != AudioRecord.STATE_INITIALIZED || track.state != AudioTrack.STATE_INITIALIZED) {
                    throw Exception("Audio hardware init failed")
                }

                // 3. Effects
                val sessionId = record.audioSessionId
                if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
                if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
                if (AutomaticGainControl.isAvailable()) agc = AutomaticGainControl.create(sessionId)?.apply { enabled = true }

                // 4. Start Loops
                track.play()
                record.startRecording()

                startSendingLoop(minBufRec)
                startReceivingLoop(minBufTrack)
                startKeepAliveLoop() // Keep NAT open during silence

            } catch (e: Exception) {
                Log.e(TAG, "Engine Crash: ${e.message}")
                stopCall()
            }
        }
    }

    private fun punchHole(targetIp: String) {
        punchJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val address = InetAddress.getByName(targetIp)
                val dummy = "HOLE_PUNCH".toByteArray()
                Log.d(TAG, "BREACH: Spraying packets to $targetIp")

                repeat(10) {
                    TARGET_PORTS.forEach { port ->
                        try {
                            val p = DatagramPacket(dummy, dummy.size, address, port)
                            callSocket?.send(p)
                        } catch (e: Exception) {}
                    }
                    delay(50)
                }
            } catch (e: Exception) { Log.e(TAG, "Punch failed: ${e.message}") }
        }
    }

    fun updateTargetIp(newIp: String) {
        try {
            targetAddress = InetAddress.getByName(newIp)
            if (isCallActive) punchHole(newIp)
        } catch (e: Exception) { Log.e(TAG, "Invalid IP: $newIp") }
    }

    // [FIXED] Type Mismatch solved here
    private fun startSendingLoop(bufSize: Int) {
        recordJob = CoroutineScope(Dispatchers.IO).launch {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buffer = ByteArray(bufSize)
            var seqNumber = 0

            while (isActive && isCallActive) {
                // Explicit check prevents 'Any' type inference error
                val address = targetAddress
                if (address == null) {
                    delay(100)
                    continue
                }

                if (_muteStatus.value) {
                    delay(100)
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

    private fun startKeepAliveLoop() {
        CoroutineScope(Dispatchers.IO).launch {
            val keepAlive = ByteArray(1)
            var seq = -1
            while (isActive && isCallActive) {
                val address = targetAddress
                if (address != null) {
                    sendPacket(keepAlive, seq--, address)
                }
                delay(500)
            }
        }
    }

    private fun sendPacket(data: ByteArray, seq: Int, address: InetAddress) {
        try {
            val key = activeSecretKey
            val packetData = if (key != null) CryptoEngine.encrypt(data, seq, key) ?: data else data

            val finalBuf = ByteBuffer.allocate(4 + packetData.size)
                .putInt(seq)
                .put(packetData)
                .array()

            callSocket?.send(DatagramPacket(finalBuf, finalBuf.size, address, CALL_PORT))
        } catch (e: Exception) { }
    }

    private fun startReceivingLoop(bufSize: Int) {
        playJob = CoroutineScope(Dispatchers.IO).launch {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val receiveBuffer = ByteArray(bufSize + 200)
            val packet = DatagramPacket(receiveBuffer, receiveBuffer.size)

            while (isActive && isCallActive) {
                try {
                    callSocket?.receive(packet)
                    if (packet.length < 4) continue

                    val wrapped = ByteBuffer.wrap(packet.data, 0, packet.length)
                    val seq = wrapped.int

                    if (seq < 0 || packet.length <= 4) continue

                    val payloadLen = packet.length - 4
                    val payload = ByteArray(payloadLen)
                    wrapped.get(payload)

                    val key = activeSecretKey
                    val audioData = if (key != null) CryptoEngine.decrypt(payload, seq, key) else payload

                    if (audioData != null) {
                        audioTrack?.write(audioData, 0, audioData.size)
                    }
                } catch (e: Exception) { }
            }
        }
    }

    fun toggleMute() { _muteStatus.value = !_muteStatus.value }

    fun stopCall() {
        if (!isCallActive) return
        Log.d(TAG, "Stopping Call Engine")

        isCallActive = false
        _muteStatus.value = false
        targetAddress = null
        activeSecretKey = null

        recordJob?.cancel()
        playJob?.cancel()
        punchJob?.cancel()

        try { callSocket?.close() } catch (e: Exception) {}
        callSocket = null
        try { audioRecord?.stop(); audioRecord?.release() } catch (e: Exception) {}
        audioRecord = null
        try { audioTrack?.stop(); audioTrack?.release() } catch (e: Exception) {}
        audioTrack = null
        try { aec?.release(); ns?.release(); agc?.release() } catch (e: Exception) {}
        aec = null; ns = null; agc = null
    }
}