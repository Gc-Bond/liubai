package com.liubai.blank.model;

/**
 * 顶栏上能放的一项。
 *
 * <h3>为什么顶栏要可编排</h3>
 *
 * 照 DiStyle 那套:它的顶栏就是一条 {@code RecyclerView}(反编译 v2.1.6,布局绑定
 * {@code p6/I0}:{@code rvStatusBarListView}),适配器叫 {@code CustomStatusBarItemAdapter},
 * 配套还有 {@code CustomStatusBarFragment} 编辑页 —— 顶栏放什么由车主自己定,
 * 和它的 Dock、桌面卡片是同一套「自定义栏」机制({@code data/custom_bar} 包下三种并列)。
 *
 * <h3>只列真读得到的</h3>
 *
 * 每一项都在 framework 的 {@code BYDAutoFeatureIds} 里对上了字段,或者是系统本来就给的。
 * **对不上的一律不进这个枚举**:
 * <ul>
 *   <li>PM2.5 —— {@code PM25_*} 只有开关和配置项(离子发生器、双通道检测),没有浓度数值;</li>
 *   <li>红绿灯 —— 那是导航给的数据,留白不接导航;</li>
 *   <li>当前续航 —— 整张车况表 dump 过,压根没有(见 {@code CarStatus} 的说明)。</li>
 * </ul>
 *
 * 列在这儿但**标了未验**的几项(车外温度、电量、里程、坡度),字段名是从 framework 里
 * 找出来的,离车环境读不到 —— 真车上验过再把注释里的"未验"去掉。
 */
public enum StatusItem {

    /** 时间。顶栏的默认主角,等宽数字。 */
    TIME("time", "时间"),
    /** 日期 + 星期。 */
    DATE("date", "日期"),

    /** 车厢内此刻多少度({@code AC_TEMP_INSIDE},设备 AC=1000)。已验。 */
    TEMP_IN("temp_in", "车内温度"),
    /** 车外温度({@code AC_TEMP_OUT},同一个设备)。**未在真车验过**。 */
    TEMP_OUT("temp_out", "车外温度"),

    /** 距下次保养还有多少公里(车况 Provider 的 {@code car_status_maintenance_mile})。已验。 */
    MAINTENANCE("maint", "距保养"),
    /** 四轮胎压里最低的那个(设备 TYRE=1016)。已验。 */
    TYRE("tyre", "胎压"),

    /** 这台车机的 CPU 占用。系统自己算得出来,不走车端。 */
    CPU("cpu", "CPU 占用"),
    /** 内存占用。同上。 */
    MEM("mem", "内存占用"),

    /** 蓝牙连着没有(图标)。 */
    BLUETOOTH("bt", "蓝牙"),
    /** Wi-Fi 连着没有(图标)。 */
    WIFI("wifi", "Wi-Fi"),

    /**
     * 占位 —— 撑开一段固定的空。
     *
     * 照 DiStyle 那套(反编译 v2.1.6 {@code CustomStatusBarFragment}:66/77/88 三档
     * "占位-小/中/大",默认编排 {@code [66, 1010, 88, 77]} 里就有三个)。**这是顶栏
     * 不需要"左边/右边"概念的原因** —— 项就是一条有序列表,间距和分布靠往里插占位。
     */
    SPACER_S("sp_s", "占位 · 小"),
    SPACER_M("sp_m", "占位 · 中"),
    SPACER_L("sp_l", "占位 · 大"),
    /**
     * 弹性占位:把剩下的宽度全吃掉,后面的项被顶到最右。
     *
     * DiStyle 没有这一档(它那条 RecyclerView 是 wrap 的),留白加它是因为顶栏是 match_parent
     * 通栏的:**没有弹性占位就没法把一串项贴到右边缘**,而"时间在左、图标在右"正是顶栏
     * 最常见的样子(默认编排就用它)。顺便,真车顶部正中那个语音球开孔也是靠它让开的。
     */
    SPACER_FLEX("sp_flex", "占位 · 撑开");

    /** 存储用的键。**存的是它不是 ordinal** —— 枚举加一项就把顺序全冲了。 */
    public final String key;
    /** 编辑页上显示的名字。 */
    public final String title;

    StatusItem(String key, String title) {
        this.key = key;
        this.title = title;
    }

    /** 图标项(蓝牙 / Wi-Fi)显示的是图标,其余是一行字。 */
    public boolean isIcon() {
        return this == BLUETOOTH || this == WIFI;
    }

    /** 占位项:不显示任何东西,只占宽度。 */
    public boolean isSpacer() {
        return this == SPACER_S || this == SPACER_M || this == SPACER_L || this == SPACER_FLEX;
    }

    /** 占位宽度(dp)。弹性那档返回 0 —— 它靠 weight 撑,不看固定宽度。 */
    public int spacerDp() {
        switch (this) {
            case SPACER_S: return 16;
            case SPACER_M: return 48;
            case SPACER_L: return 120;
            default: return 0;
        }
    }

    public static StatusItem byKey(String key) {
        for (StatusItem i : values()) {
            if (i.key.equals(key)) return i;
        }
        return null;
    }
}
