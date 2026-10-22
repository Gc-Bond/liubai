package com.liubai.blank.model;

import com.liubai.blank.R;
import com.liubai.blank.privileged.AirCon;
import com.liubai.blank.privileged.CarCtrl;

/**
 * 车控卡片上的一项。
 *
 * <h3>四类项</h3>
 * <ul>
 *   <li>{@link Kind#SEAT} —— 档位 0/1/2,点一下升一档到顶回 0。走 {@link AirCon#seat}。</li>
 *   <li>{@link Kind#TOGGLE} —— 开关,读回当前值取反再写。走 {@link CarCtrl}。</li>
 *   <li>{@link Kind#LEVEL} —— 数值档位(SOC 保电:关/25%/50%/70%),点一下跳下一档。
 *       它一张卡管两个字段,复合写归 {@link CarCtrl#socSave}。</li>
 *   <li>{@link Kind#ACTION} —— 一次性动作(车窗全开这种),没有"当前状态"可显示。</li>
 *   <li>{@link Kind#SYSTEM} —— **不是车控**。WLAN、热点这些 Android 自己的开关,
 *       和车身、CAN 一点关系都没有,走 {@link com.liubai.blank.privileged.NetSwitch}。
 *       摆在这个枚举里只是因为**桌面上它就是一张同样的开关小卡** —— 摆位、配置面板、
 *       渲染整套都在这儿,为两项另起一套卡片种类不值得。下面那些「设备号 + 写字段名」
 *       对它一概不适用,构造器也是单独的一个。</li>
 * </ul>
 *
 * <h3>⚠️ 验证状态:座椅四项 + 日行灯是真车验过的</h3>
 *
 * 座椅通风/加热 2026-08-02 在护卫舰07 上验通(见 {@code AirCon.seat()})。
 * 日行灯 2026-08-17 验通:连点开/关,写完回读都对得上(1→1、2→2),车上灯也跟着走 ——
 * **车身控制这条通道本身是通的**,不像空调那边写不进去(见 {@code AirCon} 类注释)。
 * **其余每一项都还没在真车上发过一次** —— 参数是从真车 framework 反编译的设备类源码里
 * 一条条抄出来的(不是猜的,见每项后面标的出处),但参数对不等于车身会动:CAN 侧可能
 * 静默丢帧,也可能这台车没装配。所以卡片对未验项一律**按回读结果显示**,发不动就弹回去。
 *
 * <h3>每一项的开/关值都不一样,必须逐项抄源码</h3>
 *
 * 日行灯 1=开 2=关,方向盘加热反过来 1=关 2=开,引擎音又是 0=关 1=开。
 * 没有统一规律,**不许照着别的项推**。
 *
 * <h3>加一项要做什么</h3>
 *
 * 往下面追加一个常量,填「设备号 + 写字段名 + 读字段名 + 值」。提权层不用动 ——
 * {@code BlankService.nativeSet} 按字段名反射取 {@code BYDAutoFeatureIds} 的 id,
 * 而那些 id 在 framework 里按车型分支初始化,反射正好拿到本车的真值。
 * 设备号见 {@link CarCtrl} 的常量,字段名和值域去
 * {@code carsnapshot/framework/.../bydauto/<设备>/BYDAuto*Device.java} 的 set 方法里抄。
 */
public enum CarCtrlItem {

    // ---- 座椅:真车验过的只有这四项 ----
    SEAT_VENT_L("seat_vent_l", "主驾通风", false, false),
    SEAT_HEAT_L("seat_heat_l", "主驾加热", false, true),
    SEAT_VENT_R("seat_vent_r", "副驾通风", true,  false),
    SEAT_HEAT_R("seat_heat_r", "副驾加热", true,  true),

    // ---- 开关类(未验)。值来自 BYDAuto*Device 的 setXxx + 同文件里的常量 ----

    /**
     * 日行灯。BYDAutoLightDevice.setDayTimeLightState:1=开 2=关(DAY_RUNNING_LIGHT_ON/OFF)。
     * **2026-08-17 真车验通**(开关都生效、回读一致)。
     */
    DRL("drl", "日行灯", R.drawable.ic_car_drl, CarCtrl.DEV_LIGHT,
            "LIGHT_DAY_RUNNING_LIGHT_AUTO_STATE_SET", "LIGHT_DAY_RUNNING_LIGHT_AUTO_STATE", 1, 2),

    /** 方向盘加热。setSteeringWheelHeatingState:**1=关 2=开**,和日行灯正好反着。 */
    STEERING_HEAT("steering_heat", "方向盘加热", R.drawable.ic_car_steering, CarCtrl.DEV_SETTING,
            "SET_STEERING_WHEEL_HEAT_STATE_SET", "SET_STEERING_WHEEL_HEAT_STATE", 2, 1),

    /**
     * SOC 保电:关 → 25% → 50% → 70% → 关。**一张卡管两个字段**(开关在充电设备、
     * 目标百分比在设置设备),所以写逻辑不在这儿,归 {@link CarCtrl#socSave} ——
     * 同座椅的先例({@link CarCtrl}/{@code AirCon} 负责复合写,这里只委派)。
     * 档位值和上下限见 {@link CarCtrl#SOC_STEPS}。
     */
    SOC_SAVE("soc_save", "SOC保电", R.drawable.ic_car_battery),

    /** 引擎音模拟。setEngineVoiceSimulatorState:0=关 1=开。 */
    ENGINE_VOICE("engine_voice", "引擎音", R.drawable.ic_car_engine, CarCtrl.DEV_ENGINE,
            "ENGINE_VOICE_SIMULATOR_STATE_SET", "ENGINE_VOICE_SIMULATOR_STATE", 1, 0),

    /*
     * ---- 动作类(未验)。动车身,行驶中要拦(见 CarCtrl.needsParking) ----
     *
     * 车窗四态来自 BYDAutoBodyworkDevice.setBodyWindowCtrlState + 同文件常量:
     * WINDOW_OPEN_FULL=1 / WINDOW_CLOSE=2 / WINDOW_STOP=3 / WINDOW_OPEN_HALF=4 / WINDOW_BREATH=5。
     * 四个车窗各有一个字段,一次四条一起发 —— 这也是 setAllWindowState 的做法。
     */
    WINDOW_OPEN("window_open", "车窗全开", R.drawable.ic_window_open, 1),
    WINDOW_CLOSE("window_close", "车窗全关", R.drawable.ic_window_close, 2),
    WINDOW_HALF("window_half", "车窗半开", R.drawable.ic_window_half, 4),
    WINDOW_BREATH("window_breath", "车窗通风", R.drawable.ic_window_vent, 5),

    /**
     * 天窗。setMoonRoofState:0=关 / 21~100=开度 / 252=舒适位 / 253=透气 / 254=停。
     * 这里只做「全开」和「关」两个动作,中间开度留给以后。
     */
    MOONROOF_OPEN("moonroof_open", "天窗开", R.drawable.ic_moonroof_open, CarCtrl.DEV_BODYWORK,
            new String[]{"BODYWORK_MOON_ROOF_OPEN_PERCENT_SET"}, new int[]{100}),
    MOONROOF_CLOSE("moonroof_close", "天窗关", R.drawable.ic_moonroof_close, CarCtrl.DEV_BODYWORK,
            new String[]{"BODYWORK_MOON_ROOF_OPEN_PERCENT_SET"}, new int[]{0}),

    /** 遮阳帘。setSunshadeState:0~100 开度 / 254=停。 */
    SUNSHADE_OPEN("sunshade_open", "遮阳帘开", R.drawable.ic_sunshade_open, CarCtrl.DEV_BODYWORK,
            new String[]{"BODYWORK_SUNSHADE_PANEL_PERCENT_SET"}, new int[]{100}),
    SUNSHADE_CLOSE("sunshade_close", "遮阳帘关", R.drawable.ic_sunshade_close, CarCtrl.DEV_BODYWORK,
            new String[]{"BODYWORK_SUNSHADE_PANEL_PERCENT_SET"}, new int[]{0}),

    /**
     * 座椅记忆。原厂「主驾位置」弹窗的底层是 {@code BYDAutoSettingDevice}(设备号 1023)
     * 上的两个字段:{@code SET_LF_MEMORY_LOCATION_SET}(长按=保存) /
     * {@code SET_LF_MEMORY_LOCATION_WAKE_SET}(短按=恢复),值=位置号(1/2)。走
     * {@link CarCtrl#act} 这条动作通道。
     *
     * **一张卡承载四个操作(恢复1/2、保存1/2)**:点卡片由宿主弹菜单选,不直接下发;
     * 这里的 writeFields 只是占位(恢复位置1),实际动作由 {@link CarCtrl#seatMemory} 决定。
     * 2026-08-21 未在真车验证,CAN 侧可能丢帧。
     *
     * 图标是**座椅 + 数字1**({@code ic_seat_pos},BYD 原厂 nav_common_left_seat_1_icon),
     * 原厂拿它当记忆位1 —— 正好对上面板底下那两个「位置1/位置2」。
     * 先前借用 {@code ic_seat_vent},在桌面上和「主驾通风」那张卡一模一样。
     */
    SEAT_MEMORY("seat_memory", "座椅记忆", R.drawable.ic_seat_pos, CarCtrl.DEV_SETTING,
            new String[]{"SET_LF_MEMORY_LOCATION_WAKE_SET"}, new int[]{1}),

    /*
     * ---- 系统开关。**不走 CAN**,见类注释里 Kind.SYSTEM 那条 ----
     */

    /**
     * WLAN。读走 {@code WifiManager.getWifiState()},写走提权 {@code svc wifi} ——
     * {@code setWifiEnabled} 从 Android 10 起对第三方 app 一律返回 false。
     * **2026-08-31 只验到「命令不报权限错」**:真开真关会切断 WiFi adb,没法隔着它验。
     */
    WIFI("wifi", "WLAN", R.drawable.ic_status_wifi, true),

    /**
     * 热点。反射 {@code IConnectivityManager.startTethering},前置要 WRITE_SETTINGS
     * (提权 appops 授)。**未在真车上发过一次**,而且开热点多半会把 WLAN 关掉 ——
     * 通道和权限门槛见 {@link com.liubai.blank.privileged.NetSwitch}。
     */
    HOTSPOT("hotspot", "热点", R.drawable.ic_hotspot, true),

    /**
     * 蓝牙。读写都是 {@code BluetoothAdapter} 的公开 API,**这一项不用提权** ——
     * {@code enable()/disable()} 到 Android 12 都还是普通 app 能调的
     * (要 BLUETOOTH_ADMIN,normal 级、装上就有),不像 WLAN 那样从 10 起被禁。
     *
     * <p>长按拉车机自带的蓝牙弹窗({@code com.byd.btsetting.BluetoothManagerDialog}),
     * 配对、连设备都在那儿做,见 {@code DeskCardHost.systemPageOf}。
     */
    BLUETOOTH("bluetooth", "蓝牙", R.drawable.ic_status_bluetooth, true);

    public enum Kind { SEAT, TOGGLE, LEVEL, ACTION, SYSTEM }

    /**
     * 四个车窗的控制字段,顺序:左前 / 右前 / 左后 / 右后。
     *
     * **不能写成枚举的静态字段** —— 枚举常量比静态字段先初始化,构造器里读到的会是 null。
     * 塞进这个 holder 类,第一次用到时才初始化,那时早过了枚举常量那一轮。
     */
    private static final class Windows {
        static final String[] FIELDS = {
                "BODYWORK_LF_WINDOW_CTRL_SET", "BODYWORK_RF_WINDOW_CTRL_SET",
                "BODYWORK_LR_WINDOW_CTRL_SET", "BODYWORK_RR_WINDOW_CTRL_SET" };
    }

    /** 存储用的稳定标识。**改了等于把用户配好的那一项弄丢**,只能追加不能改名。 */
    public final String key;
    public final String title;
    public final Kind kind;

    private final int iconRes;          // SEAT 用不上(按档位换图标)
    private final boolean deputy, heat; // SEAT 专用
    private final int device;
    private final String[] writeFields;
    private final int[] writeValues;    // ACTION 专用:每个字段写什么
    private final String readField;     // TOGGLE 专用
    private final int onValue, offValue;// TOGGLE 专用

    /** 座椅。 */
    CarCtrlItem(String key, String title, boolean deputy, boolean heat) {
        this.key = key; this.title = title; this.kind = Kind.SEAT;
        this.deputy = deputy; this.heat = heat;
        this.iconRes = 0; this.device = 0;
        this.writeFields = null; this.writeValues = null;
        this.readField = null; this.onValue = 0; this.offValue = 0;
    }

    /** 数值档位:一张卡循环几个数值(0 = 关),复合写归提权层。目前只有 SOC 保电。 */
    CarCtrlItem(String key, String title, int iconRes) {
        this.key = key; this.title = title; this.kind = Kind.LEVEL;
        this.iconRes = iconRes; this.device = 0;
        this.writeFields = null; this.writeValues = null;
        this.readField = null; this.onValue = 0; this.offValue = 0;
        this.deputy = false; this.heat = false;
    }

    /** 开关:读回来是 onValue 就算开着,点一下写另一个。 */
    CarCtrlItem(String key, String title, int iconRes, int device,
                String writeField, String readField, int onValue, int offValue) {
        this.key = key; this.title = title; this.kind = Kind.TOGGLE;
        this.iconRes = iconRes; this.device = device;
        this.writeFields = new String[]{writeField}; this.writeValues = null;
        this.readField = readField; this.onValue = onValue; this.offValue = offValue;
        this.deputy = false; this.heat = false;
    }

    /** 动作:一次性,可以一发多条(车窗四扇)。 */
    CarCtrlItem(String key, String title, int iconRes, int device,
                String[] writeFields, int[] writeValues) {
        this.key = key; this.title = title; this.kind = Kind.ACTION;
        this.iconRes = iconRes; this.device = device;
        this.writeFields = writeFields; this.writeValues = writeValues;
        this.readField = null; this.onValue = 0; this.offValue = 0;
        this.deputy = false; this.heat = false;
    }

    /**
     * 系统开关(WLAN/热点)。没有设备号、没有字段名 —— 这些项底下不是 CAN。
     *
     * 末尾那个 {@code system} 参数只用来把签名和 {@link Kind#LEVEL} 那个
     * {@code (key, title, iconRes)} 分开,值恒为 true。
     */
    CarCtrlItem(String key, String title, int iconRes, boolean system) {
        this.key = key; this.title = title; this.kind = Kind.SYSTEM;
        this.iconRes = iconRes; this.device = 0;
        this.writeFields = null; this.writeValues = null;
        this.readField = null; this.onValue = 0; this.offValue = 0;
        this.deputy = false; this.heat = false;
    }

    /** 动作的简写:四扇车窗同写一个值(同 BYDAutoBodyworkDevice.setAllWindowState 的做法)。 */
    CarCtrlItem(String key, String title, int iconRes, int value) {
        this(key, title, iconRes, CarCtrl.DEV_BODYWORK, Windows.FIELDS,
                new int[]{value, value, value, value});
    }

    public int device() { return device; }
    public String[] writeFields() { return writeFields; }
    public int[] writeValues() { return writeValues; }
    public String readField() { return readField; }
    public int onValue() { return onValue; }
    public int offValue() { return offValue; }

    /**
     * 0 = 跟模式强调色走。
     *
     * 座椅原来分冷暖(加热暖橙/通风青绿),2026-08-16 撤掉:一条 Dock、一张桌面上
     * 同时出现强调色和语义色,看着像两套东西 —— 「这个开着」和「现在在制冷」
     * 用同一根颜色轴表达,反而谁也读不准。冷暖只留给粒子那层(见 ThermalFx)。
     */
    public int accent() {
        return 0;
    }

    /**
     * 该显示哪个图标。字形不跟档位换 —— 开启态只换颜色（见《图标源头分析》第七节），
     * 所以这里对座椅也只按加热/通风分,{@code level} 不再参与。
     */
    public int icon(int level) {
        if (kind != Kind.SEAT) return iconRes;
        return heat ? R.drawable.ic_seat_heat : R.drawable.ic_seat_vent;
    }

    /**
     * 图标要不要左右翻。**座椅字形只有主驾那一版**,副驾在车的另一边,不翻的话
     * 两颗座椅朝着同一个方向,光看图分不出哪边(Dock 上早就翻了,
     * 见 {@code DockEditPanel.mirrored};桌面卡片和空调面板 2026-08-22 才补上)。
     *
     * 用它的地方要**两边都写**({@code setScaleX(mirrored() ? -1f : 1f)}),
     * 只在副驾时设 -1 的话,复用到的那个 ImageView 会一直翻着。
     */
    public boolean mirrored() {
        return kind == Kind.SEAT && deputy;
    }

    /**
     * 座椅的当前档位:0=关,1/2=档位,**-1 = 读不到或这台车没装**。
     * 非座椅项返回 -1(它们的状态不从 AirCon 来)。
     */
    public int level(AirCon.State s) {
        if (kind != Kind.SEAT || s == null) return -1;
        if (heat) return deputy ? s.heatR : s.heatL;
        return deputy ? s.ventR : s.ventL;
    }

    /** 座椅:点一下升一档。回调在主线程,带回写完之后的真实状态。 */
    public void tapSeat(AirCon.Listener after) {
        AirCon.seat(deputy, heat, after);
    }

    /**
     * 数值档位:点一下该跳到哪个值。0 = 关。
     *
     * **车上的值不一定落在 {@link CarCtrl#SOC_STEPS} 上** —— 原厂界面能调任意百分比,
     * 那就跳到第一个比它大的档;都不比它大(比如已经 70)就关掉。
     */
    public int nextPct(int cur) {
        if (kind != Kind.LEVEL) return 0;
        for (int step : CarCtrl.SOC_STEPS) {
            if (step > cur) return step;
        }
        return 0;
    }

    /** 数值档位:发下去。回调带回车上此刻的真实值(见 {@link CarCtrl#socSave})。 */
    public void tapLevel(int pct, CarCtrl.Result after) {
        CarCtrl.socSave(pct, after);
    }

    /**
     * 同一张椅子上的另一路(通风↔加热),没有就 null。
     *
     * **椅子不能又吹又烤** —— 开一路就得把另一路显示成关。真车侧 {@code AirCon.writeSeat}
     * 本来就会替你清掉另一路,这里是给 UI 的乐观更新用的。
     */
    public CarCtrlItem opposite() {
        if (kind != Kind.SEAT) return null;
        for (CarCtrlItem it : values()) {
            if (it.kind == Kind.SEAT && it.deputy == deputy && it.heat != heat) return it;
        }
        return null;
    }

    /** 按 key 找回来。存的 key 不认识了(降过级/改过名)返回 null,由调用方跳过。 */
    public static CarCtrlItem of(String key) {
        for (CarCtrlItem it : values()) {
            if (it.key.equals(key)) return it;
        }
        return null;
    }
}
