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
            // --- SECTION 1: SECURE P2P PAIRING ---
            HelpCard(
                title = "🔐 SECURE P2P PAIRING",
                icon = Icons.Default.QrCodeScanner,
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Text("For private, encrypted calls between two people:", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Step(1, "Go to 'Connect' tab and tap 'Scan QR Code'.")
                Step(2, "Scan your friend's Personal QR to save their 'Key'.")
                Step(3, "IMPORTANT:", isBold = true)
                Text("Pairing is one-way! Your friend MUST scan you back, or they won't be able to decrypt your voice.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(16.dp))

            // --- SECTION 2: SILENT TEXT MESSAGES (NEW) ---
            HelpCard(
                title = "💬 SILENT TEXT MESSAGES",
                icon = Icons.Default.Keyboard,
                color = MaterialTheme.colorScheme.tertiaryContainer
            ) {
                Text("Send encrypted text over UDP without speaking:", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Step(1, "Tap the Keyboard Icon (⌨️) on the Radio screen.")
                Step(2, "Type your message and tap SEND.")
                Step(3, "Receiver Behavior:", isBold = true)
                Text("• If Receiver is LOUD: Phone speaks the text (TTS).", style = MaterialTheme.typography.bodySmall)
                Text("• If Receiver is SILENT: Message saves to Voice Pager.", style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(16.dp))

            // --- SECTION 3: STATUS INDICATORS ---
            HelpCard(
                title = "🚦 STATUS LIGHTS",
                icon = Icons.Default.Info,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                StatusDotRow(Color.Green, "Green Dot", "Connected. Audio Received & Path Open.")
                StatusDotRow(Color.Yellow, "Yellow Dot", "Checking Health / Waking up peer...")
                StatusDotRow(Color.Gray, "Gray Dot", "Idle / Offline. Select a user to ping.")

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
                Spacer(Modifier.height(8.dp))

                StatusDotRow(MaterialTheme.colorScheme.primary, "Blue Button", "Ready. Hold to Talk.")
                StatusDotRow(MaterialTheme.colorScheme.error, "Red Button", "Transmitting (ON AIR).")
            }

            Spacer(Modifier.height(16.dp))

            // --- SECTION 4: VOICE PAGER ---
            HelpCard(
                title = "📟 VOICE PAGER (LOGS)",
                icon = Icons.Default.NotificationsOff,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text("Tap the 'Silent Mode' (Bell) icon to mute live audio.", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("Incoming Audio & Text will be saved to the Pager List below the PTT button. Tap any item to Play/Speak it. Items vanish after playing (Burn-on-Read).")
            }

            Spacer(Modifier.height(16.dp))

            // --- SECTION 5: OFFLINE / LAN MODE ---
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
                Step(4, "Security Note:", isBold = true)
                Text("Unpaired users can be heard on the Public Channel. For privacy in Offline Mode, Scan their QR Code.", style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(16.dp))

            // --- SECTION 6: MAKING CALLS ---
            HelpCard(
                title = "👤 MAKING CALLS (INTERNET)",
                icon = Icons.Default.Person,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Step(1, "Select a person from your contacts.")
                Step(2, "Wait for the Status Dot to turn GREEN or YELLOW.")
                Step(3, "The 'Double Punch':", isBold = true)
                Text("If the dot stays Gray, BOTH users should press the Talk button once to wake up the connection.", modifier = Modifier.padding(start = 24.dp))
            }

            Spacer(Modifier.height(16.dp))

            // --- SECTION 7: TROUBLESHOOTING ---
            Text("Troubleshooting", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))

            Text("• Incoming call but NO SOUND? You are missing their Key. Scan their QR Code.", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))

            Text("• Status stays Gray? Press the Talk button once to wake up the peer.")
            Text("• Screeching? Keep phones 2-3 meters apart.")
            Text("• No devices found? Ensure 'AP Isolation' is OFF in your Router.")

            Spacer(Modifier.height(32.dp))
        }
    }
}

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