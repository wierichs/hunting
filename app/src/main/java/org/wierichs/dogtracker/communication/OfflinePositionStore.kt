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
    SQLiteOpenHelper(context.applicationContext, databaseName, null, 2) {

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
                inactive INTEGER NOT NULL, session_id TEXT, PRIMARY KEY (user_id, profile_id)
            )
        """.trimIndent())
        createVerifiedEnds(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion == 1 && newVersion == 2)
        db.execSQL("ALTER TABLE hunt_ends ADD COLUMN session_id TEXT")
        createVerifiedEnds(db)
    }

    private fun createVerifiedEnds(db: SQLiteDatabase) = db.execSQL("""
        CREATE TABLE verified_hunt_ends (
            user_id TEXT NOT NULL, profile_id TEXT NOT NULL, session_id TEXT NOT NULL,
            expires_at INTEGER NOT NULL, payload BLOB NOT NULL,
            PRIMARY KEY (user_id, profile_id, session_id)
        )
    """.trimIndent())

    fun recordVerifiedEnd(userId: String, end: VerifiedHuntingEnd) {
        require(userId.isNotBlank())
        val scope = arrayOf(userId, end.profileId)
        val changed = writableDatabase.transaction {
            if (hasVerifiedEnd(this, scope, end.sessionId)) return@transaction false
            val values = ContentValues().apply {
                put("user_id", userId)
                put("profile_id", end.profileId)
                put("session_id", end.sessionId)
                put("expires_at", end.expiresAt.toEpochMilli())
                put("payload", LocationPayloadCipher.encrypt(endKey(scope, end.sessionId), end.toByteArray()))
            }
            insertOrThrow("verified_hunt_ends", null, values)
            val observedSession = query("hunt_ends", arrayOf("session_id"), SCOPE, scope,
                null, null, null).use { if (it.moveToFirst()) it.getString(0) else null }
            if (observedSession == null || observedSession == end.sessionId) {
                blockPositions(this, scope, end.endedAt.toEpochMilli())
            }
            true
        }
        if (changed) revisions.update { it + 1 }
    }

    fun isSessionEnded(userId: String, profileId: String, sessionId: String): Boolean =
        hasVerifiedEnd(readableDatabase, arrayOf(userId, profileId), sessionId)

    fun loadEndForForwarding(userId: String, profileId: String, sessionId: String): ByteArray? {
        val scope = arrayOf(userId, profileId)
        return readableDatabase.query("verified_hunt_ends", arrayOf("payload"),
            "$SCOPE AND session_id = ? AND expires_at > ?", scope + arrayOf(sessionId, System.currentTimeMillis().toString()),
            null, null, null).use {
            if (it.moveToFirst()) LocationPayloadCipher.decrypt(endKey(scope, sessionId), it.getBlob(0)) else null
        }
    }

    private fun hasVerifiedEnd(db: SQLiteDatabase, scope: Array<String>, sessionId: String): Boolean =
        db.query("verified_hunt_ends", arrayOf("session_id"), "$SCOPE AND session_id = ?", scope + sessionId,
            null, null, null).use { it.moveToFirst() }

    private fun endKey(scope: Array<String>, sessionId: String) =
        (scope + sessionId).joinToString(".", prefix = "verified-hunt-end.")

    private fun blockPositions(db: SQLiteDatabase, scope: Array<String>, now: Long): Int {
        db.execSQL("""
            INSERT INTO hunt_ends(user_id, profile_id, ended_at, inactive) VALUES (?, ?, ?, 1)
            ON CONFLICT(user_id, profile_id) DO UPDATE SET ended_at = max(ended_at, excluded.ended_at), inactive = 1
        """.trimIndent(), arrayOf<Any>(scope[0], scope[1], now))
        return db.delete("positions", SCOPE, scope)
    }

    fun recordHuntingMode(userId: String, profileId: String, enabled: Boolean, now: Long = System.currentTimeMillis(),
        sessionId: String? = null) {
        require(userId.isNotBlank())
        val scope = arrayOf(userId, profileId)
        val removed = writableDatabase.transaction {
            if (enabled && sessionId != null && hasVerifiedEnd(this, scope, sessionId)) {
                0
            } else if (enabled) {
                val changed = if (sessionId == null) {
                    execSQL("UPDATE hunt_ends SET inactive = 0 WHERE $SCOPE AND session_id IS NULL", scope)
                    0
                } else {
                    val prior = query("hunt_ends", arrayOf("session_id"), SCOPE, scope, null, null, null)
                        .use { if (it.moveToFirst()) it.getString(0) else null }
                    execSQL("""
                        INSERT INTO hunt_ends(user_id, profile_id, ended_at, inactive, session_id) VALUES (?, ?, 0, 0, ?)
                        ON CONFLICT(user_id, profile_id) DO UPDATE SET inactive = 0, session_id = excluded.session_id
                    """.trimIndent(), scope + sessionId)
                    if (prior != null && prior != sessionId) delete("positions", SCOPE, scope) else 0
                }
                changed
            } else {
                blockPositions(this, scope, now)
            }
        }
        if (removed > 0) revisions.update { it + 1 }
    }

    // Called only for a message verified against the current directory and authenticated sender.
    fun save(userId: String, position: OfflinePositionMessage, sessionId: String? = null): Boolean {
        require(userId.isNotBlank())
        val scope = arrayOf(userId, position.profileId)
        val key = scope + position.senderDeviceId
        var changed = false
        val accepted = writableDatabase.transaction {
            val blocked = query("hunt_ends", arrayOf("ended_at", "inactive", "session_id"), SCOPE, scope, null, null, null)
                .use { it.moveToFirst() && (it.getInt(1) != 0 || position.recordedAtEpochMillis <= it.getLong(0) ||
                    !it.isNull(2) && sessionId != it.getString(2)) }
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
