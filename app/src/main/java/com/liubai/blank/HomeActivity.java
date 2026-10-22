package com.liubai.blank;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.liubai.blank.data.AppTypes;
import com.liubai.blank.data.CardSizeStore;
import com.liubai.blank.data.DockStore;
import com.liubai.blank.data.NavSourceStore;
import com.liubai.blank.icon.IconEngine;
import com.liubai.blank.media.MusicSourceStore;
import com.liubai.blank.model.AppRef;
import com.liubai.blank.model.CarCtrlItem;
import com.liubai.blank.model.DockItem;
import com.liubai.blank.mode.ModeApplier;
import com.liubai.blank.mode.ModeManager;
import com.liubai.blank.data.DockBlacklist;
import com.liubai.blank.ui.AppearancePopup;
import com.liubai.blank.ui.BarSwipeGesture;
import com.liubai.blank.ui.ThreeFingerHvacGesture;
import com.liubai.blank.ui.DockEditPanel;
import com.liubai.blank.ui.DeskSnapshot;
import com.liubai.blank.ui.DockHost;
import com.liubai.blank.ui.StatusBarHost;
import com.liubai.blank.ui.DockView;
import com.liubai.blank.ui.Motion;
import com.liubai.blank.privileged.ForegroundWatch;
import com.liubai.blank.privileged.Shell;
import com.liubai.blank.ui.PipCard;
import com.liubai.blank.ui.SourcePickPopup;
import com.liubai.blank.ui.ResizeHandle;
import com.liubai.blank.ui.TwoFingerPageGesture;
import com.liubai.blank.ui.WallpaperSwiper;
import com.liubai.blank.ui.WallpaperHost;
import com.liubai.blank.ui.DeskCardHost;
import com.liubai.blank.ui.HvacController;
import com.liubai.blank.util.DefaultHome;
import com.liubai.blank.util.DebugGuard;
import com.liubai.blank.util.Immersive;
import com.liubai.blank.util.Launchers;
import com.liubai.blank.util.Overlay;
import com.liubai.blank.util.ViewTx;

import java.util.ArrayList;
import java.util.List;

/**
 * 留白 Blank — 车机桌面主屏。
 *
 * 桌面是三层叠放,不是多页(见 activity_home.xml):
 *   主页层    最底下那层。原来的 2×2 功能卡已整批去掉,现在是空的,新的主页功能往里加。
 *   画中画层  地图 + 音乐两张卡,盖在主页层上,靠层指示器(右上 page_dots)收起/展开。
 *   全屏 app  系统 task,盖在整个桌面之上,不归这里管。
 *
 * app 从 Dock 和「全部应用」进,由 openApp 决定是全屏还是收进画中画卡位。
 *
 * 默认桌面的查询/跳转在 {@link DefaultHome}。
 */
public class HomeActivity extends AppCompatActivity
        implements DockView.Listener, WallpaperSwiper.Host {

    private static final int REQ_ROLE_HOME = 1001;
    /** 从抽屉点 app:抽屉不自己启动,回传给桌面统一决定全屏还是画中画(见 openApp)。 */
    private static final int REQ_PICK_APP = 1004;
    /** 又是同一个抽屉,这次挑的是"地图/音乐这一类用哪个 app"(见 onPickSlotSource)。 */
    private static final int REQ_PICK_SLOT_APP = 1006;
    /** 外观面板里给某张名单挑 app(见 AppearancePopup.Pick)。 */
    private static final int REQ_PICK_LIST_APP = 1007;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** 走提权通道的杂活(发 keyevent、force-stop)。它是阻塞 IO,不能占主线程。 */
    private static final java.util.concurrent.ExecutorService PRIV_IO =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "blank-priv-io");
                t.setDaemon(true);
                return t;
            });


    /**
     * Dock 本体在悬浮窗里(见 DockHost),桌面只是它的行为委托方之一。
     *
     * **桌面窗口里不再有第二条 Dock**。以前有一条 localDock 兜两件事:悬浮窗挂不上时
     * 当主力,以及编辑态(拖拽走系统拖放、跨不了窗口)。前者其实半残 —— 只在 onCreate
     * 那一次生效,无障碍服务在桌面起来之后掉线根本走不到;后者随拖拽改成自绘一并消失
     * (见 DockView.onInterceptTouchEvent)。两份实例两套显隐规则,是 Dock 反复"莫名
     * 不见了"的病根,现在只剩悬浮那一份。
     */
    private DockHost host;
    /** 舞台:主页层和画中画层都叠在这里,尺寸恒定(见 setupStage)。 */
    private FrameLayout stage;
    /** 最底层:主页。永远在,不动。卡片去掉后现在是空的,新功能往这上面加。 */
    private View homeLayer;
    /** 桌面卡片:挂载、摆位、编辑态、候选区、车控状态回读全在这一处(见 DeskCardHost)。 */
    private DeskCardHost deskHost;
    /** 空调/车控:三指调温、Dock 空调键、空调面板、车控卡点击下发(见 HvacController)。 */
    private HvacController hvacCtrl;
    /** 中间层:地图+音乐两张卡。靠 translationY 整层滑进滑出。 */
    private View pipLayer;
    /** 画中画层是不是收起去了(露出主页)。 */
    private boolean pipCollapsed;
    /** 画中画层的聚焦:null=两张并排,MAP/MUSIC=那一张独占(见 setFocus)。 */
    private DockItem.Kind focused;
    private TwoFingerPageGesture pageGesture;
    /** 左右滑切壁纸。壁纸层在最底下,手势统一在 dispatchTouchEvent 里分发。 */
    private WallpaperSwiper wallpaperSwiper;
    /** 这一轮横滑已经给下面补过 CANCEL 了,别重复补。 */
    private boolean swipeCancelled;
    /** 上下滑收/放桌面卡片。同样在 dispatchTouchEvent 里分发。 */
    private BarSwipeGesture barSwipe;
    /** 三指滑:竖着调温度、横着调风量。 */
    private ThreeFingerHvacGesture hvacGesture;
    private DockStore dockStore;
    /** 地图/音乐这两个语义槽位分别用哪个 app。排头的就是当前用的。 */
    private NavSourceStore navSources;
    private MusicSourceStore musicSources;
    /** 去抽屉挑源时先记住是给哪个槽位挑的,结果回来才知道往哪存。 */
    private DockItem.Kind pendingSlotPick;
    /** 外观面板正在给哪张名单挑 app,选完在 onActivityResult 里用。 */
    private AppearancePopup.Pick pendingAppPick;
    private CardSizeStore cardSize;   // 两卡的宽度分配档位(拖握把改,松手吸附)
    /** 壁纸:图 / 视频 / 网页三种的铺放、取色、喂触摸、生命周期全在这一处(见 WallpaperHost)。 */
    private WallpaperHost wallpaper;
    /** 外观弹层(壁纸/配色/设置)。首次长按桌面时才建。 */
    private AppearancePopup appearance;
    private IconEngine icons;
    private static final String TAG = "Blank/Home";

    /**
     * 启动时把不属于首屏的活往后推多久。
     *
     * 系统给无障碍服务装零件那一步也占主线程,被启动的活挤掉就**永久卡死**
     * (见 Overlay.ensureAccessibilityBound)。2026-08-25 真车十来次观察,界线在 1 秒上下:
     * 552~876ms 那几次全绑上,1022/1335/1450ms 那三次全卡死。
     * 跟 WallpaperHost.WEB_START_DELAY_MS 取同一个值,两边是同一件事。
     */
    private static final long DEFER_MS = 500;

    /**
     * 两个画中画卡位,都在画中画层上:一个就铺满,两个就并排。
     * 平时空着——空的时候整层是透明的,直接看到底下的主页。
     */
    private PipCard pip1, pip2;
    private View pip1Card, pip2Card, resizeHandle;
    private View pip1Ops, pip2Ops;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 分段计时。这一整个 onCreate 跟 onStart/onResume/首次 layout 一起,是**主线程上
        // 同一条 message**(ActivityThread$H 的 EXECUTE_TRANSACTION),跑多久就挡多久 ——
        // 无障碍绑定要在主线程建 Service,撞上就绑不完,之后永久卡死
        // (见 Overlay.killSelfToRebind)。2026-08-24 真车实测:卡 839/876ms 的两次绑上了,
        // 卡 1450ms 那次没绑上。所以这里要能看出那一秒多到底花在哪一段。
        final long t0 = android.os.SystemClock.uptimeMillis();
        Immersive.windowBg(this);   // 窗口底跟模式走,否则转场露白(见那个方法的说明)
        setContentView(R.layout.activity_home);
        final long tContent = android.os.SystemClock.uptimeMillis();

        // 数据(放什么 / 怎么放),与模式(怎么摆)正交
        dockStore = new DockStore(this);
        navSources = new NavSourceStore(this);
        musicSources = new MusicSourceStore(this);
        icons = new IconEngine(this);
        // 两个卡位是**有语义**的:卡一给地图、卡二给音乐(见 openApp 的路由)。
        // 这跟 HiCar 一个思路 —— 画中画不是通用容器,是给少数常驻功能留的位置。
        pip1 = PipCard.slot(this, "pip_slot1");
        pip2 = PipCard.slot(this, "pip_slot2");
        cardSize = new CardSizeStore(this);   // 须在 setupStage 前:绑卡时就要读档位
        // 壁纸自己建 store,不在这里另存一份 —— 免得两处引用不同步
        wallpaper = new WallpaperHost(this, findViewById(android.R.id.content));
        final long tStores = android.os.SystemClock.uptimeMillis();

        // 壁纸在最底下,先铺 —— 排在 setupStage 之前,免得上面几层先亮出来再被垫底
        wallpaper.apply();
        wallpaperSwiper = new WallpaperSwiper(
                findViewById(R.id.wallpaper), findViewById(R.id.wallpaper_video),
                findViewById(R.id.wallpaper_next), wallpaper.store(), this);
        // 左右两张先在后台备好。**等手指按下才解一定来不及** —— 视频壁纸没有现成封面时
        // 解一张要抽帧 350~450ms,而一次快扫全程才 130~170ms(见 WallpaperSwiper.warm)
        wallpaperSwiper.preload();
        final long tWallpaper = android.os.SystemClock.uptimeMillis();

        // 底部 Dock:车控常驻 + 全部应用/设置。内容由 DockStore 数据驱动(可自定义)。
        // 它本体是个系统悬浮窗,离开桌面也还在 —— 桌面这里只负责把自己登记成行为委托方
        // (画中画决策这些只有 Activity 办得到)。挂不挂得上、什么时候重试,全归 DockHost。
        // **排在 setupStage 前**:Stage 4 起 HvacController 构造要拿它回喂空调读数
        // (见 setupStage 里 hvacCtrl 的构造),晚了就是传 null 进去、提权就绪时 NPE。
        host = DockHost.get(this);
        host.setDelegate(this);
        // 切模式会 recreate 本页,但悬浮窗不跟着重建 —— 挂上后重铺一次它才换得了肤。
        //
        // **和网页壁纸一样往后挪**(见 WallpaperHost.WEB_START_DELAY_MS 那段的完整理由):
        // attach + rebuild 实测占 241ms,而系统给无障碍装零件也要主线程,被挤掉就永久卡死。
        // 十来次观察下来界线在 1 秒上下 —— 577ms 那次绑上了,1022/1335/1450ms 那三次全卡死。
        // 用 postDelayed 而不是 post:post 只是排到队尾,绑定的消息要是还没进队列,照样被
        // 挤在后面;隔开半秒是给它留发起的窗口。
        // get/setDelegate 必须留在原地:setupStage 里 HvacController 构造就要拿 host。
        handler.postDelayed(() -> { if (host.attach()) host.rebuild(); }, DEFER_MS);
        final long tDock = android.os.SystemClock.uptimeMillis();

        // 三层桌面:主页层在底(现在空着),画中画层(地图+音乐)盖在上面可滑走。
        // 第三方 app 全屏是系统 task,盖在整个桌面之上,不归这里管。
        setupStage();
        final long tStage = android.os.SystemClock.uptimeMillis();

        // 顶栏:和 Dock 一对,同样是常驻悬浮窗(见 StatusBarHost)。
        // 挂上后重上一次色,理由同 Dock —— 切模式 recreate 的是本页,悬浮窗不跟着重建。
        StatusBarHost topBar = StatusBarHost.get(this);
        if (topBar.attach()) topBar.applySkin();
        // 顶栏不占布局空间,页面顶部那块让位要按它的真实高度来(真车 90px / 模拟器 36px)
        StatusBarHost.applyTopSpace(findViewById(android.R.id.content));
        // 空调读数。提权通道是**异步**连上的,这里先试一次(热启动时通常已就绪),
        // 再挂 onReady 兜住冷启动那次——只判一次 ready() 会把"还没连上"当成"没提权"。
        hvacCtrl.refreshAc();
        Shell.onReady(() -> hvacCtrl.refreshAc());
        // 胎压走 native,提权就绪才读得到 —— 冷启动那次多半还没连上,这里补一遍
        // 开关类车控(日行灯/SOC 保电这些)同理。**不读回来就是错的**:卡片一律从"关"
        // 起步,车上开着也显示灰,而点一下写哪个值是按卡片当时的样子定的(见 sendCarCtrl)
        // —— 显示反了第一次点就把值写反。2026-08-17 真车实测 SOC 保电中招。
        // onResume 那次管日常(去原厂设置里改过再回来),这里的 onReady 兜冷启动。
        Shell.onReady(() -> deskHost.refreshCtrlStates());

        // 沉浸式在这里就先声明一次。它真正生效要等 onResume 那次(decorView 得先 attach),
        // 但**首次 layout 的窗口高度**取决于这时有没有声明:不声明的话第一轮布局会按
        // "系统导航栏还占着"来算,整个舞台矮一条导航栏,之后再长高。
        // 三层结构下两层都是 match_parent、会跟着舞台重新测量,所以这不再是正确性问题,
        // 只是省掉启动时那一次多余的重排。
        Immersive.apply(this);

        // 模式上色:按各 view 的 android:tag 声明,用当前模式覆盖(A2)
        ModeApplier.apply(findViewById(android.R.id.content));

        // debug 包常驻自检:每帧扫全树,子节点数组里出现 null 当场点名(容器 + 当时状态)。
        // 只编进 debug 包,release 整个方法体被裁剪(见 DebugGuard.attachTreeCheck)。
        DebugGuard.attachTreeCheck(findViewById(android.R.id.content), () ->
                "编辑态=" + deskHost.editing()
                        + " 卡片=" + deskHost.cardCount()
                        + " 候选区=" + deskHost.pickerOpen()
                        + " Dock编辑=" + (host != null && host.dock() != null && host.dock().isEditing()));

        long tEnd = android.os.SystemClock.uptimeMillis();
        Log.i(TAG, "桌面启动 总" + (tEnd - t0) + "ms"
                + " = 布局" + (tContent - t0)
                + " + 数据" + (tStores - tContent)
                + " + 壁纸" + (tWallpaper - tStores)
                + " + Dock" + (tDock - tWallpaper)
                + " + 舞台" + (tStage - tDock)
                + " + 收尾" + (tEnd - tStage));
    }

    // ---- 三层桌面:主页层 / 画中画层 ----

    /**
     * 搭舞台:两层都 match_parent 叠在 stage 里,尺寸从头到尾恒定。
     *
     * 主页层在底、永远不动;画中画层盖在它上面,靠 translationY 整层滑进滑出。
     * 两层都在 onCreate 里一次建好,之后既不重建也不回收 —— 这是虚拟屏能一直活着的前提。
     */
    private void setupStage() {
        stage = findViewById(R.id.stage);

        homeLayer = getLayoutInflater().inflate(R.layout.layout_mode_dashboard, stage, false);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        // 桌面卡片离屏幕边的那圈边距只给主页层:画中画层要铺满舞台(见 activity_home.xml 内容层的说明)。
        // 数值就是原来内容层的 padding,卡片的位置和落盘坐标一点没动
        hlp.setMarginStart(Launchers.dp(this, 10));
        hlp.setMarginEnd(Launchers.dp(this, 16));
        hlp.topMargin = Launchers.dp(this, 10);
        homeLayer.setLayoutParams(hlp);
        // 插到 index 0:XML 里 stage 只放了 page_dots,两层都得排在它下面
        stage.addView(homeLayer, 0);
        // 先建 HvacController。后四个参数是 lambda —— 那时 deskHost 还是 null,
        // **绝不能写成方法引用**(方法引用在这一行就求值,当场 NPE)。
        // Stage 3 的 Shell.onReady 踩过同一个坑(见施工单 Stage 4"结二")
        hvacCtrl = new HvacController(this, stage, host,
                s -> deskHost.applyAcState(s),
                item -> deskHost.tileOf(item),
                () -> deskHost.revertCarCtrl(),
                () -> deskHost.refreshCtrlStates());
        // 卡片宿主在主页层就位后立刻建:setup() 要往里挂卡片(见下)。
        // tileListener 给 HvacController,onRefreshAc 也由它包(空调读数回喂 Dock 归它)
        deskHost = new DeskCardHost(this, (ViewGroup) homeLayer,
                (ViewGroup) findViewById(android.R.id.content), hvacCtrl,
                hvacCtrl::refreshAc,
                () -> { if (barSwipe != null) barSwipe.reject(); });
        // 长按桌面空白 → 外观弹层(模式/壁纸)。这是 launcher 通用的那个手势,
        // 而主页层现在正好是空的,整片都是热区。放在这一层而不是 stage 上:
        // 画中画层盖在上面,长按卡片时不该弹外观。
        homeLayer.setClickable(true);
        homeLayer.setOnLongClickListener(v -> {
            // 编辑态里长按空白不做事:候选区就在右边,要加卡片走那儿。
            // (卡片被 × 光了也不怕 —— 候选区是常驻的,不依赖"桌面上还有卡片可长按")
            if (!deskHost.editing()) openAppearance();
            return true;
        });
        // 编辑态里点空白 = 退出。同 Dock 编辑态点遮罩收起
        homeLayer.setOnClickListener(v -> {
            if (deskHost.editing()) deskHost.setEditing(false);
        });

        // 车控卡片:主页这张白纸上长出来的第一个东西。绑完还是藏着的 —— 要等
        // refreshAc 那次全量读回来、确认这台车真装了座椅通风/加热才露(见 applyAcState)
        deskHost.setup();

        pipLayer = getLayoutInflater().inflate(R.layout.layout_mode_grid, stage, false);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        // 画中画层四周留一条细缝,让卡片圆角和壁纸边露出来 —— 完全贴死状态栏和 Dock 看着像
        // 三块拼在一起(2026-09-06 真车看过 0 缝隙的版本)。数值刻意比主页层那圈边距小得多
        int gap = Launchers.dp(this, 4);
        plp.setMargins(gap, gap, gap, gap);
        pipLayer.setLayoutParams(plp);
        stage.addView(pipLayer, 1);
        bindGrid(pipLayer);

        buildDots();

        // 双指上下滑收展画中画层 **已去掉**(2026-08-20 用户觉得没意义):Pager 恒为
        // 单页,平移永远判"越界不动",整段翻页逻辑休眠。收展画中画层只剩层指示器
        // (page_dots)一个入口。手势类本身还留着 —— 双指合拢/张开进出编辑态在用,
        // 不能把 TwoFingerPageGesture 一起删了。
        pageGesture = new TwoFingerPageGesture(this, new TwoFingerPageGesture.Pager() {
            @Override public int pageCount() { return 1; }
            @Override public int currentPage() { return 0; }
            @Override public void goToPage(int index) { }
        });
        // 双指合拢/张开:进出**桌面编辑态**(卡片按下即拖、挂 ×,见 DeskCard.setEditing)。
        // 原来合拢是直接弹车控配置面板,改了 —— 摆位是这个手势更常用的那件事,
        // 而"挑摆哪几项"仍留在长按卡片原地松手那条路上(编辑态里长按空白也够得着)
        pageGesture.setOnPinchIn(() -> deskHost.setEditing(true));
        pageGesture.setOnPinchOut(() -> deskHost.setEditing(false));

        // 三指滑:竖着调温度、横着调风量。三指是桌面上唯一还空着的档(见那个类的注释)
        hvacGesture = new ThreeFingerHvacGesture(this, new ThreeFingerHvacGesture.Host() {
            @Override public void onTempStep(int steps) { hvacCtrl.stepTemp(steps); }
            @Override public void onFanStep(int steps) { hvacCtrl.stepFan(steps); }
            @Override public void onHvacGestureEnd() {
                hvacCtrl.hideBubble();
                hvacCtrl.reconcileAc();   // 松手全量回读:关态下三指带起空调等连带变化,回读校正真状态
            }
        });

        // 单指上下滑:把桌面上的卡片收起来看壁纸 / 放回来。**临时的** —— 见 DeskCardHost.setHidden
        barSwipe = new BarSwipeGesture(this, new BarSwipeGesture.Host() {
            @Override public void onBarsHidden(boolean hidden) { deskHost.setHidden(hidden); }
            @Override public boolean barsHidden() { return deskHost.hidden(); }
        });
    }

    // ---- 外观弹层(模式 / 壁纸) ----

    /**
     * 打开外观面板。
     *
     * 它必须开在桌面上,不能塞进设置页:选壁纸的本质是看效果 —— 点一张立刻应用到背后的
     * 真桌面,按住卡片面板还会淡出让你看清整屏(见 AppearancePopup)。开在设置页里
     * 背后是设置页,什么也看不见。
     */
    void openAppearance() {
        if (appearance == null) {
            appearance = new AppearancePopup(this);
            appearance.setDeskSource(cb -> DeskSnapshot.capture(
                    HomeActivity.this, deskHost, DockHost.get(HomeActivity.this).dock(), cb));
            appearance.setListener(new AppearancePopup.Listener() {
                @Override public void onWallpaperChanged() {
                    afterWallpaperChanged();
                }
                @Override public void onPaletteChanged() {
                    // **先重算壁纸取色再换肤**。tint 的明暗是按当时的配色定的(见 WallpaperHost.pushTint),
                    // 配色一换它就过期了 —— 只 reskin 的话文字跟着新配色走、面板底还留着旧那档,
                    // 切到浅色就是一整屏深底深字,读不出来
                    wallpaper.pushTint(wallpaper.store().current());
                    reskin();
                }
                @Override public void onPickAppFor(AppearancePopup.Pick what) {
                    // 抽屉是 Activity,弹层接不住 onActivityResult,由桌面代发代收
                    pendingAppPick = what;
                    startActivityForResult(
                            new Intent(HomeActivity.this, AppDrawerActivity.class),
                            REQ_PICK_LIST_APP);
                }
                @Override public void onSetDefaultHome() {
                    DefaultHome.request(HomeActivity.this, REQ_ROLE_HOME);
                }
                @Override public void onEditDock() {
                    // 编辑对象是 Dock 那条真控件,面板已经自己关了(见 buildDockTab)
                    DockHost.get(HomeActivity.this).onEditDock();
                }
                @Override public void onMotionChanged() {
                    // **不 recreate**:动效只落在桌面卡和 Dock 上,把这两样重挂一遍就够了。
                    // 走 recreate 的话面板会被销毁再拉回来,人正按着开关,眼前闪一下 ——
                    // 明明只是关个动效,看着像整个桌面出了什么事。
                    deskHost.setup();      // DeskCard 的 still 是构造时读的,重挂才拿得到新值
                    hvacCtrl.refreshAc();       // 座椅档位要重新铺到新建的车控卡上
                    DockHost.get(HomeActivity.this).rebuild();
                }
            });
            // 贴到 content 上而不是 activity_home 里:弹层要盖住整个桌面
            ((ViewGroup) findViewById(android.R.id.content)).addView(appearance,
                    new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
        }
        appearance.open();
    }

    /**
     * 就地换肤:颜色变了但结构没变时用,**不重建 Activity**。
     *
     * ModeApplier 本来就是照着"对同一棵 view 树再 apply 一次"设计的(见它的类注释),
     * 所以换配色、换强调色、换面板底色都不需要 recreate —— 而 recreate 会整屏闪一下、
     * 弹层消失再滑回来,用户正一个个试颜色的时候尤其晃眼。
     *
     * 换**模式**是另一回事:能力变了会改布局结构(画中画卡位数、四固定应用那排),
     * 那个仍然走 recreate。
     */
    private void reskin() {
        Immersive.windowBg(this);   // 窗口底不在 ModeApplier 的 tag 体系里,单独跟一下
        ModeApplier.apply(findViewById(android.R.id.content));
        // 两条常驻栏都是悬浮窗、不在这棵树里,各自单独刷(底色和透明度都在这一步重算)。
        // **顶栏这一句是补的** —— 它 2026-08-16 加进来时漏了挂到这儿,表现是换配色后
        // 整屏都变了、只有顶栏还留在旧那档(2026-08-18 用户在模拟器上看见)
        DockHost.get(this).rebuild();
        StatusBarHost.get(this).applySkin();
    }

    /** 画中画层上有没有东西。空的时候"收起"没有意义,手势和指示器都不响应。 */
    private boolean pipHasContent() {
        return (pip1 != null && pip1.occupied()) || (pip2 != null && pip2.occupied());
    }

    /** 画中画层有内容且展开着 = 壁纸被盖住,只露四边一圈。壁纸那边据此暂停/接着放。 */
    private void syncWallpaperCovered() {
        if (wallpaper != null) wallpaper.setCovered(pipHasContent() && !pipCollapsed);
    }

    /**
     * 收起/展开画中画层。
     *
     * **只动 translationY,不动尺寸、不动可见性**:层里那两张卡的宽高从头到尾不变,
     * TextureView 收不到尺寸回调,虚拟屏也就不会被判定成"尺寸变了"而重建 —— 里面的
     * app 不重启、不跳回首页。这是三层结构相对翻页最实在的好处。
     *
     * 也不能用 GONE 代替位移:GONE 会把尺寸变成 0,正好踩中 PipCard 里那条
     * "布局变化→尺寸回调→重建→再变化"的自激死循环(见 PipCard.stopHostQuietly)。
     */
    private void setPipCollapsed(boolean collapsed, boolean animate) {
        if (pipLayer == null) return;
        Log.i(TAG, "画中画层 " + (collapsed ? "收起" : "展开") + " animate=" + animate
                + " stageH=" + (stage == null ? -1 : stage.getHeight()));
        pipCollapsed = collapsed;
        syncWallpaperCovered();
        float target = collapsed ? stage.getHeight() : 0f;
        if (animate) Motion.slideTo(pipLayer, target);
        else pipLayer.setTranslationY(target);
        refreshDots(collapsed ? 1 : 0);
    }

    /**
     * 层指示器:收起/展开画中画层的唯一入口(双指上下滑已去掉,见手势构造处)。
     * 画中画层空着时整个不显示 —— 只有一层可看,给两个点是误导。
     */
    private void buildDots() {
        LinearLayout box = findViewById(R.id.page_dots);
        box.removeAllViews();
        if (!pipHasContent()) return;
        for (int i = 0; i < 2; i++) {
            final int page = i;
            View dot = new View(this);
            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.OVAL);
            dot.setBackground(gd);
            int size = Launchers.dp(this, 8);
            // dot 的父容器是 FrameLayout,LayoutParams 必须用 FrameLayout 的,
            // 否则类型不匹配被丢弃、尺寸塌成 0,热区也就接不到点击。
            FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(size, size);
            dlp.gravity = Gravity.CENTER;
            dot.setLayoutParams(dlp);

            // 点击热区要够大,车机上 8dp 的点根本点不中
            int pad = Launchers.dp(this, 10);
            FrameLayout hit = new FrameLayout(this);
            hit.setPadding(pad, pad, pad, pad);
            hit.addView(dot);
            hit.setClickable(true);
            hit.setOnClickListener(v -> setPipCollapsed(page == 1, true));
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hlp.setMargins(0, Launchers.dp(this, 4), 0, Launchers.dp(this, 4));
            box.addView(hit, hlp);
        }
        refreshDots(pipCollapsed ? 1 : 0);
    }

    private void refreshDots(int current) {
        LinearLayout box = findViewById(R.id.page_dots);
        for (int i = 0; i < box.getChildCount(); i++) {
            View hit = box.getChildAt(i);
            if (!(hit instanceof FrameLayout)) continue;
            View dot = ((FrameLayout) hit).getChildAt(0);
            if (dot == null || !(dot.getBackground() instanceof GradientDrawable)) continue;
            GradientDrawable gd = (GradientDrawable) dot.getBackground().mutate();
            gd.setColor(i == current ? ModeManager.get().accent() : ModeManager.get().line());
        }
    }

    // ---- 手势:双指合拢/张开=进出编辑态,单指左右滑=切壁纸 ----

    /**
     * 两套手势都在这里分发。
     *
     * 双指那套只观察不拦截(见 {@link TwoFingerPageGesture});左右滑切壁纸**要拦**:
     * 一旦认定是横滑,后面的事件就不能再往下走了 —— 否则手指抬起来时,底下那些
     * clickable 的东西(主页层的长按外观、卡片)还会跟着响应一次。
     *
     * 判定顺序也有讲究:先喂给壁纸滑动,它自己会把"多指"和"竖着划的"让出去
     * (见 WallpaperSwiper 的 rejected),所以双指合拢/张开不受影响。
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        // 有浮层盖在桌面上时,**桌面自己的手势整个让位**。
        // 桌面手势(双指合拢/张开进出编辑态、左右滑切壁纸)挂在这里,比整棵 view 树先收到事件 ——
        // 浮层内部的 requestDisallowInterceptTouchEvent 挡不住它们。不让位的话,
        // 在外观弹层里横滑那几排(强调色、两档透明度)就会滑到底下的壁纸上去。
        // **第二根手指一落下,就给下面补一发 CANCEL**。
        //
        // 双指绝不可能是长按,而桌面长按开的是外观弹层 —— 不取消的话,双指滑到一半
        // 弹层就自己弹出来了(而且弹层一开,下面 overlayShowing 就把后续事件全挡了,
        // 双指手势再也收不到 MOVE)。横滑切壁纸那条路早就这么处理了,双指这条一直漏着。
        if (ev.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN && !overlayShowing()) {
            MotionEvent cancel = MotionEvent.obtain(ev);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            super.dispatchTouchEvent(cancel);
            cancel.recycle();
            wallpaper.feedTouch(ev, true);   // 网页壁纸同理:双指不是给它的
        }
        if (overlayShowing()) return super.dispatchTouchEvent(ev);
        // 喂给网页壁纸。**要赶在手势判定之前** —— 手势要等 MOVE 才认得出来,
        // 而壁纸得先收到 DOWN 才成得了一次完整触摸;真被认领了,下面补 CANCEL。
        wallpaper.feedTouch(ev, false);
        // 三指排在最前面:它一旦认领,双指/单指那几套都不该再看见这串事件
        if (hvacGesture != null && hvacGesture.onTouch(ev)) {
            if (!swipeCancelled) {
                swipeCancelled = true;
                MotionEvent cancel = MotionEvent.obtain(ev);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(cancel);
                cancel.recycle();
                wallpaper.feedTouch(ev, true);
            }
            return true;
        }
        if (pageGesture != null) pageGesture.onTouchEvent(ev);
        if (wallpaperSwiper != null && wallpaperSwiper.onTouch(ev)) {
            // 被横滑吃掉了。**还得补一发 CANCEL** —— 下面的 view 已经收到过 DOWN,
            // 不告诉它们这次手势作废,按下去的那个键会一直停在按压态
            if (!swipeCancelled) {
                swipeCancelled = true;
                MotionEvent cancel = MotionEvent.obtain(ev);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(cancel);
                cancel.recycle();
                wallpaper.feedTouch(ev, true);
            }
            return true;
        }
        // 竖滑收/放两条栏。排在横滑之后:那条要跟手、得早点拿到 MOVE,这条只认一下。
        // **编辑态整个不喂** —— 那时按下即拖,竖着挪一张卡走过 96dp 太容易了
        // (BarSwipeGesture 自己也只在见过 DOWN 之后才判,半路接上的 MOVE 不算)
        if (!deskHost.editing() && barSwipe != null && barSwipe.onTouch(ev)) {
            if (!swipeCancelled) {
                swipeCancelled = true;
                MotionEvent cancel = MotionEvent.obtain(ev);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(cancel);
                cancel.recycle();
                wallpaper.feedTouch(ev, true);
            }
            return true;
        }
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) swipeCancelled = false;
        return super.dispatchTouchEvent(ev);
    }

    /**
     * 盖在桌面上的浮层开着没有。
     *
     * 只列**在这棵 view 树里**的:它们和桌面手势共享同一条事件链,所以要在
     * {@link #dispatchTouchEvent} 里让位。空调面板、Dock 编辑那些是独立悬浮窗
     * (走 DockHost / Overlay),压根不经过这里,不用管。**新加桌面浮层往这儿加一行。**
     */
    private boolean overlayShowing() {
        return appearance != null && appearance.isOpen();
    }

    /** 滑动切壁纸:落定了才真的换。 */
    /** 滑动那边已经把图解好了,直接转给壁纸层用作垫底(见 WallpaperHost.presetPoster)。 */
    @Override public void onWallpaperPoster(String id, android.graphics.Bitmap poster) {
        if (wallpaper != null) wallpaper.presetPoster(id, poster);
    }

    @Override public void onWallpaperPicked(String id) {
        if (wallpaper == null) return;
        wallpaper.store().set(id);
        afterWallpaperChanged();
    }

    /**
     * 换完壁纸的收尾。**外观面板里选、和在桌面上滑着切,走的是同一条。**
     *
     * <h3>为什么不只是换张图</h3>
     * 面板底色可以设成「跟随壁纸」({@link ModeManager#SURFACE_WALLPAPER}),那一档下
     * {@code surface()} 是从壁纸底部取的色 —— **壁纸一换,这个色就变了**,而面板、弹窗、
     * 卡片、Dock 全都用它。
     *
     * {@link WallpaperHost#apply} 内部会重算取色({@code pushTint}),也会单独刷两条
     * 常驻悬浮窗(它们不在 Activity 这棵树里)。**但桌面自己那棵树要另外刷** ——
     * 漏了这一步的表现是:换了壁纸,Dock 和顶栏的底色跟着变了,而面板/卡片还留在旧色上
     * (2026-08-23 用户报「底色选的跟随壁纸,但切换壁纸时不会变」)。
     * 换配色那条路({@code onPaletteChanged})一直是 pushTint + reskin 两步齐的,
     * 换壁纸这条只做了一半。
     *
     * 只在「跟随壁纸」那一档才 reskin:别的档下壁纸和配色无关,白刷一遍还要重建 Dock。
     */
    private void afterWallpaperChanged() {
        wallpaper.apply();          // 内部重算壁纸取色 + 刷两条常驻栏
        if (ModeManager.get().surfaceMode() != ModeManager.SURFACE_WALLPAPER) return;
        // **让开一帧再刷**。滑动切壁纸时,这一下正落在翻页动画结束的那一帧里 ——
        // 和"收旧播放器 + 解首帧图 + 起新播放器"挤在一起,就是动画走完画面顿一下。
        // 底色晚一帧变没人看得出来,顿一下看得出来。
        getWindow().getDecorView().post(this::reskin);
    }

    // ---- 点 app:全屏,还是凑成双画中画 ----

    /** Dock 上的 app 项。抽屉里点的也汇到这儿(见 onActivityResult)。 */
    @Override public void onLaunch(AppRef app) {
        openApp(app);
    }

    /**
     * 点运行区里的 app:切回去,不走 openApp 的画中画决策。
     *
     * 它本来就在跑着(不然不会出现在运行区),要的是"把那个 task 搬到前台、保留它
     * 停在哪一页",而不是重新开一个 —— 这两件事在体感上差得很远。
     */
    @Override public void onSwitchTo(AppRef app) {
        if (app == null) return;
        Launchers.launchPackage(this, app.pkg, "无法切换到该应用");
    }

    /**
     * 地图/音乐键短按:开对应卡位里的 app。
     *
     * 已经开着就只把画中画层唤回来(openApp 内部会认出"已在卡里"这种情况)。
     * 没配过源就按包名猜一个,别让这颗键点了没反应。
     */
    @Override public void onOpenSlot(DockItem.Kind kind) {
        // 已经开着的话,这一下是**切聚焦**而不是重新打开:
        //   并排 → 点谁谁独占(它吃满整层,另一张退场)
        //   有人独占 → 点任何一个都回到并排(不管点的是独占那个还是被藏起来那个)
        // 退场的那张只是不贴画面,**虚拟屏照跑**(音乐不断、导航不丢定位);
        // 回来时由 PipCard.setPresented 重绑 Surface,见 syncCardVisibility。
        PipCard card = kind == DockItem.Kind.MAP ? pip1 : pip2;
        if (card != null && card.occupied()) {
            setPipCollapsed(false, true);
            PipCard other = kind == DockItem.Kind.MAP ? pip2 : pip1;
            boolean otherOn = other != null && other.occupied();
            Log.i(TAG, "槽位键 " + kind + " 已开着; focused=" + focused
                    + " otherOn=" + otherOn
                    + " pip1.occ=" + (pip1 != null && pip1.occupied())
                    + " pip2.occ=" + (pip2 != null && pip2.occupied()));
            // 只有"当前并排且另一位也在"时,这一下才是让它独占;其余一律回并排
            setFocus(focused == null && otherOn ? kind : null);
            return;
        }

        String pkg = kind == DockItem.Kind.MAP
                ? navSources.current() : musicSources.current();
        if (TextUtils.isEmpty(pkg)) {
            // 没配过:走各自的兜底探测,探到了就记下来当默认源
            pkg = kind == DockItem.Kind.MAP ? guessNavPkg() : guessMusicPkg();
            if (TextUtils.isEmpty(pkg)) {
                // 不提"长按可指定":长按这颗键滑的是**已配置的候选**,一个都没配时长按没反应;
                // 配置入口是长按设置键进编辑面板(2026-09-12 反馈,去掉误导的提示)
                toast(kind == DockItem.Kind.MAP ? "没找到地图应用" : "没找到音乐应用");
                return;
            }
            if (kind == DockItem.Kind.MAP) navSources.select(pkg);
            else musicSources.select(pkg);
        }
        openApp(new AppRef(pkg, "", ""));
    }

    /**
     * 这一类有哪些 app 可选 = **自动识别的 ∪ 手动配过的**。
     *
     * 自动识别按系统级声明来(见 {@link AppTypes}),车主不用先去设置页手动添加 ——
     * 装了什么就认出什么。手动配过的排前面:那是用户明确表过态的,而且当前用的
     * 那个本来就在 `load()` 的排头,顺手就是选中项。
     *
     * 两条都留着,是因为识别不可能全覆盖:有的 app 既不声明 geo: 也没有
     * MediaBrowserService(自研协议的车机 app 就有这种),那时还得靠手动添加。
     */
    private List<String> slotCandidates(boolean isMap) {
        List<String> out = new ArrayList<>(isMap ? navSources.load() : musicSources.load());
        for (String pkg : (isMap ? AppTypes.maps(this) : AppTypes.music(this))) {
            if (!out.contains(pkg)) out.add(pkg);
        }
        return out;
    }

    /**
     * 长按地图/音乐键滑动选定了某个 app。
     *
     * 换源 = 换这个卡位里的 app:先把旧的收掉,再按新源开。和 onPickSlotSource
     * 里那条是同一套动作,只是入口从弹窗换成了滑动。
     */
    @Override public void onSlotSourcePicked(DockItem.Kind kind, String pkg) {
        switchSlotSource(kind, pkg);
    }

    /**
     * 长按运行位换 app:不经过桌面。换的是 Dock 上"这一格固定显示谁",
     * 数据收口在 DockHost(源列表 + DockItem 绑定),桌面不参与,所以这里是空实现。
     */
    @Override public void onRunningSlotPicked(int slot, String pkg) { }

    /**
     * 换这一类的源:落盘 → 收掉旧卡片 → **杀掉被换掉那个 app 的进程** → 按新源开。
     *
     * 三个入口(长按滑动、弹窗兜底、从抽屉挑)都走这儿 —— 原来三处各写一遍一模一样的
     * 四行,加"杀进程"这一步的时候就得改三个地方,漏一个就是同类跑俩。
     */
    private void switchSlotSource(DockItem.Kind kind, String pkg) {
        if (kind == null || TextUtils.isEmpty(pkg)) return;
        boolean isMap = kind == DockItem.Kind.MAP;
        String old = isMap ? navSources.current() : musicSources.current();
        if (isMap) navSources.select(pkg); else musicSources.select(pkg);
        PipCard card = isMap ? pip1 : pip2;
        if (card != null && card.occupied()) card.close();
        killSameKind(old, pkg);
        final DockItem.Kind k = kind;
        handler.post(() -> onOpenSlot(k));
    }

    /**
     * 换源时把被换掉的那个 app **整个进程杀掉**。
     *
     * `card.close()` 只销毁虚拟屏(连带销毁它上面的 task),**进程还活着** ——
     * 地图/音乐这类都带前台服务,收了卡片照样在后台跑。同一类留两个的后果很实在:
     * 两个地图抢 GPS 和定位回调,两个音乐抢音频焦点(表现为点了播放没声、或者
     * 两个一起响),车机内存也不宽裕。
     *
     * 只杀**被替换掉的那一个**,不去扫同类的全部候选 —— 候选是包名关键词认出来的
     * (见 AppTypes),里头可能有误判,顺手杀掉用户正在别处用的 app 就过分了。
     */
    private void killSameKind(final String old, String now) {
        if (TextUtils.isEmpty(old) || old.equals(now)) return;
        if (!Shell.ready()) return;
        Log.i(TAG, "换源:杀掉上一个 " + old);
        PRIV_IO.execute(new Runnable() {
            @Override public void run() { Shell.exec("am force-stop " + old); }
        });
    }

    /** 长按地图/音乐键:就地换这一类用哪个 app。 */
    @Override public void onPickSlotSource(DockItem.Kind kind) {
        final boolean isMap = kind == DockItem.Kind.MAP;
        List<String> candidates = slotCandidates(isMap);
        SourcePickPopup.show(this, isMap ? "选择地图" : "选择音乐", candidates,
                isMap ? navSources.current() : musicSources.current(), icons,
                new SourcePickPopup.Callback() {
                    @Override public void onPick(String pkg) {
                        switchSlotSource(isMap ? DockItem.Kind.MAP : DockItem.Kind.MUSIC, pkg);
                    }

                    @Override public void onPickFromAll() {
                        pendingSlotPick = isMap ? DockItem.Kind.MAP : DockItem.Kind.MUSIC;
                        startActivityForResult(
                                new Intent(HomeActivity.this, AppDrawerActivity.class),
                                REQ_PICK_SLOT_APP);
                    }
                });
    }

    /** 高德车机版 → BYD 自带地图,都没有就返回 null。 */
    private String guessNavPkg() {
        if (Launchers.installed(this, "com.autonavi.amapauto")) return "com.autonavi.amapauto";
        if (Launchers.installed(this, "com.byd.automap")) return "com.byd.automap";
        return null;
    }

    /** 系统默认音乐应用(CATEGORY_APP_MUSIC),车机上通常是预装那个。 */
    private String guessMusicPkg() {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_APP_MUSIC);
        android.content.pm.ResolveInfo ri =
                getPackageManager().resolveActivity(i, 0);
        return ri == null || ri.activityInfo == null ? null : ri.activityInfo.packageName;
    }

    /**
     * 点 app 的唯一决策点,所有入口(Dock / 全部应用抽屉)都走这里。
     *
     * 画中画只有两个位置,而且是给"要一直看着"的东西准备的(地图/音乐,见 A2)。
     * 位置满了就**全屏打开**,不再往桌面里塞 —— 这也是 HiCar 的做法:除了那两类,
     * 其余 app 一律全屏,用完就走。全屏时 Dock 仍然浮在上面(见 DockHost),
     * 所以"回得来"这件事不依赖桌面本身可见。
     *
     *   卡① 空       → 放卡①,铺满(只有一个 app 时并排没意义)
     *   卡① 满卡② 空 → 放卡②,两张并排
     *   两张都满     → 全屏打开
     * 总开关关着,或提权没就绪时,同样退回全屏——那时候本来也做不了画中画。
     */
    private void openApp(AppRef app) {
        if (app == null || TextUtils.isEmpty(app.pkg)) return;
        final String pkg = app.pkg;

        // 模式没给画中画 / 用户关了开关 / 提权没就绪,都只能全屏(见 PipCard.usable)
        if (!PipCard.usable(this)) {
            Log.i(TAG, "点开 " + pkg + " → 全屏(画中画不可用:模式卡位="
                    + PipCard.modeSlots() + " 开关=" + PipCard.enabled(this)
                    + " 提权=" + PipCard.available(this) + ")");
            launchFullscreen(app);
            return;
        }

        // 已经在某张卡里放着,再点一次不该把它重开一遍 —— 但要把层展开,不然
        // 用户点了看不到任何反应(它就在收起去的那一层上)
        if (pip1.isHosting(pkg) || pip2.isHosting(pkg)) {
            Log.i(TAG, pkg + " 已在卡片里,展开画中画层");
            setPipCollapsed(false, true);
            return;
        }

        // 卡位是有语义的:地图归卡一、音乐归卡二,别的一律全屏。
        // 这样两个位置永远是那两样东西,用户不用记"我刚才把地图开在左边还是右边"。
        PipCard slot = slotFor(pkg);
        if (slot == null) {
            Log.i(TAG, "点开 " + pkg + " → 全屏(不是地图也不是音乐)");
            launchFullscreen(app);
            return;
        }

        Log.i(TAG, "点开 " + pkg + " → " + (slot == pip1 ? "卡一(地图)" : "卡二(音乐)"));
        if (!openInCard(slot, pkg)) launchFullscreen(app);
        setPipCollapsed(false, true);   // 开在收起去的层上等于没开
    }

    /**
     * 这个包该落哪个卡位。不是当前地图/音乐源就返回 null(=全屏开)。
     *
     * 判定只认"用户配的源",不做类型探测 —— 探测出来的"这看着像个地图"经常是错的,
     * 而且用户无从更正;配过的才算数,没配过的第一次会由 onOpenSlot 顺手记下来。
     */
    private PipCard slotFor(String pkg) {
        if (pkg.equals(navSources.current())) return pip1;
        if (pkg.equals(musicSources.current())) return pip2;
        return null;
    }

    /**
     * 把 app 放进指定卡位。
     *
     * 卡片必须**先显出来**:TextureView 在 gone 的容器里拿不到 SurfaceTexture,
     * 画中画根本起不来。没接管成就立刻收回去,别留一张空卡在那儿。
     */
    private boolean openInCard(PipCard card, String pkg) {
        View holder = cardViewOf(card);
        if (holder != null) holder.setVisibility(View.VISIBLE);
        if (card.openApp(pkg)) return true;
        syncCardVisibility();
        return false;
    }

    /**
     * 全屏打开。
     *
     * 这个 app 正被托在某张卡上时,**先把它整个搬到主屏**(见 PipCard.releaseForFullscreen):
     * activity 不重建,地图不重新定位、音乐不断播,画中画放大成全屏就是它本来该有的样子。
     * 搬成了就到此为止 —— 它已经在主屏前台站着了,再启动一次纯属多余。
     *
     * 搬不动才走老路子:卡位已经在上一步收掉了,延一下再冷启动。延这 120ms 是等虚拟屏
     * 连同上面的 activity 真的销毁完,否则 intent 会被派发给那个还没咽气的实例,
     * 主屏看着像点了没反应。
     */
    private void launchFullscreen(AppRef app) {
        if (app != null) launchFullscreen(app.pkg, () -> Launchers.launch(this, app));
    }

    /**
     * 同上,但只有包名 —— 卡片上的放大角标这条路拿不到 AppRef,所以把"怎么起"交给调用方。
     * 搬成了根本用不上它,只有退回冷启动时才会跑。
     */
    private void launchFullscreen(String pkg, Runnable coldStart) {
        PipCard holder = pip1 != null && pip1.isHosting(pkg) ? pip1
                : pip2 != null && pip2.isHosting(pkg) ? pip2 : null;
        if (holder == null) {
            coldStart.run();
            return;
        }
        if (holder.releaseForFullscreen(pkg)) return;
        handler.postDelayed(coldStart, 120L);
    }

    /**
     * 操作条上的「全屏」:把这张卡里的 app 整个搬到主屏铺满。
     *
     * 「换应用」不在这条上 —— 长按 Dock 的地图/音乐键就是干这个的(见 onPickSlotSource),
     * 同一件事不给第二个入口。
     */
    private void expandToFullscreen(PipCard card) {
        final String pkg = card == null ? null : card.hosted();
        if (pkg == null) return;
        Log.i(TAG, "放大 " + pkg + " → 全屏");
        launchFullscreen(pkg, () -> Launchers.launchPackage(this, pkg, "无法打开该应用"));
    }

    // ---- 模式绑定 ----

    /** 与 layout_mode_grid.xml 里 ResizeHandle 的宽度一致,算可分配宽度要扣掉它。 */
    private static final int HANDLE_DP = 12;

    /** 竖屏:两张卡上下堆叠;横屏:左右并排。下面一律用"主轴"指代排列方向。 */
    private boolean isPortrait() {
        return getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_PORTRAIT;
    }

    /**
     * 两张卡能分的主轴总长:整页减掉中间的握把。横屏是宽度,竖屏是高度。
     *
     * 早先这里是按"卡片自身形状"算基准宽度(页面高 × 0.85,略窄于正方形)再乘档位倍数——
     * 那是给音乐卡定的规矩:封面主导的卡片要接近正方形才好看,右边剩多少归网格。
     * 现在两边都是 app 界面,谁也不需要固定形状,按比例分才是这个场景该有的算法
     * (按形状算出来的三档一档都不对称,默认那档左边只占 41%)。
     */
    private int availSize(View root) {
        int s = isPortrait() ? root.getHeight() : root.getWidth();
        return s <= 0 ? 0 : s - Launchers.dp(this, HANDLE_DP);
    }

    /** 第一张卡按比例吃掉主轴,剩下的归第二张(它是 weight,自动补满)。 */
    private void applyCardScale(View root, float ratio) {
        View card = root.findViewById(R.id.pip1_card);
        int avail = availSize(root);
        if (card == null || avail <= 0) return;
        setCardSize(card, Math.round(avail * CardSizeStore.clamp(ratio)));
        // 宽度变了,对调的偏移量得跟着重算;要等这次布局落定才量得到真实宽高
        card.post(new Runnable() {
            @Override public void run() { applySwap(false); }
        });
    }

    /** 把这张卡在主轴上钉成固定尺寸,交叉轴撑满。 */
    private void setCardSize(View card, int size) {
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) card.getLayoutParams();
        boolean portrait = isPortrait();
        int cur = portrait ? lp.height : lp.width;
        if (cur == size && lp.weight == 0f) return;   // 已是目标值,别白触发一次重排
        if (portrait) {
            lp.height = size;
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        } else {
            lp.width = size;
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        }
        lp.weight = 0f;
        card.setLayoutParams(lp);
    }

    /**
     * 两卡之间的横向拉伸:拖握把实时改宽度,松手吸附到档位并记住。
     *
     * 拖拽期间把虚拟屏同步挂起——让虚拟屏里的 app 每帧重排一次是重活,
     * 那样根本拖不动;松手后统一补一次。
     */
    private void setupCardResize(final View root) {
        final View col = root.findViewById(R.id.pip1_card);
        ResizeHandle handle = root.findViewById(R.id.np_resize);
        if (col == null || handle == null) return;

        handle.setListener(new ResizeHandle.Listener() {
            int avail, startW;
            float current;
            float pendingDx;
            boolean frameQueued;

            /** 一帧只应用一次:触摸采样率高过屏幕刷新率,来一个事件排一次版是白烧。 */
            private final Runnable applyFrame = new Runnable() {
                @Override public void run() {
                    frameQueued = false;
                    apply(pendingDx);
                }
            };

            @Override public void onResizeStart() {
                // 拖握把是横向的,和"左右滑切壁纸"撞了。**握把里 requestDisallowIntercept 挡不住它** ——
                // 壁纸滑动挂在 Activity.dispatchTouchEvent 上,比整棵 view 树还早收到事件。
                // 时序上正好够用:DOWN 先经过 swiper(把 rejected 清掉)、再下发到握把,
                // 这里设回 true,后面的 MOVE 就被 swiper 自己挡掉了
                if (wallpaperSwiper != null) wallpaperSwiper.reject();
                // 竖屏时这根握把是**竖着**拖的(见下面 isPortrait 那支),和收栏那条撞车,
                // 一并让开。两个 swiper 的 reject 一律成对出现
                if (barSwipe != null) barSwipe.reject();
                avail = availSize(root);
                startW = isPortrait() ? col.getHeight() : col.getWidth();
                current = cardSize.scale();   // 只按一下没拖动时,松手不该跳档
                if (pip1 != null) pip1.setResizeSuspended(true);
                if (pip2 != null) pip2.setResizeSuspended(true);
            }

            @Override public void onResize(float dx) {
                pendingDx = dx;
                if (frameQueued) return;
                frameQueued = true;
                col.postOnAnimation(applyFrame);   // 对齐到下一帧
            }

            private void apply(float delta) {
                if (avail <= 0) return;
                // 对调之后卡一在右边,往右拖是把它挤窄,方向得反过来才跟手
                float d = swapped ? -delta : delta;
                // 夹在首末档之间,拖过头也不会把任何一张卡挤没
                current = CardSizeStore.clamp((startW + d) / avail);
                int size = Math.round(avail * current);
                setCardSize(col, size);
                applySwap(false);   // 宽度变了,对调的偏移量跟着变(没对调时是空操作)
                // 画中画不重排布局,只跟着缩放——不碰 surface 就不掉帧。
                // 竖屏是上下分配,横向缩放没有意义,跳过(占位层本来也盖着)
                if (pip1 != null && !isPortrait()) pip1.previewWidth(size);
            }

            @Override public void onResizeEnd(boolean dragged) {
                col.removeCallbacks(applyFrame);
                frameQueued = false;
                if (!dragged) {
                    // 只是点了一下 —— 这一下是**左右对调**,不是改尺寸
                    if (pip1 != null) pip1.setResizeSuspended(false);
                    if (pip2 != null) pip2.setResizeSuspended(false);
                    swapCards();
                    return;
                }
                float snapped = CardSizeStore.snap(current);   // 拖过就吸附到最近档位
                cardSize.set(snapped);
                applyCardScale(root, snapped);
                if (pip1 != null) pip1.setResizeSuspended(false);
                if (pip2 != null) pip2.setResizeSuspended(false);
            }
        });
    }

    /**
     * 屏幕转向:横屏两卡左右并排,竖屏上下堆叠。
     *
     * **没有用 layout-port 布局变体**——换布局意味着重新 inflate,TextureView 跟着重建,
     * 虚拟屏销毁,而虚拟屏是 DESTROY_CONTENT_ON_REMOVAL 的,里面那个 app 直接被杀。
     * 转个屏把人家的导航转没了,没人能接受。
     * manifest 的 configChanges 已经拦下转屏(Activity 不重建),这里只把 LinearLayout
     * 的主轴和几个 LayoutParams 掉个方向,View 树原封不动,卡片和画面都保得住。
     */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyOrientation();
    }

    private void applyOrientation() {
        if (pip1Card == null || !(pip1Card.getParent() instanceof LinearLayout)) return;
        final LinearLayout box = (LinearLayout) pip1Card.getParent();
        boolean portrait = isPortrait();
        box.setOrientation(portrait ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);

        // 握把:主轴上是固定厚度,交叉轴撑满。横屏一根竖条,竖屏一根横条。
        if (resizeHandle != null) {
            LinearLayout.LayoutParams lp =
                    (LinearLayout.LayoutParams) resizeHandle.getLayoutParams();
            int thick = Launchers.dp(this, HANDLE_DP);
            lp.width = portrait ? ViewGroup.LayoutParams.MATCH_PARENT : thick;
            lp.height = portrait ? thick : ViewGroup.LayoutParams.MATCH_PARENT;
            resizeHandle.setLayoutParams(lp);
        }

        // 卡二永远吃剩下的空间,但"剩下的"在哪个轴上得跟着换——漏了这一步,
        // 它会带着横屏的 width=0dp 进竖屏,宽度直接塌成 0(实测)。
        fillMain(pip2Card);

        // 尺寸得等布局量完才算得出(availSize 要真实宽高)
        box.post(new Runnable() {
            @Override public void run() {
                syncCardVisibility();
                // 转屏后层高变了,收起态的位移量得跟着换,否则层会停在半空
                if (pipCollapsed) setPipCollapsed(true, false);
            }
        });
    }

    /** 主页:两张画中画卡,平时都藏着,整页只剩壁纸。 */
    private void bindGrid(View root) {
        pip1Card = root.findViewById(R.id.pip1_card);
        pip2Card = root.findViewById(R.id.pip2_card);
        resizeHandle = root.findViewById(R.id.np_resize);
        pip1Ops = root.findViewById(R.id.pip1_ops);
        pip2Ops = root.findViewById(R.id.pip2_ops);
        unclipForBadge();
        applyOrientation();   // 冷启动时就可能是竖屏,别等转屏回调

        // 握把监听可以立刻挂;宽度得等布局量完才算得出(见 baseCardWidth)
        setupCardResize(root);
        root.post(() -> applyCardScale(root, cardSize.scale()));

        bindCard(root, pip1, R.id.pip1_view, R.id.pip1_ph, R.id.pip1_ph_icon,
                pip1Ops, R.id.pip1_full, R.id.pip1_close);
        bindCard(root, pip2, R.id.pip2_view, R.id.pip2_ph, R.id.pip2_ph_icon,
                pip2Ops, R.id.pip2_full, R.id.pip2_close);
        syncCardVisibility();
    }

    /**
     * 绑一个卡位:圆角裁切 + 顶边操作条 + 画中画状态回调。
     *
     * 注意 attach 的目标一律传 null,**不恢复上次开的那个 app**。
     * 主页的默认状态就是空白,重开桌面/切模式/转屏都该回到空白——
     * 上次那个 app 自己冒出来会让"这页什么时候有东西"变得不可预期。
     */
    private void bindCard(final View root, final PipCard card, int viewId,
                          final int phId, final int phIconId,
                          final View ops, int fullId, int closeId) {
        final TextureView tv = root.findViewById(viewId);
        if (tv == null || card == null) return;

        // 圆角裁在画面上,不裁在卡片上——卡片得留着让角标探出边框。
        // (shape drawable 不提供 outline,clipToOutline 光靠 XML 无效,得自己给)
        final float radius = Launchers.dp(this, 22);
        tv.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        tv.setClipToOutline(true);

        View full = root.findViewById(fullId);
        if (full != null) full.setOnClickListener(v -> expandToFullscreen(card));
        View close = root.findViewById(closeId);
        if (close != null) close.setOnClickListener(v -> card.close());

        card.attach(tv, null, new PipCard.Listener() {
            @Override public void onPipState(boolean running) {
                syncCardVisibility();
                // 层从空变成有内容(或反过来)时,指示器要跟着出现/消失;
                // 而且最后一个 app 关掉后层里已经没东西了,得把它展开回来,
                // 否则下次再开 app 会开在一个还收着的层上,看着像没反应
                buildDots();
                if (!pipHasContent()) setPipCollapsed(false, false);
                syncWallpaperCovered();
                showOps(ops, running);
            }

            @Override public void onPipLaunchFailed(String pkg) {
                // 这个 app 在虚拟屏里跑不起来,就按老规矩全屏打开,别让点击落空
                Launchers.launchPackage(HomeActivity.this, pkg, "无法打开该应用");
            }

            @Override public void onPipBusy(boolean busy, String pkg, boolean instant) {
                showPlaceholder(root, phId, phIconId, busy, pkg, instant);
            }
        });
    }

    /**
     * 每张卡按自己有没有在跑来显隐;两张都没跑,整页就是空白的。
     *
     * 握把只在两张卡同时在场时才出现——它调的是两者的宽度分配,
     * 只有一张卡的时候拖它没有任何意义,还平白在页面上多一条竖线。
     */
    private void syncCardVisibility() {
        // 看 occupied() 而不是 running():正在启动的卡也得留在场上,
        // 藏了它就拿不到 surface,再也起不来(见 PipCard.occupied 的说明)
        boolean o1 = pip1 != null && pip1.occupied();
        boolean o2 = pip2 != null && pip2.occupied();
        // 聚焦**只在两张都在场时**才有意义,少一张就复位。
        // 不光是为了"两张都空了别记着旧状态":关掉配角再开回来这条路上,
        // 不复位的话新开的那张一露面就被当成配角直接藏掉,表现为"点了音乐没反应"。
        if (!(o1 && o2)) focused = null;

        // 聚焦 = 主角吃满整层、配角整个退场。
        //
        // 早先这里是把配角压成一条 15% 的窄边,那是绕不开 GONE 才有的妥协:
        // TextureView 一旦不参与渲染,那块 Surface 就不再收内容,再显出来是一片透明。
        // 现在配角可以安全地藏掉了 —— 重新露面时 PipCard.setPresented 会重绑一次
        // Surface(跟 resize 变大黑屏是同一个病、同一味药)。主角就该是真全屏。
        boolean v1 = o1 && focused != DockItem.Kind.MUSIC;
        boolean v2 = o2 && focused != DockItem.Kind.MAP;

        if (pip1Card != null) pip1Card.setVisibility(v1 ? View.VISIBLE : View.GONE);
        if (pip2Card != null) pip2Card.setVisibility(v2 ? View.VISIBLE : View.GONE);
        // 握把调的是两张卡的宽度分配,只有真并排时才有意义(聚焦时它自然就没了)
        if (resizeHandle != null) resizeHandle.setVisibility(v1 && v2 ? View.VISIBLE : View.GONE);

        // 场上只剩一张(另一张空着、或被聚焦挤走)就让它吃满整层——一个 app 还并排摆着,
        // 等于白白空一半。卡二自己就是 weight 的(卡一 GONE 后不参与分配),会自动铺满,
        // 只有卡一需要显式改回来:它被 applyCardScale 在主轴上钉成了固定尺寸。
        if (v1 && !v2) fillMain(pip1Card);
        else if (v1 && v2 && pip1Card != null && pip1Card.getParent() instanceof View) {
            applyCardScale((View) pip1Card.getParent(), cardSize.scale());
        }

        // 藏起来的那张**虚拟屏照跑**(音乐不断、导航不丢定位),只是画面不往桌面贴;
        // 重新露面要靠这一下重绑 Surface,否则贴回来是透明的
        if (pip1 != null) pip1.setPresented(v1);
        if (pip2 != null) pip2.setPresented(v2);

        // 独占时偏移要清掉、回并排时要还原。不动画:这一下是跟着聚焦切换走的,
        // 占位层正盖着,再叠一段滑行只会让两件事互相打架
        if (pip1Card != null) pip1Card.post(new Runnable() {
            @Override public void run() { applySwap(false); }
        });
    }

    /**
     * 画中画层的聚焦:null = 两张并排,否则那一张独占、另一张退场。
     *
     * 这是 Dock 上地图/音乐键的第二重身份 —— 那一位已经开着时,再点就是
     * "我现在只想看这个"。切换只动布局,不碰虚拟屏。
     */
    private void setFocus(DockItem.Kind k) {
        if (focused == k) return;
        // **先盖后动**:占位层硬切盖死,再让布局变。反过来的话(先改布局、由 syncSizeNow
        // 顺带触发淡入)中间有一百多毫秒占位层还是半透明的,底下就是被拉伸的旧画面 ——
        // 那正是"切换闪一下"最后剩下的那段。见 PipCard.beginSwitch。
        if (pip1 != null) pip1.beginSwitch();
        if (pip2 != null) pip2.beginSwitch();
        focused = k;
        Log.i(TAG, "画中画聚焦 → " + (k == null ? "并排" : k));
        syncCardVisibility();
        // 布局已经变了,虚拟屏得**立刻**跟上:走默认那条防抖路径的话,中间那 250ms
        // TextureView 是新尺寸、虚拟屏还是旧的,画面被硬拉伸,之后 resize 完又跳一次。
        // (刚从 GONE 回来的那张已经在 setPresented 里同步过了,这里会被尺寸判据跳过)
        if (pip1 != null) pip1.syncSizeNow();
        if (pip2 != null) pip2.syncSizeNow();
    }

    /** 两张卡的左右位置对调过没有(点中间那条横条切)。 */
    private boolean swapped;
    /** 对调的滑行时长。够看清"它俩换了个位置",又不至于等。 */
    private static final long SWAP_MS = 260L;

    /**
     * 点中间那条横条 = 左右两个 app 对调位置。
     *
     * 只改 translation,**不碰虚拟屏、不搬 task** —— app 既不重启也不重排,画面原样
     * 滑到另一边。想着去换 View 在 LinearLayout 里的顺序是行不通的:removeView/addView
     * 会让 TextureView 走一遍 onSurfaceTextureDestroyed,虚拟屏跟着销毁,而它是
     * DESTROY_CONTENT_ON_REMOVAL 的,里面那个 app 当场被杀。
     */
    private void swapCards() {
        // 只有真并排时才谈得上对调(独占时场上只有一张)
        if (pip1Card == null || pip2Card == null) return;
        if (pip1Card.getVisibility() != View.VISIBLE
                || pip2Card.getVisibility() != View.VISIBLE) return;
        swapped = !swapped;
        Log.i(TAG, "两卡对调 → " + (swapped ? "卡二在前" : "卡一在前"));
        applySwap(true);
    }

    /**
     * 把对调状态落到 translation 上。
     *
     * <pre>
     * 原位: [0,W1] 卡一 | [W1,W1+H] 握把 | [W1+H,…] 卡二
     * 对调: [0,W2] 卡二 | [W2,W2+H] 握把 | [W2+H,…] 卡一
     * </pre>
     *
     * 尺寸变了就得重算(拖握把、切档、转屏),所以 applyCardScale / fillMain 之后都要跟一次。
     */
    private void applySwap(boolean animate) {
        if (pip1Card == null || pip2Card == null) return;
        boolean both = pip1Card.getVisibility() == View.VISIBLE
                && pip2Card.getVisibility() == View.VISIBLE;

        float t1 = 0f, t2 = 0f, th = 0f;
        // 独占时偏移没有意义 —— 场上只剩一张,留着偏移会把它推出屏幕
        if (both && swapped) {
            boolean portrait = isPortrait();
            int s1 = portrait ? pip1Card.getHeight() : pip1Card.getWidth();
            int s2 = portrait ? pip2Card.getHeight() : pip2Card.getWidth();
            int gap = Launchers.dp(this, HANDLE_DP);
            t1 = s2 + gap;
            t2 = -(s1 + gap);
            th = s2 - s1;
        }
        slide(pip1Card, t1, animate);
        slide(pip2Card, t2, animate);
        if (resizeHandle != null) slide(resizeHandle, th, animate);
    }

    /** 沿主轴挪一段。另一轴一律清零——转屏之后主轴换了,旧那一轴的偏移会变成错位。 */
    private void slide(View v, float t, boolean animate) {
        boolean portrait = isPortrait();
        v.animate().cancel();
        if (animate) {
            if (portrait) v.animate().translationY(t).setDuration(SWAP_MS).start();
            else v.animate().translationX(t).setDuration(SWAP_MS).start();
        } else {
            if (portrait) v.setTranslationY(t); else v.setTranslationX(t);
        }
        if (portrait) v.setTranslationX(0f); else v.setTranslationY(0f);
    }

    /** 让这张卡吃满主轴剩余空间(另一张已经 GONE,不参与分配)。 */
    private void fillMain(View card) {
        if (card == null) return;
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) card.getLayoutParams();
        boolean portrait = isPortrait();
        int main = portrait ? lp.height : lp.width;
        if (main == 0 && lp.weight > 0f) return;   // 已经是铺满的,别白触发重排
        if (portrait) {
            lp.height = 0;
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        } else {
            lp.width = 0;
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        }
        lp.weight = 1f;
        card.setLayoutParams(lp);
    }

    private View cardViewOf(PipCard card) {
        return card == pip1 ? pip1Card : pip2Card;
    }

    /**
     * 让关闭角标能探出卡片边框。父链上任何一层还开着 clipChildren,
     * 负 margin 探出去的那半个按钮就会被剪掉。
     *
     * 原来这里还要额外去关 ViewPager2 内部那层 RecyclerView 的裁剪;三层结构下
     * 舞台就是个 FrameLayout(XML 里已声明 clipChildren=false),没有隐藏层级了。
     */
    private void unclipForBadge() {
        if (stage == null) return;
        stage.setClipChildren(false);
        stage.setClipToPadding(false);
    }

    /**
     * 卡片上那个悬浮的关闭角标。两张卡各有一个,所以做成对象而不是一组方法。
     *
     * 它不能常驻:卡片被画面铺满,常驻就一直压着 app 自己右上角的按钮
     * (电话的 ⋮、地图的 SKIP)。而挪到卡片外面又跟这张卡失去了关联。
     * 所以做成碰一下浮现、几秒自动收——播放器的控制层就是这个路数。
     */
    /**
     * 卡片顶边操作条的显隐。**常驻**,只跟着卡片有没有在跑走,不做浮出/自动收起——
     * 改成常驻的原因见 layout_mode_grid.xml 里操作条那段注释(三版的账都记在那儿)。
     */
    private void showOps(View ops, boolean shown) {
        if (ops == null) return;
        ops.setVisibility(shown ? View.VISIBLE : View.GONE);
    }

    /**
     * 拖动/重建期间盖住画面,只留 app 图标——像它的启动页。
     *
     * 不这么做的话:拖动时画面被横向拉扁(虚拟屏尺寸是钉住的,只能靠缩放跟手),
     * 松手重建完又突然换成新画面。盖一层之后这两段都被藏起来了。
     */
    /**
     * 淡入要快 —— 它得赶在 app 重排那几帧之前盖住画面(见 PipCard.SETTLE_MS),
     * 但也不能是"啪"地一下,那跟直接看见变形一样生硬。
     */
    private static final long PH_IN_MS = 160L;
    /** 淡出比淡入从容:新画面是从占位层后面渐渐显出来的,这一下决定了整个切换的观感。 */
    private static final long PH_OUT_MS = 240L;

    private void showPlaceholder(View root, int layerId, int iconId,
                                 boolean show, String pkg, boolean instant) {
        final View layer = root.findViewById(layerId);
        if (layer == null) return;
        // 反复切换时上一次的动画还没跑完:cancel 保留当前 alpha 接着演,不会跳
        layer.animate().cancel();

        if (show) {
            ImageView icon = root.findViewById(iconId);
            if (icon != null) icon.setImageDrawable(appIcon(pkg));
            if (layer.getVisibility() != View.VISIBLE) {
                layer.setAlpha(0f);
                layer.setVisibility(View.VISIBLE);
            }
            // 聚焦切换:布局下一帧就变,淡入那 160ms 会把拉伸的画面漏出去,所以硬切盖死
            // (见 PipCard.beginSwitch)。淡出照旧,观感全靠它。
            if (instant) {
                layer.setAlpha(1f);
                return;
            }
            layer.animate().alpha(1f).setDuration(PH_IN_MS).start();
            return;
        }

        if (layer.getVisibility() != View.VISIBLE) return;
        layer.animate().alpha(0f).setDuration(PH_OUT_MS)
                .withEndAction(new Runnable() {
                    @Override public void run() { layer.setVisibility(View.GONE); }
                }).start();
    }

    /** 占位层上的图标。用系统原图而不是重绘图标:这里要的是"这是哪个 app",越像它自己越好。 */
    private android.graphics.drawable.Drawable appIcon(String pkg) {
        if (pkg == null) return null;
        try {
            return getPackageManager().getApplicationIcon(pkg);
        } catch (Exception e) {
            return null;
        }
    }

    // ---- DockView.Listener(空调走 AirCon 的 service call,真车已验证) ----

    @Override public void onTempChange(int delta) {
        hvacCtrl.onTempChange(delta);
    }

    /** 风量滑动松手:绝对档位,0 = 关空调。见 AirCon#fanTo。 */
    @Override public void onFanLevel(int level) {
        hvacCtrl.onFanLevel(level);
    }

    @Override public void onOpenHvacPanel() {
        hvacCtrl.onOpenHvacPanel();
    }

    @Override public void onAcAction(String action) {
        hvacCtrl.onAcAction(action);
    }

    /**
     * 抽屉不自己启动 app,选完回传给桌面——全屏还是画中画只有桌面知道
     * (它才拿得到两张卡的状态)。见 onActivityResult。
     */
    @Override public void onOpenDrawer() {
        // 带 EXTRA_FOR_LAUNCH:四种调用里只有这一种是"要开一个应用",也只有它该看见
        // 留白自己的内部页面(浏览器、游戏中心)。挑数据源那几条不带,免得它们
        // 出现在音乐源/卡位候选里 —— 选中只会得到一个坏掉的卡位。
        Intent i = new Intent(this, AppDrawerActivity.class);
        i.putExtra(AppDrawerActivity.EXTRA_FOR_LAUNCH, true);
        startActivityForResult(i, REQ_PICK_APP);
    }

    /**
     * 任务键短按(或长按"全部应用"):弹系统最近任务(所有后台 app 的卡片)。
     * 普通 app 开不了 recents,只能借提权链发 APP_SWITCH(187),同 onGoBack。
     */
    @Override public void onOpenRecents() {
        if (!Shell.ready()) {
            toast("最近任务需要先开启提权服务");
            return;
        }
        PRIV_IO.execute(() -> Shell.exec("input keyevent 187"));
    }

    /**
     * Dock 设置键短按:开外观面板。
     *
     * 原先这儿开的是「桌面设置」那个独立 Activity,2026-08-16 整个并进外观面板后删了 ——
     * 那个页面第一条就是"点我去外观面板",两处管的都是「桌面长什么样、怎么用」。
     * 长按仍然是就地编辑 Dock(见 DockView 里 SETTINGS 那个 case)。
     */
    @Override public void onOpenSettings() {
        openAppearance();
    }

    /*
     * ---- 自定义 Dock:整块搬去了 DockHost ----
     *
     * 编辑面板和 Dock 现在同处一个悬浮窗(见 DockHost.onEditDock),桌面不再参与。
     * 下面这两个是 DockView.Listener 的成员,桌面作为 DockHost 的委托方必须实现,
     * 但 DockHost **不会把它们转发过来** —— 编辑相关的回调它自己就地接了。
     */

    @Override public void onEditDock() { }

    @Override public void onDockEdited(java.util.List<DockItem> items) { }

    /**
     * 主页键:留白自己就是桌面,所以"回主页"= 收面板 + 把画中画层收起来露出主页,
     * 不用启动谁。从别的 app 上按时,DockHost 已经先把桌面拉到前台了。
     */
    @Override public void onGoOriginalHome() {
        Launchers.openOriginalHome(this);
    }

    /**
     * 主页键 = **回到干净的桌面**。屏幕上开着的东西一律收掉,不留上一程的残余。
     *
     * 这里收的是桌面自己这一层:空调面板、外观弹层、车控编辑面板,再加上所有 Dialog
     * (它们都走 {@link Immersive#show},所以能被统一收掉)。Dock 那一层的编辑面板和
     * 空调悬浮窗由 {@link com.liubai.blank.ui.DockHost#onGoHome} 负责,它先收自己再转发过来。
     *
     * 别只收"当前这个" —— 用户按主页键时并不知道屏幕上还压着什么,少收一个就是
     * "回了桌面但还有个面板赖着"。
     */
    @Override public void onGoHome() {
        hvacCtrl.closePanel();
        if (appearance != null && appearance.isOpen()) appearance.close();
        Immersive.dismissAll();
        setPipCollapsed(true, true);
    }

    /**
     * 返回键:普通 app 注入不了系统返回事件,只能借提权链发 keyevent。
     * 发给的是当前前台窗口 —— Dock 常驻在别的 app 之上时,按它就是让那个 app 返回。
     */
    @Override public void onGoBack() {
        // **先收桌面自己这一层**,收到了就不往下发。
        //
        // 外观面板这些是 addView 到 android.R.id.content 的**普通 View,不是独立窗口** ——
        // keyevent 4 发出去落在 HomeActivity.onBackPressed 上,而它对这些浮层一无所知,
        // 加上"已是默认桌面就吞掉返回",最后表现成**点了 Dock 的返回完全没反应**
        // (2026-08-23 真车:在外观面板里点返回)。onGoHome 早就收了这一层,onGoBack 漏了。
        if (closeTopOverlay()) return;
        if (!Shell.ready()) {
            toast("返回需要先开启提权服务");
            return;
        }
        PRIV_IO.execute(() -> Shell.exec("input keyevent 4"));
    }

    /**
     * 收掉桌面这一层最上面那个浮层,返回是否真收了。**返回键和物理返回键共用它**。
     *
     * 顺序按"谁压在上面"排:空调面板是悬浮窗、盖在最上;外观面板是全屏 content view;
     * 桌面编辑态在最底下。**新加桌面浮层往这儿加一条**(和 {@link #overlayShowing}
     * 那份名单是同一批东西)。
     */
    private boolean closeTopOverlay() {
        if (hvacCtrl.panelOpen()) { hvacCtrl.closePanel(); return true; }
        if (appearance != null && appearance.isOpen()) { appearance.close(); return true; }
        if (deskHost.editing()) { deskHost.setEditing(false); return true; }
        return false;
    }

    // ---- 生命周期 ----

    @Override
    protected void onResume() {
        super.onResume();
        // 「常驻」关掉时,Dock 只在桌面前台露面 —— 这条比按前台包名判定还准,
        // 而且不需要无障碍服务(那是 M2 做黑名单时才必须的)
        host.onDesktopResumed();
        StatusBarHost.get(this).onDesktopResumed(true);
        wallpaper.apply();   // 在设置里换过壁纸,回来这一下才生效
        deskHost.refreshCtrlStates();   // 同理:在原厂设置里切过日行灯/SOC 保电,回来这一下才跟上
        // 前台监听掉线了就救回来。放在这儿是因为服务**任何时候**都可能掉(系统回收、覆盖安装),
        // 启动时查一次兜不住 —— 已经连上时这一行就是个静态字段判断,不花钱。
        Overlay.healAccessibility(this, ForegroundWatch.component(this));
        // decorView 要等下一帧才算准备好,直接调有时不生效——参考 DiStyle(反编译
        // v2.1.6)的实现,它在同一个车机平台上是这么做沉浸式的。
        getWindow().getDecorView().post(() -> Immersive.apply(this));
        // 有没有新版。一个进程只查一次、同一个版本只弹一次(见 AppUpdater.autoCheck),
        // 剩下的入口在设置的「关于」页里,人想查随时自己点
        com.liubai.blank.util.AppUpdater.autoCheck(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        host.onDesktopPaused();
        StatusBarHost.get(this).onDesktopResumed(false);
        // 上滑收起的那两条栏在这儿还原:收起是**桌面上的临时状态**,人一离开就该回来 ——
        // 上滑收起的卡片在这儿还原:收起是**桌面上的临时状态**,人一离开就该回来
        if (deskHost.hidden()) deskHost.setHidden(false);
    }

    /**
     * 桌面重新可见:视频壁纸要重开 —— 上次不可见时把播放器整个交出去了(见 onStop)。
     *
     * 用 onStart 而不是 onResume:两者的差别是"可见"与"能操作"。桌面被一个半透明的
     * 系统弹窗盖住时只 onPause,壁纸还看得见,那时候停播就是当着用户的面把动画冻住。
     */
    @Override
    protected void onStart() {
        super.onStart();
        wallpaper.onStart();
        // WLAN/蓝牙状态变了就刷卡片。**必须是 onStart 而不是 onResume**:在系统弹窗里
        // 连 WiFi 时桌面只是失焦(onPause),没到 onStop —— 那正是要它跟着变的场景
        deskHost.startNetWatch();
    }

    /**
     * 桌面完全看不见了(全屏 app 盖住 / 进了设置页):把解码器整个交出去,不是暂停。
     *
     * pause 仍然占着一路硬解码器实例 —— 车机上路数有限,画中画那两路虚拟屏也在抢。
     * 看不见的壁纸不值得占着这份资源。回来时由 onStart 重开,垫底的首帧图接住那
     * 一两百毫秒的空档。
     */
    @Override
    protected void onStop() {
        super.onStop();
        wallpaper.onStop();
        deskHost.stopNetWatch();
    }


    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // 系统栏可能被短暂唤出(比如从屏幕边缘划入),焦点重新回来时得再压一次,
        // 不然沉浸式效果只在第一次进入时生效。
        if (hasFocus) Immersive.apply(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 只注销自己:切模式会先建新 Activity 再销毁旧的,不判一下会把新的那个清掉
        host.clearDelegate(this);
        // 虚拟屏是系统资源,不主动释放会连着上面那个 app 一起留着
        if (pip1 != null) pip1.stop();
        if (pip2 != null) pip2.stop();
        // 播放器同理:占着一路硬解码器;网页壁纸:不停它,脚本会跟着 WebView 一起漏在那
        wallpaper.onDestroy();
        // 候选区重铺排在 ViewTx 的静态 Handler 上,那个 Runnable 捕获着这个 Activity ——
        // 走的时候没跑完的得撤掉,不然跨着生命周期把它拽住
        deskHost.onDestroy();
    }

    /**
     * 存储权限的结果。这个权限只为「我的壁纸」那一组读
     * {@link com.liubai.blank.data.WallpaperStore#personalDir()},在线壁纸落在私有目录、不需要它。
     *
     * 授完当场把壁纸页重建一次:用户点"允许"回来就该看见自己拷的那些,而不是还得再切一下筛选。
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == AppearancePopup.REQ_STORAGE && appearance != null) {
            appearance.refreshWallpaperTab();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ROLE_HOME) {
            // 什么都不弹。设成没设成看外观面板 › 权限那一行 —— 在这儿 toast 一句
            // "还不是默认桌面"就又变成催促了,而且 Android 12 以后 toast 一律压在
            // 屏幕底部、正好糊在 Dock 上
        } else if (requestCode == REQ_PICK_APP && resultCode == RESULT_OK && data != null) {
            final AppRef app = new AppRef(data.getStringExtra("pkg"),
                    data.getStringExtra("cls"), data.getStringExtra("label"));
            // 等抽屉真正退场再决策:openApp 要看两张卡的状态,而卡片此刻
            // 还在被抽屉盖着,TextureView 拿不到 surface,画中画起不来。
            handler.post(() -> openApp(app));
        } else if (requestCode == REQ_PICK_SLOT_APP && resultCode == RESULT_OK && data != null) {
            // 给地图/音乐槽位挑的源。选完当场按新源开一次,免得还要再点一下
            String pkg = data.getStringExtra("pkg");
            DockItem.Kind kind = pendingSlotPick;
            pendingSlotPick = null;
            switchSlotSource(kind, pkg);
        } else if (requestCode == REQ_PICK_LIST_APP && resultCode == RESULT_OK && data != null) {
            // 设置面板里那张名单(Dock 让位)挑完了。面板此刻已被抽屉盖掉,
            // 加完再把它拉起来并停回原来那一页(tab 是静态的,自己记得住)
            String pkg = data.getStringExtra("pkg");
            AppearancePopup.Pick what = pendingAppPick;
            pendingAppPick = null;
            // 只剩 Dock 让位这一张名单(音乐源那张 2026-09-05 跟着设置页里那两项一起删了)
            if (what == AppearancePopup.Pick.DOCK_HIDE && pkg != null && !pkg.isEmpty()) {
                if (!new DockBlacklist(this).add(pkg)) toast("已在列表中");
            }
            // 面板一直 VISIBLE(被抽屉盖着没关),open() 会因 isOpen 早退不刷新 ——
            // 拉起后再强制就地重建当前页,新加的项当场出现,不用手动关了再开
            handler.post(() -> { openAppearance(); appearance.refreshOpenTab(); });
        }
    }

    private void toast(String msg) {
        Launchers.toast(this, msg);
    }

    /** 已是默认桌面吞掉返回;非默认(测试期从列表打开)允许返回退出。 */
    @Override
    public void onBackPressed() {
        // 屏幕上压着东西就先收它,别一下退出桌面(同 Dock 编辑态接住返回键)
        if (closeTopOverlay()) return;
        if (!DefaultHome.isDefault(this)) super.onBackPressed();
    }
}
