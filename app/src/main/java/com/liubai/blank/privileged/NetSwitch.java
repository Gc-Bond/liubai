package com.liubai.blank.privileged;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.provider.Settings;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * WLAN 和热点两个系统开关。
 *
 * <h3>为什么不在 {@link CarCtrl} 里</h3>
 *
 * 车控那一套是「设备号 + 字段名 + 值」往 CAN 上发({@code BYDAutoFeatureIds}),这两个跟车身
 * 毫无关系,是 Android 自己的能力 —— 走的通道、失败的原因、能不能读回来全都不一样。
 * 桌面上它们和日行灯并排摆着(同为 {@code CarCtrlItem.Kind.SYSTEM}),但底下是两条路。
 *
 * <h3>WLAN:读走 app,写走提权</h3>
 *
 * 读用 {@link WifiManager#getWifiState()},公开 API、只要 ACCESS_WIFI_STATE。
 * **不走提权通道读** —— 那条通道读输出会丢字节(见记忆 accessibility-list-write-broke-dms),
 * 能用公开 API 读的就别绕。
 *
 * 写只能走提权:{@code WifiManager.setWifiEnabled} 从 Android 10 起对第三方 app 一律返回 false,
 * 而 {@code svc wifi enable|disable} 从 shell 身份(uid 2000)跑得通 ——
 * 2026-08-31 在护卫舰07 上验过 `svc wifi enable` 没报权限错。
 *
 * <h3>热点:读反射,写反射,前置要 WRITE_SETTINGS</h3>
 *
 * 车机没有可用的 shell 入口:{@code cmd wifi} 要 root(uid 2000 被 checkRootPermission 拒),
 * {@code cmd connectivity} 在 Android 10 上只有飞行模式一条。所以只能反射。
 *
 * 权限门槛查过了 —— {@code ConnectivityManager.enforceTetherChangePermission} 分两支:
 * {@code config_mobile_hotspot_provision_app} 长度为 2 时要 TETHER_PRIVILEGED(签名级,拿不到),
 * 否则只要 WRITE_SETTINGS。**这台车那个数组 size=0**(从 /system/framework/framework-res.apk
 * dump 出来的),走的是后一支,于是 appops 授一下 WRITE_SETTINGS 就够。
 *
 * 至于为什么绕到 {@code mService} 而不是直接调 {@code ConnectivityManager.startTethering}:
 * 那个方法要一个 {@code OnStartTetheringCallback},它是**抽象类**(虽然两个方法都有默认实现),
 * 反射造不出实例,而它 {@code Preconditions.checkNotNull} 又不收 null。里层的
 * {@code IConnectivityManager.startTethering} 收的是 {@link ResultReceiver} —— 那是个公开类,
 * new 得出来。
 *
 * <h3>⚠️ 热点这条没在真车上发过一次</h3>
 *
 * 路径是从这台车的 framework 反编译里一层层确认的(不是猜的),但确认到的是「权限过得去」,
 * 不代表模组真会起来。而且**开热点多半会把 WLAN 关掉**(STA/AP 常见互斥),所以没法隔着
 * WiFi adb 去验 —— 一开连接就断了。失败时把原因带回调用方,让它照实说。
 */
public final class NetSwitch {

    private static final String TAG = "Blank/Net";

    /** {@code ConnectivityManager.TETHERING_WIFI}。 */
    private static final int TETHERING_WIFI = 0;
    /** {@code WifiManager.WIFI_AP_STATE_ENABLING / ENABLED}。@SystemApi 常量,值抄过来。 */
    private static final int AP_ENABLING = 12, AP_ENABLED = 13;

    /** {@code BluetoothProfile.A2DP_SINK / HEADSET_CLIENT}。@hide 常量,值抄过来。 */
    private static final int A2DP_SINK = 11, HEADSET_CLIENT = 16;

    /**
     * 问"连着谁"的 profile 顺序。**车机自己的角色排前面** ——
     * 车机是音频接收端 + 免提客户端,见 {@link #tryProfiles}。
     */
    private static final int[] BT_PROFILES = {
            A2DP_SINK, HEADSET_CLIENT, BluetoothProfile.A2DP, BluetoothProfile.HEADSET };

    /** 回读轮询:一次 150ms,最多 10 次。WLAN 关到底实测要一秒出头。 */
    private static final int POLL_MS = 150, POLL_MAX = 10;

    /**
     * 自己一条线。{@link Shell#exec} 是阻塞的,回读又要轮询一秒多 —— 点一下开关不能卡主线程。
     */
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "blank-net");
        t.setDaemon(true);
        return t;
    });

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 成没成 + 一句人话的原因(成了就是空串)。原因要能直接进 toast:真车上 Log 抓不到。 */
    public interface Result {
        void done(boolean ok, String why);
    }

    private NetSwitch() {}

    // ---- WLAN ----

    public static boolean wifiOn(Context ctx) {
        WifiManager wm = wifi(ctx);
        return wm != null && wm.getWifiState() == WifiManager.WIFI_STATE_ENABLED;
    }

    /**
     * 当前连着的 WLAN 名字(SSID)。**回调在主线程**;没连上 / 读不到都回 null。
     *
     * 为什么走提权 {@code dumpsys wifi} 而不是 {@code getConnectionInfo().getSSID()}:
     * Android 10 起第三方 app 读 SSID 要 ACCESS_FINE_LOCATION + 定位服务开着,否则只给
     * "&lt;unknown ssid&gt;"。留白是第三方身份(uid 10xxx)撞这个限制;而 shell(uid 2000)
     * 读 dumpsys 拿的是系统内部状态,不吃位置权限、不用碰定位开关。
     * 2026-09-02 护卫舰07 实测拿得到、中文不乱码。
     *
     * **只 grep 那一行回来**(不是整份 dumpsys):那条提权通道读长输出会丢字节
     * (见记忆 accessibility-list-write-broke-dms),越短越稳。
     */
    public static void currentSsid(final Context ctx, final Consumer<String> cb) {
        IO.execute(() -> {
            String ssid = readSsid();
            if (cb != null) MAIN.post(() -> cb.accept(ssid));
        });
    }

    /** 在 IO 线程上读。没提权 / 没连 / 读不到一律 null。 */
    private static String readSsid() {
        if (!Shell.ready()) return null;
        // 只有 mWifiInfo 那行是"当前连接"的权威值,格式固定:
        //   mWifiInfo SSID: <名字>, BSSID: ...
        // rec[] 历史里的裸 "SSID:" 不带 mWifiInfo 前缀,不会被这条命中
        Shell.Result r = Shell.exec("dumpsys wifi | grep 'mWifiInfo SSID:'");
        return r != null && r.ok() ? parseSsid(r.out) : null;
    }

    /** 从 "mWifiInfo SSID: 名字, BSSID: ..." 抠出名字;抠不出 / 未连回 null。 */
    static String parseSsid(String out) {
        if (out == null) return null;
        int i = out.indexOf("mWifiInfo SSID:");
        if (i < 0) return null;
        String rest = out.substring(i + "mWifiInfo SSID:".length());
        int comma = rest.indexOf(", BSSID");     // 名字截到 BSSID 之前
        String ssid = (comma >= 0 ? rest.substring(0, comma) : rest).trim();
        // 没连上时这里是空串或 <unknown ssid>
        return ssid.isEmpty() || "<unknown ssid>".equals(ssid) ? null : ssid;
    }

    /**
     * 开/关 WLAN。**回调在主线程。**
     *
     * {@code svc} 是发完就走的,成功失败都不回话(空输出、exit 0),所以成没成只能靠回读判 ——
     * 同 {@link CarCtrl#set} 那套「写完等回读确认」的规矩。
     */
    public static void setWifi(final Context ctx, final boolean on, final Result cb) {
        IO.execute(() -> {
            if (!Shell.ready()) {
                post(cb, false, "开关 WLAN 需要先开启提权服务");
                return;
            }
            Shell.Result r = Shell.exec("svc wifi " + (on ? "enable" : "disable"));
            Log.i(TAG, "svc wifi " + (on ? "enable" : "disable") + " → " + r);
            boolean ok = pollUntil(() -> wifiOn(ctx) == on);
            post(cb, ok, ok ? "" : "WLAN 没" + (on ? "开起来" : "关掉"));
        });
    }

    // ---- 蓝牙 ----

    /**
     * 蓝牙开着没有。
     *
     * <p><b>这一项不用提权</b> —— {@code BluetoothAdapter.enable()/disable()} 到
     * Android 12 都还是普通 app 能调的(要 BLUETOOTH_ADMIN,normal 级、装上就有),
     * 不像 {@code setWifiEnabled} 从 10 起对第三方一律返回 false。所以这儿没有
     * {@code Shell.ready()} 那道门 —— 没提权的车上蓝牙卡照样能用。
     */
    public static boolean bluetoothOn(Context ctx) {
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        return a != null && a.isEnabled();
    }

    /**
     * 当前连着的蓝牙设备名。**回调在主线程**;没连 / 蓝牙关着 / 读不到都回 null。
     *
     * <p>走 {@code getProfileProxy} 这个公开 API,**不碰 {@code BluetoothDevice.isConnected()}**
     * —— 那是 @hide,Android 10 的 hidden API 名单可能拦(留白是第三方身份,不像
     * server 进程不受限)。代价是它异步:proxy 连上才拿得到设备,所以这里是回调式的。
     *
     * <p>先问 A2DP(音频)再问 HEADSET(免提):车机连手机通常两个 profile 都在,
     * 是同一台设备;只连了通话没连音频的场景靠第二问兜住。
     *
     * <p>连了不止一台就显示排在前面那台。卡片只有 72dp 宽,写"2 台设备"信息量还不如
     * 给个名字 —— 要看全的长按进系统页(见 DeskCardHost.systemPageOf)。
     */
    public static void connectedBtName(final Context ctx, final Consumer<String> cb) {
        final BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if (a == null || !a.isEnabled()) {
            if (cb != null) MAIN.post(() -> cb.accept(null));
            return;
        }
        tryProfiles(ctx, a, 0, cb);
    }

    /**
     * 挨个 profile 问过去,谁先给出设备就用谁。
     *
     * <p><b>顺序是「车机的角色」在前</b>:这台车 dumpsys 里跑的是 {@code A2dpSinkService}
     * 和 {@code HeadsetClientService} —— 车机是音频的**接收端**、免提的**客户端**,
     * 跟手机侧的角色正好相反。按 {@code A2DP}/{@code HEADSET} 去问,那两个 profile 上
     * 一台设备都没有(2026-09-05 就是这么翻的车)。后两个留着兜别的 ROM。
     */
    private static void tryProfiles(final Context ctx, final BluetoothAdapter a,
                                    final int idx, final Consumer<String> cb) {
        if (idx >= BT_PROFILES.length) {
            if (cb != null) cb.accept(null);
            return;
        }
        firstConnected(ctx, a, BT_PROFILES[idx], name -> {
            if (name != null) {
                if (cb != null) cb.accept(name);
            } else {
                tryProfiles(ctx, a, idx + 1, cb);
            }
        });
    }

    /** 某个 profile 上连着的第一台设备的名字。回调在主线程,拿不到给 null。 */
    private static void firstConnected(Context ctx, final BluetoothAdapter a,
                                       final int profile, final Consumer<String> cb) {
        boolean started = a.getProfileProxy(ctx, new BluetoothProfile.ServiceListener() {
            @Override public void onServiceConnected(int p, BluetoothProfile proxy) {
                String name = null;
                try {
                    java.util.List<BluetoothDevice> ds = proxy.getConnectedDevices();
                    if (ds != null && !ds.isEmpty()) name = ds.get(0).getName();
                } catch (Throwable t) {
                    Log.w(TAG, "读蓝牙已连设备失败", t);
                }
                // proxy 是系统资源,拿完就还 —— 不还会一直占着一条 service 连接
                try { a.closeProfileProxy(p, proxy); } catch (Throwable ignored) { }
                final String n = name;
                if (cb != null) MAIN.post(() -> cb.accept(n));
            }

            @Override public void onServiceDisconnected(int p) { }
        }, profile);
        // 请求都没发出去(这台车没这个 profile)就别让调用方干等
        if (!started && cb != null) MAIN.post(() -> cb.accept(null));
    }

    /**
     * 开/关蓝牙。**回调在主线程。**
     *
     * <p>{@code enable()} 只是把请求发出去,真开起来要好几秒(比 WLAN 慢一个量级),
     * 所以回读的上限单独给到 6 秒 —— 照 {@link #setWifi} 那套"写完等回读确认"的规矩。
     */
    public static void setBluetooth(final Context ctx, final boolean on, final Result cb) {
        IO.execute(() -> {
            final BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            if (a == null) {
                post(cb, false, "这台车上没有蓝牙");
                return;
            }
            boolean sent = on ? a.enable() : a.disable();
            Log.i(TAG, "bluetooth " + (on ? "enable" : "disable") + " → " + sent);
            if (!sent) {
                post(cb, false, "蓝牙开关没发出去");
                return;
            }
            boolean ok = pollUntil(() -> a.isEnabled() == on, 40);   // 40 × 150ms = 6 秒
            post(cb, ok, ok ? "" : "蓝牙没" + (on ? "开起来" : "关掉"));
        });
    }

    // ---- 热点 ----

    /**
     * 热点开着没有。**ENABLING 也算开** —— 起热点要好几秒,这期间显示成"关着"的话,
     * 用户会以为没点上又点一次,正好把它关回去。
     */
    public static boolean hotspotOn(Context ctx) {
        int s = apState(ctx);
        return s == AP_ENABLED || s == AP_ENABLING;
    }

    /**
     * 开/关热点。**回调在主线程。**
     *
     * 三步:补 WRITE_SETTINGS(缺了就用提权 appops 授) → 反射发下去 → 回读确认。
     * 任何一步倒了都把原因带回去。
     */
    public static void setHotspot(final Context ctx, final boolean on, final Result cb) {
        IO.execute(() -> {
            String why = ensureWriteSettings(ctx);
            if (why != null) {
                post(cb, false, why);
                return;
            }
            String err = on ? startTethering(ctx) : stopTethering(ctx);
            if (err != null) {
                post(cb, false, err);
                return;
            }
            boolean ok = pollUntil(() -> hotspotOn(ctx) == on);
            post(cb, ok, ok ? "" : "热点没" + (on ? "起来" : "关掉") + "(车端状态 " + apState(ctx) + ")");
        });
    }

    /**
     * 把 WRITE_SETTINGS 补上。已经有就直接过。
     *
     * **按需授,不进一键授权那张清单** —— 只有摆了热点卡的人才用得上这一项,
     * 塞进 {@link Grant.Item} 会让所有人都多看见一行要点的东西。
     *
     * @return null = 有了/授上了;否则是一句给用户看的原因
     */
    private static String ensureWriteSettings(Context ctx) {
        if (Settings.System.canWrite(ctx)) return null;
        if (!Shell.ready()) return "开关热点需要先开启提权服务";
        Shell.Result r = Shell.exec("appops set " + ctx.getPackageName() + " WRITE_SETTINGS allow");
        Log.i(TAG, "appops WRITE_SETTINGS → " + r);
        // appops 写完不是立刻生效,和 cmd 那批一样要轮询回读(见记忆 one-tap-grant)
        if (pollUntil(() -> Settings.System.canWrite(ctx))) return null;
        return "拿不到「修改系统设置」权限";
    }

    /** @return null = 发出去了,否则是失败原因 */
    private static String startTethering(Context ctx) {
        try {
            Object svc = connectivityService(ctx);
            // ResultReceiver 只是接结果的壳,真正的成败还是靠后面回读 —— 这里不等它
            ResultReceiver rr = new ResultReceiver(MAIN);
            Method m = svc.getClass().getMethod("startTethering",
                    int.class, ResultReceiver.class, boolean.class, String.class);
            // showProvisioningUi=false:车机上没人会去点那个运营商开通页
            m.invoke(svc, TETHERING_WIFI, rr, false, ctx.getPackageName());
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "startTethering 失败", t);
            return "开热点失败:" + brief(t);
        }
    }

    /** @return null = 发出去了,否则是失败原因 */
    private static String stopTethering(Context ctx) {
        try {
            Object svc = connectivityService(ctx);
            Method m = svc.getClass().getMethod("stopTethering", int.class, String.class);
            m.invoke(svc, TETHERING_WIFI, ctx.getPackageName());
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "stopTethering 失败", t);
            return "关热点失败:" + brief(t);
        }
    }

    /** {@code ConnectivityManager.mService},即 IConnectivityManager。 */
    private static Object connectivityService(Context ctx) throws Exception {
        ConnectivityManager cm = (ConnectivityManager)
                ctx.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        Field f = ConnectivityManager.class.getDeclaredField("mService");
        f.setAccessible(true);
        Object svc = f.get(cm);
        if (svc == null) throw new IllegalStateException("拿不到 IConnectivityManager");
        return svc;
    }

    /** -1 = 读不到(反射被拦或这台车没这个方法)。 */
    private static int apState(Context ctx) {
        try {
            WifiManager wm = wifi(ctx);
            if (wm == null) return -1;
            Method m = WifiManager.class.getMethod("getWifiApState");
            return (Integer) m.invoke(wm);
        } catch (Throwable t) {
            Log.w(TAG, "读不到热点状态: " + t);
            return -1;
        }
    }

    // ---- 杂 ----

    private static WifiManager wifi(Context ctx) {
        return (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    }

    /** 轮到成立为止。**在 IO 线程上调**。 */
    private static boolean pollUntil(Cond c) {
        return pollUntil(c, POLL_MAX);
    }

    /** 同上,自己定上限。蓝牙开关比 WLAN 慢一个量级,默认那 1.5 秒不够。 */
    private static boolean pollUntil(Cond c, int max) {
        for (int i = 0; i < max; i++) {
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (c.ok()) return true;
        }
        return false;
    }

    private interface Cond { boolean ok(); }

    private static void post(Result cb, boolean ok, String why) {
        if (cb != null) MAIN.post(() -> cb.done(ok, why));
    }

    /** 异常压成一行。反射这条路上的失败原因(NoSuchMethod / SecurityException)全靠它交代。 */
    private static String brief(Throwable t) {
        Throwable c = t.getCause() != null ? t.getCause() : t;
        String m = c.getMessage();
        return c.getClass().getSimpleName() + (m == null || m.isEmpty() ? "" : " " + m);
    }
}
