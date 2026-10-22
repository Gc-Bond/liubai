package com.liubai.blank.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.liubai.blank.R;
import com.liubai.blank.privileged.AirCon;
import com.liubai.blank.mode.Reskinnable;
import com.liubai.blank.mode.Dim;
import com.liubai.blank.mode.ModeManager;
import com.liubai.blank.util.Launchers;
import com.liubai.blank.util.Scrim;

/**
 * 空调全量面板:Dock 上放不下的低频项都收在这里(A/C、最大制冷、分控、后除雾、通风…),
 * 外加双温度卡和风量滑条。长按 Dock 的循环键唤出。
 *
 * 它自己贴到 android.R.id.content 上,不进 activity_home.xml —— 布局里不留一个
 * 平时看不见的层,Activity 也不用替它转发显示/隐藏。
 *
 * **写操作真车已通**(2026-07-30 实测):走 {@code BYDAutoManager} native bus 绕开了
 * IAirConditioningService 的签名权限墙,详见 {@link com.liubai.blank.privileged.AirCon} 类注释。
 * 空调开关、温度、风量、分控、循环、除雾、座椅加热/通风都能发出去且不反弹
 * (座椅那路 2026-08-02 修好档位映射后打通,见 {@code AirCon.seat()} 类注释)。
 *
 * (历史:这段注释一度写着"所有控制都发不出去" —— 那是 07-29 卡在 BYDAUTO_AC_SET 那天的
 * 状态,次日就被 native bus 方案解决了,注释没跟着改,后来误导过排查。)
 */
public class HvacPanel extends FrameLayout implements HvacSheet, Reskinnable {

    public interface Listener {
        /** 开关类:直接把 AirCon 的 transaction 编号发出去。 */
        void onHvacCommand(int code);
        /** 温度加减。deputy=true 是副驾;delta 以 **1℃** 为一格。 */
        void onHvacTemp(boolean deputy, int delta);
        /** 风量滑条松手:level 是**绝对档位** 0~7,不是增量;0 = 关空调。 */
        void onHvacFanLevel(int level);
        /** 座椅加热/通风升一档(0=关/1/2,**两档**到顶回 0)。heat=false 是通风。 */
        void onSeatLevel(boolean deputy, boolean heat);
        /** 面板打开,请求一次全量状态。 */
        void onHvacNeedState();
    }

    /**
     * 静态模式:控件一个不少,**只是不动**。模式不给动效或用户关了动效时走这条 ——
     * 温度刻度尺、座椅气流曲线、风量柱都照常显示,不跑的是冷暖粒子、扇叶转动、
     * 按压回弹、出入场和颜色渐变。
     *
     * 建的时候读一次就定死:模式切换会把整个面板丢掉重建(见 DockHost.dropHvacPanel),
     * 不需要中途变。
     */
    private final boolean still = ModeManager.get().hvacBasic();

    private Listener listener;

    private final View scrim;
    private final LinearLayout sheet;
    private final LinearLayout chipRow;
    private final TempCard cardL, cardR;
    private final SeatFlowView seat;
    private final TextView fanLabel;
    private final LinearLayout mid, fanRow;
    private final FanTrack fanTrack;
    private Motion.Spinner fanSpin;

    /** chip 的编号 → 按钮,apply 时按状态点亮。 */
    private final android.util.SparseArray<View> chips = new android.util.SparseArray<>();

    private AirCon.State state;
    private boolean open;

    public HvacPanel(Context ctx) {
        super(ctx);
        setVisibility(GONE);

        // ---- 遮罩 ----
        scrim = new View(ctx);
        scrim.setBackgroundColor(Scrim.HEAVY);
        scrim.setOnClickListener(v -> close());
        addView(scrim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // ---- 面板本体 ----
        sheet = new LinearLayout(ctx);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackground(sheetBg());
        // 面板不透明度由这个 tag 声明,换档后跟着 reskin 重刷(见 ModeApplier 的 panel 指令)。
        // DockHost 那个悬浮面板不在桌面 view 树里、刷不到,它靠 rebuild 时整个重建来吃新值
        sheet.setTag("panel");
        // 面板要"浮"在 scrim 之上,不是贴着 —— 一圈深投影把它和背景拉开层次
        sheet.setElevation(Launchers.dp(ctx, 24));
        int pad = Launchers.dp(ctx, 28);
        sheet.setPadding(pad, Launchers.dp(ctx, 24), pad, Launchers.dp(ctx, 28));
        // 吃掉落到面板空白处的触摸,免得穿透到遮罩把自己关了。
        // ViewGroup 的 OnTouchListener 只在子 view 都不消费时才轮到,所以不会挡住 chip 的点击
        sheet.setOnTouchListener((v, e) -> true);

        // 宽度按屏幕比例给,车机上手指粗、要盲操,面板和按钮都往大了做
        // (2026-08-02 .56→.64/720→820,08-16 再放到 .8/1040,09-12 分控进顶排成第 9 颗,再放到 .86/1100)。
        //
        // **宽度是被 chip 行顶出来的**:9 颗 chip 放大后一行要 ~1010dp,加左右内边距
        // 就是 ~1070dp。宽度不够时它会横向滚动,但那样两端的「开关」和「通风」永远
        // 是被切掉半个的样子 —— 一屏车机上没有理由让它滚。
        int screenW = ctx.getResources().getDisplayMetrics().widthPixels;
        LayoutParams slp = new LayoutParams(
                Math.min(Math.round(screenW * .86f), Launchers.dp(ctx, 1100)),
                LayoutParams.WRAP_CONTENT);
        slp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        slp.bottomMargin = Launchers.dockSpace(ctx);   // 所有 Dock 浮层共用这条线
        // 出入场缩放锚在底边中点:面板从 Dock 那头"长"出来,而不是原地放大。
        // 尺寸随内容变,挂在 layout 回调里持续对准(首次 open 时还没量过,不能现取)
        sheet.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            v.setPivotX((r - l) / 2f);
            v.setPivotY(b - t);
        });
        addView(sheet, slp);

        sheet.addView(header(ctx));

        // ---- chips ----
        chipRow = new LinearLayout(ctx);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setGravity(Gravity.CENTER_VERTICAL);
        addChip(ctx, AirCon.AC, 0, "开关");   // 纯文字,同「自动」——电源图标已删
        addChip(ctx, AirCon.AUTO, 0, "自动");
        // 分控(温度分区)原来是副驾卡标题旁的小徽标,2026-09-12 挪上来当第 9 颗:
        // 它和开关/自动一样是全局模式,不该藏在一张卡的角落里
        addChip(ctx, AirCon.AREA, 0, "分控");
        addChip(ctx, AirCon.COMPRESSOR, R.drawable.ic_snow, "A/C");
        addChip(ctx, AirCon.MAX, R.drawable.ic_max, "最大制冷");
        addChip(ctx, AirCon.DEFROST, R.drawable.ic_defrost, "前除雾");
        addChip(ctx, AirCon.DEFROST_R, R.drawable.ic_defrost_rear, "后除雾");
        addChip(ctx, AirCon.CYCLE, R.drawable.ic_recirculate, "内循环");
        addChip(ctx, AirCon.VENT, R.drawable.ic_wind, "通风");
        HorizontalScrollView chipScroll = new HorizontalScrollView(ctx);
        chipScroll.setHorizontalScrollBarEnabled(false);
        // 放得下时整行居中,和温度卡/滑条同轴;放不下时照常从左往右滚
        chipScroll.addView(chipRow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = Launchers.dp(ctx, 12);
        sheet.addView(chipScroll, clp);

        // ---- 中部:主驾 | (气流图位置) | 副驾 ----
        mid = new LinearLayout(ctx);
        mid.setOrientation(LinearLayout.HORIZONTAL);
        // 风向列比两张卡高一点,卡片居中对齐,别顶在上沿
        mid.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = Launchers.dp(ctx, 14);
        sheet.addView(mid, mlp);

        cardL = new TempCard(ctx, false);
        mid.addView(cardL.root, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        seat = new SeatFlowView(ctx);
        seat.setListener(which -> {
            if (listener == null) return;
            // 至少留一个吹风方向 —— 三路全关等于没在吹,车机本身也不接受
            if (isLastFlowOn(which)) return;
            listener.onHvacCommand(which == SeatFlowView.WIND ? AirCon.W_DEF
                    : which == SeatFlowView.FACE ? AirCon.W_FACE : AirCon.W_FOOT);
        });
        // 风向列定宽:字形是方的,按短边铺满,给它弹性列宽只会在两侧攒出死空白。
        // 定宽后,多出来的宽度全归两张温度卡。
        // 2026-09-12 从 200×168 放到 300×224:两张卡把宽度吃光、中间的风向反而最小,
        // 主次反了 —— 风向是这一行的主角,卡片让出 50dp 一边
        LinearLayout.LayoutParams seatLp = new LinearLayout.LayoutParams(
                Launchers.dp(ctx, 300), Launchers.dp(ctx, 224));
        seatLp.setMarginStart(Launchers.dp(ctx, 16));
        seatLp.setMarginEnd(Launchers.dp(ctx, 16));
        mid.addView(seat, seatLp);

        cardR = new TempCard(ctx, true);
        mid.addView(cardR.root, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // ---- 风量滑条 ----
        fanRow = new LinearLayout(ctx);
        fanRow.setOrientation(LinearLayout.HORIZONTAL);
        fanRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.topMargin = Launchers.dp(ctx, 16);
        sheet.addView(fanRow, flp);

        ImageView fanIc = new ImageView(ctx);
        fanIc.setImageResource(R.drawable.ic_fan);
        fanIc.setColorFilter(ModeManager.get().glyph());
        fanIc.setTag("tint:glyph");   // 换配色时由 ModeApplier 重刷
        fanRow.addView(fanIc, new LinearLayout.LayoutParams(
                Launchers.dp(ctx, 38), Launchers.dp(ctx, 38)));
        fanSpin = new Motion.Spinner(fanIc);

        fanTrack = new FanTrack(ctx);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                Launchers.dp(ctx, 34), 1f);
        tlp.setMarginStart(Launchers.dp(ctx, 16));
        tlp.setMarginEnd(Launchers.dp(ctx, 16));
        fanRow.addView(fanTrack, tlp);
        bindFanTrack();

        fanLabel = new TextView(ctx);
        fanLabel.setTextSize(16f);
        fanLabel.setTextColor(ModeManager.get().glyph());
        fanLabel.setTag("fg:glyph");
        fanLabel.setMinWidth(Launchers.dp(ctx, 48));
        fanLabel.setGravity(Gravity.END);
        fanRow.addView(fanLabel);
    }

    public void setListener(Listener l) { this.listener = l; }

    // ---- 组件构造 ----

    private GradientDrawable sheetBg() {
        // 顶浅底深的斜向渐变(原型 linear-gradient(165deg,…)):
        // 纯平一块 surface 是"廉价感"的主要来源,一点纵深就能把面变成体
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{ModeManager.get().tile2(), ModeManager.get().surface()});
        d.setCornerRadius(ModeManager.get().dimPx(getContext(), Dim.RADIUS_CARD));
        d.setStroke(Launchers.dp(getContext(), 1), ModeManager.get().line());
        // 构造这一次自己叠 —— 悬浮窗那个面板不在桌面 view 树里,等不到 ModeApplier
        d.setAlpha(ModeManager.get().panelAlpha());
        return d;
    }

    /**
     * 换配色后重铺这张 sheet。
     *
     * 它是**渐变**(tile2→surface),tag 里的 fill 只设得了单色、会把渐变压平,
     * 所以走 Reskinnable 整个重建 drawable。
     */
    @Override public void reskin() {
        sheet.setBackground(sheetBg());
    }

    private GradientDrawable round(int r, int color) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(r);
        d.setColor(color);
        return d;
    }

    /**
     * 带 1px 细描边的圆角块。原型里每个 chip/卡片都有一道
     * rgba(148,180,220,.14) 的边 —— 正是这道边把"色块"变成"面",
     * 少了它整个面板会塌成一片扁平的灰。
     */
    private GradientDrawable roundStroke(int r, int color, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(r);
        d.setColor(color);
        d.setStroke(Math.max(1, Launchers.dp(getContext(), 1)), stroke);
        return d;
    }

    /**
     * 点亮态的辉光。API 29 没有 backdrop-blur,原型那层玻璃模糊做不了;
     * 但彩色投影从 API 28 起可以 —— 用 accent 色的 spot shadow 近似
     * box-shadow: 0 0 20px cyglow 的发光感。
     */
    private void glow(View v, boolean on, int color) {
        v.setElevation(on ? Launchers.dp(getContext(), 6) : 0);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            v.setOutlineSpotShadowColor(color);
            v.setOutlineAmbientShadowColor(color);
        }
    }

    private View header(Context ctx) {
        // FrameLayout 而不是 LinearLayout:抓手条要钉在面板的几何中线上。
        // 两边 spacer 挤出来的"居中"会被左右内容的宽度差带偏 —— 左边是图标+标题,
        // 右边只有一个箭头,抓手就不在正中,整个面板第一眼就歪了
        FrameLayout h = new FrameLayout(ctx);

        LinearLayout left = new LinearLayout(ctx);
        left.setOrientation(LinearLayout.HORIZONTAL);
        left.setGravity(Gravity.CENTER_VERTICAL);

        ImageView glyph = new ImageView(ctx);
        glyph.setImageResource(R.drawable.ic_fan);
        glyph.setColorFilter(ModeManager.get().accent());
        left.addView(glyph, new LinearLayout.LayoutParams(
                Launchers.dp(ctx, 28), Launchers.dp(ctx, 28)));

        TextView title = new TextView(ctx);
        title.setText("空调");
        title.setTextSize(20f);
        Launchers.medium(title);
        title.setTextColor(ModeManager.get().muted());
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.setMarginStart(Launchers.dp(ctx, 8));
        left.addView(title, tlp);

        h.addView(left, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.CENTER_VERTICAL));

        // 抓手条:告诉手指"这是一张能收起来的抽屉",纯装饰但少了它就像块告示牌
        View grab = new View(ctx);
        grab.setBackground(round(Launchers.dp(ctx, 2), ModeManager.get().faint()));
        h.addView(grab, new FrameLayout.LayoutParams(
                Launchers.dp(ctx, 44), Launchers.dp(ctx, 4), Gravity.CENTER));

        ImageView close = new ImageView(ctx);
        close.setImageResource(R.drawable.ic_chevron_down);
        close.setColorFilter(ModeManager.get().glyph());
        close.setContentDescription("收起");
        int p = Launchers.dp(ctx, 6);
        close.setPadding(p, p, p, p);
        if (!still) Motion.press(close);
        close.setOnClickListener(v -> close());
        h.addView(close, new FrameLayout.LayoutParams(
                Launchers.dp(ctx, 30), Launchers.dp(ctx, 30),
                Gravity.END | Gravity.CENTER_VERTICAL));
        return h;
    }

    /** 一个开关 chip。icon 传 0 就只显示文字(AUTO 这种字母本身就是图标的,不硬凑)。 */
    private void addChip(Context ctx, final int code, int iconRes, String label) {
        LinearLayout chip = new LinearLayout(ctx);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setBackground(round(Launchers.dp(ctx, 999), ModeManager.get().tile2()));
        // 车机盲操,chip 热区往大了给(2026-08-02:11/9→16/13);一行放不下交给横向滚动
        int ph = Launchers.dp(ctx, 20), pv = Launchers.dp(ctx, 16);
        chip.setPadding(ph, pv, ph, pv);

        if (iconRes != 0) {
            ImageView iv = new ImageView(ctx);
            iv.setImageResource(iconRes);
            iv.setColorFilter(ModeManager.get().glyph());
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                    Launchers.dp(ctx, 24), Launchers.dp(ctx, 24));
            ilp.setMarginEnd(Launchers.dp(ctx, 10));
            chip.addView(iv, ilp);
        }
        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextSize(16f);
        tv.setTextColor(ModeManager.get().glyph());
        chip.addView(tv);

        if (!still) Motion.press(chip);
        chip.setOnClickListener(v -> {
            if (listener != null) listener.onHvacCommand(code);
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(Launchers.dp(ctx, 10));
        chipRow.addView(chip, lp);
        chips.put(code, chip);
    }

    /**
     * 温度卡:大数字 + 刻度尺。整卡可拖(左右滑改值),点左半/右半各减加一度 ——
     * 拖比点更快,点比拖更准,车上两种都要有。
     */
    private final class TempCard {
        /** 卡片本身会画冷暖粒子(背景之后、内容之前),被卡片圆角裁住。 */
        final ThermalFx root;
        final LinearLayout tempZone;   // 温度区(标签/大数字/刻度尺),关机时只压暗这一层
        final TextView num, unit;
        final boolean deputy;
        final LinearLayout seatRow;
        final SeatCluster seatHeat, seatVent;
        private float syncDim = 1f, powerDim = 1f;

        TempCard(Context ctx, boolean deputy) {
            this.deputy = deputy;
            // 卡片自己就是粒子层:制冷雪花下落、制热暖点上升。
            // 密度要收着:默认的 11 片全尺寸铺在大数字和刻度尺底下,读起来是一层脏点
            // (ThermalFx 自己就写着"小容器要少而小" —— 信息密的容器同理)。
            // 少而小,气氛还在,信息让路
            root = new ThermalFx(ctx);
            root.setDensity(4, .75f);
            root.setBackground(roundStroke(Launchers.dp(ctx, 20),
                    ModeManager.get().tile2(), ModeManager.get().line()));
            root.setClipToOutline(true);    // 粒子不许飘出卡片圆角

            LinearLayout content = new LinearLayout(ctx);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setGravity(Gravity.CENTER_HORIZONTAL);
            int pv = Launchers.dp(ctx, 14);
            content.setPadding(0, pv, 0, pv);
            root.addView(content, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));

            // 温度区单独一层:关机时只压暗它。座椅加热是座椅上的电热丝,
            // 不归空调电源管 —— 所以座椅行不在这层里
            tempZone = new LinearLayout(ctx);
            tempZone.setOrientation(LinearLayout.VERTICAL);
            tempZone.setGravity(Gravity.CENTER_HORIZONTAL);
            content.addView(tempZone, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout labRow = new LinearLayout(ctx);
            labRow.setOrientation(LinearLayout.HORIZONTAL);
            labRow.setGravity(Gravity.CENTER_VERTICAL);
            // 显式 WRAP:竖向 LinearLayout 的 addView 默认给 MATCH_PARENT,
            // 标签行会被拉满整卡贴到左边,水平居中就失效了(原型里标签是居中的)
            tempZone.addView(labRow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView lab = new TextView(ctx);
            lab.setText(deputy ? "副驾" : "主驾");
            lab.setTextSize(16f);
            lab.setLetterSpacing(0.15f);
            lab.setTextColor(ModeManager.get().faint());
            labRow.addView(lab);

            // 分控开关 2026-09-12 起在顶排 chips 里(AirCon.AREA),副驾卡不再长徽标;
            // 这里只留"同步时压暗温度区"的表现(见 setSynced)
            if (deputy) setSynced(true);

            LinearLayout big = new LinearLayout(ctx);
            big.setOrientation(LinearLayout.HORIZONTAL);
            big.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.topMargin = Launchers.dp(ctx, 8);
            tempZone.addView(big, blp);

            // 数字两侧的 ± 暗提示:整卡"点左减点右加"是隐藏交互,这两个淡符号是它唯一的线索
            big.addView(hintIcon(ctx, R.drawable.ic_minus, 0, 6));

            num = new TextView(ctx);
            num.setTextSize(48f);
            // 极细字重:原型是 font-weight:200,大号数字用默认字重会显得又胖又闷
            num.setTypeface(android.graphics.Typeface.create("sans-serif-light",
                    android.graphics.Typeface.NORMAL));
            num.setTextColor(ModeManager.get().ink());
            num.setText("--");
            big.addView(num);

            unit = new TextView(ctx);
            unit.setText("°C");
            unit.setTextSize(16f);
            unit.setTextColor(ModeManager.get().faint());
            LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ulp.setMarginStart(Launchers.dp(ctx, 4));
            big.addView(unit, ulp);

            big.addView(hintIcon(ctx, R.drawable.ic_plus, 6, 0));

            // ---- 座椅行:加热 | 通风,读不到档位时整行隐藏(选配) ----
            seatRow = new LinearLayout(ctx);
            seatRow.setOrientation(LinearLayout.HORIZONTAL);
            seatRow.setGravity(Gravity.CENTER_VERTICAL);
            seatHeat = new SeatCluster(ctx, deputy, true);
            seatVent = new SeatCluster(ctx, deputy, false);
            seatHeat.other = seatVent;
            seatVent.other = seatHeat;
            seatRow.addView(seatHeat.box);
            LinearLayout.LayoutParams vlp2 = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            vlp2.setMarginStart(Launchers.dp(ctx, 16));
            seatRow.addView(seatVent.box, vlp2);
            seatRow.setVisibility(GONE);
            LinearLayout.LayoutParams srlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            srlp.topMargin = Launchers.dp(ctx, 10);
            srlp.gravity = Gravity.CENTER_HORIZONTAL;
            content.addView(seatRow, srlp);

            bindTempCard(root, deputy);
        }

        void setThermal(int mode) {
            root.setMode(still ? ThermalFx.NONE : mode);
        }

        /** 座椅加热/通风档位回填。两路都是 -1(没装配/通道未通)就整行藏起,不画死按钮。 */
        void applySeat(int heatLv, int ventLv) {
            seatHeat.set(heatLv);
            seatVent.set(ventLv);
            seatRow.setVisibility(heatLv < 0 && ventLv < 0 ? GONE : VISIBLE);
        }

        void apply(String v) {
            float f = parse(v);
            CharSequence before = num.getText();
            if (f < 0) {
                num.setText("--");
                unit.setVisibility(GONE);
                return;
            }
            // 16/33 是行程两端,车机上显示 LO/HI 而不是数字
            if (f <= 16f)      { num.setText("LO"); unit.setVisibility(GONE); }
            else if (f >= 33f) { num.setText("HI"); unit.setVisibility(GONE); }
            else {
                num.setText(f == Math.rint(f) ? String.valueOf((int) f) : String.valueOf(f));
                unit.setVisibility(VISIBLE);
            }
            // 读数一律墨色,不按冷暖变色(同 DockView.tempColor):数字本身就说明了冷热
            num.setTextColor(ModeManager.get().ink());
            if (!still && !num.getText().toString().contentEquals(before)) Motion.pop(num);
        }

        /**
         * 分控状态上屏:同步=温度区压暗(这半边不独立);分控=全亮。开关本身在顶排 chips
         * (AirCon.AREA)。只压温度区不压整卡 —— 座椅加热不跟分控走。主驾卡没有同步一说,不响应。
         */
        void setSynced(boolean synced) {
            if (!deputy) return;
            syncDim = synced ? .55f : 1f;
            applyDim();
        }

        /** 关机压暗只作用于温度区,座椅行保持亮度和可点。 */
        void setPowerDim(float alpha) {
            powerDim = alpha;
            applyDim();
        }

        /** 同步和关机是两个独立的压暗因子,取更暗的那个 —— 各自 animate 会互相覆盖。 */
        private void applyDim() {
            tempZone.animate().alpha(Math.min(syncDim, powerDim)).setDuration(280).start();
        }
    }

    /**
     * 一组座椅控件:图标 + 三格档位条,点击循环 0→1→2→3→0。
     * 点按循环而不是滑条:3 档的量级,连点几下比对着滑条瞄准快,盲操也数得清。
     * 加热亮暖色、通风亮青色 —— 和面板"冷暖=颜色"的既有语义同一套。
     */
    private final class SeatCluster {
        final LinearLayout box;
        final ImageView icon;
        final StepBars bars;
        final boolean deputy, heat;
        SeatCluster other;      // 同座的另一路,互斥:椅子不能又吹又烤
        int level = -1;

        SeatCluster(Context ctx, boolean deputy, boolean heat) {
            this.deputy = deputy;
            this.heat = heat;
            box = new LinearLayout(ctx);
            box.setOrientation(LinearLayout.HORIZONTAL);
            box.setGravity(Gravity.CENTER_VERTICAL);
            box.setPadding(Launchers.dp(ctx, 12), Launchers.dp(ctx, 10),
                    Launchers.dp(ctx, 12), Launchers.dp(ctx, 10));
            box.setContentDescription((deputy ? "副驾" : "主驾") + (heat ? "座椅加热" : "座椅通风"));

            icon = new ImageView(ctx);
            // 38dp:和 Dock 上那两颗座椅键一个量级(48 格 - 2*4 内边距 = 40)。
            // 原来 27dp,在放大后的面板里小得像个脚注
            box.addView(icon, new LinearLayout.LayoutParams(
                    Launchers.dp(ctx, 38), Launchers.dp(ctx, 38)));

            bars = new StepBars(ctx, 2);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    Launchers.dp(ctx, 27), Launchers.dp(ctx, 21));
            blp.setMarginStart(Launchers.dp(ctx, 8));
            box.addView(bars, blp);

            if (!still) Motion.press(box);
            box.setOnClickListener(v -> {
                if (level < 0) return;
                level = (level + 1) % 3;            // 真车只有 2 档(0=关,1/2=档位),乐观升档,回读校正
                if (level > 0 && other != null && other.level > 0) {
                    other.level = 0;
                    other.render();
                }
                render();
                if (!still) Motion.pop(bars);
                if (listener != null) listener.onSeatLevel(deputy, heat);
            });
        }

        void set(int lv) {
            level = lv;
            render();
        }

        void render() {
            box.setVisibility(level < 0 ? GONE : VISIBLE);
            if (level < 0) return;
            int accent = ModeManager.get().accent();   // 加热/通风靠字形分,不靠颜色分
            // 字形固定,开启态只换颜色(见《图标源头分析》第七节)
            icon.setImageResource(heat ? R.drawable.ic_seat_heat : R.drawable.ic_seat_vent);
            icon.setScaleX(deputy ? -1f : 1f);   // 副驾座椅朝向要翻过来,同 Dock
            icon.setColorFilter(level > 0 ? accent : ModeManager.get().glyph());
            bars.set(level, accent);
        }
    }

    // 座椅档位条搬去了 StepBars —— Dock、面板、车控卡片显示的是同一个读数,不该画三份

    /**
     * 温度卡数字两侧的 ± 图标。它们是「点左半减、右半加」唯一的操作暗示,得让人一眼
     * 看见 —— 曾经给到 15dp + faint 色 + 55% 透明度,克制过头,存在感几乎为零,
     * 用户根本不知道这卡能点。现在 24dp + muted 全不透明:清楚,但仍比大数字轻一档,
     * 不抢读数的戏。
     */
    private ImageView hintIcon(Context ctx, int res, int marginStartDp, int marginEndDp) {
        ImageView iv = new ImageView(ctx);
        iv.setImageResource(res);
        iv.setColorFilter(ModeManager.get().glyph());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                Launchers.dp(ctx, 24), Launchers.dp(ctx, 24));
        lp.setMarginStart(Launchers.dp(ctx, marginStartDp));
        lp.setMarginEnd(Launchers.dp(ctx, marginEndDp));
        iv.setLayoutParams(lp);
        return iv;
    }

    /**
     * 拖动改温度:每 34dp 一格(1℃);没拖动就按点在左半还是右半各减加一格。
     *
     * 拖动过程中只本地预览(直接改卡片显示,不经过 listener),松手时才按累计的
     * 总格数发一次真实控制指令——空调写操作现在是真实生效的车控指令(native bus),
     * 拖一下发几十次会把单线程的车控通道排队堵住,感觉"半天才有反应"。
     */
    private void bindTempCard(final View card, final boolean deputy) {
        final int stepPx = Launchers.dp(getContext(), 34);
        card.setOnTouchListener(new OnTouchListener() {
            float startX;
            float startTemp = Float.NaN;
            int sent;
            boolean moved;

            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = e.getRawX();
                        sent = 0;
                        moved = false;
                        startTemp = state != null
                                ? parse(deputy && state.tempR != null ? state.tempR : state.temp)
                                : Float.NaN;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        int want = Math.round((e.getRawX() - startX) / stepPx);
                        if (want != 0) moved = true;
                        if (want != sent) {
                            sent = want;
                            if (!Float.isNaN(startTemp)) {
                                float preview = Math.max(17f, Math.min(33f, startTemp + want));
                                String previewStr = preview == Math.rint(preview)
                                        ? String.valueOf((int) preview) : String.valueOf(preview);
                                (deputy ? cardR : cardL).apply(previewStr);
                            }
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        if (!moved) {
                            boolean right = e.getX() > card.getWidth() / 2f;
                            // **点击也要当场跟手**:原来只有拖动那条分支有本地预览,
                            // 点一下得等车机回读才变,车上看着像没反应
                            if (!Float.isNaN(startTemp)) {
                                float preview = Math.max(17f, Math.min(33f,
                                        startTemp + (right ? 1f : -1f)));
                                (deputy ? cardR : cardL).apply(preview == Math.rint(preview)
                                        ? String.valueOf((int) preview) : String.valueOf(preview));
                            }
                            if (listener != null) {
                                listener.onHvacTemp(deputy, right ? 1 : -1);
                            }
                        } else if (sent != 0 && listener != null) {
                            listener.onHvacTemp(deputy, sent);
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                }
                return false;
            }
        });
    }

    /**
     * 风量:**拖动是相对调节**,和温度卡 / Dock 风量键同一个映射 —— 拖动的距离决定
     * 变化量(36dp 一档),按在轨道哪儿无所谓。原来是绝对定位(按到哪
     * 落到哪档):按下瞬间就落档,颠簸中手指在轨道上滑移多少、档位就错多少 ——
     * 位置误差被原样翻译成数值误差,灵敏度还被「轨道长度 ÷ 8 档」锁死。
     *
     * 点一下(没拖起来)仍按点的位置直接落档:轨道摆在那儿就是 8 个并排的目标,
     * 一下从 1 到 7 不该要拖 200 多 dp。但**松手才算数** —— 擦碰一下轨道不再当场落档,
     * 擦碰变成滑移就落进相对调节,最多错一两档,不会飞到手指停的地方。
     *
     * 增量按当前预览档累计并在 0~7 饱和(同 applyAdjust):拖过头再往回拉立刻响应,
     * 不用先把过头的那段距离拖回来。
     *
     * 只本地预览(setLevel 直改旋钮,不经过 listener)、松手才发一次真实控制指令,
     * 理由同 {@link #bindTempCard}。发的仍是**最终档位本身**而不是增量 ——
     * 走增量的话车机实时值一旦跟基准对不上就落错档(见 AirCon#fanTo)。
     */
    private void bindFanTrack() {
        final int stepPx = Launchers.dp(getContext(), 36);
        fanTrack.setOnTouchListener(new OnTouchListener() {
            float startX;
            int sent;            // 这一趟已经消费掉的格数(增量口径)
            int base = -1;       // 手势起点的档位:相对调节的基准,也用来判有没有真改动
            int cur = -1;        // 当前预览档
            boolean moved;

            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = e.getRawX();
                        sent = 0;
                        moved = false;
                        base = state != null && state.fan >= 0 ? state.fan : -1;
                        cur = base;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        int want = Math.round((e.getRawX() - startX) / stepPx);
                        if (want == sent) return true;
                        int delta = want - sent;
                        sent = want;
                        moved = true;
                        if (cur < 0) return true;   // 没读到当前档,相对调节没有基准
                        int target = Math.max(0, Math.min(7, cur + delta));
                        if (target != cur) {
                            cur = target;
                            fanTrack.setLevel(cur, false);
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        if (!moved) {
                            // 点选:开环一步到位,不需要基准
                            int cell = fanTrack.cellAt(e.getX());
                            if (cell > 0) {
                                cur = cell;               // 1~7 档;关空调走「开关」chip
                                fanTrack.setLevel(cur, false);
                            }
                        }
                        if (cur >= 0 && cur != base && listener != null) {
                            listener.onHvacFanLevel(cur);
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                }
                return false;
            }
        });
    }

    // ---- 开合 ----

    public boolean isOpen() { return open; }

    public void open() {
        if (open) return;
        open = true;
        setVisibility(VISIBLE);
        if (still) {
            scrim.setAlpha(1f);
            sheet.setAlpha(1f);
            sheet.setScaleX(1f);
            sheet.setScaleY(1f);
            sheet.setTranslationY(0f);
        } else {
            scrim.setAlpha(0f);
            scrim.animate().alpha(1f).setDuration(220).start();
            Motion.enter(sheet, Launchers.dp(getContext(), 36));
            cardL.root.start();   // 上次收起时停掉了,重新开面板要接着跑
            cardR.root.start();
        }
        if (listener != null) listener.onHvacNeedState();
    }

    public void close() {
        if (!open) return;
        open = false;
        fanSpin.speedTo(0f);   // 面板藏起来就别再空转烧帧
        cardL.root.stop();
        cardR.root.stop();
        if (still) {
            setVisibility(GONE);
            if (onClosed != null) onClosed.run();
            return;
        }
        scrim.animate().alpha(0f).setDuration(180).start();
        // 重新打开会打断退场,而被打断的 withEndAction 也会执行——所以藏之前再确认没在开
        Motion.exit(sheet, Launchers.dp(getContext(), 24), () -> {
            if (!open) {
                setVisibility(GONE);
                if (onClosed != null) onClosed.run();
            }
        });
    }

    /**
     * 面板真正退场后的回调。
     *
     * 面板挂在悬浮窗里时(见 DockHost),光把这个 view 设成 GONE 是不够的 —— 窗口的
     * LayoutParams 还是 MATCH_PARENT,窗口照样全屏、照样吃触摸,结果就是面板关了
     * 之后整块屏幕都点不动。宿主要靠这个回调把窗口摘掉。
     */
    public void setOnClosed(Runnable r) { this.onClosed = r; }

    private Runnable onClosed;

    // ---- 状态回填 ----

    public void apply(AirCon.State s) {
        if (s == null) return;
        state = s;

        setChipOn(AirCon.AC, s.power);
        setChipOn(AirCon.AUTO, s.auto);
        setChipOn(AirCon.AREA, s.dual);
        setChipOn(AirCon.COMPRESSOR, s.compressor);
        setChipOn(AirCon.MAX, s.max);
        setChipOn(AirCon.DEFROST, s.defrost);
        setChipOn(AirCon.DEFROST_R, s.defrostR);
        setChipOn(AirCon.CYCLE, s.cycle);
        setChipOn(AirCon.VENT, s.vent);

        cardL.apply(s.temp);
        // 没开分控时副驾跟着主驾走,读不到副驾就显示主驾的值
        cardR.apply(s.tempR != null ? s.tempR : s.temp);
        cardR.setSynced(!s.dual);
        cardL.applySeat(s.heatL, s.ventL);
        cardR.applySeat(s.heatR, s.ventR);

        // 冷暖粒子:看**压缩机**而不是设定温度 —— 夏天设 26 度照样在制冷,
        // 拿设定温度猜冷热是错的
        cardL.setThermal(thermalMode(s, parse(s.temp)));
        cardR.setThermal(thermalMode(s, parse(s.tempR != null ? s.tempR : s.temp)));

        // 风向字形走强调色,和 Dock 上那颗同色 —— 冷暖只留给粒子那层
        seat.setState(s.power && s.windDef, s.power && s.face, s.power && s.foot,
                ModeManager.get().accent());

        // 关机一律按 0 档呈现(滑条回最左、写"关"、风扇停),不管车机还记着几档 ——
        // 滑到 0 发的就是关机指令,回读得让它看起来确实到了 0
        int fan = s.power ? s.fan : 0;
        fanLabel.setText(fan < 0 ? "--" : fan == 0 ? "关" : (s.auto ? "自动" : fan + " 档"));
        fanSpin.speedTo(still ? 0f : Motion.fanSpeed(fan));
        fanTrack.setLevel(fan, true);

        // 关机压暗:只压空调自己的部分(温度区/气流图/风量条),仍可操作不拦 ——
        // ensureOn 语义下拖一下就自动开机。座椅行不压:电热丝不归空调电源管
        float dim = s.power ? 1f : .32f;
        cardL.setPowerDim(dim);
        cardR.setPowerDim(dim);
        seat.animate().alpha(dim).setDuration(300).start();
        fanRow.animate().alpha(dim).setDuration(300).start();

        // 关机态:除了电源开关,其余压暗并挡住点击
        boolean on = s.power;
        for (int i = 0; i < chips.size(); i++) {
            int code = chips.keyAt(i);
            View chip = chips.valueAt(i);
            boolean live = on || code == AirCon.AC;
            chip.setAlpha(live ? 1f : .32f);
            chip.setEnabled(live);
        }
    }

    /**
     * 现在到底在制冷还是制热。
     *
     * **看压缩机,不看设定温度**:A/C 压缩机在转就是在制冷,夏天设 26 度也一样;
     * 拿设定温度当代理会得出"26 度不制冷"这种离谱结论。
     *
     * 制热没有直接信号(纯电靠 PTC,车机没暴露状态),用"压缩机关着 + 设定偏高"
     * 近似 —— 压缩机不转、又要 26 度以上,基本就是在加热。
     * 两者都不成立时只是在送风,不放粒子。
     */
    private int thermalMode(AirCon.State s, float temp) {
        if (!s.power || temp < 0) return ThermalFx.NONE;
        if (s.compressor) return ThermalFx.COOL;
        if (temp >= 26f) return ThermalFx.HEAT;
        return ThermalFx.NONE;
    }

    /**
     * 温度冷暖对应的气流色。读不到时用常温那档,不瞎报冷热。
     *
     * 常温给 {@link ThermalPalette#COOL_SOFT} 而不是 ink —— 气流线是**图形**,
     * 它的职责是表意;温度**文字**那边同一档给的是 ink(要读得清)。两种取舍不同,
     * 别看着不一样就统一掉。
     */
    private int flowColor(float v) {
        return ThermalPalette.of(v, ThermalPalette.COOL_SOFT);
    }

    /** 这一路是不是最后一个还开着的方向。 */
    private boolean isLastFlowOn(int which) {
        if (state == null) return false;
        int on = (state.windDef ? 1 : 0) + (state.face ? 1 : 0) + (state.foot ? 1 : 0);
        if (on != 1) return false;
        return which == SeatFlowView.WIND ? state.windDef
                : which == SeatFlowView.FACE ? state.face : state.foot;
    }

    private float parse(String v) {
        if (v == null || v.isEmpty()) return -1f;
        try {
            return Float.parseFloat(v);
        } catch (NumberFormatException e) {
            return -1f;
        }
    }

    /**
     * 风量条:自绘轨道 + 渐变填充 + 7 个档位点 + 发光旋钮。
     * 单 View 全量自绘,档位变化只 invalidate 不触发 layout;
     * 位置动画用原型同款的过冲曲线(cubic-bezier(.3,1.15,.4,1)),旋钮"弹"到位。
     */
    /**
     * 风量:**七格,亮几格就是几档**。和 Dock 上那条(DockView.FanBars)同一个画法 ——
     * 同一个读数在两处该长一个样。
     *
     * 换掉的是「滑轨 + 旋钮」:旋钮位置表达的是"多少",表达不了"第几档" ——
     * 中间那几档全靠估旋钮离哪个刻度点近,车上瞟一眼根本读不出来。
     *
     * 0 档(关)= 一格不亮。**点**只落在 1~7 上,想关空调走上面那颗「开关」chip；
     * **滑**照旧是相对增量,能一路滑到 0(见 bindFanTrack)——
     * 短按误关整车停止吹风的代价太大,不给它一个一点就中的落点。
     */
    private final class FanTrack extends View {
        static final int CELLS = 7;

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int level = -1;          // -1 = 还没读到
        private LinearGradient grad;
        private int gradW;

        FanTrack(Context c) { super(c); }

        void setLevel(int fan, boolean animate) {
            if (fan == level) return;
            level = fan;
            invalidate();
        }

        /** x 落在第几格(1~CELLS)。给 bindFanTrack 用。 */
        int cellAt(float x) {
            float w = getWidth();
            if (w <= 0) return -1;
            int i = (int) (Math.max(0f, Math.min(.999f, x / w)) * CELLS);
            return i + 1;
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            if (w <= 0) return;
            float gap = Launchers.dp(getContext(), 6);
            float bw = (w - gap * (CELLS - 1)) / CELLS;
            float r = Math.min(h, bw) / 2f;

            if (grad == null || gradW != (int) w) {
                gradW = (int) w;
                // 左低右高:档位越高颜色越实,和"风越大"同向
                grad = new LinearGradient(0, 0, w, 0,
                        ModeManager.get().accentSoft(), ModeManager.get().accent(),
                        Shader.TileMode.CLAMP);
            }
            for (int i = 0; i < CELLS; i++) {
                float x = i * (bw + gap);
                boolean on = level > 0 && i < level;
                if (on) {
                    paint.setShader(grad);
                } else {
                    paint.setShader(null);
                    // 读不到档位(-1)时整排压暗,不瞎宣布"零档"
                    paint.setColor(ModeManager.get().tile2());
                }
                c.drawRoundRect(x, 0, x + bw, h, r, r, paint);
                paint.setShader(null);
            }
        }
    }

    private void setChipOn(int code, boolean on) {
        View chip = chips.get(code);
        if (chip == null) return;
        int accent = ModeManager.get().accent();
        // 亮起:accentSoft 底 + accent 半透明描边 + 辉光;熄灭:tile2 底 + 常规细边
        chip.setBackground(roundStroke(Launchers.dp(getContext(), 999),
                on ? ModeManager.get().accentSoft() : ModeManager.get().tile2(),
                on ? (accent & 0x00FFFFFF) | 0x80000000 : ModeManager.get().line()));
        glow(chip, on, accent);
        int fg = on ? accent : ModeManager.get().glyph();
        LinearLayout box = (LinearLayout) chip;
        for (int i = 0; i < box.getChildCount(); i++) {
            View c = box.getChildAt(i);
            if (c instanceof TextView) ((TextView) c).setTextColor(fg);
            else if (c instanceof ImageView) ((ImageView) c).setColorFilter(fg);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        fanSpin.cancel();
    }
}
