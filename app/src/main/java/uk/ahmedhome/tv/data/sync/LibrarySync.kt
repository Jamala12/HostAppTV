package uk.ahmedhome.tv.data.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uk.ahmedhome.tv.core.AppLog
import uk.ahmedhome.tv.core.EditionParser
import uk.ahmedhome.tv.core.TextNormalizer
import uk.ahmedhome.tv.data.db.CatalogDao
import uk.ahmedhome.tv.data.db.CategoryEntity
import uk.ahmedhome.tv.data.db.ChannelEntity
import uk.ahmedhome.tv.data.db.MovieEntity
import uk.ahmedhome.tv.data.db.SeriesEntity
import uk.ahmedhome.tv.data.db.SyncStateEntity
import uk.ahmedhome.tv.data.xtream.CatalogKind
import uk.ahmedhome.tv.data.xtream.XtreamClient
import uk.ahmedhome.tv.data.xtream.XtreamException

data class SyncProgress(
    val running: Boolean = false,
    val current: CatalogKind? = null,
    val itemsInCurrent: Int = 0,
    val error: String? = null,
)

/**
 * Sequential foreground refresh: Live TV, then Movies, then Series. Rows are upserted in bounded
 * transactions tagged with a new generation; older rows are pruned only after a catalogue
 * completes, so an interrupted refresh never erases the working library.
 */
class LibrarySync(private val dao: CatalogDao, private val scope: CoroutineScope) {
    private val mutex = Mutex()
    private var job: Job? = null
    private val _progress = MutableStateFlow(SyncProgress())
    val progress: StateFlow<SyncProgress> = _progress

    /** Deduplicates: if a refresh is running for this account, the call joins it. */
    fun refresh(accountId: String, client: XtreamClient, kinds: List<CatalogKind> = CatalogKind.entries): Job {
        job?.takeIf { it.isActive }?.let { return it }
        return scope.launch(Dispatchers.IO) {
            mutex.withLock { runSync(accountId, client, kinds) }
        }.also { job = it }
    }

    fun cancel() { job?.cancel(); job = null; _progress.value = SyncProgress() }

    private suspend fun runSync(accountId: String, client: XtreamClient, kinds: List<CatalogKind>) {
        _progress.value = SyncProgress(running = true)
        var firstError: String? = null
        for (kind in kinds) {
            val gen = System.currentTimeMillis()
            _progress.value = SyncProgress(running = true, current = kind)
            dao.upsertSyncState(SyncStateEntity(accountId, kind.key, previousComplete(accountId, kind), gen, 0, "running", null))
            try {
                val cats = client.categories(kind)
                dao.upsertCategories(cats.map { CategoryEntity(accountId, kind.key, it.id, it.name, it.order, gen) })
                val previousCount = dao.syncStatesNow(accountId).firstOrNull { it.kind == kind.key && it.lastCompleteAt != null }?.count ?: 0
                guardPrevious = previousCount
                val count = when (kind) {
                    CatalogKind.LIVE -> syncLive(accountId, client, gen)
                    CatalogKind.MOVIE -> syncMovies(accountId, client, gen)
                    CatalogKind.SERIES -> syncSeries(accountId, client, gen)
                }
                dao.pruneCategories(accountId, kind.key, gen)
                dao.upsertSyncState(SyncStateEntity(accountId, kind.key, System.currentTimeMillis(), gen, count, "complete", null))
                AppLog.i("sync ${kind.key}: $count items")
            } catch (e: CancellationException) {
                dao.upsertSyncState(SyncStateEntity(accountId, kind.key, previousComplete(accountId, kind), gen, 0, "interrupted", null))
                _progress.value = SyncProgress()
                throw e
            } catch (e: XtreamException) {
                AppLog.w("sync ${kind.key} failed", e)
                firstError = firstError ?: e.message
                dao.upsertSyncState(SyncStateEntity(accountId, kind.key, previousComplete(accountId, kind), gen, 0, "failed", e.message))
                if (e is XtreamException.Unauthorized || e is XtreamException.CleartextRefused) break
            }
        }
        _progress.value = SyncProgress(running = false, error = firstError)
    }

    private suspend fun previousComplete(accountId: String, kind: CatalogKind): Long? =
        dao.syncStatesNow(accountId).firstOrNull { it.kind == kind.key }?.lastCompleteAt

    private fun bump(n: Int) { _progress.value = _progress.value.copy(itemsInCurrent = _progress.value.itemsInCurrent + n) }

    private var guardPrevious = 0
    private fun guard(n: Int) { if (n == 0 && guardPrevious > 0) throw XtreamException.SuspiciousEmpty() }

    private suspend fun syncLive(a: String, client: XtreamClient, gen: Long): Int {
        var position = 0
        val n = client.liveStreams { batch ->
            dao.upsertChannels(batch.map {
                ChannelEntity(a, it.streamId, it.num, it.num ?: (100_000 + position++), it.name,
                    TextNormalizer.normalize(it.name), it.categoryId, it.icon, it.epgChannelId, it.tvArchive, gen)
            })
            bump(batch.size)
        }
        guard(n)
        dao.pruneChannels(a, gen)
        return n
    }

    private suspend fun syncMovies(a: String, client: XtreamClient, gen: Long): Int {
        val n = client.movies { batch ->
            dao.insertMovieBatch(a, batch.map {
                val e = EditionParser.parse(it.name, it.year)
                MovieEntity(a, it.streamId, it.name, e.title, TextNormalizer.normalize(e.title), it.categoryId, it.icon,
                    it.rating, it.added, it.extension, it.tmdbId, EditionParser.groupKey("movie", it.tmdbId, it.streamId),
                    e.language, e.quality, e.year, generation = gen)
            })
            bump(batch.size)
        }
        guard(n)
        dao.finishMovies(a, gen)
        return n
    }

    private suspend fun syncSeries(a: String, client: XtreamClient, gen: Long): Int {
        val n = client.series { batch ->
            dao.insertSeriesBatch(a, batch.map {
                val e = EditionParser.parse(it.name, it.year)
                SeriesEntity(a, it.seriesId, it.name, e.title, TextNormalizer.normalize(e.title), it.categoryId, it.cover,
                    it.plot, it.genre, it.rating, it.lastModified, it.tmdbId, EditionParser.groupKey("series", it.tmdbId, it.seriesId),
                    e.language, e.quality, e.year, generation = gen)
            })
            bump(batch.size)
        }
        guard(n)
        dao.finishSeries(a, gen)
        return n
    }
}
