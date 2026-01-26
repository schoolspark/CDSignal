package `in`.chinmoydas.signal.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(navController: NavController) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("User Manual") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // --- 1. NEW TRIGGER MODES ---
            HelpCard(
                title = "🎙️ PTT TRIGGER MODES",
                icon = Icons.Default.SettingsRemote,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Text("Located below the main Red Button:", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))

                // Mode A: Standard
                StatusDotRow(MaterialTheme.colorScheme.primary, "Standard Mode", "Press & Hold the big Red Button to talk. Release to listen.")

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = Color.Gray.copy(alpha=0.3f))
                Spacer(Modifier.height(8.dp))

                // Mode B: Tap Mode
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LockOpen, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Handsfree / Tap Mode", fontWeight = FontWeight.Bold)
                }
                Text("Tap the 'Handsfree' chip to enable. Now, tap the Red Button ONCE to start transmitting. Tap again to stop (Latch Logic).", style = MaterialTheme.typography.bodySmall)

                Spacer(Modifier.height(8.dp))

                // Mode C: Pocket Mode
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.HeadsetMic, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Pocket Mode (Hardware Keys)", fontWeight = FontWeight.Bold)
                }
                Text("Tap 'Pocket Mode' to link your Volume Keys or Headset Button. You can now PTT while the screen is OFF or the phone is in your pocket.", style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(16.dp))

            // --- 2: CLOUD WAKE (v5.3.0) ---
            HelpCard(
                title = "⚡ CLOUD WAKE / KICKER",
                icon = Icons.Default.Bolt,
                color = Color(0xFFFFF8E1) // Light Amber
            ) {
                Text("How to reach offline users:", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Step(1, "If a contact shows a Red Dot (Offline), wait 2 seconds.")
                Step(2, "An Amber Button 'WAKE DEVICE (CLOUD)' will appear.")
                Step(3, "Tap it to send a high-priority signal via Google Servers.")
                Step(4, "The other phone will wake up, start the radio, and turn Green.")
                Text("Note: Both users must have Internet for this to work.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }

            Spacer(Modifier.height(16.dp))

            // --- 3. GUARDIAN MODE (UPDATED) ---
            HelpCard(
                title = "🛡️ GUARDIAN MODE (REMOTE)",
                icon = Icons.Default.Security,
                color = MaterialTheme.colorScheme.errorContainer
            ) {
                Text("Emergency Remote Control for families.", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))

                Text("How to Authorize a Guardian:", fontWeight = FontWeight.Bold)
                Step(1, "Go to the 'Connect' Tab.")
                Step(2, "Find a trusted contact (Spouse/Parent).")
                Step(3, "Tap the Star Icon (⭐) next to their name.")
                Text("Only 'Starred' contacts can send remote commands.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)

                Spacer(Modifier.height(12.dp))
                Text("Available Commands:", fontWeight = FontWeight.Bold)
                StatusDotRow(Color.Black, "Remote Listen-In", "Forces Mic ON to check safety.")
                StatusDotRow(Color.Blue, "Silent Tracking", "Pings GPS location silently.")
                StatusDotRow(Color.DarkGray, "Remote Silence", "Silences speaker remotely.")
                StatusDotRow(Color(0xFF2E7D32), "Remote Restore", "Resets device to normal (Un-mute/Un-Vox).")
            }

            Spacer(Modifier.height(16.dp))

            // --- 4. SAFETY TOOLS ---
            HelpCard(
                title = "⛑️ SAFETY & DEFENCE TOOLS",
                icon = Icons.Default.Shield,
                color = MaterialTheme.colorScheme.tertiaryContainer
            ) {
                Text("The 5 Buttons in the Grey Card:", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                StatusDotRow(Color.Black, "Stealth (Moon)", "Disables Speaker & Vibration. Audio is routed to Earpiece only.")
                StatusDotRow(Color.DarkGray, "VOX (Voice)", "Voice Activation. Transmits automatically when you speak.")
                StatusDotRow(Color.Blue, "Shield (Bike)", "Impact Sensor. Detects crashes/falls and triggers Auto-SOS.")
                StatusDotRow(Color.Magenta, "Trace (Pin)", "Broadcasts your current GPS Location to all contacts.")
                StatusDotRow(Color.Red, "SOS (Warning)", "Panic Button. Sends 'Distress Signal' + 'Location' to everyone.")
            }

            Spacer(Modifier.height(16.dp))

            // --- 5. SECURE CALLS ---
            HelpCard(
                title = "📞 SECURE CALLS",
                icon = Icons.Default.Call,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text("Start a private, full-duplex call:", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Step(1, "Select a Target User from the list.")
                Step(2, "Tap the Green 'CALL' FAB (Bottom Right).")
                Step(3, "If accepted, PTT is disabled and you can talk normally.")
            }

            Spacer(Modifier.height(16.dp))

            // --- 6. SILENT TEXT ---
            HelpCard(
                title = "💬 SILENT TEXT MESSAGES",
                icon = Icons.Default.Keyboard,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text("Send encrypted text over UDP without speaking:", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Step(1, "Tap the Keyboard Icon (⌨️) on the Radio screen.")
                Step(2, "Type your message and tap SEND.")
                Text("• If Receiver is LOUD: Phone speaks the text (TTS).", style = MaterialTheme.typography.bodySmall)
                Text("• If Receiver is SILENT: Message saves to History.", style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(16.dp))

            // --- 7. ENCRYPTION & KEYS ---
            HelpCard(
                title = "🔐 SECURE PAIRING",
                icon = Icons.Default.QrCodeScanner,
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Text("Required to hear audio:", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Step(1, "Go to 'Connect' tab -> 'Scan QR Code'.")
                Step(2, "Scan your friend's QR to save their Key.")
                Step(3, "CRITICAL:", isBold = true)
                Text("Pairing is ONE-WAY. They must also scan YOU, otherwise they will hear static/silence.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(16.dp))

            // --- 8. OFFLINE MODE ---
            HelpCard(
                title = "📡 OFFLINE / LAN MODE",
                icon = Icons.Default.Wifi,
                color = MaterialTheme.colorScheme.errorContainer
            ) {
                Text("Use this when you have a Router but NO Internet.", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Step(1, "Connect all phones to the same WiFi.")
                Step(2, "Tap 'Offline Mode' on the login screen.")
                Step(3, "Tap the Group Icon (Top Right) to broadcast to everyone.")
            }

            Spacer(Modifier.height(16.dp))

            // --- 9. STATUS INDICATORS ---
            HelpCard(
                title = "🚦 STATUS LIGHTS",
                icon = Icons.Default.Info,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                StatusDotRow(Color.Green, "Green Dot", "Online. PTT path is open.")
                StatusDotRow(Color.Yellow, "Yellow Dot", "Checking Network...")
                StatusDotRow(Color.Red, "Red Dot", "Offline. Look for 'Wake' button.") // Updated
                StatusDotRow(Color(0xFFD32F2F), "Red Pulse", "Transmitting (On Air).")
            }

            Spacer(Modifier.height(16.dp))

            // --- 10. TROUBLESHOOTING ---
            Text("Troubleshooting", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))

            Text("• Remote Commands Failed? Ensure 'Guardian Mode' is ON in Profile and sender is a trusted contact.", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Text("• SOS Location Missing? Ensure 'Location' permission is set to 'Allow all the time'.")
            Text("• PTT cut off? Disable 'Battery Saver' for this app in Android Settings.")
            Text("• Echo? Don't test with two phones in the same room.")

            Spacer(Modifier.height(32.dp))
        }
    }
}

// --- HELPER COMPONENTS ---

@Composable
fun HelpCard(title: String, icon: ImageVector, color: Color, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = color),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null)
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
fun Step(num: Int, text: String, isBold: Boolean = false) {
    Row(Modifier.padding(vertical = 4.dp)) {
        Text("$num.", fontWeight = FontWeight.Bold, modifier = Modifier.width(24.dp))
        Text(text, fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
fun StatusDotRow(color: Color, title: String, desc: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Box(Modifier.size(12.dp).background(color, CircleShape))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            Text(desc, style = MaterialTheme.typography.bodySmall)
        }
    }
}