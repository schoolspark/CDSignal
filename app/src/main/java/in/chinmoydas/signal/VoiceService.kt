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
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.RingtoneManager
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
import `in`.chinmoydas.signal.utils.CallStatus
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

    // [SERVERLESS] Unified Port
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
    private var ringTimeoutJob: Job? = null
    private var callWatchdogJob: Job? = null
    private var ringtonePlayer: MediaPlayer? = null
    @Volatile private var lastCallPacketTime: Long = 0L
    @Volatile private var isRinging = false

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

    // [FIX] Port Cache for Breach Protocol
    private val activeIpCache = ConcurrentHashMap<String, String>()

    // [MOTHERSHIP UPGRADE] Standardized Reflector Subdomain
    private var isPremiumUser: Boolean = false
    private var activeReflectorIp: String = "signal.schoolspark.in"
    private val blockedCache = ConcurrentHashMap.newKeySet<String>()
    private val principalCache = ConcurrentHashMap.newKeySet<String>()
    private val pendingAckJobs = ConcurrentHashMap<String, Job>()

    @Volatile private var ignoredSender: String? = null

    private val incomingBuffer = java.io.ByteArrayOutputStream(512 * 1024)
    private val bufferLock = Any()
    var isRecordingEnabled = true

    @Suppress("DEPRECATION")
    private val vibrator by lazy { getSystemService(Context.VIBRATOR_SERVICE) as Vibrator }

    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

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
                val username = intent.getStringExtra("username") ?: ""
                if (ip != null && cmd != null) {
                    sendTextMessage(ip, username, cmd)
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

    // [ENTERPRISE UPGRADE] Network Callback with TURN Auth Sync
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            super.onCapabilitiesChanged(network, networkCapabilities)
            val isWifiNow = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)

            if (isOnWifi != isWifiNow) {
                isOnWifi = isWifiNow
                if (isOnWifi) {
                    Log.d(tag, "Switched to Wi-Fi: Starting LAN Discovery")
                    localLinkManager?.startDiscovery()
                    localLinkManager?.startAdvertising(myUsername, UDP_PORT)
                } else {
                    Log.d(tag, "Left Wi-Fi: Stopping LAN Discovery")
                    localLinkManager?.stopDiscovery()
                    localLinkManager?.stopAdvertising()
                }
            }
        }

        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            scope.launch(Dispatchers.IO) {
                val newIp = getLocalIpAddress()
                if (newIp.isNotEmpty() && newIp != myLocalIp) {
                    Log.i(tag, "Network Handover: $myLocalIp -> $newIp")
                    myLocalIp = newIp

                    _voiceServiceState.update { it.copy(networkStatus = "Listening...") }
                    sendTextMessage("255.255.255.255", "", "CMD:I_MOVED")

                    val currentTarget = repository.getTargetUser()
                    val targetIp = activeIpCache[currentTarget]
                    if (!targetIp.isNullOrBlank()) sendPing(targetIp)

                    broadcastHello()
                    if (isOnWifi) localLinkManager?.startAdvertising(myUsername, UDP_PORT)

                    // [ENTERPRISE] Re-fetch credentials & sync heartbeat on network change
                    refreshTurnCredentials()
                    connectionManager.triggerImmediateHeartbeat()
                } else if (myLocalIp.isEmpty()) {
                    myLocalIp = newIp
                    refreshTurnCredentials()
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

        createNotificationChannel()

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

        val prefs = getSharedPreferences("WalkiePrefs", Context.MODE_PRIVATE)
        isPremiumUser = prefs.getBoolean("is_premium", false)
        activeReflectorIp = prefs.getString("reflector_ip", "signal.schoolspark.in") ?: "signal.schoolspark.in"

        // [FIXED] Initialize with username for Mothership Registration
        networkEngine.setPremiumStatus(isPremiumUser, activeReflectorIp, repository.myUsername.value)

        networkEngine.onSignalPacket = { data, senderIp ->
            handleIncomingPacket(data, data.size, senderIp)
        }

        CallEngine.initialize(networkEngine)
        connectionManager = ConnectionManager(repository, networkEngine)

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

                        _voiceServiceState.update { it.copy(incomingCall = name, incomingIp = event.ip) }
                        startRinging()
                        showIncomingCallNotification(name, "Incoming Voice Call", isAlarm = false)
                    }
                    is CallSignaling.CallEvent.CallConnected -> {
                        getSystemService(NotificationManager::class.java).cancel(2)
                        stopRinging()
                        if (Build.VERSION.SDK_INT >= 26) {
                            vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
                        }
                        if (isSending) stopTalk()
                        if (_voiceServiceState.value.isVoxEnabled) toggleVox(false)

                        setCallMode(true)

                        val targetIp = _voiceServiceState.value.incomingIp
                            ?: activeIpCache[repository.getTargetUser()]
                            ?: repository.findContactByIp(repository.getTargetUser())?.ip

                        if (!targetIp.isNullOrBlank()) {
                            val key = getEncryptionKey()
                            CallEngine.startCall(targetIp, key)
                            startCallWatchdog()
                        } else {
                            speakText("Connection Error")
                            `in`.chinmoydas.signal.utils.CallSignaling.endCall()
                        }
                    }
                    is CallSignaling.CallEvent.CallEnded,
                    is CallSignaling.CallEvent.CallRejected -> {
                        getSystemService(NotificationManager::class.java).cancel(2)
                        stopRinging()
                        setCallMode(false)
                        callWatchdogJob?.cancel()
                        CallEngine.stopCall()
                        _voiceServiceState.update { it.copy(incomingCall = null, incomingIp = null) }
                        if (Build.VERSION.SDK_INT >= 26) {
                            vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
                        }
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

        scope.launch {
            `in`.chinmoydas.signal.utils.CallSignaling.commandEvents.collect { (targetIp, cmd) ->
                val name = activeIpCache.entries.find { it.value == targetIp }?.key ?: ""
                sendReliableCmd(targetIp, name, cmd)
            }
        }

        registerReceiver(audioNoisyReceiver, IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY))

        multicastLock.setReferenceCounted(false)
        wakeLock.setReferenceCounted(false)
        wifiLock.setReferenceCounted(false)
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), networkCallback)

        try {
            if (localLinkManager == null) {
                localLinkManager = LocalLinkManager(
                    context = this,
                    onServiceFound = { name, ip, port ->
                        val ipString = ip.hostAddress
                        if (ipString != null && ipString != myLocalIp) {
                            val lastKnownIp = activeIpCache[name]
                            if (lastKnownIp != ipString) {
                                Log.i(tag, "LAN PEER UPDATED: $name at $ipString")
                                activeIpCache[name] = ipString
                                scope.launch {
                                    repository.updateContactIp(name, ipString)
                                    if (repository.getTargetUser() == name) {
                                        connectionManager.startHeartbeatLoop(currentChannel, currentChannelKey, ipString)
                                        sendPing(ipString)
                                        withContext(Dispatchers.Main) {
                                            updateNotification("LAN: Connected to $name", null)
                                        }
                                    }
                                }
                            }
                        }
                    },
                    onServiceLost = { name ->
                        Log.i(tag, "LAN PEER LOST: $name")
                    }
                )
            }
            if (isOnWifi) localLinkManager?.startAdvertising(myUsername, UDP_PORT)
        } catch (e: Exception) { Log.e(tag, "LocalLink Init Failed", e) }
    }

    // [ENTERPRISE UPGRADE] Fetch CoTURN Credentials Dynamically
    private fun refreshTurnCredentials() {
        scope.launch(Dispatchers.IO) {
            try {
                val token = repository.getToken()
                if (token.isNullOrBlank() || token == "OFFLINE_TOKEN") return@launch

                // Fetch dynamic HMAC-SHA1 credentials from your PHP backend
                val response = RetrofitClient.api.getSignalCreds("Bearer $token")
                if (response.status == "success" && !response.iceServers.isNullOrEmpty()) {
                    val firstServer = response.iceServers[0]
                    val turnUsername = firstServer.username ?: ""
                    val turnPassword = firstServer.credential ?: ""

                    // Authorize NetworkEngine to use the relay
                    networkEngine.setTurnCredentials(turnUsername, turnPassword)
                    Log.i(tag, "Enterprise CoTURN Auth Synchronized: $turnUsername")
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to fetch Mothership credentials: ${e.message}")
            }
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

        val stateBuilder = android.support.v4.media.session.PlaybackStateCompat.Builder()
            .setActions(android.support.v4.media.session.PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    android.support.v4.media.session.PlaybackStateCompat.ACTION_PLAY or
                    android.support.v4.media.session.PlaybackStateCompat.ACTION_PAUSE)
            .setState(android.support.v4.media.session.PlaybackStateCompat.STATE_PLAYING, 0L, 1.0f)
        mediaSession.setPlaybackState(stateBuilder.build())

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

                val ipsToCall = mutableListOf<String>()

                if (target.equals("Everyone", ignoreCase = true) || target.startsWith("group:", ignoreCase = true)) {
                    ipsToCall.add("255.255.255.255")
                    val activePeers = activeIpCache.values.filter { it != "SERVER_LINK" && it.isNotEmpty() }
                    ipsToCall.addAll(activePeers)
                } else {
                    var ip = activeIpCache[target]
                    if (ip == null) {
                        val contact = repository.getAllContacts().find { it.name == target }
                        ip = contact?.ip
                    }
                    if (ip == "SERVER_LINK") ip = "signal.chinmoydas.in"
                    if (!ip.isNullOrBlank()) ipsToCall.add(ip)
                }

                if (ipsToCall.isNotEmpty()) {
                    if (ActivityCompat.checkSelfPermission(this@VoiceService, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        if (!fromVox) vibrate()
                        // [FIXED] Pass the target username down to startTalk for Mothership routing
                        startTalk(ipsToCall.distinct(), UDP_PORT, target)
                    }
                } else {
                    speakText("Select a valid contact")
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
        scope.launch { repository.myUsername.collect { myUsername = it; if(isOnWifi) localLinkManager?.startAdvertising(it, UDP_PORT) } }
        scope.launch { repository.myPairingCode.collect { myIdentityKey = it } }
        scope.launch {
            repository.targetUser.collect { target ->
                var targetIp: String? = null

                if (target.startsWith("group:", ignoreCase = true)) {
                    val raw = target.substringAfter(":")
                    if (raw.contains(":")) {
                        val parts = raw.split(":", limit = 2)
                        currentChannel = parts[0]
                    } else { currentChannel = raw }
                } else {
                    currentChannel = null
                    val contact = repository.getAllContacts().find { it.name == target }
                    targetIp = activeIpCache[target] ?: contact?.ip

                    if (!targetIp.isNullOrBlank() && targetIp != "SERVER_LINK") {
                        sendPing(targetIp)
                    }
                }
                connectionManager.startHeartbeatLoop(currentChannel, currentChannelKey, targetIp)
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
                val target = repository.getTargetUser()
                val ip = activeIpCache[target]
                connectionManager.startHeartbeatLoop(currentChannel, key, ip)
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
            val sender = intent.getStringExtra("sender_name") ?: "Unknown"

            if (!ip.isNullOrBlank()) {
                scope.launch(Dispatchers.IO) {
                    if (!networkEngine.isBound()) {
                        val networkStarted = networkEngine.start()
                        if (!networkStarted) return@launch
                        delay(150)
                    }
                    Log.d(tag, "BREACH: Punching hole to $ip:$UDP_PORT")

                    val punchPayload = PUNCH_PACKET.toByteArray()
                    val nameBytes = myUsername.toByteArray()
                    val packetSize = 1 + nameBytes.size + 4 + punchPayload.size
                    val buffer = ByteBuffer.allocate(packetSize)
                    buffer.put(nameBytes.size.toByte())
                    buffer.put(nameBytes)
                    buffer.putInt(0)
                    buffer.put(punchPayload)
                    val fullPacket = buffer.array()

                    // [FIXED] Pass the target username for Mothership routing
                    networkEngine.sendBurst(
                        NetworkEngine.TYPE_TEXT_SIGNAL,
                        fullPacket,
                        ip,
                        UDP_PORT,
                        sender,
                        10
                    )
                }
            }

            startForegroundServiceNotification("Checking Connection...")
            return START_STICKY
        }

        if (action == "TOGGLE_ECO") {
            val enabled = intent.getBooleanExtra("state", false)
            connectionManager.updateEcoMode(enabled)
            val msg =
                if (enabled) "Eco Mode: ON (Battery Saver)" else "Eco Mode: OFF (High Performance)"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            refreshNotification()
            return START_STICKY
        }

        when (action) {
            "STOP_SERVICE" -> {
                performGracefulShutdown(); return START_NOT_STICKY
            }

            "TOGGLE_MUTE" -> {
                isSilenced = !isSilenced
                _voiceServiceState.update { it.copy(isSilenced = isSilenced) }
                if (activeCalls.get() > 0) {
                    if (isSilenced) audioRouter.abandonFocus() else acquireResources()
                }
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
                val v =
                    if (Build.VERSION.SDK_INT >= 31) (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator else vibrator
                v.vibrate(VibrationEffect.createOneShot(100, 100))
                refreshNotification()
                return START_STICKY
            }

            "TOGGLE_VOX" -> {
                val requestedState = if (intent.hasExtra("state")) intent.getBooleanExtra(
                    "state",
                    false
                ) else !_voiceServiceState.value.isVoxEnabled
                _voiceServiceState.update { it.copy(isVoxEnabled = requestedState) }
                if (requestedState) {
                    startVoxMonitoring()
                    if (!intent.hasExtra("state")) Toast.makeText(
                        this,
                        "VOX: Auto-Transmit ON",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    stopVoxMonitoring()
                    if (isSending) stopTalk()
                    if (!intent.hasExtra("state")) Toast.makeText(
                        this,
                        "VOX: OFF",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                refreshNotification()
                return START_STICKY
            }

            "TOGGLE_SENSOR" -> {
                val newState = !_voiceServiceState.value.isSensorEnabled
                _voiceServiceState.update { it.copy(isSensorEnabled = newState) }
                if (newState) {
                    sensorHelper?.start(); Toast.makeText(
                        this,
                        "Shield: Crash Detection ON",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    sensorHelper?.stop(); Toast.makeText(this, "Shield: OFF", Toast.LENGTH_SHORT)
                        .show()
                }
                refreshNotification()
                return START_STICKY
            }

            "CMD_HEARTBEAT" -> {
                if (::connectionManager.isInitialized) connectionManager.triggerImmediateHeartbeat()
                return START_STICKY
            }
        }

        if (intent?.getBooleanExtra("is_cloud_wake", false) == true) {
            broadcastHello()
        }

        if (intent == null && _voiceServiceState.value.networkStatus != "Disconnected") return START_STICKY

        try {
            startForegroundServiceNotification("Initializing Radio...")
        } catch (e: Exception) {
            stopSelf(); return START_NOT_STICKY
        }

        scope.launch(Dispatchers.IO) {
            try {
                if (!multicastLock.isHeld) multicastLock.acquire()
                audioEngine.startPlayback()

                // 1. Start the UDP Socket
                val networkStarted = networkEngine.start()

                if (!networkStarted) {
                    _voiceServiceState.update { it.copy(networkStatus = "Error: UDP Bind Failed") }
                    stopSelf()
                    return@launch
                }

                // 2. [CRITICAL FIX] Register with the Oracle Reflector Port 443
                // Now that the socket is bound, this PING will actually leave the device.
                networkEngine.registerWithMothership()

                // 3. Fetch CoTURN/TURN credentials for the Hybrid mode
                refreshTurnCredentials()

                // 4. Update UI and start background maintenance
                _voiceServiceState.update { it.copy(networkStatus = "Listening...") }
                updateNotification("Online & Ready", null)

                val targetIp = activeIpCache[repository.getTargetUser()]
                connectionManager.startHeartbeatLoop(currentChannel, currentChannelKey, targetIp)

                startSignalLoop()

            } catch (e: Exception) {
                Log.e(tag, "Startup Error: ${e.message}")
            }
        }

        // LAN Advertising (Runs on Main thread, safe for mDNS/LocalLink)
        try {
            if (localLinkManager == null) localLinkManager =
                LocalLinkManager(this, { _, _, _ -> }, { _ -> })
            localLinkManager?.startAdvertising(myUsername, UDP_PORT)
        } catch (e: Exception) {
            Log.e(tag, "LocalLink Advertise Failed: ${e.message}")
        }

        return START_STICKY
    }

    private fun startSignalLoop() {
        signalingJob?.cancel()
        signalingJob = scope.launch {
            while (isActive) {
                val pollInterval = if (::connectionManager.isInitialized && connectionManager.isEcoMode) 10_000L else 2000L
                delay(pollInterval)

                val token = repository.getToken()
                if (!token.isNullOrBlank() && token != "OFFLINE_TOKEN") {
                    try {
                        val response = RetrofitClient.api.checkSignals("Bearer $token")
                        val callers = response.callers
                        if (!callers.isNullOrEmpty()) {
                            if (isSending) {
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
            val targetName = activeIpCache.entries.find { it.value == ip }?.key ?: ""
            val signalBytes = PING_SIGNAL.toByteArray()
            val buf = ByteArray(1 + signalBytes.size + 4)
            buf[0] = signalBytes.size.toByte()
            System.arraycopy(signalBytes, 0, buf, 1, signalBytes.size)
            repeat(3) {
                networkEngine.send(NetworkEngine.TYPE_TEXT_SIGNAL, buf, ip, port, targetName)
                delay(20)
            }
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

            networkEngine.send(NetworkEngine.TYPE_TEXT_SIGNAL, buf, listOf("255.255.255.255"), UDP_PORT)

            val contacts = repository.getAllContacts()
            val knownIps = contacts.mapNotNull { it.ip }.filter { it != "SERVER_LINK" && it.isNotEmpty() }
            if (knownIps.isNotEmpty()) {
                knownIps.forEach { targetIp ->
                    val targetName = activeIpCache.entries.find { it.value == targetIp }?.key ?: ""
                    networkEngine.send(NetworkEngine.TYPE_TEXT_SIGNAL, buf, targetIp, UDP_PORT, targetName)
                }
            }
        }
    }

    private fun sendPong(ip: String) {
        scope.launch {
            val targetName = activeIpCache.entries.find { it.value == ip }?.key ?: ""
            val signalBytes = PONG_SIGNAL.toByteArray()
            val buf = ByteArray(1 + signalBytes.size + 4)
            buf[0] = signalBytes.size.toByte()
            System.arraycopy(signalBytes, 0, buf, 1, signalBytes.size)
            networkEngine.send(NetworkEngine.TYPE_TEXT_SIGNAL, buf, ip, UDP_PORT, targetName)
        }
    }

    // [FIXED] Updated signature to require targetUsername for Mothership Routing
    fun sendTextMessage(targetIp: String, targetUsername: String = "", message: String) {
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
                    CryptoEngine.encrypt(rawPayload, secretKeySpec) ?: rawPayload
                } else { rawPayload }

                val nameBytes = myUsername.toByteArray(Charsets.UTF_8)
                val packetSize = 1 + nameBytes.size + 4 + finalPayload.size
                val buffer = ByteBuffer.allocate(packetSize)
                buffer.put(nameBytes.size.toByte())
                buffer.put(nameBytes)
                buffer.putInt(seqNum)
                buffer.put(finalPayload)
                val packetData = buffer.array()

                val isLocal = targetIp.startsWith("192.168.") || targetIp.startsWith("10.") || targetIp.contains("%")
                val isMobile = !isLocal

                val currentTime = System.currentTimeMillis()
                val isCold = (currentTime - lastReceiveTime) > WAKE_THRESHOLD_MS
                val recentlyWoken = (currentTime - lastWakeSentTime) < WAKE_DEBOUNCE_MS

                if (isMobile && isCold && !recentlyWoken) {
                    lastWakeSentTime = currentTime
                    val contact = repository.getAllContacts().find { it.ip == targetIp }
                    if (contact != null && contact.fcmToken.isNotEmpty()) {
                        Log.d(tag, "Sending Cloud Wake to ${contact.name}")
                        repository.sendWakeSignal("Bearer ${repository.getToken()}", myUsername, contact.fcmToken)
                    }
                }

                // [FIXED] Send through the new burst system
                networkEngine.sendBurst(NetworkEngine.TYPE_TEXT_SIGNAL, packetData, targetIp, UDP_PORT, targetUsername, 5)

            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun handleIncomingPacket(data: ByteArray, length: Int, senderIp: String) {
        if (::connectionManager.isInitialized) connectionManager.notifyNetworkActivity()

        if (senderIp == myLocalIp || length <= 5) return

        val nameLen = data[0].toInt() and 0xFF
        if (nameLen + 5 > length) return
        val senderName = String(data, 1, nameLen)

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
                if (!CallEngine.isCallActive) updateNotification("Listening...", null)
                releaseResourcesIfNeeded()
            }
            return
        }

        if (senderName.trim().equals(myUsername.trim(), ignoreCase = true)) return
        if (blockedCache.contains(senderName)) return
        if (senderIp == ignoredSender) return

        val isPrincipal = principalCache.contains(senderName)
        if (currentSpeakerName != null && currentSpeakerName != senderName) {
            if (isPrincipal) { currentSpeakerName = senderName } else { return }
        } else { currentSpeakerName = senderName }

        val seqOffset = 1 + nameLen
        val seqNum = ByteBuffer.wrap(data, seqOffset, 4).int
        val payloadOffset = seqOffset + 4
        val payloadLen = length - payloadOffset
        val lastSeq = sequenceMap.getOrPut(senderName) { -1 }

        if (seqNum > lastSeq || seqNum < lastSeq - 1000 || seqNum == 0) {
            if (!senderName.startsWith("group:") && activeIpCache[senderName] != senderIp) {
                activeIpCache[senderName] = senderIp
                scope.launch {
                    val rowsAffected = repository.updateContactIp(senderName, senderIp)
                    if (rowsAffected == 0) repository.saveContact(name = senderName, ip = senderIp, code = "", isPriority = false)
                }
            }

            sequenceMap[senderName] = seqNum
            lastIncomingIp = senderIp

            if (payloadLen == 9 && String(data, payloadOffset, payloadLen) == PUNCH_PACKET) return

            var payload = data.copyOfRange(payloadOffset, payloadOffset + payloadLen)
            val prefs = getSharedPreferences("WalkiePrefs", Context.MODE_PRIVATE)
            val isSecureMode = prefs.getBoolean("secure_mode", false)

            if (isSecureMode) {
                val isGroup = senderName.startsWith("group:")
                val secretKey = getDecryptionKey(isGroup)
                if (secretKey != null) {
                    val decrypted = CryptoEngine.decrypt(payload, secretKey)
                    if (decrypted != null) payload = decrypted
                }
            }

            val isTextHeader = if (payload.size > 4) String(payload, 0, 4, Charsets.UTF_8) else ""
            if (isTextHeader == "TXT:" || isTextHeader == "CMD:" || isTextHeader == "LOC:") {
                try {
                    val textData = String(payload, Charsets.UTF_8)
                    if (textData.startsWith("TXT:")) {
                        val cleanMessage = textData.substring(4)
                        if (cleanMessage == "CMD:CALL:PING") { lastCallPacketTime = System.currentTimeMillis(); return }
                        if (cleanMessage.startsWith("ACK:")) {
                            val originalCmd = cleanMessage.removePrefix("ACK:")
                            val jobKey = senderIp + originalCmd
                            pendingAckJobs[jobKey]?.cancel()
                            pendingAckJobs.remove(jobKey)
                            return
                        }
                        if (cleanMessage == "CMD:SOS") {
                            sendTextMessage(senderIp, senderName, "ACK:CMD:SOS")
                            showIncomingCallNotification(senderName, "SOS ALERT", isAlarm = true)
                            SafetySignaling.triggerSOS(senderName)
                            return
                        }
                        if (cleanMessage.startsWith("CMD:CALL:")) {
                            sendTextMessage(senderIp, senderName, "ACK:$cleanMessage")

                            when (cleanMessage) {
                                CallSignaling.CMD_REQ -> CallSignaling.onIncomingCall(senderIp)
                                CallSignaling.CMD_ACC -> CallSignaling.onCallAccepted()
                                CallSignaling.CMD_REJ -> CallSignaling.onCallRejected()
                                CallSignaling.CMD_END -> CallSignaling.onCallEnded()
                                CallSignaling.CMD_BUSY -> CallSignaling.onPeerBusy()
                            }
                            return
                        }
                        if (cleanMessage.startsWith("CMD:REMOTE_")) {
                            handleRemoteCommand(cleanMessage, senderIp, senderName, isPrincipal, prefs)
                            return
                        }
                        if (packetInterceptor?.invoke(cleanMessage, senderIp) == true) return

                        if (!cleanMessage.startsWith("CMD:") && !cleanMessage.startsWith("LOC:")) {
                            if (!CallEngine.isCallActive) speakText(cleanMessage)
                            scope.launch {
                                val db = AppDatabase.getDatabase(applicationContext)
                                db.pagerDao().insert(PagerEntry(sender = senderName, type = "TEXT", content = cleanMessage, isRead = false))
                            }
                            if (CallEngine.isCallActive) {
                                lastStatusMessage = "Msg: $senderName"
                                refreshNotification()
                            } else {
                                updateNotification("Msg from $senderName: $cleanMessage", senderName)
                            }
                        }
                    }
                } catch (e: Exception) { }
                return
            }

            val isInVoipCall = CallEngine.isCallActive ||
                    `in`.chinmoydas.signal.utils.CallSignaling.callStatus.value != `in`.chinmoydas.signal.utils.CallStatus.Idle

            if (!isInVoipCall) {
                handleIncomingSignal(senderName, isPrincipal)
            } else {
                if (!isReceiving) {
                    isReceiving = true
                    currentSpeakerName = senderName
                }
                resetJob?.cancel()
                resetJob = scope.launch {
                    delay(10_000)
                    if (isReceiving) {
                        isReceiving = false
                        if (isRecordingEnabled) {
                            val dataToSave = synchronized(bufferLock) { val bytes = incomingBuffer.toByteArray(); incomingBuffer.reset(); bytes }
                            if (dataToSave.isNotEmpty()) saveIncomingMessage(senderName, dataToSave)
                        }
                    }
                }
            }

            if (payload.size >= 640 && payload.size < 720) {
                try { payload = G711.decode(payload, 640) } catch (e: Exception) { }
            }

            if (!isInVoipCall && (!isSilenced || isPrincipal)) {
                try { audioEngine.playPcmChunk(payload, seqNum) } catch (e: Exception) {}
            }

            if (isRecordingEnabled) {
                synchronized(bufferLock) { try { incomingBuffer.write(payload) } catch (t: Throwable) {} }
            }
        }
    }

    private fun handleRemoteCommand(cmd: String, ip: String, name: String, isPrincipal: Boolean, prefs: android.content.SharedPreferences) {
        val isRemoteAllowed = prefs.getBoolean("allow_remote_control", false)
        if (!isRemoteAllowed) {
            val now = System.currentTimeMillis()
            if (now - lastRejectTime > 5000) {
                lastRejectTime = now
                sendTextMessage(ip, name, "Remote Control is DISABLED on this device.")
                updateNotification("🚫 Blocked CMD from $name", name)
            }
            return
        }
        if (isPrincipal) {
            when (cmd) {
                "CMD:REMOTE_MIC_ON" -> {
                    updateNotification("🎙️ REMOTE ACTIVE by $name", name)
                    if (!_voiceServiceState.value.isVoxEnabled) toggleVox(true)
                }
                "CMD:REMOTE_STEALTH" -> {
                    updateNotification("🤫 STEALTH MODE by $name", name)
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
                            sendTextMessage(ip, name, "LOC:${loc.latitude},${loc.longitude}")
                            updateNotification("📍 LOCATION SHARED to $name", name)
                        } else { sendTextMessage(ip, name, "ERROR: No GPS Signal found") }
                    }
                }
                "CMD:REMOTE_RESTORE" -> {
                    isSilenced = false
                    isTheaterMode = false
                    _voiceServiceState.update { it.copy(isSilenced = false, isTheaterMode = false) }
                    toggleSpeaker(true)
                    if (_voiceServiceState.value.isVoxEnabled) toggleVox(false)
                    speakText("Device restored to normal mode")
                    updateNotification("✅ RESTORED by $name", name)
                    sendTextMessage(ip, name, "CONFIRM: Device Restored to Normal")
                }
            }
        } else {
            scope.launch { SafetySignaling.triggerSecurityAlert(name) }
            updateNotification("⚠️ SECURITY WARN from $name", name)
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
                updateNotification("New Voice Message from $sender", sender)
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

    // [FIXED] Include Username for reliable commands over Mothership
    fun sendReliableCmd(targetIp: String, targetUsername: String = "", cmd: String) {
        val cmdKey = targetIp + cmd
        if (pendingAckJobs.containsKey(cmdKey)) return

        if (cmd.contains("CMD:CALL:END")) {
            val reqKey = targetIp + "CMD:CALL:REQ"
            pendingAckJobs[reqKey]?.cancel()
            pendingAckJobs.remove(reqKey)
        }

        val job = scope.launch(Dispatchers.IO) {
            var attempts = 0
            while (isActive && attempts < 15) {
                sendTextMessage(targetIp, targetUsername, cmd)
                attempts++
                if (attempts % 5 == 0) Log.d(tag, "Retrying $cmd to $targetIp ($attempts/15)")
                delay(2000)
            }
            pendingAckJobs.remove(cmdKey)
        }
        pendingAckJobs[cmdKey] = job
    }

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

            repeat(5) {
                // [FIXED] Loop targets and supply standard fallback name to Mothership
                targets.forEach { targetIp ->
                    networkEngine.sendBurst(NetworkEngine.TYPE_TEXT_SIGNAL, packet, targetIp, UDP_PORT, "Everyone", 8)
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
                    sendTextMessage(contact.ip, contact.name, "LOC:$lat,$lon")
                    sentCount++
                    delay(10)
                }
            }
            val currentTarget = activeIpCache[repository.getTargetUser()]
            if (!currentTarget.isNullOrBlank() && allContacts.none { it.ip == currentTarget }) {
                val targetName = activeIpCache.entries.find { it.value == currentTarget }?.key ?: ""
                sendTextMessage(currentTarget, targetName, "LOC:$lat,$lon")
                sentCount++
            }
            if (sentCount == 0) sendTextMessage("255.255.255.255", "", "LOC:$lat,$lon")
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
                activeTargets.forEach { ip ->
                    val targetName = activeIpCache.entries.find { it.value == ip }?.key ?: ""
                    sendTextMessage(ip, targetName, "CMD:CALL:PING")
                }
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

    // [FIXED] Require Target Username for Premium Mothership audio routing
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startTalk(ips: List<String>, port: Int, targetUsername: String = "") {
        cleanupJob?.cancel()
        cleanupJob = null

        if (CallEngine.isCallActive ||
            CallSignaling.callStatus.value != CallStatus.Idle) {
            Toast.makeText(this, "Line Busy: Call in progress", Toast.LENGTH_SHORT).show()
            return
        }

        if (isSending || ips.isEmpty()) return

        stopVoxMonitoring()
        acquireResources(forceAudio = true)
        audioRouter.setCallMode(true)

        isSending = true
        currentSequenceNumber = 0
        activeTargets = ips
        lastPort = port
        updateState()

        val currentTime = System.currentTimeMillis()

        val isTunnelWarm = (currentTime - lastReceiveTime) < WAKE_THRESHOLD_MS
        val recentlyWoken = (currentTime - lastWakeSentTime) < WAKE_DEBOUNCE_MS

        if (!isTunnelWarm && !recentlyWoken) {
            lastWakeSentTime = currentTime
            scope.launch(Dispatchers.IO) {
                val target = repository.getTargetUser()
                val contact = repository.getAllContacts().find { it.name == target }
                if (contact != null && contact.fcmToken.isNotEmpty()) {
                    repository.sendWakeSignal("Bearer ${repository.getToken()}", myUsername, contact.fcmToken)
                }
            }
        }

        vibrate()

        val prefs = getSharedPreferences("WalkiePrefs", Context.MODE_PRIVATE)
        val shouldCompress = if (ips.size > 1) true else !isOnWifi
        val secretKey = if (prefs.getBoolean("secure_mode", false)) getEncryptionKey() else null

        audioEngine.startRecording(shouldCompress) { rawBuffer ->
            if (_voiceServiceState.value.isVoxEnabled) voxHelper?.keepAlive()

            val payload = if (secretKey != null) {
                CryptoEngine.encrypt(rawBuffer, secretKey) ?: rawBuffer
            } else { rawBuffer }

            val nameBytes = myUsername.toByteArray()
            val sendBuf = ByteBuffer.allocate(1 + nameBytes.size + 4 + payload.size)
                .put(nameBytes.size.toByte())
                .put(nameBytes)
                .putInt(currentSequenceNumber++)
                .put(payload)
                .array()

            val burst = if (currentSequenceNumber < 5) 2 else 1

            // [FIXED] Loop over targets and inject targetUsername to sendBurst
            ips.forEach { targetIp ->
                networkEngine.sendBurst(NetworkEngine.TYPE_PTT_AUDIO, sendBuf, targetIp, lastPort, targetUsername, burst)
            }
        }
    }

    fun stopTalk() {
        if (!isSending) return
        isSending = false
        updateState()
        audioEngine.stopRecording()
        audioRouter.setCallMode(false)

        cleanupJob = scope.launch {
            val targetName = repository.getTargetUser()
            val endNameBytes = END_STREAM_SIGNAL.toByteArray()
            val buf = ByteArray(1 + endNameBytes.size + 4)
            buf[0] = endNameBytes.size.toByte()
            System.arraycopy(endNameBytes, 0, buf, 1, endNameBytes.size)
            repeat(3) {
                if (isActive) {
                    activeTargets.forEach { targetIp ->
                        networkEngine.send(NetworkEngine.TYPE_TEXT_SIGNAL, buf, targetIp, lastPort, targetName)
                    }
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
        if (audioManager.ringerMode == AudioManager.RINGER_MODE_SILENT) return

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

        val isCallIdle = `in`.chinmoydas.signal.utils.CallSignaling.callStatus.value == `in`.chinmoydas.signal.utils.CallStatus.Idle

        if (!isReceiving || (currentTime - lastReceiveTime > 3000) || isPriority) {
            acquireResources()
            isReceiving = true
            _voiceServiceState.update { it.copy(incomingCall = callerName, incomingIp = lastIncomingIp) }
            val status = if (isPriority) "⚠️ PRIORITY: $callerName" else if (isSilenced) "Missed: $callerName" else "Incoming: $callerName"
            updateNotification(status, callerName)

            if ((!isSilenced || isPriority) && isCallIdle) {
                vibrate()
            }
        }
        lastReceiveTime = currentTime
        resetJob?.cancel()

        resetJob = scope.launch {
            delay(10_000)
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
            val fgsTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            startForeground(1, notification, fgsTypes)
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
        val manager = getSystemService(NotificationManager::class.java)

        val channelId = if (isAlarm) "cd_signal_sos_alert" else "cd_signal_voip_silent"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (manager.getNotificationChannel(channelId) == null) {
                val channelName = if (isAlarm) "SOS Emergency Alerts" else "Incoming Voice Calls"
                val importance = NotificationManager.IMPORTANCE_HIGH

                val channel = NotificationChannel(channelId, channelName, importance).apply {
                    description = if (isAlarm) "Loud Emergency Alerts" else "Silent visual alerts for calls"
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    setBypassDnd(true)

                    if (isAlarm) {
                        val alarmSound = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                        val alarmAttributes = android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                        setSound(alarmSound, alarmAttributes)
                        enableVibration(true)
                        vibrationPattern = longArrayOf(0, 1000, 500, 1000, 500, 1000)
                    } else {
                        setSound(null, null)
                        enableVibration(false)
                    }
                }
                manager.createNotificationChannel(channel)
            }
        }

        val fullScreenIntent = Intent(this, MainActivity::class.java).apply {
            action = "INCOMING_CALL"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_USER_ACTION
            putExtra("auto_connect_channel", callerName)
            putExtra("is_call", !isAlarm)
        }

        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            if (isAlarm) 911 else 119,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher_foreground)
            .setContentTitle(if (isAlarm) "🚨 SOS ALERT 🚨" else "Incoming Call")
            .setContentText(if (isAlarm) "EMERGENCY: $callerName needs help!" else "$callerName is calling...")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(if (isAlarm) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setAutoCancel(true)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_call, if (isAlarm) "VIEW ALERT" else "ANSWER", fullScreenPendingIntent)

        if (!isAlarm) {
            notificationBuilder.setSilent(true)
        }

        val notificationId = if (isAlarm) 3 else 2
        manager.notify(notificationId, notificationBuilder.build())
    }

    private fun setCallMode(isActive: Boolean) {
        audioRouter.setVoipMode(isActive)
        if (!isActive) {
            audioRouter.setSpeakerphone(true)
        }
    }

    private fun startRinging() {
        if (isRinging) return
        isRinging = true

        ringTimeoutJob?.cancel()

        try {
            val ringerMode = audioManager.ringerMode

            if (ringerMode == AudioManager.RINGER_MODE_NORMAL) {
                if (ringtonePlayer == null) {
                    val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    ringtonePlayer = MediaPlayer.create(this, uri).apply {
                        isLooping = true
                        start()
                    }
                }
            }

            if (ringerMode != AudioManager.RINGER_MODE_SILENT) {
                val pattern = longArrayOf(0, 1000, 1000)
                if (Build.VERSION.SDK_INT >= 26) {
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(pattern, 0)
                }
            }

            ringTimeoutJob = scope.launch {
                delay(45_000)
                Log.w(tag, "Ring Timeout: Force stopping vibration")
                stopRinging()
                _voiceServiceState.update { it.copy(incomingCall = null, incomingIp = null) }
                `in`.chinmoydas.signal.utils.CallSignaling.onCallEnded()
            }

        } catch (e: Exception) { Log.e(tag, "Ringing Failed", e) }
    }

    private fun stopRinging() {
        isRinging = false
        ringTimeoutJob?.cancel()
        ringTimeoutJob = null

        try {
            ringtonePlayer?.stop()
            ringtonePlayer?.release()
        } catch (e: Exception) {}
        ringtonePlayer = null
        vibrator.cancel()
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