package com.eddyizm.tempus.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Arrays;

@RunWith(JUnit4.class)
public class ExternalDownloadPathTest {

    @Test
    public void normalPathIsSplitIntoFoldersAndFile() {
        assertEquals(Arrays.asList("Artist", "Album", "01 Title.flac"),
                ExternalDownloadPath.buildTargetSegments("Artist/Album/01 Title.flac", "flac", false));
    }

    @Test
    public void leadingSlashIsIgnored() {
        assertEquals(Arrays.asList("Artist", "Album", "01 Title.flac"),
                ExternalDownloadPath.sanitizeSegments("/Artist/Album/01 Title.flac"));
    }

    @Test
    public void emptyAndDotSegmentsAreDropped() {
        assertEquals(Arrays.asList("Artist", "Album", "01 Title.flac"),
                ExternalDownloadPath.sanitizeSegments("Artist//./../Album/../01 Title.flac"));
    }

    @Test
    public void blankPathHasNoSegments() {
        assertTrue(ExternalDownloadPath.sanitizeSegments(null).isEmpty());
        assertTrue(ExternalDownloadPath.sanitizeSegments("  ").isEmpty());
        assertTrue(ExternalDownloadPath.buildTargetSegments("/../.", "flac", false).isEmpty());
    }

    @Test
    public void illegalCharactersAreReplaced() {
        assertEquals(Arrays.asList("AC_DC", "What_ _Why_", "a_b_c_d_e_f.flac"),
                ExternalDownloadPath.sanitizeSegments("AC\\DC/What: *Why?/a\"b<c>d|e\u0001f.flac"));
    }

    @Test
    public void trailingDotsAndSpacesAreTrimmed() {
        assertEquals(Arrays.asList("Vol", "Album", "_", "Track.flac"),
                ExternalDownloadPath.sanitizeSegments("Vol. /Album . . /.../Track.flac"));
    }

    @Test
    public void missingExtensionIsAppended() {
        assertEquals(Arrays.asList("Artist", "Mr. Brown.flac"),
                ExternalDownloadPath.buildTargetSegments("Artist/Mr. Brown", "flac", false));
    }

    @Test
    public void existingExtensionIsKeptWithoutTranscoding() {
        assertEquals(Arrays.asList("Artist", "Track.flac"),
                ExternalDownloadPath.buildTargetSegments("Artist/Track.flac", "bin", false));
    }

    @Test
    public void transcodingReplacesExtension() {
        assertEquals(Arrays.asList("Artist", "Album", "01 Title.mp3"),
                ExternalDownloadPath.buildTargetSegments("Artist/Album/01 Title.flac", "mp3", true));
    }

    @Test
    public void lookupSegmentsIgnoreExtension() {
        assertEquals(Arrays.asList("Artist", "Album", "01 Title"),
                ExternalDownloadPath.lookupSegments("Artist/Album/01 Title.flac"));
        assertEquals(Arrays.asList("Artist", "Mr. Brown"),
                ExternalDownloadPath.lookupSegments("Artist/Mr. Brown"));
    }
}
