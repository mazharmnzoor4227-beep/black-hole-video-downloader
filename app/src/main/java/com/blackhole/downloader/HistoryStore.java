package com.blackhole.downloader;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class HistoryStore {
    private static final String PREFS = "black_hole_history";
    private static final String KEY = "items";
    private static final int MAX_ITEMS = 100;

    private HistoryStore() {
    }

    public static final class Entry {
        public final String title;
        public final String source;
        public final String resolution;
        public final long timestamp;

        public Entry(String title, String source, String resolution, long timestamp) {
            this.title = title == null || title.trim().isEmpty() ? "Video" : title.trim();
            this.source = source == null ? "" : source.trim();
            this.resolution = resolution == null ? "" : resolution.trim();
            this.timestamp = timestamp;
        }
    }

    public static synchronized void add(Context context, Entry entry) {
        List<Entry> entries = list(context);
        entries.add(0, entry);
        if (entries.size() > MAX_ITEMS) {
            entries = new ArrayList<>(entries.subList(0, MAX_ITEMS));
        }
        save(context, entries);
    }

    public static synchronized List<Entry> list(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = preferences.getString(KEY, "[]");
        List<Entry> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(raw == null ? "[]" : raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) continue;
                result.add(new Entry(
                        object.optString("title", "Video"),
                        object.optString("source", ""),
                        object.optString("resolution", ""),
                        object.optLong("timestamp", 0L)
                ));
            }
        } catch (Throwable ignored) {
            return new ArrayList<>();
        }
        return result;
    }

    public static synchronized void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY, "[]")
                .apply();
    }

    private static void save(Context context, List<Entry> entries) {
        JSONArray array = new JSONArray();
        try {
            for (Entry entry : entries) {
                JSONObject object = new JSONObject();
                object.put("title", entry.title);
                object.put("source", entry.source);
                object.put("resolution", entry.resolution);
                object.put("timestamp", entry.timestamp);
                array.put(object);
            }
        } catch (Throwable ignored) {
            return;
        }

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY, array.toString())
                .apply();
    }
}
