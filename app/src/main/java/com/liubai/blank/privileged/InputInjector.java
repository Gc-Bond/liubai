package com.liubai.blank.privileged;

import android.os.IBinder;
import android.util.Log;
import android.view.InputEvent;
import android.view.MotionEvent;

import java.lang.reflect.Method;

import com.liubai.blank.privileged.server.ServerClient;

import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.SystemServiceHelper;

/**
 * 把触摸事件注入到指定屏幕(画中画的虚拟屏)。
 *
 * 为什么不用 `input -d N tap x y`:那条命令每次都要 fork 一个进程,
 * 单次几百毫秒,点一下能忍,滑动就是幻灯片。这里改成直接持有
 * IInputManager 的 binder,一次注入就是一次 binder 调用(微秒级)。
 *
 * 身份问题同 PipHost:INJECT_EVENTS 是 signature 级权限,app 拿不到,
 * shell(uid 2000) 有。ShizukuBinderWrapper 的作用就是让这次 binder 调用
 * 以 shell 身份发出去。
 *
 * 全程反射:IInputManager / MotionEvent.setDisplayId 都是 @hide,
 * 且各版本签名有出入(Android 14 起多了 injectInputEventToTarget),
 * 按方法名+参数个数挑,比硬编码签名抗造。
 */
public final class InputInjector {

    private static final String TAG = "Blank/Inject";

    /** InputManager.INJECT_INPUT_EVENT_MODE_ASYNC —— 不等结果,要的就是跟手。 */
    private static final int MODE_ASYNC = 0;

    private static Object inputManager;
    private static Method injectMethod;
    private static Method setDisplayId;
    private static boolean initFailed;

    private InputInjector() {}

    private static synchronized boolean ensureInit() {
        if (injectMethod != null) return true;
        if (initFailed) return false;
        try {
            IBinder raw = SystemServiceHelper.getSystemService("input");
            if (raw == null) { Log.w(TAG, "拿不到 input 服务"); initFailed = true; return false; }

            // 关键:包一层,binder 调用才是 shell 身份
            IBinder wrapped = new ShizukuBinderWrapper(raw);
            Class<?> stub = Class.forName("android.hardware.input.IInputManager$Stub");
            inputManager = stub.getMethod("asInterface", IBinder.class).invoke(null, wrapped);
            if (inputManager == null) { initFailed = true; return false; }

            for (Method m : inputManager.getClass().getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if ("injectInputEvent".equals(m.getName()) && p.length == 2
                        && InputEvent.class.isAssignableFrom(p[0])) {
                    injectMethod = m;
                    break;
                }
            }
            if (injectMethod == null) { Log.w(TAG, "没找到 injectInputEvent"); initFailed = true; return false; }

            try {
                setDisplayId = MotionEvent.class.getMethod("setDisplayId", int.class);
            } catch (NoSuchMethodException nsm) {
                // 方法是有的,只是 Android 12+ 把它列进了 hidden API 受限名单,
                // 反射查找会直接抛 NoSuchMethod(而不是 IllegalAccess),很有迷惑性。
                // 我们手里有 shell,可以把策略放开;但 hidden_api_policy 是进程 fork 时
                // 从 zygote 继承的,改完必须重启本应用才生效。
                Log.w(TAG, "setDisplayId 被 hidden API 限制拦下,已放开策略,重启应用后生效");
                Shell.exec("settings put global hidden_api_policy 1");
                initFailed = true;
                return false;
            }
            Log.i(TAG, "注入通道就绪");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "初始化失败", t);
            initFailed = true;
            return false;
        }
    }

    /**
     * 把一个触摸事件送到指定屏。
     *
     * @param src       原始事件(桌面上那块预览 View 收到的)
     * @param displayId 目标虚拟屏
     * @param scaleX    预览 View → 虚拟屏的横向缩放(虚拟屏宽 / View 宽)
     * @param scaleY    纵向缩放
     */
    public static boolean send(MotionEvent src, int displayId, float scaleX, float scaleY) {
        // 三级降级,按跟手程度从好到差:
        //   1. 内置特权服务:本地 socket 往返,微秒级,逐个事件透传 → 完全跟手
        //   2. Shizuku binder 直调:同样微秒级,但要求装了 Shizuku app
        //   3. shell 的 input 命令:每次 fork 一个进程,只能整手势压成一条 → 拖动要松手
        if (ServerClient.isConnected()) {
            return ServerClient.sendTouch(src, displayId, scaleX, scaleY);
        }
        if (ShizukuShell.ready() && ensureInit()) {
            return sendViaBinder(src, displayId, scaleX, scaleY);
        }
        return sendViaShell(src, displayId, scaleX, scaleY);
    }

    // ---- 通道一:binder 直调(需 Shizuku) ----

    private static boolean sendViaBinder(MotionEvent src, int displayId, float scaleX, float scaleY) {
        // 不能直接 obtain(src) 复制:原事件带着主屏那块物理触摸屏的 deviceId,
        // InputDispatcher 会按设备所属的屏来路由,我们设的 displayId 就白设了。
        // 重新构造一个 deviceId=0 的干净事件,才认 displayId。
        MotionEvent e = MotionEvent.obtain(
                src.getDownTime(), src.getEventTime(), src.getAction(),
                src.getX() * scaleX, src.getY() * scaleY, src.getMetaState());
        try {
            e.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            setDisplayId.invoke(e, displayId);
            Object r = injectMethod.invoke(inputManager, e, MODE_ASYNC);
            boolean ok = !(r instanceof Boolean) || (Boolean) r;
            if (!ok) Log.w(TAG, "注入被拒 action=" + src.getAction() + " display=" + displayId);
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "注入失败", t);
            return false;
        } finally {
            e.recycle();
        }
    }

    // ---- 通道二:shell 的 input 命令(内置 adb 通道用这条) ----

    /** 判定「点」还是「滑」的位移阈值(虚拟屏像素)。 */
    private static final int TAP_SLOP = 24;

    /**
     * 命令必须排队串行发:tap/swipe 之间乱序会让目标 app 收到颠倒的手势。
     * 单线程也顺便挡住了并发 fork 一堆 input 进程。
     */
    private static final java.util.concurrent.ExecutorService SHELL_IO =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "blank-input");
                t.setDaemon(true);
                return t;
            });

    private static float downX, downY;
    private static long downTime;

    /**
     * 没有 binder 时的退路:把整个手势压缩成一条 `input` 命令。
     *
     * 关键是**不能逐个事件发**——`input` 每次都要 fork 一个进程,单次几百毫秒,
     * 按 ACTION_MOVE 的频率发等于每帧 fork 一次,必然卡死。
     * 所以只在抬手时结算一次:位移小算点击、位移大算滑动。
     *
     * 代价是拖动过程中没有实时跟随(手指在动,画面要等松手才响应),
     * 点击和翻页够用,连续拖拽的手感做不出来——那个得等内置 binder 服务(第二期)。
     */
    private static boolean sendViaShell(MotionEvent src, int displayId, float scaleX, float scaleY) {
        if (!Shell.ready()) return false;

        float x = src.getX() * scaleX;
        float y = src.getY() * scaleY;

        switch (src.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = x;
                downY = y;
                downTime = src.getEventTime();
                return true;                 // 先记下,抬手时才知道是点还是滑

            case MotionEvent.ACTION_UP: {
                boolean tap = Math.abs(x - downX) < TAP_SLOP && Math.abs(y - downY) < TAP_SLOP;
                // 时长照抄真实手势,否则快滑会被目标 app 当成长按
                long dur = Math.max(50, Math.min(1000, src.getEventTime() - downTime));
                final String cmd = tap
                        ? "input -d " + displayId + " tap " + (int) downX + " " + (int) downY
                        : "input -d " + displayId + " swipe " + (int) downX + " " + (int) downY
                                + " " + (int) x + " " + (int) y + " " + dur;
                // 绝不能在主线程等:一次 fork 就是几百毫秒,桌面会肉眼可见地顿一下
                SHELL_IO.execute(() -> {
                    Shell.Result r = Shell.exec(cmd);
                    if (!r.ok()) Log.w(TAG, "input 注入失败: " + r);
                });
                return true;
            }

            default:
                return true;                 // MOVE/CANCEL 全部吞掉,不逐帧发命令
        }
    }

    /** 提权断掉/换实现后重新探测(否则会一直记着上次的失败)。 */
    public static synchronized void reset() {
        inputManager = null;
        injectMethod = null;
        setDisplayId = null;
        initFailed = false;
    }
}
