package org.wierichs.dogtracker.jagdkarte

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Instant
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflinePublicBoundaryInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun feature(sourceId: String = "offline-test", license: String = "CC BY 4.0") = PublicBoundaryFeature(
        "feature-1", "Waldrevier", JSONObject("""{"type":"Polygon","coordinates":[[
            [6.8,51.2],[6.9,51.2],[6.9,51.3],[6.8,51.3],[6.8,51.2]]]}"""),
        JSONObject().put("district_name", "Waldrevier"),
        PublicBoundarySource(sourceId, "Testquelle", PublicBoundaryService.GEOJSON, "https://example.org/reviere.geojson",
            nameFields = listOf("district_name"), attribution = "Testamt", license = license))

    @Test fun explicitSavePersistsOnlyPublicFeatureMetadataInNoBackupAndCanBeDeleted() {
        val (store, root) = isolatedStore()
        val savedAt = Instant.parse("2026-11-11T08:00:00Z")
        try {
            val saved = store.save(feature(), savedAt)
            assertEquals(1, store.all().size)
            assertEquals(savedAt, saved.savedAt)
            assertEquals("Waldrevier", store.find("offline-test", "feature-1")!!.feature.name)
            assertEquals("CC BY 4.0", store.all().single().feature.source.license)
            assertTrue(File(root, "public-boundaries").isDirectory)
            assertTrue(File(root, "public-boundaries").listFiles().orEmpty().all { it.extension == "json" })
            store.delete("offline-test", "feature-1")
            assertTrue(store.all().isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun saveUpdatesTheSameFeatureWithoutCreatingDuplicates() {
        val (store, root) = isolatedStore()
        try {
            store.save(feature(), Instant.parse("2026-11-10T08:00:00Z"))
            val updated = store.save(feature().copy(name = "Waldrevier Nord",
                properties = JSONObject().put("district_name", "Waldrevier Nord")), Instant.parse("2026-11-11T08:00:00Z"))
            assertEquals(1, store.all().size)
            assertEquals("Waldrevier Nord", updated.feature.name)
            assertEquals("Waldrevier Nord", store.find("offline-test", "feature-1")!!.feature.name)
        } finally { root.deleteRecursively() }
    }

    @Test fun cacheLimitsRejectOversizedGeometryWithoutPartialFile() {
        val (store, root) = isolatedStore()
        val oversized = feature().copy(properties = JSONObject().put("large", "x".repeat(OfflinePublicBoundaryStore.MAX_FEATURE_BYTES + 1)))
        try {
            assertThrows(IllegalArgumentException::class.java) { store.save(oversized) }
            assertTrue(store.all().isEmpty())
            assertTrue(File(root, "public-boundaries").listFiles().orEmpty().isEmpty())
        } finally { root.deleteRecursively() }
    }

    private fun isolatedStore(): Pair<OfflinePublicBoundaryStore, File> {
        val root = File(context.cacheDir, "public-boundary-test-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = root }
        return OfflinePublicBoundaryStore(isolated) to root
    }
}