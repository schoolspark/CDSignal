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
        updateRoute()
    }

    // 2. Headset Detection
    private val headsetReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_HEADSET_PLUG) {
                val state = intent.getIntExtra("state", -1)
                val previousState = isHeadsetPlugged
                isHeadsetPlugged = (state == 1)

                if (previousState != isHeadsetPlugged) {
                    Log.d(tag, "Headset Plugged: $isHeadsetPlugged")
                    updateRoute()
                }
            }
        }
    }

    // [NEW] 2.5 Call Mode Toggle (Critical for VoIP Echo Cancellation)
    fun setCallMode(active: Boolean) {
        if (isVoipCallActive == active) return
        isVoipCallActive = active
        Log.d(tag, "VoIP Call Mode: $active")
        updateRoute()
    }

    // 3. User Preference (The Toggle Button)
    fun setSpeakerPreferred(preferred: Boolean) {
        if (isSpeakerPreferred == preferred) return
        isSpeakerPreferred = preferred
        updateRoute()
    }

    // 4. The Core Logic (Decides where audio goes)
    private fun updateRoute() {
        if (isHeadsetPlugged) {
            // Headset overrides everything. Always route to it if plugged.
            // But we must disable speakerphone explicitly.
            setSpeakerphoneOn(false)
        } else {
            // No headset? Obey the user's toggle.
            setSpeakerphoneOn(isSpeakerPreferred)
        }

        // Notify Service/UI of the *actual* resulting state
        onRouteChanged?.invoke(audioManager.isSpeakerphoneOn)
    }

    private fun setSpeakerphoneOn(on: Boolean) {
        if (audioManager.isSpeakerphoneOn != on) {
            audioManager.isSpeakerphoneOn = on
        }

        // [UPGRADE] Diamond State Logic:
        // 1. PTT Mode (Half-Duplex): MODE_NORMAL is fine for Speaker, saves battery.
        // 2. VoIP Call Mode (Full-Duplex): We MUST use MODE_IN_COMMUNICATION even on Speaker
        //    to enable the Hardware Acoustic Echo Canceller (AEC). Without this, echoes occur.
        if (isVoipCallActive) {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        } else {
            if (on) {
                audioManager.mode = AudioManager.MODE_NORMAL
            } else {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            }
        }
    }

    // 5. Audio Focus (The "Shut up Spotify" logic)
    fun requestFocus(): Boolean {
        if (isFocusHeld) return true

        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(attributes)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { focusChange ->
                    if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                        isFocusHeld = false
                        // Optional: Notify service to stop transmitting if focus lost hard
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
    }

    // 6. Cleanup
    fun shutdown() {
        try { context.unregisterReceiver(headsetReceiver) } catch (e: Exception) { }

        // Force cleanup even if call was active
        isVoipCallActive = false
        abandonFocus()
    }
}