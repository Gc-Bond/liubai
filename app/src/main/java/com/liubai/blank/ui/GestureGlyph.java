package com.liubai.blank.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import com.liubai.blank.mode.ModeManager;
import com.liubai.blank.mode.Reskinnable;

/**
 * 手势演示:一块微缩的留白屏幕,手指落在该按的位置做动作,然后场景里出结果。
 *
 * <h3>底是真桌面</h3>
 * 桌面和 Dock 那些技巧,底用 {@link DeskSnapshot} 抓来的**真桌面位图**:用户自己的壁纸、
 * 自己排的卡片和 Dock,手指落在真实的键上(坐标也是从真 view 量的)。线框微缩屏
 * 只留给抓不到的场景(应用抽屉、设置面板)和抓失败时兜底 —— 第一版全线框被评"有点 low"
 * (2026-09-12)。
 *
 * <h3>演示比文字多的两样</h3>
 * **在哪儿按**、**按完发生什么**。每条技巧对应一个场景({@link Scene})、一个落点
 * ({@code target})和一个结果({@link Effect}:面板升起、卡片长出 ×、温度 26→27……)。
 * 结果画在真桌面**上面**:面板、菜单、任务卡这些本来就是盖上去的;换壁纸、收卡片这类
 * 改底的,用一层色块示意。
 *
 * <h3>点按和长按怎么分</h3>
 * 点按:落下、荡一圈、**抬起**,手指只在屏上停 1/5 个周期,结果紧跟着出。
 * 长按:手指**一直在**,圆点外一道弧慢慢走满一圈(Android 长按的惯用示意),走满才出结果。
 *
 * <h3>动画只在看得见的时候跑</h3>
 * {@link #play} 打开后每帧先问 {@link #isShown()}:技巧页关了、切走了、面板收起,
 * 循环自己停;再露出来时 {@link #onVisibilityChanged} 把它重新踢起来。桌面上**不放**
 * 它的动态版 —— 空闲动画烧 CPU 真车实测过(memory dock-idle-anim-cpu-cost)。
 */
public class GestureGlyph extends View implements Reskinnable {

    /** 手指动作。HOLD_SLIDE 是「按住再滑」(Dock 应用位换 app 那套)。 */
    public enum Kind { SWIPE, PINCH_IN, PINCH_OUT, HOLD, TAP, DRAG, HOLD_SLIDE }

    /** 微缩屏幕上画的是哪一页。 */
    public enum Scene { DESK, PIP, DRAWER, PANEL }

    /** 手势做完之后场景里出什么。 */
    public enum Effect {
        NONE, WALLPAPER, CARDS_HIDE, EDIT, PIP_HIDE, TEMP, FAN, SHEET, LIST, GRID,
        KEY_ON, DOCK_EDIT, RECENTS, SLOT, MENU, REMOVE
    }

    // ---- 落点编码 ----
    /** 桌面空白处。 */
    public static final int T_BLANK = -1;
    /** 画中画两卡之间的握把。 */
    public static final int T_HANDLE = 20;
    /** 第 i 张桌面卡 / 抽屉里第 i 个图标 / 面板里第 i 张壁纸(按场景解释)。 */
    public static int item(int i) { return i; }
    /** Dock 第 i 个键:0 返回 1 主页 2 全部应用 3 扇叶 4 应用位 5 温度(双宽) 6 设置。 */
    public static int key(int i) { return 10 + i; }
    /** 温度键右侧那个箭头。 */
    public static final int T_TEMP_RIGHT = 17;

    private static final long CYCLE_MS = 2800;
    // 时间轴(周期的比例):手势窗口、结果窗口
    private static final float G0 = .08f, G1 = .52f, E1 = .82f;

    private Kind kind = Kind.TAP;
    private int fingers = 1;
    private float dx, dy;
    private Scene scene = Scene.DESK;
    private int target = T_BLANK;
    private Effect effect = Effect.NONE;
    private DeskSnapshot snap;

    private boolean playing, running;
    private long t0;

    private final Paint pFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pTrail = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final android.graphics.Rect src = new android.graphics.Rect();
    private final RectF S = new RectF();      // 微缩屏幕
    private final RectF r1 = new RectF(), r2 = new RectF();
    private final RectF[] cards = { new RectF(), new RectF(), new RectF(), new RectF() };
    private final RectF[] keys = new RectF[7];
    private final RectF[] icons = new RectF[10];
    private final RectF[] thumbs = new RectF[3];

    private int cWall, cCard, cLine, cGlyph, cAccent, cAccentSoft, cInk;
    private float u;    // 单位:view 宽的百分之一

    public GestureGlyph(Context ctx) {
        super(ctx);
        pLine.setStyle(Paint.Style.STROKE);
        pRing.setStyle(Paint.Style.STROKE);
        pRing.setStrokeCap(Paint.Cap.ROUND);
        pTrail.setStyle(Paint.Style.STROKE);
        pTrail.setStrokeCap(Paint.Cap.ROUND);
        pText.setTextAlign(Paint.Align.CENTER);
        pText.setFakeBoldText(true);
        for (int i = 0; i < keys.length; i++) keys[i] = new RectF();
        for (int i = 0; i < icons.length; i++) icons[i] = new RectF();
        for (int i = 0; i < thumbs.length; i++) thumbs[i] = new RectF();
        reskin();
    }

    public void set(Tips.Tip t) {
        kind = t.kind;
        fingers = Math.max(1, Math.min(3, t.fingers));
        float len = (float) Math.hypot(t.dx, t.dy);
        dx = len > 0 ? t.dx / len : 0;
        dy = len > 0 ? t.dy / len : 0;
        scene = t.scene;
        target = t.target;
        effect = t.effect;
        t0 = now();
        invalidate();
    }

    /** 真桌面的底。传 null 就退回线框。 */
    public void setBase(DeskSnapshot s) {
        snap = s;
        invalidate();
    }

    /** 开/关循环。开了也得等 isShown() 才真跑。 */
    public void play(boolean on) {
        playing = on;
        kick();
    }

    @Override public void reskin() {
        ModeManager tm = ModeManager.get();
        cWall = tm.tile2();
        cCard = tm.surface();
        cLine = tm.line();
        cGlyph = tm.muted();
        cAccent = tm.accent();
        cAccentSoft = tm.accentSoft();
        cInk = tm.ink();
        invalidate();
    }

    // ---- 循环 ----

    private static long now() { return android.os.SystemClock.uptimeMillis(); }

    private final Runnable frame = new Runnable() {
        @Override public void run() {
            if (!playing || !isShown()) { running = false; return; }
            invalidate();
            postOnAnimation(this);
        }
    };

    private void kick() {
        if (!playing || running || !isShown()) return;
        running = true;
        t0 = now();
        postOnAnimation(frame);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        kick();
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(frame);
        running = false;
        super.onDetachedFromWindow();
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        kick();
    }

    // ---- 小工具 ----

    private static float ease(float t) {
        return t < .5f ? 2 * t * t : 1 - (float) Math.pow(-2 * t + 2, 2) / 2;
    }

    /** 把 [a,b] 里的 t 压到 0..1,区间外夹住。 */
    private static float seg(float t, float a, float b) {
        return Math.max(0f, Math.min(1f, (t - a) / (b - a)));
    }

    /** 给 paint 上色,k 是在颜色自带 alpha 之上再乘的系数。 */
    private static void tint(Paint p, int color, float k) {
        p.setColor(color);
        p.setAlpha(Math.round(Color.alpha(color) * Math.max(0f, Math.min(1f, k))));
    }

    private void box(Canvas c, RectF r, float radius, int fill, int stroke, float k) {
        tint(pFill, fill, k);
        c.drawRoundRect(r, radius, radius, pFill);
        if (stroke != 0) {
            tint(pLine, stroke, k);
            c.drawRoundRect(r, radius, radius, pLine);
        }
    }

    private void hline(Canvas c, float x0, float x1, float y, int color, float k) {
        tint(pLine, color, k);
        c.drawLine(x0, y, x1, y, pLine);
    }

    /** 这一条是不是画在真桌面上。 */
    private boolean real() {
        return snap != null && (scene == Scene.DESK || scene == Scene.PIP);
    }

    // 位图坐标 → view 坐标
    private float mx(float bx) { return S.left + bx / snap.bitmap.getWidth() * S.width(); }
    private float my(float by) { return S.top + by / snap.bitmap.getHeight() * S.height(); }
    private void map(RectF src, RectF out) { out.set(mx(src.left), my(src.top), mx(src.right), my(src.bottom)); }

    // ---- 布局 ----

    private void layout() {
        float w = getWidth(), h = getHeight();
        // 留一圈给手指和荡开的圈;真桌面按位图比例,线框按 16:9
        float aspect = real() ? (float) snap.bitmap.getWidth() / snap.bitmap.getHeight() : 16f / 9;
        float sw = w - 8 * u, sh = sw / aspect;
        if (sh > h - 8 * u) { sh = h - 8 * u; sw = sh * aspect; }
        S.set((w - sw) / 2, (h - sh) / 2, (w + sw) / 2, (h + sh) / 2);
        float W = S.width(), H = S.height();

        // 桌面卡:左上 2×2
        float side = .13f * W, gap = .03f * W, x0 = S.left + .05f * W, y0 = S.top + .09f * H;
        for (int i = 0; i < 4; i++) {
            float cx = x0 + (i % 2) * (side + gap), cy = y0 + (i / 2) * (side + gap);
            cards[i].set(cx, cy, cx + side, cy + side);
        }
        // Dock:底部一条,7 个键,温度键双宽
        float unit = .07f * W, kgap = .028f * W;
        float total = 8 * unit + 6 * kgap;
        float kx = S.left + (W - total) / 2, ky = S.bottom - .14f * H;
        for (int i = 0; i < 7; i++) {
            float kw = i == 5 ? 2 * unit : unit;
            keys[i].set(kx, ky, kx + kw, ky + unit);
            kx += kw + kgap;
        }
        // 抽屉:5×2 图标
        float is = .09f * W, igap = .06f * W, ix0 = S.left + .13f * W, iy0 = S.top + .2f * H;
        for (int i = 0; i < 10; i++) {
            float cx = ix0 + (i % 5) * (is + igap), cy = iy0 + (i / 5) * (is + igap * 1.2f);
            icons[i].set(cx, cy, cx + is, cy + is);
        }
        // 设置面板:左栏 + 三张壁纸缩略图
        float tw = .19f * W, th = .3f * H, tx0 = S.left + .27f * W, ty0 = S.top + .2f * H;
        for (int i = 0; i < 3; i++) {
            float cx = tx0 + i * (tw + .04f * W);
            thumbs[i].set(cx, ty0, cx + tw, ty0 + th);
        }
    }

    /** Dock 条的上沿(view 坐标)。 */
    private float dockTopY() {
        return real() ? my(snap.dockTop) : S.bottom - .2f * S.height();
    }

    /** 真桌面上:技巧点名的那个键。Dock 上没摆这个键就退到 Dock 条正中。 */
    private RectF realKey(int t) {
        RectF r = null;
        if (t == T_TEMP_RIGHT) r = snap.tempRight != null ? snap.tempRight : snap.temp;
        else switch (t - 10) {
            case 1: r = snap.home; break;
            case 2: r = snap.apps; break;
            case 3: r = snap.fan; break;
            case 4: r = snap.slot; break;
            case 5: r = snap.temp; break;
            case 6: r = snap.settings; break;
        }
        if (r == null) return null;
        map(r, r2);
        return r2;
    }

    /** 落点在屏幕上的坐标。 */
    private float[] targetXY() {
        float W = S.width(), H = S.height();
        if (target == T_HANDLE) return new float[]{ S.centerX(), S.top + .45f * H };
        if (real()) {
            if (target == T_TEMP_RIGHT || (target >= 10 && target < 17)) {
                RectF r = realKey(target);
                if (r != null) {
                    float cx = target == T_TEMP_RIGHT && snap.tempRight == null ? r.right - r.width() * .18f : r.centerX();
                    return new float[]{ cx, r.centerY() };
                }
                return new float[]{ S.centerX(), (dockTopY() + S.bottom) / 2 };
            }
            if (target >= 0) {
                RectF r = snap.wifiCard != null ? snap.wifiCard
                        : !snap.cards.isEmpty() ? snap.cards.get(0) : null;
                if (r != null) { map(r, r2); return new float[]{ r2.centerX(), r2.centerY() }; }
            }
            return new float[]{ mx(snap.blankX), my(snap.blankY) };
        }
        if (target == T_TEMP_RIGHT) return new float[]{ keys[5].right - keys[5].width() * .18f, keys[5].centerY() };
        if (target >= 10 && target < 17) return new float[]{ keys[target - 10].centerX(), keys[target - 10].centerY() };
        if (target >= 0) {
            RectF r = scene == Scene.DRAWER ? icons[target] : scene == Scene.PANEL ? thumbs[target] : cards[target];
            return new float[]{ r.centerX(), r.centerY() };
        }
        return new float[]{ S.left + .64f * W, S.top + .42f * H };
    }

    // ---- 画 ----

    @Override protected void onDraw(Canvas c) {
        float w = getWidth();
        if (w <= 0 || getHeight() <= 0) return;
        u = w / 100f;
        layout();
        float t = running ? ((now() - t0) % CYCLE_MS) / (float) CYCLE_MS : .3f;

        // 点按的手指只停一小会儿,结果紧跟着出;别的动作做完才出结果
        float gEnd = kind == Kind.TAP ? G0 + (G1 - G0) * .4f : G1;
        float g = seg(t, G0, gEnd);                   // 手势进度
        float e = ease(seg(t, gEnd, E1));             // 结果进度
        // 周期尾巴上结果和手指淡出,下一圈从头来(底不淡,底是桌面)
        float fade = t > .93f ? 1 - seg(t, .93f, 1f) : 1f;

        pLine.setStrokeWidth(.5f * u);
        pRing.setStrokeWidth(1.1f * u);
        pTrail.setStrokeWidth(2.6f * u);

        int save = c.save();
        c.clipRect(S.left - 4 * u, S.top - 4 * u, S.right + 4 * u, S.bottom + 4 * u);

        if (real()) drawReal(c, e, g, fade);
        else drawScreen(c, e, g, fade);
        drawEffect(c, e, fade);
        drawFinger(c, g, t, fade);

        c.restoreToCount(save);
    }

    /** 真桌面:位图打底,改底的那几种结果用色块示意在上面。 */
    private void drawReal(Canvas c, float e, float g, float k) {
        float W = S.width(), H = S.height();
        float rad = 2.2f * u;
        int save = c.save();
        clip.reset();
        clip.addRoundRect(S, rad, rad, Path.Direction.CW);
        c.clipPath(clip);
        pBmp.setAlpha(255);
        c.drawBitmap(snap.bitmap, null, S, pBmp);

        switch (effect) {
            case WALLPAPER:     // 新壁纸从手指来的那一侧推进来(色块示意)
                if (e > 0) {
                    r1.set(S);
                    if (dx >= 0) r1.right = S.left + e * W; else r1.left = S.right - e * W;
                    tint(pFill, cAccentSoft, k * .75f);
                    c.drawRect(r1, pFill);
                    // 卡片留在原位,只换底:把卡片那几块从位图里再贴一遍
                    for (RectF r : snap.cards) {
                        src.set(Math.round(r.left), Math.round(r.top), Math.round(r.right), Math.round(r.bottom));
                        map(r, r1);
                        c.drawBitmap(snap.bitmap, src, r1, pBmp);
                    }
                }
                break;
            case CARDS_HIDE:    // 卡片淡进壁纸里
                for (RectF r : snap.cards) {
                    map(r, r1);
                    tint(pFill, cWall, k * e * .92f);
                    c.drawRoundRect(r1, 1.2f * u, 1.2f * u, pFill);
                }
                break;
            case EDIT:          // 编辑态:每张卡右上角长出 ×,右侧候选区推进来
                if (e > 0) {
                    for (RectF r : snap.cards) {
                        map(r, r1);
                        tint(pLine, cAccent, k * e);
                        c.drawRoundRect(r1, 1.2f * u, 1.2f * u, pLine);
                        tint(pFill, cAccent, k * e);
                        c.drawCircle(r1.right, r1.top, 1.6f * u, pFill);
                    }
                    r1.set(S.right - .2f * W * e, S.top + .06f * H, S.right - .015f * W, dockTopY() - .04f * H);
                    box(c, r1, 1.6f * u, cCard, cLine, k * e * .95f);
                }
                break;
            case KEY_ON: {      // 键亮起来
                RectF r = realKey(target);
                if (r != null && e > 0) {
                    r1.set(r);
                    r1.inset(-.4f * u, -.4f * u);
                    tint(pFill, cAccent, k * e * .35f);
                    c.drawRoundRect(r1, 1.2f * u, 1.2f * u, pFill);
                    tint(pLine, cAccent, k * e);
                    c.drawRoundRect(r1, 1.2f * u, 1.2f * u, pLine);
                }
                break;
            }
            case DOCK_EDIT:     // 每个键长出 ×
                if (e > 0) {
                    for (RectF r : snap.keys) {
                        map(r, r1);
                        tint(pFill, cAccent, k * e);
                        c.drawCircle(r1.right - .3f * u, r1.top + .3f * u, 1.1f * u, pFill);
                    }
                }
                break;
            case PIP_HIDE:      // 画中画层:两张半透明卡往上收走
                drawPipCards(c, k * .9f, e * (S.height() * .8f), g);
                break;
            default:
                if (scene == Scene.PIP) drawPipCards(c, k * .9f, 0, g);
                break;
        }
        c.restoreToCount(save);
        tint(pLine, cLine, k);
        c.drawRoundRect(S, rad, rad, pLine);

        // 键上点名的那个:平时描一圈,让人先看到"是这个键"
        if (target == T_TEMP_RIGHT || (target >= 10 && target < 17)) {
            RectF r = realKey(target);
            if (r != null && effect != Effect.KEY_ON) {
                r1.set(r);
                r1.inset(-.4f * u, -.4f * u);
                tint(pLine, cAccent, k * .9f);
                c.drawRoundRect(r1, 1.2f * u, 1.2f * u, pLine);
            }
        } else if (target >= 0 && snap.wifiCard != null) {
            map(snap.wifiCard, r1);
            tint(pLine, cAccent, k * .9f);
            c.drawRoundRect(r1, 1.2f * u, 1.2f * u, pLine);
        }
    }

    /** 画中画两张卡(叠在真桌面上示意)。lift 是往上收走的距离,握把跟着手指走。 */
    private void drawPipCards(Canvas c, float k, float lift, float g) {
        float W = S.width(), H = S.height();
        float top = S.top + .1f * H, bottom = dockTopY() - .04f * H;
        float hx = S.centerX();
        if (kind == Kind.DRAG) hx += (ease(g) * 2 - 1) * .12f * W;
        float gap = .012f * W;
        r1.set(S.left + .05f * W, top - lift, hx - gap, bottom - lift);
        box(c, r1, 1.6f * u, cCard, cLine, k);
        r1.set(hx + gap, top - lift, S.right - .05f * W, bottom - lift);
        box(c, r1, 1.6f * u, cCard, cLine, k);
        if (kind == Kind.DRAG) {
            tint(pFill, cAccent, k * .7f);
            r1.set(hx - .4f * u, top + .3f * (bottom - top), hx + .4f * u, bottom - .3f * (bottom - top));
            c.drawRoundRect(r1, .4f * u, .4f * u, pFill);
        }
    }

    /** 线框底:屏幕 + 这一页平时的样子。有些结果直接改这一层(壁纸换掉、卡片藏起来)。 */
    private void drawScreen(Canvas c, float e, float g, float k) {
        float W = S.width(), H = S.height();
        box(c, S, 2.2f * u, cWall, cLine, k);
        if (effect == Effect.WALLPAPER && e > 0) {
            r1.set(S);
            if (dx >= 0) r1.right = S.left + e * W; else r1.left = S.right - e * W;
            int save = c.save();
            c.clipRect(S);
            tint(pFill, cAccentSoft, k);
            c.drawRoundRect(r1, 2.2f * u, 2.2f * u, pFill);
            c.restoreToCount(save);
        }

        switch (scene) {
            case DESK:
            case PIP:
                drawDesk(c, e, g, k);
                break;
            case DRAWER:
                for (int i = 0; i < icons.length; i++) {
                    float kk = k * (effect == Effect.REMOVE && i == target ? 1 - e : 1);
                    box(c, icons[i], 1.6f * u, cCard, i == target ? cAccent : cLine, kk);
                }
                break;
            case PANEL: {
                float rx = S.left + .06f * W;
                for (int i = 0; i < 3; i++) {
                    float y = S.top + (.22f + .14f * i) * H;
                    hline(c, rx, rx + .1f * W, y, cGlyph, k * (i == 0 ? 1 : .5f));
                }
                for (int i = 0; i < thumbs.length; i++) {
                    float kk = k;
                    RectF r = thumbs[i];
                    if (effect == Effect.REMOVE && e > 0) {
                        if (i == target) kk = k * (1 - e);
                        else if (i > target) {
                            r1.set(r);
                            r1.offset(-e * (r.width() + .04f * W), 0);
                            r = r1;
                        }
                    }
                    box(c, r, 1.6f * u, cCard, i == target ? cAccent : cLine, kk);
                }
                r1.set(thumbs[0].left, thumbs[0].bottom + .1f * H, thumbs[2].right, thumbs[0].bottom + .24f * H);
                box(c, r1, 1.6f * u, cCard, cLine, k);
                break;
            }
        }
    }

    private void drawDesk(Canvas c, float e, float g, float k) {
        float W = S.width(), H = S.height();
        float cardK = effect == Effect.CARDS_HIDE ? 1 - e : 1;
        float scale = effect == Effect.EDIT ? 1 - .08f * e : 1;
        for (int i = 0; i < 4; i++) {
            if (scene == Scene.PIP) break;
            RectF r = cards[i];
            r1.set(r);
            r1.inset(r.width() * (1 - scale) / 2, r.height() * (1 - scale) / 2);
            float kk = k * cardK;
            int stroke = (target == i) ? cAccent : cLine;
            box(c, r1, 1.6f * u, cCard, stroke, kk);
            if (effect == Effect.EDIT && e > 0) {
                tint(pFill, cAccent, kk * e);
                c.drawCircle(r1.right, r1.top, 1.6f * u, pFill);
            }
        }
        if (effect == Effect.EDIT && e > 0) {
            r1.set(S.right - .22f * W * e, S.top + .09f * H, S.right - .02f * W, S.bottom - .2f * H);
            box(c, r1, 1.6f * u, cCard, cLine, k * e);
        }
        if (scene == Scene.PIP) {
            float lift = effect == Effect.PIP_HIDE ? e * .8f * H : 0;
            drawPipCards(c, k, lift, g);
        }
        // Dock 条
        r1.set(S.left, S.bottom - .2f * H, S.right, S.bottom);
        tint(pFill, cCard, k);
        c.drawRect(r1, pFill);
        hline(c, S.left, S.right, r1.top, cLine, k);
        float wiggle = effect == Effect.DOCK_EDIT ? e : 0;
        for (int i = 0; i < 7; i++) {
            RectF r = keys[i];
            r1.set(r);
            r1.inset(r.width() * .06f * wiggle, r.height() * .06f * wiggle);
            boolean isTarget = target == key(i) || (i == 5 && target == T_TEMP_RIGHT);
            boolean lit = effect == Effect.KEY_ON && isTarget && e > 0;
            int fill = lit ? cAccent : cWall;
            int stroke = isTarget ? cAccent : cLine;
            box(c, r1, 1.2f * u, fill, stroke, lit ? k * (.3f + .7f * e) : k);
            if (i == 5) drawTempKey(c, r1, e, k);
            else drawKeyGlyph(c, i, r1, e, k, lit);
            if (wiggle > 0) {
                tint(pFill, cAccent, k * wiggle);
                c.drawCircle(r1.right, r1.top, 1.1f * u, pFill);
            }
        }
    }

    /** 键里的小字形:都是圆点/短线,只求分得出"这是哪个键"。 */
    private void drawKeyGlyph(Canvas c, int i, RectF r, float e, float k, boolean lit) {
        int col = lit ? cCard : cGlyph;
        float cx = r.centerX(), cy = r.centerY(), s = r.width();
        tint(pFill, col, k);
        tint(pLine, col, k);
        switch (i) {
            case 0:
                c.drawLine(cx + .12f * s, cy - .18f * s, cx - .12f * s, cy, pLine);
                c.drawLine(cx - .12f * s, cy, cx + .12f * s, cy + .18f * s, pLine);
                break;
            case 1:
                c.drawLine(cx - .18f * s, cy + .02f * s, cx, cy - .18f * s, pLine);
                c.drawLine(cx, cy - .18f * s, cx + .18f * s, cy + .02f * s, pLine);
                c.drawLine(cx - .12f * s, cy + .18f * s, cx + .12f * s, cy + .18f * s, pLine);
                break;
            case 2:
                for (int j = 0; j < 4; j++)
                    c.drawCircle(cx + (j % 2 == 0 ? -.11f : .11f) * s, cy + (j < 2 ? -.11f : .11f) * s, .06f * s, pFill);
                break;
            case 3:
                for (int j = 0; j < 3; j++) {
                    double a = Math.toRadians(90 + 120 * j);
                    c.drawCircle(cx + (float) Math.cos(a) * .15f * s, cy - (float) Math.sin(a) * .15f * s, .07f * s, pFill);
                }
                break;
            case 4: {
                float sw = effect == Effect.SLOT ? e : 0;
                tint(pFill, col, k * (1 - sw));
                c.drawCircle(cx, cy, .2f * s, pFill);
                if (sw > 0) {
                    tint(pFill, cAccent, k * sw);
                    r2.set(cx - .2f * s, cy - .2f * s, cx + .2f * s, cy + .2f * s);
                    c.drawRoundRect(r2, .06f * s, .06f * s, pFill);
                }
                break;
            }
            case 6:
                pLine.setStrokeWidth(.6f * u);
                c.drawCircle(cx, cy, .17f * s, pLine);
                pLine.setStrokeWidth(.5f * u);
                break;
        }
    }

    /** 线框温度键:‹ 26 ›,风量那条技巧改画七格档位条。 */
    private void drawTempKey(Canvas c, RectF r, float e, float k) {
        float cx = r.centerX(), cy = r.centerY(), h = r.height();
        tint(pLine, cGlyph, k);
        float ax = r.left + .16f * r.width(), bx = r.right - .16f * r.width();
        c.drawLine(ax + .1f * h, cy - .15f * h, ax - .05f * h, cy, pLine);
        c.drawLine(ax - .05f * h, cy, ax + .1f * h, cy + .15f * h, pLine);
        c.drawLine(bx - .1f * h, cy - .15f * h, bx + .05f * h, cy, pLine);
        c.drawLine(bx + .05f * h, cy, bx - .1f * h, cy + .15f * h, pLine);
        if (effect == Effect.FAN) {
            drawFanBars(c, cx, cy, .44f * h, e, k);
            return;
        }
        boolean tick = effect == Effect.TEMP;
        float ts = .5f * h;
        pText.setTextSize(ts);
        float base = cy + ts * .36f;
        int save = c.save();
        c.clipRect(r.left + .3f * r.width(), r.top, r.right - .3f * r.width(), r.bottom);
        if (!tick || e <= 0) {
            tint(pText, cInk, k);
            c.drawText("26", cx, base, pText);
        } else {
            float shift = e * .9f * h;
            tint(pText, cInk, k * (1 - e));
            c.drawText("26", cx, base - shift, pText);
            tint(pText, cAccent, k * e);
            c.drawText("27", cx, base + .9f * h - shift, pText);
        }
        c.restoreToCount(save);
    }

    /** 七格档位条:亮 3 格 → 亮 5 格。 */
    private void drawFanBars(Canvas c, float cx, float cy, float h, float e, float k) {
        int lit = 3 + Math.round(2 * e);
        float bw = .11f * h, bg = .14f * h, total = 7 * bw + 6 * bg;
        float x = cx - total / 2;
        for (int i = 0; i < 7; i++) {
            tint(pFill, i < lit ? cAccent : cGlyph, i < lit ? k : k * .4f);
            r2.set(x, cy - h / 2, x + bw, cy + h / 2);
            c.drawRoundRect(r2, bw / 2, bw / 2, pFill);
            x += bw + bg;
        }
    }

    /** 结果层:盖在场景上面的那些(面板升起、系统页、最近任务、菜单、读数气泡)。 */
    private void drawEffect(Canvas c, float e, float k) {
        if (e <= 0) return;
        float W = S.width(), H = S.height();
        switch (effect) {
            case SHEET: {
                float top = S.bottom - e * .72f * H;
                r1.set(S.left + .04f * W, top, S.right - .04f * W, S.bottom + 3 * u);
                box(c, r1, 2f * u, cCard, cLine, k);
                for (int i = 0; i < 3; i++)
                    hline(c, r1.left + .05f * W, r1.left + (.3f + .1f * i) * W, top + (.12f + .16f * i) * H, cGlyph, k * .6f);
                break;
            }
            case DOCK_EDIT: {
                float dt = dockTopY();
                float top = dt - e * .36f * H;
                r1.set(S.left + .04f * W, top, S.right - .04f * W, dt + 2 * u);
                box(c, r1, 2f * u, cCard, cLine, k * e);
                for (int i = 0; i < 6; i++) {
                    float x = r1.left + (.06f + .15f * i) * W;
                    r2.set(x, top + .1f * H, x + .07f * W, top + .1f * H + .07f * W);
                    box(c, r2, 1f * u, cWall, cLine, k * e);
                }
                break;
            }
            case LIST: {
                box(c, S, 2.2f * u, cCard, cLine, k * e);
                hline(c, S.left + .06f * W, S.left + .3f * W, S.top + .14f * H, cInk, k * e);
                for (int i = 0; i < 4; i++) {
                    float y = S.top + (.32f + .15f * i) * H;
                    hline(c, S.left + .06f * W, S.left + (.5f + .1f * (i % 2)) * W, y, cGlyph, k * e * .7f);
                    hline(c, S.right - .12f * W, S.right - .06f * W, y, cGlyph, k * e * .7f);
                }
                break;
            }
            case GRID: {
                box(c, S, 2.2f * u, cCard, cLine, k * e);
                for (int i = 0; i < 8; i++) {
                    float x = S.left + (.1f + .21f * (i % 4)) * W, y = S.top + (.15f + .4f * (i / 4)) * H;
                    r2.set(x, y, x + .12f * W, y + .12f * W);
                    box(c, r2, 1.6f * u, cWall, cLine, k * e);
                }
                break;
            }
            case RECENTS: {
                tint(pFill, cCard, k * e * .85f);
                c.drawRoundRect(S, 2.2f * u, 2.2f * u, pFill);
                for (int i = 0; i < 3; i++) {
                    float cw = .26f * W, ch = .5f * H;
                    float cx = S.centerX() + (i - 1) * (cw + .04f * W) * e;
                    r2.set(cx - cw / 2, S.top + .22f * H, cx + cw / 2, S.top + .22f * H + ch);
                    box(c, r2, 1.6f * u, cWall, i == 1 ? cAccent : cLine, k * e);
                }
                break;
            }
            case MENU: {
                float[] p = targetXY();
                r1.set(p[0] + 4 * u, p[1] - 12 * u, p[0] + 30 * u, p[1] + 2 * u);
                box(c, r1, 1.6f * u, cCard, cLine, k * e);
                hline(c, r1.left + 3 * u, r1.right - 3 * u, r1.top + 4.5f * u, cGlyph, k * e);
                hline(c, r1.left + 3 * u, r1.right - 3 * u, r1.bottom - 4.5f * u, cGlyph, k * e);
                break;
            }
            case TEMP:
            case FAN: {
                if (!real()) break;   // 线框版把读数画在键里
                // 真桌面上:Dock 上方冒一个读数气泡(和真 Dock 的调节气泡一个意思)
                RectF key = realKey(target == T_TEMP_RIGHT ? T_TEMP_RIGHT : key(5));
                float cx = key != null ? key.centerX() : S.centerX();
                float top = dockTopY() - 12 * u * e;
                r1.set(cx - 9 * u, top - 1 * u, cx + 9 * u, top + 7 * u);
                box(c, r1, 2f * u, cCard, cAccent, k * e);
                if (effect == Effect.FAN) {
                    drawFanBars(c, cx, r1.centerY(), 4.4f * u, 1f, k * e);
                } else {
                    pText.setTextSize(4.6f * u);
                    tint(pText, cAccent, k * e);
                    c.drawText("27℃", cx, r1.centerY() + 1.6f * u, pText);
                }
                break;
            }
            default:
                break;
        }
    }

    /** 手指层。 */
    private void drawFinger(Canvas c, float g, float t, float k) {
        if (t < G0) return;
        if (g >= 1 && kind != Kind.HOLD_SLIDE) return;
        float[] p = targetXY();
        float x = p[0], y = p[1];
        float r = 2.5f * u;
        float W = S.width(), H = S.height();

        switch (kind) {
            case TAP: {
                float in = seg(g, 0, .25f), out = 1 - seg(g, .7f, 1f);
                float a = Math.min(in, out);
                tint(pDot, cAccent, k * a);
                c.drawCircle(x, y, r * (0.7f + .3f * in), pDot);
                float ring = seg(g, .2f, .8f);
                if (ring > 0 && ring < 1) {
                    tint(pRing, cAccent, k * (1 - ring));
                    c.drawCircle(x, y, r + 2.2f * r * ring, pRing);
                }
                break;
            }
            case HOLD:
                holdFinger(c, x, y, r, seg(g, 0, .12f), seg(g, .12f, .92f), seg(g, .92f, 1f), k);
                break;
            case HOLD_SLIDE: {
                float hold = seg(g, 0, .5f);
                float slide = ease(seg(g, .5f, 1f));
                float span = .1f * W;
                float fx = x + dx * span * slide, fy = y + dy * span * slide;
                if (slide > 0) {
                    float by = y - 8 * u;
                    for (int i = 0; i < 3; i++) {
                        float bx = x + (i - 1) * 6 * u;
                        boolean on = i == (slide > .5f ? 2 : 1);
                        tint(pFill, on ? cAccent : cGlyph, k * Math.min(1, slide * 3));
                        c.drawCircle(bx, by, 1.6f * u, pFill);
                    }
                    tint(pTrail, cLine, k);
                    c.drawLine(x, y, fx, fy, pTrail);
                }
                holdFinger(c, fx, fy, r, seg(hold, 0, .2f), seg(hold, .2f, 1f), 0, k);
                break;
            }
            case SWIPE: {
                float p1 = ease(seg(g, .08f, .92f));
                float a = Math.min(seg(g, 0, .1f), 1 - seg(g, .92f, 1f));
                float spanX = .16f * W, spanY = .22f * H;
                float x0 = x - dx * spanX, y0 = y - dy * spanY;
                float x1 = x + dx * spanX, y1 = y + dy * spanY;
                float fx = x0 + (x1 - x0) * p1, fy = y0 + (y1 - y0) * p1;
                fingersAt(c, x0, y0, fx, fy, r, k * a);
                break;
            }
            case PINCH_IN:
            case PINCH_OUT: {
                boolean in = kind == Kind.PINCH_IN;
                float p1 = ease(seg(g, .08f, .92f));
                float a = Math.min(seg(g, 0, .1f), 1 - seg(g, .92f, 1f));
                float far = .11f * W, near = .035f * W;
                float d = in ? far + (near - far) * p1 : near + (far - near) * p1;
                float k2 = (float) Math.sqrt(.5);
                float s0 = in ? far : near;
                tint(pTrail, cLine, k * a);
                c.drawLine(x - k2 * s0, y + k2 * s0, x - k2 * d, y + k2 * d, pTrail);
                c.drawLine(x + k2 * s0, y - k2 * s0, x + k2 * d, y - k2 * d, pTrail);
                tint(pDot, cAccent, k * a);
                c.drawCircle(x - k2 * d, y + k2 * d, r, pDot);
                c.drawCircle(x + k2 * d, y - k2 * d, r, pDot);
                break;
            }
            case DRAG: {
                float p1 = ease(g);
                float a = Math.min(seg(g, 0, .1f), 1 - seg(g, .92f, 1f));
                float hx = S.centerX() + (p1 * 2 - 1) * .12f * W;
                tint(pDot, cAccent, k * a);
                c.drawCircle(hx, y, r, pDot);
                break;
            }
        }
    }

    /** 长按的手指:圆点一直在,外面一道弧从 12 点方向走满一圈,走满荡一下。 */
    private void holdFinger(Canvas c, float x, float y, float r, float in, float arc, float pop, float k) {
        tint(pDot, cAccent, k * in);
        c.drawCircle(x, y, r * (.7f + .3f * in), pDot);
        if (arc > 0) {
            float rr = r * 1.9f;
            r2.set(x - rr, y - rr, x + rr, y + rr);
            tint(pRing, cAccent, k);
            c.drawArc(r2, -90, 360 * arc, false, pRing);
        }
        if (pop > 0 && pop < 1) {
            tint(pRing, cAccent, k * (1 - pop));
            c.drawCircle(x, y, r * 1.9f + 2 * r * pop, pRing);
        }
    }

    /** 几根手指并排(沿运动方向的垂线摊开)+ 各自的轨迹。 */
    private void fingersAt(Canvas c, float x0, float y0, float x, float y, float r, float k) {
        float px = -dy, py = dx;
        if (px == 0 && py == 0) { px = 1; py = 0; }
        float gap = 2.3f * r;
        tint(pTrail, cLine, k);
        tint(pDot, cAccent, k);
        for (int i = 0; i < fingers; i++) {
            float off = (i - (fingers - 1) / 2f) * gap;
            c.drawLine(x0 + px * off, y0 + py * off, x + px * off, y + py * off, pTrail);
        }
        for (int i = 0; i < fingers; i++) {
            float off = (i - (fingers - 1) / 2f) * gap;
            c.drawCircle(x + px * off, y + py * off, r, pDot);
        }
    }
}
