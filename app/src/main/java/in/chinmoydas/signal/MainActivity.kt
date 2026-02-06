package `in`.chinmoydas.signal

import android.Manifest
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import `in`.chinmoydas.signal.data.MainRepository
import `in`.chinmoydas.signal.screens.*
import `in`.chinmoydas.signal.ui.theme.CDSignalTheme
import `in`.chinmoydas.signal.utils.CallSignaling
import `in`.chinmoydas.signal.utils.CallStatus
import `in`.chinmoydas.signal.viewmodel.ViewModelFactory
import `in`.chinmoydas.signal.viewmodel.WalkieViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    companion object {
        private var hasAskedBattery = false
    }

    private var voiceService: VoiceService? = null
    private var isBound = false
    private lateinit var walkieViewModel: WalkieViewModel

    private lateinit var appUpdateManager: AppUpdateManager
    private val UPDATE_REQUEST_CODE = 123
    private val serviceBoundState = mutableStateOf<VoiceService?>(null)

    private val exitReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "in.chinmoydas.signal.ACTION_EXIT") {
                Log.d("MainActivity", "Received Exit Signal. Closing App.")
                finishAffinity()
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as VoiceService.LocalBinder
            val boundService = binder.getService()
            voiceService = boundService
            isBound = true
            serviceBoundState.value = boundService
            linkServiceLogic(boundService)
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            voiceService?.packetInterceptor = null
            isBound = false
            voiceService = null
            serviceBoundState.value = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        CallSignaling.initialize(applicationContext)

        // [FIX] Initial Wake Up check
        wakeUpScreen()

        appUpdateManager = AppUpdateManagerFactory.create(this)
        appUpdateManager.registerListener(installStateUpdatedListener)
        checkForUpdates()

        val filter = IntentFilter("in.chinmoydas.signal.ACTION_EXIT")
        ContextCompat.registerReceiver(
            this,
            exitReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        checkBatteryOptimizations()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startAndBindService()
        }

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )

        lifecycleScope.launch {
            val repository = withContext(Dispatchers.IO) { MainRepository(applicationContext) }
            val factory = ViewModelFactory(repository)

            setContent {
                CDSignalTheme {
                    var startDest by remember { mutableStateOf<String?>(null) }
                    val currentService by serviceBoundState
                    val callStatus by CallSignaling.callStatus.collectAsState()
                    var isCallMinimized by remember { mutableStateOf(false) }

                    val serviceState by currentService?.voiceServiceState?.collectAsState(initial = VoiceServiceState())
                        ?: remember { mutableStateOf(VoiceServiceState()) }

                    LaunchedEffect(callStatus) {
                        if (callStatus == CallStatus.Idle) {
                            isCallMinimized = false
                        }
                    }

                    LaunchedEffect(Unit) {
                        val prefs = withContext(Dispatchers.IO) {
                            getSharedPreferences("WalkiePrefs", Context.MODE_PRIVATE)
                        }
                        startDest = if (prefs.getString("jwt_token", null) != null) "home" else "login"
                    }

                    LaunchedEffect(startDest) {
                        if (startDest == "home") {
                            lifecycleScope.launch(Dispatchers.IO) { repository.syncFcmTokenToServer() }
                        }
                    }

                    if (startDest != null) {
                        val navController = rememberNavController()
                        walkieViewModel = ViewModelProvider(this@MainActivity, factory)[WalkieViewModel::class.java]

                        LaunchedEffect(currentService) {
                            if (currentService != null) linkServiceLogic(currentService!!)
                        }

                        val currentName by repository.myUsername.collectAsState()

                        // [CRITICAL] Handle intents that might have launched the app
                        LaunchedEffect(intent) { handleIntent(intent) }

                        NavHost(navController = navController, startDestination = startDest!!) {
                            composable("login") { LoginScreen(navController, getSharedPreferences("WalkiePrefs", Context.MODE_PRIVATE)) }
                            composable("help") { HelpScreen(navController) }
                            composable("diagnostics") { DiagnosticsScreen(navController) }
                            composable("info") { InfoScreen(navController) }
                            composable("home") {
                                HomeScreen(
                                    navController = navController,
                                    service = currentService,
                                    viewModel = walkieViewModel,
                                    myName = currentName,
                                    onPermissionsGranted = { startAndBindService() },
                                    onLogout = {
                                        getSharedPreferences("WalkiePrefs", Context.MODE_PRIVATE).edit().clear().apply()
                                        performExplicitExit()
                                        navController.navigate("login") { popUpTo("home") { inclusive = true } }
                                    },
                                    onExit = { performExplicitExit() }
                                )
                            }
                        }

                        val nameResolverHelper: (String) -> String = { ip ->
                            val contact = walkieViewModel.savedContacts.find { it.ip == ip }
                            contact?.name ?: if (walkieViewModel.getCurrentTargetIp() == ip) walkieViewModel.targetUser else ip
                        }

                        SafetyOverlay(nameResolver = nameResolverHelper)

                        if (callStatus != CallStatus.Idle) {
                            if (!isCallMinimized) {
                                CallScreen(
                                    nameResolver = nameResolverHelper,
                                    onSpeakerToggle = { currentService?.toggleSpeaker(!serviceState.isSpeakerOn) },
                                    isSpeakerOn = serviceState.isSpeakerOn,
                                    onHangup = { walkieViewModel.hangUp(currentService) },
                                    onAccept = { lifecycleScope.launch { `in`.chinmoydas.signal.utils.CallSignaling.acceptCall() } },
                                    onMinimize = { isCallMinimized = true }
                                )
                            } else {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    MiniCallBar(
                                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp, start = 16.dp, end = 16.dp),
                                        status = callStatus,
                                        onReturnToCall = { isCallMinimized = false }
                                    )
                                }
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

    private val installStateUpdatedListener = InstallStateUpdatedListener { state ->
        if (state.installStatus() == InstallStatus.DOWNLOADED) {
            popupSnackbarForCompleteUpdate()
        }
    }

    private fun checkForUpdates() {
        lifecycleScope.launch(Dispatchers.IO) {
            appUpdateManager.appUpdateInfo.addOnSuccessListener { appUpdateInfo ->
                if (appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                    appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)
                ) {
                    runOnUiThread {
                        try {
                            appUpdateManager.startUpdateFlowForResult(appUpdateInfo, AppUpdateType.FLEXIBLE, this@MainActivity, UPDATE_REQUEST_CODE)
                        } catch (e: Exception) { Log.e("Update", "Failed to start update flow: ${e.message}") }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        appUpdateManager.appUpdateInfo.addOnSuccessListener { appUpdateInfo ->
            if (appUpdateInfo.installStatus() == InstallStatus.DOWNLOADED) {
                popupSnackbarForCompleteUpdate()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == UPDATE_REQUEST_CODE) {
            if (resultCode != RESULT_OK) { Log.w("Update", "Update flow failed/cancelled! Code: $resultCode") }
        }
    }

    private fun popupSnackbarForCompleteUpdate() {
        Toast.makeText(this, "Update Ready! Installing...", Toast.LENGTH_LONG).show()
        appUpdateManager.completeUpdate()
    }

    private fun linkServiceLogic(service: VoiceService) {
        if (!::walkieViewModel.isInitialized) return
        service.packetInterceptor = { text, ip -> walkieViewModel.handleIncomingPacket(text, ip) }
        lifecycleScope.launch {
            service.voiceServiceState.collectLatest { state ->
                if (state.incomingCall != null && state.incomingIp != null) {
                    walkieViewModel.onReceptionStarted(state.incomingCall, state.incomingIp)
                } else {
                    walkieViewModel.onReceptionEnded()
                }
            }
        }
    }

    // [FIX] Consolidate Wake Up Logic
    private fun wakeUpScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
    }

    private fun handleIntent(intent: Intent?) {
        val autoChannel = intent?.getStringExtra("auto_connect_channel")
        val isCloudWake = intent?.getBooleanExtra("is_cloud_wake", false) == true
        val wokenBy = intent?.getStringExtra("woken_by")
        val isCall = intent?.getBooleanExtra("is_call", false) == true

        if (::walkieViewModel.isInitialized) {
            if (isCloudWake && wokenBy != null) {
                walkieViewModel.setTarget(wokenBy)
                Toast.makeText(this, "Woken by $wokenBy", Toast.LENGTH_SHORT).show()
            } else if (autoChannel != null) {
                walkieViewModel.setTarget(autoChannel)
            }

            // [FIX] If this intent is for a call, FORCE screen wake up now
            if (isCall) {
                wakeUpScreen() // Handles lock screen bypass
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // Keeps it on
            }

            intent?.removeExtra("auto_connect_channel")
            intent?.removeExtra("is_cloud_wake")
            intent?.removeExtra("woken_by")
            intent?.removeExtra("is_call")
        }
    }

    private fun checkBatteryOptimizations() {
        if (hasAskedBattery) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                hasAskedBattery = true
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) { Log.e("MainActivity", "Failed to launch Battery Optimization settings", e) }
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
        } catch (e: Exception) { Log.e("MainActivity", "Failed to start and bind service", e) }
    }

    private fun performExplicitExit() {
        if (isBound) {
            try { unbindService(connection) } catch (e: Exception) {}
            isBound = false
        }
        val stopIntent = Intent(this, VoiceService::class.java).apply { action = "STOP_SERVICE" }
        startService(stopIntent)
        finishAffinity()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // [CRITICAL] Update the intent so Compose LaunchedEffects react to it
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        appUpdateManager.unregisterListener(installStateUpdatedListener)
        try { unregisterReceiver(exitReceiver) } catch (e: Exception) {}

        if (isBound) {
            voiceService?.packetInterceptor = null
            try { unbindService(connection) } catch (e: Exception) { Log.e("MainActivity", "Failed to unbind service", e) }
            isBound = false
        }
    }
}