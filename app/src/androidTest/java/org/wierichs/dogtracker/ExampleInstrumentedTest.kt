package org.wierichs.dogtracker

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.lifecycle.Lifecycle
import android.content.Intent
import android.net.Uri
import org.wierichs.dogtracker.jagdkarte.EMAIL_AUTH_REQUEST_FILE
import org.wierichs.dogtracker.jagdkarte.JagdkarteRepository
import kotlinx.coroutines.runBlocking

import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

import org.junit.Assert.*

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    val composeRule = createAndroidComposeRule<MainActivity>()
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            InstrumentationRegistry.getInstrumentation().targetContext
                .getSharedPreferences("tracker_settings", 0).edit()
                .remove("last_tab_tracker").putBoolean("permission_introduction", false)
                .putBoolean("hunting_functions_consent", false).commit()
        }
    }).around(composeRule)

    private fun dismissIntroduction() {
        composeRule.onNodeWithText("Spaeter").performClick()
    }

    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(BuildConfig.APPLICATION_ID, appContext.packageName)
    }

    @Test
    fun mapStartsWithoutRequestingLocationAndTrackerRemainsAvailable() {
        composeRule.onNodeWithText("Jagdbruder einrichten").assertIsDisplayed()
        dismissIntroduction()
        composeRule.onNodeWithContentDescription("Benutzerkonto").assertIsDisplayed()
        composeRule.onNodeWithText("DogTracker").performClick()
        composeRule.onNodeWithText("Hundetracker").assertIsDisplayed()
        composeRule.onAllNodesWithText("Tracking starten").assertCountEquals(0)
        composeRule.onNodeWithText("Jagdkarte").performClick()
        composeRule.onNodeWithContentDescription("Benutzerkonto").assertIsDisplayed()
        composeRule.onNodeWithText("Revier auswaehlen").assertIsDisplayed()
        composeRule.onNodeWithText("DogTracker").performClick()
        composeRule.onNodeWithText("Hundetracker").assertIsDisplayed()
        composeRule.onNodeWithText("Jagdkarte").performClick()
        composeRule.onNodeWithContentDescription("Benutzerkonto").assertIsDisplayed()
    }

    @Test
    fun accountOffersMailLinkLoginAndExplainsTheCodeAlternative() {
        dismissIntroduction()
        composeRule.onNodeWithContentDescription("Benutzerkonto").performClick()
        composeRule.onNodeWithText("E-Mail").assertIsDisplayed()
        composeRule.onNodeWithText("Anmelde-Mail anfordern").assertIsDisplayed()
        composeRule.onNodeWithText("Oeffne den Link in der neuesten E-Mail", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Abbrechen").performClick()
    }

    @Test
    fun unsolicitedMailCallbackShowsAnActionableErrorAndIsNotReplayedAfterRecreation() {
        dismissIntroduction()
        DeviceTokenStorage.clear(InstrumentationRegistry.getInstrumentation().targetContext,
            EMAIL_AUTH_REQUEST_FILE, synchronous = true)
        val originalActivity = composeRule.activity
        val launchIntent = Intent(originalActivity.intent)
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.startActivity(Intent(activity, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse("https://wierichs.github.io/Jagdkarte/?code=fixture-auth-code"))
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Anmeldung nicht abgeschlossen").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasText("Bitte zuerst auf diesem Handy", substring = true) and
            hasAnyAncestor(isDialog())).assertIsDisplayed()
        assertSame(originalActivity, composeRule.activity)
        composeRule.onNodeWithText("Schliessen").performClick()
        composeRule.activityRule.scenario.onActivity { activity ->
            assertNull(activity.intent.data)
            // ActivityScenario tracks lifecycle events using its original launch intent.
            activity.intent = launchIntent
        }
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        composeRule.activityRule.scenario.recreate()
        composeRule.onAllNodesWithText("Anmeldung nicht abgeschlossen").assertCountEquals(0)
    }

    @Test
    fun pendingMailRequestRestoresTheEmailAndOffersManualCodeVerification() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        try {
            runBlocking {
                JagdkarteRepository(context, authRequestOverride = { _, _ -> "{}" })
                    .requestCode("user@example.test", false)
            }
            dismissIntroduction()
            composeRule.onNodeWithContentDescription("Benutzerkonto").performClick()
            composeRule.onNodeWithText("user@example.test").assertExists()
            composeRule.onNodeWithText("Zahlencode (alternativ)").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText("Code bestaetigen").assertIsNotEnabled()
            composeRule.onNodeWithText("Neue Mail anfordern").assertExists()
        } finally {
            DeviceTokenStorage.clear(context, EMAIL_AUTH_REQUEST_FILE, synchronous = true)
        }
    }

    @Test
    fun permissionSetupRemainsAccessibleWithoutLoginAndCancelDoesNotGrantConsent() {
        dismissIntroduction()
        composeRule.onNodeWithContentDescription("Benutzerkonto").performClick()
        composeRule.onNodeWithText("Berechtigungen und Freigaben einrichten").performClick()
        composeRule.onNodeWithText("Jagdbruder einrichten").assertIsDisplayed()
        composeRule.onNodeWithText("Spaeter").performClick()
        composeRule.runOnIdle {
            assertFalse(TrackerPreferences.huntingFunctionsConsented(
                InstrumentationRegistry.getInstrumentation().targetContext))
        }
    }

    @Test
    fun grantedPermissionsRequireAppConsentAndDoNotStartDogTrackingAutomatically() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        requiredHuntingPermissions().forEach { automation.grantRuntimePermission(context.packageName, it) }
        dismissIntroduction()
        composeRule.onNodeWithText("DogTracker").performClick()
        composeRule.onAllNodesWithText("Tracking starten").assertCountEquals(0)
        composeRule.onNodeWithText("Berechtigungen und Freigaben einrichten").performClick()
        composeRule.onNodeWithText("Freigeben und einrichten").performClick()
        composeRule.onNodeWithText("Tracking starten").assertExists()
        composeRule.runOnIdle {
            assertTrue(TrackerPreferences.huntingFunctionsConsented(context))
            assertTrue(TrackerPreferences.relayEnabled(context))
            assertFalse(TrackerPreferences.isTracking(context))
        }
    }
}