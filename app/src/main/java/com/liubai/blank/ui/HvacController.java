package com.liubai.blank.ui;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.liubai.blank.model.CarCtrlItem;
import com.liubai.blank.mode.ModeManager;
import com.liubai.blank.privileged.AirCon;
import com.liubai.blank.privileged.CarCtrl;
import com.liubai.blank.privileged.NetSwitch;
import com.liubai.blank.util.Launchers;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 空调/车控控制器:三指调温、Dock 空调键、空调面板、车控小卡的点击下发,
 * 全收在这一处。
 *
 * 2026-08-19 从 {@code HomeActivity} 整块搬出(Stage 4),方法体原样。宿主引用统一换成
 * 构造收进来的 {@code act} / {@code stage} / {@code host};四个要回喂 DeskCardHost 的
 * 能力(onState / onTileOf / onRevertCarCtrl / onRefreshCtrlStates)走构造 lambda 注入,
 * 不在 HomeActivity 保留副本。
 *
 * 循环依赖的破法(施工单"结二"):DeskCardHost 构造要一个 {@link CarCtrlTile.Listener}
 * (即本类),本类又要回喂 DeskCardHost。所以 HvacController 先建,DeskCardHost 后建,
 * 四个 lambda 在构造那一行不求值 —— **不能写成方法引用**(那时 deskHost 还是 null,
 * 当场 NPE)。Stage 3 的 Shell.onReady 踩过同一个坑。
 */
public final class HvacController implements HvacPanel.Listener, CarCtrlTile.Listener {

    private final Activity act;
    /** 舞台:气泡的锚点(见 showHvacTemp 的注释)。 */
    private final FrameLayout stage;
    /** DockHost:空调读数要回喂给 Dock。 */
    private final DockHost host;
    /** 读到新状态时通知车控卡(→ DeskCardHost.applyAcState)。 */
    private final Consumer<AirCon.State> onState;
    /** 按项找它那张车控卡(→ DeskCardHost.tileOf)。 */
    private final Function<CarCtrlItem, CarCtrlTile> onTileOf;
    /** 发不出去时让卡片弹回真实状态(→ DeskCardHost.revertCarCtrl)。 */
    private final Runnable onRevertCarCtrl;
    /** 开关写完后把卡片状态现读一遍(→ DeskCardHost.refreshCtrlStates)。 */
    private final Runnable onRefreshCtrlStates;

    /**
     * 空调面板 {@link HvacPanel}(刻度尺/风量柱/座椅气流/冷暖粒子)。首次要用时才建(见 onOpenHvacPanel),
     * 不跑动效时控件一个不少,只是不动(见 HvacPanel.still)。
     */
    private HvacSheet hvac;
    /** 三指调空调时的读数气泡。和 Dock 上调温调风量用的是同一个组件。 */
    private HvacBubble hvacBubble;

    /** @param act     宿主 Activity(建 HvacPanel/HvacBubble、findViewById)
     *  @param stage   舞台,气泡的锚点(见 showHvacTemp 的注释)
     *  @param host    DockHost,空调读数要回喂给 Dock
     *  @param onState 读到新状态时通知车控卡 —— 传 lambda,不要传方法引用(见"结二")
     *  @param onTileOf 按项找车控卡 —— 同上,传 lambda
     *  @param onRevertCarCtrl 发不出去时弹回卡片真实状态 —— 同上
     *  @param onRefreshCtrlStates 开关写完后现读一遍卡片状态 —— 同上 */
    public HvacController(Activity act, FrameLayout stage, DockHost host,
                          Consumer<AirCon.State> onState,
                          Function<CarCtrlItem, CarCtrlTile> onTileOf,
                          Runnable onRevertCarCtrl,
                          Runnable onRefreshCtrlStates) {
        this.act = act;
        this.stage = stage;
        this.host = host;
        this.onState = onState;
        this.onTileOf = onTileOf;
        this.onRevertCarCtrl = onRevertCarCtrl;
        this.onRefreshCtrlStates = onRefreshCtrlStates;
    }

    // ---- 三指调空调 ----

    /**
     * 温度加/减几格。
     *
     * 走的是 {@code TEMP_UP/TEMP_DOWN} 的**相对增量** —— 这条 native bus 上温度没有
     * "设到某个值"的接口(风量才有,见 {@link AirCon#fanTo}),一格一格点过去是唯一的路。
     * 一次滑过好几格时靠 {@code send(code, times)} 连发,不必等每一格回读。
     */
    public void stepTemp(int steps) {
        if (steps == 0) return;
        // 提权没就绪就控不了车(mock 与否也是靠提权判的,见 AirCon.available)
        if (!AirCon.available()) return;
        // 弹气泡 **且** applyAcState:后者把新状态回喂 Dock/面板/车控卡 —— 少了它三指改完
        // Dock 不刷新(Dock 自己那两颗风量键走的就是 applyAcState,三指得跟它一条路)。
        AirCon.send(steps > 0 ? AirCon.TEMP_UP : AirCon.TEMP_DOWN, Math.abs(steps),
                s -> { showHvacTemp(s, true); applyAcState(s); });
    }

    /** 风量加/减几格。同样是相对增量,和 Dock 上那两颗键一条路。 */
    public void stepFan(int steps) {
        if (steps == 0) return;
        if (!AirCon.available()) return;
        AirCon.send(steps > 0 ? AirCon.FAN_UP : AirCon.FAN_DOWN, Math.abs(steps),
                s -> { showHvacFan(s, true); applyAcState(s); });
    }

    /**
     * 调节中的读数气泡。**和 Dock 上调温、调风量弹的是同一个组件**({@link HvacBubble}) ——
     * 同一件事在两处弹两种样子,用起来就像两个 app。
     *
     * 锚点用舞台:气泡的水平位置对准锚点中心、垂直固定在 Dock 上方那条线(见 HvacBubble.place),
     * 所以三指在屏幕哪儿滑都不影响它的位置 —— 手指底下反而不该被挡住。
     */
    private void showHvacTemp(AirCon.State s, boolean adjusting) {
        if (s == null || stage == null) return;
        float c = parseTemp(s.temp);
        if (hvacBubble == null) hvacBubble = new HvacBubble(act);
        int color = ThermalPalette.of(c, ModeManager.get().ink());
        // progress 是温度在可调区间里的位置(16~32),气泡下面那排柱子按它亮
        float progress = Math.max(0f, Math.min(1f, (c - 16f) / 16f));
        hvacBubble.showTemp(stage, c, progress, s.temp == null ? "--" : s.temp, "°",
                color, adjusting);
    }

    private void showHvacFan(AirCon.State s, boolean adjusting) {
        if (s == null || stage == null) return;
        if (hvacBubble == null) hvacBubble = new HvacBubble(act);
        int level = Math.max(0, s.fan);
        hvacBubble.showFan(stage, level, String.valueOf(level), "", adjusting);
    }

    /** "26" → 26f。读不到就按 24 算(只影响气泡的颜色和柱子,不参与下发)。 */
    private static float parseTemp(String t) {
        try {
            return t == null ? 24f : Float.parseFloat(t);
        } catch (NumberFormatException e) {
            return 24f;
        }
    }

    /** 三指手势结束:气泡过一会儿自己收(HomeActivity 的 onHvacGestureEnd 转调过来)。 */
    public void hideBubble() {
        if (hvacBubble != null) hvacBubble.hideAfter(900L);
    }

    /**
     * 三指松手后全量回读校正。关态下三指把空调带起来了,调节中的乐观态只补了动的那一项
     * (温度/风量),power、AUTO 之类的连带变化补不全 —— 松手读全一次,把真状态铺回
     * Dock/面板/车控卡。每步回读会挤,所以只在松手做一次(不在 stepTemp/stepFan 里)。
     */
    public void reconcileAc() {
        if (AirCon.available()) AirCon.refreshFull(this::applyAcState);
    }

    /** HomeActivity.onGoHome 用:收浮层时把空调面板一起收掉。 */
    public void closePanel() {
        if (hvac != null && hvac.isOpen()) hvac.close();
    }

    /** 面板开着没有。返回键要按"最上面那层先收"来判(见 HomeActivity.closeTopOverlay)。 */
    public boolean panelOpen() {
        return hvac != null && hvac.isOpen();
    }

    /** Dock 上调温(DockView.Listener 转调入口)。 */
    public void onTempChange(int delta) {
        if (delta == 0) return;
        sendAc(delta > 0 ? AirCon.TEMP_UP : AirCon.TEMP_DOWN, Math.abs(delta));
    }

    /** 风量滑动松手:绝对档位,0 = 关空调。见 AirCon#fanTo。 */
    public void onFanLevel(int level) {
        if (!AirCon.available()) {
            Launchers.toast(act, "车控需要先开启提权服务");
            return;
        }
        AirCon.fanTo(level, this::applyAcState);
    }

    /** Dock 上点空调键,打开空调面板(DockView.Listener 转调入口)。 */
    public void onOpenHvacPanel() {
        if (hvac == null) {
            // 不跑动效时控件一个不少,只是不动(见 HvacPanel.still)。
            // 面板一旦建出来就跟着这个 Activity 活到底 —— 换模式会 recreate 桌面,
            // 那时字段归零、下次打开自然按新模式重建,不必在这儿处理切换。
            hvac = new HvacPanel(act);
            hvac.setListener(this);
            // 贴到 content 上而不是 activity_home 里:面板要盖住整屏(含 Dock),
            // 而 Dock 在布局里是同级兄弟,压不住它
            ((ViewGroup) act.findViewById(android.R.id.content)).addView((View) hvac,
                    new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
        }
        hvac.open();
    }

    // ---- HvacPanel.Listener ----

    /** 开关类:直接把 AirCon 的 transaction 编号发出去。 */
    @Override public void onHvacCommand(int code) {
        sendAc(code);
    }

    /** 温度加减。deputy=true 是副驾;delta 以 **1℃** 为一格。 */
    @Override public void onHvacTemp(boolean deputy, int delta) {
        if (delta == 0) return;
        int up = deputy ? AirCon.TEMP_R_UP : AirCon.TEMP_UP;
        int down = deputy ? AirCon.TEMP_R_DOWN : AirCon.TEMP_DOWN;
        sendAc(delta > 0 ? up : down, Math.abs(delta));
    }

    /** 风量滑条松手:level 是**绝对档位** 0~7,不是增量;0 = 关空调。 */
    @Override public void onHvacFanLevel(int level) {
        onFanLevel(level);
    }

    /** 座椅加热/通风升一档(0=关/1/2,**两档**到顶回 0)。heat=false 是通风。 */
    @Override public void onSeatLevel(boolean deputy, boolean heat) {
        if (!AirCon.available()) {
            Launchers.toast(act, "车控需要先开启提权服务");
            return;
        }
        AirCon.seat(deputy, heat, this::applyAcState);
    }

    /** 面板打开,请求一次全量状态。 */
    @Override public void onHvacNeedState() {
        // 先拿上次全量读的缓存秒显(空/未读过时 full=false,applyAcState 自会跳过面板铺设),
        // 再跑一次全量(14 读 ~700ms)校正 —— 免得开面板干等一屏"关"。
        applyAcState(AirCon.last());
        if (AirCon.available()) AirCon.refreshFull(this::applyAcState);
    }

    // ---- CarCtrlTile.Listener ----

    /**
     * 点了某一张车控小卡。
     *
     * 卡片已经乐观变过色了(动作类除外),这里只管发命令;发不出去就把状态重铺一遍,
     * 让它弹回真实值 —— 不弹的话卡上会一直亮着一个车里根本没开的功能。
     */
    @Override public void onCarCtrlTap(CarCtrlItem item) {
        if (item == null) return;
        // 系统开关(WLAN/热点)**必须排在车控那道门前面**:它们底下不是 CAN,
        // 车控没就绪不该拦住它们(WLAN 只要提权 shell、热点连提权都不一定要)
        if (item.kind == CarCtrlItem.Kind.SYSTEM) {
            tapNetSwitch(item);
            return;
        }
        if (!AirCon.available()) {
            Launchers.toast(act, "车控需要先开启提权服务");
            onRevertCarCtrl.run();
            return;
        }
        // 座椅记忆:一张卡打开「主驾位置」面板 —— 方向调节(按住动松开停)+
        // 位置记忆(恢复/保存 1/2)。面板内部自己发指令,这里只负责拉起。
        if (item == CarCtrlItem.SEAT_MEMORY) {
            SeatMemoryPopup.show(act);
            return;
        }
        if (item.kind == CarCtrlItem.Kind.SEAT) {
            // **椅子不能又吹又烤**:开一路就把同座另一路按灭。真车侧 AirCon.writeSeat
            // 本来就会替你清掉另一路,这里是给 UI 的乐观更新 —— 拆成一项一张卡之后,
            // 那张卡在别处,得由这儿去找(以前是同一张卡里的兄弟视图)
            CarCtrlTile self = onTileOf.apply(item);
            if (self != null && self.level() > 0) {
                CarCtrlTile other = onTileOf.apply(item.opposite());
                if (other != null && other.level() > 0) other.setLevel(0);
            }
            item.tapSeat(this::applyAcState);
            return;
        }
        // 动车身的(车窗/天窗/遮阳帘)在行驶中是安全问题,非 P 档不发 ——
        // 迪友那张卡也是这么拦的(反编译 3.0.1,u4/c.java:181 弹「请在P档下操作」)。
        // 档位读取要走 native,不能占主线程
        if (CarCtrl.needsParking(item.device())) {
            new Thread(() -> {
                boolean p = CarCtrl.inParking();
                act.runOnUiThread(() -> {
                    if (!p) {
                        Launchers.toast(act, "请挂 P 档后再操作");
                        onRevertCarCtrl.run();
                        return;
                    }
                    sendCarCtrl(item);
                });
            }, "blank-gear-check").start();
            return;
        }
        sendCarCtrl(item);
    }

    /**
     * 点了 WLAN 或热点。
     *
     * 和车控开关同一套手感:卡片已经乐观翻过面了,按它翻成的样子决定开还是关,
     * 发不动就把状态重铺一遍弹回去。差别只在**下面走的是 Android 而不是 CAN**,
     * 以及失败原因是 {@link NetSwitch} 现攒的一句话(反射被拦、权限没授、模组没起来
     * 各是各的),照实弹出来 —— 真车上 app 的 Log 抓不到,这行提示是唯一的现场证据。
     */
    private void tapNetSwitch(CarCtrlItem item) {
        CarCtrlTile tile = onTileOf.apply(item);
        boolean wantOn = tile != null && tile.level() > 0;
        NetSwitch.Result after = (ok, why) -> {
            if (!ok) Launchers.toast(act, why);
            onRefreshCtrlStates.run();
        };
        if (item == CarCtrlItem.HOTSPOT) NetSwitch.setHotspot(act, wantOn, after);
        else if (item == CarCtrlItem.BLUETOOTH) NetSwitch.setBluetooth(act, wantOn, after);
        else NetSwitch.setWifi(act, wantOn, after);
    }

    /** 真正发下去。开关类回读确认,动作类只管发(它没有能比对的状态)。 */
    private void sendCarCtrl(CarCtrlItem item) {
        if (item.kind == CarCtrlItem.Kind.ACTION) {
            CarCtrl.act(item.device(), item.writeFields(), item.writeValues(), (ok, v) -> {
                if (!ok) Launchers.toast(act, item.title + " 没发出去");
            });
            return;
        }
        final CarCtrlTile tile = onTileOf.apply(item);
        if (item.kind == CarCtrlItem.Kind.LEVEL) {
            // 数值档位(SOC 保电):卡片已经乐观跳到下一档了,按它跳成的值发。
            // 回调带回的是**车上此刻的真实值**,直接铺上去 —— 原厂界面能调出
            // 自己那三档之外的值(比如 30%),那就照实显示,不四舍五入到自己的档上
            int pct = tile != null ? tile.level() : 0;
            item.tapLevel(pct, (ok, now) -> {
                if (!ok) Launchers.toast(act, item.title + " 没生效(车上现在 " + now + ")");
                // 读不到(-1)时保持原样:setLevel(-1) 是"没装配"、会把整张卡藏掉,
                // 一次读失败不该让卡片消失
                if (tile != null && now >= 0) tile.setLevel(now);
            });
            return;
        }
        // 开关:卡片已经乐观翻过面了,按它翻成的样子决定写哪个值
        boolean wantOn = tile != null && tile.level() > 0;
        int value = wantOn ? item.onValue() : item.offValue();
        CarCtrl.set(item.device(), item.writeFields()[0], value,
                item.readField(), value, (ok, got) -> {
                    if (!ok) {
                        // 没写进去(这台车没装配 / CAN 侧丢了 / 参数不对都可能 ——
                        // 这些项还没在真车上验过,见 CarCtrlItem 类注释)。
                        // **带上回读值**:真车上 app 的 Log 抓不到(只有 crash buffer 读得出,
                        // 见记忆 car-logcat-needs-adb-shell),这行提示是唯一的现场证据。
                        // -1 = 压根读不到这个字段,其余 = 车端此刻的真实值。
                        Launchers.toast(act, item.title + " 没生效(读回 " + got + ",想要 " + value + ")");
                    }
                    onRefreshCtrlStates.run();
                });
    }

    // ---- DockView.Listener 的 onAcAction(其余 13 个留在 HomeActivity) ----

    /** Dock 上那些多功能键(电源/循环/除雾/座椅...)。 */
    public void onAcAction(String action) {
        if ("power".equals(action))        sendAc(AirCon.AC);
        else if ("fan".equals(action))     sendAc(AirCon.FAN_UP);
        else if ("recirc".equals(action))  sendAc(AirCon.CYCLE);
        else if ("defrost".equals(action)) sendAc(AirCon.DEFROST);
        else if ("wind_face".equals(action)) sendAc(AirCon.W_FACE);
        else if ("wind_foot".equals(action)) sendAc(AirCon.W_FOOT);
        else if ("wind_def".equals(action))  sendAc(AirCon.W_DEF);
        // 座椅通风/加热:一下一档,**两档到顶回 0**(0=关/1/2,见 AirCon.writeSeat 的 % 3;
        // 和空调面板里那几颗同一条规矩)
        else if (action.startsWith("seat_")) {
            seatLevel(action.endsWith("_r"), action.contains("_heat_"));
        }
    }

    private void seatLevel(boolean deputy, boolean heat) {
        if (!AirCon.available()) {
            Launchers.toast(act, "车控需要先开启提权服务");
            return;
        }
        AirCon.seat(deputy, heat, host::setHvac);
    }

    private void sendAc(int code) {
        sendAc(code, 1);
    }

    /** 发空调指令,回来顺手把 Dock 上的读数刷新掉。times>1 是长按滑动跨了几格。 */
    private void sendAc(int code, int times) {
        if (!AirCon.available()) {
            Launchers.toast(act, "车控需要先开启提权服务");
            return;
        }
        AirCon.send(code, times, this::applyAcState);
    }

    /**
     * 拉一次空调状态铺到 Dock 上。提权是异步就绪的,所以 onReady 里还要再拉一次。
     * 用全量读:Dock 现在会显示除雾/循环/电源的开关态,轻量读不带这些,
     * 启动后这几个键就一直是"不知道"。只在启动时跑,后台串行 700ms 无感。
     */
    public void refreshAc() {
        if (!AirCon.available()) return;
        AirCon.refreshFull(this::applyAcState);
    }

    private void applyAcState(AirCon.State s) {
        host.setHvac(s);
        // 只有全量读的结果才铺面板:轻量读除温度风量外都是默认值,
        // 拿去铺会把满屏开关显示成"关"
        if (hvac != null && hvac.isOpen() && s.full) hvac.apply(s);
        // 车控卡那部分归 DeskCardHost(见 DeskCardHost.applyAcState)
        onState.accept(s);
    }
}
