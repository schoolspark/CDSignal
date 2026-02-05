package `in`.chinmoydas.signal.utils

import android.annotation.SuppressLint
import android.media.*
import android.media.audiofx.*
import android.os.Process
import android.util.Log
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentSkipListMap
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

object CallEngine {

    private const val TAG = "CallEngine"
    private const val SAMPLE_RATE = 16000
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    // [TUNING] Carrier Grade Settings
    private const val JITTER_BUFFER_MS = 60 // 60ms "Waiting Room" to fix ordering
    private const val PACKET_INTERVAL_MS = 20 // Send audio every 20ms

    // [BREACH PROTOCOL]
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
    @Volatile private var activeTargetPort: Int = CALL_PORT
    private var activeSecretKey: SecretKeySpec? = null

    private var recordJob: Job? = null
    private var playJob: Job? = null
    private var netJob: Job? = null
    private var punchJob: Job? = null

    private val _muteStatus = kotlinx.coroutines.flow.MutableStateFlow(false)

    // [JITTER BUFFER] Sorts packets by Sequence Number automatically
    private val jitterBuffer = ConcurrentSkipListMap<Int, ByteArray>()

    // [FEC] Redundancy Cache
    @Volatile private var previousFrame: ByteArray? = null

    @SuppressLint("MissingPermission")
    fun startCall(ip: String, secretKey: SecretKeySpec? = null) {
        if (isCallActive) return
        Log.d(TAG, "STARTING CARRIER GRADE VOIP -> $ip")

        // Reset State
        activeTargetPort = CALL_PORT
        updateTargetIp(ip)
        activeSecretKey = secretKey
        isCallActive = true
        _muteStatus.value = false
        jitterBuffer.clear()
        previousFrame = null

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. Networking (Aggressive Timeout)
                callSocket = DatagramSocket(CALL_PORT).apply {
                    reuseAddress = true
                    soTimeout = 1000 // 1s disconnect timeout
                    receiveBufferSize = 64 * 1024 // Large OS buffer
                }

                punchHole(ip)

                // 2. Audio Hardware (Low Latency Mode)
                // Calculate precise buffer size for 20ms chunks
                val frameSize = (SAMPLE_RATE * PACKET_INTERVAL_MS / 1000) * 2 // 16-bit = 2 bytes
                val minBufRec = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AUDIO_FORMAT) * 2
                val minBufTrack = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AUDIO_FORMAT) * 2

                val track = AudioTrack(
                    AudioManager.STREAM_VOICE_CALL,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AUDIO_FORMAT,
                    maxOf(minBufTrack, frameSize * 4), // Ensure track has breathing room
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
                    throw Exception("Hardware Init Failed")
                }

                // 3. Audio Effects (Critical for Speakerphone)
                val sId = record.audioSessionId
                if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(sId)?.apply { enabled = true }
                if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(sId)?.apply { enabled = true }
                if (AutomaticGainControl.isAvailable()) agc = AutomaticGainControl.create(sId)?.apply { enabled = true }

                record.startRecording()
                track.play()

                // 4. Start Triple-Thread Architecture
                startMicrophoneLoop(frameSize)   // Thread A: Capture & Send
                startNetworkLoop()               // Thread B: Receive & Sort
                startSpeakerLoop(frameSize)      // Thread C: De-Jitter & Play

            } catch (e: Exception) {
                Log.e(TAG, "Startup Crash: ${e.message}")
                stopCall()
            }
        }
    }

    // --- THREAD A: MICROPHONE & SENDER (With Redundancy) ---
    private fun startMicrophoneLoop(frameSize: Int) {
        recordJob = CoroutineScope(Dispatchers.IO).launch {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val rawBuffer = ShortArray(frameSize / 2)
            var seqNumber = 0

            while (isActive && isCallActive) {
                // [FIX] Capture volatile address locally as InetAddress?
                // This prevents the 'Any' type mismatch error
                val currentAddress: InetAddress? = targetAddress

                if (currentAddress == null) {
                    delay(50)
                    continue
                }

                if (_muteStatus.value) {
                    delay(20)
                } else {
                    val read = audioRecord?.read(rawBuffer, 0, rawBuffer.size) ?: 0
                    if (read > 0) {
                        // 1. Compress Current Frame
                        val currentEncoded = G711.encode(rawBuffer, read)

                        // 2. Get Previous Frame (for Redundancy)
                        val prevEncoded = previousFrame ?: ByteArray(0)
                        previousFrame = currentEncoded // Save current for next loop

                        // 3. Send Both (Packet = [Header][Current][Previous])
                        sendPacket(currentEncoded, prevEncoded, seqNumber++, currentAddress)
                    }
                }
            }
        }
    }

    private fun sendPacket(current: ByteArray, previous: ByteArray, seq: Int, address: InetAddress) {
        try {
            // Payload = [Len_Cur(2)][Current_Data][Previous_Data]
            val payload = ByteBuffer.allocate(2 + current.size + previous.size)
                .putShort(current.size.toShort())
                .put(current)
                .put(previous)
                .array()

            // Encrypt
            val key = activeSecretKey
            val encrypted = if (key != null) CryptoEngine.encrypt(payload, seq, key) ?: payload else payload

            // Header = [Seq(4)][Encrypted_Payload]
            val packetData = ByteBuffer.allocate(4 + encrypted.size)
                .putInt(seq)
                .put(encrypted)
                .array()

            callSocket?.send(DatagramPacket(packetData, packetData.size, address, activeTargetPort))
        } catch (e: Exception) {}
    }

    // --- THREAD B: NETWORK RECEIVER (Packet Sorting) ---
    private fun startNetworkLoop() {
        netJob = CoroutineScope(Dispatchers.IO).launch {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buffer = ByteArray(4096)
            val p = DatagramPacket(buffer, buffer.size)

            while (isActive && isCallActive) {
                try {
                    callSocket?.receive(p)
                    if (p.length < 4) continue

                    // [LATCHING] Fixes Mobile Data NAT issues
                    if (p.port != activeTargetPort) activeTargetPort = p.port

                    val wrapped = ByteBuffer.wrap(p.data, 0, p.length)
                    val seq = wrapped.int // Header

                    val payloadLen = p.length - 4
                    val encrypted = ByteArray(payloadLen)
                    wrapped.get(encrypted)

                    val key = activeSecretKey
                    val decrypted = if (key != null) CryptoEngine.decrypt(encrypted, seq, key) else encrypted

                    if (decrypted != null) {
                        // DECODE HYBRID PACKET
                        val bb = ByteBuffer.wrap(decrypted)
                        val curLen = bb.short.toInt()

                        val currentFrame = ByteArray(curLen)
                        bb.get(currentFrame)

                        val prevLen = decrypted.size - 2 - curLen
                        val prevFrame = ByteArray(maxOf(0, prevLen))
                        if (prevLen > 0) bb.get(prevFrame)

                        // [CRITICAL] Insert into Jitter Buffer
                        // 1. Add Current Frame
                        if (!jitterBuffer.containsKey(seq)) {
                            jitterBuffer[seq] = currentFrame
                        }

                        // 2. Recover LOST Previous Frame (FEC)
                        // If we missed packet (seq-1), we restore it now from this packet's backup!
                        if (seq > 0 && !jitterBuffer.containsKey(seq - 1) && prevFrame.isNotEmpty()) {
                            Log.w(TAG, "FEC: Recovered lost packet ${seq - 1} using redundancy")
                            jitterBuffer[seq - 1] = prevFrame
                        }
                    }
                } catch (e: Exception) {
                    // Socket Timeout is normal, just loop
                }
            }
        }
    }

    // --- THREAD C: SPEAKER LOOP (De-Jitter & Play) ---
    private fun startSpeakerLoop(frameSize: Int) {
        playJob = CoroutineScope(Dispatchers.IO).launch {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

            var nextSeqToPlay = -1
            var buffering = true

            while (isActive && isCallActive) {
                // Initial Buffering (Wait for 3 packets)
                if (buffering) {
                    if (jitterBuffer.size >= 3) {
                        buffering = false
                        nextSeqToPlay = jitterBuffer.firstKey()
                    } else {
                        delay(10)
                        continue
                    }
                }

                // Check if we have the next packet
                val data = jitterBuffer.remove(nextSeqToPlay)

                if (data != null) {
                    // Packet Found! Decode G711 -> PCM
                    val pcm = G711.decode(data, data.size)
                    audioTrack?.write(pcm, 0, pcm.size)
                    nextSeqToPlay++
                } else {
                    // Packet Missing (Even after FEC recovery)
                    // Check if we fell too far behind (Latency catch-up)
                    if (jitterBuffer.isNotEmpty() && jitterBuffer.firstKey() > nextSeqToPlay + 10) {
                        // We are 10 packets behind -> Jump ahead
                        nextSeqToPlay = jitterBuffer.firstKey()
                    } else {
                        // Genuine network gap -> Wait briefly (Concealment) or Silence
                        // Ideally write comfort noise here, but silence is okay for now
                        delay(5)
                    }
                }
            }
        }
    }

    private fun punchHole(targetIp: String) {
        punchJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val address = InetAddress.getByName(targetIp)
                val dummy = "HOLE_PUNCH".toByteArray()
                repeat(15) {
                    TARGET_PORTS.forEach { port ->
                        try {
                            callSocket?.send(DatagramPacket(dummy, dummy.size, address, port))
                        } catch (e: Exception) {}
                    }
                    delay(40)
                }
            } catch (e: Exception) {}
        }
    }

    fun updateTargetIp(newIp: String) {
        try {
            targetAddress = InetAddress.getByName(newIp)
            if (isCallActive) punchHole(newIp)
        } catch (e: Exception) {}
    }

    fun toggleMute() { _muteStatus.value = !_muteStatus.value }

    fun stopCall() {
        isCallActive = false
        _muteStatus.value = false
        targetAddress = null
        activeSecretKey = null
        previousFrame = null
        jitterBuffer.clear()

        recordJob?.cancel(); playJob?.cancel(); netJob?.cancel(); punchJob?.cancel()

        try { callSocket?.close() } catch (e: Exception) {}
        try { audioRecord?.stop(); audioRecord?.release() } catch (e: Exception) {}
        try { audioTrack?.stop(); audioTrack?.release() } catch (e: Exception) {}
    }
}