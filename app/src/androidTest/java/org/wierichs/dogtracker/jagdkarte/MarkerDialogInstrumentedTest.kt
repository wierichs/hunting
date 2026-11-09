package org.wierichs.dogtracker.jagdkarte

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.caverock.androidsvg.SVG
import java.io.File
import java.time.Instant
import org.json.JSONObject
import org.wierichs.dogtracker.TrackerPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MarkerDialogInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val coordinates = MarkerCoordinates(51.5, 7.5)
    private val profile = HuntingProfile("revier", "Testrevier", "owner", false, JSONObject("""{
        "type":"Polygon","coordinates":[[[6,50],[8,50],[8,52],[6,52],[6,50]]]
    }"""))

    @Test
    fun allMarkerCatalogIconsRenderAtTheExpectedSize() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        HuntingMarkerType.entries.forEach { type ->
            val picture = SVG.getFromAsset(context.assets, "markers/${type.iconAsset}").renderToPicture(120, 144)
            assertEquals(120, picture.width)
            assertEquals(144, picture.height)
        }
    }

    @Test
    fun markerIconSizePreferencePersistsOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val previous = TrackerPreferences.markerIconSize(context)
        try {
            TrackerPreferences.setMarkerIconSize(context, "large")
            assertEquals("large", TrackerPreferences.markerIconSize(context))
        } finally {
            TrackerPreferences.setMarkerIconSize(context, previous)
        }
    }

    @Test
    fun editorCanSaveFacilityWithoutRequestingGps() {
        var savedDraft: HuntingMarkerDraft? = null
        var created = false
        composeRule.setContent {
            MaterialTheme {
                MarkerCreateDialog(profile, true, coordinates, onCreate = { draft, currentLocation ->
                    savedDraft = draft
                    assertNull(currentLocation)
                }, onCreated = { created = true }, onDismiss = {})
            }
        }
        composeRule.onNodeWithText("Bezeichnung").performTextInput("Sitz am Wald")
        composeRule.onNodeWithText("Notiz").performTextInput("Am Waldrand")
        captureScreenshot("marker-editor")
        composeRule.onNodeWithText("Speichern").performClick()
        composeRule.runOnIdle {
            assertTrue(created)
            assertEquals(HuntingMarkerType.HOCHSITZ, savedDraft?.type)
            assertEquals("Sitz am Wald", savedDraft?.title)
            assertEquals(coordinates, savedDraft?.coordinates)
        }
    }

    @Test
    fun guestOnlySeesAnschussAndWildsichtung() {
        composeRule.setContent {
            MaterialTheme {
                MarkerCreateDialog(profile, false, coordinates, onCreate = { _, _ -> }, onCreated = {}, onDismiss = {})
            }
        }
        composeRule.onNodeWithText("Markerart").performClick()
        composeRule.onNodeWithText("Wildsichtung").assertIsDisplayed()
        composeRule.onAllNodesWithText("Standplatz").assertCountEquals(0)
        composeRule.onAllNodesWithText("Hochsitz").assertCountEquals(0)
        captureScreenshot("marker-guest")
    }

    @Test
    fun standplatzCanBePlannedWithoutGps() {
        var savedType: HuntingMarkerType? = null
        composeRule.setContent {
            MaterialTheme {
                MarkerCreateDialog(profile, true, coordinates, onCreate = { draft, currentLocation ->
                    savedType = draft.type
                    assertNull(currentLocation)
                }, onCreated = {}, onDismiss = {})
            }
        }
        composeRule.onNodeWithText("Markerart").performClick()
        composeRule.onNodeWithText("Standplatz").performScrollTo().performClick()
        composeRule.onNodeWithText("Gueltig fuer 48 Stunden.").assertIsDisplayed()
        composeRule.onNodeWithText("Speichern").performClick()
        composeRule.runOnIdle { assertEquals(HuntingMarkerType.STANDPLATZ, savedType) }
    }

    @Test
    fun failedSaveKeepsDraftAndDoesNotRetryAutomatically() {
        var attempts = 0
        composeRule.setContent {
            MaterialTheme {
                MarkerCreateDialog(profile, true, coordinates, onCreate = { _, _ ->
                    attempts++
                    error("Verbindung unterbrochen.")
                }, onCreated = {}, onDismiss = {})
            }
        }
        composeRule.onNodeWithText("Bezeichnung").performTextInput("Mein Hochsitz")
        composeRule.onNodeWithText("Speichern").performClick()
        composeRule.onNodeWithText("Verbindung unterbrochen.").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Bezeichnung").assertTextContains("Mein Hochsitz")
        composeRule.onNodeWithText("Speichern").assertIsEnabled()
        composeRule.runOnIdle { assertEquals(1, attempts) }
    }

    @Test
    fun mapDoesNotOfferMarkerCreationWithoutProfile() {
        composeRule.setContent {
            MaterialTheme {
                NativeHuntingMap(null, Instant.now(), Modifier.fillMaxSize(), onChooseLocation = { false })
            }
        }
        composeRule.onAllNodesWithText("Markierung setzen", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun mapPositionChooserPassesLatitudeAndLongitude() {
        var chosen: MarkerCoordinates? = null
        composeRule.setContent {
            MaterialTheme {
                NativeHuntingMap(HuntingSnapshot(profile, emptyList(), true), Instant.now(), Modifier.fillMaxSize(),
                    onChooseLocation = { chosen = it; true })
            }
        }
        composeRule.onNodeWithText("Markierung setzen", useUnmergedTree = true).performClick()
        composeRule.onNodeWithContentDescription("Zielposition", useUnmergedTree = true).assertIsDisplayed()
        captureScreenshot("marker-position", dialog = false)
        composeRule.onNodeWithText("Hier setzen", useUnmergedTree = true).performClick()
        composeRule.runOnIdle {
            assertTrue(chosen!!.latitude in 50.0..52.0)
            assertTrue(chosen!!.longitude in 6.0..8.0)
        }
        composeRule.onNodeWithText("Markierung setzen", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Markierung setzen", useUnmergedTree = true).performClick()
        composeRule.onNodeWithContentDescription("Positionswahl abbrechen", useUnmergedTree = true).performClick()
        composeRule.onAllNodesWithContentDescription("Zielposition", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun mapLegendShowsPwaRolesDogAndBoundary() {
        val now = Instant.now()
        val points = listOf(
            HuntingMapPoint(51.5, 7.5, "Mitglied", "", MapPointKind.PARTICIPANT, now,
                participantRole = HuntingParticipantRole.GUEST),
            HuntingMapPoint(51.6, 7.6, "Bearbeiter", "", MapPointKind.PARTICIPANT, now,
                participantRole = HuntingParticipantRole.EDITOR),
            HuntingMapPoint(51.7, 7.7, "Chef", "", MapPointKind.PARTICIPANT, now,
                participantRole = HuntingParticipantRole.OWNER),
            HuntingMapPoint(51.8, 7.8, "Hund", "", MapPointKind.DOG, now.minusSeconds(121))
        )
        composeRule.setContent {
            MaterialTheme {
                NativeHuntingMap(HuntingSnapshot(profile, points, false), now, Modifier.fillMaxSize(),
                    onChooseLocation = { false })
            }
        }
        composeRule.onNodeWithText("Legende anzeigen", useUnmergedTree = true).performClick()
        composeRule.onNodeWithText("Jagdbezirksgrenze", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Mitglied", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Bearbeiter", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Chef", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Hund", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Legende ausblenden", useUnmergedTree = true).performClick()
    }

    @Test
    fun markerEditSavesOnlyTextFields() {
        val marker = HuntingMapPoint(51.5, 7.5, "Sitz am Wald", "hochsitz: Alt", MapPointKind.FACILITY,
            markerId = "c76274b7-e21a-440e-ad0d-c51c6f6345e0", markerTypeId = "hochsitz", markerNotes = "Alt")
        var savedTitle = ""
        var savedNotes = ""
        var updated = false
        composeRule.setContent {
            MaterialTheme {
                MarkerEditDialog(marker, canEdit = true, canDelete = true,
                    onUpdate = { title, notes -> savedTitle = title; savedNotes = notes },
                    onDelete = {}, onMove = {}, onCreateGuestLink = { "https://example.test/#gast=token" },
                    onShareGuestLink = {}, onUpdated = { updated = true }, onDeleted = {}, onMoved = {}, onDismiss = {})
            }
        }
        composeRule.onNodeWithText("Bezeichnung").performTextClearance()
        composeRule.onNodeWithText("Bezeichnung").performTextInput("Neuer Sitz")
        composeRule.onNodeWithText("Notiz").performTextClearance()
        composeRule.onNodeWithText("Notiz").performTextInput("Neue Notiz")
        composeRule.onNodeWithText("Speichern").performClick()
        composeRule.runOnIdle {
            assertEquals("Neuer Sitz", savedTitle)
            assertEquals("Neue Notiz", savedNotes)
            assertTrue(updated)
        }
    }

    @Test
    fun markerDeleteRequiresExplicitConfirmation() {
        val marker = HuntingMapPoint(51.5, 7.5, "Sitz am Wald", "hochsitz: Notiz", MapPointKind.FACILITY,
            markerId = "c76274b7-e21a-440e-ad0d-c51c6f6345e0", markerTypeId = "hochsitz", markerNotes = "Notiz")
        var deleted = false
        composeRule.setContent {
            MaterialTheme {
                MarkerEditDialog(marker, canEdit = true, canDelete = true,
                    onUpdate = { _, _ -> }, onDelete = { deleted = true },
                    onMove = {}, onCreateGuestLink = { "https://example.test/#gast=token" }, onShareGuestLink = {},
                    onUpdated = {}, onDeleted = {}, onMoved = {}, onDismiss = {})
            }
        }
        composeRule.onNodeWithText("Loeschen").performClick()
        composeRule.onNodeWithText("Markierung loeschen?").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(false, deleted) }
        composeRule.onNodeWithText("Endgueltig loeschen").performClick()
        composeRule.runOnIdle { assertTrue(deleted) }
    }

    @Test
    fun guestCanOnlyDeleteTheirOwnTemporaryMarker() {
        val marker = HuntingMapPoint(51.5, 7.5, "Eigener Anschuss", "anschuss", MapPointKind.FACILITY,
            markerId = "c76274b7-e21a-440e-ad0d-c51c6f6345e0", markerTypeId = "anschuss", createdBy = "guest")
        var deleted = false
        composeRule.setContent {
            MaterialTheme {
                MarkerEditDialog(marker, canEdit = false, canDelete = true,
                    onUpdate = { _, _ -> error("Gast darf den Markertext nicht bearbeiten.") },
                    onDelete = { deleted = true }, onMove = { error("Gast darf Marker nicht verschieben.") },
                    onCreateGuestLink = { error("Gast darf keine Gastlinks erstellen.") }, onShareGuestLink = {},
                    onUpdated = {}, onDeleted = {}, onMoved = {}, onDismiss = {})
            }
        }
        composeRule.onNodeWithText("Eigene Meldung loeschen?").assertIsDisplayed()
        composeRule.onAllNodesWithText("Bezeichnung").assertCountEquals(0)
        composeRule.onNodeWithText("Meldung loeschen").performClick()
        composeRule.runOnIdle { assertTrue(deleted) }
    }

    @Test
    fun markerCanCreateAndSharePositionSpecificGuestLink() {
        val marker = HuntingMapPoint(51.5, 7.5, "Sitz am Wald", "hochsitz: Notiz", MapPointKind.FACILITY,
            markerId = "c76274b7-e21a-440e-ad0d-c51c6f6345e0", markerTypeId = "hochsitz", markerNotes = "Notiz")
        var sharedLink = ""
        composeRule.setContent {
            MaterialTheme {
                MarkerEditDialog(marker, canEdit = true, canDelete = true,
                    onUpdate = { _, _ -> }, onDelete = {}, onMove = {},
                    onCreateGuestLink = { "https://wierichs.github.io/Jagdkarte/#gast=" + "A".repeat(43) },
                    onShareGuestLink = { sharedLink = it }, onUpdated = {}, onDeleted = {}, onMoved = {}, onDismiss = {})
            }
        }
        composeRule.onNodeWithText("Gastlink teilen").performClick()
        composeRule.onNodeWithText("Gastlink erstellt. Er ist 7 Tage gueltig.").assertIsDisplayed()
        composeRule.runOnIdle { assertTrue(sharedLink.startsWith("https://wierichs.github.io/Jagdkarte/#gast=")) }
    }

    private fun captureScreenshot(name: String, dialog: Boolean = true) {
        val node = if (dialog) composeRule.onNode(isDialog()) else composeRule.onRoot()
        val bitmap = node.captureToImage().asAndroidBitmap()
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}