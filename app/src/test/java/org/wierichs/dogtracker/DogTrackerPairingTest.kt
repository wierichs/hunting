package org.wierichs.dogtracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
}