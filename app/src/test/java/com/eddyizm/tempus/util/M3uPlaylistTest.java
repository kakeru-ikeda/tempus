package com.eddyizm.tempus.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Arrays;
import java.util.Collections;

@RunWith(JUnit4.class)
public class M3uPlaylistTest {

    private static final String EXT = M3uPlaylist.EXTERNAL_STORAGE_AUTHORITY;

    @Test
    public void primaryVolumeMapsToEmulatedStorage() {
        assertEquals("/storage/emulated/0/Music",
                M3uPlaylist.resolveTreeBasePath(EXT, "primary:Music"));
    }

    @Test
    public void sdVolumeMapsToStorageVolumeId() {
        assertEquals("/storage/9C33-6BBD/Folder",
                M3uPlaylist.resolveTreeBasePath(EXT, "9C33-6BBD:Folder"));
    }

    @Test
    public void nestedRelativeDirectoryIsKept() {
        assertEquals("/storage/9C33-6BBD/Music/Tempus/Downloads",
                M3uPlaylist.resolveTreeBasePath(EXT, "9C33-6BBD:Music/Tempus/Downloads/"));
    }

    @Test
    public void emptyRelativeDirectoryIsVolumeRoot() {
        assertEquals("/storage/9C33-6BBD", M3uPlaylist.resolveTreeBasePath(EXT, "9C33-6BBD:"));
        assertEquals("/storage/emulated/0", M3uPlaylist.resolveTreeBasePath(EXT, "primary:"));
    }

    @Test
    public void unknownProviderHasNoBasePath() {
        assertNull(M3uPlaylist.resolveTreeBasePath("com.example.documents", "primary:Music"));
        assertNull(M3uPlaylist.resolveTreeBasePath(EXT, "no-colon"));
        assertNull(M3uPlaylist.resolveTreeBasePath(EXT, null));
    }

    @Test
    public void entryPathIsAbsoluteWithBaseAndRelativeWithout() {
        assertEquals("/storage/9C33-6BBD/Folder/Artist/Album/024-Title.flac",
                M3uPlaylist.entryPath("/storage/9C33-6BBD/Folder", "Artist/Album/024-Title.flac"));
        assertEquals("Artist/Album/024-Title.flac",
                M3uPlaylist.entryPath(null, "Artist/Album/024-Title.flac"));
    }

    @Test
    public void extinfFormatting() {
        assertEquals("#EXTINF:215,Artist - Title", M3uPlaylist.extinf(215, "Artist", "Title"));
        assertEquals("#EXTINF:-1,Artist - Title", M3uPlaylist.extinf(null, "Artist", "Title"));
        assertEquals("#EXTINF:-1,Title", M3uPlaylist.extinf(0, null, "Title"));
        assertEquals("#EXTINF:10,Title", M3uPlaylist.extinf(10, "", "Title"));
        assertEquals("#EXTINF:10,A B - T, with comma", M3uPlaylist.extinf(10, "A\nB", "T, with comma"));
    }

    @Test
    public void buildKeepsOrderAndDuplicatesAndOmitsFailures() {
        String text = M3uPlaylist.build(Arrays.asList(
                new M3uPlaylist.Entry("A/One.flac", 100, "Artist", "One"),
                new M3uPlaylist.Entry(null, 120, "Artist", "Failed"),
                new M3uPlaylist.Entry("B/Two.flac", null, null, "Two"),
                new M3uPlaylist.Entry("A/One.flac", 100, "Artist", "One")
        ), "/storage/9C33-6BBD");

        assertEquals("#EXTM3U\n"
                + "#EXTINF:100,Artist - One\n/storage/9C33-6BBD/A/One.flac\n"
                + "#EXTINF:-1,Two\n/storage/9C33-6BBD/B/Two.flac\n"
                + "#EXTINF:100,Artist - One\n/storage/9C33-6BBD/A/One.flac\n", text);
    }

    @Test
    public void buildWithUnknownProviderUsesRelativePaths() {
        assertEquals("#EXTM3U\n#EXTINF:5,X - Y\nX - Y (Z).mp3\n",
                M3uPlaylist.build(Collections.singletonList(
                        new M3uPlaylist.Entry("X - Y (Z).mp3", 5, "X", "Y")), null));
    }

    @Test
    public void fileNameIsSanitized() {
        assertEquals("Best_ Of_.m3u8", M3uPlaylist.fileName("Best: Of?"));
        assertEquals("Trailing.m3u8", M3uPlaylist.fileName("Trailing. "));
        assertEquals("_.m3u8", M3uPlaylist.fileName(null));
    }
}
