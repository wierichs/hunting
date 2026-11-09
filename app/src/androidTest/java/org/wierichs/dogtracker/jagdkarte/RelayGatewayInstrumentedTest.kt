package org.wierichs.dogtracker.jagdkarte

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.wierichs.dogtracker.communication.RelayGatewayStatus

@RunWith(AndroidJUnit4::class)
class RelayGatewayInstrumentedTest {
    @get:Rule val rule = createComposeRule()

    @Test fun relayRequiresExplicitConsentAndCancelDoesNotStartIt() {
        var starts = 0
        rule.setContent { MaterialTheme { RelayGatewayControls(true, RelayGatewayStatus(), { starts++ }, {}) } }
        rule.onNode(isToggleable()).performClick()
        rule.onNodeWithText("Relaisfreigabe erlauben?").assertExists()
        rule.runOnIdle { assertEquals(0, starts) }
        val bitmap = rule.onNode(isDialog()).captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "relay-consent.png")
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        rule.onNodeWithText("Abbrechen").performClick()
        rule.onAllNodesWithText("Erlauben").assertCountEquals(0)
        rule.runOnIdle { assertEquals(0, starts) }
        rule.onNode(isToggleable()).performClick()
        rule.onNodeWithText("Erlauben").performClick()
        rule.runOnIdle { assertEquals(1, starts) }
    }

    @Test fun unavailableProfileCannotStartRelay() {
        rule.setContent { MaterialTheme { RelayGatewayControls(false, RelayGatewayStatus(), {}, {}) } }
        rule.onNode(isToggleable()).assertIsNotEnabled()
    }

    @Test fun activeRelayCanBeStoppedWithoutEnablingOwnLocation() {
        var stops = 0
        rule.setContent { MaterialTheme {
            RelayGatewayControls(false, RelayGatewayStatus(active = true, pending = 2, confirmed = 1), {}, { stops++ })
        } }
        rule.onNode(isToggleable()).performClick()
        rule.runOnIdle { assertEquals(1, stops) }
        rule.onAllNodesWithText("Relaisfreigabe erlauben?").assertCountEquals(0)
    }
}