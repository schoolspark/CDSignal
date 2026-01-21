package `in`.chinmoydas.signal.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import `in`.chinmoydas.signal.viewmodel.WalkieViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectTab(modifier: Modifier = Modifier, viewModel: WalkieViewModel, onConnected: () -> Unit) {
    val context = LocalContext.current
    var codeInput by remember { mutableStateOf("") }
    var inputName by remember { mutableStateOf("") }
    var isGroupMode by remember { mutableStateOf(false) }
    var showChannelQrDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val filteredContacts = remember(searchQuery, viewModel.savedContacts) {
        if (searchQuery.isBlank()) viewModel.savedContacts.toList()
        else viewModel.savedContacts.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

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

    LaunchedEffect(Unit) { viewModel.startLocalDiscovery(context) }
    DisposableEffect(Unit) { onDispose { viewModel.stopLocalDiscovery() } }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {

        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search contacts...") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                singleLine = true
            )
        }

        if (filteredContacts.isNotEmpty()) {
            item {
                Column {
                    Text("Speed Dial", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        items(filteredContacts) { contact ->
                            val isGroup = contact.name.startsWith("group:")
                            val displayName = if (isGroup) contact.name.substringAfter(":") else contact.name
                            var showMenu by remember { mutableStateOf(false) }

                            ElevatedCard(
                                onClick = { viewModel.setTarget(contact.name); onConnected() },
                                colors = CardDefaults.elevatedCardColors(
                                    containerColor = if (viewModel.targetUser == contact.name) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp).widthIn(min = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box {
                                        Icon(if (isGroup) Icons.Default.Groups else Icons.Default.Person, null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)

                                        // [NEW] Principal Star Badge
                                        if (contact.isPriority) {
                                            Icon(
                                                imageVector = Icons.Default.Star,
                                                contentDescription = "Principal",
                                                tint = Color.Yellow,
                                                modifier = Modifier.size(16.dp).align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp)
                                            )
                                        }

                                        Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures(onLongPress = { showMenu = true }) { viewModel.setTarget(contact.name); onConnected() } })
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Text(displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)

                                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                        DropdownMenuItem(text = { Text("Delete") }, onClick = { viewModel.deleteContact(contact.name); showMenu = false }, leadingIcon = { Icon(Icons.Default.Delete, null) })
                                        if (isGroup) {
                                            DropdownMenuItem(text = { Text("Share QR") }, onClick = { viewModel.generateChannelQr(contact.name, contact.savedCode); showChannelQrDialog = true; showMenu = false }, leadingIcon = { Icon(Icons.Default.QrCode, null) })
                                        } else {
                                            // [NEW] Principal Toggle
                                            DropdownMenuItem(
                                                text = { Text(if(contact.isPriority) "Unset Principal" else "Set as Principal") },
                                                onClick = { viewModel.togglePriority(contact.name); showMenu = false },
                                                leadingIcon = { Icon(Icons.Default.Star, null, tint = if(contact.isPriority) Color.Yellow else Color.Gray) }
                                            )
                                            DropdownMenuItem(text = { Text("Block") }, onClick = { viewModel.blockContact(contact.name); showMenu = false }, leadingIcon = { Icon(Icons.Default.Block, null, tint = Color.Red) })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            Column {
                Text("Add New Connection", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            SegmentedButton(selected = !isGroupMode, onClick = { isGroupMode = false }, shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp), icon = { Icon(Icons.Default.Person, null) }) { Text("Person") }
                            SegmentedButton(selected = isGroupMode, onClick = { isGroupMode = true }, shape = RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp), icon = { Icon(Icons.Default.Groups, null) }) { Text("Channel") }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = inputName,
                                onValueChange = { inputName = it },
                                label = { Text(if (isGroupMode) "Channel Name" else "Username") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                trailingIcon = { IconButton(onClick = { scanLauncher.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)) }) { Icon(Icons.Default.QrCodeScanner, "Scan", tint = MaterialTheme.colorScheme.primary) } }
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(value = codeInput, onValueChange = { if (it.length <= 8) codeInput = it }, label = { Text(if (isGroupMode) "Passkey" else "User PIN") }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = if (isGroupMode) KeyboardType.Text else KeyboardType.Number))
                            Spacer(Modifier.width(8.dp))
                            Button(
                                enabled = inputName.isNotBlank() && codeInput.isNotBlank(),
                                onClick = {
                                    val finalName = if (isGroupMode) "group:$inputName" else inputName
                                    if (finalName.isNotBlank() && codeInput.isNotBlank()) {
                                        viewModel.saveInternetContact(finalName, codeInput, { Toast.makeText(context, "Saved!", Toast.LENGTH_SHORT).show(); inputName = ""; codeInput = "" }, { Toast.makeText(context, "Connection Failed", Toast.LENGTH_SHORT).show() })
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.height(56.dp)
                            ) { Text("Add"); Spacer(Modifier.width(4.dp)); Icon(Icons.Default.AddLink, null) }
                        }
                    }
                }
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.WifiTethering, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Nearby Devices (Local Network)", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.secondary)
            }
        }

        if (viewModel.nearbyUsers.isNotEmpty()) {
            items(viewModel.nearbyUsers) { user ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column { Text(user.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge); Text(user.ip, style = MaterialTheme.typography.labelSmall, color = Color.Gray) }
                        FilledTonalButton(onClick = { viewModel.addContact(user.name, user.ip, ""); onConnected() }) { Text("Connect") }
                    }
                }
            }
        } else {
            item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = Color.LightGray); Spacer(Modifier.height(8.dp)); Text("Scanning local network...", style = MaterialTheme.typography.bodySmall, color = Color.Gray) }
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