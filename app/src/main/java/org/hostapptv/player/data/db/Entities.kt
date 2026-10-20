package org.hostapptv.player.data.db

import androidx.room.Entity
import androidx.room.Index

@Entity(tableName = "categories", primaryKeys = ["accountId", "kind", "categoryId"])
data class CategoryEntity(
    val accountId: String, val kind: String, val categoryId: String, val name: String, val sortOrder: Int, val generation: Long,
)

@Entity(
    tableName = "channels", primaryKeys = ["accountId", "streamId"],
    indices = [Index("accountId", "categoryId", "sortNum"), Index("accountId", "sortNum")],
)
data class ChannelEntity(
    val accountId: String, val streamId: Long, val num: Int?, val sortNum: Int, val name: String, val searchKey: String,
    val categoryId: String?, val icon: String?, val epgChannelId: String?, val tvArchive: Boolean, val generation: Long,
)

@Entity(
    tableName = "movies", primaryKeys = ["accountId", "streamId"],
    indices = [
        Index("accountId", "groupKey"), Index("accountId", "isPrimary", "added"),
        Index("accountId", "categoryId", "isPrimary"), Index("accountId", "isPrimary", "title"),
    ],
)
data class MovieEntity(
    val accountId: String, val streamId: Long, val name: String, val title: String, val searchKey: String,
    val categoryId: String?, val poster: String?, val rating: Double?, val added: Long?, val ext: String?,
    val tmdbId: String?, val groupKey: String, val language: String?, val quality: String?, val year: Int?,
    val isPrimary: Boolean = true, val editionCount: Int = 1, val generation: Long,
)

@Entity(
    tableName = "series", primaryKeys = ["accountId", "seriesId"],
    indices = [
        Index("accountId", "groupKey"), Index("accountId", "isPrimary", "lastModified"),
        Index("accountId", "categoryId", "isPrimary"), Index("accountId", "isPrimary", "title"),
    ],
)
data class SeriesEntity(
    val accountId: String, val seriesId: Long, val name: String, val title: String, val searchKey: String,
    val categoryId: String?, val cover: String?, val plot: String?, val genre: String?, val rating: Double?,
    val lastModified: Long?, val tmdbId: String?, val groupKey: String, val language: String?, val quality: String?,
    val year: Int?, val isPrimary: Boolean = true, val editionCount: Int = 1, val generation: Long,
)

/** kind: live | movie | series. itemKey: channel stream id, or the movie/series group key. */
@Entity(tableName = "favourites", primaryKeys = ["accountId", "kind", "itemKey"], indices = [Index("accountId", "kind", "addedAt")])
data class FavouriteEntity(
    val accountId: String, val kind: String, val itemKey: String, val title: String, val image: String?, val addedAt: Long,
)

/** Saved IDs and positions only — never a playable URL. kind: movie | episode. */
@Entity(tableName = "progress", primaryKeys = ["accountId", "kind", "contentId"], indices = [Index("accountId", "updatedAt")])
data class ProgressEntity(
    val accountId: String, val kind: String, val contentId: Long, val groupKey: String?, val seriesId: Long?,
    val season: Int?, val episode: Int?, val title: String, val subtitle: String?, val image: String?, val ext: String?,
    val positionMs: Long, val durationMs: Long, val updatedAt: Long, val finished: Boolean,
)

@Entity(tableName = "sync_state", primaryKeys = ["accountId", "kind"])
data class SyncStateEntity(
    val accountId: String, val kind: String, val lastCompleteAt: Long?, val generation: Long, val count: Int,
    val status: String, val error: String?,
)
