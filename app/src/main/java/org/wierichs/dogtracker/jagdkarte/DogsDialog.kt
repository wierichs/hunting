package org.wierichs.dogtracker.jagdkarte

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.sizeIn
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import org.wierichs.dogtracker.DogTrackerPairingQrCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.platform.LocalContext
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import org.wierichs.dogtracker.dogTrackerDeviceTokenFromQr

@Composable
internal fun DogsDialog(
    repository: JagdkarteRepository,
    profile: HuntingProfile,
    canEdit: Boolean,
    onUseDeviceToken: (String) -> Boolean,
    onDismiss: () -> Unit,
    loadDogs: suspend (String) -> List<HuntingDog> = { repository.dogs(it) },
    deleteDog: suspend (String) -> Unit = { repository.deleteDog(it) },
    registerDeviceToken: suspend (String, String) -> Unit = { dogId, token ->
        repository.registerDogDeviceToken(dogId, token)
    },
    scanDogTrackerQr: (((String?) -> Unit) -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    var dogs by remember(profile.id) { mutableStateOf(emptyList<HuntingDog>()) }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var selectedDog by remember { mutableStateOf<HuntingDog?>(null) }
    var pendingDogDeletion by remember(profile.id) { mutableStateOf<HuntingDog?>(null) }
    var deviceToken by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

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
            deviceToken != null -> "Geraeteschluessel"
            pendingDogDeletion != null -> "Hund loeschen?"
            selectedDog != null -> "Smartphone neu koppeln?"
            else -> "Hunde im Revier"
        }) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val token = deviceToken
                val dogToPair = selectedDog
                val dogToDelete = pendingDogDeletion
                if (dogToDelete != null) {
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
                    Text("${dogToPair.name}: Beim Koppeln wird der bisherige Geraeteschluessel widerrufen.")
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
                                    registerDeviceToken(dogToPair.id, deviceToken)
                                    selectedDog = null
                                    notice = "DogTracker erfolgreich gekoppelt. Kehre zum Tracker zurueck und bestaetige den Schluessel."
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
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(dog.name, Modifier.weight(1f), maxLines = 1)
                            if (canEdit) {
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
                    if (canEdit) {
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
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                if (notice.isNotBlank()) Text(notice, color = MaterialTheme.colorScheme.primary)
            }
        },
        confirmButton = {
            if (pendingDogDeletion != null && deviceToken == null) {
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