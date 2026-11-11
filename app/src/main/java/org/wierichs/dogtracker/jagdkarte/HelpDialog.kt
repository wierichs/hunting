package org.wierichs.dogtracker.jagdkarte

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun HelpDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val markdown = remember {
        runCatching {
            context.assets.open("hilfe.md").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrElse { "# Hilfe nicht verfuegbar\n\nDie Hilfe konnte nicht geladen werden." }
    }
    val markwon = remember(context) { io.noties.markwon.Markwon.create(context) }
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.92f),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp
        ) {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row {
                    Column(Modifier.weight(1f)) {
                        Text("Hilfe und Fehlerbehebung", style = MaterialTheme.typography.titleLarge)
                        Text("Jagdkarte fuer Android", style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Hilfe schliessen") }
                }
                HorizontalDivider()
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    AndroidView(
                        modifier = Modifier.fillMaxWidth(),
                        factory = { viewContext ->
                            android.widget.TextView(viewContext).apply {
                                textSize = 16f
                                setLineSpacing(0f, 1.15f)
                                movementMethod = android.text.method.LinkMovementMethod.getInstance()
                            }
                        },
                        update = { textView ->
                            textView.setTextColor(textColor)
                            textView.setLinkTextColor(linkColor)
                            markwon.setMarkdown(textView, markdown)
                        }
                    )
                }
            }
        }
    }
}