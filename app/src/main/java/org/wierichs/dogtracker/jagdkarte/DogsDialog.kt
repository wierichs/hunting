package org.wierichs.dogtracker.jagdkarte

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import org.wierichs.dogtracker.DogTrackerPairingQrCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.platform.LocalContext
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import org.wierichs.dogtracker.dogTrackerDeviceTokenFromQr

internal data class DogsDialogTarget(val profile: HuntingProfile, val canEdit: Boolean)

@Composable
internal fun DogsDialog(
    repository: JagdkarteRepository,
    profile: HuntingProfile,
    canEdit: Boolean,
    onUseDeviceToken: (String) -> Boolean,
    onDismiss: () -> Unit,
    loadDogs: suspend (String) -> List<HuntingDog> = { repository.dogs(it) },
    deleteDog: suspend (String) -> Unit = { repository.deleteDog(it) },
    stageDeviceToken: suspend (String, String) -> Unit = { dogId, token ->
        repository.stageDogDeviceToken(dogId, token)
    },
    scanDogTrackerQr: (((String?) -> Unit) -> Unit)? = null,
    loadPairingStatus: suspend (String, String) -> DogDevicePairingStatus = { dogId, token ->
        repository.dogDevicePairingStatus(dogId, token)
    },
    loadDogPairingStates: suspend (String) -> Map<String, DogPairingState> = {
        repository.dogPairingStates(it)
    }
) {
    val scope = rememberCoroutineScope()
    var dogs by remember(profile.id) { mutableStateOf(emptyList<HuntingDog>()) }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var stagedDog by remember(profile.id) { mutableStateOf<HuntingDog?>(null) }
    var stagedToken by remember(profile.id) { mutableStateOf<String?>(null) }
    var pairingStatus by remember(profile.id) { mutableStateOf<DogDevicePairingStatus?>(null) }
    var checkingPairing by remember { mutableStateOf(false) }
    var pairingCheckRevision by remember { mutableStateOf(0) }
    var selectedDog by remember { mutableStateOf<HuntingDog?>(null) }
    var pendingDogDeletion by remember(profile.id) { mutableStateOf<HuntingDog?>(null) }
    var deviceToken by remember { mutableStateOf<String?>(null) }
    var dogPairingStates by remember(profile.id) { mutableStateOf(emptyMap<String, DogPairingState>()) }
    var dogStatesError by remember { mutableStateOf("") }
    var loadingDogStates by remember { mutableStateOf(false) }
    var dogStatesRevision by remember { mutableStateOf(0) }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val showDogStates = canEdit && stagedDog == null && selectedDog == null &&
        pendingDogDeletion == null && deviceToken == null
    val pairedColor = if (isSystemInDarkTheme()) Color(0xFF81C784) else Color(0xFF2E7D32)

    LaunchedEffect(profile.id, showDogStates, dogStatesRevision, lifecycleOwner) {
        if (!showDogStates) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            dogPairingStates = emptyMap()
            while (true) {
                loadingDogStates = true
                dogStatesError = ""
                try {
                    dogPairingStates = loadDogPairingStates(profile.id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    dogPairingStates = emptyMap()
                    dogStatesError = failure.message ?: "Der Kopplungsstatus konnte nicht geladen werden."
                    return@repeatOnLifecycle
                } finally {
                    loadingDogStates = false
                }
                delay(5_000)
            }
        }
    }

    LaunchedEffect(stagedToken, pairingCheckRevision, lifecycleOwner) {
        val token = stagedToken ?: return@LaunchedEffect
        val dog = stagedDog ?: return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            pairingStatus = null
            do {
                checkingPairing = true
                error = ""
                try {
                    pairingStatus = loadPairingStatus(dog.id, token)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    error = failure.message ?: "Die Kopplung konnte nicht geprueft werden."
                    return@repeatOnLifecycle
                } finally {
                    checkingPairing = false
                }
                if (pairingStatus != DogDevicePairingStatus.PENDING) break
                delay(2_000)
            } while (true)
        }
    }

    LaunchedEffect(profile.id) {
        busy = true
        try {
            dogs = loadDogs(profile.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "Hunde konnten nicht geladen werden."
        } finally {
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) { deviceToken = null; onDismiss() } },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(when {
            stagedDog != null -> when (pairingStatus) {
                DogDevicePairingStatus.ACTIVE -> "Kopplung erfolgreich"
                DogDevicePairingStatus.NOT_FOUND -> "Kopplung nicht abgeschlossen"
                else -> "Warten auf Bestaetigung"
            }
            deviceToken != null -> "Geraeteschluessel"
            pendingDogDeletion != null -> "Hund loeschen?"
            selectedDog != null -> "Smartphone neu koppeln?"
            else -> "Hunde im Revier"
        }) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                if (showDogStates && dogStatesError.isNotBlank()) {
                    Text(dogStatesError, color = MaterialTheme.colorScheme.error)
                }
                val token = deviceToken
                val dogToPair = selectedDog
                val dogToDelete = pendingDogDeletion
                val waitingDog = stagedDog
                if (waitingDog != null) {
                    Text("${waitingDog.name} in ${profile.name}", style = MaterialTheme.typography.titleMedium)
                    Text(when (pairingStatus) {
                        DogDevicePairingStatus.ACTIVE ->
                            "Das DogTracker-Smartphone ist jetzt mit dem Hund gekoppelt. Du kannst im DogTracker das Tracking starten."
                        DogDevicePairingStatus.NOT_FOUND ->
                            "Diese Kopplung ist weder vorgemerkt noch aktiv. Sie wurde moeglicherweise abgebrochen oder ersetzt. Bitte einen neuen QR-Code im DogTracker anzeigen und erneut scannen."
                        else ->
                            "QR-Code gelesen. Tippe jetzt auf dem anderen Smartphone im DogTracker auf Kopplung bestaetigen. Erst danach ist die neue Kopplung abgeschlossen."
                    })
                    if (checkingPairing) {
                        CircularProgressIndicator()
                        Text("Kopplung wird auf dem Server geprueft.")
                    }
                    if (pairingStatus != DogDevicePairingStatus.ACTIVE) {
                        Text("Dieser Dialog prueft die Bestaetigung automatisch, solange er geoeffnet und die App sichtbar ist.")
                        OutlinedButton(enabled = !checkingPairing, onClick = { pairingCheckRevision++ }) {
                            Text("Kopplung erneut pruefen")
                        }
                    }
                } else if (dogToDelete != null) {
                    Text("${dogToDelete.name} wird dauerhaft aus diesem Revier entfernt. Die zugehoerige Smartphone-Kopplung und gespeicherte Hundeposition werden ebenfalls geloescht.")
                    Text("Diese Aktion kann nicht rueckgaengig gemacht werden.")
                } else if (token != null) {
                    DogTrackerPairingQrCode(token, "QR-Code des einmaligen Geraeteschluessels",
                        Modifier.fillMaxWidth().sizeIn(maxWidth = 256.dp, maxHeight = 256.dp))
                    Text(token, style = MaterialTheme.typography.bodySmall)
                    Text("Nur einmal sichtbar. Geheim halten.", style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = {
                        if (onUseDeviceToken(token)) {
                            deviceToken = null
                            onDismiss()
                        } else {
                            error = "Zuerst die laufende Tracking-Sitzung beenden."
                        }
                    }) { Text("Auf diesem Geraet verwenden") }
                } else if (dogToPair != null) {
                    Text("${dogToPair.name}: Der bisherige Geraeteschluessel bleibt aktiv, bis du die Kopplung im DogTracker bestaetigst.")
                    OutlinedButton(enabled = !busy, onClick = {
                        fun handleScan(rawValue: String?) {
                            val deviceToken = dogTrackerDeviceTokenFromQr(rawValue)
                            if (deviceToken == null) {
                                error = "Kein gueltiger DogTracker-Kopplungs-QR-Code erkannt."
                                return
                            }
                            busy = true
                            error = ""
                            scope.launch {
                                try {
                                    stageDeviceToken(dogToPair.id, deviceToken)
                                    selectedDog = null
                                    stagedDog = dogToPair
                                    stagedToken = deviceToken
                                    pairingStatus = null
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    error = failure.message ?: "DogTracker konnte nicht gekoppelt werden."
                                } finally {
                                    busy = false
                                }
                            }
                        }
                        val injectedScanner = scanDogTrackerQr
                        if (injectedScanner != null) {
                            injectedScanner(::handleScan)
                        } else {
                            val options = GmsBarcodeScannerOptions.Builder()
                                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                                .build()
                            GmsBarcodeScanning.getClient(context, options).startScan()
                                .addOnSuccessListener { handleScan(it.rawValue) }
                                .addOnFailureListener { error = "QR-Code konnte nicht gelesen werden." }
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("DogTracker-QR scannen") }
                } else {
                    if (dogs.isEmpty() && !busy) Text("Keine Hunde registriert.")
                    dogs.forEach { dog ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val state = dogPairingStates[dog.id]
                            if (canEdit && state != null) {
                                Icon(if (state.paired) Icons.Default.CheckCircle else Icons.Default.Close,
                                    contentDescription = "${dog.name}: ${if (state.paired) "gekoppelt" else "nicht gekoppelt"}",
                                    tint = if (state.paired) pairedColor else MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(24.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(dog.name, maxLines = 1)
                                if (canEdit) {
                                    Text(when {
                                        state == null -> if (loadingDogStates) "Status wird geladen" else "Status unbekannt"
                                        state.paired -> "Gekoppelt"
                                        else -> "Nicht gekoppelt"
                                    }, style = MaterialTheme.typography.bodySmall)
                                    if (state?.pending == true) {
                                        Text(if (state.paired) "Neue Kopplung wartet auf Bestaetigung"
                                            else "Bestaetigung ausstehend", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            if (canEdit) {
                                Column(horizontalAlignment = Alignment.End) {
                                    TextButton(enabled = !busy, onClick = { selectedDog = dog; error = "" }) {
                                        Text("Koppeln")
                                    }
                                    TextButton(enabled = !busy, onClick = {
                                        pendingDogDeletion = dog
                                        error = ""
                                    }, colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error)) { Text("Loeschen") }
                                }
                            }
                        }
                    }
                    if (canEdit) {
                        TextButton(enabled = !loadingDogStates, onClick = { dogStatesRevision++ }) {
                            Text("Kopplungsstatus aktualisieren")
                        }
                        OutlinedTextField(name, onValueChange = { name = it.take(60) }, label = { Text("Name des Hundes") },
                            enabled = !busy, singleLine = true)
                        TextButton(enabled = !busy && name.isNotBlank(), onClick = {
                            busy = true
                            error = ""
                            scope.launch {
                                try {
                                    repository.createDog(profile.id, name)
                                    name = ""
                                    dogs = repository.dogs(profile.id)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    error = failure.message ?: "Hund konnte nicht registriert werden."
                                } finally {
                                    busy = false
                                }
                            }
                        }) { Text("Hund registrieren") }
                    }
                }
                if (busy) CircularProgressIndicator()
            }
        },
        confirmButton = {
            if (stagedDog != null) {
                TextButton(onClick = {
                    stagedToken = null
                    stagedDog = null
                    pairingStatus = null
                    error = ""
                }) { Text("Zur Hundeliste") }
            } else if (pendingDogDeletion != null && deviceToken == null) {
                TextButton(enabled = !busy, onClick = {
                    val dogId = pendingDogDeletion?.id ?: return@TextButton
                    busy = true
                    error = ""
                    scope.launch {
                        try {
                            deleteDog(dogId)
                            dogs = dogs.filterNot { it.id == dogId }
                            pendingDogDeletion = null
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            error = failure.message ?: "Hund konnte nicht geloescht werden."
                        } finally {
                            busy = false
                        }
                    }
                }, colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error)) { Text("Hund dauerhaft loeschen") }
            } else if (selectedDog != null && deviceToken == null) {
                TextButton(enabled = !busy, onClick = {
                    val dogId = selectedDog?.id ?: return@TextButton
                    busy = true
                    error = ""
                    scope.launch {
                        try {
                            deviceToken = repository.pairDog(dogId)
                            selectedDog = null
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            error = failure.message ?: "Kopplung fehlgeschlagen."
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("Neu koppeln") }
            } else {
                TextButton(enabled = !busy, onClick = { deviceToken = null; onDismiss() }) { Text("Schliessen") }
            }
        },
        dismissButton = {
            if (pendingDogDeletion != null && deviceToken == null) TextButton(enabled = !busy,
                onClick = { pendingDogDeletion = null }) { Text("Abbrechen") }
            else if (selectedDog != null && deviceToken == null) TextButton(enabled = !busy,
                onClick = { selectedDog = null }) { Text("Abbrechen") }
        }
    )
}