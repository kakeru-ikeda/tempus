package com.eddyizm.tempus.util;

import java.util.List;

/**
 * Builds the .m3u8 written next to a playlist downloaded into the user-selected (SAF) folder.
 * Pure Java so it can be unit tested on the JVM.
 *
 * Players on Astell&Kern devices resolve entries by absolute path
 * ({@code /storage/9C33-6BBD/Folder/024-Title.flac}), so paths are made absolute whenever the
 * tree belongs to the platform external storage provider.
 */
public final class M3uPlaylist {

    public static final String EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents";
    public static final String FILE_EXTENSION = ".m3u8";

    private static final String PRIMARY_VOLUME = "primary";
    private static final String HOME_ROOT = "home";

    private M3uPlaylist() {
    }

    /** One playlist position. {@code relativePath} is null when the song has no file (failed). */
    public static final class Entry {
        final String relativePath;
        final Integer durationSeconds;
        final String artist;
        final String title;

        public Entry(String relativePath, Integer durationSeconds, String artist, String title) {
            this.relativePath = relativePath;
            this.durationSeconds = durationSeconds;
            this.artist = artist;
            this.title = title;
        }
    }

    /** {@code <sanitized name>.m3u8}, using the same exFAT-safe rules as the song files. */
    public static String fileName(String playlistName) {
        return ExternalDownloadPath.sanitizeSegment(playlistName) + FILE_EXTENSION;
    }

    /**
     * Absolute file system path of a SAF tree, or null when it cannot be derived (another
     * provider, or an unexpected document id). The result has no trailing slash.
     */
    public static String resolveTreeBasePath(String authority, String treeDocumentId) {
        if (!EXTERNAL_STORAGE_AUTHORITY.equals(authority) || treeDocumentId == null) return null;
        int colon = treeDocumentId.indexOf(':');
        if (colon <= 0) return null;

        String volume = treeDocumentId.substring(0, colon);
        String relative = trimSlashes(treeDocumentId.substring(colon + 1));

        String base;
        if (PRIMARY_VOLUME.equals(volume)) {
            base = "/storage/emulated/0";
        } else if (HOME_ROOT.equals(volume)) {
            base = "/storage/emulated/0/Documents";
        } else {
            base = "/storage/" + volume;
        }
        return relative.isEmpty() ? base : base + "/" + relative;
    }

    /** Path line for a file at {@code relativePath} inside the tree; relative when the base is unknown. */
    public static String entryPath(String basePath, String relativePath) {
        String relative = trimSlashes(relativePath);
        return basePath == null ? relative : basePath + "/" + relative;
    }

    public static String extinf(Integer durationSeconds, String artist, String title) {
        int duration = durationSeconds != null && durationSeconds > 0 ? durationSeconds : -1;
        String cleanTitle = singleLine(title);
        String cleanArtist = singleLine(artist);
        String label = cleanArtist.isEmpty() ? cleanTitle : cleanArtist + " - " + cleanTitle;
        return "#EXTINF:" + duration + "," + label;
    }

    /** UTF-8 text (without BOM) with LF line endings. Entries without a file are omitted. */
    public static String build(List<Entry> entries, String basePath) {
        StringBuilder out = new StringBuilder("#EXTM3U\n");
        if (entries == null) return out.toString();
        for (Entry entry : entries) {
            if (entry == null || entry.relativePath == null || trimSlashes(entry.relativePath).isEmpty()) {
                continue;
            }
            out.append(extinf(entry.durationSeconds, entry.artist, entry.title)).append('\n');
            out.append(entryPath(basePath, entry.relativePath)).append('\n');
        }
        return out.toString();
    }

    private static String singleLine(String value) {
        if (value == null) return "";
        return value.replaceAll("[\\r\\n]+", " ").trim();
    }

    private static String trimSlashes(String value) {
        if (value == null) return "";
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '/') start++;
        while (end > start && value.charAt(end - 1) == '/') end--;
        return value.substring(start, end);
    }
}
