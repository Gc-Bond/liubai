package com.liubai.blank.ui;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;

import com.liubai.blank.R;
import com.liubai.blank.mode.ModeApplier;
import com.liubai.blank.privileged.ForegroundWatch;
import com.liubai.blank.privileged.Shell;
import com.liubai.blank.util.Immersive;
import com.liubai.blank.util.Launchers;
import com.liubai.blank.util.OemEnv;
import com.liubai.blank.util.Overlay;

/**
 * 常驻顶栏的窗口宿主。内容那一半在 {@link StatusBarView}。
 *
 * 这是 {@link DockHost} 的镜像:同一套悬浮窗基建(无障碍在线挂 2032、不在退 2038、
 * 挂不上就退避重试)、同一套让位规矩(进原车环境就收起来)。同平台两家量产桌面的顶栏
 * 也是这个形状(DiStyle {@code FloatTopBarBaseView},反编译 v2.1.6 {@code v6/r.java:578}:
 * {@code type=2038 / gravity=48 / y=0 / height=status_bar_height})。
 *
 * <h3>为什么顶栏不吃触摸</h3>
 *
 * 窗口带 {@link WindowManager.LayoutParams#FLAG_NOT_TOUCHABLE} —— DiStyle 在
 * Android 10 上也是这么给的(flags {@code 0x4000738} 含 {@code 0x10},只有 API>29 才去掉)。
 * 理由不是"顶栏没东西可点",是**顶栏正压在第三方 app 的顶部操作区上**:百度地图那个
 * 搜索框、B 站顶部那排 tab 都在 y=0 那一条里。吃了触摸就是把人家的按钮点死 ——
 * 2026-08-16 在模拟器上刚见识过反面版本:系统导航栏(层级 231000)压在 Dock 之上,
 * Dock 图标中心整个落在它的触摸区里,点哪都没反应、日志一条不出。
 *
 * <h3>占位从哪来</h3>
 *
 * 和 Dock 白嫖导航栏占位是同一笔账:**系统状态栏必须继续存在**,它给每个 app 发的
 * top inset 就是天然安全区,app 内容自己缩到状态栏以下,顶栏盖在那条位置上。
 * 所以 {@link com.liubai.blank.util.Immersive} 里那条 {@code immersive.status} 压制
 * 2026-08-16 撤掉了 —— 压掉它,app 内容就铺到 y=0,顶栏会把人家顶部啃掉一条。
 *
 * 留白**自己的** Activity 不吃这份 inset(它们调 {@code Immersive.apply} 铺满物理屏),
 * 得自己在布局顶部让出 {@code @dimen/status_bar_space},同 {@code dock_space}。
 */
public final class StatusBarHost {

    private static final String TAG = "Blank/StatusBar";

    /** 自己一份,不蹭 Dock 那份:两条栏各开各的,键混在一起以后谁清谁的数据都说不清。 */
    private static final String PREFS = "status_bar";
    private static final String KEY_ENABLED = "enabled";

    private static StatusBarHost instance;

    private final Context appCtx;
    private final Handler main = new Handler(Looper.getMainLooper());

    private WindowManager wm;
    private View root;
    private StatusBarView bar;
    private boolean added;

    /** 人是不是正站在留白桌面上。决定顶栏底下透不透(同 DockHost.effectiveOpacity)。 */
    private boolean desktopResumed;

    private StatusBarHost(Context ctx) {
        appCtx = ctx.getApplicationContext();
        ForegroundWatch.addListener(pkg -> {
            // 幂等,Immersive/DockHost 那边也会各调一次 —— 谁先跑都行,
            // 重点是在 applyVisibility 之前把"在不在原车环境"算准
            OemEnv.onForeground(appCtx, pkg);
            main.post(this::applyVisibility);
        });
    }

    public static synchronized StatusBarHost get(Context ctx) {
        if (instance == null) instance = new StatusBarHost(ctx);
        return instance;
    }

    /** 挂上没有。挂不上就是屏幕上没有顶栏。 */
    public boolean attached() { return added && root != null; }

    // ---- 要不要这条栏 ----

    /**
     * 用户开着顶栏没有(外观面板 › Dock 页那一行)。默认开。
     *
     * 和 Dock 的常驻开关是两码事:Dock 那个管"什么时候露面"(常驻 / 仅桌面),
     * 这个管"要不要有" —— 关掉就压根不挂窗口,连桌面上也没有。原厂状态栏一直在
     * (留白从不压它,见《边界总纲》),关掉顶栏看到的就是原厂那条。
     */
    /**
     * 顶栏这个功能<b>整个先藏起来</b>(2026-09-05)。代码一行没删,把这里改回 true 就全回来:
     * 窗口、编排、外观面板里那个「顶栏」tab 一起活过来(那个 tab 的显隐也读这个字段)。
     *
     * <p>放一个常量而不是改 prefs 默认值:已经开过的车上 prefs 里存着 true,只改默认值
     * 对它们没用,顶栏照旧挂着。
     */
    public static final boolean FEATURE_ON = false;

    public static boolean enabled(Context ctx) {
        if (!FEATURE_ON) return false;
        return ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(Context ctx, boolean on) {
        ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, on).apply();
        StatusBarHost inst = get(ctx);
        if (on) inst.attach();
        else inst.detach();
    }

    // ---- 窗口 ----

    /**
     * 建窗口。幂等:已经挂上了就直接返回 true。
     *
     * 返回 false 是"这一次没挂上",不是"挂不上了" —— 和 Dock 一样挂退避重试,
     * 会自己回来。调用方不用兜底。
     */
    public boolean attach() {
        if (added) return true;
        // 关着就压根不建窗。**这一句要在最前面** —— 后面挂不上会排重试,
        // 用户关掉之后那条重试链照样会把窗口挂回来
        if (!enabled(appCtx)) return false;
        Context host = ForegroundWatch.get();
        if (host == null && !Overlay.canDraw(appCtx)) {
            // 悬浮窗权限归 DockHost 那边去授(走提权 shell、阻塞 IO,一次就够两条窗口用)。
            // 这里只是这一轮挂不上,等它授完由重试兜回来
            Log.i(TAG, "没有悬浮窗权限,等 Dock 那边授完再重试");
            scheduleRetry();
            return false;
        }
        try {
            Context winCtx = host != null ? host : appCtx;
            wm = (WindowManager) winCtx.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return false;

            // 同 DockHost:必须包一层模式,里面的控件要靠它解析 attr
            Context moded = new ContextThemeWrapper(appCtx, R.style.BlankTheme);
            root = LayoutInflater.from(moded).inflate(R.layout.window_status_bar, null);
            bar = root.findViewById(R.id.status_bar_root);
            root.setVisibility(View.GONE);   // 先挂上再按策略决定露不露,避免闪一下

            if (!Overlay.add(wm, root, params())) {
                root = null;
                bar = null;
                scheduleRetry();
                return false;
            }
            added = true;
            retryStep = 0;
            applySkin();
            // 提权是异步就绪的:车内温度那一项冷启动时读不到,连上后补一拍
            Shell.onReady(() -> main.post(this::refresh));
            applyVisibility();
            Log.i(TAG, "顶栏悬浮窗已挂上");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "挂顶栏悬浮窗失败: " + e);
            added = false;
            root = null;
            bar = null;
            scheduleRetry();
            return false;
        }
    }

    public void detach() {
        if (root != null && wm != null) {
            try {
                wm.removeView(root);
            } catch (Exception e) {
                Log.w(TAG, "摘顶栏失败(多半已被系统收走): " + e);
            }
        }
        root = null;
        bar = null;
        added = false;
    }

    /** 无障碍服务上下线:窗口挂的是它的 Context,服务一换就得重挂(同 DockHost)。 */
    public void onAccessibilityChanged() {
        detach();
        attach();
    }

    private static final long[] RETRY_DELAYS = {1_000L, 3_000L, 10_000L, 30_000L};
    private int retryStep;
    private boolean retryScheduled;

    private void scheduleRetry() {
        if (added || retryScheduled) return;
        long delay = RETRY_DELAYS[Math.min(retryStep, RETRY_DELAYS.length - 1)];
        retryStep++;
        retryScheduled = true;
        main.postDelayed(() -> {
            retryScheduled = false;
            if (!added) attach();
        }, delay);
    }

    /**
     * 窗口参数。绝对定位在 y=0,高度取系统状态栏那条的高度。
     *
     * 不用 {@code MATCH_PARENT} 也不用 gravity 之外的花样:顶栏要盖住的正是系统状态栏
     * 那一条,而那条的高度只有系统自己知道(真车 90px / 模拟器 36px,同一份 dimen)。
     *
     * {@code LAYOUT_NO_LIMITS} 不能省 —— 系统栏区域在可用区之外,没有它画不过去。
     */
    private WindowManager.LayoutParams params() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                barHeight(appCtx),
                Overlay.windowType(ForegroundWatch.running()),
                // NOT_TOUCHABLE:顶栏纯展示,触摸一律穿透给下面的 app(见类注释)
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = 0;
        return lp;
    }

    /**
     * 顶栏高度 = 系统状态栏高度,**不多不少**。
     *
     * y=0 对齐,等高即精确覆盖 —— 顶栏的职责就是盖住系统状态栏那一条,多一分就开始啃
     * app 内容,少一分就从边缘露出原厂状态栏。DiStyle 也是这一句
     * (反编译 v2.1.6 {@code v6/r.java:590},{@code height = status_bar_height},没有下限)。
     *
     * 曾经在这里加过 40dp 下限,理由是"模拟器上 status_bar_height 只有 36px,
     * 16sp 的时间字站不住"。那是拿模拟器的短处去改产品尺寸:真车是 90px,
     * 下限根本不生效,只让离车环境的顶栏平白高出 24px、跟真车比例对不上。
     * 要在模拟器上看到真车比例,跑 {@code ./tools/car-avd.sh cutout} 把顶部 90px 复刻出来。
     *
     * 兜底的 24dp 只对付"连这个 dimen 都取不到"的机器,正常路径上取不到它。
     */
    public static int barHeight(Context ctx) {
        Resources res = ctx.getResources();
        int id = res.getIdentifier("status_bar_height", "dimen", "android");
        int sys = id > 0 ? res.getDimensionPixelSize(id) : 0;
        return sys > 0 ? sys : Launchers.dp(ctx, 24);
    }

    /**
     * 把页面顶部那块让位改成顶栏的真实高度。
     *
     * 留白自己的 Activity 铺满物理屏(见 {@link com.liubai.blank.util.Immersive#apply}),
     * 拿不到系统的 top inset,得自己让位 —— 布局里那个 {@code @id/status_bar_space}
     * 只写了个兜底 dp,真值要运行时改(真车 90px vs 兜底 40dp=60px,差着一排车控键)。
     *
     * 找不到那个 id 就什么也不做:不是每个页面都需要让位(全屏播放这类)。
     *
     * 顶栏关掉了就**不留**这段 —— 让位是为顶栏让的,顶栏不在还空一条,页面顶上
     * 就白挂一段 90px 的空白。开关一切完要立刻再调一次(见 AppearancePopup 那行)。
     *
     * 用户选了露出原厂状态栏(见 {@link Immersive#oemStatusBar})也让同一段:
     * 那条栏和留白顶栏等高、同位,让位是同一笔账。
     */
    public static void applyTopSpace(View pageRoot) {
        if (pageRoot == null) return;
        View space = pageRoot.findViewById(R.id.status_bar_space);
        if (space == null) return;
        Context ctx = pageRoot.getContext();
        int h = enabled(ctx) || Immersive.oemStatusBar(ctx) ? barHeight(ctx) : 0;
        if (space.getLayoutParams().height != h) {
            space.getLayoutParams().height = h;
            space.requestLayout();
        }
    }

    // ---- 外观 ----

    /** 换模式/换壁纸后重新上色。顶栏和 Dock 共用一套 token,长相是一对。 */
    public void applySkin() {
        if (bar == null) return;
        ModeApplier.apply(bar);
        applyOpacity();
    }

    /**
     * 顶栏底下透不透。
     *
     * 跟 Dock 是同一个值(用户在外观面板里调的那个),也跟 Dock 同一条规矩:
     * **只在留白桌面上才半透** —— 那时底下实打实是壁纸;浮在别的 app 之上一律实色,
     * 透出去就是把人家的内容和原厂状态栏亮给用户(见 DockHost.effectiveOpacity 的说明)。
     */
    private void applyOpacity() {
        if (bar == null) return;
        View face = bar.findViewById(R.id.status_bar);
        if (face != null) {
            ModeApplier.opacity(face, desktopResumed ? DockHost.opacity(appCtx) : 100);
        }
    }

    /** 桌面进出前台。由 HomeActivity 在 onResume/onPause 调,同 Dock。 */
    public void onDesktopResumed(boolean resumed) {
        desktopResumed = resumed;
        applyOpacity();
    }

    /** 壁纸换了,底色跟着壁纸走时要重刷(桌面铺完壁纸后调)。 */
    public void onWallpaperChanged() {
        applySkin();
    }

    /**
     * 编排改了(外观面板 › 顶栏页动了项),把顶栏重铺一遍。
     *
     * 顶栏是悬浮窗、不跟着 Activity 重建,所以改完必须显式叫它一声 ——
     * 同 {@code applySkin} 那几处的道理。
     */
    public void onItemsChanged() {
        if (bar != null) bar.rebuild();
    }

    /** 内容刷一遍(提权刚就绪、模式刚换完这些时候)。 */
    public void refresh() {
        if (bar != null) bar.refresh();
    }

    // ---- 显隐 ----

    /**
     * 常驻。例外只有三条,都是显式的:
     *
     * <ol>
     *   <li>让位给原车环境 —— 和 Dock 同一条判据(见《边界总纲》);</li>
     *   <li>{@link #setHidden} 临时收起 —— 独占屏幕的页面(游戏中心)。
     *       桌面上那个上滑手势**不碰这条栏**,它只收卡片(见
     *       {@code DeskCardHost.setHidden});</li>
     *   <li>{@link #enabled} 关掉 —— 那时窗口压根没挂,走不到这儿。</li>
     * </ol>
     *
     * 没有"只在桌面显示"那一档:上下两条一个在一个不在,观感是断的。
     */
    private void applyVisibility() {
        if (root == null) return;
        root.setVisibility(OemEnv.active() || hidden ? View.GONE : View.VISIBLE);
    }

    /**
     * 有页面要独占整块屏 —— 现在只有游戏中心。
     *
     * 顶栏那 90px 卡着老游戏的放大倍数:整数倍放大只有 ×1/×2/×3,街机 640×480 要 ×2
     * 就得 960,而 1080 让掉顶栏(90)和 Dock(96)只剩 894 —— 差 66px,整档退回 ×1,
     * 画面直接缩水一半。收起顶栏后能用 984,刚好放得下(GBA、NES 也跟着各提一档)。
     *
     * **Dock 不动**:它是留白的立身之本,而且 984 已经够用。
     */
    private static boolean hidden;

    public static void setHidden(boolean h) {
        if (hidden == h) return;
        hidden = h;
        StatusBarHost inst = instance;
        if (inst != null) inst.main.post(inst::applyVisibility);
    }
}
