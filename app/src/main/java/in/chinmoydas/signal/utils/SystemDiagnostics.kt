package `in`.chinmoydas.signal.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import `in`.chinmoydas.signal.VoiceService
import `in`.chinmoydas.signal.RetrofitClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow

data class DiagnosticItem(
    val name: String,
    val status: DiagnosticStatus = DiagnosticStatus.Pending,
    val detail: String? = null
)

enum class DiagnosticStatus { Pending, Running, Success, Warning, Failure }

object SystemDiagnostics {

    fun runChecks(context: Context, service: VoiceService?) = flow {

        // 1. Audio Hardware (Critical)
        emit(DiagnosticItem("Verifying Audio Hardware", DiagnosticStatus.Running))
        delay(300)
        val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            emit(DiagnosticItem("Microphone Access Denied", DiagnosticStatus.Failure, "Grant permission in Settings"))
            // We don't return@flow here to allow checking other systems (like server conn) even if mic is off
        } else {
            emit(DiagnosticItem("Audio System Secure", DiagnosticStatus.Success))
        }

        // 2. Radio Bind (Critical)
        emit(DiagnosticItem("Checking Radio Ports", DiagnosticStatus.Running))
        delay(300)
        val bindStatus = service?.voiceServiceState?.value?.networkStatus ?: "Disconnected"

        if (bindStatus != "Error: UDP Bind Failed") {
            emit(DiagnosticItem("Port 50005 Bound", DiagnosticStatus.Success))
        } else {
            emit(DiagnosticItem("Radio Bind Failed", DiagnosticStatus.Failure, "Restart App"))
        }

        // 3. Network Transport
        emit(DiagnosticItem("Checking Connectivity", DiagnosticStatus.Running))
        delay(400)
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val hasNet = cm.activeNetwork?.let {
            cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } == true

        if (hasNet) {
            emit(DiagnosticItem("Global Network Active", DiagnosticStatus.Success))
        } else {
            emit(DiagnosticItem("Offline Mode Active", DiagnosticStatus.Warning, "Local Range Only"))
        }

        // 4. CLOUD WAKE TOKEN (New & Critical for Internet Calls)
        emit(DiagnosticItem("Cloud Wake Service", DiagnosticStatus.Running))
        delay(500)
        val prefs = context.getSharedPreferences("WalkiePrefs", Context.MODE_PRIVATE)
        val token = prefs.getString("my_fcm_token", "")

        // Google Play Services Check
        val googleApi = com.google.android.gms.common.GoogleApiAvailability.getInstance()
        val resultCode = googleApi.isGooglePlayServicesAvailable(context)

        if (resultCode != com.google.android.gms.common.ConnectionResult.SUCCESS) {
            emit(DiagnosticItem("Cloud Wake Service", DiagnosticStatus.Failure, "Google Play Services Missing"))
        } else if (token.isNullOrBlank()) {
            // This catches the "Fresh Install" bug we just fixed
            emit(DiagnosticItem("Cloud Wake Service", DiagnosticStatus.Failure, "Token Missing. Relogin required."))
        } else {
            val shortToken = token.take(6) + "..."
            emit(DiagnosticItem("Cloud Wake Service", DiagnosticStatus.Success, "Active ($shortToken)"))
        }

        // 5. SERVER HEARTBEAT (New)
        emit(DiagnosticItem("Server Link", DiagnosticStatus.Running))
        delay(500)
        val jwt = prefs.getString("jwt_token", "")
        if (jwt.isNullOrEmpty() || jwt == "OFFLINE_TOKEN") {
            emit(DiagnosticItem("Server Link", DiagnosticStatus.Warning, "Offline Login Mode"))
        } else {
            try {
                // Lightweight ping to ensure API is reachable and JWT is valid
                RetrofitClient.api.checkSignals("Bearer $jwt")
                emit(DiagnosticItem("Server Link", DiagnosticStatus.Success, "Connected"))
            } catch (e: Exception) {
                emit(DiagnosticItem("Server Link", DiagnosticStatus.Failure, "Unreachable (Check Internet)"))
            }
        }

        // 6. Guardian Services (Config Check)
        emit(DiagnosticItem("Scanning Guardian Services", DiagnosticStatus.Running))
        delay(300)
        val remoteAllowed = prefs.getBoolean("allow_remote_control", false)

        if (remoteAllowed) {
            emit(DiagnosticItem("Guardian Link Active", DiagnosticStatus.Success))
        } else {
            emit(DiagnosticItem("Guardian Link Standby", DiagnosticStatus.Warning, "Remote Control Disabled"))
        }

        delay(200)
        emit(DiagnosticItem("DIAGNOSTIC COMPLETE", DiagnosticStatus.Success))
    }
}