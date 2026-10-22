package com.liubai.blank.privileged;

import android.content.Context;
import android.os.Looper;
import android.util.Log;

import com.liubai.blank.privileged.adb.AdbConnection;
import com.liubai.blank.privileged.adb.AdbCrypto;
import com.liubai.blank.privileged.adb.AdbStream;
import com.liubai.blank.privileged.server.BlankApi;
import com.liubai.blank.privileged.server.BlankServer;
import com.liubai.blank.privileged.server.ServerClient;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * 内置提权:车机连自己的 adb,不依赖 Shizuku app,车主只装留白一个包。
 *
 * 原理是我们在 app 里实现了 adb 协议的「电脑」那一端,连本机 127.0.0.1:5555,
 * 认证通过后开出来的 shell 流就是 shell(uid 2000) 身份。全程不 root、不改系统分区,
 * 卸载即无痕,符合项目红线。
 *
 * 用户要做的只有两件事,都是一次性的:
 *   1. 在车机设置里打开无线调试(端口 5555)
 *   2. 首次连接时,车机会弹「允许 USB 调试吗?」,点允许并勾「一律允许」
 * 之后每次开机自动连上,无感。
 */
public final class SelfAdbPrivileged implements Privileged {

    private static final String TAG = "Blank/SelfAdb";

    /** Android 10 的 `adb tcpip` 约定端口。无线调试开启后 adbd 监听在这里。 */
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 5555;

    private static final SelfAdbPrivileged INSTANCE = new SelfAdbPrivileged();

    public static SelfAdbPrivileged get() { return INSTANCE; }

    private volatile AdbConnection conn;
    private volatile State state = State.UNAVAILABLE;
    /** 上一次失败的原因,给设置页显示用——「连不上」要说清是端口没开还是用户没点允许。 */
    private volatile String lastError;
    private volatile boolean connecting;
    /** 跑特权服务的那条流,必须一直持有——关了服务进程就被杀。 */
    private volatile AdbStream serverStream;

    private final List<Runnable> pending = new ArrayList<>();

    private SelfAdbPrivileged() {}

    @Override
    public State state(Context ctx) {
        AdbConnection c = conn;
        if (c != null && c.isConnected()) return State.READY;
        return state;
    }

    /**
     * 发起连接。异步——认证要等用户在车机上点「允许」,可能几十秒,绝不能占着主线程。
     */
    @Override
    public void requestPermission(final Context ctx) {
        connect(ctx.getApplicationContext());
    }

    /**
     * 连不上就**一直重试**,退避到 30 秒一次。
     *
     * 车主第一次装必踩的坑:adbd 请求授权时车机弹「允许 USB 调试」,而我们这条连接
     * 只等 {@link AdbConnection#AUTH_TIMEOUT_MS}(120 秒)。用户点「允许」的那一下要是
     * 落在超时之后,公钥虽然进了 adb_keys,**我们却再也不连了** —— 界面一直显示未授权,
     * 只能自己想到去重启 app。真车实测就是这么卡住的。
     *
     * 重试之后:超时那一刻立刻起一条新连接接着等,用户什么时候点都行。已经授权过的话
     * 新连接握手瞬间就成,不用等满 120 秒。
     *
     * 退避是给「adbd 压根没开」那种情况准备的 —— 那时连接会立刻被拒,不退避就成了
     * 每 3 秒空转一次。
     */
    private static final long[] RETRY_BACKOFF_MS = { 3_000, 3_000, 5_000, 10_000, 30_000 };

    /**
     * 等用户授权时的探测间隔。**固定,不参与退避**。
     *
     * 用户点「允许」的那一刻公钥就进了 adb_keys,下一次探测的签名当场就被接受 ——
     * 所以"点完到 Dock 浮出来"的时间基本就等于这个间隔,给到 400ms 就是无感。
     *
     * 敢轮这么密,是因为探测模式下一轮只是一次本机 loopback 往返
     * (见 {@link AdbConnection#connect(String, int, AdbCrypto, long, boolean)}):
     * 不发公钥、不会重复弹框、签名不认就立刻返回。要是每轮都照常发公钥,
     * 一轮要好几秒,那才需要拉长间隔。
     */
    private static final long AUTH_RETRY_MS = 400;

    /**
     * 授权态密探(400ms/轮)的轮数上限,到顶退回退避档。
     *
     * 300 轮 ≈ 2 分钟。车主在开车、授权框搁着不点是常态 —— 不设上限的话这个后台线程会
     * 400ms 一轮地建 TCP + 跑握手,一直空转到进程死。退回退避后**仍是探测模式**
     * (state 还在 UNAUTHORIZED,不会重弹框);用户之后点了允许,下一轮退避(最长 30s)
     * 照样接上,只是"点完到就绪"从无感变成最多等一档退避。
     */
    private static final int AUTH_MAX_PROBES = 300;

    public void connect(final Context appCtx) {
        if (connecting) return;
        AdbConnection c = conn;
        if (c != null && c.isConnected()) { fireReady(); return; }

        connecting = true;
        new Thread(new Runnable() {
            @Override public void run() {
                int fails = 0;
                int authProbes = 0;
                boolean waitingLogged = false;
                try {
                    while (!tryConnectOnce(appCtx)) {
                        long wait;
                        if (state == State.UNAUTHORIZED && authProbes < AUTH_MAX_PROBES) {
                            /*
                             * 弹框已经摆在用户面前了,这不算"连接失败"——不计进 fails,
                             * 也不退避(见 AUTH_RETRY_MS)。用户点完最多 2 秒就接上。
                             * 日志只打第一次:两秒一条会把 logcat 刷没,真出问题反而查不着。
                             */
                            wait = AUTH_RETRY_MS;
                            authProbes++;
                            if (!waitingLogged) {
                                Log.i(TAG, "等车机上点「允许」,每 " + wait + "ms 探一次,点完即连");
                                waitingLogged = true;
                            }
                        } else {
                            /*
                             * 要么本来就连不通,要么授权框被晾太久(authProbes 到顶,见
                             * AUTH_MAX_PROBES)——退回退避,别 400ms 空转到进程死。退避档下
                             * state 仍是 UNAUTHORIZED、tryConnectOnce 照走探测模式(不重弹框),
                             * 用户之后点了允许,下一轮退避照样接上。离开授权态就重置计数,
                             * 下次弹框重新密探。
                             */
                            wait = RETRY_BACKOFF_MS[
                                    Math.min(fails, RETRY_BACKOFF_MS.length - 1)];
                            fails++;
                            waitingLogged = false;
                            if (state != State.UNAUTHORIZED) authProbes = 0;
                            Log.i(TAG, "第 " + fails + " 次连接未成," + (wait / 1000) + " 秒后重试");
                        }
                        Thread.sleep(wait);
                    }
                } catch (InterruptedException ignored) {
                    // 进程要走了,不用收尾
                } finally {
                    connecting = false;
                }
            }
        }, "blank-adb-connect").start();
    }

    /** 连一次。成功返回 true(调用方就此收工),失败只记状态,由调用方决定要不要再来。 */
    private boolean tryConnectOnce(Context appCtx) {
        try {
            AdbCrypto crypto = AdbCrypto.loadOrCreate(keyDir(appCtx));
            /*
             * 只有**还没弹过框**的那次才发公钥。已经在 UNAUTHORIZED 上打转,说明框正开着,
             * 这时改走探测模式:一轮只是问一句"签名认了没",几十毫秒回来,也不会再弹一次框。
             */
            boolean firstTry = state != State.UNAUTHORIZED;
            if (firstTry) Log.i(TAG, "连接本机 adb " + HOST + ":" + PORT);
            AdbConnection open = AdbConnection.connect(
                    HOST, PORT, crypto, AdbConnection.AUTH_TIMEOUT_MS, firstTry);
            conn = open;
            state = State.READY;
            lastError = null;
            Log.i(TAG, "内置提权已就绪");
            startServer(appCtx);   // 顺带把特权服务拉起来,触摸才能跟手
            fireReady();
            return true;
        } catch (AdbConnection.AuthPendingException e) {
            // 只在框刚弹出来那一次记一笔:之后是 400ms 一轮的探测,照打会把 logcat 冲掉
            if (state != State.UNAUTHORIZED) Log.w(TAG, "等待车机上确认授权");
            state = State.UNAUTHORIZED;
            // 文案要讲清"点了就会自己连上",否则用户点完看界面没变,又去重启 app
            lastError = "请在车机屏幕上点「允许」,点完会自动连上";
            return false;
        } catch (Exception e) {
            state = State.UNAVAILABLE;
            lastError = describeFailure(e);
            Log.w(TAG, "连接失败: " + lastError, e);
            return false;
        }
    }

    /**
     * 拉起内置特权服务(BlankServer),让触摸注入走 binder 而不是 fork `input` 进程。
     *
     * 三个要点:
     *   1. 必须用一条**不关闭**的 adb 流来跑。流一关,shell 起的子进程跟着被杀,
     *      服务就没了——所以 serverStream 要一直持有着。
     *   2. 得把 provider 的 authority 当参数传过去:server 进程里没有 Context,
     *      而 debug 包的 applicationId 带 .debug 后缀,写死会找不到 provider。
     *   3. app_process 拉起一个 JVM 要一两秒,而且 binder 是 server 那边**推**过来的
     *      (见 PrivilegedProvider),所以这里只能轮询等它到货。
     * 拉不起来也不致命:InputInjector 会退回 `input` 命令,点击仍可用。
     */
    private void startServer(Context ctx) {
        try {
            if (ServerClient.isConnected()) {
                Log.i(TAG, "特权服务已在运行,直接复用");
                return;
            }
            AdbConnection c = conn;
            if (c == null) return;

            // 先清掉可能还活着的旧实例:binder 是单向推给 app 的,app 一重启
            // 旧 server 就再也找不到我们了,留着只白占内存。
            // 按进程名杀(--nice-name 设的就是它)——不能用 pkill -f 匹配命令行,
            // 那会连带匹配到执行这条命令的 shell 自己,把父进程一起带走。
            execDirect("pkill blank_server", 3000);

            // 类名走 getName(),重构不会漏改;正式版开混淆时记得给这个类加 keep 规则
            String cmd = "CLASSPATH=" + ctx.getPackageCodePath()
                    + " app_process /system/bin --nice-name=blank_server "
                    + BlankServer.class.getName()
                    + " " + BlankApi.authority(ctx);
            Log.i(TAG, "拉起特权服务: " + cmd);
            serverStream = c.open("shell:" + cmd);
            drainServerLog(serverStream);

            for (int i = 0; i < 25; i++) {
                Thread.sleep(200);
                if (ServerClient.isConnected()) {
                    Log.i(TAG, "特权服务已就绪,触摸走 binder 直注");
                    return;
                }
            }
            Log.w(TAG, "特权服务未能就绪,触摸将退回 input 命令(拖动需松手才响应)");
        } catch (Throwable t) {
            Log.w(TAG, "拉起特权服务失败", t);
        }
    }

    /** server 的 stdout 顺着流回来,读掉并转成日志——不读的话会一直堆在队列里。 */
    private void drainServerLog(final AdbStream s) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    while (!s.isClosed()) {
                        byte[] chunk = s.read(5000);
                        if (chunk == null) break;
                        if (chunk.length > 0) Log.i(TAG, new String(chunk).trim());
                    }
                } catch (Throwable ignored) { }
            }
        }, "blank-server-log");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 把异常翻译成车主看得懂的话。
     * 连不上最常见的原因是无线调试没开,而不是代码有问题——别让用户对着堆栈发呆。
     */
    private String describeFailure(Exception e) {
        String msg = String.valueOf(e.getMessage());
        if (e instanceof java.net.ConnectException || msg.contains("ECONNREFUSED")) {
            return "连不上本机 adb(" + PORT + "):多半是车机的无线调试没打开";
        }
        // 认证阶段超时 = 授权弹窗没人点。原样抛出来是 "Read timed out",车主看不懂,
        // 而且会以为坏了 —— 得说清它还在等、点了就好
        if (e instanceof java.net.SocketTimeoutException || msg.contains("timed out")) {
            return "等车机上的授权弹窗超时,重试中 —— 在屏幕上点「允许」就会连上";
        }
        return msg;
    }

    /** 私钥必须存在 app 私有目录,别人读不到——它等价于这台车的提权凭证。 */
    private File keyDir(Context ctx) {
        return new File(ctx.getFilesDir(), "adb");
    }

    @Override
    public void onReady(Runnable action) {
        if (action == null) return;
        if (state(null) == State.READY) { action.run(); return; }
        synchronized (pending) { pending.add(action); }
    }

    private void fireReady() {
        List<Runnable> copy;
        synchronized (pending) {
            if (pending.isEmpty()) return;
            copy = new ArrayList<>(pending);
            pending.clear();
        }
        for (Runnable r : copy) {
            try { r.run(); } catch (Throwable t) { Log.w(TAG, "onReady 回调异常", t); }
        }
    }

    @Override
    public String describe(Context ctx) {
        switch (state(ctx)) {
            case READY:
                return "已就绪(内置提权,无需 Shizuku)";
            case UNAUTHORIZED:
                return lastError != null ? lastError : "等待车机上确认授权";
            default:
                return lastError != null ? lastError
                        : "未连接。需先在车机设置里打开无线调试(端口 " + PORT + ")";
        }
    }

    /**
     * 以 shell 身份执行一条命令。
     *
     * 调用方大多在主线程(画中画启动就是在 TextureView 回调里同步调的),
     * 而这条通道底下是真 socket,主线程直接写会被 StrictMode 拦下
     * NetworkOnMainThreadException——原来走 Shizuku 时是 binder,没这个问题,
     * 换通道后才冒出来。所以在这里兜住:主线程调用就转到后台线程跑,再等结果回来。
     * 对调用方仍是同步语义,和 Shizuku.newProcess 一样阻塞,上层一行都不用改。
     */
    public Shell.Result exec(final String cmd, final long timeoutMs) {
        AdbConnection c = conn;
        if (c == null || !c.isConnected()) return new Shell.Result(-1, "内置提权未就绪");

        if (Looper.myLooper() == Looper.getMainLooper()) {
            FutureTask<Shell.Result> task = new FutureTask<>(new Callable<Shell.Result>() {
                @Override public Shell.Result call() { return execDirect(cmd, timeoutMs); }
            });
            new Thread(task, "blank-adb-exec").start();
            try {
                // 多给一点余量,免得比内层的读超时还先到期
                return task.get(timeoutMs + 2000, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                Log.w(TAG, "exec 超时/异常: " + cmd, e);
                return new Shell.Result(-1, String.valueOf(e));
            }
        }
        return execDirect(cmd, timeoutMs);
    }

    private Shell.Result execDirect(String cmd, long timeoutMs) {
        AdbConnection c = conn;
        if (c == null || !c.isConnected()) return new Shell.Result(-1, "内置提权未就绪");
        try {
            // 合并 stderr:失败原因通常在那儿,分开读还得多开一条流
            String out = c.exec(cmd + " 2>&1", timeoutMs);
            Log.i(TAG, "$ " + cmd + "\n  " + out.trim());
            return new Shell.Result(0, out);
        } catch (Exception e) {
            Log.w(TAG, "exec 失败: " + cmd, e);
            return new Shell.Result(-1, String.valueOf(e));
        }
    }

    public void disconnect() {
        AdbConnection c = conn;
        conn = null;
        state = State.UNAVAILABLE;
        if (c != null) c.close();
    }
}
