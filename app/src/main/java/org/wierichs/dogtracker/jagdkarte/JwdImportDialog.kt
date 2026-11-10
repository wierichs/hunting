package org.wierichs.dogtracker.jagdkarte

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RevierImportDialog(
    candidates: List<JwdImportedProfile>,
    mergeTargets: List<HuntingProfile> = emptyList(),
    suggestedMergeTargets: Map<String, String> = emptyMap(),
    onImport: suspend (JwdImportedProfile, HuntingProfile?) -> String,
    onImported: (String, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var selectedIndex by remember(candidates) { mutableIntStateOf(0) }
    var featureMenuOpen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val firstCandidate = candidates.first()
    val firstSuggestion = suggestedMergeTargets[jwdProfileNameKey(firstCandidate.name)]
        ?.takeIf { id -> mergeTargets.any { it.id == id } }
    var mergeIntoExisting by remember(candidates, suggestedMergeTargets) {
        mutableStateOf(firstSuggestion != null)
    }
    var selectedProfileId by remember(mergeTargets, candidates, suggestedMergeTargets) {
        mutableStateOf(firstSuggestion ?: mergeTargets.firstOrNull()?.id)
    }
    val imported = candidates[selectedIndex.coerceIn(candidates.indices)]
    val selectedProfile = mergeTargets.firstOrNull { it.id == selectedProfileId }
    val hasMultipleCandidates = candidates.size > 1

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (hasMultipleCandidates) "Revierflaeche auswaehlen" else "Revierdatei importieren?") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (hasMultipleCandidates) {
                    ExposedDropdownMenuBox(expanded = featureMenuOpen, onExpandedChange = { if (!busy) featureMenuOpen = it }) {
                        OutlinedTextField(imported.name, onValueChange = {}, readOnly = true, enabled = !busy,
                            label = { Text("GeoJSON-Feature") },
                            supportingText = { Text("${selectedIndex + 1} von ${candidates.size}") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(featureMenuOpen) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable))
                        ExposedDropdownMenu(expanded = featureMenuOpen, onDismissRequest = { featureMenuOpen = false }) {
                            candidates.forEachIndexed { index, candidate ->
                                DropdownMenuItem(text = { Text("${index + 1}. ${candidate.name}") }, onClick = {
                                    selectedIndex = index
                                    val suggestion = suggestedMergeTargets[jwdProfileNameKey(candidate.name)]
                                        ?.takeIf { id -> mergeTargets.any { it.id == id } }
                                    mergeIntoExisting = suggestion != null
                                    selectedProfileId = suggestion ?: mergeTargets.firstOrNull()?.id
                                    featureMenuOpen = false
                                })
                            }
                        }
                    }
                }
                Text(imported.name, style = MaterialTheme.typography.titleMedium)
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth().selectable(
                        selected = !mergeIntoExisting, onClick = { if (!busy) mergeIntoExisting = false },
                        role = Role.RadioButton)) {
                        RadioButton(selected = !mergeIntoExisting, onClick = null)
                        Text("Neues Profil")
                    }
                    if (mergeTargets.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth().selectable(
                            selected = mergeIntoExisting, onClick = { if (!busy) mergeIntoExisting = true },
                            role = Role.RadioButton)) {
                            RadioButton(selected = mergeIntoExisting, onClick = null)
                            Text("In bestehendes Revier zusammenführen")
                        }
                    }
                }
                if (mergeIntoExisting && mergeTargets.isNotEmpty()) {
                    ExposedDropdownMenuBox(expanded = featureMenuOpen,
                        onExpandedChange = { if (!busy) featureMenuOpen = it }) {
                        OutlinedTextField(selectedProfile?.name.orEmpty(), onValueChange = {}, readOnly = true,
                            enabled = !busy, label = { Text("Zielrevier") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(featureMenuOpen) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable))
                        ExposedDropdownMenu(expanded = featureMenuOpen, onDismissRequest = { featureMenuOpen = false }) {
                            mergeTargets.forEach { profile ->
                                DropdownMenuItem(text = { Text(profile.name) }, onClick = {
                                    selectedProfileId = profile.id
                                    featureMenuOpen = false
                                })
                            }
                        }
                    }
                    Text("Grenzen werden vereinigt. Vorhandene Marker bleiben erhalten; bereits vorhandene dauerhafte Einrichtungen werden nicht doppelt importiert.",
                        style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("Es wird ein neues eigenes Revierprofil angelegt.")
                }
                Text("${imported.facilities.size} dauerhafte Einrichtung(en)")
                Text("${imported.pacifiedAreaCount} befriedete(r) Bereich(e)")
                if (imported.untitledFacilityCount > 0) {
                    Text("${imported.untitledFacilityCount} Einrichtung(en) ohne Namen erhalten einen automatisch erzeugten Namen.")
                }
                if (imported.unknownTypeCount > 0) {
                    Text("${imported.unknownTypeCount} unbekannte(r) Markertyp(en) werden als Sonstiges importiert; der Originaltyp bleibt in der Notiz.")
                }
                if (imported.outsideBoundaryFacilityCount > 0) {
                    Text("${imported.outsideBoundaryFacilityCount} Einrichtung(en) liegen ausserhalb der Reviergrenze oder in einem befriedeten Bereich und werden trotzdem importiert.",
                        color = MaterialTheme.colorScheme.error)
                }
                Text("Temporäre Markierungen werden nicht importiert.", style = MaterialTheme.typography.bodySmall)
                if (busy) CircularProgressIndicator()
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && (!mergeIntoExisting || selectedProfile != null), onClick = {
                busy = true
                error = ""
                scope.launch {
                    try {
                        onImported(onImport(imported, if (mergeIntoExisting) selectedProfile else null), mergeIntoExisting)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        error = failure.message ?: "JWD-Revier konnte nicht importiert werden."
                    } finally {
                        busy = false
                    }
                }
            }) { Text(if (mergeIntoExisting) "Zusammenführen" else "Profil importieren") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("Abbrechen") }
        }
    )
}