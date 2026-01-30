package `in`.chinmoydas.signal

import android.app.ActivityManager
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

        getSharedPreferences("WalkiePrefs", Context.MODE_PRIVATE)
            .edit()
            .putString("my_fcm_token", token)
            .putBoolean("fcm_dirty", true)
            .apply()

        CoroutineScope(Dispatchers.IO).launch {
            val repository = MainRepository(applicationContext)
            var attempts = 0
            while (attempts < 3) {
                try {
                    repository.syncFcmTokenToServer()
                    getSharedPreferences("WalkiePrefs", Context.MODE_PRIVATE)
                        .edit().putBoolean("fcm_dirty", false).apply()
                    Log.d(tag, "Token Synced Successfully")
                    break
                } catch (e: Exception) {
                    attempts++
                    Log.w(tag, "Sync failed (Attempt $attempts): ${e.message}")
                    delay(2000 * attempts.toLong())
                }
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val data = remoteMessage.data
        if (data["action"] == "WAKE_RADIO") {
            val sender = data["sender"] ?: "Unknown"
            Log.d(tag, "Wake Signal Received from: $sender")

            // 1. Acquire WakeLock (Crucial for CPU to stay awake during processing)
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Signal:CloudWakeLock"
            )
            // Acquire with timeout to prevent battery drain if logic fails
            wakeLock.acquire(10 * 1000L)

            // 2. "Smart" Start Strategy
            if (isAppInForeground()) {
                // Scenario A: User is looking at the app. Start Audio immediately.
                startVoiceServiceDirectly(sender)
            } else {
                // Scenario B: App is background/killed.
                // DO NOT start service directly (causes crash on Android 12+).
                // Use Full Screen Intent (FSI) to wake the user legally.
                postFallbackNotification(sender)
            }

            // WakeLock will release automatically after timeout or we can release manually if needed,
            // but timeout is safer for fire-and-forget.
        }
    }

    private fun isAppInForeground(): Boolean {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val appProcesses = activityManager.runningAppProcesses ?: return false
        val packageName = packageName
        for (appProcess in appProcesses) {
            if (appProcess.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                appProcess.processName == packageName) {
                return true
            }
        }
        return false
    }

    private fun startVoiceServiceDirectly(sender: String) {
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
            Log.e(tag, "Service Start Failed: ${e.message}")
            postFallbackNotification(sender) // Fail-safe
        }
    }

    private fun postFallbackNotification(sender: String) {
        val channelId = "cd_signal_wake"
        val manager = getSystemService(NotificationManager::class.java)

        // Create High Importance Channel (Bypasses Do Not Disturb if possible)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Wake Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Incoming Call Alerts"
                enableVibration(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(channel)
        }

        // PendingIntent that launches MainActivity
        val intent = Intent(this, MainActivity::class.java).apply {
            // These flags ensure a fresh task if the app was killed
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("is_cloud_wake", true)
            putExtra("woken_by", sender)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Incoming Radio Call")
            .setContentText("$sender is calling...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_HIGH) // for pre-Oreo
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pendingIntent, true) // <--- THIS WAKES THE DEVICE
            .setAutoCancel(true)
            .setTimeoutAfter(30000) // Auto-dismiss if ignored
            .build()

        manager.notify(999, notification)
    }
}