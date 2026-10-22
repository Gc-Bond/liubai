package com.liubai.blank.privileged.server;

import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 留白的特权服务,以 shell(uid 2000) 身份运行 —— 相当于内化了一个最小的 Shizuku。
 *
 * 它不是 Android 组件,而是被 app_process 直接拉起的普通 Java 进程:
 *   CLASSPATH=&lt;apk&gt; app_process /system/bin --nice-name=blank_server \
 *       com.liubai.blank.privileged.server.BlankServer &lt;provider-authority&gt;
 * 这条命令由 app 内置的 adb 通道执行(见 SelfAdbPrivileged),所以整个过程
 * 不需要 Shizuku app、不需要接电脑。
 *
 * 为什么必须有这个进程:触摸注入要 INJECT_EVENTS(signature 级),app 永远拿不到。
 * 而逐条发 `input` 命令每次都要 fork 一个进程、几百毫秒,拖动只能等松手才响应。
 * 有了常驻的 shell 进程,一次注入就是一次 oneway binder 调用(微秒级),才谈得上跟手。
 *
 * <b>为什么是 binder 而不是 socket</b>:最早用的是 abstract unix socket,server 侧
 * 一切正常(/proc/net/unix 里能看到),但 app 死活连不上、还没有 avc 日志。
 * 根因是 SELinux 的 `neverallow untrusted_app shell:unix_stream_socket connectto`,
 * 这类拒绝是静默的。app ↔ shell 进程之间只有 binder 走得通,
 * 于是改用 Shizuku 那套路子:server 通过 getContentProviderExternal 拿到 app 的
 * ContentProvider,用 call() 把 binder 塞在 Bundle 里递过去。
 *
 * 另外这个进程不是 app,<b>不受 hidden API 名单限制</b>,所以能直接反射
 * MotionEvent.setDisplayId —— 在 app 进程里这个方法从 Android 12 起就被拦了。
 */
public final class BlankServer {

    /**
     * uid 2000 对应的系统包名。
     * provider.call 要报一个 callingPackage,系统会校验它确实属于调用方 uid,
     * 填自己的包名会直接 SecurityException。
     */
    private static final String SHELL_PKG = "com.android.shell";

    /** provider 刚被拉起时可能还没就绪,给几次机会。 */
    private static final int PROVIDER_RETRY = 5;
    private static final long PROVIDER_RETRY_MS = 200;

    public static void main(String[] args) {
        String authority = args.length > 0 ? args[0] : null;
        try {
            // binder 调用由 binder 线程池处理,主线程只要不退出即可;
            // 顺带给将来可能用到的 Handler 备好主 Looper。
            Looper.prepareMainLooper();

            // authority = 包名 + ".privileged"(见 BlankApi.authority),这里反推回包名
            // 给 AC 探针用 createPackageContext——server 进程没有 Context,拿不到自己包名。
            String packageName = authority != null && authority.endsWith(".privileged")
                    ? authority.substring(0, authority.length() - ".privileged".length())
                    : null;
            BlankService service = new BlankService(packageName);
            log("特权服务已启动 uid=" + Process.myUid() + " authority=" + authority);

            sendBinderToApp(authority, service);
            log("binder 已送达 app,进入服务循环");

            Looper.loop();
        } catch (Throwable t) {
            log("启动失败: " + t);
            t.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * 把 binder 递给 app。
     *
     * 这是整个方案的关键一步:app 和 shell 进程之间没有别的通道能传 binder,
     * 只有系统的 ContentProvider 机制——它本来就是跨进程发 binder 的设施。
     * 拿 provider 要 ACCESS_CONTENT_PROVIDERS_EXTERNALLY(signature 级),
     * shell 恰好有,app 自己没有,所以这个方向只能由 server 主动发起。
     */
    private static void sendBinderToApp(String authority, IBinder service) throws Exception {
        if (authority == null || authority.isEmpty()) {
            throw new IllegalArgumentException("没传 provider authority,app 侧命令拼错了");
        }
        Object am = activityManager();

        for (int i = 1; ; i++) {
            IBinder token = new Binder();
            Object holder = getContentProviderExternal(am, authority, token);
            try {
                Object provider = holder == null ? null
                        : holder.getClass().getField("provider").get(holder);
                if (provider != null) {
                    Bundle extras = new Bundle();
                    extras.putBinder(BlankApi.KEY_BINDER, service);
                    callProvider(provider, authority, BlankApi.METHOD_SEND_BINDER, extras);
                    return;
                }
            } finally {
                // 不还这个引用,app 进程会被系统当成「有外部使用者」一直吊着
                if (holder != null) removeContentProviderExternal(am, authority, token);
            }
            if (i >= PROVIDER_RETRY) {
                throw new IllegalStateException("拿不到 provider(" + authority + "),app 侧没注册?");
            }
            log("provider 还没就绪,重试 " + i + "/" + PROVIDER_RETRY);
            Thread.sleep(PROVIDER_RETRY_MS);
        }
    }

    private static Object activityManager() throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        IBinder b = (IBinder) sm.getMethod("getService", String.class).invoke(null, "activity");
        if (b == null) throw new IllegalStateException("拿不到 activity 服务");
        Class<?> stub = Class.forName("android.app.IActivityManager$Stub");
        return stub.getMethod("asInterface", IBinder.class).invoke(null, b);
    }

    /** Android 10 起多了个 tag 参数(给 ANR 排查用),按参数个数分支比硬编码签名抗造。 */
    private static Object getContentProviderExternal(Object am, String authority, IBinder token)
            throws Exception {
        Method m = findMethod(am, "getContentProviderExternal");
        if (m.getParameterTypes().length == 4) {
            return m.invoke(am, authority, 0, token, "blank");
        }
        return m.invoke(am, authority, 0, token);
    }

    private static void removeContentProviderExternal(Object am, String authority, IBinder token) {
        try {
            findMethod(am, "removeContentProviderExternal").invoke(am, authority, token);
        } catch (Throwable t) {
            log("释放 provider 引用失败(不影响功能): " + t);
        }
    }

    /**
     * IContentProvider.call 的签名逐版本在变,只有「首参是包名、末参是 Bundle」稳定,
     * 按参数个数认版本:
     *   4 → Android 9 及以前 (callingPkg, method, arg, extras)
     *   5 → Android 10       (callingPkg, authority, method, arg, extras)
     *   6 → Android 11+      (callingPkg, attributionTag, authority, method, arg, extras)
     *
     * <b>必须取参数最全的那个重载</b>:接口里同时留着一个 @Deprecated 的旧签名兼容方法,
     * 它内部把 authority 硬填成字面量 "unknown" 再转调真方法,于是必然撞上
     * 「The authority unknown does not match」的 SecurityException。
     * getMethods() 的顺序不保证,选「第一个匹配」就是在赌运气(实测 API 29 上会赌输)。
     */
    private static void callProvider(Object provider, String authority,
                                     String method, Bundle extras) throws Exception {
        Method call = null;
        for (Method m : provider.getClass().getMethods()) {
            if (!"call".equals(m.getName())) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length < 4 || p.length > 6 || p[p.length - 1] != Bundle.class) continue;
            // 首参:标准版是 callingPkg(String);华为 EMUI(Android 12+)把 callingPkg
            // 和 attributionTag 合并成一个 AttributionSource,签名变成 5 参
            // (source, authority, method, arg, extras),首参不再是 String。
            boolean ok = p[0] == String.class;
            if (!ok && p.length == 5) {
                try { ok = p[0] == Class.forName("android.content.AttributionSource"); }
                catch (Throwable ignored) { }
            }
            if (!ok) continue;
            if (call == null || p.length > call.getParameterTypes().length) call = m;
        }
        if (call == null) throw new NoSuchMethodException("IContentProvider.call 签名不认识");

        Class<?>[] p = call.getParameterTypes();
        if (p.length == 6) {
            // AOSP Android 12+: (callingPkg, attributionTag, authority, method, arg, extras)
            call.invoke(provider, SHELL_PKG, null, authority, method, null, extras);
        } else if (p.length == 5 && p[0] == String.class) {
            // AOSP Android 10/11: (callingPkg, authority, method, arg, extras)
            call.invoke(provider, SHELL_PKG, authority, method, null, extras);
        } else if (p.length == 5) {
            // 华为 EMUI: (AttributionSource, authority, method, arg, extras)
            call.invoke(provider, newAttributionSource(), authority, method, null, extras);
        } else {
            // Android 9 及以前:(callingPkg, method, arg, extras),那时没有 authority 校验
            call.invoke(provider, SHELL_PKG, method, null, extras);
        }
    }

    /**
     * 华为版 call 首参的 AttributionSource 实例。就是"以 shell 身份调 provider",
     * uid 2000 + 包名按 shell 的写,provider 侧用它做权限校验(和 AOSP 的 callingPkg 同义)。
     */
    private static Object newAttributionSource() throws Exception {
        Class<?> as = Class.forName("android.content.AttributionSource");
        return as.getConstructor(int.class, String.class, String.class)
                .newInstance(2000, "com.android.shell", null);
    }

    private static Method findMethod(Object target, String name) throws NoSuchMethodException {
        for (Method m : target.getClass().getMethods()) {
            if (name.equals(m.getName())) return m;
        }
        throw new NoSuchMethodException(name);
    }

    /** 输出到 stdout,会顺着 adb 流回到 app 的日志里,方便真车上排查。 */
    static void log(String msg) {
        System.out.println("[BlankServer] " + msg);
    }

    private BlankServer() {}
}
