package com.liubai.blank.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;

import com.liubai.blank.mode.Reskinnable;
import com.liubai.blank.mode.ModeManager;
import com.liubai.blank.util.Launchers;

/**
 * 读数:大号数值 + 跟在右下的小单位。
 *
 * 数值变化**瞬间换牌,不做动画** —— 这个数是调节中唯一要读的反馈,必须随时读得出来。
 * 曾经做过 190ms 的翻牌(旧值滚出、新值滚入,方向跟着调节方向走):快滑时步进来得比
 * 一次翻牌还快,上一张没翻完就被丢掉重来,数字于是一直停在半透明的中间态 ——
 * 最需要看清的那一刻恰恰最看不清。方向暗示也轮不到数字做:气泡只在用户自己按着
 * 滑的时候出现,往哪边调手比眼清楚;风量那边还有柱子的方向动画({@link FanStage})。
 *
 * 数字用 tnum(等宽数字):比例数字下 "1" 比 "5" 窄,滑动时整个气泡宽度会跟着一格一格地抖。
 */
class RollingNumber extends View implements Reskinnable {

    private final Paint numPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final float gap;

    private String value = "";
    private String unit = "";
    /**
     * 单位基线相对数字高度上抬多少。"档"贴着基线走(0),"°"得抬成上标 ——
     * 度数符号画在基线上会读成一个飘在右下角的句点。
     */
    private float unitRise;

    /**
     * 读数字号(sp)。**一个数,不按内容分档**。
     *
     * 曾经按内容分过(数字 36sp、词 22sp),因为那时"自动"两个汉字占满了气泡上半部。
     * 后来读数统一成单字符(A / 0 / 1~7,温度那边是 LO / HI / 数字),分档就没必要了;
     * 而且**字号一变高度就变**,气泡是按"底边贴住 dockSpace 那条线"定位的,高度变了
     * 底边就漂,表现成气泡到 Dock 的间距忽大忽小。恒定字号 = 恒定高度。
     *
     * 36 → 30:所有气泡统一成换 app 那套的高度(见 HvacBubble.STAGE_H_DP 的说明),
     * 读数是气泡里最高的东西,这条线要对齐只能它先让。
     */
    private static final float SIZE_NUM = 30f;

    RollingNumber(Context c) {
        super(c);
        numPaint.setTextSize(sp(SIZE_NUM));
        numPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        numPaint.setFontFeatureSettings("tnum");
        numPaint.setTextAlign(Paint.Align.LEFT);

        unitPaint.setTextSize(sp(13f));
        unitPaint.setTextAlign(Paint.Align.LEFT);

        gap = Launchers.dp(c, 4);
        reskin();
    }

    /** 读数和单位的颜色,换配色后要跟上(见 Reskinnable)。 */
    @Override public void reskin() {
        numPaint.setColor(ModeManager.get().ink());
        unitPaint.setColor(ModeManager.get().muted());
        invalidate();
    }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v,
                getResources().getDisplayMetrics());
    }

    void setColor(int color) {
        if (numPaint.getColor() == color) return;
        numPaint.setColor(color);
        invalidate();
    }

    void setValue(String v, String unit, float unitRise) {
        boolean unitChanged = !this.unit.equals(unit);
        this.unit = unit;
        this.unitRise = unitRise;
        if (v.equals(value)) {
            if (unitChanged) requestLayout();
            invalidate();
            return;
        }
        boolean widthMayChange = v.length() != value.length() || unitChanged;
        value = v;
        if (widthMayChange) requestLayout();
        invalidate();
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        float w = numPaint.measureText(value);
        if (!unit.isEmpty()) w += gap + unitPaint.measureText(unit);
        Paint.FontMetrics fm = numPaint.getFontMetrics();
        int h = (int) Math.ceil(fm.descent - fm.ascent);
        setMeasuredDimension((int) Math.ceil(w), h);
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        Paint.FontMetrics fm = numPaint.getFontMetrics();
        float baseline = -fm.ascent;
        float numW = numPaint.measureText(value);
        /*
         * 内容**左对齐**画,不在 view 里居中。
         *
         * 居中 + 最小宽度撑着的话,图标到文字的间距会随内容宽窄变:「A」没有单位、内容窄,
         * 左边空出 24dp;「1 档」带单位、内容宽,左边只空 15dp —— 同一个气泡切个档位,
         * 图标和读数的间距就跳了 9dp(实测)。左对齐之后这段间距恒等于 top 排的 marginStart。
         */
        c.drawText(value, 0f, baseline, numPaint);

        if (!unit.isEmpty()) {
            c.drawText(unit, numW + gap, baseline - h * unitRise, unitPaint);
        }
    }
}
