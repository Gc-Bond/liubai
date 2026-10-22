package com.liubai.blank.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import com.liubai.blank.mode.ModeManager;

/**
 * 档位条:**几格等高的小方块,亮几格就是几档**。
 *
 * 全项目只有这一种档位长相 —— Dock 的风量(7 格)和座椅(2 格)、空调面板的座椅、
 * 车控卡片的座椅都用它。早先是两份:Dock 那份等高,面板/卡片那份是"渐高竖条"
 * (60%/100%),同一个读数在两处长两个样,得认两遍(2026-08-16 收口)。
 *
 * 不实现 {@link com.liubai.blank.mode.Reskinnable}:暗档颜色在 onDraw 里现取,
 * 换肤只要有人叫它重画就行,而几个宿主换肤时本来就会重新 {@link #set} 一遍。
 */
public class StepBars extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int cells;
    private int level = -1;        // -1 = 还没读到
    private int lit;               // 0 = 跟模式强调色走;座椅传冷暖色

    public StepBars(Context ctx, int cells) {
        super(ctx);
        this.cells = Math.max(1, cells);
    }

    /** 亮几格 + 亮什么色。值没变不重画。 */
    public void set(int level, int litColor) {
        if (level == this.level && litColor == lit) return;
        this.level = level;
        this.lit = litColor;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float gap = w * .16f / Math.max(1, cells - 1);
        float bw = (w - gap * (cells - 1)) / cells;
        float r = Math.min(h, bw) / 2f;
        for (int i = 0; i < cells; i++) {
            // 没读到档位(-1)时整排压暗,不瞎宣布"零档"
            paint.setColor(level > 0 && i < level
                    ? (lit != 0 ? lit : ModeManager.get().accent())
                    : ModeManager.get().faint());
            float x = i * (bw + gap);
            c.drawRoundRect(x, 0, x + bw, h, r, r, paint);
        }
    }
}
