package org.wierichs.dogtracker.communication

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

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
    }

    override fun enqueue(message: CommunicationMessage) {
        writableDatabase.beginTransaction()
        try {
            insert(writableDatabase, message)
            writableDatabase.execSQL(
                "DELETE FROM $TABLE_NAME WHERE local_id NOT IN " +
                    "(SELECT local_id FROM $TABLE_NAME ORDER BY local_id DESC LIMIT $MAX_PENDING_MESSAGES)"
            )
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
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
        StoredMessage(
            localId = cursor.getLong(cursor.getColumnIndexOrThrow("local_id")),
            message = CommunicationMessage(
                messageId = cursor.getString(cursor.getColumnIndexOrThrow("message_id")),
                type = cursor.getString(cursor.getColumnIndexOrThrow("message_type")),
                destination = cursor.getString(cursor.getColumnIndexOrThrow("destination")),
                createdAtEpochMillis = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                expiresAtEpochMillis = if (cursor.isNull(expiresAtIndex)) null else cursor.getLong(expiresAtIndex),
                payload = cursor.getBlob(cursor.getColumnIndexOrThrow("payload"))
            )
        )
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
                    add(
                        StoredMessage(
                            localId = cursor.getLong(cursor.getColumnIndexOrThrow("local_id")),
                            message = CommunicationMessage(
                                messageId = cursor.getString(cursor.getColumnIndexOrThrow("message_id")),
                                type = cursor.getString(cursor.getColumnIndexOrThrow("message_type")),
                                destination = cursor.getString(cursor.getColumnIndexOrThrow("destination")),
                                createdAtEpochMillis = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                                expiresAtEpochMillis = if (cursor.isNull(expiresAtIndex)) null else cursor.getLong(expiresAtIndex),
                                payload = cursor.getBlob(cursor.getColumnIndexOrThrow("payload"))
                            )
                        )
                    )
                }
            }
        }
    }

    override fun acknowledge(messageId: String) {
        writableDatabase.delete(TABLE_NAME, "message_id = ?", arrayOf(messageId))
    }

    override fun pendingCount(): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM $TABLE_NAME",
        null
    ).use { cursor ->
        if (cursor.moveToFirst()) cursor.getInt(0) else 0
    }

    override fun removeExpired(nowEpochMillis: Long) {
        writableDatabase.delete(
            TABLE_NAME,
            "expires_at IS NOT NULL AND expires_at <= ?",
            arrayOf(nowEpochMillis.toString())
        )
    }

    override fun clear() {
        writableDatabase.delete(TABLE_NAME, null, null)
    }

    private fun insert(database: SQLiteDatabase, message: CommunicationMessage) {
        val values = ContentValues().apply {
            put("message_id", message.messageId)
            put("message_type", message.type)
            put("destination", message.destination)
            put("created_at", message.createdAtEpochMillis)
            put("expires_at", message.expiresAtEpochMillis)
            put("payload", message.payload)
        }
        database.insertOrThrow(TABLE_NAME, null, values)
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
        private const val DATABASE_VERSION = 2
        private const val TABLE_NAME = "communication_messages"
        private const val LEGACY_TABLE_NAME = "pending_locations"
        private const val LOCATION_MESSAGE_TYPE = "location.fix"
        private const val MAX_PENDING_MESSAGES = 10_000
    }
}