package com.liubai.blank.privileged;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;
import android.text.TextUtils;
import android.util.Log;

import com.liubai.blank.privileged.server.ServerClient;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 空调控制:读、写走的是两条完全不同的通道。
 *
 * **读**:service call 系统的 {@code IAirConditioningService}(binder 注册名 airconditioning)。
 * 走现有提权链(内置 adb / Shizuku)就够,不需要平台签名——读取的是服务端缓存字段,
 * 不碰底层设备对象,不触发权限检查。
 *
 * **写**:上面这条路走不通。完整链路(services.jar 反编译 + system_server 日志双证,
 * 2026-07-29 护卫舰07 实测钉死):{@code processXxxButtonClicked()} 进得去、方法体也真执行了,
 * 但第一句 {@code enforceCallingOrSelfPermission(BYDAUTO_AC_SET)}(signature 级)必拒,
 * {@code SecurityException} 被服务端 catch 静默吞掉——服务端内部字段会先跳一下(读数变了),
 * 但车身完全没反应,~2 秒后 doubleCheck 从车身读回真值又弹回去。早先"读写全通"的结论是
 * 只看了读数变化、没验证车里有没有真反应,是误判。
 *
 * 真正能写的是 {@code android.hardware.BYDAutoManager} 这条 native bus(设备号
 * {@code BYDAUTO_DEVICE_AC=1000}),跟 {@code IAirConditioningService} 是两套完全独立的实现:
 * 它自己只有一道包名黑名单(见反编译源码 mForbidCall/StrategyManager),没有签名校验。
 * 外层 {@code BYDAutoAcDevice} 包装类里那些 enforceCallingOrSelfPermission 检查,查的是
 * 调用方自己传进去的 Context——这条路径是从第三方车机桌面 DiStyle(反编译 v2.1.6)扒出来的:
 * 它在 app_process 常驻进程里用 {@code ActivityThread.systemMain()→getSystemContext()→
 * createPackageContext(pkg, CONTEXT_IGNORE_SECURITY)} 拿到一个自带"忽略权限检查"标记的
 * Context,这类检查在本地直接短路放行。而我们是直接调 {@code BYDAutoManager.setInt/setIntArray},
 * 连这层包装检查都不用绕。2026-07-30 真车实测:风量档位调整稳定生效、不反弹。
 *
 * 实现见 {@link com.liubai.blank.privileged.server.BlankService#systemIgnoreSecurityContext}/
 * {@code nativeAcSet}——必须活在已有的特权 daemon(BlankServer)里,原因有二:
 * 一是它不是 app、不受 hidden API 名单限制,反射 ActivityThread 才不会被拦;
 * 二是它已经用自连 adb 拿到 uid 2000 身份。这也意味着**这条写路径目前只认内置 adb 通道,
 * Shizuku 兜底通道拿不到 BlankServer,写操作在纯 Shizuku 环境下会失败**(读不受影响)。
 *
 * 读的 transaction 编号来自 framework.jar 反编译(carsnapshot/framework/…/IAirConditioningService.java)。
 * 座椅加热/通风(seat())走同一条 native bus,设备号是 SETTING(1023)不是 AC(1000);
 * 2026-08-02 打通,详见 seat() 方法注释。
 */
public final class AirCon {

    private static final String TAG = "BlankAirCon";
    private static final String SVC = "airconditioning";

    // ---- 读(getXxxButtonState / getXxxValue) ----
    private static final int GET_MAX     = 3;    // 最大制冷
    private static final int GET_COMPR   = 4;    // A/C 压缩机
    private static final int GET_DEF_R   = 5;    // 后除霜
    private static final int GET_W_FACE  = 8;    // 吹面
    private static final int GET_W_FOOT  = 9;    // 吹脚
    private static final int GET_W_DEF   = 10;   // 吹风挡
    private static final int GET_VENT    = 13;   // 通风
    private static final int GET_FAN     = 14;
    private static final int GET_AREA    = 15;   // 温度分区 = 分控
    private static final int GET_TEMP    = 16;   // String,如 "26"
    private static final int GET_AC      = 17;   // 空调电源。**0=开 1=关,是反的**
    private static final int GET_AUTO    = 18;
    private static final int GET_CYCLE   = 19;   // 内循环
    private static final int GET_DEFROST = 20;   // 前除霜
    private static final int GET_TEMP_R  = 21;   // 副驾温度

    // ---- 写(processXxxButtonClicked) ----
    // 加减类是无参的 processXxxButtonClicked(),直接发就行。
    public static final int TEMP_UP     = 48;
    public static final int TEMP_DOWN   = 49;
    public static final int TEMP_R_UP   = 50;   // 副驾,分控开着才有意义
    public static final int TEMP_R_DOWN = 51;
    public static final int FAN_UP      = 59;
    public static final int FAN_DOWN    = 60;
    // 开关类的签名是 processXxxButtonClicked(boolean),**必须显式传目标状态**。
    // 不传参数时服务端 parcel.readInt() 从空 Parcel 读出来恒是 0 → false,
    // 于是每次点击都只发"关",永远开不起来——这正是开关按钮点了没反应的原因。
    public static final int MAX        = 29;   // 最大制冷
    public static final int COMPRESSOR = 30;   // A/C
    public static final int DEFROST_R  = 31;   // 后除霜
    public static final int W_FACE     = 32;   // 吹面
    public static final int W_FOOT     = 33;   // 吹脚
    public static final int W_DEF      = 34;   // 吹风挡
    public static final int VENT       = 37;   // 通风
    public static final int AREA       = 36;   // 分控(温度分区)。读侧叫 TemperatureArea,写侧叫 TemperatureMode
    public static final int AC         = 54;   // 空调电源
    public static final int AUTO       = 55;
    public static final int CYCLE      = 57;
    public static final int DEFROST    = 58;

    /** service call 要串行:通道是单条 adb 连接,并发发包会互相打断(见 AdbConnection 的 open 串行化)。 */
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "blank-aircon");
        t.setDaemon(true);
        return t;
    });

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private AirCon() {}

    // ---- 模拟器 mock ----

    /**
     * 车机之外(模拟器/开发机)根本没有 airconditioning 这个 binder 服务,读写全落空,
     * 界面永远是 "--" 且点什么都没反应 —— UI 就没法离车调了。
     *
     * 所以先探一次服务在不在,不在就切到内存 mock:状态存在本地,读写自成闭环,
     * 所有控件都是活的。真车上服务在,这一层完全不介入。
     */
    private static Boolean mock;

    /**
     * **只在拿到明确结论时才缓存**。`service check` 的回答只有两种
     * ("Service xxx: found" / "…: not found"),除此之外的输出(null、空串、exec 失败)
     * 一律算"这次没问出来",保持 mock 为 null 让下次重判。
     *
     * 早先是 {@code mock = r.out == null || r.out.contains("not found")} —— 空串会落到
     * else 上、把 mock 定成 false(当成真车)。而这个字段是 static、**进程内只判一次**,
     * 一旦判错,之后所有写操作都走 `service call` 打在一个不存在的服务上,
     * 表现是**整条 Dock 的车控全部失灵、而且重启 app 之前好不了**(实测撞上过:
     * 覆盖安装后进程重启,Shizuku 的 binder 正在重新送达,exec 就在那个窗口里抖)。
     *
     * 判不出来的那次按真车走(返回 false),和 {@link #available()} 的把关一致 ——
     * 提权没就绪本来就控不了车,少一次误切 mock 比蒙对一次重要。
     */
    private static boolean mock() {
        if (mock == null) {
            if (!Shell.ready()) return false;         // 提权还没就绪,别把结论定死
            Shell.Result r = Shell.exec("service check " + SVC);
            String out = r.out == null ? "" : r.out.trim();
            // "not found" 里也含 "found",必须先判前者
            if (out.contains("not found")) mock = true;
            else if (out.contains("found")) mock = false;
            else {
                Log.w(TAG, "service check 没问出结果,这次按真车走,下次重判: " + r);
                return false;
            }
            Log.i(TAG, "mock=" + mock + " (service check: " + out + ")");
        }
        return mock;
    }

    /** mock 的那份车况。字段含义与 State 一一对应,初值取一个正常行驶时的样子。 */
    private static final class Mock {
        float temp = 26f, tempR = 26f;
        int fan = 3;
        boolean power = true, auto = true, compressor = true, max = false, dual = false,
                cycle = true, defrost = false, defrostR = false, vent = false,
                face = true, foot = true, windDef = false;
        int heatL = 0, ventL = 0, heatR = 0, ventR = 0;   // 座椅加热/通风 0~3 档
    }

    private static final Mock M = new Mock();

    private static String fmt(float v) {
        return v == Math.rint(v) ? String.valueOf((int) v) : String.valueOf(v);
    }

    private static State mockState() {
        return new State(fmt(M.temp), fmt(M.dual ? M.tempR : M.temp), M.fan,
                M.power, M.auto, M.compressor, M.max, M.dual, M.cycle,
                M.defrost, M.defrostR, M.vent, M.face, M.foot, M.windDef,
                M.heatL, M.ventL, M.heatR, M.ventR, true);
    }

    /**
     * 把一条指令作用到 mock 状态上,语义尽量贴真车(**温度整度一格**、风量 0~7、开关取反)。
     *
     * 温度**只走整数**,上下限取 {@link #TEMP_MIN}/{@link #TEMP_MAX}(17~33)。
     * 依据:迪友的 `CarInfoBean` 把温度存成一张整数表
     * ({@code acTemperatureString = {"17°",…,"33°"}}),加减就是挪下标——车上压根没有小数。
     */
    private static void mockApply(int code) {
        // ensureOn:关机状态下动任何车控都先自动开机(电源键除外,它就是开关本身)。
        // 物理面板就是这个语义,服务端大概率自带 —— 所以 app 侧不预发开机指令,
        // 只让 mock 对齐;真车行为等写通道打通后验证(见 CARCTL-FINDINGS)。
        //
        // 降风量先不开机:降到 0 就是"不要风",这时候反而把空调带起来是反的。
        // 降完还剩风量的情况在 switch 之后补开机。
        if (code != AC && code != FAN_DOWN && !M.power) M.power = true;
        switch (code) {
            case TEMP_UP:     M.temp = Math.min(TEMP_MAX, M.temp + 1f); break;
            case TEMP_DOWN:   M.temp = Math.max(TEMP_MIN, M.temp - 1f); break;
            // 动副驾温度自动开分控(原型语义):不然没开分控时调了也会被主驾值同步回去
            case TEMP_R_UP:   M.dual = true; M.tempR = Math.min(TEMP_MAX, M.tempR + 1f); break;
            case TEMP_R_DOWN: M.dual = true; M.tempR = Math.max(TEMP_MIN, M.tempR - 1f); break;
            case FAN_UP:      M.fan = Math.min(7, M.fan + 1); M.auto = false; break;
            case FAN_DOWN:    M.fan = Math.max(0, M.fan - 1); M.auto = false; break;
            case AC:          M.power = !M.power; break;
            case AUTO:        M.auto = !M.auto; break;
            case COMPRESSOR:  M.compressor = !M.compressor; break;
            case MAX:         M.max = !M.max; if (M.max) { M.temp = 16f; M.fan = 7; M.compressor = true; } break;
            case CYCLE:       M.cycle = !M.cycle; break;
            case DEFROST:     M.defrost = !M.defrost; if (M.defrost) M.fan = Math.max(M.fan, 5); break;
            case DEFROST_R:   M.defrostR = !M.defrostR; break;
            case VENT:        M.vent = !M.vent; break;
            case AREA:        M.dual = !M.dual; break;   // 关分控时末尾的 tempR=temp 会把副驾拉回同步
            case W_FACE:      M.face = !M.face; break;
            case W_FOOT:      M.foot = !M.foot; break;
            case W_DEF:       M.windDef = !M.windDef; break;
            default: break;
        }
        // 降完还剩风量,说明只是想小点风,照常带起来(承接开头 ensureOn 里排除的那一路)。
        // 反过来"风量降到 0 是否连电源一起关"这里**故意不猜** —— 那是车机侧的联动,
        // app 只写 AC_WIND_LEVEL_SET=0;mock 装作联动只会掩盖真车的实际行为
        if (code == FAN_DOWN && M.fan > 0) M.power = true;
        if (!M.dual) M.tempR = M.temp;
    }

    /**
     * 一次读回来的状态。读不到的字段保持默认(温度 null / 开关 false / 风量 -1)。
     *
     * 分两档读:{@link #refresh} 只取 Dock 要的温度和风量(2 次 service call),
     * {@link #refreshFull} 才把整屏 15 个字段读全(一条命令批量、约 500ms,见 readBatch)。Dock 每次刷新都跑全量太亏。
     * 轻量读回来的 State,除 temp/fan 外都是默认值 —— 别拿去驱动面板。
     */
    public static final class State {
        public final String temp;      // 主驾 "26";读不到是 null
        public final String tempR;     // 副驾
        public final int fan;          // 0~7;读不到是 -1
        /** 空调电源。注意读的是反的(0=开),这里已经转成正常语义。 */
        public final boolean power;
        public final boolean auto, compressor, max, dual, cycle, defrost, defrostR, vent;
        public final boolean face, foot, windDef;   // 吹风方向
        /**
         * 座椅加热/通风档位,0=关 / 1=低 / 2=高;-1 = 读不到或车型没装配。
         * UI 按 -1 隐藏对应控件 —— 座椅通风是选配,别给没有的车画一个死按钮。
         */
        public final int heatL, ventL, heatR, ventR;
        /**
         * 是不是全量读回来的。轻量读只有 temp/fan 可信,其余全是默认值 ——
         * 面板必须认这个标记,否则一次 Dock 刷新就把满屏开关显示成"关"。
         */
        public final boolean full;

        State(String temp, String tempR, int fan, boolean power, boolean auto, boolean compressor,
              boolean max, boolean dual, boolean cycle, boolean defrost, boolean defrostR,
              boolean vent, boolean face, boolean foot, boolean windDef,
              int heatL, int ventL, int heatR, int ventR, boolean full) {
            this.temp = temp; this.tempR = tempR; this.fan = fan;
            this.power = power; this.auto = auto; this.compressor = compressor;
            this.max = max; this.dual = dual; this.cycle = cycle;
            this.defrost = defrost; this.defrostR = defrostR; this.vent = vent;
            this.face = face; this.foot = foot; this.windDef = windDef;
            this.heatL = heatL; this.ventL = ventL; this.heatR = heatR; this.ventR = ventR;
            this.full = full;
        }

        /** 有温度就算读到了——温度是唯一的 String 字段,它通了说明整条链路通。 */
        public boolean valid() { return !TextUtils.isEmpty(temp); }

        public String tempText() { return valid() ? temp + "°" : "--°"; }
    }

    private static State empty() {
        return new State(null, null, -1, false, false, false, false, false,
                false, false, false, false, false, false, false, -1, -1, -1, -1, false);
    }

    /**
     * 最近一次全量读到的真实状态,写操作靠它做乐观 patch(见 {@link #send}),
     * 不必每次都跟着重跑一遍那批全量读 {@link #readAll}。
     * 面板每次打开都会 {@link #refreshFull} 校正一次,漂移不会累积。
     */
    private static volatile State lastKnown = empty();

    public interface Listener {
        /** 主线程回调。 */
        void onState(State s);
    }

    /**
     * 最近一次读到的状态,不发起新的读。
     *
     * 给"乐观改完发现发不出去"用:UI 已经抢先变了色,拿这个重铺一遍就弹回真实值。
     * 别拿它当刷新用 —— 它不主动去问车,真要新数据走 {@link #refreshFull}。
     */
    public static State last() {
        return lastKnown;
    }

    public static boolean available() {
        return Shell.ready();
    }

    /** 车内温度的回调。{@code ok=false} 时 {@code celsius} 无意义。 */
    public interface TempResult {
        /** 主线程回调。 */
        void onTemp(int celsius, boolean ok);
    }

    /**
     * 读**车内实测温度**(顶栏用)。
     *
     * 别跟 {@link State#temp} 混:那是**设定**温度(AC_TEMP_MAIN,人调的那个数),
     * 这里是车厢里此刻多少度(AC_TEMP_INSIDE),两个是不同的 feature。
     *
     * 来源:DiStyle 顶栏就是读的这一个(反编译 v2.1.6,{@code byd_listener/C1779b.java:405}
     * → {@code BYDAutoDeviceManager.getInt(1000, 1031798832)}),那个数字在 framework 的
     * {@code BYDAutoFeatureIds} 里叫 {@code AC_TEMP_INSIDE}。**按名字读不按数字**——
     * 这个 id 随车型分三支(CanFD 1031798832 / Toyota 122 / 其余 1320181776),
     * 服务端拿字段名反射取值(见 BlankService.nativeGet),换车型自动对上。
     *
     * 合法区间 {@code (-100, 100)},照 DiStyle 那条判据。超出即当没读到 ——
     * 读失败返回的 {@link Integer#MIN_VALUE} 也被这条一并挡住,
     * **不能拿 -1 当失败标记**:-1℃ 是个合法温度。
     */
    public static void readInsideTemp(final TempResult cb) {
        IO.execute(() -> {
            final int v = ServerClient.nativeGet(DEVICE_AC, "AC_TEMP_INSIDE");
            final boolean ok = v > -100 && v < 100;
            if (cb != null) MAIN.post(() -> cb.onTemp(v, ok));
        });
    }

    /**
     * 读**车外温度**(顶栏可选项)。
     *
     * 字段是 framework {@code BYDAutoFeatureIds} 里的 {@code AC_TEMP_OUT},和车内温度
     * 同一个设备(AC=1000)、同一条 native bus。判据也照搬车内那条:{@code (-100, 100)}
     * 之外一律当没读到 —— 读失败返回的 {@link Integer#MIN_VALUE} 被这条一并挡住,
     * **不能拿 -1 当失败标记**(-1℃ 是个合法的车外温度,冬天很常见)。
     *
     * **真车上还没验过**:字段名是从 framework 里找出来的,离车环境读不到。
     * 读不到的表现是顶栏那一项自己收起来(见 StatusBarView.setText 的 null 分支),
     * 不会显示成 0 或者 --。
     */
    public static void readOutsideTemp(final TempResult cb) {
        IO.execute(() -> {
            final int v = ServerClient.nativeGet(DEVICE_AC, "AC_TEMP_OUT");
            final boolean ok = v > -100 && v < 100;
            if (cb != null) MAIN.post(() -> cb.onTemp(v, ok));
        });
    }

    /** 轻量读:只取 Dock 用得上的温度和风量。 */
    public static void refresh(final Listener l) {
        IO.execute(() -> {
            final State s = readDock();
            if (l != null) MAIN.post(() -> l.onState(s));
        });
    }

    /** 全量读:面板打开时用,15 个字段一条命令批量读、约 500ms(见 readBatch;refreshFull 会合流去重)。 */
    /** 上次全量读**完成**的时刻;这么久内再来就直接回缓存,不重读(全量读 ~500ms 很贵)。 */
    private static volatile long lastFullAt = 0;
    private static final long FULL_FRESH_MS = 2000;
    /** 有一发全量读在途没?在途时后来者不另起,搭它的车等同一份结果(见 refreshFull)。 */
    private static boolean fullInFlight = false;
    private static final List<Listener> pendingFull = new ArrayList<>();

    /**
     * 全量读一次,回喂监听者。**合流**:开机三处(Home×2 + Dock)和三指连续松手都会短时间内
     * 挤好几次,各读各的就是 15×N 次 binder(readAll 一发就 ~500ms)。这里:刚读过(2s 内)直接
     * 回缓存;已有一发在途就把监听者挂到 {@link #pendingFull}、读完一起喂 —— N 次挤压塌成 1 次读。
     */
    public static void refreshFull(final Listener l) {
        if (SystemClock.elapsedRealtime() - lastFullAt < FULL_FRESH_MS) {
            if (l != null) MAIN.post(() -> l.onState(lastKnown));   // 刚读过,缓存够新
            return;
        }
        synchronized (pendingFull) {
            if (fullInFlight) {                 // 有一发在途:搭车,别再读
                if (l != null) pendingFull.add(l);
                return;
            }
            fullInFlight = true;
        }
        IO.execute(() -> {
            final State s = readAll();          // readAll 自己会写 lastKnown
            lastFullAt = SystemClock.elapsedRealtime();
            final List<Listener> waiters;
            synchronized (pendingFull) {
                fullInFlight = false;
                waiters = new ArrayList<>(pendingFull);
                pendingFull.clear();
            }
            MAIN.post(() -> {
                if (l != null) l.onState(s);
                for (Listener w : waiters) w.onState(s);   // 搭车的一起拿这份新状态
            });
        });
    }

    /**
     * 发一条控制,发完自动重读一次状态回调(车机侧是异步生效的,所以中间等一下)。
     * 返回值不解析——写操作必然带埋点异常,见类注释。
     */
    public static void send(final int code, final Listener after) {
        send(code, 1, after);
    }

    /**
     * 连发 times 次同一条指令 —— Dock 上长按滑动一次跨好几格时用。
     * 加减类没有"设到某个值"的接口,只能一格一格点过去。
     *
     * 开关类忽略 times:重复切换等于没切,发两次就回到原点了。
     *
     * 真车分支是乐观更新:写操作自己就算得出改成了什么值,不再跟着一次全量
     * readAll(15 字段批量读,~500ms)——那才是"半天反应"的来源,所以 Dock 只走轻量 readDock。
     * 全量状态靠 refreshFull() 在面板打开时校正一次,漂移不会累积。
     */
    public static void send(final int code, final int times, final Listener after) {
        IO.execute(() -> {
            if (mock()) {
                for (int i = 0; i < Math.max(1, times); i++) mockApply(code);
                final State ms = mockState();
                if (after != null) MAIN.post(() -> after.onState(ms));
                return;
            }
            final State s = writeReal(code, Math.max(1, times));
            lastKnown = s;
            if (after != null) MAIN.post(() -> after.onState(s));
        });
    }

    /**
     * 把风量直接设到 level(0~7),风量滑条专用。
     *
     * 滑条是**绝对定位**交互,不能走 FAN_UP/FAN_DOWN 那条相对增量的路:
     * {@link #writeFan} 会自己重读一次 GET_FAN 当基准,而 UI 的起始档位来自上一次
     * 回读,两者一旦不同步,松手落到的就是错的档 —— 降到 0 这个边界尤其够不着
     * (得车机实时值恰好 ≤ 滑动格数才减得到 0)。
     *
     * **level == 0 就是关空调**:直接发电源关,不指望 AC_WIND_LEVEL_SET=0 能写进去——
     * 同一条 native bus 上座椅档位写 0 已实测稳定失败(见 {@link #toRaw}),而写失败的
     * 异常是被静默吞掉的,只写风量 0 的话表现就是"划到底了但什么都没发生"。
     * 风量档位本身不动,下次开机还是原来那档。
     */
    public static void fanTo(final int level, final Listener after) {
        IO.execute(() -> {
            if (mock()) {
                mockFanTo(level);
                final State ms = mockState();
                if (after != null) MAIN.post(() -> after.onState(ms));
                return;
            }
            final State s = writeFanTo(level);
            lastKnown = s;
            if (after != null) MAIN.post(() -> after.onState(s));
        });
    }

    /** {@link #fanTo} 的 mock 半边。 */
    private static void mockFanTo(int level) {
        if (level <= 0) {
            M.power = false;      // 划到 0 = 关空调,风量档位留着
            return;
        }
        M.power = true;           // 关着的空调滑一下就带起来,同 ensureOn
        M.fan = Math.min(FAN_MAX, level);
        M.auto = false;           // 手动调风量即退出自动,和车机语义一致
    }

    // ---- 真车写操作:BYDAutoManager native bus,见类头注释 ----

    private static final int TEMP_MIN = 17, TEMP_MAX = 33;   // BYDAutoAcDevice.AC_TEMP_IN_CELSIUS_MIN/MAX
    private static final int FAN_MIN = 0, FAN_MAX = 7;
    private static final int CTRL_SOURCE_UI = 0;              // BYDAutoAcDevice.AC_CTRL_SOURCE_UI_KEY

    /**
     * 按 code 分派到对应的 BYDAutoManager 写调用,返回一个只改了这一个字段的
     * 乐观 State——其余字段照抄 {@link #lastKnown}。开关类忽略 times,加减类
     * 按 times 一次性算好目标值再写。
     */
    private static State writeReal(int code, int times) {
        State base = lastKnown;
        switch (code) {
            case TEMP_UP:     return patchTemp(base, false, writeTemp(false, times));
            case TEMP_DOWN:   return patchTemp(base, false, writeTemp(false, -times));
            case TEMP_R_UP:   return patchTemp(base, true, writeTemp(true, times));
            case TEMP_R_DOWN: return patchTemp(base, true, writeTemp(true, -times));
            case FAN_UP:      return patchFan(base, writeFan(times));
            case FAN_DOWN:    return patchFan(base, writeFan(-times));

            // 单值写,不带 AC_CTRL_SOURCE_SET——BYDAutoAcDevice.setAcMaxCoolingState 就是这么调的。
            case MAX: return patch(base, code, simpleToggle("AC_MAX_COOLING_STATE_SET", GET_MAX, false));

            // 其余开关类都是"目标值 + AC_CTRL_SOURCE_SET"成对写。
            case AC:         return patch(base, code, writePower());   // 老读法是反的,单独处理
            case AUTO:       return patch(base, code, writeAuto());    // native 侧 0=自动/1=手动,方向相反
            case COMPRESSOR: return patch(base, code, simpleToggle("AC_COMPRESSOR_MODE_SET", GET_COMPR, true));
            case CYCLE:      return patch(base, code, writeCycle());    // 读侧 0=内/3=外,和写侧不是一套编码
            case VENT:       return patch(base, code, simpleToggle("AC_VENTILATION_STATE_SET", GET_VENT, true));
            case AREA:       return patch(base, code, simpleToggle("AC_TEMPCTRL_SEPARATE_STATE_SET", GET_AREA, true));
            case DEFROST:    return patch(base, code, simpleToggle("AC_DEFROST_FRONT_STATE_SET", GET_DEFROST, true));
            case DEFROST_R:  return patch(base, code, simpleToggle("AC_DEFROST_REAR_STATE_SET", GET_DEF_R, true));

            case W_FACE: case W_FOOT: case W_DEF: {
                Boolean v = writeWindMode(code);
                return v == null ? base : patch(base, code, v);
            }
            default:
                Log.i(TAG, "writeReal: 未知 code " + code);
                return base;
        }
    }

    /** 只替换 temp/tempR 字段,其余照抄 base。 */
    private static State patchTemp(State base, boolean deputy, int target) {
        return new State(
                deputy ? base.temp : String.valueOf(target),
                deputy ? String.valueOf(target) : base.tempR,
                base.fan, base.power, base.auto, base.compressor, base.max, base.dual, base.cycle,
                base.defrost, base.defrostR, base.vent, base.face, base.foot, base.windDef,
                base.heatL, base.ventL, base.heatR, base.ventR, base.full);
    }

    /** 只替换 fan 字段,其余照抄 base。 */
    private static State patchFan(State base, int target) {
        // 风量决定电源:风量 >0 就是空调开着,风量归 0 就是关。关态下三指把风量拉起来,
        // 乐观态得同步把 power 带起来,不然 Dock 的 refreshHvac(off = !power)照旧显示 OFF
        // —— 和 Dock 自己风量键的 ensureOnOptimistic 一个规矩。松手再全量回读校正(见 reconcileAc)。
        return new State(base.temp, base.tempR, target,
                target > 0, base.auto, base.compressor, base.max, base.dual, base.cycle,
                base.defrost, base.defrostR, base.vent, base.face, base.foot, base.windDef,
                base.heatL, base.ventL, base.heatR, base.ventR, base.full);
    }

    /** 按 code 只替换对应的那一个布尔字段,其余照抄 base。 */
    private static State patch(State base, int code, boolean value) {
        return new State(base.temp, base.tempR, base.fan,
                code == AC ? value : base.power,
                code == AUTO ? value : base.auto,
                code == COMPRESSOR ? value : base.compressor,
                code == MAX ? value : base.max,
                code == AREA ? value : base.dual,
                code == CYCLE ? value : base.cycle,
                code == DEFROST ? value : base.defrost,
                code == DEFROST_R ? value : base.defrostR,
                code == VENT ? value : base.vent,
                code == W_FACE ? value : base.face,
                code == W_FOOT ? value : base.foot,
                code == W_DEF ? value : base.windDef,
                base.heatL, base.ventL, base.heatR, base.ventR, base.full);
    }

    /**
     * 温度是绝对值写,不是点按——直接算好目标度数一次发过去,比老的"点一下走一格"更直接。
     * deputy=false 写主驾(type=1),true 写副驾(type=2),跟 BYDAutoAcDevice.setAcTemperature
     * 的字段形状对齐:AC_TEMPERATURE_UNIT_SET/AC_TEMP_MAIN_SET/AC_TEMP_DEPUTY_SET/
     * AC_TEMP_REAR_SET/AC_CTRL_SOURCE_SET 五个一起写,不改的位置填 0(照抄 DiStyle 的写法,
     * 没有别的凭据能验证 0 是不是"不动"的哨兵值,但它是唯一验证过真实生效的形状)。
     * unit 固定填 1(摄氏度)——老的 GET_TEMP 读回像"26"这种整数,车本来就在摄氏度模式。
     *
     * delta 单位是 **1℃**(DockView.Listener#onTempChange 的约定),和真车一致,不用换算。
     *
     * 早先 UI 侧按 0.5℃ 一格,这里要 round(delta*0.5) 折算成整度——那个半度是我们自己
     * 设计出来的,车上根本没有这个状态:迪友的 `CarInfoBean` 把温度存成一张整数表
     * ({@code acTemperatureString = {"17°","18°",…,"33°"}}),加减就是挪下标;
     * 本类的 TEMP_MIN/MAX(17/33,取自 BYDAutoAcDevice.AC_TEMP_IN_CELSIUS_MIN/MAX)也对得上。
     * 结果是 UI 预览 26.5、车实际 27,两个数同时挂在屏上。2026-08-01 统一成整度。
     */
    private static int writeTemp(boolean deputy, int delta) {
        String curStr = str(deputy ? GET_TEMP_R : GET_TEMP);
        int cur = parseIntOr(curStr, 26);
        int target = clamp(cur + delta, TEMP_MIN, TEMP_MAX);
        int mainVal = deputy ? 0 : target;
        int depVal = deputy ? target : 0;
        nativeSet(new String[]{"AC_TEMPERATURE_UNIT_SET", "AC_TEMP_MAIN_SET",
                        "AC_TEMP_DEPUTY_SET", "AC_TEMP_REAR_SET", "AC_CTRL_SOURCE_SET"},
                new int[]{1, mainVal, depVal, 0, CTRL_SOURCE_UI});
        return target;
    }

    /**
     * {@link #fanTo} 的真车半边:0 = 关电源;>0 时关着的空调要先带起来,
     * 不然风量写下去也不会有风。
     */
    private static State writeFanTo(int level) {
        State base = lastKnown;
        if (level <= 0) {
            writePowerTo(false);
            return patch(base, AC, false);
        }
        if (!base.power) writePowerTo(true);
        int target = clamp(level, FAN_MIN, FAN_MAX);
        nativeSet(new String[]{"AC_WIND_LEVEL_SET", "AC_CTRL_SOURCE_SET"},
                new int[]{target, CTRL_SOURCE_UI});
        return patch(patchFan(base, target), AC, true);
    }

    /** 风量相对增减,Dock 上"点一下加一档"的快捷键用;滑条走 {@link #writeFanTo}。 */
    private static int writeFan(int delta) {
        int cur = num(GET_FAN, 3);
        int target = clamp(cur + delta, FAN_MIN, FAN_MAX);
        nativeSet(new String[]{"AC_WIND_LEVEL_SET", "AC_CTRL_SOURCE_SET"},
                new int[]{target, CTRL_SOURCE_UI});
        return target;
    }

    /** 空调电源:老的 GET_AC 是反的(0=开),native 侧 AC_POWER_STATE_SET 是正常语义(1=开)。 */
    private static boolean writePower() {
        boolean curOn = num(GET_AC, 0) == 0;
        return writePowerTo(!curOn);
    }

    /**
     * 绝对写电源,不是 toggle —— 风量滑条要的是"确保关"/"确保开",
     * 走 {@link #writePower} 那条会把本来就关着的空调反而打开。
     */
    private static boolean writePowerTo(boolean on) {
        nativeSet(new String[]{"AC_POWER_STATE_SET", "AC_CTRL_SOURCE_SET"},
                new int[]{on ? 1 : 0, CTRL_SOURCE_UI});
        return on;
    }

    /** AUTO:native 侧 AC_CTRL_MODE_SET 是"控制模式"(0=自动/1=手动),跟老读法的布尔方向相反。 */
    private static boolean writeAuto() {
        boolean curAuto = num(GET_AUTO, 0) == 1;
        boolean nextAuto = !curAuto;
        nativeSet(new String[]{"AC_CTRL_MODE_SET", "AC_CTRL_SOURCE_SET"},
                new int[]{nextAuto ? 0 : 1, CTRL_SOURCE_UI});
        return nextAuto;
    }

    /** 写侧的内外循环值,来自 {@code BYDAutoAcDevice.AC_CYCLEMODE_*}。读侧编码完全不同,见 {@link #writeCycle}。 */
    private static final int CYCLE_IN  = 1;
    private static final int CYCLE_OUT = 0;

    /**
     * 内外循环。**读写不是一套编码**,所以不能走 {@link #simpleToggle}:
     *
     * <ul>
     *   <li>读(service call {@link #GET_CYCLE}):**0 = 内循环,3 = 外循环**。
     *       2026-08-06 真车 diff 实测 —— 用车机原生空调面板切一下,这个 code 从 0 跳到 3。</li>
     *   <li>写(BYDAutoFeatureIds {@code AC_CYCLE_MODE_SET}):
     *       {@code AC_CYCLEMODE_INLOOP=1} / {@code AC_CYCLEMODE_OUTLOOP=0}。</li>
     * </ul>
     *
     * 旧代码走 simpleToggle,把读回来的值**直接当布尔翻转再写回去**:内循环读 0,
     * `0 != 1` 于是算出 target=1 —— 又是内循环,**怎么点都出不去**。外循环读 3 同样
     * 算出 1,反倒碰巧能切回内循环。所以表现是"单向的、而且平时点了全没反应"
     * (人多数时候就待在内循环)。真车日志里连点 8 下,8 条全是 `=[1, 0]`。
     *
     * 跟 {@link #writeAuto} 是同一个坑(native 侧 0=自动/1=手动也是反的),
     * 当时只修了 AUTO,循环这条漏了。
     *
     * @return 写完之后是不是内循环
     */
    private static boolean writeCycle() {
        // 只认 0 是内循环:读不到(-1)按"不在内循环"处理,让这一下去开内循环。
        // 别用 0 当读失败的默认值 —— 那正好和"内循环"撞上,分不清是真在内循环还是没读到
        boolean inLoop = num(GET_CYCLE, -1) == 0;
        int target = inLoop ? CYCLE_OUT : CYCLE_IN;
        // 确认时按**读**侧编码比:写侧 1=内/0=外,读侧却是 0=内/3=外(见上),两套不能混
        int wantRead = target == CYCLE_IN ? 0 : 3;

        // 写完回读确认、没生效就重试 —— 和 writeSeat 同一套。内外循环同样会被 CAN 侧
        // 静默丢帧(nativeSet 返回 0 却没落到车身),发完即返回的话就是"点了 UI 变了、
        // 车没动"。两个写字段一次发,主写(AC_CYCLE_MODE_SET)落在里头
        int got = -1;
        for (int i = 0; i <= WRITE_RETRY; i++) {
            nativeSet(new String[]{"AC_CYCLE_MODE_SET", "AC_CTRL_SOURCE_SET"},
                    new int[]{target, CTRL_SOURCE_UI});
            sleep(WRITE_CONFIRM_MS);
            got = num(GET_CYCLE, -1);
            if (got == wantRead) break;
            Log.w(TAG, "cycle 写了没生效(读回 " + got + ",想要 " + wantRead + "),第 "
                    + (i + 1) + "/" + (WRITE_RETRY + 1) + " 次");
        }
        // 返回确认后的真实状态:读到 0=内、3=外;都不是(读挂了)就按刚写的 target 兜底
        return got == 0 ? true : got == 3 ? false : target == CYCLE_IN;
    }

    /**
     * 吹风方向在老服务里是 3 个独立开关(面/脚/除霜挡),native 侧是单值 7 选 1 的
     * AC_WIND_MODE(面/脚/除霜三个方向的组合,见 BYDAutoAcDevice.AC_WINDMODE_*)。
     * 所以要先把 3 个老读数解成三个布尔、翻其中一个、再编码回 1~7——三者全关不在
     * 枚举范围内(现实里也没有"完全不出风"这个模式),这种情况就维持原状不发,返回 null。
     */
    private static Boolean writeWindMode(int code) {
        boolean face = num(GET_W_FACE, 0) == 1;
        boolean foot = num(GET_W_FOOT, 0) == 1;
        boolean defrost = num(GET_W_DEF, 0) == 1;
        if (code == W_FACE) face = !face;
        else if (code == W_FOOT) foot = !foot;
        else defrost = !defrost;

        int mode = windModeOf(face, foot, defrost);
        if (mode < 0) {
            Log.i(TAG, "writeWindMode: 三向全关不合法,维持原状");
            return null;
        }
        nativeSet(new String[]{"AC_WIND_MODE_SET", "AC_CTRL_SOURCE_SET"},
                new int[]{mode, CTRL_SOURCE_UI});
        return code == W_FACE ? face : code == W_FOOT ? foot : defrost;
    }

    /** BYDAutoAcDevice.AC_WINDMODE_* 的编码表,三向全关(0)不在合法范围内。 */
    private static int windModeOf(boolean face, boolean foot, boolean defrost) {
        if (face && !foot && !defrost) return 1;   // FACE
        if (face && foot && !defrost) return 2;    // FACEFOOT
        if (!face && foot && !defrost) return 3;   // FOOT
        if (!face && foot && defrost) return 4;    // FOOTDEFROST
        if (!face && !foot && defrost) return 5;   // DEFROST
        if (face && foot && defrost) return 6;     // FACEFOOTDEFROST
        if (face && !foot && defrost) return 7;    // FACEDEFROST
        return -1;
    }

    /** 单值/成对开关的通用翻转:读老值→取反→按 pairWithSource 决定写单值还是"值+来源"成对。 */
    private static boolean simpleToggle(String setField, int getter, boolean pairWithSource) {
        int cur = num(getter, 0);
        int target = cur == 1 ? 0 : 1;
        if (pairWithSource) {
            nativeSet(new String[]{setField, "AC_CTRL_SOURCE_SET"}, new int[]{target, CTRL_SOURCE_UI});
        } else {
            nativeSet(new String[]{setField}, new int[]{target});
        }
        return target == 1;
    }

    private static final int DEVICE_AC = 1000;        // BYDAUTO_DEVICE_AC
    /**
     * BYDAUTO_DEVICE_SETTING——座椅加热/通风在这个设备上,不是 BODYWORK(1001)。
     * 2026-07-30 从 DiStyle 反编译出 BYDAutoSettingDevice.{get,set}SeatHeatingState
     * 的真实实现才定位到:一直用错设备号(1001)是"写 0 必失败、一打开面板四路全显示开"
     * 的根因——设备号错了但 feature id 名字凑巧还对,所以非零值居然还写成功了。
     */
    private static final int DEVICE_SETTING = 1023;

    /** 转发到特权 daemon 里的 BYDAutoManager 调用;daemon 不可用(没连自建 adb)时安静失败。 */
    private static void nativeSet(String[] fieldNames, int[] values) {
        nativeSet(DEVICE_AC, fieldNames, values);
    }

    private static void nativeSet(int device, String[] fieldNames, int[] values) {
        int ret = ServerClient.nativeSet(device, fieldNames, values);
        Log.i(TAG, "nativeSet device=" + device + " " + java.util.Arrays.toString(fieldNames)
                + "=" + java.util.Arrays.toString(values) + " -> " + ret);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int parseIntOr(String s, int def) {
        if (TextUtils.isEmpty(s)) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /**
     * 座椅加热/通风。**2026-08-02 打通**,根因和最终结论(权威依据是本仓库
     * {@code carsnapshot/framework/…/BYDAutoSettingDevice.java} 的 BYD framework 源码,
     * 不是推断):
     *
     * 1. 设备号 {@link #DEVICE_SETTING}=1023、字段名(读不带 _SET / 写带 _SET)、
     *    seatID(1=主驾 2=副驾)都是对的——这些之前就查对了。
     * 2. **唯一的 bug 是档位值映射反了**:framework 里 OFF=1 / LOW=2 / HIGH=3,写入只放行
     *    1/2/3。旧代码按"关=3、写 0 关"处理,于是写 0 被 framework 直接拒(返回
     *    -2147482645),而读回来的关态 raw=1 又被旧 fromRaw 当成了 1 档 —— 这就是当初
     *    "写不动、四路全显示开"的全部原因。修正见 {@link #toRaw}/{@link #fromRaw}。
     * 3. 读用轮询 getInt 就可信(framework 的 getSeatHeatingState 也是这条路),不需要
     *    注册 listener——本项目 daemon 是短连接模型,也挂不住长期 listener。之前怀疑
     *    "要用 listener"是被反映射造成的假象误导了。
     * 4. framework 里座椅其实支持后排(seatID 3/4),这里只接前排两座,够用。
     */
    public static void seat(final boolean deputy, final boolean heat, final Listener after) {
        IO.execute(() -> {
            if (mock()) {
                mockSeat(deputy, heat);
                final State ms = mockState();
                if (after != null) MAIN.post(() -> after.onState(ms));
                return;
            }
            final State s = writeSeat(deputy, heat);
            lastKnown = s;
            if (after != null) MAIN.post(() -> after.onState(s));
        });
    }

    /** BYDAutoFeatureIds 里座椅加热/通风读侧字段名,写侧就是它加上 "_SET" 后缀。 */
    private static String seatGetField(boolean deputy, boolean heat) {
        return "SET_" + (deputy ? "PASSENGER" : "DRIVER") + "_SEAT_"
                + (heat ? "HEATING" : "VENTILATING") + "_STATE";
    }

    /**
     * 内部档位(0=关,1/2=档)↔ 真车原始值的映射。
     *
     * **2026-08-02 钉死:关=1,低=2,高=3。** 直接来自 BYD framework 源码
     * {@code BYDAutoSettingDevice}(SEAT_HEATING_OFF=1 / LOW=2 / HIGH=3,写入只放行 1/2/3)。
     * 之前猜"关=3、写 0 关"是错的——写 0 稳定失败正是因为 0 不是合法值;而读回来"四路全开"
     * 也是这个映射反了导致的:真车关态 raw=1,被旧 fromRaw(raw==3?0:raw)当成了 1 档。
     * 设备号(1023)、字段名、seatID 映射当时都是对的,唯独差这一位。
     */
    private static int toRaw(int level) { return level + 1; }   // 0→1(关) 1→2(低) 2→3(高)
    private static int fromRaw(int raw) { return raw - 1; }      // 1→0 2→1 3→2

    /** 当前档位,0~2;读不到/异常报 -1 让 UI 隐藏这一行(见 State.heatL 等字段注释)。 */
    private static int seatState(boolean deputy, boolean heat) {
        String field = seatGetField(deputy, heat);
        int raw = ServerClient.nativeGet(DEVICE_SETTING, field);
        int level = raw < 1 || raw > 3 ? -1 : fromRaw(raw);
        Log.i(TAG, "seatState " + field + " raw=" + raw + " -> level=" + level);
        return level;
    }

    /**
     * native 写完等多久再回读确认。**座椅和内外循环共用** —— 两者都会被 CAN 侧静默丢帧。
     *
     * 真车实测 {@code postEvent} 在写后 **19ms** 到(BYDAutoSettingDevice 的回传日志),
     * 150ms 留足余量。早先写完 8~20ms 就重读,**一次都没读到过新值** —— 那份"写后"
     * 日志全程是旧值,看着像"写不进去",其实只是读得比车身回传还早。
     */
    private static final int WRITE_CONFIRM_MS = 150;

    /**
     * 写没生效就自己补,别让人拿手点。**座椅和内外循环共用**。
     *
     * 真车实测座椅「低档→高档」这一跳会被 CAN 侧静默丢弃(nativeSet 照样返回 0、
     * 但没有 postEvent),而且**时好时坏** —— 最惨一次连写 9 次、16 秒才生效。
     * 补两次覆盖绝大多数情况;还不成就如实返回真实状态,让 UI 弹回去,总好过嘴上说成了。
     */
    private static final int WRITE_RETRY = 2;

    private static void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 座椅升一档,**写完确认、没生效就重试**,返回的是确认后的真实档位。
     *
     * 两条真车实测得来的规矩:
     *
     * 1. **互斥的另一路只在它确实开着时才清。** 早先是无条件写 `_SET=关`,而同一张
     *    椅子的加热/通风在车身侧是同一个 feature(framework 的
     *    {@code FEATURE_SEAT_HEATING_AND_VENTILATING}),紧跟主写 5ms 发出去会跟它
     *    抢同一帧 —— 谁赢看车身采样时刻,正是"时好时坏"的由来。加热本来就关着的话,
     *    这条写纯粹在给总线添乱。要清也**先清再写主路**,让主写落在最后。
     *
     * 2. **返回确认值,不是乐观值。** 早先直接拿 target 填 State,车身丢了写也照样
     *    报"已经是高档了"。而面板那侧是本地 `(level+1)%3` 自增,两套计数器一发散
     *    就再也回不来:UI 转完一整圈,车一步没动,人就在那儿狂点。
     */
    private static State writeSeat(boolean deputy, boolean heat) {
        String setField = seatGetField(deputy, heat) + "_SET";

        int cur = seatState(deputy, heat);
        if (cur < 0) cur = 0;   // 读不到/异常时当 0 处理,别把 -1 当档位
        int target = (cur + 1) % 3;

        // 同一张椅子不能又吹又烤。先清另一路、再写主路,且只在它确实开着时才发
        if (target > 0) {
            int otherCur = seatState(deputy, !heat);
            if (otherCur > 0) {
                String otherSetField = seatGetField(deputy, !heat) + "_SET";
                int otherRet = ServerClient.nativeSet(
                        DEVICE_SETTING, new String[]{otherSetField}, new int[]{toRaw(0)});
                Log.i(TAG, "seat 清另一路 " + otherSetField + "=" + toRaw(0) + " -> " + otherRet);
                sleep(WRITE_CONFIRM_MS);   // 让它落定,别和主写挤同一帧
            }
        }

        int got = cur;
        for (int i = 0; i <= WRITE_RETRY; i++) {
            nativeSet(DEVICE_SETTING, new String[]{setField}, new int[]{toRaw(target)});
            sleep(WRITE_CONFIRM_MS);
            got = seatState(deputy, heat);
            if (got == target) break;
            Log.w(TAG, "seat " + setField + " 写了没生效(读回 " + got + ",想要 " + target
                    + "),第 " + (i + 1) + "/" + (WRITE_RETRY + 1) + " 次");
        }
        if (got < 0) got = cur;   // 确认读挂了就按原档位算,别把 -1 当档位传出去

        State base = lastKnown;
        int heatL = base.heatL, ventL = base.ventL, heatR = base.heatR, ventR = base.ventR;
        if (deputy) {
            if (heat) { heatR = got; if (got > 0) ventR = 0; }
            else      { ventR = got; if (got > 0) heatR = 0; }
        } else {
            if (heat) { heatL = got; if (got > 0) ventL = 0; }
            else      { ventL = got; if (got > 0) heatL = 0; }
        }
        return new State(base.temp, base.tempR, base.fan,
                base.power, base.auto, base.compressor, base.max, base.dual, base.cycle,
                base.defrost, base.defrostR, base.vent, base.face, base.foot, base.windDef,
                heatL, ventL, heatR, ventR, base.full);
    }

    /** mock 的座椅档位循环。开加热清通风、开通风清加热 —— 同一张椅子不能又吹又烤。 */
    /**
     * 座椅升一档。**必须和真车写侧同一个循环**(writeSeat 是 `(cur + 1) % 3`):
     * 0=关 / 1 / 2 两档到顶回 0，点三下一圈。
     *
     * 这里原来写的是 % 4(0→1→2→3→0)，于是模拟器上要点**四**下才关得掉，
     * 真车却是三下 —— 一样的操作两种结果，照着模拟器调出来的手感到车上就是错的。
     */
    private static void mockSeat(boolean deputy, boolean heat) {
        if (deputy) {
            if (heat) { M.heatR = (M.heatR + 1) % 3; if (M.heatR > 0) M.ventR = 0; }
            else      { M.ventR = (M.ventR + 1) % 3; if (M.ventR > 0) M.heatR = 0; }
        } else {
            if (heat) { M.heatL = (M.heatL + 1) % 3; if (M.heatL > 0) M.ventL = 0; }
            else      { M.ventL = (M.ventL + 1) % 3; if (M.ventL > 0) M.heatL = 0; }
        }
    }

    // ---- 内部 ----

    private static State readDock() {
        if (!Shell.ready()) return empty();
        if (mock()) return mockState();
        return new State(str(GET_TEMP), null, num(GET_FAN, -1),
                false, false, false, false, false, false, false, false, false, false, false, false,
                -1, -1, -1, -1, false);
    }

    /**
     * readAll 批量读的 15 个 AC 字段,顺序 = 下面 State 构造的前 15 项。
     * 一条命令跑完这 15 个 {@code service call}、一次回传 —— 把 15 次 exec 往返压成 1 次,
     * 这是"面板半天才亮"的根治(每次 Shell.exec 的结果都要走 ContentProvider 回传,见 SelfAdbPrivileged)。
     */
    private static final int[] BATCH = {
            GET_TEMP, GET_TEMP_R, GET_FAN, GET_AC, GET_AUTO, GET_COMPR, GET_MAX, GET_AREA,
            GET_CYCLE, GET_DEFROST, GET_DEF_R, GET_VENT, GET_W_FACE, GET_W_FOOT, GET_W_DEF,
    };
    private static final String BATCH_MARK = "@@BLKAC@@";

    /** 一条命令批量读 {@link #BATCH},返回与它等长、逐项对应的输出段(缺的位置回空串→取默认)。 */
    private static String[] readBatch() {
        StringBuilder cmd = new StringBuilder();
        for (int c : BATCH) {
            cmd.append("service call ").append(SVC).append(' ').append(c)
               .append("; echo ").append(BATCH_MARK).append("; ");
        }
        String out = Shell.exec(cmd.toString()).out;
        String[] parts = out == null ? new String[0] : out.split(BATCH_MARK, -1);
        String[] res = new String[BATCH.length];
        for (int i = 0; i < BATCH.length; i++) res[i] = i < parts.length ? parts[i] : "";
        return res;
    }

    private static State readAll() {
        if (!Shell.ready()) return empty();
        if (mock()) return mockState();
        String[] p = readBatch();   // 15 个字段一次读回(逐段对应 BATCH 顺序)
        State s = new State(
                str(p[0]),          // GET_TEMP
                str(p[1]),          // GET_TEMP_R
                num(p[2], -1),      // GET_FAN
                // 电源位是反的:0 才是开(startAcPower(!z?1:0),z=false 走 start)。
                // 读不到时默认按"开"算,否则提权没通就整个面板灰掉,连状态都看不成。
                num(p[3], 0) == 0,  // GET_AC
                num(p[4], 0) == 1,  // GET_AUTO
                num(p[5], 0) == 1,  // GET_COMPR
                num(p[6], 0) == 1,  // GET_MAX
                num(p[7], 0) == 1,  // GET_AREA
                // 内循环。读侧是 0=内 / 3=外(**不是布尔**,更不是写侧那套 1=内/0=外),
                // 旧的 `== 1` 永远算不出 true —— 这就是循环键从来不亮的原因。见 writeCycle
                num(p[8], -1) == 0, // GET_CYCLE
                num(p[9], 0) == 1,  // GET_DEFROST
                num(p[10], 0) == 1, // GET_DEF_R
                num(p[11], 0) == 1, // GET_VENT
                num(p[12], 0) == 1, // GET_W_FACE
                num(p[13], 0) == 1, // GET_W_FOOT
                num(p[14], 0) == 1, // GET_W_DEF
                // 座椅加热/通风(设备 SETTING=1023,走 ServerClient.nativeGet 另一条路,没并进上面那批)。
                // 装配了的座椅 raw 恒在 1..3,没装配返回 0/65535 → seatState 判 -1 → UI 隐藏这一路。
                seatState(false, true), seatState(false, false),   // 主驾:加热、通风
                seatState(true, true),  seatState(true, false),    // 副驾:加热、通风
                true);
        lastKnown = s;   // 全量读到的才是权威值,写操作的乐观 patch 都以它为底
        return s;
    }

    private static Shell.Result call(int code) {
        return Shell.exec("service call " + SVC + " " + code);
    }

    /**
     * 取 Parcel 里的 hex 字。
     * 正常返回是一行:{@code Result: Parcel(00000000 00000002 00360032 … '…')},
     * 取 "Parcel(" 到第一个单引号之间那段。写操作的异常返回是多行 dump,
     * 这里会解析失败并落到默认值——正是想要的(异常本来就不该当数据用)。
     */
    private static String[] words(String out) {
        if (out == null) return new String[0];
        int a = out.indexOf("Parcel(");
        if (a < 0) return new String[0];
        int b = out.indexOf('\'', a);
        String body = b > a ? out.substring(a + 7, b) : out.substring(a + 7);
        body = body.trim();
        return body.isEmpty() ? new String[0] : body.split("\\s+");
    }

    /** int 返回值:第 1 个字是异常码(0 = 正常),第 2 个字才是值。 */
    private static int num(int code, int def) { return num(call(code).out, def); }

    /** 从一段 service call 输出里取整数(批量读把整段拆开后逐段喂进来,见 readAll)。 */
    private static int num(String out, int def) {
        String[] w = words(out);
        if (w.length < 2 || !"00000000".equals(w[0])) return def;
        try {
            return (int) Long.parseLong(w[1], 16);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** String 返回值:异常码 + 字符数 + UTF-16LE 数据(一个 32 位字装两个字符,低位在前)。 */
    private static String str(int code) { return str(call(code).out); }

    /** 从一段 service call 输出里取字符串(温度)。批量读拆段后逐段喂进来,见 readAll。 */
    private static String str(String out) {
        String[] w = words(out);
        if (w.length < 3 || !"00000000".equals(w[0])) return null;
        int len;
        try {
            len = (int) Long.parseLong(w[1], 16);
        } catch (NumberFormatException e) {
            return null;
        }
        if (len <= 0 || len > 32) return null;   // 温度就两三个字符,长得离谱说明解析跑偏了
        StringBuilder sb = new StringBuilder(len);
        for (int i = 2; i < w.length && sb.length() < len; i++) {
            long v;
            try {
                v = Long.parseLong(w[i], 16);
            } catch (NumberFormatException e) {
                break;
            }
            char lo = (char) (v & 0xFFFF);
            char hi = (char) ((v >>> 16) & 0xFFFF);
            if (sb.length() < len) sb.append(lo);
            if (sb.length() < len) sb.append(hi);
        }
        return sb.toString();
    }
}
