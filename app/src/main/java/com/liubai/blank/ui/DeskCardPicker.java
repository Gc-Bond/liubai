package com.liubai.blank.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.liubai.blank.data.DeskCardStore;
import com.liubai.blank.mode.Dim;
import com.liubai.blank.mode.ModeManager;
import com.liubai.blank.mode.Reskinnable;
import com.liubai.blank.model.CarCtrlItem;
import com.liubai.blank.util.Launchers;

import java.util.List;

/**
 * 桌面编辑态里的候选区:**还能往桌面上加什么**。
 *
 * 照 Dock 编辑态那套({@link DockEditPanel} 的「添加」区)搬过来的，差别在**不带遮罩**:
 * Dock 的编辑对象只有屏幕下缘那一条，遮罩留出那条就行；桌面的编辑对象是整片，
 * 遮上就没法拖卡片了。所以这里做成右侧一条竖栏 —— 横屏宽屏右边本来空着
 * (卡片默认排在左半屏，见 {@code HomeActivity.placeCards} 的一行四个)，
 * 而且**避开 Dock**:底边留出 {@link Launchers#dockSpace} 那条，不然被悬浮的 Dock 压住。
 *
 * 只列"还没摆上的"。已经在桌面上的项不出现在这儿 —— 候选区回答的是"还能加什么",
 * 不是"一共有什么" —— 已经摆上的那些,要动就在桌面上动(拖位置、× 移除)。
 */
public class DeskCardPicker extends FrameLayout implements Reskinnable {

    /** 竖栏宽度。放得下一个图标 + 四个中文字，再宽就开始吃桌面。 */
    private static final int W_DP = 168;

    public interface Listener {
        /** 点了一个车控项:把它加到桌面(落第一个空位)。 */
        void onPickCtrl(CarCtrlItem item);
        /** 点了一整种卡(时间/车况):把那种卡打开。 */
        void onPickKind(String cardId);

        /**
         * 往左拖出来了。宿主**当场就把真卡片挂到桌面上**并让它跟手 ——
         * 不做影子:影子要另画一份"卡片长什么样",拖到一半松手还得把影子换成真卡,
         * 中间那一下必然错位。直接拖真的,松手就已经在正确位置上了。
         *
         * @return 挂上去的那张卡的 id;宿主拒绝(比如已经在桌面上了)返回 null
         */
        String onCandidateDragStart(CarCtrlItem item, String kindId);
        /** 拖动中。屏幕坐标 —— 候选区和卡片各有各的坐标系,统一交给宿主换算。 */
        void onCandidateDragMove(String cardId, float rawX, float rawY);
        /**
         * 松手,落在手指那儿。
         *
         * 没有"落回候选区算取消"这条 —— 拖出来的瞬间这条竖栏就滑走让位了(它占着右侧,
         * 不让路的话卡片拖过去就被它盖住),既然让走了就没有"落回去"可言。想撤销点 ×。
         */
        void onCandidateDrop(String cardId, float rawX, float rawY);

        /**
         * 这一次拖放**作废了**(收到 CANCEL)。卡片留在手指最后到过的地方,
         * 宿主只需要收尾(落点虚框、候选区重铺)。
         *
         * 和 {@link #onCandidateDrop} 分开是必须的:CANCEL 的坐标是无意义的
         * (常常是 0,0),拿它当落点会把卡片扔到左上角。
         */
        void onCandidateDropCancelled(String cardId);
    }

    private Listener listener;
    private final LinearLayout list;
    private final LinearLayout sheet;
    private final TextView empty;
    /** 有一条正被拖出去。这期间不许重铺列表(见 {@link #refresh})。 */
    private boolean dragging;

    public DeskCardPicker(Context ctx) {
        super(ctx);
        setVisibility(GONE);

        sheet = new LinearLayout(ctx);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackground(sheetBg());
        sheet.setTag("panel");   // 面板不透明度,见 ModeApplier 的 panel 指令
        sheet.setElevation(Launchers.dp(ctx, 24));
        int ph = Launchers.dp(ctx, 12);
        sheet.setPadding(ph, Launchers.dp(ctx, 14), ph, Launchers.dp(ctx, 12));
        // 吃掉落在竖栏空白处的触摸,别漏到底下的桌面(漏下去会被当成"点空白退出编辑态")
        sheet.setOnTouchListener((v, e) -> true);

        TextView title = new TextView(ctx);
        title.setText("添加到桌面");
        title.setTextSize(14f);
        Launchers.medium(title);
        title.setTextColor(ModeManager.get().ink());
        sheet.addView(title);

        empty = new TextView(ctx);
        empty.setText("都摆上了");
        empty.setTextSize(12f);
        empty.setTextColor(ModeManager.get().muted());
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.topMargin = Launchers.dp(ctx, 10);
        sheet.addView(empty, elp);

        list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(ctx);
        sv.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams svlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        svlp.topMargin = Launchers.dp(ctx, 8);
        sheet.addView(sv, svlp);

        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                Launchers.dp(ctx, W_DP), ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.END | Gravity.TOP);
        // 底边让开 Dock:整条 Dock 是悬浮窗、盖在本窗口之上,伸到它下面的部分点不着
        slp.bottomMargin = Launchers.dockSpace(ctx);
        addView(sheet, slp);
    }

    public void setListener(Listener l) { this.listener = l; }

    public boolean isOpen() { return getVisibility() == VISIBLE; }

    /** 滑入。{@code onDesk} 是桌面上现有的车控项,它们不再出现在候选里。 */
    public void open(List<CarCtrlItem> onDesk, DeskCardStore store) {
        refresh(onDesk, store);
        if (getVisibility() == VISIBLE) return;
        setVisibility(VISIBLE);
        sheet.setTranslationX(Launchers.dp(getContext(), W_DP));
        sheet.animate().translationX(0f).setDuration(180L).start();
    }

    public void close() {
        if (getVisibility() != VISIBLE) return;
        sheet.animate().translationX(Launchers.dp(getContext(), W_DP))
                .setDuration(150L)
                .withEndAction(() -> setVisibility(GONE))
                .start();
    }

    /**
     * 重铺候选项。加完一项要调一次 —— 它得从候选里消失。
     *
     * **拖动期间一律不铺**:那一下会 {@code removeAllViews},把正被手指按着的那一条
     * 从树上摘掉,`onTouch` 里再拿 `v.getParent()` 就是 null 了(2026-08-17 平板上崩过)。
     * 松手时宿主会再调一次,那时铺才安全。
     */
    public void refresh(List<CarCtrlItem> onDesk, DeskCardStore store) {
        if (dragging) return;
        list.removeAllViews();
        Context ctx = getContext();
        int count = 0;

        // 整种卡(时间/车况):没开的列出来。车控那一整种不列 —— 它开着的时候
        // 逐项列在下面更有用,关着的时候也就是"车控"一条,由这条统一代表
        for (DeskCardStore.Kind k : DeskCardStore.kinds()) {
            if (store.visible(k.id)) continue;
            list.addView(row(0, k.title,
                    () -> { if (listener != null) listener.onPickKind(k.id); },
                    null, k.id), rowParams(ctx));
            count++;
        }
        // 车控项:整种开着的时候,把还没摆上的一项项列出来
        if (store.visible(DeskCardStore.CAR_CTRL)) {
            for (CarCtrlItem it : CarCtrlItem.values()) {
                if (onDesk != null && onDesk.contains(it)) continue;
                list.addView(row(it.icon(1), it.title,
                        () -> { if (listener != null) listener.onPickCtrl(it); },
                        it, null), rowParams(ctx));
                count++;
            }
        }
        empty.setVisibility(count == 0 ? VISIBLE : GONE);
    }

    private LinearLayout.LayoutParams rowParams(Context ctx) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Launchers.dp(ctx, 6);
        return lp;
    }

    /**
     * 一条候选。视觉照面板里的 chip(圆角 + 细描边 + tile 底),和 {@link DockEditPanel} 同源。
     *
     * 两种用法并存(同 Dock 的候选区):**点一下**加进第一个空位,**往左拖**摆到指定位置。
     */
    private View row(int iconRes, String title, Runnable onPick,
                     final CarCtrlItem item, final String kindId) {
        Context ctx = getContext();
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        int pv = Launchers.dp(ctx, 9), phz = Launchers.dp(ctx, 10);
        box.setPadding(phz, pv, phz, pv);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(ModeManager.get().dimPx(ctx, Dim.RADIUS_CHIP));
        bg.setColor(ModeManager.get().tile());
        bg.setStroke(Launchers.dp(ctx, 1), ModeManager.get().line());
        box.setBackground(bg);

        if (iconRes != 0) {
            ImageView icon = new ImageView(ctx);
            icon.setImageResource(iconRes);
            if (item != null) icon.setScaleX(item.mirrored() ? -1f : 1f);   // 副驾座椅翻过来
            icon.setColorFilter(ModeManager.get().glyph());
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                    Launchers.dp(ctx, 20), Launchers.dp(ctx, 20));
            ilp.setMarginEnd(Launchers.dp(ctx, 8));
            box.addView(icon, ilp);
        }

        TextView tv = new TextView(ctx);
        tv.setText(title);
        tv.setTextSize(13f);
        tv.setTextColor(ModeManager.get().ink());
        box.addView(tv);

        Motion.press(box);
        box.setOnClickListener(v -> onPick.run());

        // 往左拖 = 摆到指定位置。**只认往左**:这条竖栏是纵向滚动的,上下留给 ScrollView,
        // 不然想滚一下列表就把卡片拖出去了
        final int slop = android.view.ViewConfiguration.get(ctx).getScaledTouchSlop();
        box.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY;
            private String dragId;

            @Override public boolean onTouch(View v, android.view.MotionEvent e) {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        dragId = null;
                        return false;   // 不消费:点一下那条路还得走 OnClickListener
                    case android.view.MotionEvent.ACTION_MOVE:
                        if (dragId != null) {
                            if (listener != null) {
                                listener.onCandidateDragMove(dragId, e.getRawX(), e.getRawY());
                            }
                            return true;
                        }
                        float dx = downX - e.getRawX(), dy = Math.abs(e.getRawY() - downY);
                        if (dx > slop && dx > dy && listener != null) {
                            // **先置 dragging 再回调**:宿主在 onCandidateDragStart 里会挂卡片,
                            // 顺带刷新候选区 —— 那一下会把正被按着的这一条摘掉(getParent()=null)
                            dragging = true;
                            dragId = listener.onCandidateDragStart(item, kindId);
                            if (dragId != null) {
                                // 抢过手势:不然 ScrollView 会把后续 MOVE 收走当滚动。
                                // 判 null:这一条随时可能被别的路径从树上摘掉
                                if (v.getParent() != null) {
                                    v.getParent().requestDisallowInterceptTouchEvent(true);
                                }
                                // 点击已经不作数了,免得松手时又触发一次"加进空位"
                                v.setPressed(false);
                            } else {
                                dragging = false;
                            }
                        }
                        return dragId != null;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        if (dragId == null) return false;
                        // 先解除封锁,宿主在 onCandidateDrop 里会重铺列表(把刚摆上的那条去掉)
                        dragging = false;
                        if (listener != null) {
                            /*
                             * **CANCEL 的坐标不能当落点用**:它是"这次手势作废了"的通知,
                             * 系统发的那一发 raw 常常是 (0,0) —— 拿它去换算,卡片就被扔到
                             * 屏幕左上角,还会把原本占着那一格的卡一起交换下水
                             * (2026-08-18 日志实录:`落位换算 raw=(0,0)` 之后紧跟着
                             * `换位 cc_drl → (0,0),clock → (0,0)`)。
                             *
                             * 松手才算落位,和 DeskCard.onTouchEvent 里"CANCEL 不算用户
                             * 松的手"是同一条规矩。CANCEL 就让它停在手指最后到过的地方,
                             * 那一步已经由 onCandidateDragMove 铺好了。
                             */
                            if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                                listener.onCandidateDrop(dragId, e.getRawX(), e.getRawY());
                            } else {
                                listener.onCandidateDropCancelled(dragId);
                            }
                        }
                        dragId = null;
                        return true;
                    default:
                        return false;
                }
            }
        });
        return box;
    }


    private GradientDrawable sheetBg() {
        // 和 Dock 编辑面板 / 车控配置面板同源:顶浅底深的斜向渐变 + 细描边
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{ModeManager.get().tile2(), ModeManager.get().surface()});
        d.setCornerRadius(ModeManager.get().dimPx(getContext(), Dim.RADIUS_CARD));
        d.setStroke(Launchers.dp(getContext(), 1), ModeManager.get().line());
        d.setAlpha(ModeManager.get().panelAlpha());
        return d;
    }

    @Override public void reskin() {
        sheet.setBackground(sheetBg());
        // 候选项的颜色烧在各自的 drawable 里,重铺一遍最省事 —— 但重铺要有数据,
        // 没数据就等下一次 open()。宿主换肤后会重开
    }
}
