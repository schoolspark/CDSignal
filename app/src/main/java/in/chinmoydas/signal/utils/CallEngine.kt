package `in`.chinmoydas.signal.utils

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Arrays

object CallEngine {

    private const val TAG = "CallEngine"
    private const val SAMPLE_RATE = 16000
    private const val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO
    private const val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private const val CALL_PORT = 50006

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    // [FIX] Full Audio Processing Stack
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var autoGainControl: AutomaticGainControl? = null

    var isCallActive = false
        private set
    var isMuted = false

    private var recordJob: Job? = null
    private var playJob: Job? = null

    private var callSocket: DatagramSocket? = null
    private var targetIp: String? = null

    @SuppressLint("MissingPermission")
    fun startCall(ip: String) {
        if (isCallActive) return
        Log.d(TAG, "Starting Call Engine to $ip")

        targetIp = ip
        isCallActive = true
        isMuted = false

        try {
            callSocket = DatagramSocket(CALL_PORT)

            val minBufRec = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT) * 2
            val minBufTrack = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_OUT, AUDIO_FORMAT) * 2

            audioTrack = AudioTrack(
                AudioManager.STREAM_VOICE_CALL,
                SAMPLE_RATE,
                CHANNEL_CONFIG_OUT,
                AUDIO_FORMAT,
                minBufTrack,
                AudioTrack.MODE_STREAM
            )

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_CONFIG_IN,
                AUDIO_FORMAT,
                minBufRec
            )

            val sessionId = audioRecord!!.audioSessionId

            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(sessionId).apply { enabled = true }
            }
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId).apply { enabled = true }
            }
            if (AutomaticGainControl.isAvailable()) {
                autoGainControl = AutomaticGainControl.create(sessionId).apply { enabled = true }
            }

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
            audioRecord?.stop(); audioRecord?.release()
            audioTrack?.stop(); audioTrack?.release()
            echoCanceler?.release()
            noiseSuppressor?.release()
            autoGainControl?.release()
            callSocket?.close()
        } catch (e: Exception) { }

        audioRecord = null
        audioTrack = null
        echoCanceler = null; noiseSuppressor = null; autoGainControl = null
        callSocket = null
    }

    private fun startSendingLoop(bufSize: Int) {
        recordJob = CoroutineScope(Dispatchers.IO).launch {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

            val address = try { InetAddress.getByName(targetIp) } catch (e: Exception) { null }
            if (address == null) return@launch

            val buffer = ByteArray(bufSize)

            while (isActive && isCallActive) {
                if (isMuted) {
                    Arrays.fill(buffer, 0)
                    try {
                        callSocket?.send(DatagramPacket(buffer, buffer.size, address, CALL_PORT))
                        Thread.sleep(20)
                    } catch (e: Exception) {}
                } else {
                    val read = audioRecord?.read(buffer, 0, bufSize) ?: 0
                    if (read > 0) {
                        try {
                            callSocket?.send(DatagramPacket(buffer, read, address, CALL_PORT))
                        } catch (e: Exception) { }
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
                } catch (e: Exception) { }
            }
        }
    }

    fun toggleMute(mute: Boolean) {
        isMuted = mute
    }
}