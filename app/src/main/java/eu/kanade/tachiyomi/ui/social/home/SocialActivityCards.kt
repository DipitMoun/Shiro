package eu.kanade.tachiyomi.ui.social.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.tachiyomi.data.social.activity.SocialActivityItem
import eu.kanade.tachiyomi.data.social.activity.SocialActivityKind
import eu.kanade.tachiyomi.data.social.matrix.media.SocialAvatarLoader
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * The activity of the user and of the friends as work cards, like the cards of the library: the
 * poster, the title, and on the poster the photo of the person who did it. A card opens the work.
 */
@Composable
internal fun ActivityCarousel(
    activities: List<SocialActivityItem>,
    selfMxid: String,
    nameOf: (String) -> String,
    inLibrary: (String) -> Boolean,
    onOpen: (SocialActivityItem) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(activities, key = { "activity-${it.eventId}" }) { activity ->
            ActivityCard(
                activity = activity,
                mine = activity.actorMxid == selfMxid,
                actorName = if (activity.actorMxid == selfMxid) "Toi" else nameOf(activity.actorMxid),
                isNewForUser = activity.actorMxid != selfMxid && !inLibrary(activity.title),
                onClick = { onOpen(activity) },
            )
        }
    }
}

@Composable
private fun ActivityCard(
    activity: SocialActivityItem,
    mine: Boolean,
    actorName: String,
    isNewForUser: Boolean,
    onClick: () -> Unit,
) {
    val verb = when (activity.kind) {
        SocialActivityKind.ANIME_ADDED -> "a ajouté"
        SocialActivityKind.MANGA_ADDED -> "lit maintenant"
    }
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = Modifier
            .width(124.dp)
            .clip(shape)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            if (activity.coverUrl != null) {
                AsyncImage(
                    model = activity.coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = if (activity.kind == SocialActivityKind.MANGA_ADDED) Icons.Outlined.AutoStories else Icons.Outlined.Movie,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).size(36.dp),
                )
            }
            ActorBadge(
                mxid = activity.actorMxid,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
            )
            if (isNewForUser) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
                ) {
                    Text(
                        text = "Nouveau pour toi",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = activity.title,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "$actorName $verb · ${relativeTime(activity.timestamp)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }
}

/** The photo of the person, as a small circle with a rim that separates it from the poster. */
@Composable
private fun ActorBadge(mxid: String, modifier: Modifier = Modifier) {
    val avatarLoader = remember { Injekt.get<SocialAvatarLoader>() }
    val avatar by produceState<ImageBitmap?>(null, mxid) {
        value = avatarLoader.loadProfileAvatar(mxid, 96)?.let(::decodeAvatar)
    }
    val rim = Modifier
        .size(32.dp)
        .border(2.dp, MaterialTheme.colorScheme.background, CircleShape)
        .clip(CircleShape)
    val bitmap = avatar
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier.then(rim))
    } else {
        Box(modifier.then(rim).background(MaterialTheme.colorScheme.surfaceContainerHighest))
    }
}
