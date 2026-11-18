package org.wierichs.dogtracker.jagdkarte

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JwdImportInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun importConfirmationShowsNewProfileSummaryAndImportsOnConfirmation() {
        val imported = JwdImportedProfile("JWD Revier", JSONObject("""{
            "type":"Polygon","coordinates":[[[6,50],[8,50],[8,52],[6,52],[6,50]]]
        }"""), emptyList(), 2)
        var imports = 0
        var importedProfileId = ""
        var merged = true
        val unrelatedOwnerProfile = HuntingProfile("other-id", "Anderes Revier", "owner-id", false, JSONObject())
        composeRule.setContent {
            MaterialTheme {
            RevierImportDialog(listOf(imported), mergeTargets = listOf(unrelatedOwnerProfile), onImport = { _, target ->
                    imports++
                    assertNull(target)
                    "profile-id"
                }, onImported = { id, wasMerged -> importedProfileId = id; merged = wasMerged }, onDismiss = {})
            }
        }

        composeRule.onNodeWithText("Revierdatei importieren?").assertIsDisplayed()
        composeRule.onNodeWithText("JWD Revier").assertIsDisplayed()
        composeRule.onNodeWithText("0 dauerhafte Einrichtung(en)").assertIsDisplayed()
        composeRule.onNodeWithText("2 befriedete(r) Bereich(e)").assertIsDisplayed()
        composeRule.onNodeWithText("Profil importieren").performClick()
        composeRule.runOnIdle {
            assertEquals(1, imports)
            assertEquals("profile-id", importedProfileId)
            assertFalse(merged)
        }
    }

    @Test
    fun importConfirmationAllowsChoosingOneFeatureFromGeoJsonCollection() {
        val candidates = listOf(
            JwdImportedProfile("Gemeinde Nord", JSONObject(), emptyList(), 0),
            JwdImportedProfile("Gemeinde Sued", JSONObject(), emptyList(), 1)
        )
        var importedName = ""
        composeRule.setContent {
            MaterialTheme {
                RevierImportDialog(candidates, onImport = { candidate, target ->
                    importedName = candidate.name
                    assertNull(target)
                    "profile-id"
                }, onImported = { _, _ -> }, onDismiss = {})
            }
        }

        composeRule.onNodeWithText("GeoJSON-Feature").performClick()
        composeRule.onNodeWithText("2. Gemeinde Sued").performClick()
        composeRule.onNodeWithText("Profil importieren").performClick()
        composeRule.runOnIdle { assertEquals("Gemeinde Sued", importedName) }
    }

    @Test
    fun importConfirmationCanMergeIntoAnExistingEditableProfile() {
        val imported = JwdImportedProfile("Importrevier", JSONObject("""{
            "type":"Polygon","coordinates":[[[6,50],[8,50],[8,52],[6,52],[6,50]]]
        }"""), emptyList(), 0)
        val target = HuntingProfile("target-id", "Bestehendes Revier", "owner-id", false, JSONObject())
        var mergedProfileId = ""
        var mergeSelected = false
        composeRule.setContent {
            MaterialTheme {
                RevierImportDialog(listOf(imported), mergeTargets = listOf(target),
                    suggestedMergeTargets = mapOf(jwdProfileNameKey(imported.name) to target.id),
                    onImport = { _, selectedTarget ->
                        assertEquals(target.id, selectedTarget?.id)
                        "target-id"
                    }, onImported = { id, merged -> mergedProfileId = id; mergeSelected = merged }, onDismiss = {})
            }
        }

        composeRule.onNodeWithText("In bestehendes Revier zusammenführen").assertIsDisplayed()
        composeRule.onNodeWithText("Grenzen werden vereinigt. Vorhandene Marker bleiben erhalten; bereits vorhandene dauerhafte Einrichtungen werden nicht doppelt importiert.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Zusammenführen").performClick()
        composeRule.runOnIdle {
            assertEquals("target-id", mergedProfileId)
            assertEquals(true, mergeSelected)
        }
    }
}