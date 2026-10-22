package com.liubai.blank.privileged;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * 只干一件事:告诉别人**前台是哪个 app**。
 *
 * 为什么非要一个无障碍服务:Dock 常驻之后要按前台包名决定显不显示(切到原厂桌面就该让位),
 * 而普通 app 拿不到别人的前台信息 —— getRunningTasks 从 Android 5 起只返回自己的。
 * 项目里已有的 {@link Tasks#topOnMain} 走提权 shell 跑 `am stack list` 能拿到,但那是
 * **主动查询**:做实时黑名单要 500ms 级轮询,每次还得走一趟提权通道,功耗和延迟都不划算。
 * 无障碍是事件驱动的,前台一变就回调,零轮询。
 *
 * 刻意只订阅窗口状态变化、不取窗口内容:这个服务能看到的东西越少越好。
 * 它还顺带给 {@link com.liubai.blank.ui.DockHost} 提供一个更高层级的窗口 Context
 * (TYPE_ACCESSIBILITY_OVERLAY 要用它的 WindowManager,见 DockHost.params)。
 */
public final class ForegroundWatch extends AccessibilityService {

    private static final String TAG = "Blank/Fg";

    public interface Listener {
        /** 前台 app 换了。主线程回调。 */
        void onForegroundChanged(String pkg);
    }

    private static volatile ForegroundWatch instance;
    private static volatile String foreground;
    /** 不止一家要订阅(Dock 让位、全局沉浸式跟前台走),用列表代替单个字段。 */
    private static final java.util.concurrent.CopyOnWriteArrayList<Listener> listeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** 服务在不在。不在时上层要退回"只按自己的生命周期判断"。 */
    public static boolean running() {
        return instance != null;
    }

    /**
     * 这个服务在无障碍列表里的组件名。
     *
     * 包名要现取:debug 包带 .debug 后缀,写死会拼出一个不存在的组件 ——
     * 写进那份列表既不报错也永远绑不上。
     */
    public static String component(Context ctx) {
        return ctx.getPackageName() + "/" + ForegroundWatch.class.getName();
    }

    /** 拿它的 Context 建悬浮窗,层级能到 TYPE_ACCESSIBILITY_OVERLAY。没启用就返回 null。 */
    public static AccessibilityService get() {
        return instance;
    }

    public static String foreground() {
        return foreground;
    }

    public static void addListener(Listener l) {
        listeners.add(l);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        CharSequence p = event.getPackageName();
        if (p == null) return;
        String pkg = p.toString();
        // 输入法、音量条这类浮层也会报窗口变化,把它们当"前台 app"会让 Dock 乱闪
        if (TextUtils.isEmpty(pkg) || skip(pkg)) return;
        if (pkg.equals(foreground)) return;

        foreground = pkg;
        Log.i(TAG, "前台 → " + pkg);
        for (Listener l : listeners) l.onForegroundChanged(pkg);
    }

    /** 这些不算"用户切过去的 app",与 {@link Tasks} 的 SKIP 同一套理由。 */
    private static boolean skip(String pkg) {
        return pkg.startsWith("com.android.systemui")
                || pkg.contains("inputmethod")
                || pkg.endsWith("permissioncontroller");
    }

    @Override public void onInterrupt() { }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "前台监听已连接");
        // Dock 悬浮窗挂的是**这个服务的 Context**(2032 层)。服务重启时系统会把它一并收走,
        // 不在这里重挂一次,Dock 就永久消失了 —— 详见 DockHost.onAccessibilityChanged
        reattachDock();
        primeForeground();
    }

    /**
     * 补一次初始值。
     *
     * 无障碍只推**变化**,服务刚连上(冷启动、进程被杀后重拉)时它不知道现在前台是谁,
     * foreground 会一直是 null 直到用户下次切 app。要是这期间人正停在原厂桌面上,
     * Dock 就该让位却没让 —— 所以开局主动问一次。
     *
     * 问的路子是提权 shell 跑 `am stack list`(见 Tasks),阻塞 IO,不能占主线程。
     * 提权没就绪就算了:那种情况下 Dock 的其它降级路径本来也在生效。
     */
    private void primeForeground() {
        if (foreground != null || !Shell.ready()) return;
        new Thread(() -> {
            String pkg = Tasks.topOnMain(this, getPackageName());
            if (TextUtils.isEmpty(pkg) || skip(pkg)) return;
            foreground = pkg;
            Log.i(TAG, "初始前台 → " + pkg);
            for (Listener l : listeners) l.onForegroundChanged(pkg);
        }, "blank-fg-prime").start();
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        Log.i(TAG, "前台监听已断开");
        // instance 已经置 null,这一步会把 Dock 降级挂回 2038 层(要 SYSTEM_ALERT_WINDOW);
        // 挂不上也不要紧,DockHost 会如实退回"Dock 只在桌面里"的老路子 —— 总好过整个消失
        reattachDock();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        reattachDock();
        super.onDestroy();
    }

    /**
     * 两条常驻悬浮窗都挂在这个服务的 Context 上,服务一换就得跟着重挂。
     * 见 {@link com.liubai.blank.ui.DockHost#onAccessibilityChanged}。
     *
     * 顶栏单独 try 一次:它挂不上不该连累 Dock —— Dock 是能操作的那条,更要紧。
     */
    private void reattachDock() {
        try {
            com.liubai.blank.ui.DockHost.get(this).onAccessibilityChanged();
        } catch (Exception e) {
            Log.w(TAG, "重挂 Dock 失败", e);
        }
        try {
            com.liubai.blank.ui.StatusBarHost.get(this).onAccessibilityChanged();
        } catch (Exception e) {
            Log.w(TAG, "重挂顶栏失败", e);
        }
    }
}
