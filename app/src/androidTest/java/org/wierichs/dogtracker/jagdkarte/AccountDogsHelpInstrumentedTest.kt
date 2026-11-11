package org.wierichs.dogtracker.jagdkarte

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.widget.TextView
import io.noties.markwon.Markwon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AccountDogsHelpInstrumentedTest {
    @get:Rule val rule = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun editorMustConfirmDogDeletionAndTheListUpdatesWithoutServerAccess() {
        val dog = HuntingDog(UUID.randomUUID().toString(), "Faehrte")
        val profile = HuntingProfile(UUID.randomUUID().toString(), "Testrevier", UUID.randomUUID().toString(),
            huntingMode = false, boundary = null)
        var deletedDogId: String? = null
        rule.setContent { MaterialTheme {
            DogsDialog(repository = JagdkarteRepository(context), profile = profile, canEdit = true,
                onUseDeviceToken = { false }, onDismiss = {}, loadDogs = { listOf(dog) },
                deleteDog = { deletedDogId = it })
        } }

        rule.onNodeWithText("Faehrte").assertIsDisplayed()
        rule.onNodeWithText("Loeschen").performClick()
        rule.onNodeWithText("Hund loeschen?").assertIsDisplayed()
        rule.onNodeWithText("gespeicherte Hundeposition", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Hund dauerhaft loeschen").performClick()
        rule.waitUntil(5_000) { deletedDogId == dog.id }
        rule.onNodeWithText("Keine Hunde registriert.").assertIsDisplayed()
    }

    @Test fun guestCannotSeeDogRegistrationOrDeletionActions() {
        val dog = HuntingDog(UUID.randomUUID().toString(), "Faehrte")
        val profile = HuntingProfile(UUID.randomUUID().toString(), "Testrevier", UUID.randomUUID().toString(),
            huntingMode = false, boundary = null)
        rule.setContent { MaterialTheme {
            DogsDialog(repository = JagdkarteRepository(context), profile = profile, canEdit = false,
                onUseDeviceToken = { false }, onDismiss = {}, loadDogs = { listOf(dog) }, deleteDog = {})
        } }

        rule.onNodeWithText("Faehrte").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("Loeschen").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText("Hund registrieren").fetchSemanticsNodes().size)
    }

    @Test fun nativeHelpCoversLoginDogsOfflineMapsAndTroubleshooting() {
        val markdown = context.assets.open("hilfe.md").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val rendered = TextView(context)
        Markwon.create(context).setMarkdown(rendered, markdown)
        assertTrue(rendered.text.toString().contains("Anmelden und registrieren"))
        assertTrue(rendered.text.toString().contains("Hunde und Tracker"))
        assertTrue(rendered.text.toString().contains("Karten und Offline-Nutzung"))

        rule.setContent { MaterialTheme { HelpDialog(onDismiss = {}) } }

        rule.onNodeWithText("Hilfe und Fehlerbehebung").assertIsDisplayed()
    }
}