package com.liubai.blank.model;

import android.content.Context;

import java.util.List;

/**
 * Dock 的格子度量。
 *
 * Dock 是**一排定长的格子**,铺满整条通栏:第一格贴左缘、末格贴右缘,中间每一格
 * 位置固定。加一项就占掉一格,别的项一动不动;删一项那格变空,右边的也不往左收拢
 * —— 这是"拖哪格停哪格"的前提,位置不会自己跑,所见才等于所得。
 *
 * 早先 Dock 是"项紧凑排列 + 弹性留白吃掉剩余空间"(见退役的 {@link DockItem.Kind#SPACER}),
 * 那种排法下每加一项全体都要挪位,拖动落点只能落在留白**之前**,画出来的空格框有 N 个、
 * 真正的落点却只有 1 个。
 *
 * 度量集中在这里,{@link com.liubai.blank.data.DockStore} 按它生成默认配置、
 * DockView 按它渲染,两边不各写一份 48/13。
 */
public final class DockGrid {

    /** 一格的边长,也就是 Dock 的行高(对齐迪友导航栏的图标格子)。 */
    public static final int SLOT_DP = 48;
    /** 格与格之间。 */
    public static final int GAP_DP = 13;
    /** Dock 两端的内边距,见 window_dock.xml 的 paddingHorizontal。 */
    public static final int PAD_DP = 16;

    private DockGrid() { }

    /**
     * 这块屏幕上,一条通栏 Dock 摆得下几格。
     *
     * 1920×1080 @240dpi(BYD 车机与其模拟器)上是 20 格。不写死是因为它只取决于屏宽,
     * 换一块屏就该自己算 —— 写死的话在别的分辨率上要么撑出屏幕、要么右边空一截。
     */
    public static int capacity(Context ctx) {
        float density = ctx.getResources().getDisplayMetrics().density;
        int screen = ctx.getResources().getDisplayMetrics().widthPixels;
        int avail = screen - Math.round(2 * PAD_DP * density);
        int step = Math.round((SLOT_DP + GAP_DP) * density);
        int gap = Math.round(GAP_DP * density);
        // n 格连着摆是 n*slot + (n-1)*gap,反解出 n
        return Math.max(1, (avail + gap) / step);
    }

    /**
     * 格与格之间留多宽(px)。**按屏宽算出来,不是写死的 13dp。**
     *
     * 格数是 floor 出来的,除不尽的零头得有个去处。写死间距的话零头会全堆在最右边
     * —— 1920×240dpi 上是 52px(35dp):左边只有 padding 的 24px、右边却有 76px,
     * 看着像"还能再塞一格"(其实差 13dp 塞不下),整条 Dock 也就歪了。
     *
     * 所以格宽固定(48dp 是手指尺寸,不能动),把零头均摊进间距里。摊完还剩的几个 px
     * 由 DockView 的 CENTER_HORIZONTAL 平分到两端,左右就一样宽了。
     */
    public static int gapPx(Context ctx) {
        float density = ctx.getResources().getDisplayMetrics().density;
        int min = Math.round(GAP_DP * density);
        int n = capacity(ctx);
        if (n < 2) return min;
        int avail = ctx.getResources().getDisplayMetrics().widthPixels
                - Math.round(2 * PAD_DP * density);
        int spare = avail - n * Math.round(SLOT_DP * density);
        return Math.max(min, spare / (n - 1));
    }

    /** 这串项一共占了几格(温度/风量/AUTO 是 2 格,见 DockItem.slots)。 */
    public static int used(List<DockItem> items) {
        int n = 0;
        for (DockItem it : items) n += it.slots();
        return n;
    }
}
