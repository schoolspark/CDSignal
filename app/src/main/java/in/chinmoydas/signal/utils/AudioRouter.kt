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

    // [NEW] Call Mode State (Distinguishes PTT vs VoIP)
    private var isVoipCallActive: Boolean = false

    // Audio Focus (Android O+)
    private var activeFocusRequest: AudioFocusRequest? = null

    // Listener for Service to update UI when hardware changes (e.g. Headset plugged in)
    var onRouteChanged: ((Boolean) -> Unit)? = null // Returns "isSpeakerOn" state

    // 1. Initialization
    fun initialize() {
        val filter = IntentFilter(AudioManager.ACTION_HEADSET_PLUG)
        context.registerReceiver(headsetReceiver, filter)
        // Initial check
        isHeadsetPlugged = audioManager.isWiredHeadsetOn || audioManager.isBluetoothA2dpOn
        updateRoute()
    }

    // 2. Headset Detection
    private val headsetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_HEADSET_PLUG) {
                val state = intent.getIntExtra("state", -1)
                isHeadsetPlugged = (state == 1)

                // Hardware override: If headset plugged, speaker is OFF visually
                if (isHeadsetPlugged) {
                    setSpeakerphoneOn(false)
                } else {
                    // Restore user preference
                    setSpeakerphoneOn(isSpeakerPreferred)
                }

                // Notify UI of the forced change
                onRouteChanged?.invoke(!isHeadsetPlugged && isSpeakerPreferred)
            }
        }
    }

    // 3. Routing Logic (The Brain)
    private fun updateRoute() {
        if (isHeadsetPlugged) {
            setSpeakerphoneOn(false)
            // Use Communication mode for clean voice audio if calling
            setMode(if (isVoipCallActive) AudioManager.MODE_IN_COMMUNICATION else AudioManager.MODE_NORMAL)
            return
        }

        // [UPGRADE] VoIP Call Mode (Full-Duplex) vs PTT
        if (isVoipCallActive) {
            // Full Duplex: Must use MODE_IN_COMMUNICATION for Echo Cancellation (AEC)
            setMode(AudioManager.MODE_IN_COMMUNICATION)
            setSpeakerphoneOn(isSpeakerPreferred)
        } else {
            // PTT Mode: Default to Speaker, but use NORMAL mode to save battery/avoid status bar green dot
            setMode(AudioManager.MODE_NORMAL)
            setSpeakerphoneOn(isSpeakerPreferred)
        }
    }

    // 4. External Control Methods
    fun setCallMode(active: Boolean) {
        isVoipCallActive = active
        updateRoute()
    }

    fun setSpeakerPreferred(on: Boolean) {
        isSpeakerPreferred = on
        updateRoute()
    }

    private fun setMode(mode: Int) {
        if (audioManager.mode != mode) {
            audioManager.mode = mode
        }
    }

    private fun setSpeakerphoneOn(on: Boolean) {
        if (audioManager.isSpeakerphoneOn != on) {
            audioManager.isSpeakerphoneOn = on
        }
    }

    // 5. Focus Management (Hardened)
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
                        isFocusHeld = false
                        // Note: Service should monitor this flag to stop PTT if focus is lost hard
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
        // [FIX] Don't abandon focus if we are in an active VoIP call!
        // The call engine needs the focus to keep the mic alive.
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
        // Reset mode to Normal when idle to be a good citizen
        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.isSpeakerphoneOn = false
    }

    // 6. Cleanup
    fun shutdown() {
        try { context.unregisterReceiver(headsetReceiver) } catch (e: Exception) { }
        isVoipCallActive = false
        isFocusHeld = true // Pretend held so abandon works
        abandonFocus()
    }
}