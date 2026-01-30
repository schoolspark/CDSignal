package `in`.chinmoydas.signal.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

// --- ENTITIES ---

@Entity(tableName = "call_logs")
data class CallLog(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val callerName: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isIncoming: Boolean
)

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val name: String,
    val ip: String,
    val savedCode: String = "",
    val isBlocked: Boolean = false,
    val isPriority: Boolean = false, // [NEW] Priority Flag
    val fcmToken: String = ""
)

// Entity for Silent Comms (Voice Pager)
@Entity(tableName = "pager_entries")
data class PagerEntry(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val sender: String,
    val timestamp: Long = System.currentTimeMillis(),
    val type: String,    // "AUDIO" or "TEXT"
    val content: String, // File path (for Audio) OR Message body (for Text)
    val isRead: Boolean = false
)

// --- DAOs ---

@Dao
interface CallLogDao {
    @Insert
    suspend fun insert(log: CallLog)

    @Query("SELECT * FROM call_logs ORDER BY timestamp DESC LIMIT 50")
    suspend fun getAllLogs(): List<CallLog>

    @Query("DELETE FROM call_logs")
    suspend fun clearAll()
}

@Dao
interface ContactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(contact: ContactEntity)

    @Query("SELECT * FROM contacts WHERE isBlocked = 0")
    suspend fun getAllContacts(): List<ContactEntity>

    @Query("SELECT * FROM contacts WHERE isBlocked = 1")
    suspend fun getBlockedContacts(): List<ContactEntity>

    // [MOVED HERE] Get VIPs
    @Query("SELECT * FROM contacts WHERE isPriority = 1")
    suspend fun getPrincipalContacts(): List<ContactEntity>

    // Helper to preserve blocked status when re-saving a contact
    @Query("SELECT EXISTS(SELECT 1 FROM contacts WHERE name = :name AND isBlocked = 1)")
    suspend fun isBlocked(name: String): Boolean

    @Delete
    suspend fun delete(contact: ContactEntity)

    @Query("UPDATE contacts SET isBlocked = :blocked WHERE name = :name")
    suspend fun setBlockedStatus(name: String, blocked: Boolean)

    // [MOVED HERE] Set Priority
    @Query("UPDATE contacts SET isPriority = :isPriority WHERE name = :name")
    suspend fun setPriority(name: String, isPriority: Boolean)

    // Silent IP Update helper
    @Query("UPDATE contacts SET ip = :newIp WHERE name = :name")
    suspend fun updateIp(name: String, newIp: String): Int // Returns row count

    @Query("UPDATE contacts SET fcmToken = :token WHERE name = :name")
    suspend fun updateContactToken(name: String, token: String)
}

// DAO for Pager Logic
@Dao
interface PagerDao {
    @Insert
    suspend fun insert(entry: PagerEntry)

    // Returns a Flow so the UI updates instantly when a new message arrives
    @Query("SELECT * FROM pager_entries ORDER BY timestamp DESC")
    fun getAllEntries(): Flow<List<PagerEntry>>

    @Delete
    suspend fun delete(entry: PagerEntry)

    @Query("DELETE FROM pager_entries")
    suspend fun clearAll()
}

// --- DATABASE ---

@Database(entities = [CallLog::class, ContactEntity::class, PagerEntry::class], version = 7)
abstract class AppDatabase : RoomDatabase() {
    abstract fun callLogDao(): CallLogDao
    abstract fun contactDao(): ContactDao
    abstract fun pagerDao(): PagerDao

    companion object {
        @Volatile private var instance: AppDatabase? = null
        fun getDatabase(context: android.content.Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "cd_signal_db"
                )
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
        }
    }
}