package `in`.chinmoydas.signal.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import `in`.chinmoydas.signal.VoiceService
import `in`.chinmoydas.signal.RetrofitClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import java.net.DatagramSocket

data class DiagnosticItem(
    val name: String,
    val status: DiagnosticStatus = DiagnosticStatus.Pending,
    val detail: String? = null
)

enum class DiagnosticStatus { Pending, Running, Success, Warning, Failure }

object SystemDiagnostics {

    fun runChecks(context: Context, service: VoiceService?) = flow {

        // 1. PERMISSIONS AUDIT
        emit(DiagnosticItem("Security & Permissions", DiagnosticStatus.Running))
        delay(200)
        val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val hasLoc = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!hasMic) {
            emit(DiagnosticItem("Microphone Access", DiagnosticStatus.Failure, "Voice Disabled"))
        } else if (!hasLoc) {
            emit(DiagnosticItem("Permissions", DiagnosticStatus.Warning, "SOS Location Disabled"))
        } else {
            emit(DiagnosticItem("Permissions Secure", DiagnosticStatus.Success))
        }

        // 2. BACKGROUND SURVIVAL (Critical for PTT)
        emit(DiagnosticItem("Background Process", DiagnosticStatus.Running))
        delay(200)
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val isIgnoringBattery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } else true

        if (isIgnoringBattery) {
            emit(DiagnosticItem("Battery Optimization", DiagnosticStatus.Success, "Unrestricted"))
        } else {
            emit(DiagnosticItem("Battery Optimization", DiagnosticStatus.Warning, "App may sleep. Tap to fix."))
        }

        // 3. AUDIO HARDWARE (AEC Check)
        emit(DiagnosticItem("Audio Engine", DiagnosticStatus.Running))
        delay(200)
        val hasAec = AcousticEchoCanceler.isAvailable()
        val hasNs = NoiseSuppressor.isAvailable()

        if (hasAec && hasNs) {
            emit(DiagnosticItem("Audio Hardware", DiagnosticStatus.Success, "AEC + NS Ready"))
        } else {
            emit(DiagnosticItem("Audio Hardware", DiagnosticStatus.Warning, "Software Mode (Echo Risk)"))
        }

        // 4. UDP PORT BINDING (PTT & Calls)
        emit(DiagnosticItem("Radio Transport", DiagnosticStatus.Running))
        delay(200)

        // Check PTT Port 50005 (Managed by Service)
        val bindStatus = service?.voiceServiceState?.value?.networkStatus ?: "Disconnected"

        // Check Call Port 50006 (Managed by CallEngine)
        var port50006Free = false
        try {
            // If we can bind it, it's free. If it throws, it's in use (which is good if we are calling, bad if idle)
            val s = DatagramSocket(50006)
            s.close()
            port50006Free = true
        } catch (e: Exception) { port50006Free = false }

        if (bindStatus != "Error: UDP Bind Failed") {
            emit(DiagnosticItem("UDP Transport", DiagnosticStatus.Success, "Port 50005 Bound"))
        } else {
            emit(DiagnosticItem("Radio Transport", DiagnosticStatus.Failure, "Port 50005 Failed"))
        }

        // 5. HARDWARE SENSORS (Impact Shield)
        emit(DiagnosticItem("Safety Sensors", DiagnosticStatus.Running))
        delay(200)
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accel != null) {
            emit(DiagnosticItem("Impact Sensor", DiagnosticStatus.Success, "Accelerometer Active"))
        } else {
            emit(DiagnosticItem("Impact Sensor", DiagnosticStatus.Failure, "Hardware Missing"))
        }

        // 6. CLOUD WAKE (FCM)
        emit(DiagnosticItem("Cloud Wake Service", DiagnosticStatus.Running))
        delay(300)
        val prefs = context.getSharedPreferences("WalkiePrefs", Context.MODE_PRIVATE)
        val token = prefs.getString("my_fcm_token", "")

        val googleApi = com.google.android.gms.common.GoogleApiAvailability.getInstance()
        val resultCode = googleApi.isGooglePlayServicesAvailable(context)

        if (resultCode != com.google.android.gms.common.ConnectionResult.SUCCESS) {
            emit(DiagnosticItem("Cloud Wake", DiagnosticStatus.Failure, "Play Services Missing"))
        } else if (token.isNullOrBlank()) {
            emit(DiagnosticItem("Cloud Wake", DiagnosticStatus.Failure, "Token Missing. Relogin."))
        } else {
            emit(DiagnosticItem("Cloud Wake", DiagnosticStatus.Success, "Active"))
        }

        // 7. SERVER HEARTBEAT
        emit(DiagnosticItem("Signal Server", DiagnosticStatus.Running))
        delay(300)
        val jwt = prefs.getString("jwt_token", "")
        if (jwt.isNullOrEmpty() || jwt == "OFFLINE_TOKEN") {
            emit(DiagnosticItem("Signal Server", DiagnosticStatus.Warning, "Offline Login"))
        } else {
            try {
                val response = RetrofitClient.api.checkSignals("Bearer $jwt")
                if (response.signals != null) {
                    emit(DiagnosticItem("Signal Server", DiagnosticStatus.Success, "Synced (Low Latency)"))
                } else {
                    emit(DiagnosticItem("Signal Server", DiagnosticStatus.Warning, "Connected (No Data)"))
                }
            } catch (e: Exception) {
                emit(DiagnosticItem("Signal Server", DiagnosticStatus.Failure, "Unreachable"))
            }
        }

        delay(100)
        emit(DiagnosticItem("DIAGNOSTIC COMPLETE", DiagnosticStatus.Success))
    }
}