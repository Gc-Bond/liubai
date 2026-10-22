package com.liubai.blank.ui;

import com.liubai.blank.privileged.AirCon;

/**
 * 空调面板的共同外壳。
 *
 * 现在只有 {@link HvacPanel} 一个实现:不跑动效时走静态模式(见 HvacPanel.still)
 * —— 控件一个不少,只是不跑动效。
 *
 * 接口留着是因为面板有两个宿主(桌面 Activity 和 Dock 悬浮窗),它们只需要开关和喂状态,
 * 不该依赖具体实现。
 *
 * 实现类都是 View 的子类,桌面 addView 时强转即可。
 */
public interface HvacSheet {

    void setListener(HvacPanel.Listener l);

    /** 打开。实现里应当顺便请求一次全量状态(onHvacNeedState)。 */
    void open();

    void close();

    boolean isOpen();

    /** 喂一次全量状态。只有 {@link AirCon.State#full} 为真时才该调。 */
    void apply(AirCon.State s);

    /**
     * 关掉之后回调一次。Dock 那个悬浮窗版本靠它把窗口摘下来 ——
     * 窗口是 MATCH_PARENT 的,面板 view 设成 GONE 并不会让窗口变小,
     * 不摘的话它会继续全屏吃触摸(表现为面板关掉后整块屏幕都点不动)。
     */
    void setOnClosed(Runnable r);
}
