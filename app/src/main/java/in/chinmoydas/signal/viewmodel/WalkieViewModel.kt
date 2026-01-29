package `in`.chinmoydas.signal.viewmodel

import android.content.Context
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive // [FIX] Added import for isActive
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
    private var pingJob: Job? = null

    // [MISSION CRITICAL] PTT Job Management
    // Prevents "Sticky Mic" race condition
    private var transmissionJob: Job? = null

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

    // [FIX] Added Helper to get IP for Calls (Fixes MainActivity error)
    fun getCurrentTargetIp(): String? {
        if (targetUser.isEmpty()) return null

        // 1. Check Saved Contacts
        val saved = savedContacts.find { it.name == targetUser }
        if (saved != null) return saved.ip

        // 2. Check Nearby/Local Users
        val nearby = nearbyUsers.find { it.name == targetUser }
        return nearby?.ip
    }

    // Toggle Priority Logic
    fun togglePriority(name: String) {
        val contact = savedContacts.find { it.name == name } ?: return
        viewModelScope.launch {
            repository.setContactPriority(name, !contact.isPriority)
            loadData()
        }
    }

    fun observeServicePing(service: VoiceService?) {
        viewModelScope.launch {
            service?.voiceServiceState?.collect { state ->
                // If service says "Listening", we are connected to the network
                if (state.networkStatus.contains("Listening")) {
                    connectionStatus = ConnectionStatus.READY
                } else if (state.networkStatus.contains("Waiting")) {
                    connectionStatus = ConnectionStatus.OFFLINE
                }

                // Fallback: Check ping response time
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

        // [FIX] Use Extension function to send Heartbeat via Intent
        service?.triggerHeartbeat()
    }

    fun loadData() {
        viewModelScope.launch {
            savedContacts.clear()
            savedContacts.addAll(repository.getAllContacts().map {
                Contact(it.name, it.ip, true, it.savedCode, it.isPriority, it.fcmToken)
            })

            blockedContacts.clear()
            blockedContacts.addAll(repository.getBlockedContacts().map {
                Contact(it.name, it.ip, true, it.savedCode, it.isPriority, it.fcmToken)
            })

            _callLogs.value = repository.getAllLogs()
        }
    }

    fun deletePagerEntry(entry: PagerEntry) {
        viewModelScope.launch(Dispatchers.IO) { // [FIX] IO Dispatcher
            repository.deletePagerEntry(entry)
        }
    }

    fun clearPagerHistory() {
        viewModelScope.launch(Dispatchers.IO) { repository.clearPagerHistory() }
    }

    fun sendTextPayload(service: VoiceService?, text: String) {
        if (text.isBlank()) return

        val ip = getCurrentTargetIp()

        if (ip != null && ip != "SERVER_LINK") {
            service?.sendTextMessage(ip, text)

            // [FIX] Filter out System Commands from Chat History
            // We don't want "CMD:REMOTE_MIC_ON" cluttering the UI
            if (text.startsWith("CMD:") || text.startsWith("LOC:")) {
                return
            }

            // Only save actual human messages
            viewModelScope.launch {
                repository.insertPagerEntry(
                    PagerEntry(sender = "Me", type = "TEXT", content = text, isRead = true)
                )
            }
        } else {
            _uiState.value = UiState.Error("Target Offline")
        }
    }

    // [FIX] Dynamic SOS Trigger (Always targets the CURRENT user)
    // This fixes the issue where the button was stuck on the previous user's IP
    fun triggerCurrentSos(service: VoiceService?) {
        val ip = getCurrentTargetIp()

        if (ip != null && ip != "SERVER_LINK") {
            // Target specific user
            service?.sendTextMessage(ip, "CMD:SOS")
        } else {
            // If no specific target, use the Service's "Broadcast Mode"
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
        viewModelScope.launch { repository.saveContact(name, ip, code); loadData(); setTarget(name) }
    }

    fun blockContact(name: String) {
        viewModelScope.launch { repository.setBlockedStatus(name, true); loadData(); if (targetUser == name) setTarget("") }
    }

    fun unblockContact(name: String) {
        viewModelScope.launch { repository.setBlockedStatus(name, false); loadData() }
    }

    fun deleteContact(name: String) {
        viewModelScope.launch { repository.deleteContact(name); loadData(); if (targetUser == name) setTarget("") }
    }

    fun clearHistory() {
        viewModelScope.launch { repository.clearLogs(); _callLogs.value = emptyList() }
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
        viewModelScope.launch {
            val token = repository.getToken() ?: ""
            if (token.isBlank() || token == "OFFLINE_TOKEN") { _uiState.value = UiState.Error("Need Internet"); onError(); return@launch }
            try {
                if (name.startsWith("group:", true)) {
                    repository.saveContact(name, "SERVER_LINK", code)
                    repository.saveChannelKey(code)
                    repository.setTargetUser(name)
                    loadData()
                    _uiState.value = UiState.Connected(name)
                    onSuccess()
                } else {
                    val response = repository.findPeer(token, name, code)
                    if (response.ip != null) {
                        repository.saveContact(
                            name = name,
                            ip = response.ip,
                            code = code,
                            fcmToken = response.fcm_token ?: "" // Token is now explicitly saved
                        )
                        repository.saveChannelKey(code)
                        repository.setTargetUser(name)
                        loadData()
                        _uiState.value = UiState.Connected(name)
                        onSuccess()
                    } else { _uiState.value = UiState.Error("Access Denied"); onError() }
                }
            } catch (e: Exception) { _uiState.value = UiState.Error("Link Error"); onError() }
        }
    }

    fun startTransmission(onIpsFound: (List<String>, Int) -> Unit, onUpdateIps: (List<String>) -> Unit) {
        // 1. Safety Checks
        if (_uiState.value is UiState.Transmitting || _uiState.value is UiState.Receiving) return
        _uiState.value = UiState.Transmitting("Connecting...", false)

        // 2. BROADCAST MODE (Always 50005 because it is Local LAN)
        if (isBroadcastMode) {
            val allIps = nearbyUsers.filter { it.ip.isNotEmpty() }.map { it.ip }
            if (allIps.isNotEmpty()) {
                _uiState.value = UiState.Transmitting("Broadcasting (${allIps.size})", true)
                onIpsFound(allIps, 50005)
            } else { _uiState.value = UiState.Error("No Neighbors") }
            return
        }

        if (targetUser.isEmpty()) { _uiState.value = UiState.Error("No Target Selected"); return }

        // 3. LOCAL DISCOVERY USER (Always 50005 because it is Local LAN)
        val localUser = nearbyUsers.find { it.name.equals(targetUser, ignoreCase = true) }
        if (localUser != null) {
            _uiState.value = UiState.Transmitting("On Air (Local)", false)
            onIpsFound(listOf(localUser.ip), 50005)
            return
        }

        // --- INTERNET / WAN LOGIC STARTS HERE ---

        val contact = savedContacts.find { it.name.equals(targetUser, ignoreCase = true) }
        val token = repository.getToken() ?: ""
        if (token.isBlank() || token == "OFFLINE_TOKEN") { _uiState.value = UiState.Error("Offline"); return }

        // 4. GROUPS (Server-Assisted)
        if (targetUser.startsWith("group:", ignoreCase = true)) {
            val channelName = targetUser.substringAfter(":")
            val passkey = contact?.savedCode ?: ""
            viewModelScope.launch {
                try {
                    val response = repository.findChannel(token, channelName, passkey)
                    if (_uiState.value !is UiState.Transmitting) return@launch

                    // Flatten IPs from the server response
                    val finalIps = response.users?.flatMap { listOfNotNull(it.public_ip, it.local_ip) }?.toSet()

                    if (!finalIps.isNullOrEmpty()) {
                        _uiState.value = UiState.Transmitting("On Air", false)
                        // [NOTE] Groups currently use 50005. If you upgrade channel.php to send ports, update this.
                        onIpsFound(finalIps.toList(), 50005)
                    } else { _uiState.value = UiState.Error("Channel Empty") }
                } catch (e: Exception) { _uiState.value = UiState.Error("Channel Error") }
            }
            return
        }

        // 5. SAVED CONTACTS (P2P via Internet) - [CRITICAL NAT FIX APPLIED]
        if (contact != null) {
            var speculated = false

            // A. Speculative Execution: Try last known IP/Port immediately for speed
            // We default to 50005 here because we don't store ports in DB yet.
            if (contact.ip.isNotEmpty() && contact.ip != "SERVER_LINK") {
                _uiState.value = UiState.Transmitting("On Air", false)
                onIpsFound(listOf(contact.ip), 50005)
                speculated = true
            }

            // B. Refresh from Server to get the REAL IP and PORT (STUN)
            if (contact.savedCode.isNotEmpty()) {
                viewModelScope.launch {
                    try {
                        val response = repository.findPeer(token, contact.name, contact.savedCode)
                        if (_uiState.value !is UiState.Transmitting) return@launch

                        val newIp = response.ip
                        val extraIp = response.local_ip
                        val newToken = response.fcm_token ?: ""
                        // [THE FIX] Capture the dynamic port (e.g., 24912) from the server
                        val targetPort = response.port ?: 50005

                        if (newIp != null) {
                            val ipList = listOfNotNull(newIp, extraIp).distinct()

                            if (!speculated) {
                                // First attempt: Start with the correct port
                                _uiState.value = UiState.Transmitting("On Air", false)
                                onIpsFound(ipList, targetPort)
                            } else {
                                // We already started on 50005. If the IP changed, update the list.
                                // Note: Changing port mid-stream is complex, so we prioritize IP updates here.
                                onUpdateIps(ipList)
                            }

                            // Save the new IP to local DB for next time
                            if (newIp != contact.ip || newToken != contact.fcmToken) {
                                repository.saveContact(
                                    name = contact.name,
                                    ip = newIp,
                                    code = contact.savedCode,
                                    fcmToken = newToken
                                )
                            }
                        } else {
                            if (!speculated) _uiState.value = UiState.Error("User Offline")
                        }
                    } catch (e: Exception) {
                        if (!speculated) _uiState.value = UiState.Error("Link Failed")
                    }
                }
            } else if (!speculated) { _uiState.value = UiState.Error("No IP Saved") }
            return
        }

        // 6. UNSAVED SEARCH (P2P via Internet)
        viewModelScope.launch {
            try {
                // Use default public code "0000" or handle appropriately
                val response = repository.findPeer(token, targetUser, "0000")
                if (_uiState.value !is UiState.Transmitting) return@launch

                val ipList = listOfNotNull(response.ip, response.local_ip).distinct()

                // [THE FIX] Use the dynamic port
                val targetPort = response.port ?: 50005

                if (ipList.isNotEmpty()) {
                    _uiState.value = UiState.Transmitting("On Air", false)
                    onIpsFound(ipList, targetPort)
                } else { _uiState.value = UiState.Error("User Offline") }
            } catch (e: Exception) {
                if (_uiState.value !is UiState.Transmitting) return@launch;
                _uiState.value = UiState.Error("Link Failed")
            }
        }
    }

    fun stopTransmission(onStop: () -> Unit) {
        _uiState.value = if (targetUser.isNotEmpty()) UiState.Connected(targetUser) else UiState.Ready
        onStop()
    }

    fun hangUp(service: VoiceService?) {
        _uiState.value = if (targetUser.isNotEmpty()) UiState.Connected(targetUser) else UiState.Ready

        if (service == null) return

        val state = service.voiceServiceState.value

        if (state.isTransmitting) {
            service.stopTalk()
        } else if (state.incomingCall != null) {
            service.sendRemoteHangup()
        } else {
            service.stopReceiving()
        }
    }

    fun onReceptionStarted(from: String, ip: String) {
        if (_uiState.value !is UiState.Transmitting) {
            _uiState.value = UiState.Receiving(from)
        }
        if (ip.isNotEmpty() && !from.startsWith("group:", true)) {
            viewModelScope.launch {
                val existing = nearbyUsers.find { it.name == from }
                if (existing != null) {
                    if (existing.ip != ip) {
                        val index = nearbyUsers.indexOf(existing)
                        if (index != -1) nearbyUsers[index] = existing.copy(ip = ip)
                    }
                } else { nearbyUsers.add(Contact(from, ip, false)) }

                val saved = savedContacts.find { it.name == from }
                if (saved != null && saved.ip != ip) {
                    repository.saveContact(from, ip, saved.savedCode)
                    val idx = savedContacts.indexOf(saved)
                    if (idx != -1) savedContacts[idx] = saved.copy(ip = ip)
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
            val intent = android.content.Intent(service, VoiceService::class.java)
            intent.action = "TOGGLE_MUTE"
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
        viewModelScope.launch {
            val token = repository.getToken() ?: ""
            if (token.isBlank() || token == "OFFLINE_TOKEN") return@launch
            try {
                val response = repository.resetCode(token)
                if (response.status == "success") {
                    response.new_code?.let { code ->
                        repository.saveMyPairingCode(code)
                        qrBitmap = generateQr("$myName|$code")
                    }
                }
            } catch (e: Exception) { Log.e("WalkieViewModel", "Pairing code reset failed", e) }
        }
    }

    // [NEW] 2. Link the Receiver
    fun handleIncomingPacket(text: String, ip: String): Boolean {
        // 1. Calls
        if (text.startsWith("CMD:CALL")) {
            viewModelScope.launch {
                `in`.chinmoydas.signal.utils.CallSignaling.handleSignal(text, ip)
            }
            return true
        }

        // 2. SOS / Panic
        if (text.startsWith("CMD:SOS") || text.startsWith("CMD:PANIC")) {
            `in`.chinmoydas.signal.utils.SafetySignaling.triggerSOS(ip)
            // [FIX] Return FALSE so this packet falls through and gets saved
            // to the History/Pager database as a text message.
            return false
        }

        // 3. Location
        if (text.startsWith("LOC:")) {
            val coords = text.removePrefix("LOC:")
            `in`.chinmoydas.signal.utils.SafetySignaling.triggerLocation(ip, coords)
            return false // Keep false to save to history
        }

        return false
    }
    fun toggleStealth(service: VoiceService?) {
        service?.let {
            val intent = android.content.Intent(it, VoiceService::class.java)
            intent.action = "TOGGLE_STEALTH"
            it.startService(intent)
        }
    }

    fun toggleVox(service: VoiceService?) {
        service?.let {
            val intent = android.content.Intent(it, VoiceService::class.java)
            intent.action = "TOGGLE_VOX"
            it.startService(intent)
        }
    }

    fun performHealthCheck(context: Context, service: VoiceService?) =
        `in`.chinmoydas.signal.utils.SystemDiagnostics.runChecks(context, service)

    /**
     * Triggers a high-priority FCM wake signal via the PHP backend.
     * Gracefully handles JWT authentication and UI feedback.
     */
    fun sendCloudWakeUp(context: Context, contact: Contact) {
        // 1. PRE-FLIGHT CHECK: Verify we have a token to send to.
        if (contact.fcmToken.isBlank()) {
            android.widget.Toast.makeText(context, "Cloud Wake unavailable: No token for ${contact.name}", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 2. AUTHENTICATION: Fetch the 1-year JWT token from storage.
                val jwt = repository.getToken()
                val myName = repository.myUsername.value // Use the current state-flow value.

                if (jwt.isNullOrEmpty()) {
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(context, "Session expired. Please log in.", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                // 3. NETWORK CALL: Execute the POST request to fcm_wake.php.
                val authHeader = "Bearer $jwt"
                val response = repository.sendWakeSignal(
                    authHeader = authHeader,
                    senderName = myName,
                    targetToken = contact.fcmToken
                )

                // 4. UI DISPATCH: Handle the server response on the Main thread.
                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) {
                        val body = response.body()
                        if (body?.status == "success") {
                            // Success: Signal reached your PHP script and was sent to FCM.
                            android.widget.Toast.makeText(context, "Wake signal sent to ${contact.name}! ⚡", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            // Logic Error: Handled by your PHP script (e.g., Google Auth failed).
                            val errorDetail = body?.error ?: body?.message ?: "Wake request rejected"
                            android.widget.Toast.makeText(context, "Failed: $errorDetail", android.widget.Toast.LENGTH_LONG).show()
                        }
                    } else {
                        // HTTP Error: 401 Unauthorized, 404 Not Found, or 500 Server Error.
                        val errorMsg = when (response.code()) {
                            401 -> "Unauthorized: Please log in again."
                            500 -> "Server Error: Check PHP logs."
                            else -> "Server returned code ${response.code()}"
                        }
                        android.widget.Toast.makeText(context, errorMsg, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                // 5. EXCEPTION HANDLING: Handle timeouts or lost internet connection.
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Connection failed. Check your internet.", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    // [FIXED] Now saves to local storage so it persists after restart
    fun saveRecoveryEmail(context: Context, email: String, onSuccess: () -> Unit) {

        // 1. Validation Logic
        if (email.isNotEmpty() && !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            Toast.makeText(context, "Invalid Email Format", Toast.LENGTH_SHORT).show()
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 2. Call Server
                val token = repository.getToken() ?: ""
                val response = RetrofitClient.api.updateRecoveryEmail("Bearer $token", email)

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful && response.body()?.status == "success") {

                        // [CRITICAL FIX] Save to Local Storage immediately!
                        // This updates the SharedPreferences so the UI sees it next time.
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
}