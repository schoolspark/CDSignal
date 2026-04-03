package `in`.chinmoydas.signal.utils

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Central Hub for Emergency Logic.
 * Manages SOS Broadcasts, Location Streaming, and the "Safe Walk" Dead Man's Switch.
 */
object SafetySignaling {

    sealed class SafetyEvent {
        data class SOS(val senderIp: String, val reason: String = "Panic Button") : SafetyEvent()
        data class Location(val senderIp: String, val lat: Double, val lon: Double) : SafetyEvent()
        data class SecurityAlert(val intruderName: String) : SafetyEvent()
    }

    private val _safetyEvents = MutableSharedFlow<SafetyEvent>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val safetyEvents: SharedFlow<SafetyEvent> = _safetyEvents

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // --- SAFE WALK (Dead Man's Switch) ---
    private val _safeWalkTimeRemaining = MutableStateFlow<Long?>(null)
    val safeWalkTimeRemaining: StateFlow<Long?> = _safeWalkTimeRemaining.asStateFlow()

    private var safeWalkJob: Job? = null
    private var walkIntervalMs = 0L
    private var lastCheckIn = 0L

    // We must remember the duration to restart the timer after a timeout
    private var currentDurationMinutes: Int = 15

    // [CALLBACK] VoiceService listens to this to send the network packet
    var onSafeWalkTimeout: (() -> Unit)? = null

    /**
     * Starts the Safe Walk Dead Man's Switch.
     * @param minutes The interval (e.g., 15 minutes). User must check in within this time.
     */
    fun startSafeWalk(minutes: Int) {
        if (minutes <= 0) return
        stopSafeWalk() // Ensure clean slate before starting

        currentDurationMinutes = minutes
        walkIntervalMs = minutes * 60 * 1000L
        lastCheckIn = System.currentTimeMillis()

        safeWalkJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                val passed = System.currentTimeMillis() - lastCheckIn
                val remaining = walkIntervalMs - passed

                if (remaining <= 0) {
                    // TIMEOUT -> TRIGGER ALARM
                    _safeWalkTimeRemaining.value = 0

                    // 1. Notify UI
                    triggerSOS("Self", "Safe Walk Timeout")

                    // 2. Notify Network Layer (VoiceService)
                    onSafeWalkTimeout?.invoke()

                    // 3. Stop the timer gracefully (Do not use cancel() as it throws an Exception)
                    break
                } else {
                    _safeWalkTimeRemaining.value = remaining
                    delay(1000) // Update UI every second
                }
            }
        }
    }

    fun checkIn() {
        // If timer is running, just extend it.
        if (safeWalkJob?.isActive == true) {
            lastCheckIn = System.currentTimeMillis()
        } else {
            // If timer HIT ZERO and stopped, we must RESTART it completely.
            startSafeWalk(currentDurationMinutes)
        }
    }

    fun stopSafeWalk() {
        safeWalkJob?.cancel()
        safeWalkJob = null
        _safeWalkTimeRemaining.value = null // This hides the UI Card
    }

    // --- TRIGGERS ---

    fun triggerSOS(senderIp: String, reason: String = "Panic Button") {
        scope.launch { _safetyEvents.emit(SafetyEvent.SOS(senderIp, reason)) }
    }

    fun triggerLocation(senderIp: String, coords: String) {
        try {
            val parts = coords.split(",")
            if (parts.size >= 2) {
                val lat = parts[0].toDoubleOrNull()
                val lon = parts[1].toDoubleOrNull()

                if (lat != null && lon != null) {
                    scope.launch {
                        _safetyEvents.emit(SafetyEvent.Location(senderIp, lat, lon))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun triggerSecurityAlert(intruderName: String) {
        scope.launch { _safetyEvents.emit(SafetyEvent.SecurityAlert(intruderName)) }
    }

    fun clearEvent() {
        scope.launch { _safetyEvents.resetReplayCache() }
    }
}