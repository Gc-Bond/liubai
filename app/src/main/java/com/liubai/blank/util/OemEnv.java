package com.liubai.blank.util;

import android.content.Context;
import android.util.Log;

import com.liubai.blank.privileged.Tasks;

/**
 * 人现在在不在**原车环境**里。
 *
 * <h3>边界</h3>
 *
 * 留白做的一切只在留白桌面里生效;长按主页键切回原车桌面之后,屏幕上不该再有留白的任何东西。
 * 目前有两个消费者,都是"把自己那条栏收起来":
 *
 * <ul>
 *   <li>{@link com.liubai.blank.ui.DockHost}:Dock 让位(常驻模式下唯一不显示的理由)</li>
 *   <li>{@link com.liubai.blank.ui.StatusBarHost}:顶栏让位,同一条判据</li>
 * </ul>
 *
 * 还有一类"施加给系统的全局设置也要撤掉"的账,曾经由 {@link Immersive} 的 policy_control
 * 承担(真车实测:原车桌面底部那条 Dock 和顶部状态栏都是 SystemUI 的窗口,把它写进
 * {@code immersive.full} 等于把人家的 Dock 和状态栏一起按下去)。2026-08-16 那条压制
 * 整个下线了 —— 留白不再动任何 app 的系统栏,这类账现在是空的。再往系统里写全局设置之前,
 * 先想清楚它在原车环境里怎么撤。
 *
 * <h3>为什么是状态而不是"当前前台是不是原车桌面"</h3>
 *
 * 用户切过去要的是**完整的原车环境**:原车桌面本身、以及从它上面打开的每一个 app。
 * 按前台逐次判的话,从原车桌面点开音乐、前台不再是 home,留白的东西就又冒出来了。
 * 所以:切到别的桌面**置上**,回到留白**解除**,中间开什么 app 都不动它。
 */
public final class OemEnv {

    private static final String TAG = "Blank/OemEnv";

    private static volatile boolean active;

    private OemEnv() {}

    /** 在原车环境里没有。true = 留白该把自己收干净。 */
    public static boolean active() { return active; }

    /**
     * 前台变了(由 {@link com.liubai.blank.privileged.ForegroundWatch} 驱动)。
     *
     * 只认两种前台:切到**别的**桌面 → 进原车环境;回到留白自己 → 出来。其它 app 一概不动。
     */
    public static void onForeground(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        if (pkg.equals(ctx.getPackageName())) {
            set(ctx, false);
        } else if (Tasks.isHome(ctx, pkg)) {
            set(ctx, true);
        }
    }

    /**
     * 留白桌面回到前台。**这条信号不经过无障碍** —— 无障碍没启用时,它是唯一能把状态
     * 解除掉的路(否则一旦进了原车环境就再也出不来)。
     */
    public static void onBlankDesktopResumed(Context ctx) {
        set(ctx, false);
    }

    /**
     * 只翻标志位。顶栏和 Dock 各自听着前台变化去重算显隐,不用在这里推。
     *
     * 这里原来还要把 policy_control 撤一次(进原车环境这一刻就得撤,晚一步用户就看着
     * 一个没有状态栏的原车桌面)。2026-08-16 那条全局压制整个下线了 ——
     * 留白不再动任何 app 的系统栏,自然也没有"进原车环境要还回去"这回事,
     * 见 {@link Immersive#clearGlobal}。
     */
    private static void set(Context ctx, boolean value) {
        if (active == value) return;
        active = value;
        Log.i(TAG, value ? "进入原车环境,留白收起" : "回到留白");
    }
}
