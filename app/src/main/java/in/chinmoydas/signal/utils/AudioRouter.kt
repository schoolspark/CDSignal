package `in`.chinmoydas.signal.utils

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

class AudioRouter(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // State
    private var isSpeakerPreferred: Boolean = true
    private var isHeadsetPlugged: Boolean = false
    private var isBluetoothConnected: Boolean = false // NEW
    private var isFocusHeld: Boolean = false
    private var isVoipCallActive: Boolean = false

    private var activeFocusRequest: AudioFocusRequest? = null
    var onRouteChanged: ((Boolean) -> Unit)? = null

    fun initialize() {
        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_HEADSET_PLUG)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED) // NEW
        }
        context.registerReceiver(headsetReceiver, filter)

        // Initial Check
        isHeadsetPlugged = audioManager.isWiredHeadsetOn
        isBluetoothConnected = audioManager.isBluetoothA2dpOn || audioManager.isBluetoothScoOn
        updateRoute()
    }

    private val headsetReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val action = intent?.action
            if (action == AudioManager.ACTION_HEADSET_PLUG) {
                val state = intent.getIntExtra("state", -1)
                isHeadsetPlugged = (state == 1)
                updateRoute()
            } else if (action == BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
                isBluetoothConnected = (state == BluetoothProfile.STATE_CONNECTED)
                updateRoute()
            }
        }
    }

    private fun updateRoute() {
        // Bluetooth takes priority, then Wired Headset, then Speaker
        if (isBluetoothConnected || isHeadsetPlugged) {
            setSpeakerphone(false)
            onRouteChanged?.invoke(false) // Inform UI: "Headset Mode"
            if (isBluetoothConnected && isVoipCallActive) {
                startBluetoothSco()
            }
        } else {
            setSpeakerphone(isSpeakerPreferred)
            onRouteChanged?.invoke(isSpeakerPreferred)
            stopBluetoothSco()
        }

        if (isVoipCallActive) {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        } else {
            audioManager.mode = AudioManager.MODE_NORMAL
        }
    }

    private fun startBluetoothSco() {
        if (!audioManager.isBluetoothScoOn) {
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
        }
    }

    private fun stopBluetoothSco() {
        if (audioManager.isBluetoothScoOn) {
            audioManager.stopBluetoothSco()
            audioManager.isBluetoothScoOn = false
        }
    }

    fun setCallMode(active: Boolean) {
        isVoipCallActive = active
        updateRoute()
    }

    fun setSpeakerPreferred(preferSpeaker: Boolean) {
        isSpeakerPreferred = preferSpeaker
        updateRoute()
    }

    private fun setSpeakerphone(on: Boolean) {
        if (audioManager.isSpeakerphoneOn != on) {
            audioManager.isSpeakerphoneOn = on
        }
    }

    fun requestFocus(): Boolean {
        if (isFocusHeld) {
            updateRoute()
            return true
        }

        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener { focusChange ->
                    if (focusChange == AudioManager.AUDIOFOCUS_LOSS) abandonFocus()
                }
                .build()

            activeFocusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        }

        isFocusHeld = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        if (isFocusHeld) updateRoute()
        return isFocusHeld
    }

    fun abandonFocus() {
        if (isVoipCallActive) return
        if (!isFocusHeld) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activeFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            activeFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }

        isFocusHeld = false
        stopBluetoothSco()
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    fun shutdown() {
        try { context.unregisterReceiver(headsetReceiver) } catch (e: Exception) { }
        isVoipCallActive = false
        isFocusHeld = true
        abandonFocus()
    }
}