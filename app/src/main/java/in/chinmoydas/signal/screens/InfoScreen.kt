package `in`.chinmoydas.signal.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import `in`.chinmoydas.signal.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoScreen(navController: NavController) {
    val context = LocalContext.current
    val (versionName, versionCode) = remember { getAppVersion(context) }

    // LEGAL URLS
    val privacyPolicyUrl = "https://signal.chinmoydas.in/privacy-policy.php"
    val termsUrl = "https://signal.chinmoydas.in/terms-of-service.php"
    val deleteAccountUrl = "https://signal.chinmoydas.in/delete-account.php"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About & Legal") },
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
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // App Icon Container
            Box(
                Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                    contentDescription = "App Icon",
                    modifier = Modifier.size(72.dp)
                )
            }

            Spacer(Modifier.height(16.dp))

            Text("CD Signal", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Version $versionName", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)

            Spacer(Modifier.height(32.dp))

            // Compliance Section
            Text("Legal & Compliance", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.Start))
            Spacer(Modifier.height(8.dp))

            InfoItem("Privacy Policy", Icons.Default.Policy) { openUrl(context, privacyPolicyUrl) }
            InfoItem("Terms of Service", Icons.Default.Info) { openUrl(context, termsUrl) }

            // Delete Account Link (Mandatory for Play Store)
            InfoItem("Delete Account", Icons.Default.Delete) { openUrl(context, deleteAccountUrl) }

            Spacer(Modifier.height(24.dp))

            // Data Safety Badge
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Security, null, tint = Color(0xFF4CAF50))
                        Spacer(Modifier.width(8.dp))
                        Text("Security & Data Safety", fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "• Encryption: When Secure Channel is ON, all voice and text packets are encrypted using AES-GCM-256 (Military Grade).",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "• Peer-to-Peer: Audio and Location data is transmitted directly between users. We do not store your conversations.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "• Location Privacy: 'Signal Trace' coordinates are sent directly to the peer. We do not track or log your location history.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(4.dp))

                    Text(
                        "• Guardian Mode: Remote Control features (Mic/Location) are disabled by default. You must explicitly enable them in Profile settings.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(4.dp))

                    Text(
                        "• Auto-Delete: Incoming Voice Pager messages are stored locally and automatically removed immediately after playback.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // [NEW] AI Assistant Badge
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                        Spacer(Modifier.width(8.dp))
                        Text("Offline Intelligence (CD-1)", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "• Local AI: The 'Signal Assistant' runs entirely on your device. It does not send your queries to the cloud.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "• Smart Diagnostics: CD-1 can detect network errors and audio configuration issues in real-time to help you stay connected.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text("Developed by Chinmoy Das", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            Text("© 2026 All Rights Reserved", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        }
    }
}

@Composable
fun InfoItem(title: String, icon: ImageVector, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(16.dp))
                Text(title, style = MaterialTheme.typography.bodyLarge)
            }
            Icon(Icons.AutoMirrored.Filled.OpenInNew, null, modifier = Modifier.size(16.dp), tint = Color.Gray)
        }
    }
}

fun getAppVersion(context: Context): Pair<String, Long> {
    return try {
        val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val verCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pInfo.longVersionCode else pInfo.versionCode.toLong()
        Pair(pInfo.versionName, verCode)
    } catch (e: Exception) {
        Pair("Unknown", 0L)
    } as Pair<String, Long>
}

fun openUrl(context: Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        context.startActivity(intent)
    } catch (e: Exception) {
        // Handle no browser case
    }
}