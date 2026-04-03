package `in`.chinmoydas.signal.viewmodel

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import `in`.chinmoydas.signal.RetrofitClient
import `in`.chinmoydas.signal.VoiceService
import `in`.chinmoydas.signal.data.CallLog
import `in`.chinmoydas.signal.data.MainRepository
import `in`.chinmoydas.signal.data.PagerEntry
import `in`.chinmoydas.signal.utils.LocalLinkManager
import `in`.chinmoydas.signal.utils.CallSignaling
import `in`.chinmoydas.signal.utils.CallStatus
import `in`.chinmoydas.signal.utils.SafetySignaling
import `in`.chinmoydas.signal.utils.SystemDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import android.graphics.Color as AndroidColor

// Contact Data Class
data class Contact(
    val name: String,
    var ip: String,
    val isTrusted: Boolean = false,
    val savedCode: String = "",
    val isPriority: Boolean = false,
    val fcmToken: String = ""
)

enum class ConnectionStatus {
    IDLE, CHECKING, READY, OFFLINE
}

class WalkieViewModel(private val repository: MainRepository) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState>(UiState.Ready)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    val myPairingCode: StateFlow<String> = repository.myPairingCode

    val pagerEntries: StateFlow<List<PagerEntry>> = repository.pagerEntries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    var targetUser by mutableStateOf("")
        private set

    var connectionStatus by mutableStateOf(ConnectionStatus.IDLE)
        private set

    // [MISSION CRITICAL] PTT Job Management
    private var transmissionJob: Job? = null

    // [FIX ISSUE 7] Track if startup check has run in this session
    var hasShownStartupCheck by mutableStateOf(false)

    var qrBitmap by mutableStateOf<Bitmap?>(null)
    var channelQrBitmap by mutableStateOf<Bitmap?>(null)
    var sharingChannelName by mutableStateOf("")
    var isHandsFree by mutableStateOf(false)
    var isBroadcastMode by mutableStateOf(false)
        private set

    var nearbyUsers = mutableStateListOf<Contact>()
    var savedContacts = mutableStateListOf<Contact>()
    var blockedContacts = mutableStateListOf<Contact>()

    private var mediaPlayer: android.media.MediaPlayer? = null
    private val _callLogs = MutableStateFlow<List<CallLog>>(emptyList())
    val callLogs: StateFlow<List<CallLog>> = _callLogs.asStateFlow()
    private var localManager: LocalLinkManager? = null

    val recoveryEmail = repository.recoveryEmail.stateIn(viewModelScope, SharingStarted.Lazily, "Not Set")

    init {
        viewModelScope.launch {
            repository.targetUser.collect { newTarget ->
                targetUser = newTarget
                if (_uiState.value is UiState.Ready || _uiState.value is UiState.Connected) {
                    _uiState.value = if (newTarget.isEmpty()) UiState.Ready else UiState.Connected(newTarget)
                }
                if (newTarget.isNotEmpty()) {
                    connectionStatus = ConnectionStatus.CHECKING
                } else {
                    connectionStatus = ConnectionStatus.IDLE
                }
            }
        }
        loadData()
    }

    fun getCurrentTargetIp(): String? {
        if (targetUser.isEmpty()) return null
        val saved = savedContacts.find { it.name == targetUser }
        if (saved != null) return saved.ip
        val nearby = nearbyUsers.find { it.name == targetUser }
        return nearby?.ip
    }

    fun togglePriority(name: String) {
        val contact = savedContacts.find { it.name == name } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            repository.setContactPriority(name, !contact.isPriority)
            loadData()
        }
    }

    fun observeServicePing(service: VoiceService?) {
        viewModelScope.launch {
            service?.voiceServiceState?.collect { state ->
                if (state.networkStatus.contains("Listening")) {
                    connectionStatus = ConnectionStatus.READY
                } else if (state.networkStatus.contains("Waiting")) {
                    connectionStatus = ConnectionStatus.OFFLINE
                }
                val diff = System.currentTimeMillis() - state.lastPingResponse
                if (state.lastPingResponse > 0 && diff < 2000) {
                    connectionStatus = ConnectionStatus.READY
                }
            }
        }
    }

    fun triggerPing(service: VoiceService?) {
        if (targetUser.isEmpty() || isBroadcastMode) {
            connectionStatus = ConnectionStatus.IDLE
            return
        }
        connectionStatus = ConnectionStatus.CHECKING
        service?.triggerHeartbeat()
    }

    fun loadData() {
        viewModelScope.launch {
            val (saved, blocked, logs) = withContext(Dispatchers.IO) {
                val s = repository.getAllContacts().map {
                    Contact(it.name, it.ip, true, it.savedCode, it.isPriority, it.fcmToken)
                }
                val b = repository.getBlockedContacts().map {
                    Contact(it.name, it.ip, true, it.savedCode, it.isPriority, it.fcmToken)
                }
                val l = repository.getAllLogs()
                Triple(s, b, l)
            }

            savedContacts.clear()
            savedContacts.addAll(saved)
            blockedContacts.clear()
            blockedContacts.addAll(blocked)
            _callLogs.value = logs
        }
    }

    fun deletePagerEntry(entry: PagerEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deletePagerEntry(entry)
        }
    }

    fun clearPagerHistory() {
        viewModelScope.launch(Dispatchers.IO) { repository.clearPagerHistory() }
    }

    fun sendTextPayload(service: VoiceService?, text: String) {
        if (text.isBlank()) return

        // Grab the IP (for Local Mode)
        val ip = getCurrentTargetIp()

        // The Username is just the current targetUser
        val username = targetUser

        if (ip != null && ip != "SERVER_LINK") {
            // [UPDATED] Pass both IP and Username to VoiceService
            service?.sendTextMessage(ip, username, text)

            if (text.startsWith("CMD:") || text.startsWith("LOC:")) return

            viewModelScope.launch(Dispatchers.IO) {
                repository.insertPagerEntry(
                    PagerEntry(sender = "Me", type = "TEXT", content = text, isRead = true)
                )
            }
        } else {
            _uiState.value = UiState.Error("Target Offline")
        }
    }

    fun triggerCurrentSos(service: VoiceService?) {
        val ip = getCurrentTargetIp()
        val username = targetUser

        if (ip != null && ip != "SERVER_LINK") {
            // [UPDATED] Pass both IP and Username
            service?.sendTextMessage(ip, username, "CMD:SOS")
        } else {
            service?.sendPanicAlert()
        }
    }

    fun playEntry(context: Context, entry: PagerEntry, service: VoiceService?) {
        if (entry.type == "AUDIO") {
            val file = File(entry.content)
            playAndBurnMessage(file)
        } else {
            service?.speakText(entry.content)
        }
    }

    private fun playAndBurnMessage(file: File) {
        try {
            mediaPlayer?.release()
            mediaPlayer = android.media.MediaPlayer().apply {
                setDataSource(file.path)
                prepare()
                start()
                setOnCompletionListener { mp ->
                    mp.release()
                    mediaPlayer = null
                }
            }
        } catch (e: Exception) { Log.e("WalkieViewModel", "Playback failed", e) }
    }

    fun setTarget(name: String) {
        repository.setTargetUser(name)
        val contact = savedContacts.find { it.name == name }
        val key = contact?.savedCode ?: ""
        repository.saveChannelKey(key)
        connectionStatus = ConnectionStatus.CHECKING
    }

    fun addContact(name: String, ip: String, code: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.saveContact(name, ip, code)
            loadData()
            withContext(Dispatchers.Main) { setTarget(name) }
        }
    }

    fun blockContact(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.setBlockedStatus(name, true)
            loadData()
            withContext(Dispatchers.Main) {
                if (targetUser == name) setTarget("")
            }
        }
    }

    fun unblockContact(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.setBlockedStatus(name, false)
            loadData()
        }
    }

    fun deleteContact(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteContact(name)
            loadData()
            withContext(Dispatchers.Main) {
                if (targetUser == name) setTarget("")
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.clearLogs()
            _callLogs.value = emptyList()
        }
    }

    fun startLocalDiscovery(context: Context) {
        if (localManager == null) {
            localManager = LocalLinkManager(context, { name, inetAddress, _ ->
                viewModelScope.launch {
                    val ip = inetAddress.hostAddress ?: ""
                    if (blockedContacts.none { it.name == name }) {
                        val existingIndex = nearbyUsers.indexOfFirst { it.name == name }
                        if (existingIndex != -1) {
                            if (nearbyUsers[existingIndex].ip != ip) nearbyUsers[existingIndex] = nearbyUsers[existingIndex].copy(ip = ip)
                        } else { nearbyUsers.add(Contact(name, ip, false)) }
                    }
                }
            }, { displayName -> viewModelScope.launch { nearbyUsers.removeAll { it.name == displayName } } })
        }
        localManager?.startDiscovery()
    }

    fun stopLocalDiscovery() { localManager?.stop(); localManager = null }

    fun saveInternetContact(name: String, code: String, onSuccess: () -> Unit, onError: () -> Unit) {
        _uiState.value = UiState.Error("Verifying...")

        viewModelScope.launch(Dispatchers.IO) {
            val token = repository.getToken() ?: ""
            if (token.isBlank() || token == "OFFLINE_TOKEN") {
                withContext(Dispatchers.Main) { _uiState.value = UiState.Error("Need Internet"); onError() }
                return@launch
            }
            try {
                if (name.startsWith("group:", true)) {
                    repository.saveContact(name, "SERVER_LINK", code)
                    repository.saveChannelKey(code)
                    repository.setTargetUser(name)
                    loadData()
                    withContext(Dispatchers.Main) {
                        _uiState.value = UiState.Connected(name)
                        onSuccess()
                    }
                } else {
                    val response = repository.findPeer(token, name, code)
                    if (response.ip != null) {
                        repository.saveContact(
                            name = name,
                            ip = response.ip,
                            code = code,
                            fcmToken = response.fcm_token ?: ""
                        )
                        repository.saveChannelKey(code)
                        repository.setTargetUser(name)
                        loadData()
                        withContext(Dispatchers.Main) {
                            _uiState.value = UiState.Connected(name)
                            onSuccess()
                        }
                    } else {
                        withContext(Dispatchers.Main) { _uiState.value = UiState.Error("Access Denied"); onError() }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { _uiState.value = UiState.Error("Link Error"); onError() }
            }
        }
    }

    fun startTransmission(onIpsFound: (List<String>, Int) -> Unit, onUpdateIps: (List<String>, Int) -> Unit) {
        if (_uiState.value is UiState.Transmitting || _uiState.value is UiState.Receiving) return
        _uiState.value = UiState.Transmitting("Connecting...", false)

        if (isBroadcastMode) {
            val allIps = nearbyUsers.filter { it.ip.isNotEmpty() }.map { it.ip }
            if (allIps.isNotEmpty()) {
                _uiState.value = UiState.Transmitting("Broadcasting (${allIps.size})", true)
                onIpsFound(allIps, 50005)
            } else { _uiState.value = UiState.Error("No Neighbors") }
            return
        }

        if (targetUser.isEmpty()) { _uiState.value = UiState.Error("No Target Selected"); return }

        // 1. Local Network Check
        val localUser = nearbyUsers.find { it.name.equals(targetUser, ignoreCase = true) }
        if (localUser != null) {
            _uiState.value = UiState.Transmitting("On Air (Local)", false)
            onIpsFound(listOf(localUser.ip), 50005)
            return
        }

        val contact = savedContacts.find { it.name.equals(targetUser, ignoreCase = true) }
        val token = repository.getToken() ?: ""
        if (token.isBlank() || token == "OFFLINE_TOKEN") { _uiState.value = UiState.Error("Offline"); return }

        // 2. Speculative Start
        var speculated = false
        if (contact != null && contact.ip.isNotEmpty() && contact.ip != "SERVER_LINK") {
            _uiState.value = UiState.Transmitting("On Air", false)
            onIpsFound(listOf(contact.ip), 50005)
            speculated = true
        }

        // 3. Server Refresh
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (targetUser.startsWith("group:", true)) {
                    val response = repository.findChannel(token, targetUser.substringAfter(":"), contact?.savedCode ?: "")
                    if (_uiState.value !is UiState.Transmitting) return@launch
                    val finalIps = response.users?.flatMap { listOfNotNull(it.public_ip, it.local_ip) }?.toSet()

                    withContext(Dispatchers.Main) {
                        if (!finalIps.isNullOrEmpty()) {
                            _uiState.value = UiState.Transmitting("On Air", false)
                            if (speculated) onUpdateIps(finalIps.toList(), 50005)
                            else onIpsFound(finalIps.toList(), 50005)
                        } else if (!speculated) {
                            _uiState.value = UiState.Error("Channel Empty")
                        }
                    }
                } else if (contact != null) {
                    val response = repository.findPeer(token, contact.name, contact.savedCode)
                    if (_uiState.value !is UiState.Transmitting) return@launch

                    val ipList = listOfNotNull(response.ip, response.local_ip).distinct()
                    val targetPort = response.port ?: 50005

                    withContext(Dispatchers.Main) {
                        if (ipList.isNotEmpty()) {
                            if (!speculated) {
                                _uiState.value = UiState.Transmitting("On Air", false)
                                onIpsFound(ipList, targetPort)
                            } else {
                                onUpdateIps(ipList, targetPort)
                            }
                        } else if (!speculated) {
                            _uiState.value = UiState.Error("User Offline")
                        }
                    }

                    if (response.ip != contact.ip) {
                        repository.saveContact(
                            name = contact.name,
                            ip = response.ip!!,
                            code = contact.savedCode,
                            isPriority = contact.isPriority,
                            fcmToken = response.fcm_token ?: ""
                        )
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (!speculated) _uiState.value = UiState.Error("Link Failed")
                }
            }
        }
    }

    fun stopTransmission(onStop: () -> Unit) {
        _uiState.value = if (targetUser.isNotEmpty()) UiState.Connected(targetUser) else UiState.Ready
        onStop()
    }

    fun hangUp(service: VoiceService?) {
        _uiState.value = if (targetUser.isNotEmpty()) UiState.Connected(targetUser) else UiState.Ready

        // [CLEANED] Removed package prefix to avoid compilation syntax errors
        val callState = CallSignaling.callStatus.value
        if (callState != CallStatus.Idle) {
            viewModelScope.launch {
                if (callState == CallStatus.Ringing) {
                    CallSignaling.declineCall()
                } else {
                    CallSignaling.endCall()
                }
            }
        }

        if (service != null) {
            val state = service.voiceServiceState.value

            if (state.isTransmitting) {
                service.stopTalk()
            } else {
                service.stopReceiving()

                if (state.incomingCall != null && callState == CallStatus.Idle) {
                    service.sendRemoteHangup()
                }
            }
        }
    }

    fun onReceptionStarted(from: String, ip: String) {
        if (_uiState.value !is UiState.Transmitting) _uiState.value = UiState.Receiving(from)
        if (ip.isNotEmpty() && !from.startsWith("group:", true)) {
            viewModelScope.launch(Dispatchers.IO) {
                val saved = savedContacts.find { it.name == from }
                if (saved != null && saved.ip != ip) {
                    repository.updateContactIp(from, ip)
                }
            }
        }
    }

    fun onReceptionEnded() {
        if (_uiState.value is UiState.Receiving) {
            _uiState.value = if (targetUser.isNotEmpty()) UiState.Connected(targetUser) else UiState.Ready
        }
    }

    fun toggleSilence(service: VoiceService?) {
        service?.let {
            val intent = android.content.Intent(service, VoiceService::class.java).apply { action = "TOGGLE_MUTE" }
            service.startService(intent)
        }
    }

    fun toggleBroadcastMode() {
        isBroadcastMode = !isBroadcastMode
        if (isBroadcastMode) setTarget("EVERYONE") else setTarget(repository.getTargetUser())
    }

    fun toggleSpeaker(service: VoiceService?) {
        service?.toggleSpeaker(!(service.voiceServiceState.value.isSpeakerOn))
    }

    fun generateQr(content: String): Bitmap? {
        try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, 512, 512)
            val bmp = createBitmap(bitMatrix.width, bitMatrix.height, Bitmap.Config.RGB_565)
            for (x in 0 until bitMatrix.width) {
                for (y in 0 until bitMatrix.height) { bmp[x, y] = if (bitMatrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE }
            }
            return bmp
        } catch (e: Exception) { Log.e("WalkieViewModel", "QR generation failed", e) }
        return null
    }

    fun generateChannelQr(channelName: String, passkey: String) {
        val pureName = channelName.removePrefix("group:")
        sharingChannelName = pureName
        channelQrBitmap = generateQr("CHANNEL:$pureName|$passkey")
    }

    fun resetPairingCode(myName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val token = repository.getToken() ?: ""
            if (token.isBlank() || token == "OFFLINE_TOKEN") return@launch
            try {
                val response = repository.resetCode(token)
                if (response.status == "success") {
                    response.new_code?.let { code ->
                        withContext(Dispatchers.Main) {
                            repository.saveMyPairingCode(code)
                            qrBitmap = generateQr("$myName|$code")
                        }
                    }
                }
            } catch (e: Exception) { Log.e("WalkieViewModel", "Pairing code reset failed", e) }
        }
    }

    fun handleIncomingPacket(text: String, ip: String): Boolean {
        // [CLEANED] Removed package prefixes here as well
        if (text.startsWith("CMD:CALL")) {
            viewModelScope.launch {
                CallSignaling.handleSignal(text, ip)
            }
            return true
        }
        if (text.startsWith("CMD:SOS") || text.startsWith("CMD:PANIC")) {
            SafetySignaling.triggerSOS(ip)
            return false
        }
        if (text.startsWith("LOC:")) {
            val coords = text.removePrefix("LOC:")
            SafetySignaling.triggerLocation(ip, coords)
            return false
        }
        return false
    }

    fun toggleStealth(service: VoiceService?) {
        service?.let {
            val intent = android.content.Intent(it, VoiceService::class.java).apply { action = "TOGGLE_STEALTH" }
            it.startService(intent)
        }
    }

    fun toggleVox(service: VoiceService?) {
        service?.let {
            val intent = android.content.Intent(it, VoiceService::class.java).apply { action = "TOGGLE_VOX" }
            it.startService(intent)
        }
    }

    fun performHealthCheck(context: Context, service: VoiceService?) =
        SystemDiagnostics.runChecks(context, service)

    fun sendCloudWakeUp(context: Context, contact: Contact) {
        if (contact.fcmToken.isBlank()) {
            Toast.makeText(context, "Cloud Wake unavailable: No token for ${contact.name}", Toast.LENGTH_SHORT).show()
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val jwt = repository.getToken()
                val myName = repository.myUsername.value

                if (jwt.isNullOrEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Session expired. Please log in.", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val authHeader = "Bearer $jwt"
                val response = repository.sendWakeSignal(
                    authHeader = authHeader,
                    senderName = myName,
                    targetToken = contact.fcmToken
                )

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) {
                        val body = response.body()
                        if (body?.status == "success") {
                            Toast.makeText(context, "Wake signal sent to ${contact.name}! ⚡", Toast.LENGTH_SHORT).show()
                        } else {
                            val errorDetail = body?.error ?: body?.message ?: "Wake request rejected"
                            Toast.makeText(context, "Failed: $errorDetail", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        Toast.makeText(context, "Server returned code ${response.code()}", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Connection failed. Check your internet.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun saveRecoveryEmail(context: Context, email: String, onSuccess: () -> Unit) {
        if (email.isNotEmpty() && !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            Toast.makeText(context, "Invalid Email Format", Toast.LENGTH_SHORT).show()
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val token = repository.getToken() ?: ""
                val response = RetrofitClient.api.updateRecoveryEmail("Bearer $token", email)

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful && response.body()?.status == "success") {
                        repository.setRecoveryEmail(email)
                        Toast.makeText(context, "Recovery Email Updated", Toast.LENGTH_SHORT).show()
                        onSuccess()
                    } else {
                        val error = response.body()?.message ?: "Failed to save"
                        Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Connection Error", Toast.LENGTH_SHORT).show()
                    e.printStackTrace()
                }
            }
        }
    }

    fun toggleEcoMode(context: Context, enabled: Boolean) {
        val intent = Intent(context, VoiceService::class.java).apply {
            action = "TOGGLE_ECO"
            putExtra("state", enabled)
        }
        context.startService(intent)
    }

    fun clearTarget() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.setTargetUser("")
        }
    }

    // [FIXED] Fully refactored to use standard class names without backticks
    fun startCall(context: Context) {
        if (targetUser.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ip = getCurrentTargetIp()

                if (ip.isNullOrEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Call Failed: User Offline or IP Missing", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    CallSignaling.startOutgoingCall(ip, targetUser)
                }

            } catch (e: Exception) {
                Log.e("WalkieVM", "Call Start Failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}