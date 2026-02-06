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

object CallEngine {

    private const val TAG = "CallEngine"
    private const val SAMPLE_RATE = 16000
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private const val JITTER_BUFFER_MS = 60
    private const val PACKET_INTERVAL_MS = 20

    // [FIX] Port MUST match NetworkEngine port for P2P to work (Single Socket)
    private const val TARGET_PORT = 50005

    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var audioTrack: AudioTrack? = null

    // Reference to the centralized NetworkEngine
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

    // [INIT] Called by VoiceService when it starts up
    fun initialize(engine: NetworkEngine) {
        this.networkEngine = engine

        // Register callback to receive VoIP packets
        engine.onVoipPacket = { data, senderIp ->
            if (isCallActive && (targetIp == null || senderIp == targetIp)) {
                // Lock onto the first sender IP if we initiated blindly
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

        Log.d(TAG, "STARTING SINGLE-SOCKET CALL -> $ip")

        targetIp = ip
        activeSecretKey = secretKey
        isCallActive = true
        _muteStatus.value = false
        jitterBuffer.clear()
        previousFrame = null

        scope.launch {
            try {
                // 1. Audio Hardware Setup (Low Latency)
                val frameSize = (SAMPLE_RATE * PACKET_INTERVAL_MS / 1000) * 2
                val minBufRec = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AUDIO_FORMAT) * 2
                val minBufTrack = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AUDIO_FORMAT) * 2

                val track = AudioTrack(
                    AudioManager.STREAM_VOICE_CALL,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AUDIO_FORMAT,
                    maxOf(minBufTrack, frameSize * 4),
                    AudioTrack.MODE_STREAM
                )
                audioTrack = track

                val record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AUDIO_FORMAT,
                    maxOf(minBufRec, frameSize * 2)
                )
                audioRecord = record

                if (record.state != AudioRecord.STATE_INITIALIZED || track.state != AudioTrack.STATE_INITIALIZED) {
                    throw Exception("Audio Hardware Init Failed")
                }

                // 2. Audio Effects
                val sId = record.audioSessionId
                if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(sId)?.apply { enabled = true }
                if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(sId)?.apply { enabled = true }
                if (AutomaticGainControl.isAvailable()) agc = AutomaticGainControl.create(sId)?.apply { enabled = true }

                record.startRecording()
                track.play()

                // 3. Start Threads
                startMicrophoneLoop(frameSize)   // Capture & Send
                startSpeakerLoop()               // De-Jitter & Play

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
                    delay(20)
                    continue
                }

                val currentTarget = targetIp ?: continue
                val read = audioRecord?.read(rawBuffer, 0, rawBuffer.size) ?: 0

                if (read > 0) {
                    // Encode
                    val currentEncoded = G711.encode(rawBuffer, read)
                    val prevEncoded = previousFrame ?: ByteArray(0)
                    previousFrame = currentEncoded

                    // Prepare Payload
                    val payload = ByteBuffer.allocate(4 + 2 + currentEncoded.size + prevEncoded.size) // Seq(4) + Len(2) + Data + Redundancy
                        .putInt(seqNumber++)
                        .putShort(currentEncoded.size.toShort())
                        .put(currentEncoded)
                        .put(prevEncoded)
                        .array()

                    // Encrypt
                    val finalPayload = if (activeSecretKey != null) {
                        CryptoEngine.encrypt(payload, seqNumber, activeSecretKey!!) ?: payload
                    } else {
                        payload
                    }

                    // [CRITICAL] Send via NetworkEngine using VoIP Header (0x12)
                    networkEngine?.send(
                        NetworkEngine.TYPE_VOIP_CALL,
                        finalPayload,
                        listOf(currentTarget),
                        TARGET_PORT
                    )
                }
            }
        }
    }

    // Called automatically by NetworkEngine callback
    private fun processIncomingPacket(data: ByteArray) {
        try {
            // Decrypt
            var decrypted = data
            val key = activeSecretKey

            // Peek at Sequence (First 4 bytes)
            val tempBb = ByteBuffer.wrap(data)
            val seq = tempBb.int

            if (key != null) {
                val result = CryptoEngine.decrypt(data, seq, key)
                if (result != null) decrypted = result
            }

            val bb = ByteBuffer.wrap(decrypted)
            val seqNum = bb.int
            val curLen = bb.short.toInt()

            val currentFrame = ByteArray(curLen)
            bb.get(currentFrame)

            // Redundancy extraction
            val prevLen = decrypted.size - 4 - 2 - curLen
            val prevFrame = ByteArray(maxOf(0, prevLen))
            if (prevLen > 0) bb.get(prevFrame)

            // Jitter Buffer Logic
            if (!jitterBuffer.containsKey(seqNum)) {
                jitterBuffer[seqNum] = currentFrame
            }
            // FEC Recovery
            if (seqNum > 0 && !jitterBuffer.containsKey(seqNum - 1) && prevFrame.isNotEmpty()) {
                jitterBuffer[seqNum - 1] = prevFrame
            }

        } catch (e: Exception) { }
    }

    private fun startSpeakerLoop() {
        playJob = scope.launch {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            var nextSeqToPlay = -1
            var buffering = true

            while (isActive && isCallActive) {
                if (buffering) {
                    if (jitterBuffer.size >= 3) {
                        buffering = false
                        nextSeqToPlay = jitterBuffer.firstKey()
                    } else {
                        delay(10)
                        continue
                    }
                }

                val data = jitterBuffer.remove(nextSeqToPlay)
                if (data != null) {
                    val pcm = G711.decode(data, data.size)
                    audioTrack?.write(pcm, 0, pcm.size)
                    nextSeqToPlay++
                } else {
                    // Packet Loss / Catch-up logic
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

        // Don't close NetworkEngine! It's shared.

        recordJob?.cancel(); playJob?.cancel()

        try { audioRecord?.stop(); audioRecord?.release() } catch (e: Exception) {}
        try { audioTrack?.stop(); audioTrack?.release() } catch (e: Exception) {}
    }
}