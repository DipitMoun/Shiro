package eu.kanade.tachiyomi.ui.social.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PeopleAlt
import androidx.compose.material.icons.outlined.PeopleAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.domain.ui.model.NavStyle
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.data.social.matrix.profile.SocialOwnProfile
import eu.kanade.tachiyomi.ui.main.MainActivity
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Bottom bar destination for the social layer. It took the place of Updates, which now
 * lives in More.
 */
data object SocialTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            val index: UShort = when (currentNavigationStyle()) {
                NavStyle.MOVE_UPDATES_TO_MORE -> 5u
                NavStyle.MOVE_HISTORY_TO_MORE -> 2u
                NavStyle.MOVE_BROWSE_TO_MORE -> 2u
                NavStyle.MOVE_MANGA_TO_MORE -> 1u
            }
            return TabOptions(
                index = index,
                title = "Social",
                icon = socialIconPainter(isSelected),
            )
        }

    @Composable
    override fun Content() {
        val context = LocalContext.current
        SocialHomeContent()
        LaunchedEffect(Unit) {
            (context as? MainActivity)?.ready = true
        }
    }
}

@Composable
private fun socialIconPainter(selected: Boolean): Painter =
    rememberVectorPainter(if (selected) Icons.Filled.PeopleAlt else Icons.Outlined.PeopleAlt)

/**
 * Bottom bar icon: the user's photo once connected, the people icon otherwise. A photo must
 * not be tinted like the other icons, so it is drawn as an image with a selection ring.
 */
@Composable
fun SocialTabIcon(tab: Tab, selected: Boolean) {
    val ownProfile = remember { Injekt.get<SocialOwnProfile>() }
    val avatarBytes by ownProfile.avatar.collectAsStateWithLifecycle()
    val bitmap = remember(avatarBytes) { avatarBytes?.let(::decodeAvatar) }

    if (bitmap == null) {
        Icon(
            painter = tab.options.icon!!,
            contentDescription = tab.options.title,
            tint = LocalContentColor.current,
        )
        return
    }
    Image(
        bitmap = bitmap,
        contentDescription = tab.options.title,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .then(
                if (selected) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                } else {
                    Modifier
                },
            ),
    )
}
