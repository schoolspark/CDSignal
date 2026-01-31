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
 * CD-1 (Version 4.0): The Tactical Field Assistant
 * UPGRADED: Includes Wilderness Survival, Women's Safety, and Disaster Protocols.
 */
object OfflineIntelligence {

    // Define the "Intents" the assistant understands
    private enum class IntentType {
        // --- MEDICAL ---
        SURVIVAL_CPR,
        SURVIVAL_BLEEDING,
        SURVIVAL_BURNS,
        SURVIVAL_BITES,

        // --- WILDERNESS ---
        SURVIVAL_WATER,
        SURVIVAL_FIRE,
        SURVIVAL_SHELTER,
        SURVIVAL_NAV,
        SURVIVAL_FORAGE,

        // --- SITUATIONAL & SAFETY ---
        SURVIVAL_SOS,
        SURVIVAL_BATTERY,
        SURVIVAL_PSYCH, // Panic control
        SAFETY_WOMEN,   // Specific safety protocols

        // --- DISASTERS ---
        DISASTER_QUAKE,
        DISASTER_FLOOD,
        DISASTER_STORM,

        // --- SYSTEM ---
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
            .filter { it.length > 2 && it !in listOf("the", "and", "for", "with", "this", "that", "how", "what", "can", "you") }

        // 2. Score Intents
        val scores = mutableMapOf<IntentType, Int>()

        // -- MEDICAL --
        if (hasAny(q, "cpr", "heart attack", "unconscious", "breathe", "chest", "revive")) scores.merge(IntentType.SURVIVAL_CPR, 5, Int::plus)
        if (hasAny(q, "bleed", "cut", "wound", "blood", "bandage", "tourniquet", "hemorrhage")) scores.merge(IntentType.SURVIVAL_BLEEDING, 5, Int::plus)
        if (hasAny(q, "burn", "fire", "scald", "skin", "blister")) scores.merge(IntentType.SURVIVAL_BURNS, 5, Int::plus)
        if (hasAny(q, "bite", "sting", "snake", "spider", "insect", "poison", "venom")) scores.merge(IntentType.SURVIVAL_BITES, 5, Int::plus)

        // -- WILDERNESS --
        if (hasAny(q, "water", "thirst", "drink", "dehydrat", "filter", "purify", "river")) scores.merge(IntentType.SURVIVAL_WATER, 5, Int::plus)
        if (hasAny(q, "fire", "cold", "freeze", "warm", "flame", "match", "lighter", "hypothermia")) scores.merge(IntentType.SURVIVAL_FIRE, 5, Int::plus)
        if (hasAny(q, "shelter", "rain", "roof", "house", "sleep", "tent", "exposure", "sun")) scores.merge(IntentType.SURVIVAL_SHELTER, 5, Int::plus)
        if (hasAny(q, "lost", "where am i", "north", "south", "direction", "compass", "map", "star")) scores.merge(IntentType.SURVIVAL_NAV, 5, Int::plus)
        if (hasAny(q, "eat", "food", "hungry", "plant", "berry", "mushroom", "forage")) scores.merge(IntentType.SURVIVAL_FORAGE, 5, Int::plus)

        // -- SAFETY & DISASTER --
        if (hasAny(q, "sos", "morse", "signal", "help", "rescue", "trap", "stuck")) scores.merge(IntentType.SURVIVAL_SOS, 5, Int::plus)
        if (hasAny(q, "battery", "power", "drain", "charge", "last longer")) scores.merge(IntentType.SURVIVAL_BATTERY, 5, Int::plus)
        if (hasAny(q, "panic", "scared", "fear", "anxiety", "calm", "mind", "alone")) scores.merge(IntentType.SURVIVAL_PSYCH, 5, Int::plus)
        if (hasAny(q, "girl", "woman", "female", "stalk", "taxi", "cab", "harass", "follow", "rape", "unsafe")) scores.merge(IntentType.SAFETY_WOMEN, 6, Int::plus)

        if (hasAny(q, "quake", "shake", "tremor", "ground")) scores.merge(IntentType.DISASTER_QUAKE, 5, Int::plus)
        if (hasAny(q, "flood", "drown", "water rising", "swim")) scores.merge(IntentType.DISASTER_FLOOD, 5, Int::plus)
        if (hasAny(q, "storm", "hurricane", "cyclone", "wind", "tornado")) scores.merge(IntentType.DISASTER_STORM, 5, Int::plus)

        // -- SYSTEM COMMANDS --
        if (hasAny(q, "echo", "noise", "feedback", "hear", "sound", "volume", "quiet")) scores.merge(IntentType.AUDIO_ISSUES, 3, Int::plus)
        if (hasAny(q, "connect", "link", "offline", "wifi", "internet", "pair")) scores.merge(IntentType.CONNECTION_ISSUES, 3, Int::plus)
        if (hasAny(q, "police", "legal", "law", "government", "ban", "illegal")) scores.merge(IntentType.LEGAL_POLICE, 4, Int::plus)
        if (hasAny(q, "hack", "track", "safe", "privacy", "secure", "encrypt")) scores.merge(IntentType.SECURITY_PRIVACY, 3, Int::plus)
        if (hasAny(q, "who are you", "identity", "bot", "ai", "creator")) scores.merge(IntentType.IDENTITY, 5, Int::plus)
        if (hasAny(q, "emergency", "danger", "alert")) scores.merge(IntentType.EMERGENCY, 3, Int::plus)
        if (hasAny(q, "reset", "fix", "repair", "broken", "bug", "stuck")) scores.merge(IntentType.RESTORE, 4, Int::plus)
        if (hasAny(q, "hello", "hi", "hey", "start")) scores.merge(IntentType.GREETING, 2, Int::plus)

        // 3. Determine Winner
        val bestMatch = scores.maxByOrNull { it.value }

        return if (bestMatch != null && bestMatch.value >= 2) {
            when (bestMatch.key) {
                // --- MEDICAL ---
                IntentType.SURVIVAL_CPR -> AssistantResponse(
                    "CPR GUIDE:\n1. Push hard & fast in center of chest (100-120/min).\n2. Allow chest to recoil.\n3. Keep rhythm to 'Stayin Alive'.\n4. Continue until help arrives.",
                    "Call Emergency"
                ) { service?.sendPanicAlert() }

                IntentType.SURVIVAL_BLEEDING -> AssistantResponse(
                    "STOP BLEEDING:\n1. Apply direct pressure with clean cloth.\n2. Do NOT remove soaked cloth, add more.\n3. Use tourniquet (2 inches above wound) ONLY for life-threatening limb bleeds.",
                    null, null
                )

                IntentType.SURVIVAL_BURNS -> AssistantResponse(
                    "BURNS:\n1. Cool with cool water for 10 mins (No ice).\n2. Cover with sterile, non-fluffy cloth.\n3. Do NOT pop blisters.\n4. Treat for shock (keep warm).",
                    null, null
                )

                IntentType.SURVIVAL_BITES -> AssistantResponse(
                    "SNAKE/INSECT BITES:\n1. Keep limb immobilized and below heart level.\n2. Wash with soap/water.\n3. Do NOT suck poison or cut wound.\n4. Note the time and description of creature.",
                    null, null
                )

                // --- WILDERNESS ---
                IntentType.SURVIVAL_WATER -> AssistantResponse(
                    "FIND WATER:\n1. Follow gravity (valleys).\n2. Collect dew at sunrise with cloth.\n3. Filter: Pour through layers of shirt/sand/charcoal.\n4. BOIL for 1 min (3 mins at high altitude) to kill pathogens.",
                    null, null
                )

                IntentType.SURVIVAL_FIRE -> AssistantResponse(
                    "FIRE STARTER:\n1. Tinder: Dry grass, fluff, birch bark.\n2. Kindling: Small twigs.\n3. Fuel: Large logs.\n4. Spark: Lighter, Lens (glasses), or Friction (bow drill).\n\nBuild structure (Teepee or Log Cabin) to allow airflow.",
                    null, null
                )

                IntentType.SURVIVAL_SHELTER -> AssistantResponse(
                    "SHELTER PRIORITY:\n1. Location: Dry, flat, away from falling rocks/branches.\n2. Insulation: Put 6 inches of leaves/grass UNDER you to stop ground from stealing body heat.\n3. Roof: Lean branches against a tree/ridge, cover with debris.",
                    null, null
                )

                IntentType.SURVIVAL_NAV -> AssistantResponse(
                    "FIND NORTH:\n1. Sun rises East, sets West.\n2. Shadow Stick: Place stick in ground. Mark shadow tip. Wait 15 min. Mark new tip. Line connecting marks is West-East.\n3. Night: Find Big Dipper -> Follow outer 2 stars to Polaris (North Star).",
                    "Compass Mode"
                ) { /* Trigger Compass Intent if available */ }

                IntentType.SURVIVAL_FORAGE -> AssistantResponse(
                    "EDIBILITY TEST (Universal):\n1. Smell (Avoid almonds/peach scents).\n2. Touch to wrist (Wait 15m).\n3. Touch to lip (Wait 15m).\n4. Small bite (Wait 15m).\n\nWARNING: If you don't know it, DON'T eat it. You can live 3 weeks without food.",
                    null, null
                )

                // --- SAFETY & PSYCHOLOGY ---
                IntentType.SAFETY_WOMEN -> AssistantResponse(
                    "WOMEN SAFETY:\n1. Rideshare: Ask 'Who are you here for?'. Check child locks.\n2. Walking: Keep hands free. Walk against traffic.\n3. Threat: Make NOISE. 'FIRE' yells get more help than 'HELP'.\n4. Use elbow/palm strike to nose/groin if attacked.",
                    "Fake Call (Simulate)"
                ) { /* Trigger Fake Call Audio */ }

                IntentType.SURVIVAL_PSYCH -> AssistantResponse(
                    "PANIC CONTROL (Box Breathing):\n1. Inhale 4 sec.\n2. Hold 4 sec.\n3. Exhale 4 sec.\n4. Hold 4 sec.\n\nS.T.O.P Rule: Sit, Think, Observe, Plan. You can survive this.",
                    null, null
                )

                IntentType.SURVIVAL_SOS -> AssistantResponse(
                    "SOS SIGNALS:\nAudio: 3 Short, 3 Long, 3 Short ( . . . - - - . . . )\nVisual: Mirror flash at aircraft.\nGround: Create a large 'V' or 'X' with rocks/logs (contrast with ground).",
                    "Broadcast SOS Signal"
                ) { service?.sendPanicAlert() }

                // --- DISASTERS ---
                IntentType.DISASTER_QUAKE -> AssistantResponse(
                    "EARTHQUAKE:\n1. INDOORS: Drop, Cover, Hold On. Stay away from windows.\n2. OUTDOORS: Move to open area away from buildings/wires.\n3. DRIVING: Stop safely, stay inside.",
                    null, null
                )

                IntentType.DISASTER_FLOOD -> AssistantResponse(
                    "FLOOD:\n1. Move to higher ground immediately.\n2. Do NOT walk/drive through water (6 inches can knock you down).\n3. If trapped in car: Unbuckle, roll down window. If stuck, break glass.",
                    null, null
                )

                IntentType.DISASTER_STORM -> AssistantResponse(
                    "STORM/CYCLONE:\n1. Stay indoors, away from windows.\n2. If eye passes (calm), do not go out; wind will return violently.\n3. Unplug electronics.",
                    null, null
                )

                // --- TECH & SYSTEM ---
                IntentType.SURVIVAL_BATTERY -> AssistantResponse(
                    "TACTICAL POWER SAVE:\n1. Turn off Screen.\n2. Use 'Stealth Mode'.\n3. Keep phone cool (heat drains battery).\n\nI can enable Stealth Mode now.",
                    "Enable Stealth Mode"
                ) { service?.toggleTheaterMode(true) }

                IntentType.AUDIO_ISSUES -> handleAudio(service)
                IntentType.CONNECTION_ISSUES -> AssistantResponse(
                    "Ensure both devices are on the SAME Wi-Fi/Hotspot. CD Signal works peer-to-peer. No internet required.",
                    "Run Diagnostics"
                ) { SystemDiagnostics.runChecks(context, service) }

                IntentType.SECURITY_PRIVACY -> AssistantResponse(
                    "Encryption is AES-256 (GCM). Your IP is hidden. We do not log data. You are ghost.",
                    "Verify Security"
                ) { SystemDiagnostics.runChecks(context, service) }

                IntentType.LEGAL_POLICE -> handleLegal()
                IntentType.IDENTITY -> handleIdentity()
                IntentType.EMERGENCY -> AssistantResponse(
                    "Use the RED button on the Connect tab for immediate distress signals. This alerts all nearby nodes.",
                    "Trigger SOS Now"
                ) { service?.confirmSos() }

                IntentType.RESTORE -> handleRestore(service)
                IntentType.GREETING -> AssistantResponse("CD-1 Survival Systems Online. Ready for tactical or medical assistance.", null, null)
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
            "I am CD-1, the autonomous neural interface for CD Signal. Architect: Chinmoy Das. My mission is user survival.",
            null, null
        )
    }

    private fun handleFallback(context: Context, service: VoiceService?): AssistantResponse {
        return AssistantResponse(
            "I am listening. Ask about: CPR, Bleeding, Water, Fire, Shelter, Women Safety, or App Repair.",
            "Run System Check"
        ) {
            SystemDiagnostics.runChecks(context, service)
        }
    }
}