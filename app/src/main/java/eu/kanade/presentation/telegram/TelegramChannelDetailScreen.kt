package eu.kanade.presentation.telegram

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.library.components.CollectionCard
import eu.kanade.presentation.library.components.CollectionDetailHeader
import eu.kanade.presentation.library.components.LibrarySectionHeader
import eu.kanade.presentation.library.components.collectionWorkCountLabel
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.telegram.saved.TelegramMetadataProvider
import eu.kanade.tachiyomi.ui.browse.telegram.TelegramChannelUi
import eu.kanade.tachiyomi.ui.browse.telegram.TelegramWorkUi
import kotlinx.collections.immutable.persistentListOf
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed

@Composable
fun TelegramChannelDetailScreen(
    channel: TelegramChannelUi?,
    works: List<TelegramWorkUi>,
    loading: Boolean,
    busy: Boolean,
    unresolvedOnly: Boolean,
    navigateUp: () -> Unit,
    onOpenUnresolved: () -> Unit,
    onWorkClick: (TelegramWorkUi) -> Unit,
    onRecognizeWork: (TelegramWorkUi, TelegramMetadataProvider) -> Unit,
    onRenameWork: (TelegramWorkUi, String) -> Unit,
    onMergeWork: (TelegramWorkUi, TelegramWorkUi) -> Unit,
    onSeparateWork: (TelegramWorkUi) -> Unit,
    onResetWork: (TelegramWorkUi) -> Unit,
    onHideWork: (TelegramWorkUi) -> Unit,
    onImport: () -> Unit,
    onScanFullHistory: () -> Unit,
    onSync: () -> Unit,
    onJoin: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var recognitionWork by remember { mutableStateOf<TelegramWorkUi?>(null) }
    var renameWork by remember { mutableStateOf<TelegramWorkUi?>(null) }
    var mergeSource by remember { mutableStateOf<TelegramWorkUi?>(null) }
    var removeConfirm by remember { mutableStateOf(false) }

    val unresolved = works.filterNot { it.resolved }
    val resolved = works.filter { it.resolved }
    val visibleWorks = if (unresolvedOnly) unresolved else resolved

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(LocalConfiguration.current.screenHeightDp.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            AppBar(
                navigateUp = navigateUp,
                title = if (unresolvedOnly) "Non reconnus" else channel?.title ?: "Telegram",
                actions = {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "Actions du canal")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Synchroniser le canal") },
                            onClick = {
                                menuExpanded = false
                                onSync()
                            },
                            enabled = !busy,
                        )
                        if (!unresolvedOnly && unresolved.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text("Non reconnus (${unresolved.size})") },
                                onClick = {
                                    menuExpanded = false
                                    onOpenUnresolved()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Importer dans la bibliothèque") },
                            onClick = {
                                menuExpanded = false
                                onImport()
                            },
                            enabled = !busy,
                        )
                        DropdownMenuItem(
                            text = { Text("Rescanner l'historique") },
                            onClick = {
                                menuExpanded = false
                                onScanFullHistory()
                            },
                            enabled = !busy,
                        )
                        if (channel?.isMember == false) {
                            DropdownMenuItem(
                                text = { Text("Rejoindre le canal") },
                                onClick = {
                                    menuExpanded = false
                                    onJoin()
                                },
                                enabled = !busy,
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Retirer le canal") },
                            onClick = {
                                menuExpanded = false
                                removeConfirm = true
                            },
                            enabled = !busy,
                        )
                    }
                },
            )

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .fillMaxWidth()
                    .height((LocalConfiguration.current.screenHeightDp - 72).coerceAtLeast(320).dp),
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                    CollectionDetailHeader(
                        title = if (unresolvedOnly) "Non reconnus" else channel?.title ?: "Telegram",
                        description = if (unresolvedOnly) {
                            "Œuvres qui n'ont pas encore été reconnues. " +
                                "Reconnais-en une pour la faire passer dans « Œuvres »."
                        } else {
                            "Collection Telegram"
                        },
                        bannerUrl = channel?.bannerUrl,
                        bannerPath = null,
                        statsText = if (unresolvedOnly) {
                            "${unresolved.size} œuvre(s) non reconnue(s)"
                        } else {
                            "${resolved.size} œuvre(s) · ${unresolved.size} non reconnue(s) · " +
                                "${channel?.videoFiles ?: 0} fichiers"
                        },
                    )
                }

                if (!unresolvedOnly && unresolved.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "subcollections-header") {
                        LibrarySectionHeader(title = "Sous-collections")
                    }
                    item(key = "unresolved") {
                        CollectionCard(
                            title = "Non reconnus",
                            subtitle = collectionWorkCountLabel(unresolved.size),
                            previewImages = unresolved.mapNotNull { it.coverUrl }.take(3),
                            bannerModel = null,
                            onClick = onOpenUnresolved,
                            onImageClick = { onOpenUnresolved() },
                        )
                    }
                }

                item(span = { GridItemSpan(maxLineSpan) }, key = "works-header") {
                    LibrarySectionHeader(title = if (unresolvedOnly) "Œuvres non reconnues" else "Œuvres")
                }

                if (loading) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "loading") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Lecture de l'index du canal…")
                        }
                    }
                } else if (visibleWorks.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                        Text(
                            text = if (unresolvedOnly) {
                                "Aucune œuvre non reconnue."
                            } else {
                                "Aucune œuvre reconnue pour ce canal."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }

                gridItemsIndexed(visibleWorks, key = { index, work ->
                    "work-${work.id}-${work.workKey}-$index"
                }) { _, work ->
                    TelegramWorkGridCard(
                        work = work,
                        onClick = { onWorkClick(work) },
                        onRecognize = { provider -> onRecognizeWork(work, provider) },
                        onChooseRecognition = { recognitionWork = work },
                        onRename = {
                            renameWork = work
                        },
                        onMerge = {
                            mergeSource = work
                        },
                        onSeparate = { onSeparateWork(work) },
                        onReset = { onResetWork(work) },
                        onHide = { onHideWork(work) },
                    )
                }
            }
        }
    }

    recognitionWork?.let { work ->
        AlertDialog(
            onDismissRequest = { recognitionWork = null },
            title = { Text("Reconnaître « ${work.title} »") },
            text = {
                Column {
                    Text(
                        text = "Choisis la source de métadonnées. " +
                            "AniList est recommandé pour les animés.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    TelegramMetadataProvider.entries.forEach { provider ->
                        TextButton(
                            onClick = {
                                recognitionWork = null
                                onRecognizeWork(work, provider)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(provider.label, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { recognitionWork = null }) {
                    Text("Annuler")
                }
            },
        )
    }

    renameWork?.let { work ->
        var title by remember(work) { mutableStateOf(work.title) }
        AlertDialog(
            onDismissRequest = { renameWork = null },
            title = { Text("Renommer l'œuvre") },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                    label = { Text("Titre") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRenameWork(work, title)
                        renameWork = null
                    },
                ) {
                    Text("Enregistrer")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameWork = null }) {
                    Text("Annuler")
                }
            },
        )
    }

    if (removeConfirm) {
        AlertDialog(
            onDismissRequest = { removeConfirm = false },
            title = { Text("Retirer le canal ?") },
            text = {
                Text(
                    "« ${channel?.title ?: "Ce canal"} » sera retiré du suivi. " +
                        "L'index local est supprimé ; aucune œuvre de la bibliothèque n'est effacée.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        removeConfirm = false
                        onRemove()
                    },
                ) {
                    Text("Retirer")
                }
            },
            dismissButton = {
                TextButton(onClick = { removeConfirm = false }) {
                    Text("Annuler")
                }
            },
        )
    }

    mergeSource?.let { source ->
        val candidates = works
            .filter { it.workKey != source.workKey && it.kind == source.kind }
            .sortedBy { it.title.lowercase() }
        AlertDialog(
            onDismissRequest = { mergeSource = null },
            title = { Text("Fusionner « ${source.title} »") },
            text = {
                if (candidates.isEmpty()) {
                    Text("Aucune autre œuvre compatible dans ce canal.")
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(candidates, key = { "merge-${it.id}-${it.workKey}" }) { target ->
                            TextButton(
                                onClick = {
                                    mergeSource = null
                                    onMergeWork(source, target)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = target.title,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Text(
                                        text = "${target.files} fichiers · ${target.episodes} épisodes",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { mergeSource = null }) {
                    Text("Annuler")
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TelegramWorkGridCard(
    work: TelegramWorkUi,
    onClick: () -> Unit,
    onRecognize: (TelegramMetadataProvider) -> Unit,
    onChooseRecognition: () -> Unit,
    onRename: () -> Unit,
    onMerge: () -> Unit,
    onSeparate: () -> Unit,
    onReset: () -> Unit,
    onHide: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = { menuExpanded = true },
            ),
    ) {
        AsyncImage(
            model = work.coverUrl,
            contentDescription = work.title,
            placeholder = painterResource(R.drawable.telegram_baniere),
            error = painterResource(R.drawable.telegram_baniere),
            fallback = painterResource(R.drawable.telegram_baniere),
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(84.dp)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f to Color(0xCC000000),
                    ),
                ),
        )
        Text(
            text = work.title,
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp),
        )
        if (work.episodes > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Text(
            text = "${work.episodes} ép.",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(6.dp)
                .clip(RoundedCornerShape(50))
                .background(Color(0x99000000))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
        if (!work.resolved) {
            Text(
                text = "Non reconnue",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 30.dp, start = 6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.85f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(30.dp)
                .clip(CircleShape)
                .background(Color(0x99000000)),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.size(30.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.MoreVert,
                    contentDescription = "Actions de l'œuvre",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("Ouvrir") },
                onClick = {
                    menuExpanded = false
                    onClick()
                },
            )
            DropdownMenuItem(
                text = { Text("Reconnaître (AniList)") },
                onClick = {
                    menuExpanded = false
                    onRecognize(TelegramMetadataProvider.ANILIST)
                },
            )
            DropdownMenuItem(
                text = { Text("Choisir la source…") },
                onClick = {
                    menuExpanded = false
                    onChooseRecognition()
                },
            )
            DropdownMenuItem(
                text = { Text("Renommer") },
                onClick = {
                    menuExpanded = false
                    onRename()
                },
            )
            DropdownMenuItem(
                text = { Text("Fusionner avec…") },
                onClick = {
                    menuExpanded = false
                    onMerge()
                },
            )
            DropdownMenuItem(
                text = { Text("Séparer les variantes") },
                onClick = {
                    menuExpanded = false
                    onSeparate()
                },
            )
            DropdownMenuItem(
                text = { Text("Réinitialiser les corrections") },
                onClick = {
                    menuExpanded = false
                    onReset()
                },
            )
            DropdownMenuItem(
                text = { Text("Masquer de la source") },
                onClick = {
                    menuExpanded = false
                    onHide()
                },
            )
        }
    }
}
