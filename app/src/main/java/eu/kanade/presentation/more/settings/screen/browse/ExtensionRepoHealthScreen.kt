package eu.kanade.presentation.more.settings.screen.browse

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.extension.ExtensionRepoHealthChecker
import eu.kanade.tachiyomi.extension.RepoHealth
import eu.kanade.tachiyomi.extension.RepoStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import tachiyomi.presentation.core.components.material.Scaffold

/**
 * Says, for every anime store and manga repo, whether it answers and whether the app can load
 * what it offers. A repo that moves to a newer extension library silently loses its extensions
 * in the app; this is where that shows.
 */
class ExtensionRepoHealthScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model = rememberScreenModel { ExtensionRepoHealthScreenModel() }
        val state by model.state.collectAsState()

        LaunchedEffect(Unit) { model.check() }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    navigateUp = navigator::pop,
                    title = "Santé des dépôts",
                    scrollBehavior = scrollBehavior,
                    actions = {
                        if (state.loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(end = 12.dp).size(22.dp),
                                strokeWidth = 2.5.dp,
                            )
                        } else {
                            IconButton(onClick = model::check) {
                                Icon(Icons.Outlined.Refresh, contentDescription = "Vérifier maintenant")
                            }
                        }
                    },
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Summary(state)
                }
                items(state.report, key = { (if (it.anime) "a:" else "m:") + it.url }) { repo ->
                    RepoCard(repo)
                }
            }
        }
    }
}

@Composable
private fun Summary(state: ExtensionRepoHealthState) {
    val problems = state.report.count { it.hasProblem }
    val text = when {
        state.loading && state.report.isEmpty() -> "Vérification des dépôts…"
        state.report.isEmpty() -> "Aucun dépôt ajouté."
        problems == 0 -> "Tous les dépôts répondent et leurs extensions sont compatibles."
        problems == 1 -> "1 dépôt demande ton attention."
        else -> "$problems dépôts demandent ton attention."
    }
    Text(
        text = text,
        fontSize = 14.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun RepoCard(repo: RepoHealth) {
    var expanded by remember { mutableStateOf(false) }
    val (icon, tint, label) = when (repo.status) {
        RepoStatus.OK -> Triple(Icons.Filled.CheckCircle, Color(0xFF4CAF50), "Compatible")
        RepoStatus.PARTLY_INCOMPATIBLE -> Triple(Icons.Outlined.WarningAmber, Color(0xFFFFB300), "Partiellement compatible")
        RepoStatus.OUTDATED_FORMAT -> Triple(Icons.Outlined.WarningAmber, Color(0xFFFFB300), "Ancien format")
        RepoStatus.UNREACHABLE -> Triple(Icons.Outlined.ErrorOutline, MaterialTheme.colorScheme.error, "Inaccessible")
        RepoStatus.EMPTY -> Triple(Icons.Outlined.WarningAmber, Color(0xFFFFB300), "Vide")
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable(enabled = repo.incompatible.isNotEmpty() || repo.note != null) { expanded = !expanded },
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                Column(Modifier.padding(start = 10.dp).weight(1f)) {
                    Text(repo.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(
                        text = (if (repo.anime) "Anime" else "Manga") + " · " + label +
                            if (repo.total > 0) " · ${repo.total} extensions" else "",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            repo.note?.let {
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (repo.incompatible.isNotEmpty()) {
                Text(
                    text = "${repo.incompatible.size} extension(s) que cette version de Shiro ne peut pas charger" +
                        if (expanded) " :" else " (toucher pour voir)",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (expanded) {
                    Text(repo.incompatible.joinToString("\n"), fontSize = 12.sp)
                }
            }
            Box(Modifier.padding(top = 2.dp)) {
                Text(
                    text = repo.url,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                )
            }
        }
    }
}

data class ExtensionRepoHealthState(
    val loading: Boolean = false,
    val report: List<RepoHealth> = emptyList(),
)

class ExtensionRepoHealthScreenModel(
    private val checker: ExtensionRepoHealthChecker = ExtensionRepoHealthChecker(),
) : ScreenModel {

    private val _state = MutableStateFlow(ExtensionRepoHealthState())
    val state: StateFlow<ExtensionRepoHealthState> = _state.asStateFlow()

    fun check() {
        if (_state.value.loading) return
        _state.value = _state.value.copy(loading = true)
        screenModelScope.launch {
            val report = runCatching { checker.checkAll() }.getOrDefault(emptyList())
            _state.value = ExtensionRepoHealthState(loading = false, report = report)
        }
    }
}
