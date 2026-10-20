package org.hostapptv.player.core

import android.util.Log

/** All app logging goes through redaction. */
object AppLog {
    private const val TAG = "HostAppTV"
    @Volatile var secrets: Array<String> = emptyArray()

    fun i(msg: String) { Log.i(TAG, Redactor.redact(msg, *secrets)) }
    fun w(msg: String, t: Throwable? = null) {
        Log.w(TAG, Redactor.redact(msg + (t?.let { ": ${it.javaClass.simpleName} ${it.message}" } ?: ""), *secrets))
    }
    fun d(msg: String) { Log.d(TAG, Redactor.redact(msg, *secrets)) }
}
