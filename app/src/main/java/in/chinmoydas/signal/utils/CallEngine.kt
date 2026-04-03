package `in`.chinmoydas.signal.utils

import android.annotation.SuppressLint
import android.media.*
import android.media.audiofx.*
import android.os.Process
import android.util.Log
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentSkipListMap
import javax.crypto.spec.SecretKeySpec

/**
 * CD Signal - High-Performance VoIP Engine
 * Upgraded for Mothership Single-Socket Architecture (2026)
 */
object CallEngine {

    private const val TAG = "CallEngine"
    private const val SAMPLE_RATE = 16000
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private const val PACKET_INTERVAL_MS = 20

    // [FIX] MUST match NetworkEngine port for P2P/Reflector symmetry
    private const val TARGET_PORT = 50005

    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var audioTrack: AudioTrack? = null

    private var networkEngine: NetworkEngine? = null

    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null

    @Volatile var isCallActive = false
        private set

    @Volatile private var targetIp: String? = null
    private var activeSecretKey: SecretKeySpec? = null

    private var recordJob: Job? = null
    private var playJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _muteStatus = kotlinx.coroutines.flow.MutableStateFlow(false)
    private val jitterBuffer = ConcurrentSkipListMap<Int, ByteArray>()
    @Volatile private var previousFrame: ByteArray? = null

    /**
     * [INIT] Wire to the Mothership's Central Network Engine
     */
    fun initialize(engine: NetworkEngine) {
        this.networkEngine = engine

        // Register callback for incoming VoIP packets
        engine.onVoipPacket = { data, senderIp ->
            if (isCallActive && (targetIp == null || senderIp == targetIp)) {
                if (targetIp == null) targetIp = senderIp
                processIncomingPacket(data)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startCall(ip: String, secretKey: SecretKeySpec? = null) {
        if (isCallActive) return
        if (networkEngine == null) {
            Log.e(TAG, "CallEngine not initialized with NetworkEngine!")
            return
        }

        Log.d(TAG, "STARTING ENCRYPTED CALL -> $ip")

        targetIp = ip
        activeSecretKey = secretKey
        isCallActive = true
        _muteStatus.value = false
        jitterBuffer.clear()
        previousFrame = null

        scope.launch {
            try {
                val frameSize = (SAMPLE_RATE * PACKET_INTERVAL_MS / 1000) * 2
                val minBufRec = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AUDIO_FORMAT) * 2
                val minBufTrack = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AUDIO_FORMAT) * 2

                audioTrack = AudioTrack(
                    AudioManager.STREAM_VOICE_CALL,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AUDIO_FORMAT,
                    maxOf(minBufTrack, frameSize * 4),
                    AudioTrack.MODE_STREAM
                )

                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AUDIO_FORMAT,
                    maxOf(minBufRec, frameSize * 2)
                )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED || audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                    throw Exception("Audio Hardware Init Failed")
                }

                // Apply DSP Effects
                audioRecord?.audioSessionId?.let { sId ->
                    if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(sId)?.apply { enabled = true }
                    if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(sId)?.apply { enabled = true }
                    if (AutomaticGainControl.isAvailable()) agc = AutomaticGainControl.create(sId)?.apply { enabled = true }
                }

                audioRecord?.startRecording()
                audioTrack?.play()

                startMicrophoneLoop(frameSize)
                startSpeakerLoop()

            } catch (e: Exception) {
                Log.e(TAG, "Startup Crash: ${e.message}")
                stopCall()
            }
        }
    }

    private fun startMicrophoneLoop(frameSize: Int) {
        recordJob = scope.launch {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val rawBuffer = ShortArray(frameSize / 2)
            var seqNumber = 0

            while (isActive && isCallActive) {
                if (_muteStatus.value) {
                    delay(20); continue
                }

                val currentTarget = targetIp ?: continue
                val read = audioRecord?.read(rawBuffer, 0, rawBuffer.size) ?: 0

                if (read > 0) {
                    val currentEncoded = G711.encode(rawBuffer, read)
                    val prevEncoded = previousFrame ?: ByteArray(0)
                    previousFrame = currentEncoded

                    // [UPGRADE] Fix: Capture sequence BEFORE encryption to ensure IV matches header
                    val currentSeq = seqNumber++

                    val payload = ByteBuffer.allocate(6 + currentEncoded.size + prevEncoded.size)
                        .putInt(currentSeq)
                        .putShort(currentEncoded.size.toShort())
                        .put(currentEncoded)
                        .put(prevEncoded)
                        .array()

                    val finalPayload = activeSecretKey?.let {
                        CryptoEngine.encrypt(payload, it)
                    } ?: payload

                    networkEngine?.send(NetworkEngine.TYPE_VOIP_CALL, finalPayload, listOf(currentTarget), TARGET_PORT)
                }
            }
        }
    }

    private fun processIncomingPacket(data: ByteArray) {
        try {
            // 1. Decrypt (Using first 4 bytes as IV)
            val tempBb = ByteBuffer.wrap(data)
            val packetSeq = tempBb.int

            val decrypted = activeSecretKey?.let {
                CryptoEngine.decrypt(data, it)
            } ?: data

            val bb = ByteBuffer.wrap(decrypted)
            val seqNum = bb.int
            val curLen = bb.short.toInt()
            val currentFrame = ByteArray(curLen).also { bb.get(it) }

            // 2. FEC Recovery (Extract redundant previous frame if current is lost)
            val prevLen = decrypted.size - 6 - curLen
            if (prevLen > 0) {
                val prevFrame = ByteArray(prevLen).also { bb.get(it) }
                if (seqNum > 0 && !jitterBuffer.containsKey(seqNum - 1)) {
                    jitterBuffer[seqNum - 1] = prevFrame
                }
            }

            jitterBuffer[seqNum] = currentFrame

            // [UPGRADE] Smart Memory Management: Prune packets older than 1 second
            if (jitterBuffer.size > 50) {
                jitterBuffer.headMap(seqNum - 30).clear()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Decryption/Buffer Error: ${e.message}")
        }
    }

    private fun startSpeakerLoop() {
        playJob = scope.launch {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            var nextSeqToPlay = -1
            var buffering = true

            while (isActive && isCallActive) {
                if (buffering) {
                    // Buffer at least 3 packets to handle jitter
                    if (jitterBuffer.size >= 3) {
                        buffering = false
                        nextSeqToPlay = jitterBuffer.firstKey()
                    } else {
                        delay(10); continue
                    }
                }

                val data = jitterBuffer.remove(nextSeqToPlay)
                if (data != null) {
                    val pcm = G711.decode(data, data.size)
                    audioTrack?.write(pcm, 0, pcm.size)
                    nextSeqToPlay++
                } else {
                    // Catch-up logic for severe network lag
                    if (jitterBuffer.isNotEmpty() && jitterBuffer.firstKey() > nextSeqToPlay + 10) {
                        nextSeqToPlay = jitterBuffer.firstKey()
                    } else {
                        delay(5)
                    }
                }
            }
        }
    }

    fun toggleMute() { _muteStatus.value = !_muteStatus.value }

    fun stopCall() {
        isCallActive = false
        _muteStatus.value = false
        targetIp = null
        activeSecretKey = null
        previousFrame = null
        jitterBuffer.clear()

        recordJob?.cancel(); playJob?.cancel()

        try { audioRecord?.stop(); audioRecord?.release() } catch (e: Exception) {}
        try { audioTrack?.stop(); audioTrack?.release() } catch (e: Exception) {}

        aec?.release(); ns?.release(); agc?.release()
        aec = null; ns = null; agc = null
    }
}