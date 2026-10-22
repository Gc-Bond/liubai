package com.liubai.blank.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.widget.LinearLayout;

import com.liubai.blank.mode.Reskinnable;
import com.liubai.blank.mode.ModeManager;
import com.liubai.blank.util.Launchers;

/**
 * 调节气泡的外壳:斜向渐变的面 + 细描边 + 底部指向按键的尾巴 + 两层投影。
 *
 * 为什么不拿 GradientDrawable 当背景:尾巴是轮廓的一部分,描边要连着绕过尾尖再回来,
 * drawable 拼不出这个形。整条轮廓用一个 Path 画完,填充、描边、投影都跟着同一条线走。
 *
 * 渐变的理由同 {@link HvacPanel#sheetBg()} —— 纯平一块 surface 是廉价感的主要来源,
 * 给一点纵深才能把"面"变成"体"。
 *
 * 投影让卡片从背景里**浮起来**:气泡悬在正在跑的 app 内容上面,没有影子的话它和
 * 背景是同一个平面上的两块颜色,贴得再近也分不出谁在上。两层影分工不同 ——
 * 近影贴着轮廓定形,远影往下沉给出悬浮的距离感,单层要么糊要么薄。
 * 影子是黑色半透明,不参与模式换色(亮暗模式下的物理投影本来就都是暗的)。
 *
 * 尾巴让气泡和它属于的那颗 Dock 键连上:没有尾巴的话它只是一块浮在上面的板,
 * 和按下去的那颗键没有任何视觉关系。
 */
class BubbleFrame extends LinearLayout implements Reskinnable {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private final float radius;
    private final float tailH;
    private final float tailW;
    /**
     * 面四周给投影留的透明余量。投影画在轮廓**之外**,窗口不留这圈空白的话,
     * 影子在视图边界上被生生裁平 —— 看着像卡片贴在一块发黑的矩形上。
     * 底边的余量在尾尖之下,定位时由 {@link #tailHeight} 一并报给 place 消化。
     */
    private final int insetX, insetTop, insetBottom;

    private Bitmap shadow;

    BubbleFrame(Context c) {
        super(c);
        setOrientation(VERTICAL);
        setWillNotDraw(false);          // ViewGroup 默认跳过 onDraw

        radius = Launchers.dp(c, 22);
        // 尾尖够长才点得到按键:气泡的面底边对齐 dockSpace 那条线(76dp),按键上沿在
        // 56dp 处,中间隔着 20dp —— 8dp 的尾巴悬在半空,看着像没指着谁
        tailH = Launchers.dp(c, 12);
        tailW = Launchers.dp(c, 14);
        insetX = Launchers.dp(c, 16);
        insetTop = Launchers.dp(c, 12);
        insetBottom = Launchers.dp(c, 14);

        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Launchers.dp(c, 1));
        reskin();

        int padX = Launchers.dp(c, 16) + insetX;
        // 底部内边距要额外让出尾巴那一截和投影余量,不然内容会压到尾巴上
        setPadding(padX, Launchers.dp(c, 12) + insetTop, padX,
                Launchers.dp(c, 12) + (int) tailH + insetBottom);
    }

    /**
     * 面的下沿到窗口底边的距离(尾巴 + 投影余量),给定位用:
     * 气泡整体要下移这么多,面的下沿才落在 dockSpace 那条线上、尾尖正好点住按键上沿。
     */
    float tailHeight() { return tailH + insetBottom; }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w <= 0 || h <= 0) return;
        buildPath(w, h);
        buildFill(w, h);
        buildShadow(w, h);
    }

    /**
     * 重新取色。**这个 view 是本次 Reskinnable 的头号病人**:气泡是复用实例,尺寸不变就
     * 不再走 onSizeChanged,渐变面因此能一直停在用户最早那套配色上 —— 表现是 Dock 已经
     * 换了底色、贴在它上面的气泡还是旧色,而这两样是紧挨着的,割裂看得一清二楚。
     */
    @Override public void reskin() {
        stroke.setColor(ModeManager.get().line());
        buildFill(getWidth(), getHeight());   // 构造期 w/h 还是 0,那时靠 onSizeChanged 补
        invalidate();
    }

    /** 顶浅底深的斜向渐变,和空调面板那张 sheet 是同一套。 */
    private void buildFill(int w, int h) {
        if (w <= 0 || h <= 0) return;
        fill.setShader(new LinearGradient(0, 0, w, h,
                ModeManager.get().tile2(), ModeManager.get().surface(),
                Shader.TileMode.CLAMP));
    }

    /**
     * 一条连续轮廓:圆角矩形绕到底边中间时拐出尾巴,再拐回来。
     * 描边因此是不断的 —— 分两个 Path 画的话,尾巴根部会留下两道接缝。
     * 轮廓只依赖尺寸,在 onSizeChanged 里建一次,不在 onDraw 里每帧重算。
     */
    private void buildPath(int w, int h) {
        float l = insetX, r = w - insetX, top = insetTop;
        float bottom = h - tailH - insetBottom;   // 面的下沿,尾巴从这里凸出去
        float tip = h - insetBottom;              // 尾尖
        float cx = w / 2f;
        path.reset();
        path.moveTo(l + radius, top);
        path.lineTo(r - radius, top);
        path.quadTo(r, top, r, top + radius);
        path.lineTo(r, bottom - radius);
        path.quadTo(r, bottom, r - radius, bottom);
        path.lineTo(cx + tailW / 2f, bottom);
        path.quadTo(cx, tip, cx - tailW / 2f, bottom);   // 尾尖用二次曲线收圆,不是尖刺
        path.lineTo(l + radius, bottom);
        path.quadTo(l, bottom, l, bottom - radius);
        path.lineTo(l, top + radius);
        path.quadTo(l, top, l + radius, top);
        path.close();
    }

    /**
     * 投影预渲染成一张位图,onDraw 里只贴图。
     *
     * 不用 setShadowLayer:路径投影只在软件层生效,那得把整个气泡切成软件渲染 ——
     * 里头的档位柱在滑动中每帧都在跑弹簧,代价全落在主线程上。位图只在尺寸变化时
     * 重算一次(内容宽度一变才会走到这儿),每帧照旧走硬件。
     */
    private void buildShadow(int w, int h) {
        shadow = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas sc = new Canvas(shadow);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        // 远影:大模糊往下沉,给"浮着"的距离感
        p.setColor(0x40000000);
        p.setMaskFilter(new BlurMaskFilter(Launchers.dp(getContext(), 18),
                BlurMaskFilter.Blur.NORMAL));
        sc.save();
        sc.translate(0, Launchers.dp(getContext(), 8));
        sc.drawPath(path, p);
        sc.restore();
        // 近影:小模糊贴着轮廓,把边定住
        p.setColor(0x59000000);
        p.setMaskFilter(new BlurMaskFilter(Launchers.dp(getContext(), 8),
                BlurMaskFilter.Blur.NORMAL));
        sc.save();
        sc.translate(0, Launchers.dp(getContext(), 2));
        sc.drawPath(path, p);
        sc.restore();
    }

    @Override protected void onDraw(Canvas canvas) {
        if (shadow != null) canvas.drawBitmap(shadow, 0f, 0f, null);
        // 面板不透明度在这儿叠,不在 onSizeChanged 里 —— 气泡尺寸不变就不会再走那条路,
        // 而档位是随时可能改的。alpha 和 shader 是相乘关系,渐变不受影响。
        // 读的是 ModeManager 的内存字段,每帧读不心疼(见 panelOpacity 那个字段的说明)。
        int a = ModeManager.get().panelAlpha();
        fill.setAlpha(a);
        stroke.setAlpha(a);
        canvas.drawPath(path, fill);
        canvas.drawPath(path, stroke);
    }
}
