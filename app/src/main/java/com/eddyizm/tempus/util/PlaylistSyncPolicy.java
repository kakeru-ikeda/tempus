package com.eddyizm.tempus.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;

/** Pure decision logic for playlist directory synchronization, without Android dependencies. */
public final class PlaylistSyncPolicy {

    public static final long THROTTLE_MS = 10L * 60L * 1000L;

    private PlaylistSyncPolicy() {
    }

    /** Hashes ordered song IDs separated by newlines; null IDs are empty strings. */
    public static String hashSongIds(List<String> songIds) {
        StringBuilder joined = new StringBuilder();
        if (songIds != null) {
            boolean first = true;
            for (String songId : songIds) {
                if (!first) joined.append('\n');
                joined.append(Objects.toString(songId, ""));
                first = false;
            }
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(joined.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest) {
                hex.append(Character.forDigit((value & 0xff) >>> 4, 16));
                hex.append(Character.forDigit(value & 0x0f, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** Uses elapsed time; forced runs, first runs and clock rollback bypass throttling. */
    public static boolean isThrottled(long lastRunElapsedMs, long nowElapsedMs, boolean force) {
        if (force || lastRunElapsedMs <= 0 || nowElapsedMs < lastRunElapsedMs) return false;
        return nowElapsedMs - lastRunElapsedMs < THROTTLE_MS;
    }

    /** Missing files, changed contents or a known changed name require synchronization. */
    public static boolean needsSync(String storedHash, String currentHash, int missingCount,
                                    String storedName, String currentName) {
        return storedHash == null
                || !storedHash.equals(currentHash)
                || missingCount > 0
                || (currentName != null && !currentName.equals(storedName));
    }
}
