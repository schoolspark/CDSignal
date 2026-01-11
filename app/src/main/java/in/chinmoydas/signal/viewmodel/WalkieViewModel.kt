package `in`.chinmoydas.signal.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
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
import `in`.chinmoydas.signal.VoiceService
import `in`.chinmoydas.signal.data.CallLog
import `in`.chinmoydas.signal.data.MainRepository
import `in`.chinmoydas.signal.utils.LocalLinkManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import android.graphics.Color as AndroidColor

data class Contact(val name: String, var ip: String, val isTrusted: Boolean = false, val savedCode: String = "")

class WalkieViewModel(private val repository: MainRepository) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState>(UiState.Ready)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    val myPairingCode: StateFlow<String> = repository.myPairingCode

    var targetUser by mutableStateOf("")
        private set

    // REMOVED LOCAL STATE for isSilenced/isSpeakerEnabled -> Now Service controls this

    var qrBitmap by mutableStateOf<Bitmap?>(null)
    var channelQrBitmap by mutableStateOf<Bitmap?>(null)
    var sharingChannelName by mutableStateOf("")
    var isHandsFree by mutableStateOf(false)
    var isBroadcastMode by mutableStateOf(false)
        private set

    var nearbyUsers = mutableStateListOf<Contact>()
    var savedContacts = mutableStateListOf<Contact>()
    var blockedContacts = mutableStateListOf<Contact>()

    var recordedMessages = mutableStateListOf<File>()
    private var mediaPlayer: android.media.MediaPlayer? = null
    private val _callLogs = MutableStateFlow<List<CallLog>>(emptyList())
    val callLogs: StateFlow<List<CallLog>> = _callLogs.asStateFlow()
    private var localManager: LocalLinkManager? = null

    init {
        viewModelScope.launch {
            repository.targetUser.collect { newTarget ->
                targetUser = newTarget
                if (_uiState.value is UiState.Ready || _uiState.value is UiState.Connected) {
                    _uiState.value = if (newTarget.isEmpty()) UiState.Ready else UiState.Connected(newTarget)
                }
            }
        }
        loadData()
    }

    fun loadData() {
        viewModelScope.launch {
            savedContacts.clear()
            savedContacts.addAll(repository.getAllContacts().map { Contact(it.name, it.ip, true, it.savedCode) })
            blockedContacts.clear()
            blockedContacts.addAll(repository.getBlockedContacts().map { Contact(it.name, it.ip, true, it.savedCode) })
            _callLogs.value = repository.getAllLogs()
        }
    }

    fun loadRecordings(context: Context) {
        viewModelScope.launch {
            val dir = context.cacheDir
            val files = dir.listFiles { _, name -> name.endsWith(".wav") }
            recordedMessages.clear()
            files?.sortedByDescending { it.lastModified() }?.let { recordedMessages.addAll(it) }
        }
    }

    fun playAndBurnMessage(file: File) {
        try {
            mediaPlayer?.release()
            mediaPlayer = android.media.MediaPlayer().apply {
                setDataSource(file.path)
                prepare()
                start()
                setOnCompletionListener { mp ->
                    mp.release()
                    mediaPlayer = null
                    if (file.exists()) file.delete()
                    viewModelScope.launch { recordedMessages.remove(file) }
                    Log.d("WalkieViewModel", "Message played and deleted: ${file.name}")
                }
            }
        } catch (e: Exception) { Log.e("WalkieViewModel", "Playback failed", e) }
    }

    fun setTarget(name: String) {
        repository.setTargetUser(name)
        val contact = savedContacts.find { it.name == name }
        val key = contact?.savedCode ?: ""
        repository.saveChannelKey(key)
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
                        repository.saveContact(name, response.ip, code)
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

        val localUser = nearbyUsers.find { it.name.equals(targetUser, ignoreCase = true) }
        if (localUser != null) {
            _uiState.value = UiState.Transmitting("On Air (Local)", false)
            onIpsFound(listOf(localUser.ip), 50005)
            return
        }

        val contact = savedContacts.find { it.name.equals(targetUser, ignoreCase = true) }
        val token = repository.getToken() ?: ""
        if (token.isBlank() || token == "OFFLINE_TOKEN") { _uiState.value = UiState.Error("Offline"); return }

        if (targetUser.startsWith("group:", ignoreCase = true)) {
            val channelName = targetUser.substringAfter(":")
            val passkey = contact?.savedCode ?: ""
            viewModelScope.launch {
                try {
                    val response = repository.findChannel(token, channelName, passkey)
                    if (_uiState.value !is UiState.Transmitting) return@launch
                    val finalIps = response.users?.flatMap { listOfNotNull(it.public_ip, it.local_ip) }?.toSet()
                    if (!finalIps.isNullOrEmpty()) {
                        _uiState.value = UiState.Transmitting("On Air", false)
                        onIpsFound(finalIps.toList(), 50005)
                    } else { _uiState.value = UiState.Error("Channel Empty") }
                } catch (e: Exception) { _uiState.value = UiState.Error("Channel Error") }
            }
            return
        }

        if (contact != null) {
            var speculated = false
            if (contact.ip.isNotEmpty() && contact.ip != "SERVER_LINK") {
                _uiState.value = UiState.Transmitting("On Air", false)
                onIpsFound(listOf(contact.ip), 50005)
                speculated = true
            }
            if (contact.savedCode.isNotEmpty()) {
                viewModelScope.launch {
                    try {
                        val response = repository.findPeer(token, contact.name, contact.savedCode)
                        if (_uiState.value !is UiState.Transmitting) return@launch
                        val newIp = response.ip
                        val extraIp = response.local_ip
                        if (newIp != null) {
                            val ipList = listOfNotNull(newIp, extraIp).distinct()
                            if (!speculated) {
                                _uiState.value = UiState.Transmitting("On Air", false)
                                onIpsFound(ipList, 50005)
                            } else { onUpdateIps(ipList) }
                            if (newIp != contact.ip) repository.saveContact(contact.name, newIp, contact.savedCode)
                        } else { if (!speculated) _uiState.value = UiState.Error("User Offline") }
                    } catch (e: Exception) { if (!speculated) _uiState.value = UiState.Error("Link Failed") }
                }
            } else if (!speculated) { _uiState.value = UiState.Error("No IP Saved") }
            return
        }

        viewModelScope.launch {
            try {
                val response = repository.findPeer(token, targetUser, "0000")
                if (_uiState.value !is UiState.Transmitting) return@launch
                val ipList = listOfNotNull(response.ip, response.local_ip).distinct()
                if (ipList.isNotEmpty()) {
                    _uiState.value = UiState.Transmitting("On Air", false)
                    onIpsFound(ipList, response.port ?: 50005)
                } else { _uiState.value = UiState.Error("User Offline") }
            } catch (e: Exception) { if (_uiState.value !is UiState.Transmitting) return@launch; _uiState.value = UiState.Error("Link Failed") }
        }
    }

    fun stopTransmission(onStop: () -> Unit) {
        _uiState.value = if (targetUser.isNotEmpty()) UiState.Connected(targetUser) else UiState.Ready
        onStop()
    }

    fun hangUp(service: VoiceService?) {
        _uiState.value = if (targetUser.isNotEmpty()) UiState.Connected(targetUser) else UiState.Ready
        service?.stopReceiving()
        service?.stopTalk()
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
            }
        }
    }

    fun onReceptionEnded() {
        if (_uiState.value is UiState.Receiving) {
            _uiState.value = if (targetUser.isNotEmpty()) UiState.Connected(targetUser) else UiState.Ready
        }
    }

    // --- SERVICE TRIGGER FUNCTIONS ---
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
    // ---------------------------------

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
}