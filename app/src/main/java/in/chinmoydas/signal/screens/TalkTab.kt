package `in`.chinmoydas.signal.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import `in`.chinmoydas.signal.utils.CallSignaling
import kotlinx.coroutines.delay
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Composable
fun TalkTab(
    modifier: Modifier = Modifier,
    viewModel: WalkieViewModel,
    service: VoiceService?,
    onPermissionsGranted: () -> Unit,
    onCheckSystem: () -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val pagerEntries by viewModel.pagerEntries.collectAsState()
    val isSecureMode = context.getSharedPreferences("WalkiePrefs", Context.MODE_PRIVATE).getBoolean("secure_mode", false)

    // --- DIALOG STATES ---
    var showTextDialog by rememberSaveable { mutableStateOf(false) }
    var showSettingsDialog by rememberSaveable { mutableStateOf(false) }
    var showGuardianControls by rememberSaveable { mutableStateOf(false) }
    var textMessage by rememberSaveable { mutableStateOf("") }

    // UI Interaction States
    var isPressed by remember { mutableStateOf(false) }
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    val serviceState by service?.voiceServiceState?.collectAsState(initial = VoiceServiceState()) ?: remember { mutableStateOf(VoiceServiceState()) }
    val listState = rememberLazyListState()

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (it.getOrDefault(Manifest.permission.RECORD_AUDIO, false)) onPermissionsGranted()
    }

    LaunchedEffect(service) {
        service?.voiceServiceState?.collect { state ->
            if (state.incomingCall != null) viewModel.onReceptionStarted(state.incomingCall, state.incomingIp ?: "")
            else viewModel.onReceptionEnded()
        }
    }

    // Auto-scroll to new messages
    LaunchedEffect(pagerEntries.size) {
        if (pagerEntries.isNotEmpty()) listState.animateScrollToItem(pagerEntries.size)
    }

    LaunchedEffect(service) { viewModel.observeServicePing(service) }
    LaunchedEffect(viewModel.targetUser) { if (viewModel.targetUser.isNotEmpty()) viewModel.triggerPing(service) }

    LaunchedEffect(Unit) {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.VIBRATE, Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 34) perms.add(Manifest.permission.FOREGROUND_SERVICE_MICROPHONE)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        permLauncher.launch(perms.toTypedArray())
    }

    // SOS Overlay
    if (serviceState.isSosPending) {
        SosDialog(onCancel = { service?.cancelSos() }, onSend = { service?.confirmSos() })
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 100.dp, top = 16.dp, start = 16.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {

            // --- 1. HEADER & STATUS ---
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val (statusText, statusColor) = when (val state = uiState) {
                        is UiState.Ready -> "Ready" to MaterialTheme.colorScheme.primary
                        is UiState.Connected -> { val targetName = if (state.target.startsWith("group:")) state.target.substringAfter(":") else state.target; "Connected to $targetName" to MaterialTheme.colorScheme.primary }
                        is UiState.Transmitting -> { val targetName = if (state.target.startsWith("group:")) state.target.substringAfter(":") else state.target; targetName to MaterialTheme.colorScheme.error }
                        is UiState.Receiving -> { val fromName = if (state.from.startsWith("group:")) state.from.substringAfter(":") else state.from; "Receiving from $fromName" to MaterialTheme.colorScheme.secondary }
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
                            Box(Modifier.size(8.dp).background(dotColor, CircleShape))
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(statusText, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = statusColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(if (isSecureMode) "Encrypted Channel" else "Public Channel", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
            }

            // --- 2. TOP CONTROLS ---
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Guardian Remote Button
                    IconButton(onClick = {
                        if (viewModel.targetUser.isNotBlank() && viewModel.targetUser != "SERVER_LINK") {
                            showGuardianControls = true
                        } else {
                            Toast.makeText(context, "Select a Target User first!", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Icon(Icons.Default.SettingsRemote, "Guardian Remote", tint = MaterialTheme.colorScheme.error)
                    }

                    // Sync
                    IconButton(onClick = { service?.triggerHeartbeat(); viewModel.triggerPing(service); Toast.makeText(context, "Synced", Toast.LENGTH_SHORT).show() }) {
                        Icon(Icons.Default.Sync, "Sync", tint = MaterialTheme.colorScheme.primary)
                    }
                    // System Check
                    IconButton(onClick = onCheckSystem) {
                        Icon(Icons.Default.VerifiedUser, "System Check", tint = MaterialTheme.colorScheme.primary)
                    }

                    // Message
                    IconButton(onClick = { showTextDialog = true }) {
                        Icon(Icons.Default.Keyboard, "Message", tint = MaterialTheme.colorScheme.primary)
                    }

                    // Speaker (Updates automatically via AudioRouter)
                    FilledTonalIconToggleButton(
                        checked = serviceState.isSpeakerOn,
                        onCheckedChange = { viewModel.toggleSpeaker(service) },
                        colors = IconButtonDefaults.filledTonalIconToggleButtonColors(checkedContainerColor = MaterialTheme.colorScheme.primaryContainer),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(if (serviceState.isSpeakerOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff, null)
                    }

                    // Silent
                    FilledTonalIconToggleButton(
                        checked = serviceState.isSilenced,
                        onCheckedChange = { viewModel.toggleSilence(service) },
                        colors = IconButtonDefaults.filledTonalIconToggleButtonColors(checkedContainerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(if (serviceState.isSilenced) Icons.Default.NotificationsOff else Icons.Default.NotificationsActive, "Silent")
                    }

                    // Broadcast Mode
                    FilledTonalIconToggleButton(
                        checked = viewModel.isBroadcastMode,
                        onCheckedChange = { viewModel.toggleBroadcastMode() },
                        colors = IconButtonDefaults.filledTonalIconToggleButtonColors(checkedContainerColor = MaterialTheme.colorScheme.tertiaryContainer),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.Default.Groups, "Broadcast")
                    }
                }
            }

            // --- 3. PTT BUTTON ---
            item {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(240.dp).scale(if (isPressed) 0.95f else 1f).pointerInteropFilter {
                        when (it.action) {
                            MotionEvent.ACTION_DOWN -> {
                                isPressed = true
                                if (!viewModel.isHandsFree && service != null) {
                                    permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                                    viewModel.startTransmission(onIpsFound = { ips, port -> service.startTalk(ips, port) }, onUpdateIps = { newIps -> service.updateTalkTargets(newIps) })
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
                    if (serviceState.isTransmitting) {
                        val infiniteTransition = rememberInfiniteTransition()
                        val scale by infiniteTransition.animateFloat(initialValue = 1f, targetValue = 1.2f, animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse))
                        Box(modifier = Modifier.size(240.dp).scale(scale).clip(CircleShape).background(Color(0xFFFFEBEE)))
                    }
                    Box(modifier = Modifier.size(200.dp).clip(CircleShape).background(Brush.verticalGradient(colors = if (isPressed || serviceState.isTransmitting) listOf(Color(0xFFD32F2F), Color(0xFFB71C1C)) else listOf(Color(0xFFEF5350), Color(0xFFC62828)))).border(4.dp, Color.White, CircleShape).border(8.dp, Color(0x20000000), CircleShape), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Mic, contentDescription = "PTT", tint = Color.White, modifier = Modifier.size(64.dp))
                            Spacer(Modifier.height(8.dp))
                            Text(if (serviceState.isTransmitting) "ON AIR" else if(viewModel.isHandsFree) "TAP MODE" else "HOLD", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // --- 4. TRIGGER MODES ---
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Handsfree
                    ElevatedFilterChip(
                        selected = viewModel.isHandsFree,
                        onClick = {
                            viewModel.isHandsFree = !viewModel.isHandsFree
                            if (serviceState.isTransmitting) viewModel.stopTransmission { service?.stopTalk() }
                            val status = if (viewModel.isHandsFree) "Handsfree: ON" else "Handsfree: OFF"
                            Toast.makeText(context, status, Toast.LENGTH_SHORT).show()
                        },
                        label = { Text(if (viewModel.isHandsFree) "Handsfree: ON" else "Handsfree: OFF", fontWeight = FontWeight.Bold) },
                        leadingIcon = { Icon(if (viewModel.isHandsFree) Icons.Default.LockOpen else Icons.Default.Lock, null, modifier = Modifier.size(18.dp)) },
                        colors = FilterChipDefaults.elevatedFilterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer),
                        modifier = Modifier.weight(1f)
                    )

                    // Pocket Mode
                    ElevatedFilterChip(
                        selected = serviceState.isHeadsetLinked,
                        onClick = {
                            val intent = Intent(context, VoiceService::class.java).apply { action = "TOGGLE_HEADSET" }
                            context.startService(intent)
                        },
                        label = { Text(if (serviceState.isHeadsetLinked) "Pocket Mode: ON" else "Pocket Mode: OFF", fontWeight = FontWeight.Bold) },
                        leadingIcon = { Icon(if (serviceState.isHeadsetLinked) Icons.Default.HeadsetMic else Icons.Default.HeadsetOff, null, modifier = Modifier.size(18.dp)) },
                        colors = FilterChipDefaults.elevatedFilterChipColors(selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // --- 5. SAFETY & TOOLS ---
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha=0.3f)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledIconToggleButton(checked = serviceState.isTheaterMode, onCheckedChange = { viewModel.toggleStealth(service); Toast.makeText(context, if(it) "Stealth ON" else "Stealth OFF", Toast.LENGTH_SHORT).show() }) { Icon(Icons.Default.DarkMode, "Stealth") }
                        FilledIconToggleButton(checked = serviceState.isVoxEnabled, onCheckedChange = { viewModel.toggleVox(service); Toast.makeText(context, if(it) "VOX ON" else "VOX OFF", Toast.LENGTH_SHORT).show() }) { Icon(Icons.Default.RecordVoiceOver, "VOX") }
                        FilledIconToggleButton(checked = serviceState.isSensorEnabled, onCheckedChange = { service?.toggleSensor(it); Toast.makeText(context, if(it) "Shield ON" else "Shield OFF", Toast.LENGTH_SHORT).show() }) { Icon(Icons.Default.DirectionsBike, "Shield") }

                        FilledIconButton(onClick = {
                            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                                fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).addOnSuccessListener { loc ->
                                    if (loc != null) { service?.sendLocationPing(loc.latitude, loc.longitude); Toast.makeText(context, "Location Sent", Toast.LENGTH_SHORT).show() }
                                    else { Toast.makeText(context, "Locating...", Toast.LENGTH_SHORT).show() }
                                }
                            } else {
                                Toast.makeText(context, "No GPS Perm", Toast.LENGTH_SHORT).show()
                                permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
                            }
                        }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.secondary)) {
                            Icon(Icons.Default.LocationOn, "Trace", tint = Color.White)
                        }

                        FilledIconButton(
                            onClick = {
                                if (viewModel.targetUser.isNotBlank()) {
                                    // [FIX] Use ViewModel Dynamic SOS
                                    viewModel.triggerCurrentSos(service)
                                    Toast.makeText(context, "SOS SENT!", Toast.LENGTH_SHORT).show()
                                }
                                else { Toast.makeText(context, "Select Target!", Toast.LENGTH_SHORT).show() }
                            },
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.Warning, "SOS", tint = Color.White)
                        }
                    }
                }
            }

            // --- 6. PAGER ---
            if (pagerEntries.isNotEmpty()) {
                item {
                    HorizontalDivider(color = Color.LightGray.copy(alpha=0.3f))
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("MESSAGES", style = MaterialTheme.typography.labelSmall, color = Color.Gray, letterSpacing = 2.sp)
                        TextButton(onClick = { viewModel.clearPagerHistory() }) { Text("CLEAR ALL", fontSize = 10.sp) }
                    }
                }
                items(pagerEntries) { entry ->
                    PagerItem(
                        entry = entry,
                        onPlay = { viewModel.playEntry(context, entry, service); if(entry.type == "AUDIO") viewModel.deletePagerEntry(entry) },
                        onDelete = { viewModel.deletePagerEntry(entry) }
                    )
                }
            }
        }

        // --- 7. FAB ---
        val targetUser = viewModel.targetUser
        val canCall = targetUser.isNotEmpty() && targetUser != "SERVER_LINK"
        FloatingActionButton(
            onClick = {
                if (canCall) {
                    val contact = viewModel.savedContacts.find { it.name == targetUser }
                    val ip = contact?.ip ?: ""
                    if (ip.isNotEmpty()) CallSignaling.startOutgoingCall(ip) else Toast.makeText(context, "Target Offline", Toast.LENGTH_SHORT).show()
                } else { Toast.makeText(context, "Select Target", Toast.LENGTH_SHORT).show() }
            },
            containerColor = if (canCall) Color(0xFF43A047) else Color.Gray,
            contentColor = Color.White,
            modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp).size(72.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Call, "Call")
                Text("CALL", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
        }
    }

    // --- DIALOGS ---

    if (showTextDialog) {
        AlertDialog(
            onDismissRequest = { showTextDialog = false },
            title = { Text("Silent Message") },
            text = { OutlinedTextField(value = textMessage, onValueChange = { textMessage = it }, label = { Text("Type message...") }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { viewModel.sendTextPayload(service, textMessage); textMessage = ""; showTextDialog = false }) { Text("SEND") } },
            dismissButton = { TextButton(onClick = { showTextDialog = false }) { Text("Cancel") } }
        )
    }

    if (showSettingsDialog) {
        val prefs = context.getSharedPreferences("WalkiePrefs", Context.MODE_PRIVATE)
        var remoteEnabled by rememberSaveable { mutableStateOf(prefs.getBoolean("allow_remote_control", false)) }

        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("Quick Settings") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { remoteEnabled = !remoteEnabled; prefs.edit().putBoolean("allow_remote_control", remoteEnabled).apply() }) {
                    Switch(checked = remoteEnabled, onCheckedChange = { remoteEnabled = it; prefs.edit().putBoolean("allow_remote_control", it).apply() })
                    Spacer(Modifier.width(16.dp))
                    Text(if(remoteEnabled) "Guardian Mode: ON" else "Guardian Mode: OFF")
                }
            },
            confirmButton = { TextButton(onClick = { showSettingsDialog = false }) { Text("DONE") } }
        )
    }

    if (showGuardianControls) {
        AlertDialog(
            onDismissRequest = { showGuardianControls = false },
            icon = { Icon(Icons.Default.Security, null, tint = MaterialTheme.colorScheme.error) },
            title = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("GUARDIAN REMOTE", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                    Text("Target: ${viewModel.targetUser}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Only use these commands in emergencies. You must be a 'Principal' on the target device.", style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()

                    // Command 1: MIC ON
                    OutlinedButton(
                        onClick = {
                            viewModel.sendTextPayload(service, "CMD:REMOTE_MIC_ON")
                            Toast.makeText(context, "Command Sent: LISTEN IN", Toast.LENGTH_SHORT).show()
                            showGuardianControls = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red)
                    ) {
                        Icon(Icons.Default.Mic, null)
                        Spacer(Modifier.width(8.dp))
                        Text("FORCE MIC ON (LISTEN)")
                    }

                    // Command 2: LOCATION
                    OutlinedButton(
                        onClick = {
                            viewModel.sendTextPayload(service, "CMD:REMOTE_LOCATION")
                            Toast.makeText(context, "Command Sent: LOCATE", Toast.LENGTH_SHORT).show()
                            showGuardianControls = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF1976D2))
                    ) {
                        Icon(Icons.Default.LocationSearching, null)
                        Spacer(Modifier.width(8.dp))
                        Text("PING GPS LOCATION")
                    }

                    // Command 3: STEALTH
                    OutlinedButton(
                        onClick = {
                            viewModel.sendTextPayload(service, "CMD:REMOTE_STEALTH")
                            Toast.makeText(context, "Command Sent: SILENCE", Toast.LENGTH_SHORT).show()
                            showGuardianControls = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.DarkGray)
                    ) {
                        Icon(Icons.Default.VolumeOff, null)
                        Spacer(Modifier.width(8.dp))
                        Text("FORCE SILENT MODE")
                    }

                    // Command 4: RESTORE
                    OutlinedButton(
                        onClick = {
                            viewModel.sendTextPayload(service, "CMD:REMOTE_RESTORE")
                            Toast.makeText(context, "Command Sent: RESTORE", Toast.LENGTH_SHORT).show()
                            showGuardianControls = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF2E7D32)) // Green
                    ) {
                        Icon(Icons.Default.RestartAlt, null)
                        Spacer(Modifier.width(8.dp))
                        Text("RESTORE DEVICE (RESET)")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showGuardianControls = false }) { Text("CLOSE") }
            }
        )
    }
}

@Composable
fun SosDialog(onCancel: () -> Unit, onSend: () -> Unit) {
    var ticks by remember { mutableIntStateOf(5) }
    LaunchedEffect(Unit) { while(ticks > 0) { delay(1000); ticks-- }; onSend() }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("CRASH DETECTED!", color = Color.Red, fontWeight = FontWeight.Bold) },
        text = { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("Sending Emergency Alert in...", style = MaterialTheme.typography.bodyLarge); Spacer(Modifier.height(16.dp)); Text("$ticks", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error); Text("seconds", style = MaterialTheme.typography.bodySmall) } },
        confirmButton = { Button(onClick = onCancel, colors = ButtonDefaults.buttonColors(containerColor = Color.Gray), modifier = Modifier.fillMaxWidth()) { Text("I'M OKAY (CANCEL)") } },
        dismissButton = { TextButton(onClick = onSend) { Text("SEND NOW") } }
    )
}

@Composable
fun PagerItem(entry: PagerEntry, onPlay: () -> Unit, onDelete: () -> Unit) {
    val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    val isText = entry.type == "TEXT"
    val isLocation = entry.type == "LOCATION"
    val isAudio = entry.type == "AUDIO"

    Card(
        colors = CardDefaults.cardColors(containerColor = when { isLocation -> Color(0xFFE8F5E9); isText -> Color(0xFFFFF3E0); else -> Color(0xFFE3F2FD) }),
        modifier = Modifier.fillMaxWidth().clickable { onPlay() }
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = when { isLocation -> Icons.Default.LocationOn; isText -> Icons.Default.Message; else -> Icons.Default.GraphicEq }, contentDescription = null, tint = Color.Black)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = entry.sender, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

                val displayText = when {
                    isLocation -> "📍 Shared Location"
                    isAudio -> "▶ Voice Message"
                    else -> entry.content
                }

                Text(text = displayText, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(text = dateFormat.format(Date(entry.timestamp)), style = MaterialTheme.typography.labelSmall)
                Icon(Icons.Default.Close, contentDescription = "Delete", modifier = Modifier.size(16.dp).clickable { onDelete() }, tint = Color.Gray)
            }
        }
    }
}