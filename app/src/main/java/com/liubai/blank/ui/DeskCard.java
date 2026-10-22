package com.liubai.blank.ui;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.liubai.blank.mode.Reskinnable;
import com.liubai.blank.mode.ModeApplier;
import com.liubai.blank.mode.ModeManager;
import com.liubai.blank.util.Launchers;

/**
 * 桌面主页层上一张能自己摆位置的卡片。**卡片的地基**:底、圆角、半透明、拖拽摆位
 * 全在这儿,子类只管往里放内容(见 {@link #content()})。
 *
 * <h3>为什么自由摆位</h3>
 *
 * 迪友那 11 种桌面卡是**顺序 + 固定槽位**:存的 {@code KEY_DEFAULT_DESKTOP_CARD_LOCATION_VALUE}
 * 是一串 `;` 分隔的 key(反编译 3.0.1,`f4/i.java:306` split 出来按序渲染),改顺序靠弹层里
 * 的列表拖拽({@code b4/h.java} 是个 ItemTouchHelper.Callback)。**它没有自由坐标** ——
 * 整个工程里存 X/Y 的只有悬浮歌词。
 *
 * 留白主页层是一整片空白(见 layout_mode_dashboard.xml),没有槽位可言,那就干脆让卡片
 * 落在哪儿是哪儿。
 *
 * <h3>位置存比例不存 px</h3>
 *
 * 车机分辨率不止一种(1920×1080 横屏、1520×1080 竖屏都有),存 px 换台车就飞到画面外。
 * 存的是「卡片左上角占可移动范围的几成」,见 {@link #applyPos}。
 *
 * <h3>手势</h3>
 *
 * launcher 上图标那套,一个长按按之后手指走不走分岔:
 * <ul>
 *   <li>长按 → 挪动 → 松手 = 摆到新位置;</li>
 *   <li>长按 → 原地松手 = {@link Host#onCardHold},由宿主决定弹什么(车控卡弹的是配置面板)。</li>
 * </ul>
 *
 * **判断必须写在 {@link #onInterceptTouchEvent} 里**,不能挂 OnLongClickListener ——
 * 那个在长按到点的一瞬就回调了,拿不到"之后有没有移动"。
 *
 * <h3>编辑态(双指合拢进)</h3>
 *
 * 长按那套是"动一张卡"的手势;要连着摆好几张时,每张都得先按住 300ms 就很笨。
 * 所以另给一个**编辑态**({@link #setEditing}),规矩照 Dock 那条编辑态({@code DockView.setEditing}):
 * <ul>
 *   <li>按下就能拖,不用等长按;</li>
 *   <li>点按功能整个让位 —— 事件在 intercept 里就拦住,不派发给子 view(不然编辑态里
 *       挪一张车控卡就把空调给开了);</li>
 *   <li>右上角挂 ×,唯一还点得动的东西就是它。</li>
 * </ul>
 */
public abstract class DeskCard extends FrameLayout implements Reskinnable {

    private static final String TAG = "Blank/DeskCard";

    public interface Host {
        /** 摆完了。给的是**比例**(0~1)。 */
        void onCardMoved(String cardId, float xRatio, float yRatio);
        /** 长按后原地松手。卡片自己不决定弹什么。 */
        void onCardHold(String cardId);
        /** 编辑态里点了 × —— 把这张卡从桌面拿掉。卡片自己不知道该改哪份存储。 */
        void onCardRemove(String cardId);
        /** 开始拖 / 松手。宿主拿它开关落点提示({@link DropHintView})。 */
        void onCardDrag(boolean dragging);
        /** 拖动中的落点(px,相对主页层):松手就会吸到这儿。 */
        void onCardDragSpot(float x, float y, float w, float h);
        /**
         * 对齐格子:{@code [宽, 高, 原点X, 原点Y]},单位 px。**含缝的步长**,
         * 一格实际大小要减掉 {@link #cardGap()}。
         * **所有卡片吸到同一套格子上** —— 各按各的尺寸吸的话,大小不同的两张卡永远对不齐。
         */
        int[] snapCell();

        /** 格与格之间那道缝(px)。算"几格有多宽"时要减掉它。 */
        int cardGap();

        /**
         * 松手了。**拖到哪格就落哪格** —— 那格有人就跟他换位置(照 Dock 那条规矩:
         * 交换,不是插队,更不是把我推到别处去)。换位这件事只有宿主办得到(它手上有全部卡片)。
         *
         * 必须挂在这一个出口上:桌面内部拖动和从候选区拖进来都走 {@link #snapAndSave},
         * 只在其中一条路上做就会漏(2026-08-17 实测过两次)。
         *
         * @param fromTx 我拖动之前在哪儿(交换时原住户挪到这儿)。
         *               {@code NaN} = 我是刚从候选区拖进来的、没有老位置,
         *               那就把原住户挪到最近的空格
         * @return 实际该落的 translation {x, y};宿主不管就返回 null,原样落下
         */
        float[] resolveDrop(String cardId, float tx, float ty,
                            float fromTx, float fromTy, int w, int h);
    }

    /** 12dp —— 和迪友那批桌面卡片一档(反编译 3.0.1,f4/e.java 的 ViewOutlineProvider)。 */
    private static final int RADIUS_DP = 12;

    /**
     * 卡片底的不透明度(0~100)。
     *
     * **不能是实心块** —— 卡片浮在壁纸上,一块不透光的深灰压上去,壁纸就被戳了个洞。
     * 迪友那批卡片是 {@code BlurView} 毛玻璃 + 一层 20% 黑叠色(深色态 #33000000 /
     * 浅色态 #32FFFFFF,见反编译 f4/e.java),底下的壁纸是透着的。
     *
     * 留白走同一个观感、更省的做法:半透明底让壁纸透上来 —— 和 Dock 是同一套
     * ({@link ModeApplier#opacity},只动背景 drawable 的 alpha、不动 View 的 alpha,
     * 图标和文字保持全不透明,行车中才看得清)。
     *
     * 具体那一档由用户在外观弹层的「卡片」里调(见 DeskCardStore.opacity),默认 55。
     *
     * **没有真做模糊**:车机是 API 29,没有 RenderEffect;迪友是引了第三方 RenderScript
     * 模糊库才做到的,留白全项目还没有这类依赖。
     */
    /**
     * 长按多久算"按住了这张卡"。和 Dock 的拖拽排序同一个值(见 DockView.DRAG_HOLD_MS)。
     *
     * <p><b>长按不再是"拿起来拖"</b>(2026-09-05 改):非编辑态下按住只做一件事 ——
     * 松手时交给宿主({@link Host#onCardHold}),由宿主决定这张卡自己的动作
     * (WLAN 卡=出 WiFi 列表)。摆位置只在编辑态里做:双指捏进编辑态,那里按下即拖。
     *
     * <p>改之前是两层:长按 → 宿主 {@code setEditing(true)} 进编辑态 → 卡片就能拖了。
     * 于是"长按"同时是"进编辑态"的入口和"搬卡片"的起手式,按住之后手指稍微一动
     * 就在搬卡片。现在拆开:进编辑态只认双指捏,长按留给卡片自己的动作。
     */
    private static final long HOLD_MS = 300L;
    /**
     * 宿主给不出格子尺寸时的兜底步长。
     *
     * **正常情况下不该走到这儿** —— 吸附的格子是 {@link Host#snapCell()} 给的
     * "一张卡多大"(见 HomeActivity),这样格线正好落在卡片边界上,拖起来是"啪"地
     * 落进格位。早先这里写死 16dp:那是个内部步长,和卡片尺寸毫无关系,
     * 画出来就是一片没有意义的点阵(2026-08-16 翻的车)。
     */
    private static final int SNAP_FALLBACK_DP = 16;

    private final String cardId;

    public String id() { return cardId; }
    private Host host;

    private float downX, downY;      // 按下时的屏幕坐标
    private float startTx, startTy;  // 按下时卡片的位移
    private boolean held;            // 按住到点了(编辑态里还会浮起来)
    private boolean dragging;        // 真的在拖(只可能在编辑态)
    private boolean editing;         // 编辑态:按下即拖、点按让位、挂 ×
    private boolean externalDrag;    // 候选区正把它拖在手上(见 followFinger)
    private TextView delBadge;       // × 角标,进编辑态时才建(平时一张卡都不多挂一个 view)
    /** 不跑动效时静止,和空调面板同一个开关。 */
    protected final boolean still = ModeManager.get().hvacBasic();

    /**
     * 按住到点:**当场就把动作发出去**,不等松手。
     *
     * <p>不浮起来 —— 浮起是"可以拖了"的暗示,而非编辑态已经不拖了(见 {@link #HOLD_MS})。
     * 震动照给,那是"收到了"的反馈。
     *
     * <p><b>为什么不能等 ACTION_UP</b>(2026-09-05 真车实测):长按不动的手势里,
     * intercept 一直返回 false(子 view 消费着 DOWN),直到 UP 那一刻才因为
     * {@code return held} 变成拦截。而 {@code ViewGroup.dispatchTouchEvent} 在
     * "本次事件才开始拦截"时,只会给子 view 补一个 CANCEL,**这次 UP 不会再交给自己的
     * onTouchEvent** —— 于是动作永远发不出去。改之前那版是靠 {@code beginDrag()}
     * 在 MOVE 阶段就 return true 才拿得到 UP,拖动一去掉,这条路就断了。
     */
    private final Runnable holdRun = new Runnable() {
        @Override public void run() {
            held = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            if (host != null) host.onCardHold(cardId);
        }
    };

    /** 浮起来:抬升 + 略放大。**只有编辑态按下才浮** —— 浮起等于"这张卡在手上了"。 */
    private void lift() {
        setElevation(Launchers.dp(getContext(), 16));
        if (!still) {
            setScaleX(1.04f);
            setScaleY(1.04f);
        }
    }

    protected DeskCard(Context ctx, String cardId) {
        super(ctx);
        this.cardId = cardId;
        int padH = Launchers.dp(ctx, 8);
        int padV = Launchers.dp(ctx, 10);
        setPadding(padH, padV, padH, padV);
        // **只铺底,不调 reskin()** —— reskin 会走到子类的 onReskin(),而这会儿子类的
        // 字段一个都还没初始化(Java 先跑完基类构造器,才轮到子类的字段和构造体),
        // 子类那边一 for 自己的列表就是 NPE。底色是基类自己的事,直接铺
        applyCardBg();
    }

    public String cardId() { return cardId; }

    /**
     * 正被手指拖着。宿主重铺位置时要跳过它 —— 别把用户手上那张抢回存档位置。
     *
     * 也认**从候选区拖出来的那张**({@link #followFinger}):它不是走本类的手势,
     * 但同样在手指上、同样还没存位置 —— 不算进来的话,拖动途中任何一次布局都会把它
     * 按"没摆过"重排到左上角去。
     */
    public boolean dragging() { return dragging || externalDrag; }

    public void setHost(Host h) { this.host = h; }

    /** 子类往这里放内容。基类只管地基,不掺和里头长什么样。 */
    protected FrameLayout content() { return this; }

    /**
     * 宿主给的格子尺寸 {@code [宽, 高, 原点X, 原点Y]},拿不到返回 null。
     * 子类想按"几格宽"定自身大小时用(时钟卡占两格,见 ClockCard)。
     */
    protected int[] hostCell() {
        return host != null ? host.snapCell() : null;
    }

    /**
     * 垫到「一格」那么高。宿主每次重排都会给所有卡发一遍(见 DeskCardHost.onLayoutCards)。
     *
     * <h3>为什么要有这条,而不是各卡自己抄规格</h3>
     * 卡片高度原先完全是各自内容撑出来的,**没有任何地方强制** —— 靠的是每个新卡
     * 作者去"照抄车控卡的规格"。抄是人的动作,抄一次漂一次:游戏卡漏抄了车控卡
     * 「没有档位条也要把条那段空出来」的 10dp、胎压卡的内容本来就比一格矮十几 dp,
     * 于是真车上一排卡高低参差。**「所有卡一样大」这条规矩要成立,就不能只写在注释里。**
     *
     * 格子是唯一真源({@link Host#snapCell},量的是车控卡),这里把它变成对所有卡的约束。
     *
     * <b>只垫不压</b>:比一格高的卡(画中画那种)不受影响 —— 它们本来就该占好几格。
     */
    public void fitCell(int cellH) {
        if (cellH <= 0 || getMinimumHeight() == cellH) return;
        setMinimumHeight(cellH);
        requestLayout();
    }

    /**
     * 把宽度吸到**整数格**:内容要多宽就取几格(向上取整),多出来的地方留白、内容居中。
     *
     * 高度那条由 {@link #fitCell} 统一垫,宽度不行 —— 多格卡本来就该各有各的宽
     * (时钟两格、车况四格)。但"各有各的宽"不等于"随内容长到哪算哪":车况卡的宽
     * 是五格读数撑出来的,比四格宽出十几像素,右边缘就压到第五格的地界里去了
     * (用户:「车况卡片占 4 个格子吧,怎么还能超出?」)。
     *
     * 内容变了要重新调一次(读数位数会变:1936km 和 986km 不一样宽)。
     */
    protected void snapWidthToCells() {
        int[] cell = hostCell();
        if (cell == null || cell[0] <= 0) return;
        android.view.ViewGroup.LayoutParams lp = getLayoutParams();
        if (lp == null) return;
        // 自己量一次:这会儿可能还没布局过,getWidth() 是 0
        measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int need = getMeasuredWidth();
        if (need <= 0) return;
        int gap = host != null ? host.cardGap() : 0;
        int n = Math.max(1, (int) Math.ceil((need + gap) / (float) cell[0]));
        int w = cell[0] * n - gap;
        if (lp.width == w) return;
        lp.width = w;
        setLayoutParams(lp);
    }

    // ---- 编辑态 ----

    /** 进/出编辑态。见类注释。 */
    public void setEditing(boolean on) {
        if (editing == on) return;
        editing = on;
        if (on) {
            ensureDelBadge();
        } else {
            // 退出时手上可能正拖着一张,收干净:不收的话它会一直浮着、还占着 dragging,
            // 宿主重铺位置时会一直跳过它
            removeCallbacks(holdRun);
            settle();
        }
        if (delBadge != null) delBadge.setVisibility(on ? VISIBLE : GONE);
    }

    public boolean editing() { return editing; }

    /**
     * × 角标。视觉照 Dock 编辑态那颗({@code DockView.wrapForEdit}):20dp 圆底走 muted、
     * × 字取 bg 反色、elevation 抬到最上。
     *
     * **压在卡片右上角内侧,不外挂** —— 外挂的部分超出父容器就点不到了(触摸不派发到
     * 父容器边界之外),Dock 那边同一个理由。
     */
    private void ensureDelBadge() {
        if (delBadge != null) return;
        TextView del = new TextView(getContext());
        del.setText("×");
        del.setTextSize(12f);
        del.setGravity(Gravity.CENTER);
        del.setContentDescription("移除");
        del.setElevation(Launchers.dp(getContext(), 12));
        if (!still) Motion.press(del);
        del.setOnClickListener(v -> {
            if (host != null) host.onCardRemove(cardId);
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                Launchers.dp(getContext(), 20), Launchers.dp(getContext(), 20));
        lp.gravity = Gravity.END | Gravity.TOP;
        addView(del, lp);
        delBadge = del;
        paintDelBadge();     // 颜色只在这一处调,换肤走的也是它
    }

    /** 触点是不是落在 × 上。编辑态拦一切,只给它开这一个口子。 */
    private boolean hitDelBadge(MotionEvent ev) {
        if (delBadge == null || delBadge.getVisibility() != VISIBLE) return false;
        Rect r = new Rect();
        delBadge.getHitRect(r);
        return r.contains((int) ev.getX(), (int) ev.getY());
    }

    // ---- 换肤 ----

    @Override public void reskin() {
        applyCardBg();
        // × 的两个颜色(muted 底 + bg 字)都烧在 drawable 和 TextView 上了,换肤取不到,
        // **就地重新上色**。
        //
        // 早先这里是"扔掉重建"(removeView + ensureDelBadge)—— 那是个隐蔽的雷:
        // reskin() 是 ModeApplier **遍历 view 树的过程中**调的,而它在遍历里动自己的
        // 子节点数组,遍历用的却是进循环前取的 count → 读到 null → 崩在
        // `dispatchCollectViewAttributes` / `resetSubtreeAccessibilityStateChanged`
        // (2026-08-18 模拟器上连崩数次,栈里一帧自己的代码都没有)。
        // 换个颜色而已,没有任何理由动 view 树。
        paintDelBadge();
        onReskin();
        invalidate();
    }

    /** 给 × 上色。建的时候和换肤时走同一份,免得两处调色板对不上。 */
    private void paintDelBadge() {
        if (delBadge == null) return;
        delBadge.setTextColor(ModeManager.get().bg());
        GradientDrawable dbg = new GradientDrawable();
        dbg.setShape(GradientDrawable.OVAL);
        dbg.setColor(ModeManager.get().muted());
        delBadge.setBackground(dbg);
    }

    /** 只铺卡片自己的底。**不能是可重写的** —— 构造器里要用(见构造体的注释)。 */
    private void applyCardBg() {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Launchers.dp(getContext(), RADIUS_DP));
        bg.setColor(ModeManager.get().deskTile());
        setBackground(bg);
        // 半透明要在 setBackground 之后叠 —— 换的是新 drawable,alpha 不会自己跟过来
        ModeApplier.opacity(this, com.liubai.blank.data.DeskCardStore.opacity(getContext()));
    }

    /** 子类把自己那份颜色重取一遍(自绘/烧在 Paint 里的都得自己来)。 */
    protected void onReskin() {}

    // ---- 摆位 ----

    @Override public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getRawX();
                downY = ev.getRawY();
                held = false;
                dragging = false;
                if (editing) {
                    // × 是这一层唯一还点得动的东西,放它过去
                    if (hitDelBadge(ev)) return false;
                    // 按下即可拖:直接进 held,并且**把事件拦在这一层**——
                    // 不拦的话子 view 照样收得到点击,编辑态里挪一张车控卡就把功能给触发了
                    held = true;
                    startTx = getTranslationX();
                    startTy = getTranslationY();
                    lift();
                    return true;
                }
                postDelayed(holdRun, HOLD_MS);
                return false;
            case MotionEvent.ACTION_MOVE:
                float dx = ev.getRawX() - downX, dy = ev.getRawY() - downY;
                boolean moved = Math.abs(dx) > slop() || Math.abs(dy) > slop();
                if (!held) {
                    // 还没到长按时长就滑走了:那是别的手势(或者只是手抖),撤掉计时
                    if (moved) removeCallbacks(holdRun);
                    return false;
                }
                if (!editing) {
                    // 长按动作已经在 holdRun 里发出去了。这一轮剩下的事件**一律吞掉**:
                    // 不拖,也别让子 view 在松手时再触发一次点击(车控卡的可点子项就在底下)
                    return true;
                }
                if (moved) beginDrag();
                return dragging;
            default:
                removeCallbacks(holdRun);
                // 长按过就拦下来,让 onTouchEvent 统一收尾 —— 收尾逻辑只写一份,
                // 否则两处都判"长按原地松手"会把宿主的回调触发两次
                return held;
        }
    }

    /**
     * 拖动的实际处理。**判定不能只写在 {@link #onInterceptTouchEvent} 里**:
     *
     * 卡片内容有没有 clickable 的子 view,决定了后续事件走哪条路 ——
     * <ul>
     *   <li>有(车控卡的项):子 view 消费 DOWN,{@code mFirstTouchTarget != null},
     *       后续 MOVE 照样先过一遍 intercept;</li>
     *   <li>没有(时间卡就是一堆 TextView):没人消费 DOWN,{@code mFirstTouchTarget == null},
     *       **后续 MOVE 直接送到这里,intercept 再也不会被回调**。</li>
     * </ul>
     *
     * 所以 held→dragging 这一步两边都得有。只写在 intercept 里的话,时间卡这种
     * 没有可点子控件的卡**永远拖不动**(实测:长按浮起来了,手一动纹丝不动)。
     */
    @Override public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                if (!held) return true;
                // 非编辑态不拖(见 HOLD_MS),动作已在 holdRun 里发过,这里什么都不做
                if (!editing) return true;
                if (!dragging) {
                    boolean moved = Math.abs(ev.getRawX() - downX) > slop()
                            || Math.abs(ev.getRawY() - downY) > slop();
                    if (!moved) return true;
                    beginDrag();
                }
                setTranslationX(clampX(startTx + ev.getRawX() - downX));
                setTranslationY(clampY(startTy + ev.getRawY() - downY));
                if (host != null) {
                    // 虚框画在**主页层坐标**里,translation 是相对自己 layout 位置的偏移,
                    // 得把 layout 位置(卡片挂上去时那 6dp 边距)加回来
                    host.onCardDragSpot(getLeft() + snapX(getTranslationX()),
                            getTop() + snapY(getTranslationY()),
                            getWidth(), getHeight());
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(holdRun);
                if (dragging) {
                    snapAndSave();
                }
                // 非编辑态的长按动作已经在 holdRun 里发过了(不等松手,原因见那儿),
                // 这里只管收尾
                settle();
                return true;
            default:
                return true;
        }
    }

    /**
     * 进入拖动态。**两条路径都会走到这儿**(intercept 判定在前、onTouchEvent 兜底),
     * 所以通知宿主开网格的动作只写这一份 —— 写两处的话,只改一处就是"有时候有格子有时候没有"。
     */
    private void beginDrag() {
        if (dragging) return;
        dragging = true;
        if (host != null) host.onCardDrag(true);
    }

    /** 落回去:去掉浮起的抬升和放大。 */
    private void settle() {
        if (dragging && host != null) host.onCardDrag(false);
        held = false;
        dragging = false;
        setElevation(0f);
        setScaleX(1f);
        setScaleY(1f);
    }

    /** 松手:吸到网格上,再把位置(比例)交出去存。 */
    /** 格子:宿主给不出就退回兜底步长。返回 {宽, 高, 原点X, 原点Y}。 */
    private int[] cell() {
        int[] c = host != null ? host.snapCell() : null;
        if (c != null && c.length == 4 && c[0] > 0 && c[1] > 0) return c;
        int f = Launchers.dp(getContext(), SNAP_FALLBACK_DP);
        return new int[]{f, f, 0, 0};
    }

    /** x 吸到最近的格位。落点提示和松手吸附**共用这一份**,免得画的和落的不是一处。 */
    private float snapX(float x) {
        int[] c = cell();
        return clampX(c[2] + Math.round((x - c[2]) / (float) c[0]) * (float) c[0]);
    }

    private float snapY(float y) {
        int[] c = cell();
        return clampY(c[3] + Math.round((y - c[3]) / (float) c[1]) * (float) c[1]);
    }

    private void snapAndSave() {
        float tx = snapX(getTranslationX()), ty = snapY(getTranslationY());
        // 那一格有人就跟他换(见 Host.resolveDrop)。**所有落位都走这儿**。
        // fromTx/fromTy = 拖动前的位置,交换时原住户挪过去;候选区拖进来的没有老位置(NaN)
        if (host != null) {
            float[] ok = host.resolveDrop(cardId, tx, ty,
                    externalDrag ? Float.NaN : startTx, externalDrag ? Float.NaN : startTy,
                    getWidth(), getHeight());
            if (ok != null && ok.length == 2) { tx = ok[0]; ty = ok[1]; }
        }
        setTranslationX(clampX(tx));
        setTranslationY(clampY(ty));
        if (host == null) return;
        float sx = spanX(), sy = spanY();
        // **可移动范围为 0 时不许存** —— 那种情况下算出来的比例恒是 0(除数没了),
        // 存下去就把用户摆好的位置抹成"左上角"。范围会是 0 的场合是真实存在的:
        // 卡片比容器还宽、或者这一帧还没量出尺寸。宁可这次不存,也不能存个假的
        if (sx <= 0 || sy <= 0) {
            Log.i(TAG, "可移动范围还是 0(" + sx + "×" + sy + "),这次不存位置");
            return;
        }
        Log.i(TAG, "存位置 " + cardId + " translation=(" + (int) getTranslationX() + ","
                + (int) getTranslationY() + ") 可移动范围=" + (int) sx + "×" + (int) sy
                + " → 比例=(" + (getTranslationX() / sx) + "," + (getTranslationY() / sy) + ")");
        host.onCardMoved(cardId, getTranslationX() / sx, getTranslationY() / sy);
    }

    /**
     * 从候选区拖进来的落位:直接给 px,吸到格子上再存。
     *
     * 和手指拖动松手走的是**同一份**吸附与存储({@link #snapAndSave}),所以从候选区
     * 拖出来的卡和自己在桌面上挪过的卡落点规则完全一致。
     */
    public void dropAt(float tx, float ty) {
        /*
         * 记住"从哪儿来" —— 那一格有人时要把他换到这儿(见 Host.resolveDrop)。
         *
         * **不能指望 startTx 已经有值**:它只在编辑态按下那一刻记(intercept 的 DOWN),
         * 而 dropAt 有几条路径压根没经过按下 —— 候选区拖进来的
         * 第一次落位、以及任何再次落位。那时 startTx 还是初始的 0,原住户就被换到了
         * 屏幕左上角(2026-08-18 日志实录:`换位 cc_drl → (0,0),clock → (0,0)`)。
         *
         * externalDrag 那一路例外:它本来就"没有老位置"(传 NaN,原住户改为挪到最近空格)。
         */
        if (!externalDrag) {
            startTx = getTranslationX();
            startTy = getTranslationY();
        }
        setTranslationX(clampX(tx));
        setTranslationY(clampY(ty));
        // 顺序要紧:snapAndSave 里要靠 externalDrag 判断"我有没有老位置可以让给原住户"
        snapAndSave();
        externalDrag = false;
        settle();
    }

    /**
     * 把当前位置**原样存下来** —— 不吸附、不跟人换位。
     *
     * 给默认排位用(见 {@code HomeActivity.placeCards}):那批位置是宿主按行列算好的,
     * 本来就落在格位上、也已经避开了别人,再走一遍 {@link #snapAndSave} 反而会拿
     * 未初始化的 {@code startTx} 当"老位置"去和原住户交换。
     *
     * 而"存下来"这件事本身是必须的:不存的话下一次布局又从头排一遍,而那时别的卡片
     * 都有存档、直接 {@code applyPos} 走掉、不再参与行列推进 —— 这张就会跳到左上角
     * (2026-08-18 平板实测:上滑收栏触发一次布局,时钟卡从第 3 行跳到第 1 行)。
     */
    public void persistPos() {
        if (host == null) return;
        float sx = spanX(), sy = spanY();
        if (sx <= 0 || sy <= 0) return;   // 还没量出来,这次不存(理由同 snapAndSave)
        host.onCardMoved(cardId, getTranslationX() / sx, getTranslationY() / sy);
    }

    /** 跟手:拖动中只动 translation,不吸附不存(松手才走 {@link #dropAt})。 */
    public void followFinger(float tx, float ty) {
        if (!externalDrag) {
            externalDrag = true;
            lift();      // 浮起来,和手指拖桌面上的卡一个样子
        }
        setTranslationX(clampX(tx));
        setTranslationY(clampY(ty));
    }

    /**
     * 把存下来的比例位置铺上去。父容器要先量出来才有意义,所以调用方在 post 里调
     * (见 HomeActivity.placeCards)。比例 × 可移动范围 = 位移。
     */
    public void applyPos(float xRatio, float yRatio) {
        setTranslationX(clampX(xRatio * spanX()));
        setTranslationY(clampY(yRatio * spanY()));
    }

    /** 横向能挪多远:父容器宽度减去卡片自己占的,再减掉初始那点 margin。 */
    private float spanX() {
        View p = (View) getParent();
        if (p == null) return 0f;
        return Math.max(0, p.getWidth() - getWidth() - getLeft());
    }

    private float spanY() {
        View p = (View) getParent();
        if (p == null) return 0f;
        return Math.max(0, p.getHeight() - getHeight() - getTop());
    }

    /** 不许拖出画面 —— 拖丢了就再也点不着了。 */
    private float clampX(float tx) {
        return Math.max(-getLeft(), Math.min(tx, spanX()));
    }

    private float clampY(float ty) {
        return Math.max(-getTop(), Math.min(ty, spanY()));
    }

    private int slop() {
        return ViewConfiguration.get(getContext()).getScaledTouchSlop();
    }
}
