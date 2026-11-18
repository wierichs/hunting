package org.wierichs.dogtracker

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Test

class HuntingPermissionsTest {
    @Test fun permissionsFollowAndroidRuntimeBoundariesWithoutRequestingBackgroundGps() {
        val android12 = setOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE
        )
        val android13 = android12 + setOf(
            Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.POST_NOTIFICATIONS
        )
        for (sdk in listOf(31, 32)) assertEquals(android12, requiredHuntingPermissions(sdk).toSet())
        for (sdk in listOf(33, 36)) assertEquals(android13, requiredHuntingPermissions(sdk).toSet())
        assertEquals(android13 + Manifest.permission.ACCESS_LOCAL_NETWORK, requiredHuntingPermissions(37).toSet())
    }
}
