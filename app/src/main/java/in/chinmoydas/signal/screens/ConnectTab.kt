package `in`.chinmoydas.signal.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import `in`.chinmoydas.signal.viewmodel.WalkieViewModel
import `in`.chinmoydas.signal.utils.CallSignaling
import `in`.chinmoydas.signal.utils.SafetySignaling

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectTab(modifier: Modifier = Modifier, viewModel: WalkieViewModel, onConnected: () -> Unit) {
    val context = LocalContext.current
    var codeInput by remember { mutableStateOf("") }
    var inputName by remember { mutableStateOf("") }
    var isGroupMode by remember { mutableStateOf(false) }
    var showChannelQrDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // Filter Logic: Uses derivedStateOf for performance
    val filteredContacts by remember(searchQuery) {
        derivedStateOf {
            if (searchQuery.isBlank()) {
                viewModel.savedContacts.toList()
            } else {
                viewModel.savedContacts.filter {
                    it.name.contains(searchQuery, ignoreCase = true)
                }
            }
        }
    }

    // QR Logic
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            if (result.contents.startsWith("CHANNEL:")) {
                val data = result.contents.removePrefix("CHANNEL:")
                val parts = data.split("|")
                if (parts.size > 1) {
                    viewModel.saveInternetContact("group:${parts[0]}", parts[1], {
                        Toast.makeText(context, "Channel Joined!", Toast.LENGTH_SHORT).show()
                        onConnected()
                    }, {})
                }
            } else {
                val parts = result.contents.split("|")
                if (parts.size > 1) {
                    viewModel.setTarget(parts[0])
                    codeInput = parts[1]
                    isGroupMode = false
                    viewModel.saveInternetContact(parts[0], parts[1], { onConnected() }, {})
                }
            }
        }
    }

    // Lifecycle: Local Discovery
    LaunchedEffect(Unit) { viewModel.startLocalDiscovery(context) }
    DisposableEffect(Unit) { onDispose { viewModel.stopLocalDiscovery() } }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
        contentPadding = PaddingValues(bottom = 80.dp)
    ) {

        // --- 1. SAFE WALK (GUARDIAN) CARD ---
        item {
            val safeWalkRemaining by SafetySignaling.safeWalkTimeRemaining.collectAsState()
            var selectedDuration by remember { mutableIntStateOf(15) }
            val currentTime = safeWalkRemaining

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (currentTime != null) Color(0xFFFFF3E0) else MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (currentTime != null) Icons.Default.Timer else Icons.Default.DirectionsWalk,
                            contentDescription = null,
                            tint = if (currentTime != null) Color(0xFFE65100) else MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (currentTime != null) "GUARDIAN ACTIVE" else "Safe Walk",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (currentTime != null) Color(0xFFE65100) else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    if (currentTime != null) {
                        val totalSecs = currentTime / 1000
                        val minutes = totalSecs / 60
                        val seconds = totalSecs % 60
                        val timeString = String.format("%02d:%02d", minutes, seconds)

                        Text(
                            text = timeString,
                            fontSize = 48.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFBF360C),
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )

                        Text(
                            text = "Auto-SOS Trigger in $timeString",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFE65100),
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )

                        Spacer(Modifier.height(16.dp))

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            Button(
                                onClick = { SafetySignaling.checkIn() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                                modifier = Modifier.weight(1f)
                            ) { Text("I'M OK (RESET)") }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = { SafetySignaling.stopSafeWalk() },
                                modifier = Modifier.weight(1f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFBF360C)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFBF360C))
                            ) { Text("STOP") }
                        }
                    } else {
                        Text("Dead Man's Switch: Auto-SOS if you don't check in.", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            listOf(15, 30, 60).forEach { mins ->
                                FilterChip(
                                    selected = selectedDuration == mins,
                                    onClick = { selectedDuration = mins },
                                    label = { Text("$mins m") },
                                    leadingIcon = if (selectedDuration == mins) {
                                        { Icon(Icons.Default.Check, null) }
                                    } else null
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { SafetySignaling.startSafeWalk(selectedDuration) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) { Text("START MONITORING") }
                    }
                }
            }
        }

        // --- 2. SEARCH ---
        item {
            Box(Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search contacts or IP...") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    singleLine = true
                )
            }
        }

        // --- 3. SPEED DIAL (HORIZONTAL) ---
        if (filteredContacts.isNotEmpty()) {
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text("Speed Dial", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(filteredContacts) { contact ->
                            val isGroup = contact.name.startsWith("group:")
                            val displayName = if (isGroup) contact.name.substringAfter(":") else contact.name
                            var showMenu by remember { mutableStateOf(false) }
                            val isOnline = contact.ip.isNotEmpty() && contact.ip != "SERVER_LINK"

                            ElevatedCard(
                                onClick = { viewModel.setTarget(contact.name); onConnected() },
                                colors = CardDefaults.elevatedCardColors(
                                    containerColor = if (viewModel.targetUser == contact.name) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp).widthIn(min = 90.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Box(Modifier.fillMaxWidth()) {
                                        IconButton(onClick = { showMenu = true }, modifier = Modifier.size(20.dp).align(Alignment.TopEnd)) {
                                            Icon(Icons.Default.MoreVert, "Menu", modifier = Modifier.size(16.dp))
                                        }
                                        Icon(if (isGroup) Icons.Default.Groups else Icons.Default.Person, null, modifier = Modifier.size(32.dp).align(Alignment.Center), tint = if (isOnline) Color(0xFF43A047) else MaterialTheme.colorScheme.primary)
                                        if (contact.isPriority) {
                                            Icon(Icons.Default.Star, "Principal", tint = Color(0xFFFFD700), modifier = Modifier.size(14.dp).align(Alignment.TopStart))
                                        }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Text(displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)

                                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                        DropdownMenuItem(
                                            text = { Text("Voice Call", fontWeight = FontWeight.Bold) },
                                            // [MOTHERSHIP UPGRADE] Passing IP and Name
                                            onClick = { CallSignaling.startOutgoingCall(contact.ip, contact.name); showMenu = false },
                                            leadingIcon = { Icon(Icons.Default.Call, null, tint = if(isOnline) Color(0xFF43A047) else Color.Gray) },
                                            enabled = isOnline
                                        )
                                        HorizontalDivider()
                                        DropdownMenuItem(text = { Text("Delete") }, onClick = { viewModel.deleteContact(contact.name); showMenu = false }, leadingIcon = { Icon(Icons.Default.Delete, null) })
                                        if (isGroup) {
                                            DropdownMenuItem(text = { Text("Share QR") }, onClick = { viewModel.generateChannelQr(contact.name, contact.savedCode); showChannelQrDialog = true; showMenu = false }, leadingIcon = { Icon(Icons.Default.QrCode, null) })
                                        } else {
                                            DropdownMenuItem(text = { Text(if(contact.isPriority) "Unset Principal" else "Set as Principal") }, onClick = { viewModel.togglePriority(contact.name); showMenu = false }, leadingIcon = { Icon(Icons.Default.Star, null, tint = if(contact.isPriority) Color(0xFFFFD700) else Color.Gray) })
                                            DropdownMenuItem(text = { Text("Block") }, onClick = { viewModel.blockContact(contact.name); showMenu = false }, leadingIcon = { Icon(Icons.Default.Block, null, tint = Color.Red) })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }

        // --- 4. ADD CONNECTION ---
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text("Add New Connection", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            SegmentedButton(selected = !isGroupMode, onClick = { isGroupMode = false }, shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp), icon = { Icon(Icons.Default.Person, null) }) { Text("Person") }
                            SegmentedButton(selected = isGroupMode, onClick = { isGroupMode = true }, shape = RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp), icon = { Icon(Icons.Default.Groups, null) }) { Text("Channel") }
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = inputName,
                            onValueChange = { inputName = it },
                            label = { Text(if (isGroupMode) "Channel Name" else "Username") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            trailingIcon = { IconButton(onClick = { scanLauncher.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)) }) { Icon(Icons.Default.QrCodeScanner, null, tint = MaterialTheme.colorScheme.primary) } }
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(value = codeInput, onValueChange = { if (it.length <= 8) codeInput = it }, label = { Text(if (isGroupMode) "Passkey" else "User PIN") }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = if (isGroupMode) KeyboardType.Text else KeyboardType.Number))
                            Spacer(Modifier.width(8.dp))
                            Button(
                                enabled = inputName.isNotBlank() && codeInput.isNotBlank(),
                                onClick = {
                                    val finalName = if (isGroupMode) "group:$inputName" else inputName
                                    viewModel.saveInternetContact(finalName, codeInput, { inputName = ""; codeInput = "" }, {})
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.height(56.dp)
                            ) { Text("Add"); Spacer(Modifier.width(4.dp)); Icon(Icons.Default.AddLink, null) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        // --- 5. NEARBY (LAN) ---
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
                Icon(Icons.Default.WifiTethering, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Nearby Devices (LAN)", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.secondary)
            }
            Spacer(Modifier.height(8.dp))
        }

        if (viewModel.nearbyUsers.isNotEmpty()) {
            items(viewModel.nearbyUsers) { user ->
                Box(Modifier.padding(horizontal = 16.dp)) {
                    TacticalUserRow(
                        name = user.name,
                        ip = user.ip,
                        status = "Local Network",
                        isOnline = true,
                        onRadioClick = { viewModel.addContact(user.name, user.ip, ""); viewModel.setTarget(user.name); onConnected() },
                        // [MOTHERSHIP UPGRADE] Passing IP and Name
                        onCallClick = { CallSignaling.startOutgoingCall(user.ip, user.name) },
                        onWakeClick = {}
                    )
                }
            }
        } else {
            item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = Color.LightGray)
                        Spacer(Modifier.height(8.dp))
                        Text("Scanning local network...", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }

        // --- 6. SAVED DIRECTORY ---
        if (filteredContacts.isNotEmpty()) {
            item {
                Text("Directory (Saved)", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(8.dp))
            }
            items(filteredContacts) { contact ->
                val isOnline = contact.ip.isNotEmpty() && contact.ip != "SERVER_LINK"
                Box(Modifier.padding(horizontal = 16.dp)) {
                    TacticalUserRow(
                        name = contact.name,
                        ip = if (isOnline) contact.ip else "Unreachable",
                        status = if (contact.isPriority) "Guardian" else "Saved",
                        isOnline = isOnline,
                        onRadioClick = { viewModel.setTarget(contact.name); onConnected() },
                        // [MOTHERSHIP UPGRADE] Passing IP and Name
                        onCallClick = { CallSignaling.startOutgoingCall(contact.ip, contact.name) },
                        onWakeClick = { viewModel.sendCloudWakeUp(context, contact) }
                    )
                }
            }
        }
    }

    if (showChannelQrDialog) {
        AlertDialog(
            onDismissRequest = { showChannelQrDialog = false },
            title = { Text("Channel Frequency") },
            text = { Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) { Text(viewModel.sharingChannelName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Spacer(Modifier.height(16.dp)); viewModel.channelQrBitmap?.let { Image(bitmap = it.asImageBitmap(), contentDescription = "Channel QR", modifier = Modifier.size(200.dp)) } } },
            confirmButton = { TextButton(onClick = { showChannelQrDialog = false }) { Text("Close") } }
        )
    }
}

// --- TACTICAL ROW COMPONENT ---
@Composable
fun TacticalUserRow(
    name: String, ip: String, status: String, isOnline: Boolean,
    onRadioClick: () -> Unit, onCallClick: () -> Unit, onWakeClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(name.take(1).uppercase(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if(isOnline) Icons.Default.Wifi else Icons.Default.WifiOff, null, modifier = Modifier.size(10.dp), tint = if(isOnline) Color(0xFF43A047) else Color.Gray)
                    Spacer(Modifier.width(4.dp))
                    Text(ip, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
            }
            Row {
                FilledTonalIconButton(onClick = onWakeClick, modifier = Modifier.size(40.dp), colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Color(0xFFFFF8E1), contentColor = Color(0xFFFFA000))) {
                    Icon(Icons.Default.Bolt, "Wake")
                }
                Spacer(Modifier.width(8.dp))
                FilledTonalIconButton(onClick = onRadioClick, modifier = Modifier.size(40.dp), colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Icon(Icons.Default.GraphicEq, "Radio", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(8.dp))
                FilledTonalIconButton(
                    onClick = onCallClick,
                    enabled = isOnline,
                    modifier = Modifier.size(40.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = if(isOnline) Color(0xFF43A047) else Color.Gray,
                        contentColor = Color.White,
                        disabledContainerColor = Color.LightGray.copy(alpha=0.5f)
                    )
                ) { Icon(Icons.Default.Call, "Call") }
            }
        }
    }
}