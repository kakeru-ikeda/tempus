package com.eddyizm.tempus.util;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Pure decision logic for resumable HTTP downloads (no Android dependencies so it can be unit
 * tested on the JVM): Content-Range parsing and validation, retryable status codes, backoff
 * schedule and a sliding-window "too slow" detector.
 */
public final class HttpResumePolicy {

    /** Connections opened per song in total, including the first one. */
    public static final int MAX_ATTEMPTS = 6;

    /** Wait before retry n (1-based); the last value repeats. */
    static final long[] BACKOFF_MS = {1_000, 2_000, 5_000, 10_000, 20_000};

    /** No slow-detection during the first part of an attempt (TCP/TLS ramp-up). */
    public static final long SLOW_GRACE_MS = 10_000;
    /** Throughput is measured over this sliding window. */
    public static final long SLOW_WINDOW_MS = 15_000;
    /** Below this rate over the window the attempt is aborted and resumed on a new connection. */
    public static final long SLOW_MIN_BYTES_PER_SEC = 64 * 1024;

    private HttpResumePolicy() {
    }

    /** Backoff before the {@code retry}-th retry (1 = first retry). */
    public static long backoffMs(int retry) {
        if (retry <= 0) return 0;
        int index = Math.min(retry, BACKOFF_MS.length) - 1;
        return BACKOFF_MS[index];
    }

    /** 408, 429 and 5xx are worth retrying; other error statuses are final. */
    public static boolean isRetryableStatus(int code) {
        return code == 408 || code == 429 || (code >= 500 && code <= 599);
    }

    /** Parsed {@code Content-Range: bytes start-end/total}; {@code total} is -1 for "*". */
    public static final class ContentRange {
        public final long start;
        public final long end;
        public final long total;

        ContentRange(long start, long end, long total) {
            this.start = start;
            this.end = end;
            this.total = total;
        }
    }

    /** Returns null when the header is missing or malformed. */
    public static ContentRange parseContentRange(String header) {
        if (header == null) return null;
        String value = header.trim();
        if (!value.toLowerCase(Locale.ROOT).startsWith("bytes")) return null;
        value = value.substring(5).trim();
        if (value.startsWith("=")) value = value.substring(1).trim();
        int dash = value.indexOf('-');
        int slash = value.indexOf('/');
        if (dash <= 0 || slash <= dash + 1 || slash == value.length() - 1) return null;
        try {
            long start = Long.parseLong(value.substring(0, dash).trim());
            long end = Long.parseLong(value.substring(dash + 1, slash).trim());
            String totalText = value.substring(slash + 1).trim();
            long total = totalText.equals("*") ? -1 : Long.parseLong(totalText);
            if (start < 0 || end < start) return null;
            if (total != -1 && (total <= 0 || end >= total)) return null;
            return new ContentRange(start, end, total);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * A 206 answer to {@code Range: bytes=offset-} is usable only if it starts exactly at
     * {@code offset} and, when both are known, describes a file of the expected total size.
     */
    public static boolean isValidResume(ContentRange range, long offset, long expectedTotal) {
        if (range == null || range.start != offset) return false;
        if (expectedTotal > 0) {
            if (range.total != -1 && range.total != expectedTotal) return false;
            if (range.end >= expectedTotal) return false;
        }
        return true;
    }

    /**
     * Sliding-window throughput for one connection attempt. Thread safe: the download thread
     * feeds bytes, a watchdog thread asks {@link #isTooSlow(long)}.
     */
    public static final class ThroughputMonitor {
        private static final long BUCKET_MS = 500;

        private final long startMs;
        private final long graceMs;
        private final long windowMs;
        private final long minBytesPerSec;
        /** {bucketStartMs, bytes} */
        private final ArrayDeque<long[]> buckets = new ArrayDeque<>();

        public ThroughputMonitor(long startMs) {
            this(startMs, SLOW_GRACE_MS, SLOW_WINDOW_MS, SLOW_MIN_BYTES_PER_SEC);
        }

        public ThroughputMonitor(long startMs, long graceMs, long windowMs, long minBytesPerSec) {
            this.startMs = startMs;
            this.graceMs = graceMs;
            this.windowMs = windowMs;
            this.minBytesPerSec = minBytesPerSec;
        }

        public synchronized void onBytes(long nowMs, long count) {
            long bucket = nowMs - (nowMs % BUCKET_MS);
            long[] last = buckets.peekLast();
            if (last != null && last[0] == bucket) {
                last[1] += count;
            } else {
                buckets.addLast(new long[]{bucket, count});
            }
            trim(nowMs);
        }

        public synchronized boolean isTooSlow(long nowMs) {
            long elapsed = nowMs - startMs;
            if (elapsed < graceMs) return false;
            trim(nowMs);
            long span = Math.min(elapsed, windowMs);
            long bytes = 0;
            for (long[] b : buckets) bytes += b[1];
            // bytes / (span / 1000) < min  <=>  bytes * 1000 < min * span
            return bytes * 1000 < minBytesPerSec * span;
        }

        private void trim(long nowMs) {
            long cutoff = nowMs - windowMs;
            while (!buckets.isEmpty() && buckets.peekFirst()[0] + BUCKET_MS <= cutoff) {
                buckets.removeFirst();
            }
        }
    }
}
