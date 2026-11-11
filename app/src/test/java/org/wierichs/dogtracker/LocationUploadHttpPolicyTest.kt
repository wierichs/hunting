package org.wierichs.dogtracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocationUploadHttpPolicyTest {
    @Test
    fun classifiesRetryableAndPermanentResponses() {
        listOf(408, 425, 429, 500, 503).forEach { statusCode ->
            assertEquals(
                LocationUploadDisposition.RETRY,
                locationUploadDisposition(statusCode)
            )
        }
        assertEquals(
            LocationUploadDisposition.PERMANENT_REJECTION,
            locationUploadDisposition(422)
        )
        assertEquals(
            LocationUploadDisposition.INVALID_CREDENTIALS,
            locationUploadDisposition(401)
        )
        assertEquals(
            LocationUploadDisposition.HUNTING_MODE_DISABLED,
            locationUploadDisposition(409)
        )
        assertEquals(LocationUploadDisposition.ACCEPTED, locationUploadDisposition(204))
    }

    @Test
    fun parsesAndBoundsRetryAfterDeltaSeconds() {
        assertEquals(60_000L, retryAfterDelayMillis("60", nowEpochMillis = 0L))
        assertEquals(
            MIN_SERVER_RETRY_DELAY_MILLIS,
            retryAfterDelayMillis("0", nowEpochMillis = 0L)
        )
        assertEquals(
            MAX_SERVER_RETRY_DELAY_MILLIS,
            retryAfterDelayMillis("999999999999", nowEpochMillis = 0L)
        )
        assertNull(retryAfterDelayMillis("-1", nowEpochMillis = 0L))
        assertNull(retryAfterDelayMillis("not-a-date", nowEpochMillis = 0L))
    }

    @Test
    fun parsesRetryAfterHttpDate() {
        assertEquals(
            60_000L,
            retryAfterDelayMillis(
                "Wed, 21 Oct 2015 07:28:00 GMT",
                nowEpochMillis = 1_445_412_420_000L
            )
        )
    }
}
