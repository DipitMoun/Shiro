package eu.kanade.presentation.library.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun CollectionEditDialog(
    initialBannerUrl: String,
    initialBannerPath: String?,
    initialDescription: String,
    onDismiss: () -> Unit,
    onSave: (bannerUrl: String?, bannerUri: Uri?, description: String) -> Unit,
) {
    var bannerUrl by remember { mutableStateOf(initialBannerUrl) }
    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var description by remember { mutableStateOf(initialDescription) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri != null) {
            pickedUri = uri
            bannerUrl = ""
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Modifier la collection") },
        text = {
            Column {
                OutlinedTextField(
                    value = bannerUrl,
                    onValueChange = { bannerUrl = it },
                    label = { Text("URL de la banniere (image ou GIF)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { launcher.launch("image/*") },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Choisir depuis la galerie")
                }
                val status = when {
                    pickedUri != null -> "Nouvelle image selectionnee"
                    !initialBannerPath.isNullOrBlank() -> "Banniere locale actuelle"
                    else -> null
                }
                if (status != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = status, style = MaterialTheme.typography.labelSmall)
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(bannerUrl.takeIf { it.isNotBlank() }, pickedUri, description)
            }) {
                Text("Sauvegarder")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuler")
            }
        },
    )
}
