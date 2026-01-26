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
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.media.session.MediaButtonReceiver
import `in`.chinmoydas.signal.data.AppDatabase
import `in`.chinmoydas.signal.data.PagerEntry
import `in`.chinmoydas.signal.data.MainRepository
import `in`.chinmoydas.signal.utils.AudioEngine
import `in`.chinmoydas.signal.utils.AudioRouter
import `in`.chinmoydas.signal.utils.CallEngine
import `in`.chinmoydas.signal.utils.CryptoEngine
import `in`.chinmoydas.signal.utils.G711
import `in`.chinmoydas.signal.utils.LocalLinkManager
import `in`.chinmoydas.signal.utils.NetworkEngine
import `in`.chinmoydas.signal.RetrofitClient
import `in`.chinmoydas.signal.utils.StunClient
import `in`.chinmoydas.signal.utils.WavUtils
import `in`.chinmoydas.signal.utils.CallSignaling
import `in`.chinmoydas.signal.utils.SafetySignaling
import `in`.chinmoydas.signal.utils.SensorHelper
import `in`.chinmoydas.signal.utils.VoxHelper
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
    val isTransmitting: Boolean = false,
    val isTheaterMode: Boolean = false,
    val isVoxEnabled: Boolean = false,
    val isSensorEnabled: Boolean = false,
    val isSosPending: Boolean = false
)

class VoiceService : Service() {
    private val tag = "VoiceService"
    private val binder = LocalBinder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _voiceServiceState = MutableStateFlow(VoiceServiceState())
    val voiceServiceState = _voiceServiceState.asStateFlow()

    private var lastRejectTime: Long = 0
    private var isTheaterMode = false
    var packetInterceptor: ((String, String) -> Boolean)? = null

    // Engines & Routers
    private lateinit var audioEngine: AudioEngine
    private lateinit var networkEngine: NetworkEngine
    private lateinit var audioRouter: AudioRouter
    private lateinit var repository: MainRepository

    private var sensorHelper: SensorHelper? = null
    private var voxHelper: VoxHelper? = null
    private var voxJob: Job? = null

    private var tts: TextToSpeech? = null

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

    private var lastStatusMessage: String = "Initializing..."

    var isSilenced: Boolean = false
        set(value) {
            field = value
            updateState()
            refreshNotification()
        }

    private var userPrefersSpeaker = true

    private fun updateState() {
        _voiceServiceState.update {
            it.copy(
                isSilenced = isSilenced,
                isSpeakerOn = userPrefersSpeaker,
                isHeadsetLinked = isHeadsetLinked,
                isTransmitting = isSending,
                isTheaterMode = isTheaterMode
            )
        }
    }

    private val sequenceMap = ConcurrentHashMap<String, Int>()
    private val activeIpCache = ConcurrentHashMap<String, String>()
    private val blockedCache = ConcurrentHashMap.newKeySet<String>()
    private val principalCache = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var ignoredSender: String? = null
    @Volatile private var activeTargets: List<String> = emptyList()
    @Volatile private var lastPort: Int = UDP_PORT

    private val incomingBuffer = java.io.ByteArrayOutputStream(512 * 1024)
    private val bufferLock = Any()
    var isRecordingEnabled = true

    @Suppress("DEPRECATION")
    private val vibrator by lazy { getSystemService(Context.VIBRATOR_SERVICE) as Vibrator }

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
            super.onAvailable(network)
            val newIp = getLocalIpAddress()

            if (newIp.isNotEmpty() && newIp != myLocalIp) {
                Log.i(tag, "Network Handover: $myLocalIp -> $newIp")
                myLocalIp = newIp

                scope.launch {
                    try { networkEngine.stop() } catch (e: Exception) {}
                    delay(500)

                    networkEngine.start { packet ->
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

                    val principals = repository.getPrincipalContacts()
                    principalCache.clear()
                    principalCache.addAll(principals.map { it.name })

                    _voiceServiceState.update { it.copy(networkStatus = "Listening...") }
                    triggerHeartbeat()
                    localLinkManager?.startAdvertising(myUsername, UDP_PORT)
                }
            } else if (myLocalIp.isEmpty()) {
                myLocalIp = newIp
            }
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            Log.w(tag, "Network Lost")
            _voiceServiceState.update { it.copy(networkStatus = "Waiting for Network...") }
        }
    }

    inner class LocalBinder : Binder() { fun getService(): VoiceService = this@VoiceService }
    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()

        audioRouter = AudioRouter(this)
        audioRouter.initialize()
        audioRouter.onRouteChanged = { isSpeakerOn ->
            userPrefersSpeaker = isSpeakerOn
            updateState()
        }

        audioEngine = AudioEngine(this)
        networkEngine = NetworkEngine(UDP_PORT)
        repository = MainRepository(applicationContext)
        observeRepositoryFlows()

        val prefs = getSharedPreferences("WalkiePrefs", Context.MODE_PRIVATE)
        dataSaverEnabled = prefs.getBoolean("data_saver", false)

        initMediaSession()

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
            }
        }

        scope.launch {
            val principals = repository.getPrincipalContacts()
            principalCache.clear()
            principalCache.addAll(principals.map { it.name })

            val blocked = repository.getBlockedContacts()
            blockedCache.clear()
            blockedCache.addAll(blocked.map { it.name })
        }

        sensorHelper = SensorHelper(this) {
            scope.launch {
                _voiceServiceState.update { it.copy(isSosPending = true) }
                val v = if (Build.VERSION.SDK_INT >= 31) (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator else vibrator
                v.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }

        voxHelper = VoxHelper(
            onSpeechStart = { scope.launch(Dispatchers.Main) { toggleTalk(fromVox = true) } },
            onSilence = { scope.launch(Dispatchers.Main) { toggleTalk(fromVox = true) } }
        )

        registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY == intent?.action) {
                    if (isSending) stopTalk()
                }
            }
        }, android.content.IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY))

        multicastLock.setReferenceCounted(false)
        wakeLock.setReferenceCounted(false)
        wifiLock.setReferenceCounted(false)
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), networkCallback)
    }

    // Helper to Bridge MainActivity -> AudioRouter
    fun setCallMode(active: Boolean) {
        if (::audioRouter.isInitialized) {
            audioRouter.setCallMode(active)
        }
    }

    fun speakText(text: String) {
        if (isSilenced || isTheaterMode) {
            Log.d(tag, "TTS Suppressed: Device is Silenced or in Stealth Mode")
            return
        }

        val params = Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "ID")
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

    private fun toggleTalk(fromVox: Boolean = false) {
        if (isSending) {
            stopTalk()
            if (!fromVox) vibrate()
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
                        if (!fromVox) vibrate()
                        startTalk(listOf(ip), UDP_PORT)
                    }
                } else {
                    if (ActivityCompat.checkSelfPermission(this@VoiceService, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        if (!fromVox) vibrate()
                        startTalk(if(ip != null) listOf(ip) else emptyList(), UDP_PORT)
                    }
                }
            }
        }
    }

    private fun startVoxMonitoring() {
        stopVoxMonitoring()
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return

        voxJob = scope.launch(Dispatchers.IO) {
            val bufferSize = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
            val buffer = ByteArray(bufferSize)

            try {
                recorder.startRecording()
                while (isActive && _voiceServiceState.value.isVoxEnabled && !isSending) {
                    val read = recorder.read(buffer, 0, bufferSize)
                    if (read > 0) {
                        voxHelper?.process(buffer)
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "VOX Monitor Error", e)
            } finally {
                try { recorder.stop(); recorder.release() } catch (e: Exception) {}
            }
        }
    }

    private fun stopVoxMonitoring() {
        voxJob?.cancel()
        voxJob = null
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

        when (action) {
            "STOP_SERVICE" -> {
                sendBroadcast(Intent("in.chinmoydas.signal.ACTION_EXIT").setPackage(packageName))
                stopSelf()
                return START_NOT_STICKY
            }

            "TOGGLE_MUTE" -> {
                isSilenced = !isSilenced
                _voiceServiceState.update { it.copy(isSilenced = isSilenced) }
                if (activeCalls.get() > 0) {
                    if (isSilenced) {
                        audioRouter.abandonFocus()
                    } else { acquireResources() }
                }
                refreshNotification()
                return START_STICKY
            }

            "TOGGLE_HEADSET" -> {
                val newState = !_voiceServiceState.value.isHeadsetLinked
                isHeadsetLinked = newState
                _voiceServiceState.update { it.copy(isHeadsetLinked = newState) }
                mediaSession.isActive = newState
                val msg = if (newState) "Pocket Mode: ON (Keys Active)" else "Pocket Mode: OFF"
                updateNotification(msg, null)
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                return START_STICKY
            }

            "TOGGLE_STEALTH" -> {
                toggleSpeaker(!userPrefersSpeaker)
                isTheaterMode = !isTheaterMode
                _voiceServiceState.update { it.copy(isTheaterMode = isTheaterMode) }

                val v = if (Build.VERSION.SDK_INT >= 31) (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator else vibrator
                v.vibrate(VibrationEffect.createOneShot(100, 100))
                refreshNotification()
                return START_STICKY
            }

            "TOGGLE_VOX" -> {
                // [FIX] Read the specific state requested from the intent
                val requestedState = if (intent.hasExtra("state")) {
                    intent.getBooleanExtra("state", false)
                } else {
                    !_voiceServiceState.value.isVoxEnabled // Fallback to toggle
                }

                _voiceServiceState.update { it.copy(isVoxEnabled = requestedState) }

                if (requestedState) {
                    startVoxMonitoring()
                    // Only show Toast if it was a manual toggle (not a programmatic reset)
                    if (!intent.hasExtra("state")) {
                        Toast.makeText(this, "VOX: Auto-Transmit ON", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    stopVoxMonitoring()
                    if (isSending) stopTalk()
                    if (!intent.hasExtra("state")) {
                        Toast.makeText(this, "VOX: OFF", Toast.LENGTH_SHORT).show()
                    }
                }
                refreshNotification()
                return START_STICKY
            }

            "TOGGLE_SENSOR" -> {
                val newState = !_voiceServiceState.value.isSensorEnabled
                _voiceServiceState.update { it.copy(isSensorEnabled = newState) }
                if (newState) {
                    sensorHelper?.start()
                    Toast.makeText(this, "Shield: Crash Detection ON", Toast.LENGTH_SHORT).show()
                } else {
                    sensorHelper?.stop()
                    Toast.makeText(this, "Shield: OFF", Toast.LENGTH_SHORT).show()
                }
                refreshNotification()
                return START_STICKY
            }
        }

        if (intent == null && _voiceServiceState.value.networkStatus != "Disconnected") {
            return START_STICKY
        }

        createNotificationChannel()
        try { startForegroundServiceNotification("Initializing Radio...") } catch (e: Exception) { stopSelf(); return START_NOT_STICKY }

        scope.launch(Dispatchers.IO) {
            try {
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
                    _voiceServiceState.update { it.copy(networkStatus = "Error: UDP Bind Failed") }
                    stopSelf()
                    return@launch
                }

                _voiceServiceState.update { it.copy(networkStatus = "Listening...") }
                updateNotification("Online & Ready", null)
                startHeartbeatLoop()
                startSignalLoop()

            } catch (e: Exception) { Log.e(tag, "Startup Error: ${e.message}") }
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
                    try { RetrofitClient.api.checkSignals("Bearer $token") } catch (e: Exception) { }
                }
                delay(2000)
            }
        }
    }

    private val stunServers = listOf(
        Pair("stun.l.google.com", 19302),
        Pair("stun1.l.google.com", 19302),
        Pair("stun2.l.google.com", 19302),
        Pair("stun.services.mozilla.com", 3478)
    )

    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            var serverIndex = 0
            while (isActive) {
                delay(15_000)
                if (networkEngine.isBound()) {
                    try {
                        val (host, port) = stunServers[serverIndex]
                        val stunReq = StunClient.createBindRequest(host, port)
                        if (stunReq != null) networkEngine.sendRawPacket(stunReq)
                        triggerHeartbeat()
                    } catch (e: Exception) {
                        serverIndex = (serverIndex + 1) % stunServers.size
                    }
                }
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
                    RetrofitClient.api.sendHeartbeat(
                        "Bearer $token",
                        myPublicPort,
                        getLocalIpAddress(),
                        currentChannel,
                        currentChannelKey,
                        status
                    )
                } catch (e: Exception) { }
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
                // 1. Prepare Payload (Same as before)
                val rawPayload = "TXT:$message".toByteArray(Charsets.UTF_8)
                val seqNum = (System.currentTimeMillis() % 1000000).toInt()

                // 2. Encryption (Same as before)
                val prefs = getSharedPreferences("WalkiePrefs", Context.MODE_PRIVATE)
                val isSecureMode = prefs.getBoolean("secure_mode", false)
                val finalPayload = if (isSecureMode) {
                    val contact = repository.findContactByIp(targetIp)
                    val secretString = contact?.savedCode ?: "0000"
                    val secretKeySpec = CryptoEngine.deriveKey(secretString)
                    CryptoEngine.encrypt(rawPayload, seqNum, secretKeySpec) ?: rawPayload
                } else { rawPayload }

                // 3. Construct Packet (Same as before)
                val nameBytes = myUsername.toByteArray(Charsets.UTF_8)
                val packetSize = 1 + nameBytes.size + 4 + finalPayload.size
                val buffer = ByteBuffer.allocate(packetSize)
                buffer.put(nameBytes.size.toByte())
                buffer.put(nameBytes)
                buffer.putInt(seqNum)
                buffer.put(finalPayload)
                val packetData = buffer.array()

                // [THE FIX] REUSE THE EXISTING NETWORK ENGINE
                // Do NOT create a new DatagramSocket().
                // Do NOT call socket.close().
                // This sends data from Port 50005, keeping the router "door" open.

                repeat(3) {
                    // UDP_PORT is your constant 50005
                    networkEngine.send(packetData, listOf(targetIp), UDP_PORT)
                    delay(50)
                }

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
        val isPrincipal = principalCache.contains(senderName)

        if (currentSpeakerName != null && currentSpeakerName != senderName) {
            if (isPrincipal) {
                currentSpeakerName = senderName
                Log.w(tag, "Principal Override: $senderName taking over.")
            } else { return }
        } else { currentSpeakerName = senderName }

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

                if (payload.size < 500) {
                    try {
                        val textData = String(payload, Charsets.UTF_8)
                        if (textData.startsWith("TXT:")) {
                            val cleanMessage = textData.substring(4)

                            if (packetInterceptor?.invoke(cleanMessage, senderIp) == true) {
                                return
                            }

                            if (cleanMessage.startsWith("CMD:CALL")) {
                                scope.launch { CallSignaling.handlePacket(cleanMessage, senderIp) }
                                return
                            }

                            if (cleanMessage.startsWith("CMD:SOS") || cleanMessage.startsWith("CMD:PANIC")) {
                                scope.launch { SafetySignaling.triggerSOS(senderIp) }
                                return
                            }

                            if (cleanMessage.startsWith("LOC:")) {
                                val coords = cleanMessage.removePrefix("LOC:")
                                scope.launch { SafetySignaling.triggerLocation(senderIp, coords) }
                                return
                            }

                            if (cleanMessage.startsWith("CMD:REMOTE_")) {
                                val isRemoteAllowed = prefs.getBoolean("allow_remote_control", false)

                                if (!isRemoteAllowed) {
                                    val now = System.currentTimeMillis()
                                    if (now - lastRejectTime > 5000) {
                                        lastRejectTime = now
                                        sendTextMessage(senderIp, "Remote Control is DISABLED on this device.")
                                        updateNotification("🚫 Blocked CMD", "Guardian Mode OFF. Ignored $senderName")
                                    }
                                    return
                                }

                                if (isPrincipal) {
                                    if (cleanMessage == "CMD:REMOTE_MIC_ON") {
                                        updateNotification("🎙️ REMOTE ACTIVE", "Mic accessed by $senderName")
                                        if (!_voiceServiceState.value.isVoxEnabled) toggleVox(true)
                                    }
                                    else if (cleanMessage == "CMD:REMOTE_STEALTH") {
                                        updateNotification("🤫 STEALTH MODE", "Activated by $senderName")
                                        toggleSpeaker(false)
                                        isSilenced = true
                                        isTheaterMode = true
                                        _voiceServiceState.update { it.copy(isSilenced = true, isTheaterMode = true) }
                                    }
                                    else if (cleanMessage == "CMD:REMOTE_LOCATION") {
                                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                                            val locMgr = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                                            val loc = locMgr.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                                                ?: locMgr.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)

                                            if (loc != null) {
                                                sendTextMessage(senderIp, "LOC:${loc.latitude},${loc.longitude}")
                                                updateNotification("📍 LOCATION SHARED", "Sent to $senderName")
                                            } else {
                                                // [IMPROVEMENT] Tell the Guardian that GPS failed instead of staying silent
                                                sendTextMessage(senderIp, "ERROR: No GPS Signal found")
                                            }
                                        }
                                    }
                                    // [NOTE] It is cleaner to use 'else if' here to keep the chain together,
                                    // but a separate 'if' works fine too.
                                    else if (cleanMessage == "CMD:REMOTE_RESTORE") {
                                        // Reset Flags
                                        isSilenced = false
                                        isTheaterMode = false
                                        _voiceServiceState.update { it.copy(isSilenced = false, isTheaterMode = false) }

                                        // Reset Hardware
                                        toggleSpeaker(true)
                                        if (_voiceServiceState.value.isVoxEnabled) toggleVox(false)

                                        // Confirm
                                        speakText("Device restored to normal mode")
                                        updateNotification("✅ RESTORED", "Reset by $senderName")
                                        sendTextMessage(senderIp, "CONFIRM: Device Restored to Normal")
                                    }
                                } else {
                                    scope.launch { SafetySignaling.triggerSecurityAlert(senderName) }
                                    updateNotification("⚠️ SECURITY WARN", "Blocked CMD from $senderName")
                                }
                                return
                            }

                            val notificationPrefix = if(isPrincipal) "⚠️ PRIORITY MSG" else "Message"
                            speakText(cleanMessage)
                            scope.launch {
                                val db = AppDatabase.getDatabase(applicationContext)
                                db.pagerDao().insert(PagerEntry(sender = senderName, type = "TEXT", content = cleanMessage, isRead = false))
                            }
                            updateNotification("$notificationPrefix from $senderName", cleanMessage)
                            return
                        }
                    } catch (e: Exception) { }
                }

                if (payload.size >= 640 && payload.size < 720) {
                    try { payload = G711.decode(payload, 640) } catch (e: Exception) { }
                }

                if (CallEngine.isCallActive) {
                    return
                }

                if (!isSilenced || isPrincipal) {
                    try { audioEngine.writeAudio(seqNum, payload) } catch (e: Exception) {}
                }
                if (isRecordingEnabled) {
                    synchronized(bufferLock) { try { incomingBuffer.write(payload) } catch (t: Throwable) {} }
                }
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
                db.pagerDao().insert(PagerEntry(sender = sender, timestamp = timestamp, type = "AUDIO", content = file.absolutePath, isRead = false))
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

    fun sendPanicAlert() {
        scope.launch(Dispatchers.IO) {
            val allContacts = repository.getAllContacts()
            var sentCount = 0
            allContacts.forEach { contact ->
                if (!contact.ip.isNullOrBlank() && contact.ip != "SERVER_LINK") {
                    sendTextMessage(contact.ip, "CMD:SOS")
                    sentCount++
                    delay(10)
                }
            }
            val currentTarget = activeIpCache[repository.getTargetUser()]
            if (!currentTarget.isNullOrBlank() && allContacts.none { it.ip == currentTarget }) {
                sendTextMessage(currentTarget, "CMD:SOS")
                sentCount++
            }
            if (sentCount == 0) {
                sendTextMessage("255.255.255.255", "CMD:SOS")
            }
        }
    }

    fun sendLocationPing(lat: Double, lon: Double) {
        scope.launch(Dispatchers.IO) {
            val allContacts = repository.getAllContacts()
            var sentCount = 0
            allContacts.forEach { contact ->
                if (!contact.ip.isNullOrBlank() && contact.ip != "SERVER_LINK") {
                    sendTextMessage(contact.ip, "LOC:$lat,$lon")
                    sentCount++
                    delay(10)
                }
            }
            val currentTarget = activeIpCache[repository.getTargetUser()]
            if (!currentTarget.isNullOrBlank() && allContacts.none { it.ip == currentTarget }) {
                sendTextMessage(currentTarget, "LOC:$lat,$lon")
                sentCount++
            }
            if (sentCount == 0) {
                sendTextMessage("255.255.255.255", "LOC:$lat,$lon")
            }
        }
    }

    fun confirmSos() {
        _voiceServiceState.update { it.copy(isSosPending = false) }
        sendPanicAlert()
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val locMgr = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val loc = locMgr.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                ?: locMgr.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
            if (loc != null) sendLocationPing(loc.latitude, loc.longitude)
        }
        if (!_voiceServiceState.value.isVoxEnabled) toggleVox(true)
    }

    fun cancelSos() {
        _voiceServiceState.update { it.copy(isSosPending = false) }
    }

    fun toggleTheaterMode(enabled: Boolean) { toggleSpeaker(!enabled) }

    // [FIX] Updated helper to send explicit state
    fun toggleVox(enabled: Boolean) {
        val intent = Intent(this, VoiceService::class.java).apply {
            action = "TOGGLE_VOX"
            putExtra("state", enabled) // Pass specific state request
        }
        startService(intent)
    }

    fun toggleSensor(enabled: Boolean) {
        val intent = Intent(this, VoiceService::class.java).apply { action = "TOGGLE_SENSOR" }
        startService(intent)
    }

    fun updateTalkTargets(newIps: List<String>) {
        activeTargets = newIps
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startTalk(ips: List<String>, port: Int) {
        if (isSending) return
        stopVoxMonitoring()
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

        audioEngine.startRecording(shouldCompress) { rawBuffer ->
            if (_voiceServiceState.value.isVoxEnabled) voxHelper?.process(rawBuffer)
            val payloadToSend = if (isSecureMode && secretKey != null) {
                CryptoEngine.encrypt(rawBuffer, currentSequenceNumber, secretKey) ?: rawBuffer
            } else { rawBuffer }

            val nameBytes = myUsername.toByteArray()
            val headerLen = 1 + nameBytes.size + 4
            val sendBuf = ByteArray(headerLen + payloadToSend.size)
            sendBuf[0] = nameBytes.size.toByte()
            System.arraycopy(nameBytes, 0, sendBuf, 1, nameBytes.size)
            ByteBuffer.wrap(sendBuf, 1 + nameBytes.size, 4).putInt(currentSequenceNumber++)
            System.arraycopy(payloadToSend, 0, sendBuf, headerLen, payloadToSend.size)
            networkEngine.send(sendBuf, activeTargets, port)
        }
    }

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

        // [FIX] Don't restart VOX if we are just ending a transmission manually
        if (_voiceServiceState.value.isVoxEnabled) startVoxMonitoring()
    }

    @Suppress("DEPRECATION")
    fun toggleSpeaker(on: Boolean) {
        audioRouter.setSpeakerPreferred(on)
        userPrefersSpeaker = on
        updateState()
        refreshNotification()
    }

    private fun acquireResources(forceAudio: Boolean = false) {
        if (activeCalls.getAndIncrement() == 0) {
            if (!wakeLock.isHeld) wakeLock.acquire(10 * 60 * 1000L)
            if (!wifiLock.isHeld) wifiLock.acquire()
            if (!isSilenced || forceAudio) {
                audioRouter.requestFocus()
            }
        }
    }

    private fun releaseResourcesIfNeeded() {
        if (activeCalls.decrementAndGet() == 0) {
            if (wakeLock.isHeld) wakeLock.release()
            if (wifiLock.isHeld) wifiLock.release()
            audioRouter.abandonFocus()
        }
    }

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

    private fun handleIncomingSignal(callerName: String, isPriority: Boolean = false) {
        val currentTime = System.currentTimeMillis()
        if (!isReceiving || (currentTime - lastReceiveTime > 3000) || isPriority) {
            acquireResources()
            isReceiving = true
            val status = if (isPriority) "⚠️ PRIORITY: $callerName" else if (isSilenced) "Missed: $callerName" else "Incoming: $callerName"
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

    fun stopReceiving() {
        ignoredSender = lastIncomingIp
        isReceiving = false
        resetJob?.cancel()
        _voiceServiceState.update { it.copy(incomingCall = null, networkStatus = "Listening...") }
        updateNotification("Listening...", null)
        releaseResourcesIfNeeded()
        scope.launch { delay(IGNORE_SENDER_DELAY); ignoredSender = null }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("VoiceChannel", "Walkie Talkie", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startForegroundServiceNotification(status: String) {
        lastStatusMessage = status
        val notification = buildNotification(status)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notification)
        }
    }

    private fun refreshNotification() {
        updateNotification(lastStatusMessage, null)
    }

    private fun updateNotification(text: String, channelName: String?) {
        lastStatusMessage = text
        val notification = buildNotification(text, channelName)
        getSystemService(NotificationManager::class.java).notify(1, notification)
    }

    private fun buildNotification(text: String, channelName: String? = null): android.app.Notification {
        val state = _voiceServiceState.value

        val statusBuilder = StringBuilder(text)
        if (state.isTheaterMode) statusBuilder.append(" | STEALTH")
        if (state.isVoxEnabled) statusBuilder.append(" | VOX")
        if (state.isSensorEnabled) statusBuilder.append(" | SHIELD")

        val intent = Intent(this, MainActivity::class.java).apply { if (channelName != null) putExtra("auto_connect_channel", channelName) }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val exitIntent = Intent(this, VoiceService::class.java).apply { action = "STOP_SERVICE" }
        val exitPendingIntent = PendingIntent.getService(this, 999, exitIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val exitAction = NotificationCompat.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Exit", exitPendingIntent).build()

        val stealthIntent = Intent(this, VoiceService::class.java).apply { action = "TOGGLE_STEALTH" }
        val stealthPending = PendingIntent.getService(this, 888, stealthIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stealthIcon = if (state.isTheaterMode) android.R.drawable.ic_lock_silent_mode else android.R.drawable.ic_lock_silent_mode_off
        val stealthLabel = if (state.isTheaterMode) "Un-Stealth" else "Stealth"
        val stealthAction = NotificationCompat.Action.Builder(stealthIcon, stealthLabel, stealthPending).build()

        val voxIntent = Intent(this, VoiceService::class.java).apply { action = "TOGGLE_VOX" }
        val voxPending = PendingIntent.getService(this, 777, voxIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val voxIcon = android.R.drawable.ic_btn_speak_now
        val voxLabel = if (state.isVoxEnabled) "VOX Off" else "VOX On"
        val voxAction = NotificationCompat.Action.Builder(voxIcon, voxLabel, voxPending).build()

        return NotificationCompat.Builder(this, "VoiceChannel")
            .setContentTitle("CD Signal")
            .setContentText(statusBuilder.toString())
            .setSmallIcon(R.mipmap.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(stealthAction)
            .addAction(voxAction)
            .addAction(exitAction)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle().setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    override fun onDestroy() {
        if (tts != null) { tts?.stop(); tts?.shutdown(); tts = null }
        audioRouter.shutdown()
        CallEngine.stopCall()
        scope.launch { triggerHeartbeat("offline") }
        sensorHelper?.stop()
        stopVoxMonitoring()
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