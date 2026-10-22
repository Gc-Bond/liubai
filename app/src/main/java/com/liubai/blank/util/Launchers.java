package com.liubai.blank.util;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.util.Log;
import android.util.TypedValue;
import android.widget.Toast;

import com.liubai.blank.model.AppRef;

/**
 * 应用启动与小工具。集中安全启动逻辑,失败弹 toast,不抛异常打断桌面。
 */
public final class Launchers {

    private Launchers() {}

    /** 按组件启动一个 app;组件缺失时退回按包名的 launch intent。 */
    public static void launch(Context ctx, AppRef app) {
        if (app == null) return;
        try {
            if (app.cls != null && !app.cls.isEmpty()) {
                Intent i = new Intent(Intent.ACTION_MAIN);
                i.addCategory(Intent.CATEGORY_LAUNCHER);
                i.setClassName(app.pkg, app.cls);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                ctx.startActivity(i);
                return;
            }
        } catch (Exception ignored) { /* 落到按包名 */ }
        launchPackage(ctx, app.pkg, "无法启动 " + app.label);
    }

    /**
     * 按包名启动,成功与否用返回值告诉调用方 —— 给"这个不行就试下一个"的兜底链用
     * (如导航:高德 → 比亚迪自带 → geo: 通用 intent)。失败不弹 toast,由链尾决定怎么提示。
     */
    public static boolean tryLaunchPackage(Context ctx, String pkg) {
        try {
            Intent i = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    public static void launchPackage(Context ctx, String pkg, String failMsg) {
        if (!tryLaunchPackage(ctx, pkg)) toast(ctx, failMsg);
    }

    /**
     * 这个包装了没有,而且是**能启动的**。
     *
     * 用 getLaunchIntentForPackage 而不是 getPackageInfo:后者对纯服务型包也返回真,
     * 拿去当"可以打开的 app"会得到一个点了没反应的键。
     */
    public static boolean installed(Context ctx, String pkg) {
        try {
            return ctx.getPackageManager().getLaunchIntentForPackage(pkg) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 切到**原车桌面**(长按 Dock 主页键)。
     *
     * 留白把自己设成了默认桌面,原厂那个还在,只是按 HOME 不再去它那儿了。总得留一条
     * 回去的路 —— 车机不少功能(应用商店、账号、OTA 入口)只在原厂桌面上挂着。
     *
     * 用 CATEGORY_HOME 查候选、排掉自己,不写死包名:这台车上是 com.android.launcher3,
     * 换个车型就未必。**不能用 HOME_ONLY 查** —— 实测那个会把 com.byd.systemsettings
     * 也带出来(它声明了这个 category 但不是桌面),取到它就跳去设置页了。
     *
     * 显式带上 component 启动,而不是发一个纯 HOME intent:后者会被系统解析到默认桌面,
     * 也就是留白自己,等于原地不动。
     *
     * @return 找没找到能去的桌面
     */
    public static boolean openOriginalHome(Context ctx) {
        try {
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            // 两轮挑:先要"没有应用抽屉入口"的 —— 那才是纯桌面。声明 CATEGORY_HOME 的
            // 不一定是桌面:模拟器上 com.android.settings 就声明了(它有 9 个 LAUNCHER
            // 入口),真桌面 nexuslauncher 则一个都没有。只取第一个候选的话,顺序不保证,
            // 很可能跳去设置页。车上候选只有 launcher3 和留白,所以这条一直没暴露。
            ResolveInfo pick = null, fallback = null;
            for (ResolveInfo ri : pm.queryIntentActivities(home, 0)) {
                if (ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(ctx.getPackageName())) continue;   // 那是留白自己
                if (fallback == null) fallback = ri;
                if (pm.getLaunchIntentForPackage(pkg) == null) { pick = ri; break; }
            }
            // 一个"纯桌面"都没挑出来就退回第一个候选:某些 ROM 的桌面确实也挂着抽屉入口,
            // 那时宁可去一个可能不对的桌面,也好过什么都不做
            if (pick == null) pick = fallback;
            if (pick != null) {
                // **启动时不带 CATEGORY_HOME**(查询时才用它)。带着的话 BYD 的
                // launcher3 一起来就发现"我不是默认桌面",立刻回发一个带 HOME_ONLY 的
                // HOME intent 转给真正的默认桌面 —— 也就是留白,4ms 就跳回来了(真车实测)。
                // 不带 category 就当普通 Activity 启动,绕开它那段转发判断。
                Intent go = new Intent(Intent.ACTION_MAIN)
                        .setComponent(new ComponentName(
                                pick.activityInfo.packageName, pick.activityInfo.name))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                Log.i("Blank/Launchers", "启动原车桌面: " + go.getComponent());
                ctx.startActivity(go);
                return true;
            }
        } catch (Exception e) {
            Log.w("Blank/Launchers", "切原车桌面失败: " + e);
        }
        toast(ctx, "没找到原车桌面");
        return false;
    }

    public static void start(Context ctx, Intent intent, String failMsg) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
        } catch (Exception e) {
            toast(ctx, failMsg);
        }
    }

    /**
     * 提示。**位置要抬到 Dock 上方** —— 系统默认把 toast 摆在屏幕底部正中,
     * 而 Dock 是层级更高的悬浮窗,正好压在那儿:提示弹了,但一个字看不见
     * (2026-08-16 点「SOC 保电」时发现)。
     *
     * 抬的高度用 {@link #dockSpace}(所有从 Dock 弹出来的浮层共用那条线)再加一点余量,
     * 和空调面板、编辑面板的底边对齐。
     *
     * targetSdk 29,{@code setGravity} 照常生效 —— 30 起系统会忽略文字 toast 的位置设定,
     * 真要升 targetSdk 的话这儿得换成自绘浮层。
     */
    public static void toast(Context ctx, String msg) {
        Toast t = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT);
        t.setGravity(android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL,
                0, dockSpace(ctx) + dp(ctx, 12));
        t.show();
    }

    public static int dp(Context ctx, float dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, ctx.getResources().getDisplayMetrics()));
    }

    /**
     * 中等字重。**标题和分组标题用它,正文一律保持常规** —— 字重是层级的第二根轴,
     * 光靠字号拉开需要更大的档差,而车上一屏本来就不宽裕。
     *
     * 取 medium 不取 bold:鸿蒙自己按钮/开关/标题走的就是 `font_weight 4.0`(medium),
     * bold 只留给页级大标题。深色底上 bold 的中文笔画容易糊成一团,这在车机屏上更明显。
     *
     * **选中态不要用它**:medium 比 regular 宽,一排 chip 里选中的那个一变宽,整排跟着位移 ——
     * 选中靠底色和文字色区分就够了。
     */
    public static void medium(android.widget.TextView tv) {
        tv.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
    }

    /**
     * **从 Dock 弹出来的浮层,底边一律离屏幕底这么多**(px)。
     *
     * 就是 {@code @dimen/dock_space} —— 桌面和设置页都拿它给常驻 Dock 让位,浮层也坐在
     * 这条线上,底边就和桌面卡片的下边界齐平。**这才叫协调**,不只是"间距相等"。
     *
     * 别再各写各的:改之前 HvacPanel 92dp、DockEditPanel 86/104dp、SourcePickPopup 100dp、
     * HvacBubble 相对按键 -4dp —— 五处五个数,谁也对不齐,Dock 高度一改还全错位。
     */
    public static int dockSpace(Context ctx) {
        return ctx.getResources().getDimensionPixelSize(com.liubai.blank.R.dimen.dock_space);
    }
}
