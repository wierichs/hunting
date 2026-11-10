package org.wierichs.dogtracker.jagdkarte

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

@Composable
internal fun MapStyleMenu(
    style: HuntingMapStyle,
    offlineAvailable: Boolean,
    aerialAvailable: Boolean,
    onStyle: (HuntingMapStyle) -> Unit,
    onManageOffline: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Icon(Icons.Default.Layers, "Kartendarstellung", Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(style.label, style = MaterialTheme.typography.labelLarge)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            HuntingMapStyle.entries.forEach { option ->
                if (option != HuntingMapStyle.OFFLINE || offlineAvailable || style == option) {
                    DropdownMenuItem(text = {
                        Column {
                            Text(option.label)
                            if (option == HuntingMapStyle.AERIAL)
                                Text(if (aerialAvailable) "Geobasis NRW" else "Fuer diese Region noch nicht verfuegbar",
                                    style = MaterialTheme.typography.labelSmall)
                        }
                    }, leadingIcon = {
                        if (style == option) Icon(Icons.Default.Check, null) else Spacer(Modifier.size(24.dp))
                    }, enabled = when (option) {
                        HuntingMapStyle.OFFLINE -> offlineAvailable
                        HuntingMapStyle.AERIAL -> aerialAvailable
                        else -> true
                    }, onClick = { expanded = false; onStyle(option) })
                }
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Offlinekarten verwalten") }, onClick = {
                expanded = false; onManageOffline()
            })
        }
    }
}

@Composable
internal fun OfflineMapsDialog(boundary: JSONObject?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember { OfflineMapStore(context) }
    val scope = rememberCoroutineScope()
    var packages by remember { mutableStateOf(emptyList<OfflineMapPackage>()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<OfflineMapPackage?>(null) }
    LaunchedEffect(Unit) { packages = store.packages() }
    val covering = remember(packages, boundary?.toString()) { coveringOfflineMaps(packages, boundary).map { it.id }.toSet() }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            busy = true
            message = "Kartenimport laeuft ..."
            scope.launch {
                try { message = store.importDocuments(uris) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { message = failure.message ?: "Kartenimport fehlgeschlagen." }
                finally { packages = store.packages(); busy = false }
            }
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Offlinekarten") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (packages.isEmpty()) Text("Keine Offlinekarten installiert.")
            packages.forEach { entry ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(entry.name, style = MaterialTheme.typography.titleSmall)
                        Text(String.format(Locale.GERMANY, "%.1f MiB", entry.size / (1024.0 * 1024)), style = MaterialTheme.typography.bodySmall)
                        Text(when {
                            entry.coverage == null -> "Extraktgrenze fehlt"
                            entry.id in covering -> "Revier abgedeckt"
                            boundary == null -> "Kein Revier ausgewaehlt"
                            else -> "Revier nicht vollstaendig abgedeckt"
                        }, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { pendingDelete = entry }, enabled = !busy) {
                        Icon(Icons.Default.Delete, "Offlinekarte ${entry.name} loeschen")
                    }
                }
            }
            TextButton(onClick = { importer.launch(arrayOf("*/*")) }, enabled = !busy) {
                Text("MAP / Extraktgrenze importieren")
            }
            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://download.mapsforge.org/maps/v5/"))) }, enabled = !busy) { Text("Mapsforge-Downloads") }
            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://download.mapsforge.org/maps/poly/"))) }, enabled = !busy) { Text("Extraktgrenzen (.poly)") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Schliessen") } })
    pendingDelete?.let { entry ->
        AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text("Offlinekarte loeschen?") },
            text = { Text(entry.name) }, confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null; busy = true
                    scope.launch {
                        try { store.delete(entry); message = "Offlinekarte geloescht." }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { message = failure.message ?: "Loeschen fehlgeschlagen." }
                        finally { packages = store.packages(); busy = false }
                    }
                }) { Text("Loeschen") }
            }, dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Abbrechen") } })
    }
}