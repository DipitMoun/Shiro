package org.rustls.platformverifier

/**
 * The upstream verifier source references its module's generated BuildConfig.
 * Shiro does not test the verifier itself, so the test-only branches stay off.
 */
internal object BuildConfig {
    const val TEST = false
}
