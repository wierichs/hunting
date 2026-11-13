package org.wierichs.dogtracker

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.wierichs.dogtracker.communication.RelayGatewayService

internal data class HuntingPermissionState(
    val consented: Boolean = false,
    val missingPermissions: List<String> = emptyList()
) {
    val ready: Boolean get() = consented && missingPermissions.isEmpty()
}

internal fun requiredHuntingPermissions(sdk: Int = Build.VERSION.SDK_INT): List<String> =
    RelayGatewayService.requiredRelayPermissions(sdk) +
        if (sdk >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()

internal fun readHuntingPermissions(context: Context): HuntingPermissionState = HuntingPermissionState(
    consented = TrackerPreferences.huntingFunctionsConsented(context),
    missingPermissions = requiredHuntingPermissions().filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }
)

@Composable
internal fun rememberHuntingPermissionState(): MutableState<HuntingPermissionState> {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state = remember(context) { mutableStateOf(readHuntingPermissions(context)) }
    DisposableEffect(context, lifecycle) {
        val preferences = context.getSharedPreferences("tracker_settings", Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            state.value = readHuntingPermissions(context)
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) state.value = readHuntingPermissions(context)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        lifecycle.addObserver(observer)
        onDispose {
            preferences.unregisterOnSharedPreferenceChangeListener(listener)
            lifecycle.removeObserver(observer)
        }
    }
    return state
}

@Composable
internal fun HuntingPermissionSettings(
    state: HuntingPermissionState,
    onConfigure: () -> Unit,
    onDisable: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Jagdfunktionen auf diesem Geraet", style = MaterialTheme.typography.titleSmall)
        Text(when {
            state.ready -> "Automatische Jagdteilnahme und Berechtigungen eingerichtet."
            state.consented -> "Berechtigungen fehlen; Jagdfunktionen sind ausgeblendet."
            else -> "Nur Karten- und Verwaltungsfunktionen; keine automatische Standortfreigabe."
        }, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onConfigure) { Text("Berechtigungen und Freigaben einrichten") }
        if (state.consented) {
            TextButton(onClick = onDisable) { Text("Jagdfunktionen deaktivieren") }
        }
    }
}

@Composable
internal fun HuntingPermissionSetupDialog(
    state: HuntingPermissionState,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var requesting by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        requesting = false
        onRefresh()
        if (readHuntingPermissions(context).ready) {
            onDismiss()
        } else {
            result = "Berechtigungen nicht vollstaendig erteilt. Jagdfunktionen bleiben ausgeblendet. " +
                "Du kannst sie erneut anfordern oder in den Android-Einstellungen erlauben."
        }
    }
    AlertDialog(
        onDismissRequest = { if (!requesting) onDismiss() },
        title = { Text("Jagdbruder einrichten") },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Zum Ansehen und Verwalten der Jagdkarte brauchst du keine Jagdfreigaben. " +
                    "Du kannst diese Einrichtung spaeter im Benutzerkonto aendern.")
                Text("Standort", style = MaterialTheme.typography.titleSmall)
                Text("Genauer Standort wird fuer deine Position, die Reviergrenzpruefung und die Nearby-Geraetesuche benoetigt. " +
                    "Im Jagdmodus wird dein Standort bei aktiver Jagdkarte automatisch mit den berechtigten Mitgliedern des ausgewaehlten Reviers geteilt.")
                Text("Geraete in der Naehe und lokales Netzwerk", style = MaterialTheme.typography.titleSmall)
                Text("Bluetooth und, je nach Android-Version, WLAN und lokales Netzwerk erlauben den verschluesselten " +
                    "Relaisaustausch mit berechtigten Geraeten. Bevor sich zwei Handys verbinden, erscheint auf beiden eine Zahl. " +
                    "Vergleiche die Zahlen und bestaetige nur, wenn sie gleich sind.")
                Text("Benachrichtigungen", style = MaterialTheme.typography.titleSmall)
                Text("Machen aktives Tracking und den Relaisdienst sichtbar. Tracking kannst du dort beenden; das Relais gehoert zum Jagdmodus und hat keinen eigenen Stoppschalter. " +
                    "Der Revier-Relaisdienst kann im Hintergrund weiterlaufen; eigene Teilnehmerpositionen werden nur bei aktiver Karte erfasst.")
                Text("Mit der Freigabe erlaubst du die automatische Standortfreigabe und den Relaisbetrieb im Jagdmodus des ausgewaehlten Reviers. " +
                    "Es gibt keine weitere Freigabe pro Revier und keinen separaten Relais-Schalter. " +
                    "Der DogTracker startet weiterhin nur mit Tracking starten und uebernimmt dann den Relaisbetrieb. " +
                    "Ohne diese Zustimmung und alle benoetigten Berechtigungen startet keine Jagdteilnahme.")
                Text(if (state.missingPermissions.isEmpty()) "Android-Berechtigungen vorhanden."
                    else "${state.missingPermissions.size} Android-Berechtigungen fehlen.",
                    style = MaterialTheme.typography.bodySmall)
                if (result.isNotBlank()) Text(result, color = MaterialTheme.colorScheme.error)
                TextButton(enabled = !requesting, onClick = {
                    ContextCompat.startActivity(context,
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")), null)
                }) { Text("Android-Einstellungen oeffnen") }
            }
        },
        confirmButton = {
            TextButton(enabled = !requesting, onClick = {
                TrackerPreferences.setHuntingFunctionsConsent(context, true)
                onRefresh()
                val missing = readHuntingPermissions(context).missingPermissions
                if (missing.isEmpty()) onDismiss()
                else {
                    requesting = true
                    launcher.launch(requiredHuntingPermissions().toTypedArray())
                }
            }) { Text("Freigeben und einrichten") }
        },
        dismissButton = {
            TextButton(enabled = !requesting, onClick = onDismiss) { Text("Spaeter") }
        }
    )
}
