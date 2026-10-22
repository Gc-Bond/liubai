package com.liubai.blank.util;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 覆盖安装后把 HOME 入口重新打开。
 *
 * <p><b>不补这一下,升级完桌面就没了</b>:覆盖安装会按新 manifest 重置组件状态,
 * 而 {@code HomeActivity} 在 manifest 里是 {@code enabled="false"}
 * (为躲车机安装器的桌面检查,见 {@link DefaultHome#enableHomeEntry})。
 * 留白又没有开机自启 —— HOME 一关,系统再不会拉起它,app 也就没机会自己打开,
 * 死锁,只能靠用户手点抽屉图标。{@code MY_PACKAGE_REPLACED} 是唯一能自动打破它的时机。
 */
public class PackageReplacedReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        DefaultHome.enableHomeEntry(ctx);
    }
}
