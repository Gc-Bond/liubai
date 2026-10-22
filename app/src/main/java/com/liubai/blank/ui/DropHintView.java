package com.liubai.blank.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import com.liubai.blank.mode.ModeManager;
import com.liubai.blank.util.Launchers;

/**
 * 拖卡片时铺出来的**格位**:每个能放的位置画一个虚线框,手指底下那一格高亮。
 *
 * 和 Dock 编辑态里的空位是同一套表达 —— 那边空格也是虚线圆角框,一眼看得出
 * "这儿能放东西"。早先试过铺一整屏网格线,两个毛病:格子画的是内部吸附步长
 * (和卡片尺寸没关系),而且满屏的线把壁纸盖成一块布。
 *
 * 格子尺寸就是**一张卡多大**(由 {@link DeskCard.Host#snapCell()} 给),所以虚框和卡片
 * 严丝合缝,松手是"啪"地落进某一格。
 */
public class DropHintView extends View {

    private final Paint slot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hotFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hotLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float radius, inset;

    private int cw, ch, ox, oy;                  // 格子尺寸与原点
    private float hotX, hotY, hotW, hotH;        // 手指底下那一格

    public DropHintView(Context ctx) {
        super(ctx);
        setVisibility(GONE);
        setAlpha(0f);
        radius = Launchers.dp(ctx, 12);          // 和卡片圆角一致(DeskCard.RADIUS_DP)
        // 格与格之间那道 gap 是格子的一部分,画的时候往里收一半,虚框才不会两两贴住
        inset = Launchers.dp(ctx, 6);

        slot.setStyle(Paint.Style.STROKE);
        slot.setStrokeWidth(Math.max(1, Launchers.dp(ctx, 1)));
        slot.setPathEffect(new DashPathEffect(
                new float[]{Launchers.dp(ctx, 5), Launchers.dp(ctx, 4)}, 0));

        hotFill.setStyle(Paint.Style.FILL);
        hotLine.setStyle(Paint.Style.STROKE);
        hotLine.setStrokeWidth(Launchers.dp(ctx, 2));
        hotLine.setPathEffect(new DashPathEffect(
                new float[]{Launchers.dp(ctx, 7), Launchers.dp(ctx, 5)}, 0));
    }

    /** 格子尺寸和原点(px,主页层坐标)。 */
    public void setCell(int w, int h, int originX, int originY) {
        if (w == cw && h == ch && originX == ox && originY == oy) return;
        cw = w; ch = h; ox = originX; oy = originY;
        invalidate();
    }

    /** 手指底下那一格。值没变不重画 —— 拖动时每帧都在调。 */
    public void setSpot(float x, float y, float w, float h) {
        if (x == hotX && y == hotY && w == hotW && h == hotH) return;
        hotX = x; hotY = y; hotW = w; hotH = h;
        invalidate();
    }

    public void setShown(boolean on) {
        if (on) setVisibility(VISIBLE);
        animate().alpha(on ? 1f : 0f).setDuration(on ? 120 : 180)
                .withEndAction(() -> { if (!on) setVisibility(GONE); })
                .start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || cw <= 0 || ch <= 0) return;

        int fg = ModeManager.get().ink();
        int accent = ModeManager.get().accent();
        slot.setColor((fg & 0x00FFFFFF) | 0x2E000000);
        hotFill.setColor((accent & 0x00FFFFFF) | 0x24000000);
        hotLine.setColor((accent & 0x00FFFFFF) | 0xC0000000);

        // 铺满能放得下整格的范围;放不下的边角不画,免得画出半个框
        for (float y = oy; y + ch <= h; y += ch) {
            for (float x = ox; x + cw <= w; x += cw) {
                boolean hot = hotW > 0 && Math.abs(x - hotX) < 1f && Math.abs(y - hotY) < 1f;
                if (hot) continue;               // 高亮那格最后画,免得被邻居的虚线压住
                r.set(x + inset, y + inset, x + cw - inset, y + ch - inset);
                canvas.drawRoundRect(r, radius, radius, slot);
            }
        }

        if (hotW > 0) {
            r.set(hotX + inset, hotY + inset, hotX + hotW - inset, hotY + hotH - inset);
            canvas.drawRoundRect(r, radius, radius, hotFill);
            canvas.drawRoundRect(r, radius, radius, hotLine);
        }
    }
}
