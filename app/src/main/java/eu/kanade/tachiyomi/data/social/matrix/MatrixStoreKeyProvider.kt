package eu.kanade.tachiyomi.data.social.matrix

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Provides the passphrase for the encrypted Matrix SQLite store.
 *
 * A random 32-byte passphrase is created once, wrapped with a non-exportable Android Keystore
 * key, and persisted as Base64-encoded ciphertext and IV in private shared preferences. Each call
 * returns a newly decrypted byte array; callers should clear it after passing it to the SDK.
 */
class MatrixStoreKeyProvider(context: Context) {

    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /**
     * Returns the existing passphrase, or creates and persists one on first use.
     *
     * Corrupt preferences, a missing Keystore key, authentication failures, and persistence errors
     * are reported instead of silently replacing the key and making an existing store unreadable.
     */
    @Throws(MatrixStoreKeyException::class)
    fun getPassphrase(): ByteArray = synchronized(lock) {
        try {
            val keyStore = loadKeyStore()
            val ciphertext = preferences.getString(CIPHERTEXT_KEY, null)
            val iv = preferences.getString(IV_KEY, null)

            when {
                ciphertext == null && iv == null -> createAndStorePassphrase(keyStore)
                ciphertext == null || iv == null -> throw MatrixStoreKeyException(
                    "Matrix store key preferences are incomplete.",
                )
                else -> decryptPassphrase(keyStore, ciphertext, iv)
            }
        } catch (e: MatrixStoreKeyException) {
            throw e
        } catch (e: Exception) {
            throw MatrixStoreKeyException("Unable to access the Matrix store passphrase.", e)
        }
    }

    private fun createAndStorePassphrase(keyStore: KeyStore): ByteArray {
        val passphrase = ByteArray(PASSPHRASE_BYTES)
        try {
            SecureRandom().nextBytes(passphrase)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(keyStore))
            val ciphertext = cipher.doFinal(passphrase)

            val persisted = preferences.edit()
                .putString(CIPHERTEXT_KEY, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .putString(IV_KEY, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .commit()
            if (!persisted) {
                throw MatrixStoreKeyException("Unable to persist the Matrix store passphrase.")
            }

            return passphrase
        } catch (t: Throwable) {
            passphrase.fill(0)
            throw t
        }
    }

    private fun decryptPassphrase(keyStore: KeyStore, ciphertext: String, iv: String): ByteArray {
        val key = getExistingKey(keyStore)
            ?: throw MatrixStoreKeyException("The Matrix store Keystore key is missing.")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(GCM_TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)),
        )
        return cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP))
    }

    private fun getOrCreateKey(keyStore: KeyStore): SecretKey {
        getExistingKey(keyStore)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(PASSPHRASE_BYTES * 8)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun getExistingKey(keyStore: KeyStore): SecretKey? {
        val entry = keyStore.getEntry(KEY_ALIAS, null) ?: return null
        return (entry as? KeyStore.SecretKeyEntry)?.secretKey
            ?: throw MatrixStoreKeyException("The Matrix store Keystore entry is not a secret key.")
    }

    private fun loadKeyStore(): KeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply {
        load(null)
    }

    private companion object {
        val lock = Any()

        const val PREFERENCES_NAME = "matrix_store_key"
        const val CIPHERTEXT_KEY = "passphrase_ciphertext"
        const val IV_KEY = "passphrase_iv"

        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "shiro_matrix_store_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PASSPHRASE_BYTES = 32
        const val GCM_TAG_BITS = 128
    }
}

class MatrixStoreKeyException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
