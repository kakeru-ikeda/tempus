package com.eddyizm.tempus.database.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.eddyizm.tempus.model.SyncedPlaylist;

import java.util.List;

@Dao
public interface SyncedPlaylistDao {
    @Query("SELECT * FROM synced_playlist")
    List<SyncedPlaylist> getAllSync();

    @Query("SELECT * FROM synced_playlist WHERE playlist_id = :playlistId")
    SyncedPlaylist getOne(String playlistId);

    @Query("SELECT EXISTS(SELECT 1 FROM synced_playlist WHERE playlist_id = :playlistId)")
    LiveData<Boolean> isRegistered(String playlistId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(SyncedPlaylist syncedPlaylist);

    @Query("UPDATE synced_playlist SET name = :name, song_ids_hash = :songIdsHash, last_synced_at = :lastSyncedAt WHERE playlist_id = :playlistId")
    void updateSyncState(String playlistId, String name, String songIdsHash, long lastSyncedAt);

    @Query("DELETE FROM synced_playlist WHERE playlist_id = :playlistId")
    void delete(String playlistId);
}
