package eu.kanade.tachiyomi.data.telegram.tdlib

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stockage local de la session TDLib.
 *
 * TDLib écrit sa base d'authentification dans [databaseDir]. Cette base est chiffrée
 * par TDLib avec [databaseKey]. La clé TDLib est protégée par Android Keystore.
 */
class TelegramSessionStorage(context: Context) {

    private val appContext = context.applicationContext
    private val rootDir = File(appContext.noBackupFilesDir, ROOT_DIR_NAME)

    val databaseDir: File = File(rootDir, DATABASE_DIR_NAME)
    val filesDir: File = File(rootDir, FILES_DIR_NAME)

    private val keyFile: File = File(rootDir, KEY_FILE_NAME)

    init {
        prepare()
    }

    /**
     * Crée les dossiers internes nécessaires avant le démarrage de TDLib.
     */
    fun prepare() {
        ensureDirectory(rootDir)
        ensureDirectory(databaseDir)
        ensureDirectory(filesDir)
        runCatching { File(filesDir, ".nomedia").createNewFile() }
    }

    /**
     * Indique si une base TDLib existe déjà.
     */
    val hasSession: Boolean
        get() = databaseDir.listFiles()?.isNotEmpty() == true

    /**
     * Retourne la clé TDLib, en la créant si c'est la première connexion.
     *
     * La clé retournée est un ByteArray brut. L'appelant doit la passer à TDLib
     * puis la remettre à zéro dès que possible.
     */
    @Synchronized
    fun databaseKey(): ByteArray {
        prepare()

        if (keyFile.isFile) {
            return readAndUnwrapKey()
        }

        if (hasSession) {
            throw TelegramSessionKeyException(
                "La session TDLib existe mais sa clé de chiffrement est absente.",
            )
        }

        return createAndStoreKey()
    }

    /**
     * Retourne la clé existante, ou null si aucune clé n'a encore été créée.
     * Utilisé pour répondre à AuthorizationStateWaitEncryptionKey sans créer
     * une nouvelle clé au milieu d'une session existante.
     */
    @Synchronized
    fun existingDatabaseKey(): ByteArray? {
        prepare()
        return if (keyFile.isFile) readAndUnwrapKey() else null
    }

    /**
     * Efface la session et la clé.
     *
     * IMPORTANT : appeler cette méthode après la fermeture du client TDLib.
     */
    @Synchronized
    fun clearSession() {
        runCatching { rootDir.deleteRecursively() }
        runCatching { deleteKeystoreKey() }
        prepare()
    }

    private fun createAndStoreKey(): ByteArray {
        val raw = ByteArray(DATABASE_KEY_BYTES).also { SecureRandom().nextBytes(it) }
        return try {
            writeAtomically(keyFile, wrap(raw))
            raw
        } catch (t: Throwable) {
            raw.fill(0)
            throw TelegramSessionKeyException(
                "Impossible de stocker la clé de session TDLib.",
                t,
            )
        }
    }

    private fun readAndUnwrapKey(): ByteArray {
        val payload = try {
            keyFile.readBytes()
        } catch (t: Throwable) {
            throw TelegramSessionKeyException(
                "Impossible de lire la clé de session TDLib.",
                t,
            )
        }

        if (payload.size <= HEADER_BYTES || payload[0].toInt() != FORMAT_VERSION) {
            throw TelegramSessionKeyException("Format de clé de session TDLib invalide.")
        }

        return try {
            unwrap(payload)
        } catch (t: Throwable) {
            throw TelegramSessionKeyException(
                "Impossible de déchiffrer la clé de session TDLib.",
                t,
            )
        }
    }

    private fun wrap(rawKey: ByteArray): ByteArray {
        val secretKey = getOrCreateKeystoreKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val ciphertext = cipher.doFinal(rawKey)
        return byteArrayOf(FORMAT_VERSION.toByte()) + cipher.iv + ciphertext
    }

    private fun unwrap(payload: ByteArray): ByteArray {
        val iv = payload.copyOfRange(1, 1 + IV_BYTES)
        val ciphertext = payload.copyOfRange(1 + IV_BYTES, payload.size)
        val secretKey = loadKeystoreKey()
            ?: throw TelegramSessionKeyException("Clé Keystore TDLib absente.")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun getOrCreateKeystoreKey(): SecretKey {
        loadKeystoreKey()?.let { return it }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(DATABASE_KEY_BYTES * 8)
                .build(),
        )
        return generator.generateKey()
    }

    private fun loadKeystoreKey(): SecretKey? {
        val keyStore = keyStore()
        return (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    private fun deleteKeystoreKey() {
        val keyStore = keyStore()
        if (keyStore.containsAlias(KEY_ALIAS)) {
            keyStore.deleteEntry(KEY_ALIAS)
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply {
        load(null)
    }

    private fun writeAtomically(file: File, bytes: ByteArray) {
        val parent = file.parentFile ?: error("Fichier sans dossier parent: ${file.absolutePath}")
        val temp = File(parent, "${file.name}.tmp")
        try {
            FileOutputStream(temp).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
            }
        } finally {
            temp.delete()
        }
    }

    private fun ensureDirectory(directory: File) {
        check(directory.isDirectory || directory.mkdirs()) {
            "Impossible de créer le dossier de session TDLib: ${directory.absolutePath}"
        }
    }

    private companion object {
        const val ROOT_DIR_NAME = "telegram-tdlib"
        const val DATABASE_DIR_NAME = "database"
        const val FILES_DIR_NAME = "files"
        const val KEY_FILE_NAME = "database-key.bin"

        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "shiro_tdlib_database_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val DATABASE_KEY_BYTES = 32
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val FORMAT_VERSION = 1
        const val HEADER_BYTES = 1 + IV_BYTES
    }
}

class TelegramSessionKeyException(
    override val message: String,
    cause: Throwable? = null,
) : Exception(message, cause)