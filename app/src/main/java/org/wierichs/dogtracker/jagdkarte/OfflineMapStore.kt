package org.wierichs.dogtracker.jagdkarte

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.mapsforge.map.reader.MapFile
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

internal data class OfflineMapDocument(val name: String, val open: () -> InputStream)

internal class OfflineMapStore(private val context: Context) {
    private val directory get() = File(context.noBackupFilesDir, "offline-maps").apply { mkdirs() }

    suspend fun packages(): List<OfflineMapPackage> = withContext(Dispatchers.IO) { mutex.withLock { readPackages() } }

    private fun readPackages(): List<OfflineMapPackage> = directory.listFiles().orEmpty()
        .filter { it.extension == "json" }.mapNotNull { metadata ->
            runCatching {
                val json = JSONObject(AtomicFile(metadata).openRead().bufferedReader().use { it.readText() })
                val id = UUID.fromString(json.getString("id")).toString()
                val file = File(directory, "$id.map")
                val size = json.getLong("size")
                check(file.isFile && file.length() == size)
                OfflineMapPackage(id, json.getString("name"), file, size, json.getJSONObject("bounds"),
                    json.optJSONObject("coverage"), json.getString("checksum"))
            }.getOrNull()
        }.sortedBy { it.name.lowercase() }

    suspend fun importDocuments(uris: List<Uri>): String = withContext(Dispatchers.IO) {
        val documents = uris.map { uri ->
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                ?: error("Dateiname konnte nicht gelesen werden.")
            OfflineMapDocument(name) { context.contentResolver.openInputStream(uri) ?: error("Datei konnte nicht geoeffnet werden.") }
        }
        importSources(documents)
    }

    suspend fun importSources(documents: List<OfflineMapDocument>): String = withContext(Dispatchers.IO) { mutex.withLock {
        require(documents.isNotEmpty()) { "Keine Dateien ausgewaehlt." }
        documents.forEach { document ->
            require(document.name.substringAfterLast('.').lowercase() in setOf("map", "poly", "geojson", "json")) {
                "Nur MAP-, POLY- und GeoJSON-Dateien werden unterstuetzt."
            }
        }
        var imported = 0
        var footprints = 0
        val coverageDocuments = documents.filter { !it.name.endsWith(".map", true) }
        val coverage = coverageDocuments.map { (name, open) ->
            val bytes = open().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 2 * 1024 * 1024) { "Die Abdeckungsdatei ist zu gross." }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            name.substringBeforeLast('.').lowercase() to parseOfflineCoverage(bytes.toString(Charsets.UTF_8), name)
        }
        require(coverage.map { it.first }.distinct().size == coverage.size) { "Mehrere Abdeckungsdateien mit gleichem Namen." }
        documents.filter { it.name.endsWith(".map", true) }.forEach { (name, open) ->
            val id = UUID.randomUUID().toString()
            val temporary = File(directory, "$id.part")
            val target = File(directory, "$id.map")
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                open().use { input ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            check(directory.usableSpace > count + 32L * 1024 * 1024) { "Nicht genuegend freier Speicher fuer die Offlinekarte." }
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                        }
                    }
                }
                val checksum = digest.digest().joinToString("") { "%02x".format(it) }
                if (readPackages().none { it.checksum == checksum }) {
                    val map = MapFile(temporary)
                    val bounds = try {
                        val box = map.mapFileInfo.boundingBox
                        JSONObject().put("type", "Polygon").put("coordinates", JSONArray().put(JSONArray()
                            .put(JSONArray().put(box.minLongitude).put(box.minLatitude))
                            .put(JSONArray().put(box.maxLongitude).put(box.minLatitude))
                            .put(JSONArray().put(box.maxLongitude).put(box.maxLatitude))
                            .put(JSONArray().put(box.minLongitude).put(box.maxLatitude))
                            .put(JSONArray().put(box.minLongitude).put(box.minLatitude))))
                    } finally { map.close() }
                    validatedCoverage(bounds)
                    check(temporary.renameTo(target)) { "Offlinekarte konnte nicht uebernommen werden." }
                    val footprint = coverage.singleOrNull { it.first == name.substringBeforeLast('.').lowercase() }?.second
                    writePackage(OfflineMapPackage(id, name, target, target.length(), bounds, footprint, checksum))
                    imported++
                }
            } catch (failure: Exception) {
                if (!File(directory, "$id.json").isFile) target.delete()
                throw failure
            } finally { temporary.delete() }
        }
        coverage.forEach { (name, geometry) ->
            val matching = readPackages().filter { it.name.substringBeforeLast('.').lowercase() == name }
            require(matching.isNotEmpty()) { "Keine Kartendatei mit passendem Namen fuer $name vorhanden." }
            matching.forEach { entry ->
                require(validatedCoverage(geometry).intersects(validatedCoverage(entry.bounds))) { "Abdeckung liegt ausserhalb der Kartendatei." }
                writePackage(entry.copy(coverage = geometry))
                footprints++
            }
        }
        "$imported Offlinekarte(n) importiert, $footprints Abdeckung(en) zugeordnet."
    } }

    suspend fun delete(entry: OfflineMapPackage) = withContext(Dispatchers.IO) { mutex.withLock {
        val id = UUID.fromString(entry.id).toString()
        check(File(directory, "$id.map").let { !it.exists() || it.delete() }) { "Offlinekarte konnte nicht geloescht werden." }
        AtomicFile(File(directory, "$id.json")).delete()
    } }

    private fun writePackage(entry: OfflineMapPackage) {
        val json = JSONObject().put("id", entry.id).put("name", entry.name).put("size", entry.size)
            .put("bounds", entry.bounds).put("coverage", entry.coverage ?: JSONObject.NULL).put("checksum", entry.checksum)
        val atomic = AtomicFile(File(directory, "${entry.id}.json"))
        val output = atomic.startWrite()
        try { output.write(json.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
        catch (failure: Exception) { atomic.failWrite(output); throw failure }
    }

    companion object { private val mutex = Mutex() }
}