package com.liubai.blank.data;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 桌面主页层上摆了哪些卡片、各自在哪儿。
 *
 * 位置**按卡片分键**存(`pos_x_<id>`),而且存的是**比例**(0~1)不是 px ——
 * 车机分辨率不止一种(1920×1080 横屏、1520×1080 竖屏都有),存 px 换台车就飞到画面外。
 * 比例的含义见 {@link com.liubai.blank.ui.DeskCard#applyPos}。
 *
 * 显隐也按卡片分键。这一点和迪友一样(它每张卡一个 {@code _CARD_OPEN_STATE},
 * 反编译 3.0.1),但它的**位置**是一串 `;` 分隔的顺序、落进固定槽位,留白这边是自由坐标。
 */
public final class DeskCardStore {

    // ---- 卡片 id。**存储用的稳定标识,只能追加不能改名** ----
    /** 车控卡:座椅/车窗/灯这些。 */
    public static final String CAR_CTRL = "car_ctrl";
    /** 时间日期卡。 */
    public static final String CLOCK = "clock";
    /** 车况卡:续航/保养里程/胎压,只读。 */
    /** 游戏中心卡:点了进游戏中心的列表页。 */
    public static final String GAME = "game";

    /**
     * 卡片名录:桌面上**能摆哪些卡**。设置面板照着它列(见 AppearancePopup 的卡片 tab),
     * {@code DeskCardHost.setup} 照着 id 挂。
     *
     * **新加一种卡要在这里登记一条**,否则设置里看不见它、也就没法开关。
     */
    public static final class Kind {
        public final String id;
        public final String title;
        /** 一句话说明。写清楚"这张卡靠什么活着",省得车主对着空卡片猜。 */
        public final String desc;

        Kind(String id, String title, String desc) {
            this.id = id; this.title = title; this.desc = desc;
        }
    }

    private static final Kind[] KINDS = {
            new Kind(CAR_CTRL, "车控", "座椅、车窗、灯……项可以自己挑"),
            new Kind(CLOCK, "时间", "时间和日期"),
            new Kind(GAME, "游戏中心", "红白机、街机、GBA，手机当手柄"),
    };

    /** 全部卡片种类,按声明顺序。 */
    public static Kind[] kinds() { return KINDS; }

    private static final String PREFS = "desk_cards";
    private static final String P_X = "pos_x_";
    private static final String P_Y = "pos_y_";
    private static final String P_ON = "on_";
    private static final String P_OPACITY = "card_opacity";

    /**
     * 桌面卡片底的不透明度(0~100),默认 80。
     *
     * **和面板、Dock 各存各的**:卡片浮在壁纸上,透了看到的是画;面板背后是桌面内容,
     * Dock 背后又是壁纸的下沿 —— 同一档在三处的观感差得远(同 ModeManager.panelOpacity
     * 那段说明)。
     *
     * <h3>为什么从 55 提到 80</h3>
     * 55% 时花壁纸直接透上来,而关着的车控卡图标和文字都是 **60% 白**
     * (`glyph`/`muted`)—— 细笔画糊在画上,真车实测"看不太清"。提底而不是提字色,
     * 是为了保住"亮的=开着、灰的=关着"那层意思:把关着的也提到 86% 白之后,
     * 开关就只剩蓝白之分,扫一眼分不出哪几个开着。
     *
     * 顺带:55 **不在** AppearancePopup.OPACITIES 那十档里,所以面板上一档都不高亮
     * —— 默认值必须落在可选档位上。
     *
     * 走静态方法:{@link com.liubai.blank.ui.DeskCard} 是个纯 view,不该为了读一个数
     * 去持有 store(照 DockHost.setOpacity 那套)。
     */
    public static int opacity(Context ctx) {
        return prefs(ctx).getInt(P_OPACITY, 80);
    }

    public static void setOpacity(Context ctx, int percent) {
        prefs(ctx).edit().putInt(P_OPACITY, Math.max(0, Math.min(100, percent))).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private final SharedPreferences prefs;

    public DeskCardStore(Context ctx) {
        this.prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * 这张卡显不显示。默认显示 —— 装了留白就该看见东西,
     * 空白主页对第一次开机的人来说和"坏了"没区别。
     */
    public boolean visible(String id) {
        return prefs.getBoolean(P_ON + id, true);
    }

    public void setVisible(String id, boolean on) {
        prefs.edit().putBoolean(P_ON + id, on).apply();
    }

    /** 摆过没有。没摆过就用卡片自己的初始位置,别把 0,0 当成"用户特意摆在最左上"。 */
    public boolean hasPos(String id) {
        return prefs.contains(P_X + id);
    }

    public float posX(String id) { return prefs.getFloat(P_X + id, 0f); }
    public float posY(String id) { return prefs.getFloat(P_Y + id, 0f); }

    public void setPos(String id, float x, float y) {
        prefs.edit().putFloat(P_X + id, x).putFloat(P_Y + id, y).apply();
    }
}
