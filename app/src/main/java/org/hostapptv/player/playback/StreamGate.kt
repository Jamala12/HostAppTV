package org.hostapptv.player.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Counts provider media connections that are open right now. The controller waits for the count to reach
 * zero before opening the next stream, so a channel change never holds two provider connections at once.
 */
@OptIn(UnstableApi::class)
class StreamGate {
    private val _open = MutableStateFlow(0)
    val openConnections: StateFlow<Int> = _open

    /**
     * Suspends until no media connection has been open for [settleMs] (a loader that was preparing when the
     * previous stream stopped may still open and immediately close its socket), or [timeoutMs] passes.
     */
    suspend fun awaitClosed(timeoutMs: Long = 2_000, settleMs: Long = 120): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (true) {
                _open.first { it == 0 }
                delay(settleMs)
                if (_open.value == 0) break
            }
        } != null

    fun wrap(upstream: DataSource.Factory): DataSource.Factory = DataSource.Factory { Counting(upstream.createDataSource()) }

    private inner class Counting(private val delegate: DataSource) : DataSource {
        private var counted = false

        override fun addTransferListener(transferListener: TransferListener) = delegate.addTransferListener(transferListener)

        override fun open(dataSpec: DataSpec): Long {
            synchronized(this) { if (!counted) { counted = true; _open.value += 1 } }
            return delegate.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = delegate.read(buffer, offset, length)
        override fun getUri(): Uri? = delegate.uri
        override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders

        override fun close() {
            try { delegate.close() } finally {
                synchronized(this) { if (counted) { counted = false; _open.value -= 1 } }
            }
        }
    }
}
