package `in`.chinmoydas.signal.screens

import android.content.Context
import android.os.Build
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(navController: NavController) {
    val context = LocalContext.current

    // [NEW] Dynamically fetch the App Version (e.g., "v5.4.18")
    val appVersion = remember {
        try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            "v${pInfo.versionName}"
        } catch (e: Exception) {
            "v5.x" // Fallback
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                // [NEW] Uses dynamic version
                title = { Text("Field Manual $appVersion", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // --- 0. AI ASSISTANT ---
            HelpCard(
                title = "🤖 SMART ASSISTANT (CD-1)",
                icon = Icons.Default.AutoAwesome,
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer
            ) {
                Text("Your Offline Field Guide.", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Step(1, "Tap the ✨ button (Bottom Left) or type 'Help'.")
                Step(2, "Ask for: First Aid, Navigation, or App Settings.")
                Text("Works completely offline. No internet needed.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top=4.dp))
            }

            // --- 1. PTT MODES ---
            HelpCard(
                title = "🎙️ TALK MODES",
                icon = Icons.Default.SettingsRemote,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                StatusDotRow(MaterialTheme.colorScheme.primary, "Hold-to-Talk", "Standard mode. Hold Red Button to speak.")
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f))
                StatusDotRow(MaterialTheme.colorScheme.primary, "Tap Mode", "Tap 'Handsfree'. Tap once to talk, tap again to stop.")
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f))
                StatusDotRow(MaterialTheme.colorScheme.primary, "Pocket Mode", "Use Volume Keys to talk while screen is OFF.")
            }

            // --- 2. SMART WAKE ---
            HelpCard(
                title = "🔔 SMART WAKE",
                icon = Icons.Default.NotificationsActive,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            ) {
                Text("How to call offline users:", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Step(1, "If a contact is Red (Offline), just press PTT.")
                Step(2, "The app sends a Wake-Up signal automatically.")
                Step(3, "Their phone will ring and open the channel.")
            }

            // --- 3. GUARDIAN MODE ---
            HelpCard(
                title = "🛡️ GUARDIAN MODE",
                icon = Icons.Default.Security,
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            ) {
                Text("Remote Safety for Family:", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Step(1, "Go to 'Connect' Tab -> Tap Star (⭐) on a trusted contact.")
                Step(2, "That contact becomes a 'Guardian'.")
                Step(3, "Guardians can remotely check your status if you don't reply.")
            }

            // --- 4. SAFETY TOOLS ---
            HelpCard(
                title = "⛑️ EMERGENCY TOOLS",
                icon = Icons.Default.Shield,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                StatusDotRow(MaterialTheme.colorScheme.error, "SOS (Warning)", "Panic Button. Sends Alarm + Location to everyone.")
                StatusDotRow(MaterialTheme.colorScheme.onSurface, "Stealth (Moon)", "Disables Speaker & Lights. Audio -> Earpiece.")
                StatusDotRow(MaterialTheme.colorScheme.primary, "Shield (Bike)", "Fall Detection. Auto-SOS if you crash.")
                StatusDotRow(MaterialTheme.colorScheme.secondary, "Trace (Pin)", "Share your GPS Location map.")
            }

            // --- 5. SECURE CALLS ---
            HelpCard(
                title = "📞 PRIVATE CALLS",
                icon = Icons.Default.Call,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                Text("Start a private phone call:", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Step(1, "Select a User from the list.")
                Step(2, "Tap the Green 'CALL' button.")
                Step(3, "Talk and listen at the same time (like a phone).")
            }

            // --- 6. SILENT TEXT ---
            HelpCard(
                title = "💬 SILENT MESSAGING",
                icon = Icons.Default.Keyboard,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                Text("Send messages without speaking:", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Step(1, "Tap the Keyboard Icon (⌨️).")
                Step(2, "Type and Send.")
                Text("• If Receiver is Loud: Phone reads message aloud.", style = MaterialTheme.typography.bodySmall)
                Text("• If Receiver is Silent: Message saves to History.", style = MaterialTheme.typography.bodySmall)
            }

            // --- 7. PAIRING ---
            HelpCard(
                title = "🔐 SECURE PAIRING",
                icon = Icons.Default.QrCodeScanner,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            ) {
                Text("Required for Private Channels:", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Step(1, "Go to 'Connect' -> 'Scan QR'.")
                Step(2, "Scan your friend's code to link devices.")
                Text("Note: Both sides must scan each other.", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top=4.dp))
            }

            // --- 8. OFFLINE LAN ---
            HelpCard(
                title = "📡 OFFLINE / WIFI MODE",
                icon = Icons.Default.Wifi,
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            ) {
                Text("Use when Internet is down:", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Step(1, "Connect all phones to the same WiFi/Hotspot.")
                Step(2, "The app automatically finds local users.")
                Step(3, "Talk freely without data usage.")
            }

            // --- 9. TROUBLESHOOTING ---
            HelpCard(
                title = "🔧 TROUBLESHOOTING",
                icon = Icons.Default.Build,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                Text("Common Issues:", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Step(1, "No Audio? Use 'System Check' in the Assistant.")
                Step(2, "App stopping? Check 'Battery Optimization' settings.")
                Step(3, "Echo? Keep phones 10ft apart.")
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

// --- HELPER COMPONENTS ---

@Composable
fun HelpCard(
    title: String,
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = contentColor)
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
fun Step(num: Int, text: String) {
    Row(Modifier.padding(vertical = 4.dp)) {
        Text("$num.", fontWeight = FontWeight.Bold, modifier = Modifier.width(24.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun StatusDotRow(color: Color, title: String, desc: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            Text(desc, style = MaterialTheme.typography.bodySmall)
        }
    }
}