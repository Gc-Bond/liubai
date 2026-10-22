package com.liubai.blank.privileged;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.liubai.blank.privileged.server.ServerClient;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 车身控制:车窗、天窗、遮阳帘、灯、充电、方向盘加热这些**空调以外**的车控。
 *
 * <h3>通道</h3>
 *
 * 和 {@link AirCon} 的写是同一条 —— {@code android.hardware.BYDAutoManager} 那条
 * native bus,由特权 daemon({@code BlankServer})代发。区别只在设备号和字段名:
 * {@link ServerClient#nativeSet} 拿字段名去 {@code BYDAutoFeatureIds} 反射取 id,
 * 所以**加一项车控不用动提权层**,填对「设备号 + 字段名 + 值」就行。
 *
 * 那些 id 在 framework 里是按车型分支初始化的({@code isCanFD}/{@code isToyota},见
 * {@code carsnapshot/framework/.../BYDAutoFeatureIds.java} 的 static 块),按字段名反射
 * 正好拿到本车的真值,跨车型不用改 —— 这也是**不能把 id 抄成数字写死**的原因。
 *
 * 参数全部来自真车 framework 反编译的设备类源码(不是猜的):比如
 * {@code BYDAutoBodyworkDevice.setMoonRoofState} 明写着走
 * {@code BODYWORK_MOON_ROOF_OPEN_PERCENT_SET}、值域 0/21~100/252/253/254。
 *
 * <h3>⚠️ 这个类里只有日行灯在真车上发过</h3>
 *
 * {@link AirCon} 的空调和座椅是 2026-07-30 / 08-02 真车验过的(走的就是这条 native 总线;
 * 写不通的是**另一条** service call 那条路,被 BYDAUTO_AC_SET 的 signature 权限拦死)。
 * 2026-08-17 日行灯验通 —— 意义是**这条总线对 AirCon 以外的设备号(DEV_LIGHT)也通**:
 * 写完 150ms 回读一致,车上灯跟着走,一发即中没有重试。
 * 但**这个类里其余每一项都还没发过**。参数对不代表车身会动 —— CAN 侧可能静默丢帧
 * (座椅当初就是这么栽的,见 {@code AirCon.WRITE_RETRY}),也可能这台车压根没装配。
 *
 * 所以写操作一律**回读确认**:发完等一会儿读回来比一比,对不上就如实说没成,
 * 不许嘴上说成了。没有回读字段的动作类(车窗全开这种)只能报"发出去了"。
 *
 * <h3>⚠️ 动车身的项要看档位</h3>
 *
 * 车窗、天窗、遮阳帘、后备箱在行驶中被误触是安全问题 —— 迪友那张车控卡为此加了
 * 拦截,非 P 档直接弹「请在P档下操作」(反编译 3.0.1,{@code u4/c.java:181})。
 * 留白同样办:见 {@link #needsParking} 和 {@link #inParking}。
 */
public final class CarCtrl {

    private static final String TAG = "BlankCarCtrl";

    // ---- 设备号,来自 carsnapshot/framework/.../BYDAutoConstants.java ----
    /** 车身:车窗 / 天窗 / 遮阳帘。 */
    public static final int DEV_BODYWORK = 1001;
    /** 灯:日行灯。 */
    public static final int DEV_LIGHT = 1004;
    /** 充电:无线充 / SOC 保电。 */
    public static final int DEV_CHARGING = 1009;
    /** 变速箱:只用来读档位。 */
    public static final int DEV_GEARBOX = 1011;
    /** 发动机:引擎音模拟。 */
    public static final int DEV_ENGINE = 1012;
    /** 设置:座椅 / 方向盘加热 / 后备箱 / 动能回收。 */
    public static final int DEV_SETTING = 1023;
    /** 车速:只用来读,见 {@link #speed()}。 */
    public static final int DEV_SPEED = 1013;
    /** 统计:电量% / 续航里程这类,只用来读,见 {@link #batteryPercent()}。 */
    public static final int DEV_STATISTIC = 1014;

    /** 档位读法:{@code BYDAutoGearboxDevice.getGearboxAutoModeType},P 档 = 1。 */
    private static final String F_GEAR = "GEARBOX_AUTO_MODE_TYPE";
    private static final int GEAR_P = 1;

    /**
     * 充电桩工作状态字段名,配 {@link #DEV_CHARGING}。取值来自
     * {@code BYDAutoChargingDevice}:1=ready、2=start(正在充)、3=finish、4=terminate。
     */
    public static final String F_CHARGING_WORK_STATE = "CHARGING_CHARGER_WORK_STATE";
    public static final int CHARGING_WORK_STATE_START = 2;

    /**
     * 回读的间隔。和 {@code AirCon.WRITE_CONFIRM_MS} 同一个值、同一个理由:
     * 真车实测 postEvent 在写后约 19ms 到;读得比车身回传还早的话,
     * 读到的全是旧值,看着像"写不进去"。
     */
    private static final int CONFIRM_MS = 150;

    /**
     * 回读确认最多等到这儿。**不能只等一个固定时长就下结论** —— 150ms 是在空调/座椅上
     * 量出来的,充电/灯这些设备的回传慢得多:2026-08-17 真车实测,SOC 保电和日行灯
     * 写完 150ms 读回来还是旧值,于是误报"没生效",而值其实是写进去了的。
     *
     * 所以改成**读到期望值就收工**:快的设备照旧一轮就返回(不比原来慢),
     * 慢的设备多等几轮。等满还是不对才算真没生效。
     */
    private static final int CONFIRM_TIMEOUT_MS = 1500;

    /** 串行:通道是单条 adb 连接,并发发包会互相打断(同 AirCon.IO)。 */
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "blank-carctrl");
        t.setDaemon(true);
        return t;
    });

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private CarCtrl() {}

    public interface Result {
        /**
         * 主线程回调。
         *
         * @param ok    确认生效了没。**动作类(没有回读字段)恒为 true**,那只代表
         *              "发出去了没报错",不代表车身真动了 —— 别拿它当"成功"渲染
         * @param value 回读到的值;读不到或动作类是 -1
         */
        void onDone(boolean ok, int value);
    }

    public static boolean available() {
        return Shell.ready();
    }

    /**
     * 读一个字段。**同步**,只能在后台线程调。读不到返回 -1。
     */
    public static int get(int device, String field) {
        try {
            return ServerClient.nativeGet(device, field);
        } catch (Throwable t) {
            Log.w(TAG, "读 " + field + " 失败: " + t);
            return -1;
        }
    }

    /**
     * 读一个 float 字段(四舍五入成 int)。**同步**,只能在后台线程调。
     * 走的是专门的 {@link ServerClient#nativeGetFloat} 通道,不是 {@link #get}——
     * 原因见 {@link com.liubai.blank.privileged.server.BlankApi#TX_NATIVE_GET_FLOAT}
     * 的注释。读不到返回 -1。
     */
    public static int getFloat(int device, String field) {
        try {
            int v = ServerClient.nativeGetFloat(device, field);
            return v == Integer.MIN_VALUE ? -1 : v;
        } catch (Throwable t) {
            Log.w(TAG, "读 " + field + " 失败: " + t);
            return -1;
        }
    }

    /** 读车速(km/h)。**同步**,只能在后台线程调。读不到返回 -1。 */
    public static int speed() {
        return getFloat(DEV_SPEED, "SPEED_AUTO_SPEED");
    }

    /** 读当前电量百分比(0~100)。**同步**,只能在后台线程调。读不到返回 -1。 */
    public static int batteryPercent() {
        return getFloat(DEV_STATISTIC, "STATISTIC_ELEC_PERCENTAGE");
    }

    /** 异步读一个字段,回调在主线程。{@code ok} = 读到了没(读不到时 value 是 -1)。 */
    public static void read(final int device, final String field, final Result cb) {
        IO.execute(() -> {
            final int v = get(device, field);
            if (cb != null) MAIN.post(() -> cb.onDone(v >= 0, v));
        });
    }

    /** 异步读车速,回调在主线程。配 {@link #speed()},同一套异步包法。 */
    public static void readSpeed(final Result cb) {
        IO.execute(() -> {
            final int v = speed();
            if (cb != null) MAIN.post(() -> cb.onDone(v >= 0, v));
        });
    }

    /** 异步读电量百分比,回调在主线程。配 {@link #batteryPercent()}。 */
    public static void readBatteryPercent(final Result cb) {
        IO.execute(() -> {
            final int v = batteryPercent();
            if (cb != null) MAIN.post(() -> cb.onDone(v >= 0, v));
        });
    }

    /**
     * 写一个字段,写完回读 {@code readField} 确认。
     *
     * @param readField 回读用的字段,null = 这一项没有可读状态(动作类),那就只发不确认
     * @param want      期望回读到的值;{@code readField} 为 null 时忽略
     */
    public static void set(final int device, final String writeField, final int value,
                           final String readField, final int want, final Result cb) {
        IO.execute(() -> {
            int[] got = {-1};
            boolean ok = writeConfirm(device, writeField, value, readField, want, got);
            final boolean fok = ok;
            final int fgot = got[0];
            if (cb != null) MAIN.post(() -> cb.onDone(fok, fgot));
        });
    }

    /**
     * 写一条 + 回读确认。**同步,只能在 {@link #IO} 线程上调**。
     *
     * @param readField null = 没有可读回的状态(动作类),那就只发不确认、恒真
     * @param got       出参:回读到的值(读不到 -1)
     */
    private static boolean writeConfirm(int device, String writeField, int value,
                                        String readField, int want, int[] got) {
        got[0] = -1;
        try {
            int ret = ServerClient.nativeSet(device, new String[]{writeField}, new int[]{value});
            Log.i(TAG, "写 device=" + device + " " + writeField + "=" + value + " -> " + ret);
            if (readField == null) {
                // 动作类:没有可读回的状态(车窗"全开"不是一个能读回来的字段,
                // 读回来的是开度百分比,而且车窗正在动、这一刻读到多少都不算数)
                return true;
            }
            // 读到期望值就收工,读不到就再等一轮(见 CONFIRM_TIMEOUT_MS)
            for (int waited = 0; waited < CONFIRM_TIMEOUT_MS; waited += CONFIRM_MS) {
                sleep(CONFIRM_MS);
                got[0] = get(device, readField);
                if (got[0] == want) return true;
            }
            Log.w(TAG, writeField + " 写了没生效(读回 " + got[0] + ",想要 " + want + ")");
        } catch (Throwable t) {
            Log.w(TAG, "写 " + writeField + " 失败: " + t);
        }
        return false;
    }

    // ---- SOC 保电:一张卡管两个字段 ----

    /**
     * 保电目标的三个档位(%)。上下限来自 {@code BYDAutoSettingDevice}:
     * {@code SET_DR_SOC_TARGET_MAX = 70}、DM25 平台下限 {@code DM25_SOC_TARGET_MIN = 25}
     * (更老的 DM20 是 15,框架层 {@code setSOCTarget} 统一校验 15~70)。
     */
    public static final int[] SOC_STEPS = {25, 50, 70};

    /** 开关在**充电**设备上,只有开/关({@code SOC_SAVE_SWITCH_OFF=1 / ON=2})。 */
    private static final String F_SOC_SW = "CHARGING_SOC_SAVE_SWITCH";
    private static final String F_SOC_SW_SET = "CHARGING_SOC_SAVE_SWITCH_SET";
    private static final int SOC_SW_OFF = 1, SOC_SW_ON = 2;

    /** 目标百分比在**设置**设备上,是另一个字段({@code BYDAutoSettingDevice.setSOCTarget})。 */
    private static final String F_SOC_TARGET = "SET_DR_SOC_TARGET";
    private static final String F_SOC_TARGET_SET = "SET_DR_SOC_TARGET_SET";
    private static final int SOC_TARGET_MIN = 15, SOC_TARGET_MAX = 70;

    /**
     * 设 SOC 保电。{@code pct <= 0} = 关,否则开到这个百分比。
     *
     * **两条写要分开发、各自回读** —— 开关和目标值在两个设备上(1009 / 1023),
     * 紧邻发会互相挤掉帧(座椅那边实测过:返回 0 但车身静默没动)。
     * 回读确认本身就至少隔了 {@link #CONFIRM_MS},顺序写正好把间隔带上了。
     *
     * 顺序是**先落目标值再开开关**:反过来的话开关一开、上一次的旧目标值先生效一瞬。
     *
     * 回调的 value 带回**这一刻车上的真实百分比**(关着就是 0),UI 直接拿去显示。
     */
    public static void socSave(final int pct, final Result cb) {
        IO.execute(() -> {
            boolean ok;
            int[] got = {-1};
            if (pct <= 0) {
                ok = writeConfirm(DEV_CHARGING, F_SOC_SW_SET, SOC_SW_OFF, F_SOC_SW, SOC_SW_OFF, got);
            } else {
                int target = Math.max(SOC_TARGET_MIN, Math.min(SOC_TARGET_MAX, pct));
                ok = writeConfirm(DEV_SETTING, F_SOC_TARGET_SET, target, F_SOC_TARGET, target, got)
                        && writeConfirm(DEV_CHARGING, F_SOC_SW_SET, SOC_SW_ON, F_SOC_SW, SOC_SW_ON, got);
            }
            final boolean fok = ok;
            final int now = readSocPct();
            if (cb != null) MAIN.post(() -> cb.onDone(fok, now));
        });
    }

    /** 异步读 SOC 保电现在是多少。回调 value:0 = 关,>0 = 百分比,-1 = 读不到。 */
    public static void readSocSave(final Result cb) {
        IO.execute(() -> {
            final int pct = readSocPct();
            if (cb != null) MAIN.post(() -> cb.onDone(pct >= 0, pct));
        });
    }

    /**
     * 同步读 SOC 保电:0 = 关,>0 = 目标百分比,-1 = 读不到。只能在 {@link #IO} 线程上调。
     *
     * 开关关着就不必再读目标值 —— 那时它是多少都不影响车,读它只是白等一次 native 往返。
     * **读回来的百分比不一定是 {@link #SOC_STEPS} 里那三档**:原厂界面能调任意值,
     * 所以 UI 要按真实值显示,不许四舍五入到自己那三档(那是在骗人)。
     */
    private static int readSocPct() {
        int sw = get(DEV_CHARGING, F_SOC_SW);
        if (sw == SOC_SW_OFF) return 0;
        if (sw != SOC_SW_ON) return -1;
        int target = get(DEV_SETTING, F_SOC_TARGET);
        return target < 0 ? -1 : target;
    }

    /**
     * 座椅记忆字段,都在**设置**设备(1023)上。LF=主驾,值=位置号(1/2)。
     * 字段名来自 {@code BYDAutoSettingDevice} 反编译,反射拿 id 时自动按本车
     * CAN FD 分支取真值,不手写数字。
     */
    private static final String F_MEM_SAVE = "SET_LF_MEMORY_LOCATION_SET";
    private static final String F_MEM_WAKE = "SET_LF_MEMORY_LOCATION_WAKE_SET";

    /**
     * 座椅记忆。save=true 把当前座椅位置存进记忆位(原厂长按),save=false 恢复
     * 记忆位(原厂短按)。pos=1/2。走 {@link #act} 同一条动作通道,回调带回
     * 「发出去了没报错」—— 动作类没有能回读的状态可比,车身动没动得看现场。
     */
    public static void seatMemory(boolean save, int pos, Result cb) {
        act(DEV_SETTING, new String[]{save ? F_MEM_SAVE : F_MEM_WAKE}, new int[]{pos}, cb);
    }

    /**
     * 座椅/座垫方向字段,都在**设置**设备(1023)上。值域:0=停,1/2=两个方向
     * (头枕多 3=左 4=右)。字段名来自 {@code BYDAutoSettingDevice} 反编译
     * (SeatAdjustModel.setXxxValue / SeatAdjustView 常量),反射拿 id 走 native bus。
     *
     * **底下两个本车没有对应硬件**,不接 UI(见 {@link com.liubai.blank.ui.SeatMemoryPopup}):
     * 头枕(配置 SETTING_LF_HEADREST_CONFIG 为 0)、腿托(2026-08-22 车主确认)。
     * 字段在反编译里存在**不等于这台车装了**,留着是给别的车型。
     */
    public static final String F_SEAT_VERT   = "SET_DRIVER_SEAT_HEIGHT_SIGNAL_SET";
    public static final String F_SEAT_HORIZ  = "SET_DRIVER_SEAT_BALANCE_SIGNAL_SET";
    public static final String F_SEAT_BACK   = "SET_DRIVER_SEAT_BACKREST_SIGNAL_SET";
    public static final String F_CUSHION_VERT = "SET_DRIVER_SEAT_BIDET_SIGNAL_SET";
    public static final String F_LEG_VERT    = "SET_LF_LEG_REST_SIGNAL_SET";
    public static final String F_HEADREST    = "SET_LF_HEADREST_SIGNAL_SET";

    /** 座椅/座垫方向调节。field 是上面的字段,value=方向值(0=停)。走动作通道。 */
    public static void seatAdjust(String field, int value, Result cb) {
        act(DEV_SETTING, new String[]{field}, new int[]{value}, cb);
    }

    /**
     * 动作类:一次发多条,不回读确认。
     *
     * 车窗四扇各有一个字段,要**一次 setIntArray 发下去**而不是连发四条 —— 同一个 feature
     * 上紧邻的写会互相挤掉帧(座椅那边实测过:返回 0 但车身静默没动)。
     * {@code ServerClient.nativeSet} 收到多字段时走的就是 setIntArray。
     *
     * 回调里的 ok 只代表「发出去了没报错」,不代表车窗真的动了 —— 动作类没有能回读的
     * 状态可比(读回来的是开度百分比,而且车窗正在动,这一刻读到多少都不算数)。
     */
    public static void act(final int device, final String[] fields, final int[] values,
                           final Result cb) {
        IO.execute(() -> {
            boolean ok = false;
            try {
                int ret = ServerClient.nativeSet(device, fields, values);
                Log.i(TAG, "动作 device=" + device + " " + java.util.Arrays.toString(fields)
                        + "=" + java.util.Arrays.toString(values) + " -> " + ret);
                // 0 = 成功,负值 = 本地异常或车身拒绝(BYDAUTO_COMMAND_RESULT_*)。
                // 动作类没有可回读状态,但至少要把「没发下去」反馈给调用方,
                // 免得本地反射失败也被当成发出去了(座椅记忆踩过:字段在嵌套类里
                // 反射 NoSuchField,返回 MIN_VALUE 却静默)。
                ok = ret >= 0;
            } catch (Throwable t) {
                Log.w(TAG, "动作失败: " + t);
            }
            final boolean fok = ok;
            if (cb != null) MAIN.post(() -> cb.onDone(fok, -1));
        });
    }

    /**
     * 在不在 P 档。**读不到时返回 true(放行)** —— 这台车要是读不出档位,
     * 拦下来等于这些功能永远用不了;车身控制本身在非 P 档也有车辆自己的一道拦截。
     */
    public static boolean inParking() {
        int g = get(DEV_GEARBOX, F_GEAR);
        if (g < 0) {
            Log.i(TAG, "读不到档位,按放行处理");
            return true;
        }
        return g == GEAR_P;
    }

    /** 这一项动不动车身。动车身的在行驶中要拦(见类注释)。 */
    public static boolean needsParking(int device) {
        return device == DEV_BODYWORK;
    }

    private static void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
