package com.liubai.blank.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/**
 * 地图源候选列表(包名),**排头的那个就是当前用的地图**。
 *
 * 与 {@link com.liubai.blank.media.MusicSourceStore} 同构 —— 音乐那边先有,这边照着做。
 * 没合并成一个泛型类是因为两者当初的用途不同(音乐源还要喂给 MediaHub 决定优先显示
 * 谁的播放),真要合并该等两边的语义都稳定下来再说。
 *
 * 空列表也能工作:Dock 的地图键会退回按包名猜(高德车机版 → BYD 自带地图 → geo: intent)。
 */
public final class NavSourceStore {

    private static final String PREFS = "nav_sources";
    private static final String KEY = "pkgs";

    private final SharedPreferences prefs;

    public NavSourceStore(Context ctx) {
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

    /** 当前用的地图。没配过就返回 null,调用方自己兜底。 */
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

    /** 加进列表末尾(不挪排头 = 不改变当前用的)。已存在就不重复加。 */
    public boolean add(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
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
