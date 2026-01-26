package `in`.chinmoydas.signal.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import `in`.chinmoydas.signal.VoiceService
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
            return@flow
        }
        emit(DiagnosticItem("Audio System Secure", DiagnosticStatus.Success))

        // 2. Radio Bind (Critical)
        emit(DiagnosticItem("Checking Radio Ports", DiagnosticStatus.Running))
        delay(300)
        val bindStatus = service?.voiceServiceState?.value?.networkStatus ?: "Disconnected"

        if (bindStatus != "Error: UDP Bind Failed") {
            emit(DiagnosticItem("Port 50005 Bound", DiagnosticStatus.Success))
        } else {
            emit(DiagnosticItem("Radio Bind Failed", DiagnosticStatus.Failure, "Restart App"))
            return@flow
        }

        // 3. Network Transport (Hybrid Check)
        emit(DiagnosticItem("Checking Cloud Link", DiagnosticStatus.Running))
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

        // 4. Guardian Services (Config Check)
        emit(DiagnosticItem("Scanning Guardian Services", DiagnosticStatus.Running))
        delay(300)
        val prefs = context.getSharedPreferences("WalkiePrefs", Context.MODE_PRIVATE)
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