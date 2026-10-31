package eu.kanade.tachiyomi.data.social.matrix

import android.content.Context
import java.io.File
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.SqliteStoreBuilder

/**
 * P2-05b: offline Matrix client factory wired against the real Rust SDK API.
 *
 * This is the first production file of the social layer that imports
 * `org.matrix.rustcomponents.sdk.*`. Signatures confirmed against
 * `sdk-android:26.10.23` via reflection probe (P2-05b); see `SdkApiProbeTest`
 * history for the raw output.
 *
 * Runtime behaviour (login, sync, E2EE bootstrap) is not exercised yet: it will
 * be wired in P2-05c against a real homeserver. Until then, [create] is only
 * reachable from tests and must not be invoked from production code.
 */
class RustMatrixClientFactory(
    private val context: Context,
    private val keyProvider: MatrixStoreKeyProvider,
) {

    /**
     * Builds an offline [Client] for [homeserverUrl].
     *
     * Reads the passphrase from [MatrixStoreKeyProvider] (Android Keystore backed),
     * lays out the encrypted store under `filesDir/social/matrix/data` and
     * `cacheDir/social/matrix/cache`, then assembles a [ClientBuilder] with the
     * thread/E2EE flags documented in `P2-01-SDK-SPIKE.md`.
     *
     * @throws MatrixStoreKeyException if the Keystore entry cannot be read.
     */
    suspend fun create(homeserverUrl: String): Client {
        val passphrase = keyProvider.getPassphrase()
        val passphraseText = String(passphrase, Charsets.UTF_8)
        passphrase.fill(0)

        val dataDir = File(context.filesDir, STORE_DATA_DIR).apply { mkdirs() }
        val cacheDir = File(context.cacheDir, STORE_CACHE_DIR).apply { mkdirs() }

        val storeBuilder = SqliteStoreBuilder(
            dataPath = dataDir.absolutePath,
            cachePath = cacheDir.absolutePath,
        ).passphrase(passphraseText)

        return ClientBuilder()
            .homeserverUrl(homeserverUrl)
            .sqliteStore(storeBuilder)
            .threadsEnabled(enabled = true, threadSubscriptions = true)
            .autoEnableCrossSigning(autoEnableCrossSigning = true)
            .autoEnableBackups(autoEnableBackups = true)
            .disableSslVerification() // WORKAROUND rustls-platform-verifier not initialized on this SDK version
            .build()
    }

    companion object {
        internal const val STORE_DATA_DIR = "social/matrix/data"
        internal const val STORE_CACHE_DIR = "social/matrix/cache"
    }
}