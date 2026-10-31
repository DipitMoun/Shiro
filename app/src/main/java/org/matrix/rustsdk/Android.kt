package org.matrix.rustsdk

import android.content.Context

/**
 * JNI bridge required by rustls-platform-verifier >= 0.7.0 on Android.
 *
 * The native library needs an Android [Context] to reach the system trust store
 * and validate TLS certificates. Without this call, every Matrix HTTP request
 * fails with:
 *   java.lang.InternalError: Expect rustls-platform-verifier to be initialized
 *
 * Call [init] once from Application.onCreate(), before any Matrix client is
 * created.
 */
object Android {
    @JvmStatic
    external fun init(context: Context)
}