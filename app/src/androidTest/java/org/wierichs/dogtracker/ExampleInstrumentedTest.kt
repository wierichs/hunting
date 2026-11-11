package org.wierichs.dogtracker

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick

import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(BuildConfig.APPLICATION_ID, appContext.packageName)
    }

    @Test
    fun mapStartsWithoutRequestingLocationAndTrackerRemainsAvailable() {
        val trackerVisible = composeRule.onAllNodesWithText("Hundetracker").fetchSemanticsNodes().isNotEmpty()
        if (!trackerVisible) composeRule.onNodeWithText("DogTracker").performClick()
        composeRule.onNodeWithText("Hundetracker").assertIsDisplayed()
        composeRule.onNodeWithText("Jagdkarte").performClick()
        composeRule.onNodeWithContentDescription("Benutzerkonto").assertIsDisplayed()
        composeRule.onNodeWithText("Revier auswaehlen").assertIsDisplayed()
        composeRule.onNodeWithText("DogTracker").performClick()
        composeRule.onNodeWithText("Hundetracker").assertIsDisplayed()
        composeRule.onNodeWithText("Jagdkarte").performClick()
        composeRule.onNodeWithContentDescription("Benutzerkonto").assertIsDisplayed()
    }

    @Test
    fun accountOffersExplicitEmailCodeLogin() {
        composeRule.onNodeWithContentDescription("Benutzerkonto").performClick()
        composeRule.onNodeWithText("E-Mail").assertIsDisplayed()
        composeRule.onNodeWithText("Code anfordern").assertIsDisplayed()
        composeRule.onNodeWithText("Abbrechen").performClick()
    }
}