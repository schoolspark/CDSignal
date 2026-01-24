package `in`.chinmoydas.signal.screens

import android.content.Intent
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import `in`.chinmoydas.signal.utils.SafetySignaling
import kotlinx.coroutines.delay

@Composable
fun SafetyOverlay() {
    val context = LocalContext.current
    var sosSender by remember { mutableStateOf<String?>(null) }
    var locData by remember { mutableStateOf<Pair<String, Pair<Double, Double>>?>(null) }

    // [FIX] Alarm Player State
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    LaunchedEffect(Unit) {
        SafetySignaling.safetyEvents.collect { event ->
            when (event) {
                is SafetySignaling.SafetyEvent.SOS -> {
                    sosSender = event.senderIp
                }
                is SafetySignaling.SafetyEvent.Location -> {
                    locData = event.senderIp to (event.lat to event.lon)
                }
            }
        }
    }

    // --- 1. SOS RED ALERT SCREEN ---
    if (sosSender != null) {

        // [FIX] Play Loud Alarm Loop
        DisposableEffect(Unit) {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

            val player = MediaPlayer().apply {
                setDataSource(context, uri)
                setAudioStreamType(AudioManager.STREAM_ALARM) // Bypass Silent Mode
                isLooping = true
                prepare()
                start()
            }
            mediaPlayer = player
            onDispose {
                player.stop()
                player.release()
            }
        }

        val infiniteTransition = rememberInfiniteTransition()
        val color by infiniteTransition.animateColor(
            initialValue = Color.Red,
            targetValue = Color(0xFF8B0000),
            animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse)
        )
        val scale by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.2f,
            animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse)
        )

        Dialog(
            onDismissRequest = { /* Cannot dismiss without clicking button */ },
            properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false)
        ) {
            Box(
                modifier = Modifier.fillMaxSize().background(color),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Warning, "SOS", tint = Color.White, modifier = Modifier.size(120.dp).scale(scale))
                    Spacer(Modifier.height(32.dp))
                    Text("EMERGENCY ALERT", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("From: $sosSender", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                    Spacer(Modifier.height(48.dp))
                    Button(
                        onClick = {
                            sosSender = null
                            SafetySignaling.clearEvent() // [FIX] Clear sticky event
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                        modifier = Modifier.fillMaxWidth(0.8f).height(56.dp)
                    ) {
                        Text("I ACKNOWLEDGE", color = Color.Red, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    // --- 2. LOCATION POPUP ---
    if (locData != null) {
        val (sender, coords) = locData!!
        AlertDialog(
            onDismissRequest = {
                locData = null
                SafetySignaling.clearEvent()
            },
            icon = { Icon(Icons.Default.LocationOn, null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Incoming Signal Trace") },
            text = {
                Column {
                    Text("Source: $sender")
                    Text("Coords: ${coords.first}, ${coords.second}")
                }
            },
            confirmButton = {
                Button(onClick = {
                    val uri = Uri.parse("geo:${coords.first},${coords.second}?q=${coords.first},${coords.second}($sender)")
                    val intent = Intent(Intent.ACTION_VIEW, uri)
                    intent.setPackage("com.google.android.apps.maps")
                    try { context.startActivity(intent) } catch (e: Exception) { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    locData = null
                    SafetySignaling.clearEvent()
                }) {
                    Icon(Icons.Default.Map, null); Spacer(Modifier.width(8.dp)); Text("OPEN MAPS")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    locData = null
                    SafetySignaling.clearEvent()
                }) { Text("Dismiss") }
            }
        )
    }
}