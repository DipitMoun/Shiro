package eu.kanade.presentation.more

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.i18n.MR

private sealed interface WhatsNewBlock {
    data class Heading(val text: String) : WhatsNewBlock
    data class Bullet(val text: String) : WhatsNewBlock
}

/** Parses the small markdown subset used by WHATS-NEW.md: `**Heading**` and `- bullet` lines. */
private fun parseWhatsNew(raw: String): List<WhatsNewBlock> {
    val blocks = mutableListOf<WhatsNewBlock>()
    for (line in raw.lines()) {
        val text = line.trim()
        when {
            text.isEmpty() -> Unit
            text.startsWith("- ") -> blocks += WhatsNewBlock.Bullet(text.removePrefix("- ").clean())
            text.startsWith("**") && text.endsWith("**") ->
                blocks += WhatsNewBlock.Heading(text.removeSurrounding("**"))
            // Wrapped continuation of the previous bullet.
            else -> (blocks.lastOrNull() as? WhatsNewBlock.Bullet)?.let {
                blocks[blocks.lastIndex] = WhatsNewBlock.Bullet("${it.text} ${text.clean()}")
            }
        }
    }
    return blocks
}

private fun String.clean() = replace("**", "").replace("`", "")

private fun Context.readWhatsNew(): String? =
    runCatching { assets.open("whats-new.md").bufferedReader().use { it.readText() } }.getOrNull()

/** Release notes shown once after an update. */
@Composable
fun WhatsNewDialog(versionName: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val blocks = remember { context.readWhatsNew()?.let(::parseWhatsNew).orEmpty() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(MR.strings.updated_version, versionName)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                blocks.forEach { block ->
                    when (block) {
                        is WhatsNewBlock.Heading -> Text(
                            text = block.text,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        is WhatsNewBlock.Bullet -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                Modifier
                                    .padding(top = 8.dp)
                                    .size(5.dp)
                                    .background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
                            )
                            Text(text = block.text, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(MR.strings.action_ok)) }
        },
    )
}
