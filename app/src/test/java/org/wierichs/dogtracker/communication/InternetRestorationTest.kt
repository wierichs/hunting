package org.wierichs.dogtracker.communication

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.wierichs.dogtracker.jagdkarte.HuntingProfile
import org.wierichs.dogtracker.jagdkarte.HuntingSnapshot
import org.wierichs.dogtracker.jagdkarte.refreshHuntingSnapshotIfNeeded
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

    @Test
    fun inactiveHuntChecksStatusAndLoadsFullSnapshotOnlyWhenStarted() = runBlocking {
        val inactive = HuntingSnapshot(HuntingProfile("profile", "Test", "owner", false, null), emptyList(), true)
        val active = inactive.copy(profile = inactive.profile.copy(huntingMode = true))
        var onlineMode = false
        var modeReads = 0
        var fullReads = 0
        suspend fun refresh(current: HuntingSnapshot?, due: Boolean = false) = refreshHuntingSnapshotIfNeeded(
            current, due, loadHuntingMode = { modeReads++; onlineMode },
            loadSnapshot = { fullReads++; active })

        assertNull(refresh(inactive))
        assertEquals(1, modeReads)
        assertEquals(0, fullReads)
        onlineMode = true
        assertSame(active, refresh(inactive))
        assertEquals(2, modeReads)
        assertEquals(1, fullReads)
        assertSame(active, refresh(active))
        assertSame(active, refresh(inactive, due = true))
        assertSame(active, refresh(null))
        assertEquals(2, modeReads)
        assertEquals(4, fullReads)
    }

    @Test
    fun failedStatusReadDoesNotPretendThatAnInactiveHuntWasRefreshed() {
        val inactive = HuntingSnapshot(HuntingProfile("profile", "Test", "owner", false, null), emptyList(), true)
        var fullReads = 0
        assertThrows(java.io.IOException::class.java) {
            runBlocking {
                refreshHuntingSnapshotIfNeeded(inactive, false,
                    loadHuntingMode = { throw java.io.IOException("offline") },
                    loadSnapshot = { fullReads++; inactive })
            }
        }
        assertEquals(0, fullReads)
    }
}
