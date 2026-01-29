package `in`.chinmoydas.signal

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import `in`.chinmoydas.signal.data.MainRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SignalFirebaseService : FirebaseMessagingService() {

    private val tag = "FCM"

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(tag, "New Token Generated: $token")

        // 1. Save to Local Storage & Mark Dirty
        // "fcm_dirty" tells VoiceService to retry sync if we fail here (e.g. no internet)
        getSharedPreferences("WalkiePrefs", Context.MODE_PRIVATE)
            .edit()
            .putString("my_fcm_token", token)
            .putBoolean("fcm_dirty", true)
            .apply()

        // 2. Attempt Sync (with Retry Logic)
        CoroutineScope(Dispatchers.IO).launch {
            val repository = MainRepository(applicationContext)
            var attempts = 0

            while (attempts < 3) {
                try {
                    repository.syncFcmTokenToServer()
                    // Sync Success: Clear dirty flag
                    getSharedPreferences("WalkiePrefs", Context.MODE_PRIVATE)
                        .edit().putBoolean("fcm_dirty", false).apply()
                    Log.d(tag, "Token Synced Successfully")
                    break // Exit loop
                } catch (e: Exception) {
                    attempts++
                    Log.w(tag, "Sync failed (Attempt $attempts): ${e.message}")
                    delay(2000 * attempts.toLong()) // Exponential backoff (2s, 4s, 6s)
                }
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val data = remoteMessage.data
        if (data["action"] == "WAKE_RADIO") {
            val sender = data["sender"] ?: "Unknown"
            Log.d(tag, "Wake Signal Received from: $sender")

            // 1. Acquire WakeLock (Critical for CPU to stay awake)
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Signal:CloudWakeLock"
            )
            wakeLock.acquire(10 * 1000L) // 10s timeout safety

            // 2. Start VoiceService
            val intent = Intent(this, VoiceService::class.java).apply {
                action = "START_SERVICE"
                putExtra("is_cloud_wake", true)
                putExtra("woken_by", sender)
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to start VoiceService (Background Restriction): ${e.message}")

                // [MISSION CRITICAL FALLBACK]
                // If Android blocks the service, post a High-Priority notification
                // so the user can tap to wake the radio manually.
                postFallbackNotification(sender)
            }
        }
    }

    private fun postFallbackNotification(sender: String) {
        val channelId = "cd_signal_wake"
        val manager = getSystemService(NotificationManager::class.java)

        // Create High Importance Channel
        val channel = NotificationChannel(channelId, "Wake Alerts", NotificationManager.IMPORTANCE_HIGH)
        manager.createNotificationChannel(channel)

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Incoming Radio Call")
            .setContentText("$sender is trying to reach you!")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pendingIntent, true) // Heads-up display
            .setAutoCancel(true)
            .build()

        manager.notify(999, notification)
    }
}