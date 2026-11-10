package org.wierichs.dogtracker.jagdkarte

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Picture
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.PictureDrawable
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.widget.ImageView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.FloatingActionButton
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
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import org.wierichs.dogtracker.communication.RelayGateway
import org.wierichs.dogtracker.communication.RelayGatewayService
import org.wierichs.dogtracker.TrackerPreferences

private const val MAX_JWD_FILE_SIZE_BYTES = 10 * 1024 * 1024
internal const val MAX_HUNTING_MAP_ZOOM = 20.0

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
@OptIn(ExperimentalMaterial3Api::class)
internal fun JagdkarteScreen(
    onUseDeviceToken: (String) -> Boolean,
    incomingGuestLinkToken: String? = null,
    onGuestLinkReceived: () -> Unit = {},
    isFullScreen: Boolean = false,
    onFullScreenChanged: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val repository = remember { JagdkarteRepository(context) }
    val scope = rememberCoroutineScope()
    var signedIn by remember { mutableStateOf(repository.signedIn) }
    var profiles by remember { mutableStateOf(emptyList<HuntingProfile>()) }
    var hiddenProfileIds by remember { mutableStateOf(TrackerPreferences.hiddenHuntingProfileIds(context, repository.userId)) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var snapshot by remember { mutableStateOf<HuntingSnapshot?>(null) }
    var status by remember { mutableStateOf("") }
    var invitationStatus by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var revision by remember { mutableStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var managementOpen by remember { mutableStateOf(false) }
    var boundaryEditorProfile by remember { mutableStateOf<HuntingSnapshot?>(null) }
    var offlineMapsOpen by remember { mutableStateOf(false) }
    var offlineMapRevision by remember { mutableStateOf(0) }
    var profilePendingDeletion by remember { mutableStateOf<HuntingProfile?>(null) }
    var profileDeleteBusy by remember { mutableStateOf(false) }
    var profileDeleteError by remember { mutableStateOf("") }
    var profilePendingHide by remember { mutableStateOf<HuntingProfile?>(null) }
    var profileHideBusy by remember { mutableStateOf(false) }
    var profileHideError by remember { mutableStateOf("") }
    var accountOpen by remember { mutableStateOf(false) }
    var displayName by remember { mutableStateOf("") }
    var displayNameBusy by remember { mutableStateOf(false) }
    var displayNameStatus by remember { mutableStateOf("") }
    var markerIconSize by remember { mutableStateOf(HuntingMarkerSize.fromId(TrackerPreferences.markerIconSize(context))) }
    var jwdMarkerTypeMappings by remember { mutableStateOf(TrackerPreferences.jwdMarkerTypeMappings(context)) }
    var now by remember { mutableStateOf(Instant.now()) }
    var sharing by remember { mutableStateOf(false) }
    var ownLocationVisible by remember { mutableStateOf(false) }
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
    var importTargets by remember { mutableStateOf(emptyList<HuntingProfile>()) }
    var jwdImportBusy by remember { mutableStateOf(false) }
    var modePending by remember { mutableStateOf(false) }
    val gatewayState by RelayGateway.state.collectAsState()
    val relayForProfile = gatewayState.active && gatewayState.profileId == selectedId
    var relayPermissionProfileId by remember { mutableStateOf<String?>(null) }
    var markerCoordinates by remember { mutableStateOf<MarkerCoordinates?>(null) }
    var markerProfile by remember { mutableStateOf<HuntingSnapshot?>(null) }
    var editingMarker by remember { mutableStateOf<HuntingMapPoint?>(null) }
    val selectableProfiles = profiles.filterNot { it.id in hiddenProfileIds }
    val hiddenProfiles = profiles.filter { it.id in hiddenProfileIds }
    LaunchedEffect(selectedId, signedIn) {
        markerCoordinates = null
        markerProfile = null
        editingMarker = null
        inviteProfile = null
        boundaryEditorProfile = null
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
    val mapLocationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            ownLocationVisible = true
        } else {
            status = "Standortberechtigung nicht erteilt."
        }
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
                importTargets = try {
                    repository.editableProfiles().filterNot { it.id in hiddenProfileIds }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    status = failure.message ?: "Bestehende Reviere konnten nicht geladen werden."
                    emptyList()
                }
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
            pendingJwdExportJson = createJwdExport(current.profile, current.points, current.canEdit,
                jwdMarkerTypeMappings).toString(2)
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
        if (!signedIn) {
            hiddenProfileIds = emptySet()
            return@LaunchedEffect
        }
        loading = true
        try {
            val loadedProfiles = repository.profiles()
            val availableIds = loadedProfiles.map { it.id }.toSet()
            val loadedHiddenIds = TrackerPreferences.hiddenHuntingProfileIds(context, repository.userId)
                .intersect(availableIds)
            profiles = loadedProfiles
            hiddenProfileIds = loadedHiddenIds
            val guestTarget = guestLinkDestination
            if (guestTarget != null) {
                if (loadedProfiles.any { it.id == guestTarget.profileId }) {
                    if (guestTarget.profileId in loadedHiddenIds) {
                        TrackerPreferences.setHuntingProfileHidden(context, repository.userId,
                            guestTarget.profileId, false)
                        hiddenProfileIds = loadedHiddenIds - guestTarget.profileId
                    }
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
                val visibleProfiles = loadedProfiles.filterNot { it.id in loadedHiddenIds }
                if (visibleProfiles.none { it.id == selectedId }) selectedId = null
                status = when {
                    loadedProfiles.isEmpty() -> "Keine gespeicherten Reviere vorhanden."
                    visibleProfiles.isEmpty() -> "Alle Reviere sind auf diesem Geraet ausgeblendet."
                    else -> ""
                }
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
        if (!isFullScreen) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    TextButton(onClick = { menuOpen = true }, enabled = selectableProfiles.isNotEmpty()) {
                        Text(selectableProfiles.find { it.id == selectedId }?.name ?: "Revier auswaehlen",
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Default.ArrowDropDown, null)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false },
                        modifier = Modifier.heightIn(max = 360.dp)) {
                        selectableProfiles.forEach { profile ->
                            DropdownMenuItem(text = { Text(profileSelectionLabel(profile, repository.userId)) }, onClick = {
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
                if (loading) CircularProgressIndicator(Modifier.size(24.dp).padding(4.dp))
                IconButton(onClick = { revision++ }, enabled = signedIn && !loading) {
                    Icon(Icons.Default.Refresh, "Aktualisieren")
                }
                IconButton(onClick = { managementOpen = true }, enabled = signedIn) {
                    Icon(Icons.Default.MoreVert, "Revier und Verwaltung")
                }
                IconButton(onClick = { accountOpen = true }, enabled = !signingOut) {
                    Icon(Icons.Default.AccountCircle, "Benutzerkonto")
                }
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
        }, statusMessage = status,
            ownLocationVisible = ownLocationVisible,
            onToggleOwnLocation = {
                if (ownLocationVisible) ownLocationVisible = false
                else if (androidx.core.content.ContextCompat.checkSelfPermission(context,
                        Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) ownLocationVisible = true
                else mapLocationPermission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION))
            },
            onManage = { managementOpen = true },
            isFullScreen = isFullScreen,
            onToggleFullScreen = { onFullScreenChanged(!isFullScreen) },
            offlineMapRevision = offlineMapRevision,
            onManageOfflineMaps = { offlineMapsOpen = true })
    }

    if (managementOpen) {
        ModalBottomSheet(onDismissRequest = { managementOpen = false }) {
            val current = snapshot
            Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Revier und Verwaltung", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { managementOpen = false; offlineMapsOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Offlinekarten verwalten")
                }
                current?.let { profile ->
                    Text("Freigaben", style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Jagdmodus", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                        if (profile.canEdit) {
                            Switch(profile.profile.huntingMode, onCheckedChange = { enabled ->
                                modePending = true
                                if (!enabled) sharing = false
                                scope.launch {
                                    try {
                                        repository.setHuntingMode(profile.profile.id, enabled)
                                        if (selectedId == profile.profile.id) revision++
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (failure: Exception) {
                                        status = failure.message ?: "Jagdmodus konnte nicht geaendert werden."
                                    } finally {
                                        modePending = false
                                    }
                                }
                            }, enabled = !modePending)
                        } else Text(if (profile.profile.huntingMode) "Aktiv" else "Aus")
                    }
                    if (snapshot != null || gatewayState.active || gatewayState.starting || gatewayState.status.isNotBlank()) {
                        RelayGatewayControls(canStart = signedIn && profile.profile.huntingMode &&
                            !TrackerPreferences.isTracking(context), state = gatewayState, onEnable = {
                            val id = selectedId
                            if (id != null) {
                                val required = RelayGatewayService.requiredRelayPermissions() +
                                    if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
                                if (required.all { androidx.core.content.ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
                                    runCatching { RelayGateway.start(context, id) }
                                        .onFailure { status = "Relaisdienst konnte nicht gestartet werden." }
                                } else {
                                    relayPermissionProfileId = id
                                    relayPermissionLauncher.launch(required.toTypedArray())
                                }
                            }
                        }, onStop = { RelayGateway.stop(context) })
                    }
                    if (profile.profile.huntingMode || sharing) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Standort mit diesem Revier teilen", Modifier.weight(1f),
                                style = MaterialTheme.typography.labelLarge)
                            Switch(sharing, onCheckedChange = { enabled ->
                                if (!enabled) sharing = false
                                else if (androidx.core.content.ContextCompat.checkSelfPermission(context,
                                        Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) sharing = true
                                else {
                                    permissionProfileId = selectedId
                                    locationPermission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION,
                                        Manifest.permission.ACCESS_FINE_LOCATION))
                                }
                            }, enabled = selectedId != null)
                        }
                    }
                    Text("Revieraktionen", style = MaterialTheme.typography.titleSmall)
                    if (profile.canEdit && profile.profile.boundary != null) {
                        TextButton(onClick = {
                            managementOpen = false
                            boundaryEditorProfile = profile
                        }, modifier = Modifier.fillMaxWidth()) { Text("Reviergrenze bearbeiten") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { dogsOpen = true }, modifier = Modifier.weight(1f)) { Text("Hunde") }
                        if (profile.canEdit) TextButton(onClick = { inviteProfile = profile.profile },
                            modifier = Modifier.weight(1f)) { Text("Einladen") }
                    }
                    TextButton(enabled = profile.profile.boundary != null && !jwdExportBusy,
                        onClick = { managementOpen = false; startJwdExport(profile) },
                        modifier = Modifier.fillMaxWidth()) {
                        Text(if (jwdExportBusy) "JWD exportieren ..." else "JWD exportieren")
                    }
                    if (canDeleteHuntingProfile(profile.profile, repository.userId)) {
                        TextButton(onClick = {
                            profilePendingDeletion = profile.profile
                            profileDeleteError = ""
                        }, modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                            Text("Revier dauerhaft loeschen")
                        }
                    } else if (profile.profile.ownerId != repository.userId) {
                        TextButton(onClick = {
                            profilePendingHide = profile.profile
                            profileHideError = ""
                        }, modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                            Text("Aus Revierauswahl ausblenden")
                        }
                    }
                }
                if (hiddenProfiles.isNotEmpty()) {
                    Text("Ausgeblendete Reviere", style = MaterialTheme.typography.titleSmall)
                    hiddenProfiles.forEach { profile ->
                        TextButton(onClick = {
                            TrackerPreferences.setHuntingProfileHidden(context, repository.userId, profile.id, false)
                            hiddenProfileIds = hiddenProfileIds - profile.id
                            status = "${profile.name} ist wieder in der Revierauswahl sichtbar."
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("Einblenden: ${profile.name}")
                        }
                    }
                }
                if (signedIn) TextButton(enabled = !jwdImportBusy, onClick = {
                    managementOpen = false
                    jwdImportLauncher.launch(arrayOf("*/*"))
                }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (jwdImportBusy) "Import laeuft ..." else "Revierdatei importieren")
                }
                if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
                if (invitationStatus.isNotBlank()) Text(invitationStatus, style = MaterialTheme.typography.bodySmall)
                if (sharingStatus.isNotBlank()) Text(sharingStatus, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (offlineMapsOpen) OfflineMapsDialog(snapshot?.profile?.boundary, onDismiss = {
        offlineMapsOpen = false
        offlineMapRevision++
    })

    boundaryEditorProfile?.let { editingProfile ->
        BoundaryEditor(editingProfile, onSave = { original, draft ->
            repository.updateBoundary(editingProfile.profile.id, original, draft)
        }, onSaved = {
            boundaryEditorProfile = null
            if (selectedId == editingProfile.profile.id) {
                revision++
                status = "Reviergrenze gespeichert."
            }
        }, onDismiss = { boundaryEditorProfile = null })
    }

    val profileToDelete = profilePendingDeletion
    if (profileToDelete != null) {
        AlertDialog(onDismissRequest = { if (!profileDeleteBusy) profilePendingDeletion = null },
            title = { Text("Revier dauerhaft loeschen?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${profileToDelete.name} und alle zugehoerigen Revierdaten werden dauerhaft geloescht. Diese Aktion kann nicht rueckgaengig gemacht werden.")
                    if (profileDeleteBusy) CircularProgressIndicator()
                    if (profileDeleteError.isNotBlank()) Text(profileDeleteError, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(enabled = !profileDeleteBusy, onClick = {
                    profileDeleteBusy = true
                    profileDeleteError = ""
                    scope.launch {
                        try {
                            val deletingCurrentProfile = selectedId == profileToDelete.id
                            if (deletingCurrentProfile) sharing = false
                            sharingMutex.withLock {
                                if (gatewayState.profileId == profileToDelete.id &&
                                    (gatewayState.active || gatewayState.starting)) {
                                    RelayGateway.stopAndAwait(context)
                                }
                                repository.deleteProfile(profileToDelete.id)
                            }
                            profiles = profiles.filterNot { it.id == profileToDelete.id }
                            if (deletingCurrentProfile) {
                                selectedId = null
                                snapshot = null
                                sharingStatus = ""
                            }
                            revision++
                            status = "Revier geloescht."
                            managementOpen = false
                            profilePendingDeletion = null
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            profileDeleteError = failure.message ?: "Revier konnte nicht geloescht werden."
                        } finally {
                            profileDeleteBusy = false
                        }
                    }
                }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text("Endgueltig loeschen")
                }
            },
            dismissButton = {
                TextButton(enabled = !profileDeleteBusy, onClick = { profilePendingDeletion = null }) {
                    Text("Abbrechen")
                }
            })
    }

    val profileToHide = profilePendingHide
    if (profileToHide != null) {
        AlertDialog(onDismissRequest = { if (!profileHideBusy) profilePendingHide = null },
            title = { Text("Revier aus Auswahl ausblenden?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${profileToHide.name} wird nur auf diesem Geraet aus der Revierauswahl ausgeblendet. Deine Mitgliedschaft und dein Zugriff bleiben bestehen. Eine aktive eigene Standortfreigabe oder Revier-Relaisfreigabe wird beendet.")
                    if (profileHideBusy) CircularProgressIndicator()
                    if (profileHideError.isNotBlank()) Text(profileHideError, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(enabled = !profileHideBusy, onClick = {
                    profileHideBusy = true
                    profileHideError = ""
                    scope.launch {
                        try {
                            val hidingCurrentProfile = selectedId == profileToHide.id
                            if (hidingCurrentProfile) sharing = false
                            sharingMutex.withLock {
                                if (gatewayState.profileId == profileToHide.id &&
                                    (gatewayState.active || gatewayState.starting)) {
                                    RelayGateway.stopAndAwait(context)
                                }
                                TrackerPreferences.setHuntingProfileHidden(context, repository.userId,
                                    profileToHide.id, true)
                            }
                            hiddenProfileIds = hiddenProfileIds + profileToHide.id
                            if (hidingCurrentProfile) {
                                selectedId = null
                                snapshot = null
                                sharingStatus = ""
                            }
                            status = "Revier auf diesem Geraet ausgeblendet. Mitgliedschaft bleibt bestehen."
                            managementOpen = false
                            profilePendingHide = null
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            profileHideError = failure.message ?: "Revier konnte nicht ausgeblendet werden."
                        } finally {
                            profileHideBusy = false
                        }
                    }
                }) { Text("Ausblenden") }
            },
            dismissButton = {
                TextButton(enabled = !profileHideBusy, onClick = { profilePendingHide = null }) {
                    Text("Abbrechen")
                }
            })
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
        val suggestedMergeTargets = importedRevierCandidates.mapNotNull { candidate ->
            suggestedJwdMergeTargetId(candidate.name, importTargets, repository.userId)?.let { profileId ->
                jwdProfileNameKey(candidate.name) to profileId
            }
        }.toMap()
        RevierImportDialog(importedRevierCandidates,
            mergeTargets = importTargets,
            suggestedMergeTargets = suggestedMergeTargets,
            onImport = { candidate, target ->
                if (target == null) repository.importJwdProfile(candidate)
                else repository.mergeJwdProfile(target.id, candidate)
            },
            onImported = { profileId, merged ->
                pendingRevierImport = null
                importTargets = emptyList()
                selectedId = profileId
                snapshot = null
                revision++
                status = if (merged) "Revierdaten zusammengeführt." else "Revierdatei importiert."
            },
            onDismiss = { pendingRevierImport = null; importTargets = emptyList() })
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
                        Text("JWD-Export: Zuordnung nicht unterstützter Markertypen",
                            style = MaterialTheme.typography.titleSmall)
                        Text("Der Originaltyp wird in der JWD-Datei mitgespeichert und beim erneuten Import wiederhergestellt.",
                            style = MaterialTheme.typography.bodySmall)
                        HuntingMarkerType.entries.filter { !it.temporary && it.id !in JWD_EXPORT_MARKER_TYPE_LABELS }
                            .forEach { markerType ->
                                var mappingMenuOpen by remember(markerType.id) { mutableStateOf(false) }
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(markerType.label, Modifier.weight(1f))
                                    Box {
                                        TextButton(onClick = { mappingMenuOpen = true }) {
                                            Text(JWD_EXPORT_MARKER_TYPE_LABELS[
                                                jwdMarkerTypeMappings[markerType.id]] ?: "Hochsitz")
                                        }
                                        DropdownMenu(expanded = mappingMenuOpen,
                                            onDismissRequest = { mappingMenuOpen = false }) {
                                            JWD_EXPORT_MARKER_TYPE_LABELS.forEach { (targetId, label) ->
                                                DropdownMenuItem(text = { Text(label) }, onClick = {
                                                    TrackerPreferences.setJwdMarkerTypeMapping(context, markerType.id, targetId)
                                                    jwdMarkerTypeMappings = jwdMarkerTypeMappings + (markerType.id to targetId)
                                                    mappingMenuOpen = false
                                                })
                                            }
                                        }
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
    onMarkerSelected: (String) -> Unit = {},
    statusMessage: String = "",
    ownLocationVisible: Boolean = false,
    onToggleOwnLocation: () -> Unit = {},
    onManage: () -> Unit = {},
    isFullScreen: Boolean = false,
    onToggleFullScreen: () -> Unit = {},
    boundaryEdit: BoundaryMapEdit? = null,
    offlineMapRevision: Int = 0,
    onManageOfflineMaps: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val offlineStore = remember { OfflineMapStore(context) }
    var offlinePackages by remember { mutableStateOf(emptyList<OfflineMapPackage>()) }
    var localOfflineManagerOpen by remember { mutableStateOf(false) }
    var mapStyle by remember { mutableStateOf(HuntingMapStyle.entries.firstOrNull {
        it.name == TrackerPreferences.huntingMapStyle(context)
    } ?: HuntingMapStyle.OPEN_STREET_MAP) }
    var tileSourceError by remember { mutableStateOf("") }
    val availableOffline = remember(offlinePackages, snapshot?.profile?.boundary?.toString()) {
        coveringOfflineMaps(offlinePackages, snapshot?.profile?.boundary)
    }
    val aerialAvailable = remember(snapshot?.profile?.boundary?.toString()) {
        NrwAerialImagery.withinServiceExtent(snapshot?.profile?.boundary)
    }
    LaunchedEffect(offlineMapRevision, localOfflineManagerOpen) { offlinePackages = offlineStore.packages() }
    val map = remember {
        Configuration.getInstance().userAgentValue = "org.wierichs.jagdkarte/1.0"
        Configuration.getInstance().osmdroidBasePath = File(context.cacheDir, "osmdroid")
        Configuration.getInstance().osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
        MapView(context).apply {
            setUseDataConnection(mapStyle == HuntingMapStyle.OPEN_STREET_MAP)
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            minZoomLevel = 3.0
            maxZoomLevel = MAX_HUNTING_MAP_ZOOM
            controller.setZoom(7.0)
            controller.setCenter(GeoPoint(51.2, 7.0))
        }
    }
    val ownLocationOverlay = remember(map) { MyLocationNewOverlay(GpsMyLocationProvider(context), map) }
    var fittedProfileId by remember { mutableStateOf<String?>(null) }
    var focusedGuestRequest by remember { mutableStateOf(-1) }
    var renderedContent by remember { mutableStateOf<Any?>(null) }
    var choosingPosition by remember(snapshot?.profile?.id) { mutableStateOf(false) }
    var locationLegendExpanded by remember { mutableStateOf(false) }
    var boundaryVisible by remember(snapshot?.profile?.id) { mutableStateOf(true) }
    val chooseLocation by rememberUpdatedState(onChooseLocation)
    val selectMarker by rememberUpdatedState(onMarkerSelected)
    val boundaryEditing by rememberUpdatedState(boundaryEdit)
    LaunchedEffect(map, mapStyle, availableOffline.map { it.id }, aerialAvailable) {
        tileSourceError = ""
        try {
            map.setTileProvider(huntingTileProvider(context, mapStyle, availableOffline, snapshot?.profile?.boundary))
            map.setUseDataConnection(mapStyle != HuntingMapStyle.OFFLINE)
            map.minZoomLevel = if (mapStyle == HuntingMapStyle.AERIAL) NrwAerialImagery.MIN_ZOOM.toDouble() else 3.0
            if (map.zoomLevelDouble < map.minZoomLevel) map.controller.setZoom(map.minZoomLevel)
            map.maxZoomLevel = MAX_HUNTING_MAP_ZOOM
            map.invalidate()
            if (mapStyle == HuntingMapStyle.OFFLINE && availableOffline.isEmpty())
                tileSourceError = "Keine Offlinekarte deckt dieses Revier vollstaendig ab."
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            map.setTileProvider(emptyOfflineProvider(context))
            tileSourceError = if (mapStyle == HuntingMapStyle.AERIAL && !aerialAvailable)
                "Fuer dieses Revier sind noch keine freien Luftbilder eingerichtet." else "Kartenbasis konnte nicht geladen werden."
        }
    }
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
            val renderKey = listOf(snapshot?.profile?.id, boundary?.toString(), boundaryVisible, ownLocationVisible, points,
                points.map { it.staleAt(now) }, markerIconSize, focusMarkerId, focusRequest,
                boundaryEdit?.polygon, boundaryEdit?.ring, boundaryEdit?.vertex, boundaryEdit?.drawingPoints, boundaryEdit?.enabled)
            if (renderedContent != renderKey) {
                renderedContent = renderKey
                view.overlays.clear()
                if (snapshot != null) view.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(point: GeoPoint): Boolean = false
                    override fun longPressHelper(point: GeoPoint): Boolean {
                        val editing = boundaryEditing
                        if (editing != null) {
                            if (editing.enabled) editing.onPlace(MarkerCoordinates(point.latitude, point.longitude))
                            return true
                        }
                        return chooseLocation(MarkerCoordinates(point.latitude, point.longitude))
                    }
                }))
                val bounds = mutableListOf<GeoPoint>()
                geometryPolygons(boundary).forEach { rings ->
                    if (boundaryVisible) {
                        val polygon = Polygon(view).apply {
                            title = snapshot?.profile?.name
                            outlinePaint.color = android.graphics.Color.rgb(24, 105, 70)
                            outlinePaint.strokeWidth = 4f
                            fillPaint.color = android.graphics.Color.argb(28, 24, 105, 70)
                            setPoints(rings.first())
                            holes = rings.drop(1)
                        }
                        view.overlays.add(polygon)
                    }
                    bounds.addAll(rings.first())
                }
                points.forEach { point ->
                    view.overlays.add(Marker(view).apply {
                        position = GeoPoint(point.latitude, point.longitude)
                        title = point.title + if (point.staleAt(now)) " (veraltet)" else ""
                        snippet = "${point.detail}\nKoordinaten: ${markerCoordinateLabel(point.latitude, point.longitude)}"
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
                        if (boundaryEdit == null && point.markerId != null && snapshot?.let {
                                canDeleteHuntingMarker(it.canEdit, currentUserId, point)
                            } == true) {
                            point.markerId?.let { id -> setOnMarkerClickListener { _, _ -> selectMarker(id); true } }
                        }
                    })
                }
                if (boundaryEdit != null && boundary != null) {
                    val editPoints = BoundaryDraft.fromGeometry(boundary).polygons[boundaryEdit.polygon][boundaryEdit.ring]
                    val drawing = boundaryEdit.drawingPoints
                    val highlighted = drawing ?: (editPoints + editPoints.first())
                    view.overlays.add(Polyline(view).apply {
                        setPoints(highlighted.map { GeoPoint(it.latitude, it.longitude) })
                        outlinePaint.color = android.graphics.Color.rgb(204, 100, 25)
                        outlinePaint.strokeWidth = 6f
                    })
                    fun handle(point: MarkerCoordinates, selected: Boolean, midpoint: Boolean = false): Marker = Marker(view).apply {
                        position = GeoPoint(point.latitude, point.longitude)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        icon = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(if (selected) android.graphics.Color.rgb(204, 100, 25) else android.graphics.Color.WHITE)
                            setStroke((2 * context.resources.displayMetrics.density).toInt(), android.graphics.Color.rgb(24, 105, 70))
                            val size = ((if (midpoint) 14 else 22) * context.resources.displayMetrics.density).toInt()
                            setSize(size, size)
                        }
                    }
                    if (drawing == null) editPoints.forEachIndexed { index, point ->
                        val next = editPoints[(index + 1) % editPoints.size]
                        val midpoint = MarkerCoordinates((point.latitude + next.latitude) / 2, (point.longitude + next.longitude) / 2)
                        view.overlays.add(handle(midpoint, false, true).apply {
                            title = "Grenzpunkt einfuegen"
                            setOnMarkerClickListener { _, _ ->
                                boundaryEditing?.takeIf { it.enabled }?.onInsert?.invoke(index, midpoint)
                                true
                            }
                        })
                        view.overlays.add(handle(point, index == boundaryEdit.vertex).apply {
                            title = "Grenzpunkt ${index + 1}"
                            isDraggable = boundaryEdit.enabled
                            setOnMarkerClickListener { _, _ ->
                                boundaryEditing?.takeIf { it.enabled }?.onSelect?.invoke(index)
                                true
                            }
                            setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                                override fun onMarkerDragStart(marker: Marker) = Unit
                                override fun onMarkerDrag(marker: Marker) = Unit
                                override fun onMarkerDragEnd(marker: Marker) {
                                    boundaryEditing?.takeIf { it.enabled }?.onMove?.invoke(index,
                                        MarkerCoordinates(marker.position.latitude, marker.position.longitude))
                                }
                            })
                        })
                    } else drawing.forEach { point ->
                        view.overlays.add(handle(point, true).apply { setOnMarkerClickListener { _, _ -> true } })
                    }
                }
                if (ownLocationVisible) view.overlays.add(ownLocationOverlay)
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
        LaunchedEffect(ownLocationVisible) {
            if (ownLocationVisible) ownLocationOverlay.enableMyLocation()
            else ownLocationOverlay.disableMyLocation()
        }
        DisposableEffect(ownLocationOverlay) {
            onDispose { ownLocationOverlay.disableMyLocation() }
        }
        if (snapshot != null && boundaryEdit == null) {
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
                Surface(color = Color.White.copy(alpha = 0.94f), shadowElevation = 4.dp,
                    shape = RoundedCornerShape(4.dp)) {
                    IconButton(onClick = { locationLegendExpanded = !locationLegendExpanded }) {
                        Icon(Icons.Default.Info,
                            if (locationLegendExpanded) "Legende ausblenden" else "Legende anzeigen")
                    }
                }
            }
        }
        if (boundaryEdit == null) Row(Modifier.align(Alignment.TopEnd).padding(8.dp), verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Surface(color = Color.White.copy(alpha = 0.94f), shadowElevation = 4.dp,
                shape = RoundedCornerShape(4.dp)) {
                Row {
                    IconButton(onClick = onToggleOwnLocation) {
                        Icon(Icons.Default.MyLocation,
                            if (ownLocationVisible) "Eigenen Standort ausblenden" else "Eigenen Standort anzeigen")
                    }
                    if (snapshot != null) {
                        IconButton(onClick = {
                            val bounds = geometryPolygons(snapshot.profile.boundary).flatMap { it.first() }.toMutableList()
                            bounds.addAll(snapshot.points.filter { it.visibleAt(now) }
                                .map { GeoPoint(it.latitude, it.longitude) })
                            if (bounds.isNotEmpty()) {
                                map.post { map.zoomToBoundingBox(BoundingBox.fromGeoPoints(bounds), false, 48, 17.0, null) }
                            }
                        }) { Icon(Icons.Default.ZoomOutMap, "Revier einpassen") }
                        if (snapshot.profile.boundary != null) {
                            IconButton(onClick = { boundaryVisible = !boundaryVisible }) {
                                Icon(if (boundaryVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    if (boundaryVisible) "Reviergrenze ausblenden" else "Reviergrenze anzeigen")
                            }
                        }
                        IconButton(onClick = onManage) { Icon(Icons.Default.MoreVert, "Revier und Verwaltung") }
                    }
                    IconButton(onClick = onToggleFullScreen) {
                        Icon(if (isFullScreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                            if (isFullScreen) "Vollbild beenden" else "Karte im Vollbild")
                    }
                }
            }
        }
        if (boundaryEdit != null) {
            Icon(Icons.Default.Add, "Grenzposition", Modifier.align(Alignment.Center).size(28.dp),
                tint = MaterialTheme.colorScheme.primary)
            FloatingActionButton(onClick = {
                if (boundaryEdit.enabled) {
                    val center = map.mapCenter
                    boundaryEdit.onPlace(MarkerCoordinates(center.latitude, center.longitude))
                }
            }, modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)) {
                Icon(if (boundaryEdit.drawingPoints != null) Icons.Default.Add else Icons.Default.Check,
                    if (boundaryEdit.drawingPoints != null) "Grenzpunkt an Kartenmitte hinzufuegen" else "Grenzpunkt zur Kartenmitte verschieben")
            }
        }
        if (snapshot != null && boundaryEdit == null) {
            if (choosingPosition) {
                Icon(Icons.Default.LocationOn, "Zielposition", Modifier.align(Alignment.Center).offset(y = (-20).dp).size(40.dp),
                    tint = MaterialTheme.colorScheme.primary)
                FilledTonalIconButton(onClick = { choosingPosition = false }, Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                    Icon(Icons.Default.Close, "Positionswahl abbrechen")
                }
            }
            FloatingActionButton(onClick = {
                if (choosingPosition) {
                    val center = map.mapCenter
                    if (chooseLocation(MarkerCoordinates(center.latitude, center.longitude))) choosingPosition = false
                } else {
                    choosingPosition = true
                }
            }, modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)) {
                Icon(if (choosingPosition) Icons.Default.Check else Icons.Default.Add,
                    if (choosingPosition) "Hier setzen" else "Markierung setzen")
            }
        }
        Column(Modifier.align(Alignment.BottomStart).padding(8.dp).widthIn(max = 200.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Surface(color = Color.White.copy(alpha = 0.94f), shape = RoundedCornerShape(4.dp)) {
                MapStyleMenu(mapStyle, availableOffline.isNotEmpty(), aerialAvailable,
                    onStyle = { mapStyle = it; TrackerPreferences.setHuntingMapStyle(context, it.name) }, onManageOffline = {
                        if (onManageOfflineMaps != null) onManageOfflineMaps() else localOfflineManagerOpen = true
                    })
            }
            if (tileSourceError.isNotBlank()) Surface(color = Color.White.copy(alpha = 0.94f), shape = RoundedCornerShape(4.dp)) {
                Text(tileSourceError, Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall)
            }
            if (statusMessage.isNotBlank() && !statusMessage.startsWith("Stand:")) {
                Surface(color = Color.White.copy(alpha = 0.94f), shadowElevation = 4.dp,
                    shape = RoundedCornerShape(4.dp)) {
                    Text(statusMessage, Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Surface(color = Color.White.copy(alpha = 0.94f), shape = RoundedCornerShape(4.dp)) {
                TextButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(if (mapStyle == HuntingMapStyle.AERIAL)
                        NrwAerialImagery.LICENSE_URL else "https://www.openstreetmap.org/copyright")))
                }) { Text(if (mapStyle == HuntingMapStyle.AERIAL) NrwAerialImagery.ATTRIBUTION
                    else "© OpenStreetMap", style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
    if (localOfflineManagerOpen) OfflineMapsDialog(snapshot?.profile?.boundary, onDismiss = { localOfflineManagerOpen = false })
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

internal fun markerSvgPicture(context: Context, asset: String, width: Int, height: Int): Picture =
    synchronized(huntingMarkerPictureCache) {
        huntingMarkerPictureCache.getOrPut(Triple(asset, width, height)) {
            Picture().also { picture ->
                val canvas = picture.beginRecording(width, height)
                val svg = SVG.getFromAsset(context.assets, asset)
                canvas.scale(width / svg.documentWidth, height / svg.documentHeight)
                svg.renderToCanvas(canvas)
                picture.endRecording()
            }
        }
    }

private fun huntingMarkerIcon(
    context: android.content.Context,
    markerTypeId: String?,
    size: HuntingMarkerSize
): PictureDrawable {
    val type = HuntingMarkerType.fromId(markerTypeId)
    val density = context.resources.displayMetrics.density
    val width = (40 * size.scale * density).toInt().coerceAtLeast(1)
    val height = (48 * size.scale * density).toInt().coerceAtLeast(1)
    val picture = markerSvgPicture(context, "markers/${type.iconAsset}", width, height)
    return PictureDrawable(picture)
}

private fun locationIcon(context: Context, asset: String, sizeDp: Int = 36): PictureDrawable {
    val density = context.resources.displayMetrics.density
    val size = (sizeDp * density).toInt().coerceAtLeast(1)
    val picture = markerSvgPicture(context, asset, size, size)
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