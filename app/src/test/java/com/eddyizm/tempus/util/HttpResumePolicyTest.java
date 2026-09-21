package com.eddyizm.tempus.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class HttpResumePolicyTest {

    @Test
    public void parsesContentRange() {
        HttpResumePolicy.ContentRange r = HttpResumePolicy.parseContentRange("bytes 100-999/1000");
        assertNotNull(r);
        assertEquals(100, r.start);
        assertEquals(999, r.end);
        assertEquals(1000, r.total);
    }

    @Test
    public void parsesUnknownTotalAndLooseSpacing() {
        HttpResumePolicy.ContentRange r = HttpResumePolicy.parseContentRange("  Bytes 0-9/*  ");
        assertNotNull(r);
        assertEquals(-1, r.total);
    }

    @Test
    public void rejectsMalformedContentRange() {
        assertNull(HttpResumePolicy.parseContentRange(null));
        assertNull(HttpResumePolicy.parseContentRange(""));
        assertNull(HttpResumePolicy.parseContentRange("bytes */1000"));
        assertNull(HttpResumePolicy.parseContentRange("bytes 10-5/1000"));
        assertNull(HttpResumePolicy.parseContentRange("bytes 0-1000/1000"));
        assertNull(HttpResumePolicy.parseContentRange("bytes a-b/c"));
        assertNull(HttpResumePolicy.parseContentRange("items 0-9/10"));
        assertNull(HttpResumePolicy.parseContentRange("bytes 0-9/"));
    }

    @Test
    public void validatesResumeAgainstOffsetAndTotal() {
        HttpResumePolicy.ContentRange r = HttpResumePolicy.parseContentRange("bytes 500-999/1000");
        assertTrue(HttpResumePolicy.isValidResume(r, 500, 1000));
        assertTrue(HttpResumePolicy.isValidResume(r, 500, -1));
        assertFalse(HttpResumePolicy.isValidResume(r, 400, 1000));
        assertFalse(HttpResumePolicy.isValidResume(r, 500, 2000));
        assertFalse(HttpResumePolicy.isValidResume(null, 500, 1000));
        HttpResumePolicy.ContentRange unknown = HttpResumePolicy.parseContentRange("bytes 500-999/*");
        assertTrue(HttpResumePolicy.isValidResume(unknown, 500, 1000));
        HttpResumePolicy.ContentRange overshoot = HttpResumePolicy.parseContentRange("bytes 500-1500/*");
        assertFalse(HttpResumePolicy.isValidResume(overshoot, 500, 1000));
    }

    @Test
    public void retryableStatuses() {
        assertTrue(HttpResumePolicy.isRetryableStatus(408));
        assertTrue(HttpResumePolicy.isRetryableStatus(429));
        assertTrue(HttpResumePolicy.isRetryableStatus(500));
        assertTrue(HttpResumePolicy.isRetryableStatus(503));
        assertTrue(HttpResumePolicy.isRetryableStatus(599));
        assertFalse(HttpResumePolicy.isRetryableStatus(400));
        assertFalse(HttpResumePolicy.isRetryableStatus(401));
        assertFalse(HttpResumePolicy.isRetryableStatus(404));
        assertFalse(HttpResumePolicy.isRetryableStatus(416));
        assertFalse(HttpResumePolicy.isRetryableStatus(200));
    }

    @Test
    public void backoffSchedule() {
        assertEquals(0, HttpResumePolicy.backoffMs(0));
        assertEquals(1_000, HttpResumePolicy.backoffMs(1));
        assertEquals(2_000, HttpResumePolicy.backoffMs(2));
        assertEquals(5_000, HttpResumePolicy.backoffMs(3));
        assertEquals(10_000, HttpResumePolicy.backoffMs(4));
        assertEquals(20_000, HttpResumePolicy.backoffMs(5));
        assertEquals(20_000, HttpResumePolicy.backoffMs(9));
    }

    @Test
    public void notSlowDuringGracePeriod() {
        HttpResumePolicy.ThroughputMonitor m = new HttpResumePolicy.ThroughputMonitor(0, 10_000, 15_000, 64 * 1024);
        assertFalse(m.isTooSlow(9_999));
    }

    @Test
    public void slowWhenNoBytesAfterGrace() {
        HttpResumePolicy.ThroughputMonitor m = new HttpResumePolicy.ThroughputMonitor(0, 10_000, 15_000, 64 * 1024);
        assertTrue(m.isTooSlow(10_000));
    }

    @Test
    public void fastStreamIsNotSlow() {
        HttpResumePolicy.ThroughputMonitor m = new HttpResumePolicy.ThroughputMonitor(0, 10_000, 15_000, 64 * 1024);
        for (long t = 0; t <= 30_000; t += 100) {
            m.onBytes(t, 64 * 1024); // 640 KB/s
        }
        assertFalse(m.isTooSlow(30_000));
    }

    @Test
    public void burstyTrickleIsSlow() {
        // The measured pathology: ~64 KB every 5 s (~13 KB/s).
        HttpResumePolicy.ThroughputMonitor m = new HttpResumePolicy.ThroughputMonitor(0, 10_000, 15_000, 64 * 1024);
        for (long t = 0; t <= 20_000; t += 5_000) {
            m.onBytes(t, 64 * 1024);
        }
        assertTrue(m.isTooSlow(20_000));
    }

    @Test
    public void earlyFastBytesAgeOutOfWindow() {
        HttpResumePolicy.ThroughputMonitor m = new HttpResumePolicy.ThroughputMonitor(0, 10_000, 15_000, 64 * 1024);
        m.onBytes(1_000, 50L * 1024 * 1024); // big burst early on
        assertFalse(m.isTooSlow(12_000));
        // Then silence: once the burst leaves the 15 s window the attempt is slow.
        assertTrue(m.isTooSlow(17_000));
    }

    @Test
    public void rateAtThresholdIsNotSlow() {
        HttpResumePolicy.ThroughputMonitor m = new HttpResumePolicy.ThroughputMonitor(0, 10_000, 15_000, 64 * 1024);
        for (long t = 0; t < 15_000; t += 1_000) {
            m.onBytes(t, 64 * 1024);
        }
        assertFalse(m.isTooSlow(14_999));
    }
}
