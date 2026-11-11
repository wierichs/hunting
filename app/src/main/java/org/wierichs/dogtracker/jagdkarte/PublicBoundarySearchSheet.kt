package org.wierichs.dogtracker.jagdkarte

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private enum class PublicSearchPage { SEARCH, SOURCES, EDIT_SOURCE, DELETE_SOURCE, FEATURE_DETAILS, OFFLINE_BOUNDARIES, CONFIRM_OFFLINE_SAVE }

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun PublicBoundarySearchSheet(
    signedIn: Boolean,
    onSignIn: (PublicBoundarySource, PublicBoundaryFeature) -> Unit,
    onPreview: (PublicBoundaryFeature) -> Unit,
    onCreateProfile: suspend (PublicBoundarySource, PublicBoundaryFeature) -> Unit,
    search: suspend (PublicBoundarySource, String) -> List<PublicBoundaryFeature> = PublicBoundaryClient()::search,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val store = remember { PublicBoundarySourceStore(context) }
    val offlineStore = remember { OfflinePublicBoundaryStore(context) }
    val scope = rememberCoroutineScope()
    var sources by remember { mutableStateOf(emptyList<PublicBoundarySource>()) }
    var selectedSourceId by remember { mutableStateOf<String?>(store.selectedSourceId()) }
    var page by remember { mutableStateOf(PublicSearchPage.SEARCH) }
    var editingSource by remember { mutableStateOf<PublicBoundarySource?>(null) }
    var deletingSource by remember { mutableStateOf<PublicBoundarySource?>(null) }
    var sourceMenuOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<PublicBoundaryFeature>()) }
    var selectedFeature by remember { mutableStateOf<PublicBoundaryFeature?>(null) }
    var pendingOfflineSave by remember { mutableStateOf<PublicBoundaryFeature?>(null) }
    var licenseConfirmed by remember { mutableStateOf(false) }
    var savedBoundaries by remember { mutableStateOf(emptyList<SavedPublicBoundary>()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var formError by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var service by remember { mutableStateOf(PublicBoundaryService.GEOJSON) }
    var typeName by remember { mutableStateOf("") }
    var nameFields by remember { mutableStateOf("") }
    var idFields by remember { mutableStateOf("") }
    var attribution by remember { mutableStateOf("") }
    var license by remember { mutableStateOf("Nutzungsbedingungen beim Anbieter pruefen") }
    var licenseUrl by remember { mutableStateOf("") }
    var metadataUrl by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        sources = runCatching { store.all() }.getOrElse {
            message = it.message ?: "Quellenliste konnte nicht geladen werden."
            emptyList()
        }
        if (sources.none { it.id == selectedSourceId }) selectedSourceId = sources.firstOrNull()?.id
        savedBoundaries = offlineStore.all()
    }
    LaunchedEffect(page, editingSource?.id) {
        if (page != PublicSearchPage.EDIT_SOURCE) return@LaunchedEffect
        val source = editingSource
        label = source?.label.orEmpty()
        url = source?.url.orEmpty()
        service = source?.service ?: PublicBoundaryService.GEOJSON
        typeName = source?.typeName.orEmpty()
        nameFields = source?.nameFields?.joinToString(", ").orEmpty()
        idFields = source?.idFields?.joinToString(", ").orEmpty()
        attribution = source?.attribution.orEmpty()
        license = source?.license ?: "Nutzungsbedingungen beim Anbieter pruefen"
        licenseUrl = source?.licenseUrl.orEmpty()
        metadataUrl = source?.metadataUrl.orEmpty()
        formError = ""
    }
    val source = sources.firstOrNull { it.id == selectedSourceId }

    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 760.dp).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (page != PublicSearchPage.SEARCH) {
                    IconButton(onClick = {
                        page = when (page) {
                            PublicSearchPage.SEARCH -> PublicSearchPage.SEARCH
                            PublicSearchPage.SOURCES -> PublicSearchPage.SEARCH
                            PublicSearchPage.EDIT_SOURCE, PublicSearchPage.DELETE_SOURCE -> PublicSearchPage.SOURCES
                            PublicSearchPage.FEATURE_DETAILS -> PublicSearchPage.SEARCH
                            PublicSearchPage.OFFLINE_BOUNDARIES -> PublicSearchPage.SEARCH
                            PublicSearchPage.CONFIRM_OFFLINE_SAVE -> if (selectedFeature != null)
                                PublicSearchPage.FEATURE_DETAILS else PublicSearchPage.SEARCH
                        }
                    }) { Icon(Icons.Default.ArrowBack, "Zurueck") }
                }
                Column(Modifier.weight(1f)) {
                    Text(when (page) {
                        PublicSearchPage.SEARCH -> "Oeffentliche Reviersuche"
                        PublicSearchPage.SOURCES -> "Suchquellen"
                        PublicSearchPage.EDIT_SOURCE -> if (editingSource == null) "Quelle hinzufuegen" else "Quelle bearbeiten"
                        PublicSearchPage.DELETE_SOURCE -> "Suchquelle loeschen?"
                        PublicSearchPage.FEATURE_DETAILS -> selectedFeature?.name ?: "Revierdetails"
                        PublicSearchPage.OFFLINE_BOUNDARIES -> "Offline-Grenzen"
                        PublicSearchPage.CONFIRM_OFFLINE_SAVE -> "Offline-Grenze speichern"
                    }, style = MaterialTheme.typography.titleLarge)
                    if (page == PublicSearchPage.SEARCH) Text("GeoJSON und Geodienste", style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = onDismiss, modifier = Modifier.semantics { contentDescription = "Reviersuche schliessen" }) {
                    Icon(Icons.Default.Close, null)
                }
            }
            if (page == PublicSearchPage.SEARCH && savedBoundaries.isNotEmpty()) {
                TextButton(onClick = { page = PublicSearchPage.OFFLINE_BOUNDARIES }) {
                    Text("Offline-Grenzen (${savedBoundaries.size})")
                }
            }
            when (page) {
                PublicSearchPage.SEARCH -> {
                    Box {
                        OutlinedButton(onClick = { sourceMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(source?.label ?: "Quelle auswaehlen", Modifier.weight(1f), maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Icon(Icons.Default.ArrowDropDown, null)
                        }
                        DropdownMenu(sourceMenuOpen, onDismissRequest = { sourceMenuOpen = false },
                            modifier = Modifier.heightIn(max = 360.dp)) {
                            sources.forEach { option ->
                                DropdownMenuItem(text = {
                                    Column {
                                        Text(option.label)
                                        Text("${option.service.label} · ${option.license}", style = MaterialTheme.typography.labelSmall)
                                    }
                                }, onClick = {
                                    selectedSourceId = option.id
                                    store.selectSource(option.id)
                                    sourceMenuOpen = false
                                    results = emptyList()
                                    message = ""
                                })
                            }
                        }
                    }
                    OutlinedTextField(query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text("Reviername, Nummer oder Gemeinde") }, singleLine = true)
                    Button(enabled = source != null && query.trim().length >= 2 && !busy, onClick = {
                        val selectedSource = source ?: return@Button
                        busy = true
                        message = "Datenquelle wird abgefragt ..."
                        results = emptyList()
                        scope.launch {
                            try {
                                results = search(selectedSource, query.trim())
                                message = if (results.isEmpty()) "Keine passenden Reviere gefunden." else "${results.size} Treffer"
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                message = failure.message ?: "Datenquelle konnte nicht geladen werden."
                            } finally { busy = false }
                        }
                    }, modifier = Modifier.fillMaxWidth()) {
                        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Search, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (busy) "Suche laeuft" else "Suchen")
                    }
                    source?.let { current ->
                        Text("${current.attribution.ifBlank { current.label }} · ${current.license}",
                            style = MaterialTheme.typography.labelSmall)
                        if (current.snapshotDate.isNotBlank()) Text("Datenstand: ${current.snapshotDate}",
                            style = MaterialTheme.typography.labelSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            if (current.metadataUrl.isNotBlank()) TextButton(onClick = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(current.metadataUrl)))
                            }) { Text("Metadaten") }
                            if (current.licenseUrl.isNotBlank()) TextButton(onClick = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(current.licenseUrl)))
                            }) { Text("Lizenz") }
                        }
                    }
                    Row {
                        TextButton(onClick = { page = PublicSearchPage.SOURCES }, modifier = Modifier.semantics {
                            contentDescription = "Suchquellen verwalten"
                        }) { Icon(Icons.Default.Edit, null); Spacer(Modifier.width(6.dp)); Text("Quellen verwalten") }
                    }
                    if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().heightIn(max = 320.dp),
                        contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(results, key = { "${it.source.id}:${it.id}" }) { feature ->
                            Column {
                                ListItem(modifier = Modifier.clickable {
                                    selectedFeature = feature
                                    page = PublicSearchPage.FEATURE_DETAILS
                                }.semantics { contentDescription = "Revierdetails: ${feature.name}" },
                                    headlineContent = { Text(feature.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                    supportingContent = { Text(feature.properties.optString("gemeinde").takeUnless { it.isBlank() }
                                        ?: feature.source.label, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                                HorizontalDivider()
                            }
                        }
                    }
                }
                PublicSearchPage.SOURCES -> {
                    LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(sources, key = { it.id }) { item ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(item.label, style = MaterialTheme.typography.titleSmall)
                                    Text("${item.service.label} · ${item.attribution.ifBlank { item.license }}",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                if (item.userDefined) {
                                    IconButton(onClick = { editingSource = item; page = PublicSearchPage.EDIT_SOURCE }) {
                                        Icon(Icons.Default.Edit, "Quelle bearbeiten")
                                    }
                                    IconButton(onClick = { deletingSource = item; page = PublicSearchPage.DELETE_SOURCE }) {
                                        Icon(Icons.Default.Delete, "Quelle loeschen")
                                    }
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                    OutlinedButton(onClick = { editingSource = null; page = PublicSearchPage.EDIT_SOURCE },
                        modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(8.dp))
                        Text("GeoJSON- oder WFS-Quelle hinzufuegen")
                    }
                }
                PublicSearchPage.EDIT_SOURCE -> {
                    Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(label, { label = it }, label = { Text("Anzeigename") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(url, { url = it }, label = { Text("HTTPS-URL") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                        Text("Diensttyp", style = MaterialTheme.typography.labelLarge)
                        listOf(PublicBoundaryService.GEOJSON, PublicBoundaryService.WFS).forEach { option ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = service == option, onClick = { service = option })
                                Text(if (option == PublicBoundaryService.GEOJSON) "GeoJSON Feature/FeatureCollection" else "WFS 2.0 GeoJSON")
                            }
                        }
                        if (service == PublicBoundaryService.WFS) OutlinedTextField(typeName, { typeName = it },
                            label = { Text("WFS TYPENAMES") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(nameFields, { nameFields = it }, label = { Text("Namensfelder, kommasepariert") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(idFields, { idFields = it }, label = { Text("ID-Felder, kommasepariert") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(attribution, { attribution = it }, label = { Text("Quellenangabe") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(license, { license = it }, label = { Text("Lizenz") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(licenseUrl, { licenseUrl = it }, label = { Text("Lizenz-URL") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(metadataUrl, { metadataUrl = it }, label = { Text("Metadaten-URL") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        if (formError.isNotBlank()) Text(formError, color = MaterialTheme.colorScheme.error)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { page = PublicSearchPage.SOURCES }) { Text("Abbrechen") }
                        TextButton(onClick = {
                            val candidate = PublicBoundarySource(id = editingSource?.id ?: userBoundarySourceId(),
                                label = label, service = service, url = url, typeName = typeName.trim(),
                                nameFields = nameFields.split(',').map(String::trim).filter(String::isNotBlank),
                                idFields = idFields.split(',').map(String::trim).filter(String::isNotBlank),
                                attribution = attribution.trim(), license = license.trim(),
                                licenseUrl = licenseUrl.trim(), metadataUrl = metadataUrl.trim(), userDefined = true)
                            try {
                                store.saveCustom(validatePublicBoundarySource(candidate))
                                sources = store.all()
                                selectedSourceId = candidate.id
                                store.selectSource(candidate.id)
                                page = PublicSearchPage.SOURCES
                                formError = ""
                            } catch (failure: Exception) {
                                formError = failure.message ?: "Quellenkonfiguration ist ungueltig."
                            }
                        }) { Text("Speichern") }
                    }
                }
                PublicSearchPage.DELETE_SOURCE -> {
                    val deleting = deletingSource
                    Text("${deleting?.label.orEmpty()} wird von diesem Geraet entfernt. Der Webdienst wird nicht veraendert.")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { page = PublicSearchPage.SOURCES }) { Text("Abbrechen") }
                        TextButton(onClick = {
                            deleting?.let { store.deleteCustom(it.id) }
                            sources = store.all()
                            if (selectedSourceId == deleting?.id) selectedSourceId = sources.firstOrNull()?.id
                            page = PublicSearchPage.SOURCES
                        }) { Text("Loeschen") }
                    }
                }
                PublicSearchPage.OFFLINE_BOUNDARIES -> {
                    if (savedBoundaries.isEmpty()) Text("Keine oeffentlichen Reviergrenzen offline gespeichert.")
                    LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(savedBoundaries, key = { "${it.feature.source.id}:${it.feature.id}" }) { saved ->
                            ListItem(modifier = Modifier.semantics { contentDescription = "Offlinegrenze ${saved.feature.name}" },
                                headlineContent = { Text(saved.feature.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                supportingContent = {
                                    Text("${saved.feature.source.label} · ${saved.feature.source.license}\nGespeichert: ${saved.savedAt}",
                                        style = MaterialTheme.typography.bodySmall)
                                }, trailingContent = {
                                    Row {
                                        TextButton(onClick = {
                                            selectedFeature = saved.feature
                                            page = PublicSearchPage.FEATURE_DETAILS
                                        }) { Text("Oeffnen") }
                                        IconButton(onClick = {
                                            offlineStore.delete(saved.feature.source.id, saved.feature.id)
                                            savedBoundaries = offlineStore.all()
                                        }, modifier = Modifier.semantics {
                                            contentDescription = "${saved.feature.name} offline entfernen"
                                        }) { Icon(Icons.Default.Delete, null) }
                                    }
                                })
                            HorizontalDivider()
                        }
                    }
                }
                PublicSearchPage.FEATURE_DETAILS -> {
                    val feature = selectedFeature
                    if (feature == null) Text("Kein Revier ausgewaehlt.")
                    else {
                        val saved = savedBoundaries.any {
                            it.feature.source.id == feature.source.id && it.feature.id == feature.id
                        }
                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(feature.source.label, style = MaterialTheme.typography.titleSmall)
                            Text(feature.source.attribution.ifBlank { feature.source.label })
                            Text("Lizenz: ${feature.source.license}")
                            feature.properties.keys().asSequence().take(12).forEach { key ->
                                val value = feature.properties.opt(key)
                                if (value != null && value != JSONObject.NULL && value !is JSONObject && value !is JSONArray) {
                                    Text("$key: $value", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            Text(if (saved) "Auf diesem Geraet offline verfuegbar."
                                else "Zum Offline-Speichern das Speichersymbol am Suchtreffer verwenden.",
                                style = MaterialTheme.typography.bodySmall)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    onPreview(feature)
                                    selectedFeature = null
                                    onDismiss()
                                }, modifier = Modifier.weight(1f)) { Text("Auf Karte zeigen") }
                                Button(onClick = {
                                    if (saved) {
                                        offlineStore.delete(feature.source.id, feature.id)
                                        savedBoundaries = offlineStore.all()
                                    } else {
                                        licenseConfirmed = false
                                        pendingOfflineSave = feature
                                        page = PublicSearchPage.CONFIRM_OFFLINE_SAVE
                                    }
                                }, modifier = Modifier.weight(1f)) {
                                    Text(if (saved) "Offline entfernen" else "Offline speichern")
                                }
                            }
                            if (!signedIn) TextButton(onClick = {
                                selectedFeature = null
                                onDismiss()
                                onSignIn(feature.source, feature)
                            }, modifier = Modifier.fillMaxWidth()) { Text("Anmelden, um eigenes Revier anzulegen") }
                            else Button(enabled = !busy, onClick = {
                                busy = true
                                scope.launch {
                                    try {
                                        onCreateProfile(feature.source, feature)
                                        selectedFeature = null
                                        onDismiss()
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (failure: Exception) {
                                        message = failure.message ?: "Eigenes Revierprofil konnte nicht angelegt werden."
                                    } finally { busy = false }
                                }
                            }, modifier = Modifier.fillMaxWidth()) { Text("Eigenes Revier anlegen") }
                        }
                    }
                }
                PublicSearchPage.CONFIRM_OFFLINE_SAVE -> {
                    val feature = pendingOfflineSave
                    if (feature == null) {
                        Text("Keine Reviergrenze ausgewaehlt.")
                    } else {
                        val requiresLicenseConfirmation = feature.source.license.contains(
                            "Nutzungsbedingungen beim Anbieter pruefen", true)
                        Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(feature.name, style = MaterialTheme.typography.titleMedium)
                            Text(feature.source.attribution.ifBlank { feature.source.label })
                            Text("Lizenz: ${feature.source.license}")
                            if (requiresLicenseConfirmation) Row(
                                modifier = Modifier.fillMaxWidth().toggleable(licenseConfirmed, role = Role.Checkbox) {
                                    licenseConfirmed = it
                                }, verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(licenseConfirmed, onCheckedChange = null)
                                Text("Ich habe die Nutzungsbedingungen der Quelle geprueft.")
                            }
                            Text("Es wird nur diese oeffentliche Grenze mit den Quellenangaben lokal gespeichert. Sie kann unter Offline-Grenzen jederzeit entfernt werden. Private Profile, Marker und Live-Positionen werden nicht gespeichert.")
                            if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(enabled = !busy, onClick = {
                                pendingOfflineSave = null
                                page = PublicSearchPage.SEARCH
                            }) { Text("Abbrechen") }
                            Button(enabled = !busy && (!requiresLicenseConfirmation || licenseConfirmed), onClick = {
                                busy = true
                                message = ""
                                scope.launch {
                                    try {
                                        offlineStore.save(feature)
                                        savedBoundaries = offlineStore.all()
                                        message = "Reviergrenze fuer Offline gespeichert."
                                        pendingOfflineSave = null
                                        page = PublicSearchPage.OFFLINE_BOUNDARIES
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (failure: Exception) {
                                        message = failure.message ?: "Reviergrenze konnte nicht offline gespeichert werden."
                                    } finally { busy = false }
                                }
                            }) { Text(if (busy) "Speichern ..." else "Offline speichern") }
                        }
                    }
                }
            }
        }
    }

}

private val PublicBoundaryService.label: String get() = when (this) {
    PublicBoundaryService.GEOJSON -> "GeoJSON"
    PublicBoundaryService.WFS -> "WFS"
    PublicBoundaryService.OGC_API -> "OGC API Features"
    PublicBoundaryService.ARCGIS -> "ArcGIS REST"
    PublicBoundaryService.SNAPSHOT -> "Snapshot"
}
