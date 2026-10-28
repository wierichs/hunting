package org.wierichs.dogtracker.communication

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunicationMessageTest {
    @Test
    fun createAssignsUniqueIdAndCopiesPayload() {
        val source = byteArrayOf(1, 2, 3)
        val first = CommunicationMessage.create("location.fix", "https://example.test/locations", source, 100L)
        val second = CommunicationMessage.create("location.fix", "https://example.test/locations", source, 100L)

        source[0] = 9

        assertNotEquals(first.messageId, second.messageId)
        assertArrayEquals(byteArrayOf(1, 2, 3), first.payload)
    }

    @Test
    fun expirationIsExclusiveAtTheConfiguredTime() {
        val message = CommunicationMessage.create(
            type = "location.fix",
            destination = "https://example.test/locations",
            payload = byteArrayOf(1),
            createdAtEpochMillis = 100L,
            expiresAtEpochMillis = 200L
        )

        assertFalse(message.isExpired(199L))
        assertTrue(message.isExpired(200L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsExpiryBeforeCreation() {
        CommunicationMessage.create(
            type = "location.fix",
            destination = "https://example.test/locations",
            payload = byteArrayOf(1),
            createdAtEpochMillis = 200L,
            expiresAtEpochMillis = 199L
        )
    }
}