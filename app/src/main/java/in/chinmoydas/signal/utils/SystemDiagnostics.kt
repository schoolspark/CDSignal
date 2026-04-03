package `in`.chinmoydas.signal.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import `in`.chinmoydas.signal.VoiceService
import `in`.chinmoydas.signal.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

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

        // 2. BACKGROUND SURVIVAL
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

        // 3. AUDIO HARDWARE
        emit(DiagnosticItem("Audio Engine", DiagnosticStatus.Running))
        delay(200)
        val hasAec = AcousticEchoCanceler.isAvailable()
        val hasNs = NoiseSuppressor.isAvailable()

        if (hasAec && hasNs) {
            emit(DiagnosticItem("Audio Hardware", DiagnosticStatus.Success, "AEC + NS Ready"))
        } else {
            emit(DiagnosticItem("Audio Hardware", DiagnosticStatus.Warning, "Software Mode (Echo Risk)"))
        }

        // 4. UDP PORT BINDING
        emit(DiagnosticItem("Radio Transport", DiagnosticStatus.Running))
        delay(200)
        val bindStatus = service?.voiceServiceState?.value?.networkStatus ?: "Disconnected"
        if (bindStatus != "Error: UDP Bind Failed" && bindStatus != "Disconnected") {
            emit(DiagnosticItem("UDP Transport", DiagnosticStatus.Success, "Port 50005 Bound (Multiplexed)"))
        } else {
            emit(DiagnosticItem("Radio Transport", DiagnosticStatus.Failure, "Port 50005 Failed"))
        }

        // 5. HARDWARE SENSORS
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

        // 7. MOTHERSHIP AUTH & RELAY
        emit(DiagnosticItem("Mothership Uplink", DiagnosticStatus.Running))
        delay(300)
        val jwt = prefs.getString("jwt_token", "")
        if (jwt.isNullOrEmpty() || jwt == "OFFLINE_TOKEN") {
            emit(DiagnosticItem("Mothership Uplink", DiagnosticStatus.Warning, "Offline Mode Active"))
        } else {
            // [FIXED] Removed withContext(Dispatchers.IO).
            // The whole flow is now shifted to IO using .flowOn() at the bottom.
            try {
                val signalResponse = RetrofitClient.api.checkSignals("Bearer $jwt")
                val credsResponse = RetrofitClient.api.getSignalCreds("Bearer $jwt")
                val hasRelay = credsResponse.status == "success" && !credsResponse.iceServers.isNullOrEmpty()

                if (signalResponse.signals != null && hasRelay) {
                    emit(DiagnosticItem("Mothership Uplink", DiagnosticStatus.Success, "Synced + TURN Relay Ready"))
                } else if (signalResponse.signals != null) {
                    emit(DiagnosticItem("Mothership Uplink", DiagnosticStatus.Warning, "Synced (No Relay Credentials)"))
                } else {
                    emit(DiagnosticItem("Mothership Uplink", DiagnosticStatus.Warning, "Connected (No Data)"))
                }
            } catch (e: Exception) {
                emit(DiagnosticItem("Mothership Uplink", DiagnosticStatus.Failure, "Unreachable"))
            }
        }

        delay(100)
        emit(DiagnosticItem("DIAGNOSTIC COMPLETE", DiagnosticStatus.Success))
    }.flowOn(Dispatchers.IO) // [CRITICAL] This is the correct way to handle IO in a Flow
}