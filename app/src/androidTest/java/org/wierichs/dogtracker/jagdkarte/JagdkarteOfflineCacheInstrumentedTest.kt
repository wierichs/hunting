package org.wierichs.dogtracker.jagdkarte

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Instant
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JagdkarteOfflineCacheInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun encryptedCacheKeepsStaticMapDataOnlyAndExpiresAfterSevenDays() {
        val root = File(context.cacheDir, "jagdkarte-cache-test-${UUID.randomUUID()}").apply { mkdirs() }
        val isolatedContext = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir() = root
        }
        val cache = JagdkarteOfflineCache(isolatedContext)
        val userId = UUID.randomUUID().toString()
        val profileId = UUID.randomUUID().toString()
        val savedAt = Instant.parse("2026-11-11T08:00:00Z")
        val profile = HuntingProfile(profileId, "Privates Revier", userId, true,
            JSONObject("""{"type":"Polygon","coordinates":[[[6.8,51.2],[6.9,51.2],[6.9,51.3],[6.8,51.3],[6.8,51.2]]]}"""),
            "owner@example.test")
        val staticMarker = HuntingMapPoint(51.25, 6.85, "Kanzel", "Interne Notiz", MapPointKind.FACILITY,
            markerId = UUID.randomUUID().toString(), markerTypeId = "hochstand", markerNotes = "Interne Notiz",
            createdBy = userId)
        val participant = HuntingMapPoint(51.25, 6.86, "Teilnehmer", "Live", MapPointKind.PARTICIPANT,
            timestamp = savedAt)
        val dog = HuntingMapPoint(51.26, 6.86, "Hund", "Live", MapPointKind.DOG, timestamp = savedAt)
        try {
            cache.save(userId, HuntingSnapshot(profile, listOf(staticMarker, participant, dog), canEdit = true), savedAt)
            val encryptedFile = File(root, "hunting-snapshots").listFiles().orEmpty().single()
            assertFalse(String(encryptedFile.readBytes(), Charsets.UTF_8).contains("Privates Revier"))

            val cached = cache.load(userId, profileId, savedAt.plusSeconds(60))!!
            assertTrue(cached.snapshot.isStale)
            assertFalse(cached.snapshot.canEdit)
            assertNull(cached.snapshot.profile.ownerEmail)
            assertEquals(listOf("Kanzel"), cached.snapshot.points.map { it.title })
            assertNull(cache.load(UUID.randomUUID().toString(), profileId, savedAt.plusSeconds(60)))
            assertNull(cache.load(userId, profileId, savedAt.plus(JagdkarteOfflineCache.MAX_AGE).plusSeconds(1)))
            assertTrue(encryptedFile.parentFile?.isDirectory == true)
        } finally {
            root.deleteRecursively()
        }
    }
}