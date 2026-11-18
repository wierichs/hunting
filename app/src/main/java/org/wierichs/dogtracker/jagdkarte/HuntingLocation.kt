package org.wierichs.dogtracker.jagdkarte

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.io.geojson.GeoJsonReader
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal fun locationInsideBoundary(boundary: JSONObject?, latitude: Double, longitude: Double): Boolean {
    if (boundary == null || !latitude.isFinite() || !longitude.isFinite() ||
        latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return false
    return runCatching {
        if (boundary.optString("type") !in setOf("Polygon", "MultiPolygon")) return false
        val geometry = GeoJsonReader().read(boundary.toString())
        geometry.isValid && !geometry.isEmpty &&
            geometry.covers(GeometryFactory().createPoint(Coordinate(longitude, latitude)))
    }.getOrDefault(false)
}

internal suspend fun freshLocation(context: Context): Location = withTimeoutOrNull(20_000) {
    check(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
        "Genauer Standort ist nicht freigegeben."
    }
    suspendCancellableCoroutine { continuation ->
        val cancellation = CancellationTokenSource()
        continuation.invokeOnCancellation { cancellation.cancel() }
        LocationServices.getFusedLocationProviderClient(context)
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellation.token)
            .addOnSuccessListener { location ->
                if (!continuation.isActive) return@addOnSuccessListener
                val age = location?.let { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos }
                if (location == null || age == null || age !in 0L..30_000_000_000L ||
                    !location.hasAccuracy() || !location.accuracy.isFinite()) {
                    continuation.resumeWithException(IllegalStateException("Kein aktueller GPS-Standort verfuegbar."))
                } else {
                    continuation.resume(location)
                }
            }
            .addOnFailureListener { error -> if (continuation.isActive) continuation.resumeWithException(error) }
            .addOnCanceledListener { continuation.cancel() }
    }
} ?: error("Kein aktueller GPS-Standort verfuegbar.")