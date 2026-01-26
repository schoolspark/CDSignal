package `in`.chinmoydas.signal

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class SignalFirebaseService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d("FCM", "New Token: $token")

        // Save my token securely so I can share it via UDP later
        getSharedPreferences("WalkiePrefs", Context.MODE_PRIVATE)
            .edit()
            .putString("my_fcm_token", token)
            .apply()
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        // We only care about high-priority data messages with action="WAKE_RADIO"
        val data = remoteMessage.data
        if (data["action"] == "WAKE_RADIO") {

            Log.d("FCM", "Wake Signal Received from: ${data["sender"]}")

            // 1. Acquire a temporary CPU WakeLock (10 seconds)
            // This guarantees the CPU stays awake long enough to start the Radio
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Signal:CloudWakeLock"
            )
            wakeLock.acquire(10 * 1000L) // 10 seconds timeout

            // 2. Start the VoiceService (The "Main Highway")
            val intent = Intent(this, VoiceService::class.java).apply {
                action = "START_SERVICE"
                putExtra("is_cloud_wake", true) // Tell service it was woken by cloud
                putExtra("woken_by", data["sender"])
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Note: The WakeLock releases automatically after 10s,
            // but the VoiceService will take its own WakeLock, so we remain active.
        }
    }
}