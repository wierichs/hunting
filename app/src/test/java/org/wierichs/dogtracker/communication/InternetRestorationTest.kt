package org.wierichs.dogtracker.communication

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

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
}
