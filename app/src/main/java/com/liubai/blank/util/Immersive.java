package com.liubai.blank.util;

import android.app.Activity;
import android.content.Context;
import android.util.Log;
import android.view.View;

import com.liubai.blank.privileged.Shell;

/**
 * 沉浸式全屏:压住系统导航栏(返回/HOME/多任务那条)和状态栏,内容用满整屏。
 *
 * **只作用于留白自己的窗口。** 别的 app 那两条系统栏一律不动 —— 它们是留白顶栏和 Dock
 * 的占位来源(见 {@link #clearGlobal})。曾经有过一条 policy_control 全局压制,
 * 2026-08-16 随顶栏一起下线了。
 *
 * 用的是老式 setSystemUiVisibility 而不是 WindowInsetsController——目标车机是
 * Android 10(API 29),后者要 API 30+;这套 flag 组合照抄自 DiStyle(第三方车机
 * 桌面,反编译 v2.1.6)在同一 DiLink4.0 平台上的实现,没有另外发明新写法。
 *
 * 车机自绘的系统导航栏是**每个 Activity 独立生效**的:哪个 Activity 不调用
 * {@link #apply},它的窗口就会露出这条系统栏——所以留白自己的每个 Activity
 * (HomeActivity/AppDrawerActivity)都要在 onResume 和
 * onWindowFocusChanged 里各自调一遍,不是设一次全局生效。
 */
public final class Immersive {

    private Immersive() {}

    private static final String TAG = "Blank/Immersive";

    /**
     * 撤掉留白曾经写过的 {@code policy_control}。
     *
     * <b>2026-08-16:这条全局策略整个下线了。</b>
     *
     * 它原来干的事是 {@code immersive.status=留白,<当前前台包>} —— 把系统**状态栏**
     * 对所有 app 压掉,只留导航栏占位(Dock 盖在导航栏那条上)。那时候屏幕顶上什么都没有,
     * 压掉它纯粹是为了观感干净。
     *
     * 现在顶上有留白自己的常驻顶栏了(见 {@link com.liubai.blank.ui.StatusBarHost}),
     * 于是**状态栏和导航栏走同一套账**:两条系统栏都保持存在、都继续给每个 app 发 inset
     * (app 内容自己缩到安全区里,永远压不到),而它们自己被留白的顶栏和 Dock 盖住 ——
     * 白嫖系统的占位能力,两头对称。压掉状态栏反而有害:app 内容会铺到 y=0,
     * 顶栏就开始啃人家的顶部操作区(地图搜索框、B 站顶部 tab 都在那一条里)。
     *
     * 所以这里只剩"把历史值擦掉"这一件事:{@code policy_control} 是全局持久设置,
     * 不主动写 null,老设备上那条策略会一直留着,压着状态栏不放。
     * 提权没就绪就什么也不做 —— 上层会在 {@link Shell#onReady} 里再调一次。
     *
     * 写 "null" 而不是空串:policy_control 认 null 为"没有策略",
     * 写空串在部分 ROM 上会被当成一条空规则,系统栏反而回不来。
     */
    public static void clearGlobal(Context ctx) {
        if (!Shell.ready() || cleared) return;
        Shell.Result r = Shell.exec("settings put global policy_control null");
        Log.i(TAG, "撤掉全局沉浸式策略 → " + r);
        if (r.ok()) cleared = true;
    }

    /** 擦过一次就够,别每次前台变化都打一趟 shell。 */
    private static volatile boolean cleared;

    /**
     * 留白**自己的窗口**:连导航栏一起藏,铺满物理屏。
     *
     * 这只管留白自己的窗口。**别的 app 的系统栏一律不动**(见 {@link #clearGlobal}):
     * 那两条系统栏留着给人家占位,留白的顶栏和 Dock 盖在它们的位置上。
     *
     * 留白自己不需要那份占位 —— 内容让位靠布局里的 {@code @dimen/dock_space} 和
     * {@code @id/status_bar_space},壁纸则要铺到两条栏底下去,半透明才透得出壁纸而不是黑边。
     *
     * 数值 5894。DiStyle(反编译 v2.1.6,{@code ui/activity/r.java:818})桌面用的是同一个数,
     * 一位不差 —— 同平台上这是被验证过的那一份。
     */
    private static final int FLAGS =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;

    // ---- 原厂状态栏开关(用户级,全局单值) ----

    private static final String PREFS = "immersive";
    private static final String KEY_OEM_STATUS_BAR = "oem_status_bar";

    /**
     * 留白自己的页面上要不要露出原厂状态栏(时间 / PM2.5 / 车内外温度 / 信号那条)。
     * 默认不露,和一直以来一样。
     *
     * 开着时只是**不压状态栏**(去掉 FULLSCREEN 那一位),窗口仍铺满物理屏、导航栏照藏:
     * 原厂状态栏画在顶上 90px,内容靠 {@code @id/status_bar_space} 让位
     * (见 {@link com.liubai.blank.ui.StatusBarHost#applyTopSpace},和留白自己顶栏的让位是同一段)。
     * 别的 app 本来就一直有这条栏,不归这里管。
     */
    public static boolean oemStatusBar(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_OEM_STATUS_BAR, false);
    }

    public static void setOemStatusBar(Context ctx, boolean on) {
        ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_OEM_STATUS_BAR, on).apply();
    }

    /**
     * 不管开关,永远铺满物理屏的页面(游戏)。Activity 实现它就行,它弹的 Dialog 一起认
     * (Dialog 的 context 就是那个 Activity)。游戏画面顶上没有让位,露状态栏只会盖住画面。
     */
    public interface AlwaysFullscreen { }

    private static int flags(Context ctx) {
        if (ctx != null && oemStatusBar(ctx) && !(activityOf(ctx) instanceof AlwaysFullscreen)) {
            return FLAGS & ~View.SYSTEM_UI_FLAG_FULLSCREEN;
        }
        return FLAGS;
    }

    private static Activity activityOf(Context ctx) {
        while (ctx instanceof android.content.ContextWrapper) {
            if (ctx instanceof Activity) return (Activity) ctx;
            ctx = ((android.content.ContextWrapper) ctx).getBaseContext();
        }
        return null;
    }

    /** 建议在 onResume(用 decorView.post 包一层)和 onWindowFocusChanged(hasFocus) 里都调一次。 */
    public static void apply(Activity activity) {
        apply(activity.getWindow().getDecorView());
    }

    /**
     * 窗口底跟着当前模式走。**onCreate 里调一次,换配色时再调一次。**
     *
     * manifest 主题里那个 {@code windowBackground} 是**静态资源**(`@color/bg`,
     * 照装机默认的浅色配色定的 #F1F3F5),ModeManager 够不着 —— 切到深色配色之后,每次 Activity 转场都会先露几帧白底。
     *
     * 它在屏幕上的样子很不像"白底":从别的 app 回桌面时,**Dock 上沿闪过一道白线**
     * (2026-08-23 真车录屏:y=987~997 那条的平均亮度从 35 冲到 183、0.17 秒后淡出;
     * 顶栏和屏幕中部都不闪 —— 那两处分别被顶栏和壁纸盖着,只有 Dock 上沿那几个像素
     * 是"壁纸没铺到、Dock 也没盖住"的一条缝,白底正好从那儿漏出来)。
     * 所以它跟"从哪个 app 回来"无关:应用中心、倒车影像,任何一个都一样。
     *
     * <b>冷启动第一帧管不着</b>:那是系统按 manifest 主题画的 starting window,
     * 代码够不到。好在它和装机默认模式一致,不闪。
     *
     * **半透明窗口不要调**(AppDrawerActivity 用的是 BlankTheme.Translucent,
     * 底色 #73000000 是有意的),盖上不透明底就等于把那层透明度废了。
     */
    public static void windowBg(Activity activity) {
        activity.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                com.liubai.blank.mode.ModeManager.get().bg()));
    }

    /**
     * 给一个**悬浮窗的顶层 View** 套同一套 flag。
     *
     * 系统栏的显隐认的是**当前焦点窗口**的 systemUiVisibility。收焦点的悬浮窗
     * (空调面板就是这种:要滑动调温和按压反馈,所以没加 NOT_FOCUSABLE)一旦成为
     * 焦点,系统就按它那份来算 —— 默认值 0,于是车机自绘的那条导航栏当场冒出来,
     * Dock 还会被它顶上去一截(真车实测)。Activity 上的 apply 管不到别的窗口。
     *
     * 不收焦点的悬浮窗(Dock 自己)不受影响,不必调。
     */
    public static void apply(View windowRoot) {
        if (windowRoot == null) return;
        windowRoot.setSystemUiVisibility(flags(windowRoot.getContext()));
    }

    /**
     * 弹一个 Dialog 而不让系统栏跟着冒出来。**所有 Dialog 都该走这里**,别自己 show()。
     *
     * Dialog 是独立窗口、而且必然收焦点,于是系统按它那份 systemUiVisibility 算 ——
     * 跟收焦点的悬浮窗是同一个坑(见 {@link com.liubai.blank.util.Overlay#add}),
     * 只是它不走 WindowManager.addView,归不进那个口,只能在这儿再开一个。
     *
     * 顺序有讲究:flag 要在 show() **之前**设好,但那时 decorView 还没被系统接管,
     * 设了也会在 show 的瞬间被重置。标准解法是先用 NOT_FOCUSABLE 让它"不带焦点地"
     * 显示出来,这一下不会惊动系统栏;显示完再把 flag 清掉换回可交互 —— 此时窗口
     * 已经带着我们设好的 systemUiVisibility 了。
     */
    public static void show(android.app.Dialog dialog) {
        track(dialog);
        android.view.Window w = dialog.getWindow();
        if (w == null) { dialog.show(); return; }
        w.setFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        apply(w.getDecorView());
        dialog.show();
        w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
    }

    /**
     * 现在还开着的 Dialog。**只在主线程读写**(Dialog 本来就只能在主线程 show/dismiss)。
     *
     * 存弱引用:Dialog 挂在 Activity 上,Activity 死了它也该走,这里不该是那个拦住 GC 的人。
     */
    private static final java.util.List<java.lang.ref.WeakReference<android.app.Dialog>> LIVE
            = new java.util.ArrayList<>();

    private static void track(android.app.Dialog dialog) {
        // 顺手清掉已经关掉/被回收的,免得这张表只增不减
        for (java.util.Iterator<java.lang.ref.WeakReference<android.app.Dialog>> it = LIVE.iterator();
             it.hasNext(); ) {
            android.app.Dialog d = it.next().get();
            if (d == null || !d.isShowing()) it.remove();
        }
        LIVE.add(new java.lang.ref.WeakReference<>(dialog));
    }

    /**
     * 把还开着的 Dialog 全关掉。
     *
     * 给主页键用:按它的意思是"回到干净的桌面",屏幕上不该留着上一程的弹窗
     * (见 {@link com.liubai.blank.ui.DockHost#onGoHome})。**这是所有 Dialog 都必须走
     * {@link #show} 的第二个理由** —— 第一个是系统栏,现在还多了一条"能被统一收掉"。
     */
    public static void dismissAll() {
        for (java.lang.ref.WeakReference<android.app.Dialog> ref : LIVE) {
            android.app.Dialog d = ref.get();
            if (d == null || !d.isShowing()) continue;
            try {
                d.dismiss();
            } catch (Exception ignored) {
                // Activity 已经没了之类,忽略
            }
        }
        LIVE.clear();
    }
}
