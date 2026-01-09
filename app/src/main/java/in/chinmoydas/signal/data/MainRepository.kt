package `in`.chinmoydas.signal.data

import android.content.Context
import android.content.SharedPreferences
import `in`.chinmoydas.signal.RetrofitClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainRepository(context: Context) {
    private val db = AppDatabase.getDatabase(context)
    private val contactDao = db.contactDao()
    private val callLogDao = db.callLogDao()
    private val prefs: SharedPreferences = context.getSharedPreferences("WalkiePrefs", Context.MODE_PRIVATE)

    // --- Reactive Preferences ---
    private val _targetUser = MutableStateFlow(prefs.getString("current_target", "") ?: "")
    val targetUser: StateFlow<String> = _targetUser.asStateFlow()

    private val _myUsername = MutableStateFlow(prefs.getString("username", "User") ?: "User")
    val myUsername: StateFlow<String> = _myUsername.asStateFlow()

    private val _myPairingCode = MutableStateFlow(prefs.getString("my_pairing_code", "----") ?: "----")
    val myPairingCode: StateFlow<String> = _myPairingCode.asStateFlow()

    // CHANGED: Generic "Config Changed" trigger for Keys AND Blocks
    private val _configTrigger = MutableStateFlow(0)
    val configTrigger: StateFlow<Int> = _configTrigger.asStateFlow()

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            "current_target" -> _targetUser.value = prefs.getString("current_target", "") ?: ""
            "username" -> _myUsername.value = prefs.getString("username", "User") ?: "User"
            "my_pairing_code" -> _myPairingCode.value = prefs.getString("my_pairing_code", "----") ?: "----"
            // We listen to this key to sync processes
            "config_refresh_trigger" -> _configTrigger.value = prefs.getInt("config_refresh_trigger", 0)
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    // --- Contacts ---
    suspend fun getAllContacts() = contactDao.getAllContacts()
    suspend fun getBlockedContacts() = contactDao.getBlockedContacts()

    suspend fun saveContact(name: String, ip: String, code: String) {
        contactDao.insert(ContactEntity(name, ip, code, isBlocked = false))
        triggerConfigRefresh() // FIX: Notify Service to reload keys immediately
    }

    suspend fun deleteContact(name: String) {
        contactDao.delete(ContactEntity(name, "", ""))
        triggerConfigRefresh()
    }

    suspend fun setBlockedStatus(name: String, blocked: Boolean) {
        contactDao.setBlockedStatus(name, blocked)
        triggerConfigRefresh()
    }

    // Helper to update the shared preference that triggers the flow
    private fun triggerConfigRefresh() {
        val current = prefs.getInt("config_refresh_trigger", 0)
        prefs.edit().putInt("config_refresh_trigger", current + 1).apply()
    }

    // --- Call Logs ---
    suspend fun getAllLogs() = callLogDao.getAllLogs()
    suspend fun insertLog(name: String, isIncoming: Boolean) {
        callLogDao.insert(CallLog(callerName = name, isIncoming = isIncoming))
    }
    suspend fun clearLogs() = callLogDao.clearAll()

    // --- Preferences ---
    fun getTargetUser(): String = targetUser.value
    fun setTargetUser(name: String) {
        prefs.edit().putString("current_target", name).apply()
    }

    fun getMyPairingCode(): String = myPairingCode.value
    fun saveMyPairingCode(code: String) {
        prefs.edit().putString("my_pairing_code", code).apply()
    }

    fun getToken(): String? = prefs.getString("jwt_token", null)

    // --- Network / API ---
    suspend fun findPeer(token: String, name: String, code: String) =
        RetrofitClient.api.findPeer("Bearer $token", name, code)

    suspend fun findChannel(token: String, name: String, code: String) =
        RetrofitClient.api.findChannel("Bearer $token", name, code)

    suspend fun resetCode(token: String) =
        RetrofitClient.api.resetCode("Bearer $token")
}