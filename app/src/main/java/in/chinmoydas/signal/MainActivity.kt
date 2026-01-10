package `in`.chinmoydas.signal

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import `in`.chinmoydas.signal.data.MainRepository
import `in`.chinmoydas.signal.screens.DiagnosticsScreen
import `in`.chinmoydas.signal.screens.HelpScreen
import `in`.chinmoydas.signal.screens.HomeScreen
import `in`.chinmoydas.signal.screens.LoginScreen
import `in`.chinmoydas.signal.ui.theme.CDSignalTheme
import `in`.chinmoydas.signal.viewmodel.ViewModelFactory
import `in`.chinmoydas.signal.viewmodel.WalkieViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private var voiceService by mutableStateOf<VoiceService?>(null)
    private var isBound = false
    private lateinit var walkieViewModel: WalkieViewModel

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as VoiceService.LocalBinder
            voiceService = binder.getService()
            isBound = true
            observeServiceState()
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            voiceService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestBatteryOptimizationExemption()

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )

        lifecycleScope.launch {
            val repository = withContext(Dispatchers.IO) {
                MainRepository(applicationContext)
            }
            val factory = ViewModelFactory(repository)

            setContent {
                CDSignalTheme {
                    var startDest by remember { mutableStateOf<String?>(null) }

                    LaunchedEffect(Unit) {
                        val prefs = withContext(Dispatchers.IO) {
                            getSharedPreferences("WalkiePrefs", Context.MODE_PRIVATE)
                        }
                        startDest = if (prefs.getString("jwt_token", null) != null) "home" else "login"
                    }

                    if (startDest != null) {
                        val navController = rememberNavController()
                        walkieViewModel = ViewModelProvider(this@MainActivity, factory)[WalkieViewModel::class.java]
                        val currentName by repository.myUsername.collectAsState()

                        LaunchedEffect(intent) {
                            handleIntent(intent)
                        }

                        NavHost(navController = navController, startDestination = startDest!!) {
                            composable("login") { LoginScreen(navController, getSharedPreferences("WalkiePrefs", Context.MODE_PRIVATE)) }
                            composable("help") { HelpScreen(navController) }
                            composable("diagnostics") { DiagnosticsScreen(navController) }
                            composable("home") {
                                HomeScreen(
                                    navController = navController,
                                    service = voiceService,
                                    viewModel = walkieViewModel,
                                    myName = currentName,
                                    onPermissionsGranted = { startAndBindService() },
                                    onLogout = {
                                        getSharedPreferences("WalkiePrefs", Context.MODE_PRIVATE).edit().clear().apply()
                                        performExplicitExit() // FIX: Correctly stop service
                                        navController.navigate("login") { popUpTo("home") { inclusive = true } }
                                    },
                                    onExit = {
                                        performExplicitExit() // FIX: Correctly stop service and app
                                    }
                                )
                            }
                        }
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }

    private fun observeServiceState() {
        lifecycleScope.launch {
            voiceService?.voiceServiceState?.collectLatest { state ->
                if (::walkieViewModel.isInitialized) {
                    if (state.incomingCall != null && state.incomingIp != null) {
                        walkieViewModel.onReceptionStarted(state.incomingCall, state.incomingIp)
                    } else {
                        walkieViewModel.onReceptionEnded()
                    }
                }
            }
        }
    }

    private fun handleIntent(intent: Intent?) {
        val autoChannel = intent?.getStringExtra("auto_connect_channel")
        if (autoChannel != null) {
            if (::walkieViewModel.isInitialized) {
                walkieViewModel.setTarget(autoChannel)
            }
            intent.removeExtra("auto_connect_channel")
        }
    }

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.e("MainActivity", "Battery optimization request failed", e)
            }
        }
    }

    private fun startAndBindService() {
        if (isBound) return
        val serviceIntent = Intent(this, VoiceService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to start and bind service", e)
        }
    }

    // --- NEW FUNCTION: Handles proper shutdown ---
    private fun performExplicitExit() {
        // 1. Unbind UI first
        if (isBound) {
            try { unbindService(connection) } catch (e: Exception) {}
            isBound = false
        }

        // 2. Send the specific STOP command to VoiceService
        // This triggers the "START_NOT_STICKY" logic in onStartCommand
        val stopIntent = Intent(this, VoiceService::class.java).apply {
            action = "STOP_SERVICE"
        }
        startService(stopIntent)

        // 3. Close the UI
        finishAffinity()
    }

    private fun stopAndUnbindService() {
        // Deprecated for Exit logic, but kept for cleanup if needed internally
        if (isBound) {
            try { unbindService(connection) } catch (e: Exception) {}
            isBound = false
        }
        stopService(Intent(this, VoiceService::class.java))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        // --- CRITICAL FIX ---
        // We ONLY unbind here. We do NOT stop the service.
        // This allows the "Background Listening" to work when user swipes app away.
        if (isBound) {
            try {
                unbindService(connection)
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to unbind service", e)
            }
            isBound = false
        }
    }
}