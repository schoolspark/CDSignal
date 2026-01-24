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

    private val _safetyEvents = MutableSharedFlow<SafetyEvent>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val safetyEvents: SharedFlow<SafetyEvent> = _safetyEvents

    private val scope = CoroutineScope(Dispatchers.Main)

    fun triggerSOS(senderIp: String) {
        scope.launch { _safetyEvents.emit(SafetyEvent.SOS(senderIp)) }
    }

    fun triggerLocation(senderIp: String, coords: String) {
        try {
            val parts = coords.split(",")
            if (parts.size >= 2) {
                // [FIX] Safer Parsing to prevent crashes
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

    fun clearEvent() {
        scope.launch { _safetyEvents.resetReplayCache() }
    }
}