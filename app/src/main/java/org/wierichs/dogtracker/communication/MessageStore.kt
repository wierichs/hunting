package org.wierichs.dogtracker.communication

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

internal const val LOCATION_FIX_MAX_AGE_MILLIS = 24 * 60 * 60 * 1000L

data class StoredMessage(
    val localId: Long,
    val message: CommunicationMessage
)

interface MessageStore {
    fun enqueue(message: CommunicationMessage)
    fun oldestPending(): StoredMessage?
    fun pendingOfType(type: String, limit: Int = 50): List<StoredMessage>
    fun pendingCount(): Int
    fun acknowledge(messageId: String)
    fun removeExpired(nowEpochMillis: Long = System.currentTimeMillis())
    fun clear()
}

class SqliteMessageStore internal constructor(context: Context, databaseName: String = DATABASE_NAME) :
    SQLiteOpenHelper(context.applicationContext, databaseName, null, DATABASE_VERSION), MessageStore {

    override fun onCreate(database: SQLiteDatabase) {
        createMessagesTable(database)
    }

    override fun onConfigure(database: SQLiteDatabase) {
        database.execSQL("PRAGMA secure_delete = ON")
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createMessagesTable(database)
            database.query(
                LEGACY_TABLE_NAME,
                arrayOf("endpoint", "payload"),
                null,
                null,
                null,
                null,
                "id ASC"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val message = CommunicationMessage(
                        messageId = UUID.randomUUID().toString(),
                        type = LOCATION_MESSAGE_TYPE,
                        destination = cursor.getString(cursor.getColumnIndexOrThrow("endpoint")),
                        createdAtEpochMillis = System.currentTimeMillis(),
                        expiresAtEpochMillis = null,
                        payload = cursor.getString(cursor.getColumnIndexOrThrow("payload"))
                            .toByteArray(Charsets.UTF_8)
                    )
                    insert(database, message)
                }
            }
            database.execSQL("DROP TABLE $LEGACY_TABLE_NAME")
        }
        if (oldVersion < 3) encryptExistingLocationPayloads(database)
    }

    override fun enqueue(message: CommunicationMessage) {
        val database = writableDatabase
        val now = System.currentTimeMillis()
        database.beginTransaction()
        try {
            removeExpired(database, now)
            if (message.type == LOCATION_MESSAGE_TYPE) {
                val newestPending = newestPendingLocationFix(database)
                if (newestPending == null || message.createdAtEpochMillis >= newestPending.second) {
                    database.delete(TABLE_NAME, "message_type = ?", arrayOf(LOCATION_MESSAGE_TYPE))
                    insert(database, message, now)
                }
            } else {
                insert(database, message, now)
            }
            database.execSQL(
                "DELETE FROM $TABLE_NAME WHERE local_id NOT IN " +
                    "(SELECT local_id FROM $TABLE_NAME ORDER BY local_id DESC LIMIT $MAX_PENDING_MESSAGES)"
            )
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    override fun oldestPending(): StoredMessage? = readableDatabase.query(
        TABLE_NAME,
        arrayOf("local_id", "message_id", "message_type", "destination", "created_at", "expires_at", "payload"),
        null,
        null,
        null,
        null,
        "local_id ASC",
        "1"
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val expiresAtIndex = cursor.getColumnIndexOrThrow("expires_at")
        readStoredMessage(cursor, expiresAtIndex)
    }

    override fun pendingOfType(type: String, limit: Int): List<StoredMessage> {
        require(limit in 1..MAX_PENDING_MESSAGES)
        return readableDatabase.query(
            TABLE_NAME,
            arrayOf("local_id", "message_id", "message_type", "destination", "created_at", "expires_at", "payload"),
            "message_type = ?",
            arrayOf(type),
            null,
            null,
            "local_id ASC",
            limit.toString()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val expiresAtIndex = cursor.getColumnIndexOrThrow("expires_at")
                    add(readStoredMessage(cursor, expiresAtIndex))
                }
            }
        }
    }

    override fun acknowledge(messageId: String) {
        writableDatabase.delete(TABLE_NAME, "message_id = ?", arrayOf(messageId))
    }

    fun findPending(messageId: String): StoredMessage? = readableDatabase.query(
        TABLE_NAME,
        arrayOf("local_id", "message_id", "message_type", "destination", "created_at", "expires_at", "payload"),
        "message_id = ?", arrayOf(messageId), null, null, null, "1"
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val expiresIndex = cursor.getColumnIndexOrThrow("expires_at")
        readStoredMessage(cursor, expiresIndex)
    }

    fun acknowledgeWithReceipt(messageId: String, receipt: CommunicationMessage) {
        require(receipt.messageId != messageId)
        writableDatabase.beginTransaction()
        try {
            if (findPending(receipt.messageId) == null) insert(writableDatabase, receipt)
            writableDatabase.delete(TABLE_NAME, "message_id = ?", arrayOf(messageId))
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    override fun pendingCount(): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM $TABLE_NAME",
        null
    ).use { cursor ->
        if (cursor.moveToFirst()) cursor.getInt(0) else 0
    }

    override fun removeExpired(nowEpochMillis: Long) {
        removeExpired(writableDatabase, nowEpochMillis)
    }

    private fun removeExpired(database: SQLiteDatabase, nowEpochMillis: Long) {
        val locationCutoff = nowEpochMillis - LOCATION_FIX_MAX_AGE_MILLIS
        database.delete(
            TABLE_NAME,
            "(expires_at IS NOT NULL AND expires_at <= ?) OR " +
                "(message_type = ? AND created_at <= ?)",
            arrayOf(nowEpochMillis.toString(), LOCATION_MESSAGE_TYPE, locationCutoff.toString())
        )
        compactLocationFixes(database)
    }

    override fun clear() {
        writableDatabase.delete(TABLE_NAME, null, null)
    }

    private fun insert(
        database: SQLiteDatabase,
        message: CommunicationMessage,
        nowEpochMillis: Long = System.currentTimeMillis()
    ) {
        val expiresAt = if (message.type == LOCATION_MESSAGE_TYPE) {
            val maximumExpiry = Math.addExact(message.createdAtEpochMillis, LOCATION_FIX_MAX_AGE_MILLIS)
            message.expiresAtEpochMillis?.let { minOf(it, maximumExpiry) } ?: maximumExpiry
        } else {
            message.expiresAtEpochMillis
        }
        if (message.type == LOCATION_MESSAGE_TYPE && expiresAt != null && expiresAt <= nowEpochMillis) return

        val values = ContentValues().apply {
            put("message_id", message.messageId)
            put("message_type", message.type)
            put("destination", message.destination)
            put("created_at", message.createdAtEpochMillis)
            put("expires_at", expiresAt)
            put(
                "payload",
                if (message.type == LOCATION_MESSAGE_TYPE) {
                    LocationPayloadCipher.encrypt(message.messageId, message.payload)
                } else {
                    message.payload
                }
            )
        }
        database.insertOrThrow(TABLE_NAME, null, values)
    }

    private fun readStoredMessage(cursor: android.database.Cursor, expiresAtIndex: Int): StoredMessage {
        val messageId = cursor.getString(cursor.getColumnIndexOrThrow("message_id"))
        val type = cursor.getString(cursor.getColumnIndexOrThrow("message_type"))
        val storedPayload = cursor.getBlob(cursor.getColumnIndexOrThrow("payload"))
        return StoredMessage(
            localId = cursor.getLong(cursor.getColumnIndexOrThrow("local_id")),
            message = CommunicationMessage(
                messageId = messageId,
                type = type,
                destination = cursor.getString(cursor.getColumnIndexOrThrow("destination")),
                createdAtEpochMillis = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                expiresAtEpochMillis = if (cursor.isNull(expiresAtIndex)) null else cursor.getLong(expiresAtIndex),
                payload = if (type == LOCATION_MESSAGE_TYPE) {
                    LocationPayloadCipher.decrypt(messageId, storedPayload)
                } else {
                    storedPayload
                }
            )
        )
    }

    private fun encryptExistingLocationPayloads(database: SQLiteDatabase) {
        var lastLocalId = 0L
        var encryptionKey: javax.crypto.SecretKey? = null
        while (true) {
            val row = database.query(
                TABLE_NAME,
                arrayOf("local_id", "message_id", "payload"),
                "message_type = ? AND local_id > ?",
                arrayOf(LOCATION_MESSAGE_TYPE, lastLocalId.toString()),
                null,
                null,
                "local_id ASC",
                "1"
            ).use { cursor ->
                if (!cursor.moveToFirst()) null else Triple(
                    cursor.getLong(0),
                    cursor.getString(1),
                    cursor.getBlob(2)
                )
            } ?: return

            lastLocalId = row.first
            if (LocationPayloadCipher.isEncrypted(row.third)) continue
            val key = encryptionKey ?: LocationPayloadCipher.encryptionKey().also { encryptionKey = it }
            val values = ContentValues().apply {
                put("payload", LocationPayloadCipher.encrypt(row.second, row.third, key))
            }
            check(database.update(TABLE_NAME, values, "local_id = ?", arrayOf(row.first.toString())) == 1) {
                "GPS-Payload konnte waehrend des Datenbank-Upgrades nicht aktualisiert werden."
            }
        }
    }

    private fun newestPendingLocationFix(database: SQLiteDatabase): Pair<Long, Long>? =
        database.query(
            TABLE_NAME,
            arrayOf("local_id", "created_at"),
            "message_type = ?",
            arrayOf(LOCATION_MESSAGE_TYPE),
            null,
            null,
            "created_at DESC, local_id DESC",
            "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else cursor.getLong(0) to cursor.getLong(1)
        }

    private fun compactLocationFixes(database: SQLiteDatabase) {
        val newestLocalId = newestPendingLocationFix(database)?.first ?: return
        database.delete(
            TABLE_NAME,
            "message_type = ? AND local_id != ?",
            arrayOf(LOCATION_MESSAGE_TYPE, newestLocalId.toString())
        )
    }

    private fun createMessagesTable(database: SQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE $TABLE_NAME (" +
                "local_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "message_id TEXT NOT NULL UNIQUE, " +
                "message_type TEXT NOT NULL, " +
                "destination TEXT NOT NULL, " +
                "created_at INTEGER NOT NULL, " +
                "expires_at INTEGER, " +
                "payload BLOB NOT NULL)"
        )
    }

    companion object {
        private const val DATABASE_NAME = "location_outbox.db"
        private const val DATABASE_VERSION = 3
        private const val TABLE_NAME = "communication_messages"
        private const val LEGACY_TABLE_NAME = "pending_locations"
        private const val LOCATION_MESSAGE_TYPE = "location.fix"
        private const val MAX_PENDING_MESSAGES = 10_000
    }
}