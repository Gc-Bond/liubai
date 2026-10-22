package com.liubai.blank.ui;

/**
 * 冷暖语义色 + 温度分档。**全项目只有这一处定义。**
 *
 * <h3>为什么要收在一起</h3>
 *
 * 收之前散在五个文件里，而且互相对不上：{@code ThermalFx} 的冷色是 {@code #7DD3FC}、
 * {@code SnowDrift} 的是 {@code #38BDF8} —— 彩蛋的设定是"从温度键吹出一阵"，
 * 结果源头和飞出去的粒子不同色；暖色那边 {@code #FBBF24} 和 {@code #EE9B4E} 也是两个值。
 * 24/27/30 这三个断点更是抄了三份（{@code HvacPanel} 两处 + {@code DockView} 一处）。
 *
 * <h3>这不是模式色</h3>
 *
 * 它归**功能语义**，不归模式（制冷/制热必须看得出来，模式只能换皮不能取消）。所以不进 12 个 token，也不随模式变 —— 换了模式，
 * "现在在制冷"这件事的表达不该跟着变。
 *
 * <h3>为什么冷色是青绿而不是蓝</h3>
 *
 * 原来是天蓝 {@code #38BDF8}。2026-08-14 配色对齐鸿蒙时，强调色定成了鸿蒙蓝
 * {@code #006CDE}，两者色相相邻 —— 强调色一撞上语义色，"这个功能开着"和
 * "现在在制冷"就混成一件事（素白模式当年就是 accent 和制冷色同一个色号）。
 * 让路的是语义色：它只在温度相关的几处出现，挪起来就是改这个文件；
 * 强调色则遍布全局，且鸿蒙的标志性就是那抹蓝。
 */
public final class ThermalPalette {

    private ThermalPalette() {}

    // ---- 分档断点（°C）----

    /** ≤ 这个值算制冷。 */
    public static final float COOL_MAX = 24f;
    /** < 这个值算常温（COOL_MAX 到它之间是过渡）。 */
    public static final float MILD_MAX = 27f;
    /** < 这个值算制热，≥ 就是高温。 */
    public static final float WARM_MAX = 30f;

    // ---- 色 ----

    /** 制冷。青绿，避开鸿蒙蓝强调色。 */
    public static final int COOL = 0xFF4FD1C5;
    /** 制冷的轻量版：气流线、粒子这些要压在底色上不抢戏的地方用。 */
    public static final int COOL_SOFT = 0xFF99F6E4;
    /** 制热。琥珀。 */
    public static final int WARM = 0xFFFBBF24;
    /** 高温。 */
    public static final int HOT = 0xFFF87171;

    /**
     * 温度对应的色。
     *
     * @param v       温度；负数表示读不到
     * @param neutral 常温档和读不到时用什么 —— **由调用方给**，这是有意的：
     *                温度**文字**用 {@code ink}（读数首先得看得清），
     *                **气流线**用 {@link #COOL_SOFT}（它是图形，要表意）。
     *                两者取舍不同，别硬统一成一个。
     */
    public static int of(float v, int neutral) {
        if (v < 0) return neutral;
        if (v <= COOL_MAX) return COOL;
        if (v < MILD_MAX)  return neutral;
        if (v < WARM_MAX)  return WARM;
        return HOT;
    }

    /**
     * 现在是在制冷还是制热（给粒子效果用，见 {@code ThermalFx} 的 COOL/HEAT/NONE）。
     * 只按温度分，压缩机状态由调用方另判。
     */
    public static boolean isHeating(float v) { return v >= MILD_MAX; }
}
