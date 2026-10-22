package com.liubai.blank.ui;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Typeface;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.card.MaterialCardView;
import com.liubai.blank.R;
import com.liubai.blank.icon.IconEngine;
import com.liubai.blank.model.AppRef;
import com.liubai.blank.model.DeskItem;
import com.liubai.blank.model.Folder;
import com.liubai.blank.mode.ModeApplier;
import com.liubai.blank.mode.ModeManager;
import com.liubai.blank.util.Launchers;

import java.util.ArrayList;
import java.util.List;

/**
 * 桌面网格:列数按可用宽度自适应,app 占 1×1、大文件夹占 2×2,自己做占位排布(复刻原型的 dense 填空)。
 * 承载点击启动、小文件夹弹层、大 / 小切换、编辑抖动。
 */
public class DesktopView extends GridLayout {

    public interface Listener {
        void onLaunch(AppRef app);
        void onOpenFolder(int index, Folder folder);
        void onLayoutChanged();
        void onAppMenu(int index, AppRef app);  // 长按 app 开菜单(应用信息/移除/卸载,非编辑态)
        void onRemoveApp(int index);   // 编辑态点删除角标,移出桌面(不卸载)
        void onAddApp();               // 编辑态点 + 磁贴,打开添加
    }

    /**
     * 列数随可用宽度伸缩(音乐卡拉宽 → 网格变窄 → 列数减少)。
     *
     * 按「每列目标宽」反算而不是写死档位映射:任意宽度都能得出合理列数,
     * 且格子尺寸稳定在目标值附近——图标不会越挤越小,只是少几列。
     */
    private static final int COL_TARGET_DP = 120;
    private static final int MAX_COLS = 6;
    private static final int MIN_COLS = 2;   // 大文件夹占 2×2,再少就放不下了

    private IconEngine icons;
    private Listener listener;
    private List<DeskItem> items = new ArrayList<>();
    private boolean editing;
    private int lastWidth = 0;
    private int cols = MAX_COLS;
    /** 拉伸拖拽期间挂起重建:build() 是全量 removeAllViews + 重建所有子视图,每帧跑会卡死。 */
    private boolean rebuildSuspended;
    private final List<ObjectAnimator> wobblers = new ArrayList<>();

    public DesktopView(Context c) { super(c); setup(); }
    public DesktopView(Context c, AttributeSet a) { super(c, a); setup(); }

    private void setup() {
        setColumnCount(cols);
        setUseDefaultMargins(false);
    }

    private int colsFor(int width) {
        int target = Launchers.dp(getContext(), COL_TARGET_DP);
        if (target <= 0) return MAX_COLS;
        return Math.max(MIN_COLS, Math.min(MAX_COLS, width / target));
    }

    /**
     * 拖拽拉伸期间冻结整个网格,松手后恢复并按最终宽度重排一次。
     *
     * 光挂起重建还不够——那只挡住了 build(),挡不住布局。磁贴宽度是固定 px,
     * 容器被卡片连续挤窄时 GridLayout 每帧都会按新的可用宽度重排一次
     * (列放不下就往下挤),60fps 连续重排,看起来就是图标在抖。
     * 所以这里把宽度也一并冻结住:拖拽期间网格完全不参与布局,右侧超出的部分
     * 由外层 ScrollView 裁掉,图标纹丝不动;松手后恢复 match_parent 再重排一次。
     */
    public void setRebuildSuspended(boolean on) {
        if (rebuildSuspended == on) return;
        rebuildSuspended = on;

        ViewGroup.LayoutParams lp = getLayoutParams();
        if (on) {
            if (lp != null && getWidth() > 0) {
                lp.width = getWidth();      // 定死在拖拽前的宽度
                setLayoutParams(lp);
            }
        } else {
            if (lp != null) {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                setLayoutParams(lp);
            }
            scheduleRebuild();
        }
    }

    /**
     * 按最终宽度重排一次。
     *
     * 必须合并:松手后布局会连抖好几次(实测 671→666→575 三帧,只有最后一个是稳定值),
     * 每次都全量重建就是连闪三下。移除上一个待办再排新的,加一点延迟等布局落定,
     * 最终只重建一次。
     */
    private void scheduleRebuild() {
        removeCallbacks(rebuildTask);
        postDelayed(rebuildTask, 60);
    }

    private final Runnable rebuildTask = new Runnable() {
        @Override public void run() {
            if (rebuildSuspended || icons == null) return;
            int w = targetWidth();
            // 宽度没真变就别动:拖出去又拖回原位、或布局抖完回到原值时,一次多余的全量重建就是一下白闪
            if (w > 0 && w != lastWidth) build(w, false);
        }
    };

    /**
     * 我们是 match_parent,父容器宽度才是最终要撑满的目标。
     * 不能只看自身 getWidth():刚解冻、布局还没跑完时它仍是冻结前的旧值,
     * 会被上面那句判成「宽度没变」而整个跳过重排。
     */
    private int targetWidth() {
        if (getParent() instanceof View) {
            int pw = ((View) getParent()).getWidth();
            if (pw > 0) return pw;
        }
        return getWidth();
    }

    public void init(IconEngine icons, Listener listener) {
        this.icons = icons;
        this.listener = listener;
    }

    public void bind(List<DeskItem> items) {
        this.items = items != null ? items : new ArrayList<DeskItem>();
        requestBuild();
    }

    /** 空 GridLayout 在 ScrollView 里自身测不出宽度,故用父容器宽度兜底,并 post 到布局后再渲染。 */
    private void requestBuild() {
        if (icons == null) return;
        int w = usableWidth();
        if (w > 0) { build(w, true); return; }
        post(new Runnable() {
            int tries = 0;
            @Override public void run() {
                int ww = usableWidth();
                if (ww > 0) build(ww, true);
                else if (tries++ < 5) post(this);
            }
        });
    }

    private int usableWidth() {
        int w = getWidth();
        if (w <= 0 && getParent() instanceof View) w = ((View) getParent()).getWidth();
        return w;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        // 处理宽度变化(旋转/分屏/卡片拉伸)重排,也兜住初次渲染失败的情况。
        // 拖拽期间不重建,由 setRebuildSuspended(false) 在松手后统一做一次。
        // 纯尺寸重排不淡入——拖一次闪一次,视觉上像在抽搐。
        //
        // 这里不能再加 lastWidth<=0 就跳过的短路:整块网格被第二张卡顶掉时它是 GONE 的,
        // requestBuild 那会儿父子宽度都是 0、重试几次也拿不到,于是一次都没渲染成功
        // (lastWidth 停在 0)。等卡片关掉、网格重新可见拿到真实宽度时,正是靠这一次
        // onSizeChanged 来补渲染——短路掉的话网格就永远是空的。
        if (rebuildSuspended) return;
        if (w <= 0 || w == lastWidth || icons == null) return;

        // 必须排到布局流程之外,不能在这里直接 build:build 会 setColumnCount,
        // 它内部 invalidateStructure + requestLayout,而布局期间的 requestLayout 会被系统
        // 推迟到下一帧,结果新加的磁贴一个都没被测量、整片网格宽度对但完全不可见。
        scheduleRebuild();
    }

    public void setEditing(boolean on) {
        if (editing == on) return;
        editing = on;
        // 重建以显示/隐藏删除角标与 + 磁贴;build 内按 editing 起停抖动
        if (lastWidth > 0) build(lastWidth, true); else requestBuild();
    }

    public boolean isEditing() { return editing; }

    // ---- 构建 ----

    private int col(int resId) {
        ModeManager tm = ModeManager.get();
        // 桌面卡片不跟面板底色走(见 ModeManager.deskTile),所以不是 tm.tile()
        if (resId == R.color.tile)   return tm.deskTile();
        if (resId == R.color.accent) return tm.accent();
        if (resId == R.color.ink)    return tm.ink();
        if (resId == R.color.muted)  return tm.muted();
        return getContext().getColor(resId);
    }

    /** 留白磁贴卡片:圆角 + 抬升色 + 微阴影,clickable 时带水波纹。 */
    private MaterialCardView tileCard(int radiusDp, boolean clickable) {
        MaterialCardView c = new MaterialCardView(getContext());
        c.setRadius(Launchers.dp(getContext(), radiusDp));
        c.setCardBackgroundColor(col(R.color.tile));
        c.setCardElevation(Launchers.dp(getContext(), 1));
        c.setStrokeWidth(0);
        if (clickable) {
            c.setClickable(true);
            c.setFocusable(true);
            c.setRippleColor(android.content.res.ColorStateList.valueOf(0x33FFFFFF));
        }
        return c;
    }

    /** 给一个视图套上模式水波纹前景(用于文件夹内格等矩形可点区)。 */
    private void setRipple(View v) {
        android.util.TypedValue tv = new android.util.TypedValue();
        getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        if (tv.resourceId != 0) v.setForeground(getContext().getDrawable(tv.resourceId));
        v.setClickable(true);
        v.setFocusable(true);
    }

    /** 无界(圆形扩散)水波纹,用于浮空图标的按压反馈——无灰色方块。 */
    private void rippleBorderless(View v) {
        android.util.TypedValue tv = new android.util.TypedValue();
        getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
        if (tv.resourceId != 0) v.setForeground(getContext().getDrawable(tv.resourceId));
        v.setClickable(true);
        v.setFocusable(true);
    }

    /** @param fade 内容变化才淡入;纯尺寸重排(拉伸/旋转)不淡入,否则每次都闪一下 */
    private void build(int total, boolean fade) {
        stopWobble();
        removeAllViews();
        if (total <= 0) return;
        lastWidth = total;

        // 只在列数真的变了才设:setColumnCount 会 invalidateStructure + requestLayout,白设一次是白折腾
        int newCols = colsFor(total);
        if (newCols != cols || getColumnCount() != newCols) {
            cols = newCols;
            setColumnCount(cols);
        }

        int gap = Launchers.dp(getContext(), 10);
        int cellW = total / cols - gap;
        if (cellW <= 0) return;
        int labelH = Launchers.dp(getContext(), 22);
        int rowH = cellW + Launchers.dp(getContext(), 4) + labelH;
        int iconSize = Math.round(cellW * 0.56f);

        List<boolean[]> occ = new ArrayList<>();
        int[] slot = new int[2];

        for (int i = 0; i < items.size(); i++) {
            DeskItem it = items.get(i);
            int span = it.isLarge() ? 2 : 1;
            findSlot(occ, span, span, slot);
            int r = slot[0], c = slot[1];

            View view;
            if (it.kind == DeskItem.Kind.APP) {
                view = appTile(i, it.app, cellW, iconSize);
            } else if (it.folder.big) {
                view = bigFolder(i, it.folder, cellW, gap, iconSize);
            } else {
                view = smallFolder(i, it.folder, cellW, iconSize);
            }

            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                    GridLayout.spec(r, span), GridLayout.spec(c, span));
            lp.width = cellW * span + gap * (span - 1);
            lp.height = rowH * span + gap * (span - 1);
            lp.setMargins(gap / 2, gap / 2, gap / 2, gap / 2);
            addView(view, lp);
        }

        if (editing) addPlusTile(occ, slot, cellW, rowH, iconSize, gap);

        // 收次级 shape 尾:文件夹缩略格 / 角标背景按 tag 随模式上色(A4)
        ModeApplier.apply(this);

        if (editing) startWobble();
        if (fade) {
            setAlpha(0f);
            animate().alpha(1f).setDuration(220).start();
        } else {
            animate().cancel();
            setAlpha(1f);
        }
    }

    // 占位:找到能放下 w×h 块的最上-最左空位
    private void findSlot(List<boolean[]> occ, int w, int h, int[] out) {
        for (int r = 0; ; r++) {
            ensureRows(occ, r + h);
            for (int c = 0; c + w <= cols; c++) {
                if (free(occ, r, c, w, h)) {
                    for (int rr = r; rr < r + h; rr++)
                        for (int cc = c; cc < c + w; cc++) occ.get(rr)[cc] = true;
                    out[0] = r; out[1] = c;
                    return;
                }
            }
        }
    }

    private void ensureRows(List<boolean[]> occ, int need) {
        while (occ.size() < need) occ.add(new boolean[cols]);
    }

    private boolean free(List<boolean[]> occ, int r, int c, int w, int h) {
        for (int rr = r; rr < r + h; rr++)
            for (int cc = c; cc < c + w; cc++)
                if (occ.get(rr)[cc]) return false;
        return true;
    }

    // ---- 视图:app 磁贴 ----

    private View appTile(final int index, final AppRef app, int cellW, int iconSize) {
        // app 图标不套灰色磁贴,直接浮在黑底 + 名字(轻、留白);文件夹才保留卡片感。
        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);

        ImageView icon = new ImageView(getContext());
        icon.setImageDrawable(icons.iconFor(app));
        box.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));

        box.addView(label(app.label, cellW));

        rippleBorderless(box);
        box.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { if (listener != null) listener.onLaunch(app); }
        });
        // 自己起计时器判定长按,不用系统 setOnLongClickListener:
        // 系统的长按判定(View.onTouchEvent 内部)只要 ACTION_MOVE 偏出按下点约 8dp 就会自动取消,
        // 真手指按住不可能零漂移,导致长按一直被系统自己取消(与父容器 ScrollView 拦截无关,
        // 那一层已经用 requestDisallowInterceptTouchEvent 解决)。改成不检查位移的手动计时器。
        final boolean[] longPressFired = {false};
        final Runnable fireLongPress = new Runnable() {
            @Override public void run() {
                longPressFired[0] = true;
                if (listener != null && !editing) listener.onAppMenu(index, app);
            }
        };
        box.setOnTouchListener(new OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        longPressFired[0] = false;
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                        v.postDelayed(fireLongPress, android.view.ViewConfiguration.getLongPressTimeout());
                        break;
                    case MotionEvent.ACTION_UP:
                        v.getParent().requestDisallowInterceptTouchEvent(false);
                        v.removeCallbacks(fireLongPress);
                        if (longPressFired[0]) return true;   // 已触发长按,吞掉这次抬起,别再触发点击
                        break;
                    case MotionEvent.ACTION_CANCEL:
                        v.getParent().requestDisallowInterceptTouchEvent(false);
                        v.removeCallbacks(fireLongPress);
                        break;
                }
                return false;
            }
        });

        if (!editing) return box;

        // 编辑态:包一层 FrameLayout,左上角挂删除角标
        FrameLayout wrap = new FrameLayout(getContext());
        wrap.addView(box, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        addRemoveBadge(wrap, index);
        return wrap;
    }

    // ---- 视图:小文件夹(2×2 缩略) ----

    private View smallFolder(final int index, final Folder folder, int cellW, int iconSize) {
        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);

        MaterialCardView glyph = tileCard(18, true);
        box.addView(glyph, new LinearLayout.LayoutParams(cellW, cellW));

        // 2×2 缩略
        int mini = Math.round(cellW * 0.27f);
        int mg = Math.round(cellW * 0.07f);
        LinearLayout matrix = new LinearLayout(getContext());
        matrix.setOrientation(LinearLayout.VERTICAL);
        for (int r = 0; r < 2; r++) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int cc = 0; cc < 2; cc++) {
                int idx = r * 2 + cc;
                FrameLayout m = new FrameLayout(getContext());
                m.setBackgroundResource(R.drawable.mini_cell_bg);
                m.setTag("fill:tile2");
                LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(mini, mini);
                mlp.setMargins(mg / 2, mg / 2, mg / 2, mg / 2);
                if (idx < folder.apps.size()) {
                    ImageView mi = new ImageView(getContext());
                    mi.setImageDrawable(icons.iconFor(folder.apps.get(idx)));
                    int ms = Math.round(mini * 0.6f);
                    FrameLayout.LayoutParams milp = new FrameLayout.LayoutParams(ms, ms);
                    milp.gravity = Gravity.CENTER;
                    m.addView(mi, milp);
                }
                row.addView(m, mlp);
            }
            matrix.addView(row);
        }
        FrameLayout.LayoutParams matp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        matp.gravity = Gravity.CENTER;
        glyph.addView(matrix, matp);

        addResizeChip(glyph, index, false);

        box.addView(label(folder.name, cellW));
        glyph.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { if (listener != null) listener.onOpenFolder(index, folder); }
        });
        return box;
    }

    // ---- 视图:大文件夹(2×2,内部直点) ----

    private View bigFolder(final int index, final Folder folder, int cellW, int gap, int iconSize) {
        MaterialCardView card = new MaterialCardView(getContext());
        card.setRadius(Launchers.dp(getContext(), 22));
        card.setCardBackgroundColor(col(R.color.tile));
        card.setCardElevation(Launchers.dp(getContext(), 2));
        card.setStrokeWidth(0);
        int pad = Launchers.dp(getContext(), 14);
        card.setContentPadding(pad, pad, pad, pad);

        LinearLayout inner = new LinearLayout(getContext());
        inner.setOrientation(LinearLayout.VERTICAL);
        card.addView(inner, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 头部:圆点 + 名字 + 数量
        LinearLayout head = new LinearLayout(getContext());
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(getContext());
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        gd.setColor(col(R.color.accent));
        dot.setBackground(gd);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                Launchers.dp(getContext(), 6), Launchers.dp(getContext(), 6));
        dlp.setMarginEnd(Launchers.dp(getContext(), 7));
        head.addView(dot, dlp);
        TextView name = new TextView(getContext());
        name.setText(folder.name);
        name.setTextColor(col(R.color.ink));
        name.setTextSize(14f);
        head.addView(name);
        TextView cnt = new TextView(getContext());
        cnt.setText(String.valueOf(folder.apps.size()));
        cnt.setTextColor(col(R.color.muted));
        cnt.setTextSize(12f);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMarginStart(Launchers.dp(getContext(), 7));
        head.addView(cnt, clp);
        inner.addView(head);

        // 内部 3×2 应用格
        GridLayout g = new GridLayout(getContext());
        g.setColumnCount(3);
        g.setRowCount(2);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        glp.topMargin = Launchers.dp(getContext(), 8);
        inner.addView(g, glp);

        int show = Math.min(6, folder.apps.size());
        int mgSize = Math.round(cellW * 0.42f);
        for (int k = 0; k < show; k++) {
            final AppRef a = folder.apps.get(k);
            LinearLayout cellBox = new LinearLayout(getContext());
            cellBox.setOrientation(LinearLayout.VERTICAL);
            cellBox.setGravity(Gravity.CENTER);

            FrameLayout mg = new FrameLayout(getContext());
            mg.setBackgroundResource(R.drawable.mini_cell_bg);
            mg.setTag("fill:tile2");
            ImageView mi = new ImageView(getContext());
            mi.setImageDrawable(icons.iconFor(a));
            int ms = Math.round(mgSize * 0.56f);
            FrameLayout.LayoutParams milp = new FrameLayout.LayoutParams(ms, ms);
            milp.gravity = Gravity.CENTER;
            mg.addView(mi, milp);
            cellBox.addView(mg, new LinearLayout.LayoutParams(mgSize, mgSize));

            TextView ml = new TextView(getContext());
            ml.setText(a.label);
            ml.setTextColor(col(R.color.muted));
            ml.setTextSize(10f);
            ml.setSingleLine(true);
            ml.setEllipsize(android.text.TextUtils.TruncateAt.END);
            ml.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams mllp = new LinearLayout.LayoutParams(mgSize, ViewGroup.LayoutParams.WRAP_CONTENT);
            mllp.topMargin = Launchers.dp(getContext(), 4);
            cellBox.addView(ml, mllp);

            GridLayout.LayoutParams cl = new GridLayout.LayoutParams(
                    GridLayout.spec(k / 3, 1f), GridLayout.spec(k % 3, 1f));
            cl.width = 0; cl.height = 0;
            setRipple(cellBox);
            cellBox.setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) { if (listener != null) listener.onLaunch(a); }
            });
            g.addView(cellBox, cl);
        }

        addResizeChip(card, index, true);
        return card;
    }

    // ---- 大小切换角标 ----

    private void addResizeChip(FrameLayout parent, final int index, boolean big) {
        FrameLayout chip = new FrameLayout(getContext());
        chip.setBackgroundResource(R.drawable.resize_chip);
        chip.setTag("fill:tile2;stroke:line");
        ImageView g = new ImageView(getContext());
        g.setImageResource(big ? R.drawable.ic_collapse : R.drawable.ic_expand);
        g.setImageTintList(android.content.res.ColorStateList.valueOf(col(R.color.muted)));
        int gs = Launchers.dp(getContext(), 14);
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(gs, gs);
        glp.gravity = Gravity.CENTER;
        chip.addView(g, glp);

        int cs = Launchers.dp(getContext(), 26);
        int m = Launchers.dp(getContext(), 6);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(cs, cs);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.setMargins(0, m, m, 0);
        chip.setLayoutParams(lp);
        chip.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { toggleSize(index); }
        });
        parent.addView(chip);
    }

    /** 编辑态删除角标(左上角 ×),点击把该项移出桌面。 */
    private void addRemoveBadge(FrameLayout parent, final int index) {
        TextView badge = new TextView(getContext());
        badge.setText("×");
        badge.setGravity(Gravity.CENTER);
        badge.setTextColor(0xFFFFFFFF);
        badge.setTextSize(16f);
        badge.setBackgroundResource(R.drawable.circle_accent);
        badge.setTag("fill:accent");
        int cs = Launchers.dp(getContext(), 22);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(cs, cs);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.setMargins(Launchers.dp(getContext(), 4), Launchers.dp(getContext(), 4), 0, 0);
        badge.setLayoutParams(lp);
        badge.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { if (listener != null) listener.onRemoveApp(index); }
        });
        parent.addView(badge);
    }

    /** 编辑态末尾的 + 磁贴,点击打开添加。 */
    private void addPlusTile(List<boolean[]> occ, int[] slot, int cellW, int rowH, int iconSize, int gap) {
        FrameLayout box = new FrameLayout(getContext());
        TextView plus = new TextView(getContext());
        plus.setText("+");
        plus.setGravity(Gravity.CENTER);
        plus.setTextSize(32f);
        plus.setTextColor(col(R.color.muted));
        plus.setBackgroundResource(R.drawable.tile_bg);
        plus.setTag("fg:muted;fill:tile");
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(iconSize, iconSize);
        plp.gravity = Gravity.CENTER;
        box.addView(plus, plp);
        setRipple(box);
        box.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { if (listener != null) listener.onAddApp(); }
        });
        findSlot(occ, 1, 1, slot);
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                GridLayout.spec(slot[0], 1), GridLayout.spec(slot[1], 1));
        lp.width = cellW; lp.height = rowH;
        lp.setMargins(gap / 2, gap / 2, gap / 2, gap / 2);
        addView(box, lp);
    }

    private void toggleSize(int index) {
        if (index < 0 || index >= items.size()) return;
        DeskItem it = items.get(index);
        if (it.kind != DeskItem.Kind.FOLDER) return;
        it.folder.big = !it.folder.big;
        if (lastWidth > 0) build(lastWidth, true); else requestBuild();
        if (listener != null) listener.onLayoutChanged();
    }

    // ---- 公共:标签 ----

    private TextView label(String text, int cellW) {
        TextView t = new TextView(getContext());
        t.setText(text);
        t.setTextColor(col(R.color.muted));
        t.setTextSize(12f);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cellW, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Launchers.dp(getContext(), 5);
        t.setLayoutParams(lp);
        return t;
    }

    // ---- 编辑抖动 ----

    private void startWobble() {
        stopWobble();
        if (animatorsDisabled()) return;
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            ObjectAnimator a = ObjectAnimator.ofFloat(c, "rotation", -1.1f, 1.1f);
            a.setDuration(150);
            a.setStartDelay((i % 3) * 50);
            a.setRepeatMode(ObjectAnimator.REVERSE);
            a.setRepeatCount(ObjectAnimator.INFINITE);
            a.start();
            wobblers.add(a);
        }
    }

    private void stopWobble() {
        for (ObjectAnimator a : wobblers) a.cancel();
        wobblers.clear();
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).setRotation(0f);
    }

    private boolean animatorsDisabled() {
        try {
            return Settings.Global.getFloat(getContext().getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
        } catch (Exception e) {
            return false;
        }
    }
}
