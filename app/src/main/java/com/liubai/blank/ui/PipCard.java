package com.liubai.blank.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.SurfaceTexture;
import android.text.TextUtils;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;

import com.liubai.blank.privileged.PipHost;
import com.liubai.blank.privileged.Shell;

/**
 * 桌面上的画中画卡片:把音乐 app 的真实界面嵌进桌面那块卡里。
 *
 * 为什么值得这么折腾:歌词。播放器的开放 API 有包名白名单(实测 QQ音乐车机版
 * 全部返回 No permission),Flyme ticker 协议它也不发,自己的桌面歌词是悬浮窗自绘。
 * 三条路都堵死,但把它的界面整个搬过来,歌词就是它自己画的——绕过了取数据这件事。
 *
 * 降级:提权不可用 / 用户没开 / 启动失败,一律 gone,让下面那套自绘卡兜底。
 * 这卡片是加分项,不能成为没装 Shizuku 就白屏的理由。
 */
public final class PipCard {

    private static final String TAG = "Blank/PipCard";
    private static final String PREFS = "pip_card";
    private static final String KEY_ON = "enabled";
    /** 通用卡位记住上次开的 app,重建桌面后原样恢复。 */
    private static final String KEY_TARGET = "target";

    private final Context ctx;
    /** 这个卡位自己的状态(上次开的是哪个 app)。总开关不在这儿,见 switchPrefs。 */
    private final SharedPreferences prefs;
    private final PipHost host;

    /** 画中画起停时通知调用方——自绘那层要跟着让位/回来。 */
    public interface Listener {
        void onPipState(boolean running);

        /**
         * 画中画没能起来(app 拒绝在虚拟屏上跑之类),调用方该退回全屏打开。
         *
         * 只在用户主动点开时回调:自动恢复上次那个 app 失败了就安静收着,
         * 否则一开桌面就自己蹦出个全屏 app,那是惊吓不是功能。
         */
        default void onPipLaunchFailed(String pkg) { }

        /**
         * 画面暂时不能看的时候(拖动中、虚拟屏正在重建),调用方该拿占位层盖住卡片。
         *
         * 拖动时若直接让实时画面跟着拉伸,看到的是一张被拉扁的界面;松手重建完又"啪"地
         * 换成新画面,很突兀。盖上 app 图标的占位,拖动期间是静态的,换回来时也不刺眼。
         *
         * @param instant 立刻盖死,不要淡入 —— 聚焦切换用(见 beginSwitch)。
         *                淡入需要时间,而布局下一帧就变了,那段时间正好把拉伸的画面漏出去。
         */
        default void onPipBusy(boolean busy, String pkg, boolean instant) { }
    }

    private TextureView view;
    private String pkg;
    private boolean running;
    private Listener listener;
    private int vdWidth, vdHeight;   // 当前虚拟屏尺寸,用来判断卡片是否变了形
    /** 拉伸拖拽期间挂起虚拟屏同步,见 setResizeSuspended。 */
    private boolean resizeSuspended;
    /** 重建期间忽略由自己引发的尺寸回调,见 syncSize。 */
    private boolean syncing;
    /** 拖动期间钉住的 TextureView 宽度。 */
    private int frozenW;

    /** 画面暂时不能看(拖动中/正在重建),此时由调用方拿占位层盖住。 */
    private boolean busy;

    /**
     * resize 之后占位层至少还要盖住这么久。
     *
     * 虚拟屏尺寸一改,SurfaceFlinger 立刻就有新帧过来,但**里面那个 app 还没重排完** ——
     * 那几帧是旧尺寸的内容被拉到新尺寸上,正是"切换时变形一闪而过"的元凶。
     * 认第一帧就撤占位,等于专门把变形那几帧放出来给用户看。
     *
     * 用时长而不是帧数:帧数在掉帧时会被拉长成不确定的时间,而这里要的是一段
     * **稳定可预期的过渡** —— 占位层该像拖动画中画时那样从容地停一下,
     * 不是闪一下就没。撤除后还有一段淡出(见 HomeActivity.showPlaceholder),
     * 新画面是渐显出来的,所以这个值不用留重排的余量留得太满。
     */
    private static final long SETTLE_MS = 240L;
    /** 早于这个时刻来的帧一律不算数(见 SETTLE_MS)。 */
    private long settleUntil;
    /** 兜底:万一首帧一直不来,也不能让占位层一直糊在那儿。 */
    private static final long BUSY_MAX_MS = 1200;
    private final Runnable clearBusy = new Runnable() {
        @Override public void run() { setBusy(false); }
    };

    private void setBusy(boolean b) {
        if (busy == b) return;
        busy = b;
        if (!b) settleUntil = 0L;        // 占位撤了,别把停留期限留给下一次
        if (view != null && !b) view.removeCallbacks(clearBusy);
        if (listener != null) listener.onPipBusy(b, pkg, b && instantBusy);
        if (!b) instantBusy = false;     // 只作用于这一次盖上,撤除后复位
    }

    /** 这次盖占位层要不要硬切,见 beginSwitch。 */
    private boolean instantBusy;

    /**
     * 聚焦切换开始:**先把占位层盖死,再让布局动**。
     *
     * 原来的顺序是反的 —— `setFocus` 先改布局,再由 `syncSizeNow` 里的 `setBusy(true)`
     * 触发占位层淡入。可布局下一帧(~16ms)就生效了,而淡入要 160ms 才不透明,中间这段
     * 占位层还是半透明的,底下正是被硬拉伸的旧画面。三步优化(立刻 resize / SETTLE_MS
     * 停留 / 淡入淡出)治的都是 resize **之后**那段,这一段在它们之前,没人管。
     *
     * 迪友桌面(`r4/i.java:317`)在同类操作上就是这么做的:拖拽一开始先 `setVisibility`
     * 硬切盖上,全程不 resize,松手才同步。硬切在这里不违和 —— 切换是一次明确的用户动作,
     * 盖上那一下本来就该有变化;决定观感的是后面那段淡出。
     */
    public void beginSwitch() {
        if (!running) return;
        instantBusy = true;
        Log.i(TAG, "切换开始,占位层硬切盖上: " + pkg);
        setBusy(true);
        // 万一后面那条路没走到(尺寸没落定就 return 了),占位层不能一直糊着
        if (view != null) view.postDelayed(clearBusy, BUSY_MAX_MS);
    }
    /** 这次启动是不是用户主动点开的——只有主动的,失败了才该退回全屏。 */
    private boolean userInitiated;

    /** 尺寸同步防抖窗口:比一次布局里的连续变化长,比人眼察觉得到的短。 */
    private static final long SYNC_DEBOUNCE_MS = 250;
    private int pendingW, pendingH;
    private final Runnable syncTask = new Runnable() {
        @Override public void run() { doSync(); }
    };

    // 待办:识别「am 说成功、其实没起来」的 app(车机系统 app 里不少不支持副屏,
    // 系统只弹个 toast 拦下,am 照样返回 0)。试过两种判据都不可用:
    //   · 看有没有画面 —— 虚拟屏空着 SurfaceFlinger 也照画背景,帧一直有
    //   · 起后 1.5s 查 `am stack list` 里那块屏上站着谁 —— 启动慢的 app(实测
    //     com.android.dialer)那时还没进 stack,会被误判成失败,还引发连锁重建
    // 要做得靠谱得带重试、延时也得放宽,验证充分了再加。现在的表现是:
    // 这类 app 点开后卡片空着,用户可以点右上角关掉,不影响其它功能。

    /**
     * 一个卡位。桌面上有左右两个,各自记住自己开着什么。
     *
     * @param key 独立的 prefs 名——每个卡位单独记自己的 target,互不干扰
     */
    public static PipCard slot(Context ctx, String key) {
        return new PipCard(ctx, key);
    }

    private PipCard(Context ctx, String prefsName) {
        this.ctx = ctx.getApplicationContext();
        this.prefs = this.ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
        this.host = new PipHost(this.ctx);
        // 盯梢在后台线程上,收卡位要动 UI
        host.setOnStillborn(() -> new android.os.Handler(android.os.Looper.getMainLooper())
                .post(PipCard.this::onStillborn));
        host.setOnDied(() -> new android.os.Handler(android.os.Looper.getMainLooper())
                .post(this::onHostDied));
    }

    /**
     * 里面那个 app 在**外部**没了(force-stop / 系统回收 / 自己崩)。
     *
     * 不处理的话:虚拟屏还在、TextureView 上冻着最后一帧,屏幕上是一张看着完全正常的
     * 界面,点它却没反应 —— 用户只会以为"卡住了"。而 occupied() 仍是 true,Dock 上那颗
     * 键走的是「切聚焦」而不是「重新打开」,**从 Dock 上救不回来**,只能先关掉卡片
     * (真车/模拟器实测)。
     *
     * **pkg 要清掉**:occupied() 看的就是它,不清的话卡位照样占着,等于没修。
     * prefs 里的 target 反而要留着 —— app 是被杀的、不是用户主动关的,下次点 Dock
     * 或冷启动恢复时还该是它。
     */
    /** 这一次是不是用户亲手点开的。userInitiated 起来就清了,onStillborn 比那晚,只能另存一份。 */
    private boolean launchByUser;

    private void onHostDied() {
        if (!running && TextUtils.isEmpty(pkg)) return;   // 已经收过了
        Log.i(TAG, "卡位里的 " + pkg + " 已经没了,收回空态");
        pkg = null;
        stop();
    }

    /**
     * 里面那个 app **起完就没了**(见 PipHost.watchLanded):闪屏起来了、跳主界面被多屏权限
     * 拒了,虚拟屏上空无一物。这不是"app 死了",是这个 app 根本装不进画中画 ——
     * 收回卡位,交给上层按老规矩全屏打开(见 HomeActivity.onPipLaunchFailed)。
     *
     * 只有**用户刚点开**的才退回全屏:冷启动恢复上次的卡位时它起不来,悄悄收掉就行,
     * 不该开机就把某个 app 怼到用户脸上。
     */
    private void onStillborn() {
        if (!running && TextUtils.isEmpty(pkg)) return;   // 已经收过了
        String dead = pkg;
        boolean report = launchByUser;
        Log.w(TAG, "卡位里的 " + dead + " 起完就没了,收回" + (report ? ",退回全屏" : ""));
        pkg = null;
        // 别让它下次开桌面再试一遍 —— 这个 app 在虚拟屏里就是起不来
        prefs.edit().remove(KEY_TARGET).apply();
        stop();
        if (report && listener != null) listener.onPipLaunchFailed(dead);
    }

    /** 这个卡位能不能用 = 总开关开着且提权就绪。 */
    public boolean enabled() {
        return enabled(ctx);
    }

    /**
     * 画中画总开关(默认关——提权是可选项,不能默认要求)。
     *
     * **两张卡共用同一个开关**,存在固定的 PREFS 里,与各卡位自己的 target 分开放。
     * 关掉它整个功能就停用:点 app 一律全屏,桌面永远是空白的。
     */
    public static boolean enabled(Context ctx) {
        return switchPrefs(ctx).getBoolean(KEY_ON, false);
    }

    /**
     * 现在到底能不能用画中画 = **模式给了卡位** + 用户开关开着 + 提权就绪。
     *
     * 三个前提各管一件事,少一个都开不了,所以收在这一处、别在调用点各判各的:
     * 模式决定这套桌面有没有这个能力(pipSlots,见 Capabilities),
     * 开关是用户自己要不要,提权是技术上办不办得到。
     */
    public static boolean usable(Context ctx) {
        return modeSlots() > 0 && enabled(ctx) && available(ctx);
    }

    /** 当前模式给几个画中画卡位(0 = 这个模式没有画中画)。 */
    public static int modeSlots() {
        return com.liubai.blank.mode.ModeManager.get().caps().pipSlots;
    }

    public static void setEnabled(Context ctx, boolean on) {
        switchPrefs(ctx).edit().putBoolean(KEY_ON, on).apply();
    }

    private static SharedPreferences switchPrefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 卡位上次开的 app,桌面重建后照它恢复;没有就是空态。 */
    public String lastTarget() {
        return prefs.getString(KEY_TARGET, null);
    }

    /**
     * 在这个卡位里打开(或切到)指定 app。一个卡位同时只托一个 app,
     * 已经开着别的就先把旧的虚拟屏收掉。
     *
     * @return 是否真的起来了。起不来(提权没就绪、app 拒绝在虚拟屏跑)返回 false,
     *         调用方据此退回全屏打开——不能让用户点了没反应。
     */
    public boolean openApp(String target) {
        if (TextUtils.isEmpty(target) || view == null) return false;
        if (!available()) return false;      // 提权没就绪,这条路根本走不通,让调用方全屏开
        if (isHosting(target)) return true;
        if (running) stopHostQuietly();
        this.pkg = target;
        prefs.edit().putString(KEY_TARGET, target).apply();
        userInitiated = true;
        showAndLaunch(view);
        // 注意不能拿 running 当返回值:卡片空着时 TextureView 是 gone 的,
        // surface 要等下一帧才有,showAndLaunch 这一下只是把卡片显出来,
        // 真正启动在 onSurfaceTextureAvailable 里。同步返回 false 会让调用方
        // 以为失败,再全屏开一次——一次点击开出两个。
        // 所以这里一律算「接管了」,真起不来会走 onPipLaunchFailed。
        return true;
    }

    /** 关掉卡位里的 app,回到空态;下次开桌面不再自动恢复它。 */
    public void close() {
        prefs.edit().remove(KEY_TARGET).apply();
        pkg = null;
        if (running) {
            stop();
        } else {
            if (view != null) view.setVisibility(View.GONE);
            notifyState();
        }
    }

    /** 提权就绪才谈得上画中画(内置自连 adb 或 Shizuku,任一条通道通即可)。 */
    public boolean available() {
        return Shell.ready();
    }

    /** 同上,给没有卡位实例的调用方(设置页)用。 */
    public static boolean available(Context ctx) {
        return Shell.ready();
    }

    public boolean running() {
        return running;
    }

    /**
     * 这个卡位有没有活儿:已经在跑,**或者刚被指派了目标、正在起的路上**。
     *
     * 卡片显隐必须看这个而不是 running()。启动是异步的(要等 TextureView 拿到
     * surface),这中间要是因为"还没 running"把卡片藏了,surface 就永远来不了——
     * 两张卡一起开时必踩:先起来的那张一回调,后一张就被判成空的藏掉,再也起不来。
     */
    public boolean occupied() {
        return running || !TextUtils.isEmpty(pkg);
    }

    /** 当前是不是正把这个 app 托在虚拟屏上。 */
    public boolean isHosting(String p) {
        return running && p != null && p.equals(pkg);
    }

    /** 此刻托着的包名,没在跑就是 null。给卡片角标那种"只知道是这张卡"的调用方用。 */
    public String hosted() {
        return running ? pkg : null;
    }

    /**
     * 绑到卡片里的 TextureView 上。每次重建页面都要重新调。
     *
     * @param target 要嵌进来的 app 包名
     */
    public void attach(TextureView v, String target, Listener l) {
        this.view = v;
        this.pkg = target;
        this.listener = l;

        // 监听器无条件先挂,不能放在下面那个提前 return 之后。
        // surface 何时就绪由系统决定,晚一步注册就接不到那次回调;而通用卡位
        // 一开始本来就是空的(target=null),真等用户点了图标才创建 surface——
        // 监听器要是没挂,那次 onSurfaceTextureAvailable 就永远等不到,
        // 表现为「点了图标,卡片显出来了,里面却一直是空的」。
        v.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) {
                launch(s, w, h);
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) {
                // 卡片宽度是按屏幕比例算出来的,首帧之后会变一次;用户拉伸卡片时也会变。
                // 虚拟屏尺寸定死在创建时,不跟着改的话画面会被拉伸变形。
                if (!running) {
                    // 上一次因为尺寸还没落定没起成(见 launch 里的 tooSmall),
                    // 这会儿尺寸变了,补一次——不然就永远起不来了。
                    if (!TextUtils.isEmpty(pkg) && enabled() && available()) launch(s, w, h);
                    return;
                }
                syncSize(s, w, h);
            }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) {
                stop();
                return true;
            }
            // 别想在这里判断启动成没成:虚拟屏就算是空的,SurfaceFlinger
            // 照样往上画背景,帧一个不少(实测)。见上面那条待办。
            // 但用来判断"新画面出来了"是合适的 —— 只是不能认第一帧,见 SETTLE_MS。
            @Override public void onSurfaceTextureUpdated(SurfaceTexture s) {
                if (!busy || resizeSuspended) return;
                // resize 之后紧接着来的那几帧,app 还没重排完,内容仍是旧尺寸的、
                // 被拉到新尺寸上 —— 这时候撤占位,放出去的正好是变形的那几帧
                // (表现为"切换时变形一闪而过")。等它安顿下来再撤。
                if (android.os.SystemClock.uptimeMillis() < settleUntil) return;
                setBusy(false);
            }
        });

        if (!enabled() || TextUtils.isEmpty(target)) {
            Log.i(TAG, "卡位空着(开关=" + enabled() + " 目标=" + target + ")");
            v.setVisibility(View.GONE);
            notifyState();
            return;
        }

        if (available()) {
            showAndLaunch(v);
            return;
        }

        // 别在这里判死刑:提权服务的 binder 是异步连上的,冷启动时界面都搭完了它还没就位。
        // 先按降级显示,等它连上再补一次(没装 Shizuku 的话这个回调永远不来,正好就一直降级)。
        Log.i(TAG, "提权尚未就绪,先降级,等连上再启动");
        v.setVisibility(View.GONE);
        notifyState();
        Shell.onReady(() -> v.post(() -> {
            if (running || v != view) return;   // 期间页面重建过、或已起来了就不管
            showAndLaunch(v);
        }));
    }

    /** 变可见并尽快开屏;surface 还没好就交给 onSurfaceTextureAvailable 接手。 */
    private void showAndLaunch(TextureView v) {
        v.setVisibility(View.VISIBLE);
        SurfaceTexture ready = v.getSurfaceTexture();
        if (ready != null && v.getWidth() > 0) launch(ready, v.getWidth(), v.getHeight());
    }

    /** 这张卡在不在场上(见 setPresented)。默认在。 */
    private boolean presented = true;

    /**
     * 这张卡在不在场上。聚焦时另一张会被整个 `GONE` 掉——**虚拟屏照跑**,里面的 app
     * 不重启、不断播,只是画面暂时不往桌面贴。
     *
     * 重新露面时**必须重绑一次 Surface**:TextureView 停过渲染之后,那块 Surface 不会
     * 自己恢复收内容(实测再显出来是一片透明,底下主页的字都透上来)。这跟「resize 变大
     * 黑屏」是同一个病——SurfaceFlinger 那侧的绑定关系需要显式重建,framework 不会主动做。
     * 所以这里借 {@link PipHost#resize} 重绑一次,顺带把可能变过的尺寸一起同步了。
     *
     * 要等 layout 落定才拿得到真实宽高,所以走 post。
     */
    public void setPresented(boolean on) {
        // 没在跑的时候藏起来不算数:虚拟屏都还没有,谈不上"停过渲染",
        // 下次露面是全新建屏,不需要重绑
        if (!on && !running) return;
        if (presented == on) return;
        presented = on;
        if (on) resizeNow(true, "重新露面");
    }

    /**
     * 聚焦切换用:尺寸是确定的、用户正等着,**跳过防抖立刻同步**。
     *
     * 防抖那 250ms 是给「一次布局里连着变好几档」的中间值准备的,而聚焦是一次明确的
     * 用户动作,目标尺寸当场就定了。等那一下的代价是:TextureView 已经变成新尺寸、
     * 虚拟屏还是旧的,这段时间画面被硬拉伸,之后 resize 完 app 重排又跳一次 ——
     * 两次跳变叠在一起就是"切换时闪一下"。
     */
    public void syncSizeNow() {
        resizeNow(false, "聚焦切换");
    }

    /**
     * 立刻把虚拟屏尺寸同步到 TextureView 的当前大小,过渡期盖上占位层。
     *
     * @param force 尺寸没变也要重绑一次 —— 「重新露面」属于这种:卡片刚从 GONE 回来,
     *              尺寸可能和藏起来之前一模一样,但那块 Surface 已经不收内容了(见
     *              setPresented 的说明),不重绑贴回来是一片透明。
     */
    private void resizeNow(final boolean force, final String why) {
        if (!running || view == null) return;
        final TextureView v = view;
        v.removeCallbacks(syncTask);     // 防抖那条作废,这里立刻做
        setBusy(true);                   // 盖住拉伸和 app 重排,别让用户看见中间态
        v.post(new Runnable() {
            @Override public void run() {
                if (!running || v != view) { setBusy(false); return; }
                SurfaceTexture s = v.getSurfaceTexture();
                int w = v.getWidth(), h = v.getHeight();
                if (s == null || tooSmall(w, h)) {
                    Log.w(TAG, why + ":尺寸还没落定(" + w + "x" + h + "),交给尺寸回调");
                    setBusy(false);
                    return;
                }
                if (!force && w == vdWidth && h == vdHeight) { setBusy(false); return; }
                Log.i(TAG, why + " → " + w + "x" + h + ",立刻同步");
                if (host.resize(s, w, h, dpi())) {
                    vdWidth = w;
                    vdHeight = h;
                    // 占位层从这一刻起再稳定盖住一段,别把 app 重排中的那几帧放出去
                    settleUntil = android.os.SystemClock.uptimeMillis() + SETTLE_MS;
                }
                // 兜底:万一新画面的第一帧一直不来,占位层不能一直糊着
                v.postDelayed(clearBusy, BUSY_MAX_MS);
            }
        });
    }

    /**
     * 拉伸拖拽期间挂起虚拟屏同步。
     *
     * 拖动时卡片宽度每帧都在变,每帧 resize 一次既卡顿,又让里面的 app 不停地重排布局。
     * 挂起期间画面按旧尺寸临时拉伸(短暂变形,可接受),松手后按最终尺寸同步一次。
     */
    public void setResizeSuspended(boolean on) {
        if (resizeSuspended == on) return;
        resizeSuspended = on;
        if (view == null) return;

        if (on) {
            setBusy(true);     // 拖动全程盖上占位,别让用户看画面被拉扁
            // 把 TextureView 的尺寸钉死在当前值。
            // 不钉的话它每帧跟着卡片变宽,而 TextureView 尺寸一变就要重新分配
            // SurfaceTexture 的缓冲区——这是拖动不跟手的大头。
            // 钉住之后拖动期间一次都不用重分配,画面改用 scaleX 预览(见 previewWidth)。
            frozenW = view.getWidth();
            int h = view.getHeight();
            if (frozenW > 0 && h > 0) {
                ViewGroup.LayoutParams lp = view.getLayoutParams();
                lp.width = frozenW;
                lp.height = h;
                view.setLayoutParams(lp);
                view.setPivotX(0f);        // 左边缘固定,往右拉伸
            }
            return;
        }

        // 松手:恢复自适应尺寸,撤掉预览缩放,按最终大小同步一次虚拟屏
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        view.setLayoutParams(lp);
        view.setScaleX(1f);
        frozenW = 0;
        if (running) {
            // 这儿千万别主动同步一次尺寸:lp 刚改回 MATCH_PARENT,布局还没跑,
            // getWidth() 返回的仍是冻结时的旧值,拿它建出来的虚拟屏是错的;
            // 等布局落定后 onSurfaceTextureSizeChanged 还会带着真实尺寸再来一次。
            // 两次加起来 = 虚拟屏建两回、里面的 app 重启两回,就是"松手后刷新两次"。
            // 交给那一次回调去做就够了。
            view.postDelayed(clearBusy, BUSY_MAX_MS);
        } else {
            setBusy(false);
        }
    }

    /**
     * 拖动中的视觉预览:只改缩放,不碰布局,也就不会重新分配 surface。
     * 画面会横向拉伸一点(短暂变形),松手立刻恢复成真实比例。
     */
    public void previewWidth(int w) {
        if (!resizeSuspended || view == null || frozenW <= 0 || w <= 0) return;
        view.setScaleX((float) w / frozenW);
    }

    /**
     * 卡片尺寸变了,让虚拟屏跟上。
     *
     * 优先走 {@link PipHost#resize}(app 不重启、不断播),失败才退回销毁重建。
     *
     * 早先这里一律走重建,依据是「QQ音乐收到 configuration change 后不重新布局,
     * 画面居中留黑边」——**这条结论已经不成立**:实测百度地图和 QQ音乐都能正确重排,
     * PID 不变、无黑边(可能是版本更新了,也可能当时测试条件不同)。
     * 重建的代价是 app 重启、跳回首页,而聚焦切换是高频操作,担不起。
     */
    private void syncSize(SurfaceTexture s, int w, int h) {
        if (!running || w <= 0 || h <= 0) return;
        if (w == vdWidth && h == vdHeight) return;
        if (resizeSuspended || syncing) return;   // 拖拽中,松手后统一做一次
        if (view == null) return;

        // 防抖:卡片高度由 weight 分配,一次布局里会连着变好几档
        // (实测桌面刚起来时 359 → 803 → 435),每档都重建一次虚拟屏的话,
        // 里面的 app 要跟着重启三回,还会留下一串没回收的虚拟屏。
        // 等尺寸真正稳定下来再做一次。
        ViewGroup.LayoutParams lp0 = view.getLayoutParams();
        int pw = view.getParent() instanceof View ? ((View) view.getParent()).getWidth() : -1;
        Log.i(TAG, "尺寸回调 " + w + "x" + h + " (lp.width=" + (lp0 == null ? -1 : lp0.width)
                + " parentW=" + pw + " frozenW=" + frozenW + " suspended=" + resizeSuspended + ")");

        pendingW = w;
        pendingH = h;
        view.removeCallbacks(syncTask);
        view.postDelayed(syncTask, SYNC_DEBOUNCE_MS);
    }

    private void doSync() {
        if (!running || view == null) return;
        int w = pendingW, h = pendingH;
        if (w <= 0 || h <= 0 || (w == vdWidth && h == vdHeight)) return;
        if (resizeSuspended || syncing) return;
        if (tooSmall(w, h)) {
            // 布局切换(卡片顶掉网格等)过程中会冒出中间尺寸,实测抓到过 31x889。
            // 拿它去重建虚拟屏,app 就在 31px 宽的屏上排版,贴回卡片是一片撕裂的条纹,
            // 而且之后未必还有回调把它纠正回来——看着就是"画中画卡住了"。
            Log.w(TAG, "忽略过小的尺寸 " + w + "x" + h + ",等布局稳定");
            return;
        }
        SurfaceTexture s = view.getSurfaceTexture();
        if (s == null) return;

        // 先试 resize:虚拟屏变尺寸但**不销毁**,里面的 app 不重启、不断播,只收到一次
        // configuration change。实测两个目标 app 都能正确重新布局(见 syncSize 的说明)。
        if (PREFER_RESIZE && host.resize(s, w, h, dpi())) {
            vdWidth = w;
            vdHeight = h;
            settleUntil = android.os.SystemClock.uptimeMillis() + SETTLE_MS;   // 同上
            Log.i(TAG, "卡片尺寸变了 " + vdWidth + "x" + vdHeight + " → " + w + "x" + h
                    + ",走 resize(app 不重启)");
            return;
        }

        Log.i(TAG, "卡片尺寸变了 " + vdWidth + "x" + vdHeight + " → " + w + "x" + h + ",重建虚拟屏");
        // 重建期间画面是旧的/空的,继续盖着占位,等新画面的第一帧到了再撤
        setBusy(true);
        if (view != null) view.postDelayed(clearBusy, BUSY_MAX_MS);
        syncing = true;
        try {
            stopHostQuietly();
            launch(s, w, h);
        } finally {
            syncing = false;
        }
    }

    /**
     * 只释放虚拟屏,不碰卡片可见性、不通知状态——重建专用。
     *
     * 不能直接用 stop():它会把 TextureView 设成 GONE,而这会引发一次布局变化,
     * 布局变化又回调 onSurfaceTextureSizeChanged、再次触发重建,自激成死循环
     * (实测虚拟屏在 841→506→1347→841 之间无限重建,displayId 一路涨)。
     * 顺带也避免了自绘卡在重建的一瞬间闪现出来。
     */
    private void stopHostQuietly() {
        running = false;
        host.stop();
        if (view != null) view.setOnTouchListener(null);
    }

    private int dpi() {
        return ctx.getResources().getDisplayMetrics().densityDpi;
    }

    /**
     * 尺寸变化优先走 resize(app 不重启)。
     *
     * 留成开关是给「万一遇到不认 configuration change 的 app」备的退路——那种 app 会仍按
     * 旧尺寸绘制、画面居中留黑边,只能退回重建。目前实测的两个(百度地图、QQ音乐车机版)都认。
     */
    private static final boolean PREFER_RESIZE = true;

    /** 小于这个尺寸的"屏"没有意义,一定是布局还没落定时的中间值。 */
    private static final int MIN_VD_SIDE = 240;

    private boolean tooSmall(int w, int h) {
        return w < MIN_VD_SIDE || h < MIN_VD_SIDE;
    }

    private void launch(SurfaceTexture s, int w, int h) {
        if (running || w <= 0 || h <= 0) return;
        if (tooSmall(w, h)) {                 // 首次创建同样别用中间尺寸
            Log.w(TAG, "尺寸还没落定(" + w + "x" + h + "),先不建虚拟屏");
            return;
        }
        int dpi = dpi();
        final String target = pkg;
        running = host.start(s, w, h, dpi, target);
        vdWidth = w;
        vdHeight = h;

        if (running) {
            host.attachTouch(view);          // 点击/滑动直接落到里面的 app
            Log.i(TAG, "画中画已就位 " + target + " display=" + host.displayId());
            launchByUser = userInitiated;   // 起完就没了的时候还要用它,见 onStillborn
            userInitiated = false;
            notifyState();
            return;
        }

        boolean report = userInitiated;      // 是不是用户刚点开的,决定失败要不要吭声
        userInitiated = false;
        setBusy(false);
        // 起不来就别占着卡片,收回空态
        Log.w(TAG, "画中画启动失败,卡位收回空态");
        view.setVisibility(View.GONE);
        // 这个 app 在虚拟屏里跑不起来,别让它下次开桌面再试一遍
        prefs.edit().remove(KEY_TARGET).apply();
        pkg = null;          // 卡位重新算空的,否则 occupied() 会一直把空卡片撑在那儿
        notifyState();
        if (report && listener != null) listener.onPipLaunchFailed(target);
    }


    private void notifyState() {
        if (listener != null) listener.onPipState(running);
    }

    /**
     * 卡位里的 app 要去主屏全屏:**把它整个搬过去**,别杀了重开。
     *
     * 早先这里只是 stop() —— 虚拟屏一 release,DESTROY_CONTENT_ON_REMOVAL 把 task
     * 也销毁了,调用方再冷启动一次。功能上对,手感上不对:地图重新定位、音乐从头再来,
     * 一次"放大"变成一次重启。现在先 {@link PipHost#moveToPrimary} 把 task 换到主屏,
     * activity 实例原样保留,再释放这块已经空了的虚拟屏。
     *
     * 收尾一律走 {@link #close()} 而不是 stop():
     * 搬成了,app 已经在主屏自己站着,卡位这儿不该再留个占着位置的空壳,
     * 下次开桌面更不该把它从全屏拽回卡片里(那正是 KEY_TARGET 会干的事);
     * 搬不动,也得让 activity 跟着虚拟屏一起死 —— 不然它还活在虚拟屏上,
     * 调用方的启动 intent 会被派发给那个实例(`Activity not started, intent has been
     * delivered to currently running top-most instance`),表现为点了图标主屏毫无反应。
     *
     * @return true=已经在主屏前台了,调用方**不用再启动**;
     *         false=没托着它、或者没搬动,调用方照旧全屏打开
     */
    public boolean releaseForFullscreen(String target) {
        if (!isHosting(target)) return false;
        boolean moved = host.moveToPrimary(target);
        Log.i(TAG, moved ? "画中画 → 全屏,已原样搬过去: " + target
                         : "搬不动,退回收掉画中画再重开: " + target);
        close();
        return moved;
    }

    public void stop() {
        if (!running) return;
        running = false;
        setBusy(false);          // 卡片都收了,占位层别留着
        host.stop();
        if (view != null) {
            view.removeCallbacks(syncTask);   // 别让已经作废的尺寸同步再把它拉起来
            view.setOnTouchListener(null);
            view.setVisibility(View.GONE);
        }
        notifyState();
    }
}
