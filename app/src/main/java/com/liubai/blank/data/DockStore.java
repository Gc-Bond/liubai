package com.liubai.blank.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.liubai.blank.model.DockGrid;
import com.liubai.blank.model.DockItem;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 底部 Dock 配置持久化(SharedPreferences 存 JSON),与 LayoutStore 同构。
 * 首次无数据时落默认配置;后续「自定义 Dock」改的就是这份数据。
 */
public final class DockStore {

    private static final String PREFS = "dock_layout";
    private static final String KEY = "items";
    private static final String KEY_VER = "ver";
    /** 用户在编辑面板里动过 Dock。置位后版本升级不再覆盖他排好的顺序。 */
    private static final String KEY_DIRTY = "user_edited";

    /**
     * 默认配置的版本。**每次改 buildDefault 就要 +1**,否则老设备上存着的旧配置
     * 永远不会更新(load 只在没数据时才落默认),新加的按钮谁也看不见。
     *
     * 升级只对**没动过 Dock 的人**生效:动过的按 KEY_DIRTY 跳过整体覆盖,改用
     * ensureRequired 单独把新加的必备键补进去(见 load)。早先这里的注释说
     * "等编辑界面做出来再改成 dirty 标记",编辑界面已经有了,这就是那次改。
     */
    private static final int VER = 18;

    private final SharedPreferences prefs;
    private final Context ctx;

    public DockStore(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.prefs = this.ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public List<DockItem> load() {
        String raw = prefs.getString(KEY, null);
        boolean userEdited = prefs.getBoolean(KEY_DIRTY, false);
        // 版本落后时:用户没动过就整体换成新默认;动过就保留他的排法,只补必备键。
        boolean usable = raw != null && !raw.isEmpty()
                && (prefs.getInt(KEY_VER, 1) >= VER || userEdited);
        if (usable) {
            try {
                JSONArray arr = new JSONArray(raw);
                List<DockItem> items = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null) items.add(DockItem.fromJson(o));
                }
                if (!items.isEmpty()) {
                    boolean changed = ensureRequired(items);
                    // 网格化:老配置里的弹性留白展开成空格,并把总格数对齐到这块屏的容量。
                    // 这一步**对谁都要做**(包括自己排过 Dock 的人)—— 模型变了,不转就渲染不出来。
                    if (toGrid(items)) changed = true;
                    if (changed) save(items);
                    return items;
                }
            } catch (JSONException ignored) { /* 落到默认 */ }
        }
        List<DockItem> def = buildDefault(ctx);
        save(def);
        return def;
    }

    /**
     * 把"少了就回不去"的键补回来,返回是否补过。
     *
     * 设置键:编辑态和设置页的唯一入口,早期版本允许删掉,存量配置里可能没有。
     * 地图/音乐键:它们同时是画中画两个卡位的入口,VER=8 才加进默认配置——
     * 已经自定义过 Dock 的用户不会走整体覆盖,只能靠这里补。
     * 空格与格数对齐在 {@link #toGrid} 里单独做,不在这儿 —— 那是模型迁移,不是"补必备键"。
     */
    private boolean ensureRequired(List<DockItem> items) {
        boolean changed = false;
        if (!has(items, DockItem.Kind.SETTINGS)) {
            items.add(DockItem.of(DockItem.Kind.SETTINGS));
            changed = true;
        }
        // 两个运行位:VER=11 才有("同时最多四个应用"落到 Dock 上)。存量配置里没有,
        // 补在音乐键后面 —— 四个应用位挨在一起才看得出是一组
        int runs = count(items, DockItem.Kind.RUNNING);
        if (runs < 2) {
            int after = indexOf(items, DockItem.Kind.MUSIC);
            if (after < 0) after = indexOf(items, DockItem.Kind.MAP);
            int at2 = after >= 0 ? after + 1 : items.size();
            for (int i = runs; i < 2; i++) items.add(at2, DockItem.of(DockItem.Kind.RUNNING));
            changed = true;
        }

        // 补在设置键之前 —— 全部应用键 VER=17 起摆在最左边,不能再拿它当锚点了
        int at = indexOf(items, DockItem.Kind.SETTINGS);
        if (at < 0) at = items.size();
        if (!has(items, DockItem.Kind.MUSIC)) {
            items.add(at, DockItem.of(DockItem.Kind.MUSIC));
            changed = true;
        }
        if (!has(items, DockItem.Kind.MAP)) {
            items.add(at, DockItem.of(DockItem.Kind.MAP));
            changed = true;
        }
        return changed;
    }

    /**
     * 把配置搬到网格模型上,返回是否动过。做两件事:
     *
     *   ① 退役的弹性留白({@link DockItem.Kind#SPACER})换成空格。一块留白当年吃掉的是
     *      "剩余空间",没有确定格数,所以就地换成 **1 格** 空格,少的格由 ② 补齐;
     *   ② 格数不够这块屏的容量时补空格(多了**不删**,理由见下)。
     *
     * 补挑**最长的一段连续空格**下手(通常就是中间那片),这样两端的项还钉在两端:
     * 导航键在左下角、设置键在右下角是盲操的锚点,不能因为补格子把它们挤走。
     */
    private boolean toGrid(List<DockItem> items) {
        boolean changed = false;
        for (int i = 0; i < items.size(); i++) {
            DockItem it = items.get(i);
            // 退役的弹性留白 → 空格;撤掉的 AUTO 键、电源键也 → 空格(留个空位,别让右边的项左移)。
            // 不换的话它们会掉进 acButton 最后那个 else,顶着除雾的字形显示出来。
            // 电源键 2026-08-14 退役:空调开关归扇叶键,电源图标不要了(见 DockView.acButton)。
            boolean retired = it.kind == DockItem.Kind.SPACER
                    || (it.kind == DockItem.Kind.AC
                        && ("auto".equals(it.action) || "power".equals(it.action)));
            if (retired) {
                items.set(i, DockItem.of(DockItem.Kind.EMPTY));
                changed = true;
            }
        }

        int cap = DockGrid.capacity(ctx);
        int used = DockGrid.used(items);
        while (used < cap) {                       // 缺格:补在最长的那段空白里
            items.add(longestGapEnd(items), DockItem.of(DockItem.Kind.EMPTY));
            used++;
            changed = true;
        }
        /*
         * 格数**超了不删**。窄屏(竖屏车机、可旋转屏转过去)摆不下的那几格,由渲染层
         * 临时跳过、只让中间的项缺席(见 DockView.overflowSkips),配置一个字不动 ——
         * 转回宽屏立刻原样回来。
         *
         * 早先在这儿删空格顶账,后果是"转一次屏,横屏上排好的空格分布就被永久改写了"。
         * 那在车上没法解释:用户什么都没做,只是屏幕转了一下。
         */
        return changed;
    }

    /**
     * 最长一段连续空格的**末尾下标**(插在这里等于往那段空白里再加一格)。
     * 一个空格都没有就返回中点 —— 总得有个落脚处,中间最不碍事。
     */
    private static int longestGapEnd(List<DockItem> items) {
        int best = -1, bestLen = 0, run = 0;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).kind == DockItem.Kind.EMPTY) {
                run++;
                if (run > bestLen) { bestLen = run; best = i + 1; }
            } else {
                run = 0;
            }
        }
        return best >= 0 ? best : items.size() / 2;
    }

    private static boolean has(List<DockItem> items, DockItem.Kind k) {
        return indexOf(items, k) >= 0;
    }

    private static int count(List<DockItem> items, DockItem.Kind k) {
        int n = 0;
        for (DockItem it : items) if (it.kind == k) n++;
        return n;
    }

    private static int indexOf(List<DockItem> items, DockItem.Kind k) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).kind == k) return i;
        }
        return -1;
    }

    /** 用户在编辑面板里改过 Dock:之后的版本升级不再覆盖他的排法。 */
    public void markUserEdited() {
        prefs.edit().putBoolean(KEY_DIRTY, true).apply();
    }

    public void save(List<DockItem> items) {
        try {
            JSONArray arr = new JSONArray();
            for (DockItem it : items) arr.put(it.toJson());
            prefs.edit().putString(KEY, arr.toString()).putInt(KEY_VER, VER).apply();
        } catch (JSONException ignored) { }
    }

    /** 恢复默认(自定义界面的「重置」用)。 */
    public List<DockItem> reset() {
        List<DockItem> def = buildDefault(ctx);
        save(def);
        return def;
    }

    /**
     * 清空(自定义界面的「清空」用):能删的全变成空格,从头摆起。
     *
     * 删不掉的那几个原地不动 —— 设置键是编辑态和设置页的唯一入口,地图/音乐是画中画
     * 两个卡位的唯一入口,清掉就回不来了(同 {@link DockItem#removable})。
     *
     * 一项腾出的空格数按它原本占的格数算(温度那种宽项留 2 格),总格数分毫不变,
     * 所以留下的键**位置不动**,右下角那组还钉在右下角。
     *
     * 记为"用户排过":这是他主动清的,别在下次版本升级时又给他填回默认那一套。
     */
    public List<DockItem> clear() {
        List<DockItem> out = new ArrayList<>();
        for (DockItem it : load()) {
            if (!DockItem.removable(it.kind)) {
                out.add(it);
                continue;
            }
            for (int i = 0; i < it.slots(); i++) out.add(DockItem.of(DockItem.Kind.EMPTY));
        }
        save(out);
        markUserEdited();
        return out;
    }

    /**
     * 默认布局。整条读下来是:
     *
     *   返回 主页 全部应用 任务 · 空调开关 风向 主驾通风 · 地图 音乐 运行 运行 · 副驾通风 温度 循环 · 设置
     *
     * 几条定下来的规矩:
     *
     * · **座椅按人坐的位置分左右** —— 主驾通风在左半边、副驾通风在右半边,和你伸手
     *   去够的方向对得上,不用先认图标再想是哪一侧。
     * · **温度和循环挪到右半边**:左手边留给最常动的那三样(开关、风向、主驾座椅),
     *   温度靠右反而顺手 —— 副驾也够得着。
     * · **组与组之间空一格** 而不是画分隔线:迪友的导航栏里一条线都没有,全靠间距疏密
     *   分组(组内 8dp、组间 20~28dp,逆向自 navigation_bar_default_view)。这边一个空格
     *   = 48dp 格身 + 两侧 13dp,和常规间距比是 5.7 倍,比一条竖线干净。
     * · **空调开关和除雾不进默认**:低频,而且开关键一旦误触整个空调就停了。要的人
     *   从「自定义 Dock」自己加。
     * · **默认不摆普通 app**:常驻哪个 app 各人各样,预置一排"我猜你常用"占着位置,
     *   多数人还是要删。
     *
     * 地图和音乐不是"某个 app",是两个**位置**:画中画的两个卡位就是给它们留的,
     * 背后用哪个 app 长按就能换。少了这两个键,那两个卡位就没有入口了。
     * 后面两个运行位摆的是"现在还开着的另外两个应用",合起来就是"同时最多四个应用"
     * 这条规矩的样子(开第五个时最久没用的那个从 Dock 上让位,不杀应用,见
     * DockHost.refreshRunning)。
     */
    public static List<DockItem> buildDefault(Context ctx) {
        List<DockItem> items = new ArrayList<>();

        // 最左:导航键 + 全部应用 + 任务(VER=18 起;最近任务原来只在全部应用的长按里)
        items.add(DockItem.of(DockItem.Kind.BACK));
        items.add(DockItem.of(DockItem.Kind.HOME));
        items.add(DockItem.of(DockItem.Kind.DRAWER));
        items.add(DockItem.of(DockItem.Kind.RECENTS));
        items.add(DockItem.of(DockItem.Kind.EMPTY));

        // 车控左半:空调开关(扇叶键)、风向+风量、主驾座椅通风
        //
        // 扇叶键 2026-08-14 改成**空调总开关**:调风量的活风向键那三格全包了
        // (左右一次一档,底下七格档位条也在那儿),扇叶原来只剩"显示档位 + 点开面板",
        // 和风向键完全重复;而空调总开关一直没有默认位置。用扇叶而不是电源图标 ——
        // 电源图标在车机上更像"关机",扇叶一眼就是空调。
        items.add(DockItem.ac("fan"));
        items.add(DockItem.of(DockItem.Kind.WIND));   // 风向+风量:‹ 吹面/吹脚/吹风挡 ›
        items.add(DockItem.ac("seat_vent_l"));

        // 四个应用位。**运行位不绑具体 app** —— 绑了就成了"这台机器上的某个应用",
        // 出厂默认给不了这种前提;不绑的时候它显示的是"现在还开着的另外两个"
        items.add(DockItem.of(DockItem.Kind.MAP));
        items.add(DockItem.of(DockItem.Kind.MUSIC));
        items.add(DockItem.of(DockItem.Kind.RUNNING));
        items.add(DockItem.of(DockItem.Kind.RUNNING));

        // 车控右半:副驾座椅通风(见上面"按人坐的位置分左右")、温度、循环
        items.add(DockItem.ac("seat_vent_r"));
        items.add(DockItem.of(DockItem.Kind.TEMP));   // 温度:‹ 26℃ ›
        items.add(DockItem.ac("recirc"));

        // 右下角那个入口。空格由下面统一填在中间 —— Dock 铺满整条格子,
        // 末格自然贴着屏幕右缘,设置键就钉在右下角了(盲操摸得到)
        List<DockItem> tail = new ArrayList<>();
        tail.add(DockItem.of(DockItem.Kind.SETTINGS));

        // 中间用空格填够。运行区的 app 开起来时就摆进这些空格里(见 DockView.build),
        // 所以这片空白不是浪费,是留给"现在开着的东西"的地方
        int blanks = DockGrid.capacity(ctx) - DockGrid.used(items) - DockGrid.used(tail);
        for (int i = 0; i < blanks; i++) items.add(DockItem.of(DockItem.Kind.EMPTY));
        items.addAll(tail);
        return items;
    }

}
