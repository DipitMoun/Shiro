package eu.kanade.presentation.library.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.presentation.entries.components.ItemCover

/**
 * Card representing a collection or a sub-collection.
 *
 * The image area always uses the exact same ratio as a work cover, so the card keeps a fixed
 * size no matter which banner or cover is displayed. The image is cropped inside that box.
 *
 * Rendering rules:
 * - 3 covers or more: mosaic of three covers, the fourth cell being the "open" button.
 * - 1 or 2 covers: the collection banner (or the first cover) fills the box, with the "open"
 *   button in the bottom right corner.
 * - no cover: the collection banner, or the Shiro logo when there is none.
 */
@Composable
fun CollectionCard(
    title: String,
    subtitle: String,
    previewImages: List<String>,
    bannerModel: Any?,
    onClick: () -> Unit,
    onImageClick: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val covers = previewImages.filter { it.isNotBlank() }
    val showMosaic = covers.size >= 3

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(ItemCover.Book.ratio)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (!showMosaic) {
                val coverModel: Any? = bannerModel ?: covers.firstOrNull()
                if (coverModel != null) {
                    AsyncImage(
                        model = coverModel,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    DefaultEntryArtwork(modifier = Modifier.fillMaxSize(0.45f))
                }
            }

            if (showMosaic) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(3.dp),
                ) {
                    Row(modifier = Modifier.weight(1f)) {
                        MosaicCell(covers[0], Modifier.weight(1f).fillMaxHeight()) { onImageClick(0) }
                        MosaicCell(covers[1], Modifier.weight(1f).fillMaxHeight()) { onImageClick(1) }
                    }
                    Row(modifier = Modifier.weight(1f)) {
                        MosaicCell(covers[2], Modifier.weight(1f).fillMaxHeight()) { onImageClick(2) }
                        OpenCollectionCell(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(2.dp),
                            onClick = onClick,
                        )
                    }
                }
            }

            if (!showMosaic) {
                OpenCollectionCell(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .size(34.dp),
                    onClick = onClick,
                )
            }
        }

        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp, top = 6.dp, end = 4.dp),
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp, end = 4.dp, bottom = 4.dp),
        )
    }
}

/**
 * Label displayed under a collection card: the recursive number of works it contains.
 */
fun collectionWorkCountLabel(count: Int): String = if (count > 1) "$count titres" else "$count titre"

@Composable
private fun MosaicCell(
    imageUrl: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .padding(2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        if (imageUrl.isNotBlank()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            DefaultEntryArtwork(modifier = Modifier.fillMaxSize(0.6f))
        }
    }
}

/**
 * "..." cell used to open the collection when no cover fits in the mosaic.
 */
@Composable
private fun OpenCollectionCell(
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.MoreHoriz,
            contentDescription = "Ouvrir la collection",
            tint = Color.White,
        )
    }
}
