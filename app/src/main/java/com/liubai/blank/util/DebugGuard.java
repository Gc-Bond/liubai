package com.liubai.blank.util;

import android.content.Context;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import com.liubai.blank.BuildConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/**
 * debug 包常驻自检 + 全局崩溃落盘。
 *
 * 两个功能都只服务同一件事:把「改坏 view 树」这类崩溃从"下一帧崩、栈里一帧自己的
 * 代码都没有"变成"改坏那一刻就点名"。
 *
 * <ul>
 *   <li>{@link #attachTreeCheck}:OnPreDrawListener 每帧扫全树,子节点数组里出现 null
 *       当场点名(坏掉的容器 + 祖先链 + 当时状态)。**只在 debug 包编译**,release 里
 *       {@code BuildConfig.DEBUG} 是编译期常量 false,整个方法体直接不进字节码。</li>
 *   <li>{@link #installCrashDump}:全局 UncaughtExceptionHandler,崩溃先落盘到
 *       filesDir/crash/ 再交给系统收尾 —— 真车上崩了直接 pull,不用跟 logcat
 *       ring buffer 抢时间(上次崩溃日志被 /proc/stat 冲掉过)。</li>
 * </ul>
 */
public final class DebugGuard {

    private static final String TAG = "Blank/DebugGuard";

    /** 已挂过树检查的 root。WeakHashMap:root 被回收后条目自动清。 */
    private static final Map<View, Boolean> CHECKED = new WeakHashMap<>();

    private DebugGuard() {}

    // ---- 崩溃落盘 ----

    /**
     * 挂全局崩溃落盘。幂等:装两遍不会叠两层(第二遍直接跳过)。
     *
     * 先写盘、再把崩溃交给原来的默认 handler —— 默认行为(杀进程 + 弹「已停止」)
     * 不能省,落盘只是抢在它前面把现场留下来。
     */
    public static void installCrashDump(Context ctx) {
        Thread.UncaughtExceptionHandler def = Thread.getDefaultUncaughtExceptionHandler();
        if (def instanceof CrashDumpHandler) return;
        Thread.setDefaultUncaughtExceptionHandler(new CrashDumpHandler(def, ctx));
    }

    private static final class CrashDumpHandler implements Thread.UncaughtExceptionHandler {
        private final Thread.UncaughtExceptionHandler next;
        private final Context app;

        CrashDumpHandler(Thread.UncaughtExceptionHandler next, Context ctx) {
            this.next = next;
            this.app = ctx.getApplicationContext();
        }

        @Override public void uncaughtException(Thread thread, Throwable throwable) {
            try {
                dump(thread, throwable);
            } catch (Throwable ignored) {
                // 落盘自己不能崩 —— 它一崩,后面的默认收尾就没了
            }
            if (next != null) next.uncaughtException(thread, throwable);
        }

        private void dump(Thread thread, Throwable throwable) {
            File dir = new File(app.getFilesDir(), "crash");
            if (!dir.exists() && !dir.mkdirs()) return;
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(new Date());
            File out = new File(dir, "crash-" + stamp + ".txt");

            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            pw.println("time    = " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date()));
            pw.println("thread  = " + thread.getName());
            pw.println("model   = " + Build.MODEL);
            pw.println("sdk     = " + Build.VERSION.SDK_INT);
            pw.println("version = " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE
                    + ") debug=" + BuildConfig.DEBUG);
            pw.println();
            throwable.printStackTrace(pw);
            pw.flush();

            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(sw.toString().getBytes(StandardCharsets.UTF_8));
            } catch (java.io.IOException e) {
                // 写不进文件(空间满/权限没了)只打日志,别挡着默认收尾
                Log.e(TAG, "崩溃落盘写入失败 " + out.getAbsolutePath(), e);
            }
            Log.e(TAG, "崩溃已落盘 " + out.getAbsolutePath());
        }
    }

    // ---- debug 自检:view 树扫描 ----

    /**
     * 挂上一帧扫一次全树的 OnPreDrawListener。**只在 debug 包跑**。
     *
     * 扫到子节点数组里的 null(removeView 之后、系统遍历还在用旧 count 的典型后遗症),
     * 当场打出坏掉的容器、它在树里的祖先链,和调用方给的状态描述 —— 改坏那一刻就
     * 点名,而不是等之后某趟 traversal 崩在系统栈里。
     *
     * @param state 当时的状态描述(编辑态?几张卡?候选区开着?),由挂载方拼好传进来
     */
    /** 主线程一条 message 跑多久算"卡"。无障碍绑定就是被这种长 message 挤掉的。 */
    private static final long JANK_MS = 300;

    /**
     * 盯主线程:哪条 message 跑超过 {@link #JANK_MS} 就点名。**只在 debug 包跑**。
     *
     * 为什么要它:2026-08-24 真车上无障碍绑定失败,查到根因是启动时主线程卡了 ~2 秒
     * (Skipped 97 frames / Davey 1968ms),AMS 的 bindService 要在主线程创建 Service、
     * 撞上那个窗口就完不成,之后永久卡在 mBindingServices(见 Overlay.killSelfToRebind)。
     * 但 Choreographer 只说"卡了",不说卡在哪 —— Looper 的 message logging 会把
     * 正在派发的 Handler/callback 打出来,这才定位得到具体是谁。
     *
     * 开销极小:每条 message 两次 println 回调 + 一次减法,不采样不抓栈。
     */
    public static void installMainThreadWatch() {
        if (!BuildConfig.DEBUG) return;
        android.os.Looper.getMainLooper().setMessageLogging(new android.util.Printer() {
            private long start;

            @Override public void println(String x) {
                if (x == null || x.isEmpty()) return;
                // 系统固定这两个前缀:">>>>> Dispatching to …" / "<<<<< Finished to …"
                if (x.charAt(0) == '>') {
                    start = android.os.SystemClock.uptimeMillis();
                    return;
                }
                if (x.charAt(0) != '<' || start == 0) return;
                long cost = android.os.SystemClock.uptimeMillis() - start;
                start = 0;
                if (cost >= JANK_MS) Log.w(TAG, "主线程卡 " + cost + "ms: " + x);
            }
        });
    }

    public static void attachTreeCheck(View root, Supplier<String> state) {
        if (!BuildConfig.DEBUG || root == null) return;
        // 同一个 root 会被反复挂窗(detach→attach,见 HvacBubble 的 final root):
        // 监听只挂一次,不然每帧扫多遍、还泄漏
        synchronized (CHECKED) {
            if (CHECKED.containsKey(root)) return;
            CHECKED.put(root, Boolean.TRUE);
        }
        root.getViewTreeObserver().addOnPreDrawListener(() -> {
            scan(root, state);
            return true;
        });
    }

    private static void scan(View v, Supplier<String> state) {
        if (!(v instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) v;
        int n = g.getChildCount();
        for (int i = 0; i < n; i++) {
            View c = g.getChildAt(i);
            if (c == null) {
                String s = "?";
                if (state != null) {
                    try { s = state.get(); } catch (Throwable t) { s = "状态读取失败: " + t; }
                }
                Log.e(TAG, "【view 树坏了】容器=" + describe(g) + " index=" + i
                        + "/" + n + " 状态=" + s);
                dumpAncestors(g);
                return;   // 坏的是这一处,先把位置报出来;子树再扫也是同一棵坏树
            }
            scan(c, state);
        }
    }

    /** 坏容器到 root 的祖先链,一眼看出它是哪一段。 */
    private static void dumpAncestors(View v) {
        StringBuilder sb = new StringBuilder();
        View cur = v;
        while (cur != null) {
            sb.insert(0, describe(cur) + " ← ");
            cur = (View) cur.getParent();
        }
        Log.e(TAG, "【view 树坏了】容器链: " + sb);
    }

    private static String describe(View v) {
        StringBuilder sb = new StringBuilder();
        sb.append(v.getClass().getSimpleName());
        if (v.getId() != View.NO_ID) {
            try {
                sb.append('#').append(v.getResources().getResourceEntryName(v.getId()));
            } catch (Throwable ignored) {
                sb.append("#0x").append(Integer.toHexString(v.getId()));
            }
        }
        if (v.getTag() != null) sb.append(" tag=").append(v.getTag());
        sb.append('@').append(Integer.toHexString(System.identityHashCode(v)));
        return sb.toString();
    }
}
