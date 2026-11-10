package org.wierichs.dogtracker.jagdkarte

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.wierichs.dogtracker.communication.RelayGatewayStatus

@Composable
internal fun RelayGatewayControls(
    canStart: Boolean,
    state: RelayGatewayStatus,
    onEnable: () -> Unit,
    onStop: () -> Unit
) {
    var consent by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Relaisfreigabe", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Switch(state.active || state.starting, onCheckedChange = { enabled ->
                if (enabled) consent = true else onStop()
            }, enabled = !state.starting && (canStart || state.active))
        }
        if (state.status.isNotBlank()) Text(state.status, style = MaterialTheme.typography.bodySmall, maxLines = 3)
        if (state.active) Text("${state.pending} ausstehend · ${state.confirmed} vom Server bestaetigt",
            style = MaterialTheme.typography.bodySmall)
    }
    if (consent) {
        AlertDialog(onDismissRequest = { consent = false }, title = { Text("Relaisfreigabe erlauben?") },
            text = { Text("Dieses Geraet darf verschluesselte Standortmeldungen des ausgewaehlten Reviers speichern und weiterleiten. " +
                "Der Dienst bleibt mit einer Benachrichtigung aktiv. Neue Funkverbindungen muessen auf beiden Geraeten bestaetigt werden. " +
                "Dein eigener Standort bleibt separat freigabepflichtig.") },
            confirmButton = { TextButton(enabled = canStart, onClick = { consent = false; onEnable() }) { Text("Erlauben") } },
            dismissButton = { TextButton(onClick = { consent = false }) { Text("Abbrechen") } })
    }
}