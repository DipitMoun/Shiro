package eu.kanade.domain.ui.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.History
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.browse.BrowseTab
import eu.kanade.tachiyomi.ui.history.HistoriesTab
import eu.kanade.tachiyomi.ui.library.anime.AnimeLibraryTab
import eu.kanade.tachiyomi.ui.library.manga.MangaLibraryTab
import eu.kanade.tachiyomi.ui.more.MoreTab
import eu.kanade.tachiyomi.ui.social.home.SocialTab
import tachiyomi.i18n.MR
import androidx.compose.material.icons.outlined.PeopleAlt
import tachiyomi.i18n.aniyomi.AYMR

enum class NavStyle(
    val titleRes: StringResource,
    val moreTab: Tab,
) {
    MOVE_MANGA_TO_MORE(titleRes = AYMR.strings.pref_bottom_nav_no_manga, moreTab = MangaLibraryTab),
    // Shiro: Social replaced Updates in the bottom bar (Updates is always in More). The enum
    // name is kept so the stored preference stays valid.
    MOVE_UPDATES_TO_MORE(titleRes = MR.strings.pref_bottom_nav_no_social, moreTab = SocialTab),
    MOVE_HISTORY_TO_MORE(titleRes = AYMR.strings.pref_bottom_nav_no_history, moreTab = HistoriesTab),
    MOVE_BROWSE_TO_MORE(titleRes = AYMR.strings.pref_bottom_nav_no_browse, moreTab = BrowseTab),
    ;

    val moreIcon: ImageVector
        @Composable
        get() = when (this) {
            MOVE_MANGA_TO_MORE -> Icons.Outlined.CollectionsBookmark
            MOVE_UPDATES_TO_MORE -> Icons.Outlined.PeopleAlt
            MOVE_HISTORY_TO_MORE -> Icons.Outlined.History
            MOVE_BROWSE_TO_MORE -> Icons.Outlined.Explore
        }

    val tabs: List<Tab>
        get() {
            return mutableListOf(
                AnimeLibraryTab,
                MangaLibraryTab,
                SocialTab,
                HistoriesTab,
                BrowseTab,
                MoreTab,
            ).apply { remove(this@NavStyle.moreTab) }
        }
}
