package com.liubai.blank.privileged;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import rikka.shizuku.Shizuku;

/**
 * 基于官方 Shizuku 的提权实现(第一阶段)。
 *
 * Shizuku 通过 ADB 起一个 shell(uid 2000) 权限的进程,普通 app 经 Binder 调它,
 * 从而间接获得 shell 权限——这正是 BYD 私有接口(BYDAUTO_*)和
 * VirtualDisplay 相关特权 API 所需要的身份。
 *
 * 护卫舰07 是 Android 10,没有系统内置的无线调试配对(Android 11+ 才有),
 * 所以激活要么电脑连 ADB,要么走车机自连本机 ADB(第二阶段做成一键)。
 */
public final class ShizukuPrivileged implements Privileged {

    private static final String TAG = "Blank/Priv";
    private static final int REQ_CODE = 9001;

    private static ShizukuPrivileged instance;

    public static synchronized ShizukuPrivileged get() {
        if (instance == null) instance = new ShizukuPrivileged();
        return instance;
    }

    private ShizukuPrivileged() {}

    @Override
    public State state(Context ctx) {
        try {
            if (!Shizuku.pingBinder()) return State.UNAVAILABLE;
            if (Shizuku.isPreV11()) return State.UNAVAILABLE;   // 旧版协议不支持,当不可用
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                    ? State.READY : State.UNAUTHORIZED;
        } catch (Throwable t) {
            // Shizuku 未安装时 binder 相关调用会抛,不能让桌面因此崩
            Log.d(TAG, "shizuku unavailable: " + t);
            return State.UNAVAILABLE;
        }
    }

    @Override
    public void requestPermission(Context ctx) {
        try {
            if (!Shizuku.pingBinder()) return;
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                Log.w(TAG, "用户已拒绝过授权,需去 Shizuku 里手动开");
                return;
            }
            Shizuku.requestPermission(REQ_CODE);
        } catch (Throwable t) {
            Log.w(TAG, "requestPermission failed", t);
        }
    }

    @Override
    public void onReady(Runnable action) {
        try {
            if (Shizuku.pingBinder() && !Shizuku.isPreV11()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                action.run();
                return;
            }
            // Sticky 版:如果 binder 在注册前就已经连上,会立刻补一次回调,不会漏
            Shizuku.addBinderReceivedListenerSticky(() -> {
                try {
                    if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                        Log.i(TAG, "提权已就绪(异步连上)");
                        action.run();
                    }
                } catch (Throwable t) {
                    Log.d(TAG, "binder received but not granted: " + t);
                }
            });
        } catch (Throwable t) {
            Log.d(TAG, "onReady 挂载失败(多半是没装 Shizuku): " + t);
        }
    }

    @Override
    public String describe(Context ctx) {
        switch (state(ctx)) {
            case READY:        return "已授权";
            case UNAUTHORIZED: return "未授权";
            default:           return "未运行";
        }
    }

    /** 供设置页监听状态变化(Shizuku 授权是异步的)。 */
    public void addListener(Shizuku.OnRequestPermissionResultListener l) {
        try { Shizuku.addRequestPermissionResultListener(l); } catch (Throwable ignored) { }
    }

    public void removeListener(Shizuku.OnRequestPermissionResultListener l) {
        try { Shizuku.removeRequestPermissionResultListener(l); } catch (Throwable ignored) { }
    }
}
