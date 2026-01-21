package `in`.chinmoydas.signal.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.chinmoydas.signal.VoiceService
import `in`.chinmoydas.signal.VoiceServiceState
import `in`.chinmoydas.signal.data.PagerEntry
import `in`.chinmoydas.signal.viewmodel.ConnectionStatus
import `in`.chinmoydas.signal.viewmodel.UiState
import `in`.chinmoydas.signal.viewmodel.WalkieViewModel
import java.text.SimpleDateFormat
import java.util.*

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

    // Secure Mode Check for UI Badge
    val isSecureMode = context.getSharedPreferences("WalkiePrefs", Context.MODE_PRIVATE)
        .getBoolean("secure_mode", false)

    var showTextDialog by remember { mutableStateOf(false) }
    var textMessage by remember { mutableStateOf("") }

    val serviceState by service?.voiceServiceState?.collectAsState(initial = VoiceServiceState())
        ?: remember { mutableStateOf(VoiceServiceState()) }

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

    var lastClickTime by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.VIBRATE)
        if (Build.VERSION.SDK_INT >= 34) perms.add(Manifest.permission.FOREGROUND_SERVICE_MICROPHONE)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        permLauncher.launch(perms.toTypedArray())
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {

        // --- 1. STATUS HEADER ---
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

        Spacer(Modifier.height(16.dp))

        // --- 2. CONTROL TOOLBAR ---
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { service?.triggerHeartbeat(); viewModel.triggerPing(service); Toast.makeText(context, "Synced", Toast.LENGTH_SHORT).show() }) {
                Icon(Icons.Default.Sync, "Sync", tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = { showTextDialog = true }) {
                Icon(Icons.Default.Keyboard, "Type Message", tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = { viewModel.toggleSilence(service) }) {
                Icon(if (serviceState.isSilenced) Icons.Default.NotificationsOff else Icons.Default.NotificationsActive, "Silent Mode", tint = if (serviceState.isSilenced) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { viewModel.toggleBroadcastMode() }) {
                Icon(Icons.Default.Groups, "Broadcast", tint = if (viewModel.isBroadcastMode) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { viewModel.toggleSpeaker(service) }) {
                Icon(if (serviceState.isSpeakerOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.Default.Hearing, "Speaker", tint = if (serviceState.isSpeakerOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(Modifier.height(32.dp))

        // --- 3. PTT BUTTON ---
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(220.dp)
                .clip(CircleShape)
                .background(
                    if (serviceState.isTransmitting) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            if (!viewModel.isHandsFree && service != null) {
                                viewModel.startTransmission(
                                    onIpsFound = { ips, port -> service.startTalk(ips, port) },
                                    onUpdateIps = { newIps -> service.updateTalkTargets(newIps) }
                                )
                                try { awaitRelease() } finally { viewModel.stopTransmission { service.stopTalk() } }
                            }
                        },
                        onTap = {
                            if (viewModel.isHandsFree && service != null) {
                                val now = System.currentTimeMillis()
                                if (now - lastClickTime > 300) {
                                    lastClickTime = now
                                    if (serviceState.isTransmitting) viewModel.stopTransmission { service.stopTalk() }
                                    else viewModel.startTransmission(onIpsFound = { ips, port -> service.startTalk(ips, port) }, onUpdateIps = { newIps -> service.updateTalkTargets(newIps) })
                                }
                            }
                        }
                    )
                }
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    if (serviceState.isTransmitting) Icons.Default.Mic else Icons.Default.MicNone,
                    null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(72.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (serviceState.isTransmitting) "ON AIR"
                    else if (viewModel.isHandsFree) "TAP TO TALK"
                    else "HOLD TO TALK",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        if (serviceState.isHeadsetLinked) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Headset, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(8.dp))
                Text("Pocket Mode Active", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
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

        Spacer(Modifier.height(24.dp))
        HorizontalDivider(color = Color.LightGray.copy(alpha=0.5f))
        Spacer(Modifier.height(8.dp))

        // --- 4. PAGER LIST OR EMPTY STATE ---
        if (pagerEntries.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("INCOMING INTERCEPTS", style = MaterialTheme.typography.labelSmall, color = Color.Gray, letterSpacing = 2.sp)
                TextButton(onClick = { viewModel.clearPagerHistory() }) { Text("CLEAR ALL", fontSize = 10.sp) }
            }

            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(pagerEntries) { entry ->
                    PagerItem(
                        entry = entry,
                        onPlay = {
                            viewModel.playEntry(context, entry, service)
                            viewModel.deletePagerEntry(entry)
                        },
                        onDelete = { viewModel.deletePagerEntry(entry) }
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.SatelliteAlt,
                    contentDescription = null,
                    tint = Color.LightGray,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text("Awaiting Signal...", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (isSecureMode) "Secure Encryption Active" else "Public Channel Open",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSecureMode) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.5f)
                )
            }
        }
    }

    if (showTextDialog) {
        AlertDialog(
            onDismissRequest = { showTextDialog = false },
            title = { Text("Silent Message") },
            text = {
                OutlinedTextField(
                    value = textMessage,
                    onValueChange = { textMessage = it },
                    label = { Text("Type intel...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.sendTextPayload(service, textMessage)
                    textMessage = ""
                    showTextDialog = false
                }) { Text("SEND (UDP)") }
            },
            dismissButton = { TextButton(onClick = { showTextDialog = false }) { Text("Cancel") } }
        )
    }
}

// Reusable UI Component for Pager Items
@Composable
fun PagerItem(entry: PagerEntry, onPlay: () -> Unit, onDelete: () -> Unit) {
    val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    val isText = entry.type == "TEXT"

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isText) Color(0xFFFFF3E0) else Color(0xFFE3F2FD)
        ),
        modifier = Modifier.fillMaxWidth().clickable { onPlay() }
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isText) Icons.Default.Message else Icons.Default.GraphicEq,
                contentDescription = null,
                tint = Color.Black
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.sender,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (isText) entry.content else "Encrypted Voice Clip",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = dateFormat.format(Date(entry.timestamp)),
                    style = MaterialTheme.typography.labelSmall
                )
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Delete",
                    modifier = Modifier.size(16.dp).clickable { onDelete() },
                    tint = Color.Gray
                )
            }
        }
    }
}