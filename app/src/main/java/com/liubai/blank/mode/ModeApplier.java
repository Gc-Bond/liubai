package com.liubai.blank.mode;

import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * 把当前模式(ModeManager)套到一棵 view 树上。
 * 视图用 android:tag 声明要模式化的部分(声明式 DSL,分号分隔多条指令):
 *   fg:TOKEN     文字色(TextView)
 *   bg:TOKEN     纯色背景(setBackgroundColor;仅用于本无 shape 背景的 view)
 *   tint:TOKEN   图标着色(ImageView)
 *   fill:TOKEN   背景 shape 的填充色(要求背景已是 shape / GradientDrawable)
 *   stroke:TOKEN 背景 shape 的描边色(1dp)
 *   radius:TOKEN 背景 shape 的圆角(**几何**,取值走 {@link Dim} 不是色表)
 *   panel        这张底是面板/弹窗的最外层,叠上面板不透明度(**无参指令**,没有冒号)
 * 颜色 TOKEN 见 ModeManager.token(),几何 TOKEN 见 {@link Dim}。XML 里原有的
 * @color / 圆角作为初始值 / 兜底保留,apply() 运行时用模式覆盖;换模式后对同一棵树
 * 再 apply() 一次即可重新上色、重新定形。
 *
 * 为什么圆角是几何里第一个接进来的:它**只影响绘制、不触发布局**。间距、字号、
 * Dock 高度那些改了要 requestLayout,是另一档的事,得单独设计。
 */
public final class ModeApplier {

    private ModeApplier() {}

    public static void apply(View root) {
        apply(root, ModeManager.get());
    }

    public static void apply(View root, ModeManager tm) {
        applyOne(root, tm);
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                apply(g.getChildAt(i), tm);
            }
        }
    }

    private static void applyOne(View v, ModeManager tm) {
        // 自绘 view 的颜色烧在 Paint 里,tag 够不着,让它自己重取一遍(见 Reskinnable)。
        // 放在最前面:它可能顺带重建背景 drawable,后面的 fill/stroke/panel 指令要盖在新的上面
        if (v instanceof Reskinnable) ((Reskinnable) v).reskin();
        Object tag = v.getTag();
        if (!(tag instanceof String)) return;
        for (String part : ((String) tag).split(";")) {
            int c = part.indexOf(':');
            if (c < 0) {
                // 无参指令。放在带参解析之前 —— 它没有冒号,走不到下面那套 op:name 的拆法
                if ("panel".equals(part.trim())) opacity(v, tm.panelOpacity());
                continue;
            }
            String op = part.substring(0, c).trim();
            String name = part.substring(c + 1).trim();
            // 几何指令走 dim 表,不是色表(见 Dim)。放在取色之前 —— 别拿几何 token 名去查颜色
            if ("radius".equals(op)) {
                GradientDrawable gd = shapeBg(v);
                if (gd != null) gd.setCornerRadius(tm.dimPx(v.getContext(), name));
                continue;
            }
            int color = tm.token(name);
            switch (op) {
                case "fg":
                    if (v instanceof TextView) ((TextView) v).setTextColor(color);
                    break;
                case "bg":
                    v.setBackgroundColor(color);
                    break;
                case "tint":
                    if (v instanceof ImageView) {
                        ((ImageView) v).setColorFilter(color, PorterDuff.Mode.SRC_IN);
                    }
                    break;
                case "fill": {
                    GradientDrawable gd = shapeBg(v);
                    if (gd != null) gd.setColor(color);
                    break;
                }
                case "stroke": {
                    GradientDrawable gd = shapeBg(v);
                    if (gd != null) gd.setStroke(dp(v, 1), color);
                    break;
                }
            }
        }
    }

    /**
     * 把不透明度(0~100)叠到这个 view 的**背景**上。
     *
     * 只动背景的 alpha,不动 View 的 alpha —— 后者会把图标和文字一起弄透明,
     * 那就不是"透出背后"而是"整块变淡",在行车中更难看清。
     *
     * 幂等:setAlpha 设的是绝对值不是累乘,重复 apply 同一棵树不会越叠越透。
     * 和 setColor 也互不干扰(GradientDrawable 里 mAlpha 是独立字段,绘制时才相乘),
     * 所以 tag 里 panel 写在 fill 前面还是后面都一样。
     */
    public static void opacity(View v, int percent) {
        Drawable d = v.getBackground();
        if (d == null) return;
        d.mutate().setAlpha(Math.round(Math.max(0, Math.min(100, percent)) * 2.55f));
    }

    /** 取 view 的 shape 背景(可变副本)。非 shape 背景返回 null。 */
    private static GradientDrawable shapeBg(View v) {
        Drawable d = v.getBackground();
        if (d instanceof GradientDrawable) return (GradientDrawable) d.mutate();
        return null;
    }

    private static int dp(View v, int d) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, d, v.getResources().getDisplayMetrics()));
    }
}
