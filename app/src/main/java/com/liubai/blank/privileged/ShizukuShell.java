package com.liubai.blank.privileged;

import android.text.TextUtils;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;

/**
 * 以提权身份(shell uid 2000)执行命令。
 *
 * 关键点:光「拿到授权」没用,调用还得**真的走提权通道**。
 * ctx.startActivity() 之类是 app 自己的进程在调,身份还是 uid 10xxx,
 * 系统照样拒。必须把这次调用扔进 Shizuku 起的 shell 进程里执行。
 *
 * Shizuku.newProcess 标了 @RestrictedApi(给 lint 看的),这里用反射调,
 * 免得 lint 配置一变就编不过;返回值是 java.lang.Process 的子类。
 */
public final class ShizukuShell {

    private static final String TAG = "Blank/Shell";

    private ShizukuShell() {}

    /** 提权是否已就绪(未授权时不必浪费一次反射)。 */
    public static boolean ready() {
        try {
            return Shizuku.pingBinder() && !Shizuku.isPreV11()
                    && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 走 sh -c 执行,合并 stdout/stderr 一起回来(失败原因通常在 stderr)。 */
    public static Shell.Result exec(String cmd) {
        if (!ready()) return new Shell.Result(-1, "提权未就绪");
        try {
            Method m = Shizuku.class.getDeclaredMethod(
                    "newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            Object o = m.invoke(null, (Object) new String[]{"sh", "-c", cmd + " 2>&1"}, null, null);
            if (!(o instanceof Process)) return new Shell.Result(-1, "newProcess 返回了 " + o);

            Process p = (Process) o;
            String out = read(p.getInputStream());
            int exit = p.waitFor();
            Log.i(TAG, "$ " + cmd + "\n  exit=" + exit + " " + out.trim());
            return new Shell.Result(exit, out);
        } catch (Throwable t) {
            Log.w(TAG, "exec 失败: " + cmd, t);
            return new Shell.Result(-1, String.valueOf(t));
        }
    }

    private static String read(InputStream in) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        } catch (Throwable ignored) { }
        return sb.toString();
    }
}
