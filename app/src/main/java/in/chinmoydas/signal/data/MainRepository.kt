package `in`.chinmoydas.signal.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import `in`.chinmoydas.signal.GenericResponse
import `in`.chinmoydas.signal.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

class MainRepository(context: Context) {
    private val db = AppDatabase.getDatabase(context)
    private val contactDao = db.contactDao()
    private val callLogDao = db.callLogDao()
    private val pagerDao = db.pagerDao()
    private val prefs: SharedPreferences = context.getSharedPreferences("WalkiePrefs", Context.MODE_PRIVATE)

    private val _targetUser = MutableStateFlow(prefs.getString("current_target", "") ?: "")
    val targetUser: StateFlow<String> = _targetUser.asStateFlow()

    private val _channelKey = MutableStateFlow(prefs.getString("channel_key", "") ?: "")
    val channelKey: StateFlow<String> = _channelKey.asStateFlow()

    private val _myUsername = MutableStateFlow(prefs.getString("username", "User") ?: "")
    val myUsername: StateFlow<String> = _myUsername.asStateFlow()

    private val _myPairingCode = MutableStateFlow(prefs.getString("my_pairing_code", "----") ?: "----")
    val myPairingCode: StateFlow<String> = _myPairingCode.asStateFlow()

    private val _configTrigger = MutableStateFlow(0)
    val configTrigger: StateFlow<Int> = _configTrigger.asStateFlow()

    private val _recoveryEmail = MutableStateFlow(prefs.getString("recovery_email", "Not Set") ?: "Not Set")
    val recoveryEmail: StateFlow<String> = _recoveryEmail.asStateFlow()

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

    suspend fun insertPagerEntry(entry: PagerEntry) = withContext(Dispatchers.IO) {
        pagerDao.insert(entry)
    }

    suspend fun deletePagerEntry(entry: PagerEntry) = withContext(Dispatchers.IO) {
        if (entry.type == "AUDIO") {
            try {
                val file = File(entry.content)
                if (file.exists()) file.delete()
            } catch (e: Exception) { e.printStackTrace() }
        }
        pagerDao.delete(entry)
    }

    suspend fun clearPagerHistory() = withContext(Dispatchers.IO) {
        pagerDao.clearAll()
    }

    // --- CONTACTS ---

    // [FIX 1] Changed to getActiveContacts to match DAO
    suspend fun getAllContacts() = withContext(Dispatchers.IO) { contactDao.getActiveContacts() }
    suspend fun getBlockedContacts() = withContext(Dispatchers.IO) { contactDao.getBlockedContacts() }

    // [FIX 2] Must check ALL contacts (including blocked) to effectively block IPs
    suspend fun findContactByIp(ip: String): ContactEntity? = withContext(Dispatchers.IO) {
        contactDao.getAbsolutelyAllContacts().find { it.ip == ip }
    }

    suspend fun saveContact(name: String, ip: String, code: String, isPriority: Boolean = false, fcmToken: String = "") = withContext(Dispatchers.IO) {
        // [FIX 3] Prevent overwriting existing user flags. Check if they exist first.
        val existingUsers = contactDao.getAbsolutelyAllContacts()
        val existingUser = existingUsers.find { it.name == name }

        if (existingUser != null) {
            // Update existing user safely without destroying their flags
            contactDao.update(existingUser.copy(
                ip = ip,
                savedCode = if (code.isNotEmpty()) code else existingUser.savedCode,
                fcmToken = if (fcmToken.isNotEmpty()) fcmToken else existingUser.fcmToken
            ))
        } else {
            // Insert brand new user
            contactDao.insert(ContactEntity(
                name = name,
                ip = ip,
                savedCode = code,
                isBlocked = false,
                isPriority = isPriority,
                fcmToken = fcmToken
            ))
        }

        triggerConfigRefresh()
    }

    suspend fun updateContactIp(name: String, ip: String): Int {
        return contactDao.updateIp(name, ip)
    }

    suspend fun deleteContact(name: String) = withContext(Dispatchers.IO) {
        // Find existing to avoid creating a dummy object
        val all = contactDao.getAbsolutelyAllContacts()
        val toDelete = all.find { it.name == name }
        if (toDelete != null) {
            contactDao.delete(toDelete)
            triggerConfigRefresh()
        }
    }

    suspend fun setBlockedStatus(name: String, blocked: Boolean) = withContext(Dispatchers.IO) {
        contactDao.setBlockedStatus(name, blocked)
        triggerConfigRefresh()
    }

    private fun triggerConfigRefresh() {
        val current = prefs.getInt("config_refresh_trigger", 0)
        prefs.edit().putInt("config_refresh_trigger", current + 1).apply()
    }

    // --- CALL LOGS ---

    suspend fun getAllLogs() = withContext(Dispatchers.IO) { callLogDao.getAllLogs() }

    suspend fun insertLog(name: String, isIncoming: Boolean) = withContext(Dispatchers.IO) {
        callLogDao.insert(CallLog(callerName = name, isIncoming = isIncoming))
    }

    suspend fun clearLogs() = withContext(Dispatchers.IO) { callLogDao.clearAll() }

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

    // --- NETWORK API (WRAPPED IN IO) ---

    suspend fun findPeer(token: String, name: String, code: String) = withContext(Dispatchers.IO) {
        RetrofitClient.api.findPeer("Bearer $token", name, code)
    }

    suspend fun findChannel(token: String, name: String, code: String) = withContext(Dispatchers.IO) {
        RetrofitClient.api.findChannel("Bearer $token", name, code)
    }

    suspend fun resetCode(token: String) = withContext(Dispatchers.IO) {
        RetrofitClient.api.resetCode("Bearer $token")
    }

    suspend fun setContactPriority(name: String, isPriority: Boolean) = withContext(Dispatchers.IO) {
        contactDao.setPriority(name, isPriority)
        triggerConfigRefresh()
    }

    suspend fun getPrincipalContacts() = withContext(Dispatchers.IO) { contactDao.getPrincipalContacts() }

    suspend fun updateContactToken(name: String, token: String) = withContext(Dispatchers.IO) {
        db.contactDao().updateContactToken(name, token)
    }

    suspend fun sendWakeSignal(authHeader: String, senderName: String, targetToken: String) = withContext(Dispatchers.IO) {
        RetrofitClient.api.sendWakeSignal(authHeader, targetToken, senderName)
    }

    suspend fun syncFcmTokenToServer() = withContext(Dispatchers.IO) {
        val token = prefs.getString("my_fcm_token", null)
        if (token == null) {
            Log.w("FCM_DEBUG", "Skipping Sync: No FCM Token found.")
            return@withContext
        }
        val jwt = getToken() ?: return@withContext

        try {
            RetrofitClient.api.updateFcmToken("Bearer $jwt", token)
            Log.d("FCM", "Token synced to server successfully")
        } catch (e: Exception) {
            Log.e("FCM", "Failed to sync token: ${e.message}")
        }
    }

    suspend fun updateRecoveryEmail(email: String): retrofit2.Response<GenericResponse> = withContext(Dispatchers.IO) {
        val jwt = getToken() ?: throw Exception("Not logged in")
        RetrofitClient.api.updateRecoveryEmail("Bearer $jwt", email)
    }

    suspend fun sendHeartbeat(token: String, port: Int, localIp: String, channel: String?, key: String?) = withContext(Dispatchers.IO) {
        RetrofitClient.api.sendHeartbeat(
            "Bearer $token",
            port,
            localIp,
            channel,
            key
        )
    }

    fun setRecoveryEmail(email: String) {
        val valueToSave = if (email.isBlank()) "Not Set" else email
        if (email.isBlank()) {
            prefs.edit().remove("recovery_email").apply()
        } else {
            prefs.edit().putString("recovery_email", email).apply()
        }
        _recoveryEmail.value = valueToSave
    }
}