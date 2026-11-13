package org.wierichs.dogtracker.jagdkarte

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import org.wierichs.dogtracker.communication.RelayGatewayStatus

@Composable
internal fun HuntingModeControls(
    permissionsReady: Boolean,
    canEdit: Boolean,
    huntingMode: Boolean,
    pending: Boolean,
    onModeChanged: (Boolean) -> Unit
) {
    if (permissionsReady) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Jagdmodus", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            if (canEdit) Switch(huntingMode, onCheckedChange = onModeChanged, enabled = !pending)
            else Text(if (huntingMode) "Aktiv" else "Aus")
        }
    }
}

@Composable
internal fun HuntingParticipationStatus(
    huntingMode: Boolean,
    profileName: String,
    state: RelayGatewayStatus,
    trackerActive: Boolean
) {
    Column(Modifier.fillMaxWidth()) {
        Text("Jagdteilnahme: $profileName", style = MaterialTheme.typography.titleSmall)
        Text(when {
            !huntingMode -> "Jagdmodus aus: keine automatische Standortfreigabe und kein Revier-Relais."
            trackerActive -> "Der DogTracker uebernimmt den Relaisbetrieb; die eigene Teilnehmerposition wird nicht parallel geteilt."
            else -> "Standortfreigabe und Relais laufen im Jagdmodus automatisch. Eigene Positionen werden nur bei aktiver Karte erfasst."
        }, style = MaterialTheme.typography.bodySmall)
        if (huntingMode && !trackerActive) Text(when {
            state.active -> "Relais aktiv"
            state.starting -> "Relais wird vorbereitet."
            else -> "Relais wartet auf Berechtigungs- und Serverpruefung; erneuter Start erfolgt automatisch."
        }, style = MaterialTheme.typography.bodySmall)
        if (state.status.isNotBlank()) Text(state.status, style = MaterialTheme.typography.bodySmall, maxLines = 3)
        if (state.active) Text("${state.pending} ausstehend · ${state.confirmed} vom Server bestaetigt",
            style = MaterialTheme.typography.bodySmall)
    }
}