package com.eddyizm.tempus.util;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.util.Log;
import android.webkit.MimeTypeMap;

import androidx.core.app.NotificationCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.media3.common.MediaItem;

import com.eddyizm.tempus.model.Download;
import com.eddyizm.tempus.repository.DownloadRepository;
import com.eddyizm.tempus.service.DownloadProgressState;
import com.eddyizm.tempus.service.ExternalDownloadService;
import com.eddyizm.tempus.subsonic.models.Child;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import androidx.media3.common.util.UnstableApi;

@UnstableApi
public class ExternalAudioWriter {

    private static final String TAG = "ExternalAudioWriter";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    /** No byte at all for this long aborts the attempt; it is then resumed with a Range request. */
    private static final int READ_TIMEOUT_MS = 20_000;
    private static final long WATCHDOG_PERIOD_MS = 1_000;

    /** Aborts slow HTTP attempts by disconnecting them, which unblocks the reading thread. */
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "ExternalAudioWriter-watchdog");
        thread.setDaemon(true);
        return thread;
    });

    // One-off error notification IDs — distinct from Path A (1, 2) and Path B (1012, 1013)
    private static final int NO_FOLDER_NOTIFICATION_ID = 1010;
    private static final int FOLDER_ERROR_NOTIFICATION_ID = 1009;

    /** Tasks submitted and not yet finished; the foreground service runs while this is > 0. */
    private static final AtomicInteger PENDING_TASKS = new AtomicInteger();

    /** Directory listings for the running batch. Executor thread only. */
    private static SafTree treeCache;

    private enum Outcome { SUCCESS, SKIPPED, FAILED }

    private static final class SongResult {
        static final SongResult FAILED = new SongResult(Outcome.FAILED, null);

        final Outcome outcome;
        /** Path of the file inside the selected tree, '/'-separated; null on failure. */
        final String relativePath;

        SongResult(Outcome outcome, String relativePath) {
            this.outcome = outcome;
            this.relativePath = relativePath;
        }
    }

    private ExternalAudioWriter() {
    }

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

    public static void downloadToUserDirectory(Context context, Child child) {
        downloadToUserDirectory(context, child, null, null);
    }

    public static void downloadToUserDirectory(Context context, Child child, String playlistId, String playlistName) {
        if (context == null || child == null) return;
        enqueueSong(context.getApplicationContext(), child, playlistId, playlistName, null);
    }

    /**
     * Downloads every song of a playlist, then writes {@code <playlist name>.m3u8} to the root of
     * the selected folder listing the songs whose file exists (downloaded or already present).
     */
    public static void downloadPlaylistToUserDirectory(Context context, List<Child> songs, String playlistId, String playlistName) {
        if (context == null || songs == null || songs.isEmpty()) return;
        Context appContext = context.getApplicationContext();
        List<Child> snapshot = new ArrayList<>(songs);
        // Filled in by the song tasks and read by the playlist task, all on the executor thread.
        String[] relativePaths = new String[snapshot.size()];
        for (int i = 0; i < snapshot.size(); i++) {
            Child child = snapshot.get(i);
            if (child == null) continue;
            final int index = i;
            enqueueSong(appContext, child, playlistId, playlistName, path -> relativePaths[index] = path);
        }
        submit(appContext, () -> writePlaylistFile(appContext, snapshot, relativePaths, playlistName));
    }

    private static void enqueueSong(Context appContext, Child child, String playlistId, String playlistName, Consumer<String> onRelativePath) {
        // Register with the progress tracker BEFORE submitting to the executor so the
        // total count is accurate even when many tracks are enqueued in rapid succession.
        DownloadProgressState.getInstance().onEnqueue(appContext);

        submit(appContext, () -> {
            SongResult result;
            try {
                result = performDownload(appContext, child, playlistId, playlistName);
            } catch (Throwable t) {
                Log.w(TAG, "Download failed for " + child.getId(), t);
                result = SongResult.FAILED;
            }
            if (result.outcome == Outcome.FAILED) {
                // The folder may have changed under us; list it again for the next song.
                treeCache = null;
            }
            if (onRelativePath != null) {
                onRelativePath.accept(result.relativePath);
            }
            reportOutcome(appContext, result.outcome);
        });
    }

    private static void reportOutcome(Context context, Outcome outcome) {
        try {
            DownloadProgressState state = DownloadProgressState.getInstance();
            switch (outcome) {
                case SUCCESS:
                    state.onSuccess(context);
                    break;
                case SKIPPED:
                    // Already exists — count as a quiet skip so progress resolves correctly
                    state.onSkipped(context);
                    break;
                default:
                    state.onFailed(context);
                    break;
            }
        } catch (Throwable t) {
            Log.w(TAG, "Could not report progress", t);
        }
    }

    private static void submit(Context appContext, Runnable task) {
        if (PENDING_TASKS.getAndIncrement() == 0) {
            ExternalDownloadService.start(appContext, () -> PENDING_TASKS.get() > 0);
        }
        EXECUTOR.execute(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                Log.e(TAG, "Queued task failed", t);
            } finally {
                if (PENDING_TASKS.decrementAndGet() == 0) {
                    treeCache = null;
                    ExternalDownloadService.stopIfIdle();
                }
            }
        });
    }

    private static SafTree tree(Context context, Uri treeUri) {
        String key = treeUri.toString();
        if (treeCache == null || !treeCache.treeUriString.equals(key)) {
            treeCache = new SafTree(context.getContentResolver(), treeUri);
        }
        return treeCache;
    }

    private static SongResult performDownload(Context context, Child child, String playlistId, String playlistName) throws IOException {
        String fallbackName = child.getTitle() != null ? child.getTitle() : child.getId();
        DownloadProgressState.getInstance().setCurrentTrackTitle(fallbackName);

        String uriString = Preferences.getDownloadDirectoryUri();
        if (uriString == null) {
            notifyUnavailable(context);
            return SongResult.FAILED;
        }

        Uri treeUri = Uri.parse(uriString);
        DocumentFile directory = DocumentFile.fromTreeUri(context, treeUri);
        if (directory == null || !directory.canWrite()) {
            notifyFolderError(context);
            return SongResult.FAILED;
        }
        SafTree tree = tree(context, treeUri);

        MediaItem mediaItem = MappingUtil.mapDownload(child);

        String artist = child.getArtist() != null ? child.getArtist() : "";
        String title = child.getTitle() != null ? child.getTitle() : fallbackName;
        String album = child.getAlbum() != null ? child.getAlbum() : "";
        String baseName = artist.isEmpty() ? title : artist + " - " + title;
        if (!album.isEmpty()) baseName += " (" + album + ")";
        if (baseName.isEmpty()) {
            baseName = fallbackName != null ? fallbackName : "download";
        }
        String pathKey = Preferences.isDownloadDirectoryPreservePath()
                ? ExternalAudioReader.buildPathKey(child.getPath())
                : null;
        String metadataKey = pathKey != null ? pathKey : normalizeForComparison(baseName);

        Uri mediaUri = mediaItem != null && mediaItem.requestMetadata != null
                ? mediaItem.requestMetadata.mediaUri
                : null;
        if (mediaUri == null) {
            ExternalDownloadMetadataStore.remove(metadataKey);
            return SongResult.FAILED;
        }

        String scheme = mediaUri.getScheme() != null ? mediaUri.getScheme().toLowerCase(Locale.ROOT) : "";

        HttpTransfer http = null;
        DocumentFile sourceDocument = null;
        File sourceFile = null;
        long remoteLength = -1;
        String mimeType = null;
        SafTree.Node targetFile = null;
        String targetDirId = null;

        try {
            if (scheme.equals("http") || scheme.equals("https")) {
                http = new HttpTransfer(new URL(mediaUri.toString()), child.getId());
                if (!http.openInitial()) {
                    ExternalDownloadMetadataStore.remove(metadataKey);
                    return SongResult.FAILED;
                }
                mimeType = http.mimeType;
                remoteLength = http.expectedTotal;
            } else if (scheme.equals("content")) {
                sourceDocument = DocumentFile.fromSingleUri(context, mediaUri);
                mimeType = context.getContentResolver().getType(mediaUri);
                if (sourceDocument != null) {
                    remoteLength = sourceDocument.length();
                }
            } else if (scheme.equals("file")) {
                String path = mediaUri.getPath();
                if (path != null) {
                    sourceFile = new File(path);
                    if (sourceFile.exists()) {
                        remoteLength = sourceFile.length();
                    }
                }
                String ext = MimeTypeMap.getFileExtensionFromUrl(mediaUri.toString());
                if (ext != null && !ext.isEmpty()) {
                    mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                }
            } else {
                ExternalDownloadMetadataStore.remove(metadataKey);
                return SongResult.FAILED;
            }

            if (mimeType == null || mimeType.isEmpty()) {
                mimeType = "application/octet-stream";
            }

            String extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
            if ((extension == null || extension.isEmpty()) && sourceDocument != null && sourceDocument.getName() != null) {
                String name = sourceDocument.getName();
                int dot = name.lastIndexOf('.');
                if (dot >= 0 && dot < name.length() - 1) {
                    extension = name.substring(dot + 1);
                }
            }
            if ((extension == null || extension.isEmpty()) && sourceFile != null) {
                String name = sourceFile.getName();
                int dot = name.lastIndexOf('.');
                if (dot >= 0 && dot < name.length() - 1) {
                    extension = name.substring(dot + 1);
                }
            }
            if (extension == null || extension.isEmpty()) {
                String suffix = child.getSuffix();
                if (suffix != null && !suffix.isEmpty()) {
                    extension = suffix;
                } else {
                    extension = "bin";
                }
            }

            targetDirId = tree.rootId;
            StringBuilder relativeDir = new StringBuilder();
            String fileName;
            if (pathKey != null) {
                List<String> segments = ExternalDownloadPath.buildTargetSegments(
                        child.getPath(), extension, Preferences.preferTranscodedDownload());
                for (int i = 0; i < segments.size() - 1; i++) {
                    SafTree.Node folder = tree.findOrCreateDirectory(targetDirId, segments.get(i));
                    if (folder == null) {
                        ExternalDownloadMetadataStore.remove(metadataKey);
                        return SongResult.FAILED;
                    }
                    targetDirId = folder.documentId;
                    relativeDir.append(folder.name).append('/');
                }
                fileName = segments.get(segments.size() - 1);
            } else {
                String sanitized = sanitizeFileName(baseName);
                if (sanitized.isEmpty()) sanitized = sanitizeFileName(fallbackName);
                if (sanitized.isEmpty()) sanitized = "download";
                fileName = sanitized + "." + extension;
            }

            SafTree.Node existingFile = tree.findFile(targetDirId, fileName);
            Long recordedSize = ExternalDownloadMetadataStore.getSize(metadataKey);
            if (existingFile != null) {
                // Re-check this one file: the listing may be stale if it was deleted meanwhile.
                Long localLength = tree.querySize(existingFile);
                if (localLength == null) {
                    tree.forget(targetDirId, existingFile);
                } else {
                    boolean matches = false;
                    if (remoteLength > 0 && localLength == remoteLength) {
                        matches = true;
                    } else if (remoteLength <= 0 && recordedSize != null && (long) localLength == recordedSize) {
                        matches = true;
                    }
                    if (matches) {
                        ExternalDownloadMetadataStore.recordSize(metadataKey, localLength);
                        recordDownload(child, existingFile.uri, playlistId, playlistName);
                        ExternalAudioReader.onFileStored(metadataKey, existingFile.uri);
                        return new SongResult(Outcome.SKIPPED, relativeDir + existingFile.name);
                    } else {
                        tree.delete(targetDirId, existingFile);
                        ExternalDownloadMetadataStore.remove(metadataKey);
                    }
                }
            }

            targetFile = tree.createFile(targetDirId, mimeType, fileName);
            if (targetFile == null) {
                ExternalDownloadMetadataStore.remove(metadataKey);
                return SongResult.FAILED;
            }

            Uri targetUri = targetFile.uri;
            long total = 0;
            if (http != null) {
                total = http.download(context, targetUri);
                remoteLength = http.expectedTotal;
            } else try (InputStream in = openInputStream(context, mediaUri, scheme, sourceFile);
                 OutputStream out = context.getContentResolver().openOutputStream(targetUri)) {
                if (out == null) {
                    throw new IOException("Cannot open output stream");
                }

                byte[] buffer = new byte[BUFFER_SIZE];
                int len;
                long lastProgressMs = 0;
                while ((len = in.read(buffer)) != -1) {
                    out.write(buffer, 0, len);
                    total += len;
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastProgressMs > 500) {
                        DownloadProgressState.getInstance().reportBytesProgress(context, total);
                        lastProgressMs = now;
                    }
                }
                out.flush();
            }

            if (total <= 0 || (remoteLength > 0 && total != remoteLength)) {
                Log.w(TAG, "Incomplete download for " + child.getId() + ": " + total + " of " + remoteLength);
                tree.delete(targetDirId, targetFile);
                targetFile = null;
                ExternalDownloadMetadataStore.remove(metadataKey);
                return SongResult.FAILED;
            }

            if (http != null) http.logSuccess(total);
            String relativePath = relativeDir + targetFile.name;
            targetFile = null; // kept from here on
            ExternalDownloadMetadataStore.recordSize(metadataKey, total);
            recordDownload(child, targetUri, playlistId, playlistName);
            ExternalAudioReader.onFileStored(metadataKey, targetUri);
            return new SongResult(Outcome.SUCCESS, relativePath);
        } catch (Exception e) {
            Log.w(TAG, "Download failed for " + child.getId(), e);
            if (targetFile != null) {
                try {
                    tree.delete(targetDirId, targetFile);
                } catch (Exception deleteError) {
                    Log.w(TAG, "Could not delete partial file", deleteError);
                }
            }
            try {
                ExternalDownloadMetadataStore.remove(metadataKey);
            } catch (Exception ignored) {
            }
            return SongResult.FAILED;
        } finally {
            if (http != null) {
                http.close();
            }
        }
    }

    private static void writePlaylistFile(Context context, List<Child> songs, String[] relativePaths, String playlistName) {
        try {
            String uriString = Preferences.getDownloadDirectoryUri();
            if (uriString == null) return;

            List<M3uPlaylist.Entry> entries = new ArrayList<>();
            int present = 0;
            for (int i = 0; i < songs.size(); i++) {
                Child song = songs.get(i);
                if (song == null) continue;
                if (relativePaths[i] != null) present++;
                entries.add(new M3uPlaylist.Entry(relativePaths[i], song.getDuration(), song.getArtist(), song.getTitle()));
            }
            if (present == 0) {
                Log.w(TAG, "No song of playlist " + playlistName + " was saved; skipping m3u8");
                return;
            }

            Uri treeUri = Uri.parse(uriString);
            String basePath = M3uPlaylist.resolveTreeBasePath(
                    treeUri.getAuthority(), DocumentsContract.getTreeDocumentId(treeUri));
            byte[] content = M3uPlaylist.build(entries, basePath).getBytes(StandardCharsets.UTF_8);

            SafTree tree = tree(context, treeUri);
            String fileName = M3uPlaylist.fileName(playlistName);
            ContentResolver resolver = context.getContentResolver();

            SafTree.Node existing = tree.findFile(tree.rootId, fileName);
            if (existing != null) {
                try {
                    writeBytes(resolver, existing.uri, "wt", content);
                    Log.i(TAG, "Updated " + existing.name + " (" + present + "/" + entries.size() + " songs)");
                    return;
                } catch (Exception e) {
                    Log.w(TAG, "Could not truncate " + existing.name + ", recreating", e);
                    tree.delete(tree.rootId, existing);
                }
            }

            String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension("m3u8");
            SafTree.Node created = tree.createFile(tree.rootId,
                    mimeType != null ? mimeType : "application/octet-stream", fileName);
            if (created == null) {
                Log.w(TAG, "Could not create " + fileName);
                return;
            }
            writeBytes(resolver, created.uri, "w", content);
            Log.i(TAG, "Wrote " + created.name + " (" + present + "/" + entries.size() + " songs)");
        } catch (Exception e) {
            treeCache = null;
            Log.w(TAG, "Could not write playlist file for " + playlistName, e);
        }
    }

    private static void writeBytes(ContentResolver resolver, Uri uri, String mode, byte[] content) throws IOException {
        try (OutputStream out = resolver.openOutputStream(uri, mode)) {
            if (out == null) throw new IOException("Cannot open output stream");
            out.write(content);
            out.flush();
        }
    }

    /**
     * Child listings of a SAF tree, each fetched with a single query and then kept in sync with
     * the files and folders this writer creates or deletes. {@link DocumentFile#listFiles()}
     * would instead issue one query per entry for the name and type, per song.
     */
    private static final class SafTree {

        static final class Node {
            final String documentId;
            final String name;
            final boolean directory;
            final Uri uri;

            Node(Uri treeUri, String documentId, String name, boolean directory) {
                this.documentId = documentId;
                this.name = name;
                this.directory = directory;
                this.uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
            }
        }

        private static final String[] CHILD_PROJECTION = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
        };

        final ContentResolver resolver;
        final Uri treeUri;
        final String treeUriString;
        final String rootId;
        /** Directory document id → normalized name → entries in listing order. */
        private final Map<String, Map<String, List<Node>>> listings = new HashMap<>();

        SafTree(ContentResolver resolver, Uri treeUri) {
            this.resolver = resolver;
            this.treeUri = treeUri;
            this.treeUriString = treeUri.toString();
            this.rootId = DocumentsContract.getTreeDocumentId(treeUri);
        }

        private Map<String, List<Node>> listing(String directoryId) throws IOException {
            Map<String, List<Node>> listing = listings.get(directoryId);
            if (listing != null) return listing;

            listing = new HashMap<>();
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, directoryId);
            try (Cursor cursor = resolver.query(childrenUri, CHILD_PROJECTION, null, null, null)) {
                if (cursor == null) throw new IOException("Cannot list " + directoryId);
                while (cursor.moveToNext()) {
                    String id = cursor.getString(0);
                    String name = cursor.getString(1);
                    if (id == null || name == null) continue;
                    boolean isDirectory = DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2));
                    add(listing, new Node(treeUri, id, name, isDirectory));
                }
            }
            listings.put(directoryId, listing);
            return listing;
        }

        private static void add(Map<String, List<Node>> listing, Node node) {
            List<Node> sameName = listing.get(normalizeForComparison(node.name));
            if (sameName == null) {
                sameName = new ArrayList<>(1);
                listing.put(normalizeForComparison(node.name), sameName);
            }
            sameName.add(node);
        }

        Node findFile(String directoryId, String fileName) throws IOException {
            List<Node> sameName = listing(directoryId).get(normalizeForComparison(fileName));
            if (sameName == null) return null;
            for (Node node : sameName) {
                if (!node.directory) return node;
            }
            return null;
        }

        /** Existing folder of that name, a new one, or null when a file already has the name. */
        Node findOrCreateDirectory(String parentId, String name) throws IOException {
            Map<String, List<Node>> listing = listing(parentId);
            List<Node> sameName = listing.get(normalizeForComparison(name));
            if (sameName != null && !sameName.isEmpty()) {
                Node first = sameName.get(0);
                return first.directory ? first : null;
            }
            Node created = create(parentId, DocumentsContract.Document.MIME_TYPE_DIR, name, true);
            if (created != null) {
                listings.put(created.documentId, new HashMap<>());
            }
            return created;
        }

        Node createFile(String directoryId, String mimeType, String name) throws IOException {
            return create(directoryId, mimeType, name, false);
        }

        private Node create(String parentId, String mimeType, String name, boolean directory) throws IOException {
            Map<String, List<Node>> listing = listing(parentId);
            Uri parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId);
            Uri uri = DocumentsContract.createDocument(resolver, parentUri, mimeType, name);
            if (uri == null) return null;
            // The provider may adjust the name (extension, uniqueness); record what it used.
            String actualName = queryDisplayName(uri);
            Node node = new Node(treeUri, DocumentsContract.getDocumentId(uri),
                    actualName != null ? actualName : name, directory);
            add(listing, node);
            return node;
        }

        void delete(String directoryId, Node node) throws IOException {
            DocumentsContract.deleteDocument(resolver, node.uri);
            forget(directoryId, node);
        }

        void forget(String directoryId, Node node) {
            Map<String, List<Node>> listing = listings.get(directoryId);
            if (listing == null) return;
            List<Node> sameName = listing.get(normalizeForComparison(node.name));
            if (sameName != null) sameName.remove(node);
            listings.remove(node.documentId);
        }

        /** Current size of the document, or null when it no longer exists. */
        Long querySize(Node node) {
            try (Cursor cursor = resolver.query(node.uri,
                    new String[]{DocumentsContract.Document.COLUMN_SIZE}, null, null, null)) {
                if (cursor == null || !cursor.moveToFirst()) return null;
                return cursor.isNull(0) ? 0L : cursor.getLong(0);
            } catch (Exception e) {
                return null;
            }
        }

        private String queryDisplayName(Uri uri) {
            try (Cursor cursor = resolver.query(uri,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                return cursor != null && cursor.moveToFirst() ? cursor.getString(0) : null;
            } catch (Exception e) {
                return null;
            }
        }
    }

    /**
     * Shown only when the user hasn't set a download folder at all — this is a one-off
     * actionable notification that remains separate from the progress flow because the
     * user needs to take action in Settings before anything else can proceed.
     */
    private static void notifyUnavailable(Context context) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        Intent settingsIntent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.getPackageName(), null));
        PendingIntent openSettings = PendingIntent.getActivity(context, 0, settingsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, DownloadUtil.DOWNLOAD_NOTIFICATION_CHANNEL_ID)
                .setContentTitle("No download folder set")
                .setContentText("Tap to set one in settings")
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true)
                .setContentIntent(openSettings)
                .setAutoCancel(true);

        manager.notify(NO_FOLDER_NOTIFICATION_ID, builder.build());
    }

    /**
     * Shown only when the download folder exists but is not writable — separate from the
     * progress flow since it indicates a setup problem the user must resolve.
     */
    private static void notifyFolderError(Context context) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, DownloadUtil.DOWNLOAD_NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Download folder error")
                .setContentText("Cannot write to the selected folder")
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true)
                .setAutoCancel(true);
        manager.notify(FOLDER_ERROR_NOTIFICATION_ID, builder.build());
    }

    private static void recordDownload(Child child, Uri fileUri, String playlistId, String playlistName) {
        if (child == null) return;

        Download download = new Download(child);
        MusicUtil.applyTranscodedDownloadMetadata(download);
        download.setDownloadState(1);
        download.setPlaylistId(playlistId);
        download.setPlaylistName(playlistName);

        if (fileUri != null) {
            download.setDownloadUri(fileUri.toString());
        }

        new DownloadRepository().insert(download);
    }

    private static InputStream openInputStream(Context context,
                                               Uri mediaUri,
                                               String scheme,
                                               File sourceFile) throws IOException {
        switch (scheme) {
            case "content":
                InputStream contentStream = context.getContentResolver().openInputStream(mediaUri);
                if (contentStream == null) {
                    throw new IOException("Cannot open content stream");
                }
                return contentStream;
            case "file":
                if (sourceFile == null || !sourceFile.exists()) {
                    throw new IOException("Missing source file");
                }
                return new FileInputStream(sourceFile);
            default:
                throw new IOException("Unsupported scheme " + scheme);
        }
    }

    /** Writing to the SAF target failed; retrying the network would not help. */
    private static final class TargetWriteException extends IOException {
        TargetWriteException(IOException cause) {
            super(cause);
        }
    }

    /** The server answered with a status that is not worth retrying. */
    private static final class FinalHttpException extends IOException {
        FinalHttpException(int code) {
            super("HTTP " + code);
        }
    }

    /**
     * One song over HTTP(S): a fresh connection per attempt ({@code Connection: close}), a watchdog
     * that disconnects stalled or slow attempts, and resume via {@code Range} into the same
     * output stream. Never logs the URL (it carries auth parameters).
     */
    private static final class HttpTransfer {
        private final URL url;
        private final String songId;
        private final long startMs = SystemClock.elapsedRealtime();
        private final byte[] buffer = new byte[BUFFER_SIZE];

        /** Connections opened so far for this song. */
        private int attempts;
        /** Opened with headers read but body not consumed yet; handed over to {@link #download}. */
        private HttpURLConnection connection;
        private OutputStream out;
        private long written;
        private long lastProgressMs;

        /** Expected file size, -1 if unknown. */
        long expectedTotal = -1;
        String mimeType;

        HttpTransfer(URL url, String songId) {
            this.url = url;
            this.songId = songId;
        }

        private HttpURLConnection open(long offset) throws IOException {
            attempts++;
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setUseCaches(false);
            c.setRequestProperty("Accept-Encoding", "identity");
            // Never reuse a pooled connection: a degraded one stays degraded.
            c.setRequestProperty("Connection", "close");
            if (offset > 0) {
                c.setRequestProperty("Range", "bytes=" + offset + "-");
            }
            return c;
        }

        /** Connects and reads the headers, retrying transient failures. False = give up. */
        boolean openInitial() throws IOException {
            while (true) {
                String failure;
                HttpURLConnection c = null;
                try {
                    c = open(0);
                    int code = c.getResponseCode();
                    if (code < HttpURLConnection.HTTP_BAD_REQUEST) {
                        connection = c;
                        mimeType = c.getContentType();
                        expectedTotal = c.getContentLengthLong();
                        return true;
                    }
                    if (!HttpResumePolicy.isRetryableStatus(code)) {
                        Log.w(TAG, "HTTP " + code + " for " + songId);
                        disconnectQuietly(c);
                        return false;
                    }
                    failure = "HTTP " + code;
                } catch (SocketTimeoutException e) {
                    failure = "timeout";
                } catch (IOException e) {
                    failure = describe(e);
                }
                disconnectQuietly(c);
                if (!prepareRetry(failure)) return false;
            }
        }

        /** Downloads the body into {@code targetUri}; returns the number of bytes written. */
        long download(Context context, Uri targetUri) throws IOException {
            ContentResolver resolver = context.getContentResolver();
            out = openTarget(resolver, targetUri, "w");
            written = 0;
            try {
                while (true) {
                    String failure = null;
                    HttpURLConnection c = connection;
                    connection = null;
                    try {
                        if (c == null) {
                            c = open(written);
                            failure = checkResponse(c, resolver, targetUri);
                        }
                        if (failure == null) {
                            failure = stream(context, c);
                        }
                    } catch (TargetWriteException | FinalHttpException e) {
                        throw e;
                    } catch (SocketTimeoutException e) {
                        failure = "timeout";
                    } catch (IOException e) {
                        failure = describe(e);
                    } finally {
                        disconnectQuietly(c);
                    }
                    if (failure == null) break;
                    if (!prepareRetry(failure)) {
                        throw new IOException("Giving up after " + attempts + " attempts: " + failure);
                    }
                }
                try {
                    out.flush();
                    out.close();
                } catch (IOException e) {
                    throw new TargetWriteException(e);
                } finally {
                    out = null;
                }
                return written;
            } finally {
                closeQuietly(out);
                out = null;
            }
        }

        /** Validates the answer to a (resume) request. Null if the body can be streamed. */
        private String checkResponse(HttpURLConnection c, ContentResolver resolver, Uri targetUri) throws IOException {
            int code = c.getResponseCode();
            if (code == HttpURLConnection.HTTP_PARTIAL && written > 0) {
                HttpResumePolicy.ContentRange range =
                        HttpResumePolicy.parseContentRange(c.getHeaderField("Content-Range"));
                if (!HttpResumePolicy.isValidResume(range, written, expectedTotal)) {
                    // Do not trust this server's ranges any more: start over without Range.
                    restartTarget(resolver, targetUri);
                    return "bad Content-Range";
                }
                if (expectedTotal <= 0 && range.total > 0) expectedTotal = range.total;
                return null;
            }
            if (code == HttpURLConnection.HTTP_OK) {
                if (written > 0) {
                    Log.i(TAG, "Server ignored Range for " + songId + "; restarting from 0 (had " + written + ")");
                    restartTarget(resolver, targetUri);
                }
                long length = c.getContentLengthLong();
                if (length > 0) expectedTotal = length;
                return null;
            }
            if (code >= HttpURLConnection.HTTP_BAD_REQUEST && !HttpResumePolicy.isRetryableStatus(code)) {
                Log.w(TAG, "HTTP " + code + " for " + songId + " at offset " + written);
                throw new FinalHttpException(code);
            }
            return "HTTP " + code;
        }

        /** Reads the body until EOF. Null on success, otherwise the reason to retry. */
        private String stream(Context context, HttpURLConnection c) throws IOException {
            HttpResumePolicy.ThroughputMonitor monitor =
                    new HttpResumePolicy.ThroughputMonitor(SystemClock.elapsedRealtime());
            AtomicBoolean slow = new AtomicBoolean();
            // disconnect() closes the socket, so a read() blocked on a trickling server fails.
            ScheduledFuture<?> watchdog = WATCHDOG.scheduleWithFixedDelay(() -> {
                if (monitor.isTooSlow(SystemClock.elapsedRealtime()) && slow.compareAndSet(false, true)) {
                    disconnectQuietly(c);
                }
            }, WATCHDOG_PERIOD_MS, WATCHDOG_PERIOD_MS, TimeUnit.MILLISECONDS);
            String failure = null;
            try (InputStream in = c.getInputStream()) {
                int len;
                while ((len = in.read(buffer)) != -1) {
                    try {
                        out.write(buffer, 0, len);
                    } catch (IOException e) {
                        throw new TargetWriteException(e);
                    }
                    written += len;
                    long now = SystemClock.elapsedRealtime();
                    monitor.onBytes(now, len);
                    if (now - lastProgressMs > 500) {
                        DownloadProgressState.getInstance().reportBytesProgress(context, written);
                        lastProgressMs = now;
                    }
                }
            } catch (TargetWriteException e) {
                throw e;
            } catch (SocketTimeoutException e) {
                failure = "timeout";
            } catch (IOException e) {
                failure = describe(e);
            } finally {
                watchdog.cancel(false);
            }
            if (expectedTotal > 0 && written >= expectedTotal) return null;
            if (slow.get()) return "slow";
            if (failure != null) return failure;
            if (expectedTotal > 0) return "early EOF";
            return null;
        }

        private void restartTarget(ContentResolver resolver, Uri targetUri) throws IOException {
            closeQuietly(out);
            out = null;
            // A SAF stream cannot be seeked reliably: truncate and write from the start.
            out = openTarget(resolver, targetUri, "wt");
            written = 0;
        }

        /** Logs and waits before the next attempt. False when no attempt is left. */
        private boolean prepareRetry(String reason) throws IOException {
            if (attempts >= HttpResumePolicy.MAX_ATTEMPTS) {
                Log.w(TAG, "Giving up song=" + songId + " after " + attempts + " attempts, reason=" + reason
                        + ", written=" + written);
                return false;
            }
            long backoff = HttpResumePolicy.backoffMs(attempts);
            Log.i(TAG, "Retry song=" + songId + " attempt=" + (attempts + 1) + "/" + HttpResumePolicy.MAX_ATTEMPTS
                    + " reason=" + reason + " resumeOffset=" + written + " backoffMs=" + backoff);
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting to retry", e);
            }
            return true;
        }

        void logSuccess(long total) {
            long elapsedMs = Math.max(1, SystemClock.elapsedRealtime() - startMs);
            long kbPerSec = total * 1000 / 1024 / elapsedMs;
            Log.i(TAG, "Downloaded song=" + songId + " bytes=" + total + " attempts=" + attempts
                    + " timeMs=" + elapsedMs + " avgKBps=" + kbPerSec);
        }

        void close() {
            disconnectQuietly(connection);
            connection = null;
            closeQuietly(out);
            out = null;
        }

        private static OutputStream openTarget(ContentResolver resolver, Uri uri, String mode) throws IOException {
            OutputStream stream;
            try {
                stream = resolver.openOutputStream(uri, mode);
            } catch (IOException e) {
                throw new TargetWriteException(e);
            } catch (RuntimeException e) {
                throw new TargetWriteException(new IOException(e));
            }
            if (stream == null) {
                throw new TargetWriteException(new IOException("Cannot open output stream"));
            }
            return stream;
        }

        private static String describe(IOException e) {
            // Class name only: messages may echo the request URL.
            return "IOException(" + e.getClass().getSimpleName() + ")";
        }

        private static void disconnectQuietly(HttpURLConnection c) {
            if (c == null) return;
            try {
                c.disconnect();
            } catch (Exception ignored) {
            }
        }

        private static void closeQuietly(OutputStream stream) {
            if (stream == null) return;
            try {
                stream.close();
            } catch (Exception ignored) {
            }
        }
    }
}
