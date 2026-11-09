package org.wierichs.dogtracker.jagdkarte

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
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
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MarkerCreateDialog(
    profile: HuntingProfile,
    canEdit: Boolean,
    coordinates: MarkerCoordinates,
    onCreate: suspend (HuntingMarkerDraft, MarkerCoordinates?) -> Unit,
    onCreated: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val types = remember(canEdit) { HuntingMarkerType.entries.filter { it.canCreate(canEdit) } }
    var type by remember { mutableStateOf(types.first()) }
    var title by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var permissionDraft by remember { mutableStateOf<HuntingMarkerDraft?>(null) }

    fun save(draft: HuntingMarkerDraft) {
        busy = true
        error = ""
        scope.launch {
            try {
                val location = if (draft.type.requiresCurrentLocation) {
                    freshLocation(context).let { MarkerCoordinates(it.latitude, it.longitude) }
                } else null
                onCreate(draft, location)
                onCreated()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "Speicherung nicht bestaetigt."
            } finally {
                busy = false
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val draft = permissionDraft
        permissionDraft = null
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true && draft != null) {
            save(draft)
        } else {
            busy = false
            error = "Fuer diese Meldung ist ein genauer Standort erforderlich."
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Markierung setzen") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(profile.name, style = MaterialTheme.typography.labelLarge)
                Text(String.format(Locale.GERMANY, "%.6f, %.6f", coordinates.latitude, coordinates.longitude),
                    style = MaterialTheme.typography.bodySmall)
                ExposedDropdownMenuBox(expanded = menuOpen, onExpandedChange = { if (!busy) menuOpen = it }) {
                    OutlinedTextField(type.label, onValueChange = {}, readOnly = true, enabled = !busy,
                        label = { Text("Markerart") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(menuOpen) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable))
                    ExposedDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        types.forEach { option ->
                            DropdownMenuItem(text = { Text(option.label) }, onClick = { type = option; menuOpen = false })
                        }
                    }
                }
                    OutlinedTextField(title, onValueChange = { title = it.take(80) }, enabled = !busy, singleLine = true,
                    label = { Text("Bezeichnung") }, placeholder = { Text(type.label) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(notes, onValueChange = { notes = it.take(300) }, enabled = !busy, minLines = 2, maxLines = 4,
                    label = { Text("Notiz") }, supportingText = { Text("${notes.length}/300") }, modifier = Modifier.fillMaxWidth())
                if (type.temporary) Text("Gueltig fuer 48 Stunden.", style = MaterialTheme.typography.bodySmall)
                if (busy) CircularProgressIndicator()
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                val draft = HuntingMarkerDraft(type, title, notes, coordinates)
                if (type.requiresCurrentLocation && ContextCompat.checkSelfPermission(context,
                        Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    busy = true
                    permissionDraft = draft
                    permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
                } else {
                    save(draft)
                }
            }) { Text("Speichern") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Abbrechen") } }
    )
    }

    @Composable
    internal fun MarkerEditDialog(
        marker: HuntingMapPoint,
        onUpdate: suspend (String, String) -> Unit,
        onDelete: suspend () -> Unit,
        onUpdated: () -> Unit,
        onDeleted: () -> Unit,
        onDismiss: () -> Unit
    ) {
        val scope = rememberCoroutineScope()
        var title by remember(marker.markerId) { mutableStateOf(marker.title) }
        var notes by remember(marker.markerId) { mutableStateOf(marker.markerNotes) }
        var confirmingDelete by remember(marker.markerId) { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf("") }

        fun save() {
            busy = true
            error = ""
            scope.launch {
                try {
                    onUpdate(title.trim(), notes.trim())
                    onUpdated()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    error = failure.message ?: "Aenderung nicht bestaetigt."
                } finally {
                    busy = false
                }
            }
        }

        fun delete() {
            busy = true
            error = ""
            scope.launch {
                try {
                    onDelete()
                    onDeleted()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    error = failure.message ?: "Loeschung nicht bestaetigt."
                } finally {
                    busy = false
                }
            }
        }

        AlertDialog(
            onDismissRequest = { if (!busy) onDismiss() },
            title = { Text(if (confirmingDelete) "Markierung loeschen?" else "Markierung bearbeiten") },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(marker.markerTypeId.orEmpty(), style = MaterialTheme.typography.labelLarge)
                    if (confirmingDelete) {
                        Text("Diese Markierung wird dauerhaft geloescht.")
                    } else {
                        OutlinedTextField(title, onValueChange = { title = it.take(80) }, enabled = !busy, singleLine = true,
                            label = { Text("Bezeichnung") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(notes, onValueChange = { notes = it.take(300) }, enabled = !busy, minLines = 2, maxLines = 4,
                            label = { Text("Notiz") }, supportingText = { Text("${notes.length}/300") }, modifier = Modifier.fillMaxWidth())
                    }
                    if (busy) CircularProgressIndicator()
                    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && (confirmingDelete || title.isNotBlank()),
                    onClick = { if (confirmingDelete) delete() else save() }) {
                    Text(if (confirmingDelete) "Endgueltig loeschen" else "Speichern")
                }
            },
            dismissButton = {
                if (confirmingDelete) {
                    TextButton(enabled = !busy, onClick = { confirmingDelete = false }) { Text("Zurueck") }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(enabled = !busy, onClick = { confirmingDelete = true }) { Text("Loeschen") }
                        TextButton(enabled = !busy, onClick = onDismiss) { Text("Schliessen") }
                    }
                }
            }
        )
    }