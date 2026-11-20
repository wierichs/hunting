package org.wierichs.dogtracker.jagdkarte

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.locationtech.jts.io.geojson.GeoJsonReader
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

internal fun locationProfileBoundary(latitude: Double, longitude: Double): JSONObject {
    require(latitude.isFinite() && longitude.isFinite() &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0) {
        "Ungueltige Standortkoordinaten."
    }
    val latitudeRadians = Math.toRadians(latitude)
    val longitudeRadians = Math.toRadians(longitude)
    val angularRadius = 100.0 / 6_371_000.0
    val ring = JSONArray()
    repeat(64) { index ->
        val bearing = 2 * Math.PI * index / 64
        val targetLatitude = asin(
            (sin(latitudeRadians) * cos(angularRadius) +
                cos(latitudeRadians) * sin(angularRadius) * cos(bearing)).coerceIn(-1.0, 1.0)
        )
        val targetLongitude = longitudeRadians + atan2(
            sin(bearing) * sin(angularRadius) * cos(latitudeRadians),
            cos(angularRadius) - sin(latitudeRadians) * sin(targetLatitude)
        )
        ring.put(JSONArray().put((Math.toDegrees(targetLongitude) + 540) % 360 - 180)
            .put(Math.toDegrees(targetLatitude)))
    }
    ring.put(JSONArray(ring.getJSONArray(0).toString()))
    val boundary = JSONObject().put("type", "Polygon")
        .put("coordinates", JSONArray().put(ring))
    val geometry = GeoJsonReader().read(boundary.toString())
    require(geometry.isValid && geometry.envelopeInternal.width < 180 &&
        locationInsideBoundary(boundary, latitude, longitude)) {
        "An diesem Standort kann keine Kreisgrenze angelegt werden."
    }
    return boundary
}

@Composable
internal fun LocationProfileDialog(
    onCreate: suspend (String, MarkerCoordinates) -> String,
    onCreated: (String) -> Unit,
    onDismiss: () -> Unit,
    locate: (suspend () -> MarkerCoordinates)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    fun create() {
        busy = true
        error = ""
        scope.launch {
            try {
                status = "Aktueller Standort wird ermittelt ..."
                val location = locate?.invoke() ?: freshLocation(context).let {
                    MarkerCoordinates(it.latitude, it.longitude)
                }
                status = "Revierprofil wird angelegt ..."
                val id = onCreate(name.trim(), location)
                onCreated(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "Revierprofil konnte nicht angelegt werden."
            } finally {
                busy = false
                status = ""
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) create()
        else {
            busy = false
            error = "Fuer das Anlegen ist ein genauer Standort erforderlich."
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Revier um Standort erstellen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Erstellt ein eigenes Profil mit 100 m Radius um deinen aktuellen Standort. " +
                    "Die Grenze kannst du danach bearbeiten. Sie ist keine amtliche Reviergrenze " +
                    "und erteilt keine Jagderlaubnis. Das Anlegen benoetigt Internet.")
                OutlinedTextField(name, onValueChange = { name = it.take(200) },
                    label = { Text("Reviername") }, singleLine = true, enabled = !busy,
                    modifier = Modifier.fillMaxWidth())
                if (busy) CircularProgressIndicator()
                if (status.isNotBlank()) Text(status)
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && name.isNotBlank(), onClick = {
                if (locate != null || androidx.core.content.ContextCompat.checkSelfPermission(
                        context, Manifest.permission.ACCESS_FINE_LOCATION
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED) create()
                else {
                    busy = true
                    error = ""
                    permissionLauncher.launch(arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
                    ))
                }
            }) { Text("Erstellen") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("Abbrechen") }
        }
    )
}
