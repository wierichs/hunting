package org.wierichs.dogtracker.jagdkarte

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.Point
import android.graphics.drawable.PictureDrawable
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.json.JSONObject
import org.wierichs.dogtracker.TrackerPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun jwdMarkerTypeMappingPreferencePersistsOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val previous = TrackerPreferences.jwdMarkerTypeMappings(context).getValue("falle")
        try {
            TrackerPreferences.setJwdMarkerTypeMapping(context, "falle", "bock")
            assertEquals("bock", TrackerPreferences.jwdMarkerTypeMappings(context).getValue("falle"))
        } finally {
            TrackerPreferences.setJwdMarkerTypeMapping(context, "falle", previous)
        }
    }

    @Test
    fun hiddenProfilesPersistPerUserAndCanBeRestored() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ownerId = "owner-100"
        val otherUserId = "other-200"
        val profileId = "profile-300"
        try {
            TrackerPreferences.setHuntingProfileHidden(context, ownerId, profileId, true)
            assertEquals(setOf(profileId), TrackerPreferences.hiddenHuntingProfileIds(context, ownerId))
            assertEquals(emptySet<String>(), TrackerPreferences.hiddenHuntingProfileIds(context, otherUserId))
            TrackerPreferences.setHuntingProfileHidden(context, ownerId, profileId, false)
            assertEquals(emptySet<String>(), TrackerPreferences.hiddenHuntingProfileIds(context, ownerId))
        } finally {
            TrackerPreferences.setHuntingProfileHidden(context, ownerId, profileId, false)
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
        composeRule.onAllNodesWithContentDescription("Markierung setzen", useUnmergedTree = true).assertCountEquals(0)
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
        composeRule.onNodeWithContentDescription("Markierung setzen", useUnmergedTree = true).performClick()
        composeRule.onNodeWithContentDescription("Zielposition", useUnmergedTree = true).assertIsDisplayed()
        captureScreenshot("marker-position", dialog = false)
        composeRule.onNodeWithContentDescription("Hier setzen", useUnmergedTree = true).performClick()
        composeRule.runOnIdle {
            assertTrue(chosen!!.latitude in 50.0..52.0)
            assertTrue(chosen!!.longitude in 6.0..8.0)
        }
        composeRule.onNodeWithContentDescription("Markierung setzen", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Markierung setzen", useUnmergedTree = true).performClick()
        composeRule.onNodeWithContentDescription("Positionswahl abbrechen", useUnmergedTree = true).performClick()
        composeRule.onAllNodesWithContentDescription("Zielposition", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun markersNearBoundaryStayInsideWhenZoomingOutAndIn() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val boundary = geometryPolygons(profile.boundary).single().first()
        val markerCoordinates = listOf(
            GeoPoint(50.001, 7.0),
            GeoPoint(51.999, 7.0),
            GeoPoint(51.0, 6.001),
            GeoPoint(51.0, 7.999)
        )
        markerCoordinates.forEach { coordinate ->
            assertTrue(locationInsideBoundary(profile.boundary, coordinate.latitude, coordinate.longitude))
        }
        var failureMessage: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val map = MapView(context)
            map.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY)
            )
            map.maxZoomLevel = MAX_HUNTING_MAP_ZOOM
            map.layout(0, 0, 1080, 1920)
            val iconWidth = (40 * context.resources.displayMetrics.density).toInt()
            val iconHeight = (48 * context.resources.displayMetrics.density).toInt()
            val svgPicture = markerSvgPicture(context, "markers/hochsitz.svg", iconWidth, iconHeight)
            val markerPicture = Picture().apply {
                beginRecording(iconWidth, iconHeight).apply {
                    drawPicture(svgPicture)
                    drawCircle(iconWidth / 2f, iconHeight * (46f / 48f), 3f * context.resources.displayMetrics.density,
                        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.MAGENTA })
                }
                endRecording()
            }
            markerCoordinates.forEach { coordinate ->
                for (zoom in listOf(4.0, 8.0, 12.0, 16.0, MAX_HUNTING_MAP_ZOOM)) {
                    map.overlays.clear()
                    map.overlays.add(Polygon(map).apply {
                        setPoints(boundary)
                        outlinePaint.color = Color.GREEN
                        fillPaint.color = Color.argb(48, 0, 255, 0)
                    })
                    map.overlays.add(Marker(map).apply {
                        position = coordinate
                        setAnchor(0.5f, 46f / 48f)
                        icon = PictureDrawable(markerPicture)
                    })
                    map.controller.setCenter(coordinate)
                    map.controller.setZoom(zoom)
                    assertEquals(zoom, map.zoomLevelDouble, 0.001)
                    val projectedMarker = map.projection.toPixels(coordinate, Point())
                    val renderedMap = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
                    map.draw(Canvas(renderedMap))
                    var redPinNearCoordinate = false
                    var closestRedPixel: Point? = null
                    var closestRedDistanceSquared = Int.MAX_VALUE
                    var minRedX = renderedMap.width
                    var minRedY = renderedMap.height
                    var maxRedX = -1
                    var maxRedY = -1
                    val searchRadius = (12 * context.resources.displayMetrics.density).toInt()
                    val iconLeft = projectedMarker.x - iconWidth / 2
                    val iconTop = projectedMarker.y - (iconHeight * 46f / 48f).toInt()
                    for (y in 0 until renderedMap.height) {
                        for (x in 0 until renderedMap.width) {
                            val pixel = renderedMap.getPixel(x, y)
                            if (Color.red(pixel) <= 150 || Color.green(pixel) !in 20..120 || Color.blue(pixel) !in 30..140) continue
                            if (x in iconLeft - 4..iconLeft + iconWidth + 4 &&
                                y in iconTop - 4..iconTop + iconHeight + 4) {
                                minRedX = minOf(minRedX, x)
                                minRedY = minOf(minRedY, y)
                                maxRedX = maxOf(maxRedX, x)
                                maxRedY = maxOf(maxRedY, y)
                            }
                            val distanceSquared = (x - projectedMarker.x) * (x - projectedMarker.x) +
                                (y - projectedMarker.y) * (y - projectedMarker.y)
                            if (distanceSquared < closestRedDistanceSquared) {
                                closestRedDistanceSquared = distanceSquared
                                closestRedPixel = Point(x, y)
                            }
                            if (distanceSquared <= searchRadius * searchRadius) redPinNearCoordinate = true
                        }
                    }
                    val redWidth = maxRedX - minRedX + 1
                    val redHeight = maxRedY - minRedY + 1
                    if (!redPinNearCoordinate || redWidth < iconWidth * 0.75 || redHeight < iconHeight * 0.75) {
                        failureMessage = "Rendered SVG pin stayed away from $coordinate at zoom $zoom " +
                            "(projected point $projectedMarker, closest red pixel $closestRedPixel, " +
                            "distanceSquared $closestRedDistanceSquared, expected icon ${iconWidth}x$iconHeight, " +
                            "red bounds ${redWidth}x$redHeight)"
                    }
                    renderedMap.recycle()
                }
            }
            map.onDetach()
        }
        assertNull(failureMessage)
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
        composeRule.onNodeWithContentDescription("Legende anzeigen", useUnmergedTree = true).performClick()
        composeRule.onNodeWithText("Jagdbezirksgrenze", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Mitglied", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Bearbeiter", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Chef", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Hund", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Legende ausblenden", useUnmergedTree = true).performClick()
    }

    @Test
    fun guestLinkOpensTargetPopupAndKeepsDismissalAcrossMapUpdates() {
        val now = Instant.now()
        val target = HuntingMapPoint(51.5, 7.5, "Gastziel", "Zielnotiz", MapPointKind.FACILITY,
            markerId = "target", markerTypeId = "hochsitz")
        val neighbor = HuntingMapPoint(51.5, 7.5, "Nachbar", "", MapPointKind.FACILITY,
            markerId = "neighbor", markerTypeId = "hochsitz")
        val points = mutableStateOf(listOf(target, neighbor))
        val loadingSnapshot = mutableStateOf(false)
        val request = mutableIntStateOf(1)
        composeRule.setContent {
            MaterialTheme {
                NativeHuntingMap(
                    if (loadingSnapshot.value) null else HuntingSnapshot(profile, points.value, false),
                    now, Modifier.fillMaxSize(),
                    onChooseLocation = { false }, focusMarkerId = "target", focusRequest = request.intValue)
            }
        }

        fun marker(id: String): Marker =
            currentMap().overlays.filterIsInstance<Marker>().first { it.id == id }

        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertTrue(marker("target").isInfoWindowShown)
            assertFalse(marker("neighbor").isInfoWindowShown)
            points.value = listOf(target.copy(detail = "Aktualisiert"), neighbor)
        }
        composeRule.runOnIdle {
            assertTrue(marker("target").isInfoWindowShown)
            loadingSnapshot.value = true
        }
        composeRule.runOnIdle {
            loadingSnapshot.value = false
        }
        composeRule.runOnIdle {
            assertTrue(marker("target").isInfoWindowShown)
            marker("target").closeInfoWindow()
            points.value = listOf(target, neighbor)
        }
        composeRule.runOnIdle {
            assertFalse(marker("target").isInfoWindowShown)
            request.intValue++
        }
        composeRule.runOnIdle {
            assertTrue(marker("target").isInfoWindowShown)
        }
    }

    private fun currentMap(): MapView {
        fun find(view: View): MapView? {
            if (view is MapView) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) {
                find(view.getChildAt(index))?.let { return it }
            }
            return null
        }
        return WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::find) ?: error("Karte nicht gefunden")
    }

    @Test
    fun mapControlsToggleBoundaryAndFullScreen() {
        var fullScreen = false
        var ownLocationRequested = false
        composeRule.setContent {
            MaterialTheme {
                NativeHuntingMap(HuntingSnapshot(profile, emptyList(), true), Instant.now(), Modifier.fillMaxSize(),
                    onChooseLocation = { false }, onToggleOwnLocation = { ownLocationRequested = true },
                    onToggleFullScreen = { fullScreen = !fullScreen })
            }
        }
        composeRule.onNodeWithContentDescription("Eigenen Standort anzeigen", useUnmergedTree = true).performClick()
        composeRule.runOnIdle { assertTrue(ownLocationRequested) }
        composeRule.onNodeWithContentDescription("Reviergrenze ausblenden", useUnmergedTree = true).performClick()
        composeRule.onNodeWithContentDescription("Reviergrenze anzeigen", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Karte im Vollbild", useUnmergedTree = true).performClick()
        composeRule.runOnIdle { assertTrue(fullScreen) }
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
        composeRule.onNodeWithText("Koordinaten: 51.500000, 7.500000").assertIsDisplayed()
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