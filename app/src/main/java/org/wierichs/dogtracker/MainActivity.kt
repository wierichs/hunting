package org.wierichs.dogtracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.delay
import org.wierichs.dogtracker.ui.theme.DogTrackerTheme
import org.wierichs.dogtracker.communication.RelayPairingPrompts

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DogTrackerTheme {
                TrackerScreen()
            }
        }
    }
}

@Composable
private fun TrackerScreen() {
    val context = LocalContext.current
    var deviceToken by remember { mutableStateOf(TrackerPreferences.deviceToken(context).orEmpty()) }
    var showToken by remember { mutableStateOf(false) }
    var intervalSeconds by rememberSaveable {
        mutableStateOf(TrackerPreferences.intervalSeconds(context).toString())
    }
    var isTracking by rememberSaveable { mutableStateOf(TrackerPreferences.isTracking(context)) }
    var relayEnabled by rememberSaveable { mutableStateOf(TrackerPreferences.relayEnabled(context)) }
    var status by remember { mutableStateOf(TrackerPreferences.status(context)) }
    var validationMessage by remember { mutableStateOf("") }
    val pairingPrompt by RelayPairingPrompts.current.collectAsState()

    fun startTracking() {
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
    TrackerPreferences.saveDeviceToken(context, normalizedToken.lowercase())
    TrackerPreferences.saveConfiguration(context, interval)
        TrackerPreferences.setTracking(context, true)
        ContextCompat.startForegroundService(
            context,
            Intent(context, LocationTrackingService::class.java).apply {
                action = LocationTrackingService.ACTION_START
                putExtra(LocationTrackingService.EXTRA_INTERVAL_SECONDS, interval)
            }
        )
        isTracking = true
        status = "Standortdienst wird gestartet."
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val preciseLocationGranted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        val notificationsGranted = Build.VERSION.SDK_INT < 33 ||
            grants[Manifest.permission.POST_NOTIFICATIONS] == true ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

        if (preciseLocationGranted && notificationsGranted) {
            startTracking()
        } else {
            validationMessage = "Für das Tracking sind genauer Standort und Benachrichtigungen erforderlich."
        }
    }

    val nearbyPermissions = buildList {
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT == 31) add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 32) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        if (Build.VERSION.SDK_INT >= 37) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
    }

    val nearbyPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val allGranted = nearbyPermissions.all { permission ->
            grants[permission] == true || ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            TrackerPreferences.setRelayEnabled(context, true)
            relayEnabled = true
            status = "Relaisfreigabe aktiv. Tracker-Verbindungen werden per Sicherheitscode bestätigt."
            if (isTracking) {
                context.startService(
                    Intent(context, LocationTrackingService::class.java).apply {
                        action = LocationTrackingService.ACTION_RELAY_READY
                    }
                )
            }
        } else {
            TrackerPreferences.setRelayEnabled(context, false)
            relayEnabled = false
            status = "Relaisfreigabe benötigt die Berechtigungen für Geräte in der Nähe."
        }
    }

    fun enableNearbyRelay() {
        if (nearbyPermissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        ) {
            TrackerPreferences.setRelayEnabled(context, true)
            relayEnabled = true
            status = "Relaisfreigabe aktiv. Tracker-Verbindungen werden per Sicherheitscode bestätigt."
            if (isTracking) {
                context.startService(
                    Intent(context, LocationTrackingService::class.java).apply {
                        action = LocationTrackingService.ACTION_RELAY_READY
                    }
                )
            }
        } else {
            nearbyPermissionLauncher.launch(nearbyPermissions.toTypedArray())
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            isTracking = TrackerPreferences.isTracking(context)
            status = TrackerPreferences.status(context)
            delay(1500)
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
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
                enabled = !isTracking
            )

            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = !isTracking,
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

            Text("Ziel: Jagdkarte Hundestandort", style = MaterialTheme.typography.bodyMedium)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Als Tracker-Relais teilnehmen", style = MaterialTheme.typography.titleSmall)
                    Text("Verschlüsselte Meldungen anderer Geräte über dieses Smartphone weiterleiten.", style = MaterialTheme.typography.bodySmall)
                }
                Checkbox(
                    checked = relayEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            enableNearbyRelay()
                        } else {
                            TrackerPreferences.setRelayEnabled(context, false)
                            relayEnabled = false
                            status = "Relaisfreigabe ausgeschaltet."
                            if (isTracking) {
                                context.startService(
                                    Intent(context, LocationTrackingService::class.java).apply {
                                        action = LocationTrackingService.ACTION_RELAY_STOP
                                    }
                                )
                            }
                        }
                    }
                )
            }

            OutlinedTextField(
                value = intervalSeconds,
                onValueChange = { intervalSeconds = it.filter(Char::isDigit).take(4) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Intervall in Sekunden") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                enabled = !isTracking
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
                if (isTracking) {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
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
                } else {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            val permissions = buildList {
                                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                                add(Manifest.permission.ACCESS_FINE_LOCATION)
                                if (Build.VERSION.SDK_INT >= 33) {
                                    add(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                            val allGranted = permissions.all {
                                ContextCompat.checkSelfPermission(context, it) ==
                                    PackageManager.PERMISSION_GRANTED
                            }
                            if (allGranted) {
                                startTracking()
                            } else {
                                permissionLauncher.launch(permissions.toTypedArray())
                            }
                        }
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

    pairingPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = { RelayPairingPrompts.respond(prompt.endpointId, accept = false) },
            title = { Text("Tracker-Verbindung prüfen") },
            text = {
                Text("Vergleiche diesen Sicherheitscode mit dem anderen Tracker: ${prompt.authenticationDigits}. Nur fortfahren, wenn beide Codes übereinstimmen.")
            },
            confirmButton = {
                TextButton(onClick = { RelayPairingPrompts.respond(prompt.endpointId, accept = true) }) {
                    Text("Code stimmt")
                }
            },
            dismissButton = {
                TextButton(onClick = { RelayPairingPrompts.respond(prompt.endpointId, accept = false) }) {
                    Text("Ablehnen")
                }
            }
        )
    }
}