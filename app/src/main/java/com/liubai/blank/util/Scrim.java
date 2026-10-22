package com.liubai.blank.util;

/**
 * 弹层底下那层压暗。**三档，全项目只有这一处定义。**
 *
 * <h3>为什么是中性黑</h3>
 *
 * 原来四处各写各的，其中三处是 {@code #020610} 那个蓝黑（{@code 0x59020610} /
 * {@code 0x73020610}）—— 那是素白模式时代留下的，它的 bg 是 {@code #060B14}，
 * 压一层同色系的暗才不脏。素白 2026-08-02 删了，浅色模式（昼白）下再压蓝黑就是错的：
 * 白底上蒙一层蓝，观感是"发霉"而不是"变暗"。
 *
 * 遮罩的职责只有一个 —— 把背后的内容压下去、让上面的弹层浮起来。中性黑对深浅两种底
 * 都成立，不掺色相。
 *
 * <h3>三档怎么分</h3>
 *
 * 按"弹层盖住多少、要压多狠"分，不是随便取的：
 * <ul>
 *   <li>{@link #LIGHT} 20% —— 弹层只占一角，背后大部分还要看得见（外观弹层：
 *       调配色时得看着桌面）</li>
 *   <li>{@link #MEDIUM} 35% —— 半屏面板（Dock 编辑、车控编辑）</li>
 *   <li>{@link #HEAVY} 45% —— 几乎整屏的面板（空调面板），也是 themes.xml 里
 *       {@code BlankTheme.Translucent} 那个 windowBackground 用的档</li>
 * </ul>
 */
public final class Scrim {

    private Scrim() {}

    /** 20%：弹层只占一角，背后要看得清。 */
    public static final int LIGHT  = 0x33000000;

    /** 35%：半屏面板。 */
    public static final int MEDIUM = 0x59000000;

    /** 45%：近整屏面板。themes.xml 的 {@code BlankTheme.Translucent} 同档。 */
    public static final int HEAVY  = 0x73000000;
}
