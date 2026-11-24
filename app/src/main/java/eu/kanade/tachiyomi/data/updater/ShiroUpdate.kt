package eu.kanade.tachiyomi.data.updater

import android.content.Context
import android.os.Build
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.release.model.Release
import uy.kohesive.injekt.injectLazy
import java.io.File

/**
 * The small file published with every release (`latest.json`): which version is current and
 * where to get it, per CPU architecture, with the patches from earlier versions.
 */
@Serializable
data class ShiroUpdateManifest(
    val versionCode: Long,
    val versionName: String,
    /** Text shown as "what's new". */
    val notes: String = "",
    val releasePage: String = "",
    /** By architecture (arm64-v8a, armeabi-v7a, x86_64, x86) or "universal". */
    val apks: Map<String, ShiroUpdateApk> = emptyMap(),
)

@Serializable
data class ShiroUpdateApk(
    val url: String,
    val sha256: String,
    val size: Long = 0L,
    val patches: List<ShiroUpdatePatch> = emptyList(),
)

/** A patch that turns one earlier APK (identified by its hash) into this one. */
@Serializable
data class ShiroUpdatePatch(
    val fromSha256: String,
    val url: String,
    val size: Long = 0L,
)

/** What the app will download for an update. */
@Serializable
data class ShiroUpdatePlan(
    val versionCode: Long,
    val versionName: String,
    val notes: String,
    val releasePage: String,
    val apkUrl: String,
    val apkSha256: String,
    val apkSize: Long,
    val patchUrl: String? = null,
    val patchSize: Long = 0L,
)

/**
 * Chooses what to download, or null when the app is already up to date or nothing fits.
 *
 * The APK is the first of [supportedAbis] the manifest has, else the universal one. A patch is
 * used only when it starts from exactly the APK installed on the phone ([installedSha256] is read
 * lazily: hashing 80 MB is not free).
 */
internal fun selectUpdate(
    manifest: ShiroUpdateManifest,
    currentVersionCode: Long,
    supportedAbis: List<String>,
    installedSha256: () -> String?,
): ShiroUpdatePlan? {
    if (manifest.versionCode <= currentVersionCode) return null

    val apk = supportedAbis.firstNotNullOfOrNull { manifest.apks[it] } ?: manifest.apks["universal"] ?: return null
    val patch = if (apk.patches.isEmpty()) {
        null
    } else {
        val installed = installedSha256()
        apk.patches.firstOrNull { installed != null && it.fromSha256.equals(installed, ignoreCase = true) }
    }

    return ShiroUpdatePlan(
        versionCode = manifest.versionCode,
        versionName = manifest.versionName,
        notes = manifest.notes,
        releasePage = manifest.releasePage,
        apkUrl = apk.url,
        apkSha256 = apk.sha256.lowercase(),
        apkSize = apk.size,
        patchUrl = patch?.url,
        patchSize = patch?.size ?: 0L,
    )
}

/** Remembers the update found, for the download that starts later (possibly from a notification). */
class ShiroUpdatePlanStore(context: Context) {
    private val prefs = context.getSharedPreferences("shiro_update", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun save(plan: ShiroUpdatePlan) {
        prefs.edit().putString(KEY_PLAN, json.encodeToString(ShiroUpdatePlan.serializer(), plan)).apply()
    }

    fun forUrl(apkUrl: String): ShiroUpdatePlan? {
        val raw = prefs.getString(KEY_PLAN, null) ?: return null
        return runCatching { json.decodeFromString(ShiroUpdatePlan.serializer(), raw) }
            .getOrNull()
            ?.takeIf { it.apkUrl == apkUrl }
    }

    fun lastCheckAt(): Long = prefs.getLong(KEY_LAST_CHECK, 0L)

    fun markChecked(now: Long) {
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
    }

    private companion object {
        const val KEY_PLAN = "plan"
        const val KEY_LAST_CHECK = "last_check"
    }
}

class ShiroUpdateChecker {

    private val network: NetworkHelper by injectLazy()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * @return the update to offer, or null when there is none. Checks at most every
     * [MIN_CHECK_INTERVAL_MS] unless [force].
     */
    suspend fun check(context: Context, force: Boolean): Release? = withIOContext {
        val url = BuildConfig.SHIRO_UPDATE_MANIFEST_URL
        if (url.isBlank()) return@withIOContext null

        val store = ShiroUpdatePlanStore(context)
        val now = System.currentTimeMillis()
        if (!force && now - store.lastCheckAt() < MIN_CHECK_INTERVAL_MS) return@withIOContext null

        val manifest = network.client.newCall(GET(url)).awaitSuccess().use { response ->
            json.decodeFromString(ShiroUpdateManifest.serializer(), response.body.string())
        }
        store.markChecked(now)

        val plan = selectUpdate(
            manifest = manifest,
            currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
            installedSha256 = { installedApkSha256(context) },
        ) ?: return@withIOContext null

        store.save(plan)
        logcat(LogPriority.INFO) {
            "update ${plan.versionName}: " + if (plan.patchUrl != null) "patch ${plan.patchSize} bytes" else "full apk"
        }
        Release(
            version = plan.versionName,
            info = plan.notes,
            releaseLink = plan.releasePage,
            downloadLink = plan.apkUrl,
        )
    }

    private fun installedApkSha256(context: Context): String? = runCatching {
        ApkPatch.sha256Hex(File(context.applicationInfo.sourceDir))
    }.getOrNull()

    private companion object {
        const val MIN_CHECK_INTERVAL_MS = 3 * 60 * 60 * 1000L
    }
}
