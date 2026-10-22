package com.liubai.blank.util;

import android.app.Activity;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

/**
 * 「设为默认桌面」的查询与跳转。
 *
 * 跳转要三级兜底:各家车机对 RoleManager 的支持参差不齐,DiLink 上
 * ROLE_HOME 有时报 available 却拉不起选择器,所以后面还得跟着系统桌面设置页、
 * 以及最后那招「发一个 CATEGORY_HOME intent 让系统自己弹选择器」。
 */
public final class DefaultHome {

    private DefaultHome() {}

    /**
     * 打开 HOME 入口组件({@code HomeActivity})。**幂等**,重复调没有代价。
     *
     * <h3>为什么它在 manifest 里是关的</h3>
     * 车机的 {@code PackageInstaller} 拒装「桌面类应用」,弹「不支持安装桌面类应用」。
     * 判据是从车机自己的 {@code PackageInstallerActivity.smali} 读出来的
     * (2026-09-06,车友用 ES 文件管理器装不上引出来的排查):
     * <pre>
     * for (activity : pkg.activities)
     *     for (filter : activity.intents)
     *         if (filter.hasCategory("android.intent.category.HOME")
     *             &amp;&amp; activity.info.enabled &amp;&amp; activity.info.exported)
     *             IsHomeAPK = true;          // → 发 LAUNCHER_INSTALLER intent,弹框拒装
     * </pre>
     * 读的是 <b>manifest 里的静态 enabled/exported</b>,所以声明成 {@code enabled="false"}
     * 就躲得过,装完再由 app 自己打开。<b>迪粉和 DiStyle 都是这么干的</b>
     * (迪粉 SplashActivity / DiStyle MainActivity 都是 enabled=false,另配一个只有
     * LAUNCHER 的常亮入口)。留白照抄:{@code EntryActivity} 是那个常亮入口。
     *
     * <h3>三个调用点缺一不可</h3>
     * <ul>
     *   <li>{@code EntryActivity}:新装后的唯一入口,用户点图标 → 开 HOME → 进桌面。</li>
     *   <li>{@code MY_PACKAGE_REPLACED}:覆盖安装会把组件状态重置回 manifest 的
     *       enabled=false,不补这一下,<b>升级完桌面就没了</b>(留白没有开机自启,
     *       HOME 一关系统再也不会拉起它 —— 死锁,只能靠用户手点图标)。</li>
     *   <li>{@code BlankApp.onCreate}:兜底,app 因任何理由跑起来都顺手自愈。</li>
     * </ul>
     */
    public static void enableHomeEntry(Context ctx) {
        try {
            PackageManager pm = ctx.getPackageManager();
            ComponentName cn = new ComponentName(ctx, "com.liubai.blank.HomeActivity");
            if (pm.getComponentEnabledSetting(cn)
                    == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return;
            // DONT_KILL_APP:不加这个标志,改自己的组件状态会把自己的进程杀掉
            pm.setComponentEnabledSetting(cn,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
            Log.i("Blank/Home", "HOME 入口已启用");
        } catch (Exception e) {
            Log.w("Blank/Home", "启用 HOME 入口失败: " + e);
        }
    }

    /** 当前 app 是不是系统的默认桌面。 */
    public static boolean isDefault(Context ctx) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_HOME);
        ResolveInfo res = ctx.getPackageManager()
                .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
        return res != null && res.activityInfo != null
                && ctx.getPackageName().equals(res.activityInfo.packageName);
    }

    /**
     * 请求设为默认桌面。走 RoleManager 那条时会用 requestCode 回调到
     * onActivityResult;后两条兜底路径不回调,靠 onResume 刷新状态。
     */
    public static void request(Activity act, int requestCode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager rm = (RoleManager) act.getSystemService(Context.ROLE_SERVICE);
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)
                    && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                try {
                    act.startActivityForResult(
                            rm.createRequestRoleIntent(RoleManager.ROLE_HOME), requestCode);
                    return;
                } catch (Exception ignored) { }
            }
        }
        try {
            act.startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
            return;
        } catch (Exception ignored) { }
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(home);
        } catch (Exception e) {
            Launchers.toast(act, "无法打开默认桌面设置");
        }
    }
}
