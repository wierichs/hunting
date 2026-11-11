package org.wierichs.dogtracker.communication

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SqliteMessageStoreMigrationTest {
    @Test
    fun locationFixOutboxKeepsNewestFor24HoursWithoutChangingRelayExpiry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "location-retention-${UUID.randomUUID()}.db"
        val now = System.currentTimeMillis()
        val older = CommunicationMessage.create(
            "location.fix", "https://example.test/locations", "older".toByteArray(),
            createdAtEpochMillis = now - 1_000
        )
        val newest = CommunicationMessage.create(
            "location.fix", "https://example.test/locations", "newest".toByteArray(),
            createdAtEpochMillis = now
        )
        val delayedOlder = CommunicationMessage.create(
            "location.fix", "https://example.test/locations", "delayed".toByteArray(),
            createdAtEpochMillis = now - 500
        )
        val relayReceipt = CommunicationMessage.create(
            "relay.receipt", "https://example.test/relay", "receipt".toByteArray(),
            createdAtEpochMillis = now,
            expiresAtEpochMillis = now + 7 * LOCATION_FIX_MAX_AGE_MILLIS
        )

        try {
            SqliteMessageStore(context, name).use { store ->
                store.enqueue(older)
                store.enqueue(relayReceipt)
                store.enqueue(newest)
                store.enqueue(delayedOlder)

                assertNull(store.findPending(older.messageId))
                assertNull(store.findPending(delayedOlder.messageId))
                assertEquals(2, store.pendingCount())
                val pendingFix = requireNotNull(store.findPending(newest.messageId))
                assertEquals(now + LOCATION_FIX_MAX_AGE_MILLIS, pendingFix.message.expiresAtEpochMillis)
                assertEquals(newest.messageId, store.pendingOfType("location.fix").single().message.messageId)
                assertArrayEquals(newest.payload, pendingFix.message.payload)
                val storedPayload = store.writableDatabase.rawQuery(
                    "SELECT payload FROM communication_messages WHERE message_id = ?",
                    arrayOf(newest.messageId)
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    cursor.getBlob(0)
                }
                assertTrue(LocationPayloadCipher.isEncrypted(storedPayload))
                assertFalse(storedPayload.contentEquals(newest.payload))
                val secureDelete = store.writableDatabase.rawQuery("PRAGMA secure_delete", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    cursor.getInt(0)
                }
                assertEquals(1, secureDelete)

                store.removeExpired(now + LOCATION_FIX_MAX_AGE_MILLIS)

                assertNull(store.findPending(newest.messageId))
                assertNotNull(store.findPending(relayReceipt.messageId))
                assertEquals(1, store.pendingCount())
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun upgradeEncryptsExistingLocationPayloads() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "location-encryption-upgrade-${UUID.randomUUID()}.db"
        val now = System.currentTimeMillis()
        val message = CommunicationMessage.create(
            "location.fix",
            "https://example.test/locations",
            """{"latitude":51.1,"longitude":10.2,"recorded_at":"2026-11-11T00:00:00Z"}""".toByteArray(),
            createdAtEpochMillis = now
        )
        val versionTwoDatabase = object : SQLiteOpenHelper(context, name, null, 2) {
            override fun onCreate(database: SQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE communication_messages (" +
                        "local_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "message_id TEXT NOT NULL UNIQUE, " +
                        "message_type TEXT NOT NULL, " +
                        "destination TEXT NOT NULL, " +
                        "created_at INTEGER NOT NULL, " +
                        "expires_at INTEGER, " +
                        "payload BLOB NOT NULL)"
                )
            }

            override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }

        try {
            versionTwoDatabase.writableDatabase.insertOrThrow(
                "communication_messages",
                null,
                ContentValues().apply {
                    put("message_id", message.messageId)
                    put("message_type", message.type)
                    put("destination", message.destination)
                    put("created_at", message.createdAtEpochMillis)
                    putNull("expires_at")
                    put("payload", message.payload)
                }
            )
            versionTwoDatabase.close()

            SqliteMessageStore(context, name).use { store ->
                val storedPayload = store.writableDatabase.rawQuery(
                    "SELECT payload FROM communication_messages WHERE message_id = ?",
                    arrayOf(message.messageId)
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    cursor.getBlob(0)
                }
                assertTrue(LocationPayloadCipher.isEncrypted(storedPayload))
                assertFalse(storedPayload.contentEquals(message.payload))
                assertArrayEquals(message.payload, requireNotNull(store.oldestPending()).message.payload)
            }
        } finally {
            versionTwoDatabase.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun missingKeystoreKeyMakesPayloadExplicitlyUnreadable() {
        val keyAlias = "dog_tracker_test_location_${UUID.randomUUID()}"
        val messageId = UUID.randomUUID().toString()
        val plaintext = "location payload".toByteArray()
        try {
            val encrypted = LocationPayloadCipher.encrypt(
                messageId,
                plaintext,
                LocationPayloadCipher.encryptionKey(keyAlias)
            )
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(keyAlias)
            }

            var failure: UnreadableLocationPayloadException? = null
            try {
                LocationPayloadCipher.decrypt(messageId, encrypted, keyAlias)
            } catch (error: UnreadableLocationPayloadException) {
                failure = error
            }
            assertNotNull(failure)
            assertEquals(messageId, failure?.messageId)

            val rotatedCiphertext = LocationPayloadCipher.encrypt(
                messageId,
                plaintext,
                LocationPayloadCipher.encryptionKey(keyAlias)
            )
            assertArrayEquals(plaintext, LocationPayloadCipher.decrypt(messageId, rotatedCiphertext, keyAlias))
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                if (containsAlias(keyAlias)) deleteEntry(keyAlias)
            }
        }
    }

    @Test
    fun cleanupExpiresAndCompactsFixesAlreadyPresentInTheOutbox() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "location-cleanup-${UUID.randomUUID()}.db"
        val now = System.currentTimeMillis()
        val expired = CommunicationMessage.create(
            "location.fix", "https://example.test/locations", "expired".toByteArray(),
            createdAtEpochMillis = now - LOCATION_FIX_MAX_AGE_MILLIS - 1
        )
        val older = CommunicationMessage.create(
            "location.fix", "https://example.test/locations", "older".toByteArray(),
            createdAtEpochMillis = now - 60_000
        )
        val newest = CommunicationMessage.create(
            "location.fix", "https://example.test/locations", "newest".toByteArray(),
            createdAtEpochMillis = now - 30_000
        )

        try {
            SqliteMessageStore(context, name).use { store ->
                listOf(expired, older, newest).forEach { message ->
                    store.writableDatabase.insertOrThrow(
                        "communication_messages",
                        null,
                        ContentValues().apply {
                            put("message_id", message.messageId)
                            put("message_type", message.type)
                            put("destination", message.destination)
                            put("created_at", message.createdAtEpochMillis)
                            putNull("expires_at")
                            put("payload", message.payload)
                        }
                    )
                }

                store.removeExpired(now)

                assertNull(store.findPending(expired.messageId))
                assertNull(store.findPending(older.messageId))
                assertNotNull(store.findPending(newest.messageId))
                assertEquals(1, store.pendingOfType("location.fix").size)
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun serverReceiptAtomicallyReplacesEnvelopeAndSurvivesReopening() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "relay-receipt-${UUID.randomUUID()}.db"
        val envelope = CommunicationMessage.create("relay.envelope", "https://example.test/relay", "envelope".toByteArray())
        val receipt = CommunicationMessage.create("relay.receipt", "https://example.test/relay", "signed receipt".toByteArray())
        try {
            SqliteMessageStore(context, name).use { store ->
                store.enqueue(envelope)
                store.acknowledgeWithReceipt(envelope.messageId, receipt)
                store.acknowledgeWithReceipt(envelope.messageId, receipt)
                assertNull(store.findPending(envelope.messageId))
                assertEquals(1, store.pendingCount())
            }
            SqliteMessageStore(context, name).use { store ->
                assertNull(store.findPending(envelope.messageId))
                assertArrayEquals(receipt.payload, store.findPending(receipt.messageId)!!.message.payload)
            }
        } finally { context.deleteDatabase(name) }
    }

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