package `in`.chinmoydas.signal.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Settings
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
import `in`.chinmoydas.signal.VoiceService
import `in`.chinmoydas.signal.utils.DiagnosticItem
import `in`.chinmoydas.signal.utils.DiagnosticStatus
import `in`.chinmoydas.signal.viewmodel.WalkieViewModel
import kotlinx.coroutines.delay

@Composable
fun SystemReadinessDialog(
    viewModel: WalkieViewModel,
    service: VoiceService?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var currentItem by remember { mutableStateOf(DiagnosticItem("Initializing Diagnostics...", DiagnosticStatus.Pending)) }
    var history by remember { mutableStateOf(listOf<DiagnosticItem>()) }
    var isFinished by remember { mutableStateOf(false) }
    var hasCriticalError by remember { mutableStateOf(false) }

    // Track specific errors to suggest smart fixes
    var isTokenMissing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.performHealthCheck(context, service).collect { item ->
            if (item.status == DiagnosticStatus.Running) {
                currentItem = item
            } else {
                history = history + item
                if (item.status == DiagnosticStatus.Failure) {
                    hasCriticalError = true
                    currentItem = item

                    // [SMART FIX LOGIC] Check for specific error signatures
                    if (item.name == "Cloud Wake Service" && item.detail?.contains("Relogin") == true) {
                        isTokenMissing = true
                    }
                }
                if (item.name == "DIAGNOSTIC COMPLETE") {
                    isFinished = true
                    delay(1000)
                    // Auto-dismiss only if perfect
                    if (!hasCriticalError) onDismiss()
                }
            }
        }
    }

    Dialog(
        onDismissRequest = { if (isFinished) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .width(340.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = when {
                        hasCriticalError -> Icons.Default.Close
                        isFinished -> Icons.Default.VerifiedUser
                        else -> Icons.Default.Settings
                    },
                    contentDescription = null,
                    modifier = Modifier
                        .size(56.dp)
                        .background(
                            when {
                                hasCriticalError -> MaterialTheme.colorScheme.errorContainer
                                isFinished -> Color(0xFFE8F5E9)
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            },
                            CircleShape
                        )
                        .padding(12.dp),
                    tint = when {
                        hasCriticalError -> MaterialTheme.colorScheme.error
                        isFinished -> Color(0xFF2E7D32)
                        else -> MaterialTheme.colorScheme.primary
                    }
                )

                Spacer(Modifier.height(16.dp))

                Text(
                    text = if (isFinished) (if (hasCriticalError) "Issues Found" else "System Ready") else "Running Diagnostics...",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(24.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    history.forEach { check -> DiagnosticRow(check) }

                    if (!isFinished && !hasCriticalError) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 6.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(currentItem.name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))

                if (isFinished || hasCriticalError) {
                    Button(
                        onClick = {
                            if (isTokenMissing) {
                                // Inform user what to do since we can't auto-navigate from here cleanly without callbacks
                                android.widget.Toast.makeText(context, "Please Logout and Login to regenerate token.", android.widget.Toast.LENGTH_LONG).show()
                            }
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (hasCriticalError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    ) {
                        // [SMART LABEL] Change text based on the error
                        Text(if (isTokenMissing) "Close & Relogin" else if (hasCriticalError) "Close" else "Done")
                    }
                }
            }
        }
    }
}

@Composable
fun DiagnosticRow(item: DiagnosticItem) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 6.dp).fillMaxWidth()
    ) {
        Icon(
            imageVector = when (item.status) {
                DiagnosticStatus.Success -> Icons.Default.Check
                DiagnosticStatus.Warning -> Icons.Default.Warning
                else -> Icons.Default.Close
            },
            contentDescription = null,
            tint = when (item.status) {
                DiagnosticStatus.Success -> Color(0xFF43A047)
                DiagnosticStatus.Warning -> Color(0xFFFDD835)
                else -> Color(0xFFE53935)
            },
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (item.detail != null) {
                Text(
                    text = item.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (item.status == DiagnosticStatus.Warning) Color(0xFFF9A825) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}