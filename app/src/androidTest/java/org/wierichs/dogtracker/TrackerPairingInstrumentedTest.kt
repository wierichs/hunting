package org.wierichs.dogtracker

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class TrackerPairingInstrumentedTest {
    @get:Rule val rule = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val pendingFile = "pending_dog_pairing"
    private val oldToken = "b".repeat(64)
    private val pendingToken = "a".repeat(64)

    @Before fun preparePairing() {
        TrackerPreferences.setTracking(context, false)
        TrackerPreferences.saveDeviceToken(context, oldToken)
        DeviceTokenStorage.write(context, pendingToken, pendingFile, synchronous = true)
    }

    @After fun clearPairingFixture() {
        TrackerPreferences.setTracking(context, false)
        TrackerPreferences.setHuntingFunctionsConsent(context, false)
        DeviceTokenStorage.clear(context, pendingFile, synchronous = true)
        DeviceTokenStorage.clear(context, "device_token", synchronous = true)
    }

    private fun showTracker(complete: suspend (String, Boolean) -> String) {
        rule.setContent { MaterialTheme {
            TrackerScreen(HuntingPermissionState(), {}, {}, completePairing = complete)
        } }
    }

    @Test fun confirmationShowsProgressAndAProminentServerConfirmedResult() {
        val response = CompletableDeferred<String>()
        showTracker { token, confirm ->
            assertEquals(pendingToken, token)
            assertEquals(true, confirm)
            response.await()
        }
        rule.onNodeWithText("Kopplung bestaetigen").performScrollTo().performClick()
        rule.onNodeWithText("Kopplung wird bestaetigt. Bitte warten.").assertIsDisplayed()
        response.complete("activated")
        rule.onNodeWithText("DogTracker-Kopplung").assertIsDisplayed()
        rule.onNode(hasText("Kopplung erfolgreich.", substring = true) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        assertEquals(pendingToken, TrackerPreferences.deviceToken(context))
        assertNull(DeviceTokenStorage.read(context, pendingFile))
    }

    @Test fun confirmationFailureIsNextToTheButtonAndRetainsTheTokenForRetry() {
        var requests = 0
        showTracker { _, _ ->
            requests++
            if (requests == 1) throw IOException("HTTP 503")
            "already_active"
        }
        rule.onNodeWithText("Kopplung bestaetigen").performScrollTo().performClick()
        rule.onNodeWithText("Kopplung konnte nicht abgeschlossen werden:", substring = true).assertIsDisplayed()
        assertEquals(oldToken, TrackerPreferences.deviceToken(context))
        assertEquals(pendingToken, DeviceTokenStorage.read(context, pendingFile))
        rule.onNodeWithText("Kopplung bestaetigen").performScrollTo().performClick()
        rule.onNodeWithText("DogTracker-Kopplung").assertIsDisplayed()
        rule.onNode(hasText("Kopplung erfolgreich.", substring = true) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        assertEquals(2, requests)
    }

    @Test fun confirmingBeforeScanningExplainsTheMissingStepAndKeepsTheQr() {
        showTracker { _, _ -> "not_found" }
        rule.onNodeWithText("Kopplung bestaetigen").performScrollTo().performClick()
        rule.onNodeWithText("Noch keine Kopplung vorgemerkt.", substring = true).assertIsDisplayed()
        assertEquals(oldToken, TrackerPreferences.deviceToken(context))
        assertEquals(pendingToken, DeviceTokenStorage.read(context, pendingFile))
    }

    @Test fun cancellationShowsAResultAndKeepsTheExistingTracker() {
        showTracker { _, confirm ->
            assertEquals(false, confirm)
            "cancelled"
        }
        rule.onNodeWithText("Abbrechen").performScrollTo().performClick()
        rule.onNodeWithText("DogTracker-Kopplung").assertIsDisplayed()
        rule.onNode(hasText("Kopplung abgebrochen.", substring = true) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        assertEquals(oldToken, TrackerPreferences.deviceToken(context))
        assertNull(DeviceTokenStorage.read(context, pendingFile))
    }

    private fun prepareTrackingPermissions() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        requiredHuntingPermissions().forEach { automation.grantRuntimePermission(context.packageName, it) }
        TrackerPreferences.setHuntingFunctionsConsent(context, true)
        DeviceTokenStorage.clear(context, pendingFile, synchronous = true)
        assertEquals(true, readHuntingPermissions(context).ready)
    }

    @Test fun startingDogTrackerAutomaticallyWaitsForRelayHandover() {
        prepareTrackingPermissions()
        val stopped = CompletableDeferred<Unit>()
        var stops = 0
        var starts = 0
        rule.setContent { MaterialTheme {
            TrackerScreen(HuntingPermissionState(true), {}, {},
                stopRelayGateway = { stops++; stopped.await() },
                startTrackingService = { _, intent ->
                    assertEquals(LocationTrackingService.ACTION_START, intent.action)
                    starts++
                })
        } }
        rule.onNodeWithText("Tracking starten").performScrollTo().performClick()
        rule.onNodeWithText("Tracking wird vorbereitet;", substring = true).assertIsDisplayed()
        assertEquals(1, stops)
        assertEquals(0, starts)
        stopped.complete(Unit)
        rule.onNodeWithText("Tracking stoppen").assertIsDisplayed()
        assertEquals(1, starts)
    }

    @Test fun failedRelayHandoverDoesNotStartOrPretendToStartTracking() {
        prepareTrackingPermissions()
        var starts = 0
        rule.setContent { MaterialTheme {
            TrackerScreen(HuntingPermissionState(true), {}, {},
                stopRelayGateway = { throw IOException("Relaiswechsel nicht abgeschlossen.") },
                startTrackingService = { _, _ -> starts++ })
        } }
        rule.onNodeWithText("Tracking starten").performScrollTo().performClick()
        rule.onNodeWithText("Relaiswechsel nicht abgeschlossen.").assertIsDisplayed()
        assertEquals(0, starts)
        assertEquals(false, TrackerPreferences.isTracking(context))
    }
}
