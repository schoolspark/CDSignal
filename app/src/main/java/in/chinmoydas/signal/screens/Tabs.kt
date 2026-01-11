package `in`.chinmoydas.signal.screens

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import `in`.chinmoydas.signal.VoiceService
import `in`.chinmoydas.signal.VoiceServiceState
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

    val serviceState by service?.voiceServiceState?.collectAsState(initial = VoiceServiceState())
        ?: remember { mutableStateOf(VoiceServiceState()) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (it.getOrDefault(Manifest.permission.RECORD_AUDIO, false)) onPermissionsGranted()
    }

    LaunchedEffect(service) {
        service?.voiceServiceState?.collect { state ->
            if (state.incomingCall != null) {
                viewModel.onReceptionStarted(state.incomingCall, state.incomingIp ?: "")
            } else {
                viewModel.onReceptionEnded()
            }
        }
    }

    LaunchedEffect(service) {
        viewModel.observeServicePing(service)
    }

    LaunchedEffect(viewModel.targetUser) {
        if (viewModel.targetUser.isNotEmpty()) {
            viewModel.triggerPing(service)
        }
    }

    var lastClickTime by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.VIBRATE)
        if (Build.VERSION.SDK_INT >= 34) perms.add(Manifest.permission.FOREGROUND_SERVICE_MICROPHONE)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        permLauncher.launch(perms.toTypedArray())
    }

    Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {

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

        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { service?.triggerHeartbeat(); viewModel.triggerPing(service); Toast.makeText(context, "Synced", Toast.LENGTH_SHORT).show() }) {
                Icon(Icons.Default.Sync, "Sync", tint = MaterialTheme.colorScheme.primary)
            }
            Row {
                IconButton(onClick = { viewModel.toggleSilence(service) }) {
                    Icon(if (serviceState.isSilenced) Icons.Default.NotificationsOff else Icons.Default.NotificationsActive, "Silent Mode", tint = if (serviceState.isSilenced) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { viewModel.toggleBroadcastMode() }) {
                    Icon(Icons.Default.Groups, "Broadcast", tint = if (viewModel.isBroadcastMode) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { viewModel.toggleSpeaker(service) }) {
                    Icon(if (serviceState.isSpeakerOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.Default.Hearing, "Speaker", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (viewModel.targetUser.isNotEmpty() && !viewModel.isBroadcastMode) {
                val (dotColor, dotState) = when (viewModel.connectionStatus) {
                    ConnectionStatus.READY -> Color.Green to "Ready"
                    ConnectionStatus.CHECKING -> Color.Yellow to "Checking..."
                    ConnectionStatus.OFFLINE -> Color.Red to "Offline"
                    else -> Color.Gray to "Idle"
                }
                Box(Modifier.size(12.dp).background(dotColor, CircleShape))
                Spacer(Modifier.width(8.dp))
            }
            Text(statusText, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = statusColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }

        Spacer(Modifier.height(40.dp))

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (uiState is UiState.Transmitting || uiState is UiState.Receiving) {
                IconButton(onClick = { viewModel.hangUp(service) }, modifier = Modifier.size(64.dp).background(Color.Red, CircleShape)) {
                    Icon(Icons.Default.CallEnd, "Hang Up", tint = Color.White)
                }
                Spacer(Modifier.width(24.dp))
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(200.dp).clip(CircleShape).background(if (uiState is UiState.Transmitting) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                if (!viewModel.isHandsFree && service != null) {
                                    viewModel.startTransmission(onIpsFound = { ips, port -> service.startTalk(ips, port) }, onUpdateIps = { newIps -> service.updateTalkTargets(newIps) })
                                    try { awaitRelease() } finally { viewModel.stopTransmission { service.stopTalk() } }
                                }
                            },
                            onTap = {
                                if (viewModel.isHandsFree && service != null) {
                                    val now = System.currentTimeMillis()
                                    if (now - lastClickTime > 300) {
                                        lastClickTime = now
                                        if (uiState is UiState.Transmitting) viewModel.stopTransmission { service.stopTalk() }
                                        else viewModel.startTransmission(onIpsFound = { ips, port -> service.startTalk(ips, port) }, onUpdateIps = { newIps -> service.updateTalkTargets(newIps) })
                                    }
                                }
                            }
                        )
                    }
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.FlashOn, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(48.dp))
                    Text(if (uiState is UiState.Transmitting) "ON AIR" else if (viewModel.isHandsFree) "TAP TO TALK" else "HOLD TO TALK", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        }

        Spacer(Modifier.height(30.dp))

        OutlinedButton(
            onClick = { viewModel.isHandsFree = !viewModel.isHandsFree; if (uiState is UiState.Transmitting) viewModel.stopTransmission { service?.stopTalk() } },
            colors = ButtonDefaults.outlinedButtonColors(containerColor = if(viewModel.isHandsFree) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
        ) {
            Icon(if(viewModel.isHandsFree) Icons.Default.Lock else Icons.Default.LockOpen, null)
            Spacer(Modifier.width(8.dp))
            Text(if(viewModel.isHandsFree) "HANDSFREE ON" else "HANDSFREE OFF")
        }
    }
}

@Composable
fun HistoryTab(modifier: Modifier = Modifier, viewModel: WalkieViewModel) {
    val context = LocalContext.current
    val callLogs by viewModel.callLogs.collectAsState()
    val recordedMessages = viewModel.recordedMessages
    val sdf = remember { SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()) }

    LaunchedEffect(Unit) { viewModel.loadRecordings(context) }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Activity & Messages", style = MaterialTheme.typography.headlineMedium)
            IconButton(onClick = { viewModel.clearHistory() }) { Icon(Icons.Default.DeleteSweep, "Clear Logs", tint = MaterialTheme.colorScheme.error) }
        }
        Spacer(Modifier.height(16.dp))

        if (callLogs.isEmpty() && recordedMessages.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No recent activity", color = Color.Gray) }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {

                // --- UPDATED VOICE MESSAGES SECTION ---
                if (recordedMessages.isNotEmpty()) {
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Voice Messages", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                            // Delete All Button
                            TextButton(onClick = { viewModel.deleteAllRecordings(context) }) {
                                Text("Delete All", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    items(recordedMessages) { file ->
                        val parts = file.name.removeSuffix(".wav").split("_")
                        val time = parts.getOrNull(0)?.toLongOrNull() ?: 0L
                        val sender = parts.getOrElse(1) { "Unknown" }

                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Row(modifier = Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("From: $sender", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                                    Text(sdf.format(Date(time)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                                }

                                // Play and Delete Buttons
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { viewModel.deleteRecording(file) }) {
                                        Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.onErrorContainer)
                                    }
                                    Spacer(Modifier.width(4.dp))
                                    Button(onClick = { viewModel.playAndBurnMessage(file) }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onErrorContainer)) {
                                        Icon(Icons.Default.PlayArrow, "Play", tint = MaterialTheme.colorScheme.errorContainer)
                                        Spacer(Modifier.width(4.dp))
                                        Text("Play", color = MaterialTheme.colorScheme.errorContainer)
                                    }
                                }
                            }
                        }
                    }
                    item { HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp), thickness = 2.dp) }
                }
                // --------------------------------------

                if (callLogs.isNotEmpty()) {
                    item { Text("Call History", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp)) }
                    items(callLogs) { log ->
                        var showMenu by remember { mutableStateOf(false) }
                        ListItem(
                            headlineContent = { Text(if (log.callerName.startsWith("group:")) log.callerName.substringAfter(":") else log.callerName, fontWeight = FontWeight.Bold) },
                            supportingContent = { Text(sdf.format(Date(log.timestamp))) },
                            leadingContent = { Icon(if (log.isIncoming) Icons.Default.CallReceived else Icons.Default.CallMade, null, tint = if (log.isIncoming) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary) },
                            trailingContent = {
                                Box {
                                    IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, "Options") }
                                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                        DropdownMenuItem(text = { Text("Connect") }, onClick = { viewModel.setTarget(log.callerName); showMenu = false }, leadingIcon = { Icon(Icons.Default.Chat, null) })
                                        if (!log.callerName.startsWith("group:")) { DropdownMenuItem(text = { Text("Block User") }, onClick = { viewModel.blockContact(log.callerName); showMenu = false }, leadingIcon = { Icon(Icons.Default.Block, null, tint = Color.Red) }) }
                                    }
                                }
                            }
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = Color.LightGray)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectTab(modifier: Modifier = Modifier, viewModel: WalkieViewModel, onConnected: () -> Unit) {
    val context = LocalContext.current
    var codeInput by remember { mutableStateOf("") }
    var inputName by remember { mutableStateOf("") }
    var isGroupMode by remember { mutableStateOf(false) }
    var showChannelQrDialog by remember { mutableStateOf(false) }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            if (result.contents.startsWith("CHANNEL:")) {
                val data = result.contents.removePrefix("CHANNEL:")
                val parts = data.split("|")
                if (parts.size > 1) { viewModel.saveInternetContact("group:${parts[0]}", parts[1], { Toast.makeText(context, "Channel Joined!", Toast.LENGTH_SHORT).show(); onConnected() }, {}) }
            } else {
                val parts = result.contents.split("|")
                if (parts.size > 1) { viewModel.setTarget(parts[0]); codeInput = parts[1]; isGroupMode = false; viewModel.saveInternetContact(parts[0], parts[1], { onConnected() }, {}) }
            }
        }
    }

    LaunchedEffect(Unit) { viewModel.startLocalDiscovery(context) }
    DisposableEffect(Unit) { onDispose { viewModel.stopLocalDiscovery() } }

    Column(modifier = modifier.fillMaxSize().padding(24.dp)) {
        Text("Add Contact", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(selected = !isGroupMode, onClick = { isGroupMode = false }, shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2), icon = { Icon(Icons.Default.Person, null) }) { Text("Person") }
            SegmentedButton(selected = isGroupMode, onClick = { isGroupMode = true }, shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2), icon = { Icon(Icons.Default.Groups, null) }) { Text("Channel") }
        }
        Spacer(Modifier.height(16.dp))
        Card(onClick = { scanLauncher.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)) }, modifier = Modifier.fillMaxWidth().height(64.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Row { Icon(Icons.Default.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text("Scan QR Code") } } }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = inputName, onValueChange = { inputName = it }, label = { Text(if (isGroupMode) "Channel Name" else "Username") }, modifier = Modifier.weight(0.6f), singleLine = true)
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(value = codeInput, onValueChange = { if(it.length <= 8) codeInput = it }, label = { Text(if (isGroupMode) "Passkey" else "PIN") }, modifier = Modifier.weight(0.35f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = if (isGroupMode) KeyboardType.Text else KeyboardType.Number))
            Spacer(Modifier.width(8.dp))
            Button(onClick = { val finalName = if (isGroupMode) "group:$inputName" else inputName; viewModel.saveInternetContact(finalName, codeInput, { Toast.makeText(context, "Saved!", Toast.LENGTH_SHORT).show(); inputName = ""; codeInput = "" }, { Toast.makeText(context, "Failed", Toast.LENGTH_SHORT).show() }) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(56.dp), shape = RoundedCornerShape(12.dp)) { Icon(Icons.Default.Save, null) }
        }
        Spacer(Modifier.height(24.dp))
        if (viewModel.nearbyUsers.isNotEmpty()) {
            Text("Nearby Devices", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            LazyColumn(modifier = Modifier.height(120.dp)) {
                items(viewModel.nearbyUsers) { user ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Row(Modifier.padding(8.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column { Text(user.name, fontWeight = FontWeight.Bold); Text(user.ip, style = MaterialTheme.typography.bodySmall) }
                            Button(onClick = { viewModel.addContact(user.name, user.ip, ""); onConnected() }) { Text("Connect") }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("My Contacts", style = MaterialTheme.typography.titleSmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(viewModel.savedContacts) { contact ->
                val isGroup = contact.name.startsWith("group:")
                val displayName = if (isGroup) contact.name.substringAfter(":") else contact.name
                var showMenu by remember { mutableStateOf(false) }
                Box {
                    InputChip(selected = viewModel.targetUser == contact.name, onClick = { viewModel.setTarget(contact.name); onConnected() }, label = { Text(displayName) }, avatar = { Icon(if (isGroup) Icons.Default.Groups else Icons.Default.Person, null) }, trailingIcon = { IconButton(onClick = { showMenu = true }, modifier = Modifier.size(18.dp)) { Icon(Icons.Default.ArrowDropDown, null) } })
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(text = { Text("Delete") }, onClick = { viewModel.deleteContact(contact.name); showMenu = false }, leadingIcon = { Icon(Icons.Default.Delete, null) })
                        if (isGroup) { DropdownMenuItem(text = { Text("Share Channel QR") }, onClick = { viewModel.generateChannelQr(contact.name, contact.savedCode); showChannelQrDialog = true; showMenu = false }, leadingIcon = { Icon(Icons.Default.QrCode, null) }) }
                        else { DropdownMenuItem(text = { Text("Block") }, onClick = { viewModel.blockContact(contact.name); showMenu = false; Toast.makeText(context, "Blocked", Toast.LENGTH_SHORT).show() }, leadingIcon = { Icon(Icons.Default.Block, null, tint = Color.Red) }) }
                    }
                }
            }
        }
    }
    if (showChannelQrDialog) {
        AlertDialog(onDismissRequest = { showChannelQrDialog = false }, title = { Text("Channel Frequency") }, text = { Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) { Text(viewModel.sharingChannelName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Spacer(Modifier.height(16.dp)); viewModel.channelQrBitmap?.let { Image(bitmap = it.asImageBitmap(), contentDescription = "Channel QR", modifier = Modifier.size(200.dp)) } } }, confirmButton = { TextButton(onClick = { showChannelQrDialog = false }) { Text("Close") } })
    }
}

@Composable
fun ProfileTab(modifier: Modifier = Modifier, navController: NavController, myName: String, viewModel: WalkieViewModel, onLogout: () -> Unit, onExit: () -> Unit) {
    val context = LocalContext.current
    var showBlockedDialog by remember { mutableStateOf(false) }
    val myCode by viewModel.myPairingCode.collectAsState()
    LaunchedEffect(myCode) { if (myCode.isNotBlank()) viewModel.qrBitmap = viewModel.generateQr("$myName|$myCode") }

    Column(modifier = modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Top) {
        Card(elevation = CardDefaults.cardElevation(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("MY FREQUENCY ID", style = MaterialTheme.typography.labelSmall, color = Color.Gray); Text(myName, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold); Spacer(Modifier.height(16.dp))
                Text("PAIRING PIN", style = MaterialTheme.typography.labelSmall, color = Color.Gray); Text(myCode, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, letterSpacing = 4.sp)
                TextButton(onClick = { viewModel.resetPairingCode(myName) }) { Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Reset PIN") }
                Spacer(Modifier.height(16.dp)); viewModel.qrBitmap?.let { Image(bitmap = it.asImageBitmap(), contentDescription = "QR", modifier = Modifier.size(180.dp)) }
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = { showBlockedDialog = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.PrivacyTip, null); Spacer(Modifier.width(8.dp)); Text("Privacy: Blocked Users") }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = { navController.navigate("diagnostics") }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Build, null); Spacer(Modifier.width(8.dp)); Text("Run System Diagnostics") }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = { navController.navigate("info") }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Info, null)
            Spacer(Modifier.width(8.dp))
            Text("About & Legal Info")
        }
        Spacer(Modifier.height(32.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(onClick = onLogout, modifier = Modifier.weight(1f)) { Icon(Icons.AutoMirrored.Filled.ExitToApp, null); Spacer(Modifier.width(8.dp)); Text("Logout") }
            Button(onClick = onExit, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Icon(Icons.Default.PowerSettingsNew, null); Spacer(Modifier.width(8.dp)); Text("Exit App") }
        }
        Spacer(Modifier.height(50.dp))
    }
    if (showBlockedDialog) {
        AlertDialog(onDismissRequest = { showBlockedDialog = false }, title = { Text("Blocked Users") }, text = { if (viewModel.blockedContacts.isEmpty()) Text("No blocked users.") else LazyColumn { items(viewModel.blockedContacts) { contact -> Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text(contact.name); TextButton(onClick = { viewModel.unblockContact(contact.name) }) { Text("Unblock") } } } } }, confirmButton = { TextButton(onClick = { showBlockedDialog = false }) { Text("Close") } })
    }
}