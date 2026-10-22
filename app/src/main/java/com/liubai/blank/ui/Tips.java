package com.liubai.blank.ui;

import com.liubai.blank.mode.Capabilities;
import com.liubai.blank.ui.GestureGlyph.Effect;
import com.liubai.blank.ui.GestureGlyph.Kind;
import com.liubai.blank.ui.GestureGlyph.Scene;

import java.util.ArrayList;
import java.util.List;

/**
 * 玩机技巧目录:留白里那些**不摸索找不到**的操作(长按、多指、按住再滑)。
 * 设置面板的「技巧」页照这张表列给用户看,右边用 {@link GestureGlyph} 演示。
 *
 * <h3>以代码为准,不照文档抄</h3>
 * 每条对应一个具体的 handler(注释里写了在哪),加一条先去核那一侧真有这个行为。
 * 技巧页最怕的就是写一条用户照做没反应的手势(2026-09-12 盘点时就撞到两条
 * 注释和代码对不上的:循环键其实没有长按;单指上滑收的是卡片不是两条栏)。
 *
 * <h3>每条三样东西</h3>
 * 手势(几根手指、什么动作、往哪儿)、落点(场景里的哪个位置)、结果(做完场景里出什么),
 * 演示照这三样画。文案「手势:结果」两段,只给事实,不写「试试看」「更方便」这类安抚句。
 *
 * <h3>按模式过滤</h3>
 * 模式不给画中画(pipSlots=0)时,双指收展画中画、握把那两条是空操作,
 * 不列 —— 列了等于告诉用户一个不存在的手势。
 */
public final class Tips {

    public static final class Tip {
        public final String group, action, result;
        public final Kind kind;
        public final int fingers;
        public final float dx, dy;
        public final Scene scene;
        public final int target;
        public final Effect effect;
        final boolean needsPip;

        Tip(String group, String action, String result, Kind kind, int fingers, float dx, float dy,
            Scene scene, int target, Effect effect, boolean needsPip) {
            this.group = group; this.action = action; this.result = result;
            this.kind = kind; this.fingers = fingers; this.dx = dx; this.dy = dy;
            this.scene = scene; this.target = target; this.effect = effect;
            this.needsPip = needsPip;
        }
    }

    private static final String DESK = "桌面", PIP = "画中画", DOCK = "Dock",
            DRAWER = "应用抽屉", PANEL = "设置面板";

    private static Tip swipe(String g, String a, String r, int fingers, float dx, float dy,
                             Scene scene, Effect fx, boolean pip) {
        return new Tip(g, a, r, Kind.SWIPE, fingers, dx, dy, scene, GestureGlyph.T_BLANK, fx, pip);
    }
    private static Tip hold(String g, String a, String r, Scene scene, int target, Effect fx) {
        return new Tip(g, a, r, Kind.HOLD, 1, 0, 0, scene, target, fx, false);
    }
    private static Tip tap(String g, String a, String r, int target, Effect fx) {
        return new Tip(g, a, r, Kind.TAP, 1, 0, 0, Scene.DESK, target, fx, false);
    }

    /** 手势都挂在 HomeActivity.dispatchTouchEvent 那几个 *Gesture / *Swiper 上,Dock 的在 DockView.itemView。 */
    private static final Tip[] ALL = {
            // 桌面(WallpaperSwiper / BarSwipeGesture / TwoFingerPageGesture / ThreeFingerHvacGesture / DesktopView / DeskCardHost)
            swipe(DESK, "单指左右滑", "切换壁纸", 1, 1, 0, Scene.DESK, Effect.WALLPAPER, false),
            swipe(DESK, "单指上滑", "收起桌面卡片,只留壁纸;下滑放回", 1, 0, -1, Scene.DESK, Effect.CARDS_HIDE, false),
            new Tip(DESK, "双指捏合", "进入桌面编辑:拖动卡片、点 × 移除、右侧候选区添加;双指张开退出",
                    Kind.PINCH_IN, 2, 0, 0, Scene.DESK, GestureGlyph.T_BLANK, Effect.EDIT, false),
            swipe(DESK, "双指上下滑", "收起 / 展开画中画层", 2, 0, -1, Scene.PIP, Effect.PIP_HIDE, true),
            swipe(DESK, "三指上下滑", "调空调温度,上滑升温", 3, 0, -1, Scene.DESK, Effect.TEMP, false),
            swipe(DESK, "三指左右滑", "调风量,右滑加大", 3, 1, 0, Scene.DESK, Effect.FAN, false),
            hold(DESK, "长按空白处", "打开设置面板", Scene.DESK, GestureGlyph.T_BLANK, Effect.SHEET),
            hold(DESK, "长按 WLAN / 蓝牙 / 热点卡", "打开对应的系统设置页", Scene.DESK, GestureGlyph.item(1), Effect.LIST),

            // 画中画(ResizeHandle)
            new Tip(PIP, "拖动两卡之间的握把", "改两张卡的分配比例,松手吸附到档位;点一下直接切到下一档",
                    Kind.DRAG, 1, 1, 0, Scene.PIP, GestureGlyph.T_HANDLE, Effect.NONE, true),

            // Dock(DockView.itemView 各 case;应用位是 bindSlotSlide)
            hold(DOCK, "长按主页键", "切回原车桌面", Scene.DESK, GestureGlyph.key(1), Effect.GRID),
            hold(DOCK, "长按设置键", "就地自定义 Dock:增删、拖动排序", Scene.DESK, GestureGlyph.key(6), Effect.DOCK_EDIT),
            hold(DOCK, "长按全部应用", "打开最近任务,和任务键一样", Scene.DESK, GestureGlyph.key(2), Effect.RECENTS),
            new Tip(DOCK, "按住地图 / 音乐 / 应用位再左右滑", "换这一格用哪个 app,候选是自定义 Dock 里加的那几个",
                    Kind.HOLD_SLIDE, 1, 1, 0, Scene.DESK, GestureGlyph.key(4), Effect.SLOT, false),
            tap(DOCK, "点温度键 / 风向键中间", "打开空调全量面板", GestureGlyph.key(5), Effect.SHEET),
            tap(DOCK, "点温度 / 风向两侧的箭头", "每点一下动一格", GestureGlyph.T_TEMP_RIGHT, Effect.TEMP),
            tap(DOCK, "点扇叶键", "空调总开关", GestureGlyph.key(3), Effect.KEY_ON),

            // 应用抽屉(AppDrawerActivity)
            hold(DRAWER, "长按应用", "应用信息 / 卸载", Scene.DRAWER, GestureGlyph.item(2), Effect.MENU),

            // 设置面板(AppearancePopup 的名单)
            hold(PANEL, "长按壁纸或来源名单里的项", "移除", Scene.PANEL, GestureGlyph.item(1), Effect.REMOVE),
    };

    /** 这个模式下该列出来的技巧,按声明顺序(同组挨在一起)。 */
    public static List<Tip> forMode(Capabilities caps) {
        boolean pip = caps.pipSlots > 0;
        List<Tip> out = new ArrayList<>();
        for (Tip t : ALL) if (pip || !t.needsPip) out.add(t);
        return out;
    }

    private Tips() { }
}
