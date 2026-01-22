package `in`.chinmoydas.signal.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import `in`.chinmoydas.signal.viewmodel.WalkieViewModel
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun HistoryTab(modifier: Modifier = Modifier, viewModel: WalkieViewModel) {
    val context = LocalContext.current
    val callLogs by viewModel.callLogs.collectAsState()
    val pagerEntries by viewModel.pagerEntries.collectAsState()
    val sdf = remember { SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()) }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Activity & Messages", style = MaterialTheme.typography.headlineMedium)
            IconButton(onClick = { viewModel.clearHistory(); viewModel.clearPagerHistory() }) {
                Icon(Icons.Default.DeleteSweep, "Clear Logs", tint = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.height(16.dp))

        if (callLogs.isEmpty() && pagerEntries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No recent activity", color = Color.Gray) }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {

                // 1. Pager Entries (Audio/Text)
                if (pagerEntries.isNotEmpty()) {
                    item {
                        Text("Unread Messages", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                    }
                    items(pagerEntries) { entry ->
                        val isText = entry.type == "TEXT"
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Row(modifier = Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("From: ${entry.sender}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                                    Text(if(isText) "Text: ${entry.content}" else "Audio Clip", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer, maxLines = 1)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { viewModel.deletePagerEntry(entry) }) {
                                        Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.onErrorContainer)
                                    }
                                    Spacer(Modifier.width(4.dp))

                                    // [FIXED] Burn-on-Read: Auto-delete on play/read
                                    Button(onClick = {
                                        viewModel.playEntry(context, entry, null)
                                        viewModel.deletePagerEntry(entry)
                                    }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onErrorContainer)) {
                                        Icon(if(isText) Icons.Default.Message else Icons.Default.PlayArrow, "Play", tint = MaterialTheme.colorScheme.errorContainer)
                                    }
                                }
                            }
                        }
                    }
                    item { HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp), thickness = 2.dp) }
                }

                // 2. Call Logs
                if (callLogs.isNotEmpty()) {
                    item { Text("Call History", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp)) }
                    items(callLogs) { log ->
                        var showMenu by remember { mutableStateOf(false) }

                        val isPrincipal = viewModel.savedContacts.find { it.name == log.callerName }?.isPriority == true

                        ListItem(
                            headlineContent = { Text(if (log.callerName.startsWith("group:")) log.callerName.substringAfter(":") else log.callerName, fontWeight = FontWeight.Bold) },
                            supportingContent = { Text(sdf.format(Date(log.timestamp))) },
                            leadingContent = { Icon(if (log.isIncoming) Icons.Default.CallReceived else Icons.Default.CallMade, null, tint = if (log.isIncoming) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary) },
                            trailingContent = {
                                Box {
                                    IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, "Options") }
                                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                        DropdownMenuItem(text = { Text("Connect") }, onClick = { viewModel.setTarget(log.callerName); showMenu = false }, leadingIcon = { Icon(Icons.Default.Chat, null) })
                                        if (!log.callerName.startsWith("group:")) {
                                            DropdownMenuItem(
                                                text = { Text(if(isPrincipal) "Unset Principal" else "Set as Principal") },
                                                onClick = { viewModel.togglePriority(log.callerName); showMenu = false },
                                                leadingIcon = { Icon(Icons.Default.Star, null, tint = if(isPrincipal) Color.Yellow else Color.Gray) }
                                            )
                                            DropdownMenuItem(text = { Text("Block User") }, onClick = { viewModel.blockContact(log.callerName); showMenu = false }, leadingIcon = { Icon(Icons.Default.Block, null, tint = Color.Red) })
                                        }
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