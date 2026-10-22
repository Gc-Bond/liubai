package com.liubai.blank.ui.fx;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;

import com.liubai.blank.mode.ModeManager;
import com.liubai.blank.ui.DockFxField;
import com.liubai.blank.ui.ThermalFx;
import com.liubai.blank.util.Launchers;
import com.liubai.blank.ui.ThermalPalette;

/**
 * 风的状态一变(档位 / AUTO / 电源),从**温度键**那儿吹出一阵:制冷是雪花,制热是火星。
 * 2.6 秒衰减到停。
 *
 * **方向单向,由用户排的 Dock 决定**:温度键在风量键左边就往左吹,在右边就往右吹
 * (见 `DockView.sweepDir`)。整阵风一个方向 —— 风扇朝哪吹,冷气热气就往哪走。
 *
 * **粒子是从温度键里被吹出来的,不是凭空生成的。** 起点散在**那颗键的宽度范围内**
 * (`SIG_ORIGIN_W`),而且按 `pBorn` **陆续出生** —— 看着就是键上飘着的那些雪花被风
 * 一片片吹走。从一个点同时冒出一批的话,和这颗键就没关系了,也就谈不上"联动"。
 *
 * **冷热同一种运动**,只有形态和颜色不同(六角雪花 / 火星)。一阵风就该是一个方向;
 * 早先热做成"从下往上飘",看着和冷像两件事,而且 Dock 只有 48dp 高、纵向根本没有行程。
 *
 * **触发式,不常驻。** 沿用 ThermalFx 早就写下的那条判断——"常驻的动画在车上是持续
 * 勾引,也白耗帧"。Dock 比空调面板显眼得多,常驻只会更糟;而且一直在的东西也不叫彩蛋。
 *
 * 手法照抄 ThermalFx:并行数组、参数**按下标算不用随机数**、边缘淡出。
 *
 * **不参数化、不下发、不做插件**—— 参数化引擎这条路
 * DiStyle 早就走完了(模式包 zip 热重载 + 云同步 + 带条件的自动化任务),留白不在
 * 可配置性上竞争。这里的常量就留在代码里,它是一个动效,不是一个平台。
 */
public final class SnowDrift implements DockFxField {

    /**
     * 一阵吹多久(秒)。够看清吹出去,又短到不会变成常驻氛围。
     * **和 DockView.FX_SECONDS 必须一致** —— 温度键里那阵和 Dock 上这阵是同一阵风,
     * 一个先停就露馅了。
     */
    private static final float DURATION = 3f;

    /** 铺几粒。要横跨整条 Dock,少了看不出是"一阵风"。 */
    private static final int COUNT = 14;

    /**
     * 横向速度(dp/s)。**和 `ThermalFx.GUST_SPEED_DP` 保持一致** —— 温度键里被吹走的
     * 和这里这批是同一阵风,速度差一点都看得出是两批东西。
     */
    private static final float SPEED_DP = 150f;

    /** 峰值透明度。比 ThermalFx 的 0.3 再压一档——它铺的面积大得多。气氛不是内容。 */
    private static final float PEAK_ALPHA = 0.26f;

    /**
     * 冷暖两色。取自 {@code DockView.tempColor()} 那套色阶(冷蓝 / 暖黄)——
     * ThermalFx、空调面板的气流线用的都是它,冷暖在留白里本来就是这一套语言。
     *
     * 冷暖色不进模式 token:它是**功能语义**不是配色(见 ThermalPalette 的说明)。
     * 原来这里硬编码的 {@code #38BDF8} / {@code #EE9B4E} 和 ThermalFx 那两个对不上 ——
     * 彩蛋的设定是"从温度键吹出一阵",源头和飞出去的粒子却不同色。2026-08-14 收口。
     */
    private static final int COOL_COLOR = ThermalPalette.COOL;
    private static final int WARM_COLOR = ThermalPalette.WARM;

    /** 几粒火星。和雪花同量级的小颗粒,可以多一些。 */
    private static final int HEAT_COUNT = 11;

    /**
     * 火星比雪花实一些。雪花是十几片碎点,淡才不脏;火星更小、还带渐变晕,
     * 用雪花那个透明度画出来是暗褐色的、像脏点(实测),要的是亮橙。
     */
    private static final float HEAT_ALPHA_BOOST = 2.2f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp;

    private final float[] px = new float[COUNT];
    private final float[] py = new float[COUNT];
    private final float[] pr = new float[COUNT];      // 半径(px)
    private final float[] pv = new float[COUNT];      // 横向速度(px/s),冷热同一套
    private final float[] pPhase = new float[COUNT];  // 摆动相位
    private final float[] pSway = new float[COUNT];   // 摆幅(px),垂直于运动方向
    private final float[] pRot = new float[COUNT];    // 雪花自转角(度)
    private final float[] pSpin = new float[COUNT];   // 自转速度(度/秒)
    private final float[] pLen = new float[COUNT];    // 热:火星芯半径(px)
    private final float[] pAge = new float[COUNT];    // 这一粒飘了多久(秒)
    /** 这一粒什么时候被吹出来(秒,从点燃算起)。没到就还在温度键里待着,不画。 */
    private final float[] pBorn = new float[COUNT];

    private final android.graphics.Path wave = new android.graphics.Path();
    private final android.graphics.Matrix glowM = new android.graphics.Matrix();
    private android.graphics.RadialGradient glow;

    private int mode = ThermalFx.NONE;
    private int fanLevel;
    private int dir = 1;           // 往哪边吹:+1 右 / -1 左,由温度键在风量键哪一侧决定
    private int originX = -1;      // 发射源(温度键中心);-1 = 还没喂过,退回 Dock 中点
    private int originW;           // 温度键有多宽 —— 粒子在这个范围里出生,不是一个点
    private float life;            // 这一阵还剩多久(秒),0 = 停
    private float clock;           // 累计时间,驱动摆动
    private long last;             // 上一帧时间戳(ns),0 = 刚点燃
    private int seedW, seedH, seedMode = -1;

    public SnowDrift(Context ctx) {
        this.dp = Launchers.dp(ctx, 1);
        paint.setStyle(Paint.Style.FILL);
    }

    @Override
    public void onSignal(String kind, int value) {
        if (SIG_AC_MODE.equals(kind)) {
            mode = value;                 // 冷热布局不同,draw 里发现 mode 变了会重铺
        } else if (SIG_ORIGIN_X.equals(kind)) {
            originX = value;
            seedMode = -1;                // 源头挪了(换模式/改 Dock 排布),重铺
        } else if (SIG_ORIGIN_W.equals(kind)) {
            originW = value;
            seedMode = -1;
        } else if (SIG_DIR.equals(kind)) {
            dir = value >= 0 ? 1 : -1;
            seedMode = -1;                // 方向变了,重铺
        } else if (SIG_FAN_LEVEL.equals(kind)) {
            fanLevel = value;
            // 点燃一阵。已经在吹就**续满**而不是叠加第二套 —— 连着点风量应该表现成
            // "一直吹着",不是越点越密。
            life = DURATION;
            last = 0;                     // 隔了一阵再点燃,别拿上次时间戳算出个巨大的 dt
        }
    }

    @Override
    public boolean animating() {
        return life > 0f && mode != ThermalFx.NONE;
    }

    @Override
    public void draw(Canvas c, int w, int h, long frameTimeNs) {
        if (!animating() || w <= 0 || h <= 0) return;
        if (w != seedW || h != seedH || mode != seedMode) seed(w, h);

        float ox = origin(w);

        if (last != 0) {
            float dt = Math.min(0.05f, (frameTimeNs - last) / 1_000_000_000f);
            life = Math.max(0f, life - dt);
            clock += dt;
            // 档位越高吹得越急。**0 档不会走到这儿** —— 风机停转就没有风,
            // DockView 那边已经拦掉了(见 refreshHvac 末尾的 f > 0)
            float boost = 0.6f + fanLevel * 0.12f;
            float elapsed = DURATION - life;
            for (int i = 0; i < active(); i++) {
                if (elapsed < pBorn[i]) continue;        // 还在温度键里待着,没被吹出来
                pAge[i] += dt;
                // **冷热同一种运动**:都是从温度键往一个方向横着吹。冷热的区别只在
                // 长什么样(六角 / 火星)和什么颜色,不在怎么飞 —— 一阵风就该是一个方向
                px[i] += dir * pv[i] * boost * dt;
                pRot[i] += pSpin[i] * dt;                // 火星 pSpin=0,不转
                // 吹出边界就再从键里出来一片 —— 一阵风是持续吹的,不是飘一次就没了
                float edge = pr[i] * 3f;
                if ((dir < 0 && px[i] + edge < 0) || (dir > 0 && px[i] - edge > w)) {
                    float halfW = (originW > 0 ? originW : dp * 60f) * 0.5f;
                    px[i] = ox + ((i * 37 % 100) / 50f - 1f) * halfW;
                    pAge[i] = 0f;
                }
            }
        }
        last = frameTimeNs;

        boolean heat = mode == ThermalFx.HEAT;
        int base = heat ? WARM_COLOR : COOL_COLOR;

        // 生命包络:快淡入、缓淡出。t 从 1 走到 0
        float t = life / DURATION;
        float env = t > 0.85f ? (1f - t) / 0.15f : t / 0.85f;

        // 「能量逐渐增强」:档位越高热浪越亮(参考图 05 那一排从暗到亮)。
        // 雪花不跟这条 —— 冷是"多少片"在飘,不是"多亮"
        float energy = heat ? 0.62f + Math.max(0, Math.min(7, fanLevel)) * 0.055f : 1f;

        float elapsed = DURATION - life;
        for (int i = 0; i < active(); i++) {
            if (elapsed < pBorn[i]) continue;           // 还没被吹出来,不画
            // 横飞途中上下浮一点。冷热同一种运动,摆动也就同一种
            float y = py[i] + (float) Math.sin(clock * 1.1f + pPhase[i]) * pSway[i];
            float x = px[i];

            float a = edgeFade(x, y, w, h) * env * PEAK_ALPHA * energy
                    * (heat ? HEAT_ALPHA_BOOST : 1f);
            if (heat) {
                // 忽明忽暗 —— 火星就是这么闪的,不闪就成了气泡(第一版的毛病)。
                // 每粒相位不同,不然整片一起呼吸,假
                float twinkle = 0.45f + 0.55f * (float) Math.abs(Math.sin(clock * 3.2f + pPhase[i]));
                drawEmber(c, x, y, pLen[i], base, a * twinkle);
            } else {
                paint.setColor((base & 0x00FFFFFF) | ((int) (a * 255) << 24));
                drawFlake(c, x, y, pr[i], pRot[i]);
            }
        }
    }

    /**
     * 一粒火星:亮芯 + 一圈很淡的晕。小、亮、忽明忽暗地被风吹着走。
     *
     * **和雪花同量级的小颗粒**,这是四版试错后的结论:
     *   · 一条大波浪线 → 头粗尾细,像蝌蚪;
     *   · 渐变雾团 → 48dp 的窄带里糊成一坨橙斑;
     *   · 三条波纹的热浪符号 → 它本身长得就像个功能图标,散在 Dock 上和风扇/温度计
     *     /车那些混成图标堆(截图上盖在温度计上,最乱)。
     * Dock 本来就是符号密集的地方,**再放符号必然打架**;雪花能成立正是因为它小而碎、
     * 不会被当成图标。所以热也得是小颗粒 —— 自然界里和雪花对称的就是火星。
     *
     * 闪烁在 draw 里(alpha 脉动)。**那个"闪"是关键**:第一版的光点不闪,所以像气泡。
     */
    private void drawEmber(Canvas c, float x, float y, float r, int color, float a) {
        // 晕走**径向渐变**。用实心圆画晕的话边界是齐的,看着是一圈圈半透明光盘,
        // 不是发光(实测截图上圆边清清楚楚)。shader 建一次复用,每帧只改 matrix 和
        // alpha —— Dock 是软绘,每帧 new 一个顶不住。
        if (glow == null) {
            glow = new android.graphics.RadialGradient(0f, 0f, 1f,
                    new int[]{(color & 0x00FFFFFF) | 0x66000000,
                            (color & 0x00FFFFFF) | 0x1E000000,
                            color & 0x00FFFFFF},
                    new float[]{0f, 0.4f, 1f},
                    android.graphics.Shader.TileMode.CLAMP);
        }
        float gr = r * 3.4f;
        glowM.setScale(gr, gr);
        glowM.postTranslate(x, y);
        glow.setLocalMatrix(glowM);
        paint.setShader(glow);
        paint.setAlpha((int) (a * 255));
        c.drawCircle(x, y, gr, paint);
        paint.setShader(null);
        paint.setAlpha(255);

        // 芯:小而亮。和晕拉开对比,才像"一粒烧着的东西"而不是一团光
        paint.setColor((color & 0x00FFFFFF) | ((int) (Math.min(1f, a * 1.5f) * 255) << 24));
        c.drawCircle(x, y, r, paint);
    }

    /** 这一阵用几道/几粒。热浪比雪花少 —— 它是一道道的,铺满就成一片橙糊。 */
    private int active() {
        return mode == ThermalFx.HEAT ? HEAT_COUNT : COUNT;
    }

    /** 边缘淡出,不让东西贴着 Dock 边"啪"地冒出来或消失。冷热同一套 —— 运动一样。 */
    private float edgeFade(float x, float y, int w, int h) {
        float fx = Math.min(x, w - x) / (w * 0.12f);
        float fy = Math.min(y, h - y) / (h * 0.3f);
        return Math.max(0f, Math.min(1f, Math.min(fx, fy)));
    }

    private float origin(int w) {
        return originX >= 0 ? originX : w / 2f;
    }

    /**
     * 铺一遍。位置/速度按**下标**算,理由同 {@link ThermalFx}:
     * 用几个互质的步长错开,看着足够散,又保证每次都一样。
     */
    private void seed(int w, int h) {
        seedW = w;
        seedH = h;
        seedMode = mode;
        float ox = origin(w);
        for (int i = 0; i < COUNT; i++) {
            pr[i] = dp * (1.6f + (i % 4) * 0.4f);
            pPhase[i] = (i * 29 % 100) / 100f * 6.28f;
            pRot[i] = i * 31 % 60;
            pSpin[i] = (i % 2 == 0 ? 1 : -1) * (9f + (i % 3) * 5f);
            pAge[i] = (i % 5) * 0.18f;        // 出生年龄错开,不然 14 粒同时胀同时淡
            /*
             * **冷热同一套运动**:都从温度键出发,沿 dir 横着吹、上下浮一点。
             * 区别只在长什么样(六角雪花 / 火星)和什么颜色 —— 一阵风就该是一个方向,
             * 热往上飘那版看着和冷像两件事(而且 Dock 只有 48dp 高,纵向根本没有行程)。
             *
             * **起点在温度键的范围里,而且陆续出生** —— 要的是"键上飘着的那些雪花被风
             * 吹走了",不是从一个点凭空冒出一批。所以横向散在键宽内、出生时间按下标错开,
             * 看着就是一片一片被吹出来的。
             */
            float halfW = (originW > 0 ? originW : dp * 60f) * 0.5f;
            px[i] = ox + ((i * 37 % 100) / 50f - 1f) * halfW;      // 键内散布 -half..+half
            py[i] = h * (0.18f + (i * 53 % 64) / 100f);
            // 前 45% 的时间里陆续吹出来;最后那一段留给已出生的粒子飞完
            pBorn[i] = (i % active()) * (DURATION * 0.45f / active());
            // 速度是**固定的**,不按射程算 —— 风吹多久归 DURATION 管,飘多远是顺带的结果,
            // 飘不到 Dock 那头也无所谓。按射程算的话 Dock 越宽飞得越快,嗖一下就过去了。
            // ±15% 的个体差是要的:整齐划一地平移看着像一张图在滑。
            pv[i] = dp * SPEED_DP * (0.85f + (i % 5) * 0.075f);
            pSway[i] = dp * (2f + (i % 3) * 1.5f);
            if (mode == ThermalFx.HEAT) {
                pLen[i] = dp * (1.1f + (i % 4) * 0.45f);   // 火星芯半径;晕是它的 3.4 倍
                pSpin[i] = 0f;                              // 火星不转
            } else {
                pLen[i] = 0f;                               // 雪花不用它,半径走 pr
            }
        }
    }

    /**
     * 一片雪花:三条过中心的线交成六角,每条末端各挑两根短枝。
     * 画法照抄 ThermalFx —— 圆点当雪花太敷衍,六角是雪花唯一认得出来的特征。
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
