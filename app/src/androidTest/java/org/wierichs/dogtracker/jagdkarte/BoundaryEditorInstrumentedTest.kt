package org.wierichs.dogtracker.jagdkarte

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

@RunWith(AndroidJUnit4::class)
class BoundaryEditorInstrumentedTest {
    @get:Rule val composeRule = createComposeRule()
    private val boundary = JSONObject("""{"type":"MultiPolygon","coordinates":[
        [[[6,50],[8,50],[8,52],[6,52],[6,50]],[[6.5,50.5],[6.5,51],[7,51],[7,50.5],[6.5,50.5]]],
        [[[9,50],[10,50],[10,51],[9,51],[9,50]]]
    ]}""")
    private val snapshot = HuntingSnapshot(HuntingProfile("test-profile", "Testrevier", "owner", false, boundary), emptyList(), true)

    private fun start(onSave: suspend (BoundaryDraft, BoundaryDraft) -> Unit = { _, _ -> }, onSaved: () -> Unit = {},
        onDismiss: () -> Unit = {}) {
        composeRule.setContent {
            MaterialTheme { BoundaryEditor(snapshot, onSave, onSaved, onDismiss) }
        }
    }

    @Test fun pointEditsUndoRedoAndDiscardNeverWrite() {
        var writes = 0
        var dismissed = false
        start(onSave = { _, _ -> writes++ }, onDismiss = { dismissed = true })
        composeRule.onNodeWithContentDescription("Grenze speichern").assertIsNotEnabled()
        captureScreenshot("boundary-editor")
        composeRule.onNodeWithContentDescription("Grenzpunkt einfuegen").performClick()
        composeRule.onNodeWithText("Punkt\n2/5").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Grenzaenderung rueckgaengig").performClick()
        composeRule.onNodeWithText("Punkt\n1/4").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Grenze speichern").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Grenzaenderung wiederholen").performClick()
        composeRule.onNodeWithText("Punkt\n1/5").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Grenzbearbeitung schliessen").performClick()
        composeRule.onNodeWithText("Weiter bearbeiten").performClick()
        composeRule.onNodeWithContentDescription("Grenzbearbeitung schliessen").performClick()
        composeRule.onNodeWithText("Verwerfen").performClick()
        composeRule.runOnIdle {
            assertTrue(dismissed)
            assertEquals(0, writes)
            assertEquals(4, BoundaryDraft.fromGeometry(boundary).polygons[0][0].size)
        }
    }

    @Test fun savesOnlyAfterConfirmationAndRetainsOtherRings() {
        var saved: BoundaryDraft? = null
        var completed = false
        start(onSave = { original, draft ->
            assertEquals(BoundaryDraft.fromGeometry(boundary), original)
            saved = draft
        }, onSaved = { completed = true })
        composeRule.onNodeWithContentDescription("Grenzpunkt einfuegen").performClick()
        composeRule.onNodeWithContentDescription("Grenze speichern").performClick()
        composeRule.runOnIdle { assertNull(saved) }
        composeRule.onNodeWithText("Grenze speichern").performClick()
        composeRule.runOnIdle {
            assertTrue(completed)
            assertEquals(5, saved!!.polygons[0][0].size)
            assertEquals(BoundaryDraft.fromGeometry(boundary).polygons[0][1], saved!!.polygons[0][1])
            assertEquals(BoundaryDraft.fromGeometry(boundary).polygons[1], saved!!.polygons[1])
        }
    }

    @Test fun failedSaveKeepsDraftAndAllowsRetry() {
        var attempts = 0
        var completed = false
        start(onSave = { _, _ ->
            attempts++
            if (attempts == 1) error("Test: keine Schreibberechtigung")
        }, onSaved = { completed = true })
        composeRule.onNodeWithContentDescription("Grenzpunkt einfuegen").performClick()
        composeRule.onNodeWithContentDescription("Grenze speichern").performClick()
        composeRule.onNodeWithText("Grenze speichern").performClick()
        composeRule.onNodeWithText("Test: keine Schreibberechtigung").assertIsDisplayed()
        composeRule.onNodeWithText("Punkt\n2/5").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Grenze speichern").performClick()
        composeRule.onNodeWithText("Grenze speichern").performClick()
        composeRule.runOnIdle { assertEquals(2, attempts); assertTrue(completed) }
    }

    @Test fun removesPartAndHoleButProtectsLastOuterRing() {
        start()
        composeRule.onNodeWithContentDescription("Ausgewaehlte Flaeche loeschen").performClick()
        composeRule.onNodeWithText("Entfernen").performClick()
        composeRule.onNodeWithContentDescription("Ausgewaehlte Flaeche loeschen").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Grenzaenderung rueckgaengig").performClick()
        composeRule.onNodeWithText("Flaeche 1: Aussengrenze").performClick()
        composeRule.onNodeWithText("Flaeche 1: Befriedet 1").performClick()
        composeRule.onNodeWithContentDescription("Ausgewaehlte Flaeche loeschen").performClick()
        composeRule.onNodeWithText("Befriedeten Bereich entfernen?").assertIsDisplayed()
        composeRule.onNodeWithText("Entfernen").performClick()
        composeRule.onNodeWithContentDescription("Grenze speichern").assertIsEnabled()
    }

    @Test fun newAreaCanBeCancelledWithoutChangingExistingBoundary() {
        var dismissed = false
        start(onDismiss = { dismissed = true })
        composeRule.onNodeWithContentDescription("Flaeche hinzufuegen").performClick()
        composeRule.onNodeWithText("Neue Teilflaeche").performClick()
        composeRule.onNodeWithContentDescription("Neue Flaeche uebernehmen").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Grenzpunkt an Kartenmitte hinzufuegen").performClick()
        composeRule.onNodeWithText("Teilflaeche: 1 Punkte").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Neue Flaeche abbrechen").performClick()
        composeRule.onNodeWithContentDescription("Grenze speichern").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Grenzbearbeitung schliessen").performClick()
        composeRule.runOnIdle { assertTrue(dismissed) }
    }

    @Test fun mapCenterMovesSelectedVertexAndKeepsHandlesCentered() {
        var saved: BoundaryDraft? = null
        var target = MarkerCoordinates(49.9, 6.0)
        start(onSave = { _, draft -> saved = draft })
        composeRule.runOnIdle {
            val map = currentMap()
            assertEquals(8, map.overlays.filterIsInstance<Marker>().size)
            map.controller.setZoom(16.0)
            map.controller.setCenter(GeoPoint(49.9, 6.0))
        }
        composeRule.runOnIdle { target = currentMap().mapCenter.let { MarkerCoordinates(it.latitude, it.longitude) } }
        composeRule.onNodeWithContentDescription("Grenzpunkt zur Kartenmitte verschieben").performClick()
        composeRule.onNodeWithContentDescription("Grenze speichern").performClick()
        composeRule.onNodeWithText("Grenze speichern").performClick()
        composeRule.runOnIdle {
            assertNotNull(saved)
            assertEquals(target.latitude, saved!!.polygons[0][0][0].latitude, 0.000001)
            assertEquals(target.longitude, saved!!.polygons[0][0][0].longitude, 0.000001)
            val handles = currentMap().overlays.filterIsInstance<Marker>().filter { it.title.startsWith("Grenzpunkt ") && it.title != "Grenzpunkt einfuegen" }
            assertEquals(4, handles.size)
            assertEquals(target.latitude, handles.first().position.latitude, 0.000001)
        }
    }

    @Test fun drawsNewPartOnMapAndSavesItWithoutDroppingHoles() {
        var saved: BoundaryDraft? = null
        start(onSave = { _, draft -> saved = draft })
        composeRule.onNodeWithContentDescription("Flaeche hinzufuegen").performClick()
        composeRule.onNodeWithText("Neue Teilflaeche").performClick()
        listOf(GeoPoint(53.0, 9.0), GeoPoint(53.0, 10.0), GeoPoint(54.0, 9.0)).forEach { point ->
            composeRule.runOnIdle { currentMap().controller.setCenter(point) }
            composeRule.onNodeWithContentDescription("Grenzpunkt an Kartenmitte hinzufuegen").performClick()
        }
        composeRule.onNodeWithContentDescription("Neue Flaeche uebernehmen").performClick()
        composeRule.onNodeWithText("Flaeche 3: Aussengrenze").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Grenzpunkt loeschen").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Grenze speichern").performClick()
        composeRule.onNodeWithText("Grenze speichern").performClick()
        composeRule.runOnIdle {
            assertEquals(3, saved!!.polygons.size)
            assertEquals(2, saved!!.polygons[0].size)
            assertTrue(locationInsideBoundary(saved!!.validatedGeometry(), 53.1, 9.1))
        }
    }

    private fun currentMap(): MapView {
        fun findMap(view: View): MapView? {
            if (view is MapView) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) {
                findMap(view.getChildAt(index))?.let { return it }
            }
            return null
        }
        return WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::findMap) ?: error("Karte nicht gefunden")
    }

    private fun captureScreenshot(name: String) {
        val bitmap = composeRule.onNode(isDialog()).captureToImage().asAndroidBitmap()
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}