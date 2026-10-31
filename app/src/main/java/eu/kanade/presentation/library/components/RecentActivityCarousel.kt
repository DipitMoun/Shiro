package eu.kanade.presentation.library.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import eu.kanade.presentation.entries.components.ItemCover
import eu.kanade.tachiyomi.R
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Immutable
data class RecentCollection(
    val id: Long,
    val name: String,
    /**
     * A [java.io.File] for locally picked banners, a remote URL, or null when the collection has no
     * banner at all. In the latter case the Shiro logo is displayed instead of an empty box.
     */
    val bannerModel: Any?,
)

@Immutable
data class RecentWork(
    val id: Long,
    val title: String,
    val coverUrl: String?,
    val progress: EntryProgress,
    /** True when the work still has entries to resume (unseen episodes / unread chapters). */
    val canContinue: Boolean = false,
)

@Composable
fun RecentActivityCarousel(
    recentWorks: List<RecentWork>,
    recentCollections: List<RecentCollection>,
    onWorkClick: (Long) -> Unit,
    onCollectionClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onContinueClick: ((Long) -> Unit)? = null,
) {
    if (recentWorks.isEmpty() && recentCollections.isEmpty()) return

    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(recentWorks, key = { "work_${it.id}" }) { work ->
            RecentWorkCard(
                work = work,
                onClick = { onWorkClick(work.id) },
                onContinueClick = onContinueClick?.takeIf { work.canContinue }?.let { onClick ->
                    { onClick(work.id) }
                },
            )
        }
        items(recentCollections, key = { "coll_${it.id}" }) { coll ->
            RecentCollectionCard(coll, onClick = { onCollectionClick(coll.id) })
        }
    }
}

@Composable
private fun RecentWorkCard(
    work: RecentWork,
    onClick: () -> Unit,
    onContinueClick: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .width(110.dp)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (!work.coverUrl.isNullOrBlank()) {
                AsyncImage(
                    model = work.coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                DefaultEntryArtwork(modifier = Modifier.fillMaxSize())
            }

            ProgressRing(
                progress = work.progress,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp),
            )

            if (onContinueClick != null) {
                FilledIconButton(
                    onClick = onContinueClick,
                    shape = RoundedCornerShape(8.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
                        contentColor = contentColorFor(MaterialTheme.colorScheme.primaryContainer),
                    ),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .size(28.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = stringResource(MR.strings.action_resume),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = work.title,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RecentCollectionCard(coll: RecentCollection, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(110.dp)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(ItemCover.Book.ratio)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (coll.bannerModel != null) {
                AsyncImage(
                    model = coll.bannerModel,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                DefaultEntryArtwork(modifier = Modifier.fillMaxSize(0.5f))
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = coll.name,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Shiro artwork used whenever an entry or a collection has no image to show.
 */
@Composable
fun DefaultEntryArtwork(
    modifier: Modifier = Modifier,
    tint: Color? = null,
) {
    Image(
        painter = painterResource(R.drawable.ic_launcher_foreground),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        colorFilter = tint?.let { androidx.compose.ui.graphics.ColorFilter.tint(it) },
        modifier = modifier.padding(6.dp),
    )
}

/**
 * Two nested progress rings:
 * - the outer ring is the share of the whole series that has been completed,
 * - the inner ring is the progress inside the episode currently being watched (omitted when there
 *   is no episode in progress).
 *
 * The series percentage is written in the middle so the badge stays readable without any label.
 */
@Composable
fun ProgressRing(
    progress: EntryProgress,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
    showLabel: Boolean = true,
) {
    val seriesColor = MaterialTheme.colorScheme.primary
    val episodeColor = MaterialTheme.colorScheme.tertiary
    val trackColor = Color.White.copy(alpha = 0.22f)
    val textColor = Color.White

    Box(
        modifier = modifier
            .size(size)
            .background(Color.Black.copy(alpha = 0.62f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val diameter = this.size.minDimension
            val inset = diameter * 0.06f
            val outerStroke = diameter * 0.075f
            val innerStroke = outerStroke * 0.7f
            val gap = diameter * 0.045f
            val outerRadius = diameter / 2f - inset - outerStroke / 2f
            val innerRadius = outerRadius - outerStroke / 2f - gap - innerStroke / 2f

            drawRing(
                radius = outerRadius,
                strokeWidth = outerStroke,
                color = seriesColor,
                percent = progress.seriesPercent,
                trackColor = trackColor,
            )

            val episodePercent = progress.episodePercent
            if (episodePercent != null) {
                drawRing(
                    radius = innerRadius,
                    strokeWidth = innerStroke,
                    color = episodeColor,
                    percent = episodePercent,
                    trackColor = trackColor,
                )
            }
        }

        if (showLabel) {
            Text(
                text = "${progress.seriesPercent}",
                color = textColor,
                fontSize = 9.sp,
                lineHeight = 9.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

private fun DrawScope.drawRing(
    radius: Float,
    strokeWidth: Float,
    color: Color,
    percent: Int,
    trackColor: Color,
) {
    drawCircle(
        color = trackColor,
        radius = radius,
        style = Stroke(width = strokeWidth),
    )

    val clamped = percent.coerceIn(0, 100)
    if (clamped <= 0) return

    drawArc(
        color = color,
        startAngle = -90f,
        sweepAngle = 360f * clamped / 100f,
        useCenter = false,
        topLeft = Offset(center.x - radius, center.y - radius),
        size = Size(radius * 2f, radius * 2f),
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
    )
}
