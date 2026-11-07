package org.matrix.rustsdk

import android.content.Context

/**
 * JNI bridge required by rustls-platform-verifier on Android.
 *
 * The native library needs an Android [Context] to reach the system trust store
 * and validate TLS certificates. Without this call, every Matrix HTTP request
 * fails with:
 *   java.lang.InternalError: Expect rustls-platform-verifier to be initialized
 *
 * The native library must be loaded before [init] is called. Loading it here is
 * idempotent and lets the SDK reuse the same handle later.
 */
object Android {
    init {
        System.loadLibrary("matrix_sdk_ffi")
    }

    @JvmStatic
    external fun init(context: Context)
}
