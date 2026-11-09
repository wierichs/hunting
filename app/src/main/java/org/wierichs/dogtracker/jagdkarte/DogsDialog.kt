package org.wierichs.dogtracker.jagdkarte

import android.graphics.Bitmap
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun DogsDialog(
    repository: JagdkarteRepository,
    profile: HuntingProfile,
    canEdit: Boolean,
    onUseDeviceToken: (String) -> Boolean,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var dogs by remember(profile.id) { mutableStateOf(emptyList<HuntingDog>()) }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var selectedDog by remember { mutableStateOf<HuntingDog?>(null) }
    var deviceToken by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(profile.id) {
        busy = true
        try {
            dogs = repository.dogs(profile.id)
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
            selectedDog != null -> "Smartphone neu koppeln?"
            else -> "Hunde im Revier"
        }) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val token = deviceToken
                val dogToPair = selectedDog
                if (token != null) {
                    val bitmap = remember(token) {
                        val matrix = QRCodeWriter().encode(token, BarcodeFormat.QR_CODE, 384, 384)
                        val pixels = IntArray(matrix.width * matrix.height) { index ->
                            if (matrix[index % matrix.width, index / matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                        }
                        Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888).asImageBitmap()
                    }
                    Image(bitmap, "QR-Code des einmaligen Geraeteschluessels",
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
                    Text("${dogToPair.name}: Der bisherige Geraeteschluessel wird sofort widerrufen.")
                } else {
                    if (dogs.isEmpty() && !busy) Text("Keine Hunde registriert.")
                    dogs.forEach { dog ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(dog.name, Modifier.weight(1f))
                            if (canEdit) TextButton(enabled = !busy, onClick = { selectedDog = dog; error = "" }) {
                                Text("Koppeln")
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
            }
        },
        confirmButton = {
            if (selectedDog != null && deviceToken == null) {
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
            if (selectedDog != null && deviceToken == null) TextButton(enabled = !busy, onClick = { selectedDog = null }) {
                Text("Abbrechen")
            }
        }
    )
}