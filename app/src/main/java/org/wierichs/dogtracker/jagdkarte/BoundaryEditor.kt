package org.wierichs.dogtracker.jagdkarte

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal data class BoundaryMapEdit(
    val polygon: Int,
    val ring: Int,
    val vertex: Int,
    val drawingPoints: List<MarkerCoordinates>?,
    val enabled: Boolean,
    val onSelect: (Int) -> Unit,
    val onMove: (Int, MarkerCoordinates) -> Unit,
    val onInsert: (Int, MarkerCoordinates) -> Unit,
    val onPlace: (MarkerCoordinates) -> Unit
)

@Composable
internal fun BoundaryEditor(
    snapshot: HuntingSnapshot,
    onSave: suspend (BoundaryDraft, BoundaryDraft) -> Unit,
    onSaved: () -> Unit,
    onDismiss: () -> Unit
) {
    val original = remember(snapshot.profile.id) { BoundaryDraft.fromGeometry(requireNotNull(snapshot.profile.boundary)) }
    var draft by remember { mutableStateOf(original) }
    var undo by remember { mutableStateOf(emptyList<BoundaryDraft>()) }
    var redo by remember { mutableStateOf(emptyList<BoundaryDraft>()) }
    var polygon by remember { mutableStateOf(0) }
    var ring by remember { mutableStateOf(0) }
    var vertex by remember { mutableStateOf(0) }
    var drawing by remember { mutableStateOf<List<MarkerCoordinates>?>(null) }
    var drawingHole by remember { mutableStateOf(false) }
    var selectionOpen by remember { mutableStateOf(false) }
    var addOpen by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmSave by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val changed = draft != original || drawing != null
    val validityError = remember(draft) { runCatching { draft.validatedGeometry() }.exceptionOrNull()?.message }
    fun selectStart() { polygon = 0; ring = 0; vertex = 0 }
    fun change(updated: BoundaryDraft) {
        if (updated != draft) {
            undo = (undo + draft).takeLast(50)
            redo = emptyList()
            draft = updated
            error = ""
        }
    }
    fun dismiss() {
        if (!busy) {
            if (changed) confirmDiscard = true else onDismiss()
        }
    }
    val points = draft.polygons[polygon][ring]
    val outsideMarkers = remember(draft) {
        val geometry = runCatching { draft.validatedGeometry() }.getOrNull()
        if (geometry == null) 0 else snapshot.points.count { point ->
            point.kind == MapPointKind.FACILITY && !locationInsideBoundary(geometry, point.latitude, point.longitude)
        }
    }
    Dialog(onDismissRequest = ::dismiss, properties = DialogProperties(
        usePlatformDefaultWidth = false, dismissOnClickOutside = false
    )) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    BoundaryTool("Grenzbearbeitung schliessen", enabled = !busy, onClick = ::dismiss) {
                        Icon(Icons.Default.Close, null)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Reviergrenze bearbeiten", style = MaterialTheme.typography.titleMedium)
                        Text(snapshot.profile.name, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                    if (busy) CircularProgressIndicator(Modifier.size(24.dp))
                    BoundaryTool("Grenze speichern", enabled = !busy && draft != original && drawing == null && validityError == null,
                        onClick = { confirmSave = true }) { Icon(Icons.Default.Save, null) }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        TextButton(onClick = { selectionOpen = true }, enabled = !busy && drawing == null) {
                            Text("Flaeche ${polygon + 1}: ${if (ring == 0) "Aussengrenze" else "Befriedet $ring"}",
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            Icon(Icons.Default.ArrowDropDown, null)
                        }
                        DropdownMenu(selectionOpen, onDismissRequest = { selectionOpen = false },
                            modifier = Modifier.heightIn(max = 320.dp)) {
                            draft.polygons.forEachIndexed { polygonIndex, rings ->
                                rings.indices.forEach { ringIndex ->
                                    DropdownMenuItem(text = {
                                        Text("Flaeche ${polygonIndex + 1}: ${if (ringIndex == 0) "Aussengrenze" else "Befriedet $ringIndex"}")
                                    }, onClick = {
                                        polygon = polygonIndex; ring = ringIndex; vertex = 0; selectionOpen = false
                                    })
                                }
                            }
                        }
                    }
                    Box {
                        BoundaryTool("Flaeche hinzufuegen", enabled = !busy && drawing == null, onClick = { addOpen = true }) {
                            Icon(Icons.Default.Add, null)
                        }
                        DropdownMenu(addOpen, onDismissRequest = { addOpen = false }) {
                            DropdownMenuItem(text = { Text("Neue Teilflaeche") }, onClick = {
                                drawingHole = false; drawing = emptyList(); addOpen = false; error = ""
                            })
                            DropdownMenuItem(text = { Text("Befriedeten Bereich hinzufuegen") }, onClick = {
                                drawingHole = true; drawing = emptyList(); addOpen = false; error = ""
                            })
                        }
                    }
                    BoundaryTool("Ausgewaehlte Flaeche loeschen", enabled = !busy && drawing == null &&
                        (ring > 0 || draft.polygons.size > 1), onClick = { confirmDelete = true }) {
                        Icon(Icons.Default.DeleteOutline, null)
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val drawingPoints = drawing
                    if (drawingPoints == null) {
                        BoundaryTool("Vorheriger Grenzpunkt", enabled = !busy, onClick = {
                            vertex = (vertex + points.size - 1) % points.size
                        }) { Icon(Icons.Default.ChevronLeft, null) }
                        Text("Punkt\n${vertex + 1}/${points.size}", Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
                            maxLines = 2, softWrap = false)
                        BoundaryTool("Naechster Grenzpunkt", enabled = !busy, onClick = {
                            vertex = (vertex + 1) % points.size
                        }) { Icon(Icons.Default.ChevronRight, null) }
                        BoundaryTool("Grenzpunkt einfuegen", enabled = !busy, onClick = {
                            val point = points[vertex]
                            val next = points[(vertex + 1) % points.size]
                            change(draft.insertAfter(polygon, ring, vertex,
                                MarkerCoordinates((point.latitude + next.latitude) / 2, (point.longitude + next.longitude) / 2)))
                            vertex++
                        }) { Icon(Icons.Default.AddLocationAlt, null) }
                        BoundaryTool("Grenzpunkt loeschen", enabled = !busy && points.size > 3, onClick = {
                            change(draft.removeVertex(polygon, ring, vertex))
                            vertex = vertex.coerceAtMost(points.size - 2)
                        }) { Icon(Icons.Default.Delete, null) }
                    } else {
                        Text(if (drawingHole) "Befriedet: ${drawingPoints.size} Punkte" else "Teilflaeche: ${drawingPoints.size} Punkte",
                            Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.labelMedium)
                        BoundaryTool("Letzten neuen Punkt entfernen", enabled = drawingPoints.isNotEmpty(), onClick = {
                            drawing = drawingPoints.dropLast(1)
                        }) { Icon(Icons.Default.Undo, null) }
                        BoundaryTool("Neue Flaeche abbrechen", onClick = { drawing = null; error = "" }) {
                            Icon(Icons.Default.Close, null)
                        }
                        BoundaryTool("Neue Flaeche uebernehmen", enabled = drawingPoints.size >= 3, onClick = {
                            val updated = if (drawingHole) draft.addHole(polygon, drawingPoints) else draft.addPolygon(drawingPoints)
                            runCatching { updated.validatedGeometry() }.onSuccess {
                                change(updated)
                                if (drawingHole) ring = updated.polygons[polygon].lastIndex
                                else { polygon = updated.polygons.lastIndex; ring = 0 }
                                vertex = 0; drawing = null
                            }.onFailure { error = it.message ?: "Ungueltige Flaeche." }
                        }) { Icon(Icons.Default.Check, null) }
                    }
                    BoundaryTool("Grenzaenderung rueckgaengig", enabled = !busy && drawing == null && undo.isNotEmpty(), onClick = {
                        redo = redo + draft; draft = undo.last(); undo = undo.dropLast(1); selectStart(); error = ""
                    }) { Icon(Icons.Default.Undo, null) }
                    BoundaryTool("Grenzaenderung wiederholen", enabled = !busy && drawing == null && redo.isNotEmpty(), onClick = {
                        undo = undo + draft; draft = redo.last(); redo = redo.dropLast(1); selectStart(); error = ""
                    }) { Icon(Icons.Default.Redo, null) }
                }
                val message = error.ifBlank { validityError.orEmpty() }
                if (message.isNotBlank()) Text(message, Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                NativeHuntingMap(snapshot.copy(profile = snapshot.profile.copy(boundary = draft.geometry())), Instant.now(),
                    Modifier.weight(1f).fillMaxWidth(), onChooseLocation = { false },
                    boundaryEdit = BoundaryMapEdit(polygon, ring, vertex, drawing, !busy,
                        onSelect = { vertex = it },
                        onMove = { index, point -> change(draft.move(polygon, ring, index, point)); vertex = index },
                        onInsert = { index, point -> change(draft.insertAfter(polygon, ring, index, point)); vertex = index + 1 },
                        onPlace = { point ->
                            val newPoints = drawing
                            if (newPoints != null) drawing = newPoints + point
                            else change(draft.move(polygon, ring, vertex, point))
                        }))
            }
        }
        if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false },
            title = { Text("Grenzaenderungen verwerfen?") }, text = { Text("Nicht gespeicherte Aenderungen gehen verloren.") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Verwerfen") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Weiter bearbeiten") } })
        if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
            title = { Text(if (ring == 0) "Teilflaeche entfernen?" else "Befriedeten Bereich entfernen?") },
            text = { Text(if (ring == 0) "Die Teilflaeche und ihre befriedeten Bereiche werden aus dem Entwurf entfernt."
                else "Der Bereich wird wieder Teil der Revierflaeche.") },
            confirmButton = { TextButton(onClick = {
                change(if (ring == 0) draft.removePolygon(polygon) else draft.removeRing(polygon, ring))
                selectStart(); confirmDelete = false
            }) { Text("Entfernen") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } })
        if (confirmSave) AlertDialog(onDismissRequest = { confirmSave = false },
            title = { Text("Reviergrenze speichern?") },
            text = { Text("Die Grenze wird fuer alle Mitglieder dieses Reviers geaendert. Markierungen bleiben unveraendert." +
                if (outsideMarkers > 0) " $outsideMarkers Markierung(en) liegen danach ausserhalb der Grenze oder in befriedeten Bereichen." else "") },
            confirmButton = { TextButton(onClick = {
                confirmSave = false; busy = true; error = ""
                scope.launch {
                    try {
                        onSave(original, draft)
                        onSaved()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        error = failure.message ?: "Reviergrenze konnte nicht gespeichert werden."
                    } finally { busy = false }
                }
            }) { Text("Grenze speichern") } },
            dismissButton = { TextButton(onClick = { confirmSave = false }) { Text("Abbrechen") } })
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun BoundaryTool(label: String, enabled: Boolean = true, onClick: () -> Unit, content: @Composable () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        IconButton(onClick = onClick, enabled = enabled,
            modifier = Modifier.semantics { contentDescription = label }) { content() }
    }
}