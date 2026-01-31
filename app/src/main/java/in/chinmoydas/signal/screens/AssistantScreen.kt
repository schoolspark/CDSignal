package `in`.chinmoydas.signal.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.chinmoydas.signal.VoiceService
import `in`.chinmoydas.signal.utils.OfflineIntelligence
import `in`.chinmoydas.signal.viewmodel.WalkieViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// --- TACTICAL THEME COLORS ---
val TerminalBlack = Color(0xFF000000)
val TerminalGreen = Color(0xFF00FF00)
val TerminalDimGreen = Color(0xFF005500)
val TerminalRed = Color(0xFFFF0000) // For critical alerts

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val actionLabel: String? = null,
    val action: (() -> Unit)? = null
)

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
        ChatMessage("CD-1 SYSTEMS ONLINE.\nBATTERY OPTIMIZED MODE ENGAGED.\nAWAITING INPUT...", false)
    )) }
    var isThinking by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Auto-scroll to bottom
    LaunchedEffect(messages.size) { listState.animateScrollToItem(messages.size - 1) }

    fun sendMessage() {
        if (inputText.isBlank()) return
        val userMsg = inputText.uppercase() // Terminal style usually uppercase
        inputText = ""
        messages = messages + ChatMessage(userMsg, true)
        isThinking = true

        scope.launch {
            delay(400) // Simulated processing latency
            val response = OfflineIntelligence.think(userMsg, context, service)
            isThinking = false
            messages = messages + ChatMessage(response.text, false, response.actionLabel, response.action)
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.85f) // Taller for reading guides
            .imePadding(),
        containerColor = TerminalBlack, // OLED BLACK
        bottomBar = {
            // TERMINAL INPUT BAR
            Column(modifier = Modifier.background(TerminalBlack).navigationBarsPadding()) {
                // Separator Line
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(TerminalDimGreen))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(">", color = TerminalGreen, fontFamily = FontFamily.Monospace, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(12.dp))

                    // Custom Naked TextField for CLI look
                    BasicTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        textStyle = TextStyle(
                            color = TerminalGreen,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 16.sp
                        ),
                        cursorBrush = SolidColor(TerminalGreen),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { sendMessage() }),
                        decorationBox = { innerTextField ->
                            if (inputText.isEmpty()) {
                                Text("ENTER COMMAND...", color = TerminalDimGreen, fontFamily = FontFamily.Monospace)
                            }
                            innerTextField()
                        }
                    )

                    IconButton(onClick = { sendMessage() }) {
                        Icon(Icons.AutoMirrored.Filled.Send, null, tint = TerminalGreen)
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            // Drag Handle (Tactical Style)
            CenterAlignedTopBar()

            // Header info
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
                Icon(Icons.Default.Terminal, null, tint = TerminalGreen, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "OFFLINE INTELLIGENCE v4.0",
                    style = TextStyle(fontFamily = FontFamily.Monospace, color = TerminalGreen, fontWeight = FontWeight.Bold)
                )
            }

            // Console Log
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(24.dp), // More space for readability
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(messages) { msg -> TerminalMessage(msg) }
                if (isThinking) { item { TerminalLoader() } }
            }
        }
    }
}

@Composable
fun CenterAlignedTopBar() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        // Decorative "Grip" lines
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(3) {
                Box(Modifier.width(40.dp).height(2.dp).background(TerminalDimGreen))
            }
        }
    }
}

@Composable
fun TerminalMessage(msg: ChatMessage) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // PREFIX (e.g., "SYS >>" or "USR >>")
        Text(
            text = if (msg.isUser) "USR_COMMAND >>" else "SYS_RESPONSE >>",
            style = TextStyle(
                color = if (msg.isUser) Color.Gray else TerminalDimGreen,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        )

        Spacer(Modifier.height(4.dp))

        // MESSAGE CONTENT
        Text(
            text = msg.text,
            style = TextStyle(
                color = if (msg.isUser) Color.White else TerminalGreen,
                fontFamily = FontFamily.Monospace,
                fontSize = 16.sp,
                lineHeight = 22.sp // Better readability for survival guides
            )
        )

        // ACTION BUTTON (Styled as [ EXECUTE ])
        if (!msg.isUser && msg.actionLabel != null && msg.action != null) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { msg.action.invoke() },
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = TerminalBlack,
                    containerColor = TerminalGreen
                ),
                shape = MaterialTheme.shapes.extraSmall, // Boxy button
                border = null, // Solid fill
                modifier = Modifier.height(36.dp)
            ) {
                Text(
                    text = "[ ${msg.actionLabel.uppercase()} ]",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun TerminalLoader() {
    // Blinking cursor effect could go here, for now a simple text
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("PROCESSING", color = TerminalDimGreen, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Spacer(Modifier.width(4.dp))
        CircularProgressIndicator(
            modifier = Modifier.size(12.dp),
            color = TerminalGreen,
            strokeWidth = 2.dp
        )
    }
}