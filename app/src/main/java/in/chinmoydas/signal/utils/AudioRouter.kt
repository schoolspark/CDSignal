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
    private var isVoipCallActive: Boolean = false

    // Audio Focus (Android O+)
    private var activeFocusRequest: AudioFocusRequest? = null

    var onRouteChanged: ((Boolean) -> Unit)? = null

    fun initialize() {
        val filter = IntentFilter(AudioManager.ACTION_HEADSET_PLUG)
        context.registerReceiver(headsetReceiver, filter)
        isHeadsetPlugged = audioManager.isWiredHeadsetOn || audioManager.isBluetoothA2dpOn
        updateRoute()
    }

    private val headsetReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_HEADSET_PLUG) {
                val state = intent.getIntExtra("state", -1)
                val wasPlugged = isHeadsetPlugged
                isHeadsetPlugged = (state == 1)

                if (wasPlugged != isHeadsetPlugged) {
                    updateRoute()
                }
            }
        }
    }

    private fun updateRoute() {
        if (isHeadsetPlugged) {
            setSpeakerphone(false)
            onRouteChanged?.invoke(false)
        } else {
            setSpeakerphone(isSpeakerPreferred)
            onRouteChanged?.invoke(isSpeakerPreferred)
        }

        if (isVoipCallActive) {
            if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            }
        } else {
            if (audioManager.mode != AudioManager.MODE_NORMAL) {
                audioManager.mode = AudioManager.MODE_NORMAL
            }
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
            // [FIX 1] Even if held, ensure routing is correct (Double Tap safety)
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
                    if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
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

        // [FIX 2] CRITICAL: Re-apply routing (Speaker ON) immediately after gaining focus
        if (isFocusHeld) {
            updateRoute()
        }

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

        // [FIX 3] Removed "audioManager.isSpeakerphoneOn = false"
        // Just reset the mode. Leave speaker state for updateRoute to handle next time.
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    fun shutdown() {
        try { context.unregisterReceiver(headsetReceiver) } catch (e: Exception) { }
        isVoipCallActive = false
        isFocusHeld = true
        abandonFocus()
    }
}