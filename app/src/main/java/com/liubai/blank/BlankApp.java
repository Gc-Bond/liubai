package com.liubai.blank;

import android.app.Application;

import com.liubai.blank.privileged.ForegroundWatch;
import com.liubai.blank.privileged.SelfAdbPrivileged;
import com.liubai.blank.privileged.Shell;
import com.liubai.blank.mode.ModeManager;
import com.liubai.blank.util.Immersive;
import com.liubai.blank.util.Overlay;
import com.liubai.blank.util.DebugGuard;

/**
 * 应用入口:在任何视图 inflate 前初始化模式层单例,把内置提权接上,
 * 并在提权就绪后自动开启前台监听(无障碍)。
 * 后续全局子系统也在此挂载。
 */
public final class BlankApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // 崩溃先落盘再交给系统收尾:真车上崩了直接 pull filesDir/crash/,
        // 不用跟 logcat ring buffer 抢时间(见 DebugGuard)。
        DebugGuard.installCrashDump(this);
        // 挂在最前面:要抓的就是启动这几秒里谁占着主线程(无障碍绑定被它挤掉,
        // 见 Overlay.killSelfToRebind)。debug 包专属,release 整个方法体被裁掉。
        DebugGuard.installMainThreadWatch();
        ModeManager.init(this);
        // 开机就自动连,车主不需要知道「提权」这回事,更不该让他去点什么开发者页面。
        // 整个过程是异步的:连不上(没开无线调试)就一直停在降级状态,不影响桌面正常用。
        SelfAdbPrivileged.get().connect(this);

        // 提权一就绪,顺手把前台监听(无障碍)打开 —— 同样不该让车主去翻系统设置。
        // 没提权就一直不开,Dock 退化成"无条件常驻",黑名单不生效(设置页会说明)。
        String fgService = ForegroundWatch.component(this);
        Shell.onReady(() -> {
            Overlay.enableAccessibilityViaPrivileged(this, fgService);
            // 列表里有它不等于服务连上了。这儿只兜冷启动这一次,**日常靠 HomeActivity.onResume
            // 反复查** —— 服务任何时候都可能掉线,一次性检查错过了就再没机会
            // (2026-08-24 真车上这么坏了 8 小时,见 Overlay.healAccessibility)。
            Overlay.healAccessibility(this, fgService);
        });

        // 擦掉历史遗留的 policy_control。2026-08-16 起留白不再压任何 app 的系统栏 ——
        // 那两条系统栏是顶栏和 Dock 的占位来源(见 Immersive.clearGlobal),
        // 但老设备上还留着上一版写进去的压制,得主动撤一次。
        Shell.onReady(() -> Immersive.clearGlobal(this));

        // 兜底把 HOME 入口打开。它在 manifest 里是关的(躲车机安装器的桌面检查),
        // 正常由 EntryActivity / MY_PACKAGE_REPLACED 打开;这里再补一道,
        // app 因任何理由跑起来都顺手自愈(幂等,见 DefaultHome.enableHomeEntry)。
        com.liubai.blank.util.DefaultHome.enableHomeEntry(this);

        // 把打进 apk 的模拟器核心铺到内部目录(dlopen 只能读内部)。全新安装头一回才真解压,
        // 之后按 installed() 跳过。放后台:全部解出约 110MB,别卡启动主线程(见 CoreStore)。
        com.liubai.blank.game.CoreStore.io().execute(
                () -> new com.liubai.blank.game.CoreStore(this).seedFromAssets());
    }
}
