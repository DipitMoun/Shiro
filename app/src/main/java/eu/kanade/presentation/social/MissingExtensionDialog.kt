package eu.kanade.presentation.social

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.data.social.WorkSourceRef

/**
 * Asked when a friend's version of a work needs an extension this phone does not have: install it,
 * or look for the work in the usual way instead.
 */
@Composable
fun MissingExtensionDialog(
    ref: WorkSourceRef,
    installing: Boolean,
    onInstall: () -> Unit,
    onSearchInstead: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!installing) onDismiss() },
        title = { Text("Extension nécessaire") },
        text = {
            Column {
                Text(
                    "Ton ami lit « ${ref.title ?: "cette œuvre"} » avec l'extension ${ref.sourceName}. " +
                        "Installe-la pour ouvrir exactement la même version, ou cherche l'œuvre autrement.",
                )
                if (installing) {
                    CircularProgressIndicator(modifier = Modifier.padding(top = 12.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onInstall, enabled = !installing) { Text("Installer") }
        },
        dismissButton = {
            Column {
                TextButton(onClick = onSearchInstead, enabled = !installing) { Text("Chercher autrement") }
                TextButton(onClick = onDismiss, enabled = !installing) { Text("Annuler") }
            }
        },
    )
}
