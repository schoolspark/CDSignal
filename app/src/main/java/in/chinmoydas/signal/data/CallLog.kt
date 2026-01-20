package `in`.chinmoydas.signal.data

import androidx.room.*

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
    val isBlocked: Boolean = false
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

    // Helper to preserve blocked status when re-saving a contact
    @Query("SELECT EXISTS(SELECT 1 FROM contacts WHERE name = :name AND isBlocked = 1)")
    suspend fun isBlocked(name: String): Boolean

    @Delete
    suspend fun delete(contact: ContactEntity)

    @Query("UPDATE contacts SET isBlocked = :blocked WHERE name = :name")
    suspend fun setBlockedStatus(name: String, blocked: Boolean)

    // [CRITICAL FIX] Silent IP Update
    // This allows VoiceService to update IPs in the background without triggering a full UI refresh
    @Query("UPDATE contacts SET ip = :newIp WHERE name = :name")
    suspend fun updateIp(name: String, newIp: String)
}

// --- DATABASE ---

@Database(entities = [CallLog::class, ContactEntity::class], version = 3)
abstract class AppDatabase : RoomDatabase() {
    abstract fun callLogDao(): CallLogDao
    abstract fun contactDao(): ContactDao

    companion object {
        @Volatile private var instance: AppDatabase? = null
        fun getDatabase(context: android.content.Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "cd_signal_db"
                )
                    .fallbackToDestructiveMigration(false)
                    .build().also { instance = it }
            }
        }
    }
}