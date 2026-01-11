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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
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
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import `in`.chinmoydas.signal.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoScreen(navController: NavController) {
    val context = LocalContext.current
    val (versionName, versionCode) = remember { getAppVersion(context) }

    // REPLACE THESE WITH YOUR ACTUAL URLS BEFORE RELEASE
    val privacyPolicyUrl = "https://signal.chinmoydas.in/privacy-policy.php"
    val termsUrl = "https://signal.chinmoydas.in/terms-of-service.php"

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
            // --- FIX: Use Image + Box Container (Same as LoginScreen) ---
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
            // ------------------------------------------------------------

            Spacer(Modifier.height(16.dp))

            Text("CD Signal", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Version $versionName ($versionCode)", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)

            Spacer(Modifier.height(32.dp))

            // Compliance Section
            Text("Legal & Compliance", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.Start))
            Spacer(Modifier.height(8.dp))

            InfoItem("Privacy Policy", Icons.Default.Policy) { openUrl(context, privacyPolicyUrl) }
            InfoItem("Terms of Service", Icons.Default.Info) { openUrl(context, termsUrl) }

            Spacer(Modifier.height(24.dp))

            // Data Safety Badge
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Security, null, tint = Color(0xFF4CAF50)) // Green Security Icon
                        Spacer(Modifier.width(8.dp))
                        Text("Data Safety Declaration", fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "• Audio Processing: All voice data is processed locally or transmitted directly to peers (P2P). No audio is stored on external servers.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "• Location/IP: IP addresses are used strictly for establishing direct connections.",
                        style = MaterialTheme.typography.bodySmall
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