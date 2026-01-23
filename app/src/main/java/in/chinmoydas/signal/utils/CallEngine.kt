package `in`.chinmoydas.signal.utils

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

object CallEngine {

    private const val TAG = "CallEngine"
    private const val SAMPLE_RATE = 16000 // 16kHz for Voice Quality
    private const val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO
    private const val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private const val CALL_PORT = 50006 // Separate Port from PTT (50005)

    // Audio Hardware
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var echoCanceler: AcousticEchoCanceler? = null

    // State
    var isCallActive = false
        private set
    private var recordJob: Job? = null
    private var playJob: Job? = null

    // Network
    private var callSocket: DatagramSocket? = null
    private var targetIp: String? = null

    @SuppressLint("MissingPermission")
    fun startCall(ip: String) {
        if (isCallActive) return
        Log.d(TAG, "Starting Call Engine to $ip")

        targetIp = ip
        isCallActive = true

        try {
            // 1. Setup Network
            callSocket = DatagramSocket(CALL_PORT)

            // 2. Calculate Buffers
            val minBufRec = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT)
            val minBufTrack = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_OUT, AUDIO_FORMAT)

            // 3. Setup Speaker (STREAM_VOICE_CALL is critical for volume control)
            audioTrack = AudioTrack(
                AudioManager.STREAM_VOICE_CALL,
                SAMPLE_RATE,
                CHANNEL_CONFIG_OUT,
                AUDIO_FORMAT,
                minBufTrack,
                AudioTrack.MODE_STREAM
            )

            // 4. Setup Mic (VOICE_COMMUNICATION enables system Echo Suppression)
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_CONFIG_IN,
                AUDIO_FORMAT,
                minBufRec
            )

            // 5. Enable Hardware AEC
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(audioRecord!!.audioSessionId)
                echoCanceler?.enabled = true
                Log.i(TAG, "Hardware AEC Enabled")
            } else {
                Log.w(TAG, "Hardware AEC Not Supported")
            }

            // 6. Start
            audioTrack?.play()
            audioRecord?.startRecording()

            startSendingLoop(minBufRec)
            startReceivingLoop(minBufTrack)

        } catch (e: Exception) {
            Log.e(TAG, "Engine Start Failed: ${e.message}")
            stopCall()
        }
    }

    fun stopCall() {
        if (!isCallActive) return
        Log.d(TAG, "Stopping Call Engine")

        isCallActive = false
        recordJob?.cancel()
        playJob?.cancel()

        try {
            audioRecord?.stop()
            audioRecord?.release()
            echoCanceler?.release()
            audioTrack?.stop()
            audioTrack?.release()
            callSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup Error: ${e.message}")
        }

        audioRecord = null
        audioTrack = null
        echoCanceler = null
        callSocket = null
    }

    private fun startSendingLoop(bufSize: Int) {
        recordJob = CoroutineScope(Dispatchers.IO).launch {
            val buffer = ByteArray(bufSize)
            val address = InetAddress.getByName(targetIp)

            while (isActive && isCallActive) {
                val read = audioRecord?.read(buffer, 0, bufSize) ?: 0
                if (read > 0) {
                    try {
                        val packet = DatagramPacket(buffer, read, address, CALL_PORT)
                        callSocket?.send(packet)
                    } catch (e: Exception) { /* Ignore send errors */ }
                }
            }
        }
    }

    private fun startReceivingLoop(bufSize: Int) {
        playJob = CoroutineScope(Dispatchers.IO).launch {
            val buffer = ByteArray(bufSize)
            val packet = DatagramPacket(buffer, buffer.size)

            while (isActive && isCallActive) {
                try {
                    callSocket?.receive(packet)
                    audioTrack?.write(packet.data, 0, packet.length)
                } catch (e: Exception) { /* Ignore receive errors */ }
            }
        }
    }
}