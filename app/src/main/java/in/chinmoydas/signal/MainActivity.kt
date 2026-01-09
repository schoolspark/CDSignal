package `in`.chinmoydas.signal

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
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

class MainActivity : ComponentActivity() {

    private var voiceService by mutableStateOf<VoiceService?>(null)
    private var isBound = false
    private lateinit var walkieViewModel: WalkieViewModel

    private val incomingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "in.chinmoydas.signal.INCOMING_TALK") {
                val channel = intent.getStringExtra("channel_name") ?: ""
                val ip = intent.getStringExtra("sender_ip") ?: "" // GET IP FROM SERVICE

                if (::walkieViewModel.isInitialized) {
                    if (channel.isEmpty()) {
                        walkieViewModel.onReceptionEnded()
                    } else {
                        // Pass IP to ViewModel so we can reply
                        walkieViewModel.onReceptionStarted(channel, ip)
                    }
                }
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as VoiceService.LocalBinder
            voiceService = binder.getService()
            isBound = true
        }
        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            voiceService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestBatteryOptimizationExemption()

        val filter = IntentFilter("in.chinmoydas.signal.INCOMING_TALK")
        ContextCompat.registerReceiver(this, incomingReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )

        val repository = MainRepository(applicationContext)
        val factory = ViewModelFactory(repository)

        setContent {
            CDSignalTheme {
                val navController = rememberNavController()
                val prefs = remember { getSharedPreferences("WalkiePrefs", Context.MODE_PRIVATE) }

                walkieViewModel = viewModel(factory = factory)
                val currentName by repository.myUsername.collectAsState()

                LaunchedEffect(intent) {
                    handleIntent(intent)
                }

                val savedToken = prefs.getString("jwt_token", null)
                val startDest = if (savedToken != null) "home" else "login"

                NavHost(navController = navController, startDestination = startDest) {
                    composable("login") { LoginScreen(navController, prefs) }
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
                                prefs.edit().clear().apply()
                                stopAndUnbindService()
                                navController.navigate("login") { popUpTo("home") { inclusive = true } }
                            },
                            onExit = {
                                stopAndUnbindService()
                                finishAndRemoveTask()
                            }
                        )
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

    private fun stopAndUnbindService() {
        if (isBound) {
            try {
                unbindService(connection)
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to unbind service", e)
            }
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
        stopAndUnbindService()
        try {
            unregisterReceiver(incomingReceiver)
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to unregister receiver", e)
        }
    }
}