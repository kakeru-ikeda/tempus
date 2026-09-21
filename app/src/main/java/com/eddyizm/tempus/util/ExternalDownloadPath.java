package com.eddyizm.tempus.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Turns a server-side relative path (Subsonic {@code Child.path}, e.g. {@code Artist/Album/01 Title.flac})
 * into folder / file name segments that are safe to create on an exFAT SD card.
 * Pure Java so it can be unit tested on the JVM.
 */
public final class ExternalDownloadPath {

    private static final Pattern ILLEGAL_CHARS = Pattern.compile("[\\\\/:*?\"<>|\\p{Cntrl}]");
    private static final Pattern TRAILING_DOTS_SPACES = Pattern.compile("[. ]+$");
    private static final Pattern EXTENSION = Pattern.compile("\\.[A-Za-z0-9]{1,5}$");

    private ExternalDownloadPath() {
    }

    public static String sanitizeSegment(String segment) {
        if (segment == null) return "_";
        String sanitized = ILLEGAL_CHARS.matcher(segment).replaceAll("_");
        sanitized = TRAILING_DOTS_SPACES.matcher(sanitized).replaceAll("");
        return sanitized.isEmpty() ? "_" : sanitized;
    }

    /** Sanitized segments of {@code path}; empty when the path is blank or has no usable segment. */
    public static List<String> sanitizeSegments(String path) {
        if (path == null || path.trim().isEmpty()) return Collections.emptyList();
        List<String> segments = new ArrayList<>();
        for (String raw : path.split("/")) {
            if (raw.isEmpty() || raw.equals(".") || raw.equals("..")) continue;
            segments.add(sanitizeSegment(raw));
        }
        return segments;
    }

    public static boolean hasExtension(String name) {
        return name != null && EXTENSION.matcher(name).find() && stripExtension(name).length() > 0;
    }

    public static String stripExtension(String name) {
        return EXTENSION.matcher(name).replaceFirst("");
    }

    /**
     * Folder segments followed by the final file name. The existing extension is kept unless
     * {@code replaceExtension} is set (transcoded download); a missing one is filled in with
     * {@code extension}. Empty when the path is unusable.
     */
    public static List<String> buildTargetSegments(String path, String extension, boolean replaceExtension) {
        List<String> segments = sanitizeSegments(path);
        if (segments.isEmpty()) return segments;

        int last = segments.size() - 1;
        String name = segments.get(last);
        if (extension != null && !extension.isEmpty()) {
            if (!hasExtension(name)) {
                name = name + "." + extension;
            } else if (replaceExtension) {
                name = stripExtension(name) + "." + extension;
            }
        }
        segments.set(last, name);
        return segments;
    }

    /** Segments used as the lookup key: the file name loses its extension so transcoding does not matter. */
    public static List<String> lookupSegments(String path) {
        List<String> segments = sanitizeSegments(path);
        if (segments.isEmpty()) return segments;

        int last = segments.size() - 1;
        String name = segments.get(last);
        if (hasExtension(name)) {
            segments.set(last, stripExtension(name));
        }
        return segments;
    }
}
