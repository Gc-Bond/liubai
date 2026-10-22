package com.liubai.blank.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.Choreographer;
import android.widget.FrameLayout;

import com.liubai.blank.util.Launchers;

/**
 * 会自己下雪/冒热气的温度卡:制冷时雪花缓缓飘落,制热时暖色光点缓缓上升。
 *
 * 一降一升共用同一套粒子,只换方向和颜色 —— 语义对称,余光扫一眼就知道现在是在
 * 制冷还是制热,不用读数字。
 *
 * 只在空调面板打开时跑,面板一收就停(和 {@link SeatFlowView} 同一个道理):
 * 常驻的动画在车上是持续勾引,也白耗帧。
 *
 * 粒子画在**背景之后、内容之前**(onDraw 的位置),透明度压得很低 ——
 * 它是气氛不是内容,不能盖过大号读数。
 *
 * 为什么是容器自己画,而不是加一层粒子 View:MATCH_PARENT 的子 view 装进
 * WRAP_CONTENT 的父容器,父容器会按"可用高度"算自己的尺寸 —— 卡片会被撑到整屏高。
 */
public class ThermalFx extends FrameLayout implements Choreographer.FrameCallback {

    public static final int NONE = 0, COOL = 1, HEAT = 2;

    private static final int COUNT = 11;
    // 轻量档:粒子压在键的底色上,不该抢读数的戏
    private static final int COOL_COLOR = ThermalPalette.COOL_SOFT;
    private static final int HEAT_COLOR = ThermalPalette.WARM;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] px = new float[COUNT];
    private final float[] py = new float[COUNT];
    private final float[] pr = new float[COUNT];        // 半径(px)
    private final float[] pv = new float[COUNT];        // 纵向速度(px/s,恒正,方向由 mode 定)
    private final float[] pPhase = new float[COUNT];    // 横摆相位
    private final float[] pSway = new float[COUNT];     // 横摆幅度(px)
    private final float[] pRot = new float[COUNT];      // 雪花自转角(度)
    private final float[] pSpin = new float[COUNT];     // 自转速度(度/秒)

    /** 实际用几粒。Dock 的温度键只有 96×52dp,铺 11 片就成噪点了,那里只放 2 片。 */
    private int count = COUNT;
    private float radiusScale = 1f;
    private int mode = NONE;
    private boolean running;
    private long last;
    private float clock;                                // 累计时间,驱动横摆
    private boolean seeded;

    public ThermalFx(Context c) {
        super(c);
        paint.setStyle(Paint.Style.FILL);
        setWillNotDraw(false);   // ViewGroup 默认跳过 onDraw
    }

    /** 粒子数量与大小系数:小容器(如 Dock 的温度键)要少而小,不然像脏点。 */
    public void setDensity(int count, float radiusScale) {
        this.count = Math.max(1, Math.min(COUNT, count));
        this.radiusScale = radiusScale;
        seed();
    }

    /** 切换冷/热/无。NONE 直接停,不留残影。 */
    public void setMode(int mode) {
        if (this.mode == mode) return;
        this.mode = mode;
        if (mode == NONE) {
            stop();
            invalidate();
        } else {
            seed();
            start();
        }
    }

    public void start() {
        if (running || mode == NONE || getVisibility() != VISIBLE) return;
        running = true;
        last = 0;
        Choreographer.getInstance().postFrameCallback(this);
    }

    public void stop() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(this);
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stop();
    }

    /**
     * 铺一遍粒子。位置/速度是**按下标算出来的**而不是随机数:
     * 随机数会让每次打开面板的画面都不一样,而且工作流里不让用 Math.random。
     * 用几个互质的步长错开,看起来足够散。
     */
    private void seed() {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        seeded = true;
        float dp = Launchers.dp(getContext(), 1);
        for (int i = 0; i < count; i++) {
            px[i] = w * ((i * 37 % 100) / 100f);
            py[i] = h * ((i * 53 % 100) / 100f);
            // 六角画得出枝就够了,再大就压过大号读数 —— 它是气氛不是内容
            pr[i] = dp * (1.9f + (i % 4) * 0.5f) * radiusScale;
            pv[i] = dp * (7f + (i % 5) * 2.5f);         // 7~17 dp/s,慢
            pPhase[i] = (i * 29 % 100) / 100f * 6.28f;
            pSway[i] = dp * (3f + (i % 3) * 2f);
            pRot[i] = i * 31 % 60;
            pSpin[i] = (i % 2 == 0 ? 1 : -1) * (8f + (i % 3) * 5f);   // 正反都有,慢转
        }
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        seed();
    }

    /**
     * 被风吹一阵:这一格里的粒子边落边被横着带走,飘出格子外。
     *
     * **为什么要有这个**:Dock 上那阵彩蛋(见 {@code ui/fx/SnowDrift})号称是"温度键上的
     * 雪花被风吹走了"。可如果这一格里的粒子照旧在原地飘,那就是假的 —— 看着是键里一批、
     * 键外另一批,两不相干。源头得真的有反应,联动才成立。
     *
     * **不动它的功能语义**:粒子该在还在、冷热该怎么表达还怎么表达,只是被吹偏一阵。
     *
     * @param dir      +1 往右 / -1 往左,跟彩蛋同一个方向
     * @param seconds  吹多久。**和 DockView.FX_SECONDS / SnowDrift.DURATION 一致**
     * @param fanLevel 风量档位 1~7,风越大吹得越急(0 档风机停转,调用方不会吹到这儿)。
     *                 加成公式和 SnowDrift 里那个 boost 必须一样,不然风量一大,
     *                 Dock 上那批加速了、这一格里的还是原速,两批就散了
     */
    public void gust(int dir, float seconds, int fanLevel) {
        gustDir = dir >= 0 ? 1 : -1;
        gustLeft = seconds;
        float boost = 0.6f + Math.max(0, Math.min(7, fanLevel)) * 0.12f;
        gustVx = Launchers.dp(getContext(), GUST_SPEED_DP) * boost;
        start();          // mode==NONE 时 start() 自己会挡掉,不用在这儿判
    }

    /**
     * 被吹时的横向速度(dp/s)。
     *
     * **和 `ui/fx/SnowDrift.SPEED_DP` 保持一致** —— 键里被吹走的和 Dock 上那批是同一阵风,
     * 速度差一点点都看得出是两批东西(先前一个 490px/s、一个 116~244px/s,前者追着后者跑)。
     *
     * 注意这是**速度**,不是"多久飘到对面"。早先按 `射程/时长` 算,结果 Dock 越宽飞得越快,
     * 嗖一下就过去了 —— 风吹多久是 `seconds` 管的事,飘多远是这个速度顺带的结果,飘不到
     * 头也无所谓。
     */
    private static final float GUST_SPEED_DP = 150f;

    private float gustVx;      // 被吹的横向速度(px/s)
    private float gustLeft;    // 还要吹多久(秒)
    private int gustDir = 1;

    @Override public void doFrame(long now) {
        if (!running) return;
        if (last != 0) {
            float dt = Math.min(0.05f, (now - last) / 1_000_000_000f);
            clock += dt;
            int h = getHeight(), w = getWidth();
            boolean blowing = gustLeft > 0f;
            for (int i = 0; i < count; i++) {
                // 冷往下、热往上 —— **一直在走**,被吹的时候也不停
                py[i] += (mode == COOL ? pv[i] : -pv[i]) * dt;
                if (mode == COOL && py[i] - pr[i] > h) py[i] = -pr[i];
                if (mode == HEAT && py[i] + pr[i] < 0) py[i] = h + pr[i];
                pRot[i] += pSpin[i] * dt;
                if (blowing) {
                    /*
                     * 同时被风横着带走:**落下来的那些都斜着飞出去了**。
                     * 纵向的降/升不停,两个运动叠加成一条斜线 —— 那才是"边落边被吹走"。
                     *
                     * **一路飘出去,不回绕** —— 风吹 3 秒,能飘多远是速度决定的,飘不到
                     * Dock 那头也无所谓。画得出这一格是因为 DockView 设了
                     * setClipChildren(false)。
                     */
                    px[i] += gustDir * gustVx * dt;
                } else if (px[i] < 0f || px[i] > w) {
                    // 风停了,飘在格子外的收回来,别停在半路上
                    px[i] = (i * 37 % 100) / 100f * w;
                }
            }
            if (blowing) gustLeft -= dt;
            invalidate();
        }
        last = now;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override protected void onDraw(Canvas c) {
        if (mode == NONE) return;
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (!seeded) seed();

        int base = mode == COOL ? COOL_COLOR : HEAT_COLOR;
        for (int i = 0; i < count; i++) {
            // 横摆:各自相位,免得整排一起晃
            float x = px[i] + (float) Math.sin(clock * 0.9f + pPhase[i]) * pSway[i];
            float y = py[i];
            // 上下缘淡出,粒子不会"啪"地出现或消失
            float edge = Math.min(y, h - y) / (h * 0.28f);
            float a = Math.max(0f, Math.min(1f, edge)) * 0.3f;
            paint.setColor((base & 0x00FFFFFF) | ((int) (a * 255) << 24));
            if (mode == COOL) drawFlake(c, x, y, pr[i], pRot[i]);
            else c.drawCircle(x, y, pr[i] * .7f, paint);   // 热:柔和光点上升
        }
    }

    /**
     * 一片雪花:三条过中心的线交成六角,每条末端各挑两根短枝。
     * 圆点当雪花太敷衍 —— 六角是雪花唯一认得出来的特征,再小也得画出来。
     */
    private void drawFlake(Canvas c, float cx, float cy, float r, float rotDeg) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, r * .34f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        c.save();
        c.rotate(rotDeg, cx, cy);
        for (int k = 0; k < 3; k++) {
            c.save();
            c.rotate(k * 60f, cx, cy);
            c.drawLine(cx, cy - r, cx, cy + r, paint);
            // 末端小枝:雪花的"羽"
            float b = r * .42f;
            c.drawLine(cx, cy - r, cx - b * .7f, cy - r + b * .7f, paint);
            c.drawLine(cx, cy - r, cx + b * .7f, cy - r + b * .7f, paint);
            c.drawLine(cx, cy + r, cx - b * .7f, cy + r - b * .7f, paint);
            c.drawLine(cx, cy + r, cx + b * .7f, cy + r - b * .7f, paint);
            c.restore();
        }
        c.restore();
        paint.setStyle(Paint.Style.FILL);
    }
}
