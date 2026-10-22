package com.liubai.blank.util;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;

/**
 * view 树事务:把"改完 → 重铺"排到下一帧。
 *
 * **为什么要延后**:在触摸事件分发的中途 addView / removeView,而且动的正是手指底下的
 * 那棵子树 —— 这是留白连着崩了好几次的同一个根因:系统随后那趟 `performTraversals`
 * 会撞到子节点数组里的 null,崩在 `ViewGroup.dispatchCollectViewAttributes` 或
 * `resetSubtreeAccessibilityStateChanged`,栈里一帧自己的代码都没有。
 *
 * onClick 也算"事件分发中途"(`performClick` 是在 UP 里调的)。
 *
 * 这里的语义照抄 {@code DeskCardHost.laterOnUi} 原本那段:排到下一帧再动树。
 * 差别只在于多了一个 **token 去重** —— 同一个 token 上已经排着的会被顶掉(latest-wins),
 * 连点几下只跑最后那一次,不再每个都排一遍(见 refreshPickerLater 的旧实现:
 * 连点会 post 好几次重铺,后面几次都是白跑)。
 */
public final class ViewTx {

    private ViewTx() {}

    /** 所有 view 的消息队列其实都在主线程;统一走这一个,去重才能跨调用生效。 */
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /**
     * 排到下一帧执行。同一个 token 上已经排着的会被顶掉(latest-wins),
     * 连点几下只跑最后那一次。
     *
     * **去重走的是上面那个统一 Handler,不是 anchor 自己的** —— 别改成
     * {@code anchor.getHandler()}:那个是 ViewRootImpl 的,**每个窗口一份**
     * (悬浮窗和 Activity 各是各的),而 {@code removeCallbacksAndMessages} 只在
     * 同一个 Handler 内生效,跨窗口的同名 token 就顶不掉了。
     *
     * @param anchor 宿主容器。**只用来判"宿主还在不在"**(为 null 就直接 run,
     *               照抄 laterOnUi 的兜底行为),以及 token 为 null 时借它的
     *               {@code View.post} —— 那条保留了"view 还没 attach 就等 attach 再跑"
     *               的语义,而统一 Handler 没有
     * @param token  去重标识,同一件事用同一个;传 null 表示不去重
     */
    public static void post(View anchor, Object token, Runnable work) {
        if (anchor == null) {
            work.run();
            return;
        }
        if (token == null) {
            anchor.post(work);
            return;
        }
        // 先摘掉同一 token 上还没跑的旧任务,再排新的 —— 这就是 latest-wins
        MAIN.removeCallbacksAndMessages(token);
        MAIN.postAtTime(work, token, SystemClock.uptimeMillis());
    }

    /**
     * 取消某个 token 上还没跑的活儿。**宿主要没了就得调一次**。
     *
     * 上面那个 Handler 是静态的,排进去的 Runnable 基本都捕获着 Activity 或 view ——
     * 宿主销毁时还有活儿排着,就跨着生命周期把它拽住了。这是收口带来的新账:
     * 原来走 {@code View.post},队列跟着 view 一起走,不用管。
     */
    public static void cancel(Object token) {
        if (token == null) return;
        MAIN.removeCallbacksAndMessages(token);
    }
}
