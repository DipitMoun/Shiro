package eu.kanade.tachiyomi.ui.browse.telegram

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.telegram.TelegramChannelDetailScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.entries.anime.AnimeScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Détail d'un canal : œuvres détectées, œuvre forcée, import, suppression.
 */
class TelegramChannelScreen(
    private val model: TelegramScreenModel,
    private val chatId: Long,
    private val unresolvedOnly: Boolean = false,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val state by model.state.collectAsState()

        val channel = remember(state.channels, state.revision, chatId) {
            state.channels.firstOrNull { it.chatId == chatId }
        }

        // La synchronisation est déclenchée à l'entrée dans la collection,
        // pas au chargement de la page source. La sous-collection « Non
        // reconnus » hérite de la synchro déjà faite par le parent : la
        // relancer ferait clignoter la liste et fausserait le retour auto.
        LaunchedEffect(chatId, unresolvedOnly) {
            if (!unresolvedOnly) {
                model.syncChannelOnOpen(chatId)
            }
        }
        // Le JSON d'un canal peut peser plusieurs dizaines de Mo : il est lu
        // hors du thread principal, uniquement quand ce canal est ouvert.
        //
        // `state.revision` est la clé fiable : une reconnaissance ou une fusion
        // ne modifie pas forcément `state.channels` (les résumés peuvent rester
        // identiques), donc l'ancienne clé ne déclenchait jamais le recalcul.
        // La révision est incrémentée par chaque `refresh()` du modèle, y
        // compris après la reconnaissance d'une œuvre inconnue.
        val works by produceState<List<TelegramWorkUi>?>(
            initialValue = null,
            chatId,
            state.revision,
        ) {
            value = withContext(Dispatchers.IO) { model.channelWorks(chatId) }
        }

        val loadedWorks = works.orEmpty()
        val unresolved = loadedWorks.filterNot { it.resolved }

        // Une fois la dernière œuvre inconnue reconnue, la sous-collection
        // n'a plus de raison d'être : retour automatique au parent.
        LaunchedEffect(unresolvedOnly, works, state.busy) {
            if (unresolvedOnly &&
                works != null &&
                !state.busy &&
                unresolved.isEmpty()
            ) {
                navigator.pop()
            }
        }

        TelegramChannelDetailScreen(
            channel = channel,
            works = loadedWorks,
            loading = works == null,
            busy = state.busy,
            unresolvedOnly = unresolvedOnly,
            navigateUp = navigator::pop,
            onOpenUnresolved = {
                navigator.push(TelegramChannelScreen(model, chatId, unresolvedOnly = true))
            },
            onWorkClick = { work ->
                model.openWork(work) { animeId ->
                    navigator.push(AnimeScreen(animeId, fromSource = true))
                }
            },
            onRecognizeWork = { work, provider ->
                model.recognizeWork(work, provider)
            },
            onRenameWork = { work, title -> model.renameWork(work, title) },
            onMergeWork = { source, target -> model.mergeWorks(source, target) },
            onSeparateWork = { work -> model.unmergeWork(work) },
            onResetWork = { work -> model.resetWork(work) },
            onHideWork = { work -> model.hideWork(work) },
            onImport = { model.importChannel(chatId, includeUnknown = false, includeDocuments = false) },
            onScanFullHistory = { model.scanFullHistory(chatId) },
            onSync = { model.syncChannelNow(chatId) },
            onJoin = { model.joinChannel(chatId) },
            onRemove = {
                model.removeChannel(chatId)
                navigator.pop()
            },
        )
    }
}
