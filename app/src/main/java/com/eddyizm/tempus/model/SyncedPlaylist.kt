package com.eddyizm.tempus.model

import androidx.annotation.Keep
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Keep
@Entity(tableName = "synced_playlist")
data class SyncedPlaylist(
    @PrimaryKey
    @ColumnInfo(name = "playlist_id")
    val playlistId: String,

    @ColumnInfo(name = "name")
    var name: String? = null,

    @ColumnInfo(name = "song_ids_hash")
    var songIdsHash: String? = null,

    @ColumnInfo(name = "last_synced_at")
    var lastSyncedAt: Long = 0,
)
