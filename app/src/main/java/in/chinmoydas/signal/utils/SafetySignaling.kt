package `in`.chinmoydas.signal.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

object SafetySignaling {

    sealed class SafetyEvent {
        data class SOS(val senderIp: String) : SafetyEvent()
        data class Location(val senderIp: String, val lat: Double, val lon: Double) : SafetyEvent()
    }

    // [FIX] replay = 1 ensures the event "sticks" until the UI consumes it
    private val _safetyEvents = MutableSharedFlow<SafetyEvent>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val safetyEvents: SharedFlow<SafetyEvent> = _safetyEvents

    private val scope = CoroutineScope(Dispatchers.Main)

    fun triggerSOS(senderIp: String) {
        scope.launch {
            _safetyEvents.emit(SafetyEvent.SOS(senderIp))
        }
    }

    fun triggerLocation(senderIp: String, coords: String) {
        try {
            val parts = coords.split(",")
            if (parts.size == 2) {
                // [FIX] Use Locale-safe parsing could be added here,
                // but standard Double.parseDouble handles '.' correctly
                val lat = parts[0].toDouble()
                val lon = parts[1].toDouble()
                scope.launch {
                    _safetyEvents.emit(SafetyEvent.Location(senderIp, lat, lon))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // [NEW] Clear the event after it's acknowledged so it doesn't pop up again
    fun clearEvent() {
        scope.launch {
            _safetyEvents.resetReplayCache()
        }
    }
}