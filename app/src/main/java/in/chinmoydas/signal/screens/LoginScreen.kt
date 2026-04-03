package `in`.chinmoydas.signal.screens

import android.content.SharedPreferences
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import `in`.chinmoydas.signal.R
import `in`.chinmoydas.signal.viewmodel.LoginViewModel

@Composable
fun LoginScreen(navController: NavController, prefs: SharedPreferences, viewModel: LoginViewModel = viewModel()) {
    LaunchedEffect(Unit) { viewModel.loadLastUser(prefs) }

    val context = LocalContext.current
    var passwordVisible by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Card(
            Modifier
                .padding(24.dp)
                .fillMaxWidth(),
            elevation = CardDefaults.cardElevation(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                Modifier
                    .padding(32.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // --- LOGO SECTION ---
                Box(
                    Modifier
                        .size(100.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                        contentDescription = "Logo",
                        modifier = Modifier.size(72.dp)
                    )
                }
                Spacer(Modifier.height(24.dp))
                Text("CD Signal", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(32.dp))

                // --- USERNAME FIELD ---
                OutlinedTextField(
                    value = viewModel.username,
                    onValueChange = { viewModel.username = it },
                    label = { Text("Username") },
                    leadingIcon = { Icon(Icons.Default.Person, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(16.dp))

                // --- PASSWORD FIELD ---
                OutlinedTextField(
                    value = viewModel.password,
                    onValueChange = { viewModel.password = it },
                    label = { Text("Password") },
                    leadingIcon = { Icon(Icons.Default.Lock, null) },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = if (passwordVisible) "Toggle visibility" else "Toggle visibility"
                            )
                        }
                    },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(16.dp))

                // --- [NEW] PREMIUM ACCESS CODE FIELD ---
                OutlinedTextField(
                    value = viewModel.accessCode,
                    onValueChange = { viewModel.accessCode = it },
                    label = { Text("Access Code (Optional)") },
                    placeholder = { Text("For Premium 5G Relay") },
                    leadingIcon = { Icon(Icons.Default.Star, contentDescription = "Premium") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // --- FORGOT PASSWORD LINK ---
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    TextButton(onClick = { viewModel.showResetDialog = true }) {
                        Text("Forgot Password?", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    }
                }

                Spacer(Modifier.height(8.dp))

                // --- ERROR MESSAGE ---
                if (viewModel.errorMsg != null) {
                    Text(viewModel.errorMsg!!, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(16.dp))
                }

                // --- LOGIN BUTTON ---
                Button(
                    onClick = {
                        viewModel.login(context, prefs) {
                            navController.navigate("home") { popUpTo("login") { inclusive = true } }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !viewModel.isLoading
                ) {
                    if (viewModel.isLoading) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Text("LOGIN", fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(Modifier.height(16.dp))

                // --- OFFLINE MODE BUTTON ---
                TextButton(onClick = {
                    if (viewModel.username.isNotBlank()) {
                        viewModel.loginOffline(viewModel.username, prefs) {
                            navController.navigate("home") { popUpTo("login") { inclusive = true } }
                        }
                    } else {
                        viewModel.errorMsg = "Enter Name for Offline"
                    }
                }) {
                    Text("No Internet? Use Offline Mode")
                }

                Spacer(Modifier.height(8.dp))

                // --- HELP BUTTON ---
                TextButton(onClick = { navController.navigate("help") }) {
                    Icon(Icons.Default.Info, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("How to Use / Help")
                }
            }
        }
    }

    // --- RESET PASSWORD DIALOG (Remains the same as your upload) ---
    if (viewModel.showResetDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.showResetDialog = false },
            title = {
                Text(
                    if (viewModel.resetStep == 0) "Reset Password" else "Enter Verification Code",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    if (viewModel.resetStep == 0) {
                        Text("Enter your username. An OTP will be sent to your recovery email if you have configured one in Profile Settings.")

                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Privacy Note: You must have previously linked a recovery email to use this feature. We use this email strictly for verification and never share it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = viewModel.resetUsername,
                            onValueChange = { viewModel.resetUsername = it },
                            label = { Text("Username") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text("Check your email for the 6-digit code.")
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = viewModel.resetOtp,
                            onValueChange = { viewModel.resetOtp = it },
                            label = { Text("OTP Code") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = viewModel.resetNewPass,
                            onValueChange = { viewModel.resetNewPass = it },
                            label = { Text("New Password") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    if (viewModel.resetMsg != null) {
                        Spacer(Modifier.height(12.dp))
                        Text(viewModel.resetMsg!!, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (viewModel.resetStep == 0) viewModel.requestOtp()
                        else viewModel.confirmReset()
                    },
                    enabled = !viewModel.resetLoading
                ) {
                    if (viewModel.resetLoading) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Text(if (viewModel.resetStep == 0) "Get OTP" else "Update Password")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.showResetDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}