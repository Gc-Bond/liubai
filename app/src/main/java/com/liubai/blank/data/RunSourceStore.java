package com.liubai.blank.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/**
 * 运行位(第三个、第四个应用位)自己添加的 app 列表,与 NavSourceStore /
 * MusicSourceStore 同构 —— 排头的那个就是这一格当前显示的 app。
 *
 * 两个槽位各一份列表:用户在编辑面板里给某个运行位添加的 app 都在这,
 * Dock 上长按那一格也是在它们之间切换(见 DockView 的滑动选择器)。
 *
 * 没和 Nav/Music 合成一个类,是因为那俩各自还喂着别的逻辑(MediaHub 读音乐源、
 * 画中画卡位认导航/音乐源),运行位没有这些牵扯,单独一个简单的够了。
 */
public final class RunSourceStore {

    private static final String PREFS = "run_sources";

    private final SharedPreferences prefs;

    public RunSourceStore(Context ctx) {
        this.prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private String key(int slot) {
        return "slot" + slot;
    }

    public List<String> load(int slot) {
        List<String> out = new ArrayList<>();
        String raw = prefs.getString(key(slot), null);
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

    public void save(int slot, List<String> pkgs) {
        JSONArray arr = new JSONArray();
        for (String p : pkgs) arr.put(p);
        prefs.edit().putString(key(slot), arr.toString()).apply();
    }

    /** 这一格当前显示的 app。列表空就是没配,调用方自己兜底(空位)。 */
    public String current(int slot) {
        List<String> list = load(slot);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 加进列表末尾(不挪排头 = 不改变当前显示)。已存在就不重复加。 */
    public boolean add(int slot, String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        List<String> list = load(slot);
        if (list.contains(pkg)) return false;
        list.add(pkg);
        save(slot, list);
        return true;
    }

    /** 从列表里拿掉。返回是否真的拿掉了。 */
    public boolean remove(int slot, String pkg) {
        List<String> list = load(slot);
        if (!list.remove(pkg)) return false;
        save(slot, list);
        return true;
    }

    /** 选中某个候选:挪到排头即为"当前显示"。不在列表里就加进来并排头。 */
    public void select(int slot, String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        List<String> list = load(slot);
        list.remove(pkg);
        list.add(0, pkg);
        save(slot, list);
    }
}
