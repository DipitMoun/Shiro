package eu.kanade.presentation.more

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.kanade.tachiyomi.data.updater.RELEASE_URL
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.screens.InfoScreen

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

/**
 * What changed in the version just installed, shown once after an update.
 *
 * Same full-screen layout as the crash screen (icon, title, short text, a box with the details and
 * two buttons at the bottom), so the app has one recognisable "something to tell you" screen.
 */
@Composable
fun WhatsNewDialog(versionName: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val blocks = remember { context.readWhatsNew()?.let(::parseWhatsNew).orEmpty() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            InfoScreen(
                icon = Icons.Outlined.AutoAwesome,
                headingText = "Shiro $versionName",
                subtitleText = "Voici ce qui change dans cette version.",
                acceptText = "Continuer",
                onAcceptClick = onDismiss,
                rejectText = "Voir toutes les versions",
                onRejectClick = { uriHandler.openUri(RELEASE_URL) },
            ) {
                Column(
                    modifier = Modifier
                        .padding(vertical = MaterialTheme.padding.small)
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(MaterialTheme.padding.medium),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (blocks.isEmpty()) {
                        Text(
                            text = "Corrections et améliorations.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
                                Text(
                                    text = block.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
