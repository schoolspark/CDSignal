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
                title = { Text("How to Use CD Signal") },
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
            // --- 1. NEW FEATURES (TOP PRIORITY) ---

            // Safety Triad
            HelpCard(
                title = "🛡️ SAFETY TRIAD (TACTICAL)",
                icon = Icons.Default.Shield,
                color = MaterialTheme.colorScheme.errorContainer
            ) {
                Text("Located at the bottom of the Radio Screen:", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                StatusDotRow(Color.Red, "SOS Button", "Broadcasts an immediate 'Distress Signal' to your target.")
                StatusDotRow(Color.Gray, "Impact Shield", "Sensors detect crashes/falls. Starts 5s countdown before auto-SOS.")
                StatusDotRow(MaterialTheme.colorScheme.primary, "Signal Trace", "Sends your current static location pin (Lat/Long).")
                StatusDotRow(Color.Black, "Stealth Mode", "Disables Speaker & Vibration. Audio -> Earpiece only.")
            }

            Spacer(Modifier.height(16.dp))

            // Secure Calls
            HelpCard(
                title = "📞 SECURE VOICE CALLS",
                icon = Icons.Default.Call,
                color = MaterialTheme.colorScheme.tertiaryContainer
            ) {
                Text("Full-Duplex (Phone Style) vs. Radio (PTT):", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Step(1, "Go to the 'Connect' Tab.")
                Step(2, "Tap the PHONE ICON next to a user.")
                Step(3, "This starts a private call.")
                Text("Note: PTT is disabled during a phone call.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }

            Spacer(Modifier.height(16.dp))

            // VOX Pro
            HelpCard(
                title = "🎙️ VOX PRO (HANDS-FREE)",
                icon = Icons.Default.RecordVoiceOver,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text("Transmit without pressing buttons:", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("Tap the 'VOX' icon in the Safety Toolbar. The app monitors mic levels and transmits when you speak. Best used with a headset.")
            }

            Spacer(Modifier.height(16.dp))

            // --- 2. CORE FEATURES (PRESERVED) ---

            // Secure P2P
            HelpCard(
                title = "🔐 SECURE KEY EXCHANGE",
                icon = Icons.Default.QrCodeScanner,
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Text("For private, encrypted communication:", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Step(1, "Go to 'Connect' tab and tap 'Scan QR Code'.")
                Step(2, "Scan your friend's Personal QR to save their 'Key'.")
                Step(3, "IMPORTANT:", isBold = true)
                Text("Pairing is one-way! Your friend MUST scan you back, or they won't be able to decrypt your voice.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(16.dp))

            // Silent Text
            HelpCard(
                title = "💬 SILENT TEXT MESSAGES",
                icon = Icons.Default.Keyboard,
                color = MaterialTheme.colorScheme.tertiaryContainer
            ) {
                Text("Send encrypted text over UDP without speaking:", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Step(1, "Tap the Keyboard Icon (⌨️) on the Radio screen.")
                Step(2, "Type your message and tap SEND.")
                Text("• If Receiver is LOUD: Phone speaks the text (TTS).", style = MaterialTheme.typography.bodySmall)
                Text("• If Receiver is SILENT: Message saves to History.", style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(16.dp))

            // Status Indicators
            HelpCard(
                title = "🚦 STATUS LIGHTS",
                icon = Icons.Default.Info,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                StatusDotRow(Color.Green, "Green Dot", "Connected. Audio Received & Path Open.")
                StatusDotRow(Color.Yellow, "Yellow Dot", "Checking Health / Waking up peer.")
                StatusDotRow(Color.Gray, "Gray Dot", "Idle / Offline. Select a user to ping.")
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
                Spacer(Modifier.height(8.dp))
                StatusDotRow(MaterialTheme.colorScheme.primary, "Blue Button", "Ready. Hold to Talk.")
                StatusDotRow(MaterialTheme.colorScheme.error, "Red Button", "Transmitting (ON AIR).")
            }

            Spacer(Modifier.height(16.dp))

            // History & Pager (Merged)
            HelpCard(
                title = "📜 HISTORY & CALLBACK",
                icon = Icons.Default.History,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text("Review past activity and messages:", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Step(1, "Go to 'History' Tab.")
                Step(2, "Tap 'Play' on messages to listen. They auto-delete after playing (Burn-on-Read).")
                Step(3, "Tap the 'Three Dots' on a log to Call Back or Connect via PTT.")
            }

            Spacer(Modifier.height(16.dp))

            // Offline Mode
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

            // --- 3. TROUBLESHOOTING (MERGED) ---
            Text("Troubleshooting", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))

            Text("• Incoming call but NO SOUND? You are missing their Key. Scan their QR Code.", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("• Call Fails? Ensure the user is Online (Green/Yellow dot).")
            Text("• Status stays Gray? Press the Talk button once to wake up the peer.")
            Text("• Screeching? Keep phones 2-3 meters apart.")
            Text("• No devices found? Ensure 'AP Isolation' is OFF in your Router.")

            Spacer(Modifier.height(32.dp))
        }
    }
}

// --- HELPER COMPONENTS (PRESERVED) ---

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