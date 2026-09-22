package com.eddyizm.tempus.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class PlaylistSyncPolicyTest {

    @Test
    public void hashIsStableAndLowercaseSha256() {
        String hash = PlaylistSyncPolicy.hashSongIds(Arrays.asList("a", "b"));
        assertEquals(hash, PlaylistSyncPolicy.hashSongIds(Arrays.asList("a", "b")));
        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"));
        // SHA-256 of "a\nb", with no trailing newline.
        assertEquals("7e18f737311b2dc3b2f269dd78396b0351f14fb66efa879f768cb23181883c78", hash);
    }

    @Test
    public void additionsDeletionsAndReorderingChangeHash() {
        String hash = PlaylistSyncPolicy.hashSongIds(Arrays.asList("a", "b"));
        assertNotEquals(hash, PlaylistSyncPolicy.hashSongIds(Arrays.asList("a", "b", "c")));
        assertNotEquals(hash, PlaylistSyncPolicy.hashSongIds(Collections.singletonList("a")));
        assertNotEquals(hash, PlaylistSyncPolicy.hashSongIds(Arrays.asList("b", "a")));
    }

    @Test
    public void nullListHashesAsEmptyList() {
        assertEquals(PlaylistSyncPolicy.hashSongIds(Collections.emptyList()),
                PlaylistSyncPolicy.hashSongIds(null));
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                PlaylistSyncPolicy.hashSongIds(null));
    }

    @Test
    public void nullIdsAreEmptyAndRetainSeparators() {
        assertEquals(PlaylistSyncPolicy.hashSongIds(Arrays.asList("", "a", "")),
                PlaylistSyncPolicy.hashSongIds(Arrays.asList(null, "a", null)));
        assertNotEquals(PlaylistSyncPolicy.hashSongIds(Collections.singletonList("a")),
                PlaylistSyncPolicy.hashSongIds(Arrays.asList(null, "a", null)));
    }

    @Test
    public void forceAndFirstRunBypassThrottle() {
        assertFalse(PlaylistSyncPolicy.isThrottled(1_000, 1_001, true));
        assertFalse(PlaylistSyncPolicy.isThrottled(0, 1_000, false));
        assertFalse(PlaylistSyncPolicy.isThrottled(-1, 1_000, false));
    }

    @Test
    public void throttlesUntilTenMinutesHaveElapsed() {
        assertEquals(600_000L, PlaylistSyncPolicy.THROTTLE_MS);
        assertTrue(PlaylistSyncPolicy.isThrottled(1_000, 1_000, false));
        assertTrue(PlaylistSyncPolicy.isThrottled(1_000, 301_000, false));
        assertTrue(PlaylistSyncPolicy.isThrottled(1_000, 600_999, false));
        assertFalse(PlaylistSyncPolicy.isThrottled(1_000, 601_000, false));
        assertFalse(PlaylistSyncPolicy.isThrottled(1_000, 601_001, false));
    }

    @Test
    public void clockRollbackBypassesThrottle() {
        assertFalse(PlaylistSyncPolicy.isThrottled(1_000, 999, false));
    }

    @Test
    public void matchingPlaylistDoesNotNeedSync() {
        assertFalse(PlaylistSyncPolicy.needsSync("hash", "hash", 0, "name", "name"));
    }

    @Test
    public void absentOrChangedHashNeedsSync() {
        assertTrue(PlaylistSyncPolicy.needsSync(null, "hash", 0, "name", "name"));
        assertTrue(PlaylistSyncPolicy.needsSync("old", "new", 0, "name", "name"));
        assertTrue(PlaylistSyncPolicy.needsSync("hash", null, 0, "name", "name"));
    }

    @Test
    public void missingFilesNeedSync() {
        assertTrue(PlaylistSyncPolicy.needsSync("hash", "hash", 1, "name", "name"));
    }

    @Test
    public void renamedPlaylistNeedsSync() {
        assertTrue(PlaylistSyncPolicy.needsSync("hash", "hash", 0, "old", "new"));
        assertTrue(PlaylistSyncPolicy.needsSync("hash", "hash", 0, null, "new"));
    }

    @Test
    public void unknownCurrentNameIsNotARename() {
        assertFalse(PlaylistSyncPolicy.needsSync("hash", "hash", 0, "name", null));
        assertFalse(PlaylistSyncPolicy.needsSync("hash", "hash", 0, null, null));
    }
}
