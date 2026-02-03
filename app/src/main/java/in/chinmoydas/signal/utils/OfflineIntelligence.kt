package `in`.chinmoydas.signal.utils

import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.BatteryManager
import android.widget.Toast
import `in`.chinmoydas.signal.VoiceService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

data class AssistantResponse(
    val text: String,
    val actionLabel: String? = null,
    val action: (() -> Unit)? = null
)

/**
 * CD-1 (Version 5.1): Functional Actions Fix
 * FIX: Now correctly triggers UI navigation and System callbacks.
 */
object OfflineIntelligence {

    private enum class IntentType {
        // --- APP CONTROL ---
        APP_STATUS, APP_ECO_ON, APP_ECO_OFF, APP_SECURE_ON, APP_SECURE_OFF, APP_DISCONNECT,
        AUDIO_STEALTH, AUDIO_LOUD,

        // --- MEDICAL ---
        SURVIVAL_CPR, SURVIVAL_BLEEDING, SURVIVAL_BURNS, SURVIVAL_BITES,

        // --- WILDERNESS ---
        SURVIVAL_WATER, SURVIVAL_FIRE, SURVIVAL_SHELTER, SURVIVAL_NAV, SURVIVAL_FORAGE,

        // --- SAFETY ---
        SURVIVAL_SOS, SURVIVAL_BATTERY, SURVIVAL_PSYCH, SAFETY_WOMEN,

        // --- DISASTERS ---
        DISASTER_QUAKE, DISASTER_FLOOD, DISASTER_STORM,

        // --- SYSTEM ---
        CONNECTION_ISSUES, SECURITY_PRIVACY, LEGAL_POLICE, IDENTITY, RESTORE, GREETING
    }

    // [FIX] Added 'onOpenDiagnostics' callback to bridge the AI with the UI
    suspend fun think(
        query: String,
        context: Context,
        service: VoiceService?,
        onOpenDiagnostics: () -> Unit
    ): AssistantResponse {
        val q = query.lowercase(Locale.ROOT).trim()
        val scores = mutableMapOf<IntentType, Int>()

        // --- APP CONTROL ---
        if (hasAny(q, "status", "report", "sitrep", "check", "system", "diagnostic")) scores.merge(IntentType.APP_STATUS, 5, Int::plus)
        if (hasAny(q, "eco", "battery saver", "trek")) {
            if (hasAny(q, "off", "disable")) scores.merge(IntentType.APP_ECO_OFF, 6, Int::plus)
            else scores.merge(IntentType.APP_ECO_ON, 6, Int::plus)
        }
        if (hasAny(q, "secure", "encrypt")) {
            if (hasAny(q, "off", "disable", "open")) scores.merge(IntentType.APP_SECURE_OFF, 6, Int::plus)
            else scores.merge(IntentType.APP_SECURE_ON, 6, Int::plus)
        }
        if (hasAny(q, "disconnect", "standby", "clear", "stop talking")) scores.merge(IntentType.APP_DISCONNECT, 6, Int::plus)
        if (hasAny(q, "silent", "dark", "stealth", "theater")) scores.merge(IntentType.AUDIO_STEALTH, 5, Int::plus)
        if (hasAny(q, "loud", "speaker", "normal")) scores.merge(IntentType.AUDIO_LOUD, 5, Int::plus)

        // --- SURVIVAL ---
        if (hasAny(q, "cpr", "heart")) scores.merge(IntentType.SURVIVAL_CPR, 5, Int::plus)
        if (hasAny(q, "bleed", "cut", "wound")) scores.merge(IntentType.SURVIVAL_BLEEDING, 5, Int::plus)
        if (hasAny(q, "burn", "fire", "scald")) scores.merge(IntentType.SURVIVAL_BURNS, 5, Int::plus)
        if (hasAny(q, "bite", "snake", "venom")) scores.merge(IntentType.SURVIVAL_BITES, 5, Int::plus)
        if (hasAny(q, "water", "thirst")) scores.merge(IntentType.SURVIVAL_WATER, 5, Int::plus)
        if (hasAny(q, "fire", "cold")) scores.merge(IntentType.SURVIVAL_FIRE, 5, Int::plus)
        if (hasAny(q, "shelter", "rain")) scores.merge(IntentType.SURVIVAL_SHELTER, 5, Int::plus)
        if (hasAny(q, "north", "direction", "compass", "lost")) scores.merge(IntentType.SURVIVAL_NAV, 5, Int::plus)
        if (hasAny(q, "eat", "food", "berry")) scores.merge(IntentType.SURVIVAL_FORAGE, 5, Int::plus)
        if (hasAny(q, "sos", "signal", "help")) scores.merge(IntentType.SURVIVAL_SOS, 5, Int::plus)
        if (hasAny(q, "girl", "woman", "unsafe", "stalk")) scores.merge(IntentType.SAFETY_WOMEN, 6, Int::plus)

        // --- SYSTEM ---
        if (hasAny(q, "reset", "fix", "repair")) scores.merge(IntentType.RESTORE, 4, Int::plus)
        if (hasAny(q, "hello", "hi", "cd1")) scores.merge(IntentType.GREETING, 2, Int::plus)

        val bestMatch = scores.maxByOrNull { it.value }

        return if (bestMatch != null && bestMatch.value >= 2) {
            when (bestMatch.key) {
                // [FIX] Now calls 'generateSitrep' which correctly wires the Diagnostics button
                IntentType.APP_STATUS -> generateSitrep(context, service, onOpenDiagnostics)

                IntentType.APP_ECO_ON -> applyConfig(context, service, "eco_mode", true, "ECO MODE ENGAGED. Heartbeat disabled.")
                IntentType.APP_ECO_OFF -> applyConfig(context, service, "eco_mode", false, "ECO MODE DISABLED. Radio active.")

                IntentType.APP_SECURE_ON -> applyConfig(context, service, "secure_mode", true, "SECURE CHANNEL ACTIVE (AES-256).")
                IntentType.APP_SECURE_OFF -> applyConfig(context, service, "secure_mode", false, "CHANNEL OPEN (Public).")

                IntentType.APP_DISCONNECT -> {
                    val prefs = context.getSharedPreferences("WalkiePrefs", Context.MODE_PRIVATE)
                    prefs.edit().putString("current_target", "").apply()
                    AssistantResponse("TARGET CLEARED. Radio in Standby.", null, null)
                }

                IntentType.AUDIO_STEALTH -> {
                    service?.toggleTheaterMode(true)
                    AssistantResponse("STEALTH MODE ACTIVE. Haptics only.", "Restore Audio") { service?.toggleTheaterMode(false) }
                }
                IntentType.AUDIO_LOUD -> {
                    service?.toggleTheaterMode(false)
                    service?.toggleSpeaker(true)
                    AssistantResponse("AUDIO RESTORED. Speaker active.", null, null)
                }

                IntentType.SURVIVAL_CPR -> AssistantResponse("CPR: Push hard & fast (100-120 bpm) in center of chest. Allow recoil.", "Call Emergency") { service?.sendPanicAlert() }
                IntentType.SURVIVAL_BLEEDING -> AssistantResponse("BLEEDING: Direct pressure. Do not remove soaked cloths. Tourniquet only for life threat.", null, null)
                IntentType.SURVIVAL_BURNS -> AssistantResponse("BURNS: Cool water 10 mins. Cover with sterile cloth. Do NOT pop blisters.", null, null)
                IntentType.SURVIVAL_SOS -> AssistantResponse("SOS PATTERN: 3 Short, 3 Long, 3 Short. Broadcasting digital alert now...", "BROADCAST SOS") { service?.sendPanicAlert() }

                // [FIX] Implemented Fake Call Logic
                IntentType.SAFETY_WOMEN -> AssistantResponse(
                    "PROTOCOL: Stay in light. If threatened, yell 'FIRE'. Prepare to engage Fake Call?",
                    "Simulate Call"
                ) { triggerFakeRing(context) }

                // [FIX] Implemented Compass Hint
                IntentType.SURVIVAL_NAV -> AssistantResponse(
                    "NAV: Sun rises East. Moss grows North. Use Shadow Stick method if sunny.",
                    "Check Sensors"
                ) { onOpenDiagnostics() } // Re-using diagnostics to check sensors

                IntentType.SURVIVAL_WATER -> AssistantResponse("WATER: Boil 1 min. Collect morning dew. Dig in dry river bends.", null, null)

                IntentType.RESTORE -> AssistantResponse("Resetting Audio Flags...", "EXECUTE") { service?.toggleTheaterMode(false); service?.toggleSpeaker(true) }
                IntentType.GREETING -> AssistantResponse("CD-1 Online. Ready.", null, null)

                else -> AssistantResponse("Command unclear. I handle: Radio Config, Medical Guide, Survival Protocols.", "Run Diagnostics") { onOpenDiagnostics() }
            }
        } else {
            // [FIX] Fallback now correctly opens Diagnostics
            AssistantResponse("Standby. Systems nominal.", "Run Diagnostics") { onOpenDiagnostics() }
        }
    }

    private fun hasAny(query: String, vararg keywords: String): Boolean {
        return keywords.any { query.contains(it) }
    }

    // [FIX] Pass the callback here
    private fun generateSitrep(context: Context, service: VoiceService?, onOpenDiagnostics: () -> Unit): AssistantResponse {
        val prefs = context.getSharedPreferences("WalkiePrefs", Context.MODE_PRIVATE)
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryLevel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val target = prefs.getString("current_target", "") ?: ""
        val targetStatus = if (target.isBlank()) "STANDBY" else "LOCKED: $target"
        val isEco = prefs.getBoolean("eco_mode", false)
        val isSecure = prefs.getBoolean("secure_mode", false)
        val modeStr = (if(isEco) "ECO" else "PERF") + " | " + (if(isSecure) "AES-256" else "OPEN")
        val radioStatus = service?.voiceServiceState?.value?.networkStatus ?: "Offline"

        val report = """
            SITUATION REPORT
            ----------------
            BATTERY: $batteryLevel%
            RADIO:   $radioStatus
            TARGET:  $targetStatus
            MODES:   $modeStr
        """.trimIndent()

        // [FIX] Action now invokes the UI callback
        return AssistantResponse(report, "Run Diagnostics") { onOpenDiagnostics() }
    }

    private fun applyConfig(context: Context, service: VoiceService?, key: String, value: Boolean, responseText: String): AssistantResponse {
        val prefs = context.getSharedPreferences("WalkiePrefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean(key, value).apply()
        if (key == "eco_mode") {
            val intent = Intent(context, VoiceService::class.java).apply {
                action = "TOGGLE_ECO"
                putExtra("state", value)
            }
            context.startService(intent)
        }
        return AssistantResponse(responseText, null, null)
    }

    // [FIX] Simple Fake Call Simulation
    private fun triggerFakeRing(context: Context) {
        CoroutineScope(Dispatchers.Main).launch {
            Toast.makeText(context, "Fake Call in 5s...", Toast.LENGTH_SHORT).show()
            delay(5000)
            try {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                val r = RingtoneManager.getRingtone(context, uri)
                r.play()
                Toast.makeText(context, "INCOMING CALL...", Toast.LENGTH_LONG).show()
                delay(10000)
                r.stop()
            } catch (e: Exception) { }
        }
    }
}