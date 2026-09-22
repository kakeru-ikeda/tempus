package com.eddyizm.tempus.util;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.OptIn;
import androidx.lifecycle.LiveData;
import androidx.media3.common.util.UnstableApi;

import com.eddyizm.tempus.App;
import com.eddyizm.tempus.database.AppDatabase;
import com.eddyizm.tempus.database.dao.SyncedPlaylistDao;
import com.eddyizm.tempus.model.Download;
import com.eddyizm.tempus.model.SyncedPlaylist;
import com.eddyizm.tempus.service.DownloadProgressState;
import com.eddyizm.tempus.subsonic.base.ApiResponse;
import com.eddyizm.tempus.subsonic.models.Child;
import com.eddyizm.tempus.subsonic.models.PlaylistWithSongs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import retrofit2.Response;

/**
 * The writer queues m3u8 creation after song tasks on its single-thread executor,
 * so newly added songs appear after successful saves. Song files are never deleted.
 */
@OptIn(markerClass = UnstableApi.class)
public final class PlaylistSyncManager {
    private static final String TAG = "PlaylistSyncManager";
    private static final long CACHE_WAIT_MS = 3L * 60L * 1000L;
    private static PlaylistSyncManager instance;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile long lastRunElapsedMs = 0;
    private final SyncedPlaylistDao dao = AppDatabase.getInstance().syncedPlaylistDao();

    private PlaylistSyncManager() {
    }

    public static synchronized PlaylistSyncManager getInstance() {
        if (instance == null) instance = new PlaylistSyncManager();
        return instance;
    }

    public void register(String playlistId, String name, List<Child> songs) {
        if (playlistId == null) return;
        List<String> songIds = ids(songs);
        executor.execute(() -> dao.upsert(new SyncedPlaylist(playlistId, name,
                PlaylistSyncPolicy.hashSongIds(songIds), System.currentTimeMillis())));
    }

    public void unregister(String playlistId) {
        executor.execute(() -> dao.delete(playlistId));
    }

    public LiveData<Boolean> isRegistered(String playlistId) {
        return dao.isRegistered(playlistId);
    }

    public void syncAll(Context context, boolean force) {
        if (context == null || !canSync()) return;
        long now = SystemClock.elapsedRealtime();
        if (PlaylistSyncPolicy.isThrottled(lastRunElapsedMs, now, force)) return;
        Context appContext = context.getApplicationContext();
        if (!running.compareAndSet(false, true)) {
            Log.d(TAG, "Skipping syncAll: synchronization already running");
            return;
        }
        lastRunElapsedMs = now;
        executor.execute(() -> {
            try {
                backfillOnce();
                if (!awaitCache()) {
                    // Let the next trigger retry instead of waiting out the throttle.
                    lastRunElapsedMs = 0;
                    return;
                }
                for (SyncedPlaylist row : dao.getAllSync()) {
                    syncOne(appContext, row);
                }
            } finally {
                running.set(false);
            }
        });
    }

    public void syncPlaylist(Context context, String playlistId) {
        if (context == null || !canSync()) return;
        Context appContext = context.getApplicationContext();
        if (!running.compareAndSet(false, true)) {
            Log.d(TAG, "Skipping syncPlaylist: synchronization already running");
            return;
        }
        executor.execute(() -> {
            try {
                SyncedPlaylist row = dao.getOne(playlistId);
                if (row != null && awaitCache()) syncOne(appContext, row);
            } finally {
                running.set(false);
            }
        });
    }

    private void backfillOnce() {
        try {
            if (Preferences.isPlaylistSyncBackfilled()) return;
            LinkedHashMap<String, String> playlists = new LinkedHashMap<>();
            for (Download download : AppDatabase.getInstance().downloadDao()
                    .getDirectoryPlaylistDownloadsSync()) {
                playlists.putIfAbsent(download.getPlaylistId(), download.getPlaylistName());
            }
            List<SyncedPlaylist> rows = new ArrayList<>();
            for (Map.Entry<String, String> playlist : playlists.entrySet()) {
                rows.add(new SyncedPlaylist(playlist.getKey(), playlist.getValue(), null, 0L));
            }
            dao.insertIfAbsent(rows);
            Preferences.setPlaylistSyncBackfilled(true);
            Log.i(TAG, "Backfilled directory playlists: " + rows.size());
        } catch (Exception e) {
            Log.w(TAG, "Failed to backfill directory playlists", e);
        }
    }

    /** Missing-file counts are only meaningful once the folder listing is ready. */
    private static boolean awaitCache() {
        if (ExternalAudioReader.awaitCache(CACHE_WAIT_MS)) return true;
        Log.i(TAG, "Skipping sync: download folder listing not ready");
        return false;
    }

    private static boolean canSync() {
        // A batch still in flight would be enqueued again: its songs are not on disk yet.
        if (DownloadProgressState.getInstance().isBatchActive()) return false;
        return Preferences.getDownloadDirectoryUri() != null
                && (Preferences.getPassword() != null
                || (Preferences.getToken() != null && Preferences.getSalt() != null));
    }

    private void syncOne(Context appContext, SyncedPlaylist row) {
        try {
            String id = row.getPlaylistId();
            Response<ApiResponse> response = App.getSubsonicClientInstance(false)
                    .getPlaylistClient().getPlaylist(id).execute();
            ApiResponse body = response.body();
            if (!response.isSuccessful() || body == null
                    || body.getSubsonicResponse() == null
                    || body.getSubsonicResponse().getPlaylist() == null) {
                Log.i(TAG, "Skipping unavailable playlist: " + id);
                return;
            }
            PlaylistWithSongs playlist = body.getSubsonicResponse().getPlaylist();
            List<Child> songs = new ArrayList<>();
            if (playlist.getEntries() != null) {
                for (Child song : playlist.getEntries()) {
                    if (song != null) songs.add(song);
                }
            }
            if (songs.isEmpty()) return;

            String currentHash = PlaylistSyncPolicy.hashSongIds(ids(songs));
            int missing = 0;
            for (Child song : songs) {
                if (ExternalAudioReader.getUri(song) == null) missing++;
            }
            String currentName = playlist.getName() != null ? playlist.getName() : row.getName();
            long now = System.currentTimeMillis();
            if (!PlaylistSyncPolicy.needsSync(row.getSongIdsHash(), currentHash, missing,
                    row.getName(), currentName)) {
                dao.updateSyncState(id, row.getName(), row.getSongIdsHash(), now);
                return;
            }
            ExternalAudioWriter.syncPlaylistToUserDirectory(appContext, songs, id, currentName);
            dao.updateSyncState(id, currentName, currentHash, now);
            Log.i(TAG, "Synchronizing playlist " + id + ": songs=" + songs.size()
                    + ", missing=" + missing
                    + ", renamed=" + !Objects.equals(row.getName(), currentName));
        } catch (Exception e) {
            Log.w(TAG, "Failed to synchronize playlist: " + row.getPlaylistId(), e);
        }
    }

    private static List<String> ids(List<Child> songs) {
        List<String> result = new ArrayList<>();
        if (songs != null) {
            for (Child song : songs) {
                if (song != null) result.add(song.getId());
            }
        }
        return result;
    }
}
