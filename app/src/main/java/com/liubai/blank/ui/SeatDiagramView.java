package com.liubai.blank.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;

import com.liubai.blank.R;
import com.liubai.blank.mode.ModeManager;
import com.liubai.blank.privileged.CarCtrl;

/**
 * 座椅方向调节:左边选部件,中间一组共用的十字方向键,右边座椅图 + 靠背两个键。
 *
 * <h3>为什么是这个排法</h3>
 *
 * 照原厂「主驾位置」来的。早先留白是**十个独立按键围一圈**(座椅↑↓、座垫↑↓、
 * 座椅前后、靠背前后),每个键自带一行标签 —— 一屏十个圆点加十行字,
 * 密得看不出主次。原厂的做法是**先选调谁,再用同一组方向键调**,一次只有四个
 * 方向键在场,标签也省了。
 *
 * 座垫**只有升降、没有前后**,选中它时左右两个键连同热区一起撤掉,只剩上下一对;
 * 靠背那两个键和引导弧也一并收起(靠背归「座椅」那一档),同时把座椅图上坐垫那块
 * 提亮 —— 一屏只剩当前这档能动的东西。
 *
 * <h3>交互</h3>
 *
 * 照原厂 {@code CustomAdjustImageView}:**按住期间每 300ms 循环发一次方向值**,
 * 松开发 0(停止)。原厂之所以循环发,是怕 CAN 侧一次值被丢帧 —— 方向调节是连续
 * 动作,丢了就卡住不动,循环发能兜住。
 *
 * 座椅图只是视觉示意,不接触摸。**头枕和腿托这车都没有**(腿托 2026-08-22 车主
 * 确认没这个硬件),不出现在任何键里 —— `BYDAutoSettingDevice` 里有
 * `SET_LF_LEG_REST_SIGNAL_SET` 这个字段不代表本车装了。
 */
public final class SeatDiagramView extends View {

    private static final long REPEAT_MS = 300;

    /**
     * 头枕顶在素材里的横向位置,占素材宽。**换素材要重新量**(取 alpha 最上面那行的中点)
     * —— 靠背那一组全挂在这个点上。
     */
    private static final float HEAD_X = 0.891f;

    /**
     * 靠背那一组(两个键 + 引导弧)的锚点:**原点是头枕顶**,单位是座椅图的高。
     *
     * 数值是从原厂「主驾位置」截图上量出来的,不是调出来的:沿弧取 77 个像素做圆拟合,
     * 最大残差 0.07px —— 原厂那条是**正圆弧**,圆心正落在靠背转轴(坐垫后端)上,
     * 半径 = 转轴到头枕顶的 0.90 倍。所以它必然从靠背上部穿过去,这就是"擦过头枕"的由来。
     *
     * **两个键的位置也归这套常量管。** 早先键位是另外写死的归一化坐标,和座椅图
     * (运行时按素材宽高比算 bounds)各算各的 —— 弧压根不知道头枕在哪个像素上,
     * 只能靠一次次凑,凑出来的还随素材宽高比漂。同一行改错过两次(① 弧末端悬空成断线
     * ② 弧太平),都是这个根因。
     *
     * 原点取头枕顶而不是座椅图左上角:留白这张素材的头枕比原厂那张更靠画布右边
     * (0.89 vs 0.82),按 bounds 对齐的话后仰键会顶到头枕底下,弧右半截几乎露不出来。
     * 锚在头枕顶上,"半径 = 到头枕顶的 0.90 倍"这条关系才跟着素材走。
     */
    private static final float K_FWD_X = -0.372f, K_FWD_Y = 0.042f;
    private static final float K_REV_X = 0.275f, K_REV_Y = 0.438f;
    private static final float ARC_CX = -0.279f, ARC_CY = 0.671f, ARC_R = 0.653f;

    /**
     * 弧的两端离圆键中心留多远,单位是键半径 —— 原厂量的:起点 1.22、末端 1.37。
     *
     * 留成**距离**而不是固定角度:留白的键比原厂大一号(键是触摸目标,没跟着座椅缩),
     * 写死角度的话弧头会插进键里。
     */
    private static final float ARC_GAP_FWD = 1.22f, ARC_GAP_REV = 1.37f;

    /** 两端渐隐各占扫掠角的比例。原厂弧不是等浓度的,两头都是淡上来的。 */
    private static final float ARC_FADE = 0.2f;

    /**
     * 坐垫和靠背的分界(**素材归一化**,x 占素材宽、y 占素材高):从 (0.60,0.72)
     * 斜到 (0.95,1.00),线的左下方算坐垫。
     *
     * 照素材上那道自然接缝量的。**靠背根部插进坐垫的那块要留给靠背** —— 早先
     * 切在 (0.50,0.66)-(0.80,1.00),斜率反了,坐垫后半截被削掉一个三角,看着像缺了一角。
     */
    private static final float CUT_X0 = 0.60f, CUT_Y = 0.72f, CUT_X1 = 0.95f;

    /** 坐垫高亮的浓度。再淡看不出来,再浓就盖过座椅本身的形体了。 */
    private static final int HINT_ALPHA = 0x4D000000;

    /** 方向:和原厂的值域对齐,1/2 是一对相反方向。 */
    private static final int UP = 1, DOWN = 2, LEFT = 3, RIGHT = 4;
    /** 靠背单独两个键,不走十字键。 */
    private static final int BACK_FWD = 5, BACK_REV = 6;

    /** 调节哪个部件。座垫只有升降。 */
    private enum Part {
        SEAT("座椅", CarCtrl.F_SEAT_VERT, CarCtrl.F_SEAT_HORIZ),
        CUSHION("座垫", CarCtrl.F_CUSHION_VERT, null);

        final String label;
        /** 升降用的字段。 */
        final String vert;
        /** 前后用的字段;null = 这个部件没有前后。 */
        final String horiz;

        Part(String label, String vert, String horiz) {
            this.label = label; this.vert = vert; this.horiz = horiz;
        }
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 画 chevron 和准心复用,每次 reset —— 别在 onDraw 里 new。 */
    private final Path path = new Path();
    /**
     * 座椅示意图。
     *
     * **这里曾经是 Canvas 自绘,试了三版都不行,别再往回走**:圆角矩形拼的像箱子、
     * 一体化 fill 像只靴子、贝塞尔调出腰托曲线也只是"没那么难看"。
     * 座椅这种有机形体靠几十个手写坐标凑不出美术质量。
     *
     * 素材出图时四周带了两百多像素透明边,已经裁掉 —— 不裁的话按整张图算 bounds,
     * 座椅只占中间一小块,显示出来又小又偏。**换图时记得先裁透明边。**
     */
    private final Drawable seatImg;
    private final Handler loop = new Handler(Looper.getMainLooper());

    /** 弧的渐隐 shader。尺寸或配色变了才重建 —— 别每帧 new。 */
    private Shader arcShader;
    private float shaderW, shaderH;
    private int shaderColor;

    /** 坐垫高亮那层染色。同上,换模式才重建。 */
    private ColorFilter hintFilter;
    private int hintColor;

    private Part part = Part.SEAT;
    /** 正按着哪个键。0 = 没有。 */
    private int activeKey;

    private final Runnable send = new Runnable() {
        @Override public void run() {
            String f = fieldOf(activeKey);
            if (f == null) return;
            CarCtrl.seatAdjust(f, valueOf(activeKey), (ok, v) -> { });
            loop.postDelayed(this, REPEAT_MS);
        }
    };

    public SeatDiagramView(Context ctx) {
        super(ctx);
        seatImg = ctx.getDrawable(R.drawable.seat_side);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = Math.round(w * 0.52f);
        setMeasuredDimension(w, h);
    }

    // ---- 布局:全部按归一化坐标算,x 用宽、y 用高 ----

    /** 十字键中心。 */
    private float crossX(float w) { return w * 0.385f; }
    private float crossY(float h) { return h * 0.52f; }
    /** 十字的臂长。**用宽度算,x/y 同一个像素值** —— 否则十字会被压扁。 */
    private float arm(float w)    { return w * 0.105f; }
    /** 每个圆键的半径。 */
    private float keyR(float w)   { return w * 0.042f; }

    // ---- 座椅坐标系:靠背那两个键和引导弧都从这儿推,别再另写一套 ----

    /** 座椅图的高。0.62 是撞出来的:再大就要出弹窗了。 */
    private float seatH(float h) { return h * 0.62f; }

    /**
     * 素材宽高比。素材缺失时按出图时的 688×777 兜底 —— 键位现在挂在座椅坐标系上,
     * 这里返回 0 会把整组键叠到一个点上。
     */
    private float seatAspect() {
        return seatImg == null ? 688f / 777f
                : (float) seatImg.getIntrinsicWidth() / seatImg.getIntrinsicHeight();
    }

    /**
     * 横向位置。0.700 不是随手挪的:后仰键锚到头枕顶之后往右去了 0.04w,再按老的
     * 0.740 摆,键就贴到 view 右沿上了(只剩 18px)。座椅整体让 4%,右边距回到 8%,
     * 和原厂一致;左边离十字键还有 25px,不打架。
     */
    private float seatLeft(float w, float h) { return w * 0.700f - seatH(h) * seatAspect() / 2f; }
    private float seatTop(float h)           { return h * 0.585f - seatH(h) / 2f; }

    /**
     * 锚点(原点 = 头枕顶) → 像素。x/y **同用座椅高**当单位,否则锚点会被宽高比拉歪;
     * 只有找头枕顶那一步用座椅宽,因为 {@link #HEAD_X} 是按素材宽量的。
     */
    private float seatX(float w, float h, float nx) {
        return seatLeft(w, h) + HEAD_X * seatH(h) * seatAspect() + nx * seatH(h);
    }
    private float seatY(float h, float ny) { return seatTop(h) + ny * seatH(h); }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        // 靠背归「座椅」这一档:选了座垫就只调座垫,那两个键连同引导弧一起撤
        boolean back = part == Part.SEAT;
        // 弧在座椅**下面**:它是靠背转过去的轨迹,穿过靠背上部那一段该被座椅挡住,
        // 露在外面就成了一根横在座椅上的线
        if (back) drawBackArc(c, w, h);
        drawSeat(c, w, h);
        drawCushionHint(c, w, h);
        drawParts(c, w, h);
        drawCross(c, w, h);
        if (back) drawBackKeys(c, w, h);
    }

    /**
     * 选中座垫时把坐垫那块提亮 —— 光看左边那个 radio 认不出正在调座椅的哪儿。
     *
     * 做法是**按坐垫的范围把座椅图再画一遍、叠一层 accent**,而不是另描一个形状:
     * 染色被素材自己的 alpha 兜住,不会溢到背景上,换素材也不用重描轮廓。
     * 依赖 {@link #drawSeat} 刚设好的 bounds,所以必须紧跟在它后面画。
     */
    private void drawCushionHint(Canvas c, float w, float h) {
        if (seatImg == null || part != Part.CUSHION) return;
        float ih = seatH(h), iw = ih * seatAspect();
        float l = seatLeft(w, h), t = seatTop(h);

        int accent = ModeManager.get().accent();
        if (hintFilter == null || hintColor != accent) {
            hintFilter = new PorterDuffColorFilter(
                    (accent & 0x00FFFFFF) | HINT_ALPHA, PorterDuff.Mode.SRC_ATOP);
            hintColor = accent;
        }

        c.save();
        path.reset();
        path.moveTo(l, t + ih * CUT_Y);
        path.lineTo(l + iw * CUT_X0, t + ih * CUT_Y);
        path.lineTo(l + iw * CUT_X1, t + ih);
        path.lineTo(l, t + ih);
        path.close();
        c.clipPath(path);
        seatImg.setColorFilter(hintFilter);
        seatImg.draw(c);
        seatImg.setColorFilter(null);   // drawable 是共用的,不清下一帧整张都染
        c.restore();
    }

    /** 右侧座椅图。**不镜像**:原厂就是靠背在右、人朝左坐,素材照那个来的。 */
    private void drawSeat(Canvas c, float w, float h) {
        if (seatImg == null) return;
        float ih = seatH(h), iw = ih * seatAspect();
        float l = seatLeft(w, h), t = seatTop(h);
        seatImg.setBounds(Math.round(l), Math.round(t),
                Math.round(l + iw), Math.round(t + ih));
        seatImg.draw(c);
    }

    /** 左侧部件选择:一个圆点 + 名字,选中的用强调色。 */
    private void drawParts(Canvas c, float w, float h) {
        Part[] all = Part.values();
        float x = w * 0.055f;
        float r = w * 0.016f;
        for (int i = 0; i < all.length; i++) {
            float y = partY(h, i);
            boolean on = all[i] == part;

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, r * 0.28f));
            paint.setColor(on ? ModeManager.get().accent() : ModeManager.get().muted());
            c.drawCircle(x, y, r, paint);
            if (on) {
                paint.setStyle(Paint.Style.FILL);
                c.drawCircle(x, y, r * 0.52f, paint);
            }

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(on ? ModeManager.get().accent() : ModeManager.get().ink());
            paint.setTextSize(h * 0.075f);
            paint.setTextAlign(Paint.Align.LEFT);
            c.drawText(all[i].label, x + r * 2.2f, y + paint.getTextSize() * 0.36f, paint);
        }
    }

    /**
     * 第 i 项的纵向位置:整组以十字键那条中线为准居中排,间距 0.22h 固定。
     * 按项数算而不是写死每一项的 y —— 增删部件时不用回来重新分配位置。
     * 间距和 {@link #hitPart} 的 ±0.11h 热区是配套的,改一个要改另一个。
     */
    private float partY(float h, int i) {
        return h * (0.50f + (i - (Part.values().length - 1) / 2f) * 0.22f);
    }

    /**
     * 中间的方向键。**座垫没有前后,左右两个就不画**。
     *
     * 早先是画成禁用态留在原位,理由是"键位固定住手指才有肌肉记忆"——
     * 站不住:座垫这一档四个键里有两个是死的,按了没反应比键位挪动更让人疑惑。
     * 上下两个键的位置不跟着变,该有的连续性已经在了。
     */
    private void drawCross(Canvas c, float w, float h) {
        float cx = crossX(w), cy = crossY(h), a = arm(w);

        key(c, w, UP,   cx, cy - a);
        key(c, w, DOWN, cx, cy + a);
        if (part.horiz != null) {
            key(c, w, LEFT,  cx - a, cy);
            key(c, w, RIGHT, cx + a, cy);

            // 中心那个准心:原厂也有,它把四个键框成一组,少了就是四个散点。
            // **只有四个键时才画** —— 座垫那档只剩上下一对,中间夹个 + 反倒像十字缺了两条腿
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1.5f, w * 0.004f));
            paint.setColor(ModeManager.get().faint());
            float t = a * 0.22f;
            c.drawLine(cx - t, cy, cx + t, cy, paint);
            c.drawLine(cx, cy - t, cx, cy + t, paint);
        }
    }

    /**
     * 靠背两个键。**不是并排在顶部**,照原厂:前倾键在靠背左上方、后仰键在座椅右侧腰部,
     * 各自待在靠背倒过去的那个方向上。
     */
    private void drawBackKeys(Canvas c, float w, float h) {
        key(c, w, BACK_FWD, seatX(w, h, K_FWD_X), seatY(h, K_FWD_Y));
        key(c, w, BACK_REV, seatX(w, h, K_REV_X), seatY(h, K_REV_Y));
    }

    /**
     * 引导弧:**靠背转动的轨迹**,不是一条把键和座椅连起来的牵引线。
     *
     * 从前倾键旁边起,擦着靠背上部绕过去(中间那段被座椅挡掉),再一路下探到后仰键
     * 左上角 —— 那正是靠背往后倒时顶端扫过的路径。原厂画这条线是这个意思,不是装饰。
     *
     * 圆心和半径全从 {@link #ARC_CX} 那组常量推,起止角再由两个键的实际位置反算,
     * 所以键挪了、素材换了,弧都跟着走,不用重调。
     */
    private void drawBackArc(Canvas c, float w, float h) {
        float cx = seatX(w, h, ARC_CX), cy = seatY(h, ARC_CY), r = ARC_R * seatH(h);
        float kr = keyR(w);

        float fx = seatX(w, h, K_FWD_X), fy = seatY(h, K_FWD_Y);
        float rx = seatX(w, h, K_REV_X), ry = seatY(h, K_REV_Y);
        float a0 = deg(cx, cy, fx, fy) + gapAngle(r, dist(cx, cy, fx, fy), ARC_GAP_FWD * kr);
        float a1 = deg(cx, cy, rx, ry) - gapAngle(r, dist(cx, cy, rx, ry), ARC_GAP_REV * kr);
        if (!(a1 > a0)) return;   // 键位摆成这样弧无处可画;NaN 也走这条

        int color = ModeManager.get().faint();
        if (arcShader == null || shaderW != w || shaderH != h || shaderColor != color) {
            arcShader = fade(cx, cy, a0, a1, color);
            shaderW = w; shaderH = h; shaderColor = color;
        }

        paint.setStyle(Paint.Style.STROKE);
        // 原厂那条按比例只有 0.0015w,但它是深底白线(实测和背景差 90 阶);留白浅底上
        // faint 只有 40% 浓度,再照 0.002w 画出来实测才差 30~50 —— 看不清。
        // 颜色是语义档次不能动,只能靠宽度补回来。
        paint.setStrokeWidth(Math.max(2f, w * 0.003f));
        paint.setStrokeCap(Paint.Cap.BUTT);
        // **挂了 shader 就得把 paint 的 alpha 打满**:两者是相乘的关系,faint 本身
        // 只有 40% 浓度,再乘 shader 里那份 40% 就剩 16% —— 实测浓度只有该有的
        // 五分之二,线淡到看不见。alpha 交给 shader 一家管。
        paint.setColor(arcShader == null ? color : (color | 0xFF000000));
        paint.setShader(arcShader);
        c.drawArc(cx - r, cy - r, cx + r, cy + r, a0, a1 - a0, false, paint);
        paint.setShader(null);   // paint 是全 view 共用的,不清后面每一笔都跟着渐变
    }

    /**
     * 两端渐隐。SweepGradient 的圆心必须和弧同一个,角度才对得上;它的 position
     * 是从 3 点钟方向顺时针的 [0,1)。
     *
     * 弧跨过 0° 时两个 position 会掉个个儿(SweepGradient 要求递增),那种摆法下
     * 不渐变、照常画实线,总比崩了强。
     */
    private static Shader fade(float cx, float cy, float a0, float a1, int color) {
        float p0 = norm(a0), p1 = norm(a1);
        if (p1 <= p0) return null;
        int off = color & 0x00FFFFFF;
        float d = (p1 - p0) * ARC_FADE;
        return new SweepGradient(cx, cy,
                new int[]{ off, off, color, color, off, off },
                new float[]{ 0f, p0, p0 + d, p1 - d, p1, 1f });
    }

    private static float norm(float deg) { return (((deg % 360f) + 360f) % 360f) / 360f; }

    private static float deg(float cx, float cy, float x, float y) {
        return (float) Math.toDegrees(Math.atan2(y - cy, x - cx));
    }

    private static float dist(float cx, float cy, float x, float y) {
        return (float) Math.hypot(x - cx, y - cy);
    }

    /**
     * 弧上离某个键心 {@code gap} 远的那个点,相对圆心张开多少度(余弦定理)。
     * 键大小一变缝隙跟着变,不用回来改角度。
     */
    private static float gapAngle(float r, float dk, float gap) {
        float cos = (r * r + dk * dk - gap * gap) / (2f * r * dk);
        cos = Math.max(-1f, Math.min(1f, cos));
        return (float) Math.toDegrees(Math.acos(cos));
    }

    /**
     * 一个圆键:圆底 + chevron。
     *
     * chevron 的线宽**不能按比例无限缩** —— 压到 1.5px 以下就糊成一团看不见了
     * (图标产线踩过,见《chevron 小尺寸不可见》)。
     */
    private void key(Canvas c, float w, int dir, float cx, float cy) {
        float r = keyR(w);
        boolean on = activeKey == dir;

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(on ? ModeManager.get().accent() : ModeManager.get().faint());
        c.drawCircle(cx, cy, r, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2.5f, r * 0.17f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(on ? ModeManager.get().bg() : ModeManager.get().ink());

        float a = r * 0.40f;
        path.reset();
        switch (dir) {
            case UP:
                path.moveTo(cx - a, cy + a * 0.45f);
                path.lineTo(cx, cy - a * 0.45f);
                path.lineTo(cx + a, cy + a * 0.45f);
                break;
            case DOWN:
            case BACK_REV:
                // 靠背后仰也是**向下**的箭头,不是向右 —— 靠背往后倒的时候,
                // 顶端划的是一道向下的弧,原厂那个键就是照这个方向画的
                path.moveTo(cx - a, cy - a * 0.45f);
                path.lineTo(cx, cy + a * 0.45f);
                path.lineTo(cx + a, cy - a * 0.45f);
                break;
            case LEFT:
            case BACK_FWD:
                path.moveTo(cx + a * 0.45f, cy - a);
                path.lineTo(cx - a * 0.45f, cy);
                path.lineTo(cx + a * 0.45f, cy + a);
                break;
            default:
                path.moveTo(cx - a * 0.45f, cy - a);
                path.lineTo(cx + a * 0.45f, cy);
                path.lineTo(cx - a * 0.45f, cy + a);
                break;
        }
        c.drawPath(path, paint);
    }

    // ---- 键 → 车控字段 ----

    /** 这个键对应哪个字段。null = 当前部件没有这个方向,按了也不发。 */
    private String fieldOf(int key) {
        switch (key) {
            case UP: case DOWN:            return part.vert;
            case LEFT: case RIGHT:         return part.horiz;
            case BACK_FWD: case BACK_REV:  return CarCtrl.F_SEAT_BACK;
            default:                       return null;
        }
    }

    /** 方向值。1 = 上/前,2 = 下/后 —— 和原厂值域一致。 */
    private int valueOf(int key) {
        switch (key) {
            case UP: case LEFT: case BACK_FWD: return 1;
            default:                           return 2;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                // 先判部件选择:它是点一下就切,不参与按住循环
                Part p = hitPart(x, y);
                if (p != null) {
                    part = p;
                    invalidate();
                    return true;
                }
                int k = hitKey(x, y);
                if (k != 0 && fieldOf(k) != null) {
                    activeKey = k;
                    loop.removeCallbacks(send);
                    loop.post(send);
                    invalidate();
                    return true;
                }
                return false;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                String f = fieldOf(activeKey);
                if (f != null) CarCtrl.seatAdjust(f, 0, (ok, v) -> { });
                loop.removeCallbacks(send);
                activeKey = 0;
                invalidate();
                return true;
            }
        }
        return super.onTouchEvent(e);
    }

    /** 点在哪个部件项上。热区比圆点大得多,整行都算。 */
    private Part hitPart(float x, float y) {
        float w = getWidth(), h = getHeight();
        if (x > w * 0.26f) return null;
        Part[] all = Part.values();
        for (int i = 0; i < all.length; i++) {
            if (Math.abs(y - partY(h, i)) <= h * 0.11f) return all[i];
        }
        return null;
    }

    /** 点在哪个圆键上。热区放到半径的 1.6 倍,车上手指点得中。 */
    private int hitKey(float x, float y) {
        float w = getWidth(), h = getHeight();
        float cx = crossX(w), cy = crossY(h), a = arm(w);
        float hitR = keyR(w) * 1.6f;
        if (near(x, y, cx, cy - a, hitR)) return UP;
        if (near(x, y, cx, cy + a, hitR)) return DOWN;
        // 没画出来的键不留热区 —— 座垫那一档左右是空的
        if (part.horiz != null) {
            if (near(x, y, cx - a, cy, hitR)) return LEFT;
            if (near(x, y, cx + a, cy, hitR)) return RIGHT;
        }
        if (part == Part.SEAT) {   // 座垫档这两个键没画,热区一并撤
            if (near(x, y, seatX(w, h, K_FWD_X), seatY(h, K_FWD_Y), hitR)) return BACK_FWD;
            if (near(x, y, seatX(w, h, K_REV_X), seatY(h, K_REV_Y), hitR)) return BACK_REV;
        }
        return 0;
    }

    private static boolean near(float x, float y, float cx, float cy, float r) {
        float dx = x - cx, dy = y - cy;
        return dx * dx + dy * dy <= r * r;
    }
}
