package `in`.chinmoydas.signal.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import `in`.chinmoydas.signal.VoiceService
import `in`.chinmoydas.signal.VoiceServiceState
import `in`.chinmoydas.signal.data.PagerEntry
import `in`.chinmoydas.signal.viewmodel.ConnectionStatus
import `in`.chinmoydas.signal.viewmodel.UiState
import `in`.chinmoydas.signal.viewmodel.WalkieViewModel
import `in`.chinmoydas.signal.utils.CallSignaling // Required for FAB
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.delay

@OptIn(ExperimentalComposeUiApi::class)
@SuppressLint("MissingPermission")
@Composable
fun TalkTab(
    modifier: Modifier = Modifier,
    viewModel: WalkieViewModel,
    service: VoiceService?,
    onPermissionsGranted: () -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val pagerEntries by viewModel.pagerEntries.collectAsState()

    // Secure Mode Check
    val isSecureMode = context.getSharedPreferences("WalkiePrefs", Context.MODE_PRIVATE)
        .getBoolean("secure_mode", false)

    var showTextDialog by remember { mutableStateOf(false) }
    var textMessage by remember { mutableStateOf("") }

    // PTT State for Animation
    var isPressed by remember { mutableStateOf(false) }

    // Location Client
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    val serviceState by service?.voiceServiceState?.collectAsState(initial = VoiceServiceState())
        ?: remember { mutableStateOf(VoiceServiceState()) }

    // Permissions
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (it.getOrDefault(Manifest.permission.RECORD_AUDIO, false)) onPermissionsGranted()
    }

    LaunchedEffect(service) {
        service?.voiceServiceState?.collect { state ->
            if (state.incomingCall != null) viewModel.onReceptionStarted(state.incomingCall, state.incomingIp ?: "")
            else viewModel.onReceptionEnded()
        }
    }

    LaunchedEffect(service) { viewModel.observeServicePing(service) }
    LaunchedEffect(viewModel.targetUser) { if (viewModel.targetUser.isNotEmpty()) viewModel.triggerPing(service) }

    LaunchedEffect(Unit) {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.VIBRATE,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 34) perms.add(Manifest.permission.FOREGROUND_SERVICE_MICROPHONE)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        permLauncher.launch(perms.toTypedArray())
    }

    // SOS Overlay
    if (serviceState.isSosPending) {
        SosDialog(
            onCancel = { service?.cancelSos() },
            onSend = { service?.confirmSos() }
        )
    }

    // --- ROOT CONTAINER (BOX) ---
    // Needed to overlay the FAB on top of the scrolling list
    Box(modifier = modifier.fillMaxSize()) {

        // --- LAYER 1: SCROLLABLE CONTENT ---
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 100.dp, top = 16.dp, start = 16.dp, end = 16.dp), // Extra bottom padding for FAB
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // --- 1. STATUS HEADER ---
            item {
                val (statusText, statusColor) = when (val state = uiState) {
                    is UiState.Ready -> "Ready" to MaterialTheme.colorScheme.primary
                    is UiState.Connected -> {
                        val targetName = if (state.target.startsWith("group:")) state.target.substringAfter(":") else state.target
                        "Connected to $targetName" to MaterialTheme.colorScheme.primary
                    }
                    is UiState.Transmitting -> {
                        val targetName = if (state.target.startsWith("group:")) state.target.substringAfter(":") else state.target
                        targetName to MaterialTheme.colorScheme.error
                    }
                    is UiState.Receiving -> {
                        val fromName = if (state.from.startsWith("group:")) state.from.substringAfter(":") else state.from
                        "Receiving from $fromName" to MaterialTheme.colorScheme.secondary
                    }
                    is UiState.Error -> state.message to Color.Gray
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (viewModel.targetUser.isNotEmpty() && !viewModel.isBroadcastMode) {
                        val (dotColor, _) = when (viewModel.connectionStatus) {
                            ConnectionStatus.READY -> Color.Green to "Ready"
                            ConnectionStatus.CHECKING -> Color.Yellow to "Checking..."
                            ConnectionStatus.OFFLINE -> Color.Red to "Offline"
                            else -> Color.Gray to "Idle"
                        }
                        Box(Modifier.size(10.dp).background(dotColor, CircleShape))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(statusText, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = statusColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            // --- 2. CONTROL TOOLBAR ---
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { service?.triggerHeartbeat(); viewModel.triggerPing(service); Toast.makeText(context, "Synced", Toast.LENGTH_SHORT).show() }) {
                        Icon(Icons.Default.Sync, "Sync", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { showTextDialog = true }) {
                        Icon(Icons.Default.Keyboard, "Type Message", tint = MaterialTheme.colorScheme.primary)
                    }

                    // Speaker Toggle
                    IconButton(onClick = { viewModel.toggleSpeaker(service) }) {
                        Icon(
                            if (serviceState.isSpeakerOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                            "Toggle Speaker",
                            tint = if (serviceState.isSpeakerOn) MaterialTheme.colorScheme.primary else Color.Gray
                        )
                    }

                    IconButton(onClick = { viewModel.toggleSilence(service) }) {
                        Icon(if (serviceState.isSilenced) Icons.Default.NotificationsOff else Icons.Default.NotificationsActive, "Silent Mode", tint = if (serviceState.isSilenced) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { viewModel.toggleBroadcastMode() }) {
                        Icon(Icons.Default.Groups, "Broadcast", tint = if (viewModel.isBroadcastMode) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // --- 3. PTT BUTTON (Preserved Styling) ---
            item {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(240.dp)
                        .scale(if (isPressed) 0.95f else 1f)
                        .pointerInteropFilter {
                            when (it.action) {
                                MotionEvent.ACTION_DOWN -> {
                                    isPressed = true
                                    if (!viewModel.isHandsFree && service != null) {
                                        permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                                        viewModel.startTransmission(
                                            onIpsFound = { ips, port -> service.startTalk(ips, port) },
                                            onUpdateIps = { newIps -> service.updateTalkTargets(newIps) }
                                        )
                                    }
                                }
                                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                    isPressed = false
                                    if (!viewModel.isHandsFree && service != null) {
                                        viewModel.stopTransmission { service.stopTalk() }
                                    }
                                }
                            }
                            true
                        }
                ) {
                    // Outer Glow
                    if (serviceState.isTransmitting) {
                        val infiniteTransition = rememberInfiniteTransition()
                        val scale by infiniteTransition.animateFloat(
                            initialValue = 1f, targetValue = 1.2f,
                            animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse)
                        )
                        Box(
                            modifier = Modifier
                                .size(240.dp)
                                .scale(scale)
                                .clip(CircleShape)
                                .background(Color(0xFFFFEBEE))
                        )
                    }

                    // The Button
                    Box(
                        modifier = Modifier
                            .size(200.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.verticalGradient(
                                    colors = if (isPressed || serviceState.isTransmitting)
                                        listOf(Color(0xFFD32F2F), Color(0xFFB71C1C))
                                    else
                                        listOf(Color(0xFFEF5350), Color(0xFFC62828))
                                )
                            )
                            .border(4.dp, Color.White, CircleShape)
                            .border(8.dp, Color(0x20000000), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.Mic,
                                contentDescription = "PTT",
                                tint = Color.White,
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                if (serviceState.isTransmitting) "ON AIR" else if(viewModel.isHandsFree) "TAP MODE" else "HOLD",
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // --- 4. HANDSFREE INDICATOR ---
            item {
                if (serviceState.isHeadsetLinked) {
                    Text("Pocket Mode Active", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                }

                OutlinedButton(
                    onClick = { viewModel.isHandsFree = !viewModel.isHandsFree; if (serviceState.isTransmitting) viewModel.stopTransmission { service?.stopTalk() } },
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = if(viewModel.isHandsFree) MaterialTheme.colorScheme.primaryContainer else Color.Transparent),
                    modifier = Modifier.height(36.dp)
                ) {
                    Icon(if(viewModel.isHandsFree) Icons.Default.Lock else Icons.Default.LockOpen, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if(viewModel.isHandsFree) "HANDSFREE ON" else "HANDSFREE OFF", fontSize = 12.sp)
                }
            }

            // --- 5. SAFETY TOOLBAR (Bottom) ---
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=0.3f), RoundedCornerShape(12.dp))
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Stealth Mode
                    val isTheater = serviceState.isTheaterMode
                    IconButton(onClick = {
                        service?.toggleTheaterMode(!isTheater)
                        Toast.makeText(context, if(!isTheater) "Stealth Mode Active" else "Stealth Mode OFF", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.DarkMode, "Stealth Mode", tint = if(isTheater) MaterialTheme.colorScheme.primary else Color.Gray)
                    }

                    // VOX Pro
                    val isVox = serviceState.isVoxEnabled
                    IconButton(onClick = {
                        service?.toggleVox(!isVox)
                        Toast.makeText(context, if(!isVox) "VOX Pro Active" else "VOX Off", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.RecordVoiceOver, "VOX Pro", tint = if(isVox) MaterialTheme.colorScheme.primary else Color.Gray)
                    }

                    // Impact Shield
                    val isSensor = serviceState.isSensorEnabled
                    IconButton(onClick = {
                        service?.toggleSensor(!isSensor)
                        Toast.makeText(context, if(!isSensor) "Impact Shield ON" else "Impact Shield OFF", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.DirectionsBike, "Impact Shield", tint = if(isSensor) MaterialTheme.colorScheme.primary else Color.Gray)
                    }

                    // Signal Trace (Location)
                    IconButton(onClick = {
                        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                                .addOnSuccessListener { loc ->
                                    if (loc != null) {
                                        service?.sendLocationPing(loc.latitude, loc.longitude)
                                        Toast.makeText(context, "Signal Trace Sent", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Acquiring Satellites...", Toast.LENGTH_SHORT).show()
                                    }
                                }
                        } else {
                            Toast.makeText(context, "Location Permission Missing", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Icon(Icons.Default.LocationOn, "Signal Trace", tint = MaterialTheme.colorScheme.primary)
                    }

                    // SOS Button
                    Button(
                        onClick = {
                            if (viewModel.targetUser.isNotBlank()) {
                                service?.sendPanicAlert()
                                Toast.makeText(context, "🚨 SOS Signal Sent!", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Select a Target first!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Icon(Icons.Default.Warning, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("SOS", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = Color.LightGray.copy(alpha=0.5f))
            }

            // --- 6. PAGER LIST ---
            if (pagerEntries.isNotEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("INCOMING MESSAGES", style = MaterialTheme.typography.labelSmall, color = Color.Gray, letterSpacing = 2.sp)
                        TextButton(onClick = { viewModel.clearPagerHistory() }) { Text("CLEAR ALL", fontSize = 10.sp) }
                    }
                }

                items(pagerEntries) { entry ->
                    PagerItem(
                        entry = entry,
                        onPlay = {
                            if (entry.type == "LOCATION") {
                                val coords = entry.content.split(",")
                                if (coords.size == 2) {
                                    val uri = Uri.parse("geo:${coords[0]},${coords[1]}?q=${coords[0]},${coords[1]}(${entry.sender})")
                                    val mapIntent = Intent(Intent.ACTION_VIEW, uri)
                                    mapIntent.setPackage("com.google.android.apps.maps")
                                    try {
                                        context.startActivity(mapIntent)
                                    } catch (e: Exception) {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                                    }
                                }
                            } else {
                                viewModel.playEntry(context, entry, service)
                                viewModel.deletePagerEntry(entry)
                            }
                        },
                        onDelete = { viewModel.deletePagerEntry(entry) }
                    )
                }
            } else {
                // EMPTY STATE
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.SatelliteAlt,
                            contentDescription = null,
                            tint = Color.LightGray,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text("Ready to Talk", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (isSecureMode) "Encrypted Channel" else "Public Channel",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSecureMode) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }

        // --- LAYER 2: SECURE CALL FAB (RESTORED) ---
        // Aligned Bottom-Right, floating over the list
        val targetUser = viewModel.targetUser
        // Logic to determine if call is possible
        val canCall = targetUser.isNotEmpty() && targetUser != "SERVER_LINK"

        FloatingActionButton(
            onClick = {
                if (canCall) {
                    // Try to find IP of the current target to make a call
                    // Note: In a real scenario, you might need to lookup IP from contact list
                    // For now, we assume WalkieViewModel has helper or we check saved contacts
                    val contact = viewModel.savedContacts.find { it.name == targetUser }
                    val ip = contact?.ip ?: ""
                    if (ip.isNotEmpty()) {
                        CallSignaling.startOutgoingCall(ip)
                    } else {
                        Toast.makeText(context, "Target Offline", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(context, "Select a valid target", Toast.LENGTH_SHORT).show()
                }
            },
            containerColor = if (canCall) Color(0xFF43A047) else Color.Gray,
            contentColor = Color.White,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp)
                .size(72.dp) // Extra Large
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Call, "Secure Call")
                Text("CALL", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
        }
    } // End of Box

    // --- DIALOGS ---
    if (showTextDialog) {
        AlertDialog(
            onDismissRequest = { showTextDialog = false },
            title = { Text("Silent Message") },
            text = {
                OutlinedTextField(
                    value = textMessage,
                    onValueChange = { textMessage = it },
                    label = { Text("Type message...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.sendTextPayload(service, textMessage)
                    textMessage = ""
                    showTextDialog = false
                }) { Text("SEND") }
            },
            dismissButton = { TextButton(onClick = { showTextDialog = false }) { Text("Cancel") } }
        )
    }
}

// Visual-Only SOS Countdown
@Composable
fun SosDialog(onCancel: () -> Unit, onSend: () -> Unit) {
    var ticks by remember { mutableIntStateOf(5) }
    LaunchedEffect(Unit) {
        while(ticks > 0) { delay(1000); ticks-- }
    }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("CRASH DETECTED!", color = Color.Red, fontWeight = FontWeight.Bold) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Sending Emergency Alert in...", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(16.dp))
                Text("$ticks", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                Text("seconds", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = onCancel, colors = ButtonDefaults.buttonColors(containerColor = Color.Gray), modifier = Modifier.fillMaxWidth()) { Text("I'M OKAY (CANCEL)") } },
        dismissButton = { TextButton(onClick = onSend) { Text("SEND NOW") } }
    )
}

@Composable
fun PagerItem(entry: PagerEntry, onPlay: () -> Unit, onDelete: () -> Unit) {
    val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    val isText = entry.type == "TEXT"
    val isLocation = entry.type == "LOCATION"

    Card(
        colors = CardDefaults.cardColors(containerColor = when {
            isLocation -> Color(0xFFE8F5E9)
            isText -> Color(0xFFFFF3E0)
            else -> Color(0xFFE3F2FD)
        }),
        modifier = Modifier.fillMaxWidth().clickable { onPlay() }
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = when {
                    isLocation -> Icons.Default.LocationOn
                    isText -> Icons.Default.Message
                    else -> Icons.Default.GraphicEq
                },
                contentDescription = null, tint = Color.Black
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = entry.sender, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    text = when {
                        isLocation -> "📍 Shared Location (Tap to View)"
                        isText -> entry.content
                        else -> "Voice Message"
                    },
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(text = dateFormat.format(Date(entry.timestamp)), style = MaterialTheme.typography.labelSmall)
                Icon(Icons.Default.Close, contentDescription = "Delete", modifier = Modifier.size(16.dp).clickable { onDelete() }, tint = Color.Gray)
            }
        }
    }
}