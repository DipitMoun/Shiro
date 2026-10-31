package eu.kanade.tachiyomi.ui.entries.anime

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.core.net.toUri
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.core.util.ifAnimeSourcesLoaded
import eu.kanade.domain.entries.anime.model.hasCustomBackground
import eu.kanade.domain.entries.anime.model.hasCustomCover
import eu.kanade.domain.entries.anime.model.toSAnime
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.components.NavigatorAdaptiveSheet
import eu.kanade.presentation.entries.EditCoverAction
import eu.kanade.presentation.entries.anime.AnimeScreen
import eu.kanade.presentation.entries.anime.DuplicateAnimeDialog
import eu.kanade.presentation.entries.anime.EpisodeOptionsDialogScreen
import eu.kanade.presentation.entries.anime.EpisodeSettingsDialog
import eu.kanade.presentation.entries.anime.SeasonSettingsDialog
import eu.kanade.presentation.entries.anime.components.AnimeImagesDialog
import eu.kanade.presentation.entries.anime.components.MultiSourceCompatibilityContent
import eu.kanade.presentation.entries.components.DeleteItemsDialog
import eu.kanade.presentation.entries.components.SetIntervalDialog
import eu.kanade.presentation.more.settings.screen.player.PlayerSettingsGesturesScreen.SkipIntroLengthDialog
import eu.kanade.presentation.util.AssistContentScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.formatEpisodeNumber
import eu.kanade.presentation.util.isTabletUi
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.data.multisource.MultiSourceAnimeSource
import eu.kanade.tachiyomi.data.multisource.MultiSourceCompatibility
import eu.kanade.tachiyomi.data.multisource.MultiSourceMappingState
import eu.kanade.tachiyomi.data.telegram.source.TelegramAnimeSource
import eu.kanade.tachiyomi.data.torrent.service.TorrentServerService
import eu.kanade.tachiyomi.source.anime.isLocalOrStub
import eu.kanade.tachiyomi.source.anime.isSourceForTorrents
import eu.kanade.tachiyomi.ui.browse.anime.migration.anime.season.MigrateSeasonSelectScreen
import eu.kanade.tachiyomi.ui.browse.anime.migration.search.MigrateAnimeDialog
import eu.kanade.tachiyomi.ui.browse.anime.migration.search.MigrateAnimeDialogScreenModel
import eu.kanade.tachiyomi.ui.browse.anime.migration.search.MigrateAnimeSearchScreen
import eu.kanade.tachiyomi.ui.browse.anime.source.browse.BrowseAnimeSourceScreen
import eu.kanade.tachiyomi.ui.browse.anime.source.globalsearch.GlobalAnimeSearchScreen
import eu.kanade.tachiyomi.ui.category.CategoriesTab
import eu.kanade.tachiyomi.ui.entries.anime.track.AnimeTrackInfoDialogHomeScreen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.library.anime.AnimeLibraryTab
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.setting.SettingsScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.items.episode.model.Episode
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen

class AnimeScreen(
    private val animeId: Long,
    val fromSource: Boolean = false,
) : Screen(), AssistContentScreen {

    private var assistUrl: String? = null

    override fun onProvideAssistUrl() = assistUrl

    @Composable
    override fun Content() {
        if (!ifAnimeSourcesLoaded()) {
            LoadingScreen()
            return
        }

        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val haptic = LocalHapticFeedback.current
        val scope = rememberCoroutineScope()
        val lifecycleOwner = LocalLifecycleOwner.current
        val screenModel =
            rememberScreenModel { AnimeScreenModel(context, lifecycleOwner.lifecycle, animeId, fromSource) }

        val state by screenModel.state.collectAsStateWithLifecycle()

        if (state is AnimeScreenModel.State.Loading) {
            LoadingScreen()
            return
        }

        val successState = state as AnimeScreenModel.State.Success
        val isAnimeHttpSource = remember { successState.source is AnimeHttpSource }
        val isMultiSource = successState.anime.source == MultiSourceAnimeSource.ID

        val mappingModel = if (isMultiSource) {
            rememberScreenModel { MultiSourceMappingScreenModel(successState.anime.url) }
        } else {
            null
        }
        val mappingState = mappingModel?.state?.collectAsStateWithLifecycle()?.value
        val isTelegram = successState.anime.source == TelegramAnimeSource.ID
        val telegramChannels = remember(successState.anime.url, successState.source) {
            (successState.source as? TelegramAnimeSource)
                ?.workChannels(successState.anime.url)
                .orEmpty()
        }
        val telegramMappingState = remember(telegramChannels) {
            val total = telegramChannels.sumOf { it.episodeCount }.coerceAtLeast(0)
            MultiSourceMappingState(
                loading = false,
                totalEpisodes = total,
                assignedEpisodes = total,
                complete = telegramChannels.isNotEmpty(),
                sources = telegramChannels.map { channel ->
                    MultiSourceCompatibility(
                        sourceId = channel.chatId,
                        sourceName = channel.title,
                        extension = null,
                        compatibility = 1f,
                        coveredEpisodes = channel.episodeCount,
                        assignedEpisodes = channel.episodeCount,
                    )
                },
            )
        }
        val shownMapping = if (isTelegram) telegramMappingState else mappingState
        val canShowSources = isMultiSource || (isTelegram && telegramChannels.isNotEmpty())
        var showSources by rememberSaveable { mutableStateOf(false) }

        LaunchedEffect(isMultiSource, successState.anime.url) {
            mappingModel?.load()
        }

        LaunchedEffect(successState.anime, screenModel.source) {
            if (isAnimeHttpSource) {
                try {
                    withIOContext {
                        assistUrl = getAnimeUrl(screenModel.anime, screenModel.source)
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Failed to get anime URL" }
                }
            }
        }

        AnimeScreen(
            state = successState,
            snackbarHostState = screenModel.snackbarHostState,
            nextUpdate = successState.anime.expectedNextUpdate,
            isTabletUi = isTabletUi(),
            episodeSwipeStartAction = screenModel.episodeSwipeStartAction,
            episodeSwipeEndAction = screenModel.episodeSwipeEndAction,
            showNextEpisodeAirTime = screenModel.showNextEpisodeAirTime,
            alwaysUseExternalPlayer = screenModel.alwaysUseExternalPlayer,
            navigateUp = navigator::pop,
            onEpisodeClicked = { episode, alt ->
                if (isMultiSource && mappingState?.complete != true) {
                    scope.launch {
                        screenModel.snackbarHostState.showSnackbar("Attribution des sources en cours…")
                    }
                } else {
                    scope.launchIO {
                        if (screenModel.isTorrentEnabled() && successState.source.isSourceForTorrents()) {
                            TorrentServerService.start()
                        }
                        val extPlayer = screenModel.alwaysUseExternalPlayer != alt
                        openEpisode(context, screenModel, episode, extPlayer)
                    }
                }
            },
            onDownloadEpisode = screenModel::runEpisodeDownloadActions.takeIf {
                (!isMultiSource || mappingState?.complete == true) &&
                    !successState.source.isLocalOrStub() &&
                    successState.anime.fetchType == FetchType.Episodes
            },
            onAddToLibraryClicked = {
                screenModel.toggleFavorite()
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            },
            onWebViewClicked = {
                openAnimeInWebView(
                    navigator,
                    screenModel.anime,
                    screenModel.source,
                )
            }.takeIf { isAnimeHttpSource },
            onWebViewLongClicked = {
                copyAnimeUrl(
                    context,
                    screenModel.anime,
                    screenModel.source,
                )
            }.takeIf { isAnimeHttpSource },
            onTrackingClicked = {
                if (!successState.hasLoggedInTrackers) {
                    navigator.push(SettingsScreen(SettingsScreen.Destination.Tracking))
                } else {
                    screenModel.showTrackDialog()
                }
            }.takeIf { successState.anime.fetchType == FetchType.Episodes },
            onTagSearch = { scope.launch { performGenreSearch(navigator, it, screenModel.source!!) } },
            onFilterButtonClicked = screenModel::showSettingsDialog,
            onRefresh = screenModel::fetchAllFromSource,
            onContinueWatching = {
                if (isMultiSource && mappingState?.complete != true) {
                    scope.launch {
                        screenModel.snackbarHostState.showSnackbar("Attribution des sources en cours…")
                    }
                } else {
                    scope.launchIO {
                        val extPlayer = screenModel.alwaysUseExternalPlayer
                        continueWatching(context, screenModel, screenModel.getNextUnseenEpisode(), extPlayer)
                    }
                }
            },
            onSearch = { query, global -> scope.launch { performSearch(navigator, query, global) } },
            onCoverClicked = screenModel::showImagesDialog,
            onShareClicked = {
                shareAnime(
                    context,
                    screenModel.anime,
                    screenModel.source,
                )
            }.takeIf { isAnimeHttpSource },
            onDownloadActionClicked = screenModel::runDownloadAction.takeIf {
                (!isMultiSource || mappingState?.complete == true) &&
                    !successState.source.isLocalOrStub() &&
                    successState.anime.fetchType == FetchType.Episodes
            },
            onEditCategoryClicked = screenModel::showChangeCategoryDialog.takeIf { successState.anime.favorite },
            onEditFetchIntervalClicked = screenModel::showSetAnimeFetchIntervalDialog.takeIf {
                successState.anime.favorite
            },
            onMigrateClicked = {
                navigator.push(MigrateAnimeSearchScreen(successState.anime.id))
            }.takeIf { successState.anime.favorite },
            changeAnimeSkipIntro = screenModel::showAnimeSkipIntroDialog
                .takeIf { successState.anime.favorite && successState.anime.fetchType == FetchType.Episodes },
            onMultiBookmarkClicked = screenModel::bookmarkEpisodes,
            onMultiFillermarkClicked = screenModel::fillermarkEpisodes,
            onMultiMarkAsSeenClicked = screenModel::markEpisodesSeen,
            onMarkPreviousAsSeenClicked = screenModel::markPreviousEpisodeSeen,
            onMultiDeleteClicked = screenModel::showDeleteEpisodeDialog,
            onEpisodeSwipe = screenModel::episodeSwipe,
            onEpisodeSelected = screenModel::toggleSelection,
            onAllEpisodeSelected = screenModel::toggleAllSelection,
            onInvertSelection = screenModel::invertSelection,
            onSeasonClicked = {
                navigator.push(AnimeScreen(it.id))
            },
            onContinueWatchingClicked = {
                if (isMultiSource && mappingState?.complete != true) {
                    scope.launch {
                        screenModel.snackbarHostState.showSnackbar("Attribution des sources en cours…")
                    }
                } else {
                    scope.launchIO {
                        val episode = screenModel.getNextUnseenEpisode(it.anime)
                        episode?.let { ep ->
                            openEpisode(context, screenModel, ep, screenModel.alwaysUseExternalPlayer)
                        }
                    }
                }
            },
            onRelatedAnimeClicked = { relatedAnime ->
                navigator.push(AnimeScreen(relatedAnime.id, fromSource = !relatedAnime.favorite))
            },
            onRelatedAnimeLongClicked = { relatedAnime ->
                navigator.push(GlobalAnimeSearchScreen(relatedAnime.title))
            },
            relatedAnimeDisplayMode = screenModel.relatedAnimeDisplayMode,
            sourceMapping = shownMapping,
            onSourcesClicked = if (canShowSources) {
                { showSources = true }
            } else {
                null
            },
        )

        if (showSources && canShowSources) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = { showSources = false },
                sheetState = sheetState,
            ) {
                MultiSourceCompatibilityContent(
                    state = shownMapping ?: MultiSourceMappingState(),
                    onReload = { mappingModel?.retry() },
                )
            }
        }

        val onDismissRequest = {
            screenModel.dismissDialog()
            if (screenModel.autoOpenTrack && screenModel.isFromChangeCategory) {
                screenModel.isFromChangeCategory = false
                screenModel.showTrackDialog()
            }
        }
        when (val dialog = successState.dialog) {
            null -> {}
            is AnimeScreenModel.Dialog.ChangeCategory -> {
                ChangeCategoryDialog(
                    initialSelection = dialog.initialSelection,
                    onDismissRequest = onDismissRequest,
                    onEditCategories = { navigator.push(CategoriesTab) },
                    onConfirm = { include, _ ->
                        screenModel.moveAnimeToCategoriesAndAddToLibrary(dialog.anime, include)
                    },
                )
            }
            is AnimeScreenModel.Dialog.DeleteEpisodes -> {
                DeleteItemsDialog(
                    onDismissRequest = onDismissRequest,
                    onConfirm = {
                        screenModel.toggleAllSelection(false)
                        screenModel.deleteEpisodes(dialog.episodes)
                    },
                    isManga = false,
                )
            }

            is AnimeScreenModel.Dialog.DuplicateAnime -> {
                DuplicateAnimeDialog(
                    onDismissRequest = onDismissRequest,
                    onConfirm = { screenModel.toggleFavorite(onRemoved = {}, checkDuplicate = false) },
                    onOpenAnime = { navigator.push(AnimeScreen(dialog.duplicate.id)) },
                    onMigrate = {
                        screenModel.showMigrateDialog(dialog.duplicate)
                    },
                )
            }

            is AnimeScreenModel.Dialog.Migrate -> {
                MigrateAnimeDialog(
                    oldAnime = dialog.oldAnime,
                    newAnime = dialog.newAnime,
                    screenModel = MigrateAnimeDialogScreenModel(),
                    onDismissRequest = onDismissRequest,
                    onClickTitle = { navigator.push(AnimeScreen(dialog.oldAnime.id)) },
                    onClickSeasons = { navigator.push(MigrateSeasonSelectScreen(dialog.oldAnime, dialog.newAnime)) },
                    onPopScreen = { navigator.replace(AnimeScreen(dialog.newAnime.id)) },
                )
            }
            AnimeScreenModel.Dialog.EpisodeSettingsSheet -> EpisodeSettingsDialog(
                onDismissRequest = onDismissRequest,
                anime = successState.anime,
                onDownloadFilterChanged = screenModel::setDownloadedFilter,
                onUnseenFilterChanged = screenModel::setUnseenFilter,
                onBookmarkedFilterChanged = screenModel::setBookmarkedFilter,
                onFillermarkedFilterChanged = screenModel::setFillermarkedFilter,
                onSortModeChanged = screenModel::setSorting,
                onDisplayModeChanged = screenModel::setDisplayMode,
                onShowPreviewsEnabled = screenModel::showEpisodePreviews,
                onShowSummariesEnabled = screenModel::showEpisodeSummaries,
                onSetAsDefault = screenModel::setCurrentSettingsAsDefault,
            )
            AnimeScreenModel.Dialog.SeasonSettingsSheet -> SeasonSettingsDialog(
                onDismissRequest = onDismissRequest,
                anime = successState.anime,
                onDownloadFilterChanged = screenModel::setSeasonDownloadedFilter,
                onUnseenFilterChanged = screenModel::setSeasonUnseenFilter,
                onStartedFilterChanged = screenModel::setSeasonStartedFilter,
                onCompletedFilterChanged = screenModel::setSeasonCompletedFilter,
                onBookmarkedFilterChanged = screenModel::setSeasonBookmarkedFilter,
                onFillermarkedFilterChanged = screenModel::setSeasonFillermarkedFilter,
                onSortModeChanged = screenModel::setSeasonSorting,
                onDisplayGridModeChanged = screenModel::setSeasonDisplayGridMode,
                onDisplayGridSizeChanged = screenModel::setSeasonDisplayGridSize,
                onOverlayDownloadedChanged = screenModel::setSeasonDownloadOverlay,
                onOverlayUnseenChanged = screenModel::setSeasonUnseenOverlay,
                onOverlayLocalChanged = screenModel::setSeasonLocalOverlay,
                onOverlayLangChanged = screenModel::setSeasonLangOverlay,
                onOverlayContinueChanged = screenModel::setSeasonContinueOverlay,
                onDisplayModeChanged = screenModel::setSeasonDisplayMode,
                onSetAsDefault = screenModel::setSeasonCurrentSettingsAsDefault,
            )
            AnimeScreenModel.Dialog.TrackSheet -> {
                NavigatorAdaptiveSheet(
                    screen = AnimeTrackInfoDialogHomeScreen(
                        animeId = successState.anime.id,
                        animeTitle = successState.anime.title,
                        sourceId = successState.source.id,
                    ),
                    enableSwipeDismiss = { it.lastItem is AnimeTrackInfoDialogHomeScreen },
                    onDismissRequest = onDismissRequest,
                )
            }
            AnimeScreenModel.Dialog.FullImages -> {
                val sm = rememberScreenModel { AnimeImageScreenModel(successState.anime.id) }
                val anime by sm.state.collectAsState()
                if (anime != null) {
                    val getContent = rememberLauncherForActivityResult(
                        ActivityResultContracts.GetContent(),
                    ) {
                        if (it == null) return@rememberLauncherForActivityResult
                        sm.editImage(context, it)
                    }
                    AnimeImagesDialog(
                        anime = anime!!,
                        snackbarHostState = sm.snackbarHostState,
                        pagerState = sm.pagerState,
                        isCustomCover = remember(anime) { anime!!.hasCustomCover() },
                        isCustomBackground = remember(anime) { anime!!.hasCustomBackground() },
                        onShareClick = { sm.shareImage(context) },
                        onSaveClick = { sm.saveImage(context) },
                        onEditClick = {
                            when (it) {
                                EditCoverAction.EDIT -> getContent.launch("image/*")
                                EditCoverAction.DELETE -> sm.deleteCustomImage(context)
                            }
                        },
                        onDismissRequest = onDismissRequest,
                    )
                } else {
                    LoadingScreen(Modifier.systemBarsPadding())
                }
            }
            is AnimeScreenModel.Dialog.SetAnimeFetchInterval -> {
                SetIntervalDialog(
                    interval = dialog.anime.fetchInterval,
                    nextUpdate = dialog.anime.expectedNextUpdate,
                    onDismissRequest = onDismissRequest,
                    isManga = false,
                    onValueChanged = { interval: Int -> screenModel.setFetchInterval(dialog.anime, interval) }
                        .takeIf { screenModel.isUpdateIntervalEnabled },
                )
            }
            AnimeScreenModel.Dialog.ChangeAnimeSkipIntro -> {
                fun updateSkipIntroLength(newLength: Long) {
                    scope.launchIO {
                        screenModel.setAnimeViewerFlags.awaitSetSkipIntroLength(animeId, newLength)
                    }
                }
                SkipIntroLengthDialog(
                    initialSkipIntroLength = if (!successState.anime.skipIntroDisable &&
                        successState.anime.skipIntroLength == 0
                    ) {
                        screenModel.gesturePreferences.defaultIntroLength().get()
                    } else {
                        successState.anime.skipIntroLength
                    },
                    onDismissRequest = onDismissRequest,
                    onValueChanged = {
                        updateSkipIntroLength(it.toLong())
                        onDismissRequest()
                    },
                )
            }
            is AnimeScreenModel.Dialog.ShowQualities -> {
                EpisodeOptionsDialogScreen.onDismissDialog = onDismissRequest
                val episodeTitle = if (dialog.anime.displayMode == Anime.EPISODE_DISPLAY_NUMBER) {
                    stringResource(
                        AYMR.strings.display_mode_episode,
                        formatEpisodeNumber(dialog.episode.episodeNumber),
                    )
                } else {
                    dialog.episode.name
                }
                NavigatorAdaptiveSheet(
                    screen = EpisodeOptionsDialogScreen(
                        useExternalDownloader = screenModel.useExternalDownloader,
                        episodeTitle = episodeTitle,
                        episodeId = dialog.episode.id,
                        animeId = dialog.anime.id,
                        sourceId = dialog.source.id,
                    ),
                    onDismissRequest = onDismissRequest,
                )
            }
        }
    }

    private suspend fun continueWatching(
        context: Context,
        screenModel: AnimeScreenModel,
        unseenEpisode: Episode?,
        useExternalPlayer: Boolean,
    ) {
        if (unseenEpisode != null) openEpisode(context, screenModel, unseenEpisode, useExternalPlayer)
    }

    private suspend fun openEpisode(
        context: Context,
        screenModel: AnimeScreenModel,
        episode: Episode,
        useExternalPlayer: Boolean,
    ) {
        if (!screenModel.ensureTelegramEpisodeDownloaded(episode)) return

        withIOContext {
            MainActivity.startPlayerActivity(
                context,
                episode.animeId,
                episode.id,
                useExternalPlayer,
            )
        }
    }

    private fun getAnimeUrl(anime_: Anime?, source_: AnimeSource?): String? {
        val anime = anime_ ?: return null
        val source = source_ as? AnimeHttpSource ?: return null

        return try {
            source.getAnimeUrl(anime.toSAnime())
        } catch (e: Exception) {
            null
        }
    }

    private fun openAnimeInWebView(navigator: Navigator, anime_: Anime?, source_: AnimeSource?) {
        getAnimeUrl(anime_, source_)?.let { url ->
            navigator.push(
                WebViewScreen(
                    url = url,
                    initialTitle = anime_?.title,
                    sourceId = source_?.id,
                ),
            )
        }
    }

    private fun shareAnime(context: Context, anime_: Anime?, source_: AnimeSource?) {
        try {
            getAnimeUrl(anime_, source_)?.let { url ->
                val intent = url.toUri().toShareIntent(context, type = "text/plain")
                context.startActivity(
                    Intent.createChooser(
                        intent,
                        context.stringResource(MR.strings.action_share),
                    ),
                )
            }
        } catch (e: Exception) {
            context.toast(e.message)
        }
    }

    /**
     * Perform a search using the provided query.
     *
     * @param query the search query to the parent controller
     */
    private suspend fun performSearch(navigator: Navigator, query: String, global: Boolean) {
        if (global) {
            navigator.push(GlobalAnimeSearchScreen(query))
            return
        }

        if (navigator.size < 2) {
            return
        }

        when (val previousController = navigator.items[navigator.size - 2]) {
            is HomeScreen -> {
                navigator.pop()
                AnimeLibraryTab.search(query)
            }
            is BrowseAnimeSourceScreen -> {
                navigator.pop()
                previousController.search(query)
            }
        }
    }

    /**
     * Performs a genre search using the provided genre name.
     *
     * @param genreName the search genre to the parent controller
     */
    private suspend fun performGenreSearch(
        navigator: Navigator,
        genreName: String,
        source: AnimeSource,
    ) {
        if (navigator.size < 2) {
            return
        }

        val previousController = navigator.items[navigator.size - 2]
        if (previousController is BrowseAnimeSourceScreen && source is AnimeHttpSource) {
            navigator.pop()
            previousController.searchGenre(genreName)
        } else {
            performSearch(navigator, genreName, global = false)
        }
    }

    /**
     * Copy Anime URL to Clipboard
     */
    private fun copyAnimeUrl(context: Context, anime_: Anime?, source_: AnimeSource?) {
        val anime = anime_ ?: return
        val source = source_ as? AnimeHttpSource ?: return
        val url = source.getAnimeUrl(anime.toSAnime())
        context.copyToClipboard(url, url)
    }
}
