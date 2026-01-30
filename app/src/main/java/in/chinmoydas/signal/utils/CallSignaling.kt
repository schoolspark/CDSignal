package `in`.chinmoydas.signal.utils

import android.content.Context
import android.content.Intent
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

// [GLOBAL ENUM]
enum class CallStatus { Idle, Ringing, Dialing, Active }

object CallSignaling {

    private const val TAG = "CallSignaling"

    const val CMD_REQ = "CMD:CALL_REQ"
    const val CMD_ACC = "CMD:CALL_ACC"
    const val CMD_REJ = "CMD:CALL_REJ"
    const val CMD_END = "CMD:CALL_END"
    const val CMD_BUSY = "CMD:CALL_BUSY"

    // [SOURCE OF TRUTH] Persistent State for UI
    private val _callStatus = MutableStateFlow(CallStatus.Idle)
    val callStatus: StateFlow<CallStatus> = _callStatus.asStateFlow()

    // [EVENTS] One-shot events for Toasts/Sounds
    private val _callEvents = MutableSharedFlow<CallEvent>(replay = 1)
    val callEvents: SharedFlow<CallEvent> = _callEvents

    var currentCallerIp: String? = null
    private var isBusy = false
    private val scope = CoroutineScope(Dispatchers.Main)
    private var callTimeoutJob: Job? = null

    // [FIX] Context required to launch UI from background
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    // [ENTRY POINT] Called by MainActivity Interceptor
    fun handleSignal(payload: String, ip: String) {
        if (!payload.startsWith("CMD:CALL")) return

        Log.d(TAG, "Signal: $payload from $ip")

        when (payload) {
            CMD_REQ -> {
                if (isBusy || CallEngine.isCallActive) {
                    sendSignal(CMD_BUSY, ip)
                } else {
                    isBusy = true
                    currentCallerIp = ip
                    _callStatus.value = CallStatus.Ringing

                    // [MISSION CRITICAL] Wake Screen immediately
                    launchIncomingCallUI()

                    scope.launch { _callEvents.emit(CallEvent.IncomingCall(ip)) }

                    callTimeoutJob = scope.launch {
                        delay(30_000) // 30s Answer Timeout
                        if (_callStatus.value == CallStatus.Ringing) {
                            declineCall()
                        }
                    }
                }
            }
            CMD_ACC -> {
                if (_callStatus.value == CallStatus.Dialing) {
                    callTimeoutJob?.cancel()
                    _callStatus.value = CallStatus.Active

                    // 1. Notify UI & VoiceService to release mic
                    scope.launch {
                        _callEvents.emit(CallEvent.CallConnected)

                        // [FIX] Mic Manager: Handover Delay
                        // Give VoiceService 500ms to release AudioRecord before we grab it
                        delay(500)

                        // 2. Start Engine safely
                        CallEngine.startCall(ip)
                    }
                }
            }
            CMD_REJ -> {
                if (_callStatus.value == CallStatus.Dialing) {
                    reset()
                    scope.launch { _callEvents.emit(CallEvent.CallRejected) }
                }
            }
            CMD_BUSY -> {
                if (_callStatus.value == CallStatus.Dialing) {
                    reset()
                    scope.launch { _callEvents.emit(CallEvent.CallBusy) }
                }
            }
            CMD_END -> {
                CallEngine.stopCall()
                reset()
                scope.launch { _callEvents.emit(CallEvent.CallEnded) }
            }
        }
    }

    // [UI TRIGGER]
    // Fires an intent to MainActivity with flags to wake the device
    private fun launchIncomingCallUI() {
        appContext?.let { ctx ->
            val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                // These extras trigger the Keyguard dismissal in MainActivity
                action = "INCOMING_CALL"
                putExtra("is_call", true)
            }
            ctx.startActivity(intent)
        }
    }

    // [OUTGOING]
    fun startOutgoingCall(ip: String) {
        if (isBusy) return
        isBusy = true
        currentCallerIp = ip
        _callStatus.value = CallStatus.Dialing

        sendSignal(CMD_REQ, ip)
        scope.launch { _callEvents.emit(CallEvent.OutgoingCall(ip)) }

        callTimeoutJob?.cancel()
        callTimeoutJob = scope.launch {
            delay(30_000)
            if (isBusy && !CallEngine.isCallActive) {
                // Timeout logic
                sendSignal(CMD_END, ip)
                scope.launch { _callEvents.emit(CallEvent.CallEnded) }
                reset()
            }
        }
    }

    fun acceptCall() {
        val ip = currentCallerIp ?: return
        isBusy = true
        _callStatus.value = CallStatus.Active
        sendSignal(CMD_ACC, ip)

        scope.launch {
            _callEvents.emit(CallEvent.CallConnected)

            // [FIX] Mic Manager: Handover Delay
            delay(500)

            CallEngine.startCall(ip)
        }
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
        _callStatus.value = CallStatus.Idle
    }

    // [NETWORK BRIDGE]
    // Sends Intent to VoiceService to transmit UDP packet (Decoupled Architecture)
    private fun sendSignal(cmd: String, ip: String) {
        // Repeat signal 3 times for redundancy over UDP
        scope.launch(Dispatchers.IO) {
            repeat(3) {
                appContext?.sendBroadcast(Intent("in.chinmoydas.signal.SEND_SIGNAL").apply {
                    putExtra("ip", ip)
                    putExtra("cmd", cmd)
                    setPackage(appContext?.packageName)
                })
                delay(50)
            }
        }
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