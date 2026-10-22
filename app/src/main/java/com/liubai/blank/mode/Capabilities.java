package com.liubai.blank.mode;

import org.json.JSONObject;

/**
 * 一个模式带哪些能力。
 *
 * 每个模式 json 在 {@code capabilities} 段里**自己声明**带什么(画中画、动效、彩蛋、在线壁纸、
 * 游戏中心)。开源版只有 full 一个模式,全给。
 *
 * **默认一律是"全给"**:模式文件里不写 capabilities 段,或者段里缺某一项,
 * 都按最完整的来。这样老模式文件原样加载、行为不变。
 *
 * **五项都已接线**,改了真生效。
 */
public final class Capabilities {

    /**
     * 车控**动效**。json 里仍写 {@code hvac: basic|rich},字段名也没改,但语义已经收窄。
     *
     * true  = 静止:该有的控件一个不少,只是不跑动效;
     * false = 全套动效:扇叶按档位转、温度键下雪、气流线流动、座椅气流曲线(见 HvacPanel.still
     *         / SeatFlowView.still / DeskCard.still),温度键那层粒子同时是整条 Dock 彩蛋的发射源。
     *
     * 操作方式不受它影响:温度和风向一律是「‹ 读数 ›」三格、点一下走一格。这个字段只管动效。
     */
    public final boolean hvacBasic;

    /**
     * 画中画给几个卡位。0=没有画中画,1=单卡,2=双卡(当前实现就是双卡)。
     *
     * 用数字而不是开关,因为形态(单卡/双卡)本来就是模式该定的事;
     * 但**最多两个** —— 更多卡位意味着更多虚拟屏,现在两个在真车上都还没长时间验过。
     */
    public final int pipSlots;

    /**
     * 彩蛋粒子:跨组件的粒子联动。**已接线**(见 DockView.refreshHvac 末尾 / SnowDrift)——
     * 风量档位一变,从温度键往它那一侧吹出一阵:制冷是雪花,制热是火星。
     *
     * 注意和温度键上那两片雪花不是一回事 —— 那两片是**功能语义**(告诉你现在在制冷),
     * 任何模式都必须有,不能被关掉。这里关的是"世界观"那一层。
     */
    public final boolean particles;

    /**
     * 在线壁纸橱窗(外观面板的「在线」那一档)。
     *
     * <p><b>只管"能不能去橱窗拿新的",不管已经拿到手的。</b> 下载过的壁纸是本地文件,
     * 关了照样能选能用。用户自己拷进来的图片同理,那是他的文件。
     */
    public final boolean onlineWallpaper;

    /**
     * 游戏中心。**整个功能**,不只是在线库。
     *
     * <p>和壁纸不一样:关了之后整个游戏中心都进不去,已下载的 ROM 也一样。
     */
    public final boolean gameCenter;

    private Capabilities(boolean hvacBasic, int pipSlots, boolean particles,
                         boolean onlineWallpaper, boolean gameCenter) {
        this.hvacBasic = hvacBasic;
        this.pipSlots = pipSlots;
        this.particles = particles;
        this.onlineWallpaper = onlineWallpaper;
        this.gameCenter = gameCenter;
    }

    /** 全给。模式文件没写 capabilities 段时用它。 */
    public static Capabilities full() {
        return new Capabilities(false, 2, true, true, true);
    }

    /** 从 mode.json 的 capabilities 段解析;传 null(没写这段)就是全给。 */
    public static Capabilities fromJson(JSONObject c) {
        if (c == null) return full();
        return new Capabilities(
                "basic".equals(c.optString("hvac", "rich")),
                Math.max(0, Math.min(2, c.optInt("pipSlots", 2))),
                c.optBoolean("particles", true),
                c.optBoolean("onlineWallpaper", true),
                c.optBoolean("gameCenter", true));
    }
}
