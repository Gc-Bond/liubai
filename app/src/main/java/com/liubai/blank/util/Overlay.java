package com.liubai.blank.util;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.view.WindowManager;

import com.liubai.blank.privileged.ForegroundWatch;
import com.liubai.blank.privileged.Shell;

/**
 * 悬浮窗权限(SYSTEM_ALERT_WINDOW)。Dock 要常驻在别的 app 之上,只有这条路。
 *
 * 它是「特殊权限」,不能用 pm grant —— 那条只管普通运行时权限,对它无效且不报错,
 * 看起来像授权成功、实际 canDraw 仍是 false。正确的姿势是改 appops:
 *     appops set <pkg> SYSTEM_ALERT_WINDOW allow
 * 提权通道就绪时自动执行,车主不需要知道有这么个开关;没提权才退回引导手点系统页。
 *
 * 窗口类型也在这里定:API 26 起是 TYPE_APPLICATION_OVERLAY,更早只能用 TYPE_PHONE。
 * 目标车机是 API 29 走前者,minSdk 24 的老车型靠后者兜底。
 */
public final class Overlay {

    private static final String TAG = "Blank/Overlay";

    private Overlay() {}

    /** 现在能不能画悬浮窗。API 23 以下不存在这个开关,一律算能。 */
    public static boolean canDraw(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        return Settings.canDrawOverlays(ctx);
    }

    /**
     * 用提权通道把权限授给自己。已经有了就直接返回 true,不白跑一趟 shell。
     *
     * 注意包名要现取:debug 包带 .debug 后缀,写死会授给一个不存在的包 ——
     * appops 对不存在的包不报错,只是静默无效。
     */
    public static boolean grantViaPrivileged(Context ctx) {
        if (canDraw(ctx)) return true;
        if (!Shell.ready()) return false;

        String pkg = ctx.getPackageName();
        Shell.Result r = Shell.exec("appops set " + pkg + " SYSTEM_ALERT_WINDOW allow");
        boolean ok = canDraw(ctx);
        Log.i(TAG, "appops 授权 " + pkg + ": " + r + " -> canDraw=" + ok);
        return ok;
    }

    /**
     * 用提权通道把前台监听(无障碍)打开,车主不用去翻系统设置。
     *
     * 无障碍的开关就是两条 secure setting:已启用服务的列表 + 总开关。改它需要
     * WRITE_SECURE_SETTINGS,普通 app 拿不到,但 shell 身份可以 —— 提权通道本来就在。
     * DiStyle 那边这一步是要用户自己去点的,我们能做得更省事。
     *
     * **追加而不是覆盖**:那份列表是全局的,直接写会把用户已经开着的别家无障碍
     * (读屏、按键映射)全部关掉。
     */
    public static boolean enableAccessibilityViaPrivileged(Context ctx, String serviceComponent) {
        if (!Shell.ready()) return false;

        String list = readServiceList(ctx);
        if (list == null) return false;              // 读回来的不可信,宁可不开(见 readServiceList)

        if (hasComponent(list, serviceComponent)) {
            Log.i(TAG, "前台监听已在无障碍列表里");
        } else {
            String merged = list.isEmpty() ? serviceComponent : list + ":" + serviceComponent;
            if (!writeServiceList(ctx, merged)) return false;
        }
        Shell.Result r = Shell.exec("settings put secure accessibility_enabled 1");
        Log.i(TAG, "开启无障碍: " + r);
        return r.ok();
    }

    /**
     * 读那份**全车共享**的已启用无障碍服务列表,并校验它的形状。
     *
     * **读回来的值不可信,必须验过才能拿去拼接**。2026-08-12 真车上出过一次事故:读回来的值
     * 少了两个字符(`...TouchMonitorService` 和 `com.liubai...` 之间的 `:c` 没了,提权通道
     * 哪一步吃的还没定位),于是:
     *   ① 判"自己在不在名单里"时对不上(缺个开头的 c)→ 以为没加过
     *   ② 把这个残缺串当成有效前缀,又追加了一份自己 → 写回去
     * 结果 BYD 的驾驶员监测(DMS)和留白粘成一个非法组件名,**DMS 直接没绑上** —— 那是安全件。
     *
     * 所以这里宁可什么都不做:开不了无障碍只是少个功能,写坏这份列表是把别人家的服务弄没了。
     *
     * @return 合法的列表(可能是空串,表示一个都没启用);读不到或形状不对返回 null
     */
    private static String readServiceList(Context ctx) {
        // **读不走提权通道**。那条通道的输出会丢字节(就是下面这段注释里说的那个坑,
        // 至今没定位到哪一步吃的)—— 而这份列表是全车共享的,读残一个字节就会误判
        // "自己不在名单里",然后把残缺串当前缀再追加一份自己,把别家服务粘成非法组件名。
        //
        // 读 secure settings **本来就不需要任何权限**,只有写才需要。绕开那条不可靠的
        // 通道之后,形状校验才真的在校验系统里的值,而不是在校验"我们有没有读全"。
        //
        // 2026-08-23 实测:那会儿的自愈一直没生效,就是卡在这儿 —— 读回来形状不对
        // → 按"宁可什么都不做"直接放弃,于是无障碍一直停在"列表里有、但从没连上"
        // 的状态(dumpsys 的 Binding services 里躺着它)。
        String raw = Settings.Secure.getString(
                ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        String list = raw == null ? "" : raw.trim();
        if ("null".equals(list)) return "";
        if (list.isEmpty()) return "";
        for (String p : list.split(":")) {
            if (p.isEmpty()) continue;
            // 每一项都得是 pkg/cls。粘连坏掉的那种(两个组件名连在一起)会有两个斜杠,一样落这儿
            int slash = p.indexOf('/');
            if (slash <= 0 || slash == p.length() - 1 || p.indexOf('/', slash + 1) >= 0) {
                Log.w(TAG, "无障碍列表形状不对,拒绝在它基础上写入: " + list);
                return null;
            }
        }
        return list;
    }

    /** 名单里有没有这一项。**按项比对,不用 contains** —— 残缺串也能被 contains 命中。 */
    private static boolean hasComponent(String list, String component) {
        for (String p : list.split(":")) {
            if (p.equals(component)) return true;
        }
        return false;
    }

    /**
     * 写回列表并**回读确认**。写全局设置这种会影响别家 app 的动作,不确认等于没写。
     *
     * 对不上就把原值恢复回去 —— 半个坏值留在那儿比不写危险得多。
     */
    private static boolean writeServiceList(Context ctx, String value) {
        String before = readServiceList(ctx);
        Shell.exec("settings put secure enabled_accessibility_services '" + value + "'");
        // 回读同样走 Settings.Secure:**写要提权,验不用** —— 拿会丢字节的通道去验
        // "写对了没有",等于用一把不准的尺子量自己
        String raw = Settings.Secure.getString(
                ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        String now = raw == null ? "" : raw.trim();
        if ("null".equals(now)) now = "";
        if (value.equals(now) || ("null".equals(value) && now.isEmpty())) return true;

        Log.e(TAG, "无障碍列表写入后回读对不上!\n  想写: " + value + "\n  实读: " + now);
        if (before != null && !before.isEmpty()) {
            Shell.exec("settings put secure enabled_accessibility_services '" + before + "'");
            Log.w(TAG, "已恢复原值: " + before);
        }
        return false;
    }

    /** 给正常绑定留的时间。真车实测正常 1 秒内就连上,给到 5 秒很宽松。 */
    private static final long QUICK_WAIT_MS = 5_000;
    private static final long BIND_POLL_MS = 500;

    /** 自愈在跑。onResume 会反复触发,不挡住会有好几条线程同时写那份全车共享的列表。 */
    private static volatile boolean healing;

    /**
     * 没连上就在后台把它救回来。**每次回到桌面都该调一次**。
     *
     * 为什么不能只在启动时查一次:服务在任何时候都可能掉线(系统回收、覆盖安装),而
     * {@link ForegroundWatch#onUnbind} 只把两条栏降级挂回 2038,没人再去把服务拉回来。
     * 2026-08-24 真车上就这么坏了 8 小时 —— 一次性检查错过了,就再没有第二次机会,
     * 直到进程重启。迪友把同一个检查放在桌面 onResume({@code f5/m0.y()}),同一个道理。
     *
     * 已经连上时开销就是一个静态字段判断,没连上才起线程。
     */
    public static void healAccessibility(Context ctx, String serviceComponent) {
        if (healing || ForegroundWatch.running() || !Shell.ready()) return;
        healing = true;
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                /*
                 * 先给正常绑定留几秒 —— 绑定进行中的服务本来就还没 running(),不等就动手
                 * 会把正常的那次也毙了。真车实测正常绑定 1 秒内回来(19:20:37 起、38 连上)。
                 */
                for (long w = 0; w < QUICK_WAIT_MS; w += BIND_POLL_MS) {
                    if (ForegroundWatch.running()) return;
                    if (!sleep(BIND_POLL_MS)) return;
                }
                if (ForegroundWatch.running()) return;
                restartSelf(app);
            } finally {
                healing = false;
            }
        }, "blank-acc-heal").start();
    }

    /** 两次自救之间至少隔这么久,防「卡住→重启→又卡住→再重启」把桌面刷成闪烁。 */
    private static final long RESTART_COOLDOWN_MS = 5 * 60 * 1000;
    private static final String PREFS = "blank_accessibility";
    private static final String KEY_RESTARTED_AT = "restarted_at_elapsed";

    /**
     * 重启自己,让系统重新绑一遍。
     *
     * 走提权通道跑 {@code am force-stop} 而不是 {@code Process.killProcess}:必须触发
     * 系统 PackageMonitor 的 {@code onHandleForceStop} 回调,那是**能清掉那张清单的三条
     * 路径之一**(另两条是包更新和卸载),app 自己够得着的只有这条。单纯把进程杀掉不走这个回调。
     *
     * 节流用 elapsedRealtime:开机归零,所以 now &lt; 上次 = 中间冷启动过,记录作废、允许再来。
     * **别按"一次开机一次"节流** —— 车机日常是熄火休眠、上电恢复,uptime 能连着跑一整天不归零,
     * 而进程这期间会重启很多次。2026-08-25 就是这么把自己挡死的,坏了 9.2 小时。
     */
    private static void restartSelf(Context ctx) {
        android.content.SharedPreferences sp =
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long last = sp.getLong(KEY_RESTARTED_AT, -1);
        long now = android.os.SystemClock.elapsedRealtime();
        if (last >= 0 && now >= last && now - last < RESTART_COOLDOWN_MS) {
            Log.w(TAG, "距上次自救不到 " + (RESTART_COOLDOWN_MS / 60000) + " 分钟,这轮先不重启");
            return;
        }
        sp.edit().putLong(KEY_RESTARTED_AT, now).commit();   // 进程马上就没了,不能用 apply
        Log.w(TAG, "重启自己让系统重新绑 —— 跟手动 am force-stop 是同一件事");
        Shell.exec("am force-stop " + ctx.getPackageName());
    }

    /** 等服务真的连上,最多 {@link #QUICK_WAIT_MS}。阻塞,给一键授权用。 */
    public static boolean awaitBound() {
        for (long waited = 0; waited < QUICK_WAIT_MS; waited += BIND_POLL_MS) {
            if (ForegroundWatch.running()) return true;
            if (!sleep(BIND_POLL_MS)) break;
        }
        return ForegroundWatch.running();
    }

    /** 睡一会儿。被打断返回 false —— 调用方该收工了,别接着去写系统设置。 */
    private static boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 没提权时的退路:把用户送到系统的悬浮窗授权页。 */
    public static void requestManually(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + ctx.getPackageName()));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Launchers.start(ctx, i, "无法打开悬浮窗授权页");
    }

    /**
     * 悬浮窗类型。
     *
     * 无障碍服务在线时升到 TYPE_ACCESSIBILITY_OVERLAY(2032):层级比 2038 高一档,
     * 能压住一部分系统 UI 和别家的悬浮窗。DiStyle 就是这么做的(反编译 v2.1.6,
     * ui/floatview/O0.java:73 那行三元)。再往上是系统签名级窗口(来电全屏、权限弹窗),
     * 那是硬天花板,拿不到平台签名就压不住 —— 也不该压。
     *
     * 注意:用 2032 时窗口必须挂在**无障碍服务的 Context** 上,拿 application context
     * 建会被 WindowManager 拒(BadTokenException)。见 DockHost.attach 里的取法。
     */
    public static int windowType(boolean accessibilityOn) {
        if (accessibilityOn && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            return WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        }
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    /**
     * 挂一个悬浮窗。**所有悬浮窗都从这里走**,不要自己 wm.addView。
     *
     * 归口是为了一条很容易漏的规矩:系统栏的显隐认的是**当前焦点窗口**的
     * systemUiVisibility。一个收焦点的悬浮窗成为焦点后,系统就按它那份来算 ——
     * 默认值 0,于是车机自绘的那条导航栏当场冒出来,还把 Dock 顶上去一截
     * (真车实测,空调面板就是这么暴露出来的)。Activity 上的 {@link Immersive#apply}
     * 只管自己的 decorView,管不到别的窗口。
     *
     * 让每个调用方自己记得补一句 setSystemUiVisibility 是靠不住的:漏一个就复发,
     * 而且下次新增窗口的人根本不知道有这条规矩。所以放在挂窗这一步统一做。
     *
     * 只对**收焦点**的窗口套 —— 不收焦点的(Dock、空调气泡)本来就不参与系统栏决策,
     * 少改一处是一处。
     *
     * @return 挂上了没有。挂不上不抛异常:悬浮窗权限随时可能被系统或用户收走,
     *         调用方按返回值退回自己的兜底路径(比如回 Activity 里开)。
     */
    public static boolean add(WindowManager wm, android.view.View view,
                              WindowManager.LayoutParams lp) {
        if (wm == null || view == null || lp == null) return false;
        if ((lp.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0) {
            Immersive.apply(view);
        }
        try {
            wm.addView(view, lp);
            // debug 自检:悬浮窗才是触摸中改树的重灾区(Dock/顶栏/气泡/编辑面板),
            // 挂上就扫 —— attachTreeCheck 内部有 DEBUG 判断和同 root 幂等,release 零成本
            DebugGuard.attachTreeCheck(view, () -> "悬浮窗 type=" + lp.type);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "悬浮窗挂不上: " + e);
            return false;
        }
    }
}
