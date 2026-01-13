package `in`.chinmoydas.signal.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // System Check State
    var deviceInfo by remember { mutableStateOf(getDeviceDetails(context)) }
    var permStatus by remember { mutableStateOf(checkPermissions(context)) }
    var batteryStatus by remember { mutableStateOf(checkBattery(context)) }
    var netStatus by remember { mutableStateOf("Checking...") }
    var localIp by remember { mutableStateOf("...") }
    var serverStatus by remember { mutableStateOf("Checking...") }
    var storageStatus by remember { mutableStateOf(checkStorage(context)) }
    var audioFxStatus by remember { mutableStateOf(checkAudioEffects()) }

    // Audio Output State
    var audioRouteInfo by remember { mutableStateOf(getAudioRoute(context)) }

    // Mic Test State
    var isRecording by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var micStatusText by remember { mutableStateOf("Tap to Test Mic") }

    val recorder = remember { mutableStateOf<MediaRecorder?>(null) }
    val player = remember { mutableStateOf<MediaPlayer?>(null) }
    val testFile = File(context.cacheDir, "audio_test.3gp")

    // Safety: Reset Audio Mode when leaving this screen
    DisposableEffect(Unit) {
        onDispose {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.mode = AudioManager.MODE_NORMAL
            am.isSpeakerphoneOn = false
        }
    }

    LaunchedEffect(Unit) {
        netStatus = checkNetwork(context)
        localIp = getLocalIpAddress()
        serverStatus = checkServer()
        audioRouteInfo = getAudioRoute(context)
    }

    fun copyReport() {
        val report = """
            CD SIGNAL DIAGNOSTIC REPORT
            ---------------------------
            Device: $deviceInfo
            Permissions: $permStatus
            Battery: $batteryStatus
            Network: $netStatus
            Local IP: $localIp
            Server: $serverStatus
            Storage: $storageStatus
            Audio HW: $audioFxStatus
            Audio Route: ${audioRouteInfo.displayText.replace("\n", " | ")}
        """.trimIndent()

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Diagnostic Report", report)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Report copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("System Check") },
                navigationIcon = { IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { copyReport() }) { Icon(Icons.Default.ContentCopy, "Copy Report") }
                    IconButton(onClick = {
                        permStatus = checkPermissions(context)
                        batteryStatus = checkBattery(context)
                        storageStatus = checkStorage(context)
                        audioFxStatus = checkAudioEffects()
                        audioRouteInfo = getAudioRoute(context)
                        localIp = getLocalIpAddress()
                        scope.launch {
                            netStatus = "Checking..."
                            serverStatus = "Checking..."
                            netStatus = checkNetwork(context)
                            serverStatus = checkServer()
                        }
                    }) { Icon(Icons.Default.Refresh, "Refresh") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {

            // 0. DEVICE INFO (New)
            Text(deviceInfo, style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(bottom = 16.dp))

            // 1. SYSTEM HEALTH
            Text("System Health", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            DiagnosticRow("Permissions", permStatus)
            DiagnosticRow("Battery Opt", batteryStatus)
            DiagnosticRow("Network", netStatus)
            DiagnosticRow("Local IP", localIp) // NEW: Shows IP address
            DiagnosticRow("Server API", serverStatus) // NEW: Shows Latency
            DiagnosticRow("Storage", storageStatus)
            DiagnosticRow("Audio HW", audioFxStatus)

            HorizontalDivider(Modifier.padding(vertical = 24.dp))

            // 2. AUDIO OUTPUT CHECK
            Text("Audio Output Check", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(audioRouteInfo.displayText, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Button(onClick = {
                            toggleSpeakerTest(context, true)
                            audioRouteInfo = getAudioRoute(context)
                        }) {
                            Icon(Icons.AutoMirrored.Filled.VolumeUp, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Force Speaker")
                        }
                        OutlinedButton(onClick = {
                            toggleSpeakerTest(context, false)
                            audioRouteInfo = getAudioRoute(context)
                        }) {
                            Icon(Icons.Default.Hearing, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Earpiece/BT")
                        }
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 24.dp))

            // 3. MIC LOOPBACK TEST
            Text("Mic & Speaker Loopback", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text("Record 3s then play back.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Spacer(Modifier.height(16.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = if(isRecording) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(24.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(micStatusText, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(16.dp))

                    if (!isRecording && !isPlaying) {
                        Button(onClick = {
                            scope.launch {
                                isRecording = true
                                micStatusText = "Recording..."
                                startRecording(context, testFile, recorder)
                                delay(3000)
                                stopRecording(recorder)
                                isRecording = false
                                micStatusText = "Playing..."
                                isPlaying = true
                                startPlaying(testFile, player) {
                                    isPlaying = false
                                    micStatusText = "Done"
                                }
                            }
                        }) {
                            Icon(Icons.Default.Mic, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Start Check")
                        }
                    } else if (isRecording) {
                        CircularProgressIndicator(color = Color.Red)
                    } else if (isPlaying) {
                        Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

// --- AUDIO HELPERS ---

data class AudioRouteState(val displayText: String)

fun getAudioRoute(context: Context): AudioRouteState {
    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val sb = StringBuilder()

    var isHeadset = false
    var isBt = false

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        for (device in devices) {
            when (device.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> {
                    sb.append("🎧 Wired Headset Detected\n")
                    isHeadset = true
                }
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> {
                    sb.append("📶 Bluetooth Device Detected\n")
                    isBt = true
                }
            }
        }
    } else {
        @Suppress("DEPRECATION")
        if (am.isWiredHeadsetOn) { sb.append("🎧 Wired Headset Detected\n"); isHeadset = true }
        @Suppress("DEPRECATION")
        if (am.isBluetoothScoOn || am.isBluetoothA2dpOn) { sb.append("📶 Bluetooth Detected\n"); isBt = true }
    }

    val active = if (am.isSpeakerphoneOn) "📢 Loudspeaker (Forced On)"
    else if (isBt) "📶 Bluetooth (Should be Active)"
    else if (isHeadset) "🎧 Wired Headset (Should be Active)"
    else "📞 Phone Earpiece (Default)"

    sb.append("\nCurrently Routing To:\n$active")
    return AudioRouteState(sb.toString())
}

fun toggleSpeakerTest(context: Context, on: Boolean) {
    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    am.mode = AudioManager.MODE_IN_COMMUNICATION
    am.isSpeakerphoneOn = on
    if (!on) {
        try { am.startBluetoothSco(); am.isBluetoothScoOn = true } catch (e: Exception) {}
    }
}

// --- DIAGNOSTIC HELPERS ---
@Composable
fun DiagnosticRow(label: String, status: String) {
    val isPass = status.startsWith("OK") || status.startsWith("Yes") || status.startsWith("Connected") || status.contains("✓")
    val icon = if (isPass) Icons.Default.CheckCircle else if (status.contains("Checking")) Icons.Default.Refresh else Icons.Default.Warning
    val color = if (isPass) Color(0xFF4CAF50) else if (status.contains("Checking")) Color.Gray else MaterialTheme.colorScheme.error

    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp)).padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontWeight = FontWeight.Medium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(status, color = color, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.width(8.dp))
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        }
    }
}

fun getDeviceDetails(context: Context): String {
    return try {
        val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}) | v${pInfo.versionName}"
    } catch (e: Exception) {
        "Unknown Device"
    }
}

fun checkPermissions(context: Context): String {
    val mic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val notif = if (Build.VERSION.SDK_INT >= 33) ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED else true
    val nearby = if (Build.VERSION.SDK_INT >= 33) ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED else true

    return when {
        !mic -> "Fail (Mic Denied)"
        !notif -> "Fail (Notif Denied)"
        !nearby -> "Fail (Nearby Denied)"
        else -> "OK (All Granted)"
    }
}

fun checkBattery(context: Context): String {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val isIgnored = pm.isIgnoringBatteryOptimizations(context.packageName)
    return if (isIgnored) "OK (Unrestricted)" else "Warning (Restricted)"
}

fun checkAudioEffects(): String {
    val aec = AcousticEchoCanceler.isAvailable()
    val ns = NoiseSuppressor.isAvailable()
    val agc = AutomaticGainControl.isAvailable()
    return "AEC:${if(aec) "✓" else "✗"} NS:${if(ns) "✓" else "✗"} AGC:${if(agc) "✓" else "✗"}"
}

fun checkStorage(context: Context): String {
    return try {
        val file = File(context.cacheDir, "test_write.tmp")
        file.writeText("test")
        val success = file.exists()
        file.delete()
        if (success) "OK (Writable)" else "Fail (Read-Only)"
    } catch (e: Exception) {
        "Fail (Error)"
    }
}

fun checkNetwork(context: Context): String {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val net = cm.activeNetwork ?: return "Disconnected"
    val caps = cm.getNetworkCapabilities(net) ?: return "No Data"
    return if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) "Connected (WiFi)" else "Connected (Mobile Data)"
}

fun getLocalIpAddress(): String {
    try {
        val en = NetworkInterface.getNetworkInterfaces()
        while (en.hasMoreElements()) {
            val intf = en.nextElement()
            val enumIpAddr = intf.inetAddresses
            while (enumIpAddr.hasMoreElements()) {
                val inetAddress = enumIpAddr.nextElement()
                if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                    return inetAddress.hostAddress ?: "Unknown"
                }
            }
        }
    } catch (ex: Exception) { }
    return "Not Found"
}

suspend fun checkServer(): String = withContext(Dispatchers.IO) {
    try {
        val start = System.currentTimeMillis()
        val url = URL("https://signal.chinmoydas.in/api/auth.php")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 3000
        conn.connect()
        val duration = System.currentTimeMillis() - start
        val code = conn.responseCode
        if (code in 200..405) "OK ($duration ms)" else "Fail (Error $code)"
    } catch (e: Exception) {
        "Fail (Offline)"
    }
}

fun startRecording(context: Context, file: File, recorderState: MutableState<MediaRecorder?>) {
    @Suppress("DEPRECATION")
    val recorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
    recorder.apply {
        setAudioSource(MediaRecorder.AudioSource.MIC)
        setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
        setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
        setOutputFile(file.absolutePath)
        prepare()
        start()
    }
    recorderState.value = recorder
}

fun stopRecording(recorderState: MutableState<MediaRecorder?>) {
    try {
        recorderState.value?.stop()
        recorderState.value?.release()
    } catch (e: Exception) {}
    recorderState.value = null
}

fun startPlaying(file: File, playerState: MutableState<MediaPlayer?>, onComplete: () -> Unit) {
    val player = MediaPlayer().apply {
        setDataSource(file.absolutePath)
        prepare()
        start()
        setOnCompletionListener {
            it.release()
            playerState.value = null
            onComplete()
        }
    }
    playerState.value = player
}