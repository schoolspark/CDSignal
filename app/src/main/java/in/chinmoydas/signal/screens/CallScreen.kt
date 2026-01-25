package `in`.chinmoydas.signal.screens

import android.content.Context
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.filled.VolumeOff
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import `in`.chinmoydas.signal.utils.CallEngine
import `in`.chinmoydas.signal.utils.CallSignaling
import kotlinx.coroutines.delay

@Composable
fun CallOverlay(
    nameResolver: (String) -> String,
    onSpeakerToggle: () -> Unit,
    isSpeakerOn: Boolean
) {
    var callState by remember { mutableStateOf<CallState>(CallState.Idle) }
    var callerIp by remember { mutableStateOf("") }

    // [FIX] Audio Feedback System (Rings/Dialtone)
    SoundEffectManager(callState = callState)

    val displayName = remember(callerIp) {
        if (callerIp.isNotEmpty()) nameResolver(callerIp) else "Unknown"
    }

    LaunchedEffect(Unit) {
        // Collects events. Since we use replay=1, we immediately get the current state.
        CallSignaling.callEvents.collect { event ->
            when (event) {
                is CallSignaling.CallEvent.IncomingCall -> {
                    callerIp = event.ip
                    callState = CallState.Ringing
                }
                is CallSignaling.CallEvent.OutgoingCall -> {
                    callerIp = event.ip
                    callState = CallState.Dialing
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
        when (callState) {
            CallState.Ringing -> IncomingCallDialog(displayName, { CallSignaling.acceptCall() }, { CallSignaling.declineCall() })
            CallState.Dialing -> DialingDialog(displayName, { CallSignaling.endCall() })
            CallState.Active -> ActiveCallScreen(
                callerName = displayName,
                isSpeakerOn = isSpeakerOn,
                onSpeakerToggle = onSpeakerToggle,
                onEndCall = { CallSignaling.endCall() }
            )
            else -> {}
        }
    }
}

// [FIX] Plays Ringtone or Dialtone based on state
@Composable
fun SoundEffectManager(callState: CallState) {
    val context = LocalContext.current

    DisposableEffect(callState) {
        var ringtone: Ringtone? = null
        var toneGenerator: ToneGenerator? = null

        when (callState) {
            CallState.Ringing -> {
                try {
                    val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    ringtone = RingtoneManager.getRingtone(context, uri)
                    if (android.os.Build.VERSION.SDK_INT >= 28) {
                        ringtone.isLooping = true
                    }
                    ringtone.play()
                } catch (e: Exception) { e.printStackTrace() }
            }
            CallState.Dialing -> {
                try {
                    toneGenerator = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80)
                    toneGenerator.startTone(ToneGenerator.TONE_SUP_RINGTONE)
                } catch (e: Exception) { e.printStackTrace() }
            }
            else -> {}
        }

        onDispose {
            ringtone?.stop()
            toneGenerator?.stopTone()
            toneGenerator?.release()
        }
    }
}

@Composable
fun DialingDialog(callerName: String, onCancel: () -> Unit) {
    Dialog(onDismissRequest = { onCancel() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(modifier = Modifier.fillMaxWidth().padding(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("CALLING...", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                Text(callerName, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(32.dp))
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(32.dp))
                CallButton(Icons.Default.CallEnd, Color(0xFFE53935), "CANCEL", onCancel)
            }
        }
    }
}

@Composable
fun IncomingCallDialog(callerName: String, onAccept: () -> Unit, onDecline: () -> Unit) {
    Dialog(onDismissRequest = { onDecline() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(modifier = Modifier.fillMaxWidth().padding(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("INCOMING SECURE CALL", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                Text(callerName, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
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
fun ActiveCallScreen(
    callerName: String,
    isSpeakerOn: Boolean,
    onSpeakerToggle: () -> Unit,
    onEndCall: () -> Unit
) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(120.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Call, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(24.dp))
            Text(callerName, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            CallTimer()
            Spacer(Modifier.height(64.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                // [FIX] Mute button wired to Engine
                var isMuted by remember { mutableStateOf(false) }
                IconButton(onClick = {
                    isMuted = !isMuted
                    CallEngine.toggleMute(isMuted)
                }, modifier = Modifier.size(56.dp).background(if(isMuted) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant, CircleShape)) {
                    Icon(if (isMuted) Icons.Default.MicOff else Icons.Default.Mic, null)
                }

                Button(onClick = onEndCall, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)), shape = CircleShape, modifier = Modifier.size(80.dp), contentPadding = PaddingValues(0.dp)) {
                    Icon(Icons.Default.CallEnd, null, modifier = Modifier.size(32.dp))
                }

                // [FIX] Speaker button wired to Service/Activity
                IconButton(onClick = onSpeakerToggle, modifier = Modifier.size(56.dp).background(if(isSpeakerOn) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, CircleShape)) {
                    Icon(if (isSpeakerOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff, null)
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

enum class CallState { Idle, Ringing, Dialing, Active }