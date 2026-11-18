package org.wierichs.dogtracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlinx.coroutines.runBlocking
import java.io.IOException

class DogTrackerPairingTest {
    @Test fun createsDistinctServerCompatibleDeviceTokens() {
        val first = createDogTrackerDeviceToken()
        val second = createDogTrackerDeviceToken()

        assertTrue(Regex("^[0-9a-f]{64}$").matches(first))
        assertTrue(Regex("^[0-9a-f]{64}$").matches(second))
        assertNotEquals(first, second)
    }

    @Test fun onlyVersionedDogTrackerPairingQrIsAccepted() {
        val token = createDogTrackerDeviceToken()
        val qr = dogTrackerPairingQr(token)

        assertEquals(token, dogTrackerDeviceTokenFromQr(qr))
        assertEquals(token, dogTrackerDeviceTokenFromQr("  $qr  "))
        assertNull(dogTrackerDeviceTokenFromQr(token))
        assertNull(dogTrackerDeviceTokenFromQr("jagdkarte-dogtracker:pair:v2:$token"))
        assertNull(dogTrackerDeviceTokenFromQr("${DOG_TRACKER_PAIRING_QR_PREFIX}invalid"))
    }

    @Test fun qrBuilderRejectsInvalidOrUppercaseTokens() {
        assertFalse(runCatching { dogTrackerPairingQr("A".repeat(64)) }.isSuccess)
        assertFalse(runCatching { dogTrackerPairingQr("x".repeat(64)) }.isSuccess)
    }

    @Test fun retryAfterServerCommitAdoptsTheAlreadyActiveToken() = runBlocking {
        val oldToken = "b".repeat(64)
        val pendingToken = "a".repeat(64)
        var activeToken = oldToken
        var storedPendingToken: String? = pendingToken
        var requests = 0
        val completeOnServer: suspend (String, Boolean) -> String = { _, confirm ->
            assertTrue(confirm)
            requests++
            if (requests == 1) throw IOException("timeout after server commit")
            "already_active"
        }
        val saveActive: (String) -> Unit = { activeToken = it }
        val clearPending: () -> Unit = { storedPendingToken = null }

        assertThrows(IOException::class.java) {
            runBlocking {
                settleDogTrackerPairing(pendingToken, true, completeOnServer, saveActive, clearPending)
            }
        }
        assertEquals(oldToken, activeToken)
        assertEquals(pendingToken, storedPendingToken)

        val resolution = settleDogTrackerPairing(pendingToken, true, completeOnServer, saveActive, clearPending)
        assertEquals(DogTrackerPairingResolution.ALREADY_ACTIVE, resolution)
        assertEquals(pendingToken, activeToken)
        assertNull(storedPendingToken)
        assertEquals(2, requests)
    }

    @Test fun abortKeepsOldTokenUnlessTheServerAlreadyActivatedTheNewOne() = runBlocking {
        val oldToken = "b".repeat(64)
        val pendingToken = "a".repeat(64)
        var activeToken = oldToken
        var storedPendingToken: String? = pendingToken
        val saveActive: (String) -> Unit = { activeToken = it }
        val clearPending: () -> Unit = { storedPendingToken = null }

        val cancelled = settleDogTrackerPairing(pendingToken, false, { _, confirm ->
            assertFalse(confirm)
            "cancelled"
        }, saveActive, clearPending)
        assertEquals(DogTrackerPairingResolution.CANCELLED, cancelled)
        assertEquals(oldToken, activeToken)
        assertNull(storedPendingToken)

        storedPendingToken = pendingToken
        val alreadyActive = settleDogTrackerPairing(pendingToken, false, { _, _ -> "already_active" },
            saveActive, clearPending)
        assertEquals(DogTrackerPairingResolution.ALREADY_ACTIVE, alreadyActive)
        assertEquals(pendingToken, activeToken)
        assertNull(storedPendingToken)
    }

    @Test fun missingConfirmationRetainsPendingTokenForRetry() = runBlocking {
        val pendingToken = "a".repeat(64)
        var activeToken = "b".repeat(64)
        var storedPendingToken: String? = pendingToken

        val resolution = settleDogTrackerPairing(pendingToken, true, { _, _ -> "not_found" },
            { activeToken = it }, { storedPendingToken = null })

        assertEquals(DogTrackerPairingResolution.NOT_FOUND, resolution)
        assertEquals("b".repeat(64), activeToken)
        assertEquals(pendingToken, storedPendingToken)
    }
}