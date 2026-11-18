package org.wierichs.dogtracker.communication

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.wierichs.dogtracker.jagdkarte.snapshotRetryDelayMillis

class InternetRestorationTest {
    @Test
    fun offlineToOnlineWakesSyncOnlyOnceUntilTheNetworkChangesAgain() = runBlocking {
        val restored = flowOf(null, "mobile", "mobile", null, "mobile", "wifi", "wifi")
            .onInternetRestored().toList()

        assertEquals(listOf("mobile", "mobile", "wifi"), restored)
    }

    @Test
    fun startingAlreadyOnlineDoesNotRepeatTheInitialSync() = runBlocking {
        assertEquals(emptyList<String>(), flowOf("wifi", "wifi", null).onInternetRestored().toList())
    }

    @Test
    fun temporaryTransportFailuresOnAnAvailableRouteRetrySoonWithoutChangingOtherBackoff() {
        assertEquals(listOf(2_000L, 5_000L, 10_000L, 30_000L),
            (1..4).map { snapshotRetryDelayMillis(it, transportWithRoute = true) })
        assertEquals(30_000L, snapshotRetryDelayMillis(9, transportWithRoute = true))
        assertEquals(120_000L, snapshotRetryDelayMillis(3, transportWithRoute = false))
    }
}
