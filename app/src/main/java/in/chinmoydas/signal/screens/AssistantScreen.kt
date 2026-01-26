package `in`.chinmoydas.signal.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import `in`.chinmoydas.signal.VoiceService
import `in`.chinmoydas.signal.utils.OfflineIntelligence
import `in`.chinmoydas.signal.viewmodel.WalkieViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class ChatMessage(val text: String, val isUser: Boolean, val actionLabel: String? = null, val action: (() -> Unit)? = null)

@Composable
fun AssistantSheet(
    viewModel: WalkieViewModel,
    service: VoiceService?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var inputText by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf(listOf(
        ChatMessage("System Online. I am your Offline Assistant. How can I help?", false)
    )) }
    var isThinking by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) { listState.animateScrollToItem(messages.size - 1) }

    fun sendMessage() {
        if (inputText.isBlank()) return
        val userMsg = inputText
        inputText = ""
        messages = messages + ChatMessage(userMsg, true)
        isThinking = true

        scope.launch {
            delay(600)
            val response = OfflineIntelligence.think(userMsg, context, service)
            isThinking = false
            messages = messages + ChatMessage(response.text, false, response.actionLabel, response.action)
        }
    }

    // [FIX] Use Scaffold to guarantee the Input Bar is pinned to the bottom
    Scaffold(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.75f) // Safer height (75% of screen)
            .imePadding(),        // Move up for keyboard
        containerColor = MaterialTheme.colorScheme.surface,
        bottomBar = {
            // INPUT AREA (Pinned to Bottom)
            Column(modifier = Modifier.navigationBarsPadding()) {
                Divider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(16.dp)
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Ask about audio, connection...") },
                        shape = RoundedCornerShape(24.dp),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { sendMessage() })
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = { sendMessage() },
                        modifier = Modifier
                            .size(48.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, null, tint = Color.White)
                    }
                }
            }
        },
        content = { innerPadding ->
            // CHAT CONTENT
            Column(modifier = Modifier.padding(innerPadding).padding(horizontal = 16.dp)) {
                // Drag Handle
                Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.width(40.dp).height(4.dp).background(Color.Gray.copy(alpha=0.4f), CircleShape))
                }

                // Header
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                    Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Signal Assistant (Offline)", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                }

                // Messages List
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(messages) { msg -> ChatBubble(msg) }
                    if (isThinking) { item { ThinkingBubble() } }
                }
            }
        }
    )
}

@Composable
fun ChatBubble(msg: ChatMessage) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = if (msg.isUser) Alignment.End else Alignment.Start) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(RoundedCornerShape(
                    topStart = 16.dp, topEnd = 16.dp,
                    bottomStart = if (msg.isUser) 16.dp else 4.dp,
                    bottomEnd = if (msg.isUser) 4.dp else 16.dp
                ))
                .background(if (msg.isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer)
                .padding(12.dp)
        ) {
            Text(msg.text, style = MaterialTheme.typography.bodyMedium)
        }
        if (!msg.isUser && msg.actionLabel != null && msg.action != null) {
            Spacer(Modifier.height(4.dp))
            SuggestionChip(
                onClick = { msg.action.invoke() },
                label = { Text(msg.actionLabel) },
                icon = { Icon(Icons.Default.SmartToy, null, modifier = Modifier.size(16.dp)) },
                colors = SuggestionChipDefaults.suggestionChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    }
}

@Composable
fun ThinkingBubble() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text("Thinking...", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
    }
}