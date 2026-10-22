package com.liubai.blank.mode;

import org.json.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 模式的**几何** token(尺寸,单位 dp),和 12 个配色 token 平行的另一半。
 *
 * 为什么要有这一层:配色只有 12 个色号,两个模式换过来差别很有限;**形状差别比颜色大** ——
 * 圆润和硬朗是两种气质,一眼就分得出。几何(圆角/间距/描边/Dock 高度/字号)本该算进
 * "外观"里,但代码里尺寸一直是写死的:{@code Launchers.dp} 246 处、
 * 布局 XML 里 166 处、{@code dimens.xml} 统共只有 1 条。
 *
 * **不打算把那 412 处全换掉**,也不该。真正决定"是不是另一套桌面"的就几类:
 * 圆角、Dock 高度、间距密度、字号、描边。先从**圆角**开始 —— 它只影响绘制、不触发布局,
 * 是这几类里最安全的一个。
 *
 * 用法:**新代码一律走 token,老代码遇到再换**,不专门开工去改。
 * 模式文件里 {@code dims} 段整段可以不写,缺哪项回退哪项的默认值,所以老模式原样加载、
 * 行为不变(和 {@link Capabilities} 同一条向后兼容策略)。
 */
public final class Dim {

    /** 大卡片 / 弹层:桌面上那四张卡、popup 背板。 */
    public static final String RADIUS_CARD = "radius_card";
    /** 磁贴:快捷车控那些格子。 */
    public static final String RADIUS_TILE = "radius_tile";
    /** Dock 上的键、空调面板里的控件。 */
    public static final String RADIUS_KEY = "radius_key";
    /** 小件:迷你格、握把提示这类。 */
    public static final String RADIUS_CHIP = "radius_chip";

    /**
     * 默认几何(dp)。值取自改造前代码里的实际数字,所以不写 dims 段的模式外观一模一样。
     *
     * 注意**胶囊不在这儿**:{@code pill_*}、{@code progress_track} 那些用的是 999dp,
     * 它表达的是"全圆"这个语义而不是某个尺寸,模式改它只会把胶囊改坏。
     */
    private static final Map<String, Float> DEFAULTS;

    static {
        Map<String, Float> m = new HashMap<>();
        m.put(RADIUS_CARD, 22f);   // card_bg.xml / popup_bg.xml
        m.put(RADIUS_TILE, 18f);   // tile_bg.xml / tile_bg_active.xml
        m.put(RADIUS_KEY, 16f);    // DockView 里几处 setCornerRadius(16)
        m.put(RADIUS_CHIP, 8f);    // mini_cell_bg.xml
        DEFAULTS = Collections.unmodifiableMap(m);
    }

    private Dim() {}

    /** 模式没声明这项时用的值。未知 token 返回 0 —— 拼错了就该看得出来,不是悄悄给个数。 */
    public static float fallback(String name) {
        Float f = DEFAULTS.get(name);
        return f == null ? 0f : f;
    }

    /** 从 mode.json 的 dims 段解析;传 null(没写这段)就是全默认。非数字的项跳过。 */
    public static Map<String, Float> fromJson(JSONObject d) {
        Map<String, Float> out = new HashMap<>(DEFAULTS);
        if (d == null) return Collections.unmodifiableMap(out);
        for (Iterator<String> it = d.keys(); it.hasNext(); ) {
            String k = it.next();
            if (k.startsWith("_")) continue;          // _note 之类的说明字段
            double v = d.optDouble(k, Double.NaN);
            if (!Double.isNaN(v)) out.put(k, (float) v);
        }
        return Collections.unmodifiableMap(out);
    }
}
