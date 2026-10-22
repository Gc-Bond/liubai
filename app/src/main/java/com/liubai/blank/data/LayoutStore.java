package com.liubai.blank.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.liubai.blank.model.AppRef;
import com.liubai.blank.model.DeskItem;
import com.liubai.blank.model.Folder;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 桌面布局持久化(SharedPreferences 存 JSON)。
 * 首次无数据时按已装应用生成默认分组;每次加载对账:剔除已卸载、追加新装。
 */
public final class LayoutStore {

    private static final String PREFS = "desk_layout";
    private static final String KEY = "items";
    private static final int MIN_FOLDER = 2;   // 少于这么多不成夹,直接散落

    // 默认分组的包名特征(contains 匹配)。含护卫舰07 实机包名 + 通用名,便于移植其他车型。
    private static final String[] PRIMARY = {   // 散落桌面的高频 app
            "automap", "autonavi", "amap", "gaode",        // 高德(BYD 定制=com.byd.automap)
            "bluetoothcall", "dialer", "byd.phone",        // 电话(蓝牙电话=com.byd.bluetoothcall)
            "auto_camera", "bydcamera",                    // 相机
            "appstore", "appmarket", "market",             // 应用市场(BYD市场=com.byd.appstore)
            "multiplemode"                                // 百变模式
    };
    private static final String[] MEDIA = {     // 影音
            "kuwo", "kwmusic", "mediacenter",              // 酷我 / 蓝牙音乐(com.byd.mediacenter)
            "kugou", "cloudmusic", "qqmusic", "migu",
            "aweme", "douyin", "bilibili", "iqiyi", "youku", "ximalaya", "qqlive", "video", "music"
    };
    private static final String[] CAR = {       // 车辆(招牌大文件夹,车机专属功能)
            "carsettings", "byd.avc", "cdr", "smarttravel", "bydlogtool", "emanual", "miniprogram",
            "airconditioning", "aircondition", "avm", "panorama", "tire", "tpms", "energy", "charge"
    };
    private static final String[] TOOLS = {     // 工具(浏览器/文件/系统类)
            "quark", "browser", "chrome", "documentsui", "filemanager", "files",
            "weather", "rapidmode", "appstartmanagement", "appmanage",
            "adbhelper", "launchertests", "hicar", "calculator", "deskclock", "calendar"
    };

    private final SharedPreferences prefs;

    public LayoutStore(Context ctx) {
        this.prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---- 读 ----

    public List<DeskItem> load(AppRepository repo) {
        List<DeskItem> items = readStored();
        if (items == null) {
            items = buildDefault(repo);
            save(items);
            return items;
        }
        reconcile(items, repo);
        save(items);
        return items;
    }

    private List<DeskItem> readStored() {
        String raw = prefs.getString(KEY, null);
        if (raw == null || raw.isEmpty()) return null;
        try {
            JSONArray arr = new JSONArray(raw);
            List<DeskItem> items = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) items.add(DeskItem.fromJson(o));
            }
            return items.isEmpty() ? null : items;
        } catch (JSONException e) {
            return null;
        }
    }

    // ---- 写 ----

    public void save(List<DeskItem> items) {
        try {
            JSONArray arr = new JSONArray();
            for (DeskItem it : items) arr.put(it.toJson());
            prefs.edit().putString(KEY, arr.toString()).apply();
        } catch (JSONException ignored) { }
    }

    /** 添加 app 到桌面末尾(按组件去重)。返回是否真的添加。 */
    public boolean addApp(List<DeskItem> items, AppRef app) {
        for (DeskItem d : items) {
            if (d.kind == DeskItem.Kind.APP && d.app.sameComponent(app)) return false;
            if (d.kind == DeskItem.Kind.FOLDER) {
                for (AppRef a : d.folder.apps) if (a.sameComponent(app)) return false;
            }
        }
        items.add(DeskItem.app(app));
        save(items);
        return true;
    }

    // ---- 对账:剔除已卸载、追加新装 ----

    private void reconcile(List<DeskItem> items, AppRepository repo) {
        // 剔除已卸载
        Iterator<DeskItem> it = items.iterator();
        while (it.hasNext()) {
            DeskItem d = it.next();
            if (d.kind == DeskItem.Kind.APP) {
                if (!repo.isInstalled(d.app.pkg)) it.remove();
            } else {
                Iterator<AppRef> ai = d.folder.apps.iterator();
                while (ai.hasNext()) {
                    if (!repo.isInstalled(ai.next().pkg)) ai.remove();
                }
                if (d.folder.apps.isEmpty()) it.remove();
            }
        }

        // 补进新装的:桌面就是全量,不该出现「装了却找不到」的 app
        java.util.Set<String> have = new java.util.HashSet<>();
        for (DeskItem d : items) {
            if (d.kind == DeskItem.Kind.APP) have.add(d.app.pkg);
            else for (AppRef a : d.folder.apps) have.add(a.pkg);
        }
        for (AppRef a : repo.queryLaunchable()) {
            if (!have.contains(a.pkg)) placeNew(items, a);
        }
    }

    /** 新装的 app 按建默认布局那套规则归位:对得上哪类就进哪个夹,都对不上进「其他」。 */
    private void placeNew(List<DeskItem> items, AppRef app) {
        String p = app.pkg.toLowerCase();
        if (matches(p, PRIMARY)) {          // 高频的照旧散落在外
            items.add(DeskItem.app(app));
            return;
        }
        String name = matches(p, MEDIA) ? "影音"
                : matches(p, CAR) ? "车辆"
                : matches(p, TOOLS) ? "工具" : "其他";
        for (DeskItem d : items) {
            if (d.kind == DeskItem.Kind.FOLDER && name.equals(d.folder.name)) {
                d.folder.apps.add(app);
                return;
            }
        }
        Folder f = new Folder(name, false);   // 该类的夹还不存在就现建一个
        f.apps.add(app);
        items.add(DeskItem.folder(f));
    }

    private boolean matches(String pkg, String[] tokens) {
        for (String t : tokens) if (pkg.contains(t)) return true;
        return false;
    }

    // ---- 默认布局 ----

    public List<DeskItem> buildDefault(AppRepository repo) {
        // 桌面放**全部**已装应用,一个不漏:高频的散落在外,其余按类进文件夹。
        // 车机上带启动图标的 app 本来就不多(实机 140 个包里只有 24 个有图标),
        // 全量铺开也就十来个条目,正好一屏——不必再让用户去别处找 app。
        List<AppRef> pool = new ArrayList<>(repo.queryLaunchable());
        List<DeskItem> items = new ArrayList<>();

        // 每类都全收,不设上限:限了数量的话超出的会漏到「其他」里,
        // 明明是音乐 app 却出现在杂物夹,比夹子长一点更让人困惑。
        List<AppRef> primaries = pull(pool, PRIMARY, Integer.MAX_VALUE);
        List<AppRef> media = pull(pool, MEDIA, Integer.MAX_VALUE);
        List<AppRef> car = pull(pool, CAR, Integer.MAX_VALUE);
        List<AppRef> tools = pull(pool, TOOLS, Integer.MAX_VALUE);

        for (AppRef a : primaries) items.add(DeskItem.app(a));
        addFolder(items, "影音", true, media);
        addFolder(items, "车辆", false, car);
        addFolder(items, "工具", false, tools);
        addFolder(items, "其他", false, pool);   // 剩下的兜底收进来,保证没有 app 掉队
        return items;
    }

    /** 若 apps 够数则加成文件夹并返回 1,否则把它们退回 items 尾部散落并返回 0。 */
    private int addFolder(List<DeskItem> items, String name, boolean big, List<AppRef> apps) {
        if (apps.size() >= MIN_FOLDER) {
            Folder f = new Folder(name, big);
            f.apps.addAll(apps);
            items.add(DeskItem.folder(f));
            return 1;
        }
        for (AppRef a : apps) items.add(DeskItem.app(a));
        return 0;
    }

    /** 从 pool 中拉出匹配任一 token 的 app(最多 cap 个),并从 pool 移除。 */
    private List<AppRef> pull(List<AppRef> pool, String[] tokens, int cap) {
        List<AppRef> out = new ArrayList<>();
        Iterator<AppRef> it = pool.iterator();
        while (it.hasNext() && out.size() < cap) {
            AppRef a = it.next();
            String p = a.pkg.toLowerCase();
            for (String t : tokens) {
                if (p.contains(t)) { out.add(a); it.remove(); break; }
            }
        }
        return out;
    }
}
