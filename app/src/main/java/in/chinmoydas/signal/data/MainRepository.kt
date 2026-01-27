package `in`.chinmoydas.signal.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import `in`.chinmoydas.signal.GenericResponse
import `in`.chinmoydas.signal.RetrofitClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class MainRepository(context: Context) {
    private val db = AppDatabase.getDatabase(context)
    private val contactDao = db.contactDao()
    private val callLogDao = db.callLogDao()
    private val pagerDao = db.pagerDao() // [NEW] Pager DAO
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

    // [NEW] Expose Pager Entries as a Flow for Real-time UI updates
    val pagerEntries: Flow<List<PagerEntry>> = pagerDao.getAllEntries()

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

    // --- PAGER / SILENT COMMS METHODS ---

    suspend fun insertPagerEntry(entry: PagerEntry) {
        pagerDao.insert(entry)
    }

    suspend fun deletePagerEntry(entry: PagerEntry) {
        // If it's an audio file, delete the actual file from storage to save space
        if (entry.type == "AUDIO") {
            try {
                val file = File(entry.content)
                if (file.exists()) file.delete()
            } catch (e: Exception) { e.printStackTrace() }
        }
        // Then delete the database record
        pagerDao.delete(entry)
    }

    suspend fun clearPagerHistory() {
        // Note: For a true cleanup, you might want to iterate and delete files first,
        // but for speed, clearing the DB is usually sufficient for the UI.
        pagerDao.clearAll()
    }

    // --- CONTACTS ---

    suspend fun getAllContacts() = contactDao.getAllContacts()
    suspend fun getBlockedContacts() = contactDao.getBlockedContacts()

    // Helper used by VoiceService for encryption lookups
    suspend fun findContactByIp(ip: String): ContactEntity? {
        return contactDao.getAllContacts().find { it.ip == ip }
    }

    // Inside MainRepository.kt

    suspend fun saveContact(name: String, ip: String, code: String, fcmToken: String = "") {
        val isBlocked = contactDao.isBlocked(name) // Preserve Block Status
        // [FIX] Pass the fcmToken into the Entity
        contactDao.insert(ContactEntity(name, ip, code, isBlocked = isBlocked, fcmToken = fcmToken))
        triggerConfigRefresh()
    }

    // Silent Update: Updates IP without triggering UI refresh
    suspend fun updateContactIp(name: String, ip: String) {
        contactDao.updateIp(name, ip)
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

    // --- CALL LOGS ---

    suspend fun getAllLogs() = callLogDao.getAllLogs()
    suspend fun insertLog(name: String, isIncoming: Boolean) {
        callLogDao.insert(CallLog(callerName = name, isIncoming = isIncoming))
    }
    suspend fun clearLogs() = callLogDao.clearAll()

    // --- PREFS HELPERS ---

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

    // --- NETWORK API ---

    suspend fun findPeer(token: String, name: String, code: String) =
        RetrofitClient.api.findPeer("Bearer $token", name, code)

    suspend fun findChannel(token: String, name: String, code: String) =
        RetrofitClient.api.findChannel("Bearer $token", name, code)

    suspend fun resetCode(token: String) =
        RetrofitClient.api.resetCode("Bearer $token")

    suspend fun setContactPriority(name: String, isPriority: Boolean) {
        contactDao.setPriority(name, isPriority)
        triggerConfigRefresh()
    }

    suspend fun getPrincipalContacts() = contactDao.getPrincipalContacts()

    suspend fun updateContactToken(name: String, token: String) {
        db.contactDao().updateContactToken(name, token)
    }

    suspend fun sendWakeSignal(authHeader: String, senderName: String, targetToken: String) =
        RetrofitClient.api.sendWakeSignal(authHeader, targetToken, senderName)


    suspend fun syncFcmTokenToServer() {
        val token = prefs.getString("my_fcm_token", null) ?: return
        if (token == null) {
            Log.w("FCM_DEBUG", "Skipping Sync: No FCM Token found in SharedPreferences yet.")
            return
        }
        val jwt = getToken() ?: return

        try {
            RetrofitClient.api.updateFcmToken("Bearer $jwt", token)
            Log.d("FCM", "Token synced to server successfully")
        } catch (e: Exception) {
            Log.e("FCM", "Failed to sync token: ${e.message}")
        }
    }

    suspend fun updateRecoveryEmail(email: String): retrofit2.Response<GenericResponse> {
        val jwt = getToken() ?: throw Exception("Not logged in")
        // Uses the function you defined in RetrofitClient
        return RetrofitClient.api.updateRecoveryEmail("Bearer $jwt", email)
    }
}