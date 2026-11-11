package org.wierichs.dogtracker.jagdkarte

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.Instant

internal data class SavedPublicBoundary(
    val feature: PublicBoundaryFeature,
    val savedAt: Instant,
    val fileBytes: Long
)

internal class OfflinePublicBoundaryStore(context: Context) {
    private val appContext = context
    private val directory get() = File(appContext.noBackupFilesDir, "public-boundaries").apply { mkdirs() }

    fun all(): List<SavedPublicBoundary> = directory.listFiles().orEmpty()
        .filter { it.extension == "json" }.mapNotNull { file -> runCatching { read(file) }.getOrNull() }
        .sortedByDescending { it.savedAt }

    fun find(sourceId: String, featureId: String): SavedPublicBoundary? =
        readOrNull(metadataFile(sourceId, featureId))

    fun save(feature: PublicBoundaryFeature, savedAt: Instant = Instant.now()): SavedPublicBoundary {
        val checkedGeometry = normalizeGeoJsonGeometry(feature.geometry, feature.name).first
        val savedFeature = feature.copy(geometry = checkedGeometry, properties = JSONObject(feature.properties.toString()))
        val json = JSONObject().put("source", PublicBoundarySourceStore.sourceToJson(savedFeature.source))
            .put("featureId", savedFeature.id).put("name", savedFeature.name)
            .put("geometry", savedFeature.geometry).put("properties", savedFeature.properties)
            .put("savedAt", savedAt.toString())
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_FEATURE_BYTES) { "Diese Reviergrenze ist groesser als 4 MB und wird nicht offline gespeichert." }
        val destination = metadataFile(savedFeature.source.id, savedFeature.id)
        val existingSize = destination.takeIf(File::isFile)?.length() ?: 0L
        val currentTotal = directory.listFiles().orEmpty().filter { it.extension == "json" }.sumOf(File::length)
        require(destination.exists() || directory.listFiles().orEmpty().count { it.extension == "json" } < MAX_FEATURES) {
            "Hoechstens $MAX_FEATURES oeffentliche Reviergrenzen koennen offline gespeichert werden."
        }
        require(currentTotal - existingSize + bytes.size <= MAX_TOTAL_BYTES) {
            "Der Offline-Grenzspeicher ist auf 64 MB begrenzt. Bitte gespeicherte Grenzen entfernen."
        }
        val atomic = AtomicFile(destination)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            atomic.finishWrite(output)
        } catch (failure: Exception) {
            atomic.failWrite(output)
            throw failure
        }
        return SavedPublicBoundary(savedFeature, savedAt, bytes.size.toLong())
    }

    fun delete(sourceId: String, featureId: String) {
        AtomicFile(metadataFile(sourceId, featureId)).delete()
    }

    private fun readOrNull(file: File): SavedPublicBoundary? = runCatching { read(file) }.getOrNull()

    private fun read(file: File): SavedPublicBoundary {
        val json = JSONObject(AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
        val source = PublicBoundarySourceStore.sourceFromJson(json.getJSONObject("source"),
            json.getJSONObject("source").optBoolean("userDefined"))
        val featureJson = JSONObject().put("type", "Feature").put("id", json.getString("featureId"))
            .put("properties", json.getJSONObject("properties")).put("geometry", json.getJSONObject("geometry"))
        val feature = publicBoundaryFeatureFromJson(featureJson, source, 0)
            ?: error("Gespeicherte Reviergrenze ist ungueltig.")
        check(file.name == metadataFile(source.id, feature.id).name)
        return SavedPublicBoundary(feature, Instant.parse(json.getString("savedAt")), file.length())
    }

    private fun metadataFile(sourceId: String, featureId: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest("$sourceId\u0000$featureId".toByteArray())
        val id = digest.joinToString("") { "%02x".format(it) }
        return File(directory, "$id.json")
    }

    companion object {
        const val MAX_FEATURE_BYTES = 4 * 1024 * 1024
        const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
        const val MAX_FEATURES = 100
    }
}