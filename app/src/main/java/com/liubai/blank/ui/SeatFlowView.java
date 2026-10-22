package com.liubai.blank.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;

import com.liubai.blank.R;
import com.liubai.blank.mode.ModeManager;
import com.liubai.blank.mode.Reskinnable;

/**
 * 面板中间那颗风向字形:人形侧影 + 三路气流,吹哪儿一眼看得出。
 *
 * 字形和 Dock 上那颗**同一个来源** —— BYD 的 nav_common_ac_wind_mode_1..7,
 * 编号就是 {@code BYDAutoAcDevice.AC_WINDMODE_*}(见 {@link #iconFor})。
 * 早先这里是一套自绘的气流曲线(250x170 画布 + 流动虚线),画得不难看,但和整条 Dock
 * 不是一套笔法 —— 同一个功能在两处长两个样,用户先得认两遍。
 *
 * 点按仍然是**按纵向三分**:上=吹风挡 / 中=吹面 / 下=吹脚。字形本身就是个坐着的人,
 * 头在上脚在下,手指戳的位置和它表达的含义天然对得上。
 */
public class SeatFlowView extends View implements Reskinnable {

    public static final int WIND = 0, FACE = 1, FEET = 2;

    public interface Listener {
        void onFlowToggle(int which);
    }

    /**
     * 三路开关 → 字形。编号直接是 {@code AirCon.windModeOf()} 那张表:
     * 1=FACE 2=FACEFOOT 3=FOOT 4=FOOTDEFROST 5=DEFROST 6=三向全开 7=FACEDEFROST。
     * 三向全关(0)在车上不合法,一律按吹面显示。
     *
     * {@link DockView} 上那颗键也调这儿 —— 同一份映射只留一份。
     */
    public static int iconFor(boolean face, boolean foot, boolean windDef) {
        if (face && !foot && !windDef) return R.drawable.ic_wind_mode_1;
        if (face && foot && !windDef)  return R.drawable.ic_wind_mode_2;
        if (!face && foot && !windDef) return R.drawable.ic_wind_mode_3;
        if (!face && foot && windDef)  return R.drawable.ic_wind_mode_4;
        if (!face && !foot && windDef) return R.drawable.ic_wind_mode_5;
        if (face && foot && windDef)   return R.drawable.ic_wind_mode_6;
        if (face && !foot && windDef)  return R.drawable.ic_wind_mode_7;
        return R.drawable.ic_wind_mode_1;
    }

    /**
     * 三组风线在字形里的纵向位置(viewBox 6826.7 的坐标,量自 mode_1/3/5 的第三条路径):
     * 风挡 1212~2252 / 吹面 2624~3108 / 吹脚 3701~4760。
     * 分界取相邻两组的中点,再经 icon_map 那套归一化(活动区 20、tweak 1.12)换算成
     * 占 View 高的比例 —— **别按 View 高三等分**,三组线本来就不是均匀分布的。
     */
    private static final float SPLIT_WIND_FACE = .31f, SPLIT_FACE_FEET = .45f;

    private Drawable glyph;
    private int glyphRes;
    /** 三向全开那张,垫在底下当"还没开的那几路" —— 七个字形共用一个包围盒,人形严丝合缝。 */
    private final Drawable ghost;
    private int flowColor = 0;   // 0 = 还没给色,onDraw 里退回模式强调色
    private Listener listener;

    public SeatFlowView(Context c) {
        super(c);
        ghost = c.getDrawable(R.drawable.ic_wind_mode_6);
        setGlyph(R.drawable.ic_wind_mode_1);
    }

    @Override public void reskin() {
        invalidate();
    }

    public void setListener(Listener l) { this.listener = l; }

    /** 三路开关 + 冷暖色。字形跟着组合换,颜色跟着冷暖走。 */
    public void setState(boolean wind, boolean face, boolean feet, int color) {
        setGlyph(iconFor(face, feet, wind));
        this.flowColor = color;
        invalidate();
    }

    private void setGlyph(int res) {
        if (res == glyphRes && glyph != null) return;
        glyphRes = res;
        glyph = getContext().getDrawable(res);
    }

    // ---- 绘制 ----

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0 || glyph == null) return;
        int s = Math.min(w, h);                    // 字形是方的,取短边铺满、居中
        int l = (w - s) / 2, t = (h - s) / 2;
        // 先垫一层三向全开的暗字形:没开的那几路以暗线露出来,一眼看得出还能开哪儿
        // (这是旧那版自绘的「静止暗线」,换字形不能把它丢了)
        if (ghost != null) {
            ghost.setBounds(l, t, l + s, t + s);
            ghost.setColorFilter(ModeManager.get().faint(), PorterDuff.Mode.SRC_IN);
            ghost.draw(canvas);
        }
        glyph.setBounds(l, t, l + s, t + s);
        glyph.setColorFilter(flowColor != 0 ? flowColor : ModeManager.get().accent(),
                PorterDuff.Mode.SRC_IN);
        glyph.draw(canvas);
    }

    // ---- 点按:按纵向区间判断点的是哪一路 ----

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() != MotionEvent.ACTION_UP || listener == null) {
            return e.getActionMasked() == MotionEvent.ACTION_DOWN;
        }
        float f = e.getY() / Math.max(1, getHeight());
        listener.onFlowToggle(f < SPLIT_WIND_FACE ? WIND : (f < SPLIT_FACE_FEET ? FACE : FEET));
        performClick();
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
