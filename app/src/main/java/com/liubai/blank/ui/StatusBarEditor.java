package com.liubai.blank.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.liubai.blank.data.StatusBarStore;
import com.liubai.blank.mode.ModeManager;
import com.liubai.blank.model.StatusItem;
import com.liubai.blank.util.Launchers;
import com.liubai.blank.util.ViewTx;

import java.util.ArrayList;
import java.util.List;

/**
 * 顶栏编排器:上面一条**预览**(按住就能拖着换顺序、× 拿掉),下面一格格**候选**
 * (按住拖到预览条上指定那一格,点一下加到末尾)。
 *
 * <h3>为什么是这个形状</h3>
 *
 * 照 DiStyle 的编辑页(反编译 v2.1.6 {@code CustomStatusBarFragment}:8 列
 * {@code GridLayoutManager} + {@code CustomStatusBarItemAdapter},项里还混着
 * "占位-小/中/大")。留白这边的手感统一到自己这套:**按下即拖 + 挂 ×**,
 * 和 Dock 编辑态({@code DockView.setEditing})、桌面卡片编辑是同一种语言。
 *
 * 早先这里做成过"每项点一下在 不显示→左边→右边 之间循环",是拍脑袋定的 ——
 * 用户当场问回来"这么 low 吗?你确定 DiStyle 这么做的?"。确实不是。
 *
 * <h3>为什么编排要在这儿做,而不是在真顶栏上</h3>
 *
 * 顶栏窗口带 {@code FLAG_NOT_TOUCHABLE}(它压在第三方 app 的顶部操作区上,吃了触摸
 * 就是把人家的按钮点死,见 {@link StatusBarHost} 的类注释)—— 真顶栏根本不接触摸,
 * 只能给一条**长得一样的预览**在这里拖。DiStyle 也是在设置页里编排的。
 */
public final class StatusBarEditor extends LinearLayout {

    /**
     * 顶栏重铺的 ViewTx 去重 token —— 拖动中连点几下只重铺最后那次。
     *
     * **一个实例一个,不能 static**:外观面板重建时新旧编辑器会短暂并存,
     * 共用一个 token 的话,旧的那次 {@code onDetachedFromWindow} 会把新的活儿一起撤了。
     */
    private final Object tokenRebuild = new Object();

    public interface Listener {
        /** 编排变了。宿主负责让真顶栏重铺。 */
        void onChanged();
    }

    /** 预览条:项按顺序排在这儿。 */
    private final LinearLayout strip;
    /** 候选区:还能加的项。 */
    private final LinearLayout pool;
    /** 候选区拖过来时的落点竖条。拖动中显示在预览条上,平时 GONE。 */
    private View dropInd;

    /** 候选区拖动时跟手的影子(画在窗口 root 上,见 beginPoolDrag)。 */
    private View dragGhost;
    /** 拖着的这一项。 */
    private StatusItem dragItem;

    private final StatusBarStore store;
    private Listener listener;

    public StatusBarEditor(Context ctx) {
        super(ctx);
        setOrientation(VERTICAL);
        store = new StatusBarStore(ctx);

        addView(label(ctx, "顶栏预览 · 按住拖着换顺序,× 拿掉"));

        /*
         * 预览条本身照顶栏的样子来(同一个 surface 底 + 同一条下边线的位置感),
         * 这样拖出来的效果就是顶栏上的效果 —— 编辑的对象看着不像成品,是最容易出错的地方。
         */
        /*
         * 预览条宽度 = 真实顶栏的可用宽度(屏幕宽 − 顶栏左右各 16dp padding),
         * 外面包一层横向滚动。这样"预览条满了"和"真实顶栏满了"是同一把尺子 ——
         * 面板内容区比屏幕窄一截,直接 MATCH_PARENT 的话,预览条看着塞满、真顶栏还空着
         * 一大段,编排的比例就对不上(2026-08-20 用户当场指出)。
         *
         * 超宽能横滑:撑开是弹性条,需要父容器宽度确定才能算剩余,所以 strip 宽度
         * 固定成屏幕宽而不是包内容;拖动排序在 DOWN 时已经 disallow 拦截,
         * 不会跟横向滚动打架。
         */
        HorizontalScrollView hscroll = new HorizontalScrollView(ctx);
        hscroll.setHorizontalScrollBarEnabled(false);
        hscroll.setVerticalScrollBarEnabled(false);

        strip = new LinearLayout(ctx);
        strip.setOrientation(HORIZONTAL);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Launchers.dp(ctx, 10);
        strip.setPadding(pad, pad, pad, pad);
        int statusW = ctx.getResources().getDisplayMetrics().widthPixels
                - Launchers.dp(ctx, 32);   // 真实顶栏 status_bar 的 16dp×2 padding
        strip.setLayoutParams(new FrameLayout.LayoutParams(
                statusW, FrameLayout.LayoutParams.WRAP_CONTENT));
        hscroll.addView(strip);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Launchers.dp(ctx, 10));
        bg.setColor(ModeManager.get().tile());
        bg.setStroke(Launchers.dp(ctx, 1), ModeManager.get().line());
        strip.setBackground(bg);
        LayoutParams slp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        slp.topMargin = Launchers.dp(ctx, 8);
        addView(hscroll, slp);

        addView(label(ctx, "还能加 · 按住拖到预览条上,点一下放到末尾"));
        pool = new LinearLayout(ctx);
        pool.setOrientation(VERTICAL);
        addView(pool);

        rebuild();
    }

    public void setListener(Listener l) { this.listener = l; }

    private TextView label(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(12f);
        tv.setTextColor(ModeManager.get().muted());
        LayoutParams lp = new LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.topMargin = Launchers.dp(ctx, 16);
        lp.leftMargin = Launchers.dp(ctx, 4);
        tv.setLayoutParams(lp);
        return tv;
    }

    /** 两块都按存档重铺。改完编排一律走这儿,不做增量 —— 项统共十几个。 */
    public void rebuild() {
        Context ctx = getContext();
        strip.removeAllViews();
        List<StatusItem> items = store.items();
        if (items.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("顶栏空着 · 从下面挑几项放上来");
            empty.setTextSize(12f);
            empty.setTextColor(ModeManager.get().muted());
            strip.addView(empty);
        }
        for (int i = 0; i < items.size(); i++) {
            strip.addView(chip(ctx, items.get(i), i));
        }
        // 落点竖条放最后(removeAllViews 已清空,这里重建):占的是"最后一个 chip 之后"
        // 的位,平时 GONE 不占空间,也不会把前面 chip 的位置挤动
        dropInd = new View(ctx);
        dropInd.setBackground(round(ctx, ModeManager.get().accent()));
        dropInd.setLayoutParams(new LinearLayout.LayoutParams(
                Launchers.dp(ctx, 3), Launchers.dp(ctx, 24)));
        dropInd.setVisibility(GONE);
        strip.addView(dropInd);

        pool.removeAllViews();
        LinearLayout row = null;
        int inRow = 0;
        for (StatusItem item : store.candidates()) {
            // 一行四个:项名最长的是"占位 · 撑开",四列在半屏面板里正好不折行
            if (row == null || inRow == 4) {
                row = new LinearLayout(ctx);
                row.setOrientation(HORIZONTAL);
                LayoutParams rlp = new LayoutParams(
                        LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
                rlp.topMargin = Launchers.dp(ctx, 8);
                pool.addView(row, rlp);
                inRow = 0;
            }
            row.addView(poolCell(ctx, item));
            inRow++;
        }
    }

    /**
     * 预览条上的占位项:和信息 chip 同构的一个圆角框,框内是**真实宽度的灰条 + 短名 + ×**。
     *
     * 宽度的语言靠灰条(16dp 就画 16dp 的条),名字只给个称呼(小/中/大/撑开),
     * × 和信息 chip 一样在框内右侧 —— 同一条预览上不能有两套交互语言。
     */
    private View spacerChip(Context ctx, final StatusItem item, final int index) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        int ph = Launchers.dp(ctx, 10), pv = Launchers.dp(ctx, 6);
        box.setPadding(ph, pv, ph, pv);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Launchers.dp(ctx, 8));
        // 占位在顶栏上是看不见的一段,框底保持透明,描边就够了
        bg.setColor(0x00000000);
        bg.setStroke(Launchers.dp(ctx, 1), ModeManager.get().line());
        box.setBackground(bg);

        box.addView(spacerBar(ctx, item, 14));

        TextView name = new TextView(ctx);
        name.setText(spacerName(item));
        name.setTextSize(11f);
        name.setTextColor(ModeManager.get().muted());
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nlp.setMarginStart(Launchers.dp(ctx, 6));
        box.addView(name, nlp);

        TextView del = new TextView(ctx);
        del.setText("×");
        del.setTextSize(14f);
        del.setTextColor(ModeManager.get().muted());
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dlp.setMarginStart(Launchers.dp(ctx, 8));
        box.addView(del, dlp);
        del.setOnClickListener(v -> {
            store.removeAt(index);
            apply();
        });

        // 撑开是弹性条:它自己吃满剩余宽度,所以外层也改成 weight=1
        LayoutParams lp = item == StatusItem.SPACER_FLEX
                ? new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                : new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(Launchers.dp(ctx, 6));
        box.setLayoutParams(lp);
        box.setOnTouchListener(new DragToReorder(index));
        return box;
    }

    /**
     * 占位条本体:固定几档宽度(16/48/120dp),撑开那档是弹性占满。
     * 透明底 + 描边 —— 它对应顶栏上一段看不见的空,不该画成实心。
     * 高度统一压扁(14dp):小档是 16×14 的扁横条,不会像 checkbox。
     */
    private View spacerBar(Context ctx, StatusItem item, int heightDp) {
        View bar = new View(ctx);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Launchers.dp(ctx, 4));
        bg.setColor(0x00000000);
        bg.setStroke(Launchers.dp(ctx, 1), ModeManager.get().line());
        bar.setBackground(bg);
        LinearLayout.LayoutParams lp = item == StatusItem.SPACER_FLEX
                ? new LinearLayout.LayoutParams(0, Launchers.dp(ctx, heightDp), 1f)
                : new LinearLayout.LayoutParams(
                        Launchers.dp(ctx, item.spacerDp()), Launchers.dp(ctx, heightDp));
        bar.setLayoutParams(lp);
        return bar;
    }

    /** 预览条上的一项。按住拖 = 换顺序,点 × = 拿掉。 */
    private View chip(Context ctx, final StatusItem item, final int index) {
        // 占位项走自己的样式(灰条 + 短名,见 spacerChip)
        if (item.isSpacer()) return spacerChip(ctx, item, index);

        final LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        int ph = Launchers.dp(ctx, 10), pv = Launchers.dp(ctx, 6);
        box.setPadding(ph, pv, ph, pv);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Launchers.dp(ctx, 8));
        bg.setColor(ModeManager.get().tile2());
        bg.setStroke(Launchers.dp(ctx, 1), ModeManager.get().accent());
        box.setBackground(bg);

        TextView tv = new TextView(ctx);
        tv.setText(item.title);
        tv.setTextSize(12f);
        tv.setTextColor(ModeManager.get().ink());
        box.addView(tv);

        TextView del = new TextView(ctx);
        del.setText("×");
        del.setTextSize(14f);
        del.setTextColor(ModeManager.get().muted());
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dlp.setMarginStart(Launchers.dp(ctx, 8));
        box.addView(del, dlp);
        del.setOnClickListener(v -> {
            store.removeAt(index);
            apply();
        });

        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(Launchers.dp(ctx, 6));
        box.setLayoutParams(lp);

        box.setOnTouchListener(new DragToReorder(index));
        return box;
    }

    /**
     * 按住拖着换顺序。
     *
     * **松手才落定**:拖动途中只把自己跟着手指平移,不实时交换 —— 实时交换要在每一帧
     * remove/add view,手指底下那个 view 会被换掉,拖动当场断掉(Dock 编辑早年踩过)。
     * 落点按"手指此刻在第几个 chip 的范围里"算。
     */
    private final class DragToReorder implements OnTouchListener {
        private final int index;
        private float downX;
        private boolean dragging;

        DragToReorder(int index) { this.index = index; }

        @Override public boolean onTouch(View v, MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getRawX();
                    dragging = false;
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = ev.getRawX() - downX;
                    if (!dragging) {
                        if (Math.abs(dx) < ViewConfiguration.get(getContext()).getScaledTouchSlop()) {
                            return true;
                        }
                        dragging = true;
                        v.setElevation(Launchers.dp(getContext(), 8));
                        v.setScaleX(1.06f);
                        v.setScaleY(1.06f);
                    }
                    v.setTranslationX(dx);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    v.setElevation(0f);
                    v.setScaleX(1f);
                    v.setScaleY(1f);
                    v.setTranslationX(0f);
                    if (dragging) {
                        int to = targetIndex(v, ev.getRawX());
                        if (to != index) {
                            store.move(index, to);
                            apply();
                        }
                    }
                    dragging = false;
                    return true;
                }
                default:
                    return false;
            }
        }

        /** 手指此刻落在第几格上。按每个 chip 的中心线判,和拖动时看到的位置一致。 */
        private int targetIndex(View self, float rawX) {
            int[] loc = new int[2];
            List<Integer> centers = new ArrayList<>();
            for (int i = 0; i < strip.getChildCount(); i++) {
                View c = strip.getChildAt(i);
                // 落点竖条和空态提示不参与排序判定
                if (c == dropInd || c.getVisibility() != View.VISIBLE) continue;
                c.getLocationOnScreen(loc);
                centers.add(loc[0] + c.getWidth() / 2);
            }
            int to = 0;
            for (int i = 0; i < centers.size(); i++) {
                if (rawX > centers.get(i)) to = i;
            }
            return Math.max(0, Math.min(centers.size() - 1, to));
        }
    }

    /**
     * 候选区一格:按住拖到预览条上指定那一格,点一下加到末尾。两条路并存。
     *
     * 拖那条照 {@link DockEditPanel} 候选区那套(按下超过 slop 进拖拽,没挪够就当
     * 点击,自己补 {@code performClick})。**不用系统拖放**(startDragAndDrop):
     * 面板是悬浮窗,系统拖放框架是为 Activity 窗口设计的,影子由 {@link #beginPoolDrag}
     * 自己画在窗口 root 上。
     */
    private View poolCell(Context ctx, final StatusItem item) {
        // 占位格直接画成真实宽度的灰条 + 短名,不写字面"占位·小"这种抽象词
        if (item.isSpacer()) return poolSpacerCell(ctx, item);

        TextView tv = new TextView(ctx);
        tv.setText(item.title);
        tv.setTextSize(12f);
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(ModeManager.get().ink());
        int pv = Launchers.dp(ctx, 10);
        tv.setPadding(pv, pv, pv, pv);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Launchers.dp(ctx, 8));
        bg.setColor(ModeManager.get().tile());
        bg.setStroke(Launchers.dp(ctx, 1), ModeManager.get().line());
        tv.setBackground(bg);
        attachPoolInteractions(tv, item);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginEnd(Launchers.dp(ctx, 6));
        tv.setLayoutParams(lp);
        return tv;
    }

    /** 候选区占位格:真实宽度的灰条 + 一行短名(小/中/大/撑开)。 */
    private View poolSpacerCell(Context ctx, final StatusItem item) {
        LinearLayout cell = new LinearLayout(ctx);
        cell.setOrientation(HORIZONTAL);
        cell.setGravity(Gravity.CENTER);
        int pad = Launchers.dp(ctx, 6);
        cell.setPadding(pad, Launchers.dp(ctx, 10), pad, Launchers.dp(ctx, 10));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Launchers.dp(ctx, 8));
        bg.setColor(ModeManager.get().tile());
        bg.setStroke(Launchers.dp(ctx, 1), ModeManager.get().line());
        cell.setBackground(bg);

        cell.addView(spacerBar(ctx, item, 14));

        TextView name = new TextView(ctx);
        name.setText(item == StatusItem.SPACER_FLEX ? "撑开" : spacerName(item));
        name.setTextSize(11f);
        name.setTextColor(ModeManager.get().muted());
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nlp.setMarginStart(Launchers.dp(ctx, 8));
        cell.addView(name, nlp);

        attachPoolInteractions(cell, item);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginEnd(Launchers.dp(ctx, 6));
        cell.setLayoutParams(lp);
        return cell;
    }

    /** 占位档的短名:灰条旁边那行小字,只给个称呼,形状才是主体。 */
    private String spacerName(StatusItem item) {
        switch (item) {
            case SPACER_S: return "小";
            case SPACER_M: return "中";
            case SPACER_L: return "大";
            default: return "撑开";
        }
    }

    /**
     * 候选格的手感:点一下加到末尾,按住拖到预览条上指定那一格。两条路并存。
     *
     * 拖那条照 {@link DockEditPanel} 候选区那套(按下超过 slop 进拖拽,没挪够就当
     * 点击,自己补 {@code performClick})。**不用系统拖放**(startDragAndDrop):
     * 面板是悬浮窗,系统拖放框架是为 Activity 窗口设计的,影子由 {@link #beginPoolDrag}
     * 自己画在窗口 root 上。
     */
    private void attachPoolInteractions(View cell, final StatusItem item) {
        cell.setClickable(true);
        cell.setOnClickListener(v -> {
            store.add(item);
            apply();
        });
        // 按压反馈自己管(原来 Motion.press 那条 OnTouchListener 会被下面这个覆盖)
        final int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        cell.setOnTouchListener(new OnTouchListener() {
            private float downX, downY;
            private boolean dragging;

            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX(); downY = e.getRawY(); dragging = false;
                        // 顶栏 tab 整页包在 ScrollView 里:不拦掉纵向拦截,手一抖列表先滚,
                        // 拖动就起不来。候选区统共两三行,本来也不需要滚
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                        Motion.pressIn(v);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!dragging
                                && (Math.abs(e.getRawX() - downX) > slop
                                 || Math.abs(e.getRawY() - downY) > slop)) {
                            dragging = true;
                            Motion.pressOut(v);
                            beginPoolDrag(item, e.getRawX(), e.getRawY());
                        }
                        if (dragging) movePoolDrag(e.getRawX(), e.getRawY());
                        return true;
                    case MotionEvent.ACTION_UP:
                        Motion.pressOut(v);
                        if (dragging) endPoolDrag(e.getRawX(), e.getRawY());
                        else v.performClick();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        Motion.pressOut(v);
                        if (dragging) cancelPoolDrag();
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    // ---- 候选区拖到预览条 ----

    /** 圆角纯色底,落点竖条这类小东西用。 */
    private GradientDrawable round(Context ctx, int color) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(Launchers.dp(ctx, 2));
        d.setColor(color);
        return d;
    }

    /**
     * 候选区按住拖出:在窗口 root 上画一个跟手影子(照
     * {@code DockHost.startCandidateDrag} 那套)。
     *
     * 影子画在 {@code getRootView()} 上而不是编辑器里:编辑器在 ScrollView 里,
     * 加在它自己身上会被滚动区裁剪;root 是整个面板窗口,加在上面的 view 不受
     * 滚动影响,还能用 elevation 盖住面板内容。
     */
    private void beginPoolDrag(StatusItem item, float rawX, float rawY) {
        View v = getRootView();
        if (!(v instanceof ViewGroup)) return;
        ViewGroup root = (ViewGroup) v;
        cancelPoolDrag();
        dragItem = item;
        // 占位项的影子就是灰条,信息项才是文字 chip —— 拖什么,影子就是什么
        View g;
        if (item.isSpacer()) {
            g = spacerBar(getContext(), item, 14);
        } else {
            g = new TextView(getContext());
            ((TextView) g).setText(item.title);
            ((TextView) g).setTextSize(12f);
            ((TextView) g).setGravity(Gravity.CENTER);
            ((TextView) g).setIncludeFontPadding(false);
            ((TextView) g).setTextColor(ModeManager.get().ink());
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(Launchers.dp(getContext(), 8));
            bg.setColor(ModeManager.get().tile2());
            bg.setStroke(Launchers.dp(getContext(), 1), ModeManager.get().accent());
            g.setBackground(bg);
            int ph = Launchers.dp(getContext(), 10), pv = Launchers.dp(getContext(), 6);
            g.setPadding(ph, pv, ph, pv);
        }
        g.setAlpha(.9f);
        g.setElevation(Launchers.dp(getContext(), 24));   // 压在面板内容之上
        // 先量出尺寸再定位:addView 完还没 measure 的话 getWidth 是 0,第一帧会偏半格
        int spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        g.measure(spec, spec);
        root.addView(g, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        dragGhost = g;
        movePoolDrag(rawX, rawY);
    }

    /** 拖动中:影子跟手,落点竖条跟着走。 */
    private void movePoolDrag(float rawX, float rawY) {
        if (dragGhost != null) {
            View root = getRootView();
            if (root != null) {
                int[] o = new int[2];
                root.getLocationOnScreen(o);
                dragGhost.setX(rawX - o[0] - dragGhost.getMeasuredWidth() / 2f);
                dragGhost.setY(rawY - o[1] - dragGhost.getMeasuredHeight() / 2f);
            }
        }
        moveDropInd(rawX, rawY);
    }

    /** 松手:落在预览条上就插进那一格,落在别处什么也不做。 */
    private void endPoolDrag(float rawX, float rawY) {
        StatusItem item = dragItem;
        int at = insertIndexAt(rawX, rawY);
        cancelPoolDrag();
        if (item == null || at < 0) return;
        store.addAt(at, item);
        apply();
    }

    /** 收掉影子和落点竖条。 */
    private void cancelPoolDrag() {
        View v = getRootView();
        ViewGroup root = v instanceof ViewGroup ? (ViewGroup) v : null;
        if (dragGhost != null && root != null) root.removeView(dragGhost);
        dragGhost = null;
        dragItem = null;
        if (dropInd != null) dropInd.setVisibility(GONE);
    }

    /**
     * 手指在预览条上时,落在第几个间隙(0 = 最前,n = 末尾)。不在预览条范围返回 -1。
     *
     * 判定和预览条排序共用一套:按每个 chip 的中心线分。上沿容差小、下沿容差大 ——
     * 候选区在预览条下面,手是往上拖的,别还没拖到就把落点收掉。
     */
    private int insertIndexAt(float rawX, float rawY) {
        int[] o = new int[2];
        strip.getLocationOnScreen(o);
        int top = o[1] - Launchers.dp(getContext(), 24);
        int bottom = o[1] + strip.getHeight() + Launchers.dp(getContext(), 48);
        if (rawY < top || rawY > bottom) return -1;
        int at = 0;
        for (int i = 0; i < strip.getChildCount(); i++) {
            View c = strip.getChildAt(i);
            if (c == dropInd || c.getVisibility() != View.VISIBLE) continue;
            int[] lo = new int[2];
            c.getLocationOnScreen(lo);
            if (rawX > lo[0] + c.getWidth() / 2f) at++;
        }
        return at;
    }

    /** 拖动中把落点竖条挪到第 {@code at} 格前面。 */
    private void moveDropInd(float rawX, float rawY) {
        int at = insertIndexAt(rawX, rawY);
        if (dropInd == null) return;
        if (at < 0) {
            dropInd.setVisibility(GONE);
            return;
        }
        dropInd.setVisibility(VISIBLE);
        float x;
        if (at == 0) {
            x = strip.getPaddingLeft();
        } else {
            View prev = visibleChildAt(strip, at - 1);
            x = prev == null ? strip.getPaddingLeft() : prev.getRight() + Launchers.dp(getContext(), 3);
        }
        dropInd.setX(x - Launchers.dp(getContext(), 1));
        dropInd.setElevation(Launchers.dp(getContext(), 8));
    }

    /** {@code parent} 里第 {@code index} 个可见的项(跳过落点竖条)。 */
    private View visibleChildAt(LinearLayout parent, int index) {
        int seen = 0;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View c = parent.getChildAt(i);
            if (c == dropInd || c.getVisibility() != View.VISIBLE) continue;
            if (seen == index) return c;
            seen++;
        }
        return null;
    }

    /**
     * 改完编排:重铺 + 通知宿主。**一律排到下一帧,不在当前这一趟事件里做**。
     *
     * 这是踩出来的:`rebuild()` 会 `removeAllViews()`,而调用它的三个地方(拖动松手、
     * 点 ×、点/拖候选格)全都还在**触摸事件分发的中途** —— 手指正按着的那个 chip 被连根拆掉,
     * 之后系统那趟 traversal 就崩在 {@code ViewGroup.dispatchCollectViewAttributes}
     * (`NullPointerException: mViewFlags`,子节点数组里出现了 null)。
     * 2026-08-18 在模拟器上稳定复现:顶栏预览条上拖一下,必崩。
     *
     * post 之后这一趟事件先走完,view 树在下一帧才动,系统那边看到的始终是完整的树。
     */
    private void apply() {
        ViewTx.post(this, tokenRebuild, () -> {
            rebuild();
            if (listener != null) listener.onChanged();
        });
    }

    /** 恢复默认编排。 */
    public void reset() {
        store.reset();
        apply();
    }

    /**
     * 面板收了就把还没跑的重铺撤掉。
     *
     * {@link ViewTx} 的 Handler 是静态的,排上去的 Runnable 捕获着这个编辑器 ——
     * 不撤的话,外观面板都关了,下一帧还要在一棵已经摘掉的树上重铺一遍。
     */
    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        ViewTx.cancel(tokenRebuild);
        cancelPoolDrag();
    }
}
