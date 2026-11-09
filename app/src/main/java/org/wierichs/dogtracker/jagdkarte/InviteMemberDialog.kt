package org.wierichs.dogtracker.jagdkarte

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InviteMemberDialog(
    profile: HuntingProfile,
    onInvite: suspend (String, HuntingProfileMemberRole) -> Unit,
    onInvited: (HuntingProfileMemberRole) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var email by remember(profile.id) { mutableStateOf("") }
    var role by remember(profile.id) { mutableStateOf(HuntingProfileMemberRole.GUEST) }
    var roleMenuOpen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Benutzer einladen") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(profile.name, style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(email, onValueChange = { email = it.take(254) }, enabled = !busy,
                    singleLine = true, label = { Text("E-Mail-Adresse") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth())
                ExposedDropdownMenuBox(expanded = roleMenuOpen, onExpandedChange = { if (!busy) roleMenuOpen = it }) {
                    OutlinedTextField(role.label, onValueChange = {}, readOnly = true, enabled = !busy,
                        label = { Text("Rolle") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(roleMenuOpen) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable))
                    ExposedDropdownMenu(expanded = roleMenuOpen, onDismissRequest = { roleMenuOpen = false }) {
                        HuntingProfileMemberRole.entries.forEach { option ->
                            DropdownMenuItem(text = { Text(option.label) }, onClick = {
                                role = option
                                roleMenuOpen = false
                            })
                        }
                    }
                }
                if (busy) CircularProgressIndicator()
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && runCatching { normalizeInvitationEmail(email) }.isSuccess, onClick = {
                busy = true
                error = ""
                scope.launch {
                    try {
                        onInvite(email, role)
                        onInvited(role)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        error = failure.message ?: "Einladung konnte nicht versendet werden."
                    } finally {
                        busy = false
                    }
                }
            }) { Text("Einladung senden") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("Abbrechen") }
        }
    )
}
