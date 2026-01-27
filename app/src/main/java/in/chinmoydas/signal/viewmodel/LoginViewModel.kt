package `in`.chinmoydas.signal.viewmodel

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.messaging.FirebaseMessaging
import `in`.chinmoydas.signal.RetrofitClient
import `in`.chinmoydas.signal.data.MainRepository
import retrofit2.HttpException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class LoginViewModel : ViewModel() {
    var username by mutableStateOf("")
    var password by mutableStateOf("")
    var isLoading by mutableStateOf(false)
    var errorMsg by mutableStateOf<String?>(null)

    var showResetDialog by mutableStateOf(false)
    var resetStep by mutableStateOf(0) // 0: Request, 1: Verify & Reset
    var resetUsername by mutableStateOf("")
    var resetOtp by mutableStateOf("")
    var resetNewPass by mutableStateOf("")
    var resetMsg by mutableStateOf<String?>(null)
    var resetLoading by mutableStateOf(false)

    fun loadLastUser(prefs: SharedPreferences) {
        val savedUser = prefs.getString("last_username", "") ?: ""
        if (savedUser.isNotEmpty()) username = savedUser
    }

    // --- OFFLINE BYPASS ---
    fun loginOffline(name: String, prefs: SharedPreferences, onSuccess: () -> Unit) {
        if (name.isBlank()) { errorMsg = "Enter a display name"; return }
        prefs.edit()
            .putString("jwt_token", "OFFLINE_TOKEN")
            .putString("username", name)
            .putString("my_pairing_code", "----")
            .apply()
        onSuccess()
    }

    // [UPDATED] Login Function with Robust Token Sync
    fun login(context: Context, prefs: SharedPreferences, onSuccess: () -> Unit) {
        if (username.isBlank() || password.isBlank()) { errorMsg = "Enter user/pass"; return }
        isLoading = true; errorMsg = null

        viewModelScope.launch {
            try {
                // Send "0000" as code placeholder to let server decide/validate
                val response = RetrofitClient.api.login(username, password, "0000")

                if (response.status == "success") {
                    // 1. Save Session Data
                    prefs.edit()
                        .putString("jwt_token", response.token)
                        .putString("username", response.username)
                        .putString("last_username", response.username)
                        .putString("my_pairing_code", response.code)
                        .apply()

                    // 2. [CRITICAL FIX] Force Sync FCM Token immediately
                    // This handles the "Race Condition" where the app started without a token.
                    try {
                        val repo = MainRepository(context)
                        val savedToken = prefs.getString("my_fcm_token", null)

                        if (!savedToken.isNullOrBlank()) {
                            // Scenario A: Token exists (Normal case), just sync it.
                            repo.syncFcmTokenToServer()
                            Log.d("LOGIN", "Synced existing FCM token")
                        } else {
                            // Scenario B: Token missing (Fresh Install), fetch it explicitly.
                            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                                if (!token.isNullOrBlank()) {
                                    // Save it first
                                    prefs.edit().putString("my_fcm_token", token).apply()

                                    // Then sync it on a background thread
                                    viewModelScope.launch(Dispatchers.IO) {
                                        repo.syncFcmTokenToServer()
                                        Log.d("LOGIN", "Fetched & Synced NEW FCM token")
                                    }
                                }
                            }.addOnFailureListener { e ->
                                Log.e("LOGIN", "Failed to fetch FCM token", e)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("LOGIN", "Post-Login Sync Failed", e)
                    }

                    onSuccess()
                } else {
                    errorMsg = response.error ?: "Auth Failed"
                }
            } catch (e: HttpException) {
                if (e.code() == 401) errorMsg = "Incorrect Password"
                else errorMsg = "Server Error: ${e.code()}"
            } catch (e: Exception) {
                errorMsg = "Connection failed."
                e.printStackTrace()
            } finally {
                isLoading = false
            }
        }
    }

    // --- PASSWORD RESET LOGIC ---

    fun requestOtp() {
        if (resetUsername.isBlank()) { resetMsg = "Enter username"; return }
        resetLoading = true; resetMsg = null

        viewModelScope.launch {
            try {
                val response = RetrofitClient.api.requestOtp(username = resetUsername)
                if (response.status == "success") {
                    resetStep = 1
                    resetMsg = "Code sent to email."
                } else {
                    resetMsg = response.message ?: "Error from server"
                }
            } catch (e: Exception) {
                e.printStackTrace()
                resetMsg = "Connection failed: ${e.localizedMessage}"
            } finally {
                resetLoading = false
            }
        }
    }

    fun confirmReset() {
        if (resetOtp.isBlank() || resetNewPass.length < 4) { resetMsg = "Check OTP/Password"; return }
        resetLoading = true; resetMsg = null
        viewModelScope.launch {
            try {
                val response = RetrofitClient.api.resetPassword(
                    username = resetUsername,
                    otp = resetOtp,
                    pass = resetNewPass
                )
                if (response.status == "success") {
                    showResetDialog = false
                    errorMsg = "Password reset! Please login."
                    // Clear reset states
                    resetStep = 0; resetOtp = ""; resetNewPass = ""
                } else {
                    resetMsg = response.message ?: "Reset failed"
                }
            } catch (e: Exception) {
                resetMsg = "Connection failed"
            } finally {
                resetLoading = false
            }
        }
    }
}