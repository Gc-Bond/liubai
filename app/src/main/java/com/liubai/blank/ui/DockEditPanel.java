package com.liubai.blank.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnTouchListener;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.liubai.blank.R;
import com.liubai.blank.data.AppRepository;
import com.liubai.blank.data.AppTypes;
import com.liubai.blank.data.NavSourceStore;
import com.liubai.blank.data.RunSourceStore;
import com.liubai.blank.icon.IconEngine;
import com.liubai.blank.media.MusicSourceStore;
import com.liubai.blank.model.AppRef;
import com.liubai.blank.model.DockItem;
import com.liubai.blank.mode.Reskinnable;
import com.liubai.blank.mode.Dim;
import com.liubai.blank.mode.ModeManager;
import com.liubai.blank.util.Launchers;
import com.liubai.blank.util.Scrim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dock 编辑态的浮层。**它自己几乎不占屏**:
 *
 *   · 常驻的只有 Dock 上方一条窄提示条(恢复默认 / 完成);
 *   · 点 Dock 末尾的 + 才弹出「添加」小浮层,而且**只列还没加的**。
 *
 * 早先做过一版"半屏候选面板 + 预览行":候选项默认几乎都已在 Dock 里,
 * 于是满屏灰色禁用块,看着像坏了;预览行也是多余的 —— 编辑对象就是下面那条真 Dock。
 * 现在增删排序全在真 Dock 上发生(见 {@link DockView#setEditing}),这里只做补充。
 */
public class DockEditPanel extends FrameLayout implements Reskinnable {

    public interface Listener {
        /** 恢复默认 Dock。 */
        void onResetDock();
        /** 清空 Dock:能删的全删成空格,删不掉的(设置/地图/音乐)留在原位。 */
        void onClearDock();
        /** 点了一个候选项:把它加进 Dock(落到第一个空格,见 DockView.addItem)。 */
        void onAddItem(DockItem item);
        /**
         * 从候选区把一项拖出来了。屏幕坐标 —— 面板和 Dock 虽然在同一个悬浮窗里,
         * 但各有各的 view 坐标系,统一用 raw 交给宿主换算(见 DockHost.candidateDrag)。
         */
        /**
         * @param glyph 没有图标的键(温度)拿它当影子,{@code iconRes} 为 0 时才有值 ——
         *              影子只画图标的话,拖温度键就是按住了什么都不跟手,看着像拖不动。
         */
        void onCandidateDragStart(DockItem item, int iconRes, String glyph, boolean mirrored);
        void onCandidateDragMove(float rawX, float rawY);
        /** 松手:落在 Dock 上就插进那一格,落在别处算取消。 */
        void onCandidateDrop(float rawX, float rawY);
        void onCandidateDragCancel();
        /**
         * 编辑态把某个应用位绑成指定 app。index 是它在 Dock 里的下标
         * (见 DockView.setSlotApp:地图/音乐位走换源,运行位/应用位绑死包名)。
         */
        void onAssignApp(int index, AppRef app);
        /** 面板改了某个应用位的"自己添加的列表",Dock 上对应键的显示要跟着刷。 */
        void onSlotListChanged();
        /** 编辑结束。 */
        void onEditDone();
    }

    /**
     * 可以往 Dock 上加的东西,**按组排**:一排十来个混在一起找不着。
     * 「空格」也在其列:Dock 的格子总数是死的,所以往中间放一个空格 = 从别处收回一个
     * (见 DockView.placeAt),右边那段项整体后移一格 —— 这是把某一组键推开的唯一办法。
     */
    private static final String[] GROUP_TITLES = {"空调", "系统", "排版"};
    private static final DockItem[][] GROUPS = {
            {
                    // 座椅通风/加热也归这儿 —— 它们本来就是空调系统的一部分,
                    // 单开一组"座椅"是照代码结构分的,不是照用户脑子里的分法
                    DockItem.of(DockItem.Kind.TEMP),
                    // 风向键漏了很久:它可删(removable 没排除它)却不在候选里,
                    // 删掉就再也加不回来 —— 而 08-14 之后这三格是 Dock 上**唯一**能调风量的
                    // 地方,等于一删就永久失去调风量(实测确认过)。补进来,和温度键对称。
                    DockItem.of(DockItem.Kind.WIND),
                    DockItem.ac("fan"),
                    DockItem.ac("defrost"),
                    DockItem.ac("recirc"),
                    DockItem.ac("seat_vent_l"),
                    DockItem.ac("seat_vent_r"),
                    DockItem.ac("seat_heat_l"),
                    DockItem.ac("seat_heat_r"),
            },
            {
                    // 设置键不在候选里:它不可移除,所以永远已经在 Dock 上(见 DockView.wrapForEdit)
                    DockItem.of(DockItem.Kind.BACK),
                    DockItem.of(DockItem.Kind.HOME),
                    DockItem.of(DockItem.Kind.DRAWER),
                    DockItem.of(DockItem.Kind.RECENTS),
            },
            {
                    DockItem.of(DockItem.Kind.DIVIDER),
                    DockItem.of(DockItem.Kind.EMPTY),
            },
    };

    private Listener listener;
    private final View scrim;
    private final LinearLayout sheet;    // Dock 上方那一张面板
    private final LinearLayout addRow;   // 「添加」分组区
    private final TextView addEmpty;     // 全加上了的时候顶替分组区
    private final View addSep;           // 「添加」区上方的分隔线
    private final TextView addTitle;     // 「添加」区标题 —— 候选区展开时这两样连同 addRow 一起藏

    private final IconEngine icons;
    private final NavSourceStore navSources;
    private final MusicSourceStore musicSources;
    private final RunSourceStore runSources;
    /** 四个应用位一行(地图/音乐 + 两个运行位),点一格就地展开候选。 */
    private final LinearLayout slotRow;
    /** 候选区:点应用位才展开,里面是缩小版的全部应用网格(已添加的打勾)。 */
    private final FrameLayout candidateBox;
    private final GridView candidateGrid;
    /** 正展开候选的是哪个应用位(Dock 里的下标),-1 = 收起。 */
    private int pickingSlot = -1;

    private List<DockItem> current = new ArrayList<>();
    private boolean open;

    public DockEditPanel(Context ctx) {
        super(ctx);
        setVisibility(GONE);
        icons = new IconEngine(ctx);
        navSources = new NavSourceStore(ctx);
        musicSources = new MusicSourceStore(ctx);
        runSources = new RunSourceStore(ctx);

        // 遮罩留出底部 Dock 那条:编辑对象要一直看得见、点得着。点遮罩=完成
        scrim = new View(ctx);
        scrim.setBackgroundColor(Scrim.MEDIUM);
        scrim.setOnClickListener(v -> close());
        LayoutParams sclp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        sclp.bottomMargin = Launchers.dockSpace(ctx);   // 遮罩不盖住 Dock,下沿也落在这条线上
        addView(scrim, sclp);

        // ---- 一张面板:标题 + 添加区。和空调面板同一套外观语言 ----
        sheet = new LinearLayout(ctx);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackground(sheetBg());
        sheet.setTag("panel");   // 面板不透明度,见 ModeApplier 的 panel 指令
        sheet.setElevation(Launchers.dp(ctx, 24));
        int ph = Launchers.dp(ctx, 28);
        sheet.setPadding(ph, Launchers.dp(ctx, 22), ph, Launchers.dp(ctx, 26));
        sheet.setOnTouchListener((v, e) -> true);   // 吃掉落在面板空白处的触摸

        sheet.addView(header(ctx), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 四个应用位:地图/音乐 + 两个运行位,点一格就地管理"自己添加的 app" ----
        sheet.addView(sectionTitle(ctx, "四个应用位 · 点一下添加/取消,长按 Dock 上的键切换", 16));
        slotRow = new LinearLayout(ctx);
        slotRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams srlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        srlp.topMargin = Launchers.dp(ctx, 10);
        sheet.addView(slotRow, srlp);

        candidateBox = new FrameLayout(ctx);
        candidateBox.setVisibility(GONE);
        candidateGrid = new GridView(ctx);
        // 7 列、横向 6dp:缩小版跟全屏抽屉同一套收法(左右别空一大片),纵向保持原样
        candidateGrid.setNumColumns(7);
        candidateGrid.setVerticalSpacing(Launchers.dp(ctx, 8));
        candidateGrid.setHorizontalSpacing(Launchers.dp(ctx, 6));
        candidateGrid.setVerticalScrollBarEnabled(false);
        candidateGrid.setBackground(round(Launchers.dp(ctx, 16),
                ModeManager.get().tile2(), ModeManager.get().line()));
        candidateGrid.setPadding(Launchers.dp(ctx, 8), Launchers.dp(ctx, 6),
                Launchers.dp(ctx, 8), Launchers.dp(ctx, 6));
        // 高度定死(封顶 CANDIDATE_MAX_DP),网格内部自己滚 —— 不外包 ScrollView,
        // 那层会让 GridView 量出无限高、整个面板被撑出屏幕
        candidateBox.addView(candidateGrid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Launchers.dp(ctx, CANDIDATE_MAX_DP)));
        LinearLayout.LayoutParams cblp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cblp.topMargin = Launchers.dp(ctx, 8);
        sheet.addView(candidateBox, cblp);

        // 「添加」分组区。候选区展开时整段藏起来 —— 两个"添加"并存既挤又让人分不清
        addSep = new View(ctx);
        addSep.setBackgroundColor(ModeManager.get().line());
        addSep.setTag("bg:line");
        LinearLayout.LayoutParams seplp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Launchers.dp(ctx, 1)));
        seplp.topMargin = Launchers.dp(ctx, 20);
        sheet.addView(addSep, seplp);

        addTitle = sectionTitle(ctx, "添加 · 点一下加进空位,或者直接拖到想要的那一格", 14);
        sheet.addView(addTitle);

        // 纵向:一组一行(组标题 + 该组的 chip 行),横着排会把标题和 chip 挤成一条
        addRow = new LinearLayout(ctx);
        addRow.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams arlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        arlp.topMargin = Launchers.dp(ctx, 8);
        sheet.addView(addRow, arlp);

        addEmpty = new TextView(ctx);
        addEmpty.setText("能加的都加上了");
        addEmpty.setTextSize(16f);
        addEmpty.setTextColor(ModeManager.get().faint());
        addEmpty.setTag("fg:faint");
        addEmpty.setVisibility(GONE);
        LinearLayout.LayoutParams aelp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        aelp.topMargin = Launchers.dp(ctx, 12);
        sheet.addView(addEmpty, aelp);

        // 定宽而不是 wrap_content:头部是"左说明 + 右按钮"的两端对齐结构,
        // 靠内容算宽度会把右边的按钮挤没、把说明文字截断。
        // 宽度按头部那一行需要的量给,不按最宽的一组 chip —— 候选项少的时候右边才不空一片
        int screenW = ctx.getResources().getDisplayMetrics().widthPixels;
        LayoutParams slp = new LayoutParams(
                Math.min(Math.round(screenW * .62f), Launchers.dp(ctx, 760)),
                LayoutParams.WRAP_CONTENT);
        slp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        slp.bottomMargin = Launchers.dockSpace(ctx);   // 和空调面板、气泡同一条线
        sheet.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            v.setPivotX((r - l) / 2f);
            v.setPivotY(b - t);
        });
        addView(sheet, slp);
    }

    /** 标题行:左边说清这是什么、怎么操作,右边恢复默认 / 完成。 */
    private View header(Context ctx) {
        LinearLayout h = new LinearLayout(ctx);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout left = new LinearLayout(ctx);
        left.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(ctx);
        title.setText("自定义 Dock");
        title.setTextSize(20f);
        Launchers.medium(title);
        title.setTextColor(ModeManager.get().ink());
        left.addView(title);

        TextView sub = new TextView(ctx);
        sub.setText("在下面那条 Dock 上直接改");
        sub.setTextSize(14f);
        sub.setTextColor(ModeManager.get().faint());
        LinearLayout.LayoutParams sublp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sublp.topMargin = Launchers.dp(ctx, 6);
        left.addView(sub, sublp);

        h.addView(left);

        View sp = new View(ctx);
        LinearLayout.LayoutParams splp = new LinearLayout.LayoutParams(0, 1, 1f);
        splp.setMarginStart(Launchers.dp(ctx, 24));
        h.addView(sp, splp);

        // 摆在最左、离「完成」最远:这一下会把自己排的东西全抹掉,不该跟收工的键挨着
        TextView clear = pill(ctx, "清空");
        clear.setOnClickListener(v -> {
            if (listener != null) listener.onClearDock();
        });
        h.addView(clear);

        TextView reset = pill(ctx, "恢复默认");
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.setMarginStart(Launchers.dp(ctx, 12));
        reset.setOnClickListener(v -> {
            if (listener != null) listener.onResetDock();
        });
        h.addView(reset, rlp);

        TextView done = pill(ctx, "完成");
        done.setTextColor(ModeManager.get().accentText());
        done.setBackground(round(Launchers.dp(ctx, 999),
                ModeManager.get().accentSoft(), ModeManager.get().accent()));
        done.setOnClickListener(v -> close());
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.setMarginStart(Launchers.dp(ctx, 12));
        h.addView(done, dlp);
        return h;
    }

    private TextView sectionTitle(Context ctx, String text, int topDp) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(14f);
        tv.setTextColor(ModeManager.get().faint());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Launchers.dp(ctx, topDp);
        tv.setLayoutParams(lp);
        return tv;
    }

    private GradientDrawable sheetBg() {
        // 顶浅底深的斜向渐变 + 细描边,和空调面板同源:纯平一块底是"廉价感"的主因
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{ModeManager.get().tile2(), ModeManager.get().surface()});
        d.setCornerRadius(ModeManager.get().dimPx(getContext(), Dim.RADIUS_CARD));
        d.setStroke(Launchers.dp(getContext(), 1), ModeManager.get().line());
        d.setAlpha(ModeManager.get().panelAlpha());
        return d;
    }

    /** 同 {@link HvacPanel#reskin()}:渐变底 tag 刷不了,整个重建。 */
    @Override public void reskin() {
        sheet.setBackground(sheetBg());
    }

    public void setListener(Listener l) { this.listener = l; }

    // ---- 开合 ----

    public boolean isOpen() { return open; }

    public void open(List<DockItem> dockItems) {
        current = dockItems != null ? dockItems : new ArrayList<>();
        pickingSlot = -1;   // 重新打开就是全新一次编辑,不残留上次展开的候选
        renderAdd();
        renderAppSlots();
        if (open) return;
        open = true;
        setVisibility(VISIBLE);
        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(200).start();
        Motion.enter(sheet, Launchers.dp(getContext(), 28));
    }

    /** Dock 内容变了:「添加」区跟着刷新 —— 刚加进去的那一项这一轮就不再列出来。 */
    public void syncDock(List<DockItem> dockItems) {
        current = dockItems != null ? dockItems : new ArrayList<>();
        if (open) {
            renderAdd();
            renderAppSlots();
        }
    }

    public void close() {
        if (!open) return;
        open = false;
        scrim.animate().alpha(0f).setDuration(160).start();
        Motion.exit(sheet, Launchers.dp(getContext(), 20), () -> {
            if (!open) setVisibility(GONE);
        });
        if (listener != null) listener.onEditDone();
    }

    // ---- 四个应用位:地图/音乐 + 两个运行位 ----

    /** 一个应用位在面板里的样子:它在 Dock 里的下标、类型、当前绑的 app(可能 null)。 */
    private static final class SlotEntry {
        final int index;
        final DockItem.Kind kind;
        final AppRef app;
        final int slotNo;   // 第几个应用位,1~4,面板上按这个叫「应用位 N」
        SlotEntry(int index, DockItem.Kind kind, AppRef app, int slotNo) {
            this.index = index;
            this.kind = kind;
            this.app = app;
            this.slotNo = slotNo;
        }
    }

    /** 候选区最高多高,再长内部滚。按行高 48dp 算是 6 行出头,够一眼扫完常用 app。 */
    private static final int CANDIDATE_MAX_DP = 300;

    /**
     * 面板里展示的应用位:地图、音乐,加前两个运行位(四个固定应用就是它们)。
     *
     * 地图/音乐是**语义位**,背后用哪个 app 看源配置(NavSourceStore / MusicSourceStore);
     * 运行位显示绑定的 app,没绑就是空位。顺序照 Dock 上的排列来,别在面板里重排 ——
     * 面板里看到的和下面真 Dock 上的是同一排,改完对得上。
     */
    private List<SlotEntry> appSlotEntries() {
        List<SlotEntry> out = new ArrayList<>();
        int run = 0;
        for (int i = 0; i < current.size(); i++) {
            DockItem it = current.get(i);
            if (it.kind == DockItem.Kind.MAP) {
                out.add(new SlotEntry(i, it.kind, currentApp(it.kind, null, 1), 1));
            } else if (it.kind == DockItem.Kind.MUSIC) {
                out.add(new SlotEntry(i, it.kind, currentApp(it.kind, null, 2), 2));
            } else if (it.kind == DockItem.Kind.RUNNING && run < 2) {
                out.add(new SlotEntry(i, it.kind, currentApp(it.kind, it.app, 3 + run), 3 + run));
                run++;
            }
        }
        return out;
    }

    /**
     * 这一位现在显示的 app:读自己添加的列表排头。运行位没配过列表时退回
     * DockItem 里的绑定(bound,存量配置),地图/音乐位没配过就是 null(显示「未设置」)。
     */
    private AppRef currentApp(DockItem.Kind kind, AppRef bound, int slotNo) {
        String pkg;
        if (kind == DockItem.Kind.MAP) pkg = navSources.current();
        else if (kind == DockItem.Kind.MUSIC) pkg = musicSources.current();
        else pkg = runSources.current(slotNo - 3);
        if (pkg == null || pkg.isEmpty()) return bound;
        return new AppRef(pkg, "", AppTypes.label(getContext(), pkg));
    }

    /** 重画四个应用位 + 候选区。Dock 内容变了(换源/落盘)也会经 syncDock 走到这儿。 */
    private void renderAppSlots() {
        List<SlotEntry> entries = appSlotEntries();
        renderSlotRow(entries);
        // 点的那一格被移走(不会发生,应用位不可删)或 Dock 重排后下标对不上就收起
        boolean stillThere = false;
        for (SlotEntry e : entries) if (e.index == pickingSlot) stillThere = true;
        if (!stillThere) pickingSlot = -1;
        renderCandidates(entries);
    }

    /** 面板上方那一排四个应用位格子。Dock 内容变了(换绑/重排)就整排重画。 */
    private void renderSlotRow(List<SlotEntry> entries) {
        slotRow.removeAllViews();
        boolean first = true;
        for (final SlotEntry e : entries) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (first) first = false;
            else lp.setMarginStart(Launchers.dp(getContext(), 10));
            slotRow.addView(slotCell(e), lp);
        }
    }

    /** 一个应用位格子:图标 + 一行字。没绑显示「未设置」,图标退回这一位的默认字形。 */
    private View slotCell(final SlotEntry e) {
        Context ctx = getContext();
        LinearLayout cell = new LinearLayout(ctx);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        int p = Launchers.dp(ctx, 10);
        cell.setPadding(p, p, p, p);
        // 正在编辑的那一格描 accent 边,一眼看出"我现在在给谁加"
        cell.setBackground(round(Launchers.dp(ctx, 999),
                ModeManager.get().tile2(),
                e.index == pickingSlot ? ModeManager.get().accent() : ModeManager.get().line()));
        cell.setContentDescription("应用位" + e.slotNo);

        ImageView iv = new ImageView(ctx);
        int side = Launchers.dp(ctx, 34);
        if (e.app != null) {
            iv.setImageDrawable(icons.iconFor(e.app));
            iv.setPadding(Launchers.dp(ctx, 3), Launchers.dp(ctx, 3),
                    Launchers.dp(ctx, 3), Launchers.dp(ctx, 3));
        } else {
            // 四个位都能放任意 app,空位就统一画「应用」网格,不再暗示"这是地图/音乐位"
            iv.setImageResource(R.drawable.ic_apps);
            iv.setColorFilter(ModeManager.get().glyph());
        }
        cell.addView(iv, new LinearLayout.LayoutParams(side, side));

        TextView tv = new TextView(ctx);
        tv.setText(e.app != null ? e.app.label : "未设置");
        tv.setTextSize(12f);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setTextColor(e.app != null ? ModeManager.get().ink() : ModeManager.get().faint());
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = Launchers.dp(ctx, 6);
        tv.setGravity(Gravity.CENTER);
        cell.addView(tv, tlp);

        Motion.press(cell);
        cell.setOnClickListener(v -> {
            // 再点一下正在展开的那格 = 收起。展开的是哪个位、候选是谁,渲染时按当前
            // items 现认,别在这缓存 —— Dock 内容变了(换绑/重排)格子要跟着对得上。
            pickingSlot = pickingSlot == e.index ? -1 : e.index;
            renderAppSlots();
        });
        return cell;
    }

    /** 这一位"自己添加的"列表(排头 = 当前显示的)。 */
    private List<String> slotList(SlotEntry e) {
        if (e.kind == DockItem.Kind.MAP) return navSources.load();
        if (e.kind == DockItem.Kind.MUSIC) return musicSources.load();
        return runSources.load(e.slotNo - 3);
    }

    /**
     * 候选区:点应用位才展开,里面是**缩小版的全部应用**网格 —— 一屏看得到几十个,
     * 已添加的右上角打勾、描强调色边,点一下在加/取消之间切换,连着点就是多选。
     * 不另开全屏抽屉:那个是桌面"全部应用"的入口,编辑就该在这一页里就地完成。
     */
    private void renderCandidates(List<SlotEntry> entries) {
        SlotEntry pick = null;
        for (SlotEntry e : entries) {
            if (e.index == pickingSlot) { pick = e; break; }
        }
        if (pick == null) {
            candidateBox.setVisibility(GONE);
            showAddSection(true);
            return;
        }
        // 候选区摊开时「添加」区让位 —— 两个"添加"并排只会让人分不清在加什么
        showAddSection(false);
        List<AppRef> all = new AppRepository(getContext()).queryLaunchable();
        candidateGrid.setAdapter(new AppGridAdapter(all,
                new HashSet<>(slotList(pick)), usedElsewhere(pick), pick));
        candidateBox.setVisibility(VISIBLE);
    }

    /**
     * 别的应用位已经添加过的包名 → 它在哪个位(slotNo)。四个应用位**不能重复**
     * 加同一个 app(用户定的规矩:位 1 加了 QQ 音乐,位 2/3/4 就点不动它)。
     * 记录位置是为了点击时能提示"已在应用位 N" —— 视觉上这些 app 压灰、不打勾
     * (打勾只留给当前位,别的位也打勾就分不清哪格选上了,真机踩过)。
     */
    private Map<String, Integer> usedElsewhere(SlotEntry pick) {
        Map<String, Integer> out = new HashMap<>();
        for (SlotEntry e : appSlotEntries()) {
            if (e.index == pick.index) continue;
            for (String p : slotList(e)) out.put(p, e.slotNo);
        }
        return out;
    }

    /** 缩小版全部应用:一格一个 app,当前位已加的勾选,别的位已加的禁用。 */
    private final class AppGridAdapter extends BaseAdapter {
        private final List<AppRef> apps;
        private final Set<String> mine;
        private final Map<String, Integer> usedElsewhere;
        private final SlotEntry pick;

        AppGridAdapter(List<AppRef> apps, Set<String> mine, Map<String, Integer> usedElsewhere,
                       SlotEntry pick) {
            this.apps = apps;
            this.mine = mine;
            this.usedElsewhere = usedElsewhere;
            this.pick = pick;
        }

        @Override public int getCount() { return apps.size(); }
        @Override public Object getItem(int position) { return apps.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            AppRef app = apps.get(position);
            Integer where = usedElsewhere.get(app.pkg);
            return appGridCell(pick, app, mine.contains(app.pkg), where);
        }
    }

    /**
     * 缩小版网格里一格:小图标 + 名字。三种状态 —— 当前位已加(accent 勾 + 描边,点一下
     * 取消)、别的位已加(**压灰、不打勾**,点不动 —— 规矩是不能重复,见 usedElsewhere;
     * 打勾只留给当前位,别的位也打勾就分不清哪格选上了)、没加(可点添加)。
     */
    private View appGridCell(final SlotEntry pick, final AppRef app,
                             final boolean inList, final Integer where) {
        Context ctx = getContext();
        FrameLayout cell = new FrameLayout(ctx);
        int p = Launchers.dp(ctx, 5);
        cell.setPadding(p, Launchers.dp(ctx, 7), p, Launchers.dp(ctx, 7));
        cell.setBackground(round(Launchers.dp(ctx, 12),
                inList ? ModeManager.get().accentSoft() : Color.TRANSPARENT,
                inList ? ModeManager.get().accent() : ModeManager.get().line()));
        // 别的位已加:整格压灰,一眼看出"这个 app 已经在别的位置、这里点不动"
        cell.setAlpha(where != null ? 0.35f : 1f);

        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER);
        ImageView iv = new ImageView(ctx);
        iv.setImageDrawable(icons.iconFor(app));
        int side = Launchers.dp(ctx, 36);
        body.addView(iv, new LinearLayout.LayoutParams(side, side));
        TextView tv = new TextView(ctx);
        tv.setText(app.label);
        tv.setTextSize(12f);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(ModeManager.get().ink());
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = Launchers.dp(ctx, 4);
        body.addView(tv, tlp);
        cell.addView(body, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 右上角打勾只表示"当前位已添加",别的位已加的不打勾(压灰已经够了)
        ImageView check = new ImageView(ctx);
        check.setImageResource(R.drawable.ic_check);
        check.setColorFilter(ModeManager.get().accent(), android.graphics.PorterDuff.Mode.SRC_IN);
        check.setVisibility(inList ? View.VISIBLE : View.GONE);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                Launchers.dp(ctx, 14), Launchers.dp(ctx, 14));
        clp.gravity = Gravity.TOP | Gravity.END;
        cell.addView(check, clp);

        Motion.press(cell);
        // 当前位已加 = 取消;没加 = 添加;别的位已加(且当前位没有)= 只弹一句"在哪个位",
        // 不落任何事(四个位不能重复加同一个 app,见 usedElsewhere)。
        // **判断顺序有讲究**:当前位打勾的优先走移除 —— 存量配置里同一个 app 可能
        // 同时出现在两个位(去重规矩是后加的),那种情况下它也命中"在别的位",无条件
        // 拦截的话它就永远删不掉了(真机踩过:点了只弹提示、勾一直在)
        cell.setOnClickListener(v -> {
            if (where != null && !inList) {
                Launchers.toast(ctx, "已在应用位 " + where);
                return;
            }
            toggleSlotApp(pick, app, inList);
        });
        return cell;
    }

    /** 「添加」区(分隔线 + 标题 + 分组行)整体显隐,候选区展开时让位。 */
    private void showAddSection(boolean on) {
        int v = on ? View.VISIBLE : View.GONE;
        addSep.setVisibility(v);
        addTitle.setVisibility(v);
        addRow.setVisibility(v);
        addEmpty.setVisibility(v);
    }

    /**
     * 缩小版网格里点一格:在/不在列表 = 移除/添加,落进这一位的 store。
     *
     * 动到"当前"(列表排头)时才需要同步 Dock:地图/音乐位的键图标读 store 排头,
     * 重铺一遍就换过来(onSlotListChanged);运行位的显示是 DockItem 绑定,
     * 得把新排头写回绑定(onAssignApp,新排头为空就是清绑定)。
     */
    private void toggleSlotApp(SlotEntry pick, AppRef app, boolean inList) {
        boolean semantic = pick.kind == DockItem.Kind.MAP || pick.kind == DockItem.Kind.MUSIC;
        if (inList) {
            if (semantic) {
                boolean wasCurrent = app.pkg.equals(currentApp(pick.kind, null, pick.slotNo).pkg);
                if (pick.kind == DockItem.Kind.MAP) navSources.remove(app.pkg);
                else musicSources.remove(app.pkg);
                if (wasCurrent && listener != null) listener.onSlotListChanged();
            } else {
                int slot = pick.slotNo - 3;
                boolean wasCurrent = app.pkg.equals(runSources.current(slot));
                runSources.remove(slot, app.pkg);
                if (wasCurrent) {
                    String next = runSources.current(slot);   // 新排头,可能 null = 清空
                    AppRef nx = next != null
                            ? new AppRef(next, "", AppTypes.label(getContext(), next)) : null;
                    if (listener != null) listener.onAssignApp(pick.index, nx);
                }
            }
        } else {
            // 四个应用位不能重复加同一个 app(用户定的规矩):别的位已经加过就拦下。
            // UI 上那格已经点不动,这里再拦一道是防别的入口(双保险,别删)
            if (usedElsewhere(pick).containsKey(app.pkg)) return;
            if (semantic) {
                boolean wasEmpty = slotList(pick).isEmpty();
                if (pick.kind == DockItem.Kind.MAP) navSources.add(app.pkg);
                else musicSources.add(app.pkg);
                // 列表从空变有:新加的成了排头 = 当前使用的地图/音乐,得按新源走
                // 换源那条路(onAssignApp → setSlotApp → 换源),光刷图标不够 ——
                // 上层要收掉旧卡片、按新源重开,不然显示的是新源、卡片还是旧的
                if (wasEmpty && listener != null) listener.onAssignApp(pick.index, app);
            } else {
                int slot = pick.slotNo - 3;
                boolean wasEmpty = runSources.current(slot) == null;
                runSources.add(slot, app.pkg);
                // 列表从空变有:新加的成了排头 = 当前,运行位的绑定得跟上,
                // 不然 Dock 上这一格还是空位
                if (wasEmpty && listener != null) listener.onAssignApp(pick.index, app);
            }
        }
        renderAppSlots();
    }

    // ---- 「添加」内容 ----

    private void renderAdd() {
        addRow.removeAllViews();
        for (int g = 0; g < GROUPS.length; g++) {
            LinearLayout line = null;
            for (final DockItem cand : GROUPS[g]) {
                // 已经在 Dock 里就**不列出来**(而不是列成灰的)——
                // 一排点不动的灰块只会让人以为界面坏了
                if (isUnique(cand.kind) && contains(cand)) continue;
                if (line == null) {
                    // 整组都加过了就连组标题一起省掉,不留空标题
                addRow.addView(groupLabel(GROUP_TITLES[g], addRow.getChildCount() == 0));
                line = groupLine();
                addRow.addView(line);
            }
            line.addView(chip(iconFor(cand), labelFor(cand), cand));
        }
        }
        addEmpty.setVisibility(GONE);
    }

    private TextView groupLabel(String text, boolean first) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextSize(14f);
        tv.setTextColor(ModeManager.get().faint());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Launchers.dp(getContext(), first ? 4 : 16);
        tv.setLayoutParams(lp);
        return tv;
    }

    private LinearLayout groupLine() {
        LinearLayout line = new LinearLayout(getContext());
        line.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Launchers.dp(getContext(), 10);
        line.setLayoutParams(lp);
        return line;
    }

    /**
     * 一个可加项,加完这一项就从列表里消失。两条路:
     *
     *   · **点一下** —— 落到第一个空格(见 {@link DockView#addItem})。车在动的时候省事。
     *   · **拖出去** —— 落到手指指的那一格(见 {@link DockView#dropAt})。停着的时候更准。
     */
    private View chip(int iconRes, String text, final DockItem cand) {
        Context ctx = getContext();
        LinearLayout chip = new LinearLayout(ctx);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        // 只摆图标,四边等距 —— 名字退到 contentDescription(读屏还认得出)。
        // Dock 上这些键本来就只有字形,菜单里也照原样显示,挑的时候才对得上号
        // 图标 28 + 两边 14 = 56dp 见方。原来 20+12=44dp,比 Dock 格子(48)还小 ——
        // 车上戴着手套点不准,而这排 chip 正是"加东西"的唯一入口
        int cp = Launchers.dp(ctx, 14);
        chip.setPadding(cp, cp, cp, cp);
        chip.setContentDescription(text);

        int side = Launchers.dp(ctx, 28);
        String glyph = glyphText(cand);

        final View center;
        if (iconRes == 0 && glyph != null) {
            // **文字键**:Dock 上就没有字形的那几个(温度是读数、AUTO 是俩字母),
            // 菜单里也照原样用文字 —— 给它现造一个图标,只会是两处对不上。
            TextView tv = new TextView(ctx);
            tv.setText(glyph);
            tv.setTextSize(16f);
            tv.setGravity(Gravity.CENTER);
            tv.setTextColor(ModeManager.get().ink());
            tv.setFontFeatureSettings("tnum");
            center = tv;
        } else {
            ImageView iv = new ImageView(ctx);
            iv.setImageResource(iconRes);
            // 副驾在右边,座椅字形跟着镜像 —— 两颗一模一样的话,光看图分不出哪边
            if (cand != null && mirrored(cand)) iv.setScaleX(-1f);
            iv.setColorFilter(ModeManager.get().ink());
            center = iv;
        }

        /*
         * **占两格的键,菜单里也画成两格的样子**。
         *
         * 温度和风向在 Dock 上是「‹ 读数 ›」「‹ 图标 ›」三段、整键 2 格(见 DockItem.slots);
         * 菜单里如果只画中间那个字形,就是"挑的时候一个圆,放上去变成一长条",对不上。
         * 宽度按 Dock 的格子算:一格 48,两格 96 —— 减掉 chip 自己的 14×2 内边距,
         * 内容区正好 68(箭头 18 + 中间 32 + 箭头 18)。
         */
        if (cand != null && cand.slots() == 2) {
            LinearLayout inner = new LinearLayout(ctx);
            inner.setOrientation(LinearLayout.HORIZONTAL);
            inner.setGravity(Gravity.CENTER_VERTICAL);
            int aw = Launchers.dp(ctx, 18), cw = Launchers.dp(ctx, 32);
            inner.addView(arrowGlyph(ctx, R.drawable.ic_chevron_left),
                    new LinearLayout.LayoutParams(aw, side));
            inner.addView(center, new LinearLayout.LayoutParams(cw, side));
            inner.addView(arrowGlyph(ctx, R.drawable.ic_chevron_right),
                    new LinearLayout.LayoutParams(aw, side));
            chip.addView(inner, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, side));
        } else {
            chip.addView(center, new LinearLayout.LayoutParams(side, side));
        }

        chip.setBackground(round(Launchers.dp(ctx, 999),
                ModeManager.get().tile2(), ModeManager.get().line()));

        Motion.press(chip);
        chip.setOnClickListener(v -> {
            if (listener != null) listener.onAddItem(cand);
        });

        // 拖出去放到指定那一格。点一下照旧(落第一个空格) —— 两条路并存,
        // 手指没挪够 slop 就当点击,自己补一发 performClick。
        //
        // 不用系统拖放:整条 Dock 活在悬浮窗里,系统拖放框架是为 Activity 窗口设计的
        // (同 DockView.onInterceptTouchEvent 那条理由)。面板和 Dock 本来就在同一个
        // root 下(DockHost.openEditor 把面板插在 Dock 下层),自己画个跟手的影子就够了。
        if (cand != null) {
            final int slop = android.view.ViewConfiguration.get(ctx).getScaledTouchSlop();
            final boolean mir = mirrored(cand);
            chip.setOnTouchListener(new OnTouchListener() {
                private float downX, downY;
                private boolean dragging;

                @Override public boolean onTouch(View v, MotionEvent e) {
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            downX = e.getRawX(); downY = e.getRawY(); dragging = false;
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            if (!dragging
                                    && (Math.abs(e.getRawX() - downX) > slop
                                     || Math.abs(e.getRawY() - downY) > slop)) {
                                dragging = true;
                                if (listener != null) listener.onCandidateDragStart(cand, iconRes, glyph, mir);
                            }
                            if (dragging && listener != null) {
                                listener.onCandidateDragMove(e.getRawX(), e.getRawY());
                            }
                            return true;
                        case MotionEvent.ACTION_UP:
                            if (dragging) {
                                if (listener != null) listener.onCandidateDrop(e.getRawX(), e.getRawY());
                            } else {
                                v.performClick();
                            }
                            return true;
                        case MotionEvent.ACTION_CANCEL:
                            if (dragging && listener != null) listener.onCandidateDragCancel();
                            return true;
                        default:
                            return false;
                    }
                }
            });
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(Launchers.dp(ctx, 12));
        chip.setLayoutParams(lp);
        return chip;
    }

    /** 副驾那颗:座椅朝向要跟主驾相反。 */
    private static boolean mirrored(DockItem it) {
        return it.kind == DockItem.Kind.AC && it.action.startsWith("seat_")
                && it.action.endsWith("_r");
    }

    /** 这类项在 Dock 上只该有一个;分隔线/空格/app 可以重复(排版要用,app 可能想放两个不同的)。 */
    private boolean isUnique(DockItem.Kind kind) {
        return kind != DockItem.Kind.DIVIDER && kind != DockItem.Kind.EMPTY
                && kind != DockItem.Kind.APP;
    }

    private boolean contains(DockItem cand) {
        for (DockItem it : current) {
            if (it.kind != cand.kind) continue;
            if (it.kind == DockItem.Kind.AC) {
                if (it.action.equals(cand.action)) return true;
            } else {
                return true;
            }
        }
        return false;
    }

    // ---- 项的图标与名字 ----

    private int iconFor(DockItem it) {
        switch (it.kind) {
            case HOME:     return R.drawable.ic_home;
            case BACK:     return R.drawable.ic_back;
            // 温度键在 Dock 上是**文字读数**「26°C」,没有字形 —— 这里也走文字(见 glyphText),
            // 不摆温度计:那会是菜单里一个样、Dock 上另一个样。
            case TEMP:     return 0;
            // 风向键中间那格画的就是当前风向字形(见 DockView.windModeIcon),
            // 未提权时默认 mode_1,菜单里取同一个,挑的时候对得上号。
            case WIND:     return R.drawable.ic_wind_mode_1;
            case DRAWER:   return R.drawable.ic_apps;
            case RECENTS:  return R.drawable.ic_recents;
            case SETTINGS: return R.drawable.ic_settings;
            case DIVIDER:  return R.drawable.ic_divider;
            case EMPTY:    return R.drawable.ic_blank;
            case AC:
                if ("fan".equals(it.action))     return R.drawable.ic_fan;
                if ("defrost".equals(it.action)) return R.drawable.ic_defrost;
                if ("recirc".equals(it.action))  return R.drawable.ic_recirculate;
                if (it.action.startsWith("seat_vent_")) return R.drawable.ic_seat_vent;
                if (it.action.startsWith("seat_heat_")) return R.drawable.ic_seat_heat;
                return 0;    // AUTO 是文字键,没有字形
            default:       return 0;
        }
    }

    /** 两格键两侧那个箭头。压成 muted:它是"能左右调"的提示,不该跟中间的字形抢。 */
    private ImageView arrowGlyph(Context ctx, int res) {
        ImageView iv = new ImageView(ctx);
        iv.setImageResource(res);
        iv.setColorFilter(ModeManager.get().muted());
        return iv;
    }

    /**
     * 没有字形的那些键在菜单里显示什么。
     *
     * 温度取「°C」而不是当前读数:菜单是「这是什么键」,摆个具体度数会让人以为
     * 点下去就设成那个温度。
     */
    private String glyphText(DockItem it) {
        if (it == null) return null;
        return it.kind == DockItem.Kind.TEMP ? "°C" : null;
    }

    private String labelFor(DockItem it) {
        switch (it.kind) {
            case HOME:     return "主页";
            case BACK:     return "返回";
            case TEMP:     return "温度";
            case WIND:     return "风向";
            case DRAWER:   return "全部应用";
            case RECENTS:  return "任务";
            case SETTINGS: return "设置";
            case DIVIDER:  return "分隔线";
            case EMPTY:    return "空格";
            case AC:
                if ("fan".equals(it.action))     return "风量";
                if ("defrost".equals(it.action)) return "除雾";
                if ("recirc".equals(it.action))  return "循环";
                if ("seat_vent_l".equals(it.action)) return "主驾通风";
                if ("seat_vent_r".equals(it.action)) return "副驾通风";
                if ("seat_heat_l".equals(it.action)) return "主驾加热";
                if ("seat_heat_r".equals(it.action)) return "副驾加热";
                return "车控";
            default:       return "";
        }
    }

    // ---- 外观零件 ----

    private TextView pill(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(16f);
        tv.setTextColor(ModeManager.get().muted());
        tv.setBackground(round(Launchers.dp(ctx, 999),
                Color.TRANSPARENT, ModeManager.get().line()));
        // 上下 12dp:文字 16sp 加两边留白约 48dp 高,和 Dock 格子一个手指尺寸
        tv.setPadding(Launchers.dp(ctx, 22), Launchers.dp(ctx, 12),
                Launchers.dp(ctx, 22), Launchers.dp(ctx, 12));
        Motion.press(tv);
        return tv;
    }

    private GradientDrawable round(int r, int color, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(r);
        d.setColor(color);
        d.setStroke(Math.max(1, Launchers.dp(getContext(), 1)), stroke);
        return d;
    }
}
