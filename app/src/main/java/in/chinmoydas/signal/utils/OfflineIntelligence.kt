package `in`.chinmoydas.signal.utils

import android.content.Context
import android.content.Intent
import `in`.chinmoydas.signal.VoiceService
import java.util.Locale
import kotlin.math.max

data class AssistantResponse(
    val text: String,
    val actionLabel: String? = null,
    val action: (() -> Unit)? = null
)

/**
 * CD-1 (Version 2.0): The Offline Neural-Lite Engine
 * Uses weighted keyword scoring to infer intent from vague user queries.
 */
object OfflineIntelligence {

    // Define the "Intents" the assistant understands
    private enum class IntentType {
        AUDIO_ISSUES,
        CONNECTION_ISSUES,
        SECURITY_PRIVACY,
        LEGAL_POLICE,
        APP_FEATURES,
        IDENTITY,
        EMERGENCY,
        RESTORE,
        GREETING,
        UNKNOWN
    }

    suspend fun think(query: String, context: Context, service: VoiceService?): AssistantResponse {
        val q = query.lowercase(Locale.ROOT).trim()

        // 1. Clean Input (Remove noise words)
        val tokens = q.replace(Regex("[^a-z0-9 ]"), "").split(" ")
            .filter { it.length > 2 && it !in listOf("the", "and", "for", "with", "this", "that") }

        // 2. Score Intents
        val scores = mutableMapOf<IntentType, Int>()

        fun score(intent: IntentType, weight: Int, vararg keywords: String) {
            val current = scores.getOrDefault(intent, 0)
            // Fuzzy match: If input token contains keyword or vice versa (handles typos like "conection")
            val matches = keywords.count { kw -> tokens.any { t -> t.contains(kw) || kw.contains(t) } }
            if (matches > 0) scores[intent] = current + (matches * weight)
        }

        // --- BRAIN WEIGHTS ---
        score(IntentType.AUDIO_ISSUES, 10, "hear", "sound", "volum", "audio", "quiet", "loud", "speak", "mic", "talk", "voic")
        score(IntentType.CONNECTION_ISSUES, 10, "connect", "net", "wifi", "offline", "inter", "broken", "fail", "reach", "link")
        score(IntentType.SECURITY_PRIVACY, 10, "spy", "encry", "safe", "secur", "hack", "priva", "track", "log", "data")
        score(IntentType.LEGAL_POLICE, 15, "police", "legal", "law", "court", "warrant", "cop", "gov")
        score(IntentType.APP_FEATURES, 8, "wake", "cloud", "red", "dot", "guard", "princ", "remo", "featu")
        score(IntentType.EMERGENCY, 20, "sos", "help", "emerg", "crash", "danger", "alert")
        score(IntentType.RESTORE, 15, "fix", "reset", "repair", "bug")
        score(IntentType.IDENTITY, 20, "who", "are", "you", "name", "creat", "chinmoy")
        score(IntentType.GREETING, 5, "hi", "hello", "hey", "test")

        // 3. Determine Winner
        val topIntent = scores.entries.maxByOrNull { it.value }?.toPair() ?: (IntentType.UNKNOWN to 0)

        // Threshold check (too vague?)
        val finalIntent = if (topIntent.second >= 5) topIntent.first else IntentType.UNKNOWN

        // 4. Execute Logic
        return when (finalIntent) {
            IntentType.AUDIO_ISSUES -> handleAudio(context, service)
            IntentType.CONNECTION_ISSUES -> handleConnection(context, service)
            IntentType.SECURITY_PRIVACY -> handleSecurity(context)
            IntentType.LEGAL_POLICE -> handleLegal()
            IntentType.APP_FEATURES -> handleFeatures(q, context, service)
            IntentType.EMERGENCY -> handleEmergency(service)
            IntentType.RESTORE -> handleRestore(service)
            IntentType.IDENTITY -> handleIdentity()
            IntentType.GREETING -> AssistantResponse("System Online. I am CD-1. Signals are green. How can I assist?")
            IntentType.UNKNOWN -> handleFallback(context, service)
        }
    }

    // --- HANDLERS (The Knowledge Base) ---

    private fun handleAudio(context: Context, service: VoiceService?): AssistantResponse {
        val isSilenced = service?.voiceServiceState?.value?.isSilenced == true
        if (isSilenced) {
            return AssistantResponse(
                "You are in 'Theater Mode' (Silenced), so you cannot hear incoming calls. Un-mute to restore audio.",
                "Un-Mute Device"
            ) { service?.toggleTheaterMode(false) }
        }

        val isSpeaker = service?.voiceServiceState?.value?.isSpeakerOn == true
        if (!isSpeaker) {
            return AssistantResponse(
                "I noticed your Loudspeaker is OFF. The audio is playing through the small ear-piece at the top (Privacy Mode).",
                "Turn On Speaker"
            ) { service?.toggleSpeaker(true) }
        }

        return AssistantResponse(
            "Your audio settings look correct. If you still can't hear, let's run a hardware loopback test.",
            "Run Audio Diagnostics"
        ) { `in`.chinmoydas.signal.utils.AudioDiagnostics.runAudioTest(context, service) }
    }

    private fun handleConnection(context: Context, service: VoiceService?): AssistantResponse {
        val status = service?.voiceServiceState?.value?.networkStatus ?: "Unknown"

        if (status.contains("Error") || status.contains("Failed")) {
            return AssistantResponse(
                "I detect a Radio Bind Error ($status). Port 50005 might be blocked by another app.",
                "Restart Radio Engine"
            ) {
                val intent = Intent(context, VoiceService::class.java).apply { action = "STOP_SERVICE" }
                context.startService(intent)
            }
        }

        return AssistantResponse(
            "Current Link Status: $status. \n\nRemember: Even without Internet, you can talk to anyone on your Wi-Fi/Hotspot immediately.",
            "Scan Local Network"
        ) { /* Trigger Discovery */ }
    }

    private fun handleSecurity(context: Context): AssistantResponse {
        val isSecure = context.getSharedPreferences("WalkiePrefs", Context.MODE_PRIVATE).getBoolean("secure_mode", false)
        val state = if (isSecure) "ACTIVE" else "OFF"

        return AssistantResponse(
            "Security Report:\n• Algorithm: AES-256 (GCM)\n• Storage: RAM-Only (No logs)\n• Encryption is currently: $state.\n\nEven if intercepted, your voice data looks like random static noise.",
            if (isSecure) null else "Enable Encryption"
        ) { /* Deep link to profile setting if needed */ }
    }

    private fun handleFeatures(q: String, context: Context, service: VoiceService?): AssistantResponse {
        if (q.contains("guard") || q.contains("remo")) {
            return AssistantResponse(
                "Guardian Mode allows a trusted contact (Principal) to remotely unmute your mic during emergencies. You must explicitly allow this in your Profile.",
                "Check Permissions"
            ) { /* Link to Profile */ }
        }
        return AssistantResponse(
            "Cloud Wake feature allows you to ring a device even if their app is sleeping. Just look for the 'Red Dot' next to their name, wait 2 seconds, and tap 'Wake Device'.",
            null, null
        )
    }

    private fun handleEmergency(service: VoiceService?): AssistantResponse {
        return AssistantResponse(
            "⚠️ EMERGENCY PROTOCOL INITIATED.\n\nThis will blast a high-priority SOS beacon with your GPS location to ALL nearby devices, bypassing their Silent Mode.",
            "CONFIRM SOS BROADCAST"
        ) { service?.confirmSos() }
    }

    private fun handleRestore(service: VoiceService?): AssistantResponse {
        return AssistantResponse(
            "I can perform a 'Soft Reset' of the radio flags. This fixes 90% of audio glitches without deleting data.",
            "Execute Repair"
        ) {
            service?.toggleTheaterMode(false)
            service?.toggleSpeaker(true)
            service?.toggleVox(false)
        }
    }

    private fun handleLegal(): AssistantResponse {
        return AssistantResponse(
            "CD Signal is a peer-to-peer utility. We are not a carrier. We do not store call logs, locations, or identities. We cannot provide data that simply does not exist.",
            null, null
        )
    }

    private fun handleIdentity(): AssistantResponse {
        return AssistantResponse(
            "I am CD-1, the autonomous neural interface for CD Signal. My purpose is to ensure connection continuity and protect user sovereignty. Architect: Chinmoy Das.",
            null, null
        )
    }

    private fun handleFallback(context: Context, service: VoiceService?): AssistantResponse {
        // Even if we don't know the answer, offer a useful tool
        return AssistantResponse(
            "I'm analyzing your query but the context is unclear. However, I can check your system health to ensure you are ready to transmit.",
            "Run Full System Check"
        ) {
            `in`.chinmoydas.signal.utils.SystemDiagnostics.runChecks(context, service)
        }
    }
}