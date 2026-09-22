package com.eddyizm.tempus.util;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.documentfile.provider.DocumentFile;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.eddyizm.tempus.App;
import com.eddyizm.tempus.subsonic.models.Child;
import com.eddyizm.tempus.subsonic.models.PodcastEpisode;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ExternalAudioReader {

    private static final Map<String, DocumentFile> cache = new ConcurrentHashMap<>();
    private static final Object LOCK = new Object();
    private static final ExecutorService REFRESH_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final MutableLiveData<Long> refreshEvents = new MutableLiveData<>();
    // Files stored while a rebuild is running; merged into its result so they are not lost.
    private static final Map<String, DocumentFile> storedDuringRefresh = new HashMap<>();

    // Per-file events are coalesced: every event rebinds whole song lists on the main thread.
    private static final long STORED_EVENT_THROTTLE_MS = 1000;
    private static final Object EVENT_LOCK = new Object();
    private static Handler mainHandler;
    private static long lastStoredEventMs = 0;
    private static boolean storedEventScheduled = false;

    private static volatile String cachedDirUri;
    private static volatile boolean refreshInProgress = false;
    private static volatile boolean refreshQueued = false;

    private static String sanitizeFileName(String name) {
        String sanitized = name.replaceAll("[\\/:*?\\\"<>|]", "_");
        sanitized = sanitized.replaceAll("\\s+", " ").trim();
        return sanitized;
    }

    private static String normalizeForComparison(String name) {
        String s = sanitizeFileName(name);
        s = Normalizer.normalize(s, Normalizer.Form.NFKD);
        s = s.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return s.toLowerCase(Locale.ROOT);
    }

    private static void ensureCache() {
        String uriString = Preferences.getDownloadDirectoryUri();
        if (uriString == null) {
            synchronized (LOCK) {
                cache.clear();
                cachedDirUri = null;
            }
            ExternalDownloadMetadataStore.clear();
            return;
        }

        if (uriString.equals(cachedDirUri)) {
            return;
        }

        boolean runSynchronously = false;
        synchronized (LOCK) {
            if (refreshInProgress) {
                return;
            }

            if (Looper.myLooper() == Looper.getMainLooper()) {
                scheduleRefreshLocked();
                return;
            }

            refreshInProgress = true;
            runSynchronously = true;
        }

        if (runSynchronously) {
            try {
                rebuildCache();
            } finally {
                onRefreshFinished();
            }
        }
    }

    /**
     * Blocks a background thread until the folder cache matches the selected folder, so lookups
     * do not report every file as missing while a rebuild started elsewhere is still running.
     * Returns false when no folder is selected or the wait times out.
     */
    public static boolean awaitCache(long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (true) {
            ensureCache();
            String uriString = Preferences.getDownloadDirectoryUri();
            if (uriString == null) return false;
            if (uriString.equals(cachedDirUri) && !refreshInProgress) return true;
            if (SystemClock.elapsedRealtime() >= deadline) return false;
            SystemClock.sleep(500);
        }
    }

    public static void refreshCache() {
        refreshCacheAsync();
    }

    public static void refreshCacheAsync() {
        synchronized (LOCK) {
            cachedDirUri = null;
            cache.clear();
        }
        requestRefresh();
    }

    /**
     * Records a file just written (or found already present) by {@link ExternalAudioWriter}
     * without rescanning the folder. When no cache for the current folder exists yet the pending
     * rebuild picks the file up from {@link ExternalDownloadMetadataStore}.
     */
    public static void onFileStored(String key, Uri uri) {
        if (key == null || uri == null) return;
        String uriString = Preferences.getDownloadDirectoryUri();
        DocumentFile file = DocumentFile.fromSingleUri(App.getContext(), uri);
        if (file == null) return;
        synchronized (LOCK) {
            if (refreshInProgress) {
                storedDuringRefresh.put(key, file);
            }
            if (uriString != null && uriString.equals(cachedDirUri)) {
                cache.put(key, file);
            }
        }
        postStoredEvent();
    }

    private static void postStoredEvent() {
        synchronized (EVENT_LOCK) {
            long now = SystemClock.elapsedRealtime();
            long wait = lastStoredEventMs + STORED_EVENT_THROTTLE_MS - now;
            if (wait <= 0) {
                lastStoredEventMs = now;
                refreshEvents.postValue(now);
                return;
            }
            if (storedEventScheduled) return;
            storedEventScheduled = true;
            if (mainHandler == null) mainHandler = new Handler(Looper.getMainLooper());
            mainHandler.postDelayed(() -> {
                synchronized (EVENT_LOCK) {
                    storedEventScheduled = false;
                    lastStoredEventMs = SystemClock.elapsedRealtime();
                }
                refreshEvents.setValue(SystemClock.elapsedRealtime());
            }, wait);
        }
    }

    /** Playlists written next to the songs are not downloads. */
    private static boolean isPlaylistFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".m3u8") || lower.endsWith(".m3u");
    }

    public static LiveData<Long> getRefreshEvents() {
        return refreshEvents;
    }

    private static String buildKey(String artist, String title, String album) {
        String name = artist != null && !artist.isEmpty() ? artist + " - " + title : title;
        if (album != null && !album.isEmpty()) name += " (" + album + ")";
        return normalizeForComparison(name);
    }

    /**
     * Key for a file saved under the server path (see {@link ExternalDownloadPath}): normalized
     * segments joined with '/', extension dropped. Null when the path is blank. Flat keys never
     * contain '/', so the two kinds cannot collide except for a single-segment path.
     */
    public static String buildPathKey(String path) {
        List<String> segments = ExternalDownloadPath.lookupSegments(path);
        if (segments.isEmpty()) return null;
        StringBuilder key = new StringBuilder();
        for (String segment : segments) {
            if (key.length() > 0) key.append('/');
            key.append(normalizeForComparison(segment));
        }
        return key.toString();
    }

    private static Uri findUri(String artist, String title, String album) {
        ensureCache();
        if (cachedDirUri == null) return null;

        DocumentFile file = cache.get(buildKey(artist, title, album));
        return file != null && file.exists() ? file.getUri() : null;
    }

    public static Uri getUri(Child media) {
        String pathKey = buildPathKey(media.getPath());
        if (pathKey != null) {
            ensureCache();
            if (cachedDirUri == null) return null;
            DocumentFile file = cache.get(pathKey);
            if (file != null && file.exists()) return file.getUri();
        }
        return findUri(media.getArtist(), media.getTitle(), media.getAlbum());
    }

    public static Uri getUri(PodcastEpisode episode) {
        return findUri(episode.getArtist(), episode.getTitle(), episode.getAlbum());
    }

    public static synchronized void removeMetadata(Child media) {
        if (media == null) {
            return;
        }

        String key = buildKey(media.getArtist(), media.getTitle(), media.getAlbum());
        cache.remove(key);
        ExternalDownloadMetadataStore.remove(key);

        String pathKey = buildPathKey(media.getPath());
        if (pathKey != null) {
            cache.remove(pathKey);
            ExternalDownloadMetadataStore.remove(pathKey);
        }
    }

    public static boolean delete(Child media) {
        ensureCache();
        if (cachedDirUri == null) return false;

        String key = buildPathKey(media.getPath());
        DocumentFile file = key != null ? cache.get(key) : null;
        if (file == null || !file.exists()) {
            key = buildKey(media.getArtist(), media.getTitle(), media.getAlbum());
            file = cache.get(key);
        }
        boolean deleted = false;
        if (file != null && file.exists()) {
            deleted = file.delete();
        }
        if (deleted) {
            cache.remove(key);
            ExternalDownloadMetadataStore.remove(key);
        }
        return deleted;
    }

    private static void requestRefresh() {
        synchronized (LOCK) {
            scheduleRefreshLocked();
        }
    }

    private static void scheduleRefreshLocked() {
        if (refreshInProgress) {
            refreshQueued = true;
            return;
        }

        refreshInProgress = true;
        REFRESH_EXECUTOR.execute(() -> {
            try {
                rebuildCache();
            } finally {
                onRefreshFinished();
            }
        });
    }

    private static void rebuildCache() {
        String uriString = Preferences.getDownloadDirectoryUri();
        if (uriString == null) {
            synchronized (LOCK) {
                cache.clear();
                cachedDirUri = null;
            }
            ExternalDownloadMetadataStore.clear();
            return;
        }

        DocumentFile directory = DocumentFile.fromTreeUri(App.getContext(), Uri.parse(uriString));
        Map<String, Long> expectedSizes = ExternalDownloadMetadataStore.snapshot();
        Set<String> verifiedKeys = new HashSet<>();
        Map<String, DocumentFile> newEntries = new HashMap<>();

        // Folders that lead to a file saved under its server path; only these are descended into
        // so a large library in the same tree is not scanned.
        Set<String> pathPrefixes = new HashSet<>();
        for (String key : expectedSizes.keySet()) {
            int slash = key.indexOf('/');
            while (slash > 0) {
                pathPrefixes.add(key.substring(0, slash));
                slash = key.indexOf('/', slash + 1);
            }
        }

        if (directory != null && directory.canRead()) {
            for (DocumentFile file : directory.listFiles()) {
                if (file == null) continue;
                String existing = file.getName();
                if (existing == null) continue;

                if (file.isDirectory()) {
                    String prefix = normalizeForComparison(existing);
                    if (pathPrefixes.contains(prefix)) {
                        scanPathDirectory(file, prefix, pathPrefixes, expectedSizes, verifiedKeys, newEntries);
                    }
                    continue;
                }
                if (isPlaylistFile(existing)) continue;

                String base = existing.replaceFirst("\\.[^\\.]+$", "");
                String key = normalizeForComparison(base);
                Long expected = expectedSizes.get(key);
                long actualLength = file.length();

                if (expected != null && expected > 0 && actualLength == expected) {
                    newEntries.put(key, file);
                    verifiedKeys.add(key);
                } else {
                    ExternalDownloadMetadataStore.remove(key);
                }
            }
        }

        if (!expectedSizes.isEmpty()) {
            if (verifiedKeys.isEmpty()) {
                ExternalDownloadMetadataStore.clear();
            } else {
                for (String key : expectedSizes.keySet()) {
                    if (!verifiedKeys.contains(key)) {
                        ExternalDownloadMetadataStore.remove(key);
                    }
                }
            }
        }

        synchronized (LOCK) {
            cache.clear();
            cache.putAll(newEntries);
            cache.putAll(storedDuringRefresh);
            storedDuringRefresh.clear();
            cachedDirUri = uriString;
        }
    }

    private static void scanPathDirectory(DocumentFile directory,
                                          String prefix,
                                          Set<String> pathPrefixes,
                                          Map<String, Long> expectedSizes,
                                          Set<String> verifiedKeys,
                                          Map<String, DocumentFile> newEntries) {
        for (DocumentFile file : directory.listFiles()) {
            if (file == null) continue;
            String existing = file.getName();
            if (existing == null) continue;

            if (file.isDirectory()) {
                String childPrefix = prefix + "/" + normalizeForComparison(existing);
                if (pathPrefixes.contains(childPrefix)) {
                    scanPathDirectory(file, childPrefix, pathPrefixes, expectedSizes, verifiedKeys, newEntries);
                }
                continue;
            }
            if (isPlaylistFile(existing)) continue;

            String key = prefix + "/" + normalizeForComparison(ExternalDownloadPath.stripExtension(existing));
            Long expected = expectedSizes.get(key);
            if (expected != null && expected > 0 && file.length() == expected) {
                newEntries.put(key, file);
                verifiedKeys.add(key);
            }
        }
    }

    private static void onRefreshFinished() {
        boolean runAgain;
        synchronized (LOCK) {
            refreshInProgress = false;
            runAgain = refreshQueued;
            refreshQueued = false;
        }

        refreshEvents.postValue(SystemClock.elapsedRealtime());

        if (runAgain) {
            requestRefresh();
        }
    }
}