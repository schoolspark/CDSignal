package `in`.chinmoydas.signal.utils

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

class AudioRouter(private val context: Context) {

    private val tag = "AudioRouter"
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // State
    private var isSpeakerPreferred: Boolean = true
    private var isHeadsetPlugged: Boolean = false
    private var isBluetoothConnected: Boolean = false
    private var isFocusHeld: Boolean = false
    private var isVoipCallActive: Boolean = false

    private var activeFocusRequest: AudioFocusRequest? = null
    var onRouteChanged: ((Boolean) -> Unit)? = null

    fun initialize() {
        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_HEADSET_PLUG)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        }

        // [CRITICAL FIX] Android 14+ Requires explicit RECEIVER_NOT_EXPORTED flag
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(headsetReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(headsetReceiver, filter)
        }

        // Initial Check
        @Suppress("DEPRECATION")
        isHeadsetPlugged = audioManager.isWiredHeadsetOn

        // Wrap this in try-catch for Android 12+ permission issues
        try {
            isBluetoothConnected = audioManager.isBluetoothA2dpOn || audioManager.isBluetoothScoOn
        } catch (e: SecurityException) {
            Log.w(tag, "Bluetooth permission missing, assuming disconnected.")
            isBluetoothConnected = false
        }

        updateRoute()
    }

    // --- COMPATIBILITY BRIDGE for VoiceService ---
    fun setVoipMode(isActive: Boolean) {
        setCallMode(isActive)
    }

    fun setSpeakerphone(enable: Boolean) {
        if (audioManager.isSpeakerphoneOn != enable) {
            audioManager.isSpeakerphoneOn = enable
        }
    }
    // ---------------------------------------------------

    private val headsetReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val action = intent?.action

            when (action) {
                AudioManager.ACTION_HEADSET_PLUG -> {
                    val state = intent.getIntExtra("state", -1)
                    isHeadsetPlugged = (state == 1)
                    updateRoute()
                }
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
                    isBluetoothConnected = (state == BluetoothProfile.STATE_CONNECTED)
                    updateRoute()
                }
                AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED -> {
                    val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                    if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                        Log.d(tag, "Bluetooth SCO Connected. Forcing Voice Mode.")
                        if (isVoipCallActive) {
                            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                        }
                    } else if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED) {
                        Log.d(tag, "Bluetooth SCO Disconnected.")
                        // Only restart if we still have permission and should be connected
                        if (isBluetoothConnected && isVoipCallActive && isFocusHeld) {
                            startBluetoothSco()
                        }
                    }
                }
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

        // Apply Mode
        if (isVoipCallActive) {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        } else {
            audioManager.mode = AudioManager.MODE_NORMAL
        }
    }

    private fun startBluetoothSco() {
        try {
            if (!audioManager.isBluetoothScoOn) {
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to start Bluetooth SCO: ${e.message}")
        }
    }

    private fun stopBluetoothSco() {
        try {
            if (audioManager.isBluetoothScoOn) {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to stop Bluetooth SCO: ${e.message}")
        }
    }

    fun setCallMode(active: Boolean) {
        if (isVoipCallActive != active) {
            isVoipCallActive = active
            updateRoute()
        }
    }

    fun setSpeakerPreferred(preferSpeaker: Boolean) {
        if (isSpeakerPreferred != preferSpeaker) {
            isSpeakerPreferred = preferSpeaker
            updateRoute()
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
                    if (focusChange == AudioManager.AUDIOFOCUS_LOSS || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                        abandonFocus()
                    }
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
        isFocusHeld = true // Force abandonFocus to execute
        abandonFocus()
    }
}