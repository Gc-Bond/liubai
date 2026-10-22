package com.liubai.blank.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.ContextThemeWrapper;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.liubai.blank.AppDrawerActivity;
import com.liubai.blank.HomeActivity;
import com.liubai.blank.R;
import com.liubai.blank.data.AppTypes;
import com.liubai.blank.data.DockBlacklist;
import com.liubai.blank.data.DockStore;
import com.liubai.blank.data.NavSourceStore;
import com.liubai.blank.data.RunSourceStore;
import com.liubai.blank.icon.IconEngine;
import com.liubai.blank.model.AppRef;
import com.liubai.blank.model.DockItem;
import com.liubai.blank.privileged.AirCon;
import com.liubai.blank.mode.ModeManager;
import com.liubai.blank.media.MusicSourceStore;
import com.liubai.blank.privileged.ForegroundWatch;
import com.liubai.blank.privileged.Shell;
import com.liubai.blank.privileged.Tasks;
import com.liubai.blank.util.Immersive;
import com.liubai.blank.util.Launchers;
import com.liubai.blank.util.OemEnv;
import com.liubai.blank.util.Overlay;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * 常驻 Dock 的宿主:把 Dock 从桌面 Activity 里搬进一个系统悬浮窗。
 *
 * 为什么必须这么做:Dock 原来是 activity_home 布局里的一个子 View,生命周期绑死在
 * HomeActivity 上 —— 一进设置页/抽屉,桌面 onStop,Dock 跟着消失。悬浮窗不属于任何
 * Activity,才谈得上"常驻"。这条路是照 DiStyle(反编译 v2.1.6,类 v6.j / FloatNavBarBaseView)
 * 在同一 DiLink 平台上的做法走的,窗口 flag 组合也一致。
 *
 * **全 app 只有这一条 Dock**:桌面上、别的 app 之上、编辑态,看到的都是同一个实例。
 * 桌面窗口里那条降级/编辑用的 localDock 已经删掉 —— 两份实例配两套显隐规则,是
 * "Dock 莫名其妙不见了"反复复发的病根:标志位由外部 Activity 负责清,而 Activity
 * 随时会死,清不掉就永久卡住。现在显隐只有 {@link #applyVisibility} 一个出口,
 * 它读的全是本类自己的状态,没有需要别人来清的东西。
 *
 * 编辑 Dock 也在这个窗口里做(见 {@link #onEditDock}):编辑面板作为 Dock 的兄弟节点
 * 插进同一棵树,窗口临时切成全屏 + 收焦点。这一步的前提是拖拽不再走系统拖放
 * (见 {@link DockView#onInterceptTouchEvent})。
 *
 * 显隐一律只切 visibility,绝不 addView/removeView:反复挂摘会闪、会丢按压动画的状态,
 * 而且每次都要重建整条 Dock。DiStyle 也是这么处理的(hideView 只 setVisibility(GONE))。
 *
 * 行为转发:Dock 上的键大多要桌面才办得到(画中画决策只有 HomeActivity 拿得到两张卡的
 * 状态),所以桌面活着时全部原样转发给它;桌面不在时走降级路径 —— 该拉桌面的先拉桌面,
 * 纯车控和返回键则不依赖任何 Activity,直接走提权链。
 */
public final class DockHost implements DockView.Listener {

    private static final String TAG = "Blank/DockHost";

    private static final String PREFS = "dock_host";
    /** 常驻开关:开=在任何 app 之上都显示;关=只在留白桌面里显示。 */
    private static final String KEY_ALWAYS_ON = "always_on";
    /** Dock 底色不透明度(0~100)。默认 100 = 和以前一样的实色。 */
    private static final String KEY_OPACITY = "opacity";

    private static DockHost instance;

    private final Context appCtx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final DockStore store;
    private final IconEngine icons;
    /** 用户自己加的让位名单,**默认是空的**。要无障碍在线才判得了。 */
    private final DockBlacklist blacklist;
    /** 运行区要跳过地图/音乐(它们有专属键),所以这里也要知道当前源是谁。 */
    private final NavSourceStore navSources;
    private final MusicSourceStore musicSources;
    /** 两个运行位自己添加的 app 列表(见 RunSourceStore)。 */
    private final RunSourceStore runSources;

    private WindowManager wm;
    /**
     * 窗口内容的根。Dock 和编辑面板都住在这里面 —— 挂窗口、切显隐、改窗口参数
     * 认的都是它,{@link #dock} 只是它里头那条 Dock。
     */
    private FrameLayout root;
    private DockView dock;
    private boolean added;
    /** 编辑面板。第一次进编辑态才建,之后留着复用。 */
    private DockEditPanel editPanel;
    /** 桌面 Activity。它活着就由它处理 Dock 的行为,死了才走降级。 */
    private WeakReference<DockView.Listener> delegate;
    /** 桌面在不在前台。决定"该不该先把桌面拉起来",也驱动非常驻模式下的显隐。 */
    private boolean desktopResumed;
    /** 桌面还没到前台时攒下的动作,见 onDesktop。到 onDesktopResumed 那一刻才真正执行。 */
    private Runnable pendingDesktopAction;
    /**
     * 应用抽屉在不在前台。它跟桌面同包但不是桌面 —— 用户已经站在这页上时,
     * 再点一下 Dock 上对应的键不该走 onDesktop 那套"先清到桌面、再重新拉起"的流程
     * (singleTask 的桌面会把它从任务栈顶清掉),那样等于把这页关了又重开一次,
     * 状态清零不说,过程里桌面还会露一帧。见 AppDrawerActivity 的 onResume/onPause。
     */
    private boolean drawerResumed;

    public void onDrawerResumed(boolean resumed) { drawerResumed = resumed; }

    private DockHost(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
        this.store = new DockStore(appCtx);
        this.icons = new IconEngine(appCtx);
        this.blacklist = new DockBlacklist(appCtx);
        this.navSources = new NavSourceStore(appCtx);
        this.musicSources = new MusicSourceStore(appCtx);
        this.runSources = new RunSourceStore(appCtx);
        ForegroundWatch.addListener(this::onForegroundChanged);
        // 屏幕转向了要重算 y —— 它是从物理屏高倒推的绝对值(见 dockTop),转过来就废了。
        // 车机固定横屏,平时走不到这条;但模拟器上开一个强制竖屏的 app 就能复现:
        // 整个 display 转成 1080x1920,Dock 还停在 y=984,正好卡在屏幕中间。
        // 悬浮窗不属于任何 Activity,收不到 onConfigurationChanged,只能挂在 application 上
        appCtx.registerComponentCallbacks(new android.content.ComponentCallbacks() {
            @Override public void onConfigurationChanged(android.content.res.Configuration c) {
                main.post(DockHost.this::reposition);
            }

            @Override public void onLowMemory() { }
        });
        // 提权一就绪就再试一次挂窗口:冷启动时 appops 授权要等提权连上才做得了,
        // 头一次 attach 多半是失败的。回调可能被触发多次(两条通道),attach 自己幂等
        Shell.onReady(() -> main.post(() -> {
            if (!added && attach()) rebuild();
        }));
    }

    public static synchronized DockHost get(Context ctx) {
        if (instance == null) instance = new DockHost(ctx);
        return instance;
    }

    // ---- 常驻开关 ----

    /** 默认开:装了留白就是要它常驻,不然这个功能等于没做。 */
    public static boolean alwaysOn(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ALWAYS_ON, true);
    }

    public static void setAlwaysOn(Context ctx, boolean on) {
        ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ALWAYS_ON, on).apply();
        if (instance != null) instance.applyVisibility();
    }

    /**
     * Dock 底色的不透明度(0~100)。
     *
     * 有壁纸之前这件事没有意义(底下也是纯色),现在 Dock 压着整条屏幕下缘,
     * 透出一点壁纸观感差很多。**不做成连续滑块**:车上手指粗、车还在动,
     * 滑块拖不准;而且低于某个值图标就压不住背景了,给几个挑好的档位更实在。
     */
    public static int opacity(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_OPACITY, 100);
    }

    public static void setOpacity(Context ctx, int percent) {
        ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_OPACITY, percent).apply();
        if (instance != null) instance.applyOpacity();
    }

    /**
     * 把底色和不透明度刷到 Dock 上。**换壁纸、换模式、改设置、重挂之后都要调一次。**
     *
     * 底色本身不在这儿决定 —— Dock 和面板、弹窗共用 surface 那一个 token,
     * 谁来定、跟不跟壁纸走,统一在 {@link ModeManager#surface()} 里(设置在外观弹层的配色 tab)。
     * 这里只负责把当前值刷到这条 Dock 的背景上,因为它是悬浮窗、不跟着 Activity 重建。
     */
    private void applySkin() {
        if (dock == null) return;
        android.graphics.drawable.Drawable d = dock.getBackground();
        if (d instanceof android.graphics.drawable.GradientDrawable) {
            ((android.graphics.drawable.GradientDrawable) d).setColor(ModeManager.get().surface());
        }
        applyOpacity(dock, effectiveOpacity());
    }

    /**
     * 这一刻真正该用的不透明度。**Dock 底下只允许透出壁纸**,应用内容和原厂导航栏都不行。
     *
     * 用户设的那个值(见 {@link #opacity})只在站在留白桌面上时成立 —— 那时桌面窗口铺满
     * 物理屏、自己把导航栏藏了(见 {@link Immersive#apply}),Dock 底下实打实是壁纸。
     *
     * 浮在别的 app 之上时一律实色:底下是那个 app 的内容,还有正占着位的原厂导航栏
     * (它是 Dock 占位的立身之本),透出去就是把原厂的返回/home/方框亮给用户,
     * 违反《边界总纲》。
     *
     * 切 app 那一下会从半透变实色,**硬切不做过渡** —— 它跟着 app 切换那一帧一起发生,
     * 反而看不出来;慢淡入才会被看成"Dock 闪了一下"(同 pip 聚焦切换那次的结论)。
     */
    private int effectiveOpacity() {
        return desktopResumed ? opacity(appCtx) : 100;
    }

    /** 把不透明度刷到 Dock 的背景上。换模式后也要调一次 —— 底色变了,alpha 要重新叠。 */
    private void applyOpacity() {
        applySkin();
    }

    /** 壁纸换了,Dock 底色跟着壁纸走时要重刷。由桌面在铺完壁纸后调。 */
    public void onWallpaperChanged() {
        applySkin();
    }

    /**
     * 给一条 Dock 的背景叠上不透明度。
     *
     * 实现在 {@link com.liubai.blank.mode.ModeApplier#opacity} —— 面板/弹窗那档
     * 走的是同一份(它们是两个独立的档位,但"怎么叠"是同一件事)。
     */
    public static void applyOpacity(View dockView, int percent) {
        com.liubai.blank.mode.ModeApplier.opacity(dockView, percent);
    }

    // ---- 窗口 ----

    /**
     * 建窗口。幂等:已经挂上了就直接返回 true。
     *
     * 返回 false 是**"这一次没挂上"**,不是"挂不上了" —— 没有悬浮窗权限时会去后台授权
     * (见 {@link #grantOverlayAsync}),别的失败挂退避重试(见 {@link #RETRY_DELAYS}),
     * 两条路都会自己回来重挂。调用方不用管,更不用自己兜底:桌面窗口里那条降级 Dock
     * 已经删掉了,现在"挂不上"就是"屏幕上没有 Dock",所以重试这条路必须一直在。
     */
    public boolean attach() {
        if (added) return true;
        // 无障碍在线时窗口挂它身上(层级能到 2032);它不在才需要 SYSTEM_ALERT_WINDOW
        Context host = ForegroundWatch.get();
        if (host == null && !Overlay.canDraw(appCtx)) {
            // 授权走的是提权 shell,**阻塞 IO**:主线程上最长能卡 timeout+2s
            // (见 SelfAdbPrivileged.exec 对主线程的处理),而 attach 的调用方全在主线程
            // (桌面 onCreate、设置页点击、无障碍连上/断开)。丢后台去授,拿到再回来重挂
            Log.i(TAG, "没有悬浮窗权限,先去授权");
            grantOverlayAsync();
            return false;
        }

        try {
            Context winCtx = host != null ? host : appCtx;
            wm = (WindowManager) winCtx.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return false;

            // 必须包一层模式:Dock 里的 ModeApplier/Material 控件要靠它解析 attr,
            // 拿裸的 application context inflate 会崩在属性解析上
            Context moded = new ContextThemeWrapper(appCtx, R.style.BlankTheme);
            root = (FrameLayout) LayoutInflater.from(moded).inflate(R.layout.window_dock, null);
            dock = root.findViewById(R.id.dock);
            dock.init(icons, this);
            dock.bind(store.load());
            // Dock 高度是 wrap_content 算出来的(图标尺寸 + 内边距),而窗口的 y 是从
            // 物理屏高倒推它得来的(见 dockTop)。高度一变就得重推一次 —— 挂上的头一帧、
            // 换模式重铺、以后改图标尺寸,都走这条,不用在各处记得手动同步
            dock.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if (b - t != ob - ot) main.post(this::reposition);
            });
            root.setVisibility(View.GONE);   // 先挂上再按策略决定露不露,避免闪一下

            if (!Overlay.add(wm, root, params(false))) {
                root = null;
                dock = null;
                scheduleRetry();
                return false;
            }
            added = true;
            retryStep = 0;    // 挂上了,退避重新计数
            applyOpacity();   // 背景是每次 inflate 新建的,挂一次就得叠一次
            Log.i(TAG, "Dock 悬浮窗已挂上");

            // 提权是异步就绪的:返回键在没提权时不渲染,冷启动时往往在建完 Dock 之后
            // 才连上,不重建那颗键这一程就一直缺席(与 HomeActivity 原来的处理一致)
            Shell.onReady(() -> main.post(this::rebuild));
            Shell.onReady(this::refreshRunning);
            refreshAc();
            refreshRunning();
            applyVisibility();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "挂 Dock 悬浮窗失败: " + e);
            added = false;
            root = null;
            dock = null;
            scheduleRetry();
            return false;
        }
    }

    // ---- 挂不上就重试 ----

    /**
     * 退避重试的间隔。
     *
     * **这条兜底不能省**。桌面窗口里那条降级 Dock 已经删掉了(见 HomeActivity 的字段说明),
     * 所以"挂不上"现在等价于"屏幕上没有 Dock" —— 不能一次定生死。真会挂不上的场合:
     * 冷启动时提权还没连上(授不了 appops)、无障碍服务掉线的那一瞬、系统临时拒了 addView。
     * 这几种都是会自己好的,隔一会儿再试就行。
     */
    private static final long[] RETRY_DELAYS = {1_000L, 3_000L, 10_000L, 30_000L};
    private int retryStep;
    private boolean retryScheduled;

    private final Runnable retryAttach = () -> {
        retryScheduled = false;
        if (added) return;
        if (attach()) rebuild();
    };

    private void scheduleRetry() {
        if (added || retryScheduled) return;
        long delay = RETRY_DELAYS[Math.min(retryStep, RETRY_DELAYS.length - 1)];
        retryStep++;
        retryScheduled = true;
        main.postDelayed(retryAttach, delay);
    }

    /** 正在后台授权,别同时发好几趟(提权通道是单线的)。 */
    private boolean granting;

    /**
     * 后台把悬浮窗权限授给自己,拿到就回主线程重挂。
     *
     * 在 IO 线程上做是因为它走提权 shell,在主线程上最长能卡 12 秒 —— 那是 ANR。
     */
    private void grantOverlayAsync() {
        if (granting) return;
        if (!Shell.ready()) {
            // 提权还没连上,授不了。等它就绪(见构造函数里那条 onReady),同时挂退避重试兜底
            scheduleRetry();
            return;
        }
        granting = true;
        BACK_IO.execute(() -> {
            final boolean ok = Overlay.grantViaPrivileged(appCtx);
            main.post(() -> {
                granting = false;
                if (ok) {
                    if (!added && attach()) rebuild();
                } else {
                    Log.i(TAG, "悬浮窗授权没成功,稍后重试");
                    scheduleRetry();
                }
            });
        });
    }

    /**
     * 窗口参数。两态:
     *
     * **常态** —— 只占屏幕下缘一条,不收焦点、不吃 Dock 以外的触摸,下层 app 照常用。
     *
     * **编辑态** —— 铺满整屏,因为候选面板要在 Dock 上方展开,而它和 Dock 在同一个窗口里
     * (见 window_dock.xml)。这时必须**收焦点**:面板上有滚动和点击,不收焦点收不全触摸;
     * 也必须吃掉整屏触摸,否则手指划到面板外面就落到下层 app 上,编辑一半把别的 app 点了。
     *
     * 收焦点的窗口会把系统栏带出来(真车实测,见 Overlay.add 的说明),所以编辑态那份
     * 由 {@link #applyEditingWindow} 补一次 Immersive —— updateViewLayout 不经过
     * Overlay.add,那道统一归口在这条路上够不着。
     */
    private WindowManager.LayoutParams params(boolean editing) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                // 通栏:整行都是 Dock。早先是 WRAP_CONTENT 的居中胶囊,两侧空白靠
                // NOT_TOUCH_MODAL 穿透给下层 app;铺满整行后那条穿透带就没了 ——
                // 屏幕最下面这一行彻底归 Dock,和原车导航栏一样是占位的(迪友同理)。
                WindowManager.LayoutParams.MATCH_PARENT,
                // 编辑态要盖满**整块物理屏**。不能用 MATCH_PARENT:那拿到的是可用区
                // (导航栏之上,990),底下会露出一条原厂导航栏
                editing ? realHeight() : WindowManager.LayoutParams.WRAP_CONTENT,
                Overlay.windowType(ForegroundWatch.running()),
                editing
                        ? WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        // NOT_FOCUSABLE:不抢焦点,否则前台 app 失焦、输入法被顶掉
                        // NOT_TOUCH_MODAL:Dock 之外的触摸照常落到下层 app(通栏后只剩上方)
                        // LAYOUT_NO_LIMITS:允许画到系统栏区域 —— Dock 要盖住原厂导航栏
                        //   那一条,而那条在可用区之外,没有它就画不过去
                        : WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        /*
         * **绝对定位**:顶部对齐 + 自己算 y,不用 gravity BOTTOM。
         *
         * 同平台两家量产桌面都是这个形状(DiStyle FloatNavBarBaseView V6/j.java、
         * 迪友 b5/c.java:gravity=48、width=MATCH_PARENT、y 自己算),不是巧合 ——
         * gravity BOTTOM 贴的是**可用区**下缘,而可用区随系统栏显隐反复变:
         * 导航栏一露面 Dock 就跟着上移一条(2026-08-14 真车实测,从 [0,984] 跳到
         * [0,894][1920,990])。位置写死就跟这些变化彻底脱钩。
         *
         * y 从**物理**屏高倒推,让 Dock 底边压死屏幕最下沿(见 dockTop)。
         */
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = editing ? 0 : dockTop();
        return lp;
    }

    /**
     * 物理屏高(含系统栏那条)。{@code getMetrics} 拿到的是可用区,会少掉导航栏那 90。
     *
     * 不认 {@link #wm}:空调面板也要用它,而那条路上 Dock 可能压根没挂上(wm 是 null)。
     */
    private int realHeight() {
        WindowManager w = wm != null
                ? wm : (WindowManager) appCtx.getSystemService(Context.WINDOW_SERVICE);
        if (w == null) return 0;
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        w.getDefaultDisplay().getRealMetrics(dm);
        return dm.heightPixels;
    }

    /**
     * Dock 顶边的绝对 y = 物理屏高 − Dock 实测高度。
     *
     * 让底边压死物理屏最下沿,于是原厂导航栏那条(真车 [0,990][1920,1080])整个被盖住 ——
     * **这是整套方案的立身之处**:导航栏保持存在、继续给每个 app 发 content inset
     * (app 内容自动缩到 990、永不被压),而它自己被 Dock 挡得看不见。见 Immersive 类注释。
     *
     * 高度没测量出来时返回物理屏高,窗口正好落在屏幕外 —— 那一刻 root 还是 GONE
     * (见 attach),看不见;布局完成后由 OnLayoutChangeListener 推一次真值。
     */
    private int dockTop() {
        int h = dock == null ? 0 : dock.getHeight();
        return Math.max(0, realHeight() - h);
    }

    public void detach() {
        if (!added || wm == null || root == null) return;
        try {
            wm.removeView(root);
        } catch (Exception ignored) {
        } finally {
            added = false;
            root = null;
            dock = null;
            // 面板长在这棵树上,树没了它也不能留 —— 留着下次 open 会往一个已经被
            // 摘掉的父容器上加视图
            editPanel = null;
            // 返回键接管(root.setOnKeyListener)不必显式清:它随 root 一起被丢弃
            // 让位状态**不跟着这次挂载走**:它描述的是"用户现在在原厂环境里",
            // 跟 Dock 这个窗口挂没挂上无关。无障碍重启时窗口会重挂一次,那时清掉的话,
            // 人还在原厂桌面上,Dock 就又冒出来了
        }
    }

    /**
     * 无障碍服务连上/断开了,把 Dock 重新挂一遍。
     *
     * **不这么做 Dock 会永久消失。** 无障碍在线时这个悬浮窗是挂在**它的 Context** 上的
     * (TYPE_ACCESSIBILITY_OVERLAY,2032 层,见 Overlay.windowType),那个服务一重启,
     * 系统会把挂在它上面的窗口一并收走 —— 而这边的 added 还是 true,{@link #attach()}
     * 第一行就 return 了,于是再也挂不回来。桌面主页本来就是空的,Dock 一没,
     * 屏幕上只剩一张壁纸、什么都点不了,看起来就是死机。
     *
     * 实测日志:「前台监听已断开 → 已连接」之后,dumpsys window 里就只剩桌面一个窗口了。
     * 真车上无障碍服务被系统回收一次就会中招。
     */
    public void onAccessibilityChanged() {
        // 先无条件清掉自己这边的状态:窗口可能已经被系统收走了,removeView 会抛,detach 吃掉
        detach();
        // 空调面板也挂在无障碍的 context 上,而且它把 WindowManager **缓存**在 hvacWm 里
        // (面板实例还在就不重取)。服务一换那个 wm 就失效了,下次唤出面板会
        // BadTokenException「token null is not valid」,只能靠降级路径退回桌面里开。
        // 丢掉缓存,下次打开时按新的 context 重建。
        dropHvacPanel();
        if (attach()) rebuild();
    }

    /** 挂上没有。没挂上就是屏幕上没有 Dock —— 上层据此提示用户(设置页那条状态)。 */
    public boolean attached() { return added && root != null; }

    public DockView dock() { return dock; }

    /** 数据或模式变了,重新铺一遍。切模式会 recreate 桌面,那时要调一次。 */
    public void rebuild() {
        if (dock != null) {
            dock.bind(store.load());
            // bind 里会走一遍 ModeApplier(按 tag 重设 fill:surface),不透明度得跟着重叠一次
            applyOpacity();
        }
        dropHvacPanel();
    }

    /**
     * 丢掉缓存的空调面板,下次唤出时按当前模式重建。
     *
     * **切模式后必须做**:面板的静态模式(HvacPanel.still)和配色都是**建的那一刻**定死的
     —— still 构造时读一次,颜色也是构造时从 ModeManager 取的实色。
     * 不丢的话换了模式还是旧的那一套:该静的还在动,底色也是上一个模式的。
     *
     * 挂在 rebuild 里:需要重铺 Dock 的场合(模式变、Dock 数据变)恰好也是面板该重建的场合;
     * 多丢一次无害,反正下次打开才重建。
     */
    private void dropHvacPanel() {
        if (hvacPanel == null) return;
        if (hvacPanel.isOpen()) hvacPanel.close();
        detachHvacOverlay();   // 窗口先摘掉,否则旧面板会一直全屏吃触摸
        hvacPanel = null;
    }

    public void setHvac(AirCon.State s) {
        if (dock != null) dock.setHvac(s);
    }

    // ---- 显隐策略 ----

    /**
     * 该不该露。**常驻就是常驻** —— 开着就在所有页面上显示,唯一的例外是让位给原厂桌面
     * (见 {@link OemEnv})。关着就只在留白桌面上显示。
     *
     * 这里曾经有一整套倒车让位:倒车广播把 Dock 藏起来、无障碍事件负责放回来、再加一个
     * 90 秒安全阀兜底。删掉了 —— 倒车影像也是一个页面,常驻既然是"所有页面都显示",
     * 它就不该是例外;而那套机制真正的代价是,恢复路径挂在无障碍上,无障碍一没开
     * (实测会掉、也可能压根没启用),每按一次倒车键 Dock 就消失 90 秒。
     * 层层兜底盖不住一个错的前提。
     */
    /** 上一次打的显隐判据,用来给日志去重(见 applyVisibility 里的说明)。 */
    private String lastVisLog;

    private void applyVisibility() {
        if (root == null) return;
        // 正在编辑:一律显示。编辑对象就是这条 Dock 本身,这时候把它藏了等于编辑到一半
        // 界面没了 —— 这也是过去那个 editingElsewhere 标志位存在的原因,现在编辑就在
        // 本窗口里,直接读面板的开合状态,没有需要外部来清的标志位了
        if (editing()) {
            root.setVisibility(View.VISIBLE);
            return;
        }
        boolean show;
        if (!alwaysOn(appCtx)) {
            show = desktopResumed;          // 非常驻:只在留白桌面上露
        } else if (OemEnv.active()) {
            show = false;                   // 让位给原车环境 —— 常驻下唯一的例外
        } else {
            // 用户自己在设置页加的隐藏名单。默认空,加了才生效,要无障碍在线才判得了
            String fg = ForegroundWatch.foreground();
            show = fg == null || !blacklist.blocks(fg);
        }
        // 「Dock 不见了」是反复出现的问题,而判据有四条(编辑态/常驻/原车环境/让位名单),
        // 光看结果说不出是哪条。这行把四条一次说清。
        //
        // **按"上次打了什么"去重,不能按 root 当前的可见性去重** —— root 挂上来时就是
        // GONE(见 add 那句"先挂上再按策略"),判定结果也是 GONE 的话就成了"没变化",
        // 于是最该看见日志的那种情况反而一行都不打(2026-09-05 就这么白跑了一轮)
        String vis = (show ? "显示" : "隐藏")
                + " —— 常驻=" + alwaysOn(appCtx)
                + " 原车环境=" + OemEnv.active()
                + " 桌面在前台=" + desktopResumed
                + " 前台=" + ForegroundWatch.foreground();
        if (!vis.equals(lastVisLog)) {
            lastVisLog = vis;
            Log.i(TAG, "Dock " + vis);
        }
        root.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /** 按当前状态把窗口参数重下一遍。Dock 高度变了要调 —— y 是从物理屏高倒推它算的。 */
    private void reposition() {
        if (!added || wm == null || root == null) return;
        try {
            wm.updateViewLayout(root, params(editing()));
        } catch (Exception e) {
            Log.w(TAG, "重排 Dock 失败: " + e);
        }
    }

    /** 编辑面板开着没有。显隐、窗口参数、行为转发都拿它当"在不在编辑"的唯一依据。 */
    private boolean editing() {
        return editPanel != null && editPanel.isOpen();
    }

    /**
     * 前台变了。无障碍服务是**进程级**的,而 DockHost 是单例 —— 在这里接一次就够,
     * 不必让每个 Activity 各自去听。
     */
    private void onForegroundChanged(String pkg) {
        // 幂等,Immersive 那边也会调一次 —— 谁先跑都行,重点是在 applyVisibility 之前
        OemEnv.onForeground(appCtx, pkg);
        main.post(this::applyVisibility);
        refreshRunning();
    }

    // ---- 运行区(macOS Dock 那套:开着的 app 排在固定项右边) ----

    /**
     * 运行位有几个。**同时最多开四个应用**这条规矩落在这儿:导航、音乐钉死两个,
     * 剩下的应用轮流用这两个位子(见 DockItem 的四个应用位)。
     *
     * 开第五个的时候,最久没用的那个从 Dock 上让位 —— 只让位、不杀应用,见
     * refreshRunning。内存回收是系统自己的事(LMK 会按优先级收),launcher 不替它杀。
     */
    private static final int RUNNING_MAX = 2;

    /** 查 `am stack list` 是阻塞 IO,不能占主线程(和返回键那条同一个理由)。 */
    private static final java.util.concurrent.ExecutorService RUN_IO =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "blank-dock-running");
                t.setDaemon(true);
                return t;
            });

    /**
     * 开着的应用**按槽位排**:下标就是应用位的位置,元素可以是 null(那一格空着)。
     * 不是 z 序 —— 位置一旦定下就不随使用顺序变,见 refreshRunning 里的槽位维护。
     */
    private List<String> runningPkgs = new ArrayList<>();

    /**
     * 重新采一次"现在开着哪些 app"。
     *
     * 数据源是 {@link Tasks#onMainDisplay},它**按 z 序返回**(最近用的在前),已经滤掉了
     * SystemUI/输入法那些。z 序决定的是**谁还留在四个应用位里**(超出的只让位、不杀),
     * 但**不决定摆放顺序** —— 那个另算,见下面的槽位维护。
     *
     * 触发时机挂在前台变化上(见 onForegroundChanged),所以是事件驱动、不轮询;
     * 无障碍没启用时这里就一直不刷新,运行区停在上一次的样子 —— 可以接受,
     * 总比每秒跑一趟 shell 强。
     */
    public void refreshRunning() {
        if (!Shell.ready()) return;
        RUN_IO.execute(() -> {
            List<String> all = Tasks.onMainDisplay(appCtx, appCtx.getPackageName());
            List<String> keep = new ArrayList<>();
            for (String p : all) {
                // 地图和音乐不占运行位:它们在画中画层里、Dock 上已有专属键,
                // 再来一个等于同一个 app 在 Dock 上出现两次。
                if (p.equals(navSources.current()) || p.equals(musicSources.current())) continue;
                if (keep.size() < RUNNING_MAX) keep.add(p);
                // 排在 z 序后面的只从 Dock 让位、不 force-stop:后台应用是系统内存
                // 管理(LMK)的职责,launcher 硬杀会把后台导航/音乐的状态一起清掉
                // (2026-08-20 反馈:自动杀应用不合理)
            }

            /*
             * keep 是 **z 序**(最近用的在前)。直接拿它当显示顺序的话,在两个应用之间切
             * 一次,两格就互换一次 —— 图标在眼皮底下跳,想点的那个每次都在别的位置。
             *
             * 所以显示顺序**沿用上一次的槽位**:还开着的留在原格,关掉的把那一格空出来
             * (null),新开的填第一个空格。位置由"谁先来的"决定,不再随使用顺序动。
             */
            List<String> slots = new ArrayList<>(runningPkgs);
            for (int i = 0; i < slots.size(); i++) {
                if (slots.get(i) != null && !keep.contains(slots.get(i))) slots.set(i, null);
            }
            for (String p : keep) {
                if (slots.contains(p)) continue;    // 已经有位子了,别挪
                int at = slots.indexOf(null);
                if (at >= 0) slots.set(at, p);
                else slots.add(p);
            }
            // 尾部的空格修掉,否则 slots 只增不减,越攒越长
            while (!slots.isEmpty() && slots.get(slots.size() - 1) == null) {
                slots.remove(slots.size() - 1);
            }

            boolean changed = !slots.equals(runningPkgs);
            runningPkgs = slots;
            main.post(() -> {
                // 编辑态别动:bindRunning 会整条重铺,手指正拖着的那一格会被换成新 view,
                // 这次拖动就废了。运行区在编辑态本来也不渲染(见 DockView.build)
                if (dock == null || dock.isEditing()) return;
                if (!changed) return;   // 槽位没变就别重建 Dock
                List<AppRef> refs = new ArrayList<>();
                // null 原样传过去 —— 那一格空着,DockView 按下标取,不能跳过
                for (String p : slots) refs.add(p == null ? null : new AppRef(p, "", labelOf(p)));
                dock.bindRunning(refs);             // 这一步会重铺,放最后
            });
        });
    }

    private String labelOf(String pkg) {
        try {
            android.content.pm.PackageManager pm = appCtx.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    /**
     * 点运行区的 app:**切回去**,不是重开。
     *
     * 走 launch intent 而不是 am start:同一个 task 已经在了,launch intent 会把它
     * 整个搬到前台并保留栈内状态(用户上次停在哪一页还在哪一页)。"点一下地图就切回去"
     * 的手感就靠这个 —— 关键在于那个 task 从来没被杀过。
     */
    @Override public void onSwitchTo(AppRef app) {
        if (app == null) return;
        Log.i(TAG, "切回 " + app.pkg);
        Launchers.launchPackage(appCtx, app.pkg, "无法切换到该应用");
    }

    // ---- Dock 编辑:就在这条 Dock 自己的窗口里做 ----

    /**
     * 长按设置键进编辑态。
     *
     * **不再回桌面**。以前编辑的是桌面窗口里另起的一条 Dock(localDock),因为当时拖拽
     * 走系统拖放、跨不了窗口;悬浮这条则要临时退场,免得屏幕下缘叠两条。那套要求
     * 「桌面活着」「编辑完有人把标志位清掉」,任何一环断掉 Dock 就再也不显示了。
     *
     * 拖拽改成自绘之后(见 {@link DockView#onInterceptTouchEvent}),编辑面板直接插进
     * 本窗口,编辑的就是这条真 Dock 本身 —— 没有第二份实例,也没有要外部来清的标志位。
     */
    @Override public void onEditDock() {
        main.post(this::openEditor);
    }

    private void openEditor() {
        if (root == null || dock == null) return;
        try {
            if (editPanel == null) {
                Context moded = new ContextThemeWrapper(appCtx, R.style.BlankTheme);
                DockEditPanel p = new DockEditPanel(moded);
                p.setListener(editListener());
                // 插在 Dock **下层**(index 0):面板的遮罩特意留出了底部那条,
                // 编辑对象要一直看得见、点得着
                root.addView(p, 0, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
                editPanel = p;
            }
            applyEditingWindow(true);
            grabBackKey(true);
            dock.setEditing(true);
            editPanel.open(store.load());
            applyVisibility();
        } catch (Exception e) {
            Log.w(TAG, "开 Dock 编辑面板失败: " + e);
            closeEditor();
        }
    }

    /** 收编辑态:窗口缩回一条、Dock 退出编辑渲染、显隐重新按常规判。 */
    private void closeEditor() {
        grabBackKey(false);
        if (dock != null) dock.setEditing(false);
        applyEditingWindow(false);
        applyVisibility();
    }

    /**
     * 编辑态期间接住返回键。
     *
     * 这个窗口在编辑态是**收焦点**的,返回键会先送到它这儿。不接住的话事件会漏到下层
     * app 上 —— 表现为"编辑 Dock 时按了下返回,底下那个应用自己退了"。接住之后
     * 按返回 = 收工,和点「完成」一个意思。
     */
    private void grabBackKey(boolean on) {
        if (root == null) return;
        if (!on) {
            root.setOnKeyListener(null);
            root.setFocusableInTouchMode(false);
            return;
        }
        root.setFocusableInTouchMode(true);
        root.requestFocus();
        root.setOnKeyListener((v, code, ev) -> {
            if (code != KeyEvent.KEYCODE_BACK) return false;
            // DOWN 也要吃掉,只吞 UP 的话 DOWN 还是会漏下去
            if (ev.getAction() == KeyEvent.ACTION_UP && editPanel != null) editPanel.close();
            return true;
        });
    }

    /**
     * 切换窗口的两态参数。
     *
     * 编辑态收焦点,而**收焦点的窗口会把系统栏带出来**(真车实测,归口在 Overlay.add)——
     * 这条路走的是 updateViewLayout、够不着那道归口,所以在这儿自己补一次。
     */
    private void applyEditingWindow(boolean editing) {
        if (wm == null || root == null) return;
        try {
            if (editing) Immersive.apply(root);
            wm.updateViewLayout(root, params(editing));
        } catch (Exception e) {
            Log.w(TAG, "切编辑态窗口参数失败: " + e);
        }
    }

    private DockEditPanel.Listener editListener() {
        return new DockEditPanel.Listener() {
            @Override public void onResetDock() {
                List<DockItem> def = store.reset();
                if (dock != null) dock.bind(def);
                if (editPanel != null) editPanel.syncDock(def);
            }

            @Override public void onClearDock() {
                List<DockItem> cleared = store.clear();
                if (dock != null) dock.bind(cleared);
                if (editPanel != null) editPanel.syncDock(cleared);
            }

            @Override public void onAddItem(DockItem item) {
                if (dock != null) dock.addItem(item);
            }

            @Override public void onCandidateDragStart(DockItem item, int iconRes, String glyph,
                                                       boolean mirrored) {
                startCandidateDrag(item, iconRes, glyph, mirrored);
            }

            @Override public void onCandidateDragMove(float rawX, float rawY) {
                moveCandidateDrag(rawX, rawY);
            }

            @Override public void onCandidateDrop(float rawX, float rawY) {
                dropCandidate(rawX, rawY);
            }

            @Override public void onCandidateDragCancel() {
                endCandidateDrag();
            }

            @Override public void onAssignApp(int index, AppRef app) {
                // 面板把 index 认成"这一格",落回 Dock 上同一格。地图/音乐位在里面转发成
                // 换源(见 DockView.setSlotApp),运行位/应用位绑死包名 —— 面板不自己改
                // Dock 的数据,改树和数据都收口在 DockView,和别的入口走同一条路
                if (dock == null || !dock.isEditing()) return;
                dock.setSlotApp(index, app);
            }

            @Override public void onSlotListChanged() {
                // 面板改了某个应用位的"自己添加的列表"(只增删、没动当前)。
                // 地图/音乐键的图标读 store 排头,重铺一遍就换过来了;运行位的绑定
                // 已经在面板那边用 setSlotApp 同步过,这里只负责把 Dock 刷成 store 的现状
                rebuild();
            }

            @Override public void onEditDone() {
                closeEditor();
            }
        };
    }

    // ---- 从候选区拖一项到 Dock 上指定那一格 ----

    /**
     * 候选项拖动时跟手的那个影子。
     *
     * **不走系统拖放**(startDragAndDrop):整条 Dock 活在悬浮窗里,系统拖放框架是为
     * Activity 窗口设计的(同 {@link DockView#onInterceptTouchEvent} 那条理由)。
     * 好在编辑面板和 Dock 本来就在同一个 {@link #root} 下(见 {@link #openEditor}
     * 的 addView(p, 0)),自己画个影子、按屏幕坐标换算就够了。
     */
    private View dragGhost;
    private DockItem dragItem;

    private void startCandidateDrag(DockItem item, int iconRes, String glyph, boolean mirrored) {
        if (root == null || item == null) return;
        endCandidateDrag();
        dragItem = item;
        final View g;
        if (iconRes == 0 && glyph != null) {
            // 没字形的键(温度是读数「°C」)拿文字当影子。少了这条,ImageView 收到
            // resId 0 就是个全透明方块 —— 拖动其实一直在跑,只是什么都不跟手
            TextView t = new TextView(root.getContext());
            t.setText(glyph);
            t.setTextSize(16f);
            t.setGravity(Gravity.CENTER);
            t.setTextColor(ModeManager.get().ink());
            t.setFontFeatureSettings("tnum");
            g = t;
        } else {
            ImageView iv = new ImageView(root.getContext());
            iv.setImageResource(iconRes);
            iv.setColorFilter(ModeManager.get().ink());
            if (mirrored) iv.setScaleX(-1f);
            g = iv;
        }
        g.setAlpha(.9f);
        g.setElevation(Launchers.dp(root.getContext(), 32));   // 压在面板之上,别被它盖住
        int s = Launchers.dp(root.getContext(), 30);
        root.addView(g, new FrameLayout.LayoutParams(s, s));
        dragGhost = g;
    }

    private void moveCandidateDrag(float rawX, float rawY) {
        if (dragGhost == null || root == null) return;
        int[] o = new int[2];
        root.getLocationOnScreen(o);
        dragGhost.setX(rawX - o[0] - dragGhost.getWidth() / 2f);
        dragGhost.setY(rawY - o[1] - dragGhost.getHeight() / 2f);
        // 把"这一项占几格"一起带过去:两格的键(温度/风向)要高亮两格
        int span = dragItem == null ? 1 : dragItem.slots();
        if (dock != null) dock.hintDropAt(
                rawY >= dockTopOnScreen() ? rawX - dockLeftOnScreen() : -1f, span);
    }

    /** 松手:落在 Dock 那条上就插进对应那一格,落在别处什么也不做。 */
    private void dropCandidate(float rawX, float rawY) {
        DockItem item = dragItem;
        endCandidateDrag();
        if (item == null || dock == null) return;
        if (rawY < dockTopOnScreen()) return;              // 没落到 Dock 上 = 放弃
        dock.dropAt(rawX - dockLeftOnScreen(), item);
    }

    private void endCandidateDrag() {
        if (dragGhost != null && root != null) root.removeView(dragGhost);
        dragGhost = null;
        dragItem = null;
        if (dock != null) dock.hintDropAt(-1f, 1);
    }

    /**
     * Dock 会压进某个页面窗口里多少 px —— **页面底部该让的就是这一段,不是整条 Dock 高度**。
     *
     * 常见的误解是"页面得给 Dock 让出一整条"。不对:Dock 的底边压死物理屏最下沿,盖住的是
     * 原厂导航栏那条(见 {@link #dockTop()}),而导航栏照旧给每个 app 发 content inset ——
     * 没走沉浸式的页面,窗口本来就缩到导航栏上沿了,Dock 落在窗口外面。真正压进来的,
     * 只有 Dock 比导航栏高出来的那一点。
     *
     * 实测(模拟器,2026-09-12):浏览器窗口底 1008、Dock 顶 996,只重叠 12px;
     * 而按 dock_space 硬让 56dp(=84px)就等于白挂 72px 空白 —— 底栏和 Dock 之间
     * 夹着一条什么都没有的带子,用户一眼就看出来了。
     *
     * 走了沉浸式、把导航栏整个压掉的页面(内容铺到物理屏底)不吃亏:那时窗口底就是屏底,
     * 这个方法算出来自然是整条 Dock 高度。
     *
     * 布局完成后调(要 pageRoot 的实际高度);Dock 还没挂上时返回 0。
     */
    public static int overlapInto(View pageRoot) {
        if (pageRoot == null) return 0;
        DockHost host = get(pageRoot.getContext());
        int dockH = host.dock == null ? 0 : host.dock.getHeight();
        if (dockH <= 0) return 0;
        int[] loc = new int[2];
        pageRoot.getLocationOnScreen(loc);
        int pageBottom = loc[1] + pageRoot.getHeight();
        return Math.max(0, pageBottom - (host.realHeight() - dockH));
    }

    private int dockTopOnScreen() {
        int[] o = new int[2];
        dock.getLocationOnScreen(o);
        return o[1];
    }

    private int dockLeftOnScreen() {
        int[] o = new int[2];
        dock.getLocationOnScreen(o);
        return o[0];
    }

    // ---- 桌面 Activity 的登记 ----

    public void setDelegate(DockView.Listener l) {
        delegate = l == null ? null : new WeakReference<>(l);
    }

    /** 只有登记的那个才能注销,防止后建的 Activity 被先死的那个清掉。 */
    public void clearDelegate(DockView.Listener l) {
        if (delegate != null && delegate.get() == l) delegate = null;
    }

    public void onDesktopResumed() {
        desktopResumed = true;
        // 回到留白桌面就是"从原车环境回来了"。这条信号**不经过无障碍** —— 没启用无障碍时,
        // 它是唯一能把状态解除掉的路(否则进了原车环境就再也出不来)。
        // OemEnv 那边会顺带把全局沉浸式重新下发回来。
        OemEnv.onBlankDesktopResumed(appCtx);
        applySkin();      // 底下换成壁纸了,半透明这时才允许(见 effectiveOpacity)
        applyVisibility();
        // 回到桌面往往意味着刚关掉/切走了什么,运行区顺手对一次。
        // 无障碍没启用时这是运行区**唯一**的刷新时机,更不能省。
        refreshRunning();
        // 桌面这一帧还没画到屏幕上就把待办动作发出去,别让它先露一眼(见 onDesktop)。
        if (pendingDesktopAction != null) {
            Runnable action = pendingDesktopAction;
            pendingDesktopAction = null;
            action.run();
        }
    }

    public void onDesktopPaused() {
        desktopResumed = false;
        // 有东西盖到桌面上来了(倒车影像、任意全屏 app),**编辑器必须收掉**:它的窗口是
        // 全屏 modal(见 params),root 设 GONE 并不会让它缩小 —— 它会继续吃满整屏触摸、
        // 还收着焦点把返回键吞掉,盖上来的那个 app 就成了"整屏点不动、连返回都没反应"。
        // 编辑是在桌面上发起的,桌面被盖住必然走这里,这个信号不经过无障碍。
        if (editing()) editPanel.close();
        // 底下不再是壁纸了(盖上来的 app + 正占着位的原厂导航栏),Dock 当场转实色
        applySkin();
        applyVisibility();
    }

    private DockView.Listener target() {
        return delegate == null ? null : delegate.get();
    }

    /**
     * 把桌面拉到前台再干活。
     *
     * "全部应用""设置""编辑 Dock"这几件事本来就该在桌面里做,而且它们走的是
     * startActivityForResult —— 后台 Activity 在 Android 10 上发起会被后台启动限制挡掉,
     * 必须先真的把桌面拉起来。已经在前台就直接干,不白走一趟 Intent。
     *
     * 动作不是立刻发出去的:攒进 pendingDesktopAction,等 onDesktopResumed 那一刻
     * (HomeActivity.onResume,画面还没上屏)才真正执行,见那边的说明。
     * 早先这里是固定等 120ms——纯粹是猜桌面什么时候能到前台,猜早了 target() 还是空,
     * 猜晚了/猜多了则是白白把桌面当时的画面(尤其画中画层里正播着的另一个 app)亮给
     * 用户看了一眼,表现为"点设置/点别的 app 图标先闪一下别的应用"。
     */
    private void onDesktop(Runnable action) {
        DockView.Listener t = target();
        if (t != null && desktopResumed) {
            action.run();
            return;
        }
        pendingDesktopAction = action;
        Intent i = new Intent(appCtx, HomeActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        appCtx.startActivity(i);
    }

    // ---- DockView.Listener:桌面在就转发,不在就降级 ----

    @Override public void onTempChange(int delta) {
        DockView.Listener t = target();
        if (t != null) { t.onTempChange(delta); return; }
        if (delta == 0) return;
        sendAc(delta > 0 ? AirCon.TEMP_UP : AirCon.TEMP_DOWN, Math.abs(delta));
    }

    /** 风量给的是绝对档位(0~7),不是增量 —— 走 fanTo 而不是 FAN_UP/DOWN。 */
    @Override public void onFanLevel(int level) {
        DockView.Listener t = target();
        if (t != null) { t.onFanLevel(level); return; }
        if (!AirCon.available()) {
            Launchers.toast(appCtx, "车控需要先开启提权服务");
            return;
        }
        AirCon.fanTo(level, this::setHvac);
    }

    @Override public void onAcAction(String action) {
        DockView.Listener t = target();
        if (t != null) { t.onAcAction(action); return; }
        if ("power".equals(action))        sendAc(AirCon.AC, 1);
        else if ("fan".equals(action))     sendAc(AirCon.FAN_UP, 1);
        else if ("recirc".equals(action))  sendAc(AirCon.CYCLE, 1);
        else if ("defrost".equals(action)) sendAc(AirCon.DEFROST, 1);
        else if (action.startsWith("seat_")) {
            // 座椅通风/加热:一下一档,两档到顶回 0(0=关/1/2)。走 AirCon.seat 而不是 sendAc ——
            // 它不是空调那套 keyevent 码,是按字段直接写(见 AirCon.writeSeat)
            if (!AirCon.available()) {
                Launchers.toast(appCtx, "车控需要先开启提权服务");
                return;
            }
            AirCon.seat(action.endsWith("_r"), action.contains("_heat_"), this::setHvac);
        }
    }

    /**
     * 空调全量面板。**不再回桌面** —— 它自己也是个悬浮窗,在任何 app 之上都能唤出来。
     *
     * 在导航或视频全屏时想调个温度,原来要先被踢回桌面、调完再切回去,那两次跳转
     * 比调温本身还费事。既然 Dock 已经常驻在别人之上,它唤出来的面板也该在原地展开。
     */
    @Override public void onOpenHvacPanel() {
        main.post(this::openHvacOverlay);
    }

    // ---- 空调面板(第二个悬浮窗) ----

    /** 面板实例。不跑动效时走静态模式(见 HvacPanel.still)。 */
    private HvacSheet hvacPanel;
    private WindowManager hvacWm;

    /**
     * 建/显示空调面板窗口。
     *
     * 和 Dock 那个窗口的关键差别:这个**要能收焦点**(没有 NOT_FOCUSABLE) —— 面板上有
     * 滑动调温和按压反馈,不收焦点触摸收不全。代价是它在场时前台 app 会失焦,所以
     * 面板本来就是"用完就收"的(点遮罩或几秒后自动关)。
     *
     * 建不起来就退回老路子:回桌面、在 Activity 的 content 上开。这条兜底不能省 ——
     * 悬浮窗权限是可能被系统或用户收走的,不该因此连空调都调不了。
     */
    private void openHvacOverlay() {
        try {
            if (hvacPanel == null) {
                Context winCtx = ForegroundWatch.get() != null ? ForegroundWatch.get() : appCtx;
                WindowManager w = (WindowManager) winCtx.getSystemService(Context.WINDOW_SERVICE);
                if (w == null) throw new IllegalStateException("没有 WindowManager");
                Context moded = new ContextThemeWrapper(appCtx, R.style.BlankTheme);
                // 不跑动效时控件一个不少,只是不动(见 HvacPanel.still)
                HvacSheet p = new HvacPanel(moded);
                p.setListener(hvacListener());
                // 关了就把窗口摘掉。**这条不能省**:窗口是 MATCH_PARENT 的,面板 view
                // 设成 GONE 并不会让窗口变小,它会继续全屏吃触摸 —— 表现为面板关掉之后
                // 整块屏幕都点不动(实测:第二次长按 Dock 完全没反应)。
                p.setOnClosed(this::detachHvacOverlay);
                hvacPanel = p;
                hvacWm = w;
            }
            if (!hvacAttached) {
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        // 跟编辑态同一个理由:要盖满**整块物理屏**。MATCH_PARENT 拿到的是
                        // 可用区(导航栏之上),底下会露出一条原厂导航栏(见 params)
                        realHeight(),
                        Overlay.windowType(ForegroundWatch.running()),
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                // 走 Overlay.add:这个窗口收焦点,沉浸式 flag 由它统一补上
                if (!Overlay.add(hvacWm, (View) hvacPanel, lp)) {
                    throw new IllegalStateException("空调面板窗口挂不上");
                }
                hvacAttached = true;
            }
            ((View) hvacPanel).setVisibility(View.VISIBLE);
            hvacPanel.open();
            if (AirCon.available()) AirCon.refreshFull(this::onHvacState);
        } catch (Exception e) {
            Log.w(TAG, "空调面板悬浮窗建不起来,退回桌面里开: " + e);
            onDesktop(() -> {
                DockView.Listener t = target();
                if (t != null) t.onOpenHvacPanel();
            });
        }
    }

    /** 把面板窗口摘下来。view 实例留着,下次唤出直接重新 addView,不用重建整棵树。 */
    private void detachHvacOverlay() {
        if (hvacPanel == null || hvacWm == null) return;
        try {
            hvacWm.removeView((View) hvacPanel);
        } catch (Exception ignored) {
            // 已经不在窗口上了(比如被系统回收),忽略
        }
        hvacAttached = false;
    }

    private boolean hvacAttached;

    private void onHvacState(AirCon.State s) {
        setHvac(s);
        if (hvacPanel != null && hvacPanel.isOpen() && s.full) hvacPanel.apply(s);
    }

    /**
     * 面板的动作一律直接走 AirCon,不转发给桌面 —— 面板现在可能开在别人的 app 上,
     * 那时候桌面根本不在前台。车控本来也不依赖 Activity。
     */
    private HvacPanel.Listener hvacListener() {
        return new HvacPanel.Listener() {
            @Override public void onHvacCommand(int code) { sendAc(code, 1); }

            @Override public void onHvacTemp(boolean deputy, int delta) {
                if (delta == 0) return;
                int up = deputy ? AirCon.TEMP_R_UP : AirCon.TEMP_UP;
                int down = deputy ? AirCon.TEMP_R_DOWN : AirCon.TEMP_DOWN;
                sendAc(delta > 0 ? up : down, Math.abs(delta));
            }

            @Override public void onHvacFanLevel(int level) {
                if (!AirCon.available()) {
                    Launchers.toast(appCtx, "车控需要先开启提权服务");
                    return;
                }
                AirCon.fanTo(level, DockHost.this::onHvacState);
            }

            @Override public void onSeatLevel(boolean deputy, boolean heat) {
                if (!AirCon.available()) {
                    Launchers.toast(appCtx, "车控需要先开启提权服务");
                    return;
                }
                AirCon.seat(deputy, heat, DockHost.this::onHvacState);
            }

            @Override public void onHvacNeedState() {
                if (AirCon.available()) AirCon.refreshFull(DockHost.this::onHvacState);
            }
        };
    }

    @Override public void onLaunch(AppRef app) {
        DockView.Listener t = target();
        // 画中画决策只有桌面做得了(它才拿得到两张卡的状态),桌面不在就直接全屏起
        if (t != null && desktopResumed) { t.onLaunch(app); return; }
        if (t != null) { onDesktop(() -> { DockView.Listener d = target(); if (d != null) d.onLaunch(app); }); return; }
        Launchers.launch(appCtx, app);
    }

    /**
     * 地图/音乐键:短按开对应卡位。画中画卡位只有桌面拿得到(TextureView 在它的布局里),
     * 所以一律先回桌面再转发 —— 在别的 app 上按这两个键,本来也就是"我要回去看地图"。
     */
    @Override public void onOpenSlot(DockItem.Kind kind) {
        onDesktop(() -> {
            DockView.Listener t = target();
            if (t != null) t.onOpenSlot(kind);
        });
    }

    @Override public void onPickSlotSource(DockItem.Kind kind) {
        onDesktop(() -> {
            DockView.Listener t = target();
            if (t != null) t.onPickSlotSource(kind);
        });
    }

    @Override public void onGoOriginalHome() {
        // 不绕 onDesktop:切原车桌面跟留白的 Activity 没关系,前台是别的 app 时也该能切
        Launchers.openOriginalHome(appCtx);
    }

    @Override public void onSlotSourcePicked(DockItem.Kind kind, String pkg) {
        // 跟 onOpenSlot 一样要先回桌面:换源会重开卡片,而卡片长在桌面上
        onDesktop(() -> {
            DockView.Listener t = target();
            if (t != null) t.onSlotSourcePicked(kind, pkg);
        });
    }

    /**
     * 长按运行位滑动选定了某个 app:这一格固定显示它,不再被"正在跑的 app"顶掉。
     *
     * 和地图/音乐位的换源不同,这里**不碰桌面、不杀进程**:运行位只是把"这格默认是
     * 谁"改掉,旧 app 该跑还跑着。数据收口:先写源列表(排头=当前),再把 DockItem
     * 的绑定换成新 app,两边对不上 Dock 就会自己"跳回旧绑定"(build 时绑定优先)。
     */
    @Override public void onRunningSlotPicked(int slot, String pkg) {
        if (dock == null || pkg == null || pkg.isEmpty()) return;
        runSources.select(slot, pkg);
        int index = dock.runningSlotIndex(slot);
        if (index < 0) return;
        dock.setSlotApp(index, new AppRef(pkg, "", AppTypes.label(appCtx, pkg)));
    }

    @Override public void onOpenDrawer() {
        if (drawerResumed) {   // 抽屉开着时再点"全部应用"是**关掉它**(见 drawerResumed)
            AppDrawerActivity.closeOpen();
            return;
        }
        onDesktop(() -> {
            DockView.Listener t = target();
            if (t != null) t.onOpenDrawer();
        });
    }

    /**
     * 任务键短按(或长按"全部应用"):弹系统最近任务(所有后台 app 的卡片)。
     *
     * 普通 app 身份没有打开 recents 的入口,借提权链发 APP_SWITCH(187) 键事件,
     * 跟 onGoBack 发返回键同一条路。recents 是 SystemUI 的系统界面,不依赖桌面,
     * 所以不走 onDesktop 那套"先把桌面拉起来"——桌面在不在跟前台任务无关。
     */
    @Override public void onOpenRecents() {
        DockView.Listener t = target();
        if (t != null && desktopResumed) { t.onOpenRecents(); return; }
        if (!Shell.ready()) {
            Launchers.toast(appCtx, "最近任务需要先开启提权服务");
            return;
        }
        BACK_IO.execute(() -> Shell.exec("input keyevent 187"));
    }

    /** 设置键短按。现在开的是桌面上的外观面板,所以要先回桌面(见 HomeActivity.onOpenSettings)。 */
    @Override public void onOpenSettings() {
        onDesktop(() -> {
            DockView.Listener t = target();
            if (t != null) t.onOpenSettings();
        });
    }

    /**
     * Dock 排过了:当场落盘。
     *
     * 不再转发给桌面 —— 编辑就发生在这个窗口里,桌面在不在前台跟这件事没关系了。
     */
    @Override public void onDockEdited(List<DockItem> items) {
        store.save(items);
        // 用户亲手排过了:以后升级默认 Dock 不再覆盖他的顺序(见 DockStore.KEY_DIRTY)
        store.markUserEdited();
        if (editPanel != null) editPanel.syncDock(items);
    }

    /**
     * 主页键 = **回到干净的桌面**:先把 Dock 这一层开着的东西收掉,再回桌面让它收自己那层
     * (见 {@link com.liubai.blank.HomeActivity#onGoHome})。
     *
     * 顺序有讲究:编辑面板要在拉桌面**之前**关。它是全屏 modal + 收焦点的窗口,留着的话
     * 桌面起来了也被它盖着、还吞返回键 —— 跟 {@link #onDesktopPaused} 里那条是同一个坑。
     */
    @Override public void onGoHome() {
        if (editing()) editPanel.close();
        if (hvacPanel != null && hvacPanel.isOpen()) hvacPanel.close();
        onDesktop(() -> {
            DockView.Listener t = target();
            if (t != null) t.onGoHome();
        });
    }

    /**
     * 返回键:不依赖任何 Activity,直接走提权链发 keyevent —— 发给的是当前前台窗口,
     * 所以 Dock 浮在别的 app 上时,按它就是让那个 app 返回。这正是常驻的意义。
     */
    @Override public void onGoBack() {
        DockView.Listener t = target();
        if (t != null && desktopResumed) { t.onGoBack(); return; }
        if (!Shell.ready()) {
            Launchers.toast(appCtx, "返回需要先开启提权服务");
            return;
        }
        BACK_IO.execute(() -> Shell.exec("input keyevent 4"));
    }

    /** 提权通道是阻塞 IO,不能占主线程(与 HomeActivity 里那条同一个理由)。 */
    private static final java.util.concurrent.ExecutorService BACK_IO =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "blank-dockhost");
                t.setDaemon(true);
                return t;
            });

    // ---- 车控(桌面不在时由这里直接发) ----

    private void sendAc(int code, int times) {
        if (!AirCon.available()) {
            Launchers.toast(appCtx, "车控需要先开启提权服务");
            return;
        }
        // 回调走 onHvacState 而不是 setHvac:后者**只刷 Dock**。面板上点了开关,
        // 指令发出去了、状态也回读了,却只喂给 Dock —— 面板要等下次 open() 主动
        // 请求全量状态才变,表现就是"点了没反应,关掉重开才对"。
        AirCon.send(code, times, this::onHvacState);
    }

    private void refreshAc() {
        if (!AirCon.available()) return;
        AirCon.refreshFull(this::setHvac);
    }
}
