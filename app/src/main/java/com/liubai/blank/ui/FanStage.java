package com.liubai.blank.ui;

import android.animation.ArgbEvaluator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;

import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;

import com.liubai.blank.mode.ModeManager;
import com.liubai.blank.util.Launchers;

/**
 * 调节气泡里的那块"舞台"。风量模式下是七根档位柱。
 *
 * 柱子离散、可数、位置固定 —— 开车扫一眼要能数出"第几档",这个不能靠感觉;
 * 柱高本身编码风大风小,不看数字也有量感。
 *
 * **原来上面还吹着一层粒子**(越高档越密越快、带拖尾),2026-08-01 去掉了:
 * 舞台只有 150×34dp,七八条淡线散在里头读不出气流,只剩杂音。档位是离散量,
 * 柱子已经把"第几档"和"风大风小"都说清楚了,粒子是多余的一层。
 *
 * **试过一版"七块等高阶梯、只切填充色"**(对齐视觉改良稿的 B 方案),2026-08-02 回滚:
 * 加大的圆角连带把辉光条放大,5dp 的格间距装不下,亮块的光晕互相桥接、糊到邻块上,
 * 整排挤成一团。150dp 宽的舞台里,柱子的"块感"给不起那个圆角和光晕。
 *
 * 柱子用弹簧而不是补间:滑动中档位是连续变的,弹簧被打断后能带着当前速度改道。
 * 七根各自延迟起跳,档位一变就有一道波浪扫过去,方向跟着调节方向走。
 *
 * 温度也共用这个气泡,但温度是连续量,柱子不成立 —— MODE_TEMP 下退化成一条带冷暖
 * 色阶的横条。两种模式同一个 View,切来切去不重新 measure。
 *
 */
class FanStage extends View {

    static final int MODE_FAN = 0, MODE_TEMP = 1;

    private static final int BARS = 7;          // 1~7 档;0 档是"全灭"不是一根柱子
    private static final int CASCADE_MS = 26;   // 相邻两根柱子的起跳间隔

    private static final ArgbEvaluator ARGB = new ArgbEvaluator();

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // ---- 柱子 ----
    private final FloatValueHolder[] barH = new FloatValueHolder[BARS];
    private final SpringAnimation[] barSpring = new SpringAnimation[BARS];
    private final Runnable[] barKick = new Runnable[BARS];
    private final float[] barTarget = new float[BARS];

    private int mode = MODE_FAN;
    private int level = -1;        // 0~7;-1 = 读不到
    private float tempProgress;    // MODE_TEMP:0..1
    private int tempColor = ThermalPalette.COOL_SOFT;

    private LinearGradient tempGrad;
    private int tempGradW;

    FanStage(Context c) {
        super(c);
        for (int i = 0; i < BARS; i++) {
            barH[i] = new FloatValueHolder(0f);
            barSpring[i] = Motion.valueSpring(barH[i], Motion.ST_BAR, Motion.DP_BAR,
                    (a, v, velocity) -> invalidate());
            final int idx = i;
            barKick[i] = () -> barSpring[idx].animateToFinalPosition(barTarget[idx]);
        }
    }

    // ---- 对外 ----

    void setMode(int mode) {
        if (this.mode == mode) return;
        this.mode = mode;
        invalidate();
    }

    /** 档位 0~7,-1 = 读不到。dir 决定柱子波浪往哪边扫。 */
    void setFan(int level, int dir) {
        if (this.level == level) return;
        this.level = level;
        retarget(dir);
        invalidate();
    }

    void setTemp(float progress, int color) {
        tempProgress = Math.max(0f, Math.min(1f, progress));
        tempColor = color;
        invalidate();
    }

    // ---- 柱子 ----

    /**
     * 重新给七根柱子派高度,并按方向排好起跳顺序:升档从左往右点起来,降档从右往左灭下去。
     * 波浪的方向跟手,不然降档时看着像在"重新点亮",语义是反的。
     */
    private void retarget(int dir) {
        float h = usableBarH();
        if (h <= 0) return;
        float floor = floorH();
        int order = 0;
        for (int k = 0; k < BARS; k++) {
            int i = dir < 0 ? BARS - 1 - k : k;      // 降档从右往左灭
            boolean lit = level > 0 && i < level;
            // 点亮的柱子高度递增:柱高本身就编码了"风大风小",不看数字也有量感。
            // 起点别抬太高:舞台只有 34dp,0.45 起跳时七根的高度差只剩 6dp,肉眼是一样高的
            // 起点从 0.22 抬到 0.45:低档位时七根全趴在底下,读不出"有风"
            float t = lit ? floor + (h - floor) * (0.30f + 0.70f * i / (BARS - 1f)) : floor;
            // 只动 target 真的变了的那几根。全量重排有两个毛病:没变的柱子正在跑的弹簧
            // 会被打断,而且延迟按绝对序号排 —— 滑一格只该动一根,却要等它排到第 i 位。
            // 连续快滑时更糟:高位柱子的 kick 被反复取消重排,可能一直起跳不了
            if (Math.abs(t - barTarget[i]) < 0.5f) continue;
            barTarget[i] = t;
            removeCallbacks(barKick[i]);
            postDelayed(barKick[i], (long) order * CASCADE_MS);
            order++;                                  // 序号按"这次变了的第几根"走
        }
    }

    private float usableBarH() { return getHeight() * 0.58f; }

    /** 没点亮的柱子留的底座:告诉用户"这里还有档位",不是空的。 */
    /**
     * 灭掉的柱子留多高。**不能太矮也不能太高**:4dp 时是根细线,和点亮的圆角柱像两种
     * 东西;10dp 又把亮柱的可用空间挤掉三成,七根看着一样高、读不出档位。7dp 是折中。
     */
    private float floorH() { return Launchers.dp(getContext(), 7); }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        for (int i = 0; i < BARS; i++) {
            removeCallbacks(barKick[i]);
            barSpring[i].cancel();
        }
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        // 尺寸定了才算得出柱高,首次布局要把当前档位落到位(不带波浪,直接就位)
        float floor = floorH(), bh = usableBarH();
        for (int i = 0; i < BARS; i++) {
            boolean lit = level > 0 && i < level;
            barTarget[i] = lit ? floor + (bh - floor) * (0.30f + 0.70f * i / (BARS - 1f)) : floor;
            barSpring[i].cancel();
            barH[i].setValue(barTarget[i]);
        }
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (mode == MODE_TEMP) { drawTemp(c, w, h); return; }

        int accent = ModeManager.get().accent();

        paint.setStyle(Paint.Style.FILL);
        float gap = Launchers.dp(getContext(), 5);
        float bw = (w - gap * (BARS - 1)) / BARS;
        float r = Math.min(bw, Launchers.dp(getContext(), 4)) / 2f;
        int dim = (ModeManager.get().faint() & 0x00FFFFFF) | 0x99000000;
        float floor = floorH();
        float span = Math.max(1f, usableBarH() - floor);
        for (int i = 0; i < BARS; i++) {
            float bh = barH[i].getValue();
            float x = i * (bw + gap);
            // 颜色跟着**高度**走而不是跟着档位瞬时切:柱子是级联起跳的,颜色一瞬间全切过去
            // 会露出"已经亮了但还没长起来"的半拍。挂在高度上,波浪就是边长高边点亮,
            // 降档落下来也同步暗回去。×2.2 是让它刚离地就亮起来,不然看着像"先长后亮"
            float t = Math.max(0f, Math.min(1f, (bh - floor) / span));
            paint.setColor((int) ARGB.evaluate(Math.min(1f, t * 2.2f), dim, accent));
            c.drawRoundRect(x, h - bh, x + bw, h, r, r, paint);
            // 柱顶曾经补过一圈"辉光"(22% 透明度的 accent 圆角条),已撤:半透明青压在
            // 暗底上,亮度卡在柱体和背景之间,放大看就是一层脏影 —— 描边式的假光晕
            // 做不出真辉光,而"点亮"本来就有颜色和高度两个信号,不缺这一层
        }
    }

    /** 温度:连续量,一条带冷暖色阶的横条 + 末端旋钮。 */
    private void drawTemp(Canvas c, int w, int h) {
        float barH = Launchers.dp(getContext(), 6);
        float top = (h - barH) / 2f;
        float r = barH / 2f;

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        paint.setColor(ModeManager.get().faint());
        c.drawRoundRect(0, top, w, top + barH, r, r, paint);

        if (tempProgress > 0f) {
            if (tempGrad == null || tempGradW != w) {
                tempGradW = w;
                // 冷→暖的色阶,和空调面板里气流那套配色同源
                tempGrad = new LinearGradient(0, 0, w, 0,
                        new int[]{ThermalPalette.COOL, ThermalPalette.COOL_SOFT,
                                ThermalPalette.WARM, ThermalPalette.HOT},
                        new float[]{0f, .42f, .72f, 1f}, Shader.TileMode.CLAMP);
            }
            paint.setShader(tempGrad);
            c.drawRoundRect(0, top, Math.max(barH, w * tempProgress), top + barH, r, r, paint);
            paint.setShader(null);

            float kx = Math.max(barH, w * tempProgress);
            float kr = Launchers.dp(getContext(), 7);
            paint.setColor((tempColor & 0x00FFFFFF) | 0x33000000);
            c.drawCircle(kx, h / 2f, kr + Launchers.dp(getContext(), 4), paint);
            // 把手不再填 ink:暗模式下那是画面里唯一的纯白圆,比读数还抢眼。
            // 改成「卡片色一圈 + 数据色芯」:圈把它和轨道分开,芯就是当前温度的语义色
            // (和读数同源)。两个颜色都来自模式/数据,不写死
            paint.setColor(ModeManager.get().surface());
            c.drawCircle(kx, h / 2f, kr, paint);
            paint.setColor(tempColor);
            c.drawCircle(kx, h / 2f, kr - Launchers.dp(getContext(), 2.5f), paint);
        }
    }
}
