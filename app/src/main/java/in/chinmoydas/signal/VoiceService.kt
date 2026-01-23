package `in`.chinmoydas.signal

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.*
import android.speech.tts.TextToSpeech
import android.support.v4.media.session.MediaSessionCompat
import android.view.KeyEvent
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.media.session.MediaButtonReceiver
import `in`.chinmoydas.signal.data.AppDatabase
import `in`.chinmoydas.signal.data.PagerEntry
import `in`.chinmoydas.signal.data.MainRepository
import `in`.chinmoydas.signal.utils.AudioEngine
import `in`.chinmoydas.signal.utils.CryptoEngine
import `in`.chinmoydas.signal.utils.G711
import `in`.chinmoydas.signal.utils.LocalLinkManager
import `in`.chinmoydas.signal.utils.NetworkEngine
import `in`.chinmoydas.signal.RetrofitClient
import `in`.chinmoydas.signal.utils.StunClient
import `in`.chinmoydas.signal.utils.WavUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class VoiceServiceState(
    val incomingCall: String? = null,
    val incomingIp: String? = null,
    val networkStatus: String = "Listening...",
    val isSilenced: Boolean = false,
    val isSpeakerOn: Boolean = true,
    val lastPingResponse: Long = 0L,
    val isHeadsetLinked: Boolean = true,
    val isTransmitting: Boolean = false
)

class VoiceService : Service(), TextToSpeech.OnInitListener {
    private val tag = "VoiceService"
    private val binder = LocalBinder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _voiceServiceState = MutableStateFlow(VoiceServiceState())
    val voiceServiceState = _voiceServiceState.asStateFlow()

    private lateinit var audioEngine: AudioEngine
    private lateinit var networkEngine: NetworkEngine
    private lateinit var repository: MainRepository

    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    private lateinit var mediaSession: MediaSessionCompat
    private var isHeadsetLinked = true

    private val END_STREAM_SIGNAL = "__END_TX__"
    private val PING_SIGNAL = "__PING__"
    private val PONG_SIGNAL = "__PONG__"

    private val UDP_PORT = 50005
    @Volatile private var myPublicPort: Int = UDP_PORT

    @Volatile private var isOnWifi = false
    @Volatile private var dataSaverEnabled = false

    @Volatile private var activeKeySpec: javax.crypto.spec.SecretKeySpec? = null
    @Volatile private var activeKeySource: String? = null

    private val IGNORE_SENDER_DELAY = 5000L

    @Volatile private var isReceiving = false
    @Volatile private var isSending = false
    private var lastReceiveTime = 0L
    private var resetJob: Job? = null

    @Volatile var lastIncomingIp: String? = null
    @Volatile private var myUsername: String = "User"
    @Volatile private var myLocalIp: String = ""

    @Volatile private var currentChannel: String? = null
    @Volatile private var currentChannelKey: String? = null

    @Volatile private var targetContactKey: String? = null
    @Volatile private var myIdentityKey: String? = null

    @Volatile private var currentSpeakerName: String? = null

    var isSilenced: Boolean = false
        set(value) {
            field = value
            updateState()
            updateNotification(if (value) "Silent Mode Active" else "Listening...", null)
        }

    private var userPrefersSpeaker = true

    private fun updateState() {
        _voiceServiceState.update {
            it.copy(
                isSilenced = isSilenced,
                isSpeakerOn = userPrefersSpeaker,
                isHeadsetLinked = isHeadsetLinked,
                isTransmitting = isSending
            )
        }
    }

    private val sequenceMap = ConcurrentHashMap<String, Int>()
    private val activeIpCache = ConcurrentHashMap<String, String>()
    private val blockedCache = ConcurrentHashMap.newKeySet<String>()

    // VIP Cache for Principal Override
    private val principalCache = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var ignoredSender: String? = null
    @Volatile private var activeTargets: List<String> = emptyList()
    @Volatile private var lastPort: Int = UDP_PORT

    private val incomingBuffer = java.io.ByteArrayOutputStream(512 * 1024)
    private val bufferLock = Any()
    var isRecordingEnabled = true

    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    @Suppress("DEPRECATION")
    private val vibrator by lazy { getSystemService(Context.VIBRATOR_SERVICE) as Vibrator }
    private var activeFocusRequest: AudioFocusRequest? = null
    private val wakeLock by lazy { (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CDSignal:VoiceLock") }

    private val wifiLock by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "CDSignal:HighPerfLock")
        } else {
            @Suppress("DEPRECATION")
            (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "CDSignal:HighPerfLock")
        }
    }
    private val multicastLock by lazy { (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).createMulticastLock("CDSignal:MulticastLock") }
    private val activeCalls = AtomicInteger(0)
    private var heartbeatJob: Job? = null
    private var signalingJob: Job? = null
    private var currentSequenceNumber = 0
    private var localLinkManager: LocalLinkManager? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            super.onCapabilitiesChanged(network, networkCapabilities)
            val isWifiNow = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            if (isOnWifi != isWifiNow) isOnWifi = isWifiNow
        }

        override fun onAvailable(network: Network) {
            myLocalIp = getLocalIpAddress()
            triggerHeartbeat()
            localLinkManager?.startAdvertising(myUsername, UDP_PORT)
        }
    }

    inner class LocalBinder : Binder() { fun getService(): VoiceService = this@VoiceService }
    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        audioEngine = AudioEngine(this)
        networkEngine = NetworkEngine(UDP_PORT)
        repository = MainRepository(applicationContext)
        observeRepositoryFlows()

        val prefs = getSharedPreferences("WalkiePrefs", Context.MODE_PRIVATE)
        dataSaverEnabled = prefs.getBoolean("data_saver", false)

        initMediaSession()
        tts = TextToSpeech(this, this)

        multicastLock.setReferenceCounted(false)
        wakeLock.setReferenceCounted(false)
        wifiLock.setReferenceCounted(false)
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), networkCallback)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            isTtsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    fun speakText(text: String) {
        if (isTtsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    private fun initMediaSession() {
        val componentName = android.content.ComponentName(this, MediaButtonReceiver::class.java)
        mediaSession = MediaSessionCompat(this, "VoiceServiceMediaSession", componentName, null)
        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)

        mediaSession.setCallback(object : MediaSessionCompat.Callback() {
            override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                val keyEvent = mediaButtonEvent?.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                if (keyEvent?.action == KeyEvent.ACTION_DOWN) {
                    when (keyEvent.keyCode) {
                        KeyEvent.KEYCODE_HEADSETHOOK,
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                        KeyEvent.KEYCODE_MEDIA_PLAY,
                        KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                            toggleTalk()
                            return true
                        }
                    }
                }
                return super.onMediaButtonEvent(mediaButtonEvent)
            }
        })
        isHeadsetLinked = true
        mediaSession.isActive = true
    }

    private fun toggleTalk() {
        if (isSending) {
            stopTalk()
            vibrate()
        } else {
            scope.launch {
                val target = repository.getTargetUser()
                if (target.isBlank()) return@launch

                var ip = activeIpCache[target]
                if (ip == null) {
                    val contact = repository.getAllContacts().find { it.name == target }
                    ip = contact?.ip
                }

                if (ip == "SERVER_LINK") ip = "signal.chinmoydas.in"

                if (!ip.isNullOrBlank()) {
                    if (ActivityCompat.checkSelfPermission(this@VoiceService, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        vibrate()
                        startTalk(listOf(ip), UDP_PORT)
                    }
                } else {
                    if (ActivityCompat.checkSelfPermission(this@VoiceService, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        vibrate()
                        startTalk(if(ip != null) listOf(ip) else emptyList(), UDP_PORT)
                    }
                }
            }
        }
    }

    private fun observeRepositoryFlows() {
        scope.launch { repository.myUsername.collect { myUsername = it; localLinkManager?.startAdvertising(it, UDP_PORT) } }
        scope.launch { repository.myPairingCode.collect { myIdentityKey = it } }

        scope.launch {
            repository.targetUser.collect { target ->
                if (target.startsWith("group:", ignoreCase = true)) {
                    val raw = target.substringAfter(":")
                    if (raw.contains(":")) {
                        val parts = raw.split(":", limit = 2)
                        currentChannel = parts[0]
                    } else { currentChannel = raw }
                } else {
                    currentChannel = null
                    val savedContact = repository.getAllContacts().find { it.name == target }
                    if (savedContact != null) targetContactKey = savedContact.savedCode else targetContactKey = null
                }
                triggerHeartbeat()
                val ip = activeIpCache[target] ?: repository.getAllContacts().find { it.name == target }?.ip
                if (!ip.isNullOrBlank() && ip != "SERVER_LINK") sendPing(ip)
            }
        }
        scope.launch {
            repository.configTrigger.collect {
                val blocked = repository.getBlockedContacts()
                blockedCache.clear()
                blockedCache.addAll(blocked.map { it.name })

                // VIP Cache refresh
                val principals = repository.getPrincipalContacts()
                principalCache.clear()
                principalCache.addAll(principals.map { it.name })
            }
        }
        scope.launch {
            repository.channelKey.collect { key ->
                currentChannelKey = key
                if (currentChannel != null) targetContactKey = key
            }
        }
    }

    private fun getEncryptionKey(): javax.crypto.spec.SecretKeySpec? {
        val keyToUse = targetContactKey
        if (keyToUse.isNullOrBlank()) return null
        if (keyToUse == activeKeySource && activeKeySpec != null) return activeKeySpec
        activeKeySource = keyToUse
        activeKeySpec = CryptoEngine.deriveKey(keyToUse)
        return activeKeySpec
    }

    private fun getDecryptionKey(isGroupPacket: Boolean): javax.crypto.spec.SecretKeySpec? {
        val keyToUse = if (isGroupPacket) targetContactKey else myIdentityKey
        if (keyToUse.isNullOrBlank()) return null
        return CryptoEngine.deriveKey(keyToUse)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaButtonReceiver.handleIntent(mediaSession, intent)
        val action = intent?.action

        if (action == "STOP_SERVICE") {
            sendBroadcast(Intent("in.chinmoydas.signal.ACTION_EXIT").setPackage(packageName))
            stopSelf()
            return START_NOT_STICKY
        }
        if (action == "TOGGLE_MUTE") {
            isSilenced = !isSilenced
            if (activeCalls.get() > 0) {
                if (isSilenced) {
                    audioManager.mode = AudioManager.MODE_NORMAL
                    audioManager.isSpeakerphoneOn = false
                    abandonFocus()
                } else {
                    acquireResources()
                }
            }
            return START_STICKY
        }
        if (action == "TOGGLE_HEADSET") {
            isHeadsetLinked = !isHeadsetLinked
            mediaSession.isActive = isHeadsetLinked
            updateState()
            updateNotification(if (isHeadsetLinked) "Keys Attached" else "Keys Detached", null)
            return START_STICKY
        }

        createNotificationChannel()
        try { startForegroundServiceNotification("Initializing...") } catch (e: Exception) { stopSelf(); return START_NOT_STICKY }

        scope.launch(Dispatchers.IO) {
            if (!multicastLock.isHeld) multicastLock.acquire()
            audioEngine.startPlayback()
            val networkStarted = networkEngine.start { packet ->
                if (StunClient.isStunResponse(packet.data)) {
                    val result = StunClient.parseResponse(packet.data)
                    if (result != null && myPublicPort != result.publicPort) {
                        myPublicPort = result.publicPort
                        triggerHeartbeat()
                    }
                    return@start
                }
                handleIncomingPacket(packet.data, packet.length, packet.address?.hostAddress ?: "")
            }
            if (!networkStarted) {
                _voiceServiceState.update { it.copy(networkStatus = "Error: Network failed") }
                stopSelf()
                return@launch
            }
            updateNotification("Listening...", null)
            startHeartbeatLoop()
            startSignalLoop()
        }

        try {
            if (localLinkManager == null) localLinkManager = LocalLinkManager(this, { _, _, _ -> }, { _ -> })
            localLinkManager?.startAdvertising(myUsername, UDP_PORT)
        } catch (e: Exception) { }

        return START_STICKY
    }

    private fun startSignalLoop() {
        signalingJob?.cancel()
        signalingJob = scope.launch {
            while (isActive) {
                val token = repository.getToken()
                if (!token.isNullOrBlank() && token != "OFFLINE_TOKEN") {
                    try {
                        val response = RetrofitClient.api.checkSignals("Bearer $token")
                        val smartSignals = response.signals
                        if (!smartSignals.isNullOrEmpty()) {
                            smartSignals.forEach { signal ->
                                val ip = signal.public_ip
                                val port = signal.public_port ?: 50005
                                if (!ip.isNullOrBlank()) {
                                    activeIpCache[signal.sender] = ip
                                    repository.updateContactIp(signal.sender, ip)
                                    sendPing(ip, port)
                                }
                            }
                        } else if (!response.callers.isNullOrEmpty()) {
                            val contacts = repository.getAllContacts()
                            response.callers.forEach { caller ->
                                val ip = activeIpCache[caller] ?: contacts.find { it.name == caller }?.ip
                                if (!ip.isNullOrBlank() && ip != "SERVER_LINK") sendPing(ip)
                            }
                        }
                    } catch (e: Exception) { }
                }
                delay(2000)
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val restartServiceIntent = Intent(applicationContext, VoiceService::class.java).also { it.setPackage(packageName) }
        val restartServicePendingIntent = PendingIntent.getService(this, 1, restartServiceIntent, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
        val alarmService = applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmService.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 1000, restartServicePendingIntent)
        super.onTaskRemoved(rootIntent)
    }

    fun sendPing(ip: String, port: Int = UDP_PORT) {
        scope.launch {
            val signalBytes = PING_SIGNAL.toByteArray()
            val buf = ByteArray(1 + signalBytes.size + 4)
            buf[0] = signalBytes.size.toByte()
            System.arraycopy(signalBytes, 0, buf, 1, signalBytes.size)
            repeat(3) { networkEngine.send(buf, listOf(ip), port); delay(20) }
        }
    }

    private fun sendPong(ip: String) {
        scope.launch {
            val signalBytes = PONG_SIGNAL.toByteArray()
            val buf = ByteArray(1 + signalBytes.size + 4)
            buf[0] = signalBytes.size.toByte()
            System.arraycopy(signalBytes, 0, buf, 1, signalBytes.size)
            networkEngine.send(buf, listOf(ip), UDP_PORT)
        }
    }

    fun sendTextMessage(targetIp: String, message: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val rawPayload = "TXT:$message".toByteArray(Charsets.UTF_8)
                val seqNum = (System.currentTimeMillis() % 1000000).toInt()
                val prefs = getSharedPreferences("WalkiePrefs", Context.MODE_PRIVATE)
                val isSecureMode = prefs.getBoolean("secure_mode", false)
                val finalPayload = if (isSecureMode) {
                    val contact = repository.findContactByIp(targetIp)
                    val secretString = contact?.savedCode ?: "0000"
                    val secretKeySpec = CryptoEngine.deriveKey(secretString)
                    CryptoEngine.encrypt(rawPayload, seqNum, secretKeySpec) ?: rawPayload
                } else { rawPayload }

                val nameBytes = myUsername.toByteArray(Charsets.UTF_8)
                val packetSize = 1 + nameBytes.size + 4 + finalPayload.size
                val buffer = ByteBuffer.allocate(packetSize)
                buffer.put(nameBytes.size.toByte())
                buffer.put(nameBytes)
                buffer.putInt(seqNum)
                buffer.put(finalPayload)
                val packetData = buffer.array()

                val socket = DatagramSocket()
                val address = InetAddress.getByName(targetIp)
                val packet = DatagramPacket(packetData, packetData.size, address, 50005)
                repeat(3) { socket.send(packet); delay(50) }
                socket.close()
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun handleIncomingPacket(data: ByteArray, length: Int, senderIp: String) {
        if (isSending || senderIp == myLocalIp || length <= 5) return
        val nameLen = data[0].toInt() and 0xFF
        if (nameLen + 5 > length) return
        val senderName = String(data, 1, nameLen)

        if (senderName == PING_SIGNAL) { sendPong(senderIp); return }
        if (senderName == PONG_SIGNAL) { _voiceServiceState.update { it.copy(lastPingResponse = System.currentTimeMillis()) }; return }

        if (senderName == END_STREAM_SIGNAL) {
            if (isReceiving) {
                isReceiving = false
                if (isRecordingEnabled) {
                    val actualName = currentSpeakerName ?: "Unknown"
                    val dataToSave = synchronized(bufferLock) { val bytes = incomingBuffer.toByteArray(); incomingBuffer.reset(); bytes }
                    if (dataToSave.isNotEmpty()) saveIncomingMessage(actualName, dataToSave)
                }
                currentSpeakerName = null
                resetJob?.cancel()
                _voiceServiceState.update { it.copy(incomingCall = null, networkStatus = "Listening...") }
                updateNotification("Listening...", null)
                releaseResourcesIfNeeded()
            }
            return
        }

        if (senderName.trim().equals(myUsername.trim(), ignoreCase = true) || blockedCache.contains(senderName) || senderIp == ignoredSender) return

        // [NEW] Ruthless Preemption (Principal Override)
        val isPrincipal = principalCache.contains(senderName)

        if (currentSpeakerName != null && currentSpeakerName != senderName) {
            if (isPrincipal) {
                currentSpeakerName = senderName
                Log.w(tag, "Principal Override: $senderName taking over.")
            } else {
                return
            }
        } else {
            currentSpeakerName = senderName
        }

        val seqOffset = 1 + nameLen
        val seqNum = ByteBuffer.wrap(data, seqOffset, 4).int
        val payloadOffset = seqOffset + 4
        val payloadLen = length - payloadOffset
        val lastSeq = sequenceMap.getOrPut(senderName) { -1 }

        if (seqNum > lastSeq || seqNum < lastSeq - 1000 || seqNum == 0) {
            if (!senderName.startsWith("group:") && activeIpCache[senderName] != senderIp) {
                activeIpCache[senderName] = senderIp
                scope.launch { repository.updateContactIp(senderName, senderIp) }
            }

            if (seqNum == 0 || seqNum < lastSeq - 1000) {
                scope.launch { repository.insertLog(senderName, true) }
                sequenceMap[senderName] = -1
            }
            sequenceMap[senderName] = seqNum
            lastIncomingIp = senderIp
            handleIncomingSignal(senderName, isPrincipal)

            if (payloadLen > 0) {
                var payload = data.copyOfRange(payloadOffset, payloadOffset + payloadLen)

                val prefs = getSharedPreferences("WalkiePrefs", Context.MODE_PRIVATE)
                val isSecureMode = prefs.getBoolean("secure_mode", false)

                if (isSecureMode) {
                    val isGroup = senderName.startsWith("group:")
                    val secretKey = getDecryptionKey(isGroup)
                    if (secretKey != null) {
                        var decrypted = CryptoEngine.decrypt(payload, seqNum, secretKey)
                        if (decrypted == null && payload.size > 656 && payload.size < 720) {
                            try {
                                val trimmedPayload = payload.copyOfRange(0, 656)
                                decrypted = CryptoEngine.decrypt(trimmedPayload, seqNum, secretKey)
                            } catch (e: Exception) { }
                        }
                        if (decrypted != null) payload = decrypted
                    }
                }

                // Case A: Text Message
                if (payload.size < 500) {
                    try {
                        val textData = String(payload, Charsets.UTF_8)
                        if (textData.startsWith("TXT:")) {
                            val cleanMessage = textData.substring(4)

                            // Intercept Call Commands before they become chat messages
                            if (cleanMessage.startsWith("CMD:CALL")) {
                                scope.launch {
                                    `in`.chinmoydas.signal.utils.CallSignaling.handlePacket(cleanMessage, senderIp)
                                }
                                return // STOP! Do not save to database or speak via TTS
                            }

                            // [NEW] Principal Text Override
                            val shouldSpeak = !isSilenced || isPrincipal
                            val notificationPrefix = if(isPrincipal) "⚠️ PRIORITY MSG" else "Message"
                            val contentPrefix = if(isPrincipal) "Priority Message from $senderName" else "Message from $senderName"

                            if (shouldSpeak) {
                                if (isTtsReady) {
                                    tts?.speak("$contentPrefix. $cleanMessage", TextToSpeech.QUEUE_FLUSH, null, null)
                                }
                                scope.launch {
                                    val db = AppDatabase.getDatabase(applicationContext)
                                    db.pagerDao().insert(PagerEntry(sender = senderName, type = "TEXT", content = cleanMessage, isRead = true))
                                }
                                // Ensure user sees notification even if spoken
                                updateNotification("$notificationPrefix from $senderName", cleanMessage)
                            } else {
                                scope.launch {
                                    val db = AppDatabase.getDatabase(applicationContext)
                                    db.pagerDao().insert(PagerEntry(sender = senderName, type = "TEXT", content = cleanMessage, isRead = false))
                                    updateNotification("$notificationPrefix from $senderName", cleanMessage)
                                }
                            }
                            return
                        }
                    } catch (e: Exception) { }
                }

                // Case B: Audio
                if (payload.size >= 640 && payload.size < 720) {
                    try { payload = G711.decode(payload, 640) } catch (e: Exception) { }
                }

                // [NEW] Principal Audio Override
                if (!isSilenced || isPrincipal) {
                    try { audioEngine.writeAudio(seqNum, payload) } catch (e: Exception) {}
                }

                if (isRecordingEnabled) {
                    synchronized(bufferLock) { try { incomingBuffer.write(payload) } catch (t: Throwable) {} }
                }

                _voiceServiceState.update { it.copy(lastPingResponse = System.currentTimeMillis()) }
            }
        }
    }

    private fun handleIncomingSignal(callerName: String, isPriority: Boolean = false) {
        val currentTime = System.currentTimeMillis()
        if (!isReceiving || (currentTime - lastReceiveTime > 3000) || isPriority) {
            acquireResources()
            isReceiving = true

            // [UPDATED] Notification distinction for Principal Override
            val status = if (isPriority) "⚠️ PRIORITY: $callerName"
            else if (isSilenced) "Missed: $callerName"
            else "Incoming: $callerName"

            updateNotification(status, callerName)
            if (!isSilenced || isPriority) vibrate()

            _voiceServiceState.update { it.copy(incomingCall = callerName, incomingIp = lastIncomingIp) }
        }
        lastReceiveTime = currentTime
        resetJob?.cancel()
        resetJob = scope.launch {
            delay(5000)
            if (isReceiving) {
                isReceiving = false
                releaseResourcesIfNeeded()
                updateNotification("Listening...", null)
                _voiceServiceState.update { it.copy(incomingCall = null, networkStatus = "Listening...") }
            }
        }
    }

    private fun saveIncomingMessage(sender: String, data: ByteArray) {
        scope.launch(Dispatchers.IO) {
            try {
                val timestamp = System.currentTimeMillis()
                val fileName = "${timestamp}_${sender}.wav"
                val file = java.io.File(cacheDir, fileName)
                WavUtils.saveWavFile(file, data)

                val db = AppDatabase.getDatabase(applicationContext)
                val entry = PagerEntry(
                    sender = sender,
                    timestamp = timestamp,
                    type = "AUDIO",
                    content = file.absolutePath,
                    isRead = false
                )
                db.pagerDao().insert(entry)
                updateNotification("New Voice Message", "From $sender")
            } catch (e: Exception) { Log.e(tag, "Failed to save message", e) }
        }
    }

    fun sendRemoteHangup() {
        val target = _voiceServiceState.value.incomingCall
        stopReceiving()
        if (!target.isNullOrBlank() && !target.startsWith("group:")) {
            scope.launch(Dispatchers.IO) {
                try {
                    val token = repository.getToken()
                    if (!token.isNullOrBlank() && token != "OFFLINE_TOKEN") {
                        RetrofitClient.api.sendSignal("Bearer $token", "hangup", target)
                    }
                } catch (e: Exception) { }
            }
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startTalk(ips: List<String>, port: Int) {
        if (isSending) return

        val targetUser = repository.getTargetUser()
        if (targetUser.isNotBlank() && !targetUser.startsWith("group:")) {
            scope.launch(Dispatchers.IO) {
                try {
                    val token = repository.getToken()
                    if (!token.isNullOrBlank() && token != "OFFLINE_TOKEN") {
                        RetrofitClient.api.sendSignal("Bearer $token", "call_request", targetUser)
                    }
                } catch (e: Exception) { }
            }
        }

        acquireResources(forceAudio = true)
        isSending = true
        currentSequenceNumber = 0
        activeTargets = ips
        lastPort = port
        updateState()

        val prefs = getSharedPreferences("WalkiePrefs", Context.MODE_PRIVATE)
        dataSaverEnabled = prefs.getBoolean("data_saver", false)
        val isGroupCall = ips.size > 1
        val shouldCompress = isGroupCall || (!isOnWifi && dataSaverEnabled)

        val isSecureMode = prefs.getBoolean("secure_mode", false)
        val secretKey = if (isSecureMode) getEncryptionKey() else null

        scope.launch { repository.insertLog(if (isGroupCall) "Group Broadcast" else "PTT Call", false) }

        val nameBytes = myUsername.toByteArray()
        val headerLen = 1 + nameBytes.size + 4

        audioEngine.startRecording(shouldCompress) { rawBuffer ->
            val payloadToSend = if (isSecureMode && secretKey != null) {
                CryptoEngine.encrypt(rawBuffer, currentSequenceNumber, secretKey) ?: rawBuffer
            } else {
                rawBuffer
            }

            val sendBuf = ByteArray(headerLen + payloadToSend.size)
            sendBuf[0] = nameBytes.size.toByte()
            System.arraycopy(nameBytes, 0, sendBuf, 1, nameBytes.size)
            ByteBuffer.wrap(sendBuf, 1 + nameBytes.size, 4).putInt(currentSequenceNumber++)
            System.arraycopy(payloadToSend, 0, sendBuf, headerLen, payloadToSend.size)
            networkEngine.send(sendBuf, activeTargets, port)
        }
    }

    fun updateTalkTargets(newIps: List<String>) { activeTargets = newIps }

    fun stopTalk() {
        if (!isSending) return
        isSending = false
        updateState()
        audioEngine.stopRecording()
        scope.launch {
            val endNameBytes = END_STREAM_SIGNAL.toByteArray()
            val buf = ByteArray(1 + endNameBytes.size + 4)
            buf[0] = endNameBytes.size.toByte()
            System.arraycopy(endNameBytes, 0, buf, 1, endNameBytes.size)
            repeat(3) { networkEngine.send(buf, activeTargets, lastPort); delay(20) }
            releaseResourcesIfNeeded()
        }
    }

    fun stopReceiving() {
        ignoredSender = lastIncomingIp
        isReceiving = false
        resetJob?.cancel()
        _voiceServiceState.update { it.copy(incomingCall = null, networkStatus = "Listening...") }
        updateNotification("Listening...", null)
        releaseResourcesIfNeeded()
        scope.launch { delay(IGNORE_SENDER_DELAY); ignoredSender = null }
    }

    @Suppress("DEPRECATION")
    fun toggleSpeaker(on: Boolean) {
        userPrefersSpeaker = on
        updateState()
        if (activeCalls.get() > 0 && !isSilenced) {
            audioManager.isSpeakerphoneOn = on
            if (!on) {
                try { audioManager.startBluetoothSco(); audioManager.isBluetoothScoOn = true } catch (e: Exception) {}
            } else {
                try { audioManager.stopBluetoothSco(); audioManager.isBluetoothScoOn = false } catch (e: Exception) {}
            }
        }
    }

    private fun acquireResources(forceAudio: Boolean = false) {
        if (activeCalls.getAndIncrement() == 0) {
            if (!wakeLock.isHeld) wakeLock.acquire(10 * 60 * 1000L)
            if (!wifiLock.isHeld) wifiLock.acquire()
            if (!isSilenced || forceAudio) {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                audioManager.isSpeakerphoneOn = userPrefersSpeaker
                if (!userPrefersSpeaker) { try { audioManager.startBluetoothSco(); audioManager.isBluetoothScoOn = true } catch (e: Exception) {} }
                requestFocus()
            }
        }
    }

    private fun releaseResourcesIfNeeded() {
        if (activeCalls.decrementAndGet() == 0) {
            if (wakeLock.isHeld) wakeLock.release()
            if (wifiLock.isHeld) wifiLock.release()
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = false
            abandonFocus()
        }
    }

    private fun requestFocus(): Boolean {
        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { focusChange ->
                if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                    if (isSending) stopTalk()
                    if (isReceiving) stopReceiving()
                }
            }.build()
        activeFocusRequest = focusRequest
        return audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() { activeFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }; activeFocusRequest = null }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        try {
            val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE) else null
            if (effect != null) vibrator.vibrate(effect) else vibrator.vibrate(100)
        } catch (e: Exception) { }
    }

    private fun getLocalIpAddress(): String {
        try {
            for (ni in java.net.NetworkInterface.getNetworkInterfaces()) {
                for (addr in ni.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) return addr.hostAddress ?: ""
                }
            }
        } catch (e: Exception) { }
        return ""
    }

    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                triggerHeartbeat()
                delay(25000)
            }
        }
    }

    fun triggerHeartbeat(status: String = "online") {
        scope.launch {
            val stunReq = StunClient.createBindRequest()
            if (stunReq != null) networkEngine.sendRawPacket(stunReq)

            val token = repository.getToken()
            if (!token.isNullOrBlank() && token != "OFFLINE_TOKEN") {
                try {
                    RetrofitClient.api.sendHeartbeat("Bearer $token", myPublicPort, getLocalIpAddress(), currentChannel, currentChannelKey, status)
                } catch (e: Exception) { Log.e(tag, "Heartbeat failed", e) }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("VoiceChannel", "Walkie Talkie", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startForegroundServiceNotification(status: String) {
        val notification = buildNotification(status)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notification)
        }
    }

    private fun updateNotification(text: String, channelName: String?) {
        val notification = buildNotification(text, channelName)
        getSystemService(NotificationManager::class.java).notify(1, notification)
    }

    private fun buildNotification(text: String, channelName: String? = null): android.app.Notification {
        val intent = Intent(this, MainActivity::class.java).apply { if (channelName != null) putExtra("auto_connect_channel", channelName) }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val exitIntent = Intent(this, VoiceService::class.java).apply { action = "STOP_SERVICE" }
        val exitPendingIntent = PendingIntent.getService(this, 999, exitIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val exitAction = NotificationCompat.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Exit", exitPendingIntent).build()

        val muteIntent = Intent(this, VoiceService::class.java).apply { action = "TOGGLE_MUTE" }
        val mutePendingIntent = PendingIntent.getService(this, 888, muteIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val muteLabel = if (isSilenced) "Unmute" else "Mute"
        val muteIcon = if (isSilenced) android.R.drawable.ic_lock_silent_mode else android.R.drawable.ic_lock_silent_mode_off
        val muteAction = NotificationCompat.Action.Builder(muteIcon, muteLabel, mutePendingIntent).build()

        val headsetIntent = Intent(this, VoiceService::class.java).apply { action = "TOGGLE_HEADSET" }
        val headsetPendingIntent = PendingIntent.getService(this, 777, headsetIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val headsetIcon = if (isHeadsetLinked) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val headsetLabel = if (isHeadsetLinked) "Detach Keys" else "Grab Keys"
        val headsetAction = NotificationCompat.Action.Builder(headsetIcon, headsetLabel, headsetPendingIntent).build()

        return NotificationCompat.Builder(this, "VoiceChannel")
            .setContentTitle("CD Signal").setContentText(text).setSmallIcon(R.mipmap.ic_launcher_foreground).setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true).setOngoing(true)
            .addAction(muteAction).addAction(headsetAction).addAction(exitAction)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle().setShowActionsInCompactView(0, 1, 2)).build()
    }

    override fun onDestroy() {
        if (tts != null) {
            tts?.stop()
            tts?.shutdown()
            tts = null
        }
        scope.launch { triggerHeartbeat("offline") }
        audioEngine.shutdown()
        networkEngine.stop()
        localLinkManager?.stop()
        mediaSession.release()
        (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.unregisterNetworkCallback(networkCallback)
        if (wakeLock.isHeld) wakeLock.release()
        if (multicastLock.isHeld) multicastLock.release()
        if (wifiLock.isHeld) wifiLock.release()
        scope.cancel()
        super.onDestroy()
    }
}