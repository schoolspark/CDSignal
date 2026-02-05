package `in`.chinmoydas.signal.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import `in`.chinmoydas.signal.utils.CallEngine
import `in`.chinmoydas.signal.utils.CallSignaling
import `in`.chinmoydas.signal.utils.CallStatus
import kotlinx.coroutines.delay

@Composable
fun CallScreen(
    nameResolver: (String) -> String,
    onSpeakerToggle: () -> Unit,
    isSpeakerOn: Boolean,
    onHangup: () -> Unit,
    onAccept: () -> Unit, // [NEW] Accept Action
    onMinimize: () -> Unit
) {
    val callStatus by CallSignaling.callStatus.collectAsState()
    val remoteIp = CallSignaling.currentCallerIp
    val displayName = remember(remoteIp) {
        if (remoteIp != null) nameResolver(remoteIp) else "Unknown"
    }

    // Keep screen on
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = (context as? android.app.Activity)?.window
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF263238), Color(0xFF000000))))
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {

            // --- 1. TOP BAR ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Minimize Button
                IconButton(onClick = onMinimize) {
                    Icon(Icons.Default.KeyboardArrowDown, "Minimize", tint = Color.White, modifier = Modifier.size(36.dp))
                }

                // Encryption Badge
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Security, null, tint = Color(0xFF81C784), modifier = Modifier.size(16.dp))
                    Text("End-to-End Encrypted", color = Color(0xFF81C784), style = MaterialTheme.typography.labelSmall)
                }
                Spacer(modifier = Modifier.size(36.dp))
            }

            // --- 2. PROFILE AREA ---
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier.size(120.dp).clip(CircleShape).background(Color.Gray),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = displayName.take(1).uppercase(), style = MaterialTheme.typography.displayMedium, color = Color.White)
                }
                Spacer(Modifier.height(24.dp))
                Text(text = displayName, style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))

                // Status Text
                val statusText = when(callStatus) {
                    CallStatus.Dialing -> "Dialing..."
                    CallStatus.Ringing -> "Incoming Voice Call"
                    CallStatus.Active -> null // Show Timer instead
                    else -> "Connecting..."
                }

                if (statusText != null) {
                    Text(text = statusText, color = Color.LightGray, style = MaterialTheme.typography.bodyLarge)
                } else {
                    CallTimer()
                }
            }

            // --- 3. ACTION BUTTONS ---
            // If RINGING (Incoming) -> Show Accept & Reject
            // If ACTIVE/DIALING -> Show Mute, Speaker, Hangup
            if (callStatus == CallStatus.Ringing) {
                IncomingCallControls(onAccept = onAccept, onReject = onHangup)
            } else {
                ActiveCallControls(
                    onHangup = onHangup,
                    onSpeakerToggle = onSpeakerToggle,
                    isSpeakerOn = isSpeakerOn
                )
            }
        }
    }
}

// [NEW] Buttons for Incoming Call
@Composable
fun IncomingCallControls(onAccept: () -> Unit, onReject: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 48.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // REJECT BUTTON
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Button(
                onClick = onReject,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)), // Red
                shape = CircleShape,
                modifier = Modifier.size(72.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(Icons.Default.CallEnd, null, modifier = Modifier.size(32.dp), tint = Color.White)
            }
            Spacer(Modifier.height(8.dp))
            Text("Decline", color = Color.White)
        }

        // ACCEPT BUTTON
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Button(
                onClick = onAccept,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)), // Green
                shape = CircleShape,
                modifier = Modifier.size(72.dp), // Slightly larger
                contentPadding = PaddingValues(0.dp)
            ) {
                // Pulsing Animation
                val infiniteTransition = rememberInfiniteTransition()
                val scale by infiniteTransition.animateFloat(
                    initialValue = 1f, targetValue = 1.1f,
                    animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse)
                )
                Icon(
                    Icons.Default.Call,
                    null,
                    modifier = Modifier.size(32.dp).graphicsLayer(scaleX = scale, scaleY = scale), // Fix: Import graphicsLayer if needed or remove anim
                    tint = Color.White
                )
            }
            Spacer(Modifier.height(8.dp))
            Text("Accept", color = Color.White)
        }
    }
}

// Buttons for Active Call
@Composable
fun ActiveCallControls(onHangup: () -> Unit, onSpeakerToggle: () -> Unit, isSpeakerOn: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Mute
        var isMuted by remember { mutableStateOf(false) }
        IconButton(
            onClick = { isMuted = !isMuted; CallEngine.toggleMute() },
            modifier = Modifier.size(56.dp).background(if (isMuted) Color.White else MaterialTheme.colorScheme.surfaceVariant, CircleShape)
        ) {
            Icon(if (isMuted) Icons.Default.MicOff else Icons.Default.Mic, null, tint = if (isMuted) Color.Black else Color.White)
        }

        // Hangup
        Button(
            onClick = onHangup,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
            shape = CircleShape,
            modifier = Modifier.size(72.dp),
            contentPadding = PaddingValues(0.dp)
        ) {
            Icon(Icons.Default.CallEnd, null, modifier = Modifier.size(32.dp), tint = Color.White)
        }

        // Speaker
        IconButton(
            onClick = onSpeakerToggle,
            modifier = Modifier.size(56.dp).background(if (isSpeakerOn) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, CircleShape)
        ) {
            Icon(if (isSpeakerOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff, null, tint = if (isSpeakerOn) Color.Black else Color.White)
        }
    }
}

@Composable
fun CallTimer() {
    var seconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = System.currentTimeMillis()
        while (true) {
            seconds = (System.currentTimeMillis() - start) / 1000
            delay(1000)
        }
    }
    Text("%02d:%02d".format(seconds / 60, seconds % 60), style = MaterialTheme.typography.titleLarge, color = Color(0xFF81C784))
}

@Composable
fun MiniCallBar(modifier: Modifier = Modifier, status: CallStatus, onReturnToCall: () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth().height(56.dp).clickable { onReturnToCall() },
        colors = CardDefaults.cardColors(containerColor = Color(0xFF4CAF50)),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        shape = RoundedCornerShape(28.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(10.dp).background(Color.White, CircleShape))
                Spacer(modifier = Modifier.width(12.dp))
                Text(text = if (status == CallStatus.Active) "Touch to return to call" else "Incoming Call...", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            }
            Icon(Icons.Default.Call, contentDescription = null, tint = Color.White)
        }
    }
}