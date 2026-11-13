package org.wierichs.dogtracker.jagdkarte

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.wierichs.dogtracker.communication.RelayGatewayStatus
import org.wierichs.dogtracker.HuntingPermissionSettings
import org.wierichs.dogtracker.HuntingPermissionState
import org.wierichs.dogtracker.TrackerPreferences
import androidx.compose.runtime.mutableStateOf

@RunWith(AndroidJUnit4::class)
class RelayGatewayInstrumentedTest {
    @get:Rule val rule = createComposeRule()

    @Test fun activeHuntingParticipationHasNoSeparateRelayOrLocationControls() {
        rule.setContent { MaterialTheme {
            HuntingParticipationStatus(true, "Testrevier", RelayGatewayStatus(active = true), false)
        } }
        rule.onNodeWithText("Relais aktiv").assertExists()
        rule.onNodeWithText("Standortfreigabe und Relais laufen", substring = true).assertExists()
        rule.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test fun inactiveHuntingModeDoesNotOfferAManualRelayStart() {
        rule.setContent { MaterialTheme {
            HuntingParticipationStatus(false, "Testrevier", RelayGatewayStatus(), false)
        } }
        rule.onNodeWithText("Jagdmodus aus:", substring = true).assertExists()
        rule.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test fun oldSetupConsentDoesNotSilentlyAuthorizeAutomaticLocationSharing() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("tracker_settings", 0).edit()
            .putBoolean("hunting_functions_consent", true)
            .putBoolean("permission_introduction", true)
            .remove("automatic_hunting_consent").commit()
        try {
            assertFalse(TrackerPreferences.huntingFunctionsConsented(context))
            assertFalse(TrackerPreferences.permissionIntroductionCompleted(context))
            TrackerPreferences.setHuntingFunctionsConsent(context, true)
            assertTrue(TrackerPreferences.huntingFunctionsConsented(context))
            assertTrue(TrackerPreferences.permissionIntroductionCompleted(context))
        } finally {
            TrackerPreferences.setHuntingFunctionsConsent(context, false)
        }
    }

    @Test fun existingAndroidPermissionsDoNotImplyConsentAndSettingsRemainReachable() {
        var configurations = 0
        rule.setContent { MaterialTheme {
            HuntingPermissionSettings(HuntingPermissionState(consented = false),
                { configurations++ }, {})
        } }
        rule.onNodeWithText("Nur Karten- und Verwaltungsfunktionen", substring = true).assertExists()
        rule.runOnIdle { assertEquals(0, configurations) }
        rule.onAllNodesWithText("Jagdfunktionen deaktivieren").assertCountEquals(0)
        rule.onNodeWithText("Berechtigungen und Freigaben einrichten").performClick()
        rule.runOnIdle { assertEquals(1, configurations) }
    }

    @Test fun revokedPermissionHidesHuntingFunctionsButAllowsDisablingConsent() {
        var disables = 0
        rule.setContent { MaterialTheme {
            HuntingPermissionSettings(HuntingPermissionState(true, listOf(android.Manifest.permission.BLUETOOTH_SCAN)),
                {}, { disables++ })
        } }
        rule.onNodeWithText("Berechtigungen fehlen; Jagdfunktionen sind ausgeblendet.").assertExists()
        rule.onNodeWithText("Jagdfunktionen deaktivieren").performClick()
        rule.runOnIdle { assertEquals(1, disables) }
    }

    @Test fun huntingModeVisibilityUpdatesWithPermissionsWithoutGrantingGuestEditingRights() {
        val permissionsReady = mutableStateOf(false)
        var writes = 0
        rule.setContent { MaterialTheme {
            HuntingModeControls(permissionsReady.value, false, true, false, { writes++ })
        } }
        rule.onAllNodesWithText("Jagdmodus").assertCountEquals(0)
        rule.runOnIdle { permissionsReady.value = true }
        rule.onNodeWithText("Jagdmodus").assertExists()
        rule.onNodeWithText("Aktiv").assertExists()
        rule.onAllNodes(isToggleable()).assertCountEquals(0)
        rule.runOnIdle { permissionsReady.value = false }
        rule.onAllNodesWithText("Jagdmodus").assertCountEquals(0)
        rule.runOnIdle { assertEquals(0, writes) }
    }
}