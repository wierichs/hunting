package org.wierichs.dogtracker

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal data class PendingLocation(
    val id: Long,
    val endpoint: String,
    val payload: String
)

internal class LocationOutbox(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(database: SQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE $TABLE_NAME (id INTEGER PRIMARY KEY AUTOINCREMENT, endpoint TEXT NOT NULL, payload TEXT NOT NULL)"
        )
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    companion object {
        private const val DATABASE_NAME = "location_outbox.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_NAME = "pending_locations"
        private const val MAX_PENDING_LOCATIONS = 10_000

        fun enqueue(context: Context, endpoint: String, payload: String) {
            LocationOutbox(context).use { helper ->
                val values = ContentValues().apply {
                    put("endpoint", endpoint)
                    put("payload", payload)
                }
                helper.writableDatabase.insertOrThrow(TABLE_NAME, null, values)
                helper.writableDatabase.execSQL(
                    "DELETE FROM $TABLE_NAME WHERE id NOT IN (SELECT id FROM $TABLE_NAME ORDER BY id DESC LIMIT $MAX_PENDING_LOCATIONS)"
                )
            }
        }

        fun first(context: Context): PendingLocation? = LocationOutbox(context).use { helper ->
            helper.readableDatabase.query(
                TABLE_NAME,
                arrayOf("id", "endpoint", "payload"),
                null,
                null,
                null,
                null,
                "id ASC",
                "1"
            ).use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                PendingLocation(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    endpoint = cursor.getString(cursor.getColumnIndexOrThrow("endpoint")),
                    payload = cursor.getString(cursor.getColumnIndexOrThrow("payload"))
                )
            }
        }

        fun delete(context: Context, id: Long) {
            LocationOutbox(context).use { helper ->
                helper.writableDatabase.delete(TABLE_NAME, "id = ?", arrayOf(id.toString()))
            }
        }

        fun clear(context: Context) {
            LocationOutbox(context).use { helper ->
                helper.writableDatabase.delete(TABLE_NAME, null, null)
            }
        }
    }
}