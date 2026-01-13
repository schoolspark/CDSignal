package `in`.chinmoydas.signal.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            // --- SECTION 1: INSTANT CHANNEL JOINING ---
            HelpCard(
                title = "📲 INSTANT CHANNELS",
                icon = Icons.Default.QrCodeScanner,
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Text("Joining a frequency is now easier than ever!", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Step(1, "Go to 'Connect' tab and tap 'Scan QR Code'.")
                Step(2, "Scan another user's Channel QR to join instantly.")
                Step(3, "To share: find your Channel in 'My Contacts', tap the arrow, and select 'Share Channel QR'.")
            }

            Spacer(Modifier.height(16.dp))

            // --- SECTION 2: STATUS INDICATORS ---
            HelpCard(
                title = "🚦 STATUS LIGHTS & INDICATORS",
                icon = Icons.Default.Info,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text("Understand exactly what is happening:", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))

                StatusDotRow(Color.Green, "Green Dot", "Connected. Audio Received & Path Open.")
                StatusDotRow(Color.Yellow, "Yellow Dot", "Signaling. Waking up the peer...")
                StatusDotRow(Color.Red, "Red Dot", "Standby. Press PTT to wake up.")

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
                Spacer(Modifier.height(8.dp))

                StatusDotRow(MaterialTheme.colorScheme.primary, "Blue Button", "Ready. Hold to Talk.")
                StatusDotRow(MaterialTheme.colorScheme.error, "Red Button (ON AIR)", "Transmitting. You are live.")

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    Icon(Icons.Default.Lock, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Handsfree Mode", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        Text("Tap 'Handsfree' to switch modes. Tap once to talk, tap again to stop.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // --- SECTION 3: VOICE PAGER & POCKET MODE ---
            HelpCard(
                title = "📟 VOICE PAGER & POCKET MODE",
                icon = Icons.Default.NotificationsOff,
                color = MaterialTheme.colorScheme.tertiaryContainer
            ) {
                Text("Never miss a message even when you're busy.", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Step(1, "Tap the 'Silent Mode' (Bell) icon on the Radio screen.")
                Step(2, "When someone talks, the app stays silent but RECORDS the message.")
                Step(3, "Go to the 'History' tab to see missed messages in RED.")
                Spacer(Modifier.height(8.dp))
                Text("🎧 Pocket Mode:", fontWeight = FontWeight.Bold)
                Step(4, "Click your Wired or Bluetooth Headset button to talk, even if the phone is in your pocket.")
                Step(5, "Tip: Use 'Detach Keys' in the notification to let your music app take back control.")
            }

            Spacer(Modifier.height(16.dp))

            // --- SECTION 4: EVENT / OFFLINE MODE ---
            HelpCard(
                title = "📢 EVENT / OFFLINE MODE",
                icon = Icons.Default.Wifi,
                color = MaterialTheme.colorScheme.errorContainer
            ) {
                Text("Use this when you have a Router but NO Internet.", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Step(1, "Connect all phones to the same WiFi/Router.")
                Step(2, "Do NOT Log in. Tap 'Offline Mode' on the login screen.")
                Step(3, "Enter your name (e.g., 'Stage Left') and enter.")
                Step(4, "Wait a few seconds for others to appear in 'Nearby Devices'.")
                Step(5, "CRITICAL STEP:", isBold = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Tap the ")
                    Icon(Icons.Default.Groups, null, tint = MaterialTheme.colorScheme.primary)
                    Text(" (Group Icon) at the top right.")
                }
                Text("The text must say 'BROADCAST MODE' in RED.", color = Color.Red, fontWeight = FontWeight.Bold)
                Step(6, "Hold the big button to talk to everyone.")
            }

            Spacer(Modifier.height(16.dp))

            // --- SECTION 5: INDIVIDUAL CALLS & CONNECTION ---
            HelpCard(
                title = "👤 INDIVIDUAL CALLS (The 'Double Punch')",
                icon = Icons.Default.Person,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text("How to connect instantly on mobile networks (Jio/Airtel):", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Step(1, "Select a person from your contacts.")
                Step(2, "The 'Double Punch' Rule:", isBold = true)
                Text("For the fastest connection, BOTH users should press the Talk button once.", modifier = Modifier.padding(start = 24.dp))
                Step(3, "This 'wakes up' the connection instantly on strict firewalls.")
                Step(4, "As soon as you hear audio, the status light will turn GREEN.")
            }

            Spacer(Modifier.height(16.dp))

            // --- SECTION 6: TROUBLESHOOTING ---
            Text("Troubleshooting", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("• Status stays Red? Just press the Talk button! It sends a wake-up signal to your peer.")
            Text("• Screeching? The app has built-in feedback suppression, but try to keep phones 2-3 meters apart.")
            Text("• Light stuck on Yellow? Tap the Sync (Refresh) icon at the top left to force a connection check.")
            Text("• No devices found? Ensure 'AP Isolation' is OFF in your Router settings.")
            Text("• Sound clear on one side only? Check the noisy device's mic for dust or cases blocking the secondary mic.")

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
fun HelpCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, content: @Composable ColumnScope.() -> Unit) {
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