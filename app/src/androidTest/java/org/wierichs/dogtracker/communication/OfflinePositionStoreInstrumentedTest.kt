package org.wierichs.dogtracker.communication

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflinePositionStoreInstrumentedTest {
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
