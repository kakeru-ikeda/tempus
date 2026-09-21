package com.eddyizm.tempus.util;

import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import com.eddyizm.tempus.App;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Expected sizes of files saved into the user-selected folder, keyed like
 * {@link ExternalAudioReader}. Loaded from SharedPreferences once and kept in memory; every
 * change is persisted with {@code apply()}, so the JSON is never re-parsed per call.
 */
public final class ExternalDownloadMetadataStore {

    private static final String PREF_KEY = "external_download_metadata";

    private static Map<String, Long> sizes;

    private ExternalDownloadMetadataStore() {
    }

    private static SharedPreferences preferences() {
        return App.getInstance().getPreferences();
    }

    private static Map<String, Long> loaded() {
        if (sizes != null) return sizes;
        sizes = new HashMap<>();
        String raw = preferences().getString(PREF_KEY, "{}");
        try {
            JSONObject object = new JSONObject(raw);
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                long size = object.optLong(key, -1L);
                if (size > 0) {
                    sizes.put(key, size);
                }
            }
        } catch (JSONException ignored) {
        }
        return sizes;
    }

    private static void persist() {
        JSONObject object = new JSONObject();
        try {
            for (Map.Entry<String, Long> entry : loaded().entrySet()) {
                object.put(entry.getKey(), (long) entry.getValue());
            }
        } catch (JSONException ignored) {
        }
        preferences().edit().putString(PREF_KEY, object.toString()).apply();
    }

    public static synchronized void clear() {
        loaded().clear();
        persist();
    }

    public static synchronized void recordSize(String key, long size) {
        if (key == null || size <= 0) {
            return;
        }
        Long previous = loaded().put(key, size);
        if (previous == null || previous != size) {
            persist();
        }
    }

    public static synchronized void remove(String key) {
        if (key == null) {
            return;
        }
        if (loaded().remove(key) != null) {
            persist();
        }
    }

    @Nullable
    public static synchronized Long getSize(String key) {
        if (key == null) {
            return null;
        }
        return loaded().get(key);
    }

    public static synchronized Map<String, Long> snapshot() {
        Map<String, Long> current = loaded();
        return current.isEmpty() ? Collections.emptyMap() : new HashMap<>(current);
    }

    public static synchronized void retainOnly(Set<String> keysToKeep) {
        if (keysToKeep == null || keysToKeep.isEmpty()) {
            clear();
            return;
        }
        if (loaded().keySet().retainAll(keysToKeep)) {
            persist();
        }
    }
}
