package org.wierichs.dogtracker.jagdkarte

import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polygon
import org.wierichs.dogtracker.TrackerPreferences

@RunWith(AndroidJUnit4::class)
class PublicBoundarySearchInstrumentedTest {
    @get:Rule val rule = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun registryContainsTheFifteenCheckedPwaSourcesWithoutSecrets() {
        val sources = PublicBoundarySourceStore(context).all()
        assertEquals(15, sources.size)
        assertEquals(3, sources.count { it.service == PublicBoundaryService.WFS })
        assertEquals(6, sources.count { it.service == PublicBoundaryService.ARCGIS })
        assertEquals(5, sources.count { it.service == PublicBoundaryService.OGC_API })
        assertEquals(1, sources.count { it.service == PublicBoundaryService.SNAPSHOT })
        assertTrue(sources.none { it.url.contains("maptiler", true) || it.url.contains("publishable", true) })
        assertEquals("maerkischer-kreis", sources.first { it.label.startsWith("M\u00e4rkischer") }.id)
    }

    @Test fun customSourceCanBeStoredSelectedReloadedAndRemovedLocally() {
        val store = PublicBoundarySourceStore(context)
        val source = PublicBoundarySource(userBoundarySourceId(), "Lokaler Test", PublicBoundaryService.WFS,
            "https://example.org/wfs", typeName = "test:districts", nameFields = listOf("name"), userDefined = true)
        try {
            store.saveCustom(source)
            store.selectSource(source.id)
            val reloaded = PublicBoundarySourceStore(context)
            assertEquals(source.id, reloaded.selectedSourceId())
            assertEquals(source, reloaded.all().single { it.id == source.id })
            reloaded.deleteCustom(source.id)
            assertFalse(reloaded.all().any { it.id == source.id })
            assertNull(reloaded.selectedSourceId())
        } finally { store.deleteCustom(source.id) }
    }

    @Test fun anonymousSearchUsesSelectedSourceAndCanPreviewWithoutCreatingProfile() {
        val sourceStore = PublicBoundarySourceStore(context)
        val previous = sourceStore.selectedSourceId()
        sourceStore.selectSource("bielefeld")
        val feature = PublicBoundaryFeature("122", "Testrevier Mitte", JSONObject("""{
            "type":"Polygon","coordinates":[[[8.5,52.0],[8.6,52.0],[8.6,52.1],[8.5,52.1],[8.5,52.0]]]}
        """), JSONObject().put("revierda_1", "Testrevier Mitte"), sourceStore.all().first { it.id == "bielefeld" })
        var searchedSource = ""
        var searchedText = ""
        var previewed: PublicBoundaryFeature? = null
        var promptedSource: PublicBoundarySource? = null
        var created = false
        var dismissed = false
        try {
            rule.setContent { MaterialTheme {
                PublicBoundarySearchSheet(signedIn = false,
                    onSignIn = { source, _ -> promptedSource = source }, onPreview = { previewed = it },
                    onCreateProfile = { _, _ -> created = true; error("Anonymous profile creation must not run") },
                    search = { source, query ->
                        searchedSource = source.id
                        searchedText = query
                        listOf(feature)
                    }, onDismiss = { dismissed = true })
            } }
            rule.onNodeWithText("Bielefeld, Nordrhein-Westfalen").assertIsDisplayed()
            rule.onNodeWithText("Reviername, Nummer oder Gemeinde").performTextInput("Mitte")
            rule.onNodeWithText("Suchen").performClick()
            rule.onNodeWithText("Testrevier Mitte").assertIsDisplayed()
            rule.onNodeWithContentDescription("Revierdetails: Testrevier Mitte").performScrollTo().performClick()
            rule.onNodeWithText("Auf Karte zeigen").performClick()
            rule.runOnIdle {
                assertEquals("bielefeld", searchedSource)
                assertEquals("Mitte", searchedText)
                assertEquals(feature, previewed)
                assertFalse(created)
                assertTrue(dismissed)
            }
        } finally { previous?.let(sourceStore::selectSource) }
    }

    @Test fun unauthenticatedClaimCarriesSelectedFeatureIntoSignInAction() {
        val sourceStore = PublicBoundarySourceStore(context)
        val previous = sourceStore.selectedSourceId()
        sourceStore.selectSource("bielefeld")
        val source = sourceStore.all().first { it.id == "bielefeld" }
        val feature = PublicBoundaryFeature("claim-id", "Testrevier", JSONObject("""{
            "type":"Polygon","coordinates":[[[8.5,52.0],[8.6,52.0],[8.6,52.1],[8.5,52.1],[8.5,52.0]]]}
        """), JSONObject(), source)
        var prompted: PublicBoundaryFeature? = null
        var claimed = false
        try {
            rule.setContent { MaterialTheme {
                PublicBoundarySearchSheet(signedIn = false, onSignIn = { _, selected -> prompted = selected },
                    onPreview = {}, onCreateProfile = { _, _ -> claimed = true },
                    search = { _, _ -> listOf(feature) }, onDismiss = {})
            } }
            rule.onNodeWithText("Reviername, Nummer oder Gemeinde").performTextInput("Test")
            rule.onNodeWithText("Suchen").performClick()
            rule.waitUntil(5_000) {
                rule.onAllNodesWithContentDescription("Revierdetails: Testrevier").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithContentDescription("Revierdetails: Testrevier").performScrollTo().performClick()
            rule.onNodeWithText("Anmelden", substring = true).performScrollTo().performClick()
            rule.runOnIdle {
                assertEquals(feature, prompted)
                assertFalse(claimed)
            }
        } finally { previous?.let(sourceStore::selectSource) }
    }

    @Test fun userCanAddSelectAndSearchAOwnGeoJsonSource() {
        val sourceStore = PublicBoundarySourceStore(context)
        val previous = sourceStore.selectedSourceId()
        var searched: PublicBoundarySource? = null
        var dismissed = false
        try {
            sourceStore.selectSource("bielefeld")
            rule.setContent { MaterialTheme {
                PublicBoundarySearchSheet(signedIn = false, onSignIn = { _, _ -> }, onPreview = {},
                    onCreateProfile = { _, _ -> error("Test darf kein Profil schreiben.") },
                    search = { source, _ ->
                        searched = source
                        emptyList()
                    }, onDismiss = { dismissed = true })
            } }
            rule.onNodeWithContentDescription("Suchquellen verwalten").performClick()
            rule.onNodeWithText("GeoJSON- oder WFS-Quelle hinzufuegen").performClick()
            rule.onNodeWithText("Quelle hinzufuegen").assertIsDisplayed()
            rule.onNodeWithText("Anzeigename").performTextInput("Meine Gemeinde")
            rule.onNodeWithText("HTTPS-URL").performTextInput("https://example.org/reviere.geojson")
            rule.onNodeWithText("Namensfelder, kommasepariert").performTextInput("name, gemeinde")
            rule.onNodeWithText("Lizenz").performTextClearance()
            rule.onNodeWithText("Lizenz").performTextInput("CC BY 4.0")
            rule.onNodeWithText("Speichern").performClick()
            rule.onNodeWithContentDescription("Zurueck").performClick()
            rule.onNodeWithText("Meine Gemeinde").assertIsDisplayed()
            rule.onNodeWithText("Reviername, Nummer oder Gemeinde").performTextInput("Wald")
            rule.onNodeWithText("Suchen").performClick()
            rule.onNodeWithText("Keine passenden Reviere gefunden.").assertIsDisplayed()
            rule.runOnIdle {
                assertEquals("custom", searched?.id?.substringBefore('-'))
                assertEquals(PublicBoundaryService.GEOJSON, searched?.service)
                assertEquals("https://example.org/reviere.geojson", searched?.url)
            }
        } finally {
            sourceStore.all().filter { it.userDefined && it.label == "Meine Gemeinde" }.forEach { sourceStore.deleteCustom(it.id) }
            previous?.let(sourceStore::selectSource)
        }
    }

    @Test fun unknownLicenseRequiresAcknowledgmentBeforeLocalOfflineSaveAndCanBeRemoved() {
        val sourceStore = PublicBoundarySourceStore(context)
        val previous = sourceStore.selectedSourceId()
        val testId = UUID.randomUUID().toString().replace("-", "")
        val source = PublicBoundarySource("offline-$testId", "Testquelle", PublicBoundaryService.GEOJSON,
            "https://example.org/reviere.geojson", nameFields = listOf("name"),
            license = "Nutzungsbedingungen beim Anbieter pruefen")
        val feature = PublicBoundaryFeature("feature-$testId", "Waldrevier", JSONObject("""{
            "type":"Polygon","coordinates":[[[6.8,51.2],[6.9,51.2],[6.9,51.3],[6.8,51.3],[6.8,51.2]]]}"""),
            JSONObject().put("name", "Waldrevier"), source)
        val store = OfflinePublicBoundaryStore(context)
        try {
            store.all().forEach { store.delete(it.feature.source.id, it.feature.id) }
            store.delete(source.id, feature.id)
            assertNull(store.find(source.id, feature.id))
            sourceStore.selectSource("bielefeld")
            rule.setContent { MaterialTheme {
                PublicBoundarySearchSheet(signedIn = false, onSignIn = { _, _ -> }, onPreview = {},
                    onCreateProfile = { _, _ -> error("Test darf kein Profil schreiben.") },
                    search = { _, _ -> listOf(feature) }, onDismiss = {})
            } }
            rule.onNodeWithText("Reviername, Nummer oder Gemeinde").performTextInput("Wald")
            rule.onNodeWithText("Suchen").performClick()
            rule.waitUntil(5_000) {
                rule.onAllNodesWithContentDescription("Revierdetails: Waldrevier").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithContentDescription("Revierdetails: Waldrevier").performScrollTo().performClick()
            rule.onNodeWithText("Offline speichern").performClick()
            rule.onNodeWithText("Offline-Grenze speichern").assertIsDisplayed()
            rule.onNodeWithText("Ich habe die Nutzungsbedingungen der Quelle geprueft.").assertIsDisplayed()
            rule.onNodeWithText("Offline speichern").assertIsNotEnabled()
            rule.onNodeWithText("Ich habe die Nutzungsbedingungen der Quelle geprueft.").performClick()
            rule.onNodeWithText("Offline speichern").assertIsEnabled()
            rule.onNodeWithText("Offline speichern").performClick()
            rule.waitUntil(5_000) { store.find(source.id, feature.id) != null }
            rule.onNodeWithText("Waldrevier").assertExists()
            rule.onNodeWithContentDescription("Waldrevier offline entfernen").performClick()
            rule.onNodeWithText("Keine oeffentlichen Reviergrenzen offline gespeichert.").assertIsDisplayed()
            rule.runOnIdle { assertNull(store.find(source.id, feature.id)) }
        } finally {
            store.delete(source.id, feature.id)
            previous?.let(sourceStore::selectSource)
        }
    }

    @Test fun anonymousFeaturePreviewFitsMapAndCanBeClearedWithoutNetworkTiles() {
        val previousStyle = TrackerPreferences.huntingMapStyle(context)
        val geometry = JSONObject("""{"type":"Polygon","coordinates":[[
            [6.8,51.2],[6.82,51.2],[6.82,51.22],[6.8,51.22],[6.8,51.2]]]}""")
        val preview = mutableStateOf<JSONObject?>(geometry)
        try {
            TrackerPreferences.setHuntingMapStyle(context, HuntingMapStyle.OFFLINE.name)
            rule.setContent { MaterialTheme {
                NativeHuntingMap(snapshot = null, now = java.time.Instant.now(), modifier = Modifier.fillMaxSize(),
                    onChooseLocation = { false }, previewBoundary = preview.value,
                    previewBoundaryLabel = if (preview.value == null) "" else "Testtreffer",
                    previewBoundaryKey = if (preview.value == null) "" else "source:feature",
                    onClearBoundaryPreview = { preview.value = null })
            } }
            rule.waitUntil(10_000) { rule.runOnIdle { currentMap().zoomLevelDouble >= 11.0 } }
            rule.onNodeWithText("Vorschau: Testtreffer").assertIsDisplayed()
            rule.onNodeWithContentDescription("Vorschau schliessen").performClick()
            rule.runOnIdle {
                assertNull(preview.value)
                assertTrue(currentMap().overlays.filterIsInstance<Polygon>().isEmpty())
                assertFalse(currentMap().useDataConnection())
            }
        } finally { TrackerPreferences.setHuntingMapStyle(context, previousStyle) }
    }

    private fun currentMap(): MapView {
        fun find(view: View): MapView? {
            if (view is MapView) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
            return null
        }
        return WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::find) ?: error("Karte nicht gefunden")
    }
}