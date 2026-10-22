package com.liubai.blank.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 这些 app 在前台时,Dock 自动让位。**默认是空的,一项都不预置。**
 *
 * 曾经预置过倒车/全景影像,理由是"那块屏是拿来看车后面的,Dock 压着是安全问题"。
 * 2026-08-12 全部退掉:「常驻」的语义就是所有页面都显示,倒车影像也是一个页面,
 * 它不该是例外。真要让位的只有原厂桌面那一种,而那条不走名单,是 DockHost 里的状态
 * (见 DockHost.yieldedToOemHome)。
 *
 * 让位是例外,例外该由用户自己加 —— 这个类现在纯粹是那个"自己加"的载体。
 *
 * 判定要靠前台包名,所以整份名单只在无障碍服务启用时才生效(见 ForegroundWatch)。
 */
public final class DockBlacklist {

    private static final String PREFS = "dock_blacklist";
    private static final String KEY = "pkgs";
    /** 退种做过没有。见 {@link #unseedOnce}。 */
    private static final String KEY_UNSEEDED = "unseeded";

    /**
     * **曾经**预置过的包(倒车/全景影像的几种实现)。现在只用来做一次性退种,不再往名单里种。
     *
     * 留着这份清单是因为已装机的车上,这些包名还躺在 prefs 里 —— 光把预置代码删掉,
     * 老用户的倒车让位照旧生效,等于没改。
     *
     * 注意这会连带删掉用户**自己**手动加的同名项(如果真有人手动加过 com.byd.avc)。
     * 只做一次,加回来就不会再动。
     */
    private static final String[] LEGACY_SEED = {
            "com.byd.avm",              // 全景影像(AVM = Around View Monitor)
            "com.byd.auto_avm",
            "com.byd.rearview",         // 倒车影像
            "com.byd.auto_rearview",
            "com.byd.panorama",
            "com.byd.auto_camera",      // 车载摄像头(部分车型倒车走这个)
            "com.byd.avc",              // 倒车/全景影像(AutoVideo)。真车上实际命中的是这个
    };

    private final SharedPreferences prefs;

    public DockBlacklist(Context ctx) {
        this.prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        unseedOnce();
    }

    /**
     * 把当年种下的预置项从名单里清掉,**只做一次**。
     *
     * 只做一次是关键:清完就把标记落下,用户之后想自己把倒车加回名单,不会在下次启动时
     * 被我们又删掉 —— 那就成了跟用户较劲。
     */
    private void unseedOnce() {
        if (prefs.getBoolean(KEY_UNSEEDED, false)) return;
        List<String> list = load();
        if (list.removeAll(Arrays.asList(LEGACY_SEED))) save(list);
        prefs.edit().putBoolean(KEY_UNSEEDED, true).apply();
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

    /**
     * 这个前台包该不该让 Dock 让位。
     *
     * 用 startsWith 而不是相等:同一功能在不同车型上包名常带后缀
     * (com.byd.avm / com.byd.avm.hs 之类),写死全名会漏。
     */
    public boolean blocks(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        for (String b : load()) {
            if (pkg.startsWith(b)) return true;
        }
        return false;
    }

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
