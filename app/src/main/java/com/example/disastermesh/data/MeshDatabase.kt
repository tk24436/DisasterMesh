package com.example.disastermesh.data

import android.content.Context
import androidx.room.*

@Entity(tableName = "alerts")
data class AlertEntity(
    @PrimaryKey val messageId: String,
    val senderName: String,
    val status: String,
    val message: String,
    val landmark: String,
    val timestamp: Long,
    val hopCount: Int,
    val ttl: Int,
    val priority: Int,
    val isRelayed: Boolean,
    val receivedFrom: String
) {
    fun toPacket(): SosPacket {
        return SosPacket(
            messageId = messageId,
            senderName = senderName,
            status = status,
            message = message,
            landmark = landmark,
            timestamp = timestamp,
            hopCount = hopCount,
            ttl = ttl,
            priority = priority,
            isRelayed = isRelayed,
            receivedFrom = receivedFrom
        )
    }
}

fun SosPacket.toEntity(): AlertEntity {
    return AlertEntity(
        messageId = messageId,
        senderName = senderName,
        status = status,
        message = message,
        landmark = landmark,
        timestamp = timestamp,
        hopCount = hopCount,
        ttl = ttl,
        priority = priority,
        isRelayed = isRelayed,
        receivedFrom = receivedFrom
    )
}

@Entity(tableName = "direct_messages")
data class DirectMessageEntity(
    @PrimaryKey val messageId: String,
    val senderName: String,
    val targetName: String,
    val content: String,
    val timestamp: Long,
    val isOutgoing: Boolean,
    val isRead: Boolean
) {
    fun toDirectMessage(): DirectMessage {
        return DirectMessage(
            messageId = messageId,
            senderName = senderName,
            targetName = targetName,
            content = content,
            timestamp = timestamp,
            isOutgoing = isOutgoing,
            isRead = isRead
        )
    }
}

fun DirectMessage.toEntity(): DirectMessageEntity {
    return DirectMessageEntity(
        messageId = messageId,
        senderName = senderName,
        targetName = targetName,
        content = content,
        timestamp = timestamp,
        isOutgoing = isOutgoing,
        isRead = isRead
    )
}

@Dao
interface AlertDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAlert(alert: AlertEntity)

    @Query("SELECT * FROM alerts ORDER BY priority DESC, timestamp DESC")
    fun getAll(): List<AlertEntity>

    @Query("UPDATE alerts SET isRelayed = :value WHERE messageId = :messageId")
    fun updateRelayed(messageId: String, value: Boolean)

    @Query("DELETE FROM alerts")
    fun deleteAll()
}

@Dao
interface DirectMessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(message: DirectMessageEntity)

    @Query(
        "SELECT * FROM direct_messages " +
        "WHERE senderName = :peerName OR targetName = :peerName " +
        "ORDER BY timestamp ASC"
    )
    fun getMessagesWithPeer(peerName: String): List<DirectMessageEntity>

    @Query("SELECT * FROM direct_messages ORDER BY timestamp DESC")
    fun getAll(): List<DirectMessageEntity>

    @Query(
        "UPDATE direct_messages SET isRead = 1 " +
        "WHERE senderName = :peerName AND isRead = 0"
    )
    fun markRead(peerName: String)

    @Query("DELETE FROM direct_messages")
    fun deleteAll()
}

@Database(
    entities = [AlertEntity::class, DirectMessageEntity::class],
    version = 2,
    exportSchema = false
)
abstract class MeshDatabase : RoomDatabase() {

    abstract fun alertDao(): AlertDao
    abstract fun directMessageDao(): DirectMessageDao

    companion object {
        @Volatile
        private var INSTANCE: MeshDatabase? = null

        fun getDatabase(context: Context): MeshDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MeshDatabase::class.java,
                    "disastermesh.db"
                )
                    .allowMainThreadQueries()
                    .fallbackToDestructiveMigration()
                    .build()

                INSTANCE = instance
                instance
            }
        }
    }
}
