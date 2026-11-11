package org.wierichs.dogtracker.jagdkarte

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.wierichs.dogtracker.DeviceTokenStorage

internal data class CachedHuntingSnapshot(val snapshot: HuntingSnapshot, val savedAt: Instant)

internal class JagdkarteOfflineCache(context: Context) {
    private val appContext = context
    private val directory get() = File(appContext.noBackupFilesDir, "hunting-snapshots").apply { mkdirs() }

    @Synchronized
    fun allForUser(userId: String, now: Instant = Instant.now()): List<CachedHuntingSnapshot> {
        if (userId.isBlank()) return emptyList()
        return directory.listFiles().orEmpty().filter { it.extension == "enc" }
            .mapNotNull { readOrNull(it, userId, now) }
            .sortedByDescending { it.savedAt }
    }

    @Synchronized
    fun load(userId: String, profileId: String, now: Instant = Instant.now()): CachedHuntingSnapshot? {
        if (userId.isBlank() || profileId.isBlank()) return null
        return readOrNull(snapshotFile(userId, profileId), userId, now)
    }

    @Synchronized
    fun save(userId: String, snapshot: HuntingSnapshot, savedAt: Instant = Instant.now()) {
        require(userId.isNotBlank() && snapshot.profile.id.isNotBlank())
        val cachedSnapshot = snapshot.copy(
            profile = snapshot.profile.copy(ownerEmail = null),
            points = snapshot.points.filter { it.kind == MapPointKind.FACILITY },
            canEdit = false,
            cachedAt = savedAt,
            isStale = true
        )
        val destination = snapshotFile(userId, snapshot.profile.id)
        val snapshotJson = snapshotToJson(cachedSnapshot)
        val existing = readOrNull(destination, userId, savedAt)
        if (existing != null && snapshotToJson(existing.snapshot).toString() == snapshotJson.toString() &&
            Duration.between(existing.savedAt, savedAt) < CACHE_REFRESH_INTERVAL) return
        val document = JSONObject()
            .put("userId", userId)
            .put("profileId", snapshot.profile.id)
            .put("savedAt", savedAt.toString())
            .put("snapshot", snapshotJson)
        val encrypted = DeviceTokenStorage.encrypt(document.toString().toByteArray(StandardCharsets.UTF_8))
        require(encrypted.size <= MAX_PROFILE_BYTES) { "Der Offline-Revierstand ist zu gross und wurde nicht gespeichert." }

        val currentFiles = directory.listFiles().orEmpty().filter { it.extension == "enc" }
        val existingSize = destination.takeIf(File::isFile)?.length() ?: 0L
        require(destination.exists() || currentFiles.size < MAX_PROFILES) {
            "Der Offline-Cache ist voll. Bitte online ein Revier oeffnen, um alte Staende zu ersetzen."
        }
        require(currentFiles.sumOf(File::length) - existingSize + encrypted.size <= MAX_TOTAL_BYTES) {
            "Der Offline-Cache ist auf 32 MiB begrenzt."
        }

        val atomic = AtomicFile(destination)
        val output = atomic.startWrite()
        try {
            output.write(encrypted)
            atomic.finishWrite(output)
        } catch (failure: Exception) {
            atomic.failWrite(output)
            throw failure
        }
    }

    private fun readOrNull(file: File, userId: String, now: Instant): CachedHuntingSnapshot? {
        return try {
            val encrypted = AtomicFile(file).openRead().use { input ->
                require(file.length() <= MAX_PROFILE_BYTES)
                input.readBytes()
            }
            val document = JSONObject(DeviceTokenStorage.decrypt(encrypted).toString(StandardCharsets.UTF_8))
            if (document.optString("userId") != userId) return null
            val profileId = document.getString("profileId")
            if (file.name != snapshotFile(userId, profileId).name) return null
            val savedAt = Instant.parse(document.getString("savedAt"))
            if (savedAt.isAfter(now.plusSeconds(60)) || Duration.between(savedAt, now) > MAX_AGE) {
                AtomicFile(file).delete()
                return null
            }
            CachedHuntingSnapshot(snapshotFromJson(document.getJSONObject("snapshot"), savedAt), savedAt)
        } catch (_: Exception) {
            AtomicFile(file).delete()
            null
        }
    }

    private fun snapshotFile(userId: String, profileId: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$userId\u0000$profileId".toByteArray(StandardCharsets.UTF_8))
        val id = digest.joinToString("") { "%02x".format(it) }
        return File(directory, "$id.enc")
    }

    private fun snapshotToJson(snapshot: HuntingSnapshot) = JSONObject()
        .put("profile", JSONObject()
            .put("id", snapshot.profile.id)
            .put("name", snapshot.profile.name)
            .put("ownerId", snapshot.profile.ownerId)
            .put("huntingMode", snapshot.profile.huntingMode)
            .put("boundary", snapshot.profile.boundary ?: JSONObject.NULL))
        .put("canEdit", false)
        .put("points", JSONArray().apply {
            snapshot.points.forEach { point ->
                put(JSONObject()
                    .put("latitude", point.latitude)
                    .put("longitude", point.longitude)
                    .put("title", point.title)
                    .put("detail", point.detail)
                    .put("kind", point.kind.name)
                    .put("timestamp", point.timestamp?.toString() ?: JSONObject.NULL)
                    .put("expiresAt", point.expiresAt?.toString() ?: JSONObject.NULL)
                    .put("markerId", point.markerId ?: JSONObject.NULL)
                    .put("markerTypeId", point.markerTypeId ?: JSONObject.NULL)
                    .put("markerNotes", point.markerNotes)
                    .put("participantRole", point.participantRole.id)
                    .put("createdBy", point.createdBy ?: JSONObject.NULL))
            }
        })

    private fun snapshotFromJson(json: JSONObject, savedAt: Instant): HuntingSnapshot {
        val profileJson = json.getJSONObject("profile")
        val boundary = profileJson.opt("boundary").takeUnless { it == null || it == JSONObject.NULL } as? JSONObject
        val profile = HuntingProfile(profileJson.getString("id"), profileJson.getString("name"),
            profileJson.getString("ownerId"), profileJson.getBoolean("huntingMode"), boundary)
        val pointsJson = json.getJSONArray("points")
        val points = (0 until pointsJson.length()).map { index ->
            val point = pointsJson.getJSONObject(index)
            HuntingMapPoint(
                latitude = point.getDouble("latitude"),
                longitude = point.getDouble("longitude"),
                title = point.getString("title"),
                detail = point.getString("detail"),
                kind = MapPointKind.valueOf(point.getString("kind")),
                timestamp = point.optionalInstant("timestamp"),
                expiresAt = point.optionalInstant("expiresAt"),
                markerId = point.optNullableString("markerId"),
                markerTypeId = point.optNullableString("markerTypeId"),
                markerNotes = point.optString("markerNotes"),
                participantRole = HuntingParticipantRole.fromId(point.optString("participantRole")),
                createdBy = point.optNullableString("createdBy")
            )
        }
        return HuntingSnapshot(profile, points, canEdit = false, cachedAt = savedAt, isStale = true)
    }

    private fun JSONObject.optionalInstant(key: String): Instant? =
        optNullableString(key)?.let(Instant::parse)

    private fun JSONObject.optNullableString(key: String): String? = optString(key)
        .takeUnless { it.isBlank() || it == "null" }

    companion object {
        val MAX_AGE: Duration = Duration.ofDays(7)
        private val CACHE_REFRESH_INTERVAL: Duration = Duration.ofHours(12)
        const val MAX_PROFILE_BYTES = 4 * 1024 * 1024
        const val MAX_TOTAL_BYTES = 32L * 1024 * 1024
        const val MAX_PROFILES = 20
    }
}