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
            val senderIp = data["sender_ip"]
            val senderPort = data["sender_port"]?.toIntOrNull() ?: 50005

            Log.d(tag, "Wake Signal from $sender ($senderIp:$senderPort)")

            // Acquire temporary lock just to process this logic
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Signal:CloudWakeLock")
            wakeLock.acquire(5 * 1000L) // 5s timeout is enough to launch service

            startVoiceServiceForPunchBack(sender, senderIp, senderPort)
        }
    }

    private fun startVoiceServiceForPunchBack(sender: String, ip: String?, port: Int) {
        val intent = Intent(this, VoiceService::class.java).apply {
            action = "ACTION_PUNCH_BACK"
            putExtra("target_ip", ip)
            putExtra("target_port", port)
            putExtra("sender_name", sender)
            putExtra("is_cloud_wake", true)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            // [FIX] Android 12+ "ForegroundServiceStartNotAllowedException"
            Log.e(tag, "Background Start Failed: ${e.message}. Posting Fallback Notification.")
            postFallbackNotification(sender)
        }
    }

    private fun postFallbackNotification(sender: String) {
        val channelId = "cd_signal_wake"
        val manager = getSystemService(NotificationManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Wake Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Incoming Call Alerts"
                enableVibration(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                // [FIX] Ensure sound plays even in Do Not Disturb if allowed
                setBypassDnd(true)
            }
            manager.createNotificationChannel(channel)
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("is_cloud_wake", true)
            putExtra("woken_by", sender)
            // [FIX] Pass "is_call" so MainActivity keeps screen on
            putExtra("is_call", true)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Incoming Signal")
            .setContentText("$sender is transmitting...")
            // [FIX] Use your own icon, system icons are unsafe
            .setSmallIcon(R.mipmap.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pendingIntent, true)
            .setAutoCancel(true)
            .setTimeoutAfter(30000)
            .build()

        manager.notify(999, notification)
    }
}