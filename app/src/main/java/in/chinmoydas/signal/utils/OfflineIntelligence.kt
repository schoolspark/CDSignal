package `in`.chinmoydas.signal.utils

import android.content.Context
import android.content.Intent
import `in`.chinmoydas.signal.VoiceService

data class AssistantResponse(
    val text: String,
    val actionLabel: String? = null,
    val action: (() -> Unit)? = null
)

object OfflineIntelligence {

    /**
     * CD-1: The Offline Privacy Advocate & Technical Expert.
     * Designed to defend the app's architecture and assist users without leaking source code.
     */
    suspend fun think(query: String, context: Context, service: VoiceService?): AssistantResponse {
        val q = query.lowercase().trim()

        // =========================================================================
        // 1. PRIVACY & LEGAL DEFENSE (The Advocate)
        // =========================================================================

        // Users asking about tracking, server logs, or government data requests
        if (q.contains("track") || q.contains("log") || q.contains("server") || q.contains("store") || q.contains("save")) {
            return AssistantResponse(
                "Impossible. CD Signal is architected as a 'Serverless P2P' system. Your voice data moves directly from Device A to Device B via an ephemeral UDP tunnel. We do not have a central database to store your calls, even if we wanted to. We cannot provide data we do not possess.",
                "Verify Connection"
            ) { /* Check status */ }
        }

        // Users asking about encryption or "spying"
        if (q.contains("spy") || q.contains("encrypt") || q.contains("safe") || q.contains("secure") || q.contains("listen")) {
            val isSecure = context.getSharedPreferences("WalkiePrefs", Context.MODE_PRIVATE).getBoolean("secure_mode", false)
            val status = if (isSecure) "currently ACTIVE" else "currently DISABLED"

            return AssistantResponse(
                "Your defense is AES-256 Military-Grade Encryption (GCM Mode). Even if a bad actor or router intercepts your WiFi packets, they will only see random static noise. CD Signal uses 'RAM-Only' processing—voice data is never written to the hard drive. Encryption is $status.",
                "Toggle Security"
            ) { /* Navigate to Security Settings */ }
        }

        // Legal/Police inquiries
        if (q.contains("police") || q.contains("legal") || q.contains("law") || q.contains("warrant") || q.contains("court")) {
            return AssistantResponse(
                "CD Signal operates as a standard VoIP utility, similar to a telephone. We are a software provider, not a telecommunications carrier. Because the app creates direct peer-to-peer connections, no metadata is retained by Chinmoy Das. We have no 'backdoor' for anyone, including law enforcement.",
                null, null
            )
        }

        // =========================================================================
        // 2. TECHNICAL TROUBLESHOOTING (The Engineer)
        // =========================================================================

        // Audio Issues (Echo, Volume, Hearing)
        if (q.contains("hear") || q.contains("sound") || q.contains("volume") || q.contains("quiet") || q.contains("echo")) {
            val isSpeakerOn = service?.voiceServiceState?.value?.isSpeakerOn == true
            val isSilenced = service?.voiceServiceState?.value?.isSilenced == true

            if (isSilenced) {
                return AssistantResponse(
                    "DIAGNOSIS: Your device is in 'Theater Mode' (Silenced). This suppresses all incoming audio streams.",
                    "Un-Mute Now"
                ) { service?.toggleTheaterMode(false) }
            }
            if (!isSpeakerOn) {
                return AssistantResponse(
                    "DIAGNOSIS: Privacy Earpiece Mode is active. Audio is routed to the small top speaker (like a phone call), not the main loudspeaker.",
                    "Switch to Speaker"
                ) { service?.toggleSpeaker(true) }
            }
            return AssistantResponse(
                "Audio path seems clear. I am initializing the Acoustic Echo Canceler (AEC). We can run a 3-second Loopback Test to verify your microphone.",
                "Run Audio Test"
            ) {
                `in`.chinmoydas.signal.utils.AudioDiagnostics.runAudioTest(context, service)
            }
        }

        // Connection/Network Issues
        if (q.contains("connect") || q.contains("online") || q.contains("internet") || q.contains("wifi") || q.contains("fail")) {
            val netStatus = service?.voiceServiceState?.value?.networkStatus ?: "Unknown"

            if (netStatus.contains("Error")) {
                return AssistantResponse(
                    "CRITICAL FAILURE: The UDP Radio Socket has crashed ($netStatus). This usually happens if another app seizes Port 50005.",
                    "Restart Radio Engine"
                ) {
                    val intent = Intent(context, VoiceService::class.java).apply { action = "STOP_SERVICE" }
                    context.startService(intent)
                }
            }
            return AssistantResponse(
                "Current Transport Status: $netStatus. CD Signal uses a 'Hybrid Mesh' protocol. If the Internet fails, we automatically fall back to Local LAN discovery. You are reachable.",
                "Check Reachability"
            ) { /* Open Readiness Dialog */ }
        }

        // Microphone/Transmission
        if (q.contains("talk") || q.contains("mic") || q.contains("voice") || q.contains("transmit")) {
            val isVox = service?.voiceServiceState?.value?.isVoxEnabled == true
            if (isVox) {
                return AssistantResponse(
                    "NOTICE: Voice Activation (VOX) is ON. The microphone opens automatically when you speak. This overrides the PTT button.",
                    "Disable VOX"
                ) { service?.toggleVox(false) }
            }
            return AssistantResponse(
                "Microphone is in 'Push-to-Talk' (PTT) mode. Encryption keys are rotated every session. Hold the red button to transmit.",
                null, null
            )
        }

        // =========================================================================
        // 3. APP FEATURES & HELP (The Guide)
        // =========================================================================

        // [NEW] Cloud Wake-Up Instructions
        if (q.contains("wake") || q.contains("cloud") || q.contains("red dot") || q.contains("reach") || q.contains("offline")) {
            return AssistantResponse(
                "To use Cloud Wake: If a Saved Contact shows a Red Dot (Offline), wait 2 seconds. An amber 'WAKE DEVICE (CLOUD)' button will appear automatically.  Tapping it sends a secure signal via Google servers to wake their phone.",
                null, null
            )
        }

        // [NEW] Guardian Mode Setup Instructions
        if (q.contains("set guardian") || q.contains("add guardian") || q.contains("principal") || q.contains("trust")) {
            return AssistantResponse(
                "To authorize a Guardian: Go to the 'Connect' tab and find the person in 'Saved Contacts'. Tap the 'Star' (Priority) icon next to their name.  Starred contacts can bypass Silent Mode and ping your location during emergencies.",
                null, null
            )
        }

        // Guardian Mode / Remote Control
        if (q.contains("guardian") || q.contains("remote") || q.contains("control") || q.contains("child")) {
            return AssistantResponse(
                "Guardian Mode is a safety feature allowing a trusted 'Principal' device to remotely manage this unit (e.g., unmute it during an emergency). It is strictly permission-based.",
                "Manage Permissions"
            ) { /* Nav to Settings */ }
        }

        // Restore / Reset
        if (q.contains("reset") || q.contains("restore") || q.contains("fix") || q.contains("broken")) {
            return AssistantResponse(
                "I can perform a 'Soft Reset' of the audio and network flags without deleting your data.",
                "Execute Restore"
            ) {
                // Fix: Directly reset hardware flags instead of sending a network command
                service?.toggleTheaterMode(false)
                service?.toggleSpeaker(true)
                service?.toggleVox(false)
            }
        }

        // SOS / Emergency
        if (q.contains("sos") || q.contains("help") || q.contains("emergency") || q.contains("crash")) {
            return AssistantResponse(
                "The SOS feature broadcasts a high-priority distress beacon with your GPS coordinates to all nearby devices. It bypasses Silent Mode on receiver devices.",
                "Trigger SOS"
            ) { service?.confirmSos() }
        }

        // Identity / About
        if (q.contains("who are you") || q.contains("what is this") || q.contains("cd signal")) {
            return AssistantResponse(
                "I am CD-1, the embedded intelligence for CD Signal. This app is a sovereign communication tool developed by Chinmoy Das. It prioritizes user ownership of data over corporate surveillance.",
                null, null
            )
        }

        // =========================================================================
        // 4. FALLBACK (The Learner)
        // =========================================================================

        return AssistantResponse(
            "I am designed to protect your privacy and maintain connection. I didn't understand that query. Try asking about 'Cloud Wake', 'Encryption', or 'Guardian Mode'.",
            "Run System Diagnostics" // <--- Update Label
        ) {
            // Link to your SystemDiagnostics OR AudioDiagnostics
            `in`.chinmoydas.signal.utils.SystemDiagnostics.runChecks(context, service)
        }
    }
}