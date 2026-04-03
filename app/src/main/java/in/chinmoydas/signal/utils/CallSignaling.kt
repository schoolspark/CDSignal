package `in`.chinmoydas.signal.utils

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * CD Signal - VoIP State & Signaling Hub
 * Upgraded for Mothership Session Management (2026)
 */
enum class CallStatus { Idle, Ringing, Dialing, Active }

data class CallSession(
    val peerIp: String,
    val peerName: String = "Unknown",
    val startTime: Long = 0L,
    val isEncrypted: Boolean = false
)

object CallSignaling {
    private const val TAG = "CallSignaling"

    // [PROTOCOL COMMANDS]
    const val CMD_REQ = "CMD:CALL:REQ"   // Invite
    const val CMD_ACC = "CMD:CALL:ACC"   // Accept
    const val CMD_REJ = "CMD:CALL:REJ"   // Reject
    const val CMD_END = "CMD:CALL:END"   // Hangup
    const val CMD_BUSY = "CMD:CALL:BUSY" // Busy
    const val CMD_PING = "CMD:CALL:PING" // Keep-alive

    // Command Bus: Listened to by VoiceService for reliable delivery
    private val _commandEvents = MutableSharedFlow<Pair<String, String>>(replay = 0)
    val commandEvents = _commandEvents.asSharedFlow()

    // UI State
    private val _callStatus = MutableStateFlow(CallStatus.Idle)
    val callStatus: StateFlow<CallStatus> = _callStatus.asStateFlow()

    private val _currentSession = MutableStateFlow<CallSession?>(null)
    val currentSession: StateFlow<CallSession?> = _currentSession.asStateFlow()

    // Event Bus for Service Logic (Ringing, Connecting, etc)
    private val _callEvents = MutableSharedFlow<CallEvent>(replay = 1)
    val callEvents: SharedFlow<CallEvent> = _callEvents

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var callTimeoutJob: Job? = null
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * --- INCOMING SIGNAL HANDLER ---
     * Called by VoiceService when it receives a "CMD:CALL:..." text packet.
     */
    fun handleSignal(payload: String, ip: String, senderName: String = "Unknown") {
        if (!payload.startsWith("CMD:CALL")) return
        Log.d(TAG, "Incoming Signal: $payload from $senderName ($ip)")

        when (payload) {
            CMD_REQ -> {
                // 1. Idempotency: Ignore if we are already ringing/active for this session
                if (_callStatus.value != CallStatus.Idle) {
                    if (_currentSession.value?.peerIp == ip) {
                        // Already handled, VoiceService already sent the ACK
                        return
                    }
                    // We are busy with someone else
                    queueCommand(ip, CMD_BUSY)
                    return
                }

                // 2. Set Session & State
                _currentSession.value = CallSession(peerIp = ip, peerName = senderName)
                _callStatus.value = CallStatus.Ringing

                // 3. Trigger UI & Audio (Ringtone)
                launchIncomingCallUI(senderName)
                scope.launch { _callEvents.emit(CallEvent.IncomingCall(ip)) }

                // 4. Safety Timeout: Auto-Reject if user doesn't answer in 30s
                startTimeoutTimer(30_000) { declineCall() }
            }

            CMD_ACC -> {
                if (_callStatus.value == CallStatus.Dialing) {
                    stopTimeoutTimer()
                    _callStatus.value = CallStatus.Active
                    scope.launch { _callEvents.emit(CallEvent.CallConnected) }
                }
            }

            CMD_REJ -> {
                if (_callStatus.value == CallStatus.Dialing || _callStatus.value == CallStatus.Ringing) {
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
                scope.launch { _callEvents.emit(CallEvent.CallEnded) }
                reset()
            }
        }
    }

    /**
     * --- OUTGOING ACTIONS (Triggered by UI Buttons) ---
     */

    fun startOutgoingCall(ip: String, name: String) {
        if (_callStatus.value != CallStatus.Idle) return

        _currentSession.value = CallSession(peerIp = ip, peerName = name)
        _callStatus.value = CallStatus.Dialing

        // Instruct VoiceService to send reliable invite
        queueCommand(ip, CMD_REQ)
        scope.launch { _callEvents.emit(CallEvent.OutgoingCall(ip)) }

        // Timeout if no response from peer
        startTimeoutTimer(25_000) {
            queueCommand(ip, CMD_END)
            scope.launch { _callEvents.emit(CallEvent.CallEnded) }
            reset()
        }
    }

    fun acceptCall() {
        val session = _currentSession.value ?: return
        stopTimeoutTimer()
        _callStatus.value = CallStatus.Active

        // Inform Peer we are connecting
        queueCommand(session.peerIp, CMD_ACC)
        scope.launch { _callEvents.emit(CallEvent.CallConnected) }
    }

    fun declineCall() {
        _currentSession.value?.let { queueCommand(it.peerIp, CMD_REJ) }
        scope.launch { _callEvents.emit(CallEvent.CallRejected) }
        reset()
    }

    fun endCall() {
        _currentSession.value?.let { queueCommand(it.peerIp, CMD_END) }
        scope.launch { _callEvents.emit(CallEvent.CallEnded) }
        reset()
    }

    /**
     * --- INTERNALS & HELPERS ---
     */

    private fun reset() {
        _callStatus.value = CallStatus.Idle
        _currentSession.value = null
        stopTimeoutTimer()
    }

    private fun queueCommand(ip: String, cmd: String) {
        scope.launch { _commandEvents.emit(ip to cmd) }
    }

    private fun startTimeoutTimer(ms: Long, onTimeout: () -> Unit) {
        stopTimeoutTimer()
        callTimeoutJob = scope.launch {
            delay(ms)
            onTimeout()
        }
    }

    private fun stopTimeoutTimer() {
        callTimeoutJob?.cancel()
        callTimeoutJob = null
    }

    // Compatibility wrappers for VoiceService
    fun onIncomingCall(ip: String) { handleSignal(CMD_REQ, ip) }
    fun onCallAccepted() { acceptCall() }
    fun onCallRejected() { declineCall() }
    fun onCallEnded() { endCall() }
    fun onPeerBusy() {
        scope.launch { _callEvents.emit(CallEvent.CallBusy) }
        reset()
    }

    /**
     * Handles the "Full Screen Intent" logic for Android 12+
     * ensuring the Call UI pops up even if the phone is locked.
     */
    private fun launchIncomingCallUI(callerName: String) {
        appContext?.let { ctx ->
            try {
                val intent = Intent(ctx, `in`.chinmoydas.signal.MainActivity::class.java).apply {
                    action = "INCOMING_CALL"
                    putExtra("auto_connect_channel", callerName)
                    putExtra("is_call", true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                }
                ctx.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "UI Launch Failed: ${e.message}")
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