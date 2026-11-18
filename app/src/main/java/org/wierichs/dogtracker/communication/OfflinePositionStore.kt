package org.wierichs.dogtracker.communication

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal class OfflinePositionStore(context: Context, databaseName: String = DATABASE_NAME) :
    SQLiteOpenHelper(context.applicationContext, databaseName, null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE positions (
                user_id TEXT NOT NULL, profile_id TEXT NOT NULL, sender_id TEXT NOT NULL,
                sequence INTEGER NOT NULL, recorded_at INTEGER NOT NULL, payload BLOB NOT NULL,
                PRIMARY KEY (user_id, profile_id, sender_id)
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE hunt_ends (
                user_id TEXT NOT NULL, profile_id TEXT NOT NULL, ended_at INTEGER NOT NULL,
                inactive INTEGER NOT NULL, PRIMARY KEY (user_id, profile_id)
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
        error("Unsupported offline position database version: $oldVersion to $newVersion")

    fun recordHuntingMode(userId: String, profileId: String, enabled: Boolean, now: Long = System.currentTimeMillis()) {
        require(userId.isNotBlank())
        val scope = arrayOf(userId, profileId)
        val removed = writableDatabase.transaction {
            if (enabled) {
                execSQL("UPDATE hunt_ends SET inactive = 0 WHERE $SCOPE AND inactive = 1", scope)
                0
            } else {
                val values = ContentValues().apply {
                    put("user_id", userId)
                    put("profile_id", profileId)
                    put("ended_at", now)
                    put("inactive", 1)
                }
                check(insertWithOnConflict("hunt_ends", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
                delete("positions", SCOPE, scope)
            }
        }
        if (removed > 0) revisions.update { it + 1 }
    }

    // Called only for a message verified against the current directory and authenticated sender.
    fun save(userId: String, position: OfflinePositionMessage): Boolean {
        require(userId.isNotBlank())
        val scope = arrayOf(userId, position.profileId)
        val key = scope + position.senderDeviceId
        var changed = false
        val accepted = writableDatabase.transaction {
            val blocked = query("hunt_ends", arrayOf("ended_at", "inactive"), SCOPE, scope, null, null, null)
                .use { it.moveToFirst() && (it.getInt(1) != 0 || position.recordedAtEpochMillis <= it.getLong(0)) }
            if (blocked) return@transaction false

            val alreadySuperseded = query("positions", arrayOf("sequence", "recorded_at"),
                "$SCOPE AND sender_id = ?", key, null, null, null).use {
                it.moveToFirst() && (position.sequence <= it.getLong(0) || position.recordedAtEpochMillis < it.getLong(1))
            }
            if (!alreadySuperseded) {
                val values = ContentValues().apply {
                    put("user_id", userId)
                    put("profile_id", position.profileId)
                    put("sender_id", position.senderDeviceId)
                    put("sequence", position.sequence)
                    put("recorded_at", position.recordedAtEpochMillis)
                    put("payload", LocationPayloadCipher.encrypt(payloadId(key), position.toByteArray()))
                }
                check(insertWithOnConflict("positions", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
                changed = true
            }
            true
        }
        if (changed) revisions.update { it + 1 }
        return accepted
    }

    fun load(userId: String, profileId: String): List<OfflinePositionMessage> {
        require(userId.isNotBlank())
        return readableDatabase.query("positions", arrayOf("sender_id", "payload"), SCOPE,
            arrayOf(userId, profileId), null, null, "sender_id").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val key = payloadId(arrayOf(userId, profileId, cursor.getString(0)))
                    val bytes = LocationPayloadCipher.decrypt(key, cursor.getBlob(1))
                    add(OfflinePositionMessage.parse(bytes, Long.MAX_VALUE) ?: throw UnreadableLocationPayloadException(key))
                }
            }
        }
    }

    private fun payloadId(key: Array<String>): String = key.joinToString(".", prefix = "offline-position.")

    companion object {
        private const val DATABASE_NAME = "offline_positions.db"
        private const val SCOPE = "user_id = ? AND profile_id = ?"
        private val revisions = MutableStateFlow(0L)
        val changes = revisions.asStateFlow()
    }
}
