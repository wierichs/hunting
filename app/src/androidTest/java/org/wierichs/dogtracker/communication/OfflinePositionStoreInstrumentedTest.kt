package org.wierichs.dogtracker.communication

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import java.util.Base64
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflinePositionStoreInstrumentedTest {
    @Test
    fun signedEndSurvivesReopeningAndDoesNotBlockTheNextSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "offline-ends-test-${UUID.randomUUID()}.db"
        val profile = UUID.randomUUID().toString()
        val session = UUID.randomUUID().toString()
        val next = UUID.randomUUID().toString()
        val device = UUID.randomUUID().toString()
        val user = UUID.randomUUID().toString()
        val now = Instant.now()
        val encode = Base64.getUrlEncoder().withoutPadding()
        val key = RelaySigningIdentity.publicKeyBase64Url()
        val capabilities = RelayCapabilities(RelayServerEncryptionKey("encryption", key), "receipt", key)
        val body = encode.encodeToString(JSONObject().put("document_type", "hunt_end").put("profile_id", profile)
            .put("session_id", session).put("ended_at", now.toString()).put("expires_at", now.plusSeconds(86_400).toString())
            .toString().toByteArray())
        val wire = JSONObject().put("action", "relay_document").put("key_id", "receipt").put("document", body)
            .put("signature", encode.encodeToString(RelaySigningIdentity.sign("location-relay-document-v1\nreceipt\n$body".toByteArray())))
            .toString().toByteArray()
        val end = VerifiedHuntingEnd.verify(wire, capabilities, profile, session, now)
        fun position(sequence: Long, timestamp: Instant) = OfflinePositionMessage.create(profile, device, "participant",
            user, sequence, timestamp.toEpochMilli(), 51.0, 7.0, 5.0, RelaySigningIdentity::sign)
        try {
            OfflinePositionStore(context, databaseName).use {
                it.recordHuntingMode(user, profile, true, sessionId = session)
                assertTrue(it.save(user, position(1, now.minusSeconds(1)), session))
                it.recordVerifiedEnd(user, end)
                assertTrue(it.load(user, profile).isEmpty())
            }
            OfflinePositionStore(context, databaseName).use {
                assertArrayEquals(wire, it.loadEndForForwarding(user, profile, session))
                it.recordHuntingMode(user, profile, true, sessionId = session)
                assertFalse(it.save(user, position(2, now.plusSeconds(1)), session))
                it.recordHuntingMode(user, profile, true, sessionId = next)
                val newPosition = position(3, now.plusSeconds(2))
                assertTrue(it.save(user, newPosition, next))
                it.recordVerifiedEnd(user, end)
                assertEquals(listOf(newPosition), it.load(user, profile))
                assertFalse(it.isSessionEnded(user, profile, next))
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun delayedFixDoesNotReplaceNewerFixAndKnownHuntEndClearsOnlyItsOwnScope() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "offline-positions-test-${UUID.randomUUID()}.db"
        val profile = UUID.randomUUID().toString()
        val otherProfile = UUID.randomUUID().toString()
        val device = UUID.randomUUID().toString()
        val subject = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        fun position(sequence: Long, time: Long, latitude: Double, profileId: String = profile) =
            OfflinePositionMessage.create(profileId, device, "dog", subject, sequence, time,
                latitude, 7.0, 5.0, RelaySigningIdentity::sign)

        val newest = position(2, now - 121_000, 51.1)
        val older = position(1, now - 122_000, 51.0)
        val other = position(1, now - 121_000, 51.2, otherProfile)
        try {
            OfflinePositionStore(context, databaseName).use { store ->
                assertTrue(store.save("hunter", newest))
                assertTrue(store.save("hunter", older))
                assertTrue(store.save("hunter", newest))
                assertTrue(store.save("hunter", other))
            }
            OfflinePositionStore(context, databaseName).use { store ->
                assertEquals(listOf(newest), store.load("hunter", profile))
                assertTrue(store.load("other-account", profile).isEmpty())
                store.recordHuntingMode("hunter", profile, false, now)
                assertTrue(store.load("hunter", profile).isEmpty())
                assertEquals(listOf(other), store.load("hunter", otherProfile))
                assertFalse(store.save("hunter", position(3, now + 1, 51.3)))
            }
            OfflinePositionStore(context, databaseName).use { store ->
                store.recordHuntingMode("hunter", profile, true)
                assertFalse(store.save("hunter", newest))
                val newHunt = position(3, now + 1, 51.3)
                assertTrue(store.save("hunter", newHunt))
                assertEquals(listOf(newHunt), store.load("hunter", profile))
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }
}
