package org.wierichs.dogtracker.communication

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SqliteMessageStoreMigrationTest {
    @Test
    fun upgradePreservesLegacyPendingLocationsInOrder() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "communication-migration-${UUID.randomUUID()}.db"
        val legacyDatabase = object : SQLiteOpenHelper(context, databaseName, null, 1) {
            override fun onCreate(database: SQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE pending_locations (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "endpoint TEXT NOT NULL, payload TEXT NOT NULL)"
                )
            }

            override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val firstPayload = "{\"latitude\":51.1,\"longitude\":10.4}"
        val secondPayload = "{\"latitude\":51.2,\"longitude\":10.5}"

        try {
            val database = legacyDatabase.writableDatabase
            listOf(firstPayload, secondPayload).forEach { payload ->
                database.insertOrThrow(
                    "pending_locations",
                    null,
                    ContentValues().apply {
                        put("endpoint", "https://example.test/dog-location")
                        put("payload", payload)
                    }
                )
            }
            legacyDatabase.close()

            SqliteMessageStore(context, databaseName).use { store ->
                assertEquals(2, store.pendingCount())
                val first = requireNotNull(store.oldestPending())
                assertArrayEquals(firstPayload.toByteArray(Charsets.UTF_8), first.message.payload)
                assertEquals("https://example.test/dog-location", first.message.destination)

                store.acknowledge(first.message.messageId)

                assertEquals(1, store.pendingCount())
                assertArrayEquals(
                    secondPayload.toByteArray(Charsets.UTF_8),
                    requireNotNull(store.oldestPending()).message.payload
                )
            }
        } finally {
            legacyDatabase.close()
            context.deleteDatabase(databaseName)
        }
    }
}