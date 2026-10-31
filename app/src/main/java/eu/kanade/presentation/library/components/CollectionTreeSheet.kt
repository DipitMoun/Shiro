package eu.kanade.presentation.library.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tachiyomi.domain.category.model.Category
import java.text.Normalizer

private val DiacriticsRegex = Regex("\\p{Mn}+")

private data class TreeRow(
    val category: Category,
    val depth: Int,
    val hasChildren: Boolean,
    /** Parent chain, only used for flat search results (e.g. "Shonen › Vu"). */
    val path: String? = null,
)

private fun String.searchKey(): String {
    return Normalizer.normalize(this, Normalizer.Form.NFD)
        .replace(DiacriticsRegex, "")
        .lowercase()
        .trim()
}

/**
 * Bottom sheet used to jump to any collection of the library.
 *
 * Collections are displayed as a tree: folder icons, indentation, expand/collapse chevrons, entry
 * counts and a search field. The search filters the tree as the user types (ignoring accents and
 * case) and shows the parent path of every nested match. The collection currently opened is
 * highlighted.
 */
@Composable
fun CollectionTreeSheet(
    categories: List<Category>,
    currentCategoryId: Long?,
    workCountForCategory: (Category) -> Int,
    onCategorySelected: (Category) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    val byId = remember(categories) { categories.associateBy { it.id } }
    val childrenByParent = remember(categories) { categories.groupBy { it.parentId } }
    val roots = remember(categories) { categories.filter { it.parentId == null } }

    val expanded = remember(categories) {
        mutableStateMapOf<Long, Boolean>().apply {
            // Reveal the current collection by expanding all its ancestors.
            var parentId = currentCategoryId?.let { byId[it]?.parentId }
            while (parentId != null) {
                put(parentId, true)
                parentId = byId[parentId]?.parentId
            }
        }
    }

    fun pathOf(category: Category): String? {
        val parts = mutableListOf<String>()
        var parentId = category.parentId
        var guard = 0
        while (parentId != null && guard++ < 32) {
            val parent = byId[parentId] ?: break
            parts.add(0, parent.name)
            parentId = parent.parentId
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" › ")
    }

    val searchKey = query.searchKey()
    val isSearching = searchKey.isNotEmpty()

    // Recomputed on every composition so the list follows the query keystroke by keystroke.
    val rows: List<TreeRow> = if (!isSearching) {
        buildList {
            fun walk(category: Category, depth: Int) {
                val children = childrenByParent[category.id].orEmpty()
                add(TreeRow(category, depth, children.isNotEmpty()))
                if (children.isNotEmpty() && expanded[category.id] == true) {
                    children.forEach { walk(it, depth + 1) }
                }
            }
            roots.forEach { walk(it, 0) }
        }
    } else {
        categories
            .filter { it.name.searchKey().contains(searchKey) }
            .sortedBy { it.name.searchKey() }
            .map { TreeRow(it, 0, false, pathOf(it)) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            item(key = "sheet_header") {
                Column {
                    Text(
                        text = "Collections",
                        style = MaterialTheme.typography.titleLarge,
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Rechercher une collection") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "Effacer")
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = { focusManager.clearFocus() },
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            if (rows.isEmpty()) {
                item(key = "sheet_empty") {
                    Text(
                        text = if (isSearching) {
                            "Aucune collection ne correspond à « $query »"
                        } else {
                            "Aucune collection"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
            }

            items(rows, key = { "row_${it.category.id}" }) { row ->
                CollectionTreeRow(
                    row = row,
                    isCurrent = row.category.id == currentCategoryId,
                    expanded = expanded[row.category.id] == true,
                    workCount = workCountForCategory(row.category),
                    onClick = { onCategorySelected(row.category) },
                    onToggle = {
                        expanded[row.category.id] = expanded[row.category.id] != true
                    },
                )
            }
        }
    }
}

@Composable
private fun CollectionTreeRow(
    row: TreeRow,
    isCurrent: Boolean,
    expanded: Boolean,
    workCount: Int,
    onClick: () -> Unit,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isCurrent) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    Color.Transparent
                },
            )
            .clickable(onClick = onClick)
            .padding(
                start = 10.dp + (row.depth * 18).dp,
                end = 4.dp,
                top = 10.dp,
                bottom = 10.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (row.hasChildren && expanded) {
                Icons.Default.FolderOpen
            } else {
                Icons.Default.Folder
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.category.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.path != null) {
                Text(
                    text = row.path,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Text(
            text = "$workCount",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (row.hasChildren) {
            IconButton(
                onClick = onToggle,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = if (expanded) {
                        Icons.Default.ExpandLess
                    } else {
                        Icons.Default.ExpandMore
                    },
                    contentDescription = if (expanded) "Replier" else "Déplier",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Spacer(modifier = Modifier.width(32.dp))
        }
    }
}
