package `in`.chinmoydas.signal.utils

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

object CallSignaling {

    private const val TAG = "CallSignaling"

    // Protocol Commands
    const val CMD_REQ = "CMD:CALL_REQ"
    const val CMD_ACC = "CMD:CALL_ACC"
    const val CMD_REJ = "CMD:CALL_REJ"
    const val CMD_END = "CMD:CALL_END"
    const val CMD_BUSY = "CMD:CALL_BUSY"

    // UI Events
    private val _callEvents = MutableSharedFlow<CallEvent>()
    val callEvents: SharedFlow<CallEvent> = _callEvents

    // State
    var currentCallerIp: String? = null
    var isBusy = false

    // Sender Function (To be linked from ViewModel)
    var sendUdpTextFunction: ((String, String) -> Unit)? = null

    // Scope for emitting events
    private val scope = CoroutineScope(Dispatchers.Main)

    suspend fun handlePacket(text: String, senderIp: String) {
        if (!text.startsWith("CMD:CALL")) return

        Log.d(TAG, "Signal: $text from $senderIp")

        when (text) {
            CMD_REQ -> {
                if (isBusy || CallEngine.isCallActive) {
                    sendSignal(CMD_BUSY, senderIp)
                } else {
                    currentCallerIp = senderIp
                    _callEvents.emit(CallEvent.IncomingCall(senderIp))
                }
            }
            CMD_ACC -> {
                _callEvents.emit(CallEvent.CallConnected)
                CallEngine.startCall(senderIp)
            }
            CMD_REJ -> {
                _callEvents.emit(CallEvent.CallRejected)
                reset()
            }
            CMD_BUSY -> {
                _callEvents.emit(CallEvent.CallBusy)
                reset()
            }
            CMD_END -> {
                CallEngine.stopCall()
                _callEvents.emit(CallEvent.CallEnded)
                reset()
            }
        }
    }

    // [FIX] Redundant Signaling: Send critical packets 3 times to prevent data loss
    private fun sendSignal(cmd: String, ip: String) {
        scope.launch(Dispatchers.IO) {
            repeat(3) {
                sendUdpTextFunction?.invoke(cmd, ip)
                delay(50) // Tiny delay between bursts
            }
        }
    }

    // UI Actions
    fun startOutgoingCall(ip: String) {
        currentCallerIp = ip
        sendSignal(CMD_REQ, ip)
        // Notify UI immediately
        scope.launch {
            _callEvents.emit(CallEvent.OutgoingCall(ip))
        }
    }

    fun acceptCall() {
        val ip = currentCallerIp ?: return
        isBusy = true
        sendSignal(CMD_ACC, ip)

        // [FIX] Critical: Update LOCAL UI state immediately to prevent freeze
        scope.launch {
            _callEvents.emit(CallEvent.CallConnected)
        }

        CallEngine.startCall(ip)
    }

    fun declineCall() {
        val ip = currentCallerIp ?: return
        sendSignal(CMD_REJ, ip)
        reset()
    }

    fun endCall() {
        val ip = currentCallerIp
        if (ip != null) {
            // [FIX] Send the END command multiple times before closing
            sendSignal(CMD_END, ip)
        }

        CallEngine.stopCall()

        // Notify Local UI immediately
        scope.launch {
            _callEvents.emit(CallEvent.CallEnded)
        }

        reset()
    }

    private fun reset() {
        isBusy = false
        currentCallerIp = null
    }

    sealed class CallEvent {
        data class IncomingCall(val ip: String) : CallEvent()
        data class OutgoingCall(val ip: String) : CallEvent()
        object CallConnected : CallEvent()
        object CallRejected : CallEvent()
        object CallBusy : CallEvent()
        object CallEnded : CallEvent()
    }
}