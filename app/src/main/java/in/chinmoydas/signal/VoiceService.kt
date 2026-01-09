package `in`.chinmoydas.signal

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import `in`.chinmoydas.signal.data.MainRepository
import `in`.chinmoydas.signal.utils.AudioEngine
import `in`.chinmoydas.signal.utils.LocalLinkManager
import `in`.chinmoydas.signal.utils.NetworkEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class VoiceServiceState(
    val incomingCall: String? = null,
    val incomingIp: String? = null,
    val networkStatus: String = "Listening..."
)

class VoiceService : Service() {
    private val tag = "VoiceService"
    private val binder = LocalBinder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _voiceServiceState = MutableStateFlow(VoiceServiceState())
    val voiceServiceState = _voiceServiceState.asStateFlow()

    private lateinit var audioEngine: AudioEngine
    private lateinit var networkEngine: NetworkEngine
    private lateinit var repository: MainRepository

    private val END_STREAM_SIGNAL = "__END_TX__"
    private val UDP_PORT = 50005
    private val IGNORE_SENDER_DELAY = 5000L

    @Volatile private var isReceiving = false
    @Volatile private var isSending = false
    private var lastReceiveTime = 0L
    private var resetJob: Job? = null

    @Volatile var lastIncomingIp: String? = null
    @Volatile private var myUsername: String = "User"
    @Volatile private var myLocalIp: String = ""

    // --- KEY VARIABLES ---
    @Volatile private var currentChannel: String? = null
    @Volatile private var currentChannelKey: String? = null

    @Volatile var isSilenced = false
    private val sequenceMap = ConcurrentHashMap<String, Int>()

    private val blockedCache = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var ignoredSender: String? = null

    @Volatile private var activeTargets: List<String> = emptyList()
    @Volatile private var lastPort: Int = UDP_PORT

    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    @Suppress("DEPRECATION")
    private val vibrator by lazy { getSystemService(Context.VIBRATOR_SERVICE) as Vibrator }

    private var activeFocusRequest: AudioFocusRequest? = null

    private val wakeLock by lazy { (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CDSignal:VoiceLock") }
    private val multicastLock by lazy { (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).createMulticastLock("CDSignal:MulticastLock") }
    private val activeCalls = AtomicInteger(0)

    private var heartbeatJob: Job? = null
    private var currentSequenceNumber = 0

    private var localLinkManager: LocalLinkManager? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
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

        multicastLock.setReferenceCounted(false)
        wakeLock.setReferenceCounted(false)

        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), networkCallback)
    }

    private fun observeRepositoryFlows() {
        scope.launch { repository.myUsername.collect { myUsername = it; localLinkManager?.startAdvertising(it, UDP_PORT) } }

        scope.launch {
            repository.targetUser.collect { target ->
                if (target.startsWith("group:", ignoreCase = true)) {
                    val raw = target.substringAfter(":")
                    if (raw.contains(":")) {
                        val parts = raw.split(":", limit = 2)
                        currentChannel = parts[0]
                    } else {
                        currentChannel = raw
                    }
                } else {
                    currentChannel = null
                    currentChannelKey = null
                }
                triggerHeartbeat()
            }
        }

        scope.launch {
            repository.configTrigger.collect {
                val blocked = repository.getBlockedContacts()
                blockedCache.clear()
                blockedCache.addAll(blocked.map { it.name })
            }
        }

        scope.launch {
            repository.channelKey.collect { key ->
                currentChannelKey = key
                triggerHeartbeat()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_SERVICE") {
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        startForegroundServiceNotification("Listening...")

        if (!multicastLock.isHeld) multicastLock.acquire()

        audioEngine.startPlayback()
        val networkStarted = networkEngine.start { packet -> handleIncomingPacket(packet.data, packet.length, packet.address?.hostAddress ?: "") }
        if (!networkStarted) {
            _voiceServiceState.value = VoiceServiceState(networkStatus = "Error: Network failed to start")
            stopSelf()
            return START_NOT_STICKY
        }

        if (localLinkManager == null) localLinkManager = LocalLinkManager(this, { _, _, _ -> }, { _ -> })
        localLinkManager?.startAdvertising(myUsername, UDP_PORT)

        // REMOVED: toggleSpeaker(true) -> This was the bug causing WhatsApp to fail
        startHeartbeatLoop()
        return START_STICKY
    }

    private fun handleIncomingPacket(data: ByteArray, length: Int, senderIp: String) {
        if (isSending || senderIp == myLocalIp || length <= 5) return

        val nameLen = data[0].toInt() and 0xFF
        if (nameLen + 5 > length) return

        val senderName = String(data, 1, nameLen)

        if (senderName == END_STREAM_SIGNAL) {
            if (isReceiving) {
                isReceiving = false
                resetJob?.cancel()
                _voiceServiceState.value = VoiceServiceState(networkStatus = "Listening...")
                updateNotification("Listening...", null)
                releaseResourcesIfNeeded()
            }
            return
        }

        if (senderName.trim().equals(myUsername.trim(), ignoreCase = true) || blockedCache.contains(senderName) || senderIp == ignoredSender) return

        val seqOffset = 1 + nameLen
        val seqNum = ByteBuffer.wrap(data, seqOffset, 4).int
        val payloadOffset = seqOffset + 4
        val payloadLen = length - payloadOffset

        val lastSeq = sequenceMap.getOrPut(senderName) { -1 }

        if (seqNum > lastSeq || seqNum < lastSeq - 1000 || seqNum == 0) {
            if (seqNum == 0 || seqNum < lastSeq - 1000) {
                scope.launch { repository.insertLog(senderName, true) }
                sequenceMap[senderName] = -1
            }
            sequenceMap[senderName] = seqNum
            lastIncomingIp = senderIp
            handleIncomingSignal(senderName)

            if (payloadLen > 0 && !isSilenced) {
                val rawAudio = data.copyOfRange(payloadOffset, payloadOffset + payloadLen)
                audioEngine.writeAudio(seqNum, rawAudio)
            }
        }
    }

    private fun handleIncomingSignal(callerName: String) {
        val currentTime = System.currentTimeMillis()
        if (!isReceiving || (currentTime - lastReceiveTime > 3000)) {
            acquireResources()
            isReceiving = true
            val status = if(isSilenced) "Missed: $callerName" else "Incoming: $callerName"
            updateNotification(status, callerName)
            _voiceServiceState.value = VoiceServiceState(incomingCall = callerName, incomingIp = lastIncomingIp, networkStatus = status)
            vibrate()
        }
        lastReceiveTime = currentTime
        resetJob?.cancel()
        resetJob = scope.launch {
            delay(2500)
            if (isReceiving) {
                isReceiving = false
                releaseResourcesIfNeeded()
                updateNotification("Listening...", null)
                _voiceServiceState.value = VoiceServiceState(networkStatus = "Listening...")
            }
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startTalk(ips: List<String>, port: Int) {
        if (isSending) return
        acquireResources()
        isSending = true
        currentSequenceNumber = 0
        activeTargets = ips
        lastPort = port

        scope.launch { repository.insertLog(if (ips.size > 1) "Group Broadcast" else "PTT Call", false) }

        val nameBytes = myUsername.toByteArray()
        val headerLen = 1 + nameBytes.size + 4

        audioEngine.startRecording { opusBuffer ->
            val payload = opusBuffer
            val sendBuf = ByteArray(headerLen + payload.size)
            sendBuf[0] = nameBytes.size.toByte()
            System.arraycopy(nameBytes, 0, sendBuf, 1, nameBytes.size)
            ByteBuffer.wrap(sendBuf, 1 + nameBytes.size, 4).putInt(currentSequenceNumber++)
            System.arraycopy(payload, 0, sendBuf, headerLen, payload.size)

            networkEngine.send(sendBuf, activeTargets, port)
        }
    }

    fun updateTalkTargets(newIps: List<String>) {
        activeTargets = newIps
    }

    fun stopTalk() {
        if (!isSending) return
        isSending = false
        audioEngine.stopRecording()

        scope.launch {
            val endNameBytes = END_STREAM_SIGNAL.toByteArray()
            val buf = ByteArray(1 + endNameBytes.size + 4)
            buf[0] = endNameBytes.size.toByte()
            System.arraycopy(endNameBytes, 0, buf, 1, endNameBytes.size)

            repeat(3) {
                networkEngine.send(buf, activeTargets, lastPort)
                delay(20)
            }
            releaseResourcesIfNeeded()
        }
    }

    fun stopReceiving() {
        ignoredSender = lastIncomingIp
        isReceiving = false
        resetJob?.cancel()
        _voiceServiceState.value = VoiceServiceState(networkStatus = "Listening...")
        updateNotification("Listening...", null)
        releaseResourcesIfNeeded()
        scope.launch { delay(IGNORE_SENDER_DELAY); ignoredSender = null }
    }

    @Suppress("DEPRECATION")
    fun toggleSpeaker(on: Boolean) {
        // Only modify if we are actively in a call
        if (activeCalls.get() > 0) {
            audioManager.isSpeakerphoneOn = on
            if (!on) {
                try {
                    audioManager.startBluetoothSco()
                    audioManager.isBluetoothScoOn = true
                } catch (e: Exception) {
                    Log.w(tag, "Could not start Bluetooth SCO", e)
                }
            }
        }
    }

    private fun acquireResources() {
        if (activeCalls.getAndIncrement() == 0) {
            // --- FIX: Switch to Call Mode HERE, not in onStart ---
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = true // Default to speaker for PTT

            if (!wakeLock.isHeld) wakeLock.acquire(10 * 60 * 1000L)
            requestFocus()
        }
    }

    private fun releaseResourcesIfNeeded() {
        if (activeCalls.decrementAndGet() == 0) {
            // --- FIX: Release Call Mode immediately ---
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = false

            if (wakeLock.isHeld) wakeLock.release()
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

    private fun abandonFocus() {
        activeFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        activeFocusRequest = null
    }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        try {
            val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE) else null
            if (effect != null) vibrator.vibrate(effect) else vibrator.vibrate(100)
        } catch (e: Exception) {
            Log.w(tag, "Vibration failed", e)
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            for (ni in java.net.NetworkInterface.getNetworkInterfaces()) {
                for (addr in ni.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) return addr.hostAddress ?: ""
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to get local IP address", e)
        }
        return ""
    }

    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                triggerHeartbeat()
                delay(30000)
            }
        }
    }

    fun triggerHeartbeat(status: String = "online") {
        scope.launch {
            val token = repository.getToken()
            if (!token.isNullOrBlank() && token != "OFFLINE_TOKEN") {
                try {
                    RetrofitClient.api.sendHeartbeat("Bearer $token", UDP_PORT, getLocalIpAddress(), currentChannel, currentChannelKey, status)
                } catch (e: Exception) {
                    Log.e(tag, "Heartbeat failed", e)
                }
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
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, "VoiceChannel")
            .setContentTitle("CD Signal").setContentText(status).setSmallIcon(R.mipmap.ic_launcher_foreground).setContentIntent(pendingIntent).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notification)
        }
    }

    private fun updateNotification(text: String, channelName: String?) {
        val intent = Intent(this, MainActivity::class.java).apply { if (channelName != null) putExtra("auto_connect_channel", channelName) }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, "VoiceChannel")
            .setContentTitle("CD Signal").setContentText(text).setSmallIcon(R.mipmap.ic_launcher_foreground).setContentIntent(pendingIntent).setOnlyAlertOnce(true).setOngoing(true).build()
        getSystemService(NotificationManager::class.java).notify(1, notification)
    }

    override fun onDestroy() {
        scope.launch { triggerHeartbeat("offline") }

        audioEngine.shutdown()
        networkEngine.stop()
        localLinkManager?.stop()

        (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.unregisterNetworkCallback(networkCallback)

        if (wakeLock.isHeld) wakeLock.release()
        if (multicastLock.isHeld) multicastLock.release()

        scope.cancel()
        super.onDestroy()
    }
}