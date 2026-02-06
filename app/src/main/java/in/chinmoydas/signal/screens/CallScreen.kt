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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
    onAccept: () -> Unit, // [CRITICAL] Passed from MainActivity to trigger CallSignaling.acceptCall()
    onMinimize: () -> Unit
) {
    val callStatus by CallSignaling.callStatus.collectAsState()
    val remoteIp = CallSignaling.currentCallerIp
    val displayName = remember(remoteIp) {
        if (remoteIp != null) nameResolver(remoteIp) else "Unknown"
    }

    // [FEATURE] Keep screen on during call (Vital for VoIP)
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
                IconButton(onClick = onMinimize) {
                    Icon(Icons.Default.KeyboardArrowDown, "Minimize", tint = Color.White, modifier = Modifier.size(36.dp))
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Security, null, tint = Color(0xFF81C784), modifier = Modifier.size(16.dp))
                    Text("End-to-End Encrypted", color = Color(0xFF81C784), style = MaterialTheme.typography.labelSmall)
                }
                // Spacer for symmetry
                Spacer(modifier = Modifier.size(36.dp))
            }

            // --- 2. PROFILE AREA ---
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier.size(120.dp).clip(CircleShape).background(Color.Gray),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = displayName.take(1).uppercase(),
                        style = MaterialTheme.typography.displayMedium,
                        color = Color.White
                    )
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))

                // Status Logic
                val statusText = when(callStatus) {
                    CallStatus.Dialing -> "Dialing..."
                    CallStatus.Ringing -> "Incoming Voice Call"
                    CallStatus.Active -> null // Show Timer
                    else -> "Connecting..."
                }

                if (statusText != null) {
                    Text(text = statusText, color = Color.LightGray, style = MaterialTheme.typography.bodyLarge)
                } else {
                    CallTimer()
                }
            }

            // --- 3. ACTION CONTROLS ---
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

// [INCOMING] Green Accept / Red Decline
@Composable
fun IncomingCallControls(onAccept: () -> Unit, onReject: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 48.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Decline
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Button(
                onClick = onReject,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                shape = CircleShape,
                modifier = Modifier.size(72.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(Icons.Default.CallEnd, null, modifier = Modifier.size(32.dp), tint = Color.White)
            }
            Spacer(Modifier.height(8.dp))
            Text("Decline", color = Color.White)
        }

        // Accept (With Pulse Animation)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
            val scale by infiniteTransition.animateFloat(
                initialValue = 1f,
                targetValue = 1.15f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000),
                    repeatMode = RepeatMode.Reverse
                ), label = "scale"
            )

            Button(
                onClick = onAccept,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                shape = CircleShape,
                modifier = Modifier
                    .size(72.dp)
                    .scale(scale), // Apply scale here on the button itself
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(Icons.Default.Call, null, modifier = Modifier.size(32.dp), tint = Color.White)
            }
            Spacer(Modifier.height(8.dp))
            Text("Accept", color = Color.White)
        }
    }
}

// [ACTIVE] Mute / Speaker / Hangup
@Composable
fun ActiveCallControls(onHangup: () -> Unit, onSpeakerToggle: () -> Unit, isSpeakerOn: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Mute Toggle
        var isMuted by remember { mutableStateOf(false) }
        IconButton(
            onClick = {
                isMuted = !isMuted
                CallEngine.toggleMute()
            },
            modifier = Modifier
                .size(56.dp)
                .background(
                    if (isMuted) Color.White else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    CircleShape
                )
        ) {
            Icon(
                imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                contentDescription = "Mute",
                tint = if (isMuted) Color.Black else Color.White
            )
        }

        // End Call
        Button(
            onClick = onHangup,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
            shape = CircleShape,
            modifier = Modifier.size(72.dp),
            contentPadding = PaddingValues(0.dp)
        ) {
            Icon(Icons.Default.CallEnd, null, modifier = Modifier.size(32.dp), tint = Color.White)
        }

        // Speaker Toggle
        IconButton(
            onClick = onSpeakerToggle,
            modifier = Modifier
                .size(56.dp)
                .background(
                    if (isSpeakerOn) Color(0xFF81C784) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    CircleShape
                )
        ) {
            Icon(
                imageVector = if (isSpeakerOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                contentDescription = "Speaker",
                tint = if (isSpeakerOn) Color.Black else Color.White
            )
        }
    }
}

@Composable
fun CallTimer() {
    var seconds by remember { mutableLongStateOf(0L) }

    // Using start time to prevent drift
    LaunchedEffect(Unit) {
        val startTime = System.currentTimeMillis()
        while (true) {
            seconds = (System.currentTimeMillis() - startTime) / 1000
            delay(1000)
        }
    }

    Text(
        text = "%02d:%02d".format(seconds / 60, seconds % 60),
        style = MaterialTheme.typography.titleLarge,
        color = Color(0xFF81C784)
    )
}

@Composable
fun MiniCallBar(modifier: Modifier = Modifier, status: CallStatus, onReturnToCall: () -> Unit) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
            .height(56.dp)
            .clickable { onReturnToCall() },
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
                // Pulsing dot
                val infiniteTransition = rememberInfiniteTransition(label = "mini_pulse")
                val alpha by infiniteTransition.animateFloat(
                    initialValue = 0.3f, targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "alpha"
                )
                Box(modifier = Modifier.size(10.dp).background(Color.White.copy(alpha = alpha), CircleShape))

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = if (status == CallStatus.Active) "Touch to return to call" else "Incoming Call...",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Icon(Icons.Default.Call, contentDescription = null, tint = Color.White)
        }
    }
}