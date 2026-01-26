package `in`.chinmoydas.signal.utils

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// Move Enum here so it's accessible globally
enum class CallStatus { Idle, Ringing, Dialing, Active }

object CallSignaling {

    private const val TAG = "CallSignaling"

    const val CMD_REQ = "CMD:CALL_REQ"
    const val CMD_ACC = "CMD:CALL_ACC"
    const val CMD_REJ = "CMD:CALL_REJ"
    const val CMD_END = "CMD:CALL_END"
    const val CMD_BUSY = "CMD:CALL_BUSY"

    // [FIX] Persistent State (Source of Truth)
    private val _callStatus = MutableStateFlow(CallStatus.Idle)
    val callStatus: StateFlow<CallStatus> = _callStatus.asStateFlow()

    // Keep events for one-shot triggers (like sounds or toasts)
    private val _callEvents = MutableSharedFlow<CallEvent>(replay = 1)
    val callEvents: SharedFlow<CallEvent> = _callEvents

    var currentCallerIp: String? = null
    var isBusy = false
    private var callTimeoutJob: Job? = null

    var sendUdpTextFunction: ((String, String) -> Unit)? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    suspend fun handlePacket(text: String, senderIp: String) {
        if (!text.startsWith("CMD:CALL")) return

        Log.d(TAG, "Signal: $text from $senderIp")

        when (text) {
            CMD_REQ -> {
                if (isBusy || CallEngine.isCallActive) {
                    sendSignal(CMD_BUSY, senderIp)
                } else {
                    isBusy = true
                    currentCallerIp = senderIp
                    _callStatus.value = CallStatus.Ringing // [FIX] Update State
                    _callEvents.emit(CallEvent.IncomingCall(senderIp))
                }
            }
            CMD_ACC -> {
                callTimeoutJob?.cancel()
                _callStatus.value = CallStatus.Active // [FIX] Update State
                _callEvents.emit(CallEvent.CallConnected)
                CallEngine.startCall(senderIp)
            }
            CMD_REJ -> {
                callTimeoutJob?.cancel()
                _callEvents.emit(CallEvent.CallRejected)
                reset()
            }
            CMD_BUSY -> {
                callTimeoutJob?.cancel()
                _callEvents.emit(CallEvent.CallBusy)
                reset()
            }
            CMD_END -> {
                callTimeoutJob?.cancel()
                CallEngine.stopCall()
                _callEvents.emit(CallEvent.CallEnded)
                reset()
            }
        }
    }

    private fun sendSignal(cmd: String, ip: String) {
        scope.launch(Dispatchers.IO) {
            repeat(5) {
                sendUdpTextFunction?.invoke(cmd, ip)
                delay(40)
            }
        }
    }

    fun startOutgoingCall(ip: String) {
        if (isBusy) return
        isBusy = true
        currentCallerIp = ip

        // [FIX] Set State IMMEDIATELY
        _callStatus.value = CallStatus.Dialing

        sendSignal(CMD_REQ, ip)
        scope.launch { _callEvents.emit(CallEvent.OutgoingCall(ip)) }

        callTimeoutJob?.cancel()
        callTimeoutJob = scope.launch {
            delay(30_000)
            if (isBusy && !CallEngine.isCallActive) {
                sendSignal(CMD_END, ip)
                _callEvents.emit(CallEvent.CallEnded)
                reset()
            }
        }
    }

    fun acceptCall() {
        val ip = currentCallerIp ?: return
        isBusy = true
        _callStatus.value = CallStatus.Active // [FIX] Update State
        sendSignal(CMD_ACC, ip)
        scope.launch { _callEvents.emit(CallEvent.CallConnected) }
        CallEngine.startCall(ip)
    }

    fun declineCall() {
        val ip = currentCallerIp
        if (ip != null) sendSignal(CMD_REJ, ip)
        reset()
    }

    fun endCall() {
        callTimeoutJob?.cancel()
        val ip = currentCallerIp
        if (ip != null) sendSignal(CMD_END, ip)
        CallEngine.stopCall()
        scope.launch { _callEvents.emit(CallEvent.CallEnded) }
        reset()
    }

    private fun reset() {
        isBusy = false
        currentCallerIp = null
        callTimeoutJob?.cancel()
        _callStatus.value = CallStatus.Idle // [FIX] Reset State
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