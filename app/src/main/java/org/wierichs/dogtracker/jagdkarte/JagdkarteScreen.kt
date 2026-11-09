package org.wierichs.dogtracker.jagdkarte

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Picture
import android.graphics.drawable.PictureDrawable
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.widget.ImageView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import com.caverock.androidsvg.SVG
import java.io.File
import java.io.ByteArrayOutputStream
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.wierichs.dogtracker.communication.RelayGateway
import org.wierichs.dogtracker.communication.RelayGatewayService
import org.wierichs.dogtracker.TrackerPreferences

private const val MAX_JWD_FILE_SIZE_BYTES = 10 * 1024 * 1024

private fun readRevierDocument(context: android.content.Context, uri: Uri): List<JwdImportedProfile> {
    val fileName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else "" }.orEmpty()
    require(listOf(".jwd", ".json", ".geojson").any { fileName.endsWith(it, ignoreCase = true) }) {
        "Bitte eine JWD-, JSON- oder GeoJSON-Revierdatei auswaehlen."
    }
    val content = ByteArrayOutputStream()
    context.contentResolver.openInputStream(uri)?.use { input ->
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= MAX_JWD_FILE_SIZE_BYTES) { "Die Datei ist groesser als 10 MB." }
            content.write(buffer, 0, read)
        }
    } ?: error("Die ausgewaehlte Datei konnte nicht geoeffnet werden.")
    return parseRevierImport(content.toString(Charsets.UTF_8.name()), fileName)
}

@Composable
internal fun JagdkarteScreen(
    onUseDeviceToken: (String) -> Boolean,
    incomingGuestLinkToken: String? = null,
    onGuestLinkReceived: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val repository = remember { JagdkarteRepository(context) }
    val scope = rememberCoroutineScope()
    var signedIn by remember { mutableStateOf(repository.signedIn) }
    var profiles by remember { mutableStateOf(emptyList<HuntingProfile>()) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var snapshot by remember { mutableStateOf<HuntingSnapshot?>(null) }
    var status by remember { mutableStateOf("") }
    var invitationStatus by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var revision by remember { mutableStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var accountOpen by remember { mutableStateOf(false) }
    var displayName by remember { mutableStateOf("") }
    var displayNameBusy by remember { mutableStateOf(false) }
    var displayNameStatus by remember { mutableStateOf("") }
    var markerIconSize by remember { mutableStateOf(HuntingMarkerSize.fromId(TrackerPreferences.markerIconSize(context))) }
    var now by remember { mutableStateOf(Instant.now()) }
    var sharing by remember { mutableStateOf(false) }
    var sharingStatus by remember { mutableStateOf("") }
    var permissionProfileId by remember { mutableStateOf<String?>(null) }
    var signingOut by remember { mutableStateOf(false) }
    var dogsOpen by remember { mutableStateOf(false) }
    var inviteProfile by remember { mutableStateOf<HuntingProfile?>(null) }
    var pendingGuestLinkToken by remember { mutableStateOf<String?>(null) }
    var guestLinkDestination by remember { mutableStateOf<GuestLinkTarget?>(null) }
    var guestLinkBusy by remember { mutableStateOf(false) }
    var guestLinkError by remember { mutableStateOf("") }
    var guestLinkAttempt by remember { mutableStateOf(0) }
    var guestFocusMarkerId by remember { mutableStateOf<String?>(null) }
    var guestFocusRequest by remember { mutableStateOf(0) }
    var pendingJwdExportJson by remember { mutableStateOf<String?>(null) }
    var jwdExportBusy by remember { mutableStateOf(false) }
    var pendingRevierImport by remember { mutableStateOf<List<JwdImportedProfile>?>(null) }
    var jwdImportBusy by remember { mutableStateOf(false) }
    var modePending by remember { mutableStateOf(false) }
    val gatewayState by RelayGateway.state.collectAsState()
    val relayForProfile = gatewayState.active && gatewayState.profileId == selectedId
    var relayPermissionProfileId by remember { mutableStateOf<String?>(null) }
    var markerCoordinates by remember { mutableStateOf<MarkerCoordinates?>(null) }
    var markerProfile by remember { mutableStateOf<HuntingSnapshot?>(null) }
    var editingMarker by remember { mutableStateOf<HuntingMapPoint?>(null) }
    LaunchedEffect(selectedId, signedIn) {
        markerCoordinates = null
        markerProfile = null
        editingMarker = null
        inviteProfile = null
    }
    val sharingMutex = remember { Mutex() }
    val relayPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val id = relayPermissionProfileId
        relayPermissionProfileId = null
        val required = RelayGatewayService.requiredRelayPermissions() +
            if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        if (id != null && id == selectedId && signedIn && snapshot?.profile?.huntingMode == true && required.all {
                grants[it] == true || androidx.core.content.ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }) {
            runCatching { RelayGateway.start(context, id) }.onFailure { status = "Relaisdienst konnte nicht gestartet werden." }
        } else status = "Relaisfreigabe nicht gestartet. Erforderliche Berechtigungen fehlen oder das Revier hat gewechselt."
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        sharing = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true && signedIn &&
            selectedId == permissionProfileId && snapshot?.profile?.huntingMode == true
        permissionProfileId = null
        if (!sharing) sharingStatus = "Genauer Standort wurde nicht freigegeben."
    }
    val jwdExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val exportJson = pendingJwdExportJson
        pendingJwdExportJson = null
        if (uri == null || exportJson == null) {
            jwdExportBusy = false
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(exportJson.toByteArray(Charsets.UTF_8))
                    } ?: error("Die Zieldatei konnte nicht geoeffnet werden.")
                }
                status = "JWD-Datei exportiert."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                status = failure.message ?: "JWD-Datei konnte nicht gespeichert werden."
            } finally {
                jwdExportBusy = false
            }
        }
    }
    val jwdImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        jwdImportBusy = true
        scope.launch {
            try {
                pendingRevierImport = withContext(Dispatchers.IO) { readRevierDocument(context, uri) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                status = failure.message ?: "JWD-Datei konnte nicht gelesen werden."
            } finally {
                jwdImportBusy = false
            }
        }
    }

    fun startJwdExport(current: HuntingSnapshot) {
        try {
            pendingJwdExportJson = createJwdExport(current.profile, current.points, current.canEdit).toString(2)
            jwdExportBusy = true
            status = "JWD-Datei wird vorbereitet ..."
            jwdExportLauncher.launch(jwdFileName(current.profile.name))
        } catch (failure: Exception) {
            pendingJwdExportJson = null
            jwdExportBusy = false
            status = failure.message ?: "JWD-Datei konnte nicht vorbereitet werden."
        }
    }

    LaunchedEffect(incomingGuestLinkToken) {
        val token = incomingGuestLinkToken ?: return@LaunchedEffect
        if (runCatching { guestLinkUrl(token) }.isSuccess) {
            pendingGuestLinkToken = token
            guestLinkError = ""
            onGuestLinkReceived()
            if (!signedIn) {
                accountOpen = true
                status = "Melde dich an oder registriere dich, um dem Revier als Gast beizutreten."
            }
        }
    }

    LaunchedEffect(pendingGuestLinkToken, signedIn, guestLinkAttempt) {
        val token = pendingGuestLinkToken?.takeIf { signedIn } ?: return@LaunchedEffect
        guestLinkBusy = true
        guestLinkError = ""
        try {
            guestLinkDestination = repository.acceptGuestLink(token)
            pendingGuestLinkToken = null
            revision++
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            guestLinkError = failure.message ?: "Gastlink konnte nicht angenommen werden."
        } finally {
            guestLinkBusy = false
        }
    }

    LaunchedEffect(accountOpen, signedIn, repository.userId) {
        if (!accountOpen || !signedIn) return@LaunchedEffect
        displayNameBusy = true
        displayNameStatus = ""
        try {
            displayName = repository.ownDisplayName().orEmpty()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            displayNameStatus = failure.message ?: "Anzeigename konnte nicht geladen werden."
        } finally {
            displayNameBusy = false
        }
    }

    LaunchedEffect(snapshot?.profile?.huntingMode, relayForProfile) {
        if (snapshot?.profile?.huntingMode == false || (snapshot == null && !relayForProfile)) sharing = false
        if (snapshot?.profile?.huntingMode == false && relayForProfile) RelayGateway.stop(context)
    }

    LaunchedEffect(selectedId, sharing, signedIn, relayForProfile) {
        val profileId = selectedId?.takeIf { sharing && signedIn } ?: return@LaunchedEffect
        sharingMutex.withLock {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    while (true) {
                        try {
                            val location = freshLocation(context)
                            if (relayForProfile) {
                                check(RelayGateway.enqueue(profileId, location))
                                sharingStatus = "Standort lokal gespeichert; Serverbestaetigung siehe Relaisstatus."
                            } else {
                                val sent = repository.shareLocation(profileId, location.latitude, location.longitude, location.accuracy)
                                sharingStatus = if (sent) "Standort vom Server bestaetigt." else "Standort nicht geteilt: ausserhalb der Grenze oder Jagdmodus aus."
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            if (relayForProfile) {
                                RelayGateway.cancelOwn(profileId)
                                runCatching { repository.removeLocation(profileId) }
                            }
                            sharingStatus = "Standort nicht bestaetigt; Verbindung und GPS pruefen."
                        }
                        delay(30_000)
                    }
                } finally {
                    withContext(NonCancellable) {
                        try {
                            RelayGateway.cancelOwn(profileId)
                            repository.removeLocation(profileId)
                            sharingStatus = "Standortfreigabe pausiert."
                        } catch (_: Exception) {
                            sharingStatus = "Entfernen nicht bestaetigt; letzte Position bleibt hoechstens 90 Sekunden sichtbar."
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(signedIn, revision) {
        profiles = emptyList()
        if (!signedIn) return@LaunchedEffect
        loading = true
        try {
            profiles = repository.profiles()
            val guestTarget = guestLinkDestination
            if (guestTarget != null) {
                if (profiles.any { it.id == guestTarget.profileId }) {
                    selectedId = guestTarget.profileId
                    guestFocusMarkerId = guestTarget.markerId
                    guestFocusRequest++
                    status = "Gastlink angenommen. Das Revier wurde geoeffnet."
                } else {
                    selectedId = null
                    status = "Das Revier aus dem Gastlink ist fuer dieses Konto nicht verfuegbar."
                }
                guestLinkDestination = null
            } else {
                if (profiles.none { it.id == selectedId }) selectedId = null
                status = if (profiles.isEmpty()) "Keine gespeicherten Reviere vorhanden." else ""
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            snapshot = null
            status = error.message ?: "Reviere konnten nicht geladen werden."
        } finally {
            loading = false
        }
    }

    LaunchedEffect(selectedId, signedIn, revision) {
        snapshot = null
        val profileId = selectedId?.takeIf { signedIn } ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                while (true) {
                    try {
                        snapshot = repository.snapshot(profileId)
                        status = "Stand: ${java.time.LocalTime.now().withNano(0)}"
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        snapshot = null
                        status = error.message ?: "Aktualisierung fehlgeschlagen."
                    }
                    delay(30_000)
                }
            } finally {
                snapshot = null
            }
        }
    }

    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = Instant.now()
                delay(1_000)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Jagdkarte", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = { revision++ }, enabled = signedIn && !loading) {
                Icon(Icons.Default.Refresh, "Aktualisieren")
            }
            IconButton(onClick = { accountOpen = true }, enabled = !signingOut) {
                Icon(Icons.Default.AccountCircle, "Benutzerkonto")
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                TextButton(onClick = { menuOpen = true }, enabled = profiles.isNotEmpty()) {
                    Text(profiles.find { it.id == selectedId }?.name ?: "Revier auswaehlen",
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                    profiles.forEach { profile ->
                        DropdownMenuItem(text = {
                            Text(profileSelectionLabel(profile, repository.userId))
                        }, onClick = {
                            if (gatewayState.active || gatewayState.starting) RelayGateway.stop(context)
                            sharing = false
                            sharingStatus = ""
                            snapshot = null
                            selectedId = profile.id
                            menuOpen = false
                        })
                    }
                }
            }
            if (loading) CircularProgressIndicator(Modifier.padding(8.dp))
            if (!signedIn) Button(onClick = { accountOpen = true }, enabled = !signingOut) { Text("Anmelden") }
        }
        if (signedIn) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(enabled = !jwdImportBusy, onClick = {
                    jwdImportLauncher.launch(arrayOf("*/*"))
                }) { Text(if (jwdImportBusy) "Datei wird geprueft ..." else "Revierdatei importieren") }
            }
        }
        snapshot?.let { current ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Jagdmodus", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                if (current.canEdit) {
                    Switch(current.profile.huntingMode, onCheckedChange = { enabled ->
                        modePending = true
                        if (!enabled) sharing = false
                        scope.launch {
                            try {
                                repository.setHuntingMode(current.profile.id, enabled)
                                if (selectedId == current.profile.id) revision++
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                status = failure.message ?: "Jagdmodus konnte nicht geaendert werden."
                            } finally {
                                modePending = false
                            }
                        }
                    }, enabled = !modePending)
                } else {
                    Text(if (current.profile.huntingMode) "Aktiv" else "Aus")
                }
                TextButton(onClick = { dogsOpen = true }) { Text("Hunde") }
                if (current.canEdit) TextButton(onClick = { inviteProfile = current.profile }) { Text("Einladen") }
                TextButton(enabled = current.profile.boundary != null && !jwdExportBusy,
                    onClick = { startJwdExport(current) }) {
                    Text(if (jwdExportBusy) "Exportieren ..." else "JWD exportieren")
                }
            }
        }
        if (snapshot != null || gatewayState.active || gatewayState.starting || gatewayState.status.isNotBlank()) {
            RelayGatewayControls(canStart = signedIn && snapshot?.profile?.huntingMode == true && !TrackerPreferences.isTracking(context),
                state = gatewayState, onEnable = {
                    val id = selectedId
                    if (id != null) {
                        val required = RelayGatewayService.requiredRelayPermissions() +
                            if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
                        if (required.all { androidx.core.content.ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
                            runCatching { RelayGateway.start(context, id) }.onFailure { status = "Relaisdienst konnte nicht gestartet werden." }
                        } else {
                            relayPermissionProfileId = id
                            relayPermissionLauncher.launch(required.toTypedArray())
                        }
                    }
                }, onStop = { RelayGateway.stop(context) })
        }
        if (snapshot?.profile?.huntingMode == true || sharing) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(sharing, onCheckedChange = { enabled ->
                    if (!enabled) {
                        sharing = false
                    } else if (androidx.core.content.ContextCompat.checkSelfPermission(context,
                            Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        sharing = true
                    } else {
                        permissionProfileId = selectedId
                        locationPermission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
                    }
                }, enabled = selectedId != null)
                Text("Standort mit diesem Revier teilen", style = MaterialTheme.typography.bodyMedium)
            }
        }
        NativeHuntingMap(snapshot, now, Modifier.weight(1f).fillMaxWidth(), onChooseLocation = { coordinates ->
            val current = snapshot
            if (current != null && locationInsideBoundary(current.profile.boundary, coordinates.latitude, coordinates.longitude)) {
                markerCoordinates = coordinates
                markerProfile = current
                true
            } else {
                status = "Die Markierung muss innerhalb der hinterlegten Reviergrenze liegen."
                false
            }
        }, markerIconSize = markerIconSize, focusMarkerId = guestFocusMarkerId,
            focusRequest = guestFocusRequest, currentUserId = repository.userId, onMarkerSelected = { markerId ->
            editingMarker = snapshot?.points?.firstOrNull { it.markerId == markerId }
        })
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(8.dp)) {
                snapshot?.let {
                    Text(if (it.profile.huntingMode) "Jagdmodus aktiv" else "Jagdmodus aus",
                        style = MaterialTheme.typography.labelLarge)
                }
                if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                if (invitationStatus.isNotBlank()) Text(invitationStatus, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                if (sharingStatus.isNotBlank()) Text(sharingStatus, style = MaterialTheme.typography.bodySmall, maxLines = 3)
            }
            TextButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/copyright")))
            }) { Text("© OpenStreetMap", style = MaterialTheme.typography.labelSmall) }
        }
    }

    val markerContext = markerProfile
    val coordinates = markerCoordinates
    if (markerContext != null && coordinates != null && selectedId == markerContext.profile.id && signedIn) {
        MarkerCreateDialog(markerContext.profile, markerContext.canEdit, coordinates,
            onCreate = { draft, currentLocation -> repository.createMarker(markerContext.profile.id, draft, currentLocation) },
            onCreated = { markerCoordinates = null; markerProfile = null; revision++ },
            onDismiss = { markerCoordinates = null; markerProfile = null })
    }

    val editContext = snapshot
    val markerToEdit = editingMarker
    val canDeleteSelectedMarker = editContext != null && markerToEdit != null &&
        canDeleteHuntingMarker(editContext.canEdit, repository.userId, markerToEdit)
    if (editContext != null && markerToEdit != null && canDeleteSelectedMarker &&
        selectedId == editContext.profile.id && signedIn) {
        MarkerEditDialog(markerToEdit,
            canEdit = editContext.canEdit,
            canDelete = true,
            onUpdate = { title, notes -> repository.updateMarker(editContext.profile.id, markerToEdit, title, notes) },
            onDelete = { repository.deleteMarker(editContext.profile.id, markerToEdit) },
            onMove = { coordinates -> repository.moveMarkerToLocation(editContext.profile.id,
                markerToEdit.markerId ?: error("Markierung nicht gefunden."), coordinates) },
            onCreateGuestLink = { repository.createGuestLink(editContext.profile.id,
                markerToEdit.markerId ?: error("Markierung nicht gefunden.")) },
            onShareGuestLink = { link ->
                val shareIntent = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, link)
                context.startActivity(Intent.createChooser(shareIntent, "Gastlink teilen"))
            },
            onUpdated = { editingMarker = null; revision++ },
            onDeleted = { editingMarker = null; revision++ },
            onMoved = { editingMarker = null; revision++ },
            onDismiss = { editingMarker = null })
    }

    val pendingGuestToken = pendingGuestLinkToken
    if (pendingGuestToken != null && signedIn) {
        AlertDialog(
            onDismissRequest = { if (!guestLinkBusy) { pendingGuestLinkToken = null; guestLinkError = "" } },
            title = { Text("Gastlink annehmen") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(guestLinkError.ifBlank { "Der Zugang zum verknuepften Revier wird geprueft." })
                    if (guestLinkBusy) CircularProgressIndicator()
                }
            },
            confirmButton = {
                if (guestLinkError.isNotBlank()) TextButton(enabled = !guestLinkBusy, onClick = { guestLinkAttempt++ }) {
                    Text("Erneut versuchen")
                }
            },
            dismissButton = {
                TextButton(enabled = !guestLinkBusy, onClick = { pendingGuestLinkToken = null; guestLinkError = "" }) {
                    Text("Schliessen")
                }
            }
        )
    }

    if (dogsOpen && snapshot != null) {
        val current = snapshot!!
        DogsDialog(repository, current.profile, current.canEdit, onUseDeviceToken,
            onDismiss = { dogsOpen = false })
    }

    val profileToInvite = inviteProfile
    if (profileToInvite != null && signedIn && selectedId == profileToInvite.id) {
        InviteMemberDialog(profileToInvite,
            onInvite = { email, role -> repository.inviteMember(profileToInvite.id, email, role) },
            onInvited = { role ->
                inviteProfile = null
                invitationStatus = ""
                status = "${role.label}-Einladung per E-Mail angefordert."
            },
            onDismiss = { inviteProfile = null })
    }

    val importedRevierCandidates = pendingRevierImport
    if (!importedRevierCandidates.isNullOrEmpty() && signedIn) {
        RevierImportDialog(importedRevierCandidates,
            onImport = { candidate -> repository.importJwdProfile(candidate) },
            onImported = { profileId ->
                pendingRevierImport = null
                selectedId = profileId
                snapshot = null
                revision++
                status = "Revierdatei importiert."
            },
            onDismiss = { pendingRevierImport = null })
    }

    if (accountOpen) {
        if (signedIn) {
            AlertDialog(onDismissRequest = { accountOpen = false }, title = { Text("Benutzerkonto") },
                text = {
                    Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(repository.email)
                        Text("Anzeigename f\u00fcr Reviermitglieder")
                        OutlinedTextField(displayName, onValueChange = { displayName = it.take(60) },
                            enabled = !displayNameBusy, singleLine = true, placeholder = { Text("Optional") },
                            supportingText = { Text("${displayName.length}/60") }, modifier = Modifier.fillMaxWidth())
                        TextButton(enabled = !displayNameBusy, onClick = {
                            displayNameBusy = true
                            displayNameStatus = ""
                            scope.launch {
                                try {
                                    repository.saveOwnDisplayName(displayName)
                                    displayName = displayName.trim()
                                    displayNameStatus = if (displayName.isBlank()) "Anzeigename entfernt." else "Anzeigename gespeichert."
                                    revision++
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    displayNameStatus = failure.message ?: "Anzeigename konnte nicht gespeichert werden."
                                } finally {
                                    displayNameBusy = false
                                }
                            }
                        }) { Text("Anzeigename speichern") }
                        if (displayNameBusy) CircularProgressIndicator()
                        if (displayNameStatus.isNotBlank()) Text(displayNameStatus, style = MaterialTheme.typography.bodySmall)
                        Text("Gr\u00f6\u00dfe der Reviermarker auf diesem Ger\u00e4t")
                        Column(Modifier.selectableGroup()) {
                            HuntingMarkerSize.entries.forEach { size ->
                                Row(Modifier.fillMaxWidth().selectable(
                                    selected = markerIconSize == size,
                                    onClick = {
                                        markerIconSize = size
                                        TrackerPreferences.setMarkerIconSize(context, size.id)
                                    },
                                    role = Role.RadioButton
                                ), verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = markerIconSize == size, onClick = null)
                                    Text(size.label)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val previousProfileId = selectedId
                        signingOut = true
                        sharing = false
                        snapshot = null
                        profiles = emptyList()
                        selectedId = null
                        signedIn = false
                        displayName = ""
                        displayNameStatus = ""
                        accountOpen = false
                        scope.launch {
                            try {
                                withContext(NonCancellable) {
                                    sharingMutex.withLock {
                                        try {
                                            if (gatewayState.active || gatewayState.starting) RelayGateway.stopAndAwait(context)
                                            previousProfileId?.let { repository.removeLocation(it) }
                                        } finally {
                                            repository.signOut()
                                        }
                                    }
                                }
                                status = "Abgemeldet."
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                status = "Lokal abgemeldet; Server-Abmeldung nicht bestaetigt."
                            } finally {
                                signingOut = false
                            }
                        }
                    }) { Text("Abmelden") }
                }, dismissButton = { TextButton(onClick = { accountOpen = false }) { Text("Schliessen") } })
        } else {
            SignInDialog(repository, onDismiss = { accountOpen = false }, onSignedIn = { invitationError ->
                signedIn = true
                accountOpen = false
                invitationStatus = invitationError.orEmpty()
                revision++
            })
        }
    }
}

@Composable
private fun SignInDialog(repository: JagdkarteRepository, onDismiss: () -> Unit, onSignedIn: (String?) -> Unit) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var requestedEmail by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf("") }
    var register by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (register) "Registrieren" else "Anmelden") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(email, onValueChange = { email = it; requestedEmail = null; code = "" },
                    label = { Text("E-Mail") }, singleLine = true, enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(register, onCheckedChange = { register = it; requestedEmail = null; code = "" }, enabled = !busy)
                    Text("Neues Konto")
                }
                if (requestedEmail != null) {
                    OutlinedTextField(code, onValueChange = { code = it.filter(Char::isDigit).take(10) },
                        label = { Text("E-Mail-Code") }, singleLine = true, enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                    TextButton(enabled = !busy, onClick = { requestedEmail = null; code = "" }) { Text("Code erneut anfordern") }
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                if (busy) CircularProgressIndicator()
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && email.isNotBlank() && (requestedEmail == null || code.isNotBlank()), onClick = {
                busy = true
                error = ""
                scope.launch {
                    try {
                        val address = requestedEmail
                        if (address == null) {
                            repository.requestCode(email, register)
                            requestedEmail = email.trim()
                            code = ""
                        } else {
                            repository.verifyCode(address, code)
                            val invitationError = try {
                                repository.acceptProfileInvitations()
                                null
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                "Einladungen konnten nicht angenommen werden: ${failure.message ?: "Bitte spaeter erneut versuchen."}"
                            }
                            code = ""
                            onSignedIn(invitationError)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        error = failure.message ?: "Anmeldung fehlgeschlagen."
                    } finally {
                        busy = false
                    }
                }
            }) { Text(if (requestedEmail == null) "Code anfordern" else "Bestaetigen") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Abbrechen") } }
    )
}

@Composable
internal fun NativeHuntingMap(
    snapshot: HuntingSnapshot?,
    now: Instant,
    modifier: Modifier,
    onChooseLocation: (MarkerCoordinates) -> Boolean,
    markerIconSize: HuntingMarkerSize = HuntingMarkerSize.STANDARD,
    focusMarkerId: String? = null,
    focusRequest: Int = 0,
    currentUserId: String = "",
    onMarkerSelected: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val map = remember {
        Configuration.getInstance().userAgentValue = "org.wierichs.jagdkarte/1.0"
        Configuration.getInstance().osmdroidBasePath = File(context.cacheDir, "osmdroid")
        Configuration.getInstance().osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            minZoomLevel = 3.0
            maxZoomLevel = 19.0
            controller.setZoom(7.0)
            controller.setCenter(GeoPoint(51.2, 7.0))
        }
    }
    var fittedProfileId by remember { mutableStateOf<String?>(null) }
    var focusedGuestRequest by remember { mutableStateOf(-1) }
    var renderedContent by remember { mutableStateOf<Any?>(null) }
    var choosingPosition by remember(snapshot?.profile?.id) { mutableStateOf(false) }
    var locationLegendExpanded by remember { mutableStateOf(false) }
    val chooseLocation by rememberUpdatedState(onChooseLocation)
    val selectMarker by rememberUpdatedState(onMarkerSelected)
    DisposableEffect(map, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> map.onResume()
                Lifecycle.Event.ON_PAUSE -> map.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) map.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
            map.onPause()
            map.onDetach()
        }
    }
    Box(modifier) {
        AndroidView(factory = { map }, modifier = Modifier.fillMaxSize().clipToBounds(), update = { view ->
            val points = snapshot?.points.orEmpty().filter { it.visibleAt(now) }
            val boundary = snapshot?.profile?.boundary
            val renderKey = listOf(snapshot?.profile?.id, boundary?.toString(), points,
                points.map { it.staleAt(now) }, markerIconSize, focusMarkerId, focusRequest)
            if (renderedContent != renderKey) {
                renderedContent = renderKey
                view.overlays.clear()
                if (snapshot != null) view.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(point: GeoPoint): Boolean = false
                    override fun longPressHelper(point: GeoPoint): Boolean = chooseLocation(MarkerCoordinates(point.latitude, point.longitude))
                }))
                val bounds = mutableListOf<GeoPoint>()
                geometryPolygons(boundary).forEach { rings ->
                    val polygon = Polygon(view).apply {
                        title = snapshot?.profile?.name
                        outlinePaint.color = android.graphics.Color.rgb(24, 105, 70)
                        outlinePaint.strokeWidth = 4f
                        fillPaint.color = android.graphics.Color.argb(28, 24, 105, 70)
                        setPoints(rings.first())
                        holes = rings.drop(1)
                    }
                    bounds.addAll(rings.first())
                    view.overlays.add(polygon)
                }
                points.forEach { point ->
                    view.overlays.add(Marker(view).apply {
                        position = GeoPoint(point.latitude, point.longitude)
                        title = point.title + if (point.staleAt(now)) " (veraltet)" else ""
                        snippet = point.detail
                        if (point.kind == MapPointKind.FACILITY) {
                            setAnchor(0.5f, 46f / 48f)
                            icon = huntingMarkerIcon(context, point.markerTypeId, markerIconSize)
                        } else {
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            val iconAsset = if (point.kind == MapPointKind.DOG) {
                                dogLocationIconAsset(point.staleAt(now))
                            } else {
                                participantMarkerIconAsset(point.participantRole)
                            }
                            icon = locationIcon(context, iconAsset)
                        }
                        if (point.markerId != null && snapshot?.let {
                                canDeleteHuntingMarker(it.canEdit, currentUserId, point)
                            } == true) {
                            point.markerId?.let { id -> setOnMarkerClickListener { _, _ -> selectMarker(id); true } }
                        }
                    })
                }
                val guestTarget = points.firstOrNull { it.markerId == focusMarkerId }
                if (snapshot != null && guestTarget != null && focusedGuestRequest != focusRequest) {
                    fittedProfileId = snapshot.profile.id
                    focusedGuestRequest = focusRequest
                    view.post {
                        view.controller.setZoom(16.0)
                        view.controller.animateTo(GeoPoint(guestTarget.latitude, guestTarget.longitude))
                    }
                } else if (snapshot != null && fittedProfileId != snapshot.profile.id) {
                    fittedProfileId = snapshot.profile.id
                    if (bounds.isEmpty()) bounds.addAll(points.map { GeoPoint(it.latitude, it.longitude) })
                    if (bounds.isNotEmpty()) {
                        val box = BoundingBox.fromGeoPoints(bounds)
                        view.post { view.zoomToBoundingBox(box, false, 48, 17.0, null) }
                    }
                }
                view.invalidate()
            }
        })
        if (snapshot != null) {
            Column(Modifier.align(Alignment.TopStart).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (locationLegendExpanded) {
                    Surface(color = Color.White.copy(alpha = 0.94f), shadowElevation = 4.dp,
                        shape = RoundedCornerShape(4.dp), modifier = Modifier.widthIn(max = 280.dp)) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.size(width = 22.dp, height = 12.dp)
                                    .background(Color(0x203B7355)).border(2.dp, Color(0xFF186946)))
                                Text("Jagdbezirksgrenze", style = MaterialTheme.typography.labelSmall)
                            }
                            val visiblePoints = snapshot.points.filter { it.visibleAt(now) }
                            if (visiblePoints.any { it.kind == MapPointKind.PARTICIPANT }) {
                                LocationLegendEntry(context, "location-member.svg", "Mitglied")
                                LocationLegendEntry(context, "location-editor.svg", "Bearbeiter")
                                LocationLegendEntry(context, "location-owner.svg", "Chef")
                            }
                            if (visiblePoints.any { it.kind == MapPointKind.DOG }) {
                                LocationLegendEntry(context, "location-dog.svg", "Hund")
                            }
                        }
                    }
                }
                TextButton(onClick = { locationLegendExpanded = !locationLegendExpanded }) {
                    Text(if (locationLegendExpanded) "Legende ausblenden" else "Legende anzeigen")
                }
            }
        }
        if (snapshot != null) {
            if (choosingPosition) {
                Icon(Icons.Default.LocationOn, "Zielposition", Modifier.align(Alignment.Center).offset(y = (-20).dp).size(40.dp),
                    tint = MaterialTheme.colorScheme.primary)
                FilledTonalIconButton(onClick = { choosingPosition = false }, Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                    Icon(Icons.Default.Close, "Positionswahl abbrechen")
                }
            }
            ExtendedFloatingActionButton(onClick = {
                if (choosingPosition) {
                    val center = map.mapCenter
                    if (chooseLocation(MarkerCoordinates(center.latitude, center.longitude))) choosingPosition = false
                } else {
                    choosingPosition = true
                }
            }, modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(if (choosingPosition) "Hier setzen" else "Markierung setzen") })
        }
    }
}

@Composable
private fun LocationLegendEntry(context: Context, asset: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AndroidView(factory = { ImageView(it).apply { contentDescription = "$label Symbol" } },
            update = { it.setImageDrawable(locationIcon(context, asset, 22)) }, modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

private val huntingMarkerPictureCache = mutableMapOf<Triple<String, Int, Int>, Picture>()

private fun huntingMarkerIcon(
    context: android.content.Context,
    markerTypeId: String?,
    size: HuntingMarkerSize
): PictureDrawable {
    val type = HuntingMarkerType.fromId(markerTypeId)
    val density = context.resources.displayMetrics.density
    val width = (40 * size.scale * density).toInt().coerceAtLeast(1)
    val height = (48 * size.scale * density).toInt().coerceAtLeast(1)
    val key = Triple(type.iconAsset, width, height)
    val picture = synchronized(huntingMarkerPictureCache) {
        huntingMarkerPictureCache.getOrPut(key) {
            SVG.getFromAsset(context.assets, "markers/${type.iconAsset}").renderToPicture(width, height)
        }
    }
    return PictureDrawable(picture)
}

private fun locationIcon(context: Context, asset: String, sizeDp: Int = 36): PictureDrawable {
    val density = context.resources.displayMetrics.density
    val size = (sizeDp * density).toInt().coerceAtLeast(1)
    val key = Triple(asset, size, size)
    val picture = synchronized(huntingMarkerPictureCache) {
        huntingMarkerPictureCache.getOrPut(key) {
            SVG.getFromAsset(context.assets, asset).renderToPicture(size, size)
        }
    }
    return PictureDrawable(picture)
}

internal fun geometryPolygons(geometry: JSONObject?): List<List<List<GeoPoint>>> {
    if (geometry == null) return emptyList()
    val coordinates = geometry.optJSONArray("coordinates") ?: return emptyList()
    fun rings(polygon: JSONArray): List<List<GeoPoint>> = (0 until polygon.length()).map { ringIndex ->
        val ring = polygon.getJSONArray(ringIndex)
        (0 until ring.length()).map { pointIndex ->
            val point = ring.getJSONArray(pointIndex)
            GeoPoint(point.getDouble(1), point.getDouble(0))
        }
    }.filter { it.size >= 4 }
    return when (geometry.optString("type")) {
        "Polygon" -> listOf(rings(coordinates)).filter { it.isNotEmpty() }
        "MultiPolygon" -> (0 until coordinates.length()).map { rings(coordinates.getJSONArray(it)) }.filter { it.isNotEmpty() }
        else -> emptyList()
    }
}