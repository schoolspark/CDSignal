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

    private val _targetUser = MutableStateFlow(prefs.getString("current_target", "") ?: "")
    val targetUser: StateFlow<String> = _targetUser.asStateFlow()
    private val _channelKey = MutableStateFlow(prefs.getString("channel_key", "") ?: "")
    val channelKey: StateFlow<String> = _channelKey.asStateFlow()
    private val _myUsername = MutableStateFlow(prefs.getString("username", "User") ?: "User")
    val myUsername: StateFlow<String> = _myUsername.asStateFlow()

    private val _myPairingCode = MutableStateFlow(prefs.getString("my_pairing_code", "----") ?: "----")
    val myPairingCode: StateFlow<String> = _myPairingCode.asStateFlow()

    private val _configTrigger = MutableStateFlow(0)
    val configTrigger: StateFlow<Int> = _configTrigger.asStateFlow()

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            "current_target" -> _targetUser.value = prefs.getString("current_target", "") ?: ""
            "username" -> _myUsername.value = prefs.getString("username", "User") ?: "User"
            "my_pairing_code" -> _myPairingCode.value = prefs.getString("my_pairing_code", "----") ?: "----"
            "channel_key" -> _channelKey.value = prefs.getString("channel_key", "") ?: ""
            "config_refresh_trigger" -> _configTrigger.value = prefs.getInt("config_refresh_trigger", 0)
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    suspend fun getAllContacts() = contactDao.getAllContacts()
    suspend fun getBlockedContacts() = contactDao.getBlockedContacts()

    // --- FIX: Preserve "Blocked" status when updating a contact ---
    suspend fun saveContact(name: String, ip: String, code: String) {
        val isBlocked = contactDao.isBlocked(name) // Check if already blocked
        contactDao.insert(ContactEntity(name, ip, code, isBlocked = isBlocked))
        triggerConfigRefresh()
    }
    // -------------------------------------------------------------

    // --- NEW: Efficient Background Update ---
    // Called by VoiceService to silently update IP without redrawing UI
    suspend fun updateContactIp(name: String, ip: String) {
        contactDao.updateIp(name, ip)
        // Note: We do NOT trigger config refresh here.
        // This prevents the UI from flickering while you are talking.
    }

    suspend fun deleteContact(name: String) {
        contactDao.delete(ContactEntity(name, "", ""))
        triggerConfigRefresh()
    }

    suspend fun setBlockedStatus(name: String, blocked: Boolean) {
        contactDao.setBlockedStatus(name, blocked)
        triggerConfigRefresh()
    }

    private fun triggerConfigRefresh() {
        val current = prefs.getInt("config_refresh_trigger", 0)
        prefs.edit().putInt("config_refresh_trigger", current + 1).apply()
    }

    suspend fun getAllLogs() = callLogDao.getAllLogs()
    suspend fun insertLog(name: String, isIncoming: Boolean) {
        callLogDao.insert(CallLog(callerName = name, isIncoming = isIncoming))
    }
    suspend fun clearLogs() = callLogDao.clearAll()

    fun getTargetUser(): String = targetUser.value
    fun setTargetUser(name: String) {
        prefs.edit().putString("current_target", name).apply()
    }
    fun saveChannelKey(key: String) {
        prefs.edit().putString("channel_key", key).apply()
    }

    fun getMyPairingCode(): String = myPairingCode.value
    fun saveMyPairingCode(code: String) {
        prefs.edit().putString("my_pairing_code", code).apply()
    }

    fun getToken(): String? = prefs.getString("jwt_token", null)

    suspend fun findPeer(token: String, name: String, code: String) =
        RetrofitClient.api.findPeer("Bearer $token", name, code)

    suspend fun findChannel(token: String, name: String, code: String) =
        RetrofitClient.api.findChannel("Bearer $token", name, code)

    suspend fun resetCode(token: String) =
        RetrofitClient.api.resetCode("Bearer $token")
}