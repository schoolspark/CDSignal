package `in`.chinmoydas.signal.viewmodel

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import `in`.chinmoydas.signal.RetrofitClient
import retrofit2.HttpException
import kotlinx.coroutines.launch

class LoginViewModel : ViewModel() {
    var username by mutableStateOf("")
    var password by mutableStateOf("")
    var isLoading by mutableStateOf(false)
    var errorMsg by mutableStateOf<String?>(null)

    fun loadLastUser(prefs: SharedPreferences) {
        val savedUser = prefs.getString("last_username", "") ?: ""
        if (savedUser.isNotEmpty()) username = savedUser
    }

    // --- OFFLINE BYPASS ---
    fun loginOffline(name: String, prefs: SharedPreferences, onSuccess: () -> Unit) {
        if (name.isBlank()) { errorMsg = "Enter a display name"; return }
        prefs.edit().putString("jwt_token", "OFFLINE_TOKEN").putString("username", name).putString("my_pairing_code", "----").apply()
        onSuccess()
    }

    fun login(prefs: SharedPreferences, onSuccess: () -> Unit) {
        if (username.isBlank() || password.isBlank()) { errorMsg = "Enter user/pass"; return }
        isLoading = true; errorMsg = null

        viewModelScope.launch {
            try {
                // Send "0000" to let server decide
                val response = RetrofitClient.api.login(username, password, "0000")
                if (response.status == "success") {
                    prefs.edit()
                        .putString("jwt_token", response.token)
                        .putString("username", response.username)
                        .putString("last_username", response.username)
                        .putString("my_pairing_code", response.code)
                        .apply()
                    onSuccess()
                } else {
                    errorMsg = response.error ?: "Auth Failed"
                }
            } catch (e: HttpException) {
                if (e.code() == 401) errorMsg = "Incorrect Password"
                else errorMsg = "Server Error: ${e.code()}"
            } catch (e: Exception) {
                errorMsg = "Connection failed."
            } finally {
                isLoading = false
            }
        }
    }
}