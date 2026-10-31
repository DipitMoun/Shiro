package eu.kanade.presentation.library.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.presentation.core.theme.active

/**
 * Top bar used when a collection is opened. It mimics the bar of a work screen: the back arrow
 * sits exactly where it does in a work, the same filter and overflow actions are available, and
 * the collection name fades in once the banner is scrolled out of the screen.
 */
@Composable
fun CollectionToolbar(
    title: String,
    titleAlpha: Float,
    hasActiveFilters: Boolean,
    navigateUp: () -> Unit,
    onEdit: () -> Unit,
    onClickFilter: () -> Unit,
    onClickRefresh: () -> Unit,
    onClickOpenRandomEntry: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior?,
    modifier: Modifier = Modifier,
) {
    AppBar(
        titleContent = {
            AppBarTitle(
                title = title,
                modifier = Modifier.alpha(titleAlpha),
            )
        },
        modifier = modifier,
        backgroundColor = MaterialTheme.colorScheme
            .surfaceColorAtElevation(3.dp)
            .copy(alpha = titleAlpha),
        navigateUp = navigateUp,
        actions = {
            IconButton(onClick = onEdit) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = "Modifier la collection",
                )
            }
            IconButton(onClick = onClickFilter) {
                Icon(
                    imageVector = Icons.Outlined.FilterList,
                    contentDescription = "Filtres",
                    tint = if (hasActiveFilters) {
                        MaterialTheme.colorScheme.active
                    } else {
                        LocalContentColor.current
                    },
                )
            }
            AppBarActions(
                persistentListOf(
                    AppBar.OverflowAction(
                        title = "Mettre à jour cette collection",
                        onClick = onClickRefresh,
                    ),
                    AppBar.OverflowAction(
                        title = "Ouvrir une œuvre au hasard",
                        onClick = onClickOpenRandomEntry,
                    ),
                ),
            )
        },
        scrollBehavior = scrollBehavior,
    )
}
