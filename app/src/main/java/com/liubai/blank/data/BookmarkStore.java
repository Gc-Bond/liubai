package com.liubai.blank.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.liubai.blank.model.Bookmark;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 浏览器书签持久化(SharedPreferences 存 JSON),与 DockStore 同构。 */
public final class BookmarkStore {

    private static final String PREFS = "browser_bookmarks";
    private static final String KEY = "items";

    private final SharedPreferences prefs;

    public BookmarkStore(Context ctx) {
        this.prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public List<Bookmark> load() {
        List<Bookmark> list = new ArrayList<>();
        String raw = prefs.getString(KEY, null);
        if (raw == null || raw.isEmpty()) return list;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) list.add(Bookmark.fromJson(o));
            }
        } catch (Exception ignored) { }
        return list;
    }

    public void save(List<Bookmark> list) {
        JSONArray arr = new JSONArray();
        try {
            for (Bookmark b : list) arr.put(b.toJson());
        } catch (Exception ignored) { }
        prefs.edit().putString(KEY, arr.toString()).apply();
    }

    public boolean isBookmarked(List<Bookmark> list, String url) {
        for (Bookmark b : list) if (b.url.equals(url)) return true;
        return false;
    }
}
