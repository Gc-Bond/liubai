package com.liubai.blank.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.util.Log;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.liubai.blank.R;
import com.liubai.blank.icon.IconEngine;
import com.liubai.blank.data.AppTypes;
import com.liubai.blank.data.NavSourceStore;
import com.liubai.blank.data.RunSourceStore;
import com.liubai.blank.media.MusicSourceStore;
import com.liubai.blank.model.AppRef;
import com.liubai.blank.model.DockGrid;
import com.liubai.blank.model.DockItem;
import com.liubai.blank.privileged.AirCon;
import com.liubai.blank.mode.ModeApplier;
import com.liubai.blank.mode.Dim;
import com.liubai.blank.mode.ModeManager;
import com.liubai.blank.ui.fx.SnowDrift;
import com.liubai.blank.util.Launchers;

import java.util.ArrayList;
import java.util.List;

/**
 * 底部 Dock:按 DockStore 的数据渲染,不写死布局——后续「自定义 Dock」只改数据。
 *
 * 形态对齐原型(hvac-dock.html):居中贴底坞,按键一律是"幽灵键"——不带底色、不带边框。
 * 状态只用**字形颜色**表达:这个功能开着 = accent 青,没开 = muted 灰;
 * 温度是例外,它走冷暖数据色(读数本身就是状态)。温度/风量是定宽读数胶囊。
 * 所有状态变化走动画(弹簧按压 / 颜色渐变 / 数值 pop / 风扇变速),参数集中在 {@link Motion}。
 *
 * 跟手性:滑动调节和开关点按都先改**乐观值**立即上屏,车机回读(几百毫秒后)只做校正
 * —— 等回读再刷 UI 的话,真车上每一下都要顿一秒,那就谈不上丝滑了。
 */
public class DockView extends LinearLayout {

    public interface Listener {
        /** 温度调节,delta 以 **1℃** 为一格(长按滑动可能一次给多格)。 */
        void onTempChange(int delta);
        /** 风量调节:level 是**绝对档位** 0~7,不是增量;0 = 关空调。 */
        void onFanLevel(int level);
        void onAcAction(String action);      // 空调快捷:fan/auto/recirc/defrost/power
        /** 唤出空调全量面板(长按循环键)。 */
        void onOpenHvacPanel();
        void onLaunch(AppRef app);
        /** 点运行区里的 app:**切回去**,不是重新启动(见 DockHost.onSwitchTo)。 */
        void onSwitchTo(AppRef app);
        /** 短按地图/音乐键:打开对应的画中画卡位,已经开着就把画中画层唤回来。 */
        void onOpenSlot(DockItem.Kind kind);
        /** 长按地图/音乐键:就地换这一类用哪个 app。(已被滑动选择取代,留作兜底入口) */
        void onPickSlotSource(DockItem.Kind kind);
        /** 长按地图/音乐键滑动选定了某个 app:换这一类的源,并把卡位切过去。 */
        void onSlotSourcePicked(DockItem.Kind kind, String pkg);
        /** 长按运行位滑动选定了某个 app:这一格固定显示它(slot = 第几个运行位,0/1)。 */
        void onRunningSlotPicked(int slot, String pkg);
        void onOpenDrawer();
        /** 任务键短按(或长按"全部应用"):弹系统最近任务(所有后台 app 的卡片)。 */
        void onOpenRecents();
        void onOpenSettings();
        /** 长按设置键:就地唤出「自定义 Dock」面板。 */
        void onEditDock();
        /** 编辑模式下 Dock 内容变了(增删/排序),存起来。 */
        void onDockEdited(List<DockItem> items);
        /** 回留白主页:收起面板、回到第一页。 */
        void onGoHome();
        /** 长按主页键:切到原车桌面(留白把自己设成默认桌面后,总得留条回去的路)。 */
        void onGoOriginalHome();
        /** 系统返回。走提权链发 keyevent —— 普通 app 注入不了返回事件。 */
        void onGoBack();
        /** 提权是否就绪。没就绪就不渲染返回键,不摆一个按不动的死键。 */
    }

    private IconEngine icons;
    private Listener listener;
    private List<DockItem> items = new ArrayList<>();
    /**
     * 运行区:当前跑着的 app,**按槽位排**。动态内容,不存盘(见 bindRunning)。
     *
     * 下标就是应用位的位置,元素可以是 null —— 那一格现在空着。顺序由 DockHost 钉住
     * (见 DockHost.refreshRunning),这边只管照着摆。
     */
    private List<AppRef> running = new ArrayList<>();

    // ---- 车控读数与开关态 ----
    private String temp;
    private int fan = -1;
    /** 开关态只有全量读之后才可信(hasToggles);之前电源按"开"处理,不瞎灰化。 */
    private boolean power = true;
    private boolean auto, defrost, recirc, compressor;
    /**
     * 风向:吹面/吹脚/吹风挡。车机上是三个各自独立的开关,可以同时开 ——
     * 七种合法组合各有一个字形,{@link #windModeIcon()} 按这三个值挑。
     */
    private boolean windFace, windFoot, windDef;
    private boolean hasToggles;

    /**
     * 座椅加热/通风档位,0=关 / 1/2=档 / -1=读不到或没装配。
     *
     * 这几个键早先**压根没接状态**:当时 {@link AirCon.State} 里还没有座椅字段,
     * 于是 seatButton 干脆不挂 HvacKey、一直是常态灰。08-02 加了回读、面板那侧
     * 早就在用了,Dock 这侧一直没跟上 —— 表现就是"座椅开着 Dock 也不亮"。
     */
    private int seatHeatL = -1, seatVentL = -1, seatHeatR = -1, seatVentR = -1;
    /** 座椅键按 action 索引(四颗各自独立,且是否摆出来由用户配)。build() 重建时要清。 */
    private final java.util.Map<String, HvacKey> seatKeys = new java.util.HashMap<>();
    /** 拖动候选项时高亮的那一格,-1 = 没有。 */
    private int dropHint = -1;
    /** 座椅键底下那两格档位条,同上按 action 索引。 */
    private final java.util.Map<String, StepBars> seatBars = new java.util.HashMap<>();

    /** 调节中的乐观值:手指动了立刻上屏,回读只做校正。NaN/-1 表示没在用。 */
    private float adjTemp = Float.NaN;
    private int adjFan = -1;
    private boolean slideActive;

    private HvacBubble bubble;
    /*
     * ---- 键内留白:图标看起来多大,由这三个值决定 ----
     *
     * 格子恒定 48dp(DockGrid.SLOT_DP,那是验过的手指尺寸,不能动),所以
     * **图标可见尺寸 = 48 - 2*pad**。想让图标大一点就收这里,不要去动格子。
     *
     * 三档的相对关系是有意的,**要调就一起调**,否则整条 Dock 大小不齐:
     *   线性字形 40dp —— 车控键、主页/返回/设置
     *   app 原生 38dp —— 多是满铺方图,同样大的显示区里显得更满
     *   加减号   30dp —— 笔画少,比字形再收一点才不会显得比旁边重
     *
     * 2026-08-16 线性字形从 36 放到 40:字形在自己的 24 网格里只占 20 的活动区
     * (见《图标源头分析》第七节),所以**实际墨迹还要再打八三折** —— 36dp 的显示区
     * 里字形只有 30dp,摆在 38dp 满铺的 app 图标旁边就是"车控那排怎么小一号"。
     *
     * 早先这三个值(10/8/13dp)散在十来处 setPadding 里,改一次得挨个找、必漏一两处
     * —— 集中在这儿就是为了这个。
     */
    /** 线性字形键的内边距。 */
    private static final int PAD_GLYPH_DP = 4;
    /** app 原生图标键的内边距。 */
    private static final int PAD_APP_DP = 5;
    /**
     * 「‹ 读数 ›」两侧箭头的内边距。**比字形键小得多**,因为那一段只有约 33dp 宽
     * (整键 2 格、三段按 3:4:3 分,见 childSlot),按字形键那套 9dp 留边,
     * 箭头本身只剩 15dp —— 在 48dp 行高的 Dock 上小得像个装饰,点也点不准。
     * 收到 3dp,箭头落在 27dp 上下,和其它键的字形(48-12=36dp)才算一个量级。
     */
    private static final int PAD_STEP_DP = 3;

    // ---- 编辑模式 ----
    /** 编辑态:每项挂 × 移除、长按可拖动排序,原本的点按功能全部让位。 */
    private boolean editing;
    /** 正在被拖动的那一项的下标;-1 = 没在拖。 */
    private int dragFrom = -1;
    /** 按下的位置与命中的格,用来判长按和滑动取消。 */
    private float downX, downY;
    private int downIndex = -1;
    /** 长按已经触发、进入拖动态。 */
    private boolean dragging;
    private int touchSlop = -1;

    // 车控键的视图(按 DockStore 配置可能缺项,用前判空)
    private TextView tempText;
    private ThermalFx tempFx;      // 温度键的冷暖粒子层
    private ImageView windIcon;    // 风向键中间那个图标(就是当前吹哪儿)
    private StepBars windBars;     // 图标底下那排风量档位格
    private HvacKey windKey;
    private HvacKey fanKey, defrostKey, recircKey;
    private Motion.Spinner fanSpin;
    private ValueAnimator tempAnim;
    private int tempColorNow;
    private String tempShown = "";

    // ---- 效果场(彩蛋粒子,见 DockFxField) ----

    /**
     * 整条 Dock 的效果场。**和 tempFx 是两回事**:tempFx 画在温度键自己的画布里,
     * 是功能语义(制冷制热得看得出来,任何模式都有);这一层跨整条 Dock,是"世界观",
     * 归模式的 particles 能力管(第 2 步接开关)。
     */
    private DockFxField fx;
    private boolean fxRunning;                   // 帧循环开着没
    private boolean fxVisible = true;
    private long fxFrameNs;                      // 当前帧时间戳,doFrame 喂给 draw

    private int fxLastFan = Integer.MIN_VALUE;   // 上次吹过的档位;值变才触发,天然去重
    private boolean fxLastAuto;                  // 连 AUTO 一起比:风量的**呈现**变了就算变
    private boolean fxLastPower;                 // 开空调那一下也该吹 —— 那正是"开始吹风"

    /** 一阵风吹多久(秒)。温度键里的粒子和 Dock 上那阵共用,两边必须同时起同时停。 */
    private static final float FX_SECONDS = 3f;

    /**
     * 帧循环。**驱动权在 DockView,不在效果场** —— 效果场自持 Choreographer 的话,
     * 它写错了宿主就停不下来(ThermalFx 现在正是这样:Dock 都 GONE 了回调还在跑)。
     */
    private final Choreographer.FrameCallback fxFrame = new Choreographer.FrameCallback() {
        @Override public void doFrame(long now) {
            if (!fxRunning) return;
            if (fx != null && fx.animating()) {
                fxFrameNs = now;
                invalidate();
                Choreographer.getInstance().postFrameCallback(this);
            } else {
                fxRunning = false;
                invalidate();          // 收尾一帧,把残余粒子擦掉
            }
        }
    };

    public DockView(Context c) { super(c); setup(); }
    public DockView(Context c, AttributeSet a) { super(c, a); setup(); }

    private void setup() {
        setOrientation(HORIZONTAL);
        // 水平居中:格数是 floor 出来的,零头大部分已经均摊进间距(见 DockGrid.gapPx),
        // 摊不掉的最后几个 px 由它平分到两端 —— 否则会全甩在右边,左右不一样宽。
        setGravity(Gravity.CENTER_VERTICAL | Gravity.CENTER_HORIZONTAL);
        // 按压回弹会短暂超出按键边界,点亮态的辉光也画在边界外
        setClipChildren(false);
        setClipToPadding(false);
        tempColorNow = ModeManager.get().ink();
        fx = new SnowDrift(getContext());
    }

    public void init(IconEngine icons, Listener listener) {
        this.icons = icons;
        this.listener = listener;
    }

    public void bind(List<DockItem> items) {
        this.items = items != null ? items : new ArrayList<>();
        build();
    }

    /**
     * 运行区:当前跑着的 app。**按槽位传**,下标 = 第几个应用位,null 表示那一格空着 ——
     * 顺序由 DockHost 钉住(见 DockHost.refreshRunning),这边照着摆就行,不能跳过 null。
     *
     * 它**不进 DockStore** —— 这份内容是动态的,存下来毫无意义,而且编辑 Dock 时
     * 也不该让人拖它(见 build:编辑态直接不渲染运行区)。
     */
    public void bindRunning(List<AppRef> running) {
        this.running = running != null ? running : new ArrayList<>();
        build();
    }

    /**
     * 车控状态刷新。轻量读只带温度/风量,开关态维持上一次全量读的值;
     * 不在滑动中时,回读一到就把乐观值让位给真值。
     */
    public void setHvac(AirCon.State s) {
        if (s == null) return;
        temp = s.temp;
        fan = s.fan;
        if (s.full) {
            power = s.power;
            auto = s.auto;
            defrost = s.defrost;
            recirc = s.cycle;
            compressor = s.compressor;
            windFace = s.face;
            windFoot = s.foot;
            windDef = s.windDef;
            seatHeatL = s.heatL; seatVentL = s.ventL;
            seatHeatR = s.heatR; seatVentR = s.ventR;
            hasToggles = true;
        }
        if (!slideActive) {
            adjTemp = Float.NaN;
            adjFan = -1;
        }
        refreshHvac(true);
    }

    // ---- 构建 ----

    /** 这一轮 build 渲染出来的项和它的 view,一一对应(技巧页照它量落点,见 DeskSnapshot)。 */
    private final List<DockItem> shownItems = new ArrayList<>();
    private final List<View> shownViews = new ArrayList<>();
    private View tempStepL, tempStepR;

    /** 这一项现在在 Dock 上的 view;没渲染(被跳过 / 没这项)返回 null。同类多项(应用位)给第一个。 */
    public View viewOf(DockItem.Kind kind, String acAction) {
        for (int i = 0; i < shownItems.size(); i++) {
            DockItem it = shownItems.get(i);
            if (it.kind != kind) continue;
            if (kind == DockItem.Kind.AC && !it.action.equals(acAction)) continue;
            return shownViews.get(i);
        }
        return null;
    }

    /** 温度键两侧的箭头:dir<0 左,否则右。 */
    public View tempStep(int dir) { return dir < 0 ? tempStepL : tempStepR; }

    /** 渲染出来的全部键(空格 / 分隔线不算)。 */
    public List<View> keyViews() {
        List<View> out = new ArrayList<>();
        for (int i = 0; i < shownItems.size(); i++) {
            DockItem.Kind k = shownItems.get(i).kind;
            if (k != DockItem.Kind.EMPTY && k != DockItem.Kind.DIVIDER) out.add(shownViews.get(i));
        }
        return out;
    }

    private void build() {
        removeAllViews();
        shownItems.clear();
        shownViews.clear();
        tempStepL = tempStepR = null;
        if (fanSpin != null) fanSpin.cancel();
        tempText = null; tempFx = null;
        windIcon = null; windBars = null; windKey = null;
        // 颜色缓存跟着视图一起作废。tintTempTo 靠 tempColorNow 判"颜色没变就别重染",
        // 而重建出来的温度键是全新的 view、身上一点颜色都没有 —— 缓存不清的话那次
        // 会被判成"已经是这个色了"直接跳过,于是空调明明开着,温度键却是原色浅灰
        tempColorNow = ModeManager.get().ink();
        fanKey = defrostKey = recircKey = null;
        seatKeys.clear();
        seatBars.clear();
        fanSpin = null;

        /*
         * 应用位(RUNNING)按顺序领走"现在还开着的应用" —— 第一个应用位放最近用的那个。
         *
         * 开着的应用**优先**,绑定的 app 是闲下来时的默认:两个都没在跑就摆两个绑定的,
         * 跑起来一个就顶掉一个。绑定不是把格子占死 —— 那样"同时最多四个应用"这条规矩
         * 在 Dock 上就看不见了。
         *
         * 它们是 items 里**固定的格子**,不是见缝插针:早先运行区是追加在末尾的,一开 app
         * 就把右下角的入口组顶开;后来改成填任意空格,位置又跟着空格数飘。现在位置钉死,
         * 开不开应用都在那儿,空着就是个空位。
         *
         * 编辑态摆成空位:里头是动态内容,让人拖它、给它挂 × 都没有意义(它也不可删)。
         */
        java.util.Set<Integer> skip = overflowSkips();
        int runAt = 0;
        for (int i = 0; i < items.size(); i++) {
            final DockItem it = items.get(i);
            if (skip.contains(i)) continue;        // 这块屏摆不下,先让它缺席(见 overflowSkips)
            // 风向键一律渲染:「‹ 风向 ›」这三格是 Dock 上唯一能调风量的地方。
            View v = null;
            if (it.kind == DockItem.Kind.RUNNING) {
                final int slot = runAt++;               // 这一格是第几个应用位
                if (it.app != null) {
                    // **绑定优先**:绑过的这一格固定就是它,不再被开着的应用顶掉。
                    // 顶掉的话用户特意钉上来的入口会自己跑掉,位置也就跟着变了
                    v = appButton(it.app);
                    // 绑定的格子长按也能换 app,和地图/音乐键同一套滑动选择器。
                    // 候选是用户自己在这个位添加的那几个(见 slotCandidates)
                    bindSlotSlide(v, DockItem.Kind.RUNNING, slot);
                } else {
                    // 没绑的格子才接开着的应用。running 按槽位排(下标 = 第几个应用位),
                    // 所以谁在哪格是稳定的 —— 切个应用不会让两格互换
                    AppRef live = (!editing && slot < running.size()) ? running.get(slot) : null;
                    v = live != null ? runningButton(live, slot) : appSlotHint();
                }
            }
            if (v == null) v = itemView(it);
            if (v == null) {                       // 如提权没通时的返回键
                if (editing) addView(hiddenStub());   // 同上:编辑态一项一个子视图,不许缺
                continue;
            }
            addView(editing ? wrapForEdit(v, it) : v);
            shownItems.add(it);
            shownViews.add(v);
        }

        // 末格右边不留格间距,否则整条会比可用宽度多出 13dp、被推得偏左一格缝,
        // 右下角那个键就贴不住屏幕边了
        if (getChildCount() > 0) {
            View last = getChildAt(getChildCount() - 1);
            LayoutParams lp = (LayoutParams) last.getLayoutParams();
            lp.setMarginEnd(0);
            last.setLayoutParams(lp);
        }

        ModeApplier.apply(this);
        // 气泡是**另一个窗口**,上面这趟遍历够不着它,只能点名(见 HvacBubble.reskin)。
        // 它也不跟着 Dock 重建 —— 实例挂在本类字段上,不点名就一直是旧配色
        if (bubble != null) bubble.reskin();
        refreshHvac(false);
    }

    /**
     * 运行区里的一项。和固定的 APP 项长得一样,差别只在点一下是**切回去**而不是重新启动。
     *
     * 早先下方还有个 accent 小圆点标"这个 app 现在开着"(macOS 那套)。去掉了 ——
     * 应用位现在位置钉死,谁在哪格是稳定的,圆点这个记号反而是多余的噪点。
     */
    private View runningButton(final AppRef app, final int runSlot) {
        FrameLayout box = new FrameLayout(getContext());
        box.setClipChildren(false);

        ImageView iv = new ImageView(getContext());
        if (icons != null) iv.setImageDrawable(icons.iconFor(app));
        iv.setContentDescription(app.label);
        int pad = Launchers.dp(getContext(), PAD_APP_DP);
        iv.setPadding(pad, pad, pad, pad);
        // 单击的按压动画/切换都交给 bindSlotSlide 的 touch 链路统一管(见下)
        iv.setOnClickListener(v -> { if (listener != null) listener.onSwitchTo(app); });
        box.addView(iv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // 动态运行位长按 = 和绑定运行位同一套滑动选择器,候选是**用户配置好的应用**
        // (RunSourceStore,编辑面板里加的那批) —— 长按出来的是"我要的这几个",
        // 不是"系统正在跑的这一堆"(2026-08-20 反馈:不想要运行中候选)。
        // **挂在 iv 上,不挂 box**:appButton 就是直接把选择器挂图标本体的。挂 box 的话
        // DOWN 会被 clickable 的 iv 消费掉,box 收不到事件,长按永远不触发(实测踩过)。
        bindSlotSlide(iv, DockItem.Kind.RUNNING, runSlot);

        LayoutParams lp = slotParams(1);
        box.setLayoutParams(lp);
        return box;
    }

    /** 造一项的视图。返回 null 表示这项当前不该出现在 Dock 上。 */
    private View itemView(final DockItem it) {
        switch (it.kind) {
            case HOME: {
                View h = iconButton(R.drawable.ic_home, "主页", v -> {
                    if (listener != null) listener.onGoHome();
                });
                // 长按切回原车桌面。跟设置键长按开编辑面板是同一类:长按用来"打开点什么",
                // 不用来调值 —— 调值的键(温度/风向)一律摆成三格,点一下走一格。
                h.setOnLongClickListener(lv -> {
                    if (listener != null) listener.onGoOriginalHome();
                    return true;
                });
                return h;
            }
            case BACK:
                // 常驻渲染,不跟提权状态走:返回是导航键,提权没就绪时点下去会 toast
                // 「返回需要先开启提权服务」(见 DockHost.onGoBack),不是死键 —— 渲染出来
                // 用户才知道有这个键、才会去开提权。早先"提权没就绪就跳过"的结果是重装后
                // 键凭空消失,用户以为功能丢了(2026-08-20)。
                return iconButton(R.drawable.ic_back, "返回", v -> {
                    if (listener != null) listener.onGoBack();
                });
            case TEMP:     return tempButton();
            case DIVIDER:  return divider();
            case AC:       return acButton(it.action);
            case APP:      return it.app != null ? appButton(it.app) : null;
            case DRAWER: {
                View btn = iconButton(R.drawable.ic_apps, "全部应用", v -> {
                    if (listener != null) listener.onOpenDrawer();
                });
                // 短按开抽屉,长按弹最近任务 —— 两个手势在一颗键上。长按被吃掉后
                // 不会再触发 onClick,不会出现"长按弹完任务又把抽屉打开"的连招。
                // 2026-09-12 起最近任务有了自己的键(RECENTS),这条长按留作快捷入口。
                btn.setOnLongClickListener(v -> {
                    if (listener != null) listener.onOpenRecents();
                    return true;
                });
                return btn;
            }
            case RECENTS:
                // 常驻渲染,和返回键同一条规矩:提权没就绪时点下去会 toast
                // 「最近任务需要先开启提权服务」(见 DockHost.onOpenRecents),不是死键。
                // 原先最近任务只挂在"全部应用"的长按上,不摸索找不到 —— 补成一颗看得见的键。
                return iconButton(R.drawable.ic_recents, "任务", v -> {
                    if (listener != null) listener.onOpenRecents();
                });
            case MAP:
            case MUSIC: {
                // 语义槽位:短按开/唤回对应的画中画卡位,长按就地换这一类用哪个 app
                // (换源不该先跑一趟设置页 —— 和长按设置键就地改 Dock 是同一条规矩)
                boolean isMap = it.kind == DockItem.Kind.MAP;
                String label = isMap ? "地图" : "音乐";
                OnClickListener open = v -> {
                    if (listener != null) listener.onOpenSlot(it.kind);
                };
                // 显示**当前源那个 app 的原生图标** —— 一眼看出这颗键会开哪个 app,
                // 和长按滑动换 app 是同一件事的两头(换完这里的图标就跟着变)。
                // 还没配过源(装机第一次)才退回内置的线性字形,那时也没 app 可显示。
                String cur = isMap ? new NavSourceStore(getContext()).current()
                                   : new MusicSourceStore(getContext()).current();
                View s;
                if (cur != null && !cur.isEmpty() && icons != null) {
                    s = appIconButton(
                            icons.iconFor(new AppRef(cur, "", AppTypes.label(getContext(), cur))),
                            label, open);
                } else {
                    s = iconButton(isMap ? R.drawable.ic_nav : R.drawable.ic_music, label, open);
                }
                bindSlotSlide(s, it.kind, -1);
                return s;
            }
            case SETTINGS: {
                // 短按进设置页,长按就地改 Dock —— 改的是眼前这条,不该先离开桌面
                View s = iconButton(R.drawable.ic_settings, "桌面设置", v -> {
                    if (listener != null) listener.onOpenSettings();
                });
                s.setOnLongClickListener(lv -> {
                    if (listener != null) listener.onEditDock();
                    return true;
                });
                return s;
            }
            case WIND:     return windButton();
            case RUNNING:     // 运行位空着的时候(build 里已按 running 列表填过一轮)
            case SPACER:      // 退役类型,老配置读进来时已在 DockStore.toGrid 换成 EMPTY
            case EMPTY:
                return emptySlot();
            default:       return null;
        }
    }

    // ---- 编辑模式:直接在真 Dock 上改 ----

    /**
     * 进/出编辑态。**编辑的就是这条真 Dock**,不另做预览 —— 预览再像也是"另一条",
     * 而且预览一旦换行就不是 Dock 的样子了;在真身上改,挤不挤、放不放得下,一眼就看到。
     */
    public void setEditing(boolean on) {
        if (editing == on) return;
        editing = on;
        cancelDrag();
        // 注意:编辑态**不能改 Dock 的高度**(比如加 padding 给角标让位)。
        // Dock 高度一变,pager 跟着变高,但已经测量过的页不会重测 ——
        // 结果是下一页从底缝里探出一条边。角标只能画在项内部,不撑大这一条。
        build();
    }

    public boolean isEditing() { return editing; }

    /**
     * 候选面板点选一项:填进 Dock 上**第一个空格**。
     *
     * 不追加到末尾 —— 末尾是设置键那组,加过去会把右下角那组入口挤出屏幕、格数也超了
     * (与 {@link #addItem} 是同一个道理)。填空格则总格数不变,别的项一个都不动。
     *
     * 这是"点一下"那条路。想直接放到某一格就从候选区拖过来,落点走 {@link #dropAt}
     * —— 两条并存,车在动的时候点一下省事,停着的时候拖着摆更准。
     *
     * 拖那条**不走系统拖放**(它在悬浮窗里没保证,见 {@link #onInterceptTouchEvent}):
     * 编辑面板和这条 Dock 本来就在同一个 root 下,影子由 DockHost 自己画
     * (见 {@code DockHost.startCandidateDrag})。
     */
    public void addItem(DockItem item) {
        if (item == null || !editing) return;
        int at = firstEmpty();
        if (at < 0 || !placeAt(at, item)) {
            Launchers.toast(getContext(), "Dock 的格子满了,先删掉几个再放");
            return;
        }
        notifyEdited();
    }

    /**
     * 从候选区拖过来的一项,落到 x 所在那一格(x 是**这条 Dock 内**的坐标)。
     *
     * 和 {@link #addItem} 的区别只在落点:那个落第一个空格,这个落手指指的地方。
     * 插入语义同 {@link #placeAt} —— 插在那一格前面,右边的项后移,最近的空格被吃掉。
     */
    void dropAt(float x, DockItem item) {
        if (item == null || !editing) return;
        int at = indexAt(x);
        if (at < 0) at = firstEmpty();                 // 落在格与格之间的缝上:退回第一个空格
        if (at < 0 || !placeAt(at, item)) {
            Launchers.toast(getContext(), "Dock 的格子满了,先删掉几个再放");
            return;
        }
        notifyEdited();
    }

    /**
     * 拖动过程中高亮落点。x < 0 = 收掉提示。
     *
     * **span 是这一项占几格**(温度、风向占 2,见 DockItem.slots):按它高亮**连续 span 格**,
     * 不然拖一个两格的键只看到一格变淡,松手却推走两格 —— 预览和落点对不上。
     *
     * 只改透明度不改尺寸:尺寸一变整条 Dock 就会重排,落点在手指底下溜走
     * (同 {@link #wrapForEdit} 里那条理由)。
     */
    void hintDropAt(float x, int span) {
        int at = x < 0 ? -1 : indexAt(x);
        if (span < 1) span = 1;
        if (at == dropHint && span == dropSpan) return;
        dropHint = at;
        dropSpan = span;
        for (int i = 0; i < getChildCount(); i++) {
            boolean lit = at >= 0 && i >= at && i < at + span;
            getChildAt(i).setAlpha(lit ? .35f : 1f);
        }
    }

    /** 当前拖的这一项占几格,决定高亮几格(见 hintDropAt)。 */
    private int dropSpan = 1;

    private int firstEmpty() {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).kind == DockItem.Kind.EMPTY) return i;
        }
        return -1;
    }

    /**
     * 编辑态给指定下标那一格换上 app(编辑面板里点应用位,见 DockEditPanel)。
     *
     * 地图/音乐位走换源 —— 那一格还是语义位,上层负责收旧卡、按新源重开
     * (见 HomeActivity.switchSlotSource);运行位是**绑死包名**,
     * 换了就固定显示它,不再被"正在跑的 app"顶掉。app 传 null = 解开绑定,
     * 运行位退回空位(显示当前开着的应用) —— 面板里把这一位清空时走这条。
     */
    public void setSlotApp(int index, AppRef app) {
        if (index < 0 || index >= items.size()) return;
        DockItem it = items.get(index);
        if (it.kind == DockItem.Kind.MAP || it.kind == DockItem.Kind.MUSIC) {
            if (app == null) return;   // 语义位没有"空"这一说,图标退默认字形即可
            if (listener != null) listener.onSlotSourcePicked(it.kind, app.pkg);
            build();   // 图标跟着新源换
            return;
        }
        if (it.kind == DockItem.Kind.RUNNING) {
            items.set(index, app != null ? DockItem.running(app) : DockItem.of(DockItem.Kind.RUNNING));
        } else {
            return;
        }
        build();
        notifyEdited();
    }

    /** 是不是"应用类" —— 只有这几种进得了应用位。 */
    private static boolean appLike(DockItem it) {
        return it != null && (it.kind == DockItem.Kind.MAP || it.kind == DockItem.Kind.MUSIC
                || it.kind == DockItem.Kind.RUNNING || it.kind == DockItem.Kind.APP);
    }

    /** 第 slot 个运行位在 items 里的下标。找不到返回 -1(如 Dock 被重置没了运行位)。 */
    public int runningSlotIndex(int slot) {
        int seen = 0;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).kind != DockItem.Kind.RUNNING) continue;
            if (seen == slot) return i;
            seen++;
        }
        return -1;
    }

    /**
     * 编辑态的一项:原视图 + 右上角 ×,长按可拖动排序。
     * 原本的点按/滑动功能全部摘掉 —— 编辑时点温度不该弹空调面板。
     */
    private View wrapForEdit(final View child, final DockItem item) {
        child.setOnClickListener(null);
        child.setOnLongClickListener(null);
        child.setOnTouchListener(null);
        child.setClickable(false);
        child.setLongClickable(false);

        final FrameLayout box = new FrameLayout(getContext());
        // 宽度原样照搬:网格化之后每一项都已经是整数格宽(见 slotParams),编辑态不该
        // 再改尺寸 —— 尺寸一变,整条 Dock 就在进编辑态的一瞬间重排,长按的键会从
        // 手指底下溜走。以前分隔线只有 1dp、得在这儿撑到 40dp 才挂得住角标,
        // 现在它本来就占一格,那条兜底连同它的副作用一起没了。
        LayoutParams inner = (LayoutParams) child.getLayoutParams();
        LayoutParams outer = new LayoutParams(
                inner != null ? inner.width : slotWidth(1), dockH());
        if (inner != null) {
            outer.setMarginEnd(inner.getMarginEnd());
            outer.setMarginStart(inner.getMarginStart());
        }
        box.setLayoutParams(outer);
        // 角标画在按键顶边之上(负 margin),这一层不能裁;让出的空间由 DockView 的顶部 padding 提供
        box.setClipChildren(false);
        box.setClipToPadding(false);
        child.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        box.addView(child);

        // 有几个键**不可移除**(见 DockItem.removable):设置键既是设置页入口也是
        // 编辑态入口,删了就回不来;地图/音乐键是画中画两个卡位的唯一入口。
        // 不给它们 × ,但排序照常
        boolean canRemove = DockItem.removable(item.kind)
                || (item.kind == DockItem.Kind.RUNNING && item.app != null);
        if (canRemove) {
            TextView del = new TextView(getContext());
            del.setText("×");
            del.setTextSize(12f);
            del.setTextColor(ModeManager.get().bg());
            del.setGravity(Gravity.CENTER);
            GradientDrawable dbg = new GradientDrawable();
            dbg.setShape(GradientDrawable.OVAL);
            dbg.setColor(ModeManager.get().muted());
            del.setBackground(dbg);
            del.setContentDescription("移除");
            // 必须抬到所有按键之上:点亮态的车控键为了做辉光带着 6dp elevation,
            // 角标不抬就被自己那颗键盖住 —— 现象是"点亮的键没有 ×",看着像编辑态坏了
            del.setElevation(Launchers.dp(getContext(), 12));
            Motion.press(del);
            del.setOnClickListener(v -> {
                int idx = indexOfChild(box);
                if (idx < 0 || idx >= items.size()) return;
                if (items.get(idx).kind == DockItem.Kind.RUNNING) {
                    // 应用位删的是"绑定",不是格子本身 —— 位子还在,变回空的应用位
                    items.set(idx, DockItem.of(DockItem.Kind.RUNNING));
                } else if (items.get(idx).kind == DockItem.Kind.EMPTY) {
                    if (!removeBlank(idx)) return;
                } else {
                    // 删掉的项**留下同样多的空格**,不是让右边的项挤过来 —— 格子的位置是死的,
                    // 删一个东西不该把整条 Dock 往左抖一格。腾出来的格随后可以再放别的
                    int slots = items.get(idx).slots();
                    items.remove(idx);
                    for (int i = 0; i < slots; i++) {
                        items.add(idx, DockItem.of(DockItem.Kind.EMPTY));
                    }
                }
                build();
                notifyEdited();
            });
            FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(
                    Launchers.dp(getContext(), 20), Launchers.dp(getContext(), 20));
            // 压在按键右上角内侧(iOS 抖动模式那样)。不外挂:一外挂就得撑高 Dock,
            // 而且超出父容器的部分点不到 —— 触摸不会派发到父容器边界之外
            dlp.gravity = Gravity.END | Gravity.TOP;
            box.addView(del, dlp);
        }

        // 长按拖动排序不在这儿挂 —— 整条 Dock 统一在 onInterceptTouchEvent 里接管,
        // 每格自己不再收触摸(× 角标除外,它是这一层唯一还点得动的东西)
        return box;
    }

    /**
     * 两格互换:拖到哪一格就占哪一格,原来那格让给被换下来的项。
     *
     * 网格是**交换**不是插队 —— 插队会把它右边的所有项整体推走一格,那就不叫"拖哪格
     * 停哪格"了。拖到空格上就是"移过去、原位置变空",正是这条规则的自然结果。
     *
     * 交换不改变项的集合,所以总格数恒定,末格照旧贴着右缘。宽项(占 2 格的读数胶囊)
     * 和窄项换位时,夹在中间的项会平移一格 —— 那是宽度差,躲不掉。
     */
    private void swap(int a, int b) {
        if (a == b || a < 0 || b < 0 || a >= items.size() || b >= items.size()) return;
        DockItem ia = items.get(a);
        items.set(a, items.get(b));
        items.set(b, ia);
        // 视图跟着换。拖动期间只挪 view 不重建 —— 重建出来的新 view 收不到后续拖拽事件
        int lo = Math.min(a, b), hi = Math.max(a, b);
        View vHi = getChildAt(hi), vLo = getChildAt(lo);
        removeViewAt(hi);
        removeViewAt(lo);
        addView(vHi, lo);
        addView(vLo, hi);
    }

    /**
     * 把一项放进 index 格,并从别处收回等量的空格。放不下(空格不够)返回 false。
     *
     * 每放一项就要**还回同样多的格**(格子总数是死的):优先吃落点右边最近的空格,右边
     * 没有了才往左找。放进空格时,被吃掉的正是落点自己那一格 —— 等于就地填空,
     * 别的项一个都不动,这是 {@link #addItem} 走的那条路径。
     */
    private boolean placeAt(int index, DockItem item) {
        int need = item.slots();
        if (emptyCount() < need) return false;
        View v = itemView(item);
        if (v == null) return false;       // 如提权没通时的返回键:摆不出来就不占位
        items.add(index, item);
        addView(wrapForEdit(v, item), index);

        int freed = 0;
        for (int i = index + 1; i < items.size() && freed < need; ) {
            if (items.get(i).kind == DockItem.Kind.EMPTY) {
                items.remove(i);
                removeViewAt(i);
                freed++;
            } else {
                i++;
            }
        }
        for (int i = index - 1; i >= 0 && freed < need; i--) {
            if (items.get(i).kind == DockItem.Kind.EMPTY) {
                items.remove(i);
                removeViewAt(i);
                freed++;
            }
        }
        return true;
    }

    private int emptyCount() {
        int n = 0;
        for (DockItem it : items) if (it.kind == DockItem.Kind.EMPTY) n++;
        return n;
    }

    /**
     * 删掉 index 处的空格:右边那段项**整体往左收一格**(往中间加空格的反操作)。
     *
     * 格子总数是死的,所以这一格不是消失,是**并进别处的空白** —— 找右边最近的一段
     * 空白并进去,右边没有了再往左找。并到哪儿,哪边就宽一格。
     *
     * 整条只剩这一个空格时返回 false:没有别的空白可并,删了也只能原地再生一个,
     * 白重建一次。
     */
    private boolean removeBlank(int index) {
        if (emptyCount() <= 1) return false;
        items.remove(index);
        int at = -1;
        for (int i = index; i < items.size(); i++) {
            if (items.get(i).kind == DockItem.Kind.EMPTY) { at = i; break; }
        }
        if (at < 0) {
            for (int i = index - 1; i >= 0; i--) {
                if (items.get(i).kind == DockItem.Kind.EMPTY) { at = i; break; }
            }
        }
        items.add(at, DockItem.of(DockItem.Kind.EMPTY));
        return true;
    }

    private void notifyEdited() {
        if (listener != null) listener.onDockEdited(new ArrayList<>(items));
    }

    // ---- 编辑态的拖动排序(自绘,不走系统拖放) ----

    /**
     * 长按多久算开拖。比系统 ViewConfiguration 的 500ms 短 —— 编辑态里每一格本来就
     * 只剩"拖"这一个手势(点按功能全让位了,× 是单独一颗),不用留那么长的犹豫期。
     */
    private static final long DRAG_HOLD_MS = 300L;

    /**
     * 长按到点:抬起这一格,进入拖动态。
     *
     * 抬起用 elevation + 轻微放大,不改尺寸也不动布局 —— 一动布局,手指底下的键就跑了。
     */
    private final Runnable dragStart = new Runnable() {
        @Override public void run() {
            if (!editing || downIndex < 0 || downIndex >= getChildCount()) return;
            dragging = true;
            dragFrom = downIndex;
            View box = getChildAt(dragFrom);
            if (box != null) {
                box.setElevation(Launchers.dp(getContext(), 16));
                box.setScaleX(1.06f);
                box.setScaleY(1.06f);
            }
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        }
    };

    /**
     * 编辑态的触摸全由这条 Dock 自己接管,**不用系统拖放**(startDragAndDrop)。
     *
     * 为什么:Dock 整个活在悬浮窗里(见 {@link com.liubai.blank.ui.DockHost}),而系统
     * 拖放框架是为 Activity 窗口设计的,在 overlay 窗口里能不能跑通没有保证 ——
     * 不赌这一手。迪友那条 Dock 同样是悬浮窗,它的排序走的就是 ItemTouchHelper,
     * 纯 MotionEvent 拦截、跟窗口类型无关(反编译 3.0.1,b4/a.java 是
     * ItemTouchHelper.Callback,全工程零处 startDragAndDrop)。
     *
     * DOWN 时先**不拦**:× 角标得先有机会收到自己的点击。等长按到点、真进了拖动态,
     * 下一个 MOVE 才拦下来,系统会自动给子视图补一发 CANCEL。
     *
     * 非编辑态一律不拦,免得动到地图/音乐键那套长按滑动换 app 的手势(见 bindSlotSlide)。
     */
    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (!editing) return false;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                downIndex = indexAt(downX);
                dragging = false;
                dragFrom = -1;
                if (downIndex >= 0) postDelayed(dragStart, DRAG_HOLD_MS);
                return false;
            case MotionEvent.ACTION_MOVE:
                if (dragging) return true;
                // 长按没到点就先滑走了:那是滑动不是拖,撤掉计时
                if (Math.abs(ev.getX() - downX) > slop()
                        || Math.abs(ev.getY() - downY) > slop()) {
                    removeCallbacks(dragStart);
                }
                return false;
            default:
                removeCallbacks(dragStart);
                return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!editing) return super.onTouchEvent(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // 按在格与格之间的空隙上。收下它,否则后续事件根本不会派发过来
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) { dragTo(ev.getX()); return true; }
                // 长按没到点就滑走了 = 不是拖,撤掉计时。onInterceptTouchEvent 里也有一份,
                // 但编辑态的格子不消费 DOWN(wrapForEdit 把触摸摘光了),事件 DOWN 后直接落到
                // 这里、intercept 不再被回调 —— 那份只剩 ×(clickable、会吃 DOWN)还走得到,
                // 自绘格子够不着。所以这儿补一份,否则按下即滑也会在 300ms 后误判成拖动。
                if (Math.abs(ev.getX() - downX) > slop()
                        || Math.abs(ev.getY() - downY) > slop()) {
                    removeCallbacks(dragStart);
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(dragStart);
                endDrag();
                return true;
            default:
                return super.onTouchEvent(ev);
        }
    }

    /**
     * 手指移到 x:进了谁的地盘就跟谁换位,没换位就让被拖的那格贴着手指走。
     *
     * 换位**不落盘**,松手才算数(见 {@link #endDrag}) —— 手指划过一路键就写一路配置
     * 的话,拖到一半改主意都收不回来。
     */
    private void dragTo(float x) {
        if (dragFrom < 0 || dragFrom >= getChildCount() || dragFrom >= items.size()) return;
        int to = indexAt(x);
        boolean canSwap = to >= 0 && to != dragFrom && to < items.size()
                // 应用位只收应用类(地图/音乐/应用位/app):车控键换过来会把格子挤走,
                // 四个应用位就凑不齐了。它们**之间**照常互换,顺序随你排
                && !(items.get(to).kind == DockItem.Kind.RUNNING
                     && !appLike(items.get(dragFrom)));
        if (canSwap) {
            View old = getChildAt(dragFrom);
            if (old != null) old.setTranslationX(0f);   // 换位前归零,新格重新起算
            swap(dragFrom, to);
            dragFrom = to;
            // 位移等下一帧:swap 刚挪完 view,getLeft() 还是上一轮布局的值
            return;
        }
        View box = getChildAt(dragFrom);
        if (box != null) box.setTranslationX(x - (box.getLeft() + box.getWidth() / 2f));
    }

    /** 松手:落回格位、落盘、按最终顺序重建(补回 × 和下标)。 */
    private void endDrag() {
        boolean was = dragging;
        cancelDrag();
        if (!was) return;
        build();
        notifyEdited();
    }

    /** 清掉拖动态并把抬起的那格放回去。进出编辑态、松手、被打断都走这儿。 */
    private void cancelDrag() {
        removeCallbacks(dragStart);
        if (dragFrom >= 0 && dragFrom < getChildCount()) {
            View box = getChildAt(dragFrom);
            if (box != null) {
                box.setTranslationX(0f);
                box.setScaleX(1f);
                box.setScaleY(1f);
                box.setElevation(0f);
            }
        }
        dragging = false;
        dragFrom = -1;
        downIndex = -1;
    }

    /** x 落在第几格上。落在格与格的间隙、或整条两端之外返回 -1。 */
    private int indexAt(float x) {
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (c.getVisibility() == GONE) continue;
            if (x >= c.getLeft() && x < c.getRight()) return i;
        }
        return -1;
    }

    private int slop() {
        if (touchSlop < 0) touchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        return touchSlop;
    }

    // ---- 状态上屏(全部走这里,保证读数/开关/动画一致) ----

    private void refreshHvac(boolean animate) {
        boolean off = hasToggles && !power;
        /*
         * 一条规则,全 Dock 统一:**这个功能开着 → 图标转青,没开 → 灰**。
         * 不上底色、不加辉光,免得一部分键有底一部分只变色,看着是两套东西。
         * 电源未知(!hasToggles)时一律按灰处理,不瞎宣布。
         */
        boolean on = hasToggles && power;

        if (tempText != null) {
            float v = !Float.isNaN(adjTemp) ? adjTemp : tempValue();
            String label = off ? "OFF" : tempLabel(v);
            if (!label.equals(tempShown)) {
                tempShown = label;
                // 温度独占一格、字号大,单位缩成上标(「26℃」而不是「26°」)。
                tempText.setText(withUnit(label));
                if (animate) Motion.pop(tempText);
            }
            // 数值走"开着就亮":冷暖色阶取消了 —— Dock 上一格一个颜色太花,
            // 而且冷暖在温度气泡和空调面板里都还看得到,不缺这一处
            tintTempTo(on ? ModeManager.get().accent() : ModeManager.get().glyph(), animate);
            if (tempFx != null) tempFx.setMode(thermalMode(on, v));
        }

        if (fanKey != null) {
            // 档位数字角标撤了(图标上不叠字);风量在风向键那格的档位条上照样看得见
            if (fanSpin != null) fanSpin.speedTo(off ? 0f : Motion.fanSpeed(fanShow()));
            fanKey.set(on, animate);          // 通电就在送风,这颗的"开着"就是通电
        }

        if (windKey != null) {
            // 图标跟着状态换:三个风向能同时开,单个字形确实表达不全 —— 但 BYD 自己
            // 把七种组合各画了一个(nav_common_ac_wind_mode_1..7,编号就是 AC_WINDMODE_*),
            // 组合问题在字形那一层就解决了,不用在这儿挑一个报。
            if (windIcon != null) windIcon.setImageResource(windModeIcon());
            if (windBars != null) windBars.set(off ? 0 : fanShow(), 0);
            windKey.set(on, animate);
        }

        if (defrostKey != null) {
            defrostKey.set(on && defrost, animate);
        }

        if (recircKey != null) {
            // 内/外循环靠字形区分 —— 它们是二选一的两个模式,不是同一个键的开关态。
            // 开着不开着由 set() 那侧的颜色说,不换字形(见《图标源头分析》第七节)。
            recircKey.icon.setImageResource(!hasToggles || recirc
                    ? R.drawable.ic_recirculate : R.drawable.ic_recirculate_out);
            // **空调开着就亮**,不看是内还是外 —— 循环是二选一的模式,没有"关"这个态,
            // 任何时候都有一个在生效。早先是 `on && recirc`,外循环时整个键是灭的,
            // 看着就像状态没读到/键坏了。是内是外由字形分,不由亮不亮分
            recircKey.set(on, animate);
        }

        // 座椅:开着就亮 + 底下两格显示档位。-1(读不到/没装配)按灰处理,不瞎宣布。
        // 字形不跟着档位换 —— 开启态只换颜色(见《图标源头分析》第七节),档位交给格子
        for (java.util.Map.Entry<String, HvacKey> e : seatKeys.entrySet()) {
            String action = e.getKey();
            int lv = seatLevel(action);
            e.getValue().set(lv > 0, animate);
            StepBars bars = seatBars.get(action);
            // 一律强调色:加热/通风靠字形分,不靠颜色分(2026-08-16 撤掉冷暖色)
            if (bars != null) bars.set(Math.max(lv, 0), ModeManager.get().accent());
        }


        /*
         * 彩蛋:风量档位变了就吹一阵(见 DockFxField)。
         *
         * 喂点选在这儿而不是 setHvac —— setHvac 只走**回读**路径,而用户滑风量走的是
         * adjFan → applyAdjust → refreshHvac,根本不经过它;跟着回读吹的话每一下都慢半拍。
         * fanShow() 取的是含乐观值的显示档位,所以是当场吹。
         *
         * **值变才发**:比对上次天然去重 —— 乐观值吹过之后,几百毫秒后回读同一个值不会
         * 再吹第二阵。谁改的都吹(用户滑动 / AUTO 自调 / 副驾实体键),和扇叶变速同语义:
         * Dock 反映的是车的状态,不是"谁按了按钮"。
         */
        if (fx != null) {
            int f = fanShow();
            /*
             * 比的是**档位 + AUTO + 电源**三者,不只是档位数字:
             *   · 档位  —— 那一格的读数就是它;
             *   · AUTO  —— 按下 AUTO 时真车要等车机自调完、几百毫秒后回读才有反应,按下那一刻
             *             没反馈;模拟器上更是根本不动 fan(mock 的 AUTO 只翻标志,AirCon:165,
             *             那里有意不装作联动);
             *   · 电源  —— **开空调正是"开始吹风"这个动作本身**,最该吹的时刻,而它常常只有
             *             power 变、fan 照旧。
             *
             * 关空调不用特判:那时 on=false → thermalMode 给 NONE,下面 m != NONE 的闸门挡住。
             */
            if (f != fxLastFan || auto != fxLastAuto || power != fxLastPower) {
                boolean first = fxLastFan == Integer.MIN_VALUE;
                fxLastFan = f;
                fxLastAuto = auto;
                fxLastPower = power;
                /*
                 * 头一次只记不吹:Dock 重建/开机时本来就会走一遍 refreshHvac,
                 * 那不是"档位变了",人也没动过任何东西。
                 *
                 * **滑动中也只记不吹**:手还按着,档位每变一格就重启一阵,吹得又碎又吵;
                 * 落定那一下由 stepFan 直接点(不等回读 —— 等回读
                 * 慢半拍)。值在滑动中照记,所以松手后回读同一个档位不会再补一阵。
                 */
                if (!first && !slideActive) gust();
            }
        }
    }

    /**
     * 条件齐了就吹一阵(彩蛋)。两处触发:档位呈现变化(refreshHvac,非滑动时)、
     * 风量落定(stepFan 攒完那 300ms 之后)。
     *
     * 彩蛋归模式的 **particles 能力**管 —— 声明了 particles=false 就该没有。**判定放在触发处而不是创建处**:切模式时
     * DockView 未必重建(Dock 是常驻悬浮窗),只在创建时查会漏。
     * fxLast* 那几个值与此无关、照记不误,免得切回来时积压的差值突然吹一阵。
     */
    private void gust() {
        if (fx == null || !ModeManager.get().caps().particles) return;
        int f = fanShow();
        int m = thermalMode(hasToggles && power, !Float.isNaN(adjTemp) ? adjTemp : tempValue());
        int dir = sweepDir();
        /*
         * 不吹的三种情况:
         *   · 没方向 —— 温度键不在 Dock 上,算不出往哪边吹;
         *   · 没冷暖 —— 空调没开;
         *   · **风量 0 或读不到** —— 0 档是风机真的停转了(同
         *     Motion.fanSpeed 的判断:"0 是风机真的停了,得停转"),
         *     没有风就不该有东西被吹走;-1 是没读到档位,不瞎吹。
         */
        if (dir != 0 && m != ThermalFx.NONE && f > 0) {
            // **源头自己也得被吹动**:温度键里那些雪花边落边被横着吹走,
            // 否则"从键里吹出来"是假的 —— 键内一批照旧原地飘、键外另起一批,
            // 两不相干。**射程给到 Dock 边缘**,让它们真的飘过整条 Dock,
            // 而不是在自己那 96dp 的格子里回绕(那样只挪一点点,像没吹动)
            tempFx.gust(dir, FX_SECONDS, f);
            // 源头给中心 + 宽度:粒子从**温度键这一格里**陆续被吹出来,
            // 而不是从一个点凭空冒出一批 —— 那样看着和这颗键没关系
            fx.onSignal(DockFxField.SIG_ORIGIN_X, (int) centerX(tempFx));
            fx.onSignal(DockFxField.SIG_ORIGIN_W, tempFx.getWidth());
            fx.onSignal(DockFxField.SIG_DIR, dir);
            fx.onSignal(DockFxField.SIG_AC_MODE, m);
            fx.onSignal(DockFxField.SIG_FAN_LEVEL, f);   // 最后点燃
            fxStart();
        }
    }

    /**
     * 冷暖判定:制冷看压缩机(夏天设 26 度照样制冷),制热靠"压缩机关 + 设定偏高"近似。
     *
     * 温度键的粒子({@link ThermalFx})和整条 Dock 的效果场共用这一处,**不写第二套** ——
     * 两边各判各的,迟早出现"温度键在下雪、Dock 上吹的却是暖光点"。
     */
    private int thermalMode(boolean on, float temp) {
        if (!on) return ThermalFx.NONE;
        if (compressor) return ThermalFx.COOL;
        return temp >= 26f ? ThermalFx.HEAT : ThermalFx.NONE;
    }

    /**
     * 粒子画在**所有键之前**(Dock 背景之后)。
     *
     * 画在键下面而不是上面:Dock 的键全是幽灵键(无底色无边框),粒子在下面照样从键缝和
     * 字形背后透出来,而"会不会糊住读数"这个风险直接归零。层级也和 ThermalFx
     * ("背景之后、内容之前")对齐。
     */
    @Override
    protected void dispatchDraw(Canvas c) {
        if (fx != null && fx.animating()) {
            fx.draw(c, getWidth(), getHeight(), fxFrameNs);
        }
        super.dispatchDraw(c);
        drawTopEdge(c);
    }

    /** 那道上边线的画笔。颜色现取(见 {@link #drawTopEdge}),这里只留一支。 */
    private final Paint edge = new Paint();

    /**
     * Dock 那块"面"的上边线。
     *
     * **自己画而不是走 shape 的 stroke**:shape 描边是四边一起的,下/左/右那三条正好
     * 压在屏幕边缘上,看出来就是"Dock 没和屏幕边框贴合、四周镶了道边"(2026-08-18
     * 平板上实测)。只有上边这条是真分界 —— 它上面才是壁纸。
     *
     * 颜色现取而不缓存:换模式、换壁纸后 line 会变,而这条线不在 ModeApplier 的
     * tag 体系里(那套只认背景 shape),缓存了就得再补一条刷新路径。
     */
    private void drawTopEdge(Canvas c) {
        // hairline 而不是 line,**且线宽固定 1 个物理像素**:dp(1) 在 1.5 密度下是
        // 1.5px、渲染时摊成两行,配上 20% 白就成了暗壁纸上一道亮杠(理由见 hairline)
        edge.setColor(ModeManager.get().hairline());
        c.drawRect(0f, 0f, getWidth(), 1f, edge);
    }

    /**
     * 风往哪边吹:**温度键在风源的哪一侧,就往哪一侧**。风从风源起、穿过温度键继续往外,
     * 所以温度键在右就往右吹。
     *
     * 方向不是写死的,是从用户排的 Dock 里算出来的 —— 把温度键拖到风源左边,风就往左吹。
     * 缺了参照物就没有方向,那一趟不吹(0)。
     *
     * **风源认扇叶键**(它是空调总开关,一整条 Dock 上最像出风口的那个),
     * 它不在时才退回风向键 —— 后者只是"能调风量",摆位上不代表风从那儿来。
     *
     * ⚠️ 这里的优先级**颠倒过一次**,后果是粒子恒往左吹:默认布局是
     * 「扇叶 → 温度 → 风向」,拿风向当参照的话温度永远在它左边,`dir` 恒为 -1。
     * 改动这两行前先在真机上看一眼方向。
     *
     * 不跑动效时温度键不建粒子层({@code tempFx} 为 null),那边不吹。
     */
    private int sweepDir() {
        View src = fanKey != null ? fanKey.view : (windKey != null ? windKey.view : null);
        if (src == null || tempFx == null || tempFx.getWidth() == 0) return 0;
        float srcX = centerX(src), tempX = centerX(tempFx);
        return tempX == srcX ? 0 : (tempX > srcX ? 1 : -1);
    }

    /** 某颗键在 DockView 坐标系里的中心 x。编辑态会包一层(wrapForEdit),所以逐级累加。 */
    private float centerX(View target) {
        if (target == null) return getWidth() / 2f;
        float x = 0;
        View v = target;
        while (v != null && v != this) {
            x += v.getLeft();
            v = (v.getParent() instanceof View) ? (View) v.getParent() : null;
        }
        return x + target.getWidth() / 2f;
    }

    private void tintTempTo(int target, boolean animate) {
        if (target == tempColorNow && tempAnim == null) return;
        if (tempAnim != null) tempAnim.cancel();
        if (!animate) {
            tempColorNow = target;
            applyTempColor(target);
            tempAnim = null;
            return;
        }
        tempAnim = Motion.color(tempColorNow, target, c -> {
            tempColorNow = c;
            applyTempColor(c);
        });
    }

    /** 读数和右上角的冷暖角标一起染 —— 一颗键一个颜色,不能一半青一半灰。 */
    private void applyTempColor(int c) {
        if (tempText != null) tempText.setTextColor(c);
    }

    // ---- 读数格式 ----

    /** 右上角那个角标画哪一种:冷 / 常温 / 热。 */
    /**
     * 温度键的图标**恒定用中性温度计**。
     *
     * 原来按温度换字形(≤24 换成带雪花的 temp-cold、≥27 换成带热浪的 temp-hot),
     * 2026-08-02 去掉:那两个符号在 Dock 上是多余的一层噪音。冷暖该看的地方还在 ——
     * 温度键内部的 ThermalFx 粒子、空调面板、以及调风量时吹出来的雪花/火星。
     */
    /** 16/33 是行程两端,车机上显示 LO/HI;读不到显示 --°。 */
    /**
     * 把读数末尾的单位缩成小字(「26℃」的 ℃ 只有数字的 55%)。
     *
     * LO / HI / -- 这类没有单位的原样返回 —— 它们本身就是状态词,缩一半反而看不清。
     */
    private CharSequence withUnit(String label) {
        if (!label.endsWith("°")) return label;
        String num = label.substring(0, label.length() - 1);
        // 用「°C」两个字符而不是单字符「℃」:后者缩到一半之后那个度数圈就糊成一团,
        // 屏上看着像个小写 c(实测)。
        android.text.SpannableString s = new android.text.SpannableString(num + "°C");
        s.setSpan(new android.text.style.RelativeSizeSpan(.5f),
                num.length(), num.length() + 2,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    private String tempLabel(float v) {
        if (v < 0) return "--°";
        if (v <= 16f) return "LO";
        if (v >= 33f) return "HI";
        return trimTemp(v) + "°";
    }

    /**
     * 读数一律墨色。
     *
     * 早先按冷暖走色阶(制冷青绿 → 常温墨 → 暖黄 → 热红),2026-08-16 撤掉:
     * 那套语义色和强调色摆在一条 Dock 上像两个系统,而**数字本身就说明了冷热** ——
     * "22 度"和"29 度"不需要再靠颜色说一遍。
     */
    private int tempColor(float v) {
        return ModeManager.get().ink();
    }

    private float tempValue() {
        if (temp == null || temp.isEmpty()) return -1f;
        try {
            return Float.parseFloat(temp);
        } catch (NumberFormatException e) {
            return -1f;
        }
    }

    private int fanShow() {
        return adjFan >= 0 ? adjFan : fan;
    }

    /** 26.0 显示成 26,26.5 保留一位 —— 整数位后面挂个 .0 在车上看着碍眼。 */
    private String trimTemp(float v) {
        return v == Math.rint(v) ? String.valueOf((int) v) : String.valueOf(v);
    }

    // ---- 车控键构造 ----

    /**
     * 温度键:「‹ 26°C ›」占三格,左右一次一度,中间读数点一下开空调面板。
     *
     * 早先是另一套:一格,温度计图标居中、读数缩成角标,靠**按住 350ms 再左右滑**调温。
     * 长按滑动在车上盲操不好使,撤了,改成直接点一下加减,读数 18sp 独占一格当 Dock 的视觉锚点。
     *
     * 跑动效时中间那格裹一层 {@link ThermalFx},制冷时两片小雪花从读数背后飘过,
     * 它同时是整条 Dock 彩蛋的**发射原点**(见 {@link #gust()});不跑动效时不建这一层。
     */
    private View tempButton() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);

        tempText = new TextView(getContext());
        tempText.setTextSize(18f);           // 独占一格,给得比原来的角标(10sp)大
        tempText.setTextColor(ModeManager.get().ink());
        tempText.setGravity(Gravity.CENTER);
        tempText.setFontFeatureSettings("tnum");   // 等宽数字:数值变化不跳字距
        tempText.setContentDescription("温度,点一下开空调面板");
        GradientDrawable midBg = new GradientDrawable();
        midBg.setCornerRadius(ModeManager.get().dimPx(getContext(), Dim.RADIUS_KEY));
        midBg.setColor(Color.TRANSPARENT);
        tempText.setBackground(midBg);
        tempText.setOnClickListener(v -> {
            if (listener != null) listener.onOpenHvacPanel();
        });
        tempShown = "";

        // 中间那格:跑动效时垫一层粒子层(下雪 + 效果场的发射源),否则就是光秃秃的读数。
        // 只有两片雪花、半径也收着 —— 这么小的格子上再多就成脏点了。
        View mid;
        if (ModeManager.get().hvacBasic()) {
            mid = tempText;
        } else {
            tempFx = new ThermalFx(getContext());
            tempFx.setDensity(2, .8f);
            tempFx.addView(tempText, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            mid = tempFx;
        }

        tempStepL = stepKey(R.drawable.ic_chevron_left, "调低温度", -1);
        row.addView(tempStepL, childSlot(3f));
        row.addView(mid, childSlot(4f));
        tempStepR = stepKey(R.drawable.ic_chevron_right, "调高温度", 1);
        row.addView(tempStepR, childSlot(3f));

        keyChrome(row, "温度", 2);
        // 不挂 HvacKey:读数颜色归 tintTempTo 管(开着=青、没开=灰),再染一遍就是两处抢同一个 view
        return row;
    }

    /**
     * 座椅键:通风/加热 × 主驾/副驾,四颗。加热和通风各有自己的字形,主副靠右上角的
     * 「主」「副」角标区分 —— 跟温度/风量的角标是同一套语言,一眼看得出是哪一侧。
     *
     * 开着就点亮 + 换实心字形,跟面板那侧同一套表达(见 HvacPanel.SeatCluster.render)。
     * 一下一档、两档到顶回 0,这条规矩也和面板一致(见 HvacPanel.onSeatLevel)。
     */
    /** 当前风向组合该用哪个字形。映射只有一份,在 {@link SeatFlowView#iconFor} —— 面板中间那颗用的也是它。 */
    private int windModeIcon() {
        return hasToggles ? SeatFlowView.iconFor(windFace, windFoot, windDef)
                          : R.drawable.ic_wind_mode_1;
    }

    /** 按 action 取对应那一路的档位。命名跟 DockHost/HomeActivity 那边同一套解析。 */
    private int seatLevel(String action) {
        boolean deputy = action.endsWith("_r");
        boolean heat = action.contains("_heat_");
        return deputy ? (heat ? seatHeatR : seatVentR)
                      : (heat ? seatHeatL : seatVentL);
    }

    private View seatButton(final String action) {
        boolean deputy = action.endsWith("_r");
        boolean heat = action.contains("_heat_");

        // 竖着摆:上面座椅,下面两格档位。和风向键那格同一个形态(图标 + 底下一条读数),
        // 一颗键交代两件事 —— 开没开看颜色,几档看格子。
        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setClipChildren(false);

        ImageView ic = new ImageView(getContext());
        ic.setImageResource(heat ? R.drawable.ic_seat_heat : R.drawable.ic_seat_vent);
        ic.setColorFilter(ModeManager.get().glyph());
        // 副驾在右边,座椅朝向跟着镜像 —— 和候选面板里那颗保持一致
        if (deputy) ic.setScaleX(-1f);
        // 40dp:和别的车控键(48 - 2*PAD_GLYPH_DP)一样大。别照抄风向键那个 26 ——
        // 键高 48,图标底下只需要给档位条让出 5dp
        box.addView(ic, new LinearLayout.LayoutParams(
                Launchers.dp(getContext(), 40), Launchers.dp(getContext(), 40)));

        // 两格档位。和风量那条同一个画法(见 StepBars)
        StepBars bars = new StepBars(getContext(), 2);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                Launchers.dp(getContext(), 9), Launchers.dp(getContext(), 3));
        blp.topMargin = Launchers.dp(getContext(), 2);
        box.addView(bars, blp);
        seatBars.put(action, bars);

        keyChrome(box, (deputy ? "副驾座椅" : "主驾座椅") + (heat ? "加热" : "通风"), 1);
        // keyChrome 之后建:HvacKey 要拿 view 的 GradientDrawable 背景。
        // 主/副不再叠一个「主」「副」字 —— 座椅字形本身已经镜像过,朝向就是那条信息
        seatKeys.put(action, new HvacKey(box, ic, null));
        Motion.press(box);
        box.setOnClickListener(v -> {
            if (listener != null) listener.onAcAction(action);
        });
        return box;
    }

    // ---- 车控加减 ----

    /*
     * 只给最直白的加减,不做「按住 350ms 进调节态 + 左右滑动」那套,
     * 交互水平对齐迪友(它的空调条也是「⊖ 读数 ⊕」三件套,见 float_view_ac_conditioner_layout)。
     *
     * 这里只换长相和手势,**Dock 数据一个字节不动**:数据里仍然是一个「温度键」,
     * 只是渲染成三格宽(见 DockItem.slots)。换模式不该改写用户自己排过的 Dock。
     */

    /** 加减键点了之后攒着的温度格数(每格 1℃)。 */
    private int stepPendingTemp;

    /**
     * 连点先攒着,停手 300ms 才真发车控指令。
     *
     * 连点先攒着、停手才发:空调写是真实生效的指令,连点五六下就发五六条,
     * 车控通道排起队来反而变成"半天才有反应"。屏上的读数不等它 —— 乐观值当场就上屏。
     */
    private final Runnable sendTempStep = new Runnable() {
        @Override public void run() {
            if (stepPendingTemp != 0 && listener != null) listener.onTempChange(stepPendingTemp);
            stepPendingTemp = 0;
        }
    };

    /** 温度加减一格 = 1℃。还没读到当前温度时不动 —— 没有基准的加减是瞎调。 */
    private void stepTemp(int delta) {
        if (Float.isNaN(adjTemp)) adjTemp = tempValue();
        if (Float.isNaN(adjTemp) || adjTemp < 0) return;
        ensureOnOptimistic();       // 关着就先带起来,和滑动调节一个规矩
        applyAdjust(true, delta);   // 乐观值立即上屏
        stepPendingTemp += delta;
        removeCallbacks(sendTempStep);
        postDelayed(sendTempStep, 300L);
    }



    /**
     * 风向键:「‹ 图标 ›」占三格,左右切吹面 → 吹脚 → 吹风挡,中间那个图标就是当前吹哪儿。
     *
     * 和温度那组同一个形态(尖括号 + 中间读数),整条 Dock 上"要调的东西"长一个样。
     * 完整版把风向做在空调面板的座椅气流曲线上,Dock 上这一格是给两版共用的快捷方式 ——
     * 车上换风向挺常用,不该每次都先把面板叫出来。
     */
    private View windButton() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);

        windIcon = new ImageView(getContext());
        windIcon.setImageResource(windModeIcon());
        windIcon.setColorFilter(ModeManager.get().glyph());

        windBars = new StepBars(getContext(), 7);

        // 中间那格竖着摆:上面是吹哪儿,下面是多大风。一个格子交代两件事,
        // 省下一整格的地方(迪友那个风向键就是这么做的)
        LinearLayout mid = new LinearLayout(getContext());
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                Launchers.dp(getContext(), 38), Launchers.dp(getContext(), 38));
        mid.addView(windIcon, ilp);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                Launchers.dp(getContext(), 24), Launchers.dp(getContext(), 3));
        blp.topMargin = Launchers.dp(getContext(), 2);
        mid.addView(windBars, blp);

        // 中间那块点一下**开空调面板**,不是就地换风向 —— 迪友那个键就是这么分工的
        // (o4/r0.java:两侧加减发风量指令,中间调 l4.f.f().F() 把面板叫出来)。
        // 风向有三个模式、还能同时开,在 48dp 的格子里循环切换不如进面板点得明白;
        // Dock 上这一格只负责**显示**现在吹哪儿、多大风。
        mid.setContentDescription("风向与风量,点一下开空调面板");
        mid.setClickable(true);
        mid.setOnClickListener(v -> {
            if (listener != null) listener.onOpenHvacPanel();
        });

        row.addView(arrowKey(R.drawable.ic_chevron_left, "调低风量", -1), childSlot(3f));
        row.addView(mid, childSlot(4f));
        row.addView(arrowKey(R.drawable.ic_chevron_right, "调高风量", 1), childSlot(3f));

        keyChrome(row, "风向", 2);
        windKey = new HvacKey(row, windIcon, null);
        return row;
    }

    /**
     * 风量档位格:七格,亮几格就是几档。
     *
     * 摆在风向图标底下 —— 风向和风量本来就是一起调的,分成两个键既占地方,
     * 眼睛还要在两处之间来回找。**只读**,调风量在空调面板里(或完整版的长按滑动)。
     */
    /** 风向键两侧的箭头。 */
    private ImageView arrowKey(int iconRes, String desc, final int dir) {
        ImageView iv = new ImageView(getContext());
        iv.setImageResource(iconRes);
        int pad = Launchers.dp(getContext(), PAD_STEP_DP);
        iv.setPadding(pad, pad, pad, pad);
        iv.setColorFilter(ModeManager.get().glyph());
        iv.setContentDescription(desc);
        iv.setOnClickListener(v -> stepFan(dir));
        return iv;
    }

    /**
     * 风量加减一格。0 档 = 风机关。
     *
     * 和温度那边一个路子:乐观值当场上屏(底下的档位格立刻跟着走),连点先攒着,
     * 停手 300ms 才真发指令 —— 空调写是真实生效的车控指令,连点五六下就发五六条,
     * 车控通道排起队反而变成"半天才有反应"。
     */
    private void stepFan(int delta) {
        if (adjFan < 0) adjFan = fan;
        if (adjFan < 0) return;
        ensureOnOptimistic();
        applyAdjust(false, delta);
        removeCallbacks(sendFanStep);
        postDelayed(sendFanStep, 300L);
    }

    private final Runnable sendFanStep = new Runnable() {
        @Override public void run() {
            // 风量发**绝对档位**而不是增量(见 AirCon#fanTo)
            if (adjFan >= 0 && listener != null) listener.onFanLevel(adjFan);
        }
    };

    /** 三格形态里的一个加减键。 */
    private ImageView stepKey(int iconRes, String desc, final int delta) {
        ImageView iv = new ImageView(getContext());
        iv.setImageResource(iconRes);
        int pad = Launchers.dp(getContext(), PAD_STEP_DP);   // 比字形键再收一点:加减号本身笔画少
        iv.setPadding(pad, pad, pad, pad);
        iv.setColorFilter(ModeManager.get().glyph());
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(ModeManager.get().dimPx(getContext(), Dim.RADIUS_KEY));
        bg.setColor(Color.TRANSPARENT);
        iv.setBackground(bg);
        iv.setContentDescription(desc);
        Motion.press(iv);
        iv.setOnClickListener(v -> stepTemp(delta));
        return iv;
    }

    /**
     * 「‹ 读数 ›」这类键内部每一段的宽度。整键占 **2 格**(见 DockItem.slots),
     * 三段按权重分:两侧箭头各 3、中间读数 4。
     *
     * 原来每段各占一整格、整键三格,太吃地方 —— 一条 Dock 统共 20 格
     * (1920@240dpi,见 DockGrid.capacity),温度和风向就吃掉 6 格,加上默认那些键直接超格
     * (实测 21 格,已经有项被 overflowSkips 跳掉了)。压到 2 格后箭头约 33dp 宽、
     * 中间约 43dp,高度仍是整格 48dp —— 竖着够手指落点,横着窄一点可以接受。
     *
     * **段与段之间不留格间距**:那是格与格之间的事,键内部留空反而把可点区域切碎,
     * 手指落在缝里就没反应。三段紧贴,整键宽度正好等于 2 格连排。
     *
     * **LayoutParams 的类型必须匹配直接父容器**(这里是 LinearLayout),类型不对会被丢弃、
     * 尺寸塌成 0,画得出来却点不着。
     */
    private LinearLayout.LayoutParams childSlot(float weight) {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight);
    }

    private View acButton(final String action) {
        if ("fan".equals(action)) {
            // 扇叶占正中,档位数字缩成右上角的小角标。**这一格只管显示**:
            // 点一下开空调面板,调风量在风向键那三格上(‹ 风向 ›,左右一次一档)。
            // 原来这里还能长按滑动调档,2026-08-14 连同温度键那套一起撤了。
            // 跑动效时扇叶按档位转快转慢(Motion.fanSpeed),否则静止 —— 转速是动效。
            FrameLayout box = new FrameLayout(getContext());
            box.setClipChildren(false);
            ImageView ic = new ImageView(getContext());
            ic.setImageResource(R.drawable.ic_fan);
            ic.setColorFilter(ModeManager.get().glyph());
            int fp = Launchers.dp(getContext(), PAD_GLYPH_DP);   // 同上
            ic.setPadding(fp, fp, fp, fp);
            box.addView(ic, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));

            keyChrome(box, "空调开关", 1);
            if (!ModeManager.get().hvacBasic()) fanSpin = new Motion.Spinner(ic);
            fanKey = new HvacKey(box, ic, null);
            // **这颗是空调总开关**(2026-08-14):调风量的活风向键那三格全包了,
            // 扇叶原来只剩"显示档位 + 点开面板",和风向键重复;而总开关一直没有默认位置。
            // 用扇叶而不是电源图标 —— 电源图标在车机上更像"关机",扇叶一眼就是空调。
            //
            // ⚠️ 短按开关空调曾经因为**误触代价大**被撤过(碰一下整车就停止吹风),
            // 这次是明确要回来的。真车上留意这一点。
            box.setOnClickListener(v -> togglePower());
            return box;
        }
        if (action.startsWith("seat_")) return seatButton(action);

        // 独立的电源键 2026-08-14 退役:空调开关归扇叶键(见上),电源图标本身也不要了。
        // 存量配置里的 AC:power 在 DockStore.toGrid 里换成空格。
        int iconRes;
        String label;
        if ("recirc".equals(action))  { iconRes = R.drawable.ic_recirculate; label = "内外循环"; }
        else                          { iconRes = R.drawable.ic_defrost;     label = "前除雾"; }

        ImageView iv = new ImageView(getContext());
        iv.setImageResource(iconRes);
        int pad = Launchers.dp(getContext(), PAD_GLYPH_DP);
        iv.setPadding(pad, pad, pad, pad);
        keyChrome(iv, label, 1);
        HvacKey key = new HvacKey(iv, iv, null);
        Motion.press(iv);

        if ("recirc".equals(action)) {
            recircKey = key;
            iv.setOnClickListener(v -> {
                if (hasToggles) { ensureOnOptimistic(); recirc = !recirc; refreshHvac(true); }
                if (listener != null) listener.onAcAction(action);
            });
            // 长按曾经在这儿开空调全量面板,已撤 —— 整条 Dock 的长按统一是
            // 「按住 + 滑动调值」,循环键没有值可调,就不该占着长按。
            // 面板入口在温度键中间那格短按。
        } else {
            defrostKey = key;
            iv.setOnClickListener(v -> {
                if (hasToggles) { ensureOnOptimistic(); defrost = !defrost; refreshHvac(true); }
                if (listener != null) listener.onAcAction(action);
            });
        }
        return iv;
    }

    /** 车控键的公共外观:透明圆角承载(点亮时上色)、占几格、格间距。 */
    private void keyChrome(View v, String desc, int slots) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(ModeManager.get().dimPx(getContext(), Dim.RADIUS_KEY));
        bg.setColor(Color.TRANSPARENT);
        v.setBackground(bg);
        v.setContentDescription(desc);
        v.setLayoutParams(slotParams(slots));
    }

    /**
     * 一个可点亮的车控键:透明底 ⇄ accentSoft 底 + accent 字形,切换用颜色渐变,
     * 点亮再给一圈 accent 色的投影当辉光(API 28+,低版本只少个光晕,不缺状态)。
     */
    private final class HvacKey {
        final View view;
        final ImageView icon;     // 可空(纯文字键)
        final TextView label;     // 可空(纯图标键)
        private final GradientDrawable bg;
        private ValueAnimator anim;
        private boolean lit;
        private boolean applied;
        private int fgNow;

        HvacKey(View view, ImageView icon, TextView label) {
            this.view = view;
            this.icon = icon;
            this.label = label;
            this.bg = (GradientDrawable) view.getBackground();
            this.fgNow = ModeManager.get().glyph();
        }

        /**
         * 状态只用**字形颜色**表达:开着=accent 青,没开=muted 灰。
         *
         * 不给底色也不给辉光 —— 一部分键有底色、一部分只变色,一条 Dock 上两套表达
         * 就散了;而且相邻的实心块会连成一排砖,边界糊掉、焦点也没了。
         */
        void set(boolean lit, boolean animate) {
            if (applied && lit == this.lit) return;
            applied = true;
            this.lit = lit;
            int fgTo = lit ? ModeManager.get().accent() : ModeManager.get().glyph();
            if (anim != null) anim.cancel();
            if (!animate) {
                apply(fgTo);
                return;
            }
            final int fgFrom = fgNow;
            android.animation.ArgbEvaluator eval = new android.animation.ArgbEvaluator();
            anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(Motion.COLOR_MS);
            anim.addUpdateListener(a ->
                    apply((int) eval.evaluate(a.getAnimatedFraction(), fgFrom, fgTo)));
            anim.start();
        }

        private void apply(int fgColor) {
            fgNow = fgColor;
            if (icon != null) icon.setColorFilter(fgColor);
            if (label != null) label.setTextColor(fgColor);
        }
    }

    // ---- 长按滑动调节 ----


    // ---- 地图/音乐键:长按滑动换 app ----

    /** 换 app 一格的滑动距离。比风量(36dp)还宽 —— 候选通常只有 2~4 个,给窄了一滑就窜过头。 */
    private static final int SLOT_STEP_DP = 64;

    /** 选择态里这一类的候选,和滑到了第几个。进选择态时查一次,松手清空。 */
    private List<AppRef> slotItems;
    private int slotIndex;
    /** 进选择态时的起点下标。滑动中它不变(还没落定),存下来免得每帧去读一次 prefs。 */
    private int slotBase;

    /**
     * 地图/音乐键的长按滑动:按住 350ms 摊开候选,左右滑动换选中,松手落定。
     *
     * **Dock 上现在只剩这一处长按滑动**(温度/风量那套 2026-08-14 撤了,改成三格点按):
     * **连续值/多选项一律「按住 + 滑动 + 松手」,短按只做二选一的开关或打开界面**。
     *
     * 滑到头**停住**不绕回:候选少,绕回反而让人分不清"到底有几个、我在哪儿"。
     * 滑动中只动高亮,**松手才真正换 app** —— 换源要收掉旧卡片再按新源开,
     * 滑过程中每格都换等于连着杀好几个 app。
     */
    /**
     * 应用位键的长按滑动:按住 350ms 摊开候选,左右滑动换选中,松手落定。
     *
     * 四个应用位(地图/音乐/两个运行位)共用这一套:候选都是**用户自己添加的**
     * (见 slotCandidates),自动识别的不掺进来 —— 长按出来的是"我要的这几个",
     * 不是"系统猜的这一堆"。
     *
     * runSlot 只在运行位有效(第几个运行位,0/1),地图/音乐传 -1。
     *
     * 没绑定的运行位也走这儿:候选同样是用户配置好的那批(RunSourceStore),
     * 选中即绑定 —— 四个应用位长按的行为完全一致,不掺"正在运行"的动态候选。
     */
    private void bindSlotSlide(final View btn, final DockItem.Kind kind, final int runSlot) {
        final int stepPx = Launchers.dp(getContext(), SLOT_STEP_DP);
        btn.setOnTouchListener(new OnTouchListener() {
            float startX;
            boolean picking;
            final Runnable enter = new Runnable() {
                @Override public void run() {
                    slotItems = slotCandidates(kind, runSlot);
                    if (slotItems.isEmpty()) return;   // 一个候选都没有(绑定的格不该走到,兜底)
                    // 只有一个候选也照常显示气泡:这一格只配了一个 app 时长按也得
                    // 看见"它是谁",不能静默(2026-08-20 反馈:只设一个 app,长按没反应)
                    picking = true;
                    slideActive = true;
                    Motion.pressOut(btn);
                    slotBase = currentSlotIndex(kind, runSlot, slotItems);
                    slotIndex = slotBase;
                    showSlotBubble(btn);
                }
            };

            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = e.getRawX();
                        picking = false;
                        Motion.pressIn(btn);
                        btn.postDelayed(enter, 350L);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!picking) {
                            if (Math.abs(e.getRawX() - startX) > stepPx) {
                                btn.removeCallbacks(enter);
                                Motion.pressOut(btn);
                            }
                            return true;
                        }
                        int want = Math.round((e.getRawX() - startX) / stepPx) + slotBase;
                        // 夹在两端:滑到头停住
                        want = Math.max(0, Math.min(slotItems.size() - 1, want));
                        if (want != slotIndex) {
                            slotIndex = want;
                            if (bubble != null) bubble.updateSlot(slotNames(slotItems), slotIndex);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        btn.removeCallbacks(enter);
                        Motion.pressOut(btn);
                        if (picking) {
                            picking = false;
                            slideActive = false;
                            if (bubble != null) bubble.hideAfter(500L);
                            AppRef pick = slotIndex >= 0 && slotIndex < slotItems.size()
                                    ? slotItems.get(slotIndex) : null;
                            // 落在原来那个上就什么也不做,别白重开一次卡片
                            if (pick != null && listener != null && slotIndex != slotBase) {
                                if (kind == DockItem.Kind.RUNNING) {
                                    listener.onRunningSlotPicked(runSlot, pick.pkg);
                                } else {
                                    listener.onSlotSourcePicked(kind, pick.pkg);
                                }
                                // 键上显示的是当前源的图标,换完要跟着变。post 一下:
                                // 别在触摸回调里就把自己这个 View 拆了重建
                                post(new Runnable() {
                                    @Override public void run() { build(); }
                                });
                            }
                            slotItems = null;
                        } else if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                            // 只 performClick,**不要**再手动调一次 onOpenSlot ——
                            // 这两个键是 iconButton(..., onClick) 建的,本来就挂着
                            // OnClickListener(内容正是 onOpenSlot),performClick 已经
                            // 会触发一次。调两次的后果是自己抵消:第一次 focused=null
                            // 切成独占,第二次 focused!=null 又切回并排,表现为"点了没反应"
                            // (真车实测)。温度/风量键没这问题,它们没挂 onClick。
                            v.performClick();
                        }
                        return true;
                }
                return false;
            }
        });
    }

    /** 这一格有哪些候选:**用户自己添加的那几个**(当前那个在排头),不掺自动识别。 */
    private List<AppRef> slotCandidates(DockItem.Kind kind, int runSlot) {
        List<String> pkgs;
        if (kind == DockItem.Kind.RUNNING) {
            pkgs = new RunSourceStore(getContext()).load(runSlot);
        } else {
            boolean isMap = kind == DockItem.Kind.MAP;
            pkgs = new ArrayList<>(isMap
                    ? new NavSourceStore(getContext()).load()
                    : new MusicSourceStore(getContext()).load());
        }
        List<AppRef> out = new ArrayList<>();
        for (String p : pkgs) out.add(new AppRef(p, "", AppTypes.label(getContext(), p)));
        return out;
    }

    private int currentSlotIndex(DockItem.Kind kind, int runSlot, List<AppRef> items) {
        String cur;
        if (kind == DockItem.Kind.RUNNING) {
            cur = new RunSourceStore(getContext()).current(runSlot);
        } else {
            boolean isMap = kind == DockItem.Kind.MAP;
            cur = isMap ? new NavSourceStore(getContext()).current()
                        : new MusicSourceStore(getContext()).current();
        }
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).pkg.equals(cur)) return i;
        }
        return 0;
    }

    private List<String> slotNames(List<AppRef> items) {
        List<String> out = new ArrayList<>();
        for (AppRef a : items) out.add(a.label);
        return out;
    }

    private void showSlotBubble(View anchor) {
        Log.i("Blank/Dock", "换 app 选择态:候选 " + slotItems.size() + " 个 "
                + slotNames(slotItems) + ",当前第 " + slotIndex);
        if (bubble == null) bubble = new HvacBubble(getContext());
        List<android.graphics.drawable.Drawable> ds = new ArrayList<>();
        for (AppRef a : slotItems) ds.add(icons != null ? icons.iconFor(a) : null);
        bubble.showSlot(anchor, ds, slotNames(slotItems), slotIndex);
    }

    /**
     * 开/关空调。电源键短按和**风量键短按**都走这儿。
     *
     * 风量键兼作总开关是因为两者本来就是一回事 —— 关空调等于风停,键上的档位数字
     * 直接变成 OFF,状态反馈就长在按下去的那个键上,不用另找地方看。这也让电源键
     * 变成可选的(用户想精简 Dock 就在编辑里把它隐藏掉)。
     */
    private void togglePower() {
        if (hasToggles) { power = !power; refreshHvac(true); }
        if (listener != null) listener.onAcAction("power");
    }

    /**
     * ensureOn 的乐观半边:关机状态下发起任何调节,先把电源置开再执行,
     * 读数立刻从 "OFF" 恢复 —— mock/车机侧的另一半见 AirCon.mockApply,回读只做确认。
     * 电源未知(!hasToggles)时不动,别在一无所知时瞎宣布开机。
     */
    private boolean ensureOnOptimistic() {
        if (!hasToggles || power) return false;
        power = true;
        Launchers.toast(getContext(), "空调已开启");
        return true;
    }

    /** 滑动一格:乐观值立即上屏(Dock 读数 + 气泡),不等车机回读。 */
    private void applyAdjust(boolean isTemp, int delta) {
        if (isTemp) {
            if (!Float.isNaN(adjTemp)) {
                adjTemp = Math.max(17f, Math.min(33f, adjTemp + delta));
            }
        } else {
            if (adjFan >= 0) adjFan = Math.max(0, Math.min(7, adjFan + delta));   // 0 = 风机关
            if (auto) auto = false;   // 手动调风量即退出自动,和车机语义一致
        }
        refreshHvac(true);
    }








    // ---- 其余按钮 ----

    /**
     * 编辑态里一个空格的样子:一圈虚线框,画满自己这一格。
     *
     * 只在编辑态出现 —— 平时空格什么都不画(见 itemView 的 EMPTY 分支)。
     * 一格一个 view,不是一块自绘出 N 个框:拖拽落点认的是**这一项**(见 wrapForEdit
     * 里的 drag listener),一块画 N 个框就只有一个落点,画出来的格子会骗人。
     */
    private final class SlotHint extends View {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        SlotHint(Context c) {
            super(c);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Launchers.dp(c, 1));
            paint.setColor(ModeManager.get().line());
            float dash = Launchers.dp(c, 4);
            paint.setPathEffect(new DashPathEffect(new float[]{dash, dash}, 0f));
            // 硬件加速下 drawRoundRect + PathEffect 的虚线会被吃掉(画成实线),
            // 这一小块交给软件层画
            setLayerType(LAYER_TYPE_SOFTWARE, null);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float inset = paint.getStrokeWidth() / 2f;
            float r = Launchers.dp(getContext(), 12);
            canvas.drawRoundRect(inset, inset, getWidth() - inset, getHeight() - inset, r, r, paint);
        }
    }

    /**
     * 这块屏摆不下时,哪几项这一轮先不画(返回它们在 items 里的下标)。
     *
     * 竖屏车机(或可旋转屏转过去)的宽度只够十来格,而配置是在横屏上排的二十格。
     * 溢出不管的话,LinearLayout 会把超出的部分从**两端**裁掉 —— 返回键和设置键一起没,
     * 而设置键是编辑态和设置页的唯一入口,丢了就再也进不去(真机竖屏实测)。
     *
     * 所以从**中间**开始让位:先撤空格(它们不承载功能),不够再撤中间的可删项。
     * 两端的锚点(左边导航、右边入口)和不可删的四个应用位一个都不动。
     *
     * **只跳不改配置** —— items 一个字不动,转回横屏立刻全回来。数据层裁剪的话,
     * 用户在横屏排好的 Dock 会因为转了一次屏被永久改写,那在车上没法解释。
     *
     * 编辑态一律不跳:× 删除和拖拽都拿 indexOfChild 当 items 的下标(见 wrapForEdit),
     * 少画一项就会错位、删错东西。编辑态宁可溢出 —— 那时面板上有「完成」退得出去。
     */
    private java.util.Set<Integer> overflowSkips() {
        if (editing) return java.util.Collections.emptySet();
        int over = DockGrid.used(items) - DockGrid.capacity(getContext());
        if (over <= 0) return java.util.Collections.emptySet();

        List<Integer> blanks = new ArrayList<>(), others = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            DockItem it = items.get(i);
            if (it.kind == DockItem.Kind.EMPTY) blanks.add(i);
            else if (DockItem.removable(it.kind)) others.add(i);
        }
        // 离中间越近越先撤:两端是盲操摸得到的地方,动了就白瞎了通栏
        final int mid = items.size() / 2;
        java.util.Comparator<Integer> byCenter =
                (a, b) -> Math.abs(a - mid) - Math.abs(b - mid);
        java.util.Collections.sort(blanks, byCenter);
        java.util.Collections.sort(others, byCenter);

        java.util.Set<Integer> skip = new java.util.HashSet<>();
        for (List<Integer> pool : java.util.Arrays.asList(blanks, others)) {
            for (int i : pool) {
                if (over <= 0) break;
                skip.add(i);
                over -= items.get(i).slots();
            }
        }
        return skip;
    }

    /** 屏幕转了/尺寸变了:格数跟着变,整条重铺一遍(见 overflowSkips)。 */
    @Override
    protected void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        build();
    }

    /**
     * 空着的应用位:格子中间写个「应用」。
     *
     * 不留成一片空白 —— 这两格是**只能放应用**的固定位子,不标出来的话它和旁边的
     * 普通空格长得一模一样,谁也不知道那儿是干嘛的、为什么拖别的键进不去。
     */
    private View appSlotHint() {
        TextView tv = new TextView(getContext());
        tv.setText("应用");
        tv.setTextSize(12f);
        tv.setTextColor(ModeManager.get().glyph());
        tv.setGravity(Gravity.CENTER);
        tv.setContentDescription("应用位");
        // 长按空应用位进编辑面板:四个应用位只配了一个 app 时,剩下三格长按也得
        // 有入口,不然"想往空格加 app"没有任何反馈(2026-08-20 反馈)。
        tv.setOnLongClickListener(v -> {
            if (listener != null) listener.onEditDock();
            return true;
        });
        tv.setLayoutParams(slotParams(1));
        return tv;
    }

    /**
     * 一个空着的格子。平时**什么都不画** —— 它只占位,看上去就是一片空白,和网格化之前的
     * Dock 没有分别;一排看得见的方块会把整条切碎,雪花那类横穿 Dock 的动效也没法从这头
     * 吹到那头。只有编辑态才把它显出来,让人知道哪儿能放。
     */
    private View emptySlot() {
        View slot = editing ? new SlotHint(getContext()) : new View(getContext());
        slot.setLayoutParams(slotParams(1));
        return slot;
    }

    /**
     * 编辑态里被模式隐藏的项(如完整版下的风向键)的占位:0 宽、无边距,看不见、
     * 也拖不中(0 宽,{@link #indexAt} 的区间判定永远落不进去)。它只干一件事 ——
     * 保住「子视图下标 == items 下标」这条编辑逻辑的地基:swap / × 删除 / placeAt
     * 全拿 indexOfChild 当 items 的下标用,少画一项就整条错位(见 overflowSkips 的注释)。
     * 项本身的数据原样保留,切回渲染它的模式就回来 —— 模式只改长相,不动用户排的 Dock。
     */
    private View hiddenStub() {
        View v = new View(getContext());
        v.setLayoutParams(new LayoutParams(0, dockH()));
        return v;
    }

    /** 占 n 格的项:宽度是 n 格连排的宽度,右边留一个格间距(末格的由 build 抹掉)。 */
    private LayoutParams slotParams(int slots) {
        LayoutParams lp = new LayoutParams(slotWidth(slots), dockH());
        lp.setMarginEnd(gap());
        return lp;
    }

    /** n 格连着摆有多宽:n 个格身 + 中间 n-1 个格间距。 */
    private int slotWidth(int slots) {
        return slots * dockH() + (slots - 1) * gap();
    }

    /**
     * 车控区与 app 区之间的竖线。**占一整格**,线画在格子正中 —— 网格化之后每一项都是
     * 整数格,分隔线也不例外。
     *
     * 顺带解决了编辑态的老问题:线只有 1dp 宽时手指摸不着、× 角标也没处挂,以前得另做
     * 一个 40dp 的"编辑态分隔线"。现在它本来就是一格宽,两种状态用同一个。
     */
    private View divider() {
        FrameLayout box = new FrameLayout(getContext());
        View line = new View(getContext());
        line.setBackgroundColor(ModeManager.get().line());
        line.setTag("bg:line");
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(
                Launchers.dp(getContext(), 1), Launchers.dp(getContext(), 32));
        llp.gravity = Gravity.CENTER;
        box.addView(line, llp);
        box.setLayoutParams(slotParams(1));
        return box;
    }

    private View iconButton(int iconRes, String contentDesc, OnClickListener onClick) {
        ImageView iv = baseIconButton(contentDesc, onClick, PAD_GLYPH_DP);
        iv.setImageResource(iconRes);
        // 幽灵键:平时 muted 灰字形,无底色 —— 整条 Dock 才是那块"面",按键不再各垫一块砖
        iv.setTag("tint:glyph");
        iv.setColorFilter(ModeManager.get().glyph());
        return iv;
    }

    /**
     * 用 app 原生图标的按键(地图/音乐槽位用)。
     *
     * **不 tint** —— 原生图标自带配色,染成灰的就没意义了。这跟运行区那些 app 图标
     * 是一个处理(见 runningButton),内边距也取同一个 {@link #PAD_APP_DP}:app 图标
     * 自己就带留白,按线性字形那样再多垫一圈会显得小一号。
     */
    private View appIconButton(android.graphics.drawable.Drawable icon, String contentDesc,
                               OnClickListener onClick) {
        ImageView iv = baseIconButton(contentDesc, onClick, PAD_APP_DP);
        iv.setImageDrawable(icon);
        return iv;
    }

    private ImageView baseIconButton(String contentDesc, OnClickListener onClick, int padDp) {
        ImageView iv = new ImageView(getContext());
        iv.setContentDescription(contentDesc);
        int pad = Launchers.dp(getContext(), padDp);
        iv.setPadding(pad, pad, pad, pad);
        Motion.press(iv);
        iv.setOnClickListener(onClick);

        // 图标 28dp + 左右 10dp = 48dp,正好和行高等宽——按键是正方的
        LayoutParams lp = slotParams(1);
        iv.setLayoutParams(lp);
        return iv;
    }

    private View appButton(final AppRef app) {
        ImageView iv = new ImageView(getContext());
        if (icons != null) iv.setImageDrawable(icons.iconFor(app));
        iv.setContentDescription(app.label);
        // padding 比线性字形键小:车控键的字形自带一圈留白;app 用的是原生图标,
        // 多是满铺方图,同样大的显示区里显得更满,所以少收一点边(见 PAD_APP_DP)。
        int pad = Launchers.dp(getContext(), PAD_APP_DP);
        iv.setPadding(pad, pad, pad, pad);
        Motion.press(iv);
        iv.setOnClickListener(v -> { if (listener != null) listener.onLaunch(app); });

        LayoutParams lp = slotParams(1);
        iv.setLayoutParams(lp);
        return iv;
    }

    /**
     * Dock 行高。48dp —— 迪友桌面的导航栏整条 60dp、图标格子占 0.8 也是 48dp,
     * 同一块 1920x1080 @ 240dpi 的屏上,这就是验过的手指尺寸。
     *
     * 早先给到 52dp,格子撑大了图标却只有 24dp,整条读起来又厚又空。收到 48dp 的同时
     * 把格子内边距一并收紧(见 {@link #PAD_GLYPH_DP} 那组),图标反而比原来大一圈 ——
     * **想让图标更大就调那三个值,别动这里**:格子是手指尺寸,而且 48 + 上下 4dp padding
     * 正好等于 @dimen/dock_space(56dp),动了它舞台高度和画中画布局都要跟着改。
     * (2026-08-18 嫌整条高,收的是那对 padding,不是这里。)
     */
    private int dockH() {
        return Launchers.dp(getContext(), 48);
    }

    /**
     * 项与项之间的间距。**按屏宽算**(见 DockGrid.gapPx),不是写死的 13dp ——
     * 13dp 只是下限,格数除不尽剩下的零头均摊进来,免得全堆在最右边。
     *
     * 13dp 这个下限对齐原型的从容感:8dp 时按键挤在一起,整条读起来像工具栏而不是 Dock。
     */
    private int gap() {
        return DockGrid.gapPx(getContext());
    }

    /** 气泡是 PopupWindow,Dock 被移出窗口时必须收掉,否则会漏一个悬浮窗。 */
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (bubble != null) bubble.dismiss();
        if (fanSpin != null) fanSpin.cancel();
        fxStop();
    }

    // ---- 效果场:绘制钩子与帧循环 ----

    /**
     * 起停挂 onVisibilityAggregated,**不挂 onAttached/onDetached** —— DockHost 的显隐
     * 只切 visibility、从不 removeView,Dock 一挂上就几乎永不 detach。靠 onDetached 的话,
     * 被全屏 app 盖住时粒子还在后台烧 CPU(而 Dock 是常驻悬浮窗,这个代价一直在付)。
     */
    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        fxVisible = isVisible;
        if (!isVisible) fxStop();
        else if (fx != null && fx.animating()) fxStart();
    }

    private void fxStart() {
        if (fxRunning || fx == null || !fxVisible) return;
        fxRunning = true;
        Choreographer.getInstance().postFrameCallback(fxFrame);
    }

    private void fxStop() {
        fxRunning = false;
        Choreographer.getInstance().removeFrameCallback(fxFrame);
    }
}
