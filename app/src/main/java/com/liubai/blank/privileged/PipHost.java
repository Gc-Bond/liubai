package com.liubai.blank.privileged;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;

/**
 * 画中画:把某个 app 启动到虚拟屏,画面贴回桌面的一块区域。
 *
 * 这是迪粉「画中画」的机制(其提权服务 miksp.jar 里可见 setLaunchDisplayId +
 * DisplayManager + SurfaceTexture + ActivityOptions 的组合),不是 Android 官方 PiP
 * ——官方 PiP 要目标 app 自己支持,而这个对任意 app 都能用。
 *
 * 意义:显示的是 app 的真实界面,歌词/封面/进度全是官方画的,
 * 不需要拿数据、不需要走播放器的开放 API(实测那条路被白名单挡死)。
 *
 * 三步:
 *   1. createVirtualDisplay 建虚拟屏,画面输出到我们给的 Surface
 *   2. ActivityOptions.setLaunchDisplayId 把 app 起到这块虚拟屏
 *   3. Surface 来自桌面上 TextureView 的 SurfaceTexture,于是画面就贴回来了
 *
 * 权限:VIRTUAL_DISPLAY_FLAG_PUBLIC / OWN_CONTENT_ONLY 与跨屏启动 activity
 * 需要 INTERACT_ACROSS_DISPLAYS 等 signature 级权限,**必须提权后才可用**。
 */
public final class PipHost {

    private static final String TAG = "Blank/Pip";

    /**
     * 虚拟屏要能承载普通 activity + 只显示自己的内容。
     *
     * DESTROY_CONTENT_ON_REMOVAL 定义了「关掉卡片」的语义:虚拟屏默认的 removeMode 是
     * MOVE_CONTENT_TO_PRIMARY——屏没了,上面的 activity 会被**迁回主屏**继续留着,
     * 也就是"关掉画中画=变成全屏"。我们要的是另一种:**关掉卡片就是关掉这个 app**,
     * 桌面回到空白(用户拍板),所以显式改成销毁。
     *
     * 「画中画 → 全屏」不靠这个 removeMode 的默认行为,走 {@link #moveToPrimary}
     * 显式搬:那条路能确认搬到没搬到、能带到前台、搬不动时还能退回冷启动,
     * 而 removeMode 只是个撒手不管的副作用。
     */
    /** DisplayManager.VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL —— @hide,只能写字面量。 */
    private static final int FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 << 8;

    private static final int FLAGS =
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                    | FLAG_DESTROY_CONTENT_ON_REMOVAL;

    private final Context ctx;
    private VirtualDisplay display;
    private Surface surface;
    private int vdWidth, vdHeight;

    /** 正在往主屏搬(见 moveToPrimary)。盯梢线程读,主线程写,所以要 volatile。 */
    private volatile boolean detaching;

    public PipHost(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public boolean isRunning() {
        return display != null;
    }

    public int displayId() {
        return display != null ? display.getDisplay().getDisplayId() : -1;
    }

    /**
     * 建虚拟屏并把 app 起上去。
     *
     * @param texture 桌面上 TextureView 的 SurfaceTexture,虚拟屏画面往这里输出
     * @return 是否成功
     */
    public boolean start(SurfaceTexture texture, int width, int height, int dpi, String pkg) {
        stop();
        try {
            texture.setDefaultBufferSize(width, height);
            surface = new Surface(texture);
            vdWidth = width;
            vdHeight = height;

            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) { Log.w(TAG, "no DisplayManager"); return false; }

            display = dm.createVirtualDisplay("blank-pip", width, height, dpi, surface, FLAGS);
            if (display == null) { Log.w(TAG, "createVirtualDisplay 返回 null(多半是缺权限)"); return false; }

            int id = display.getDisplay().getDisplayId();
            Log.i(TAG, "虚拟屏已建立 displayId=" + id + " " + width + "x" + height + "@" + dpi);
            return launchOnDisplay(pkg, id);
        } catch (Throwable t) {
            Log.w(TAG, "start 失败(通常是没提权)", t);
            stop();
            return false;
        }
    }

    /**
     * 改虚拟屏尺寸,**不销毁重建**——里面的 app 不重启,只收到一次 configuration change。
     * 实测百度地图和 QQ音乐都能正确重新布局,PID 不变、不断播、无黑边。
     *
     * **光调 resize() 不够,必须跟一次 setSurface()**,否则卡片变大时画面出不来(全黑)。
     *
     * 原因在 buffer 尺寸上:resize() 在 framework 里只改了 DisplayDevice 的宽高并发一个
     * CHANGED 事件,而输出用的那块 Surface 是建屏时就绑给 SurfaceFlinger 的,
     * VirtualDisplayAdapter 只在**surface 引用变了**的时候才重新 setDisplaySurface。
     * resize 不置那个标志 → SF 侧的 buffer geometry 一直是旧的。变小时旧的大 buffer
     * 装得下新画面,所以看着正常;变大就装不下,合成结果落不进来,画面一直黑
     * (实测 923→1199 能过、923→1568 全黑,等 14 秒也不恢复)。
     *
     * setDefaultBufferSize 兜不住这件事:它的语义是「producer 没显式指定尺寸时才用这个默认值」,
     * 而 SF 配置输出时是显式设 buffer geometry 的,会把它覆盖掉。
     *
     * 所以这里换一个**新的 Surface 对象**(包同一个 SurfaceTexture)推给它:适配器是引用比较,
     * 传旧的那个会被当成「没变」跳过。SF 收到后会 disconnect 旧的、按新尺寸重新 connect,
     * buffer 也就跟着重分配了。
     *
     * 不走「先 setSurface(null) 再设回来」:surface 为 null 期间虚拟屏会被当成 blanked,
     * 里面的 app 可能收到 onStop,那就白折腾了。
     */
    public boolean resize(SurfaceTexture texture, int width, int height, int dpi) {
        if (display == null) return false;
        try {
            if (texture != null) texture.setDefaultBufferSize(width, height);
            display.resize(width, height, dpi);

            if (texture != null) {
                Surface fresh = new Surface(texture);
                display.setSurface(fresh);
                Surface old = surface;
                surface = fresh;
                // setSurface 是同步 binder 调用,返回时 DMS 那边已经拿到自己那份引用
                // (Surface 跨进程是 dup 过去的),所以本地这个旧的可以直接放掉。
                if (old != null) {
                    try { old.release(); } catch (Throwable ignored) { }
                }
            }

            vdWidth = width;
            vdHeight = height;
            Log.i(TAG, "虚拟屏 resize → " + width + "x" + height + "@" + dpi + "(已重绑 surface)");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "resize 失败", t);
            return false;
        }
    }

    /**
     * 把 app 启动到指定虚拟屏。
     *
     * 跨屏启动要 INTERACT_ACROSS_DISPLAYS(signature 级),普通 app 身份必被拒——
     * 哪怕 Shizuku 已授权也一样,因为 ctx.startActivity() 是**我们自己的进程**在调,
     * uid 还是 10xxx。所以这一步必须整个扔进提权 shell 里跑。
     * 直连 startActivity 只作为没提权时的兜底(基本会失败,但留着能看到原始报错)。
     */
    private boolean launchOnDisplay(String pkg, int displayId) {
        ComponentName cn = launchComponent(pkg);
        if (cn == null) { Log.w(TAG, "找不到启动入口: " + pkg); return false; }

        if (Shell.ready()) {
            // 只给 NEW_TASK(0x10000000)——**不要** MULTIPLE_TASK,也不要 EXCLUDE_FROM_RECENTS。
            //
            // 这一条决定了画中画的语义是「搬」还是「另开一个」:
            //   带 MULTIPLE_TASK  → 每次都新建 task。已经在主屏跑着的 app 会被复制一份到
            //                       虚拟屏,主屏后台还留着旧的,同一个 app 两个实例互相抢
            //                       activity(音乐当场黑屏,实测)。
            //   只给 NEW_TASK     → 系统把**原来那个 task 整个搬到目标屏**,taskId 不变,
            //                       app 的状态和返回栈都保留。模拟器实测:设置 app 的
            //                       taskId=418 从 display 0 原样搬到虚拟屏 135。
            // 桌面的交互(点第二个 app 时把第一个也收进画中画)要的正是"搬"。
            //
            // EXCLUDE_FROM_RECENTS 同理不能加:搬的是**用户自己的 task**,加了会把人家
            // 从最近任务里抹掉。(当初加它是为了擦掉 MULTIPLE_TASK 造出来的僵尸 task,
            // 现在根本不造了。)
            String cmd = "am start --display " + displayId
                    + " -f 0x10000000"
                    + " -n " + cn.flattenToShortString();
            Shell.Result r = Shell.exec(cmd);
            // am 起不来时 exit 仍是 0,只能看输出。
            // "does not support launch on secondary displays" 是硬失败:app 明确不支持副屏
            // (车机自带的系统 app 里不少这样),系统只弹个 toast,am 照样返回 0。
            String out = r.out == null ? "" : r.out;
            boolean bad = out.contains("Error") || out.contains("Exception")
                    || out.contains("secondary display");

            // "Activity not started, its current task has been brought to the front"
            // **不是失败**——搬迁模式下这是成功的常态输出:app 本来就在跑,系统没有新起
            // activity,而是把它那个 task 搬了过来。但同一句话也可能意味着 task 被挪去了
            // 别处,所以不能光看输出,得问一下系统那块屏上到底站着谁。
            if (!bad && out.contains("Activity not started") && !landedOn(displayId, pkg)) {
                Log.w(TAG, pkg + " 的实例被挪到了别处,没落在 display " + displayId);
                bad = true;
            }
            if (r.ok() && !bad) {
                Log.i(TAG, "已把 " + cn.flattenToShortString() + " 起到 display " + displayId);
                watchLanded(pkg, displayId);
                watchAlive(pkg, displayId);   // 起来之后盯着它别悄悄死掉
                return true;
            }
            Log.w(TAG, "提权跨屏启动失败: " + r);
            return false;
        }

        Log.w(TAG, "未提权,退回普通 startActivity(预期会被拒)");
        try {
            Intent i = new Intent(Intent.ACTION_MAIN).setComponent(cn);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            ActivityOptions opts = ActivityOptions.makeBasic();
            opts.setLaunchDisplayId(displayId);
            ctx.startActivity(i, opts.toBundle());
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "跨屏启动失败(需 INTERACT_ACROSS_DISPLAYS)", t);
            return false;
        }
    }

    /** 主屏。就是 Display.DEFAULT_DISPLAY,写成常量是为了让 move-stack 那行读得出来。 */
    private static final int PRIMARY_DISPLAY = 0;

    /**
     * 把虚拟屏上的 app **整个搬回主屏**,而不是杀了重开。
     *
     * 这是「画中画 → 全屏」该走的路:`am display move-stack` 换的是 task 所在的屏,
     * activity 实例、返回栈、播放进度全部原样保留 —— 用户看到的是地图从卡片里长成全屏,
     * 而不是重新定位一次、音乐从头再来。老路子(release 虚拟屏,靠
     * DESTROY_CONTENT_ON_REMOVAL 连 task 一起销毁,再冷启动)只留作搬不动时的兜底。
     * 迪友桌面(`a4/t.java`)也是这么切的。
     *
     * 搬完还要再 `am start` 一次:move-stack 只保证 task 落到主屏,不保证它站在最前面
     * (系统 resume 的是主屏当时 focused 的那个 stack,可能是桌面自己)。只给 NEW_TASK、
     * **不带 MULTIPLE_TASK**,语义是"把已有的这个 task 提到前台",不重建 activity——
     * 跟 launchOnDisplay 里那行是同一套写法,原因见那儿的长注释。
     *
     * 必须在 release 虚拟屏**之前**调:反过来 task 早跟着虚拟屏一起没了,没得搬。
     *
     * @return 是否确认已落在主屏并提到了前台。false 时调用方该退回冷启动,
     *         否则用户会点了没反应(app 还在马上要被销毁的虚拟屏上)。
     */
    public boolean moveToPrimary(String pkg) {
        if (display == null || pkg == null || !Shell.ready()) return false;
        // 抢在 watchLanded 前面举旗:它的活儿正是"跑到主屏去了就搬回来",
        // 而我们这会儿要的恰恰是搬去主屏。启动后 6 秒内切全屏就会撞上,
        // 光靠它那句 display==null 的守卫挡不住(我们还没 release)。
        detaching = true;

        int[] at = whereIs(pkg);
        if (at == null) { Log.w(TAG, "搬回主屏:查不到 " + pkg + " 的 task"); return false; }

        // 已经在主屏就别白搬一趟 —— 闪屏把 task 带回去过(见 watchLanded)就是这种情形
        if (at[1] != PRIMARY_DISPLAY) {
            Shell.Result mv = Shell.exec("am display move-stack " + at[0] + " " + PRIMARY_DISPLAY);
            if (!mv.ok()) { Log.w(TAG, "move-stack 失败: " + mv); return false; }

            // 必须回查一次真值。move-stack 跟 am start 一样,失败了也常常是 exit 0,
            // 而这里判错的代价是"卡片没了、全屏也没起来"——虚拟屏紧接着就要 release,
            // 没搬走的 task 会被 DESTROY_CONTENT_ON_REMOVAL 顺手杀掉。
            // 这里不用 landedOn():它查不到时按"宁可放过"返回 true,那套宽松语义
            // 用在这儿正好是反的。
            int[] now = whereIs(pkg);
            if (now == null || now[1] != PRIMARY_DISPLAY) {
                Log.w(TAG, pkg + " 搬完仍不在主屏(" + (now == null ? "查无 task" : "display=" + now[1]) + ")");
                return false;
            }
        }

        ComponentName cn = launchComponent(pkg);
        if (cn == null) { Log.w(TAG, "搬回主屏后找不到启动入口: " + pkg); return false; }
        Shell.Result r = Shell.exec("am start -f 0x10000000 -n " + cn.flattenToShortString());
        if (!r.ok()) { Log.w(TAG, "提到前台失败: " + r); return false; }

        Log.i(TAG, "已把 " + pkg + " 从 display " + displayId() + " 原样搬回主屏(实例保留)");
        return true;
    }

    /**
     * 起完之后盯一会儿:**有些 app 的闪屏会把整个 task 带回主屏**。
     *
     * 实测百度地图:`SlashActivity` 确实起到了虚拟屏,但它跳转 `MapsActivity` 之后
     * 整个 task 落到了 display 0(`visible=false`),虚拟屏上空无一物 —— 表现为
     * 「卡片全黑」;这个 task 一旦被拉到前台,又变成主屏上的全屏 app 盖住桌面。
     * `am start` 那一刻是成功的,所以启动时的判据看不出问题,只能事后盯。
     *
     * 盯到了就用 `am display move-stack` 整个搬回来(实测搬完画面立刻恢复,
     * 而且 task 的 bounds 会自动跟上虚拟屏尺寸)。
     *
     * 分几次查而不是查一次:闪屏跳转的时机各家不同,查早了还没跳、查晚了用户已经
     * 看见黑屏。搬回来是幂等的,多搬一次没有代价 —— 这跟「判定启动失败」不一样,
     * 那个误判要收回卡位,代价大得多(见 PipCard 里那条老待办)。
     */
    private void watchLanded(final String pkg, final int displayId) {
        for (int i = 0; i < WATCH_DELAYS_MS.length; i++) {
            final long delay = WATCH_DELAYS_MS[i];
            final boolean last = i == WATCH_DELAYS_MS.length - 1;
            WATCH.schedule(() -> {
                // 卡片已经关了 / 换了另一块屏 / 正在往主屏搬,这次盯梢就作废
                if (display == null || displayId() != displayId || detaching) return;
                int[] at = whereIs(pkg);
                if (at == null) {
                    /*
                     * **起完就没了**:盯到最后一次,任何屏上都查不到这个 task。
                     *
                     * 实测 bilibili 车机版:闪屏起到虚拟屏是成功的,但它自己 startActivity
                     * 跳主界面时被系统拒了 —— 虚拟屏的 owner 是留白,app 既不是 owner 也没有
                     * INTERNAL_SYSTEM_WINDOW,系统直接 Permission Denial(logcat 里能看到
                     * "starting Intent ... with launchDisplayId=53" 那条)。闪屏随即销毁,
                     * 虚拟屏上空无一物,表现为**卡片全黑**。
                     *
                     * `am start` 那一刻是成功的,启动时的判据看不出问题;只能盯到最后仍然
                     * 查无此 task,才判定"这个 app 装不进画中画",交给上层退回全屏。
                     *
                     * 前几次查不到不算数:冷启动慢的 app 这时候可能还没起来。
                     */
                    if (last) {
                        Log.w(TAG, pkg + " 起完就没了(闪屏跳转多半被多屏权限拒了),判定装不进画中画");
                        Runnable r = onStillborn;
                        if (r != null) r.run();
                    }
                    return;
                }
                if (at[1] == displayId) return;                 // 本来就在这块屏上
                Log.w(TAG, pkg + " 跑到了 display " + at[1] + "(stack " + at[0]
                        + "),搬回 display " + displayId);
                Shell.Result mv = Shell.exec("am display move-stack " + at[0] + " " + displayId);
                if (!mv.ok()) Log.w(TAG, "搬回失败: " + mv);
            }, delay, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }

    /** 查几次、隔多久。覆盖闪屏跳转的常见时机,最后一次留足冷启动加载的余量。 */
    private static final long[] WATCH_DELAYS_MS = { 1200L, 3000L, 6000L };

    /**
     * 起来之后,每隔这么久确认一次里面那个 app 还活着。
     *
     * 它被外部杀掉(`am force-stop`、系统回收、自己崩)之后,**虚拟屏还在、TextureView
     * 上冻着最后一帧** —— 屏幕上是一张看着完全正常的地图,实际早就死了,点也没反应。
     * 用户只会以为"卡住了",而卡位还占着,从 Dock 上也救不回来(occupied 仍是 true)。
     *
     * 首次延迟必须**长于 watchLanded 的最后一次(6 秒)**:启动期查不到 task 是常态
     * (启动慢的 app 那时还没进 stack),拿它判死就是 PipCard 那条老待办里踩过的坑。
     * 这里只在"已经确认起来过"之后才跑,判据的含义完全不同 —— 稳定期查不到就是真没了。
     */
    private static final long ALIVE_FIRST_MS = 15_000;
    private static final long ALIVE_PERIOD_MS = 8_000;
    /** 连查几次都找不到才判死。一次可能撞上 am 抖动,不值得为此收掉用户的卡片。 */
    private static final int ALIVE_MISS_LIMIT = 2;

    private java.util.concurrent.ScheduledFuture<?> aliveTask;
    private Runnable onDied;

    /** 里面的 app 没了。回调在**盯梢线程**上,调用方要自己切回主线程。 */
    public void setOnDied(Runnable r) { this.onDied = r; }

    /** 起完就没了(见 watchLanded):这个 app 装不进画中画,上层该退回全屏。 */
    private Runnable onStillborn;

    public void setOnStillborn(Runnable r) { this.onStillborn = r; }

    /**
     * 开始盯"它还在不在"。只在 launchOnDisplay 确认起来之后调。
     *
     * 判据是 whereIs(pkg)==null —— 查的是**全局任意屏**,所以 app 自己跑去主屏
     * 也算活着(那是 watchLanded 管的事,不该在这儿被当成死了)。
     */
    private void watchAlive(final String pkg, final int displayId) {
        cancelAlive();
        final int[] missed = { 0 };
        aliveTask = WATCH.scheduleWithFixedDelay(() -> {
            // 卡片已经关了 / 换了另一块屏,这轮盯梢就作废
            if (display == null || displayId() != displayId) return;
            if (whereIs(pkg) != null) { missed[0] = 0; return; }
            if (++missed[0] < ALIVE_MISS_LIMIT) {
                Log.i(TAG, pkg + " 这次没查到(" + missed[0] + "/" + ALIVE_MISS_LIMIT + ")");
                return;
            }
            Log.w(TAG, pkg + " 已经不在了(连查 " + ALIVE_MISS_LIMIT + " 次无 task),收回卡位");
            missed[0] = 0;
            Runnable r = onDied;
            if (r != null) r.run();
        }, ALIVE_FIRST_MS, ALIVE_PERIOD_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void cancelAlive() {
        java.util.concurrent.ScheduledFuture<?> t = aliveTask;
        aliveTask = null;
        if (t != null) t.cancel(false);
    }

    /** 盯梢用的单线程:Shell.exec 是阻塞 IO,绝不能占主线程。 */
    private static final java.util.concurrent.ScheduledExecutorService WATCH =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "blank-pip-watch");
                t.setDaemon(true);
                return t;
            });

    /**
     * 这个包现在落在哪:返回 {stackId, displayId}。找不到返回 null。
     *
     * `am stack list` 的一段形如:
     * <pre>Stack id=97 bounds=[0,0][1920,1080] displayId=0 userId=0
     *   taskId=1992: com.baidu.naviauto/...</pre>
     *
     * 注意 API 30(Android 11)起,同一命令的段首从 `Stack id=` 改成了
     * `RootTask id=`(多窗口改造),而且每段多了一整块跨行的 configuration。
     * 只认老格式的话这里查谁都是 null —— watchAlive 会把活着的 app 连查两次判死、
     * watchLanded 会把起好的 app 判成"起完就没了",画中画起来几秒/二十几秒就被收回
     * (华为 MRX-W09 / API 31 真机实测)。两种前缀都认,段首都是数字,下面正则可通吃。
     */
    private static int[] whereIs(String pkg) {
        Shell.Result r = Shell.exec("am stack list");
        if (!r.ok() || r.out == null || r.out.isEmpty()) return null;
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "^(\\d+)\\b.*?displayId=(\\d+)", java.util.regex.Pattern.DOTALL);
        for (String seg : r.out.split("(?m)^(?:RootTask|Stack) id=")) {
            if (seg.isEmpty() || !seg.contains(pkg)) continue;
            java.util.regex.Matcher m = p.matcher(seg);
            if (m.find()) {
                try {
                    return new int[] { Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) };
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * 目标屏上到底站着谁。`am stack list` 按 displayId 列出每块屏上的 activity,
     * 这是唯一靠得住的判据 —— am start 的输出只能说明「它做了什么」,说明不了「结果在哪」。
     * 查不到就返回 true:宁可放过,不可误杀。
     */
    private boolean landedOn(int displayId, String pkg) {
        Shell.Result r = Shell.exec("am stack list");
        // 命令自己就没跑成的时候别下结论。exec 失败时会把异常文本塞进 out
        // (execDirect 就是这么写的),那串东西当然不含 displayId,照着解析下去
        // 就成了"没落在目标屏"——把一次通道抖动误判成启动失败,画中画白白收回空态。
        if (!r.ok() || r.out == null || r.out.isEmpty()) return true;
        // 按 Stack/RootTask 分段(前缀随 API 30 变过,原因见 whereIs);
        // displayId 后面补个空格,免得 9 匹配上 90
        for (String seg : r.out.split("(?m)^(?:RootTask|Stack) id=")) {
            if (seg.contains("displayId=" + displayId + " ") && seg.contains(pkg)) return true;
        }
        return false;
    }

    private ComponentName launchComponent(String pkg) {
        Intent i = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
        return i != null ? i.getComponent() : null;
    }

    /**
     * 让这块 View 上的触摸落到虚拟屏里的 app 上——画中画从"能看"变成"能用"。
     *
     * View 和虚拟屏尺寸不一定相等(卡片小、虚拟屏大),所以按比例换算;
     * 事件原样透传,多点触控、滑动惯性都由目标 app 自己处理。
     */
    public void attachTouch(View view) {
        view.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (display == null) return false;
                float sx = v.getWidth()  > 0 ? (float) vdWidth  / v.getWidth()  : 1f;
                float sy = v.getHeight() > 0 ? (float) vdHeight / v.getHeight() : 1f;
                InputInjector.send(event, displayId(), sx, sy);
                return true;   // 事件已转走,别再让桌面自己处理
            }
        });
    }

    public void stop() {
        cancelAlive();
        detaching = false;
        if (display != null) {
            try { display.release(); } catch (Throwable ignored) { }
            display = null;
        }
        if (surface != null) {
            try { surface.release(); } catch (Throwable ignored) { }
            surface = null;
        }
    }
}
