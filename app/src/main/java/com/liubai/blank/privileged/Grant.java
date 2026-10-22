package com.liubai.blank.privileged;

import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.util.Log;

import com.liubai.blank.HomeActivity;
import com.liubai.blank.media.BlankNotificationListener;
import com.liubai.blank.util.DefaultHome;
import com.liubai.blank.util.Overlay;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 一键授权:把留白要的几项权限用提权通道一次性授完。
 *
 * <h3>为什么要有这么个东西</h3>
 *
 * 这几项各有各的系统页面,挨个去点要翻五个地方,而且其中两项(无障碍、通知使用权)
 * 在车机的系统设置里藏得很深。桌面上原来挂着一条"留白还不是默认桌面 · 点此设为默认"
 * 的提示丸 —— 2026-08-18 删掉了:在壁纸上常驻一条自我推销的提示不是解决办法,
 * 把该授的一次点完才是。
 *
 * <h3>一键的边界:通道自己不能一键</h3>
 *
 * 所有项都靠 shell 身份写系统设置({@link Shell},内置自连 adb 优先、Shizuku 兜底),
 * 而**通道本身授不了自己** —— 没有通道时这里一项也做不了,只能如实报"提权未就绪"。
 * 同平台的 DiStyle 也是这个形状(反编译 v2.1.6 {@code com/lmy/desktop/utils/D.java}:
 * 一串 {@code pm grant} / {@code appops set} / {@code settings put secure} 常量,
 * 靠 {@code f6/C2593b}「Lmy-ADBCmd」那条自连 adb 通道执行),它还额外做了一件事 ——
 * 带一个"自动打开系统 ADB 开关"的开关,给通道自己铺路。
 *
 * <h3>写全局设置的那两项要小心</h3>
 *
 * 无障碍列表和通知监听列表都是**全车共享**的,直接覆盖会把用户开着的别家服务弄没
 * (读屏、按键映射)。所以:
 * <ul>
 *   <li>无障碍走 {@link Overlay#enableAccessibilityViaPrivileged} —— 它自己会读-追加-回读校验;</li>
 *   <li>通知使用权走 {@code cmd notification allow_listener},**不写 settings** ——
 *       写那条 setting 回读是对的、但服务压根不会被绑定(踩过)。</li>
 * </ul>
 */
public final class Grant {

    private static final String TAG = "Blank/Grant";

    /** 自己一条线:每项都要发命令+回读,整趟几百毫秒,不能占主线程。 */
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "blank-grant");
        t.setDaemon(true);
        return t;
    });

    private Grant() {}

    /** 一项要授的东西。顺序就是执行顺序 —— 悬浮窗排头,它决定屏幕上有没有那两条栏。 */
    public enum Item {
        OVERLAY("悬浮窗", "常驻 Dock 和顶栏靠它浮在所有应用之上"),
        ACCESSIBILITY("前台监听", "判前台应用:让位名单、Dock 层级都要它"),
        DEFAULT_HOME("默认桌面", "按 HOME 回到留白"),
        NOTIFICATION("通知使用权", "读歌名和封面"),
        STORAGE("读存储", "「我的壁纸」要读你自己拷进来的文件");

        public final String title;
        public final String why;

        Item(String title, String why) {
            this.title = title;
            this.why = why;
        }
    }

    /** 进度回调。全在主线程之外跑,UI 自己 post 回去。 */
    public interface Progress {
        /** 一项做完了。 */
        void onItem(Item item, boolean ok);
        /** 全部做完。{@code missing} 是仍然没拿到的那几项(空 = 齐了)。 */
        void onDone(List<Item> missing);
    }

    /** 这一项现在到底有没有。**一律现查系统,不缓存** —— 用户可能刚在系统设置里改过。 */
    public static boolean granted(Context ctx, Item item) {
        switch (item) {
            case OVERLAY:
                return Overlay.canDraw(ctx);
            case ACCESSIBILITY:
                return ForegroundWatch.running();
            case DEFAULT_HOME:
                return DefaultHome.isDefault(ctx);
            case NOTIFICATION:
                return notificationAllowed(ctx);
            case STORAGE:
                return ctx.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                        == PackageManager.PERMISSION_GRANTED;
        }
        return false;
    }

    /** 还缺哪几项。 */
    public static List<Item> missing(Context ctx) {
        List<Item> out = new ArrayList<>();
        for (Item i : Item.values()) {
            if (!granted(ctx, i)) out.add(i);
        }
        return out;
    }

    /**
     * 一键:把缺的那几项都授一遍。**在后台线程跑** —— 每一项都是若干条 shell 命令,
     * 加上回读确认,整趟少说几百毫秒。
     *
     * 已经有的项直接跳过(不重复写全局设置),但会照样回调一次 ok=true,
     * 界面上那一行才有反馈。
     */
    public static void all(final Context ctx, final Progress cb) {
        final Context app = ctx.getApplicationContext();
        IO.execute(() -> {
            List<Item> missing = new ArrayList<>();
            for (Item item : Item.values()) {
                boolean ok = granted(app, item) || apply(app, item);
                Log.i(TAG, "授权 " + item.title + " -> " + ok);
                if (!ok) missing.add(item);
                if (cb != null) cb.onItem(item, ok);
            }
            if (cb != null) cb.onDone(missing);
        });
    }

    /** 补授一项,后台跑完回调。 */
    public static void one(Context ctx, final Item item, final Done cb) {
        final Context app = ctx.getApplicationContext();
        IO.execute(() -> {
            boolean ok = granted(app, item) || apply(app, item);
            Log.i(TAG, "补授 " + item.title + " -> " + ok);
            if (cb != null) cb.onDone(ok);
        });
    }

    /** 单项授权的结果回调。 */
    public interface Done {
        void onDone(boolean ok);
    }

    /**
     * 授一项。返回的是**授完之后再查一遍**的结果,不是"命令发出去了"。
     *
     * 没有提权通道时一项也做不了 —— 这时返回 false,让界面如实说,别显示成"已授权"。
     */
    public static boolean apply(Context ctx, Item item) {
        if (!Shell.ready()) {
            Log.i(TAG, "提权未就绪," + item.title + " 授不了");
            return false;
        }
        String pkg = ctx.getPackageName();
        switch (item) {
            case OVERLAY:
                return Overlay.grantViaPrivileged(ctx);

            case ACCESSIBILITY: {
                String comp = ForegroundWatch.component(ctx);
                Overlay.enableAccessibilityViaPrivileged(ctx, comp);
                // 列表里有了不等于服务连上了(覆盖安装后常常不绑),等它真连上再回话。
                // 这里已经在 blank-grant 线程上,阻塞几秒没关系,返回值才是要给界面看的。
                // 没连上就是没连上 —— 写 settings 那套救不回来(2026-08-25 真车五次全失败),
                // 那种情况归 Overlay.healAccessibility 重启进程处理
                return Overlay.awaitBound();
            }

            case DEFAULT_HOME: {
                // **提权版**:cmd package set-home-activity 直接落定,不用弹系统选择器。
                // 它不校验类名(写错也返回成功),所以要靠下面这次现查确认
                Shell.Result r = Shell.exec("cmd package set-home-activity "
                        + pkg + "/" + HomeActivity.class.getName());
                boolean ok = DefaultHome.isDefault(ctx);
                Log.i(TAG, "设默认桌面: " + r + " -> " + ok);
                return ok;
            }

            case NOTIFICATION: {
                // **必须走 cmd,不能写 settings**:写 enabled_notification_listeners
                // 回读是对的,但系统不会去绑定那个 service,读会话照样是空的(踩过)
                String comp = pkg + "/" + BlankNotificationListener.class.getName();
                Shell.Result r = Shell.exec("cmd notification allow_listener " + comp);
                // **要等一下再判**:命令 exit=0 的时候那条 secure setting 还没写到位,
                // 立刻查一定是 false(2026-08-18 平板实测:手动跑完同一条命令再 settings get
                // 是有的,而 app 里紧接着查是 false),于是白报一次"没授上"
                boolean ok = awaitGranted(ctx, Item.NOTIFICATION);
                Log.i(TAG, "通知使用权: " + r + " -> " + ok);
                return ok;
            }

            case STORAGE: {
                Shell.exec("pm grant " + pkg + " "
                        + android.Manifest.permission.READ_EXTERNAL_STORAGE);
                return ctx.checkSelfPermission(
                        android.Manifest.permission.READ_EXTERNAL_STORAGE)
                        == PackageManager.PERMISSION_GRANTED;
            }
        }
        return false;
    }

    /**
     * 写完等它生效:每 100ms 查一次,最多 1 秒。
     *
     * 那几条系统设置是**异步**落地的(命令返回 ≠ 已写入),而且同进程读 Settings 还有一层
     * 本地缓存。所以判成败要"读到为止"而不是写完立刻读 —— 车控那条总线上同一个道理
     * (见 {@code CarCtrl.writeConfirm})。
     */
    private static boolean awaitGranted(Context ctx, Item item) {
        for (int i = 0; i < 10; i++) {
            if (granted(ctx, item)) return true;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return granted(ctx, item);
            }
        }
        return granted(ctx, item);
    }

    /**
     * 通知监听开着没有。
     *
     * 查的是那条全局 setting(系统就是按它决定绑不绑),**按项比对**:这份名单是全车共享的,
     * 用 contains 判会被别家包名里的子串蒙到。
     */
    private static boolean notificationAllowed(Context ctx) {
        String flat = Settings.Secure.getString(
                ctx.getContentResolver(), "enabled_notification_listeners");
        if (flat == null || flat.isEmpty()) return false;
        String pkg = ctx.getPackageName();
        for (String p : flat.split(":")) {
            int slash = p.indexOf('/');
            if (slash > 0 && pkg.equals(p.substring(0, slash))) return true;
        }
        return false;
    }
}
