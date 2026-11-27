package eu.kanade.tachiyomi.ui.social.story

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.tachiyomi.data.social.friends.SocialFriendStore
import eu.kanade.tachiyomi.data.social.matrix.media.SocialAvatarLoader
import eu.kanade.tachiyomi.data.social.story.SocialStoryService
import eu.kanade.tachiyomi.data.social.story.StoryReel
import eu.kanade.tachiyomi.ui.social.ParticipantAvatar
import eu.kanade.tachiyomi.ui.social.SocialChatFriend
import eu.kanade.tachiyomi.ui.social.home.shortHandle
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

private val unseenRing = Brush.sweepGradient(
    listOf(Color(0xFFFEDA75), Color(0xFFFA7E1E), Color(0xFFD62976), Color(0xFF962FBF), Color(0xFFFEDA75)),
)

/**
 * The row at the top of the Social page: "Votre story" with the + to add one, then a ring around
 * the avatar of each friend who posted. A gradient ring means something new to watch.
 */
@Composable
fun StoriesRow(selfMxid: String, selfName: String, contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp)) {
    val navigator = LocalNavigator.currentOrThrow
    val service = remember { Injekt.get<SocialStoryService>() }
    val friends = remember { Injekt.get<SocialFriendStore>() }
    val avatars = remember { Injekt.get<SocialAvatarLoader>() }
    val reels by service.reels.collectAsStateWithLifecycle()
    val friendList by friends.friends.collectAsStateWithLifecycle()
    val ownReel = reels.firstOrNull { it.authorMxid == selfMxid }
    val ownAvatar by produceState<ByteArray?>(null, selfMxid) { value = runCatching { avatars.loadOwnAvatar(96) }.getOrNull() }

    LazyRow(contentPadding = contentPadding, modifier = Modifier.padding(vertical = 8.dp)) {
        item(key = "own") {
            StoryBubble(
                name = "Votre story",
                avatar = ownAvatar,
                mxid = selfMxid,
                ringed = ownReel != null,
                unseen = false,
                showAdd = true,
                onClick = {
                    if (ownReel != null) navigator.push(StoryViewerScreen(selfMxid)) else navigator.push(StoryComposerScreen())
                },
                onAdd = { navigator.push(StoryComposerScreen()) },
            )
        }
        items(reels.filter { it.authorMxid != selfMxid }, key = { "story-${it.authorMxid}" }) { reel ->
            val friendAvatar by produceState<ByteArray?>(null, reel.authorMxid) {
                value = runCatching { avatars.loadProfileAvatar(reel.authorMxid, 96) }.getOrNull()
            }
            StoryBubble(
                name = friendList.firstOrNull { it.mxid == reel.authorMxid }?.displayName ?: shortHandle(reel.authorMxid),
                avatar = friendAvatar,
                mxid = reel.authorMxid,
                ringed = true,
                unseen = reel.hasUnseen,
                showAdd = false,
                onClick = { navigator.push(StoryViewerScreen(reel.authorMxid)) },
                onAdd = {},
            )
        }
    }
}

@Composable
private fun StoryBubble(
    name: String,
    avatar: ByteArray?,
    mxid: String,
    ringed: Boolean,
    unseen: Boolean,
    showAdd: Boolean,
    onClick: () -> Unit,
    onAdd: () -> Unit,
) {
    Column(
        modifier = Modifier.width(82.dp).clickable(onClick = onClick).padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            val ringModifier = when {
                ringed && unseen -> Modifier.border(3.dp, unseenRing, CircleShape)
                ringed -> Modifier.border(2.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), CircleShape)
                else -> Modifier
            }
            Box(ringModifier.padding(4.dp)) {
                ParticipantAvatar(SocialChatFriend(mxid, name, avatar), size = 62.dp)
            }
            if (showAdd) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .border(2.dp, MaterialTheme.colorScheme.background, CircleShape)
                        .clickable(onClick = onAdd),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Add, "Ajouter une story", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
                }
            }
        }
        Text(
            text = name,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}
