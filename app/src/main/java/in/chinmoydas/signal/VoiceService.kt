package `in`.chinmoydas.signal

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.support.v4.media.session.MediaSessionCompat
import android.view.KeyEvent
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.session.MediaButtonReceiver
import `in`.chinmoydas.signal.data.AppDatabase
import `in`.chinmoydas.signal.data.PagerEntry
import `in`.chinmoydas.signal.data.MainRepository
import `in`.chinmoydas.signal.utils.ConnectionManager
import `in`.chinmoydas.signal.utils.AudioEngine
import `in`.chinmoydas.signal.utils.AudioRouter
import `in`.chinmoydas.signal.utils.CallEngine
import `in`.chinmoydas.signal.utils.CryptoEngine
import `in`.chinmoydas.signal.utils.G711
import `in`.chinmoydas.signal.utils.LocalLinkManager
import `in`.chinmoydas.signal.utils.NetworkEngine
import `in`.chinmoydas.signal.utils.WavUtils
import `in`.chinmoydas.signal.utils.CallSignaling
import `in`.chinmoydas.signal.utils.SafetySignaling
import `in`.chinmoydas.signal.utils.SensorHelper
import `in`.chinmoydas.signal.utils.VoxHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

    // Interceptor for UI logic
    var packetInterceptor: ((String, String) -> Boolean)? = null

    // Engines & Routers
    private lateinit var audioEngine: AudioEngine
    private lateinit var networkEngine: NetworkEngine
    private lateinit var audioRouter: AudioRouter
    private lateinit var repository: MainRepository
    private lateinit var connectionManager: ConnectionManager

    private var sensorHelper: SensorHelper? = null
    private var voxHelper: VoxHelper? = null
    private var voxJob: Job? = null

    private var tts: TextToSpeech? = null

    private lateinit var mediaSession: MediaSessionCompat
    private var isHeadsetLinked = true

    // [BREACH PROTOCOL] Signals & Constants
    private val END_STREAM_SIGNAL = "__END_TX__"
    private val PING_SIGNAL = "__PING__"
    private val PONG_SIGNAL = "__PONG__"
    private val PUNCH_PACKET = "__PUNCH__"
    private val WAKE_THRESHOLD_MS = 45_000L

    // [BREACH PROTOCOL] Anti-Spam Tracker
    private var lastWakeSentTime: Long = 0L
    private val WAKE_DEBOUNCE_MS = 10_000L

    private val UDP_PORT = 50005
    @Volatile private var isOnWifi = false
    @Volatile private var dataSaverEnabled = false
    @Volatile private var activeKeySpec: javax.crypto.spec.SecretKeySpec? = null
    @Volatile private var activeKeySource: String? = null
    private val IGNORE_SENDER_DELAY = 500L

    @Volatile private var isReceiving = false
    @Volatile private var isSending = false
    private var lastReceiveTime = 0L
    private var resetJob: Job? = null
    private var cleanupJob: Job? = null

    private var callWatchdogJob: Job? = null
    @Volatile private var lastCallPacketTime: Long = 0L

    @Volatile var lastIncomingIp: String? = null
    @Volatile private var myUsername: String = "User"
    @Volatile private var myLocalIp: String = ""
    @Volatile private var currentChannel: String? = null
    @Volatile private var currentChannelKey: String? = null
    @Volatile private var targetContactKey: String? = null
    @Volatile private var myIdentityKey: String? = null
    @Volatile private var currentSpeakerName: String? = null

    @Volatile private var activeTargets: List<String> = emptyList()
    @Volatile private var lastPort: Int = UDP_PORT

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

    // [FIX] Added Port Cache to remember NAT ports
    private val activeIpCache = ConcurrentHashMap<String, String>()
    private val activePortCache = ConcurrentHashMap<String, Int>()

    private val blockedCache = ConcurrentHashMap.newKeySet<String>()
    private val principalCache = ConcurrentHashMap.newKeySet<String>()
    private val pendingAckJobs = ConcurrentHashMap<String, Job>()

    @Volatile private var ignoredSender: String? = null

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

    private var signalingJob: Job? = null
    private var currentSequenceNumber = 0
    private var localLinkManager: LocalLinkManager? = null

    private val signalReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "in.chinmoydas.signal.SEND_SIGNAL") {
                val ip = intent.getStringExtra("ip")
                val cmd = intent.getStringExtra("cmd")
                if (ip != null && cmd != null) {
                    sendTextMessage(ip, cmd)
                }
            }
        }
    }

    private val audioNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY == intent?.action) {
                if (isSending) stopTalk()
            }
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            super.onCapabilitiesChanged(network, networkCapabilities)
            val isWifiNow = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            if (isOnWifi != isWifiNow) isOnWifi = isWifiNow
        }

        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            scope.launch(Dispatchers.IO) {
                val newIp = getLocalIpAddress()
                if (newIp.isNotEmpty() && newIp != myLocalIp) {
                    Log.i(tag, "Network Handover: $myLocalIp -> $newIp")
                    myLocalIp = newIp

                    try { networkEngine.stop() } catch (e: Exception) {}
                    delay(100)

                    // [FIX] Update network start to pass PORT
                    val networkStarted = networkEngine.start { packet ->
                        handleIncomingPacket(packet.data, packet.length, packet.address?.hostAddress ?: "", packet.port)
                    }

                    if (networkStarted) {
                        _voiceServiceState.update { it.copy(networkStatus = "Listening...") }
                        sendTextMessage("255.255.255.255", "CMD:I_MOVED")

                        val currentTarget = repository.getTargetUser()
                        val targetIp = activeIpCache[currentTarget]
                        if (!targetIp.isNullOrBlank()) sendPing(targetIp)

                        broadcastHello()
                        localLinkManager?.startAdvertising(myUsername, UDP_PORT)

                        // [FIX] Use ConnectionManager STUN logic instead of hard reset
                        connectionManager.triggerImmediateHeartbeat()
                    }
                } else if (myLocalIp.isEmpty()) {
                    myLocalIp = newIp
                }
            }
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            Log.w(tag, "Network Lost")
            scope.launch {
                delay(2000)
                if (getLocalIpAddress().isEmpty()) {
                    _voiceServiceState.update { it.copy(networkStatus = "Waiting for Network...") }
                }
            }
        }
    }

    inner class LocalBinder : Binder() { fun getService(): VoiceService = this@VoiceService }
    override fun onBind(intent: Intent): IBinder = binder

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()

        audioRouter = AudioRouter(this)
        audioRouter.initialize()
        audioRouter.onRouteChanged = { isSpeakerOn ->
            userPrefersSpeaker = isSpeakerOn
            updateState()
        }

        audioEngine = AudioEngine(this)
        repository = MainRepository(applicationContext)

        networkEngine = NetworkEngine(UDP_PORT) { stunData ->
            if (::connectionManager.isInitialized) {
                connectionManager.handleStunResponse(stunData)
            }
        }

        connectionManager = ConnectionManager(repository, networkEngine)

        val prefs = getSharedPreferences("WalkiePrefs", Context.MODE_PRIVATE)
        val ecoEnabled = prefs.getBoolean("eco_mode", false)
        dataSaverEnabled = prefs.getBoolean("data_saver", false)
        connectionManager.updateEcoMode(ecoEnabled)

        initMediaSession()

        val filter = IntentFilter("in.chinmoydas.signal.SEND_SIGNAL")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(signalReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(signalReceiver, filter)
        }

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
            }
        }

        observeRepositoryFlows()

        scope.launch {
            CallSignaling.callEvents.collect { event ->
                when (event) {
                    is CallSignaling.CallEvent.IncomingCall -> {
                        val name = activeIpCache.entries.find { it.value == event.ip }?.key
                            ?: repository.findContactByIp(event.ip)?.name
                            ?: "Unknown Caller"
                        showIncomingCallNotification(name, "Incoming Voice Call", isAlarm = false)
                    }
                    is CallSignaling.CallEvent.CallConnected -> {
                        getSystemService(NotificationManager::class.java).cancel(2)
                        if (isSending) stopTalk()
                        if (_voiceServiceState.value.isVoxEnabled) toggleVox(false)
                        setCallMode(true)
                        startCallWatchdog()
                    }
                    is CallSignaling.CallEvent.CallEnded,
                    is CallSignaling.CallEvent.CallRejected -> {
                        getSystemService(NotificationManager::class.java).cancel(2)
                        setCallMode(false)
                        callWatchdogJob?.cancel()
                    }
                    else -> {}
                }
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

        `in`.chinmoydas.signal.utils.SafetySignaling.onSafeWalkTimeout = {
            sendPanicAlert()
            speakText("Emergency Alert! Safe Walk Timeout.")
            showIncomingCallNotification("SOS TRIGGERED: Safe Walk Timeout", isAlarm = true)
        }

        scope.launch {
            `in`.chinmoydas.signal.utils.SafetySignaling.safeWalkTimeRemaining.collect { time ->
                if (time != null && time > 0) {
                    if (!wakeLock.isHeld) wakeLock.acquire(65 * 60 * 1000L)
                } else {
                    if (wakeLock.isHeld && !isSending && !isReceiving) {
                        wakeLock.release()
                    }
                }
            }
        }

        registerReceiver(audioNoisyReceiver, IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY))

        multicastLock.setReferenceCounted(false)
        wakeLock.setReferenceCounted(false)
        wifiLock.setReferenceCounted(false)
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), networkCallback)
    }

    fun setCallMode(active: Boolean) {
        if (::audioRouter.isInitialized) {
            audioRouter.setCallMode(active)
        }
    }

    fun speakText(text: String) {
        if (isSilenced || isTheaterMode) {
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
                // [FIX] Use cached port if available
                var port = activePortCache[target] ?: UDP_PORT

                if (ip == null) {
                    val contact = repository.getAllContacts().find { it.name == target }
                    ip = contact?.ip
                }
                if (ip == "SERVER_LINK") ip = "signal.chinmoydas.in"

                if (!ip.isNullOrBlank()) {
                    if (ActivityCompat.checkSelfPermission(this@VoiceService, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        if (!fromVox) vibrate()
                        startTalk(listOf(ip), port) // Use dynamic port
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
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            if (CallEngine.isCallActive) return

        voxJob = scope.launch(Dispatchers.IO) {
            val bufferSize = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
            val buffer = ByteArray(bufferSize)

            try {
                recorder.startRecording()
                while (isActive && _voiceServiceState.value.isVoxEnabled && !isSending) {
                    val read = recorder.read(buffer, 0, bufferSize)
                    if (read > 0) voxHelper?.process(buffer)
                }
            } catch (e: Exception) { Log.e(tag, "VOX Monitor Error", e) }
            finally { try { recorder.stop(); recorder.release() } catch (e: Exception) {} }
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
                connectionManager.startHeartbeatLoop(currentChannel, currentChannelKey)
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

    private fun performGracefulShutdown() {
        scope.launch(Dispatchers.IO) {
            try {
                sendBroadcast(Intent("in.chinmoydas.signal.ACTION_EXIT").setPackage(packageName))
                val token = repository.getToken()
                if (!token.isNullOrBlank()) {
                    RetrofitClient.api.sendHeartbeat("Bearer $token", 0, "0.0.0.0", null, null, "offline")
                }
            } catch (e: Exception) { Log.e(tag, "Failed to send goodbye: ${e.message}") }
            delay(300)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaButtonReceiver.handleIntent(mediaSession, intent)
        val action = intent?.action

        if (action == "ACTION_PUNCH_BACK") {
            val ip = intent.getStringExtra("target_ip")
            val port = intent.getIntExtra("target_port", 50005)
            val sender = intent.getStringExtra("sender_name") ?: "Unknown"

            if (!ip.isNullOrBlank()) {
                scope.launch(Dispatchers.IO) {
                    if (!networkEngine.isBound()) {
                        // [FIX] Pass 0 or default since packet is null here
                        val networkStarted = networkEngine.start { packet ->
                            handleIncomingPacket(packet.data, packet.length, packet.address?.hostAddress ?: "", packet.port)
                        }
                        if (!networkStarted) return@launch
                        delay(150)
                    }
                    Log.d(tag, "BREACH: Punching hole to $ip:$port")

                    val punchPayload = PUNCH_PACKET.toByteArray()
                    val nameBytes = myUsername.toByteArray()
                    val packetSize = 1 + nameBytes.size + 4 + punchPayload.size
                    val buffer = ByteBuffer.allocate(packetSize)
                    buffer.put(nameBytes.size.toByte())
                    buffer.put(nameBytes)
                    buffer.putInt(0)
                    buffer.put(punchPayload)
                    val fullPacket = buffer.array()

                    networkEngine.sendBurst(fullPacket, listOf(ip), port, isMobileTarget = true, burstCount = 10)
                }
            }

            startForegroundServiceNotification("Checking Connection...")
            return START_STICKY
        }

        if (action == "TOGGLE_ECO") {
            val enabled = intent.getBooleanExtra("state", false)
            connectionManager.updateEcoMode(enabled)
            val msg = if(enabled) "Eco Mode: ON (Battery Saver)" else "Eco Mode: OFF (High Performance)"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            refreshNotification()
            return START_STICKY
        }

        when (action) {
            "STOP_SERVICE" -> { performGracefulShutdown(); return START_NOT_STICKY }
            "TOGGLE_MUTE" -> {
                isSilenced = !isSilenced
                _voiceServiceState.update { it.copy(isSilenced = isSilenced) }
                if (activeCalls.get() > 0) { if (isSilenced) audioRouter.abandonFocus() else acquireResources() }
                refreshNotification()
                return START_STICKY
            }
            "TOGGLE_HEADSET" -> {
                val newState = !_voiceServiceState.value.isHeadsetLinked
                isHeadsetLinked = newState
                _voiceServiceState.update { it.copy(isHeadsetLinked = newState) }
                mediaSession.isActive = newState
                val msg = if (newState) "Pocket Mode: ON" else "Pocket Mode: OFF"
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
                val requestedState = if (intent.hasExtra("state")) intent.getBooleanExtra("state", false) else !_voiceServiceState.value.isVoxEnabled
                _voiceServiceState.update { it.copy(isVoxEnabled = requestedState) }
                if (requestedState) {
                    startVoxMonitoring()
                    if (!intent.hasExtra("state")) Toast.makeText(this, "VOX: Auto-Transmit ON", Toast.LENGTH_SHORT).show()
                } else {
                    stopVoxMonitoring()
                    if (isSending) stopTalk()
                    if (!intent.hasExtra("state")) Toast.makeText(this, "VOX: OFF", Toast.LENGTH_SHORT).show()
                }
                refreshNotification()
                return START_STICKY
            }
            "TOGGLE_SENSOR" -> {
                val newState = !_voiceServiceState.value.isSensorEnabled
                _voiceServiceState.update { it.copy(isSensorEnabled = newState) }
                if (newState) { sensorHelper?.start(); Toast.makeText(this, "Shield: Crash Detection ON", Toast.LENGTH_SHORT).show() }
                else { sensorHelper?.stop(); Toast.makeText(this, "Shield: OFF", Toast.LENGTH_SHORT).show() }
                refreshNotification()
                return START_STICKY
            }
            "CMD_HEARTBEAT" -> {
                if (::connectionManager.isInitialized) connectionManager.triggerImmediateHeartbeat()
                return START_STICKY
            }
        }

        if (intent?.getBooleanExtra("is_cloud_wake", false) == true) {
            val wokenBy = intent.getStringExtra("woken_by")
            broadcastHello()
        }

        if (intent == null && _voiceServiceState.value.networkStatus != "Disconnected") return START_STICKY

        createNotificationChannel()
        try { startForegroundServiceNotification("Initializing Radio...") } catch (e: Exception) { stopSelf(); return START_NOT_STICKY }

        scope.launch(Dispatchers.IO) {
            try {
                if (!multicastLock.isHeld) multicastLock.acquire()
                audioEngine.startPlayback()

                val networkStarted = networkEngine.start { packet ->
                    handleIncomingPacket(packet.data, packet.length, packet.address?.hostAddress ?: "", packet.port)
                }

                if (!networkStarted) {
                    _voiceServiceState.update { it.copy(networkStatus = "Error: UDP Bind Failed") }
                    stopSelf()
                    return@launch
                }

                _voiceServiceState.update { it.copy(networkStatus = "Listening...") }
                updateNotification("Online & Ready", null)
                connectionManager.startHeartbeatLoop(currentChannel, currentChannelKey)
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
                // [ECO MODE FIX]
                // We do NOT stop the loop. We just poll slower.
                val pollInterval = if (::connectionManager.isInitialized && connectionManager.isEcoMode) 10_000L else 2000L

                delay(pollInterval)

                val token = repository.getToken()
                if (!token.isNullOrBlank() && token != "OFFLINE_TOKEN") {
                    try {
                        val response = RetrofitClient.api.checkSignals("Bearer $token")
                        val callers = response.callers
                        if (!callers.isNullOrEmpty()) {
                            if (isSending) {
                                // Interruption Logic
                                val interrupter = callers.firstOrNull { caller ->
                                    val ip = activeIpCache[caller]
                                    (ip != null && activeTargets.contains(ip)) || (repository.getTargetUser() == caller)
                                }
                                if (interrupter != null) {
                                    Log.w(tag, "Remote Hangup Received from $interrupter")
                                    stopTalk()
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(applicationContext, "Call ended by $interrupter", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) { }
                }
            }
        }
    }

    fun triggerHeartbeat(status: String = "online") {
        if (::connectionManager.isInitialized) connectionManager.triggerImmediateHeartbeat()
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

    fun broadcastHello() {
        scope.launch(Dispatchers.IO) {
            val prefs = getSharedPreferences("WalkiePrefs", Context.MODE_PRIVATE)
            val myToken = prefs.getString("my_fcm_token", "NO_TOKEN") ?: "NO_TOKEN"
            val payloadString = "HELLO:$myUsername:$myToken"
            val payloadBytes = payloadString.toByteArray(Charsets.UTF_8)
            val buf = ByteArray(1 + payloadBytes.size + 4)
            buf[0] = payloadBytes.size.toByte()
            System.arraycopy(payloadBytes, 0, buf, 1, payloadBytes.size)
            ByteBuffer.wrap(buf, 1 + payloadBytes.size, 4).putInt(0)
            networkEngine.send(buf, listOf("255.255.255.255"), UDP_PORT)
            val contacts = repository.getAllContacts()
            val knownIps = contacts.mapNotNull { it.ip }.filter { it != "SERVER_LINK" && it.isNotEmpty() }
            if (knownIps.isNotEmpty()) networkEngine.send(buf, knownIps, UDP_PORT)
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

    // [BREACH PROTOCOL] Unified Text Sender with Burst & Smart Wake
    fun sendTextMessage(targetIp: String, message: String) {
        scope.launch(Dispatchers.IO) {
            try {
                // 1. Prepare Payload
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

                // 2. Identify Network Type
                val isLocal = targetIp.startsWith("192.168.") || targetIp.startsWith("10.")
                val isMobile = !isLocal

                // 3. Smart Wake Check
                val lastSeen = _voiceServiceState.value.lastPingResponse
                val isCold = (System.currentTimeMillis() - lastReceiveTime) > WAKE_THRESHOLD_MS

                if (isMobile && isCold) {
                    val contact = repository.getAllContacts().find { it.ip == targetIp }
                    if (contact != null && contact.fcmToken.isNotEmpty()) {
                        repository.sendWakeSignal("Bearer ${repository.getToken()}", myUsername, contact.fcmToken)
                    }
                }

                // 4. BURST SEND
                // [FIX] Use activePortCache if available, otherwise UDP_PORT
                val targetPort = activePortCache[targetIp] ?: UDP_PORT
                networkEngine.sendBurst(packetData, listOf(targetIp), targetPort, isMobileTarget = isMobile, burstCount = 5)

            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun handleIncomingPacket(data: ByteArray, length: Int, senderIp: String, senderPort: Int) {
        // 1. SELF FILTER (IP Based)
        if (senderIp == myLocalIp) return
        if (length <= 5) return

        // 2. PARSE HEADER
        val nameLen = data[0].toInt() and 0xFF
        if (nameLen + 5 > length) return
        val senderName = String(data, 1, nameLen)

        // 3. PROTOCOL COMMANDS (Hello, Ping, Pong)
        if (senderName.startsWith("HELLO:")) {
            val parts = senderName.split(":")
            if (parts.size >= 3) {
                val remoteUser = parts[1]
                val remoteToken = parts[2]
                if (remoteUser.isNotEmpty() && remoteToken != "NO_TOKEN") {
                    scope.launch {
                        repository.updateContactToken(remoteUser, remoteToken)
                        repository.updateContactIp(remoteUser, senderIp)
                    }
                }
            }
            return
        }

        if (senderName == PING_SIGNAL) { sendPong(senderIp); return }
        if (senderName == PONG_SIGNAL) { _voiceServiceState.update { it.copy(lastPingResponse = System.currentTimeMillis()) }; return }

        // 4. END STREAM LOGIC
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

        // 5. SELF FILTER (Name Based) & BLOCKING
        // [CRITICAL FIX] Nuclear Self Filter - Trim and IgnoreCase
        if (senderName.trim().equals(myUsername.trim(), ignoreCase = true)) return
        if (blockedCache.contains(senderName)) return
        if (senderIp == ignoredSender) return

        val isPrincipal = principalCache.contains(senderName)

        if (currentSpeakerName != null && currentSpeakerName != senderName) {
            if (isPrincipal) {
                currentSpeakerName = senderName
            } else { return }
        } else { currentSpeakerName = senderName }

        // 6. SEQUENCE CHECK
        val seqOffset = 1 + nameLen
        val seqNum = ByteBuffer.wrap(data, seqOffset, 4).int
        val payloadOffset = seqOffset + 4
        val payloadLen = length - payloadOffset
        val lastSeq = sequenceMap.getOrPut(senderName) { -1 }

        if (seqNum > lastSeq || seqNum < lastSeq - 1000 || seqNum == 0) {
            // Update IP and PORT mapping
            if (!senderName.startsWith("group:")) {
                activeIpCache[senderName] = senderIp
                // [CRITICAL] Update Port Cache to punch back correctly
                if (senderPort > 1024) {
                    activePortCache[senderName] = senderPort
                }

                if (activeIpCache[senderName] != senderIp) {
                    scope.launch {
                        val rowsAffected = repository.updateContactIp(senderName, senderIp)
                        if (rowsAffected == 0) repository.saveContact(name = senderName, ip = senderIp, code = "", isPriority = false)
                    }
                }
            }

            sequenceMap[senderName] = seqNum
            lastIncomingIp = senderIp

            // Check PUNCH packet
            if (payloadLen == 9) {
                val checkPunch = String(data, payloadOffset, payloadLen)
                if (checkPunch == PUNCH_PACKET) return
            }

            // 7. DECRYPTION (Do this BEFORE deciding if it's Audio or Text)
            var payload = data.copyOfRange(payloadOffset, payloadOffset + payloadLen)
            val prefs = getSharedPreferences("WalkiePrefs", Context.MODE_PRIVATE)
            val isSecureMode = prefs.getBoolean("secure_mode", false)

            if (isSecureMode) {
                val isGroup = senderName.startsWith("group:")
                val secretKey = getDecryptionKey(isGroup)
                if (secretKey != null) {
                    var decrypted = CryptoEngine.decrypt(payload, seqNum, secretKey)
                    // Retry for G711 frame size mismatch
                    if (decrypted == null && payload.size > 656 && payload.size < 720) {
                        try {
                            val trimmedPayload = payload.copyOfRange(0, 656)
                            decrypted = CryptoEngine.decrypt(trimmedPayload, seqNum, secretKey)
                        } catch (e: Exception) { }
                    }
                    if (decrypted != null) payload = decrypted
                }
            }

            // 8. ROUTING LOGIC (The Fix)
            val isTextOrCmd = if (payload.size > 4) {
                val header = String(payload, 0, 4, Charsets.UTF_8)
                header == "TXT:" || header == "CMD:" || header == "LOC:"
            } else false

            if (isTextOrCmd) {
                // --- IT IS A MESSAGE ---
                try {
                    val textData = String(payload, Charsets.UTF_8)
                    if (textData.startsWith("TXT:")) {
                        val cleanMessage = textData.substring(4)

                        // Internal Signals
                        if (cleanMessage == "CMD:CALL:PING") { lastCallPacketTime = System.currentTimeMillis(); return }
                        if (cleanMessage.startsWith("ACK:")) {
                            val originalCmd = cleanMessage.removePrefix("ACK:")
                            val jobKey = senderIp + originalCmd
                            pendingAckJobs[jobKey]?.cancel()
                            pendingAckJobs.remove(jobKey)
                            return
                        }

                        // SOS
                        if (cleanMessage == "CMD:SOS") {
                            sendTextMessage(senderIp, "ACK:CMD:SOS")
                            showIncomingCallNotification(senderName, "SOS ALERT", isAlarm = true) // Specific SOS Alert
                            SafetySignaling.triggerSOS(senderName)
                            return
                        }

                        // UI Interceptor (Chat Dialogs)
                        if (packetInterceptor?.invoke(cleanMessage, senderIp) == true) return

                        // Remote Control Logic
                        if (cleanMessage.startsWith("CMD:REMOTE_")) {
                            handleRemoteCommand(cleanMessage, senderIp, senderName, isPrincipal, prefs)
                            return
                        }

                        // Normal Text Message
                        if (!cleanMessage.startsWith("CMD:") && !cleanMessage.startsWith("LOC:")) {
                            speakText(cleanMessage) // TTS
                            scope.launch {
                                val db = AppDatabase.getDatabase(applicationContext)
                                db.pagerDao().insert(PagerEntry(sender = senderName, type = "TEXT", content = cleanMessage, isRead = false))
                            }
                            updateNotification("Message from $senderName", cleanMessage)
                        }
                    }
                } catch (e: Exception) { }
                return
            }

            // --- IT IS AUDIO ---

            // [CRITICAL FIX] ALLOW AUDIO DURING CALL
            if (CallEngine.isCallActive) {
                lastCallPacketTime = System.currentTimeMillis()
                // Do NOT return here. Play the audio.
            } else if (isSending) {
                return // Half-duplex PTT only suppresses Rx while Tx
            }

            // 9. HANDLE SIGNAL
            if (!CallEngine.isCallActive) {
                handleIncomingSignal(senderName, isPrincipal)
            }

            // G711 Decode
            if (payload.size >= 640 && payload.size < 720) {
                try { payload = G711.decode(payload, 640) } catch (e: Exception) { }
            }

            // [FIX] Play Audio always if allowed
            if (!isSilenced || isPrincipal || CallEngine.isCallActive) {
                try { audioEngine.playPcmChunk(payload, seqNum) } catch (e: Exception) {}
            }
            if (isRecordingEnabled) {
                synchronized(bufferLock) { try { incomingBuffer.write(payload) } catch (t: Throwable) {} }
            }
        }
    }

    // Helper for Remote Commands (Cleaned up from main logic)
    private fun handleRemoteCommand(cmd: String, ip: String, name: String, isPrincipal: Boolean, prefs: android.content.SharedPreferences) {
        val isRemoteAllowed = prefs.getBoolean("allow_remote_control", false)
        if (!isRemoteAllowed) {
            val now = System.currentTimeMillis()
            if (now - lastRejectTime > 5000) {
                lastRejectTime = now
                sendTextMessage(ip, "Remote Control is DISABLED on this device.")
                updateNotification("🚫 Blocked CMD", "Ignored $name")
            }
            return
        }
        if (isPrincipal) {
            when (cmd) {
                "CMD:REMOTE_MIC_ON" -> {
                    updateNotification("🎙️ REMOTE ACTIVE", "Mic accessed by $name")
                    if (!_voiceServiceState.value.isVoxEnabled) toggleVox(true)
                }
                "CMD:REMOTE_STEALTH" -> {
                    updateNotification("🤫 STEALTH MODE", "Activated by $name")
                    toggleSpeaker(false)
                    isSilenced = true
                    isTheaterMode = true
                    _voiceServiceState.update { it.copy(isSilenced = true, isTheaterMode = true) }
                }
                "CMD:REMOTE_LOCATION" -> {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        val locMgr = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                        val loc = locMgr.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                            ?: locMgr.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                        if (loc != null) {
                            sendTextMessage(ip, "LOC:${loc.latitude},${loc.longitude}")
                            updateNotification("📍 LOCATION SHARED", "Sent to $name")
                        } else { sendTextMessage(ip, "ERROR: No GPS Signal found") }
                    }
                }
                "CMD:REMOTE_RESTORE" -> {
                    isSilenced = false
                    isTheaterMode = false
                    _voiceServiceState.update { it.copy(isSilenced = false, isTheaterMode = false) }
                    toggleSpeaker(true)
                    if (_voiceServiceState.value.isVoxEnabled) toggleVox(false)
                    speakText("Device restored to normal mode")
                    updateNotification("✅ RESTORED", "Reset by $name")
                    sendTextMessage(ip, "CONFIRM: Device Restored to Normal")
                }
            }
        } else {
            scope.launch { SafetySignaling.triggerSecurityAlert(name) }
            updateNotification("⚠️ SECURITY WARN", "Blocked CMD from $name")
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

    fun sendReliableCmd(targetIp: String, cmd: String) {
        if (pendingAckJobs.containsKey(targetIp + cmd)) return
        val job = scope.launch(Dispatchers.IO) {
            var attempts = 0
            while (isActive && attempts < 30) {
                sendTextMessage(targetIp, cmd)
                attempts++
                if (attempts % 5 == 0) Log.d(tag, "Retrying $cmd to $targetIp ($attempts/30)")
                delay(2000)
            }
            pendingAckJobs.remove(targetIp + cmd)
        }
        pendingAckJobs[targetIp + cmd] = job
    }

    // [BREACH PROTOCOL] Nuclear SOS with Burst Mode
    fun sendPanicAlert() {
        scope.launch(Dispatchers.IO) {
            val allContacts = repository.getAllContacts()
            val targets = ArrayList<String>()

            allContacts.forEach { contact ->
                if (!contact.ip.isNullOrBlank() && contact.ip != "SERVER_LINK") targets.add(contact.ip)
                if (contact.fcmToken.isNotEmpty()) {
                    repository.sendWakeSignal("Bearer ${repository.getToken()}", myUsername, contact.fcmToken)
                }
            }
            val currentTarget = activeIpCache[repository.getTargetUser()]
            if (!currentTarget.isNullOrBlank() && !targets.contains(currentTarget)) targets.add(currentTarget)

            if (targets.isEmpty()) targets.add("255.255.255.255")

            val sosPayload = "TXT:CMD:SOS".toByteArray()
            val nameBytes = myUsername.toByteArray()
            val buf = ByteBuffer.allocate(1 + nameBytes.size + 4 + sosPayload.size)
            buf.put(nameBytes.size.toByte())
            buf.put(nameBytes)
            buf.putInt(0)
            buf.put(sosPayload)
            val packet = buf.array()

            // Aggressive Flood (8 copies repeated 5 times)
            repeat(5) {
                targets.forEach { ip ->
                    val isMobile = !ip.startsWith("192.") && !ip.startsWith("10.")
                    val port = activePortCache[ip] ?: UDP_PORT
                    networkEngine.sendBurst(packet, listOf(ip), port, isMobileTarget = isMobile, burstCount = 8)
                }
                delay(1000)
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
            if (sentCount == 0) sendTextMessage("255.255.255.255", "LOC:$lat,$lon")
        }
    }

    fun startCallWatchdog() {
        callWatchdogJob?.cancel()
        lastCallPacketTime = System.currentTimeMillis()
        callWatchdogJob = scope.launch {
            Log.d(tag, "Call Watchdog Started")
            while (isActive) {
                val now = System.currentTimeMillis()
                if (now - lastCallPacketTime > 15000) {
                    Log.e(tag, "Watchdog: Peer dead. Force Hangup.")
                    `in`.chinmoydas.signal.utils.CallSignaling.endCall()
                    break
                }
                activeTargets.forEach { ip -> sendTextMessage(ip, "CMD:CALL:PING") }
                delay(2000)
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

    fun cancelSos() { _voiceServiceState.update { it.copy(isSosPending = false) } }
    fun toggleTheaterMode(enabled: Boolean) { toggleSpeaker(!enabled) }

    fun toggleVox(enabled: Boolean) {
        val intent = Intent(this, VoiceService::class.java).apply {
            action = "TOGGLE_VOX"
            putExtra("state", enabled)
        }
        startService(intent)
    }

    fun toggleSensor(enabled: Boolean) {
        val intent = Intent(this, VoiceService::class.java).apply { action = "TOGGLE_SENSOR" }
        startService(intent)
    }

    fun updateTalkTargets(newIps: List<String>, newPort: Int) {
        activeTargets = newIps
        lastPort = newPort
        Log.d(tag, "Updated Targets: $newIps on Port $newPort")
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startTalk(ips: List<String>, port: Int) {
        cleanupJob?.cancel()
        cleanupJob = null
        if (CallEngine.isCallActive) {
            // [FIX] Allow PTT during call? Usually no, but let's log it.
            Log.w(tag, "PTT Triggered during Call - Mixed Audio Mode")
        }
        if (isSending) return
        if (ips.isEmpty()) {
            speakText("Select a contact first")
            return
        }

        stopVoxMonitoring()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForegroundServiceNotification("Transmitting...")

        acquireResources(forceAudio = true)
        audioRouter.setCallMode(true)

        isSending = true
        currentSequenceNumber = 0
        activeTargets = ips
        lastPort = port
        updateState()

        val currentTime = System.currentTimeMillis()
        val prefs = getSharedPreferences("WalkiePrefs", Context.MODE_PRIVATE)
        dataSaverEnabled = prefs.getBoolean("data_saver", false)
        val isEco = prefs.getBoolean("eco_mode", false)

        val isTunnelOpen = (currentTime - lastReceiveTime) < WAKE_THRESHOLD_MS
        val recentlyWoken = (currentTime - lastWakeSentTime) < WAKE_DEBOUNCE_MS

        val shouldWake = if (isEco) {
            !recentlyWoken
        } else {
            !isTunnelOpen && !recentlyWoken
        }

        if (shouldWake) {
            lastWakeSentTime = currentTime
            scope.launch(Dispatchers.IO) {
                val target = repository.getTargetUser()
                val contact = repository.getAllContacts().find { it.name == target }
                if (contact != null && contact.fcmToken.isNotEmpty()) {
                    Log.d(tag, "Sending Wake Signal to $target (Tunnel Closed/Eco)")
                    repository.sendWakeSignal("Bearer ${repository.getToken()}", myUsername, contact.fcmToken)
                }
            }
        }

        val shouldCompress = if (ips.size > 1) true else !isOnWifi && dataSaverEnabled
        val isSecureMode = prefs.getBoolean("secure_mode", false)
        val secretKey = if (isSecureMode) getEncryptionKey() else null

        val isLocal = ips.all { it.startsWith("192.") || it.startsWith("10.") }

        vibrate()

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

            val burstCount = if (currentSequenceNumber < 5) 2 else 1
            // [FIX] Use dynamic lastPort from cache if available
            networkEngine.sendBurst(sendBuf, activeTargets, lastPort, isMobileTarget = !isLocal, burstCount = burstCount)
        }
    }

    fun stopTalk() {
        if (!isSending) return
        isSending = false
        updateState()
        audioEngine.stopRecording()
        audioRouter.setCallMode(false)

        cleanupJob = scope.launch {
            val endNameBytes = END_STREAM_SIGNAL.toByteArray()
            val buf = ByteArray(1 + endNameBytes.size + 4)
            buf[0] = endNameBytes.size.toByte()
            System.arraycopy(endNameBytes, 0, buf, 1, endNameBytes.size)
            repeat(3) {
                if (isActive) {
                    networkEngine.send(buf, activeTargets, lastPort)
                    delay(20)
                }
            }
            releaseResourcesIfNeeded()
            startForegroundServiceNotification("Listening...")
        }
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
            _voiceServiceState.update { it.copy(incomingCall = callerName, incomingIp = lastIncomingIp) }
            val status = if (isPriority) "⚠️ PRIORITY: $callerName" else if (isSilenced) "Missed: $callerName" else "Incoming: $callerName"
            updateNotification(status, callerName)
            if (!isSilenced || isPriority) vibrate()
        }
        lastReceiveTime = currentTime
        resetJob?.cancel()
        resetJob = scope.launch {
            delay(5000)
            if (isReceiving) stopReceiving()
        }
    }

    fun stopReceiving() {
        ignoredSender = lastIncomingIp
        isReceiving = false
        resetJob?.cancel()
        _voiceServiceState.update { it.copy(incomingCall = null, networkStatus = "Listening...") }
        updateNotification("Listening...", null)
        getSystemService(NotificationManager::class.java).cancel(2)
        releaseResourcesIfNeeded()
        scope.launch {
            delay(IGNORE_SENDER_DELAY)
            ignoredSender = null
        }
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

    private fun showIncomingCallNotification(callerName: String, msgBody: String = "Incoming Signal", isAlarm: Boolean = false) {
        val channelId = "cd_signal_call_alert_v2"
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Incoming Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Wakes device for Calls and SOS"
                setSound(Settings.System.DEFAULT_RINGTONE_URI, null)
                enableVibration(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
            }
            manager.createNotificationChannel(channel)
        }
        val fullScreenIntent = Intent(this, MainActivity::class.java).apply {
            action = "INCOMING_CALL"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_USER_ACTION
            putExtra("auto_connect_channel", callerName)
            putExtra("is_call", true)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(this, 119, fullScreenIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher_foreground)
            .setContentTitle(if(isAlarm) "SOS ALERT" else "INCOMING SIGNAL")
            .setContentText("$msgBody from $callerName")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(if(isAlarm) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setAutoCancel(true)
            .setOngoing(true)
            .setVibrate(longArrayOf(0, 500, 500, 500))
            .addAction(android.R.drawable.ic_menu_call, "OPEN", fullScreenPendingIntent)
            .build()
        manager.notify(2, notification)
    }

    override fun onDestroy() {
        if (tts != null) { tts?.stop(); tts?.shutdown(); tts = null }
        audioRouter.shutdown()
        CallEngine.stopCall()
        if (::connectionManager.isInitialized) connectionManager.stop()
        sensorHelper?.stop()
        stopVoxMonitoring()
        audioEngine.shutdown()
        networkEngine.stop()
        localLinkManager?.stop()
        mediaSession.release()
        (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.unregisterNetworkCallback(networkCallback)
        try { unregisterReceiver(signalReceiver) } catch (e: Exception) {}
        try { unregisterReceiver(audioNoisyReceiver) } catch (e: Exception) {}
        if (wakeLock.isHeld) wakeLock.release()
        if (multicastLock.isHeld) multicastLock.release()
        if (wifiLock.isHeld) wifiLock.release()
        scope.cancel()
        super.onDestroy()
    }
}