package com.liubai.blank.game;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.liubai.blank.R;
import com.liubai.blank.ui.StatusBarHost;
import com.liubai.blank.util.Immersive;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 游戏中心:车机屏当显示器,**手机浏览器当手柄**(手机端零安装)。
 *
 * 跟浏览器一样是「留白内部但独立可启动」的条目 —— 区别是它连 LAUNCHER 都不声明,
 * 所以原厂 DiLink 桌面上不会长出图标,只在留白自己的「全部应用」里露面
 * (见 AppDrawerActivity.loadApps 的注入)。抽屉选中后由桌面 openApp 走
 * {@code Launchers.launch} 的显式 Intent 拉起 —— 显式 Intent 不做 filter 匹配,
 * 没有 LAUNCHER 声明照样起得来;而它既不是地图也不是音乐源,slotFor 返回 null,
 * 天然走全屏,正是它该有的样子。
 *
 * 模拟靠 libretro 核心(mGBA 管 GB/GBC/GBA,FCEUmm 管 NES),核心是第三方预编译的 .so,
 * 运行时 dlopen;JNI 桥见 {@code cpp/bridge.c}。真车实测最紧的 GBA 也有 11.4 倍余量。
 *
 * **核心打进 apk**(2026-09-06):7 个核心的 .so.zip 放在 assets/cores/,启动时铺到
 * 内部目录(见 {@link CoreStore#seedFromAssets} 和 {@link #corePath})。自己编的
 * retrobridge 仍是唯一进 jniLibs 的原生库,核心走 assets 是因为它们要按机型 dlopen。
 *
 * **ROM 不进 apk**(版权物)。放在 {@link #romDir()} 下,用 adb push 或 U 盘拷进去。
 * 用 getExternalFilesDir 而不是 /sdcard/blank/roms:前者不需要任何运行时权限,
 * targetSdk 29 的分区存储也不用管。
 */
public class GameActivity extends Activity implements Immersive.AlwaysFullscreen {

    private static final String TAG = "Blank/Game";

    /** 手柄页监听的端口。固定值 —— 用户要把它连同 IP 一起敲进手机浏览器。 */
    private static final int PAD_PORT = 8899;

    /**
     * 街机核心,按优先级排。
     *
     * 街机 ROM 全是 {@code .zip},**光看后缀分不出该用哪个核心** —— kof98.zip 是 NeoGeo、
     * dino.zip 是 CPS2、gridlee.zip 是 1982 年的老基板,谁也认不出谁。所以这里不按
     * 后缀查表,而是**用手上已经装了的那个**:FBNeo 覆盖 CPS1/2/3 + NeoGeo + PGM,
     * 是主力;MAME 覆盖面更广(那些 80 年代的老基板只有它认),留作补充。
     *
     * 两个都装就用 FBNeo —— 它维护得最新,同一个游戏两边都支持时它更稳。
     */
    private static final String[] ARCADE_CORES = {
            "libfbneo_libretro.so",
            "libfbalpha2012_neogeo_libretro.so",
            "libfbalpha2012_cps1_libretro.so",
            "libfbalpha2012_cps2_libretro.so",
            "libmame2003_plus_libretro.so",
    };

    private FrameLayout root;
    /** 左栏下半那排功能键。换肤时整块重建 —— 颜色是建的时候取的。 */
    private LinearLayout settingsRow;
    private LinearLayout leftBar;
    /** 右栏:扫码连手柄那面码墙(见 {@link PlayerPanel})。和左栏对称,全屏时一起收。 */
    private LinearLayout rightBar;
    /** 左栏最上面那两行:正在玩的是哪个游戏、哪台机器(见 buildNowPlaying)。 */
    private TextView nowName, nowPlat;
    /** 进全屏时亮两秒的那条提示(「双击画面退出全屏」)。全屏下屏上没有任何控件。 */
    private TextView fullTip;
    /** 侧栏宽度。算画面倍数时要用(左右各留一条,见 applyGeometry)。 */
    private int sideW;
    /** 全屏:两条侧栏收起,画面按整屏宽重算(见 applyGeometry)。 */
    private boolean fullscreen;

    // ---- 画面比例三档(见 applyGeometry 和 cycleFit)----
    /** 整数倍方形像素:一个游戏像素正好占 N×N 个屏幕像素,边缘最硬。出厂就是这档。 */
    private static final int FIT_PIXEL  = 0;
    /** 按核心报的宽高比等比铺满:不变形,最接近当年在电视上的样子。 */
    private static final int FIT_ASPECT = 1;
    /** 填满可用区:没有黑边,代价是变形(FC 横向要拉宽 45%)。 */
    private static final int FIT_FILL   = 2;
    private static final String[] FIT_NAME = { "原始", "还原", "拉满" };
    private static final String KEY_FIT = "screen_fit";

    /** 当前是哪一档。**全局设置**,换游戏不重置(存 gamecenter.xml)。 */
    private int fit = FIT_PIXEL;
    /** 那颗切档键。键面直接写着当前档名,不另开一处显示。 */
    private TextView fitBtn;
    /**
     * 这一局核心报的宽高比。**0 = 核心没报**,那时退回按方形像素算(libretro 的约定)。
     *
     * 不能拿 gameW/gameH 顶替:FC 的 256×240 是给 4:3 电视看的,像素宽高比 8:7,
     * 按方形像素画比当年窄 25%;竖屏街机更反过来(报 0.75,方形像素会画成横的)。
     */
    private float gameAspect;
    /** 左栏那颗暂停键。文字要能从外面同步(见 {@link #syncPauseBtn})。 */
    private TextView pauseBtn;
    /** 存档/读档两颗键。核心不支持快照时要灰掉,操作完还要在键上回一句。 */
    private TextView saveBtn, loadBtn;
    /** 快进键。当前几倍速就写在键上,不另开一处显示。 */
    private TextView speedBtn;
    /** 金手指键。取码要联网,所以它还要能在键上说"正在取"。 */
    private TextView cheatBtn;
    /**
     * 这一局的金手指表(含每条开没开)。null = 还没取过。
     *
     * **开关状态存在这儿,不在 native 侧** —— 那边只收一张"开着的"最终表
     * (见 Retro.nativeSetCheats),少一套要同步的状态。换游戏时整个丢掉。
     */
    private java.util.List<CheatStore.Cheat> cheats;
    /**
     * 已经试过下 cheat ini 的街机 romset。
     *
     * 官方包里没有那个 romset 是常事(几千个 ini 也盖不全),没这一层的话
     * 每次点这个游戏都要重新等一次 4 秒超时。
     */
    private final java.util.Set<String> cheatIniTried = new java.util.HashSet<>();
    /**
     * 正在下 ini 的那个 romset。**它挡的是"用户又点了一下"**:
     * 下载期间再点同一个游戏直接忽略,等下完了自己会接着装。
     */
    private String cheatIniLoading;
    /** 左栏上半:手柄入口卡(点开是扫码弹层,见 PadPopup)。 */
    private PlayerPanel players;
    private FrameLayout center;
    private SurfaceView surfaceView;
    private TextView hint;
    private PadServer pad;
    private CoreStore cores;
    private final AudioOut audio = new AudioOut();
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

    private GameLibrary lib;
    private GameListView list;
    /** 正在玩的那个。加载失败时是 null,但那时人**仍然在游戏页**(见 inGame)。 */
    private GameLibrary.Game current;
    /**
     * **还装在核心里**的那个游戏。和 {@link #current} 不是一回事:
     * 回列表时 current 置空(那时没有"正在玩的"),这个不清 —— 核心并没有卸,
     * 游戏只是被列表页盖住、暂停着。
     *
     * 有了它 {@link #play} 才认得出"点的就是刚才那个",直接掀开盖子接着打。
     * 没有它的时候,回列表再点同一张卡是整个 nativeShutdown + nativeInit 重载,
     * 正在打的一局直接没了 —— 而文档里承诺的是"回来接着玩不用重新加载"。
     */
    private GameLibrary.Game loadedGame;
    /**
     * 人在游戏页还是列表页。
     *
     * **不能拿 current 判断**:游戏加载失败时 current 是 null,而人明明还在游戏页上
     * 看着错误提示 —— 那时按返回会直接弹回桌面,而不是回列表(实测踩过)。
     */
    private boolean inGame;
    /** 核心自报的画面分辨率,换游戏会变(GB 160×144 / GBA 240×160 / NES 256×240)。 */
    private int gameW = 160, gameH = 144;

    private int statusH, dockH;
    /** 上次算几何时的窗口尺寸 —— 只有真变了才重算,否则 setLayoutParams 会自己触发自己。 */
    private int lastW, lastH;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Immersive.windowBg(this);   // 窗口底跟模式走,否则转场露白(见那个方法的说明)

        // 模式不给游戏中心时**挡在最前面**:后面那一大串(扫 ROM、建列表、拉在线目录、
        // 起模拟器)一件都不做,进来就是干干净净一句话(见 Capabilities.gameCenter)。
        if (!com.liubai.blank.mode.ModeManager.get().caps().gameCenter) {
            showLockedPage();
            return;
        }

        // 玩游戏时顶栏收起(见 onResume),所以不给它让位 —— 那 90px 正好卡着放大倍数,
        // 让出去街机就只能 ×1、画面缩水一半。Dock 照常让位:它不收,而且 984 够用了。
        statusH = 0;
        dockH = getResources().getDimensionPixelSize(R.dimen.dock_space);
        // 侧栏宽度是**算出来的**(见 dimens.xml 那条注释):中间必须给街机的 ×2 留够 1280
        sideW = getResources().getDimensionPixelSize(R.dimen.game_side_width);

        root = new FrameLayout(this);
        root.setBackgroundColor(com.liubai.blank.mode.ModeManager.get().bg());

        fit = getSharedPreferences("gamecenter", MODE_PRIVATE).getInt(KEY_FIT, FIT_PIXEL);

        // **画面铺整屏、两条侧栏浮在它上面**,不是并排的三栏。
        //
        // 老游戏按整数倍放大后左右必然剩大片空白:左边那条摆功能键,右边那条摆
        // 扫码连手柄的码墙。但画面该在**整块屏幕**上居中,不是在"减掉左栏之后
        // 剩下的那块"里居中 —— 后者会让画面整体右移小两百像素,人坐在中间看着就是歪的。
        //
        // 不重叠是算出来的,不是靠布局挡的:倍数按「屏宽减掉左右各一条栏」算
        // (见 applyGeometry),所以画面最宽时两边缘正好停在两条栏的外侧。
        center = new FrameLayout(this);
        root.addView(center, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        leftBar = new LinearLayout(this);
        leftBar.setOrientation(LinearLayout.VERTICAL);
        leftBar.setPadding(dp(16), dp(16), dp(16), dockH + dp(16));
        root.addView(leftBar, new FrameLayout.LayoutParams(
                sideW, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START));

        rightBar = new LinearLayout(this);
        rightBar.setOrientation(LinearLayout.VERTICAL);
        rightBar.setPadding(dp(16), dp(16), dp(16), dockH + dp(16));
        root.addView(rightBar, new FrameLayout.LayoutParams(
                sideW, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END));

        surfaceView = new SurfaceView(this);
        center.addView(surfaceView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        hint = new TextView(this);
        hint.setTextColor(com.liubai.blank.mode.ModeManager.get().ink());
        hint.setTextSize(16);
        hint.setGravity(Gravity.CENTER);
        hint.setLineSpacing(0, 1.5f);
        hint.setPadding(48, 40, 48, 40);
        // 压一层底:它有时要盖在画面上说话(下载进度),没有底色就跟游戏画面糊在一起
        hint.setBackgroundColor(com.liubai.blank.mode.ModeManager.get().bg());
        center.addView(hint, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        // ---- 左栏:正在玩什么 → (空) → 功能键**贴底** ----
        // 贴底是因为车里手是从下面伸上来的:最常按的那几颗(存档、选游戏)离手最近。
        // 中间那块空着的高度由一根 weight 撑,不是靠间距凑
        leftBar.addView(buildNowPlaying());
        View gap = new View(this);
        leftBar.addView(gap, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        settingsRow = buildSettings();
        leftBar.addView(settingsRow);

        // ---- 右栏:一整面码墙,每个玩家一张 ----
        // 码**常驻**,不再是"点开入口卡才有"。右边这条栏空着也是空着,而扫码是
        // 上车玩的第一件事;摆在那儿它自己会说话,不用再靠进游戏时弹一次窗提醒。
        players = new PlayerPanel(this);
        rightBar.addView(players, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 进全屏时报一句「双击画面退出」,两秒后自己走。
        //
        // **全屏下屏幕上一个控件都没有**,双击这件事人不会自己猜到 —— 常驻一颗
        // 退出按钮又等于全屏没收干净(它就压在画面上)。说一次、然后消失,
        // 是这两者之间唯一说得通的做法
        fullTip = buildFullTip();
        FrameLayout.LayoutParams elp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        elp.topMargin = dp(16);
        fullTip.setVisibility(View.GONE);
        root.addView(fullTip, elp);

        // **双击画面 = 进/出全屏。** 全屏里没有别的东西可点,而画面本身不吃触摸,
        // 拿它当开关正合适(视频播放器也是这个手势)。
        // 只在游戏页认:列表页那一层盖在上面,空白处的触摸会落到这儿来
        final android.view.GestureDetector gd = new android.view.GestureDetector(this,
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    // 不返回 true 的话后面的事件根本不来,双击也就无从谈起
                    @Override public boolean onDown(android.view.MotionEvent e) { return true; }
                    @Override public boolean onDoubleTap(android.view.MotionEvent e) {
                        if (!inGame) return false;
                        setFullscreen(!fullscreen);
                        return true;
                    }
                });
        center.setOnTouchListener((v, e) -> gd.onTouchEvent(e));

        // 窗口尺寸会变一次:onCreate 时还没进沉浸式(顶栏/导航栏还占着位),
        // Immersive.apply 之后才铺满物理屏。用监听而不是 post 一次 —— 早算的那次
        // 可用高度少一档,画面会白白小一号(模拟器上实测 ×5 而不是 ×6)。
        // **监听 center,不是 root**:applyGeometry 按中间栏的尺寸算,记的也是它。
        // 挂在 root 上就是死循环 —— 记 1320×1080、比 1920×1080,永远不等,
        // 每次布局都重算、重算又触发布局(实测刷了 4914 次,surface 一直重建、画面全灰)
        center.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> {
            if (v.getWidth() != lastW || v.getHeight() != lastH) applyGeometry();
        });

        // 选游戏页盖在最上面。它和游戏是**同一个 Activity 的两个状态**,不是两个页面 ——
        // 切回列表时核心不用重新加载,返回键的层级也顺:游戏里按返回回列表,列表里按返回回桌面
        cores = new CoreStore(this);
        lib = new GameLibrary(this);
        lib.scan();
        list = new GameListView(this, lib);
        list.setCoreChecker(p -> coreReady(coreFor(p)));
        list.setListener(new GameListView.Listener() {
            @Override public void onPick(GameLibrary.Game g) { play(g); }
            @Override public void onNeedCore(GameLibrary.Platform p) {
                downloadCore(coreFor(p), () -> list.refresh());
            }
            @Override public void onLoadMore() { loadMoreOnline(); }
            @Override public void onSearch(String q) { searchOnline(q); }
            @Override public void onGenre(OnlineStore.Genre g) { selectGenre(g); }
            @Override public void onTab(GameLibrary.Platform p) { loadGenresFor(p); }
            @Override public void onDelete(java.util.List<GameLibrary.Game> games) {
                confirmDelete(games);
            }
        });
        root.addView(list, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);

        showList();
        confirmSafety();          // 先问一句车停稳了没有,答完才让他挑游戏
        // 这几发都要往 list 上回填(还有 inflight 要报"正在加载"),**必须等它建好**
        refreshOnlineList();          // 后台拉在线目录,把没下载的云端游戏并进列表
        refreshHot();                 // 首页那两块热门单独抓(站方策展,不是列表页前几个)
        // 两台机器的类型栏都先抓着。点左栏切过去时缓存里已经有,类型栏和列表
        // 同时出现 —— 不会晚一拍冒出来再把整页往下推一格
        fetchGenres(GameLibrary.Platform.NES, false);
        fetchGenres(GameLibrary.Platform.ARCADE, false);
        fetchGenres(GameLibrary.Platform.GBA, false);

        // 诊断专用,不留在正式功能里:批量核对一批 romset 能不能玩,不用一个个点。
        // adb shell am start -n <pkg>/.game.GameActivity --ez batchTest true
        // 结果全部写 logcat(tag Blank/Game,行首 BATCHTEST),跑完打 BATCHTEST_DONE。
        if (getIntent() != null && getIntent().getBooleanExtra("batchTest", false)) {
            new Thread(this::runArcadeBatchTest, "blank-batch-test").start();
        }

        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) {
                Retro.nativeSetSurface(h.getSurface());
                Retro.nativeStart();     // 没装进游戏时 native 会自己挡掉(见 g_game_ok)
                audio.start();
            }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int ht) {
                Retro.nativeSetSurface(h.getSurface());   // 尺寸变了要重新绑,否则缓冲几何过期
            }
            @Override public void surfaceDestroyed(SurfaceHolder h) {
                audio.stop();
                Retro.nativeStop();
                Retro.nativeSetSurface(null);
            }
        });

        pad = new PadServer(this, PAD_PORT);
        // 手机上那排功能键。**回调在 accept 线程上**,碰 Retro/UI 都得切回主线程
        pad.setCommandListener((cmd, arg) -> handler.post(() -> onPadCommand(cmd, arg)));
        // 谁连上了、谁断了,二维码那格当场亮/灭 —— 回调在 accept 线程上,要切回主线程
        pad.setPlayersListener(on -> handler.post(() -> {
            for (int i = 0; i < on.length; i++) players.setOnline(i, on[i]);
        }));
        pad.start();
        padTick.run();
        handler.postDelayed(sramTick, 60000);
    }

    /**
     * 刷手柄地址与连接状态。
     *
     * 得轮询:IP 是后台线程枚举网卡取的(主线程上做会卡,见 PadServer.localIp),
     * 连接状态也在 accept 线程上变。两秒一次够了 —— 这里只拼个字符串。
     */
    private final Runnable padTick = new Runnable() {
        @Override public void run() {
            if (pad != null) players.setBaseUrl(pad.url());
            handler.postDelayed(this, 2000);
        }
    };

    /**
     * 定时把电池存档落盘。
     *
     * 光靠退出时写不够 —— 车机上进程说没就没(系统回收、熄火掉电),那种时候
     * onPause 都不一定跑得到。一分钟一次:没变就不写(native 侧 memcmp 挡着),
     * 真变了最多也就丢一分钟的进度。
     */
    private final Runnable sramTick = new Runnable() {
        @Override public void run() {
            if (inGame) Retro.nativeFlushSram(false);
            handler.postDelayed(this, 60000);
        }
    };

    /**
     * 进门先问一句「车停稳了没有」。
     *
     * <b>这是整个游戏中心唯一一处主动打断用户的地方</b>,和"能不问就别问"那条不冲突 ——
     * 那条管的是我们自己的实现细节(下核心、铺 BIOS、解压 ROM,用户不该知道这些东西
     * 存在);这条是安全:游戏会占满整块中控屏,行驶中它盖住的是车辆信息。
     *
     * 一次会话只问一次(它挂在 onCreate 里)。Activity 是 singleTask 常驻的,
     * 从游戏页回列表、再从桌面卡片点进来都不重走 onCreate,所以不会重复弹;
     * 只有真正退出过才会再问。
     *
     * 按返回键 = 退出,不是"默认同意" —— 二次确认要是能被返回键绕过去就没意义了。
     *
     * 用 {@link com.liubai.blank.ui.ConfirmPopup} 而**不是系统 AlertDialog**:
     * 后者顶着系统默认皮肤,和留白整套(深色圆角、48dp 大按钮、跟着模式换色)完全
     * 两个调性,在车机屏上小得可怜;而且它配 Immersive.show 之后按钮还可能点不动
     * —— 这两条 ConfirmPopup 的类注释里早写着了,第一版没查就写了 AlertDialog。
     */
    /** 模式不给游戏中心时看到的那一页:说清楚是没开,别让人以为坏了。 */
    private void showLockedPage() {
        FrameLayout box = new FrameLayout(this);
        box.setBackgroundColor(com.liubai.blank.mode.ModeManager.get().bg());
        TextView t = new TextView(this);
        t.setText("游戏中心在完整模式里\n\n切到完整模式就能玩,已经下过的游戏也在");
        t.setTextSize(16f);
        t.setLineSpacing(dp(6), 1f);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(com.liubai.blank.mode.ModeManager.get().muted());
        box.addView(t, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        setContentView(box);
    }

    private void confirmSafety() {
        com.liubai.blank.ui.ConfirmPopup.show(this,
                "先停稳,再开玩",
                "游戏会占满整块中控屏,行驶中它盖住的是车辆信息。",
                "车已停好", () -> { },
                "退出", this::finish);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Immersive.apply(this);
    }

    /**
     * 玩的时候把顶栏收起来,退出去再放回来。
     *
     * 放在 onResume/onPause 而不是 onCreate/onDestroy:切后台时桌面重新露出来,
     * 那会儿顶栏该在;等切回游戏再收。
     */
    @Override
    protected void onResume() {
        super.onResume();
        StatusBarHost.setHidden(true);
        // 切后台时 onPause 暂停了模拟;回来只有还在游戏页才恢复 ——
        // 列表页本来就是暂停的(见 showList),不能在这里一刀切继续
        if (inGame) Retro.nativePause(false);
        syncPauseBtn();
        applyMode();
    }

    /**
     * 把这一屏的颜色重新取一遍。
     *
     * 配色跟着模式走(见 GameListView 里那段),而模式是在桌面的外观面板里切的 ——
     * 切完再回到游戏中心,这个 Activity 多半还活着(singleTask),onCreate 不会再跑。
     * 不重刷的话它就顶着上一套配色,跟刚换好的桌面又是两拨。
     *
     * 做法是**把取过色的那几块整个重建**:颜色都是建 view 的时候取的,重建最省心。
     */
    private void applyMode() {
        if (root != null) root.setBackgroundColor(com.liubai.blank.mode.ModeManager.get().bg());
        if (hint != null) {
            hint.setTextColor(com.liubai.blank.mode.ModeManager.get().ink());
            hint.setBackgroundColor(com.liubai.blank.mode.ModeManager.get().bg());
        }
        if (players != null) players.reskin();
        if (leftBar != null && settingsRow != null) {
            leftBar.removeView(settingsRow);
            settingsRow = buildSettings();
            leftBar.addView(settingsRow);
            syncPauseBtn();
            syncSpeedBtn();
            syncStateBtns();
        }
        skinNowPlaying();
        if (fullTip != null) skinFullTip(fullTip);
        if (list != null) list.refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        StatusBarHost.setHidden(false);
        // 切后台就暂停模拟:不暂停的话模拟线程和音频照常全速跑,
        // 车机上白烧一个满核,游戏音还一直外放。run_loop 暂停时
        // 只睡不推进,回来重新对表,不会追帧
        Retro.nativePause(true);
        // **切走可能就再也回不来了** —— 车机上被系统回收、熄火掉电都是常态。
        // 这是把电池存档写下去的最后机会,当场写完(顺序要紧:先暂停,写的那一刻
        // 模拟线程已经停稳,不会拷到半新半旧的存档,见 nativeFlushSram)
        Retro.nativeFlushSram(true);
    }

    /**
     * 核心在哪。核心打进 apk(assets/cores),启动时 {@link CoreStore#seedFromAssets} 铺进来;
     * 用户也可自己放进 dropDir 搬进来。买断街机核心 FBNeo 16 MB 从国外 buildbot 拉太慢,
     * 所以 2026-09-06 起全打进包、本地铺,点游戏零网络。
     *
     * 路径只能是 {@link #getFilesDir()}(app 内部目录)。**不能放 /sdcard** ——
     * 外部存储是 noexec 挂的,dlopen 一定失败;而 getExternalFilesDir 虽然写着 files,
     * 落点仍在外部存储上,一样不行。ROM 反过来:它只是数据,放外面方便用户自己拷。
     */
    private String corePath(String coreName) {
        return new File(cores.dir(), coreName).getAbsolutePath();
    }

    /**
     * 核心的 system 目录:BIOS 放这儿,存档也落这儿。
     *
     * **必须是外部目录**,因为里面的东西要用户自己放 —— NeoGeo 那一挂(拳皇、合金弹头)
     * 没有 {@code neogeo.zip} 一个都跑不起来,而 app 内部目录在 release 包上
     * 连 {@code run-as} 都进不去。
     *
     * 跟核心正相反:核心是**代码**,只能落内部目录(外部存储 noexec,dlopen 必失败);
     * BIOS 和存档是**数据**,读写外部存储没有任何问题,放外面用户还能自己备份。
     */
    private String systemDir() {
        File d = getExternalFilesDir("system");
        if (d != null && !d.exists()) d.mkdirs();
        return d != null ? d.getAbsolutePath() : getFilesDir().getAbsolutePath();
    }

    /**
     * 电池存档(.srm)落在哪:{@code system/<ROM 基名>.srm}。
     *
     * 名字按 RetroArch 那套通行约定走(ROM 基名 + .srm),存档能和别的模拟器互换;
     * 目录用 system/ 是既定设计(见 {@link #systemDir}),外部存储、用户能自己备份。
     *
     * <b>基名取 {@code g.file},不能取 {@code lib.romFor(g)}</b> —— 后者对 zip 包着的
     * 掌机 ROM 返回的是缓存里那份 hash 命名的解压产物,系统清一次缓存名字就变了,
     * 存档等于丢。
     */
    private String sramPath(GameLibrary.Game g) {
        return new File(systemDir(),
                GameLibrary.baseName(g.file.getName()) + ".srm").getAbsolutePath();
    }

    /** 这个核心铺好没有。核心打进 apk、启动时铺进内部目录(见 {@link CoreStore#seedFromAssets})。 */
    private boolean coreReady(String coreName) {
        return cores.installed(coreName);
    }

    /**
     * 核心还没铺好就点了游戏(启动铺核心的后台线程还没跑完),当场补上再接着做 {@code then}。
     *
     * 核心打进 apk,正常启动时就铺好了({@code coreReady} 为真直接开);只有开机头几秒
     * 抢在铺完之前点游戏才走到这儿。{@link CoreStore#download} 会先从 assets 就地解出
     * (零网络),assets 缺了才退回 buildbot。
     *
     * 补完自动接着开游戏,不问、不确认。用户不该知道"核心"这回事,他只是点了一个游戏。
     */
    private void downloadCore(String coreName, Runnable then) {
        hint.setVisibility(View.VISIBLE);
        hint.setText("正在准备…");
        CoreStore.io().execute(() -> {
            // 先看用户自己放没放 —— 本地装的优先于联网,车里没网也能玩。
            //
            // 搬进来了就直接重试,**不判断搬的是不是刚才以为缺的那个**:街机核心是按
            // "装了哪个"挑的(见 ARCADE_CORES),搬之前算出来的 coreName 是"一个都没装
            // 所以打算去装第一个",搬进来另一个照样能开。重试时会重新挑一遍。
            // 不怕循环:第二次 importDropped 就返回空了(同名同大小会跳过)。
            if (!cores.importDropped().isEmpty()) {
                post(then);
                return;
            }
            final CoreStore.Remote t = cores.resolve(coreName);
            // 进度按百分比节流:15 MB 用 64 KB 一块读,不节流就是上千次 UI 刷新
            final int[] lastPct = { -1 };
            boolean ok = cores.download(t, (done, all) -> {
                int pct = all > 0 ? (int) (done * 100 / all) : -1;
                if (pct == lastPct[0]) return;
                lastPct[0] = pct;
                post(() -> hint.setText("正在准备 " + t.name + "\n"
                        + (pct >= 0 ? pct + "%" : GameLibrary.humanSize(done))));
            });
            post(() -> {
                if (ok) {
                    hint.setVisibility(View.GONE);
                    then.run();
                } else {
                    hint.setText("下载失败,检查一下网络\n" + t.name
                            + "\n\n也可以自己把核心放到:\n" + cores.dropDir());
                }
            });
        });
    }

    // ---- 在线游戏库(yikm) ----

    /**
     * 在线站上拉几个分类。
     *
     * 站上分类挺多(SFC/NDS/MD/DOS…),但车机上就玩这两台机器,别的分类
     * 拉回来也是占列表位置。yikm 的 FC 在 tag=0、街机在 tag=9(同一条
     * /nes 列表,靠 tag 分),后缀决定落盘文件名,核心按后缀认平台。
     */
    private static final OnlineStore.Category[] ONLINE_CATS = {
            new OnlineStore.Category("红白机", 0, ".nes", 0xFFE05B4A),
            new OnlineStore.Category("街机", 9, ".zip", 0xFFE0A34A),
            new OnlineStore.Category("GBA", 11, ".gba", 0xFF6B8FE0),
    };

    /**
     * 平台 → 站上的 tag。**顺序要和 {@link #ONLINE_CATS} 对上**(类型页的 e 参数按下标取)。
     *
     * GB/GBC 站上没有单独的分类,跟着 GBA 走 —— 反正都是 mGBA 那个核心。
     */
    private static int catIndexOf(GameLibrary.Platform p) {
        if (p == GameLibrary.Platform.ARCADE) return 1;
        if (p == GameLibrary.Platform.GBA || p == GameLibrary.Platform.GB) return 2;
        return 0;
    }

    /**
     * 翻页保险丝 —— **不是**业务上的页数上限。
     *
     * 到底与否由站方的翻页条说了算(见 {@link OnlineStore.Page#hasNext})。这个数
     * 只防一种情况:站点改版导致 hasNext 恒为 true,那就成了死循环。所以它必须
     * **大于任何一个分类的真实页数**,否则又变回老样子把人截在半路。
     *
     * 实测(2026-08-24,桌面 UA 每页 20 个):FC 1031 页 ≈ 20602 个、
     * 街机 49 页 = 974 个、GBA 159 页 ≈ 3171 个。取 1500 留出余量。
     */
    private static final int ONLINE_MAX_PAGE = 1500;
    /**
     * 每个分类各自拉到第几页(下标对齐 {@link #ONLINE_CATS})。0 = 还没拉过。
     *
     * **必须按分类分开记。**三个分类真实页数差两个数量级(FC 1031 / 街机 49),
     * 共用一个计数器的话,翻过第 49 页时街机早就到底,而站方对超界页是钳回
     * 最后一页而非返回空 —— 于是每翻一页都把街机最后 14 个游戏重复灌一遍。
     * 老代码没暴露这个问题,只是因为上限 15 谁都没翻到底。
     */
    private final int[] catPage = new int[ONLINE_CATS.length];
    /** 每个分类是否已到底(下标对齐 {@link #ONLINE_CATS})。 */
    private final boolean[] catEnd = new boolean[ONLINE_CATS.length];
    /** 正在拉下一页:滚动到底会触发多次,这一挡保证同一时间只有一发在途。 */
    private boolean onlineLoading = false;
    /** 已拉到的全部云端条目(跨页累积)。mergeOnline 用完整列表,防止只并最后一批。 */
    private final java.util.List<OnlineStore.Remote> onlineAll = new java.util.ArrayList<>();
    /** 当前选中的类型分类,null = 平台全部。 */
    private OnlineStore.Genre currentGenre;
    /** 类型模式已拉到的全部云端条目(跨页累积),展示前转成 Game(见 toGenreGames)。 */
    private final java.util.List<OnlineStore.Remote> genreAll = new java.util.ArrayList<>();
    /** 类型模式下一次该拉第几页。0 = 还没拉过。 */
    private int genrePage = 0;
    /** 当前类型是否已拉到底(站方翻页条说的,见 OnlineStore.Page#hasNext)。 */
    private boolean genreEnd = false;
    /** 正在下载的云端游戏。null = 没有在下的。下载期间点别的云端游戏会被拦下来。 */
    private volatile GameLibrary.Game downloading = null;
    /**
     * 在途的列表请求数(平台列表 / 类型列表 / 搜索)。
     *
     * 列表空着时,是"还在路上"还是"真没有"是两句不同的话 —— 用计数而不是
     * 一个布尔,因为几条请求可能叠着跑,谁先回来都不该把别人的在途状态抹掉。
     */
    private int inflight;
    /**
     * 搜索请求的序号。
     *
     * 网站搜索慢起来要几秒,而人打字是连着的:先发的那一发后回来会把新词的
     * 结果盖掉(300ms 防抖挡不住网络乱序)。回填前比一下序号,过期的直接扔。
     */
    private int searchSeq;

    /** 在途 +1 / -1,顺带把"正在加载"告诉列表。 */
    private void inflight(int delta) {
        inflight = Math.max(0, inflight + delta);
        list.setLoading(inflight > 0);
    }

    /**
     * 拉在线列表的第一页并进游戏库。
     *
     * 列表是**分页**的,跟网站上一样:一次每个分类各拉一页(桌面 UA 20 条),
     * 滚到底再拉下一页(见 {@link #loadMoreOnline})。第一页只有几十 KB,
     * 车机网也是眨眼的事。拉失败就静默 —— 本地已下载的照常玩,下次进来再试。
     */
    private void refreshOnlineList() {
        java.util.Arrays.fill(catPage, 0);
        java.util.Arrays.fill(catEnd, false);
        onlineLoading = false;
        onlineAll.clear();
        loadMoreOnline();
    }

    /**
     * 网站搜索:走 yikm 的 /search?name= 接口(支持中文名、英文 romset 名、数字),
     * 结果只留车机能玩的(FC/街机/GBA,见 OnlineStore.search)。网站的搜索
     * 索引很弱(实测搜「魂斗罗」,FC 版一个都不在索引里),所以补两路:
     * ①本地模糊搜索 —— 手工推的包、站外游戏按名字照样能搜出;
     * ②已拉到的在线列表数据(onlineAll/genreAll,来自列表页,索引是全的)——
     * 列表里明明有的「魂斗罗(美版)」「4合1」,搜索接口没有,列表数据里有。
     */
    private void searchOnline(String q) {
        final int seq = ++searchSeq;
        inflight(1);
        OnlineStore.io().execute(() -> {
            java.util.List<GameLibrary.Game> hits = new java.util.ArrayList<>();
            try {
                // 本地优先:所有平台按名字模糊匹配,已下载的游戏点一次就进
                hits.addAll(lib.search(null, false, q));
                java.util.Set<String> seenIds = new java.util.HashSet<>();
                for (GameLibrary.Game g : hits) {
                    if (g.online != null) seenIds.add(g.online.id);
                }
                // 已拉到的列表数据(平台列表 + 类型列表,按名字模糊匹配)。
                // 这些卡片来自列表页,不用再打 /search —— 那是另一套弱索引
                java.util.List<OnlineStore.Remote> pool = new java.util.ArrayList<>(onlineAll);
                pool.addAll(genreAll);
                String needle = q.trim().toLowerCase(Locale.US);
                for (OnlineStore.Remote r : pool) {
                    if (!r.name.toLowerCase(Locale.US).contains(needle)) continue;
                    addSearchHit(hits, seenIds, r);
                }
                // 网站 /search:英文 romset 名、数字只有它认识
                for (OnlineStore.Remote r : new OnlineStore().search(q)) {
                    addSearchHit(hits, seenIds, r);
                }
            } catch (Exception e) {
                Log.w(TAG, "网站搜索失败: " + e);
            }
            // 本地先出、云端按网站顺序在后;已下载的(本地条目)总在云端同名前
            hits.sort((a, b) -> Boolean.compare(a.cloud, b.cloud));
            post(() -> {
                inflight(-1);
                if (seq != searchSeq) return;      // 后面又输入了,这批是上一个词的结果
                list.setSearchHits(hits);
            });
        });
    }

    /** 往搜索结果里加一条云端条目,id/同名同平台都去重。 */
    private void addSearchHit(java.util.List<GameLibrary.Game> hits,
                              java.util.Set<String> seenIds, OnlineStore.Remote r) {
        GameLibrary.Platform p = GameLibrary.Platform.bySuffix(r.cat.suffix);
        if (p == null) return;
        if (seenIds.contains(r.id)) return;
        for (GameLibrary.Game g : hits) {
            if (g.platform == p && g.name.equals(r.name)) return;   // 本地同名同平台
        }
        seenIds.add(r.id);
        hits.add(new GameLibrary.Game(new File(lib.romDir(), r.romFileName()), p, r));
    }

    /**
     * 拉在线列表的下一页,并入游戏库。
     *
     * 由列表滚到底触发(见 GameListView.Listener.onLoadMore)。每个分类走自己的
     * 页码、自己判到底(见 {@link #catPage}/{@link #catEnd}):街机 49 页拉完就
     * 不再碰它,FC 还能接着往下翻。全部分类都到底了才禁掉「下一页」。
     */
    private void loadMoreOnline() {
        if (onlineLoading) return;
        if (currentGenre != null) {      // 类型模式:「下一页」拉的是该类型的下一页
            loadGenrePage();
            return;
        }
        if (allCatsEnd()) return;
        onlineLoading = true;
        inflight(1);
        // 这一发每个分类各拉自己的下一页,快照下标避免和回调里的写抢
        final int[] want = new int[ONLINE_CATS.length];
        for (int i = 0; i < ONLINE_CATS.length; i++) {
            want[i] = catEnd[i] ? 0 : catPage[i] + 1;
        }
        OnlineStore.io().execute(() -> {
            java.util.List<OnlineStore.Remote> got = new java.util.ArrayList<>();
            final boolean[] done = new boolean[ONLINE_CATS.length];
            final boolean[] ok = new boolean[ONLINE_CATS.length];
            boolean bad = false;
            for (int i = 0; i < ONLINE_CATS.length; i++) {
                if (want[i] == 0) continue;          // 这个分类已经到底了
                OnlineStore.Category c = ONLINE_CATS[i];
                try {
                    OnlineStore.Page p = new OnlineStore().fetchList(c, want[i]);
                    got.addAll(p.items);
                    ok[i] = true;
                    // 到顶了,或撞上保险丝(站点改版 hasNext 恒 true 时的兜底)
                    done[i] = !p.hasNext || want[i] >= ONLINE_MAX_PAGE;
                } catch (Exception e) {
                    Log.w(TAG, "拉在线列表失败 " + c.label + " 第" + want[i] + "页: " + e);
                    bad = true;
                }
            }
            final boolean failed = bad;
            post(() -> {
                inflight(-1);
                for (int i = 0; i < ONLINE_CATS.length; i++) {
                    // 失败的分类**不推进页码** —— 推了就等于把那一页悄悄丢了,
                    // 下次「下一页」会从再下一页拉起,中间那 20 个再也回不来
                    if (ok[i]) catPage[i] = want[i];
                    if (done[i]) catEnd[i] = true;
                }
                onlineLoading = false;
                onlineAll.addAll(got);
                lib.mergeOnline(onlineAll);   // 传完整列表,不是这一页
                // 一条都没拉到过 = 车里没网。空列表那句话要说实话,别让人以为
                // 是自己没放 ROM(见 GameListView.emptyText)
                list.setOnlineFailed(failed && onlineAll.isEmpty());
                list.setOnlineEnd(allCatsEnd());
                list.refresh();
            });
        });
    }

    /** 三个分类是不是都拉到底了 —— 是才禁掉「下一页」。 */
    private boolean allCatsEnd() {
        for (boolean e : catEnd) {
            if (!e) return false;
        }
        return true;
    }

    /**
     * 抓一个平台的类型分类并回填列表。
     *
     * FC/街机才有(平台列表页顶部那排 tag-type 链接),本地页不抓。
     * 抓回来列表上方就多一条类型栏,点类型拉该类型的列表(见 loadGenrePage)。
     */
    private void loadGenresFor(GameLibrary.Platform p) {
        if (p == null) return;
        currentGenre = null;             // 换平台了,类型模式作废
        genreAll.clear();
        genrePage = 0;
        genreEnd = false;
        java.util.List<OnlineStore.Genre> cached = genreCache.get(p);
        if (cached != null) {
            list.setGenres(cached);      // 同步给出:类型栏和列表同时出现,不推页
            return;
        }
        fetchGenres(p, true);
    }

    /**
     * 平台 → 类型栏。抓过就存着。
     *
     * 不存的话每次点左栏都重抓一遍,而类型栏总比列表晚回来 —— 列表先铺好,
     * 几百毫秒后类型栏才插进搜索框下面,把整页往下推一格。站上的类型就固定
     * 那七八个(FC 八个、街机七个),一次会话里不会变,存一份切平台时同步给出。
     */
    private final java.util.Map<GameLibrary.Platform, java.util.List<OnlineStore.Genre>> genreCache
            = new java.util.EnumMap<>(GameLibrary.Platform.class);

    /**
     * 抓一个平台的类型栏并存进 {@link #genreCache}。
     *
     * @param apply 抓完是否回填给列表。**预抓时必须是 false** —— 那会儿人还在
     *              别的目录上,填过去会把他正看着的类型栏换成另一台机器的。
     */
    private void fetchGenres(GameLibrary.Platform p, boolean apply) {
        final int tag = ONLINE_CATS[catIndexOf(p)].tag;
        OnlineStore.io().execute(() -> {
            java.util.List<OnlineStore.Genre> genres;
            try {
                genres = new OnlineStore().fetchGenres(tag);
            } catch (Exception e) {
                Log.w(TAG, "拉类型分类失败: " + e);
                return;
            }
            if (genres.isEmpty()) return;
            final java.util.List<OnlineStore.Genre> got = genres;
            post(() -> {
                genreCache.put(p, got);
                if (apply) list.setGenres(got);
            });
        });
    }

    /**
     * 点了类型分类(或「全部」)。
     *
     * g 为 null = 回平台全部:类型模式结束,列表回到 mergeOnline 灌好的平台列表;
     * 否则从该类型的第一页开始拉(见 loadGenrePage),翻页由 loadMoreOnline 分流。
     */
    private void selectGenre(OnlineStore.Genre g) {
        currentGenre = g;
        genreAll.clear();
        genrePage = 0;
        genreEnd = false;
        if (g == null) {
            list.setOnlineEnd(allCatsEnd());
            list.refresh();
            return;
        }
        loadGenrePage();
    }

    /**
     * 拉当前类型的一页列表。
     *
     * 类型列表是纯展示(不 merge 进 lib.all),拉到的 Remote 转成 Game 后
     * 整段回填给列表(见 {@link #toGenreGames}) —— 已下载的用本地条目、
     * 没下的建云端条目,和平台列表的观感一致。
     */
    private void loadGenrePage() {
        final int page = genrePage + 1;
        if (genreEnd || page > ONLINE_MAX_PAGE) return;
        onlineLoading = true;
        inflight(1);
        final OnlineStore.Genre g = currentGenre;
        // 类型页的 e 就是平台下标:FC=0、街机=1、GBA=2(和 ONLINE_CATS 一一对上)
        final OnlineStore.Category cat = ONLINE_CATS[
                (g.e >= 0 && g.e < ONLINE_CATS.length) ? g.e : 0];
        OnlineStore.io().execute(() -> {
            java.util.List<OnlineStore.Remote> got = new java.util.ArrayList<>();
            boolean more = false, ok = false;
            try {
                OnlineStore.Page p = new OnlineStore().fetchGenreList(g, cat, page);
                got.addAll(p.items);
                more = p.hasNext;
                ok = true;
            } catch (Exception e) {
                Log.w(TAG, "拉类型列表失败「" + g.label + "」第" + page + "页: " + e);
            }
            final boolean hasNext = more, fetched = ok;
            post(() -> {
                inflight(-1);
                // 人已经换到别的类型了:这批是上一个类型的货。灌进去就是
                // "点了格斗,列表里混着射击"(快速连点两个类型必现)
                if (g != currentGenre) return;
                // 拉挂了不推进页码,理由同 loadMoreOnline
                if (fetched) {
                    genrePage = page;
                    genreEnd = !hasNext || page >= ONLINE_MAX_PAGE;
                }
                onlineLoading = false;
                genreAll.addAll(got);
                list.setGenreItems(toGenreGames());
                list.setOnlineEnd(genreEnd);
            });
        });
    }

    /** 类型列表 Remote → Game。三步的细节见 {@link GameLibrary#gameFor}。 */
    private java.util.List<GameLibrary.Game> toGenreGames() {
        java.util.List<GameLibrary.Game> out = new java.util.ArrayList<>();
        for (OnlineStore.Remote r : genreAll) {
            GameLibrary.Game g = lib.gameFor(r);
            if (g != null) out.add(g);
        }
        return out;
    }

    // ---- 删本地游戏 ----

    /**
     * 删之前问一句。**删掉的 ROM 拿不回来**(要重下),而车上误触的成本很高。
     *
     * 走 {@link Immersive#show} 而不是 {@code dialog.show()} —— 直接 show 的
     * 对话框会把系统栏勾出来,按钮还点不动(见 Immersive.show 的注释)。
     * 大小是**删之前**算的:删完文件就没了,length() 只会返回 0。
     */
    private void confirmDelete(java.util.List<GameLibrary.Game> games) {
        if (games.isEmpty()) return;
        long total = 0;
        for (GameLibrary.Game g : games) total += g.file.isFile() ? g.file.length() : 0;

        StringBuilder names = new StringBuilder();
        for (int i = 0; i < games.size() && i < 5; i++) {
            names.append("\n· ").append(games.get(i).name);
        }
        if (games.size() > 5) names.append("\n· 等 ").append(games.size()).append(" 个");

        android.app.AlertDialog d = new android.app.AlertDialog.Builder(this)
                .setTitle("删掉这 " + games.size() + " 个游戏?")
                .setMessage("会释放 " + GameLibrary.humanSize(total)
                        + "。删掉的 ROM 要重新下载才能玩。" + names)
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dlg, w) -> doDelete(games))
                .create();
        Immersive.show(d);
    }

    /**
     * 真删。文件 I/O 放后台 —— 一批几十个的话,主线程上删会卡住动画。
     *
     * 删完重扫重建:{@link GameLibrary#scan} 之后云端条目要重新并入(scan 自己
     * 会做,见 onlineCache),「最近玩过」不用另外清 —— 它本来就跳过文件已经
     * 没了的条目。
     */
    private void doDelete(java.util.List<GameLibrary.Game> games) {
        // 删的正好是核心里装着的那个:**先把核心卸了**。不卸的话左栏「继续玩」
        // 还指着它,点进去是接着玩一个文件已经没了的游戏 —— 眼下还能跑
        // (数据都在内存里),但下次任何重载都必然失败,而界面上什么也不说
        if (loadedGame != null) {
            for (GameLibrary.Game g : games) {
                if (!g.key().equals(loadedGame.key())) continue;
                audio.stop();
                Retro.nativeShutdown();
                loadedGame = null;
                break;
            }
        }
        CoreStore.io().execute(() -> {
            long freed = 0;
            for (GameLibrary.Game g : games) freed += lib.delete(g);
            final long total = freed;
            post(() -> {
                Log.i(TAG, "删了 " + games.size() + " 个游戏,共释放 "
                        + GameLibrary.humanSize(total));
                lib.scan();
                // 刚卸掉的话「继续玩」那条也要跟着撤
                list.setResume(loadedGame == null ? null : loadedGame.name, this::resume);
                if (!list.exitSelectMode()) list.refresh();   // 退出多选那句自带 refresh
            });
        });
    }

    // ---- 首页热门 ----

    /**
     * 抓网站首页那两块热门,回填给首页。
     *
     * **和列表页是两份数据。** 列表页把同一个游戏的各版本平铺开,前十条里一半是
     * 重复作品;热门是站方一作一条的精选。以前首页拿「列表前 10」当热门,于是
     * 超级马里奥、雪人兄弟、坦克大战这些精选进不来,跟网站对不上(见
     * {@link OnlineStore#fetchHot})。
     *
     * 两块在同一张首页里,一次请求。拉不到就是空的,首页那两块不画 ——
     * 不拿别的数据凑,凑出来的跟网站对不上,正是这块以前的毛病。
     */
    private void refreshHot() {
        // 也算进"在途":首页那一屏就靠这两块,它没回来之前首页是空的,
        // 该说「正在加载…」而不是「在线库这会儿没给出内容」
        inflight(1);
        OnlineStore.io().execute(() -> {
            OnlineStore.HotSections hot;
            try {
                hot = new OnlineStore().fetchHot(ONLINE_CATS[0], ONLINE_CATS[1], ONLINE_CATS[2]);
            } catch (Exception e) {
                Log.w(TAG, "拉首页热门失败: " + e);
                post(() -> inflight(-1));
                return;
            }
            post(() -> {
                inflight(-1);
                list.setHot(hot);
            });
        });
    }

    /**
     * 下载云端游戏的 ROM,只下载不下发。
     *
     * 进度直接打在列表卡片的角标上(「下载中 42%」)。下完就地转正:
     * 卡片还站在原地(不重排,网页顺序不乱),只是「云端」角标消失、变成
     * 本地条目 —— 用户再点一次这张卡才进游戏,而游戏没从眼前挪走。
     * 下载失败角标变「下载失败」,再点一次重试。
     */
    private void downloadRemoteRom(GameLibrary.Game g) {
        if (downloading != null) {
            // 一次只下一个,防连点也防下错:提醒用户那一个在动。
            // **不用 Toast** —— Android 12+ 的 toast 钉在屏幕底部,而 Dock 是浮在
            // 所有页面之上的悬浮窗,提示正好被它压住(桌面那边早有这笔账)
            list.flash("正在下载 " + downloading.name + ",下完再点别的");
            return;
        }
        // 可能从「核心下载完 → 自动进游戏」这条路过:那时在游戏页,hint 还
        // 亮着核心下载的 100%。ROM 下载的进度打在列表卡片角标上,hint 收掉、
        // 列表露出来,不然一行「100%」一直糊在中间
        hint.setVisibility(View.GONE);
        showList();
        downloading = g;
        list.setDownloading(g.key());
        OnlineStore store = new OnlineStore();
        OnlineStore.io().execute(() -> {
            File romOut = g.file;
            // 先抓详情:ROM 地址、街机 romset 都在 play 页上(列表页只有名字)。
            // 抓不到就按失败走,卡片标角标
            final OnlineStore.Remote r = store.resolve(g.online);
            // 街机按 romset 驱动名落盘:模拟器按文件名认游戏,中文名落盘核心找不到驱动
            // (retro_load_game false / Romset is unknown 都这病根)。街机可能是 dino.zip,也可能是
            // kof97h.zip$kof97.zip($ 拼 clone$parent)—— 取第一个(主驱动)落盘,
            // $ 后面的 parent 由 OnlineStore.download 按 extraUrls 下到同目录(核心要两份都在)。
            String romsetBase = null;
            if (r != null && g.platform == GameLibrary.Platform.ARCADE && !r.romset.isEmpty()) {
                String main = new java.io.File(r.romset.split("\\$")[0]).getName();
                if (!main.isEmpty()) {
                    romsetBase = main.replaceAll("(?i)\\.zip$", "");
                    romOut = new File(g.file.getParentFile(), main);
                }
            }
            File picOut = new File(lib.romDir(), "sshots");
            if (!picOut.isDirectory()) picOut.mkdirs();
            // 封面跟着 romset 名走(街机):scan 后按文件名配对封面才找得到。
            // 掌机还是中文名 —— fceumm 不挑文件名,但 sshots 按中文名好认
            String picName = romsetBase != null
                    ? romsetBase : g.online.name.replace('/', '／').trim();
            File pic = new File(picOut, picName + ".png");
            // lambda 只能碰 final,把下载目标固定下来
            final File romTarget = romOut;
            final String romBase = romsetBase;

            final int[] lastPct = { -1 };
            OnlineStore.Progress p = (done, total) -> {
                int pct = total > 0 ? (int) (done * 100 / total) : -1;
                if (pct == lastPct[0]) return;
                lastPct[0] = pct;
                post(() -> list.setDownloadProgress(g.key(), pct));
            };
            // ROM + 封面直连 CDN,带 Referer
            boolean ok = r != null && store.download(r, romTarget, pic, p);
            // 街机 clone 顺带下来的 parent 也是一张能玩的卡片(samsho.zip),站上查得到中文名
            // 就一并记进 names.txt,免得列表里冒出个英文名。要联网,得在这条 IO 线程上算完
            final java.util.Map<String, String> parentNames =
                    ok ? store.parentNames(r) : java.util.Collections.emptyMap();
            // lambda 只能碰 final:结果先拷一份,下面的完成回调读它
            final boolean downloadOk = ok;
            post(() -> {
                downloading = null;
                if (downloadOk) {
                    if (romBase != null) {
                        // 清掉旧版按中文名落盘的残留(dino 下载前「恐龙快打.zip」),
                        // 不然 scan 会把同一游戏扫出两份
                        if (!romTarget.equals(g.file) && g.file.isFile()) g.file.delete();
                        // 文件已按 romset 名落地(dino.zip):换掉 Game 的路径,
                        // 并把「romset→中文显示名」记进 names.txt,重启 scan 后
                        // 本地卡片还显示中文名
                        g.file = romTarget;
                        lib.addNameMapping(romBase, g.name);
                    }
                    for (java.util.Map.Entry<String, String> e : parentNames.entrySet()) {
                        lib.addNameMapping(e.getKey(), e.getValue());
                    }
                    // 就地转正,不 scan:scan 会按「本地在后」重排,刚下的
                    // 这张卡会从当前页挪走,用户想再点一次就找不着了
                    g.markDownloaded();
                    list.clearDownloading();
                    list.refresh();
                } else {
                    list.setDownloadFailed(g.key());
                }
            });
        });
    }


    /** 回主线程,顺带挡掉页面已经关了的情况 —— 下载跑三分钟,人早可能退出去了。 */
    private void post(Runnable r) {
        handler.post(() -> { if (!isFinishing() && !isDestroyed()) r.run(); });
    }

    /**
     * 这个平台该用哪个核心。
     *
     * mGBA 一个顶 GB/GBC/GBA 三家;NES 归 FCEUmm;街机**不按后缀查表** ——
     * 街机 ROM 全是 .zip,`kof98.zip` 是 NeoGeo、`dino.zip` 是 CPS2、
     * `gridlee.zip` 是 1982 年的老基板,后缀里没有这个信息,只能用手上装了的那个。
     */
    private String coreFor(GameLibrary.Platform p) {
        switch (p) {
            case NES: return "libfceumm_libretro.so";
            case GB:
            case GBA: return "libmgba_libretro.so";
            case ARCADE:
                for (String c : ARCADE_CORES) if (coreReady(c)) return c;
                return ARCADE_CORES[0];      // 一个都没装:去装排第一的那个
        }
        return null;
    }

    // ---- 两个状态:选游戏 / 玩 ----

    /** 回选游戏页。游戏**不卸**,只是被盖住 —— 回来接着玩不用重新加载。 */
    private void showList() {
        current = null;
        inGame = false;
        // 全屏是"专心玩这一局"的状态,回列表就该结束 —— 不然列表铺在全屏底下,
        // 左栏没了、右上角还挂着颗退出全屏,两页的控件混在一起
        if (fullscreen) setFullscreen(false);
        // 核心里还装着的那个:左栏顶上给一条「继续玩」。这是"不卸核心"这件事
        // 唯一说得通的兑现方式 —— 否则它只是白占内存
        list.setResume(loadedGame == null ? null : loadedGame.name, this::resume);
        list.refresh();
        list.setVisibility(View.VISIBLE);
        // 盖住了还在跑纯属白烧电。**用暂停而不是静音** —— 静音是用户那颗键的状态,
        // 借来做页面切换会跟他打架(他按了静音,回游戏被我们解开)
        Retro.nativePause(true);
        Retro.nativeFlushSram(true);   // 顺手把存档落了,人可能就在列表页把车熄了
    }

    private void showGame() {
        inGame = true;
        list.setVisibility(View.GONE);
        Retro.nativePause(false);
        syncPauseBtn();      // 刚解的暂停,那颗键的字得跟上
        syncSpeedBtn();
        pushPadState(null);  // 手机那排键跟着一起醒过来
    }

    /** 回到还装在核心里的那一局,接着打。左栏「继续玩」走这儿。 */
    private void resume() {
        if (loadedGame != null) play(loadedGame);
    }

    /** 开一个游戏。核心没装就先去装,装完自动回到这儿。 */
    private void play(GameLibrary.Game g) {
        // 核心里装的就是它:掀开盖子接着打,**不重载** —— 重载等于把正在打的
        // 那一局清了,而人点「继续玩」/再点同一张卡想的正是接着玩
        if (loadedGame != null && !g.cloud && g.key().equals(loadedGame.key())) {
            current = g;
            setNowPlaying(g);
            surfaceView.setVisibility(View.VISIBLE);
            hint.setVisibility(View.GONE);
            showGame();
            return;
        }
        String coreName = coreFor(g.platform);
        if (coreName == null) return;
        if (!coreReady(coreName)) {
            showGame();               // 让进度显示在游戏页上,列表页盖着看不见
            downloadCore(coreName, () -> play(g));
            return;
        }
        // 云端条目:点一下只下载不下发 —— 进度打在列表角标上,下完卡片就地
        // 变本地、还站在原地,再点一次才进游戏。
        // 文件其实已经在的(旧版本下过、状态没转正,scan 认不出那类 zip):
        // 就地转正即可,也不直接进 —— 跟下载流程一致,免得点一下误开。
        if (g.cloud) {
            if (!g.file.isFile()) {
                downloadRemoteRom(g);
            } else {
                g.markDownloaded();
                list.refresh();
            }
            return;
        }

        // 街机的金手指要**在装游戏之前**把 ini 放好 —— 核心是在 load_game 里读它、
        // 生成那张选项表的,游戏都跑起来了再放,这一局不会有(见 CheatStore)。
        // 几 KB、超时 4 秒,一个 romset 只试一次:官方包里没有的、车里没网的,
        // 都只是这局没金手指,不耽误玩。
        //
        // **这一步必须有画面反馈**,而且要挡住重复点击 —— 第一版两样都没有:点一下之后
        // 界面上什么也不发生(游戏没装、还在列表页),人自然会再点一次,而那一次
        // `cheatIniTried.add` 已经返回 false,于是**绕过等待直接把游戏装了**,
        // ini 晚 0.1 秒才落地,这一局就是没有金手指。真车上实测复现:
        // 同一个 kof97,先装的那次核心只报 45 个选项,ini 到位之后再进是 101 个。
        if (g.platform == GameLibrary.Platform.ARCADE) {
            final String romset = GameLibrary.baseName(lib.romFor(g).getName());
            final File ini = new File(fbneoCheatDir(), romset + ".ini");
            if (!ini.isFile()) {
                if (romset.equals(cheatIniLoading)) return;   // 已经在下了,别再发一遍
                if (cheatIniTried.add(romset)) {
                    cheatIniLoading = romset;
                    showGame();                  // 列表页盖着,得先掀开才看得见下面这句
                    hint.setVisibility(View.VISIBLE);
                    hint.setText("正在准备…");
                    CoreStore.io().execute(() -> {
                        CheatStore.downloadArcadeIni(romset, ini);
                        post(() -> {
                            cheatIniLoading = null;
                            play(g);             // 拿没拿到都接着装,别把人卡在这儿
                        });
                    });
                    return;
                }
                // tried 里已经有 = 这个 romset 之前就没拿到(官方包里没有 / 那次没网),
                // 不再等第二遍,直接装 —— 这一局没金手指而已
            }
        }

        audio.stop();
        Retro.nativeShutdown();       // 换核心前必须先彻底卸掉上一个
        loadedGame = null;            // 上一个已经不在核心里了,别让「继续玩」指着空地

        String info = Retro.nativeInit(corePath(coreName),
                lib.romFor(g).getAbsolutePath(),   // zip 包着的掌机 ROM 要先解出来
                systemDir(), sramPath(g));
        Log.i(TAG, g.file.getName() + " → " + info);

        if (!info.startsWith("OK")) {
            // **把画面收掉**。核心没起来,surface 上留着的是上一个游戏的最后一帧,
            // 错误文字压在那上面根本读不清(实测截图里就是一片花)
            showGame();
            surfaceView.setVisibility(View.GONE);
            hint.setVisibility(View.VISIBLE);
            // 核心点名缺文件(见 bridge.c 的 g_core_err),**几乎全是缺 BIOS**:
            // NeoGeo 那一挂要 neogeo.zip、PGM(三国战纪)要 pgm.zip。
            // 认得出是哪份就补齐再重试,没有就告诉用户放哪儿(见 installBios)
            String bios = info.startsWith("缺文件|") ? biosFor(info) : null;
            if (bios != null) {
                installBios(bios, () -> play(g));
                return;
            }

            String why = "这个游戏起不来\n\n" + g.name + "\n" + info;
            if (g.platform == GameLibrary.Platform.ARCADE) {
                // 排除掉缺 BIOS(上面单独处理了)之后,街机剩下的失败基本就是版本对不上
                why += "\n\n街机 ROM 分版本(romset):同一个游戏,\n"
                     + "FBNeo 认的和 MAME 认的文件不一样。\n"
                     + "多半是这套 ROM 跟当前核心对不上。";
            }
            hint.setText(why);
            return;
        }

        current = g;
        loadedGame = g;
        lib.remember(g);
        setNowPlaying(g);
        cheats = null;          // 换游戏了:上一局那张表的地址在这一局是别人的内存
        players.setPlayers(g.platform.maxPlayers);   // GB 只有 1 个手柄口,P2 那张码不该给得出来
        // 手机那侧的键位跟着机器走,**三套**:
        //   街机 六颗 + 投币;GBA 两颗 + L/R 肩键;NES/GB 就是 A/B(真机手柄本来如此)。
        // GBA 单列一套是因为它比 GB 多两颗肩键 —— 马里奥赛车、火焰纹章那些全要用。
        // 已经连着的手柄会当场换过去(见 PadServer.setLayout)
        if (pad != null) {
            pad.setLayout(g.platform == GameLibrary.Platform.ARCADE ? "arcade"
                    : g.platform == GameLibrary.Platform.GBA ? "gba" : "pad");
        }
        surfaceView.setVisibility(View.VISIBLE);
        hint.setVisibility(View.GONE);
        readGeometry(info);
        applyGeometry();              // 分辨率换了,画面尺寸要重算
        syncStateBtns();              // 换核心了,快照支不支持得重新问一遍
        showGame();

        if (surfaceView.getHolder().getSurface() != null) {
            Retro.nativeSetSurface(surfaceView.getHolder().getSurface());
            Retro.nativeStart();
            audio.start();
        }
    }

    /**
     * 缺的这份 BIOS 补齐了就接着开游戏。
     *
     * BIOS 要用户自己放(见 {@link BiosStore}),放在 roms/ 或 system/ 任一处就行,这里把另一处补上。
     * **两处本来就都有、核心还报缺**,说明这份 BIOS 版本不对 —— 这时不能再重试,否则就是死循环。
     */
    private void installBios(String biosFile, Runnable then) {
        File sys = new File(systemDir());
        boolean wasThere = BiosStore.installed(biosFile, lib.romDir(), sys);
        CoreStore.io().execute(() -> {
            boolean ok = !wasThere && BiosStore.install(biosFile, lib.romDir(), sys);
            post(() -> {
                if (ok) {
                    then.run();
                    return;
                }
                hint.setVisibility(View.VISIBLE);
                hint.setText(wasThere
                        ? "放进来的 " + biosFile + " 核心不认\n\n多半是版本不对,换一份 FBNeo 用的试试"
                        : "这个游戏还差一个 BIOS:" + biosFile + "\n\n"
                            + "BIOS 是基板固件,不随 app 提供。自己找一份放到:\n"
                            + lib.romDir());
            });
        });
    }

    /**
     * 从核心报的缺失清单里认出缺的是哪个 BIOS。认不出返回 null。
     *
     * 判据是**缺失文件的名字前缀**,不是游戏名 —— 核心报的是
     * {@code pgm_t01s.rom ... is required} 这种,前缀就写着它属于哪套 BIOS。
     * FBNeo 有时也直接点名({@code Verify the following romsets : kov2 pgm}),
     * 那就更省事。这张表跟 {@link GameLibrary.Platform} 里挡列表用的
     * {@code BIOS_NAMES} 是同一批东西,加新基板时两处一起加。
     */
    private static String biosFor(String info) {
        String s = info.toLowerCase(Locale.US);
        if (s.contains("pgm_") || s.contains(" pgm")) return "pgm.zip";
        if (s.contains("sfix") || s.contains("sp-s") || s.contains("neogeo")
                || s.contains("000-lo")) return "neogeo.zip";
        if (s.contains("decocass")) return "decocass.zip";
        if (s.contains("skns")) return "skns.zip";
        if (s.contains("isgsm")) return "isgsm.zip";
        return null;
    }

    /**
     * 诊断用:street-test 一批街机 romset,不进 UI、不等人点。
     *
     * 只调 play() 里加载那两行(nativeShutdown + nativeInit),**不碰 surface/audio/list**——
     * 这批全靠"装不装得起来"判定,画面对不对不需要真的跑起来看。retro_load_game 返回 true
     * 但核心自己判定 romset 缺文件时(FBNeo 常见),info 仍是 "OK|...",区分不出来;
     * 那种情况靠 core_log 转发到 logcat 的 "is missing/is required" 这行,日志里跟这条
     * BATCHTEST 挨在一起,脚本按顺序解析即可,不用改这边。
     */
    private void runArcadeBatchTest() {
        List<GameLibrary.Game> arcade = lib.of(GameLibrary.Platform.ARCADE);
        Log.i(TAG, "BATCHTEST_START " + arcade.size());
        for (GameLibrary.Game g : arcade) {
            String coreName = coreFor(g.platform);
            if (coreName == null || !coreReady(coreName)) {
                Log.i(TAG, "BATCHTEST\t" + g.file.getName() + "\tNO_CORE\t-");
                continue;
            }
            Retro.nativeShutdown();
            // 空存档路径 = 这一趟不碰任何人的存档。批量装载几十个 romset,
            // 传了路径就会在 system/ 下拉一堆空 .srm
            String info = Retro.nativeInit(corePath(coreName),
                    lib.romFor(g).getAbsolutePath(), systemDir(), "");
            Log.i(TAG, "BATCHTEST\t" + g.file.getName() + "\t"
                    + (info.startsWith("OK") ? "OK" : "HARDFAIL") + "\t" + info);
        }
        Retro.nativeShutdown();
        Log.i(TAG, "BATCHTEST_DONE");
    }

    /**
     * 返回键:全屏 → 游戏 → 列表 → 首页 → 桌面。
     *
     * 不这么分层的话,玩两个游戏要进出桌面两趟 —— 而车上每多一次跳转都是一次分神。
     * 「回首页」那一级是后补的:从首页热门点「拳皇系列」钻进筛选列表后按返回
     * 原先是**直接退出游戏中心**,想回去看看别的还得从抽屉里重进一遍。
     */
    @Override
    public void onBackPressed() {
        // 全屏先退出来 —— 双击画面之外的另一条退路(全屏下屏上没有任何控件)
        if (fullscreen) { setFullscreen(false); return; }
        if (inGame) { showList(); return; }
        // 多选态下先退出选择 —— 勾了几个正犹豫,按返回该是"算了",不是退回桌面
        if (list.exitSelectMode()) return;
        // 翻到别的目录/类型/搜索里了:先回首页
        if (list.goHome()) return;
        super.onBackPressed();
    }

    /**
     * 机身上那排功能键。只放玩的时候真会用的,跑分/统计那些验证期的东西没进来。
     *
     * **竖排**:画面按整数倍放大后占了中间 960 宽,横排会从左边一直伸到画面底下,
     * 最后一颗「换游戏」正好被压住(实测)。竖着贴左边机身就躲开了。
     */
    private LinearLayout buildSettings() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);

        addKey(row, "🔇 静音", b -> b.setText(audio.toggleMute() ? "🔈 取消静音" : "🔇 静音"));
        // 暂停位不只这颗键会改 —— 回列表、切后台都会动它(见 showList/onPause),
        // 所以键上的字不能自己记状态,得能从外面同步(见 syncPauseBtn)
        pauseBtn = addKey(row, "⏸ 暂停", b -> {
            boolean now = !Retro.nativeIsPaused();
            Retro.nativePause(now);
            b.setText(now ? "▶ 继续" : "⏸ 暂停");
        });
        addKey(row, "↺ 重置", b -> Retro.nativeReset());
        // 快进**点按切档**(×1 → ×2 → ×3 → ×1),不做"按住不放" —— 车上按住一颗键
        // 不方便,手指还挡着画面;调值一律三格点按是桌面那边定下来的手感
        speedBtn = addKey(row, "⏩ 快进", b -> {
            int now = Retro.nativeGetSpeed();
            int next = now >= 30 ? 10 : (now >= 20 ? 30 : 20);
            Retro.nativeSetSpeed(next);
            syncSpeedBtn();
        });
        // 存档/读档摆成常驻键而不是收进面板:**车上随时要下车**,这是最贴场景的
        // 一个动作,多一层菜单就等于让人在停车场里翻菜单。
        // 不问、不确认 —— 存档只有一个位子,写坏了的余地由 .bak 兜(见 saveState)
        // 金手指摆在存档前面:它是"这一局怎么玩"的设置,和存/读档那对不是一类
        cheatBtn = addKey(row, "🎲 金手指", b -> openCheats());
        saveBtn = addKey(row, "💾 存档", b -> saveState());
        loadBtn = addKey(row, "📂 读档", b -> loadState());
        // 画面比例:点一下轮下一档(原始 → 还原 → 拉满)。**键面直接写着当前是哪档**,
        // 不另开一处显示 —— 三档的差别在屏幕上一眼就看得见,不需要解释文字
        fitBtn = addKey(row, "", b -> cycleFit());
        syncFitBtn();
        // 全屏:两条侧栏收起,画面按整屏重算。**没有"退出全屏"的对偶键** ——
        // 全屏之后这排键整个不在屏上了,退出走双击画面或返回键
        addKey(row, "⛶ 全屏", b -> setFullscreen(true));
        // 「换游戏」变成回列表 —— 一包 ROM 一百多个,循环切换要点上百次
        addKey(row, "🎮 选游戏", b -> showList());
        return row;
    }

    /**
     * 左栏最上面那两行:正在玩的是哪个游戏、哪台机器。
     *
     * 二维码从这条栏收走之后上半截空了一大块 —— 摆这两行不是为了填空:游戏画面
     * 本身常常不写自己叫什么(开局动画、GAME OVER 那些画面上什么都没有),而
     * 「换游戏」是这里的高频动作,人得知道现在这局是哪个。
     */
    private LinearLayout buildNowPlaying() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(2), 0, 0, dp(12));

        nowName = new TextView(this);
        nowName.setTextSize(15);
        nowName.setMaxLines(2);
        nowName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(nowName);

        nowPlat = new TextView(this);
        nowPlat.setTextSize(11);
        nowPlat.setPadding(0, dp(2), 0, 0);
        col.addView(nowPlat);

        skinNowPlaying();
        setNowPlaying(null);
        return col;
    }

    /** 换了游戏(或还没开)就把这两行刷一遍。 */
    private void setNowPlaying(GameLibrary.Game g) {
        if (nowName == null) return;
        nowName.setText(g == null ? "还没开始" : g.name);
        nowPlat.setText(g == null ? "从「选游戏」挑一个" : g.platform.label);
    }

    private void skinNowPlaying() {
        if (nowName == null) return;
        nowName.setTextColor(com.liubai.blank.mode.ModeManager.get().ink());
        nowPlat.setTextColor(com.liubai.blank.mode.ModeManager.get().muted());
    }

    /**
     * 手机按了功能键。
     *
     * <b>手机上那排就是车机左栏那排</b>(除了「选游戏」——那要整个列表页,手机上没法给)。
     * 玩的时候人的手在手机上,存个档还要伸手去够中控屏是别扭的,尤其后排。
     *
     * 每条都走车机这边现成的那个方法,不另写一套逻辑 —— 两边点同一颗键必须是同一件事。
     */
    private void onPadCommand(char cmd, String arg) {
        switch (cmd) {
            case 'p':                                     // 暂停 / 继续
                Retro.nativePause(!Retro.nativeIsPaused());
                syncPauseBtn();
                break;
            case 'f':                                     // 快进切档(×1 → ×2 → ×3)
                int now = Retro.nativeGetSpeed();
                Retro.nativeSetSpeed(now >= 30 ? 10 : (now >= 20 ? 30 : 20));
                syncSpeedBtn();
                break;
            case 'm':                                     // 静音
                audio.toggleMute();
                applyMode();                              // 那颗键的字要跟着换
                break;
            case 'r': Retro.nativeReset(); break;         // 重置
            case 's': saveState(); return;                // 存档:结果由回调广播
            case 'l': loadState(); return;                // 读档:同上
            case 'F': setFullscreen(!fullscreen); break;  // 全屏切换
            case 'a': cycleFit(); return;                 // 画面比例:自己会推状态
            case 'c': toggleCheatFromPad(arg); return;    // 金手指:切某一条
            case 'g': cheatPanelOpen("1".equals(arg.trim())); break;
            case 'q': break;                              // 只是问一下现在什么状态
            default: return;
        }
        pushPadState(null);
    }

    /**
     * 把当前状态推给手机,让它那排键跟车机显示一致。
     *
     * 一条 JSON:暂停位、倍速、静音位、全屏位,外加金手指表(名字 + 每条现在是第几档)。
     * 金手指表可能上百条(拳皇 101 条),但只在**进游戏/开关金手指**时发,不是每帧。
     *
     * @param msg 一次性提示(「已存档」「没有存档」),手机上闪一下就没
     */
    /**
     * 金手指面板开着的时候**把游戏按住**。
     *
     * 一百来条要翻(拳皇 101 条),而人一手拿着手机、眼睛在列表上 —— 游戏还在跑的话
     * 角色就站在那儿挨打。关掉面板接着玩。
     *
     * <b>本来就暂停着的话,关面板时别擅自恢复</b>:那是用户自己按的暂停,不是我们借来的。
     * 所以记一笔这次暂停是谁按的。
     */
    private boolean cheatPaused;

    private void cheatPanelOpen(boolean open) {
        if (open) {
            cheatPaused = !Retro.nativeIsPaused();
            if (cheatPaused) Retro.nativePause(true);
        } else if (cheatPaused) {
            cheatPaused = false;
            Retro.nativePause(false);
        }
        syncPauseBtn();
        pushPadState(null);
    }

    private void pushPadState(String msg) {
        if (pad == null) return;
        StringBuilder b = new StringBuilder(256);
        b.append("S{\"p\":").append(Retro.nativeIsPaused() ? 1 : 0)
         .append(",\"s\":").append(Retro.nativeGetSpeed())
         .append(",\"m\":").append(audio.muted() ? 1 : 0)
         .append(",\"f\":").append(fullscreen ? 1 : 0)
         .append(",\"a\":").append(fit)
         .append(",\"g\":").append(inGame ? 1 : 0)
         // 这台机器最多几个人 —— 手机那侧据此决定 P 号能轮到几(GB 就 1 个口,
         // 轮到 P2 是白轮:核心根本不会来问第二个口)
         .append(",\"n\":").append(current == null ? 1 : current.platform.maxPlayers);
        if (msg != null) b.append(",\"t\":\"").append(jsonEscape(msg)).append("\"");
        if (cheats != null && !cheats.isEmpty()) {
            b.append(",\"c\":[");
            for (int i = 0; i < cheats.size(); i++) {
                CheatStore.Cheat c = cheats.get(i);
                if (i > 0) b.append(',');
                b.append("{\"n\":\"").append(jsonEscape(c.name))
                 .append("\",\"v\":\"").append(jsonEscape(c.label()))
                 .append("\",\"o\":").append(c.on() ? 1 : 0).append('}');
            }
            b.append(']');
        }
        b.append('}');
        pad.broadcast(b.toString());
    }

    /** 金手指的名字是游戏给的,里面什么都可能有 —— 引号和反斜杠会把这条 JSON 撕开。 */
    private static String jsonEscape(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(' ');
            else b.append(c);
        }
        return b.toString();
    }

    /**
     * 手机点了金手指表里的第 n 条:往下轮一档(同车机那张面板)。
     *
     * 表还没取过的话先取 —— 手机那侧第一次点开就是这条路。
     */
    private void toggleCheatFromPad(String arg) {
        if (cheats == null || cheats.isEmpty()) {
            loadCheats(() -> pushPadState(cheats == null || cheats.isEmpty()
                    ? "这个游戏没有金手指" : null));
            return;
        }
        try {
            int i = Integer.parseInt(arg.trim());
            if (i < 0 || i >= cheats.size()) return;
            CheatStore.Cheat c = cheats.get(i);
            c.sel = (c.sel + 1) % c.options.length;
            applyCheats();
        } catch (NumberFormatException ignored) { }
        pushPadState(null);
    }

    /**
     * 进/出全屏。
     *
     * 全屏 = 两条侧栏收起 + 按整屏重算(见 {@link #applyGeometry})。**Dock 照旧让位** ——
     * 它是浮在所有页面之上的悬浮窗、常驻是拍过板的,画到它底下只会被盖住。
     *
     * 全屏下**屏幕上一个控件都没有**,这是有意的:全屏就是"这一局专心打"。退出有两条路
     * —— 双击画面、或按返回键;进去时会报一句提示说清楚(见 fullTip)。
     */
    private void setFullscreen(boolean on) {
        if (fullscreen == on) return;
        fullscreen = on;
        leftBar.setVisibility(on ? View.GONE : View.VISIBLE);
        rightBar.setVisibility(on ? View.GONE : View.VISIBLE);
        // 屏幕尺寸没变,布局监听不会响 —— 这一发必须自己打
        applyGeometry();
        if (on) showFullTip(); else fullTip.setVisibility(View.GONE);
    }

    /** 「双击画面退出全屏」那条提示。压在游戏画面上,所以给足对比(实底 + 描边)。 */
    private TextView buildFullTip() {
        TextView b = new TextView(this);
        b.setText("双击画面退出全屏");
        b.setTextSize(14);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(16), dp(10), dp(16), dp(10));
        // **不吃触摸**:它盖在画面上,而画面此刻正是那个双击开关
        b.setClickable(false);
        b.setFocusable(false);
        skinFullTip(b);
        return b;
    }

    /** 亮两秒然后自己走。每次进全屏都说一遍 —— 它本来就只有两秒,不算打扰。 */
    private void showFullTip() {
        skinFullTip(fullTip);          // 中途换过模式的话,这会儿重新取一遍色
        fullTip.setVisibility(View.VISIBLE);
        fullTip.removeCallbacks(hideFullTip);
        fullTip.postDelayed(hideFullTip, 2000);
    }

    private final Runnable hideFullTip = new Runnable() {
        @Override public void run() { fullTip.setVisibility(View.GONE); }
    };

    /** 它的颜色也跟着模式走(见 {@link #applyMode})。 */
    private void skinFullTip(TextView b) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(com.liubai.blank.mode.ModeManager.get().tile());
        bg.setCornerRadius(dp(20));
        bg.setStroke(dp(1), com.liubai.blank.mode.ModeManager.get().line());
        b.setBackground(bg);
        b.setTextColor(com.liubai.blank.mode.ModeManager.get().ink());
    }

    /** 金手指的码缓存在哪:{@code system/cheats/<站上 id>.txt}。 */
    private File cheatDir() {
        File d = new File(systemDir(), "cheats");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    /**
     * 打开金手指面板。第一次点要联网取码,取完这一局就存着了。
     *
     * 三种"没有"分开说清楚,别让人对着一颗点了没反应的键猜:
     * 街机那套码我们拿不到(见 {@link CheatStore} 类注释)、这个游戏站上认不出是哪个
     * (本地手推的 ROM 没有站上的 id)、站上就是没给这个游戏配码。
     */
    private void openCheats() {
        loadCheats(() -> {
            if (cheats == null || cheats.isEmpty()) return;   // 说不了话的那几种已经在 loadCheats 里报过
            showCheats();
        });
    }

    /**
     * 把这一局的金手指表取出来放进 {@link #cheats},取到了才调 {@code then}。
     *
     * **车机的面板和手机那排共用这一条** —— 两边点开的必须是同一张表,
     * 不然一边开了另一边显示没开。取不到的几种情况在这儿一次说清楚(键上闪一句),
     * 手机那侧靠回传的提示文字看到同样的话。
     */
    private void loadCheats(final Runnable then) {
        if (current == null) return;
        if (cheats != null) { then.run(); return; }

        // 街机:码在核心里,装游戏那会儿就报上来了(见 CheatStore 类注释),不用联网也不用等
        if (current.platform == GameLibrary.Platform.ARCADE) {
            cheats = CheatStore.fromVariables(Retro.nativeVariables());
            if (cheats.isEmpty()) { flashKey(cheatBtn, "🎲 金手指", "这个游戏没有");
                                    pushPadState("这个游戏没有金手指"); }
            else then.run();
            return;
        }
        // GBA/GB **站上一条都没有**(2026-08-23 抽样 14 个 GBA 游戏,cheat 接口全返回空)。
        // 不短路的话每点一次都要白等一次网络才告诉他"没有"。
        // 哪天站上补了这批数据,把这段删掉就行 —— 下面那条路本来就通
        if (current.platform == GameLibrary.Platform.GBA
                || current.platform == GameLibrary.Platform.GB) {
            flashKey(cheatBtn, "🎲 金手指", "GBA 还没有");
            pushPadState("GBA 还没有金手指");
            return;
        }
        if (!Retro.nativeCanCheat()) {
            flashKey(cheatBtn, "🎲 金手指", "这台机器不行");
            pushPadState("这台机器不支持金手指");
            return;
        }
        final String id = current.online == null ? null : current.online.id;
        if (id == null) {
            // 本地手推进来的 ROM,没跟站上的条目对上号 —— 码是按站上的 id 取的
            flashKey(cheatBtn, "🎲 金手指", "这个游戏没有");
            pushPadState("这个游戏没有金手指");
            return;
        }
        cheatBtn.setText("🎲 正在取…");
        CoreStore.io().execute(() -> {
            final java.util.List<CheatStore.Cheat> got = CheatStore.load(cheatDir(), id);
            post(() -> {
                cheats = got;
                cheatBtn.setText("🎲 金手指");
                if (got.isEmpty()) { flashKey(cheatBtn, "🎲 金手指", "这个游戏没有");
                                     pushPadState("这个游戏没有金手指"); }
                else then.run();
            });
        });
    }

    /** 面板本身。**每点一下当场重下发整张表** —— 开着面板就能看见画面变化。 */
    private void showCheats() {
        if (cheats == null || cheats.isEmpty() || current == null) return;
        cheatPanelOpen(true);
        CheatPopup.show(this, current.name, cheats,
                () -> { applyCheats(); pushPadState(null); },
                () -> cheatPanelOpen(false));
    }

    /**
     * 把当前这张表下发下去。**两种形态一起发**:直写型给 native 的每帧写回,
     * 选项型交给核心自己的 cheat 引擎(见 {@link CheatStore})。
     *
     * 全量发,不做增量 —— 一张表最多几十条,而增量要另记一套"上次发了什么"。
     */
    private void applyCheats() {
        if (cheats == null) return;
        Retro.nativeSetCheats(CheatStore.triples(cheats));
        CheatStore.pushOptions(cheats);
    }

    /** FBNeo 读 cheat 的地方:{@code <system>/fbneo/cheats/}(路径是核心写死的)。 */
    private File fbneoCheatDir() {
        File d = new File(new File(systemDir(), "fbneo"), "cheats");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    /** 即时存档落在哪:{@code system/<ROM 基名>.state}。同 SRAM,基名取 g.file。 */
    private String statePath(GameLibrary.Game g) {
        return new File(systemDir(),
                GameLibrary.baseName(g.file.getName()) + ".state").getAbsolutePath();
    }

    /**
     * 存一个快照。
     *
     * **旧的先挪成 .bak 再写新的** —— 只有一个存档位,手一滑就把打了半小时的
     * 局面盖掉了;留一份上一次的,至少还有退路。
     */
    private void saveState() {
        if (current == null) return;
        final String path = statePath(current);
        CoreStore.io().execute(() -> {
            File f = new File(path);
            if (f.isFile()) {
                File bak = new File(path + ".bak");
                bak.delete();
                f.renameTo(bak);
            }
            final boolean ok = Retro.nativeSaveState(path);
            post(() -> {
                flashKey(saveBtn, "💾 存档", ok ? "✓ 已存档" : "✗ 存不了");
                pushPadState(ok ? "已存档" : "存不了");
            });
        });
    }

    /** 读回上次那个快照。没有存档、或者存档跟当前核心对不上都会失败,按键上说一声。 */
    private void loadState() {
        if (current == null) return;
        final String path = statePath(current);
        CoreStore.io().execute(() -> {
            final boolean ok = Retro.nativeLoadState(path);
            post(() -> {
                flashKey(loadBtn, "📂 读档", ok ? "✓ 读回来了" : "✗ 没有存档");
                pushPadState(ok ? "读回来了" : "没有存档");
            });
        });
    }

    /**
     * 按键上就地报一句,一秒半后变回去。
     *
     * 不用 toast(会被 Dock 压住),也不用中间那块 hint —— 存档是个小动作,
     * 反馈就该出现在按下去的那颗键上,眼睛不用挪。
     */
    private void flashKey(final TextView btn, final String normal, String msg) {
        if (btn == null) return;
        btn.setText(msg);
        btn.removeCallbacks(keyRestore.get(btn));
        Runnable restore = () -> btn.setText(normal);
        keyRestore.put(btn, restore);
        btn.postDelayed(restore, 1500);
    }

    private final java.util.Map<TextView, Runnable> keyRestore = new java.util.HashMap<>();

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private interface KeyAction { void run(TextView b); }

    /**
     * 暂停键。文字要跟着实际暂停位走 —— 回列表(showList 暂停)、切后台
     * (onPause 暂停)之后再回来,键上还写着「▶ 继续」而游戏其实在跑,
     * 点一下反倒把它停了(实测)。
     */
    private void syncPauseBtn() {
        if (pauseBtn != null) pauseBtn.setText(Retro.nativeIsPaused() ? "▶ 继续" : "⏸ 暂停");
    }

    /** 快进键上写着当前几倍速。换游戏会回到原速(native 侧 unload 时重置),键上也得跟着。 */
    private void syncSpeedBtn() {
        if (speedBtn == null) return;
        int s = Retro.nativeGetSpeed();
        speedBtn.setText(s <= 10 ? "⏩ 快进" : "⏩ ×" + (s / 10));
    }

    /**
     * 画面比例往下轮一档,当场重摆(见 {@link #applyGeometry})。
     *
     * **档位是全局的**,不跟着游戏走:人对"锐一点还是满一点"的偏好是自己的口味,
     * 不是某个游戏的属性 —— 每换一个游戏重挑一遍才是折磨。
     */
    private void cycleFit() {
        fit = (fit + 1) % FIT_NAME.length;
        getSharedPreferences("gamecenter", MODE_PRIVATE).edit().putInt(KEY_FIT, fit).apply();
        syncFitBtn();
        applyGeometry();
        pushPadState("画面 · " + FIT_NAME[fit]);   // 手机上按的时候得知道轮到哪档了
    }

    private void syncFitBtn() {
        if (fitBtn != null) fitBtn.setText("🖼 画面 · " + FIT_NAME[fit]);
    }

    /**
     * 这一局能不能即时存档 —— 不能就把那两颗键灰掉。
     *
     * 点了没反应是最差的一种:用户会以为存上了,下次读档才发现什么都没有。
     */
    private void syncStateBtns() {
        boolean can = Retro.nativeCanState();
        for (TextView b : new TextView[]{ saveBtn, loadBtn }) {
            if (b == null) continue;
            b.setEnabled(can);
            b.setAlpha(can ? 1f : 0.4f);
        }
    }

    private TextView addKey(LinearLayout row, String label, final KeyAction action) {
        final TextView b = new TextView(this);
        b.setText(label);
        b.setTextSize(15);
        b.setTextColor(com.liubai.blank.mode.ModeManager.get().ink());
        b.setPadding(24, 12, 24, 12);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(com.liubai.blank.mode.ModeManager.get().tile());
        bg.setCornerRadius(22f);
        bg.setStroke(1, com.liubai.blank.mode.ModeManager.get().line());
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 10;
        b.setLayoutParams(lp);
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(v -> action.run(b));
        row.addView(b);
        return b;
    }

    /**
     * 画面摆多大。三档,由那颗切档键决定(见 {@link #cycleFit})。
     *
     * <h3>为什么要有档</h3>
     * 车机屏是 16:9,而老游戏全是 4:3 上下的比例 —— 卡住画面的永远是**高度**,
     * 横向那一千多像素本来就用不上。魂斗罗全屏也只占屏宽的 55%,而 yikm 网页版
     * 是直接横向拉宽 45% 铺满的(实测它 canvas 缓冲 800×600、显示 1600×828)。
     * 拉不拉这件事没有唯一答案:有人要锐、有人要满,所以做成档而不是替谁做主。
     *
     * <ul>
     *   <li>{@link #FIT_PIXEL} 整数倍方形像素 —— 最锐,黑边最宽,出厂档</li>
     *   <li>{@link #FIT_ASPECT} 按核心报的宽高比等比 —— 不变形,竖屏街机也只有它显得对</li>
     *   <li>{@link #FIT_FILL} 填满 —— 没黑边,变形</li>
     * </ul>
     *
     * <h3>全屏是另一回事</h3>
     * 全屏只管**收起两条侧栏**、把那点宽度让给画面,形状仍归档位管。唯一的例外
     * 在原始档:全屏本来就是"尽量大",在那儿还守整数倍等于全屏什么也没做
     * (卡在高度上,收侧栏不长高),所以原始档一进全屏就放开成等比铺满。
     */
    private void applyGeometry() {
        if (surfaceView == null || center == null) return;

        // center 铺的是整屏(左栏是浮在它上面的),所以这里拿到的就是屏幕尺寸
        int fullW = center.getWidth(), fullH = center.getHeight();
        if (fullW <= 0 || fullH <= 0) return;

        lastW = fullW; lastH = fullH;
        // Dock 是悬浮窗、浮在本页之上,画到它底下等于白画;顶栏玩游戏时是收起来的
        int usableH = fullH - statusH - dockH;
        // **算倍数时左右各扣掉一条栏,摆位却按整屏居中。**
        //
        // 扣两条(不是只扣左边那条实际占着的)是为了让画面正居中:只扣左边的话
        // 画面能长到贴着左栏,再居中就必然往右压出去。两边一起扣之后,画面最宽时
        // 左边缘正好停在左栏外侧 —— 居中和不压左栏这两件事同时成立。
        //
        // 全屏就没有这份顾虑了,左栏收起来,整屏都是画面的。掌机那几台能因此多涨
        // 一档(GBA 实测 ×5 → ×6);街机 640×480 卡在高度上,全屏只是把两侧收干净
        int availW = fullscreen ? fullW : fullW - 2 * sideW;

        int vw, vh;
        switch (fit) {
            case FIT_FILL:
                // 填满,不管比例。**这是唯一会变形的一档**,而变形是用户自己点出来的
                vw = availW;
                vh = usableH;
                break;

            case FIT_ASPECT: {
                // 按核心报的宽高比等比放到最大。核心报 0 就退回方形像素
                // (libretro 的约定,见 bridge.c 那段)—— 那时它和原始档只差整数倍
                float ar = gameAspect > 0 ? gameAspect : gameW / (float) gameH;
                vh = usableH;
                vw = Math.round(vh * ar);
                if (vw > availW) {          // 竖屏街机之外基本走不到,但 3:4 那些会
                    vw = availW;
                    vh = Math.round(vw / ar);
                }
                break;
            }

            default:
                if (fullscreen) {
                    // **原始档一进全屏就放开整数倍**(理由见方法头)。可用高 996 时:
                    // 街机 320×224 从 ×4 的 896 涨到 996(面积 +24%),FC 960 → 996
                    float k = Math.min(availW / (float) gameW, usableH / (float) gameH);
                    vw = Math.round(gameW * k);
                    vh = Math.round(gameH * k);
                } else {
                    // 平时:**整数倍放大**,一个游戏像素正好占 N×N 个屏幕像素,边缘是硬的
                    int scale = Math.max(1, Math.min(availW / gameW, usableH / gameH));
                    vw = gameW * scale;
                    vh = gameH * scale;
                }
        }

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(vw, vh,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = statusH + (usableH - vh) / 2;
        surfaceView.setLayoutParams(lp);
        Log.i(TAG, "画面 " + gameW + "x" + gameH + " ar=" + gameAspect
                + " [" + FIT_NAME[fit] + (fullscreen ? " 全屏" : "") + "] → " + vw + "x" + vh);
    }

    /**
     * nativeInit 的返回里第 4 段是分辨率(形如 "240x160 (max 240x160)"),
     * 末尾还有一段 "ar 1.3333" 是核心报的宽高比。
     *
     * 宽高比**按前缀找不按下标**:它是后加的,写死下标的话以后中间再插一段就静默读错。
     * 核心没报(0)也是正常情况,那时按方形像素画。
     */
    private void readGeometry(String info) {
        gameAspect = 0f;
        try {
            String[] parts = info.split("\\|");
            if (parts.length > 3) {
                String[] wh = parts[3].trim().split(" ")[0].split("x");
                gameW = Integer.parseInt(wh[0]);
                gameH = Integer.parseInt(wh[1]);
            }
            for (String p : parts) {
                if (!p.startsWith("ar ")) continue;
                gameAspect = Float.parseFloat(p.substring(3).trim());
                break;
            }
        } catch (Exception e) {
            Log.w(TAG, "分辨率读不出来,沿用 " + gameW + "x" + gameH, e);
        }
    }

    /**
     * 退出要把核心**整个卸掉**,不能只 nativeStop。
     *
     * 验证工程里没卸,所以那个 app 退到后台还常驻 47 MB native heap —— 它是独立进程,
     * 无所谓。合进留白之后同一个进程:玩一局就给桌面永久添 50 MB,而车机内存本来就紧。
     * nativeShutdown 里的顺序是停线程 → unload_game → deinit → dlclose,
     * 顺序不能乱(线程还在跑时 dlclose,下一帧就跳进已卸载的代码段)。
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(padTick);
        handler.removeCallbacks(sramTick);
        if (players != null) players.close();   // 扫码弹层还开着的话,别留成 WindowLeaked
        if (pad != null) pad.stop();
        audio.stop();
        Retro.nativeStop();
        Retro.nativeShutdown();
    }
}
