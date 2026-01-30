package `in`.chinmoydas.signal.utils

import android.content.Context
import `in`.chinmoydas.signal.VoiceService
import java.util.Locale

data class AssistantResponse(
    val text: String,
    val actionLabel: String? = null,
    val action: (() -> Unit)? = null
)

/**
 * CD-1 (Version 3.0): The Tactical Field Assistant
 * Includes offline survival database and system repair tools.
 */
object OfflineIntelligence {

    // Define the "Intents" the assistant understands
    private enum class IntentType {
        SURVIVAL_CPR,
        SURVIVAL_BLEEDING,
        SURVIVAL_BURNS,
        SURVIVAL_SOS,
        SURVIVAL_BATTERY,
        AUDIO_ISSUES,
        CONNECTION_ISSUES,
        SECURITY_PRIVACY,
        LEGAL_POLICE,
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
            .filter { it.length > 2 && it !in listOf("the", "and", "for", "with", "this", "that", "how", "what") }

        // 2. Score Intents
        val scores = mutableMapOf<IntentType, Int>()

        // -- SURVIVAL DATABASE --
        if (hasAny(q, "cpr", "heart attack", "unconscious", "breathe", "chest")) scores.merge(IntentType.SURVIVAL_CPR, 5, Int::plus)
        if (hasAny(q, "bleed", "cut", "wound", "blood", "bandage", "tourniquet")) scores.merge(IntentType.SURVIVAL_BLEEDING, 5, Int::plus)
        if (hasAny(q, "burn", "fire", "scald", "skin")) scores.merge(IntentType.SURVIVAL_BURNS, 5, Int::plus)
        if (hasAny(q, "sos", "morse", "signal", "help", "rescue")) scores.merge(IntentType.SURVIVAL_SOS, 5, Int::plus)
        if (hasAny(q, "battery", "power", "drain", "charge", "last longer")) scores.merge(IntentType.SURVIVAL_BATTERY, 5, Int::plus)

        // -- SYSTEM COMMANDS --
        if (hasAny(q, "echo", "noise", "feedback", "hear", "sound", "volume", "quiet")) scores.merge(IntentType.AUDIO_ISSUES, 3, Int::plus)
        if (hasAny(q, "connect", "link", "offline", "wifi", "internet", "pair")) scores.merge(IntentType.CONNECTION_ISSUES, 3, Int::plus)
        if (hasAny(q, "police", "legal", "law", "government", "ban", "illegal")) scores.merge(IntentType.LEGAL_POLICE, 4, Int::plus)
        if (hasAny(q, "hack", "track", "safe", "privacy", "secure", "encrypt")) scores.merge(IntentType.SECURITY_PRIVACY, 3, Int::plus)
        if (hasAny(q, "who are you", "identity", "bot", "ai", "creator")) scores.merge(IntentType.IDENTITY, 5, Int::plus)
        if (hasAny(q, "emergency", "danger", "panic", "alert", "sos")) scores.merge(IntentType.EMERGENCY, 3, Int::plus)
        if (hasAny(q, "reset", "fix", "repair", "broken", "bug", "stuck")) scores.merge(IntentType.RESTORE, 4, Int::plus)
        if (hasAny(q, "hello", "hi", "hey", "start")) scores.merge(IntentType.GREETING, 2, Int::plus)

        // 3. Determine Winner
        val bestMatch = scores.maxByOrNull { it.value }

        return if (bestMatch != null && bestMatch.value >= 2) {
            when (bestMatch.key) {
                IntentType.SURVIVAL_CPR -> AssistantResponse(
                    "CPR GUIDE:\n1. Push hard & fast in center of chest (100-120/min).\n2. Allow chest to recoil.\n3. Do this until help arrives.\n\nKeep rhythm to 'Stayin Alive'.",
                    "Call Emergency"
                ) { service?.sendPanicAlert() }

                IntentType.SURVIVAL_BLEEDING -> AssistantResponse(
                    "STOP BLEEDING:\n1. Apply direct pressure with clean cloth.\n2. Elevate wound above heart.\n3. Do NOT remove soaked cloth, add more on top.\n4. Use tourniquet ONLY for life-threatening limb bleeds.",
                    null, null
                )

                IntentType.SURVIVAL_BURNS -> AssistantResponse(
                    "TREATING BURNS:\n1. Cool with cool (not cold) water for 10 mins.\n2. Cover with sterile, non-fluffy cloth.\n3. Do NOT pop blisters.\n4. Do NOT apply ice or butter.",
                    null, null
                )

                IntentType.SURVIVAL_SOS -> AssistantResponse(
                    "SOS MORSE CODE:\n. . .   - - -   . . . (3 Short, 3 Long, 3 Short)\n\nVISUAL: Wave arms up and down slowly at sides.",
                    "Broadcast SOS Signal"
                ) { service?.sendPanicAlert() }

                IntentType.SURVIVAL_BATTERY -> AssistantResponse(
                    "TACTICAL POWER SAVE:\n1. Turn off Screen.\n2. Use 'Stealth Mode'.\n3. Keep phone cool.\n\nI can enable Stealth Mode now to disable speaker and lights.",
                    "Enable Stealth Mode"
                ) { service?.toggleTheaterMode(true) }

                IntentType.AUDIO_ISSUES -> handleAudio(service)
                IntentType.CONNECTION_ISSUES -> AssistantResponse(
                    "Ensure both devices are on the SAME Wi-Fi or Hotspot. If on Mobile Data, check if your carrier blocks UDP port 50005.",
                    "Run Diagnostics"
                ) { SystemDiagnostics.runChecks(context, service) }

                IntentType.SECURITY_PRIVACY -> AssistantResponse(
                    "Encryption is AES-256 (GCM). Your IP is hidden from strangers. We do not log data. You are ghost.",
                    "Verify Security"
                ) { SystemDiagnostics.runChecks(context, service) }

                IntentType.LEGAL_POLICE -> handleLegal()
                IntentType.IDENTITY -> handleIdentity()
                IntentType.EMERGENCY -> AssistantResponse(
                    "Use the RED button on the Connect tab for immediate distress signals. This alerts all nearby nodes.",
                    "Trigger SOS Now"
                ) { service?.confirmSos() } // Uses the stabilized SOS logic

                IntentType.RESTORE -> handleRestore(service)
                IntentType.GREETING -> AssistantResponse("CD-1 Systems Online. Tactical systems ready. How can I assist?", null, null)
                else -> handleFallback(context, service)
            }
        } else {
            handleFallback(context, service)
        }
    }

    private fun hasAny(query: String, vararg keywords: String): Boolean {
        return keywords.any { query.contains(it) }
    }

    private fun handleAudio(service: VoiceService?): AssistantResponse {
        return AssistantResponse(
            "If you hear echo, enable 'Headset Mode' or lower volume. If audio is choppy, you are reaching range limits.",
            "Fix Audio (Reset)"
        ) {
            service?.toggleSpeaker(false)
            service?.toggleSpeaker(true)
        }
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
            "CD Signal is a peer-to-peer utility. We are not a carrier. We do not store call logs, locations, or identities.",
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
        return AssistantResponse(
            "I don't have a protocol for that yet. I can provide Survival Guides (CPR, Burns, SOS) or check system health.",
            "Run System Check"
        ) {
            SystemDiagnostics.runChecks(context, service)
        }
    }
}