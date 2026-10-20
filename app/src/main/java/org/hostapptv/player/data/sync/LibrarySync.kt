package org.hostapptv.player.data.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.hostapptv.player.core.AppLog
import org.hostapptv.player.core.EditionParser
import org.hostapptv.player.core.TextNormalizer
import org.hostapptv.player.data.db.CatalogDao
import org.hostapptv.player.data.db.CategoryEntity
import org.hostapptv.player.data.db.ChannelEntity
import org.hostapptv.player.data.db.MovieEntity
import org.hostapptv.player.data.db.SeriesEntity
import org.hostapptv.player.data.db.SyncStateEntity
import org.hostapptv.player.data.xtream.CatalogKind
import org.hostapptv.player.data.xtream.XtreamClient
import org.hostapptv.player.data.xtream.XtreamException

data class SyncProgress(
    val running: Boolean = false,
    val current: CatalogKind? = null,
    val itemsInCurrent: Int = 0,
    val error: String? = null,
    val paused: Boolean = false,
)

/**
 * Sequential foreground refresh: Live TV, then Movies, then Series. Rows are upserted in bounded
 * transactions tagged with a new generation; older rows are pruned only after a catalogue
 * completes, so an interrupted refresh never erases the working library.
 */
class LibrarySync(private val dao: CatalogDao, private val scope: CoroutineScope) {
    private data class Refresh(val accountId: String, val client: XtreamClient, val kinds: List<CatalogKind>)
    private val requested = MutableStateFlow<Refresh?>(null)
    private val foreground = MutableStateFlow(true)
    private val _progress = MutableStateFlow(SyncProgress())
    val progress: StateFlow<SyncProgress> = _progress

    init {
        scope.launch(Dispatchers.IO) {
            combine(foreground, requested) { visible, request -> request.takeIf { visible } }.collectLatest { request ->
                if (request == null) {
                    _progress.value = _progress.value.copy(running = false, paused = requested.value != null)
                } else {
                    try { runSync(request.accountId, request.client, request.kinds) }
                    finally { _progress.value = _progress.value.copy(running = false, paused = !foreground.value && requested.value != null) }
                    requested.compareAndSet(request, null)
                }
            }
        }
    }

    /** Same-account duplicate requests coalesce; resuming restarts an interrupted catalogue safely. */
    fun refresh(accountId: String, client: XtreamClient, kinds: List<CatalogKind> = CatalogKind.entries) {
        val current = requested.value
        if (current?.accountId == accountId && current.kinds == kinds) return
        requested.value = Refresh(accountId, client, kinds)
    }

    /** Bulk imports run only on the browsing screen, never alongside video decoding or in Fire TV Home. */
    fun setForeground(visible: Boolean) { foreground.value = visible }

    fun cancel() { requested.value = null; _progress.value = SyncProgress() }

    private suspend fun runSync(accountId: String, client: XtreamClient, kinds: List<CatalogKind>) {
        _progress.value = SyncProgress(running = true)
        var firstError: String? = null
        for (kind in kinds) {
            val gen = System.currentTimeMillis()
            _progress.value = SyncProgress(running = true, current = kind)
            var previous: SyncStateEntity? = null
            try {
                previous = dao.syncStatesNow(accountId).firstOrNull { it.kind == kind.key }
                // Preserve the prior count before writing the running state: it protects against destructive empty refreshes.
                dao.upsertSyncState(SyncStateEntity(accountId, kind.key, previous?.lastCompleteAt, gen, previous?.count ?: 0, "running", null))
                val cats = client.categories(kind)
                dao.upsertCategories(cats.map { CategoryEntity(accountId, kind.key, it.id, it.name, it.order, gen) })
                val previousCount = previous?.count ?: 0
                val count = when (kind) {
                    CatalogKind.LIVE -> syncLive(accountId, client, gen, previousCount)
                    CatalogKind.MOVIE -> syncMovies(accountId, client, gen, previousCount)
                    CatalogKind.SERIES -> syncSeries(accountId, client, gen, previousCount)
                }
                dao.pruneCategories(accountId, kind.key, gen)
                dao.upsertSyncState(SyncStateEntity(accountId, kind.key, System.currentTimeMillis(), gen, count, "complete", null))
                AppLog.i("sync ${kind.key}: $count items")
            } catch (e: CancellationException) {
                recordFailure(accountId, kind, gen, previous, "interrupted", null)
                throw e
            } catch (e: Exception) {
                AppLog.w("sync ${kind.key} failed", e)
                val message = if (e is XtreamException) e.message else "Couldn't save the library. Check available storage and try refreshing again."
                firstError = firstError ?: message
                recordFailure(accountId, kind, gen, previous, "failed", message)
                if (e is XtreamException.Unauthorized || e is XtreamException.CleartextRefused) break
            }
        }
        _progress.value = SyncProgress(running = false, error = firstError)
    }

    private suspend fun recordFailure(a: String, kind: CatalogKind, gen: Long, previous: SyncStateEntity?, status: String, message: String?) {
        withContext(NonCancellable) {
            // Storage failure can also prevent bookkeeping; it must never escape the worker and crash playback.
            runCatching { dao.upsertSyncState(SyncStateEntity(a, kind.key, previous?.lastCompleteAt, gen, previous?.count ?: 0, status, message)) }
        }
    }

    private fun bump(n: Int) { _progress.value = _progress.value.copy(itemsInCurrent = _progress.value.itemsInCurrent + n) }

    private fun guard(n: Int, previous: Int) { if (n == 0 && previous > 0) throw XtreamException.SuspiciousEmpty() }

    private suspend fun syncLive(a: String, client: XtreamClient, gen: Long, previous: Int): Int {
        var position = 0
        val n = client.liveStreams { batch ->
            dao.upsertChannels(batch.map {
                ChannelEntity(a, it.streamId, it.num, it.num ?: (100_000 + position++), it.name,
                    TextNormalizer.normalize(it.name), it.categoryId, it.icon, it.epgChannelId, it.tvArchive, gen)
            })
            bump(batch.size)
        }
        guard(n, previous)
        dao.pruneChannels(a, gen)
        return n
    }

    private suspend fun syncMovies(a: String, client: XtreamClient, gen: Long, previous: Int): Int {
        val n = client.movies { batch ->
            dao.insertMovieBatch(a, batch.map {
                val e = EditionParser.parse(it.name, it.year)
                MovieEntity(a, it.streamId, it.name, e.title, TextNormalizer.normalize(e.title), it.categoryId, it.icon,
                    it.rating, it.added, it.extension, it.tmdbId, EditionParser.groupKey("movie", it.tmdbId, it.streamId),
                    e.language, e.quality, e.year, generation = gen)
            })
            bump(batch.size)
        }
        guard(n, previous)
        dao.finishMovies(a, gen)
        return n
    }

    private suspend fun syncSeries(a: String, client: XtreamClient, gen: Long, previous: Int): Int {
        val n = client.series { batch ->
            dao.insertSeriesBatch(a, batch.map {
                val e = EditionParser.parse(it.name, it.year)
                SeriesEntity(a, it.seriesId, it.name, e.title, TextNormalizer.normalize(e.title), it.categoryId, it.cover,
                    it.plot, it.genre, it.rating, it.lastModified, it.tmdbId, EditionParser.groupKey("series", it.tmdbId, it.seriesId),
                    e.language, e.quality, e.year, generation = gen)
            })
            bump(batch.size)
        }
        guard(n, previous)
        dao.finishSeries(a, gen)
        return n
    }
}
