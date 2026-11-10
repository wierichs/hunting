package org.wierichs.dogtracker.jagdkarte

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.config.Configuration
import org.osmdroid.mapsforge.MapsForgeTileSource
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.wierichs.dogtracker.TrackerPreferences
import kotlin.math.*

@RunWith(AndroidJUnit4::class)
class MapAppearanceInstrumentedTest {
    @get:Rule val rule = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val district = JSONObject("""{"type":"Polygon","coordinates":[[
        [9.515,47.135],[9.525,47.135],[9.525,47.145],[9.515,47.145],[9.515,47.135]]]}""")

    @Test fun menuHidesUnavailableOfflineAndDisablesUnavailableAerial() {
        rule.setContent { MaterialTheme {
            MapStyleMenu(HuntingMapStyle.OPEN_STREET_MAP, false, false, {}, {})
        } }
        rule.onNodeWithContentDescription("Kartendarstellung").performClick()
        rule.onNodeWithText("Offline").assertDoesNotExist()
        rule.onNodeWithText("Luftbild").assertIsNotEnabled()
        rule.onNodeWithText("Fuer diese Region noch nicht verfuegbar").assertIsDisplayed()
        rule.onNodeWithText("Offlinekarten verwalten").assertIsDisplayed()
    }

    @Test fun menuOffersAllThreeStylesForAvailableSources() {
        var chosen: HuntingMapStyle? = null
        rule.setContent { MaterialTheme {
            MapStyleMenu(HuntingMapStyle.OPEN_STREET_MAP, true, true, { chosen = it }, {})
        } }
        rule.onNodeWithContentDescription("Kartendarstellung").performClick()
        rule.onNodeWithText("Offline").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(HuntingMapStyle.OFFLINE, chosen) }
        rule.onNodeWithContentDescription("Kartendarstellung").performClick()
        rule.onNodeWithText("Luftbild").performClick()
        rule.runOnIdle { assertEquals(HuntingMapStyle.AERIAL, chosen) }
    }

    @Test fun offlineWithoutPackagesHasNoNetworkFallback() {
        val provider = huntingTileProvider(context, HuntingMapStyle.OFFLINE, emptyList())
        try {
            assertFalse(provider.useDataConnection())
            assertNull(provider.getMapTile(MapTileIndex.getTileIndex(12, 2156, 1439)))
        } finally { provider.detach() }
    }

    @Test fun aerialUses256PixelTilesWithWmtsZoomOffsetAndDedicatedCacheNamespace() {
        val source = aerialTileSource()
        assertEquals(256, source.tileSizePixels)
        assertEquals("https://www.wmts.nrw.de/geobasis/wmts_nw_dop/tiles/nw_dop/EPSG_3857_16/07/2156/1439",
            source.getTileURLString(MapTileIndex.getTileIndex(12, 2156, 1439)))
        assertEquals(NrwAerialImagery.CACHE_NAME, source.name())
    }

    @Test fun previousSatellitePreferenceMigratesToAerialWithoutUserSetup() {
        val previous = TrackerPreferences.huntingMapStyle(context)
        try {
            context.getSharedPreferences("tracker_settings", android.content.Context.MODE_PRIVATE).edit()
                .putString("hunting_map_style", "SATELLITE").commit()
            assertEquals(HuntingMapStyle.AERIAL.name, TrackerPreferences.huntingMapStyle(context))
        } finally { TrackerPreferences.setHuntingMapStyle(context, previous) }
    }

    @Test fun nativeAerialMapRendersNrwWithoutKeyAndStopsOutsideServiceExtent() {
        assumeTrue("Live-Luftbildtest muss ausdruecklich aktiviert werden",
            InstrumentationRegistry.getArguments().getString("liveAerial") == "true")
        val previous = TrackerPreferences.huntingMapStyle(context)
        val activeBoundary = mutableStateOf(JSONObject("""{"type":"Polygon","coordinates":[[
            [6.725,51.185],[6.735,51.185],[6.735,51.195],[6.725,51.195],[6.725,51.185]]]}"""))
        try {
            TrackerPreferences.setHuntingMapStyle(context, HuntingMapStyle.AERIAL.name)
            rule.setContent { MaterialTheme {
                NativeHuntingMap(HuntingSnapshot(HuntingProfile("aerial-test", "Luftbildtest NRW", "test-owner", false,
                    activeBoundary.value), emptyList(), true), Instant.now(), Modifier.fillMaxSize(), onChooseLocation = { false })
            } }
            rule.waitUntil(15_000) { rule.runOnIdle { currentMap().tileProvider.tileSource.name() == NrwAerialImagery.CACHE_NAME } }
            rule.runOnIdle {
                assertTrue(currentMap().useDataConnection())
                assertEquals(5.0, currentMap().minZoomLevel, 0.0)
                assertEquals(20.0, currentMap().maxZoomLevel, 0.0)
            }
            rule.waitUntil(45_000) {
                rule.runOnIdle {
                    val map = currentMap()
                    val zoom = map.zoomLevelDouble.toInt()
                    (1..3).all { horizontal -> (1..4).all { vertical ->
                        val point = map.projection.fromPixels(map.width * horizontal / 4, map.height * vertical / 5)
                        val tileX = ((point.longitude + 180) / 360 * (1 shl zoom)).toInt()
                        val radians = Math.toRadians(point.latitude)
                        val tileY = ((1 - ln(tan(radians) + 1 / cos(radians)) / Math.PI) / 2 * (1 shl zoom)).toInt()
                        map.tileProvider.getMapTile(MapTileIndex.getTileIndex(zoom, tileX, tileY)) != null
                    } }
                }
            }
            rule.onNodeWithText(NrwAerialImagery.ATTRIBUTION).assertIsDisplayed()
            rule.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
                val colors = mutableSetOf<Int>()
                for (vertical in bitmap.height * 3 / 10 until bitmap.height * 7 / 10 step 6)
                    for (horizontal in bitmap.width * 3 / 10 until bitmap.width * 7 / 10 step 6)
                        colors.add(bitmap.getPixel(horizontal, vertical))
                assertTrue("Die Luftbild-Kartenbasis muss sichtbar und nicht leer sein", colors.size > 100)
                File(context.getExternalFilesDir(null), "nrw-aerial-native-map.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            rule.runOnIdle { activeBoundary.value = district }
            rule.waitUntil(10_000) { rule.runOnIdle { !currentMap().useDataConnection() } }
            rule.onNodeWithText("Fuer dieses Revier sind noch keine freien Luftbilder eingerichtet.").assertIsDisplayed()
        } finally { TrackerPreferences.setHuntingMapStyle(context, previous) }
    }

    @Test fun malformedMapAndCancelledImportLeaveNoPartialFiles() = runBlocking {
        withStore { store, root ->
            var rejected = false
            try { store.importSources(listOf(OfflineMapDocument("broken.map") { ByteArrayInputStream(ByteArray(80)) })) }
            catch (_: Exception) { rejected = true }
            assertTrue(rejected)
            try {
                store.importSources(listOf(OfflineMapDocument("cancelled.map") { throw CancellationException("Testabbruch") }))
                fail("Abbruch muss weitergereicht werden")
            } catch (_: CancellationException) { }
            assertTrue(store.packages().isEmpty())
            assertTrue(File(root, "offline-maps").listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun realPackageImportsDeduplicatesReloadsAndDeletes() = runBlocking {
        val sources = fixtureSources()
        withStore { store, root ->
            store.importSources(sources.take(1))
            val withoutCoverage = store.packages().single()
            assertTrue(coveringOfflineMaps(listOf(withoutCoverage), district).isEmpty())
            store.importSources(sources)
            val entry = store.packages().single()
            assertEquals(1, coveringOfflineMaps(listOf(entry), district).size)
            assertEquals(withoutCoverage.checksum, entry.checksum)
            val reloaded = OfflineMapStore(object : ContextWrapper(context) { override fun getNoBackupFilesDir() = root }).packages()
            assertEquals(entry.id, reloaded.single().id)
            store.delete(entry)
            assertTrue(store.packages().isEmpty())
        }
    }

    @Test fun realPackageRendersNonblankTileWithNetworkDisabled() = runBlocking {
        withStore { store, _ ->
            store.importSources(fixtureSources())
            val packages = coveringOfflineMaps(store.packages(), district)
            assertEquals(1, packages.size)
            Configuration.getInstance().osmdroidBasePath = File(context.cacheDir, "osmdroid")
            Configuration.getInstance().osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
            val provider = huntingTileProvider(context, HuntingMapStyle.OFFLINE, packages)
            try {
                assertFalse(provider.useDataConnection())
                val zoom = 15
                val tileX = ((9.52 + 180) / 360 * (1 shl zoom)).toInt()
                val latitudeRadians = Math.toRadians(47.14)
                val tileY = ((1 - ln(tan(latitudeRadians) + 1 / cos(latitudeRadians)) / Math.PI) / 2 * (1 shl zoom)).toInt()
                val drawable = (provider.tileSource as MapsForgeTileSource).renderTile(MapTileIndex.getTileIndex(zoom, tileX, tileY))
                assertNotNull("Mapsforge muss eine Kachel rendern", drawable)
                val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
                drawable!!.setBounds(0, 0, 256, 256)
                drawable.draw(Canvas(bitmap))
                val colors = mutableSetOf<Int>()
                for (latitudePixel in 0 until 256 step 2) for (longitudePixel in 0 until 256 step 2)
                    colors.add(bitmap.getPixel(longitudePixel, latitudePixel))
                assertTrue("Die Offlinekachel darf nicht leer sein", colors.size > 20)
                File(context.getExternalFilesDir(null), "offline-rendered-tile.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            } finally { provider.detach() }
        }
    }

    @Test fun nativeMapUsesOfflineAndPreservesCenterWhenSwitchingSources(): Unit = runBlocking {
        val store = OfflineMapStore(context)
        val before = store.packages().map { it.id }.toSet()
        val previousStyle = TrackerPreferences.huntingMapStyle(context)
        val activeBoundary = mutableStateOf(district)
        try {
            store.importSources(fixtureSources())
            TrackerPreferences.setHuntingMapStyle(context, HuntingMapStyle.OFFLINE.name)
            rule.setContent { MaterialTheme {
                NativeHuntingMap(HuntingSnapshot(HuntingProfile("offline-test", "Testrevier", "test-owner", false, activeBoundary.value),
                    emptyList(), true), Instant.now(), Modifier.fillMaxSize(), onChooseLocation = { false })
            } }
            rule.waitUntil(15_000) { rule.runOnIdle { currentMap().tileProvider.tileSource is MapsForgeTileSource } }
            var latitude = 0.0
            var longitude = 0.0
            rule.runOnIdle {
                assertFalse(currentMap().useDataConnection())
                latitude = currentMap().mapCenter.latitude
                longitude = currentMap().mapCenter.longitude
            }
            rule.onNodeWithContentDescription("Kartendarstellung").performClick()
            rule.onNodeWithText("OpenStreetMap").performClick()
            rule.runOnIdle {
                assertTrue(currentMap().useDataConnection())
                assertEquals(latitude, currentMap().mapCenter.latitude, 0.00001)
                assertEquals(longitude, currentMap().mapCenter.longitude, 0.00001)
            }
            rule.onNodeWithContentDescription("Kartendarstellung").performClick()
            rule.onNodeWithText("Offline").performClick()
            rule.runOnIdle { assertFalse(currentMap().useDataConnection()) }
            rule.waitUntil(30_000) {
                rule.runOnIdle {
                    val map = currentMap()
                    val zoom = map.zoomLevelDouble.toInt()
                    (1..3).all { horizontal -> (1..4).all { vertical ->
                        val point = map.projection.fromPixels(map.width * horizontal / 4, map.height * vertical / 5)
                        val tileX = ((point.longitude + 180) / 360 * (1 shl zoom)).toInt()
                        val radians = Math.toRadians(point.latitude)
                        val tileY = ((1 - ln(tan(radians) + 1 / cos(radians)) / Math.PI) / 2 * (1 shl zoom)).toInt()
                        map.tileProvider.getMapTile(MapTileIndex.getTileIndex(zoom, tileX, tileY)) != null
                    } }
                }
            }
            rule.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
                val colors = mutableSetOf<Int>()
                for (vertical in bitmap.height * 3 / 10 until bitmap.height * 7 / 10 step 6)
                    for (horizontal in bitmap.width * 3 / 10 until bitmap.width * 7 / 10 step 6)
                        colors.add(bitmap.getPixel(horizontal, vertical))
                assertTrue("Die native Offline-Kartenbasis muss sichtbar und nicht leer sein", colors.size > 20)
                File(context.getExternalFilesDir(null), "offline-native-map.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            rule.runOnIdle {
                activeBoundary.value = JSONObject("""{"type":"Polygon","coordinates":[[[6,50],[8,50],[8,52],[6,52],[6,50]]]}""")
            }
            rule.waitUntil(10_000) { rule.runOnIdle { currentMap().tileProvider.tileSource !is MapsForgeTileSource } }
            rule.runOnIdle { assertFalse(currentMap().useDataConnection()) }
            rule.onNodeWithText("Keine Offlinekarte deckt dieses Revier vollstaendig ab.").assertIsDisplayed()
        } finally {
            TrackerPreferences.setHuntingMapStyle(context, previousStyle)
            store.packages().filter { it.id !in before }.forEach { store.delete(it) }
        }
    }

    private fun fixtureSources(): List<OfflineMapDocument> {
        val map = File(context.getExternalFilesDir(null), "liechtenstein.map")
        val polygon = File(context.getExternalFilesDir(null), "liechtenstein.poly")
        assumeTrue("Mapsforge-Testpaket muss im externen App-Testverzeichnis liegen", map.isFile && polygon.isFile)
        return listOf(OfflineMapDocument(map.name) { map.inputStream() }, OfflineMapDocument(polygon.name) { polygon.inputStream() })
    }

    private suspend fun withStore(test: suspend (OfflineMapStore, File) -> Unit) {
        val root = File(context.cacheDir, "offline-test-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = root }
        try { test(OfflineMapStore(isolated), root) } finally { root.deleteRecursively() }
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