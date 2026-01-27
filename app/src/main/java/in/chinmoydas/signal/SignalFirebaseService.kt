package `in`.chinmoydas.signal

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import `in`.chinmoydas.signal.data.MainRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SignalFirebaseService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d("FCM", "New Token Generated: $token")

        // 1. Save to Local Storage (Source of Truth)
        // We use "my_fcm_token" to match MainRepository.syncFcmTokenToServer()
        getSharedPreferences("WalkiePrefs", Context.MODE_PRIVATE)
            .edit()
            .putString("my_fcm_token", token)
            .apply()

        // 2. [CRITICAL FIX] Sync to Server Immediately
        // If the token changes while the app is in the background, we must tell the server.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repository = MainRepository(applicationContext)
                repository.syncFcmTokenToServer()
                Log.d("FCM", "Rotated Token Synced to Server Successfully")
            } catch (e: Exception) {
                Log.e("FCM", "Failed to Sync Rotated Token: ${e.message}")
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        // We only care about high-priority data messages with action="WAKE_RADIO"
        val data = remoteMessage.data
        if (data["action"] == "WAKE_RADIO") {

            val sender = data["sender"] ?: "Unknown"
            Log.d("FCM", "Wake Signal Received from: $sender")

            // 1. Acquire a temporary CPU WakeLock (10 seconds)
            // This guarantees the CPU stays awake long enough to start the Radio
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Signal:CloudWakeLock"
            )
            // Safety: Set a timeout so we don't drain battery if service fails to start
            wakeLock.acquire(10 * 1000L)

            // 2. Start the VoiceService (The "Main Highway")
            val intent = Intent(this, VoiceService::class.java).apply {
                action = "START_SERVICE"
                putExtra("is_cloud_wake", true) // Tell service it was woken by cloud
                putExtra("woken_by", sender)
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } catch (e: Exception) {
                Log.e("FCM", "Failed to start VoiceService", e)
            }

            // Note: The WakeLock releases automatically after 10s,
            // but the VoiceService will take its own WakeLock, so we remain active.
        }
    }
}