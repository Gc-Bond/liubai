package com.liubai.blank.privileged;

import android.text.TextUtils;

/**
 * 以提权身份执行命令的统一入口,上层只认这一个。
 *
 * 底下有两条通道,优先级从高到低:
 *   1. 内置自连 adb(SelfAdbPrivileged)——车主只装留白一个包,首选
 *   2. Shizuku(ShizukuShell)——过渡期兜底,装了 Shizuku app 的仍能用
 * 换通道不影响调用方,这正是 Privileged 抽象层存在的意义。
 *
 * 关键前提没变:光「拿到授权」没用,调用还得真的走提权通道。
 * ctx.startActivity() 之类是 app 自己的进程在调,身份还是 uid 10xxx,系统照样拒。
 */
public final class Shell {

    /** 一条命令的默认执行上限。am/input 这类都是毫秒级返回,给到 10 秒足够宽松。 */
    private static final long DEFAULT_TIMEOUT_MS = 10_000;

    private Shell() {}

    /** 一次执行的结果。exit<0 表示压根没跑起来(没提权/通道断了)。 */
    public static final class Result {
        public final int exit;
        public final String out;

        public Result(int exit, String out) {
            this.exit = exit;
            this.out = out;
        }

        public boolean ok() { return exit == 0; }

        @Override public String toString() {
            return "exit=" + exit + (TextUtils.isEmpty(out) ? "" : " " + out.trim());
        }
    }

    /** 任一条通道可用即可。 */
    public static boolean ready() {
        return selfAdbReady() || ShizukuShell.ready();
    }

    /** 当前走的是哪条通道,给设置页/探测页显示。 */
    public static String channel() {
        if (selfAdbReady()) return "内置提权";
        if (ShizukuShell.ready()) return "Shizuku";
        return "未就绪";
    }

    /**
     * 任一条通道就绪时回调(已就绪则立即执行)。
     *
     * 必须有这个:两条通道都是**异步**连上的,app 冷启动时界面都搭完了它们还没就位。
     * 这时用 ready() 判一次就下结论,会把「还没连上」当成「没提权」而永久降级。
     * 回调可能被触发多次(两条通道先后就绪),调用方要自己做幂等。
     */
    public static void onReady(Runnable action) {
        SelfAdbPrivileged.get().onReady(action);
        ShizukuPrivileged.get().onReady(action);
    }

    public static Result exec(String cmd) {
        return exec(cmd, DEFAULT_TIMEOUT_MS);
    }

    public static Result exec(String cmd, long timeoutMs) {
        if (selfAdbReady()) return SelfAdbPrivileged.get().exec(cmd, timeoutMs);
        if (ShizukuShell.ready()) return ShizukuShell.exec(cmd);
        return new Result(-1, "提权未就绪");
    }

    private static boolean selfAdbReady() {
        return SelfAdbPrivileged.get().state(null) == Privileged.State.READY;
    }
}
