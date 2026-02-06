package `in`.chinmoydas.signal.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import `in`.chinmoydas.signal.VoiceService
import `in`.chinmoydas.signal.viewmodel.WalkieViewModel

@Composable
fun ProfileTab(modifier: Modifier = Modifier, navController: NavController, myName: String, viewModel: WalkieViewModel, onLogout: () -> Unit, onExit: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("WalkiePrefs", Context.MODE_PRIVATE)
    var showEmailDialog by remember { mutableStateOf(false) }
    val recoveryEmail by viewModel.recoveryEmail.collectAsState()
    var showBlockedDialog by remember { mutableStateOf(false) }
    var showGuardianConsent by remember { mutableStateOf(false) }
    val myCode by viewModel.myPairingCode.collectAsState()

    // Data Saver State
    var isDataSaver by remember { mutableStateOf(prefs.getBoolean("data_saver", false)) }

    // Secure Mode State
    var isSecureMode by remember { mutableStateOf(prefs.getBoolean("secure_mode", false)) }

    // Guardian Mode State
    var isRemoteAllowed by remember { mutableStateOf(prefs.getBoolean("allow_remote_control", false)) }

    // Get App Version
    val appVersion = remember {
        try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            "v${pInfo.versionName}"
        } catch (e: Exception) { "Unknown Version" }
    }

    LaunchedEffect(myCode) { if (myCode.isNotBlank()) viewModel.qrBitmap = viewModel.generateQr("$myName|$myCode") }

    Column(modifier = modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Top) {

        // --- 1. ID CARD ---
        Card(elevation = CardDefaults.cardElevation(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("MY FREQUENCY ID", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                Text(myName, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))

                Text("PAIRING PIN", style = MaterialTheme.typography.labelSmall, color = Color.Gray)

                var isPinVisible by remember { mutableStateOf(false) }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (isPinVisible) myCode else "••••",
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 4.sp
                    )
                    IconButton(onClick = { isPinVisible = !isPinVisible }) {
                        val icon = if (isPinVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility
                        Icon(icon, contentDescription = "Toggle PIN", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Pairing PIN", myCode)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "PIN Copied", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.ContentCopy, "Copy PIN", tint = MaterialTheme.colorScheme.primary)
                    }
                }

                TextButton(onClick = { viewModel.resetPairingCode(myName) }) {
                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Reset PIN")
                }
                Spacer(Modifier.height(16.dp))
                viewModel.qrBitmap?.let { Image(bitmap = it.asImageBitmap(), contentDescription = "QR", modifier = Modifier.size(180.dp)) }
            }
        }

        Spacer(Modifier.height(16.dp))

        // --- 2. SETTINGS TOGGLES ---

        // A. Data Saver (Updated with Icon)
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha=0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    // [FIX] Added Icon Row
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.SignalCellularAlt, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Data Saver Mode (4G)", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    }
                    Text(
                        if (isDataSaver) "Audio compressed on mobile data." else "High Quality Audio on all networks.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = isDataSaver,
                    onCheckedChange = {
                        isDataSaver = it
                        prefs.edit().putBoolean("data_saver", it).apply()
                    }
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // B. Secure Channel
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha=0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Security, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Secure Channel", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    }
                    Text(
                        if (isSecureMode) "Encrypted. Only paired users can hear." else "Raw Audio. Fastest, but public.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = isSecureMode,
                    onCheckedChange = {
                        isSecureMode = it
                        prefs.edit().putBoolean("secure_mode", it).apply()
                    }
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // C. Guardian Mode
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isRemoteAllowed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha=0.5f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Shield, null, modifier = Modifier.size(16.dp), tint = if(isRemoteAllowed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.width(8.dp))
                        Text("Guardian Mode", fontWeight = FontWeight.Bold, color = if(isRemoteAllowed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    }
                    Text(
                        if (isRemoteAllowed) "ACTIVE. Trusted users can access Mic." else "DISABLED. Remote commands blocked.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = isRemoteAllowed,
                    onCheckedChange = { check ->
                        if (check) {
                            showGuardianConsent = true
                            isRemoteAllowed = true // Optimistic update, reverted if cancelled
                        } else {
                            isRemoteAllowed = false
                            prefs.edit().putBoolean("allow_remote_control", false).apply()
                        }
                    },
                    colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.error, checkedTrackColor = MaterialTheme.colorScheme.errorContainer)
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        // D. Eco Mode
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha=0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.BatterySaver, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Eco Mode (Trekking)", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    }
                    Text(
                        "Stops background data. Saves battery. Wakes up only for incoming calls (2s delay).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                var isEco by remember { mutableStateOf(prefs.getBoolean("eco_mode", false)) }
                Switch(
                    checked = isEco,
                    onCheckedChange = {
                        isEco = it
                        prefs.edit().putBoolean("eco_mode", it).apply()
                        // Helper to send Intent to Service
                        val intent = Intent(context, VoiceService::class.java).apply {
                            action = "TOGGLE_ECO"
                            putExtra("state", it)
                        }
                        context.startService(intent)
                    }
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // --- NEW: RECOVERY EMAIL CARD ---
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha=0.3f)),
            modifier = Modifier.fillMaxWidth().clickable { showEmailDialog = true }
        ) {
            Row(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Email, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Recovery Email", fontWeight = FontWeight.Bold)
                    }
                    Text(
                        recoveryEmail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(Icons.Default.Edit, contentDescription = "Edit Email", modifier = Modifier.size(20.dp), tint = Color.Gray)
            }
        }

        Spacer(Modifier.height(24.dp))

        // --- 3. ACTIONS ---

        OutlinedButton(onClick = { showBlockedDialog = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.PrivacyTip, null); Spacer(Modifier.width(8.dp)); Text("Blocked Users")
        }

        Spacer(Modifier.height(8.dp))

        OutlinedButton(onClick = { navController.navigate("diagnostics") }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Build, null); Spacer(Modifier.width(8.dp)); Text("Run System Diagnostics")
        }

        Spacer(Modifier.height(8.dp))

        OutlinedButton(onClick = { navController.navigate("info") }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Info, null); Spacer(Modifier.width(8.dp)); Text("About & Legal Info")
        }

        Spacer(Modifier.height(32.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(onClick = onLogout, modifier = Modifier.weight(1f)) {
                Icon(Icons.AutoMirrored.Filled.ExitToApp, null); Spacer(Modifier.width(8.dp)); Text("Logout")
            }
            Button(onClick = onExit, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                Icon(Icons.Default.PowerSettingsNew, null); Spacer(Modifier.width(8.dp)); Text("Exit App")
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(appVersion, style = MaterialTheme.typography.labelMedium, color = Color.Gray)

        Spacer(Modifier.height(50.dp))
    }

    // --- DIALOGS ---

    // 1. Blocked Users Dialog
    if (showBlockedDialog) {
        AlertDialog(
            onDismissRequest = { showBlockedDialog = false },
            title = { Text("Blocked Users") },
            text = {
                if (viewModel.blockedContacts.isEmpty()) Text("No blocked users.")
                else LazyColumn { items(viewModel.blockedContacts) { contact -> Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text(contact.name); TextButton(onClick = { viewModel.unblockContact(contact.name) }) { Text("Unblock") } } } }
            },
            confirmButton = { TextButton(onClick = { showBlockedDialog = false }) { Text("Close") } }
        )
    }

    // 2. Guardian Consent Dialog
    if (showGuardianConsent) {
        AlertDialog(
            onDismissRequest = {
                // [FIX] If cancelled, revert the switch
                showGuardianConsent = false
                isRemoteAllowed = false
            },
            icon = { Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Enable Guardian Mode?", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("This feature allows trusted contacts (Principals) to remotely activate your Microphone and GPS during emergencies.")
                    Spacer(Modifier.height(8.dp))
                    Text("• You will be notified whenever remote access occurs.", fontWeight = FontWeight.Bold)
                    Text("• Only users you have saved as Principals can use this.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isRemoteAllowed = true
                        prefs.edit().putBoolean("allow_remote_control", true).apply()
                        showGuardianConsent = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("I UNDERSTAND (ENABLE)")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    // [FIX] If cancelled, revert the switch
                    showGuardianConsent = false
                    isRemoteAllowed = false
                }) { Text("CANCEL") }
            }
        )
    }

    // 3. RECOVERY EMAIL DIALOG
    if (showEmailDialog) {
        var emailInput by remember { mutableStateOf(if(recoveryEmail == "Not Set") "" else recoveryEmail) }

        AlertDialog(
            onDismissRequest = { showEmailDialog = false },
            title = { Text("Update Recovery Email") },
            text = {
                Column {
                    Text(
                        "Data Privacy: Optional. Used securely for account recovery.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    OutlinedTextField(
                        value = emailInput,
                        onValueChange = { emailInput = it },
                        label = { Text("Email Address") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (recoveryEmail != "Not Set" && recoveryEmail.isNotBlank()) {
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(
                            onClick = {
                                viewModel.saveRecoveryEmail(context, "") { showEmailDialog = false }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("REMOVE LINKED EMAIL")
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.saveRecoveryEmail(context, emailInput) { showEmailDialog = false }
                }) { Text("SAVE") }
            },
            dismissButton = {
                TextButton(onClick = { showEmailDialog = false }) { Text("CANCEL") }
            }
        )
    }
}