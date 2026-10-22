package eu.kanade.presentation.telegram

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// Modèle de données temporaire pour représenter un groupe Telegram
data class TelegramChat(
    val id: Long,
    val title: String,
)

@Composable
fun TelegramChatSelectorDialog(
    chats: List<TelegramChat>,
    initiallySelectedIds: Set<Long> = emptySet(),
    onDismissRequest: () -> Unit,
    onSave: (Set<Long>) -> Unit,
    title: String = "Sélectionner les groupes",
) {
    var selectedIds by remember { mutableStateOf(initiallySelectedIds) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(selectedIds)
                    onDismissRequest()
                },
            ) {
                Text("Valider")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("Annuler")
            }
        },
        title = {
            Text(title)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp),
            ) {
                if (chats.isEmpty()) {
                    Text(
                        text = "Aucun groupe trouvé.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    LazyColumn {
                        items(chats, key = { it.id }) { chat ->
                            val isSelected = selectedIds.contains(chat.id)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedIds = if (isSelected) {
                                            selectedIds - chat.id
                                        } else {
                                            selectedIds + chat.id
                                        }
                                    }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = {
                                        selectedIds = if (isSelected) {
                                            selectedIds - chat.id
                                        } else {
                                            selectedIds + chat.id
                                        }
                                    },
                                )
                                Text(
                                    text = chat.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
    )
}
