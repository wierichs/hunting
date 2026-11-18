package org.wierichs.dogtracker.jagdkarte

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit

internal object PublicBoundaryRequests {
    const val PAGE_SIZE = 500
    const val MAX_PAGES = 40
    const val MAX_RESPONSE_BYTES = 12 * 1024 * 1024
    const val MAX_TOTAL_RESPONSE_BYTES = 48 * 1024 * 1024
    const val MAX_TOTAL_TIME_MILLIS = 90_000L
    const val MAX_FEATURES = 20_000

    fun wfs(source: PublicBoundarySource, startIndex: Int): String {
        require(startIndex >= 0)
        return query(source.url, mapOf("SERVICE" to "WFS", "VERSION" to "2.0.0", "REQUEST" to "GetFeature",
            "TYPENAMES" to source.typeName, "OUTPUTFORMAT" to source.outputFormat,
            "SRSNAME" to source.srsName, "COUNT" to PAGE_SIZE.toString(), "STARTINDEX" to startIndex.toString()))
    }

    fun arcGis(source: PublicBoundarySource, offset: Int): String {
        require(offset >= 0)
        val base = source.url.trimEnd('/') + "/${source.layerId}/query"
        return query(base, mapOf("where" to "1=1", "outFields" to "*", "outSR" to "4326", "f" to "geojson",
            "resultOffset" to offset.toString(), "resultRecordCount" to source.pageSize.toString()))
    }

    fun paginationUrl(current: String, link: String, expectedOrigin: String): String {
        val resolved = URI(current).resolve(link).normalize()
        require(resolved.scheme.equals("https", true) && resolved.userInfo == null && resolved.fragment == null &&
            resolved.origin() == expectedOrigin) { "Der Datendienst verweist bei der Paginierung auf einen anderen Ursprung." }
        return resolved.toASCIIString()
    }

    fun redirectUrl(current: String, location: String, expectedOrigin: String): String {
        val resolved = URI(current).resolve(location).normalize()
        require(resolved.scheme.equals("https", true) && resolved.userInfo == null && resolved.fragment == null &&
            resolved.origin() == expectedOrigin) {
            "Der Datendienst leitet auf einen anderen oder unsicheren Ursprung weiter."
        }
        return resolved.toASCIIString()
    }

    fun origin(url: String): String = URI(url).origin()

    private fun query(url: String, parameters: Map<String, String>): String {
        val uri = URI(url)
        val pairs = uri.rawQuery.orEmpty().split('&').filter(String::isNotBlank).filterNot { pair ->
            val key = runCatching { URLDecoder.decode(pair.substringBefore('='), Charsets.UTF_8.name()) }
                .getOrDefault(pair.substringBefore('='))
            parameters.keys.any { it.equals(key, true) }
        }.toMutableList()
        parameters.forEach { (key, value) ->
            pairs += "${URLEncoder.encode(key, Charsets.UTF_8.name())}=${URLEncoder.encode(value, Charsets.UTF_8.name())}"
        }
        val base = url.substringBefore('#').substringBefore('?')
        return if (pairs.isEmpty()) base else "$base?${pairs.joinToString("&")}"
    }

    private fun URI.origin() = "${scheme.lowercase(Locale.ROOT)}://${host.lowercase(Locale.ROOT)}:${if (port == -1) 443 else port}"
}

internal class PublicBoundaryRequestBudget(
    private val nowNanos: () -> Long = System::nanoTime,
    private val startedNanos: Long = nowNanos()
) {
    private var consumedBytes = 0

    fun checkTime() {
        require(nowNanos() - startedNanos <= PublicBoundaryRequests.MAX_TOTAL_TIME_MILLIS * 1_000_000) {
            "Die Reviersuche ueberschritt das Zeitlimit von 90 Sekunden."
        }
    }

    fun consume(bytes: Int) {
        checkTime()
        require(bytes >= 0 && consumedBytes.toLong() + bytes <= PublicBoundaryRequests.MAX_TOTAL_RESPONSE_BYTES) {
            "Die gesamte Datendienst-Antwort ueberschreitet 48 MB."
        }
        consumedBytes += bytes
    }
}

internal class PublicBoundaryClient(
    addressLookup: (String) -> Array<InetAddress> = InetAddress::getAllByName
) {
    private val httpClient = OkHttpClient.Builder()
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                resolvePublicBoundaryAddresses(hostname, addressLookup)
        })
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(PublicBoundaryRequests.MAX_TOTAL_TIME_MILLIS, TimeUnit.MILLISECONDS)
        .build()

    suspend fun search(source: PublicBoundarySource, query: String = ""): List<PublicBoundaryFeature> =
        withContext(Dispatchers.IO) {
            val checked = validatePublicBoundarySource(source)
            val budget = PublicBoundaryRequestBudget()
            val features = when (checked.service) {
                PublicBoundaryService.GEOJSON, PublicBoundaryService.SNAPSHOT ->
                    publicBoundaryFeaturesFromJson(getJson(checked.url, budget), checked)
                PublicBoundaryService.WFS -> loadWfs(checked, budget)
                PublicBoundaryService.ARCGIS -> loadArcGis(checked, budget)
                PublicBoundaryService.OGC_API -> loadOgcApi(checked, budget)
            }
            filterPublicBoundaryFeatures(features, query)
        }

    private suspend fun loadWfs(source: PublicBoundarySource, budget: PublicBoundaryRequestBudget): List<PublicBoundaryFeature> {
        val features = linkedMapOf<String, PublicBoundaryFeature>()
        for (page in 0 until PublicBoundaryRequests.MAX_PAGES) {
            currentCoroutineContext().ensureActive()
            val json = getJson(PublicBoundaryRequests.wfs(source, page * PublicBoundaryRequests.PAGE_SIZE), budget)
            val pageFeatures = publicBoundaryFeaturesFromJson(json, source)
            pageFeatures.forEach { features.putIfAbsent(it.id, it) }
            ensureFeatureLimit(features.size)
            val rawCount = json.optJSONArray("features")?.length() ?: 0
            val matched = json.optString("numberMatched").toLongOrNull()
            if (rawCount < PublicBoundaryRequests.PAGE_SIZE || matched != null && matched <= (page + 1L) * PublicBoundaryRequests.PAGE_SIZE) {
                return features.values.toList()
            }
        }
        throw IllegalArgumentException("Der WFS-Datensatz ueberschreitet ${PublicBoundaryRequests.MAX_FEATURES} Features.")
    }

    private suspend fun loadArcGis(source: PublicBoundarySource, budget: PublicBoundaryRequestBudget): List<PublicBoundaryFeature> {
        val features = linkedMapOf<String, PublicBoundaryFeature>()
        for (page in 0 until PublicBoundaryRequests.MAX_PAGES) {
            currentCoroutineContext().ensureActive()
            val json = getJson(PublicBoundaryRequests.arcGis(source, page * source.pageSize), budget)
            val pageFeatures = publicBoundaryFeaturesFromJson(json, source)
            pageFeatures.forEach { features.putIfAbsent(it.id, it) }
            ensureFeatureLimit(features.size)
            val count = json.optJSONArray("features")?.length() ?: 0
            if (!json.optBoolean("exceededTransferLimit") && count < source.pageSize) return features.values.toList()
            if (count == 0) throw IllegalArgumentException("ArcGIS meldet weitere Datensaetze, liefert aber eine leere Seite.")
        }
        throw IllegalArgumentException("Der ArcGIS-Datensatz ueberschreitet ${PublicBoundaryRequests.MAX_FEATURES} Features.")
    }

    private suspend fun loadOgcApi(source: PublicBoundarySource, budget: PublicBoundaryRequestBudget): List<PublicBoundaryFeature> {
        val expectedOrigin = PublicBoundaryRequests.origin(source.url)
        val visited = mutableSetOf<String>()
        val features = linkedMapOf<String, PublicBoundaryFeature>()
        var current = source.url
        var received = 0L
        for (page in 0 until PublicBoundaryRequests.MAX_PAGES) {
            currentCoroutineContext().ensureActive()
            require(visited.add(current)) { "Der OGC-API-Dienst wiederholt einen Paginierungslink." }
            val json = getJson(current, budget)
            val rawFeatures = json.optJSONArray("features")
                ?: throw IllegalArgumentException("Die OGC-API-Antwort enthaelt keine Feature-Liste.")
            received += rawFeatures.length()
            publicBoundaryFeaturesFromJson(json, source).forEach { features.putIfAbsent(it.id, it) }
            ensureFeatureLimit(features.size)
            val matched = json.optString("numberMatched").toLongOrNull()
            if (matched != null && received >= matched) return features.values.toList()
            val next = (0 until json.optJSONArray("links").orEmptyLength()).asSequence()
                .mapNotNull { json.getJSONArray("links").optJSONObject(it) }
                .firstOrNull { it.optString("rel") == "next" }?.optString("href")
            if (next.isNullOrBlank()) {
                if (matched != null && received < matched) {
                    throw IllegalArgumentException("Unvollstaendige OGC-API-Antwort: $received von $matched Features.")
                }
                if (rawFeatures.length() >= PublicBoundaryRequests.PAGE_SIZE) {
                    throw IllegalArgumentException("OGC API liefert keinen naechsten Seitenlink.")
                }
                return features.values.toList()
            }
            current = PublicBoundaryRequests.paginationUrl(current, next, expectedOrigin)
        }
        throw IllegalArgumentException("Der OGC-API-Datensatz ueberschreitet ${PublicBoundaryRequests.MAX_FEATURES} Features.")
    }

    private suspend fun getJson(url: String, budget: PublicBoundaryRequestBudget): JSONObject {
        var target = validatePublicBoundarySource(PublicBoundarySource("request", "Request",
            PublicBoundaryService.GEOJSON, url)).url
        val expectedOrigin = PublicBoundaryRequests.origin(target)
        for (redirect in 0..3) {
            currentCoroutineContext().ensureActive()
            budget.checkTime()
            val request = Request.Builder().url(target)
                .header("Accept", "application/geo+json, application/json")
                .header("User-Agent", "Jagdkarte-Android/1.0")
                .build()
            val response = httpClient.newCall(request).execute()
            try {
                val status = response.code
                if (status in 300..399) {
                    require(redirect < 3) { "Zu viele Weiterleitungen beim Datendienst." }
                    val location = response.header("Location") ?: error("Weiterleitung ohne Zieladresse.")
                    target = PublicBoundaryRequests.redirectUrl(target, location, expectedOrigin)
                    continue
                }
                if (status !in 200..299) throw IOException("Datendienst: HTTP $status")
                val body = response.body ?: throw IOException("Der Datendienst liefert keine Antwortdaten.")
                val declaredLength = body.contentLength()
                require(declaredLength < 0 || declaredLength <= PublicBoundaryRequests.MAX_RESPONSE_BYTES) {
                    "Die Datendienst-Antwort ist groesser als 12 MB."
                }
                val bytes = ByteArrayOutputStream()
                body.byteStream().use { input ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        budget.consume(read)
                        require(bytes.size() + read <= PublicBoundaryRequests.MAX_RESPONSE_BYTES) {
                            "Die Datendienst-Antwort ist groesser als 12 MB."
                        }
                        bytes.write(buffer, 0, read)
                    }
                }
                return try { JSONObject(bytes.toString(Charsets.UTF_8.name()).removePrefix("\uFEFF")) }
                catch (_: Exception) { throw IOException("Der Datendienst liefert kein GeoJSON-JSON.") }
            } finally { response.close() }
        }
        throw IOException("Datendienst konnte nicht geladen werden.")
    }

    private fun ensureFeatureLimit(count: Int) {
        require(count <= PublicBoundaryRequests.MAX_FEATURES) { "Der Datensatz ueberschreitet ${PublicBoundaryRequests.MAX_FEATURES} Reviere." }
    }
}

private fun JSONArray?.orEmptyLength() = this?.length() ?: 0

internal fun filterPublicBoundaryFeatures(features: List<PublicBoundaryFeature>, query: String): List<PublicBoundaryFeature> {
    val normalized = normalizeBoundarySearchText(query)
    if (normalized.isBlank()) return features
    return features.filter { feature ->
        normalizeBoundarySearchText(feature.name).contains(normalized) ||
            feature.properties.keys().asSequence().mapNotNull { key ->
                feature.properties.opt(key)?.takeIf { it !is JSONObject && it !is JSONArray }?.toString()
            }.any { normalizeBoundarySearchText(it).contains(normalized) }
    }
}

private fun normalizeBoundarySearchText(value: String): String = value.trim().lowercase(Locale.ROOT)
    .replace("ä", "a").replace("ö", "o").replace("ü", "u").replace("ß", "ss")
    .replace("ae", "a").replace("oe", "o").replace("ue", "u")
    .let { Normalizer.normalize(it, Normalizer.Form.NFD) }
    .replace(Regex("\\p{Mn}+"), "")