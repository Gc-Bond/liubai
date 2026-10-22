package com.liubai.blank.ui;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.PixelFormat;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.liubai.blank.R;
import com.liubai.blank.privileged.ForegroundWatch;
import com.liubai.blank.mode.ModeApplier;
import com.liubai.blank.mode.ModeManager;
import com.liubai.blank.util.Launchers;
import com.liubai.blank.util.Overlay;

/**
 * 长按 Dock 车控键时浮在它上方的调节气泡。
 *
 * 为什么自己成一个窗口而不是往 activity_home 里塞一层:Dock 自己就能管住它的生死,
 * 不用让 Activity 转发一遍显示/隐藏,布局里也不多一个平时看不见的 View。
 *
 * **窗口是 WindowManager 直接挂的,不用 PopupWindow**。Dock 搬进悬浮窗之后
 * (见 DockHost),PopupWindow 这条路就走不通了:它拿 anchor 的
 * applicationWindowToken 去 addView,而系统窗口里那个 token 对 WMS 无效,
 * 直接抛 BadTokenException 把整个桌面进程带走(实测崩在长按风量键上)。
 * 光把 windowLayoutType 改成系统类型也没用 —— type 对了 token 还是错的。
 * 跟 {@link DockHost} 挂空调面板一个路子:用宿主那个 Context 的 WindowManager 挂,
 * token 由系统自己给。
 *
 * 三层结构,各管一件事:
 *   {@link BubbleFrame}   外壳。渐变的面 + 指向按键的尾巴
 *   {@link RollingNumber} 读数。数值瞬间换牌不做动画(为什么见它的类注释)
 *   {@link FanStage}      舞台。风量是"柱子(可数)+ 粒子(量感)",温度是一条冷暖色阶横条
 *
 * 数值本身不在这里算 —— 它只负责显示,调什么、调到多少由 DockView 的手势和上层决定。
 * 风量柱子的动画方向是个例外:它由"这次的值比上次大还是小"推出来,调用方不该操心这个。
 */
public final class HvacBubble {

    /**
     * 舞台尺寸。**收紧过两次**:原来 182×58,里头就七根柱子 + 几条粒子,空得慌,
     * 整个气泡显得又大又散(实测截图上就是这个毛病)。柱子该占主体。
     * 2026-08-02 又把高度 34→27:**所有气泡统一成换 app 那套的高度**(图标排 44 +
     * 名字行 ≈ 67dp) —— 同一个壳子弹出来忽高忽矮,看着像两个组件。读数字号一并
     * 收了 36→30(见 RollingNumber.SIZE_NUM),上排 35 + 舞台 6+27 ≈ 68dp,对上这条线。
     */
    private static final int STAGE_W_DP = 150;
    private static final int STAGE_H_DP = 27;

    private final Context ctx;
    /** 挂窗用的,跟宿主走(见 attach)。没挂上时是 null。 */
    private WindowManager wm;
    private final WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
    private boolean attached;
    private final BubbleFrame root;
    /** 空调那套内容(图标 + 读数)。换 app 时整排让位给 slotBox。 */
    private final LinearLayout top;
    /** 换 app 那套内容:一排候选图标 + 当前那个的名字。 */
    private final LinearLayout slotBox;
    private final LinearLayout slotStrip;
    private final TextView slotName;
    private final ImageView icon;
    private final Motion.Spinner spin;
    private final RollingNumber number;
    private final FanStage stage;

    private int iconRes;
    private int lastLevel = Integer.MIN_VALUE;   // 柱子动画方向靠它推(FanStage)
    private boolean exiting;

    private final Runnable hideTask = new Runnable() {
        @Override public void run() { hide(); }
    };
    private View host;     // 用它的 handler 排延时隐藏
    private View anchored; // 当前挂在哪颗键上 —— 内容变了要照它重新定位

    public HvacBubble(Context ctx) {
        this.ctx = ctx;

        root = new BubbleFrame(ctx);

        // 上排:图标 + 读数
        top = new LinearLayout(ctx);
        top.setOrientation(LinearLayout.HORIZONTAL);
        // **横向也居中**:这排是 wrap_content,而下面的舞台是固定 150dp 宽、撑起了整个
        // 气泡的宽度。不居中的话上排就吊在左边、右边空掉一大片,上挤下满看着是歪的
        // (读数从"自动"改成"A"之后更明显)。
        top.setGravity(Gravity.CENTER_VERTICAL | Gravity.CENTER_HORIZONTAL);

        icon = new ImageView(ctx);
        icon.setColorFilter(ModeManager.get().muted());
        top.addView(icon, new LinearLayout.LayoutParams(
                Launchers.dp(ctx, 21), Launchers.dp(ctx, 21)));
        spin = new Motion.Spinner(icon);

        number = new RollingNumber(ctx);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.setMarginStart(Launchers.dp(ctx, 8));
        top.addView(number, nlp);

        root.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        stage = new FanStage(ctx);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                Launchers.dp(ctx, STAGE_W_DP), Launchers.dp(ctx, STAGE_H_DP));
        slp.topMargin = Launchers.dp(ctx, 6);   // 收紧,凑统一高度那条线(见 STAGE_H_DP)
        root.addView(stage, slp);

        // 换 app 那一套(地图/音乐键长按滑动)。跟空调两套内容共用这个气泡壳子 ——
        // 窗口挂载/定位/退场那些逻辑一份就够,没必要为它再写一个气泡。
        slotBox = new LinearLayout(ctx);
        slotBox.setOrientation(LinearLayout.VERTICAL);
        slotBox.setGravity(Gravity.CENTER_HORIZONTAL);
        slotBox.setVisibility(View.GONE);

        slotStrip = new LinearLayout(ctx);
        slotStrip.setOrientation(LinearLayout.HORIZONTAL);
        slotStrip.setGravity(Gravity.CENTER_VERTICAL);
        // 宽度必须显式 WRAP_CONTENT:LinearLayout 在 VERTICAL 下的默认 LayoutParams 是
        // MATCH_PARENT,那样这排图标会被父容器的宽度(由下面那行名字撑出来)夹住,
        // 排不下的图标直接被压成 0 宽 —— 表现为"三个候选只画出来一个"(真车实测)
        slotBox.addView(slotStrip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        slotName = new TextView(ctx);
        slotName.setTextSize(14f);
        slotName.setGravity(Gravity.CENTER);
        slotName.setSingleLine(true);
        slotName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nmlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nmlp.topMargin = Launchers.dp(ctx, 8);
        slotBox.addView(slotName, nmlp);

        root.addView(slotBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        lp.format = PixelFormat.TRANSLUCENT;
        lp.gravity = Gravity.TOP | Gravity.START;   // x/y 用屏幕绝对坐标(见 place)
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                // 手指还按在 Dock 键上,气泡不能抢事件
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                // 允许画到系统栏区域,气泡贴着屏幕边缘时不被裁
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
    }

    // ---- 风量 ----

    /**
     * 换配色后重新取色。
     *
     * **这个气泡是独立悬浮窗**(自己的 WindowManager),不在桌面那棵 view 树里,
     * {@link ModeApplier#apply} 从 content 根遍历下来永远到不了它 —— 只能由持有它的
     * {@link DockView} 在重铺时点名调一次。它也不跟着 Dock 重建(实例挂在 DockView 字段上),
     * 所以这是它唯一的换色时机:**2026-08-02 之前它一直停在用户最早那套配色上**,
     * 表现为 Dock 换了底色、贴在上面的气泡还是旧色。
     *
     * 里头的 {@link BubbleFrame}(渐变面)和 {@link RollingNumber}(读数)都实现了
     * {@link com.liubai.blank.mode.Reskinnable},对这棵子树 apply 一遍就全刷到了。
     *
     * icon / slotName / number 的颜色**不归这儿管**:它们是状态色(温度的冷暖、风量的
     * accent),每次显示时由 applyTemp / applyFan / updateSlot 现设。
     */
    public void reskin() {
        ModeApplier.apply(root);
    }

    /**
     * @param level 0~7,-1 = 读不到。柱子点亮几根、粒子吹多快、风扇转多快都看它
     * @param adjusting true=调节中(常驻);false=只是看一眼当前值(短按),过一会自己收
     */
    public void showFan(View anchor, int level, String value, String unit, boolean adjusting) {
        prepare(anchor, R.drawable.ic_fan, adjusting);
        stage.setMode(FanStage.MODE_FAN);
        applyFan(level, value, unit, false);
        place(anchor, adjusting);
    }

    public void updateFan(int level, String value, String unit) {
        applyFan(level, value, unit, true);
        reposition();
    }

    private void applyFan(int level, String value, String unit, boolean animate) {
        int dir = !animate || lastLevel == Integer.MIN_VALUE ? 0
                : Integer.compare(level, lastLevel);
        lastLevel = level;
        number.setColor(ModeManager.get().ink());
        number.setValue(value, unit, 0f);   // "档"贴基线
        icon.setColorFilter(ModeManager.get().accent());
        stage.setFan(level, dir);
        // 风扇跟着档位转:0 档停、读不到怠转(见 Motion#fanSpeed)
        spin.speedTo(Motion.fanSpeed(level));
    }

    // ---- 换 app(地图/音乐键长按滑动) ----

    /** 候选图标的边长,和高亮那一格放大后的边长。 */
    private static final int SLOT_ICON_DP = 34;
    private static final int SLOT_ICON_ON_DP = 44;

    /**
     * 长按地图/音乐键进入选择态:一排候选图标,当前那个放大点亮、名字显示在下面。
     *
     * 跟风量气泡显示 7 根柱子是同一个模型 —— 把所有选项一次摊开,滑到哪儿一目了然,
     * 不用记"我现在滑到第几个了"。
     */
    public void showSlot(View anchor, java.util.List<android.graphics.drawable.Drawable> icons,
                         java.util.List<String> names, int index) {
        host = anchor;
        if (exiting) { exiting = false; root.animate().cancel(); detach(); }

        top.setVisibility(View.GONE);
        stage.setVisibility(View.GONE);
        slotBox.setVisibility(View.VISIBLE);
        spin.cancel();

        slotStrip.removeAllViews();
        int gap = Launchers.dp(ctx, 4);
        for (int i = 0; i < icons.size(); i++) {
            ImageView iv = new ImageView(ctx);
            iv.setImageDrawable(icons.get(i));
            int s = Launchers.dp(ctx, SLOT_ICON_ON_DP);
            LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(s, s);
            lp2.setMarginStart(i == 0 ? 0 : gap);
            slotStrip.addView(iv, lp2);
        }
        applySlot(names, index);
        place(anchor, true);
    }

    /** 滑动中换了一格。只动高亮和名字,不重建那排图标。 */
    public void updateSlot(java.util.List<String> names, int index) {
        applySlot(names, index);
    }

    /**
     * 没被选中的缩小、压暗 —— 用大小和透明度区分,不画选框。
     *
     * 尺寸走 scale 而不是改 LayoutParams:改 lp 会触发整排重新测量布局,滑动中
     * 每格都来一次会顿;scale 只在绘制阶段缩放,而且能顺带做弹性动画。
     */
    private void applySlot(java.util.List<String> names, int index) {
        float shrink = (float) SLOT_ICON_DP / SLOT_ICON_ON_DP;
        for (int i = 0; i < slotStrip.getChildCount(); i++) {
            View iv = slotStrip.getChildAt(i);
            boolean on = i == index;
            iv.animate().cancel();
            iv.animate().scaleX(on ? 1f : shrink).scaleY(on ? 1f : shrink)
                    .alpha(on ? 1f : .45f).setDuration(140).start();
        }
        slotName.setTextColor(ModeManager.get().ink());
        slotName.setText(index >= 0 && index < names.size() ? names.get(index) : "");
    }

    /** 空调那两套内容回来时,把换 app 那排收掉。 */
    private void showHvacContent() {
        slotBox.setVisibility(View.GONE);
        top.setVisibility(View.VISIBLE);
        stage.setVisibility(View.VISIBLE);
    }

    // ---- 温度 ----

    public void showTemp(View anchor, float celsius, float progress, String value, String unit,
                         int color, boolean adjusting) {
        prepare(anchor, tempIcon(celsius), adjusting);
        stage.setMode(FanStage.MODE_TEMP);
        spin.cancel();
        icon.setRotation(0f);
        applyTemp(progress, value, unit, color);
        place(anchor, adjusting);
    }

    /** 内容换了就重新量一次、重新摆 —— 高度一变,底边和 Dock 的间距就跟着漂(见 reposition)。 */
    public void updateTemp(float celsius, float progress, String value, String unit, int color) {
        applyTemp(progress, value, unit, color);
        setIcon(tempIcon(celsius));
        reposition();
    }

    private void applyTemp(float progress, String value, String unit, int color) {
        number.setColor(color);
        number.setValue(value, unit, .42f);   // "°"是上标,抬到数字上部
        icon.setColorFilter(color);                // 温度计跟着冷暖变色,它本来就是温度的化身
        stage.setTemp(progress, color);
    }

    /** 温度图标。~~调节中跟着冷暖变~~ 现在恒定,见下。 */
    private int tempIcon(float v) {
        // 冷暖不再换字形(同 DockView.tempIconRes),恒定中性温度计
        return R.drawable.ic_temp;
    }

    // ---- 开合 ----

    private void prepare(View anchor, int iconRes, boolean adjusting) {
        host = anchor;
        showHvacContent();   // 上一次可能停在换 app 那套内容上
        // 正在退场时来了新的 show:直接收掉重开,别在半透明尸体上续画
        if (exiting) {
            exiting = false;
            root.animate().cancel();
            detach();
        }
        if (!attached) {                 // 上一次已经收干净了,方向从头算
            lastLevel = Integer.MIN_VALUE;
        }
        setIcon(iconRes);
    }

    private void setIcon(int res) {
        if (iconRes == res) return;
        iconRes = res;
        icon.setImageResource(res);
    }

    /**
     * 宿主是不是系统悬浮窗。同一条 Dock 会在悬浮窗和桌面内嵌之间来回切(没权限时退回内嵌),
     * 所以每次建窗前都得判一次,不能记住。
     */
    private boolean hostIsOverlay(View anchor) {
        ViewGroup.LayoutParams p = anchor.getRootView().getLayoutParams();
        return p instanceof WindowManager.LayoutParams
                && ((WindowManager.LayoutParams) p).type
                        >= WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW;
    }

    /**
     * 挂窗。**WindowManager 必须从宿主那个 Context 拿** —— token 是它给的,拿错了
     * WMS 会以 "token is not valid" 拒掉,而这个异常会把整个进程带走。
     *
     * 宿主在悬浮窗里就跟 DockHost 一样,优先用无障碍服务的 Context(层级能到 2032);
     * 宿主还在 Activity 里(没悬浮窗权限时的降级)就用自己的 ctx,类型退回子窗口。
     */
    private boolean attach(View anchor) {
        ensureDetached();
        try {
            Context winCtx = ctx;
            int type = WindowManager.LayoutParams.TYPE_APPLICATION_PANEL;
            if (hostIsOverlay(anchor)) {
                AccessibilityService svc = ForegroundWatch.get();
                winCtx = svc != null ? svc : ctx.getApplicationContext();
                type = Overlay.windowType(ForegroundWatch.running());
            }
            WindowManager w = (WindowManager) winCtx.getSystemService(Context.WINDOW_SERVICE);
            if (w == null) return false;
            lp.type = type;
            if (!Overlay.add(w, root, lp)) return false;
            wm = w;
            attached = true;
            return true;
        } catch (Exception e) {
            // 挂不上就安静收着:气泡是锦上添花,不能因为它调不了空调
            Log.w("Blank/HvacBubble", "气泡窗口挂不上: " + e);
            return false;
        }
    }

    private void detach() {
        if (!attached) return;
        attached = false;
        if (wm == null) return;
        try {
            wm.removeView(root);
        } catch (Exception ignored) {
            // 已经不在窗口上了(宿主被摘掉时会连带收走),忽略
        }
    }

    /**
     * 挂之前先确保这个 View 不在任何窗口上。
     *
     * **无障碍服务重启过之后必须这么做**:气泡是挂在那个 service 的 context 上的,
     * 服务一换,{@link #wm} 就成了旧的,{@link #detach} 里那次 removeView 会抛异常并被吞掉 ——
     * 于是 root 还记着"我被 add 过",下次再 add 就直接
     * 「View has already been added to the window manager」,气泡再也浮不出来,
     * 表现成"按住滑动没反应"(实测反复开关无障碍就会撞上)。
     *
     * 一个 View 同时只能属于一个 WindowManager,所以这里宁可多摘一次。
     */
    private void ensureDetached() {
        if (root.getParent() == null) {
            attached = false;
            return;
        }
        try {
            WindowManager w = wm != null
                    ? wm : (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (w != null) w.removeViewImmediate(root);
        } catch (Exception ignored) {
            // 旧窗口早没了,忽略 —— 目的只是把 root 的归属清干净
        }
        attached = false;
    }

    /** 量好尺寸摆到 anchor 正上方,尾尖对着它。 */
    /**
     * 内容变了之后重新量、重新摆。
     *
     * **为什么必须有**:窗口的 lp.y 是"顶边"坐标,而定位算的是"底边贴住 dockSpace 那条线"
     * (y = 屏高 − dockSpace − 高 + 尾尖)。气泡高度一变(比如读数从「A」的 22sp 换成
     * 「1」的 36sp),lp.y 不跟着重算的话底边就漂了 —— 表现成**气泡到 Dock 的间距忽大忽小**。
     */
    private void reposition() {
        if (attached && anchored != null) place(anchored, true);
    }

    private void place(View anchor, boolean adjusting) {
        anchored = anchor;
        root.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int w = root.getMeasuredWidth(), h = root.getMeasuredHeight();

        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        /*
         * 水平对准这颗键。**半宽要按当前缩放取**:长按进调节态那一刻按键还在按压缩放里
         * (pressOut 的弹簧刚起步,scale≈0.9),getLocationOnScreen 是过变换矩阵的 ——
         * 左缘随缩放向内收了 (1-scale)*宽/2,再加"未缩放的半宽"就把中心算偏了几个 px。
         * 首次滑动一步后 reposition 时键已弹回原大,坐标才对 —— 表现成气泡在第一格
         * 横着蹦一小下。缩放轴心在中心,「缩放后左缘 + 缩放后半宽」恒等于真中心。
         */
        lp.x = loc[0] + Math.round(anchor.getWidth() * anchor.getScaleX() / 2f) - w / 2;
        /*
         * 垂直**不跟着按键走**,而是和其他 Dock 浮层落在同一条线上
         * (见 {@link Launchers#dockSpace})——**面的下沿**对齐那条线,尾尖再从那儿伸下去
         * 点住按键。
         *
         * 之前是 `loc[1] - h - 4dp`(相对按键),底边比空调面板高出去 32dp:一个悬在半空、
         * 一个贴着 Dock,同样是从 Dock 弹出来的东西却看着是两套。
         */
        int screenH = ctx.getResources().getDisplayMetrics().heightPixels;
        lp.y = screenH - Launchers.dockSpace(ctx) - h + (int) root.tailHeight();

        if (attached) {
            wm.updateViewLayout(root, lp);
        } else {
            if (!attach(anchor)) return;
            // 从按键那头长出来:缩放锚在尾尖
            root.setPivotX(w / 2f);
            root.setPivotY(h);
            Motion.enter(root, Launchers.dp(ctx, 10));
        }

        anchor.removeCallbacks(hideTask);
        if (!adjusting) anchor.postDelayed(hideTask, 1300L);
    }

    /** 松手后让气泡再停一下,免得数值刚落定就闪没。 */
    public void hideAfter(long delayMs) {
        if (host == null) { dismiss(); return; }
        host.removeCallbacks(hideTask);
        host.postDelayed(hideTask, delayMs);
    }

    /** 退场动画后收窗。 */
    private void hide() {
        if (!attached || exiting) return;
        exiting = true;
        spin.cancel();
        Motion.exit(root, Launchers.dp(ctx, 6), () -> {
            exiting = false;
            detach();
        });
    }

    /** 立即收掉(Dock 离窗等场景),不走动画。 */
    public void dismiss() {
        if (host != null) host.removeCallbacks(hideTask);
        root.animate().cancel();
        spin.cancel();
        exiting = false;
        detach();
    }
}
