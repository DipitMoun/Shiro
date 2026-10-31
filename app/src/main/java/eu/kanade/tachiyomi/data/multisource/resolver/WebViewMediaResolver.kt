package eu.kanade.tachiyomi.data.multisource.resolver

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.data.multisource.MultiSourceDebugLog
import eu.kanade.tachiyomi.util.system.WebViewUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import org.json.JSONArray
import org.json.JSONTokener
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Résolveur de secours basé sur le WebView système.
 *
 * Certaines extensions ne savent plus extraire l'URL finale d'un lecteur
 * (JavaScript, iframe, token temporaire, changement de domaine). On charge
 * alors la page de l'épisode dans une WebView, on intercepte les requêtes
 * média et on renvoie l'URL utilisable par le lecteur ou FFmpeg.
 *
 * Ce composant est volontairement à la demande : une seule WebView, détruite
 * dès que la résolution est terminée.
 */
class WebViewMediaResolver(
    private val application: Application = Injekt.get(),
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {

    suspend fun resolve(
        pageUrl: String,
        sourceHeaders: Map<String, String> = emptyMap(),
    ): List<WebViewMedia> = RESOLVE_MUTEX.withLock {
        withContext(Dispatchers.Main) {
            if (!WebViewUtil.supportsWebView(application)) {
                logcat(LogPriority.WARN) { "WebViewResolver: WebView indisponible" }
                return@withContext emptyList()
            }
            if (!pageUrl.startsWith("http://") && !pageUrl.startsWith("https://")) {
                logcat(LogPriority.WARN) { "WebViewResolver: URL non HTTP ignorée : $pageUrl" }
                return@withContext emptyList()
            }

            MultiSourceDebugLog.log(
                "webview",
                "start url=$pageUrl headers=${sourceHeaders.keys.joinToString(",")}",
            )

            suspendCancellableCoroutine { continuation ->
                val media = Collections.synchronizedList(mutableListOf<WebViewMedia>())
                val finished = AtomicBoolean(false)
                val handler = Handler(Looper.getMainLooper())
                val visitedPages = mutableSetOf<String>()
                var webView: WebView? = null

                fun destroy() {
                    val view = webView ?: return
                    webView = null
                    runCatching {
                        view.stopLoading()
                        view.loadUrl("about:blank")
                        view.clearHistory()
                        view.removeAllViews()
                        view.destroy()
                    }
                    handler.removeCallbacksAndMessages(null)
                }

                fun finish(result: List<WebViewMedia>) {
                    if (!finished.compareAndSet(false, true)) return
                    destroy()
                    MultiSourceDebugLog.log(
                        "webview",
                        "finish count=${result.size} urls=${result.joinToString(" | ") { it.url }}",
                    )
                    if (continuation.isActive) {
                        continuation.resume(
                            result.distinctBy { it.url }
                                .sortedByDescending { it.url.contains(".m3u8", ignoreCase = true) },
                        )
                    }
                }

                val finishSoon = Runnable {
                    if (!finished.get()) finish(media.toList())
                }

                fun capture(url: String, headers: Map<String, String>?) {
                    if (finished.get() || !isMediaUrl(url)) return
                    if (media.any { it.url == url }) return

                    val merged = linkedMapOf<String, String>()
                    merged.putAll(sourceHeaders)
                    headers?.forEach { (key, value) -> merged[key] = value }
                    CookieManager.getInstance().getCookie(url)
                        ?.takeIf { it.isNotBlank() }
                        ?.let { merged["Cookie"] = it }

                    media.add(WebViewMedia(url = url, headers = merged))
                    MultiSourceDebugLog.log(
                        "webview",
                        "media url=$url headers=${merged.keys.joinToString(",")}",
                    )
                    logcat(LogPriority.DEBUG) { "WebViewResolver: média capturé $url" }

                    // Dès qu'un média jouable est trouvé, on laisse un court délai
                    // pour récupérer une éventuelle meilleure piste, puis on libère
                    // immédiatement la WebView au lieu d'attendre le timeout complet.
                    handler.removeCallbacks(finishSoon)
                    handler.postDelayed(finishSoon, MEDIA_SETTLE_MS)
                }

                fun capturePerformance(view: WebView) {
                    view.evaluateJavascript(PERFORMANCE_JS) { result ->
                        runCatching {
                            parseJsArray(result).let { array ->
                                for (index in 0 until array.length()) {
                                    capture(array.optString(index), null)
                                }
                            }
                        }
                    }
                }

                fun loadFirstIframe(view: WebView, baseUrl: String) {
                    if (finished.get()) return
                    view.evaluateJavascript(IFRAME_JS) { result ->
                        MultiSourceDebugLog.log("webview", "iframeResult=$result")
                        if (finished.get()) return@evaluateJavascript
                        val nextUrl = runCatching {
                            parseJsArray(result).let { array ->
                                (0 until array.length())
                                    .map { array.optString(it) }
                                    .firstOrNull { url ->
                                        (url.startsWith("http://") || url.startsWith("https://")) &&
                                            visitedPages.add(url)
                                    }
                            }
                        }.getOrNull()
                        if (nextUrl != null) {
                            MultiSourceDebugLog.log("webview", "iframe load url=$nextUrl referer=$baseUrl")
                            val iframeHeaders = sourceHeaders.toMutableMap().apply {
                                put("Referer", baseUrl)
                            }
                            view.loadUrl(nextUrl, iframeHeaders)
                        }
                    }
                }

                @SuppressLint("SetJavaScriptEnabled")
                fun createWebView(): WebView {
                    val view = WebView(application)
                    view.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        cacheMode = WebSettings.LOAD_DEFAULT
                        allowFileAccess = false
                        allowContentAccess = false
                        userAgentString = sourceHeaders["User-Agent"]
                            ?: WebSettings.getDefaultUserAgent(application)
                    }
                    CookieManager.getInstance().apply {
                        setAcceptCookie(true)
                        setAcceptThirdPartyCookies(view, true)
                    }
                    view.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ): WebResourceResponse? {
                            capture(request.url.toString(), request.requestHeaders)
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            super.onPageFinished(view, url)
                            if (finished.get()) return
                            MultiSourceDebugLog.log("webview", "pageFinished url=$url")
                            view.evaluateJavascript(PLAY_JS, null)
                            view.evaluateJavascript(DOM_JS) { state ->
                                MultiSourceDebugLog.log("webview", "dom=$state")
                            }
                            loadFirstIframe(view, url ?: pageUrl)
                            handler.postDelayed({ capturePerformance(view) }, 1_500)
                            handler.postDelayed({
                                view.evaluateJavascript(PLAY_JS, null)
                                capturePerformance(view)
                            }, 4_000)
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: android.webkit.WebResourceError,
                        ) {
                            MultiSourceDebugLog.log(
                                "webview",
                                "error code=${error.errorCode} url=${request.url} " +
                                    "description=${error.description}",
                            )
                            logcat(LogPriority.WARN) {
                                "WebViewResolver: erreur ${error.errorCode} pour ${request.url}"
                            }
                        }
                    }
                    return view
                }

                handler.postDelayed({
                    logcat(LogPriority.WARN) {
                        "WebViewResolver: délai dépassé, ${media.size} média(s) capturé(s)"
                    }
                    finish(media.toList())
                }, timeoutMs)

                continuation.invokeOnCancellation {
                    handler.post {
                        if (finished.compareAndSet(false, true)) destroy()
                    }
                }

                runCatching {
                    webView = createWebView()
                    webView?.onResume()
                    webView?.resumeTimers()
                    if (sourceHeaders.isEmpty()) {
                        webView?.loadUrl(pageUrl)
                    } else {
                        webView?.loadUrl(pageUrl, sourceHeaders)
                    }
                }.onFailure {
                    MultiSourceDebugLog.log("webview", "create failed", it)
                    logcat(LogPriority.ERROR, it) { "WebViewResolver: création WebView impossible" }
                    finish(emptyList())
                }
            }
        }
    }

    /**
     * evaluateJavascript renvoie une chaîne JSON qui contient elle-même une
     * chaîne JSON quand le JS retourne JSON.stringify(...). Il faut donc
     * déballer les deux niveaux avant de lire le tableau.
     */
    private fun parseJsArray(result: String?): JSONArray {
        if (result.isNullOrBlank()) return JSONArray()
        return runCatching {
            when (val value = JSONTokener(result).nextValue()) {
                is JSONArray -> value
                is String -> JSONArray(value)
                else -> JSONArray()
            }
        }.getOrDefault(JSONArray())
    }

    private fun isMediaUrl(url: String): Boolean {
        if (url.isBlank() || url.startsWith("blob:") || url.startsWith("data:")) return false
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return path.endsWith(".m3u8") ||
            path.endsWith(".mp4") ||
            path.endsWith(".mpd") ||
            path.contains("/master.m3u8")
    }

    data class WebViewMedia(
        val url: String,
        val headers: Map<String, String>,
    )

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
        const val MEDIA_SETTLE_MS = 1_500L

        /**
         * Plusieurs téléchargements peuvent demander une résolution en parallèle.
         * On les sérialise pour ne jamais garder deux WebView en mémoire.
         */
        val RESOLVE_MUTEX = Mutex()

        const val IFRAME_JS = """
            (function() {
                try {
                    const urls = [];
                    document.querySelectorAll('iframe').forEach(function(f) {
                        if (f.src && /^https?:\/\//i.test(f.src)) urls.push(f.src);
                    });
                    try {
                        if (typeof thisChapterSources === 'object' && thisChapterSources) {
                            Object.values(thisChapterSources).forEach(function(html) {
                                const match = /src=["']([^"']+)/i.exec(html || '');
                                if (match && /^https?:\/\//i.test(match[1])) urls.push(match[1]);
                            });
                        }
                    } catch (e) {}
                    return JSON.stringify(Array.from(new Set(urls)));
                } catch (e) {
                    return "[]";
                }
            })()
        """

        const val DOM_JS = """
            (function() {
                try {
                    return JSON.stringify({
                        title: document.title,
                        iframes: Array.from(document.querySelectorAll('iframe')).map(function(f) { return f.src; }),
                        videos: Array.from(document.querySelectorAll('video, source')).map(function(v) { return v.src; }),
                        chapterSources: typeof thisChapterSources,
                        text: (document.body && document.body.innerText || '').slice(0, 300)
                    });
                } catch (e) {
                    return "{}";
                }
            })()
        """

        const val PERFORMANCE_JS = """
            (function() {
                try {
                    return JSON.stringify(
                        performance.getEntriesByType('resource')
                            .map(e => e.name)
                            .filter(u => /\.(m3u8|mp4|mpd)(\?|$)/i.test(u))
                    );
                } catch (e) {
                    return "[]";
                }
            })()
        """

        const val PLAY_JS = """
            (function() {
                try {
                    document.querySelectorAll('video').forEach(function(v) {
                        v.muted = true;
                        var p = v.play();
                        if (p && p.catch) p.catch(function() {});
                    });
                    document.querySelectorAll(
                        '[class*="play" i], [aria-label*="play" i], button[title*="play" i], .vjs-big-play-button'
                    ).forEach(function(el) {
                        try { el.click(); } catch (e) {}
                    });
                    var gateWords = ['18', 'oui', 'yes', 'accepter', 'entrer', 'continuer', 'adulte'];
                    document.querySelectorAll('button, a, [role="button"]').forEach(function(el) {
                        var text = (el.innerText || '').toLowerCase();
                        if (gateWords.some(function(word) { return text.indexOf(word) !== -1; })) {
                            try { el.click(); } catch (e) {}
                        }
                    });
                } catch (e) {}
                return true;
            })()
        """
    }
}
