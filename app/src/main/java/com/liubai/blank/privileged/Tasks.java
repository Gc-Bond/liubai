package com.liubai.blank.privileged;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.text.TextUtils;
import android.util.Log;

import com.liubai.blank.HomeActivity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 问系统:主屏上现在还活着哪些用户 app。
 *
 * 用来判断「点第二个 app 时,第一个还在不在」——这是双画中画能不能成立的前提:
 * 有人在跑才谈得上把两个一起收进卡片,没人在跑就该全屏打开。
 *
 * 数据源是 `am stack list`(PipHost.landedOn 也在用它判落点)。为什么不用
 * ActivityManager.getRunningTasks:那个从 Android 5 起就只返回自己的 task 了,
 * 拿别人的任务列表要 REAL_GET_TASKS(signature 级),普通 app 身份必被拒——
 * 而 shell 身份跑 am 命令没有这个限制,提权通道本来就在,顺手用。
 */
public final class Tasks {

    private static final String TAG = "Blank/Tasks";

    /** `am stack list` 里的一行任务:  taskId=418: com.android.settings/com.android.settings.Settings ... */
    private static final Pattern TASK_LINE = Pattern.compile("taskId=\\d+:\\s+([\\w.]+)/([\\w.$]+)");

    /**
     * 这些不算"用户开的 app"。
     * SystemUI 会以各种浮层 activity 的形式冒出来(多窗口提示、音量面板),
     * 把它当成"用户上一个用的 app"收进画中画毫无意义。
     */
    private static final String[] SKIP = {
            "com.android.systemui",
            "com.android.inputmethod",
            "com.google.android.inputmethod",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
    };

    /**
     * 所有桌面类 app 的包名。**它们一律不算"用户开的 app"**。
     *
     * 原来只靠 `mActivityType=home` 跳过桌面就够了 —— 桌面总在 home stack 里。但
     * 「长按主页键切原车桌面」那条路是**显式启动、不带 CATEGORY_HOME** 的
     * (见 Launchers.openOriginalHome:带了会被 launcher3 转发回默认桌面),
     * 于是原厂桌面落在 standard stack 里,被当成普通 app 收进了 Dock 运行区。
     *
     * 缓存住:装/卸 launcher 才会变,而 refreshRunning 是跟着前台变化跑的,
     * 不该每次都去问一遍 PackageManager。
     */
    private static Set<String> homeCache;

    /** 这个包是不是桌面类 app。判据同 homePackages:声明 HOME 且没有应用抽屉入口。 */
    public static boolean isHome(Context ctx, String pkg) {
        return pkg != null && !pkg.isEmpty() && homePackages(ctx).contains(pkg);
    }

    private static Set<String> homePackages(Context ctx) {
        if (homeCache != null) return homeCache;
        Set<String> out = new HashSet<>();
        try {
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            for (ResolveInfo ri : pm.queryIntentActivities(home, 0)) {
                if (ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                // 只把"没有应用抽屉入口"的算桌面。com.android.settings 之类也声明了
                // CATEGORY_HOME,但它是正经 app —— 用户开了设置就该进运行区,
                // 一并排掉就把它从 Dock 上抹掉了。
                if (pm.getLaunchIntentForPackage(pkg) == null) out.add(pkg);
            }
        } catch (Exception ignored) { }
        homeCache = out;
        return out;
    }

    private Tasks() { }

    /**
     * 主屏上除桌面外最靠前的那个用户 app。没有就返回 null。
     *
     * `am stack list` 的输出顺序就是 z 序(前台的在前),所以第一个命中的
     * 就是"用户上一个在用的 app"——哪怕它已经被桌面盖住(visible=false),
     * 只要 task 还活着就算数。用户按 Home 回桌面再点第二个 app,正是这种情形。
     *
     * @param selfPkg 桌面自己的包名,要排除掉
     */
    public static String topOnMain(Context ctx, String selfPkg) {
        List<String> all = onMainDisplay(ctx, selfPkg);
        return all.isEmpty() ? null : all.get(0);
    }

    /** 主屏上还活着的用户 app,按 z 序从前到后。 */
    public static List<String> onMainDisplay(Context ctx, String selfPkg) {
        List<String> out = new ArrayList<>();
        if (!Shell.ready()) return out;
        Set<String> homes = homePackages(ctx);

        Shell.Result r = Shell.exec("am stack list");
        if (r.out == null || r.out.isEmpty()) {
            Log.w(TAG, "am stack list 没有输出: " + r);
            return out;
        }

        // 按 Stack 分段,只看主屏那几段
        for (String seg : r.out.split("Stack id=")) {
            // displayId 后面补个空格,免得 0 匹配上 128 之类
            if (!seg.contains("displayId=0 ")) continue;
            // 系统的 home stack(原厂桌面/兜底 launcher)不是用户开的 app
            if (seg.contains("mActivityType=home")) continue;

            Matcher m = TASK_LINE.matcher(seg);
            while (m.find()) {
                String pkg = m.group(1);
                String cls = m.group(2);
                // 桌面类一律不算:原厂桌面被显式启动时不在 home stack 里,
                // 光靠上面那条 mActivityType=home 拦不住它
                if (skip(pkg, cls, selfPkg) || homes.contains(pkg) || out.contains(pkg)) continue;
                out.add(pkg);
            }
        }
        return out;
    }

    /**
     * 一次问清楚这几个包在不在(任意屏上都算),返回其中还活着的。
     *
     * 和 {@link #alive} 同一个判断,区别只在**一趟 shell 问完所有包** —— 地图和音乐
     * 各问一次就是两趟阻塞 IO,而它俩每次前台变化都要判(Dock 上的运行圆点靠它)。
     */
    public static java.util.Set<String> aliveAmong(java.util.Collection<String> pkgs) {
        java.util.Set<String> out = new java.util.HashSet<>();
        if (pkgs == null || pkgs.isEmpty() || !Shell.ready()) return out;
        Shell.Result r = Shell.exec("am stack list");
        if (r.out == null) return out;
        for (String p : pkgs) {
            if (!TextUtils.isEmpty(p) && r.out.contains(p + "/")) out.add(p);
        }
        return out;
    }

    /** 这个 app 是不是还活着(任意屏上都算)。用于判断上次的画中画该不该恢复。 */
    public static boolean alive(String pkg) {
        if (TextUtils.isEmpty(pkg) || !Shell.ready()) return false;
        Shell.Result r = Shell.exec("am stack list");
        return r.out != null && r.out.contains(pkg + "/");
    }

    /**
     * 桌面自己的主页(HomeActivity)不算"用户开的 app"——但同包名下的其它独立页面
     * (浏览器 BrowserActivity、游戏中心 GameActivity,它们在 AndroidManifest 里
     * 是独立 activity,只是刻意不声明 LAUNCHER)是用户真正在用的东西,
     * 不能因为包名跟桌面一样就被一并当成"回桌面了"。
     * 判据从"包名等于自己"收紧到"这条 task 就是桌面主页那个 Activity"。
     */
    private static boolean skip(String pkg, String cls, String selfPkg) {
        if (TextUtils.isEmpty(pkg)) return true;
        if (selfPkg != null && selfPkg.equals(pkg)) {
            return HomeActivity.class.getName().equals(cls);
        }
        for (String s : SKIP) {
            if (pkg.startsWith(s)) return true;
        }
        return false;
    }
}
