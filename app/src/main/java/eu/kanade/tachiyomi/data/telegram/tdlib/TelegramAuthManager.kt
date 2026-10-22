package eu.kanade.tachiyomi.data.telegram.tdlib

import android.content.Context
import android.os.Build
import eu.kanade.tachiyomi.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import tachiyomi.core.common.util.system.logcat
import java.io.Closeable
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Gestionnaire unique de la session TDLib de l'utilisateur.
 *
 * Un seul client TDLib doit exister par processus. Utiliser cette classe via Injekt
 * comme singleton applicatif.
 */
class TelegramAuthManager(
    context: Context,
    private val sessionStorage: TelegramSessionStorage = TelegramSessionStorage(context),
    private val config: TelegramTdlibConfig = TelegramTdlibConfig.fromBuildConfig(),
) : Closeable {

    private val _authState = MutableStateFlow<TelegramAuthState>(TelegramAuthState.Starting)
    val authState: StateFlow<TelegramAuthState> = _authState.asStateFlow()

    private val _currentUser = MutableStateFlow<TelegramUserSummary?>(null)
    val currentUser: StateFlow<TelegramUserSummary?> = _currentUser.asStateFlow()

    /**
     * Toutes les updates TDLib. Les consommateurs doivent filtrer par type.
     * Aucun résultat de requête n'est émis ici.
     */
    private val _updates = MutableSharedFlow<TdApi.Object>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val updates: SharedFlow<TdApi.Object> = _updates.asSharedFlow()

    @Volatile
    private var tdlibClient: Client? = null

    private val updateHandler = Client.ResultHandler { update ->
        _updates.tryEmit(update)
        if (update is TdApi.UpdateAuthorizationState) {
            onAuthorizationState(update.authorizationState)
        }
    }

    private val exceptionHandler = Client.ExceptionHandler { throwable ->
        logcat(LogPriority.ERROR, throwable) { "Exception native TDLib" }
        _authState.value = TelegramAuthState.Error(
            throwable.message ?: "Erreur native TDLib",
        )
    }

    /**
     * Démarre TDLib. Idempotent.
     *
     * À appeler quand l'écran Telegram est ouvert, pas forcément au démarrage de l'app.
     */
    @Synchronized
    fun start() {
        if (tdlibClient != null) return

        if (!config.isConfigured) {
            _authState.value = TelegramAuthState.Error(
                "TELEGRAM_API_ID/TELEGRAM_API_HASH manquants. Voir la configuration Gradle.",
            )
            return
        }

        sessionStorage.prepare()

        _authState.value = TelegramAuthState.Starting

        try {
            tdlibClient = Client.create(updateHandler, exceptionHandler, exceptionHandler)
        } catch (t: Throwable) {
            logcat(LogPriority.ERROR, t) { "Impossible de créer le client TDLib" }
            _authState.value = TelegramAuthState.Error(
                t.message ?: "Impossible de charger TDLib. Vérifiez l'AAR et les bibliothèques natives.",
            )
        }
    }

    private fun configureTdlib() {
        val key = try {
            sessionStorage.databaseKey()
        } catch (t: Throwable) {
            logcat(LogPriority.ERROR, t) { "Clé de session TDLib illisible" }
            runCatching { sessionStorage.clearSession() }
            _authState.value = TelegramAuthState.Error(
                "Session TDLib illisible. Reconnectez votre compte Telegram.",
            )
            return
        }

        val parameters = TdApi.TdlibParameters().apply {
            useTestDc = false
            databaseDirectory = sessionStorage.databaseDir.absolutePath
            filesDirectory = sessionStorage.filesDir.absolutePath
            databaseEncryptionKey = key.copyOf()
            useFileDatabase = true
            useChatInfoDatabase = true
            useMessageDatabase = true
            useSecretChats = false
            apiId = config.apiId
            apiHash = config.apiHash
            systemLanguageCode = "en"
            deviceModel = Build.MODEL
            systemVersion = Build.VERSION.RELEASE
            applicationVersion = BuildConfig.VERSION_NAME
            enableStorageOptimizer = true
            ignoreFileNames = false
        }

        send(TdApi.SetTdlibParameters(parameters))
        key.fill(0)
        _authState.value = TelegramAuthState.WaitingTdlibParameters
    }

    private fun onAuthorizationState(state: TdApi.AuthorizationState) {
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> configureTdlib()

            is TdApi.AuthorizationStateWaitEncryptionKey -> {
                val key = try {
                    sessionStorage.existingDatabaseKey()
                } catch (t: Throwable) {
                    logcat(LogPriority.ERROR, t) { "Clé TDLib absente ou invalide" }
                    null
                }

                if (key == null) {
                    runCatching { sessionStorage.clearSession() }
                    _authState.value = TelegramAuthState.Error(
                        "La clé de session TDLib est absente. Reconnectez votre compte.",
                    )
                    return
                }

                send(TdApi.CheckDatabaseEncryptionKey(key))
                key.fill(0)
            }

            is TdApi.AuthorizationStateWaitPhoneNumber -> {
                _authState.value = TelegramAuthState.WaitingPhoneNumber
            }

            is TdApi.AuthorizationStateWaitCode -> {
                _authState.value = TelegramAuthState.WaitingCode(
                    phoneNumber = state.codeInfo.phoneNumber,
                    codeType = state.codeInfo.type.toString(),
                    timeoutSeconds = state.codeInfo.timeout,
                )
            }

            is TdApi.AuthorizationStateWaitPassword -> {
                _authState.value = TelegramAuthState.WaitingPassword(
                    passwordHint = state.passwordHint,
                )
            }

            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> {
                _authState.value = TelegramAuthState.WaitingOtherDevice(state.link)
            }

            is TdApi.AuthorizationStateWaitRegistration -> {
                _authState.value = TelegramAuthState.WaitingRegistration
            }

            is TdApi.AuthorizationStateReady -> onReady()

            is TdApi.AuthorizationStateLoggingOut -> {
                _authState.value = TelegramAuthState.LoggingOut
            }

            is TdApi.AuthorizationStateClosing -> {
                _authState.value = TelegramAuthState.Closing
            }

            is TdApi.AuthorizationStateClosed -> {
                _currentUser.value = null
                _authState.value = TelegramAuthState.Closed
            }

            else -> {
                _authState.value = TelegramAuthState.Unknown(state.toString())
            }
        }
    }

    private fun onReady() {
        _authState.value = TelegramAuthState.Ready(null)

        send(
            TdApi.GetMe(),
            onSuccess = { user ->
                val summary = TelegramUserSummary(
                    id = user.id,
                    displayName = listOf(user.firstName, user.lastName)
                        .filter { it.isNotBlank() }
                        .joinToString(" ")
                        .ifBlank { "Compte Telegram" },
                    phoneNumber = user.phoneNumber.takeIf { it.isNotBlank() },
                )
                _currentUser.value = summary
                _authState.value = TelegramAuthState.Ready(summary)
            },
            onError = { error ->
                // Ne pas casser l'état Ready si seul GetMe échoue.
                logcat(LogPriority.WARNING, error) { "GetMe TDLib a échoué" }
            },
        )
    }

    /**
     * TODO(UI) : appeler cette méthode depuis le formulaire du numéro de téléphone.
     */
    fun sendPhoneNumber(phoneNumber: String) {
        start()

        val normalized = normalizePhoneNumber(phoneNumber)
        if (normalized == null) {
            _authState.value = TelegramAuthState.Error(
                "Numéro invalide. Utilisez le format international, par exemple +33612345678.",
            )
            return
        }

        send(
            TdApi.SetAuthenticationPhoneNumber(
                normalized,
                TdApi.PhoneNumberAuthenticationSettings(),
            ),
        )
    }

    /**
     * TODO(UI) : afficher un champ code quand [authState] vaut WaitingCode.
     */
    fun sendAuthCode(code: String) {
        val clean = code.trim()
        if (clean.length !in 3..10) {
            _authState.value = TelegramAuthState.Error("Code Telegram invalide.")
            return
        }

        send(TdApi.CheckAuthenticationCode(clean))
    }

    /**
     * TODO(UI) : afficher un champ mot de passe quand [authState] vaut WaitingPassword.
     */
    fun checkPassword(password: String) {
        if (password.isEmpty()) {
            _authState.value = TelegramAuthState.Error("Mot de passe 2FA vide.")
            return
        }

        send(TdApi.CheckAuthenticationPassword(password))
    }

    fun resendCode() {
        send(TdApi.ResendAuthenticationCode())
    }

    /**
     * TODO(UI) : bouton "Se déconnecter" sans effacer immédiatement les données locales.
     */
    fun logOut() {
        send(TdApi.LogOut())
    }

    /**
     * Déconnexion complète : TDLib, base de session et clé Keystore.
     *
     * TODO(UI) : demander une confirmation explicite avant d'appeler cette méthode.
     */
    suspend fun logoutAndWipe() = withContext(Dispatchers.IO) {
        val client = tdlibClient
        if (client != null) {
            runCatching {
                withTimeoutOrNull(10_000) {
                    client.sendAwait<TdApi.Ok>(TdApi.LogOut())
                }
            }
        }

        close()
        sessionStorage.clearSession()
        _currentUser.value = null
        _authState.value = TelegramAuthState.Closed
    }

    @Synchronized
    override fun close() {
        val client = tdlibClient ?: return
        tdlibClient = null

        runCatching {
            client.send(TdApi.Close()) { }
        }
        runCatching {
            client.close()
        }
    }

    fun restart() {
        close()
        start()
    }

    internal fun requireClient(): Client {
        return tdlibClient ?: throw TelegramTdlibException(
            0,
            "TDLib n'est pas démarré. Appelez start().",
        )
    }

    val isReady: Boolean
        get() = _authState.value is TelegramAuthState.Ready

    private fun normalizePhoneNumber(raw: String): String? {
        val cleaned = raw.trim()
            .replace(" ", "")
            .replace("-", "")
            .replace("(", "")
            .replace(")", "")

        if (!cleaned.startsWith("+")) return null

        val digits = cleaned.drop(1)
        return cleaned.takeIf {
            digits.length in 7..15 && digits.all(Char::isDigit)
        }
    }

    private fun <T : TdApi.Object> send(
        function: TdApi.Function<T>,
        onSuccess: (T) -> Unit = {},
        onError: (TelegramTdlibException) -> Unit = { onTdlibError(it) },
    ) {
        val client = tdlibClient
        if (client == null) {
            onError(TelegramTdlibException(0, "TDLib n'est pas démarré."))
            return
        }

        try {
            client.send(function) { result ->
                when (result) {
                    is TdApi.Error -> {
                        onError(TelegramTdlibException(result.code, result.message))
                    }

                    else -> {
                        @Suppress("UNCHECKED_CAST")
                        onSuccess(result as T)
                    }
                }
            }
        } catch (t: Throwable) {
            onError(TelegramTdlibException(0, t.message ?: "Erreur TDLib"))
        }
    }

    private fun onTdlibError(error: TelegramTdlibException) {
        logcat(LogPriority.ERROR, error) { "TDLib ${error.code}: ${error.message}" }
        _authState.value = TelegramAuthState.Error(error.message, error.code)
    }
}

data class TelegramTdlibConfig(
    val apiId: Int,
    val apiHash: String,
) {
    val isConfigured: Boolean
        get() = apiId > 0 && apiHash.isNotBlank()

    companion object {
        fun fromBuildConfig(): TelegramTdlibConfig = TelegramTdlibConfig(
            apiId = BuildConfig.TELEGRAM_API_ID,
            apiHash = BuildConfig.TELEGRAM_API_HASH,
        )
    }
}

data class TelegramUserSummary(
    val id: Long,
    val displayName: String,
    val phoneNumber: String?,
)

sealed interface TelegramAuthState {
    data object Starting : TelegramAuthState
    data object WaitingTdlibParameters : TelegramAuthState
    data object WaitingEncryptionKey : TelegramAuthState
    data object WaitingPhoneNumber : TelegramAuthState

    data class WaitingCode(
        val phoneNumber: String,
        val codeType: String,
        val timeoutSeconds: Int,
    ) : TelegramAuthState

    data class WaitingPassword(
        val passwordHint: String,
    ) : TelegramAuthState

    data object WaitingRegistration : TelegramAuthState

    data class WaitingOtherDevice(
        val link: String,
    ) : TelegramAuthState

    data class Ready(
        val user: TelegramUserSummary?,
    ) : TelegramAuthState

    data object LoggingOut : TelegramAuthState
    data object Closing : TelegramAuthState
    data object Closed : TelegramAuthState
    data class Error(
        val message: String,
        val code: Int = 0,
    ) : TelegramAuthState

    data class Unknown(
        val description: String,
    ) : TelegramAuthState
}

class TelegramTdlibException(
    val code: Int,
    override val message: String,
) : Exception(message)

/**
 * Attend le résultat d'une fonction TDLib.
 *
 * Exemple :
 * val chat = client.sendAwait<TdApi.Chat>(TdApi.GetChat(chatId))
 */
suspend fun <T : TdApi.Object> Client.sendAwait(
    function: TdApi.Function<T>,
): T = suspendCancellableCoroutine { continuation ->
    try {
        send(function) { result ->
            when (result) {
                is TdApi.Error -> {
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            TelegramTdlibException(result.code, result.message),
                        )
                    }
                }

                else -> {
                    @Suppress("UNCHECKED_CAST")
                    if (continuation.isActive) {
                        continuation.resume(result as T)
                    }
                }
            }
        }
    } catch (t: Throwable) {
        if (continuation.isActive) {
            continuation.resumeWithException(t)
        }
    }
}
