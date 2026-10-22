package com.liubai.blank.media;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/**
 * 音乐源候选列表(包名)持久化,与 DockStore/LayoutStore 同构。
 * 空列表也能工作——MediaHub 会退回"任意活跃会话"。
 *
 * **排头的那个就是"当前音乐 app"**:Dock 上的音乐键短按开它、长按在候选里换一个
 * (见 select)。这份列表同时还喂给 MediaHub 决定优先显示谁的播放,两个用途共用一份
 * 配置是有意的 —— 用户心里只有"我用哪个音乐 app"这一件事。
 */
public final class MusicSourceStore {

    private static final String PREFS = "music_sources";
    private static final String KEY = "pkgs";

    private final SharedPreferences prefs;

    public MusicSourceStore(Context ctx) {
        this.prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public List<String> load() {
        List<String> out = new ArrayList<>();
        String raw = prefs.getString(KEY, null);
        if (raw == null || raw.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                String p = arr.optString(i);
                if (p != null && !p.isEmpty()) out.add(p);
            }
        } catch (JSONException ignored) { }
        return out;
    }

    public void save(List<String> pkgs) {
        JSONArray arr = new JSONArray();
        for (String p : pkgs) arr.put(p);
        prefs.edit().putString(KEY, arr.toString()).apply();
    }

    /** 当前用的音乐 app。没配过就返回 null,调用方自己兜底(CATEGORY_APP_MUSIC)。 */
    public String current() {
        List<String> list = load();
        return list.isEmpty() ? null : list.get(0);
    }

    /** 选中某个候选:挪到排头即为"当前使用"。不在列表里就加进来并排头。 */
    public void select(String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        List<String> list = load();
        list.remove(pkg);
        list.add(0, pkg);
        save(list);
    }

    /** 返回是否真的加了(已存在则不重复加)。 */
    public boolean add(String pkg) {
        List<String> list = load();
        if (list.contains(pkg)) return false;
        list.add(pkg);
        save(list);
        return true;
    }

    public void remove(String pkg) {
        List<String> list = load();
        if (list.remove(pkg)) save(list);
    }
}
