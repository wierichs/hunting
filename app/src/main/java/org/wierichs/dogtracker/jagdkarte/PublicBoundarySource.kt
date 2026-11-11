package org.wierichs.dogtracker.jagdkarte

import java.net.URI
import java.net.Inet6Address
import java.net.InetAddress
import java.security.MessageDigest
import java.util.UUID
import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

internal enum class PublicBoundaryService(val id: String) {
    GEOJSON("geojson"), WFS("wfs"), OGC_API("ogcapi"), ARCGIS("arcgis"), SNAPSHOT("snapshot");

    companion object {
        fun fromId(id: String): PublicBoundaryService = entries.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Unbekannter Datendiensttyp: $id")
    }
}

internal data class PublicBoundarySource(
    val id: String,
    val label: String,
    val service: PublicBoundaryService,
    val url: String,
    val nameFields: List<String> = emptyList(),
    val idFields: List<String> = emptyList(),
    val typeName: String = "",
    val outputFormat: String = "application/json",
    val srsName: String = "EPSG:4326",
    val layerId: Int = 0,
    val pageSize: Int = 500,
    val attribution: String = "",
    val license: String = "Nutzungsbedingungen beim Anbieter pruefen",
    val licenseUrl: String = "",
    val metadataUrl: String = "",
    val snapshotDate: String = "",
    val userDefined: Boolean = false
)

internal data class PublicBoundaryFeature(
    val id: String,
    val name: String,
    val geometry: JSONObject,
    val properties: JSONObject,
    val source: PublicBoundarySource
)

internal fun validatePublicBoundarySource(source: PublicBoundarySource): PublicBoundarySource {
    require(source.id.matches(Regex("[a-z0-9][a-z0-9._-]{0,79}"))) { "Ungueltige Quellenkennung." }
    require(source.label.trim().isNotEmpty() && source.label.length <= 120) { "Quellenname muss 1 bis 120 Zeichen enthalten." }
    require(source.url.length <= 2048) { "Quellen-URL ist zu lang." }
    val uri = URI(source.url.trim())
    require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) {
        "Bitte eine HTTPS-URL ohne Zugangsdaten oder Fragment angeben."
    }
    require(!uri.host.equals("localhost", true) && !uri.host.endsWith(".localhost", true) &&
        !uri.host.endsWith(".local", true)) { "Lokale Datendienst-Adressen werden nicht unterstuetzt." }
    val literalHost = uri.host.removePrefix("[").removeSuffix("]")
    if (literalHost.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}")) || literalHost.contains(':')) {
        require(isPublicBoundaryAddress(InetAddress.getByName(literalHost))) {
            "Private und lokale Datendienst-Adressen werden nicht unterstuetzt."
        }
    }
    listOf(source.licenseUrl, source.metadataUrl).filter(String::isNotBlank).forEach { value ->
        val metadata = URI(value)
        require(metadata.scheme.equals("https", true) && !metadata.host.isNullOrBlank() && metadata.userInfo == null) {
            "Lizenz- und Metadatenlinks muessen HTTPS verwenden."
        }
    }
    if (source.service == PublicBoundaryService.WFS) {
        require(source.typeName.trim().isNotEmpty() && source.typeName.length <= 200) { "WFS benoetigt einen TYPENAMES-Layer." }
        require(source.outputFormat.length <= 120 && source.srsName.length <= 40) { "Ungueltige WFS-Konfiguration." }
    }
    if (source.service == PublicBoundaryService.ARCGIS) require(source.layerId >= 0) { "Ungueltige ArcGIS-Layer-ID." }
    require(source.pageSize in 1..1000) { "Seitengroesse muss zwischen 1 und 1000 liegen." }
    require(source.nameFields.size <= 20 && source.idFields.size <= 20 &&
        (source.nameFields + source.idFields).all { it.matches(Regex("[A-Za-z_][A-Za-z0-9_.-]{0,79}")) }) {
        "Ungueltige Feldliste in der Quellenkonfiguration."
    }
    return source.copy(id = source.id.trim(), label = source.label.trim(), url = uri.toASCIIString())
}

internal fun publicBoundaryFeatureFromJson(feature: JSONObject, source: PublicBoundarySource, index: Int): PublicBoundaryFeature? {
    if (feature.optString("type") != "Feature") return null
    val properties = feature.optJSONObject("properties") ?: JSONObject()
    val rawGeometry = feature.optJSONObject("geometry") ?: return null
    if (rawGeometry.optString("type") !in setOf("Polygon", "MultiPolygon")) return null
    val name = geoJsonFeatureName(properties, "Unbenanntes Revier ${index + 1}", source.nameFields)
    require(name.length <= 200) { "Reviername darf hoechstens 200 Zeichen enthalten." }
    val (geometry, _) = normalizeGeoJsonGeometry(rawGeometry, "${source.label}, Feature ${index + 1}")
    val externalId = source.idFields.firstNotNullOfOrNull { field ->
        properties.opt(field)?.takeIf { it !is JSONObject && it !is JSONArray && it.toString().isNotBlank() }?.toString()
    } ?: feature.opt("id")?.takeIf { it !is JSONObject && it !is JSONArray && it.toString().isNotBlank() }?.toString()
    val id = externalId?.takeIf { it.length <= 240 } ?: contentId(source.id, name, geometry.toString())
    return PublicBoundaryFeature(id, name, geometry, properties, source)
}

internal fun publicBoundaryFeaturesFromJson(json: JSONObject, source: PublicBoundarySource): List<PublicBoundaryFeature> {
    val features = when (json.optString("type")) {
        "FeatureCollection" -> json.optJSONArray("features")
            ?: throw IllegalArgumentException("Der Dienst hat keine FeatureCollection geliefert.")
        "Feature" -> JSONArray().put(json)
        else -> throw IllegalArgumentException("Der Dienst muss GeoJSON Feature oder FeatureCollection liefern.")
    }
    val results = ArrayList<PublicBoundaryFeature>(features.length())
    val ids = mutableSetOf<String>()
    for (index in 0 until features.length()) {
        val feature = features.optJSONObject(index) ?: continue
        val parsed = publicBoundaryFeatureFromJson(feature, source, index) ?: continue
        if (ids.add(parsed.id)) results += parsed
    }
    return results
}

internal fun userBoundarySourceId(): String = "custom-${UUID.randomUUID()}"

internal class PublicBoundarySourceStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("public_boundary_sources", Context.MODE_PRIVATE)

    fun selectedSourceId(): String? = preferences.getString("selected_source", null)

    fun selectSource(id: String) {
        require(all().any { it.id == id })
        preferences.edit { putString("selected_source", id) }
    }

    fun all(): List<PublicBoundarySource> {
        val defaults = appContext.assets.open("public-boundary-sources.json").bufferedReader().use { reader ->
            val array = JSONArray(reader.readText())
            (0 until array.length()).map { sourceFromJson(array.getJSONObject(it), userDefined = false) }
        }
        val custom = runCatching { JSONArray(preferences.getString("custom_sources", "[]")) }
            .getOrDefault(JSONArray())
        return defaults + (0 until custom.length()).mapNotNull { index ->
            runCatching { sourceFromJson(custom.getJSONObject(index), userDefined = true) }.getOrNull()
        }.filter { it.service in setOf(PublicBoundaryService.GEOJSON, PublicBoundaryService.WFS) }
    }

    fun saveCustom(source: PublicBoundarySource) {
        require(source.service in setOf(PublicBoundaryService.GEOJSON, PublicBoundaryService.WFS)) {
            "Eigene Quellen koennen GeoJSON oder WFS verwenden."
        }
        val checked = validatePublicBoundarySource(source.copy(userDefined = true))
        require(checked.id.startsWith("custom-") && all().none { !it.userDefined && it.id == checked.id }) {
            "Eigene Quellen brauchen eine separate lokale Kennung."
        }
        val sources = runCatching { JSONArray(preferences.getString("custom_sources", "[]")) }
            .getOrDefault(JSONArray())
        val updated = JSONArray()
        var replaced = false
        for (index in 0 until sources.length()) {
            val existing = sources.optJSONObject(index) ?: continue
            if (existing.optString("id") == checked.id) {
                updated.put(sourceToJson(checked))
                replaced = true
            } else updated.put(existing)
        }
        if (!replaced) updated.put(sourceToJson(checked))
        preferences.edit { putString("custom_sources", updated.toString()) }
    }

    fun deleteCustom(id: String) {
        val existing = runCatching { JSONArray(preferences.getString("custom_sources", "[]")) }
            .getOrDefault(JSONArray())
        val updated = JSONArray()
        for (index in 0 until existing.length()) {
            val source = existing.optJSONObject(index) ?: continue
            if (source.optString("id") != id) updated.put(source)
        }
        preferences.edit { putString("custom_sources", updated.toString()) }
        if (selectedSourceId() == id) preferences.edit { remove("selected_source") }
    }

    companion object {
        fun sourceFromJson(json: JSONObject, userDefined: Boolean) = validatePublicBoundarySource(PublicBoundarySource(
            id = json.getString("id"), label = json.getString("label"),
            service = PublicBoundaryService.fromId(json.getString("type")), url = json.getString("url"),
            nameFields = json.stringList("nameFields"), idFields = json.stringList("idFields"),
            typeName = json.optString("typeName"), outputFormat = json.optString("outputFormat", "application/json"),
            srsName = json.optString("srsName", "EPSG:4326"), layerId = json.optInt("layerId"),
            pageSize = json.optInt("pageSize", 500), attribution = json.optString("attribution"),
            license = json.optString("license", "Nutzungsbedingungen beim Anbieter pruefen"),
            licenseUrl = json.optString("licenseUrl"), metadataUrl = json.optString("metadataUrl"),
            snapshotDate = json.optString("snapshotDate"), userDefined = userDefined))

        fun sourceToJson(source: PublicBoundarySource) = JSONObject().put("id", source.id).put("label", source.label)
            .put("type", source.service.id).put("url", source.url)
            .put("nameFields", JSONArray(source.nameFields)).put("idFields", JSONArray(source.idFields))
            .put("typeName", source.typeName).put("outputFormat", source.outputFormat).put("srsName", source.srsName)
            .put("layerId", source.layerId).put("pageSize", source.pageSize)
            .put("attribution", source.attribution).put("license", source.license)
            .put("licenseUrl", source.licenseUrl).put("metadataUrl", source.metadataUrl)
            .put("snapshotDate", source.snapshotDate).put("userDefined", source.userDefined)

        private fun JSONObject.stringList(key: String) = optJSONArray(key)?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
        }.orEmpty()
    }
}

private fun contentId(sourceId: String, name: String, geometry: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest("$sourceId\u0000$name\u0000$geometry".toByteArray())
    return "content-" + bytes.take(16).joinToString("") { "%02x".format(it) }
}

internal fun isPublicBoundaryAddress(address: InetAddress): Boolean =
    !address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isLinkLocalAddress &&
        !address.isSiteLocalAddress && !address.isMulticastAddress &&
        !(address is Inet6Address && address.address.first().toInt() and 0xfe == 0xfc)