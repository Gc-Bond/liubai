package com.liubai.blank.model;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 底部 Dock 上的一项。Dock 是数据驱动的(DockStore 存 JSON、DockView 按序渲染),
 * 后续做「自定义 Dock」只需增删改这份数据,渲染层不动。
 *
 * HOME  回留白主页(收面板、回第一页)   BACK 系统返回(借提权链发 keyevent,未就绪时点了会提示)
 * TEMP  温度键(「‹ 读数 ›」三格,左右一次一度,中间短按唤空调面板)
 * WIND  风向键(「‹ 图标 ›」三格,左右切吹面/吹脚/吹风挡,中间那个图标就是当前吹哪儿)
 * AC    空调快捷,由 action 区分(fan=空调总开关+风量读数 / recirc / defrost / seat_*)
 * APP   启动指定 app               DRAWER   全部应用      SETTINGS 桌面设置
 * RECENTS 最近任务(系统的后台 app 卡片,借提权链发 APP_SWITCH 键,见 DockHost.onOpenRecents)
 * MAP / MUSIC 语义槽位键,见下
 * RUNNING 运行位 —— 摆的不是某个固定 app,而是**现在开着的那个**,见下
 * EMPTY 空格 —— Dock 是一排定长的格子,没放东西的格就是它
 * SPACER 【已退役】旧的弹性留白。网格化之后没有"弹性"一说了,读老配置时展开成 EMPTY
 * DIVIDER 竖分隔线,把导航/app/车控几个区在视觉上切开
 *
 * **MAP/MUSIC 和 APP 的区别**:APP 绑死一个包名,删了就没了;MAP/MUSIC 绑的是一个
 * *位置*,背后用哪个 app 可以换(长按就地切,见 NavSourceStore/MusicSourceStore)。
 * 它俩还各自对应画中画的一个固定卡位(地图→卡一、音乐→卡二),所以在编辑态里
 * 不可删 —— 删掉等于把那个卡位的入口也删了。
 *
 * **四个应用位**:导航、音乐,加两个 RUNNING,合起来就是"同时最多开四个应用"这条
 * 规矩在 Dock 上的样子。导航和音乐是钉死的两个,另外两个轮换 —— 开第五个应用时,
 * 最久没用的那个从 Dock 上让位(不杀应用,见 DockHost.refreshRunning),位子给新来的。
 * 四个都不可删:格子是应用的容身之处,删了应用就没地方待。
 */
public final class DockItem {

    public enum Kind {
        HOME, BACK, TEMP, AC, APP, MAP, MUSIC, DRAWER, SETTINGS, SPACER, DIVIDER, EMPTY, RUNNING,
        WIND, RECENTS
    }

    /**
     * 这一项在编辑态里能不能删。
     *
     * 设置键是编辑态和设置页的唯一入口;地图/音乐是画中画两个卡位的唯一入口;
     * RUNNING 是运行中应用的位子 —— 这三类删了都回不来,或者让应用无处可去。
     *
     * 空格**可以删**:删它就是让右边那段项往左收一格(加空格的反操作),
     * 见 DockView 里 × 的处理。
     */
    public static boolean removable(Kind k) {
        return k != Kind.SETTINGS && k != Kind.MAP && k != Kind.MUSIC
                && k != Kind.RUNNING;
    }

    /**
     * 这一项占几格。默认一格 —— Dock 是一排 48dp 的方格子。
     *
     * **温度和风向各占两格**:「‹ 读数 ›」「‹ 风向 ›」,左右一次一格。
     * 原来是一格 + 按住滑动,那套撤了(见 DockView.tempButton),调节键就得摆在 Dock 上。
     *
     * 一度是三格(每段各占一整格),太吃地方:一条 Dock 统共 20 格,这两个就去掉 6 格,
     * 加上默认那些键实测占到 21 格、已经超格。改成 2 格后三段按权重分(3:4:3),
     * 见 DockView.childSlot。
     *
     * 扇叶键(AC action=fan)是一格:它是空调总开关兼档位显示,调风量在风向键上 ——
     * 风向和风量本来就该一起调。
     */
    public static int slots(Kind kind, String action) {
        return (kind == Kind.WIND || kind == Kind.TEMP) ? 2 : 1;
    }

    public int slots() { return slots(kind, action); }

    public final Kind kind;
    public final String action;   // kind==AC 时有效:fan / recirc / defrost / seat_*(auto、power 已退役)
    public final AppRef app;      // kind==APP 时有效

    private DockItem(Kind kind, String action, AppRef app) {
        this.kind = kind;
        this.action = action == null ? "" : action;
        this.app = app;
    }

    public static DockItem of(Kind k) { return new DockItem(k, null, null); }
    public static DockItem ac(String action) { return new DockItem(Kind.AC, action, null); }
    public static DockItem app(AppRef a) { return new DockItem(Kind.APP, null, a); }

    /**
     * 绑定了具体 app 的运行位:这一格固定就是它。app 传 null 就是个空着的应用位,
     * 那时它显示当前开着的应用(见 DockView.build)。
     */
    public static DockItem running(AppRef a) { return new DockItem(Kind.RUNNING, null, a); }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("kind", kind.name());
        if (kind == Kind.AC) o.put("action", action);
        if (app != null) o.put("app", app.toJson());   // APP 和绑定过的 RUNNING 都要存
        return o;
    }

    public static DockItem fromJson(JSONObject o) {
        Kind k;
        try {
            k = Kind.valueOf(o.optString("kind"));
        } catch (IllegalArgumentException e) {
            return of(Kind.EMPTY);   // 未知类型(如降级安装)退化为空格,不炸
        }
        if (k == Kind.AC) return ac(o.optString("action"));
        if (k == Kind.APP) {
            JSONObject ao = o.optJSONObject("app");
            return ao != null ? app(AppRef.fromJson(ao)) : of(Kind.EMPTY);
        }
        if (k == Kind.RUNNING) {
            JSONObject ao = o.optJSONObject("app");
            return ao != null ? running(AppRef.fromJson(ao)) : of(Kind.RUNNING);
        }
        return of(k);
    }
}
