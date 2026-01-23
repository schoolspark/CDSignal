package `in`.chinmoydas.signal.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import `in`.chinmoydas.signal.utils.CallSignaling
import kotlinx.coroutines.delay

@Composable
fun CallOverlay() {
    var callState by remember { mutableStateOf<CallState>(CallState.Idle) }
    var callerIp by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        CallSignaling.callEvents.collect { event ->
            when (event) {
                is CallSignaling.CallEvent.IncomingCall -> {
                    callerIp = event.ip
                    callState = CallState.Ringing
                }
                is CallSignaling.CallEvent.CallConnected -> {
                    callState = CallState.Active
                }
                is CallSignaling.CallEvent.CallEnded,
                is CallSignaling.CallEvent.CallRejected,
                is CallSignaling.CallEvent.CallBusy -> {
                    callState = CallState.Idle
                }
            }
        }
    }

    AnimatedVisibility(
        visible = callState != CallState.Idle,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut()
    ) {
        if (callState == CallState.Ringing) {
            IncomingCallDialog(
                callerIp = callerIp,
                onAccept = { CallSignaling.acceptCall() },
                onDecline = { CallSignaling.declineCall() }
            )
        } else if (callState == CallState.Active) {
            ActiveCallScreen(
                callerIp = callerIp,
                onEndCall = { CallSignaling.endCall() }
            )
        }
    }
}

@Composable
fun IncomingCallDialog(callerIp: String, onAccept: () -> Unit, onDecline: () -> Unit) {
    Dialog(
        onDismissRequest = { onDecline() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("INCOMING SECURE CALL", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                Text(callerIp, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(32.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    CallButton(Icons.Default.CallEnd, Color(0xFFE53935), "DECLINE", onDecline)
                    CallButton(Icons.Default.Call, Color(0xFF43A047), "ACCEPT", onAccept)
                }
            }
        }
    }
}

@Composable
fun ActiveCallScreen(callerIp: String, onEndCall: () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(Modifier.size(120.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Call, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(24.dp))
            Text(callerIp, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            CallTimer()
            Spacer(Modifier.height(64.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                var isMuted by remember { mutableStateOf(false) }
                IconButton(onClick = { isMuted = !isMuted }, modifier = Modifier.size(56.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)) {
                    Icon(if (isMuted) Icons.Default.MicOff else Icons.Default.Mic, null)
                }
                Button(onClick = onEndCall, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)), shape = CircleShape, modifier = Modifier.size(80.dp), contentPadding = PaddingValues(0.dp)) {
                    Icon(Icons.Default.CallEnd, null, modifier = Modifier.size(32.dp))
                }
            }
        }
    }
}

@Composable
fun CallButton(icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, text: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = color), shape = CircleShape, modifier = Modifier.size(72.dp), contentPadding = PaddingValues(0.dp)) {
            Icon(icon, null, modifier = Modifier.size(32.dp), tint = Color.White)
        }
        Spacer(Modifier.height(8.dp))
        Text(text, style = MaterialTheme.typography.labelSmall)
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
    Text("%02d:%02d".format(seconds / 60, seconds % 60), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
}

enum class CallState { Idle, Ringing, Active }