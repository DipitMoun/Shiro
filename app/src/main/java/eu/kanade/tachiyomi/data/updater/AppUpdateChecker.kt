package eu.kanade.tachiyomi.data.updater

import android.content.Context
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.util.system.isPreviewBuildType
import tachiyomi.domain.release.interactor.GetApplicationRelease

class AppUpdateChecker {

    private val shiroChecker = ShiroUpdateChecker()

    /** Looks for a newer Shiro build in the manifest published with the releases. */
    suspend fun checkForUpdate(context: Context, forceCheck: Boolean = false): GetApplicationRelease.Result {
        val release = shiroChecker.check(context, forceCheck) ?: return GetApplicationRelease.Result.NoNewUpdate
        return GetApplicationRelease.Result.NewUpdate(release)
    }
}

val GITHUB_REPO: String by lazy {
    if (isPreviewBuildType) {
        "DipitMoun/Shiro"
    } else {
        "DipitMoun/Shiro"
    }
}

val RELEASE_TAG: String by lazy {
    if (isPreviewBuildType) {
        "r${BuildConfig.COMMIT_COUNT}"
    } else {
        "v${BuildConfig.VERSION_NAME}"
    }
}

val RELEASE_URL = "https://github.com/$GITHUB_REPO/releases"
