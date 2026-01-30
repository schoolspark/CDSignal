package `in`.chinmoydas.signal.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log

class AudioRouter(private val context: Context) {

    private val tag = "AudioRouter"
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // State
    private var isSpeakerPreferred: Boolean = true
    private var isHeadsetPlugged: Boolean = false
    private var isFocusHeld: Boolean = false

    // [CRITICAL] This flag controls Echo Cancellation (AEC)
    private var isVoipCallActive: Boolean = false

    // Audio Focus (Android O+)
    private var activeFocusRequest: AudioFocusRequest? = null

    // Listener for Service to update UI when hardware changes (e.g. Headset plugged in)
    var onRouteChanged: ((Boolean) -> Unit)? = null // Returns "isSpeakerOn" state

    // 1. Initialization
    fun initialize() {
        val filter = IntentFilter(AudioManager.ACTION_HEADSET_PLUG)
        context.registerReceiver(headsetReceiver, filter)

        // Initial hardware check
        isHeadsetPlugged = audioManager.isWiredHeadsetOn || audioManager.isBluetoothA2dpOn
        updateRoute()
    }

    // 2. Headset Detection (Simplified)
    private val headsetReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_HEADSET_PLUG) {
                val state = intent.getIntExtra("state", -1)
                val wasPlugged = isHeadsetPlugged
                isHeadsetPlugged = (state == 1)

                if (wasPlugged != isHeadsetPlugged) {
                    Log.i(tag, "Headset Changed: $isHeadsetPlugged")
                    // [FIX] Don't change settings here. Let updateRoute handle it.
                    updateRoute()
                }
            }
        }
    }

    // 3. The "Brain" (Central Routing Logic)
    private fun updateRoute() {
        // [RULE 1] Headset always wins. If plugged, force speaker OFF.
        if (isHeadsetPlugged) {
            setSpeakerphone(false)
            onRouteChanged?.invoke(false)
        } else {
            // [RULE 2] If no headset, respect user preference (Speaker vs Earpiece)
            setSpeakerphone(isSpeakerPreferred)
            onRouteChanged?.invoke(isSpeakerPreferred)
        }

        // [RULE 3] Echo Cancellation (AEC)
        // If transmitting (PTT) or Calling, we MUST use MODE_IN_COMMUNICATION.
        // This turns on the noise-canceling hardware.
        if (isVoipCallActive) {
            if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            }
        } else {
            // If idle, go back to Normal to save battery and hide status bar icons
            if (audioManager.mode != AudioManager.MODE_NORMAL) {
                audioManager.mode = AudioManager.MODE_NORMAL
            }
        }
    }

    // 4. Public Control Methods

    // Called by VoiceService when PTT starts/stops
    fun setCallMode(active: Boolean) {
        isVoipCallActive = active
        updateRoute()
    }

    // Called by UI "Speaker" Toggle
    fun setSpeakerPreferred(preferSpeaker: Boolean) {
        isSpeakerPreferred = preferSpeaker
        updateRoute()
    }

    private fun setSpeakerphone(on: Boolean) {
        if (audioManager.isSpeakerphoneOn != on) {
            audioManager.isSpeakerphoneOn = on
        }
    }

    // 5. Audio Focus (Stops Music Apps)
    fun requestFocus(): Boolean {
        if (isFocusHeld) return true

        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener { focusChange ->
                    if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                        // If we lose focus permanently, stop logic
                        abandonFocus()
                    }
                }
                .build()

            activeFocusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            )
        }

        isFocusHeld = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        return isFocusHeld
    }

    fun abandonFocus() {
        // [FIX] Don't abandon focus if we are actively transmitting/calling!
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
        // Reset to normal state
        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.isSpeakerphoneOn = false
    }

    // 6. Cleanup
    fun shutdown() {
        try { context.unregisterReceiver(headsetReceiver) } catch (e: Exception) { }
        isVoipCallActive = false
        isFocusHeld = true // Force abandon to execute
        abandonFocus()
    }
}