package org.wierichs.dogtracker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.wierichs.dogtracker.ui.theme.DogTrackerTheme
import org.wierichs.dogtracker.communication.RelayPairingPrompts
import org.wierichs.dogtracker.jagdkarte.JagdkarteScreen
import org.wierichs.dogtracker.jagdkarte.guestLinkTokenFromUrl
import org.wierichs.dogtracker.jagdkarte.EmailAuthLink
import org.wierichs.dogtracker.jagdkarte.emailAuthLinkFromUrl
import org.wierichs.dogtracker.communication.RelayGateway
import org.wierichs.dogtracker.DogTrackerPairingQrCode

private const val PENDING_PAIRING_FILE = "pending_dog_pairing"

class MainActivity : ComponentActivity() {
    private val pendingGuestLinkToken = mutableStateOf<String?>(null)
    private val pendingEmailAuthLink = mutableStateOf<EmailAuthLink?>(null)

    private fun receiveLinks(intent: Intent?) {
        val authLink = emailAuthLinkFromUrl(intent?.data?.toString())
        pendingEmailAuthLink.value = authLink
        pendingGuestLinkToken.value = if (authLink == null) guestLinkTokenFromUrl(intent?.data?.toString()) else null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        receiveLinks(intent)
        enableEdgeToEdge()
        setContent {
            DogTrackerTheme {
                JagdkarteApp(pendingGuestLinkToken.value, pendingEmailAuthLink.value,
                    onGuestLinkReceived = { pendingGuestLinkToken.value = null },
                    onEmailAuthLinkReceived = {
                        pendingEmailAuthLink.value = null
                        intent?.data = null
                    })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveLinks(intent)
    }
}

@Composable
private fun JagdkarteApp(
    pendingGuestLinkToken: String?,
    incomingEmailAuthLink: EmailAuthLink?,
    onGuestLinkReceived: () -> Unit,
    onEmailAuthLinkReceived: () -> Unit
) {
    val context = LocalContext.current
    val huntingPermissionState = rememberHuntingPermissionState()
    val huntingAccess by huntingPermissionState
    var permissionSetupOpen by rememberSaveable {
        mutableStateOf(pendingGuestLinkToken == null && incomingEmailAuthLink == null &&
            !TrackerPreferences.permissionIntroductionCompleted(context))
    }
    fun closePermissionSetup() {
        if (TrackerPreferences.requiresAutomaticHuntingConsent(context)) {
            TrackerPreferences.setHuntingFunctionsConsent(context, false)
        }
        TrackerPreferences.completePermissionIntroduction(context)
        permissionSetupOpen = false
    }
    fun disableHuntingFunctions() {
        TrackerPreferences.setHuntingFunctionsConsent(context, false)
        huntingPermissionState.value = readHuntingPermissions(context)
    }
    LaunchedEffect(huntingAccess) {
        if (TrackerPreferences.requiresAutomaticHuntingConsent(context) &&
            pendingGuestLinkToken == null && incomingEmailAuthLink == null) {
            permissionSetupOpen = true
        }
        if (!huntingAccess.ready) {
            if (RelayGateway.state.value.active || RelayGateway.state.value.starting) RelayGateway.stop(context)
            if (TrackerPreferences.isTracking(context)) {
                context.startService(Intent(context, LocationTrackingService::class.java)
                    .setAction(LocationTrackingService.ACTION_STOP))
            }
        } else if (TrackerPreferences.isTracking(context)) {
            context.startService(Intent(context, LocationTrackingService::class.java)
                .setAction(LocationTrackingService.ACTION_RELAY_READY))
        }
    }
    var trackerSelected by rememberSaveable {
        mutableStateOf(pendingGuestLinkToken == null && incomingEmailAuthLink == null &&
            TrackerPreferences.lastTabIsTracker(context))
    }
    var mapFullScreen by rememberSaveable { mutableStateOf(TrackerPreferences.mapFullScreen(context)) }
    fun selectTab(tracker: Boolean) {
        trackerSelected = tracker
        TrackerPreferences.setLastTabIsTracker(context, tracker)
        if (tracker) {
            mapFullScreen = false
            TrackerPreferences.setMapFullScreen(context, false)
        }
    }
    LaunchedEffect(pendingGuestLinkToken, incomingEmailAuthLink) {
        if (pendingGuestLinkToken != null || incomingEmailAuthLink != null) {
            selectTab(false)
            mapFullScreen = false
            TrackerPreferences.setMapFullScreen(context, false)
        }
    }
    val pairingPrompt by RelayPairingPrompts.current.collectAsState()
    Scaffold(bottomBar = {
        if (!mapFullScreen || trackerSelected) {
            NavigationBar {
                NavigationBarItem(selected = !trackerSelected, onClick = { selectTab(false) },
                    icon = { Icon(Icons.Default.Place, null) },
                    label = { Text("Jagdkarte") })
                NavigationBarItem(selected = trackerSelected, onClick = {
                    selectTab(true)
                }, icon = { Icon(Icons.Default.LocationOn, null) },
                    label = { Text("DogTracker") })
            }
        }
    }) { padding ->
        Surface(Modifier.fillMaxSize().padding(padding)) {
            if (trackerSelected) TrackerScreen(huntingAccess, onConfigurePermissions = { permissionSetupOpen = true },
                onDisableHuntingFunctions = { disableHuntingFunctions() }) else JagdkarteScreen(
                onUseDeviceToken = { token ->
                if (TrackerPreferences.isTracking(context)) {
                    false
                } else {
                    TrackerPreferences.saveDeviceToken(context, token)
                    selectTab(true)
                    true
                }
            },
                incomingGuestLinkToken = pendingGuestLinkToken,
                onGuestLinkReceived = onGuestLinkReceived,
                incomingEmailAuthLink = incomingEmailAuthLink,
                onEmailAuthLinkReceived = onEmailAuthLinkReceived,
                isFullScreen = mapFullScreen,
                onFullScreenChanged = {
                    mapFullScreen = it
                    TrackerPreferences.setMapFullScreen(context, it)
                },
                huntingAccess = huntingAccess,
                onConfigurePermissions = { permissionSetupOpen = true },
                onDisableHuntingFunctions = { disableHuntingFunctions() }
            )
        }
        if (permissionSetupOpen) {
            HuntingPermissionSetupDialog(huntingAccess,
                onRefresh = { huntingPermissionState.value = readHuntingPermissions(context) },
                onDismiss = { closePermissionSetup() })
        }
    }
    pairingPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = { RelayPairingPrompts.respond(prompt.endpointId, accept = false) },
            title = { Text("Handys verbinden") },
            text = {
                Text("Zahl auf diesem Handy: ${prompt.authenticationDigits}\n\n" +
                    "Vergleiche sie mit der Zahl auf dem anderen Handy. Bestätige auf beiden Handys nur, wenn die Zahlen gleich sind.")
            },
            confirmButton = {
                TextButton(onClick = { RelayPairingPrompts.respond(prompt.endpointId, accept = true) }) {
                    Text("Zahlen sind gleich")
                }
            },
            dismissButton = {
                TextButton(onClick = { RelayPairingPrompts.respond(prompt.endpointId, accept = false) }) {
                    Text("Nicht verbinden")
                }
            }
        )
    }
}

@Composable
internal fun TrackerScreen(
    huntingAccess: HuntingPermissionState,
    onConfigurePermissions: () -> Unit,
    onDisableHuntingFunctions: () -> Unit,
    completePairing: suspend (String, Boolean) -> String = { token, confirm ->
        DogTrackerPairingApi.complete(token, confirm)
    },
    stopRelayGateway: suspend (Context) -> Unit = { RelayGateway.stopAndAwait(it) },
    startTrackingService: (Context, Intent) -> Unit = { context, intent ->
        ContextCompat.startForegroundService(context, intent)
    }
) {
    val context = LocalContext.current
    var deviceToken by remember { mutableStateOf(TrackerPreferences.deviceToken(context).orEmpty()) }
    var pendingPairingToken by remember {
        mutableStateOf(DeviceTokenStorage.read(context, PENDING_PAIRING_FILE))
    }
    var pairingBusy by remember { mutableStateOf(false) }
    var pairingMessage by remember { mutableStateOf("") }
    var pairingResult by remember { mutableStateOf<String?>(null) }
    var trackingStarting by remember { mutableStateOf(false) }
    val pairingScope = rememberCoroutineScope()
    var showToken by remember { mutableStateOf(false) }
    var intervalSeconds by rememberSaveable {
        mutableStateOf(TrackerPreferences.intervalSeconds(context).toString())
    }
    var isTracking by rememberSaveable { mutableStateOf(TrackerPreferences.isTracking(context)) }
    var status by remember { mutableStateOf(TrackerPreferences.status(context)) }
    var validationMessage by remember { mutableStateOf("") }
    val hostActivity = context as? Activity

    DisposableEffect(pendingPairingToken) {
        if (pendingPairingToken != null) hostActivity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            if (pendingPairingToken != null) hostActivity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    fun startTracking() {
        if (trackingStarting) return
        if (!readHuntingPermissions(context).ready) {
            validationMessage = "Bitte die Jagdfunktionen und erforderlichen Berechtigungen zuerst einrichten."
            return
        }
        if (pendingPairingToken != null) {
            validationMessage = "Bitte die QR-Kopplung erst hier im DogTracker bestaetigen oder abbrechen."
            return
        }
        val normalizedToken = deviceToken.filterNot(Char::isWhitespace)
        val interval = intervalSeconds.toLongOrNull()
        if (!normalizedToken.matches(Regex("[0-9a-fA-F]{64}"))) {
            validationMessage = "Bitte den 64-stelligen Geräteschlüssel aus der Jagdkarte einfügen."
            return
        }
        if (interval == null || interval !in 15L..3600L) {
            validationMessage = "Das Intervall muss zwischen 15 und 3600 Sekunden liegen."
            return
        }

        validationMessage = ""
        trackingStarting = true
        pairingScope.launch {
            var started = false
            try {
                TrackerPreferences.saveDeviceToken(context, normalizedToken.lowercase())
                TrackerPreferences.saveConfiguration(context, interval)
                TrackerPreferences.setTracking(context, true)
                stopRelayGateway(context)
                check(readHuntingPermissions(context).ready) {
                    "Jagdfreigabe oder erforderliche Berechtigung wurde entzogen."
                }
                startTrackingService(context, Intent(context, LocationTrackingService::class.java).apply {
                    action = LocationTrackingService.ACTION_START
                    putExtra(LocationTrackingService.EXTRA_INTERVAL_SECONDS, interval)
                })
                started = true
                isTracking = true
                status = "Standortdienst wird gestartet."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                validationMessage = failure.message ?: "Tracking konnte nicht gestartet werden."
            } finally {
                if (!started) {
                    TrackerPreferences.setTracking(context, false)
                    isTracking = false
                }
                trackingStarting = false
            }
        }
    }

    fun setPairingStatus(message: String) {
        TrackerPreferences.setStatus(context, message)
        status = message
        pairingResult = message
    }

    fun resolvePendingPairing(confirm: Boolean) {
        val token = pendingPairingToken ?: return
        if (pairingBusy) return
        pairingBusy = true
        validationMessage = ""
        pairingMessage = if (confirm) "Kopplung wird bestaetigt. Bitte warten." else "Kopplung wird abgebrochen. Bitte warten."
        pairingScope.launch {
            try {
                val resolution = settleDogTrackerPairing(
                    token = token,
                    confirm = confirm,
                    completeOnServer = completePairing,
                    saveActiveToken = { TrackerPreferences.saveDeviceToken(context, it) },
                    clearPendingToken = { DeviceTokenStorage.clear(context, PENDING_PAIRING_FILE) }
                )
                when (resolution) {
                    DogTrackerPairingResolution.ACTIVATED -> {
                        deviceToken = token
                        pendingPairingToken = null
                        setPairingStatus("Kopplung erfolgreich. Dieses Smartphone ist jetzt mit dem Hund verbunden. Jetzt Tracking starten.")
                    }
                    DogTrackerPairingResolution.ALREADY_ACTIVE -> {
                        deviceToken = token
                        pendingPairingToken = null
                        setPairingStatus(if (confirm) {
                            "Kopplung erfolgreich. Sie war bereits bestaetigt. Jetzt Tracking starten."
                        } else {
                            "Die Kopplung war schon abgeschlossen; der Schluessel wurde uebernommen."
                        })
                    }
                    DogTrackerPairingResolution.CANCELLED -> {
                        pendingPairingToken = null
                        setPairingStatus("Kopplung abgebrochen. Der bisherige Schluessel bleibt aktiv.")
                    }
                    DogTrackerPairingResolution.NOT_FOUND -> {
                        if (confirm) {
                            pairingMessage = "Noch keine Kopplung vorgemerkt. Scanne diesen QR-Code zuerst in der Jagdkarte beim passenden Hund und tippe danach hier erneut auf Kopplung bestaetigen."
                        } else {
                            pendingPairingToken = null
                            setPairingStatus("Kopplung abgebrochen. Der bisherige Schluessel bleibt aktiv.")
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                pairingMessage = "Kopplung konnte nicht abgeschlossen werden: ${failure.message ?: "Unbekannter Fehler."} Bitte die Internetverbindung pruefen und erneut versuchen. Der QR-Code bleibt fuer einen erneuten Versuch erhalten."
            } finally {
                pairingBusy = false
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            isTracking = TrackerPreferences.isTracking(context)
            status = TrackerPreferences.status(context)
            delay(1500)
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize(), contentWindowInsets = WindowInsets(0, 0, 0, 0)) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Hundetracker", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Scanne den QR-Code in der Jagdkarte oder füge den einmalig angezeigten Geräteschlüssel ein. Das Smartphone erfasst und überträgt den Standort nur während einer gestarteten Tracking-Sitzung.",
                style = MaterialTheme.typography.bodyLarge
            )

            OutlinedTextField(
                value = deviceToken,
                onValueChange = { deviceToken = it.filterNot(Char::isWhitespace).take(64) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Geräteschlüssel") },
                placeholder = { Text("64-stelligen Schlüssel hier einfügen") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { showToken = !showToken }) {
                        Text(if (showToken) "Verbergen" else "Anzeigen")
                    }
                },
                enabled = !isTracking && !trackingStarting && pendingPairingToken == null
            )

            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = !isTracking && !trackingStarting && pendingPairingToken == null,
                onClick = {
                    val options = GmsBarcodeScannerOptions.Builder()
                        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                        .build()
                    GmsBarcodeScanning.getClient(context, options).startScan()
                        .addOnSuccessListener { barcode ->
                            val scannedToken = barcode.rawValue.orEmpty().filterNot(Char::isWhitespace)
                            if (scannedToken.matches(Regex("[0-9a-fA-F]{64}"))) {
                                deviceToken = scannedToken.lowercase()
                                validationMessage = ""
                                status = "QR-Code gelesen. Tippe auf Tracking starten."
                            } else {
                                validationMessage = "Der QR-Code enthält keinen gültigen Geräteschlüssel."
                            }
                        }
                        .addOnFailureListener {
                            validationMessage = "QR-Scanner nicht verfügbar. Du kannst den Schlüssel manuell eingeben."
                        }
                }
            ) {
                Text("QR-Code scannen")
            }

            if (pendingPairingToken == null) {
                OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !isTracking && !trackingStarting, onClick = {
                    val token = createDogTrackerDeviceToken()
                    DeviceTokenStorage.write(context, token, PENDING_PAIRING_FILE)
                    pendingPairingToken = token
                    validationMessage = ""
                    pairingMessage = ""
                }) { Text("QR-Code fuer Jagdkarte anzeigen") }
            } else {
                Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Mit Jagdkarte koppeln", style = MaterialTheme.typography.titleSmall)
                        Text("In Jagdkarte unter Hunde den Hund auswaehlen und diesen QR-Code scannen. Danach hier auf Kopplung bestaetigen tippen.",
                            style = MaterialTheme.typography.bodySmall)
                        Text("Der bisherige Schluessel bleibt bis zur Bestaetigung aktiv. Zum Bestaetigen oder Abbrechen ist eine Internetverbindung erforderlich.",
                            style = MaterialTheme.typography.bodySmall)
                        DogTrackerPairingQrCode(dogTrackerPairingQr(pendingPairingToken!!),
                            "Einmaliger DogTracker-Kopplungscode",
                            Modifier.fillMaxWidth().height(220.dp))
                        if (pairingMessage.isNotBlank()) {
                            Text(pairingMessage, color = if (pairingBusy) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error)
                        }
                        if (pairingBusy) CircularProgressIndicator()
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(enabled = !pairingBusy, onClick = {
                                resolvePendingPairing(confirm = false)
                            }, modifier = Modifier.weight(1f)) {
                                Text(if (pairingBusy) "Bitte warten" else "Abbrechen")
                            }
                            Button(enabled = !pairingBusy, onClick = {
                                resolvePendingPairing(confirm = true)
                            }, modifier = Modifier.weight(1f)) {
                                Text(if (pairingBusy) "Bitte warten" else "Kopplung bestaetigen")
                            }
                        }
                    }
                }
            }

            Text("Ziel: Jagdkarte Hundestandort", style = MaterialTheme.typography.bodyMedium)

            HuntingPermissionSettings(huntingAccess, onConfigurePermissions, onDisableHuntingFunctions)

            OutlinedTextField(
                value = intervalSeconds,
                onValueChange = { intervalSeconds = it.filter(Char::isDigit).take(4) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Intervall in Sekunden") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                enabled = !isTracking && !trackingStarting
            )

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Status", style = MaterialTheme.typography.titleSmall)
                    Text(if (isTracking) "Tracking aktiv" else "Tracking gestoppt")
                    Text(status, style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (validationMessage.isNotBlank()) {
                Text(validationMessage, color = MaterialTheme.colorScheme.error)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (trackingStarting) {
                    CircularProgressIndicator()
                    Text("Tracking wird vorbereitet; der DogTracker uebernimmt das Relais.")
                } else if (isTracking) {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = pendingPairingToken == null,
                        onClick = {
                            context.startService(
                                Intent(context, LocationTrackingService::class.java).apply {
                                    action = LocationTrackingService.ACTION_STOP
                                }
                            )
                        }
                    ) {
                        Text("Tracking stoppen")
                    }
                } else if (huntingAccess.ready) {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { startTracking() }
                    ) {
                        Text("Tracking starten")
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                "Übertragen werden Position, GPS-Genauigkeit und Messzeitpunkt. Bei fehlender Verbindung werden Positionen lokal gepuffert.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    pairingResult?.let { message ->
        AlertDialog(
            onDismissRequest = { pairingResult = null },
            title = { Text("DogTracker-Kopplung") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { pairingResult = null }) { Text("Verstanden") } }
        )
    }
}