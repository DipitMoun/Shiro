package eu.kanade.tachiyomi.ui.main



import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File
import androidx.core.content.IntentCompat
import android.provider.OpenableColumns
import android.net.Uri
import eu.kanade.tachiyomi.ui.social.SocialChatContext
import eu.kanade.tachiyomi.ui.social.SocialChatScreen
import eu.kanade.tachiyomi.data.social.identity.WorkId
import eu.kanade.tachiyomi.data.social.identity.WorkIdentity
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Application
import android.app.SearchManager
import android.app.assist.AssistContent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.core.animation.doOnEnd
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.util.Consumer
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.interpolator.view.animation.LinearOutSlowInInterpolator
import androidx.lifecycle.lifecycleScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.NavigatorDisposeBehavior
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.anime.interactor.GetAnimeIncognitoState
import eu.kanade.domain.source.manga.interactor.GetMangaIncognitoState
import eu.kanade.presentation.components.AppStateBanners
import eu.kanade.presentation.components.DownloadedOnlyBannerBackgroundColor
import eu.kanade.presentation.components.IncognitoModeBannerBackgroundColor
import eu.kanade.presentation.components.IndexingBannerBackgroundColor
import eu.kanade.presentation.more.settings.screen.browse.AnimeExtensionStoresScreen
import eu.kanade.presentation.more.settings.screen.browse.MangaExtensionReposScreen
import eu.kanade.presentation.more.settings.screen.data.RestoreBackupScreen
import eu.kanade.presentation.util.AssistContentScreen
import eu.kanade.presentation.util.DefaultNavigatorScreenTransition
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.core.common.Constants
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.download.anime.AnimeDownloadCache
import eu.kanade.tachiyomi.data.download.manga.MangaDownloadCache
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.player.service.HttpServerService
import eu.kanade.tachiyomi.data.social.friends.SocialInviteMonitor
import eu.kanade.tachiyomi.data.social.matrix.session.MatrixSessionBootstrap
import eu.kanade.tachiyomi.data.social.matrix.MatrixSessionManager
import eu.kanade.tachiyomi.data.updater.AppUpdateChecker
import eu.kanade.tachiyomi.data.updater.RELEASE_URL
import eu.kanade.tachiyomi.extension.anime.api.AnimeExtensionApi
import eu.kanade.tachiyomi.extension.manga.api.MangaExtensionApi
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.browse.anime.source.browse.BrowseAnimeSourceScreen
import eu.kanade.tachiyomi.ui.browse.anime.source.globalsearch.GlobalAnimeSearchScreen
import eu.kanade.tachiyomi.ui.browse.manga.source.browse.BrowseMangaSourceScreen
import eu.kanade.tachiyomi.ui.browse.manga.source.globalsearch.GlobalMangaSearchScreen
import eu.kanade.tachiyomi.ui.deeplink.DeepLinkScreenType
import eu.kanade.tachiyomi.ui.deeplink.anime.DeepLinkAnimeScreen
import eu.kanade.tachiyomi.ui.deeplink.manga.DeepLinkMangaScreen
import eu.kanade.tachiyomi.ui.entries.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.entries.manga.MangaScreen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.more.NewUpdateScreen
import eu.kanade.tachiyomi.ui.more.OnboardingScreen
import eu.kanade.tachiyomi.ui.player.ExternalIntents
import eu.kanade.tachiyomi.ui.player.PlayerActivity
import eu.kanade.presentation.more.settings.screen.SettingsSocialScreen
import eu.kanade.tachiyomi.ui.setting.SettingsScreen
import eu.kanade.tachiyomi.ui.social.SocialFriendsScreen
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.isNavigationBarNeedsScrim
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.system.updaterEnabled
import eu.kanade.tachiyomi.util.view.setComposeContent
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import mihon.core.migration.Migrator
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.entries.anime.interactor.GetAnime
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class MainActivity : BaseActivity() {

    private val libraryPreferences: LibraryPreferences by injectLazy()
    private val preferences: BasePreferences by injectLazy()

    private val animeDownloadCache: AnimeDownloadCache by injectLazy()
    private val downloadCache: MangaDownloadCache by injectLazy()
    private val chapterCache: ChapterCache by injectLazy()

    private val getAnimeIncognitoState: GetAnimeIncognitoState by injectLazy()
    private val getMangaIncognitoState: GetMangaIncognitoState by injectLazy()

    // To be checked by splash screen. If true then splash screen will be removed.
    var ready = false

    private var navigator: Navigator? = null

    init {
        registerSecureActivity(this)
    }

        override fun onResume() {
        super.onResume()
        eu.kanade.tachiyomi.ui.social.SocialNotificationState.isAppForeground = true
    }

    override fun onPause() {
        eu.kanade.tachiyomi.ui.social.SocialNotificationState.isAppForeground = false
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val isLaunch = savedInstanceState == null

        // Prevent splash screen showing up on configuration changes
        val splashScreen = if (isLaunch) installSplashScreen() else null

        super.onCreate(savedInstanceState)

        val didMigration = Migrator.awaitAndRelease()
        eu.kanade.tachiyomi.data.notification.Notifications.createChannels(this)

        // Do not let the launcher create a new activity http://stackoverflow.com/questions/16283079
        if (!isTaskRoot) {
            finish()
            return
        }

        // Restore the Matrix session and keep the sync service running: it joins rooms of
        // existing friends and surfaces new friend requests live. Also covers a first login
        // from the settings, which no longer needs an app restart.
        lifecycleScope.launchIO {
            runCatching {
                val session = Injekt.get<MatrixSessionManager>()
                MatrixSessionBootstrap.ensureRestored(session)
                session.state.collect { state ->
                    val pushManager = Injekt.get<eu.kanade.tachiyomi.data.social.matrix.push.SocialPushManager>()
                    when (state) {
                        is eu.kanade.tachiyomi.data.social.matrix.SocialSessionState.Authenticated -> {
                            // Live listeners run in-process while the app is open: no service needed.
                            Injekt.get<eu.kanade.tachiyomi.data.social.matrix.sync.SocialLiveListener>().start()
                            Injekt.get<SocialInviteMonitor>().refresh()
                            pushManager.ensureRegistered()
                            // Always on: OEM freezers (Transsion Hiber, MIUI...) freeze an app woken
                            // by a push within seconds; only a running foreground service is spared.
                            eu.kanade.tachiyomi.data.social.matrix.sync.MatrixSyncService.start(this@MainActivity)
                        }
                        // Next login (maybe another account) must register its own pusher.
                        is eu.kanade.tachiyomi.data.social.matrix.SocialSessionState.Unauthenticated -> {
                            pushManager.reset()
                            eu.kanade.tachiyomi.data.social.matrix.sync.MatrixSyncService.stop(this@MainActivity)
                        }
                        else -> Unit
                    }
                }
            }.onFailure {
                logcat(LogPriority.WARN) { "social startup failed: ${it.message}" }
            }
        }

        setComposeContent {
            val context = LocalContext.current

            var incognito by remember { mutableStateOf(getMangaIncognitoState.await(null)) }
            var incognitoAnime by remember { mutableStateOf(getAnimeIncognitoState.await(null)) }
            val downloadOnly by preferences.downloadedOnly().collectAsState()
            val indexing by downloadCache.isInitializing.collectAsState()
            val indexingAnime by animeDownloadCache.isInitializing.collectAsState()

            val isSystemInDarkTheme = isSystemInDarkTheme()
            val statusBarBackgroundColor = when {
                indexing || indexingAnime -> IndexingBannerBackgroundColor
                downloadOnly -> DownloadedOnlyBannerBackgroundColor
                incognito || incognitoAnime -> IncognitoModeBannerBackgroundColor
                else -> MaterialTheme.colorScheme.surface
            }
            LaunchedEffect(isSystemInDarkTheme, statusBarBackgroundColor) {
                // Draw edge-to-edge and set system bars color to transparent
                val lightStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.BLACK)
                val darkStyle = SystemBarStyle.dark(Color.TRANSPARENT)
                enableEdgeToEdge(
                    statusBarStyle = if (statusBarBackgroundColor.luminance() > 0.5) lightStyle else darkStyle,
                    navigationBarStyle = if (isSystemInDarkTheme) darkStyle else lightStyle,
                )
            }

            Navigator(
                screen = HomeScreen,
                disposeBehavior = NavigatorDisposeBehavior(
                    disposeNestedNavigators = false,
                    disposeSteps = true,
                ),
            ) { navigator ->

                LaunchedEffect(navigator) {
                    this@MainActivity.navigator = navigator

                    if (isLaunch) {
                        // Set start screen
                        handleIntentAction(intent, navigator)

                        // Reset Incognito Mode on relaunch
                        preferences.incognitoMode().set(false)
                    }
                }
                LaunchedEffect(navigator.lastItem) {
                    (navigator.lastItem as? BrowseMangaSourceScreen)?.sourceId
                        .let(getMangaIncognitoState::subscribe)
                        .collectLatest { incognito = it }
                }

                LaunchedEffect(navigator.lastItem) {
                    (navigator.lastItem as? BrowseAnimeSourceScreen)?.sourceId
                        .let(getAnimeIncognitoState::subscribe)
                        .collectLatest { incognitoAnime = it }
                }

                val scaffoldInsets = WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)
                Scaffold(
                    topBar = {
                        AppStateBanners(
                            downloadedOnlyMode = downloadOnly,
                            incognitoMode = incognito || incognitoAnime,
                            indexing = indexing || indexingAnime,
                            modifier = Modifier.windowInsetsPadding(scaffoldInsets),
                        )
                    },
                    contentWindowInsets = scaffoldInsets,
                ) { contentPadding ->
                    // Consume insets already used by app state banners
                    Box {
                        // Social friend invite popup (global, above the navigator)
                        SocialFriendInvitePopup()
                        // Shows current screen
                        DefaultNavigatorScreenTransition(
                            navigator = navigator,
                            modifier = Modifier
                                .padding(contentPadding)
                                .consumeWindowInsets(contentPadding),
                        )
                        // Draw navigation bar scrim when needed
                        if (remember { isNavigationBarNeedsScrim() }) {
                            Spacer(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .windowInsetsBottomHeight(WindowInsets.navigationBars)
                                    .alpha(0.8f)
                                    .background(MaterialTheme.colorScheme.surfaceContainer),
                            )
                        }
                    }
                }

                // Pop source-related screens when incognito mode is turned off
                LaunchedEffect(Unit) {
                    preferences.incognitoMode().changes()
                        .drop(1)
                        .filter { !it }
                        .onEach {
                            val currentScreen = navigator.lastItem
                            if ((
                                    currentScreen is BrowseMangaSourceScreen ||
                                        (currentScreen is MangaScreen && currentScreen.fromSource)
                                    ) ||
                                (
                                    currentScreen is BrowseAnimeSourceScreen ||
                                        (currentScreen is AnimeScreen && currentScreen.fromSource)
                                    )
                            ) {
                                navigator.popUntilRoot()
                            }
                        }
                        .launchIn(this)
                }

                HandleOnNewIntent(context = context, navigator = navigator)

                CheckForUpdates()
                ShowOnboarding()
            }

            var showChangelog by remember { mutableStateOf(didMigration && !BuildConfig.DEBUG) }
            if (showChangelog) {
                eu.kanade.presentation.more.WhatsNewDialog(
                    versionName = BuildConfig.VERSION_NAME,
                    onDismiss = { showChangelog = false },
                )
            }
        }

        val startTime = System.currentTimeMillis()
        splashScreen?.setKeepOnScreenCondition {
            val elapsed = System.currentTimeMillis() - startTime
            elapsed <= SPLASH_MIN_DURATION || !ready && elapsed <= SPLASH_MAX_DURATION
        }
        setSplashScreenExitAnimation(splashScreen)

        if (isLaunch && libraryPreferences.autoClearItemCache().get()) {
            lifecycleScope.launchIO {
                chapterCache.clear()
            }
        }

        externalPlayerResult = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result: ActivityResult ->
            if (result.resultCode == Activity.RESULT_OK) {
                val animeId = savedInstanceState?.getLong(SAVED_STATE_ANIME_KEY)
                val episodeId = savedInstanceState?.getLong(SAVED_STATE_EPISODE_KEY)

                if (animeId != null && episodeId != null) {
                    runBlocking {
                        ExternalIntents.externalIntents.initAnime(animeId, episodeId)
                    }
                }

                ExternalIntents.externalIntents.onActivityResult(result.data)
            }
        }
    }

    override fun onProvideAssistContent(outContent: AssistContent) {
        super.onProvideAssistContent(outContent)
        when (val screen = navigator?.lastItem) {
            is AssistContentScreen -> {
                screen.onProvideAssistUrl()?.let { outContent.webUri = it.toUri() }
            }
        }
    }

    @Composable
    private fun HandleOnNewIntent(context: Context, navigator: Navigator) {
        LaunchedEffect(Unit) {
            callbackFlow {
                val componentActivity = context as ComponentActivity
                val consumer = Consumer<Intent> { trySend(it) }
                componentActivity.addOnNewIntentListener(consumer)
                awaitClose { componentActivity.removeOnNewIntentListener(consumer) }
            }
                .collectLatest { handleIntentAction(it, navigator) }
        }
    }

    @Composable
    private fun CheckForUpdates() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow

        // App updates
        LaunchedEffect(Unit) {
            if (updaterEnabled) {
                try {
                    val result = AppUpdateChecker().checkForUpdate(context)
                    if (result is GetApplicationRelease.Result.NewUpdate) {
                        val updateScreen = NewUpdateScreen(
                            versionName = result.release.version,
                            changelogInfo = result.release.info,
                            releaseLink = result.release.releaseLink,
                            downloadLink = result.release.downloadLink,
                        )
                        navigator.push(updateScreen)
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e)
                }
            }
        }

        // Extensions updates
        LaunchedEffect(Unit) {
            try {
                AnimeExtensionApi().checkForUpdates(context)
                MangaExtensionApi().checkForUpdates(context)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
            }
        }
    }

    @Composable
    private fun ShowOnboarding() {
        val navigator = LocalNavigator.currentOrThrow

        LaunchedEffect(Unit) {
            if (!preferences.shownOnboardingFlow().get() && navigator.lastItem !is OnboardingScreen) {
                navigator.push(OnboardingScreen())
            }
        }
    }

    /**
     * Sets custom splash screen exit animation on devices prior to Android 12.
     *
     * When custom animation is used, status and navigation bar color will be set to transparent and will be restored
     * after the animation is finished.
     */
    @Suppress("Deprecation")
    private fun setSplashScreenExitAnimation(splashScreen: SplashScreen?) {
        val root = findViewById<View>(android.R.id.content)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && splashScreen != null) {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT

            splashScreen.setOnExitAnimationListener { splashProvider ->
                // For some reason the SplashScreen applies (incorrect) Y translation to the iconView
                splashProvider.iconView.translationY = 0F

                val activityAnim = ValueAnimator.ofFloat(1F, 0F).apply {
                    interpolator = LinearOutSlowInInterpolator()
                    duration = SPLASH_EXIT_ANIM_DURATION
                    addUpdateListener { va ->
                        val value = va.animatedValue as Float
                        root.translationY = value * 16.dpToPx
                    }
                }

                val splashAnim = ValueAnimator.ofFloat(1F, 0F).apply {
                    interpolator = FastOutSlowInInterpolator()
                    duration = SPLASH_EXIT_ANIM_DURATION
                    addUpdateListener { va ->
                        val value = va.animatedValue as Float
                        splashProvider.view.alpha = value
                    }
                    doOnEnd {
                        splashProvider.remove()
                    }
                }

                activityAnim.start()
                splashAnim.start()
            }
        }
    }

    private fun handleIntentAction(intent: Intent, navigator: Navigator): Boolean {
        val notificationId = intent.getIntExtra("notificationId", -1)
        if (notificationId > -1) {
            NotificationReceiver.dismissNotification(
                applicationContext,
                notificationId,
                intent.getIntExtra("groupId", 0),
            )
        }

        // A backup file opened or shared from elsewhere: copied here, then the restore screen opens on it.
        val backupUri = incomingBackupUri(intent)
        if (backupUri != null) {
            lifecycleScope.launch {
                val local = withContext(Dispatchers.IO) { copyIncomingBackup(backupUri) }
                if (local == null) {
                    toast("Fichier de sauvegarde illisible")
                } else {
                    navigator.popUntilRoot()
                    navigator.push(RestoreBackupScreen(local.toString()))
                }
            }
            intent.data = null
            ready = true
            return true
        }

        if (intent.hasExtra("roomId") || intent.action?.startsWith("social.room.") == true) {
            return openSocialRoomIntent(intent, navigator)
        }

        val oauthCallback = intent.data?.takeIf { it.scheme == "dev.workers.dipitmoun" && it.path == "/oauth" }
        if (oauthCallback != null) {
            val callbackUrl = oauthCallback.toString()
            // Consumed once: a recreated activity must not replay the one-shot callback.
            intent.data = null
            lifecycleScope.launch {
                val session = Injekt.get<eu.kanade.tachiyomi.data.social.matrix.MatrixSessionManager>()
                val result = session.completeOAuth(callbackUrl)
                if (result is eu.kanade.tachiyomi.data.social.matrix.SocialResult.Failure) {
                    toast("Connexion impossible : ${result.error}")
                }
            }
            ready = true
            return true
        }

        val tabToOpen = when (intent.action) {
            Constants.SHORTCUT_ANIMELIB -> HomeScreen.Tab.AnimeLib()
            Constants.SHORTCUT_LIBRARY -> HomeScreen.Tab.Library()
            Constants.SHORTCUT_MANGA -> {
                val idToOpen = intent.extras?.getLong(Constants.MANGA_EXTRA) ?: return false
                navigator.popUntilRoot()
                HomeScreen.Tab.Library(idToOpen)
            }
            Constants.SHORTCUT_ANIME -> {
                val idToOpen = intent.extras?.getLong(Constants.ANIME_EXTRA) ?: return false
                navigator.popUntilRoot()
                HomeScreen.Tab.AnimeLib(idToOpen)
            }
            Constants.SHORTCUT_UPDATES -> HomeScreen.Tab.Updates
            Constants.SHORTCUT_HISTORY -> HomeScreen.Tab.History
            Constants.SHORTCUT_SOURCES -> HomeScreen.Tab.Browse(tabIndex = 0)
            Constants.SHORTCUT_ANIMEEXTENSIONS -> HomeScreen.Tab.Browse(true, true)
            Constants.SHORTCUT_EXTENSIONS -> HomeScreen.Tab.Browse(true)
            Constants.SHORTCUT_TELEGRAM -> {
                navigator.popUntilRoot()
                HomeScreen.Tab.Browse(tabIndex = 6)
            }
            Constants.SHORTCUT_DOWNLOADS -> {
                navigator.popUntilRoot()
                HomeScreen.Tab.More(toDownloads = true)
            }
            Constants.SHORTCUT_ANIME_DOWNLOADS -> {
                navigator.popUntilRoot()
                HomeScreen.Tab.More(toDownloads = true)
            }
            Constants.INTENT_SOCIAL_SETTINGS -> {
                navigator.popUntilRoot()
                navigator.push(SettingsSocialScreen())
                null
            }
            Intent.ACTION_SEARCH, Intent.ACTION_SEND, "com.google.android.gms.actions.SEARCH_ACTION" -> {
                // If the intent match the "standard" Android search intent
                // or the Google-specific search intent (triggered by saying or typing "search *query* on *Tachiyomi*" in Google Search/Google Assistant)

                // Get the search query provided in extras, and if not null, perform a global search with it.
                val query = intent.getStringExtra(SearchManager.QUERY)
                    ?: intent.getStringExtra(Intent.EXTRA_TEXT)

                if (!query.isNullOrEmpty()) {
                    navigator.popUntilRoot()

                    val screenType = intent.getStringExtra(INTENT_SEARCH_TYPE).orEmpty()
                        .ifBlank { "ANIME" }
                        .let(DeepLinkScreenType::valueOf)

                    when (screenType) {
                        DeepLinkScreenType.MANGA -> {
                            navigator.push(GlobalMangaSearchScreen(query))
                            navigator.push(DeepLinkMangaScreen(query))
                        }
                        DeepLinkScreenType.ANIME -> {
                            navigator.push(GlobalAnimeSearchScreen(query))
                            navigator.push(DeepLinkAnimeScreen(query))
                        }
                    }
                }
                null
            }
            INTENT_SEARCH -> { // Used by extensions (url intent handlers)
                val query = intent.getStringExtra(INTENT_SEARCH_QUERY)
                if (!query.isNullOrEmpty()) {
                    val filter = intent.getStringExtra(INTENT_SEARCH_FILTER)
                    navigator.popUntilRoot()
                    navigator.push(GlobalMangaSearchScreen(query, filter))
                }
                null
            }
            INTENT_ANIMESEARCH -> { // Same as above
                val query = intent.getStringExtra(INTENT_SEARCH_QUERY)
                if (!query.isNullOrEmpty()) {
                    val filter = intent.getStringExtra(INTENT_SEARCH_FILTER)
                    navigator.popUntilRoot()
                    navigator.push(GlobalAnimeSearchScreen(query, filter))
                }
                null
            }
            Intent.ACTION_VIEW -> {
                // Handling opening of backup files
                if (intent.data.toString().endsWith(".tachibk")) {
                    navigator.popUntilRoot()
                    navigator.push(RestoreBackupScreen(intent.data.toString()))
                }
                // Deep link to add anime extension repo
                else if (intent.isAddAnimeExtensionStoreIntent()) {
                    intent.data?.getQueryParameter("url")?.let { repoUrl ->
                        navigator.popUntilRoot()
                        navigator.push(AnimeExtensionStoresScreen(repoUrl))
                    }
                } // Deep link to add extension repo
                else if (intent.scheme == "tachiyomi" && intent.data?.host == "add-repo") {
                    intent.data?.getQueryParameter("url")?.let { repoUrl ->
                        navigator.popUntilRoot()
                        navigator.push(MangaExtensionReposScreen(repoUrl))
                    }
                } // Deep link to add a Shiro friend
                    else if (intent.scheme == "shiro" && intent.data?.host == "social") {
                        navigator.popUntilRoot()
                        navigator.push(SocialFriendsScreen(intent.data.toString()))
                    }

                null
            }
            else -> return false
        }

        if (tabToOpen != null) {
            lifecycleScope.launch { HomeScreen.openTab(tabToOpen) }
        }

        ready = true
        return true
    }

    private fun openSocialRoomIntent(intent: Intent, navigator: Navigator): Boolean {
        val roomId = intent.getStringExtra("roomId") ?: return false
        val workIdString = intent.getStringExtra("workId") ?: return false
        val workId = WorkId.parse(workIdString) ?: return false
        val authorMxid = intent.getStringExtra("authorMxid") ?: return false
        val roomTitle = intent.getStringExtra("roomTitle")
            ?.takeIf { it.isNotBlank() }
            ?.take(200)
            ?: "Salon Matrix"

        val chatContext = SocialChatContext(
            animeId = 0L,
            work = WorkIdentity(
                id = workId,
                ids = mapOf(workId.scheme.wireName to workId.remoteId),
                title = roomTitle,
                legacyId = workId,
                addedAt = null,
            ),
            title = roomTitle,
            posterUrl = null,
            episodes = emptyList(),
            initialRoomId = roomId,
            initialAuthorMxid = authorMxid,
        )
        navigator.popUntilRoot()
        navigator.push(SocialChatScreen(chatContext))
        ready = true
        return true
    }

    /**
     * The file of an "open with" or "share to" when it is a Shiro backup, else null. The address
     * of a file received through a messenger rarely ends with ".tachibk", so its name is read from
     * the provider, and failing that the file is recognised by its gzip header (backups are gzip).
     */
    private fun incomingBackupUri(intent: Intent): Uri? {
        val uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        } ?: return null
        if (uri.scheme != "content" && uri.scheme != "file") return null

        val name = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment
        if (name?.endsWith(".tachibk", ignoreCase = true) == true) return uri

        val gzip = runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                input.read() == 0x1f && input.read() == 0x8b
            }
        }.getOrNull() == true
        return uri.takeIf { gzip }
    }

    /** Own copy of the file: the permission to read the shared one does not outlive this screen. */
    private fun copyIncomingBackup(uri: Uri): Uri? = runCatching {
        val target = File(cacheDir, "incoming-backup.tachibk")
        contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
            ?: return null
        Uri.fromFile(target)
    }.getOrNull()

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        ExternalIntents.externalIntents.animeId?.let {
            outState.putLong(SAVED_STATE_ANIME_KEY, it)
        }
        ExternalIntents.externalIntents.episodeId?.let {
            outState.putLong(SAVED_STATE_EPISODE_KEY, it)
        }
    }

    private fun Intent.isAddAnimeExtensionStoreIntent(): Boolean {
        return scheme == "aniyomi" && (data?.host == "add-repo" || data?.host == "extension-store")
    }

    companion object {
        const val INTENT_SEARCH = "eu.kanade.tachiyomi.SEARCH"
        const val INTENT_ANIMESEARCH = "eu.kanade.tachiyomi.ANIMESEARCH"
        const val INTENT_SEARCH_QUERY = "query"
        const val INTENT_SEARCH_FILTER = "filter"
        const val INTENT_SEARCH_TYPE = "type"

        const val SAVED_STATE_ANIME_KEY = "saved_state_anime_key"
        const val SAVED_STATE_EPISODE_KEY = "saved_state_episode_key"

        private var externalPlayerResult: ActivityResultLauncher<Intent>? = null

        suspend fun startPlayerActivity(
            context: Context,
            animeId: Long,
            episodeId: Long,
            extPlayer: Boolean,
            sourceId: Long? = null,
            video: Video? = null,
            hosterIndex: Int = -1,
            videoIndex: Int = -1,
            hosterList: List<Hoster>? = null,
        ) {
            if (extPlayer) {
                val sourceId = sourceId ?: (Injekt.get<GetAnime>().await(animeId)?.source ?: -1L)
                val (success, port) = startHttpServerService(context, sourceId)
                if (!success) {
                    withUIContext { Injekt.get<Application>().toast(AYMR.strings.http_server_start_failure) }
                    return
                }

                val video = video?.copyHttpServer(port)
                val intent = try {
                    ExternalIntents.newIntent(context, animeId, episodeId, video)
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e)
                    withUIContext { Injekt.get<Application>().toast(e.message) }
                    null
                } ?: return
                externalPlayerResult?.launch(intent) ?: return
            } else {
                context.startActivity(
                    PlayerActivity.newIntent(
                        context,
                        animeId,
                        episodeId,
                        hosterList,
                        hosterIndex,
                        videoIndex,
                    ),
                )
            }
        }

        suspend fun startHttpServerService(
            context: Context,
            sourceId: Long,
            timeout: Duration = 5.seconds,
        ): Pair<Boolean, Int> {
            HttpServerService.resetIsRunning()
            context.startService(
                Intent(context, HttpServerService::class.java)
                    .putExtra(HttpServerService.EXTRA_SOURCE_ID, sourceId),
            )

            val ready = withTimeoutOrNull(timeout) {
                HttpServerService.isRunning.first { it }
            }

            return Pair(ready == true, HttpServerService.port)
        }
    }
}

// Splash screen
private const val SPLASH_MIN_DURATION = 500 // ms
private const val SPLASH_MAX_DURATION = 5000 // ms
private const val SPLASH_EXIT_ANIM_DURATION = 400L // ms
