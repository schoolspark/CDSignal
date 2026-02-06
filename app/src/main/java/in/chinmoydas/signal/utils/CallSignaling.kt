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
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class CallStatus { Idle, Ringing, Dialing, Active }

object CallSignaling {

    private const val TAG = "CallSignaling"

    // [PROTOCOL COMMANDS]
    const val CMD_REQ = "CMD:CALL:REQ"
    const val CMD_ACC = "CMD:CALL:ACC"
    const val CMD_REJ = "CMD:CALL:REJ"
    const val CMD_END = "CMD:CALL:END"
    const val CMD_BUSY = "CMD:CALL:BUSY"

    // [NEW] Event Bus for VoiceService (Reliable Transmission)
    // Emits: Pair(TargetIP, CommandString)
    private val _commandEvents = MutableSharedFlow<Pair<String, String>>(replay = 0)
    val commandEvents = _commandEvents.asSharedFlow()

    private val _callStatus = MutableStateFlow(CallStatus.Idle)
    val callStatus: StateFlow<CallStatus> = _callStatus.asStateFlow()

    private val _callEvents = MutableSharedFlow<CallEvent>(replay = 1)
    val callEvents: SharedFlow<CallEvent> = _callEvents

    var currentCallerIp: String? = null
    private var isBusy = false
    private val scope = CoroutineScope(Dispatchers.Main)
    private var callTimeoutJob: Job? = null
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    // --- INCOMING SIGNAL HANDLER (Called by VoiceService) ---
    //
    fun handleSignal(payload: String, ip: String) {
        if (!payload.startsWith("CMD:CALL")) return
        Log.d(TAG, "Signal: $payload from $ip")

        when (payload) {
            CMD_REQ -> {
                // [FIX] Idempotency: If already ringing for THIS ip, just re-ack
                if (_callStatus.value == CallStatus.Ringing && currentCallerIp == ip) {
                    queueCommand(ip, "ACK:$CMD_REQ")
                    return
                }

                if (isBusy || CallEngine.isCallActive) {
                    queueCommand(ip, CMD_BUSY)
                    return
                }

                // 1. Set State
                isBusy = true
                currentCallerIp = ip
                _callStatus.value = CallStatus.Ringing

                // 2. Launch UI & Notify Service
                launchIncomingCallUI()
                scope.launch { _callEvents.emit(CallEvent.IncomingCall(ip)) }

                // 3. Send ACK (Stops caller retry loop)
                queueCommand(ip, "ACK:$CMD_REQ")

                // 4. Safety Timeout (30s Ringing Limit)
                callTimeoutJob = scope.launch {
                    delay(30_000)
                    if (_callStatus.value == CallStatus.Ringing) declineCall()
                }
            }

            CMD_ACC -> {
                if (_callStatus.value == CallStatus.Dialing) {
                    callTimeoutJob?.cancel()
                    _callStatus.value = CallStatus.Active
                    scope.launch { _callEvents.emit(CallEvent.CallConnected) }
                    // ACK the Acceptance to stop their retry loop
                    queueCommand(ip, "ACK:$CMD_ACC")
                }
            }

            CMD_REJ -> {
                if (_callStatus.value == CallStatus.Dialing) {
                    scope.launch { _callEvents.emit(CallEvent.CallRejected) }
                    reset()
                }
            }

            CMD_BUSY -> {
                if (_callStatus.value == CallStatus.Dialing) {
                    scope.launch { _callEvents.emit(CallEvent.CallBusy) }
                    reset()
                }
            }

            CMD_END -> {
                // Remote hangup
                scope.launch { _callEvents.emit(CallEvent.CallEnded) }
                reset()
            }
        }
    }

    // --- OUTGOING ACTIONS (Called by UI) ---

    fun startOutgoingCall(ip: String) {
        if (isBusy) return
        isBusy = true
        currentCallerIp = ip
        _callStatus.value = CallStatus.Dialing

        // Send Reliable Invite
        queueCommand(ip, CMD_REQ)

        scope.launch { _callEvents.emit(CallEvent.OutgoingCall(ip)) }

        callTimeoutJob?.cancel()
        callTimeoutJob = scope.launch {
            delay(30_000)
            // If still dialing after 30s, cancel it
            if (_callStatus.value == CallStatus.Dialing) {
                queueCommand(ip, CMD_END)
                scope.launch { _callEvents.emit(CallEvent.CallEnded) }
                reset()
            }
        }
    }

    fun acceptCall() {
        val ip = currentCallerIp ?: return
        callTimeoutJob?.cancel() // Stop the "Missed Call" timer
        _callStatus.value = CallStatus.Active

        // Send Reliable Accept
        queueCommand(ip, CMD_ACC)

        scope.launch { _callEvents.emit(CallEvent.CallConnected) }
    }

    fun declineCall() {
        val ip = currentCallerIp
        if (ip != null) queueCommand(ip, CMD_REJ)
        scope.launch { _callEvents.emit(CallEvent.CallRejected) }
        reset()
    }

    fun endCall() {
        val ip = currentCallerIp
        if (ip != null) queueCommand(ip, CMD_END)
        scope.launch { _callEvents.emit(CallEvent.CallEnded) }
        reset()
    }

    // Reset internal state
    private fun reset() {
        isBusy = false
        currentCallerIp = null
        callTimeoutJob?.cancel()
        _callStatus.value = CallStatus.Idle
    }

    // --- HELPER: Enqueue Command for VoiceService ---
    private fun queueCommand(ip: String, cmd: String) {
        scope.launch {
            _commandEvents.emit(ip to cmd)
        }
    }

    // --- HELPER: Android 12+ Background Activity Start ---
    fun onIncomingCall(ip: String) {
        // Wrapper for VoiceService to call
        handleSignal(CMD_REQ, ip)
    }

    fun onCallAccepted() { acceptCall() }
    fun onCallRejected() { declineCall() }
    fun onCallEnded() { endCall() }
    fun onPeerBusy() {
        scope.launch { _callEvents.emit(CallEvent.CallBusy) }
        reset()
    }

    private fun launchIncomingCallUI() {
        appContext?.let { ctx ->
            try {
                val intent = Intent(ctx, `in`.chinmoydas.signal.MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    // These extras ensure MainActivity opens the Call Overlay
                    action = "INCOMING_CALL"
                    putExtra("is_call", true)
                }
                ctx.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch UI", e)
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