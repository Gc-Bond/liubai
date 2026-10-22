package com.liubai.blank.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.liubai.blank.R;
import com.liubai.blank.data.DeskCardStore;
import com.liubai.blank.data.DockBlacklist;
import com.liubai.blank.data.OnlineWallpapers;
import com.liubai.blank.data.WallpaperStore;
import com.liubai.blank.icon.IconEngine;
import com.liubai.blank.model.AppRef;
import com.liubai.blank.privileged.ForegroundWatch;
import com.liubai.blank.data.StatusBarStore;
import com.liubai.blank.model.StatusItem;
import com.liubai.blank.privileged.Grant;
import com.liubai.blank.ui.DockHost;
import com.liubai.blank.privileged.Shell;
import com.liubai.blank.mode.Mode;
import com.liubai.blank.mode.ModeApplier;
import com.liubai.blank.mode.Dim;

import com.liubai.blank.mode.ModeManager;
import com.liubai.blank.util.AppUpdater;
import com.liubai.blank.util.Immersive;
import com.liubai.blank.util.Launchers;
import com.liubai.blank.util.Scrim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 外观面板:壁纸 + 配色 + 模式 + 卡片,桌面上就地改。
 *
 * **全屏 + 左侧竖排导航**。车机是 16:9 横屏,横向宽得富余、竖向只有 720dp ——
 * 导航排在左边,竖向空间就全留给内容,壁纸一屏能摆下 9 张大图。
 *
 * **选壁纸的本质是看效果**,全屏之后桌面被盖住,所以给了两条路:
 *   点一下   —— 立刻应用(桌面在面板背后已经换好了)
 *   按住不放 —— 面板整体淡出,露出背后真实的桌面(壁纸在播、卡片和 Dock 都在),松手回来
 * 于是"看大图"和"看真实效果"两件事都成立,不用在半屏面板里挤。
 *
 * **一个方向的滚动**。以前是四组各自横滑、又嵌在竖滚里,车上手指抖一下就滑错组;
 * 现在合成一个竖滚网格,筛选挪到顶栏,而且**两根轴分开**:来源是单选(全部/内置/我的),
 * 形态是一个「只看动态」开关,不把它们乘成四五个并排的 chip。
 *
 * **四个 tab 的分工**:
 *   壁纸 —— 大图网格,点选即用
 *   配色 —— 底色 / 强调色 / 面板底色 / 透明度
 *   功能 —— 画中画、动效这类开关
 *   卡片 —— 桌面上摆哪几张卡
 */
public class AppearancePopup extends FrameLayout {

    public interface Listener {
        /** 壁纸换了,桌面去重铺。 */
        void onWallpaperChanged();
        /**
         * 只是颜色变了(配色来源 / 强调色 / 面板底色)。**就地换肤,别重建。**
         *
         * 颜色是能在现有 view 树上重新 apply 的(ModeApplier 就是这么设计的),
         * 而 recreate 会让整屏闪一下、弹层消失再滑回来 —— 用户正在挑颜色,
         * 每点一次跳一下,很晃眼。
         */
        void onPaletteChanged();
        /**
         * 挑一个 app 加进某张名单。抽屉是个 Activity,弹层自己起不了也接不住结果 ——
         * 由桌面代发 startActivityForResult,选完回来再喂给对应的 Store(见 HomeActivity)。
         */
        void onPickAppFor(Pick what);

        /**
         * 去系统那个「默认桌面」选择页。
         *
         * 一键授权里这一项优先走提权(`cmd package set-home-activity`,一步落定),
         * 但有的 ROM 连 shell 身份也拒(华为平板实测 `Failed to set default home`)——
         * 那时只能退回系统页面让人点一下。弹层自己起不了 Activity,得桌面代发。
         */
        void onSetDefaultHome();

        /** 进 Dock 编辑态。长按 Dock 设置键是同一条路,这里是给它一条看得见的入口。 */
        void onEditDock();

        /**
         * 动效总开关变了。桌面要把**桌面卡和 Dock 重挂一遍** ——
         * 动效是这些 view 建的时候读进去的(DeskCard.still / DockView),
         * 就地换肤刷不到。但**别 recreate**:面板正开着,人手还按在开关上。
         */
        void onMotionChanged();
    }

    /** 挑 app 是给哪张名单用的。 */
    public enum Pick {
        /** 打开时收起 Dock 的应用。 */
        DOCK_HIDE
    }

    /** 左侧导航栏宽度。放得下四个中文 tab,又不至于把内容区挤窄。 */
    private static final int RAIL_DP = 132;
    /** 壁纸网格列数。1280dp 宽的屏上每格约 356dp,是原来那种小格子的六倍面积。 */
    private static final int GRID_COLS = 3;
    /** 按住多久开始"透出桌面"。短了会和轻点打架,长了像没反应。 */
    private static final long HOLD_MS = 320;
    /** 缩略图解码上限(px)。等于 WallpaperStore 首帧缓存的门槛,越过就不缓存了,见 chip()。 */
    private static final int THUMB_MAX_PX = 480;

    /**
     * 四个 tab。顺序对应「背景 → 颜色 → 能力 → 摆什么」,从最常改的排到最少改的。
     *
     * 卡片显隐归在这儿而不是设置页,理由和壁纸一样:改完按住就能看到真桌面,
     * 勾掉一张卡当场就知道桌面少了什么。
     */
    private enum Tab { WALLPAPER, PALETTE, FEATURE, DOCK, TOPBAR, GRANT, TIPS, ABOUT }

    /**
     * 壁纸来源。**「内置」不叫「官方」**:它强调的是跟着 APK 走、装机就有,
     * 和将来下载来的「在线」正好对得上;而「我的」是用户自己拷进去的那些。
     *
     * 形态(动态/静态)是**另一根轴**,由「只看动态」那个开关管 —— 两根轴不相乘:
     * 相乘的话"内置动态/内置静态/我的动态/我的静态"四个起步,加了在线就是六个,一行摆不下,
     * 而且五个 chip 并排却分属两类概念,看着就别扭。
     */
    private enum Source {
        ALL("全部"), PERSONAL("我的"), ONLINE("在线");
        final String label;
        Source(String l) { this.label = l; }
    }

    /**
     * 壁纸的形态。三种在桌面上的脾气完全不同 —— 图片就是一张图;视频占着一路硬解码器;
     * 交互是网页,能点,而且**不参与左右滑切换**(见 WallpaperStore.slideRing)。
     * 每个格子左上角标着自己是哪种(见 cellTag),chip 那排按它筛。
     */
    private enum Form {
        IMAGE("图片"), VIDEO("视频"), WEB("交互");
        /** 格子左上角那个小标上的字;chip 上是「只看」+ 它。 */
        final String tag;
        Form(String t) { this.tag = t; }

        boolean matches(Entry e) {
            if (this == WEB) return e.web;
            if (this == VIDEO) return e.video && !e.web;
            return !e.video && !e.web;
        }

        static Form of(Entry e) {
            if (e.web) return WEB;
            return e.video ? VIDEO : IMAGE;
        }
    }

    /**
     * 网格里的一格。把三个来源摊平成同一个模型:本地的(内置/我的/已下载的在线)只有 id,
     * 在线的还带着目录里那份元数据 —— **没下载的也要能摆出来**,那正是橱窗的意义。
     */
    private static final class Entry {
        final String id;                        // WallpaperStore 认的带前缀 id
        final String label;                     // 展示名(在线壁纸有中文名,本地的用文件名)
        final boolean video;
        /** 交互壁纸(网页)。和图/视频是**两类东西**,不是"动态"的一个变种。 */
        final boolean web;
        final boolean personal;                 // 只有"我的"要提示超规格
        final boolean official;                 // 内置的:没有自己那个 chip 了,靠格子上一个小标说明出处
        final OnlineWallpapers.Remote remote;    // null = 本地项
        final boolean downloaded;               // 在线项才有意义

        Entry(WallpaperStore.Item it) {
            id = it.id();
            // 用 display() 不用 name:网页壁纸的 name 带着一条内部标记用的尾斜杠,
            // 直接摆出来界面上就是"锦鲤池/"
            label = it.display();
            video = it.video;
            web = it.web;
            personal = it.source == WallpaperStore.Source.PERSONAL;
            official = it.source == WallpaperStore.Source.OFFICIAL;
            remote = null;
            downloaded = true;
        }

        Entry(OnlineWallpapers.Remote r, boolean downloaded) {
            id = r.id();
            label = r.name;
            video = !"image".equals(r.kind);
            web = false;            // 在线橱窗目前只发图和视频
            personal = false;
            official = false;
            remote = r;
            this.downloaded = downloaded;
        }
    }

    private final LinearLayout sheet;
    private final View scrim;
    private final LinearLayout body;        // tab 内容区,切 tab 时换里面的东西
    private final LinearLayout headerSlot;  // 内容区顶栏的左半边:壁纸放筛选,其余放标题
    private final TextView tabFeature, tabWallpaper, tabPalette, tabDock,
            tabTopBar, tabGrant, tabTips, tabAbout;
    private final DockBlacklist blacklist;
    private final IconEngine icons;

    private final WallpaperStore wallpapers;
    private Listener listener;
    /**
     * 停在哪个 tab。**静态**:换配色/模式会 recreate 整个桌面、弹层跟着重建,
     * 挂在实例上的话用户刚在「配色」里点完颜色,重建回来就被扔回「壁纸」了。
     */
    private static Tab tab = Tab.WALLPAPER;
    /** 筛选也是静态的,同上:换个配色不该把人筛好的范围清掉。 */
    private static Source source = Source.ALL;
    /** 形态筛选,**null = 不限**。三档互斥,见 {@link #formChip}。 */
    private static Form form = null;

    /**
     * 等着滚进视口才去办的缩略图。**下载和解码都在这条路上。**
     *
     * <h3>两次都栽在"按二十几张写的"</h3>
     * 第一次:开面板就把所有封面下下来 —— 3Mbps 的出口上串行下几百张,开面板先卡半分钟。
     * 改成了滚到哪下哪(就是这个队列)。
     *
     * 第二次(2026-08-23):**下载懒了,解码没懒** —— 建格子时每一格都在主线程
     * `wallpapers.load()`(磁盘读 + JPEG 解码一张 480px),不管它在不在视野里。
     * test 库涨到 104 张之后,点一下「在线」就是一百多次同步解码压在主线程上。
     * 所以现在**每一格都进这个队列**(不只是缺封面的在线格),解码也搬到后台线程。
     */
    private final List<ThumbJob> coverPending = new ArrayList<>();

    /** 一个等着出图的格子:下完/解完就地铺回它自己的 ImageView。 */
    private static final class ThumbJob {
        /** 本地项是 null;在线项非空,可能还要先把封面下下来。 */
        final OnlineWallpapers.Remote remote;
        /** 解码用的 id(见 WallpaperStore.load),在线项是 {@code o:key}。 */
        final String id;
        final View cell;
        final ImageView thumb;
        final int reqW, reqH;
        boolean started;

        ThumbJob(OnlineWallpapers.Remote remote, String id, View cell, ImageView thumb,
                 int reqW, int reqH) {
            this.remote = remote; this.id = id; this.cell = cell; this.thumb = thumb;
            this.reqW = reqW; this.reqH = reqH;
        }
    }

    /**
     * 解好的缩略图。**切一次筛选整页重建**(「全部」→「在线」→ 再切回来),
     * 没缓存的话同一张图要反复解 —— 解码本身现在不卡主线程了,但仍然是白烧的 CPU 和电。
     *
     * 6MB 上限:480×270 的 ARGB_8888 约 518KB,够存十来张;超了按最久没用的丢。
     * 面板关掉不清 —— 下次开面板首屏那几张直接就有。
     */
    private static final android.util.LruCache<String, android.graphics.Bitmap> THUMBS =
            new android.util.LruCache<String, android.graphics.Bitmap>(6 * 1024 * 1024) {
                @Override protected int sizeOf(String k, android.graphics.Bitmap b) {
                    return b.getByteCount();
                }
            };

    /**
     * 解缩略图的线程。**和下载那条分开**:下载必须串行(3Mbps 的出口,并发只会互抢),
     * 而解码是纯 CPU —— 混在一条线上,一张图没下完,后面十张封面现成的也跟着干等。
     *
     * 两条线程、低优先级:它抢的是主线程正在用的那颗核。
     */
    private static final java.util.concurrent.ExecutorService DECODE =
            java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "blank-thumb");
                t.setPriority(Thread.MIN_PRIORITY + 2);
                t.setDaemon(true);
                return t;
            });

    /** 壁纸页那个 ScrollView,判可见范围要用它。页面重建时换新的。 */
    private ScrollView wallpaperScroll;

    /**
     * 壁纸网格**一批一批地建**,不是一次全建出来。
     *
     * 一次全建的代价:在线库 104 张 = 104 个格子(每格 FrameLayout + ImageView + 两个
     * TextView),点一下「在线」就是四百多个 view 一口气 inflate 出来,再加一百多次
     * 缩略图解码 —— 卡在点下去那一下(2026-08-23 用户反馈)。
     *
     * 一批 {@link #GRID_PAGE} 个,离底不到一屏就再来一批(见 {@link #maybeGrowGrid})。
     * 首批要**铺满首屏**:3 列、格子 16:9,面板里一屏看得见两行半 —— 给 9(三行)
     * 正好不露空白,再少就是一打开先看见半屏空的。
     */
    private static final int GRID_PAGE = GRID_COLS * 3;
    private final List<Entry> gridItems = new ArrayList<>();
    private int gridShown;
    /** 装格子的那个容器。追加时往它里面加,不碰后面那些说明和设置行。 */
    private LinearLayout gridBox;
    private String gridCur;

    private final Map<View, String> wallpaperCells = new LinkedHashMap<>();
    /** 壁纸格子 → 它右上角那个勾。选中态一起刷。 */
    private final Map<View, View> wallpaperBadges = new LinkedHashMap<>();

    /** 按住透出桌面:等待中的那个延时任务,和"正透着"这个状态。 */
    private Runnable pendingHold;
    private boolean previewing;

    private final OnlineWallpapers online;
    /** 橱窗(含没下载的)。先用本地缓存的清单铺,后台刷到新清单再重建一次。 */
    private List<OnlineWallpapers.Remote> remotes = new ArrayList<>();
    /** 这个进程刷过清单没有。清单一周才过期,不必每开一次面板都去问。 */
    private boolean onlineRefreshed;
    /**
     * key → 下载进度。**-1 表示还在排队**(下载是单线程串行的,3Mbps 出口并发只会互抢)。
     * 分开表示是必须的:排队的要是也显示"下载中 0%",看着就跟卡住一模一样 —— 实测踩过。
     */
    private final Map<String, Integer> downloading = new LinkedHashMap<>();
    private static final int QUEUED = -1;
    /**
     * key → 那一次下载的取消开关。**每次下载新建一个,别按 key 复用** —— 取消后马上重点下载,
     * 旧任务(可能还排在队列里)醒来要看的是**自己那个**开关;按 key 去这里取的话拿到的是新的
     * (false),于是新旧两条会同时往同一个 .part 里写。所以 runnable 捕获局部实例,这张表
     * 只给"点一下取消"那条路查。
     */
    private final Map<String, AtomicBoolean> cancels = new LinkedHashMap<>();

    public AppearancePopup(Context ctx) {
        super(ctx);
        setVisibility(GONE);
        wallpapers = new WallpaperStore(ctx);
        online = new OnlineWallpapers(ctx);
        blacklist = new DockBlacklist(ctx);
        icons = new IconEngine(ctx);

        // 遮罩压得很淡:它在这儿只负责"点外面关掉",不负责挡住背景 ——
        // 背景正是要给人看的东西。压太黑就退化成迪友那种"弹层白做"了。
        scrim = new View(ctx);
        scrim.setBackgroundColor(Scrim.LIGHT);
        scrim.setOnClickListener(v -> close());
        addView(scrim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // 全屏铺满,只给底部的 Dock 让出那一条 —— Dock 是悬浮窗,压不住它
        sheet = new LinearLayout(ctx);
        sheet.setOrientation(LinearLayout.HORIZONTAL);
        sheet.setBackground(sheetBg());
        // 只交底色给 ModeApplier,**不加 panel**:那条是给半屏面板叠不透明度用的,
        // 全屏还半透会把背后的壁纸和卡片全透上来,缩略图和字都读不清了。
        // 要看真实效果有"按住透出桌面"那条路,不靠面板本身透。
        sheet.setTag("fill:surface");
        sheet.setElevation(Launchers.dp(ctx, 24));
        // 吃掉落在面板空白处的触摸,免得穿到遮罩上把自己关了
        sheet.setOnTouchListener((v, e) -> true);

        LayoutParams slp = new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        slp.bottomMargin = Launchers.dockSpace(ctx);
        // 顶上同理让出常驻顶栏那条 —— 不让的话「外观」标题和那排筛选 chip 会跟顶栏的
        // 时间糊在一起(顶栏不吃触摸,所以还点得动,但看着就是两层字叠着)
        slp.topMargin = StatusBarHost.barHeight(ctx);
        addView(sheet, slp);

        // ---- 左侧竖排导航 ----
        LinearLayout rail = new LinearLayout(ctx);
        rail.setOrientation(LinearLayout.VERTICAL);
        int rp = Launchers.dp(ctx, 16);
        rail.setPadding(rp, Launchers.dp(ctx, 22), rp, rp);

        TextView title = new TextView(ctx);
        // 叫「设置」不叫「外观」(2026-09-05):这儿早就不只是壁纸配色了 ——
        // 功能开关、Dock 编排、权限都在里面,再叫外观是名不副实
        title.setText("设置");
        title.setTextSize(20f);
        Launchers.medium(title);
        title.setTag("fg:ink");
        title.setTextColor(ModeManager.get().ink());
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = Launchers.dp(ctx, 6);
        tlp.bottomMargin = Launchers.dp(ctx, 20);
        rail.addView(title, tlp);

        tabWallpaper = tab(ctx, "壁纸", R.drawable.ic_nav_wallpaper);
        tabPalette = tab(ctx, "配色", R.drawable.ic_nav_palette);
        tabFeature = tab(ctx, "功能", R.drawable.ic_nav_mode);
        tabDock = tab(ctx, "Dock", R.drawable.ic_nav_dock);
        tabTopBar = tab(ctx, "顶栏", R.drawable.ic_nav_dock);
        tabGrant = tab(ctx, "权限", R.drawable.ic_nav_grant);
        tabTips = tab(ctx, "技巧", R.drawable.ic_nav_tips);
        tabAbout = tab(ctx, "关于", R.drawable.ic_info);
        tabWallpaper.setOnClickListener(v -> switchTab(Tab.WALLPAPER));
        tabPalette.setOnClickListener(v -> switchTab(Tab.PALETTE));
        tabFeature.setOnClickListener(v -> switchTab(Tab.FEATURE));
        tabDock.setOnClickListener(v -> switchTab(Tab.DOCK));
        tabTopBar.setOnClickListener(v -> switchTab(Tab.TOPBAR));
        tabGrant.setOnClickListener(v -> switchTab(Tab.GRANT));
        tabTips.setOnClickListener(v -> switchTab(Tab.TIPS));
        tabAbout.setOnClickListener(v -> switchTab(Tab.ABOUT));
        rail.addView(tabWallpaper);
        rail.addView(tabPalette);
        rail.addView(tabFeature);
        rail.addView(tabDock);
        // 顶栏整个先藏起来,设置入口也不露(见 StatusBarHost.FEATURE_ON)
        if (StatusBarHost.FEATURE_ON) rail.addView(tabTopBar);
        rail.addView(tabGrant);
        rail.addView(tabTips);
        rail.addView(tabAbout);

        sheet.addView(rail, new LinearLayout.LayoutParams(
                Launchers.dp(ctx, RAIL_DP), ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- 右侧:顶栏 + 内容 ----
        LinearLayout right = new LinearLayout(ctx);
        right.setOrientation(LinearLayout.VERTICAL);
        int cp = Launchers.dp(ctx, 24);
        right.setPadding(cp, Launchers.dp(ctx, 18), cp, 0);
        sheet.addView(right, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        headerSlot = new LinearLayout(ctx);
        headerSlot.setOrientation(LinearLayout.HORIZONTAL);
        headerSlot.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(headerSlot, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = new TextView(ctx);
        close.setText("✕");
        close.setTextSize(18f);
        close.setTextColor(ModeManager.get().muted());
        close.setTag("fg:muted");
        close.setGravity(Gravity.CENTER);
        close.setOnClickListener(v -> close());
        int cs = Launchers.dp(ctx, 44);
        header.addView(close, new LinearLayout.LayoutParams(cs, cs));

        right.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        blp.topMargin = Launchers.dp(ctx, 12);
        right.addView(body, blp);
    }

    public void setListener(Listener l) { this.listener = l; }

    public boolean isOpen() { return getVisibility() == VISIBLE; }

    public void open() {
        if (isOpen()) return;
        // 趁还没 VISIBLE 抓一张干净的桌面给技巧页当底(异步,回来时页可能已经建好也可能没有)
        if (snapSource != null) snapSource.capture(s -> {
            snap = s;
            if (tipsGlyph != null && tipsGlyph.isAttachedToWindow()) tipsGlyph.setBase(s);
        });
        setVisibility(VISIBLE);
        switchTab(tab);
        // 上一次可能停在"按住透出桌面"的半路上(手指没抬就关了),这里把透明度收回来
        previewing = false;
        sheet.setAlpha(1f);
        // 从下面滑上来。位移动画不动尺寸,面板里的 TextureView 缩略图不会被判成尺寸变化
        sheet.setTranslationY(sheet.getHeight() > 0 ? sheet.getHeight() : 600);
        Motion.slideTo(sheet, 0f);
        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(160).start();
    }

    /**
     * 面板开着时就地重建当前页。名单从抽屉挑完 app 回来时用 —— 那会儿面板一直是 VISIBLE
     * (抽屉是盖在上面的另一个 Activity,没关掉这面板),{@link #open()} 的 isOpen 早退会
     * 跳过 switchTab,新加的项不刷新。移除走的是面板内 switchTab 所以没这毛病,加要补这一下。
     */
    public void refreshOpenTab() {
        if (isOpen()) switchTab(tab);
    }

    public void close() {
        if (!isOpen()) return;
        if (pendingHold != null) { removeCallbacks(pendingHold); pendingHold = null; }
        previewing = false;
        sheet.setAlpha(1f);
        Motion.slideTo(sheet, sheet.getHeight());
        scrim.animate().alpha(0f).setDuration(160).start();
        postDelayed(() -> setVisibility(GONE), 220);
    }

    // ---- tab ----

    /** 左侧导航的一项。整条 52dp 高、通栏 —— 车上要的是一巴掌拍得中的靶子。 */
    /**
     * 左栏的一项:图标 + 文字。
     *
     * <p>图标走 {@code CompoundDrawable} 而不是自己拼 LinearLayout —— 一个 TextView
     * 就能摆下,选中态那套(背景、文字色、图标 tint)也还是只动这一个 view。
     * tint 在 {@link #markTab} 里跟着选中态走,所以图标源文件一律画成白色。
     */
    private TextView tab(Context ctx, String text, int iconRes) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(16f);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(Launchers.dp(ctx, 14), 0, Launchers.dp(ctx, 12), 0);
        android.graphics.drawable.Drawable d =
                androidx.core.content.ContextCompat.getDrawable(ctx, iconRes);
        if (d != null) {
            int px = Launchers.dp(ctx, 20);
            d = d.mutate();
            d.setBounds(0, 0, px, px);
            t.setCompoundDrawablesRelative(d, null, null, null);
            t.setCompoundDrawablePadding(Launchers.dp(ctx, 10));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Launchers.dp(ctx, 52));
        lp.bottomMargin = Launchers.dp(ctx, 8);
        t.setLayoutParams(lp);
        return t;
    }

    /**
     * 颜色变了:自己就地换肤,再让桌面也换。
     *
     * **不重建弹层**:面板本身不动、不重新滑入,只把里面的内容按新颜色重画一遍。
     * 用户正在这儿一个个试颜色,每点一次弹窗跳一下太晃眼。
     */
    /**
     * **先通知宿主,再重建自己**。顺序不能反 —— 桌面那一步会重算壁纸取色
     * (见 HomeActivity.onPaletteChanged),弹层里 chip 的底和字是代码直接 new 出来的、
     * 换肤那条 tag 通路刷不到,只能靠重建时读到新值。反过来先重建的话,它们全部用上一套
     * 配色算,切到浅色就是一排深底深字 —— 而背景走 tag 被刷新了,于是只有 chip 掉队。
     */
    private void notifyPalette() {
        if (listener != null) listener.onPaletteChanged();
        sheet.setBackground(sheetBg());
        switchTab(tab);   // 只换 body 里的内容,sheet 位置和滑入动画都不动
    }

    private void switchTab(Tab which) {
        tab = which;
        markTab(tabWallpaper, which == Tab.WALLPAPER);
        markTab(tabPalette, which == Tab.PALETTE);
        markTab(tabFeature, which == Tab.FEATURE);
        markTab(tabDock, which == Tab.DOCK);
        markTab(tabTopBar, which == Tab.TOPBAR);
        markTab(tabGrant, which == Tab.GRANT);
        markTab(tabTips, which == Tab.TIPS);
        markTab(tabAbout, which == Tab.ABOUT);
        body.removeAllViews();
        headerSlot.removeAllViews();
        switch (which) {
            case WALLPAPER: buildWallpaperTab(); break;
            case PALETTE:   buildPaletteTab(); break;
            case FEATURE:   buildFeatureTab(); break;
            case DOCK:      buildDockTab();    break;
            case TOPBAR:    buildTopBarTab(); break;
            case GRANT:     buildGrantTab();   break;
            case TIPS:      buildTipsTab();    break;
            case ABOUT:     buildAboutTab();   break;
        }
    }

    private void markTab(TextView t, boolean on) {
        ModeManager tm = ModeManager.get();
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(ModeManager.get().dimPx(getContext(), Dim.RADIUS_KEY));
        gd.setColor(on ? tm.accentSoft() : android.graphics.Color.TRANSPARENT);
        gd.setStroke(Launchers.dp(getContext(), 1),
                on ? tm.accent() : android.graphics.Color.TRANSPARENT);
        t.setBackground(gd);
        int fg = on ? tm.accentText() : tm.muted();
        t.setTextColor(fg);
        // 图标跟文字同色:选中态是"整项亮起来",图标留在灰色会像是没跟上
        for (android.graphics.drawable.Drawable d : t.getCompoundDrawablesRelative()) {
            if (d != null) d.setColorFilter(fg, android.graphics.PorterDuff.Mode.SRC_IN);
        }
    }

    // ---- 技巧 tab ----

    /** 右边正在演示的那条。跨重建保留:换肤重画这一页时还停在同一条上。 */
    private Tips.Tip tipShown;
    /** 真桌面快照:open() 时抓,技巧页当底。抓不到就 null,演示退回线框。 */
    private DeskSnapshot snap;
    private DeskSnapshot.Source snapSource;
    /** 技巧页右边那个演示 view;快照晚于建页到达时要补喂它。 */
    private GestureGlyph tipsGlyph;

    public void setDeskSource(DeskSnapshot.Source src) { snapSource = src; }

    /**
     * 玩机技巧:左边按组列出全部操作,右边演示选中的那条。
     *
     * 只有右边那一个 {@link GestureGlyph} 在动 —— 一页十几个循环动画没必要,也费 CPU。内容按当前模式过滤(见 {@link Tips#forMode}):
     * 模式不给画中画时,那两条不列。
     */
    private void buildTipsTab() {
        final Context ctx = getContext();
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        body.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ScrollView scroll = new ScrollView(ctx);
        scroll.setVerticalScrollBarEnabled(false);
        final LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, 0, 0, Launchers.dp(ctx, 16));
        scroll.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(scroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        // 右边:演示区。一块底色卡,上面是大号演示,下面两行字
        final LinearLayout demo = new LinearLayout(ctx);
        demo.setOrientation(LinearLayout.VERTICAL);
        demo.setGravity(Gravity.CENTER_HORIZONTAL);
        int dpad = Launchers.dp(ctx, 20);
        demo.setPadding(dpad, dpad, dpad, dpad);
        GradientDrawable dbg = new GradientDrawable();
        dbg.setCornerRadius(ModeManager.get().dimPx(ctx, Dim.RADIUS_CARD));
        dbg.setColor(ModeManager.get().tile());
        demo.setBackground(dbg);
        demo.setTag("fill:tile;radius:radius_card");
        final GestureGlyph big = new GestureGlyph(ctx);
        big.setBase(snap);
        tipsGlyph = big;
        demo.addView(big, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Launchers.dp(ctx, 300)));
        final TextView dAction = new TextView(ctx);
        dAction.setTextSize(18f);
        Launchers.medium(dAction);
        dAction.setTextColor(ModeManager.get().ink());
        dAction.setTag("fg:ink");
        dAction.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = Launchers.dp(ctx, 8);
        demo.addView(dAction, alp);
        final TextView dResult = new TextView(ctx);
        dResult.setTextSize(14f);
        dResult.setTextColor(ModeManager.get().muted());
        dResult.setTag("fg:muted");
        dResult.setGravity(Gravity.CENTER);
        dResult.setLineSpacing(0, 1.2f);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = Launchers.dp(ctx, 6);
        demo.addView(dResult, rlp);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                Launchers.dp(ctx, 560), ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.leftMargin = Launchers.dp(ctx, 20);
        dlp.topMargin = Launchers.dp(ctx, 10);
        row.addView(demo, dlp);

        final List<Tips.Tip> tips = Tips.forMode(ModeManager.get().caps());
        final List<Runnable> repaint = new ArrayList<>();
        if (tipShown == null || !tips.contains(tipShown)) tipShown = tips.get(0);
        final Runnable show = () -> {
            big.set(tipShown);
            dAction.setText(tipShown.action);
            dResult.setText(tipShown.result);
            for (Runnable r : repaint) r.run();
        };
        String group = null;
        for (final Tips.Tip t : tips) {
            if (!t.group.equals(group)) {
                group = t.group;
                col.addView(sectionLabel(ctx, group));
            }
            col.addView(tipRow(ctx, t, repaint, () -> { tipShown = t; show.run(); }));
        }
        show.run();
        big.play(true);
        ModeApplier.apply(row);
    }

    /** 列表里的一行:手势名和结果,选中的那行描一圈强调色。演示只在右边那一块,行里不放小图。 */
    private View tipRow(final Context ctx, final Tips.Tip t, List<Runnable> repaint,
                        final Runnable onPick) {
        final LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int ph = Launchers.dp(ctx, 12), pv = Launchers.dp(ctx, 8);
        row.setPadding(ph, pv, ph, pv);
        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView tv = new TextView(ctx);
        tv.setText(t.action);
        tv.setTextSize(16f);
        tv.setTextColor(ModeManager.get().ink());
        tv.setTag("fg:ink");
        texts.addView(tv);
        TextView dv = new TextView(ctx);
        dv.setText(t.result);
        dv.setTextSize(12f);
        dv.setTextColor(ModeManager.get().muted());
        dv.setTag("fg:muted");
        texts.addView(dv);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final Runnable paint = () -> {
            ModeManager tm = ModeManager.get();
            boolean on = tipShown == t;
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(tm.dimPx(ctx, Dim.RADIUS_TILE));
            bg.setColor(on ? tm.accentSoft() : tm.tile());
            bg.setStroke(Launchers.dp(ctx, 1), on ? tm.accent() : tm.line());
            row.setBackground(bg);
        };
        paint.run();
        repaint.add(paint);
        row.setClickable(true);
        Motion.press(row);
        row.setOnClickListener(v -> onPick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Launchers.dp(ctx, 8);
        row.setLayoutParams(lp);
        return row;
    }

    // ---- 壁纸 tab ----

    private void buildWallpaperTab() {
        Context ctx = getContext();
        wallpaperCells.clear();
        wallpaperBadges.clear();
        coverPending.clear();

        ensureSourceAllowed();
        for (Source s : Source.values()) {
            // 模式不给在线壁纸时连这个筛选都不摆 —— 摆个点进去是空的没意义
            if (s == Source.ONLINE && !onlineAllowed()) continue;
            headerSlot.addView(sourceChip(ctx, s));
        }
        for (Form f : Form.values()) headerSlot.addView(formChip(ctx, f));

        ScrollView scroll = new ScrollView(ctx);
        wallpaperScroll = scroll;
        scroll.setVerticalScrollBarEnabled(false);
        // 滚到哪儿下哪儿的封面(pumpCovers),滚到底再建下一批格子(maybeGrowGrid)
        scroll.getViewTreeObserver().addOnScrollChangedListener(() -> {
            pumpCovers();
            maybeGrowGrid();
        });
        // 底部留一截:面板下边缘就贴着 Dock,不留白的话最后一行像是被切掉了
        scroll.setPadding(0, 0, 0, Launchers.dp(ctx, 16));
        scroll.setClipToPadding(false);
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        List<Entry> items = filtered();
        gridItems.clear();
        gridShown = 0;
        gridBox = null;
        if (items.isEmpty()) {
            col.addView(emptyHint(ctx));
        } else {
            // 格子单独装一个容器:后面还要往里追加,而它下面那些说明和设置行不能被挤乱
            gridItems.addAll(items);
            gridCur = wallpapers.current();
            gridBox = new LinearLayout(ctx);
            gridBox.setOrientation(LinearLayout.VERTICAL);
            col.addView(gridBox, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            growGrid();          // 只建第一批,剩下的滚到了再说
        }

        col.addView(personalHint(ctx, wallpapers.personal().isEmpty()));

        // 下载来的在线壁纸占的地方,连同清理入口一起摆在这一页最底下 ——
        // 它是壁纸自己的维护,跟"选哪张"是同一件事的两头(2026-08-16 从设置页搬来)
        col.addView(settingRow(ctx, "已下载的在线壁纸", "清掉本地副本,橱窗里还能再下",
                () -> {
                    long b = online.downloadedBytes();
                    return b == 0 ? "无" : mb(b);
                },
                () -> online.downloadedBytes() > 0,
                v -> {
                    long freed = online.clearDownloaded();
                    Launchers.toast(getContext(), freed == 0 ? "没有可清理的" : "已清理 " + mb(freed));
                    switchTab(Tab.WALLPAPER);   // 清完列表里那些"已下载"的标记要跟着变
                }));
        ModeApplier.apply(col);

        loadOnline();
        // 头一屏那几张:等布局量出来再判可见,不然这会儿高度还是 0
        scroll.post(this::pumpCovers);
    }

    /**
     * 把**滚进视野的**那些格子的图弄出来:缺封面的先下,然后解码,完事铺回去。
     * 滚动时和页面铺完各泵一次。
     *
     * 判定放宽了上下各半屏:等真滚到才开始办,人已经在看着空格子了。
     * 每张只发一次({@code started}),好了就地铺回那个格子 —— 不重建整页,
     * 重建会把用户的滚动位置和正在下载的进度条一起弄没。
     *
     * <b>下载和解码都在后台线程</b>,主线程只负责 setImageBitmap。
     */
    private void pumpCovers() {
        if (wallpaperScroll == null || coverPending.isEmpty()) return;
        final int h = wallpaperScroll.getHeight();
        if (h <= 0) return;
        int[] sLoc = new int[2], cLoc = new int[2];
        wallpaperScroll.getLocationOnScreen(sLoc);
        for (final ThumbJob p : coverPending) {
            if (p.started || p.cell.getHeight() <= 0) continue;
            p.cell.getLocationOnScreen(cLoc);
            int top = cLoc[1] - sLoc[1];
            int bottom = top + p.cell.getHeight();
            if (bottom < -h / 2 || top > h + h / 2) continue;   // 离视野还远
            p.started = true;
            OnlineWallpapers.prefetch().execute(() -> {
                // 在线项的封面可能还没下 —— 抽不出来就不解了(网断了、源站没这张)
                if (p.remote != null && !online.hasCover(p.remote) && !online.fetchCover(p.remote)) {
                    return;
                }
                final android.graphics.Bitmap bmp = wallpapers.load(p.id, p.reqW, p.reqH);
                if (bmp == null) return;
                THUMBS.put(p.id + "@" + p.reqW, bmp);
                post(() -> {
                    // 页面可能已经重建了(切筛选/切 tab),那这张就交给新页面去铺 ——
                    // 它建格子时会先问缓存,而图刚刚已经放进去了
                    if (p.thumb.getParent() == null) return;
                    p.thumb.setImageBitmap(bmp);
                });
            });
        }
    }

    /**
     * 当前筛选下要显示的壁纸。内置 → 我的 → 在线,和面板里的顺序一致。
     *
     * **「全部」只收能用的**:没下载的橱窗项只在「在线」里出现。于是两个词各有各的意思 ——
     * 「全部」= 我现在能选的所有壁纸(下载完那张就"进来了"),「在线」= 整个库,是个橱窗。
     * 混在一起的话,下载前后那张都在「全部」里躺着,只是遮罩没了,人分不清到底下没下。
     */
    private List<Entry> filtered() {
        List<Entry> out = new ArrayList<>();
        // 内置的永远收:它没有自己那个 chip 了(装机自带的东西没什么好单独看的,
        // 「全部」里就能看见),改由格子上一个「内置」小标去说明出处。
        if (source == Source.ALL) {
            for (WallpaperStore.Item it : wallpapers.official()) out.add(new Entry(it));
        }
        if (source == Source.ALL || source == Source.PERSONAL) {
            for (WallpaperStore.Item it : wallpapers.personal()) out.add(new Entry(it));
        }
        if (source == Source.ALL || source == Source.ONLINE) {
            // 橱窗才摆没下载的;**不给在线壁纸时只摆已下载的** —— 下过的壁纸是本地文件,
            // 照样能用能选,只是进不了橱窗拿新的(见 Capabilities.onlineWallpaper)
            boolean showcase = source == Source.ONLINE && onlineAllowed();
            for (OnlineWallpapers.Remote r : remotes) {
                boolean got = online.isDownloaded(r);
                if (got || showcase) out.add(new Entry(r, got));
            }
        }
        if (form == null) return out;

        List<Entry> kept = new ArrayList<>();
        for (Entry e : out) {
            if (form.matches(e)) kept.add(e);
        }
        return kept;
    }

    /**
     * 先用本地缓存的清单铺上,再到后台看要不要刷新(过期才真去下,见 OnlineWallpapers.refresh)。
     * 封面谁滚进视野谁才抽(见 pumpCovers)。
     */
    private void loadOnline() {
        if (!onlineAllowed()) return;
        if (remotes.isEmpty()) remotes = online.cached();
        if (onlineRefreshed) return;
        onlineRefreshed = true;
        // 走 prefetch 不走 io:用户随时可能点下载,那一下不该排在刷清单后面
        OnlineWallpapers.prefetch().execute(() -> {
            final List<OnlineWallpapers.Remote> fresh = online.refresh();
            if (fresh == null) return;
            post(() -> {
                remotes = fresh;
                if (isOpen() && tab == Tab.WALLPAPER) switchTab(Tab.WALLPAPER);
            });
        });
    }

    /** 在线壁纸橱窗给不给。见 {@code Capabilities.onlineWallpaper}。 */
    private boolean onlineAllowed() {
        return ModeManager.get().caps().onlineWallpaper;
    }

    /** 上次停在「在线」而现在模式不给在线壁纸了:那个筛选已经没了,回落到「全部」。 */
    private void ensureSourceAllowed() {
        if (source == Source.ONLINE && !onlineAllowed()) source = Source.ALL;
    }

    private View sourceChip(Context ctx, final Source s) {
        TextView t = chipButton(ctx, s.label, s == source);
        if (s != source) {
            t.setOnClickListener(v -> {
                source = s;
                switchTab(Tab.WALLPAPER);   // 重建这一页:筛选变了,网格整个换
            });
        }
        return t;
    }

    /**
     * 形态那根轴。是**开关**不是单选项 —— 它和左边三个来源不是一类东西,别混着排。
     *
     * 三个开关**互斥**,合起来是一根轴上的四态(不限 / 图片 / 视频 / 交互)。
     * 用开关而不是单选项,是因为"再点一下取消"就等于"不限",省掉一个「全部」chip。
     *
     * 格子左上角还有一份形态小标(见 cellTag):筛选是"我要找哪种",小标是
     * "这张是哪种",两件事都要。
     */
    private View formChip(Context ctx, final Form f) {
        TextView t = chipButton(ctx, "只看" + f.tag, form == f);
        t.setOnClickListener(v -> {
            form = (form == f) ? null : f;   // 再点一下取消 = 不限
            switchTab(Tab.WALLPAPER);
        });
        // 和来源那组拉开距离,一眼看出是另一件事。三个形态开关之间不留这个距离,
        // 它们本来就是一组。
        if (f == Form.IMAGE) {
            ((LinearLayout.LayoutParams) t.getLayoutParams()).setMarginStart(Launchers.dp(ctx, 22));
        }
        return t;
    }

    /**
     * 壁纸格子左上角那种小标。压一层半透明黑 —— 白字要在任意封面上都读得清,
     * 而封面什么颜色都有,只有自己带底才稳(和未下载那层遮罩同一个理由)。
     */
    private TextView cellTag(Context ctx, String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(10f);
        t.setTextColor(0xFFFFFFFF);
        int gh = Launchers.dp(ctx, 6), gv = Launchers.dp(ctx, 3);
        t.setPadding(gh, gv, gh, gv);
        GradientDrawable gb = new GradientDrawable();
        gb.setCornerRadius(ModeManager.get().dimPx(ctx, Dim.RADIUS_CHIP));
        gb.setColor(0x99000000);
        t.setBackground(gb);
        t.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    private TextView chipButton(Context ctx, String text, boolean on) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(14f);
        t.setGravity(Gravity.CENTER);
        int ph = Launchers.dp(ctx, 18), pv = Launchers.dp(ctx, 8);
        t.setPadding(ph, pv, ph, pv);
        ModeManager tm = ModeManager.get();
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(ModeManager.get().dimPx(ctx, Dim.RADIUS_TILE));
        gd.setColor(on ? tm.accentSoft() : tm.tile());
        gd.setStroke(Launchers.dp(ctx, 1), on ? tm.accent() : tm.line());
        t.setBackground(gd);
        t.setTextColor(on ? tm.accentText() : tm.muted());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(Launchers.dp(ctx, 10));
        t.setLayoutParams(lp);
        return t;
    }

    /**
     * 壁纸网格。**只竖着滚**:以前每组一条横滑、又套在竖滚里,车上手指一抖就滑错方向。
     * 格子按可用宽度平分,16:9 —— 和屏幕同比例,缩略图里看到的构图就是铺上去的构图。
     */
    /** 再建一批格子。建完顺手泵一次封面 —— 新的那几行可能已经在视野里了。 */
    private void growGrid() {
        if (gridBox == null || gridShown >= gridItems.size()) return;
        int end = Math.min(gridItems.size(), gridShown + GRID_PAGE);
        grid(gridBox, gridItems.subList(gridShown, end), gridCur);
        gridShown = end;
        // 新建的格子没经过 buildWallpaperTab 末尾那一发,自己过一遍换肤
        ModeApplier.apply(gridBox);
        post(this::pumpCovers);
    }

    /**
     * 滚到快见底了就再来一批。
     *
     * 阈值给一整屏:等真滚到底再建,人会先看见一段空白再看见图冒出来 ——
     * 提前一屏建好,滚下去时它已经在那儿了。
     */
    private void maybeGrowGrid() {
        if (wallpaperScroll == null || gridBox == null || gridShown >= gridItems.size()) return;
        View content = wallpaperScroll.getChildAt(0);
        if (content == null) return;
        int rest = content.getHeight() - wallpaperScroll.getHeight() - wallpaperScroll.getScrollY();
        if (rest <= wallpaperScroll.getHeight()) growGrid();
    }

    /**
     * 铺一批格子。**每批都是整行**({@link #GRID_PAGE} 是列数的整数倍),
     * 所以追加的那批直接从新行起排,不用记上一批排到第几列。
     */
    private void grid(LinearLayout parent, List<Entry> items, String cur) {
        Context ctx = getContext();
        int gap = Launchers.dp(ctx, 16);
        int avail = getResources().getDisplayMetrics().widthPixels
                - Launchers.dp(ctx, RAIL_DP) - Launchers.dp(ctx, 48);   // 内容区左右 padding
        int cellW = (avail - gap * (GRID_COLS - 1)) / GRID_COLS;
        int cellH = cellW * 9 / 16;

        LinearLayout row = null;
        for (int i = 0; i < items.size(); i++) {
            if (i % GRID_COLS == 0) {
                row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.bottomMargin = gap;
                parent.addView(row, rlp);
            }
            Entry it = items.get(i);
            View cell = chip(it, it.id.equals(cur), cellW, cellH);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(cellW, cellH);
            if (i % GRID_COLS != GRID_COLS - 1) clp.setMarginEnd(gap);
            cell.setLayoutParams(clp);
            row.addView(cell);
        }
    }

    /**
     * 申请读存储权限(只为"我的壁纸"这一处)。
     *
     * 权限只能由 Activity 发起,而本弹层是挂在桌面上的 View —— 所以要把宿主 Activity 找出来。
     * 结果不用等回调:系统弹框期间面板还在,用户点完"允许"回来会重建这一页,那时自然就读到了。
     */
    private void requestStoragePermission() {
        Context c = getContext();
        while (c instanceof android.content.ContextWrapper && !(c instanceof android.app.Activity)) {
            c = ((android.content.ContextWrapper) c).getBaseContext();
        }
        if (!(c instanceof android.app.Activity)) return;
        ((android.app.Activity) c).requestPermissions(
                new String[]{ android.Manifest.permission.READ_EXTERNAL_STORAGE }, REQ_STORAGE);
    }

    /** 存储权限的请求码。桌面收到结果后重建外观面板(见 HomeActivity.onRequestPermissionsResult)。 */
    public static final int REQ_STORAGE = 4021;

    /** 授权结果回来时让壁纸页重建一次 —— 刚授的权要当场生效,不该等用户自己再切一下。 */
    public void refreshWallpaperTab() {
        if (isOpen() && tab == Tab.WALLPAPER) switchTab(Tab.WALLPAPER);
    }

    private View emptyHint(Context ctx) {
        TextView t = new TextView(ctx);
        t.setText("这个筛选下还没有壁纸");
        t.setTextSize(14f);
        t.setTag("fg:muted");
        t.setTextColor(ModeManager.get().muted());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Launchers.dp(ctx, 40);
        t.setLayoutParams(lp);
        return t;
    }

    private View personalHint(Context ctx, boolean empty) {
        TextView hint = new TextView(ctx);
        hint.setTag("fg:faint");
        hint.setTextColor(ModeManager.get().faint());
        hint.setTextSize(12f);
        hint.setLineSpacing(Launchers.dp(ctx, 2), 1f);
        if (!wallpapers.canReadPersonal()) {
            /*
             * **这句以前是个死胡同**:告诉用户"需要存储权限",却没有任何地方能授权 ——
             * app 至今没申请过这个权限,车上只能靠 adb 授,等于"我的壁纸"整个是死的。
             * 现在点这行就发起申请。(在线壁纸不受影响,它落在私有目录,零权限。)
             */
            hint.setText("我的壁纸:需要存储权限才能读取 " + WallpaperStore.personalDirLabel()
                    + "\n点这里授权");
            hint.setClickable(true);
            hint.setOnClickListener(v -> requestStoragePermission());
        } else if (empty) {
            // 别在这儿提 tools/wallpaper-video.sh —— 那是仓库里的开发脚本,
            // 车上的用户既不知道是什么、也没法运行。只说清楚要求,让他自己去准备素材
            hint.setText("我的壁纸:把图片或视频放进 " + WallpaperStore.personalDirLabel()
                    + " 就会出现在这里\n视频建议 1920×1080、30 帧以内 —— 再高车机也显示不出来,还更容易卡顿");
        } else if (source == Source.ONLINE) {
            // 「下下来的去哪了」是真会被问到的:在线壁纸存在 app 自己的目录里,不进用户那个文件夹,
            // 所以这里直接说清楚它下完出现在哪、以及怎么清
            hint.setText("下载后就能在「全部」里选到,不会占用 " + WallpaperStore.personalDirLabel()
                    + "\n占用多少、怎么清理,在设置页「壁纸」里");
        } else {
            hint.setText("我的壁纸放在 " + WallpaperStore.personalDirLabel()
                    + "   ·   按住任意一张可以看真实效果");
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Launchers.dp(ctx, 4);
        lp.bottomMargin = Launchers.dp(ctx, 16);
        hint.setLayoutParams(lp);
        return hint;
    }

    /**
     * 一格壁纸。点一下=选中并应用;按住=面板淡出,露出背后真实的桌面。
     *
     * 在线且**还没下载**的那些走另一条:点一下先下载(格子上显示进度),下完了才谈应用。
     * 缩略图不受影响 —— 封面是跟着橱窗单独下的,没下正片也照样有图看。
     */
    private View chip(final Entry item, boolean selected, int w, int h) {
        Context ctx = getContext();
        final boolean needDownload = item.remote != null && !item.downloaded;

        FrameLayout cell = new FrameLayout(ctx);
        cell.setBackground(chipFrame(selected));

        ImageView thumb = new ImageView(ctx);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        /*
         * 缩略图按 480px 上限解,不按格子的真实像素要 —— 那样会绕过 WallpaperStore 的首帧缓存
         * (它只缓存 480 及以下),一屏十几个没有封面的视频同时抽帧,解码器不够用,
         * 抽出来就是绿条纹(那个坑在 WallpaperStore.firstFrame 的注释里)。
         * 480 拉到 530 宽肉眼看不出来,值这个换。
         */
        int reqW = Math.min(w, THUMB_MAX_PX);
        int reqH = reqW * 9 / 16;
        // **建格子时不解码。** 解一张 480px 封面是磁盘读 + JPEG 解码,一百多格全压在
        // 主线程上就是点一下「在线」卡一下(2026-08-23 实测 104 张)。缓存里现成的直接铺,
        // 没有的排队等滚进视野,在后台线程连下带解(见 pumpCovers)
        android.graphics.Bitmap cached = THUMBS.get(item.id + "@" + reqW);
        if (cached != null) {
            thumb.setImageBitmap(cached);
        } else {
            coverPending.add(new ThumbJob(item.remote, item.id, cell, thumb, reqW, reqH));
        }
        thumb.setClipToOutline(true);
        thumb.setBackground(chipFrame(false));
        int p = Launchers.dp(ctx, 3);
        cell.addView(thumb, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        cell.setPadding(p, p, p, p);

        View badge = checkBadge(ctx);
        badge.setVisibility(selected ? VISIBLE : GONE);
        int bs = Launchers.dp(ctx, 30);
        LayoutParams blp = new LayoutParams(bs, bs);
        blp.gravity = Gravity.END | Gravity.TOP;
        blp.setMarginEnd(Launchers.dp(ctx, 10));
        blp.topMargin = Launchers.dp(ctx, 10);
        cell.addView(badge, blp);

        // 未下载:压一层"↓ 点这下载"。已在下的显示百分比 + 取消的去处
        final TextView state = new TextView(ctx);
        if (needDownload) {
            Integer pct = downloading.get(item.remote.key);
            state.setText(pct == null ? sizeLabel(item.remote) : progressLabel(pct));
            state.setTextSize(12f);
            state.setGravity(Gravity.CENTER);
            state.setTextColor(0xFFFFFFFF);
            state.setBackgroundColor(0x99000000);   // 压一层黑,白字在任何封面上都读得清
            cell.addView(state, new LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        // 左上角一排小标。第一个永远是**形态**(图片/视频/交互) —— 三种壁纸在桌面上的
        // 脾气完全不同(交互的能点、而且不参与左右滑切换),扫一眼就该知道是哪种。
        // 后面跟**出处**:「内置」是因为它没有自己的 chip 了(装机自带的没什么好单独看的),
        // 「已下载」则是 —— **没遮罩**只说明"不用再等",不等于看得出"我下过",
        // 橱窗里扫一眼就该知道哪些已经在本地了,不必靠回忆。
        LinearLayout tags = new LinearLayout(ctx);
        tags.setOrientation(LinearLayout.HORIZONTAL);
        tags.addView(cellTag(ctx, Form.of(item).tag));
        String from = item.official ? "内置"
                : (item.remote != null && item.downloaded) ? "已下载" : null;
        if (from != null) {
            View f = cellTag(ctx, from);
            ((LinearLayout.LayoutParams) f.getLayoutParams())
                    .setMarginStart(Launchers.dp(ctx, 5));
            tags.addView(f);
        }
        LayoutParams tlp = new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.gravity = Gravity.START | Gravity.TOP;
        tlp.setMarginStart(Launchers.dp(ctx, 10));
        tlp.topMargin = Launchers.dp(ctx, 10);
        cell.addView(tags, tlp);

        // 底部压一条名字。光靠封面认不出哪张是哪张 —— 尤其网页壁纸,好几张都是深色底。
        // 不给它自己的实色底,而是压一层**从透明到黑的渐变**:实色底像贴了块牌子,
        // 渐变是照片上打字幕的做法,画面不被切断。下面两角跟着格子圆,不然渐变会露出直角。
        TextView title = new TextView(ctx);
        title.setText(item.label);
        title.setTextSize(12f);
        title.setTextColor(0xFFFFFFFF);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        int nh = Launchers.dp(ctx, 9), nv = Launchers.dp(ctx, 7);
        title.setPadding(nh, Launchers.dp(ctx, 14), nh, nv);   // 上边留够,让渐变有地方淡出
        float r = ModeManager.get().dimPx(ctx, Dim.RADIUS_TILE);
        GradientDrawable scrim = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0xCC000000});
        scrim.setCornerRadii(new float[]{0, 0, 0, 0, r, r, r, r});
        title.setBackground(scrim);
        LayoutParams nlp = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.gravity = Gravity.BOTTOM;
        cell.addView(title, nlp);

        wallpaperCells.put(cell, item.id);
        wallpaperBadges.put(cell, badge);

        cell.setClickable(true);
        cell.setOnClickListener(v -> {
            if (!needDownload) { applyWallpaper(item); return; }
            // 正在下(或排着队)的,这一下是取消。不问第二遍 —— 点错了重点一下就是了,
            // 而下载要一两分钟,想停却停不下来才是真难受
            if (downloading.containsKey(item.remote.key)) cancelDownload(item.remote, state);
            else startDownload(item.remote, state);
        });

        final float[] down = new float[2];
        final int slop = android.view.ViewConfiguration.get(ctx).getScaledTouchSlop();
        cell.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (needDownload) break;   // 还没下下来,没什么可预览的
                    down[0] = e.getRawX();
                    down[1] = e.getRawY();
                    // 先应用再淡出 —— 透出来的得是这一张的效果,不是上一张的。
                    // 判 isOpen:面板在计时途中被关掉的话,这一下不能再去改壁纸
                    pendingHold = () -> {
                        if (!isOpen()) return;
                        applyWallpaper(item);
                        startPreview();
                    };
                    v.postDelayed(pendingHold, HOLD_MS);
                    break;
                case MotionEvent.ACTION_MOVE:
                    /*
                     * **手指一动就取消计时**。只等 UP/CANCEL 是不够的:手指按下先停顿一下
                     * 再滑(车上很常见),320ms 一到就会当成"按住",于是壁纸被换掉、面板还淡出去,
                     * 人只是想滚列表。ScrollView 抢走手势后会补一个 CANCEL,但那要等划过
                     * touch slop,停顿期间它不会来。
                     */
                    if (pendingHold != null
                            && (Math.abs(e.getRawX() - down[0]) > slop
                                || Math.abs(e.getRawY() - down[1]) > slop)) {
                        v.removeCallbacks(pendingHold);
                        pendingHold = null;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (pendingHold != null) { v.removeCallbacks(pendingHold); pendingHold = null; }
                    stopPreview();
                    break;
            }
            return false;   // 不吃事件:轻点照常触发 onClick,竖滑照常滚列表
        });
        return cell;
    }

    /** 选中角标。描边之外再给一个勾 —— 车上离屏幕远,只靠一圈描边看不出选没选中。 */
    private View checkBadge(Context ctx) {
        ModeManager tm = ModeManager.get();
        TextView t = new TextView(ctx);
        t.setText("✓");
        t.setTextSize(14f);
        t.setGravity(Gravity.CENTER);
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(tm.accent());
        t.setBackground(gd);
        t.setTextColor(tm.onAccent());
        return t;
    }

    /**
     * 下这一张。**下完不自动应用** —— 用户点的是"我要这张",但下载要一两分钟(3Mbps 限速),
     * 这期间他多半已经在看别的了,忽然把桌面换掉是吓人的。下完只把格子变成可选状态。
     */
    private void startDownload(final OnlineWallpapers.Remote r, final TextView state) {
        if (downloading.containsKey(r.key)) return;       // 已经在队里了,别开第二条
        // 先记成"排队中"。下载是串行的,前面还有别人时如实说,别装成"下载中 0%"
        downloading.put(r.key, QUEUED);
        state.setText(progressLabel(QUEUED));
        // **捕获这个实例**,后面一律认它、不去 cancels 里按 key 现查(理由见 cancels 的注释)
        final AtomicBoolean cancel = new AtomicBoolean(false);
        cancels.put(r.key, cancel);
        OnlineWallpapers.io().execute(() -> {
            // 排队期间被取消的,轮到自己时直接走人。UI 那边点的那一下已经收拾干净了
            if (cancel.get()) return;
            post(() -> {                                   // 轮到自己了才改口
                // 判还在排队:进度回调可能比这个 post 先到,那时别把百分比又抹回 0
                if (cancel.get()) return;
                Integer cur = downloading.get(r.key);
                if (cur != null && cur == QUEUED) {
                    downloading.put(r.key, 0);
                    state.setText(progressLabel(0));
                }
            });
            // 下之前先读一次规格:清单里的 4K 标志不全准,漏网的 4K 下了也放不了
            if (!online.playable(r)) {
                post(() -> {
                    if (cancel.get()) return;
                    downloading.remove(r.key);
                    cancels.remove(r.key);
                    if (!isOpen()) return;
                    Launchers.toast(getContext(), "「" + r.name + "」是 4K,车机放不了");
                    remotes = online.cached();      // 它已进黑名单,重建后就不摆了
                    if (tab == Tab.WALLPAPER) switchTab(Tab.WALLPAPER);
                });
                return;
            }
            final boolean ok = online.download(r, (done, total) -> {
                if (total <= 0 || cancel.get()) return;    // 迟到的回调别把已还原的文案刷回去
                int pct = (int) (done * 100 / total);
                Integer last = downloading.get(r.key);
                if (last != null && pct == last) return;   // 只在百分比真的变了时才回主线程
                downloading.put(r.key, pct);
                post(() -> { if (!cancel.get()) state.setText(progressLabel(pct)); });
            }, cancel);
            post(() -> {
                // 取消的收尾归点击那一下(它可能已经重开了一条,这里再动表就把新的删了)
                if (cancel.get()) return;
                downloading.remove(r.key);
                cancels.remove(r.key);
                if (!isOpen()) return;
                if (ok) {
                    if (tab == Tab.WALLPAPER) switchTab(Tab.WALLPAPER);   // 重建:这格变成可选了
                } else {
                    state.setText("下载失败,点重试");
                    Launchers.toast(getContext(), "「" + r.name + "」下载失败");
                }
            });
        });
    }

    /**
     * 停掉这一张。**表在这里就清干净**,不等后台线程收尾 —— 用户可能立刻又点一次重下,
     * 那时旧任务多半还没走到检查点,它醒来时不该再碰这两张表(那删的就是新任务的条目了)。
     */
    private void cancelDownload(final OnlineWallpapers.Remote r, final TextView state) {
        AtomicBoolean cancel = cancels.remove(r.key);
        if (cancel != null) cancel.set(true);
        downloading.remove(r.key);
        state.setText(sizeLabel(r));
    }

    /** 没下过的那层遮罩上写的字。清单里没有体积,只写个下载。 */
    private static String sizeLabel(OnlineWallpapers.Remote r) {
        return "↓ 下载";
    }

    /**
     * 下载中那层遮罩上写的字。**第二行必须写出取消的去处** ——
     * 光显示百分比的话,没人会想到"再点一下"能停。
     */
    private static String progressLabel(int pct) {
        return (pct == QUEUED ? "排队中" : "下载中 " + pct + "%") + "\n点这取消";
    }

    /** 选中并铺上去。已经是这张就什么都不做 —— 免得按住预览时又重启一遍播放器。 */
    private void applyWallpaper(Entry item) {
        String id = item.id;
        if (id.equals(wallpapers.current())) return;
        wallpapers.set(id);
        if (item.personal && wallpapers.isOversized(id)) {
            Launchers.toast(getContext(),
                    "这个视频超出车机解码能力,可能几秒后卡住;建议先压到 1080p/30fps");
        }
        // 只改描边和勾,不重建列表 —— 重建会让滚动位置弹回顶部
        markSelection();
        if (listener != null) listener.onWallpaperChanged();
    }

    /** 按住:把整个面板淡掉,露出背后真实的桌面(壁纸在播、卡片和 Dock 都在)。 */
    private void startPreview() {
        pendingHold = null;
        if (previewing) return;
        previewing = true;
        sheet.animate().alpha(0f).setDuration(140).start();
        scrim.animate().alpha(0f).setDuration(140).start();
    }

    private void stopPreview() {
        if (!previewing) return;
        previewing = false;
        sheet.animate().alpha(1f).setDuration(160).start();
        scrim.animate().alpha(1f).setDuration(160).start();
    }

    private void markSelection() {
        String cur = wallpapers.current();
        for (Map.Entry<View, String> e : wallpaperCells.entrySet()) {
            boolean on = e.getValue().equals(cur);
            e.getKey().setBackground(chipFrame(on));
            View badge = wallpaperBadges.get(e.getKey());
            if (badge != null) badge.setVisibility(on ? VISIBLE : GONE);
        }
    }

    private GradientDrawable chipFrame(boolean selected) {
        ModeManager tm = ModeManager.get();
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(ModeManager.get().dimPx(getContext(), Dim.RADIUS_TILE));
        gd.setColor(tm.tile2());
        gd.setStroke(Launchers.dp(getContext(), selected ? 3 : 1),
                selected ? tm.accent() : tm.line());
        return gd;
    }

    // ---- 配色 tab ----

    /**
     * 可选的强调色。**12 个 token 里只开放这一个**(理由见 ModeManager.accent())。
     *
     * 给一排选好的颜色而不是取色盘:车上手指粗、车还在动,在色轮上精确拖一个点不现实,
     * 而且随手取出来的颜色多半和这套底色不搭。这些是挑过的 —— 饱和度和明度都落在
     * 能压住深底也能压住浅底的区间里。
     *
     * <h3>2026-08-14 收窄:这排候选曾经是「配色乱」的真正源头</h3>
     *
     * 原来 11 个里有三个是**语义色本身或它的近邻**:{@code #38BDF8}(当时的制冷色,
     * 一模一样)、{@code #F97316} 橙和 {@code #E7A94E} 琥珀(逼近制热色 {@code #FBBF24})。
     * 模式 json 里的 accent 早就避开了语义色,但那只约束了模式作者 ——
     * 用户在这里点一下就能配出"强调色 = 制冷色"的界面,「这个功能开着」和「现在在制冷」
     *
     * 现在全部落在**蓝→紫→玫红**这段色相里,和冷暖语义色(青绿 / 琥珀 / 红,
     * 见 {@link ThermalPalette})隔开;末尾留一个中性灰给想要极克制的人。
     * **往这里加颜色前先对一遍 ThermalPalette**,别再把语义色放进来。
     */
    private static final int[] ACCENTS = {
            0xFF006CDE,  // 鸿蒙蓝(模式默认)
            0xFF3F97E9,  // 亮蓝 —— 鸿蒙自己的文字档色号
            0xFF5B6CF0,  // 靛
            0xFF7C5CE0,  // 紫
            0xFFA855F7,  // 亮紫
            0xFFE0407F,  // 玫红
            0xFF8E8E93,  // 石板灰(想要极克制时用;鸿蒙 datapanel 里的中性档)
    };

    private void buildPaletteTab() {
        Context ctx = getContext();
        ScrollView scroll = new ScrollView(ctx);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 一、底色。三档:深色 / 浅色 / 跟随壁纸。
        //
        // **这里不提模式** —— 换的只是颜色,不动能力(见 paletteCard 的说明)。
        // 「跟随壁纸」原来是单独一节「面板底色」,2026-08-16 并进来:那一节实际只有
        // 两格(跟随配色/跟随壁纸),而"跟随配色"跟这里选深浅是同一件事,分成两节
        // 反倒要人先想明白两者什么关系。
        col.addView(sectionLabel(ctx, "底色"));
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Mode dark = null, light = null;
        for (Mode m : ModeManager.listAll(ctx)) {
            if (androidx.core.graphics.ColorUtils.calculateLuminance(m.bg) < 0.5) {
                if (dark == null) dark = m;
            } else if (light == null) {
                light = m;
            }
        }
        boolean byWall = ModeManager.get().surfaceMode() == ModeManager.SURFACE_WALLPAPER;
        boolean curDark = androidx.core.graphics.ColorUtils
                .calculateLuminance(ModeManager.get().paletteMode().bg) < 0.5;
        if (dark != null) row.addView(paletteCard(ctx, dark, "深色", !byWall && curDark));
        if (light != null) row.addView(paletteCard(ctx, light, "浅色", !byWall && !curDark));
        row.addView(wallpaperCard(ctx, byWall));
        HorizontalScrollView hs = new HorizontalScrollView(ctx);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(row);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = Launchers.dp(ctx, 8);
        col.addView(hs, hlp);

        // 二、强调色
        col.addView(sectionLabel(ctx, "强调色"));
        LinearLayout dots = new LinearLayout(ctx);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.addView(accentDot(ctx, 0, ModeManager.get().accentOverride() == 0));  // 跟随底色
        for (int c : ACCENTS) {
            dots.addView(accentDot(ctx, c, ModeManager.get().accentOverride() == c));
        }
        HorizontalScrollView ds = new HorizontalScrollView(ctx);
        ds.setHorizontalScrollBarEnabled(false);
        ds.addView(dots);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = Launchers.dp(ctx, 8);
        col.addView(ds, dlp);

        // 三、卡片透明度。2026-09-05 从「卡片」栏搬过来(那一栏整个删了,剩下的
        // 显隐开关跟编辑态里加卡/点 × 是重复入口)。
        // **和 Dock 分开一档**:卡片浮在壁纸上,透了看到的是画;Dock 那条压在屏幕底沿,
        // 透出来的是壁纸的下沿 —— 同一档在两处观感差得远
        col.addView(sectionLabel(ctx, "卡片透明度"));
        LinearLayout cops = new LinearLayout(ctx);
        cops.setOrientation(LinearLayout.HORIZONTAL);
        int curCard = DeskCardStore.opacity(ctx);
        for (int o : OPACITIES) {
            cops.addView(opacityChip(ctx, o, curCard == o,
                    v -> DeskCardStore.setOpacity(getContext(), v)));
        }
        HorizontalScrollView cs = new HorizontalScrollView(ctx);
        cs.setHorizontalScrollBarEnabled(false);
        cs.addView(cops);
        LinearLayout.LayoutParams clp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp2.topMargin = Launchers.dp(ctx, 8);
        col.addView(cs, clp2);

        // 四、Dock 透明度
        col.addView(sectionLabel(ctx, "Dock 透明度"));
        LinearLayout ops = new LinearLayout(ctx);
        ops.setOrientation(LinearLayout.HORIZONTAL);
        int curOp = DockHost.opacity(ctx);
        for (int o : OPACITIES) {
            ops.addView(opacityChip(ctx, o, curOp == o,
                    v -> DockHost.setOpacity(getContext(), v)));
        }
        HorizontalScrollView os = new HorizontalScrollView(ctx);
        os.setHorizontalScrollBarEnabled(false);
        os.addView(ops);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        olp.topMargin = Launchers.dp(ctx, 8);
        col.addView(os, olp);

        TextView hint = new TextView(ctx);
        hint.setText("强调色是整个桌面唯一的重点色。选中态、开关、图标高亮都跟着它走 ——\n"
                + "配套的柔和底和压在上面的文字色会自动算好,不用也不能单独挑。");
        hint.setTextSize(12f);
        hint.setTextColor(ModeManager.get().faint());
        hint.setLineSpacing(Launchers.dp(ctx, 2), 1f);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = Launchers.dp(ctx, 18);
        col.addView(hint, tlp);
    }

    private TextView sectionLabel(Context ctx, String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(12f);
        Launchers.medium(t);
        t.setTextColor(ModeManager.get().muted());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Launchers.dp(ctx, 14);
        t.setLayoutParams(lp);
        return t;
    }

    /**
     * 一张底色卡:**卡片自己就是预览**。背景是这套的底色、名字用这套的文字色,
     * 右边一个圆点是这套的强调色 —— 深浅、字色、主色三件事说完了。
     *
     * 这里试过两版更"丰富"的画法,都退回来了:
     *   一排色号圆点(accent/ink/tile2/line)—— 摆出来等于在说"这些都能改",
     *     可实际只有强调色和面板底色开放,是误导;而且没人看得出那几个点分别是什么。
     *   迷你 Dock 缩影 —— 150dp 宽的小卡片里,一个圆角条加三个点根本认不出是 Dock,
     *     用户只会问"这三个颜色干嘛用的"(原话)。
     * 结论:小卡片装不下示意图,能表达的就这么多,多画的都是噪声。
     */
    private View paletteCard(Context ctx, final Mode t, String label, boolean current) {
        int w = Launchers.dp(ctx, 150), h = Launchers.dp(ctx, 64);
        FrameLayout cell = new FrameLayout(ctx);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(ModeManager.get().dimPx(ctx, Dim.RADIUS_TILE));
        bg.setColor(t.bg);
        bg.setStroke(Launchers.dp(ctx, current ? 2 : 1),
                current ? ModeManager.get().accent() : ModeManager.get().line());
        cell.setBackground(bg);

        LinearLayout inner = new LinearLayout(ctx);
        inner.setOrientation(LinearLayout.HORIZONTAL);
        inner.setGravity(Gravity.CENTER_VERTICAL);
        int p = Launchers.dp(ctx, 12);
        inner.setPadding(p, p, p, p);

        TextView name = new TextView(ctx);
        // **不写模式名**。配色跟模式是两件事:选底色时提模式名,会让人以为
        // 这里在切模式(能力),而它只换颜色。按底色自己的明暗叫「浅色/深色」,所见即所得。
        name.setText(label);
        name.setTextSize(14f);
        name.setTextColor(t.ink);        // 用它自己的文字色 —— 深浅底一眼可辨
        inner.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View dot = new View(ctx);
        GradientDrawable dg = new GradientDrawable();
        dg.setShape(GradientDrawable.OVAL);
        dg.setColor(t.accent);
        dot.setBackground(dg);
        int ds = Launchers.dp(ctx, 18);
        inner.addView(dot, new LinearLayout.LayoutParams(ds, ds));
        cell.addView(inner);

        if (!current) {
            cell.setClickable(true);
            cell.setOnClickListener(v -> {
                ModeManager.get().setPalette(getContext(), t.id);
                // 面板底色一并定回"跟配色":不然选了深色、面板还在跟壁纸取色,
                // 看着仍是灰的 —— 用户点的是"底色",他要的是整套跟着变
                ModeManager.get().setSurfaceMode(ModeManager.SURFACE_MODE);
                notifyPalette();
            });
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.setMarginEnd(Launchers.dp(ctx, 10));
        cell.setLayoutParams(lp);
        return cell;
    }

    /**
     * 「跟随壁纸」那一档:面板底色从当前壁纸取色(色相跟着画面,明暗和饱和度是钉死的,
     * 见 {@link com.liubai.blank.data.WallpaperStore#bottomTint})。
     *
     * 配色骨架(文字色、强调色)按壁纸的明暗自动挑深色套还是浅色套 —— 壁纸暗就用深色那套,
     * 否则白字压在浅色面板上没法看。
     */
    private View wallpaperCard(Context ctx, boolean current) {
        int tint = ModeManager.get().wallpaperTint();
        int preview = tint != 0 ? tint : ModeManager.get().paletteMode().surface;
        int w = Launchers.dp(ctx, 150), h = Launchers.dp(ctx, 64);
        FrameLayout cell = new FrameLayout(ctx);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(ModeManager.get().dimPx(ctx, Dim.RADIUS_TILE));
        bg.setColor(preview);
        bg.setStroke(Launchers.dp(ctx, current ? 2 : 1),
                current ? ModeManager.get().accent() : ModeManager.get().line());
        cell.setBackground(bg);

        TextView name = new TextView(ctx);
        name.setText("跟随壁纸");
        name.setTextSize(14f);
        boolean lightBg = androidx.core.graphics.ColorUtils.calculateLuminance(preview) > 0.4;
        name.setTextColor(lightBg ? 0xFF1A1A1A : 0xFFECEEEF);
        int p = Launchers.dp(ctx, 12);
        name.setPadding(p, p, p, p);
        cell.addView(name, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        cell.setClickable(true);
        cell.setOnClickListener(v -> {
            // 骨架跟着壁纸明暗走:壁纸暗配深色套,亮配浅色套。
            // 这里必须测壁纸本身的亮度(wallpapers.isDark),不能读 wallpaperTint() ——
            // 后者的明暗是按当前配色钉死的(见 WallpaperStore.bottomTint),读它等于
            // 拿"现在是深是浅"来回答"现在是深是浅",不管壁纸长什么样都会保持原样。
            boolean wallDark = wallpapers.isDark(wallpapers.current());
            for (Mode m : ModeManager.listAll(getContext())) {
                boolean mDark = androidx.core.graphics.ColorUtils.calculateLuminance(m.bg) < 0.5;
                if (mDark == wallDark) {
                    ModeManager.get().setPalette(getContext(), m.id);
                    break;
                }
            }
            ModeManager.get().setSurfaceMode(ModeManager.SURFACE_WALLPAPER);
            notifyPalette();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.setMarginEnd(Launchers.dp(ctx, 10));
        cell.setLayoutParams(lp);
        return cell;
    }

    /**
     * 不透明度的档位。Dock 那节和「面板 / 弹窗」那节共用这一套档位(值共用,选中的档各存各的)。
     *
     * 10% 一档,一路给到 0(无底:背景全透,图标直接浮在壁纸上 —— 配一张干净壁纸时很好看)。
     * 仍然不做成连续滑块:车上手指粗、车还在动,滑块拖不准,而且拖的过程中每一帧都要
     * 重画 Dock。给定档反而更快到位 —— 一行放不下就横向滚。
     */
    private static final int[] OPACITIES = { 100, 90, 80, 70, 60, 50, 40, 30, 20, 0 };

    /**
     * 一档透明度。Dock 和「面板 / 弹窗」两节共用这个 chip,靠 panel 参数分岔。
     *
     * 改完不重建桌面 —— Dock 是悬浮窗自己刷背景;面板和弹窗走 reskin 那条既有的换肤路径
     * (它们的最外层底都标了 panel,见 ModeApplier)。整屏重建反而看不出前后差别。
     *
     * @param panel true=改面板/弹窗那档,false=改 Dock 那档
     */
    private View opacityChip(Context ctx, final int percent, boolean selected,
                             final java.util.function.IntConsumer apply) {
        TextView c = new TextView(ctx);
        c.setText(percent == 100 ? "不透明" : percent == 0 ? "无底" : percent + "%");
        c.setTextSize(14f);
        c.setGravity(Gravity.CENTER);
        int ph = Launchers.dp(ctx, 16), pv = Launchers.dp(ctx, 8);
        c.setPadding(ph, pv, ph, pv);
        ModeManager tm = ModeManager.get();
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(ModeManager.get().dimPx(ctx, Dim.RADIUS_TILE));
        gd.setColor(selected ? tm.accentSoft() : tm.tile());
        // 选中一律 2dp accent —— 和底色卡、面板底色 chip 同一套(见 accentDot 的说明)
        gd.setStroke(Launchers.dp(ctx, selected ? 2 : 1), selected ? tm.accent() : tm.line());
        c.setBackground(gd);
        c.setTextColor(selected ? tm.accentText() : tm.muted());
        c.setClickable(true);
        c.setOnClickListener(v -> {
            apply.accept(percent);
            notifyPalette();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(Launchers.dp(ctx, 10));
        c.setLayoutParams(lp);
        return c;
    }

    /** 一个强调色圆点。color 传 0 表示「跟随底色」那一格。 */
    private View accentDot(Context ctx, final int color, boolean selected) {
        int size = Launchers.dp(ctx, 52);
        FrameLayout cell = new FrameLayout(ctx);

        View dot = new View(ctx);
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(color == 0 ? ModeManager.get().paletteMode().accent : color);
        // 「跟随底色」那格描一圈虚边区分:它不是一个具体颜色,是"不覆盖"
        if (color == 0) gd.setStroke(Launchers.dp(ctx, 2), ModeManager.get().line());
        dot.setBackground(gd);
        int ds = Launchers.dp(ctx, selected ? 40 : 32);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(ds, ds);
        dlp.gravity = Gravity.CENTER;
        cell.addView(dot, dlp);

        if (selected) {
            /*
             * 选中的套一圈环,车机上比"变大一点"更明确。
             *
             * **环用 accent,不用 ink**:这一屏里选中态一共四处(底色卡、强调色圆点、
             * 面板底色 chip、透明度 chip),规则统一成「2dp accent 描边」—— 原来这里
             * 是白环、别处是蓝框,同一个面板两种选中语言,一眼就觉得不是一套东西。
             *
             * 选中的正好是 accent 那一格时环和点同色,不影响辨识:点 40dp、环 52dp,
             * 中间留着 6dp 间隙,读出来是"带光环的点",和未选中的 32dp 素点仍然分得开。
             */
            View ring = new View(ctx);
            GradientDrawable rg = new GradientDrawable();
            rg.setShape(GradientDrawable.OVAL);
            rg.setColor(0x00000000);
            rg.setStroke(Launchers.dp(ctx, 2), ModeManager.get().accent());
            ring.setBackground(rg);
            FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(size, size);
            rlp.gravity = Gravity.CENTER;
            cell.addView(ring, rlp);
        }

        cell.setClickable(true);
        cell.setOnClickListener(v -> {
            ModeManager.get().setAccentOverride(color);
            notifyPalette();
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.setMarginEnd(Launchers.dp(ctx, 8));
        cell.setLayoutParams(lp);
        return cell;
    }

    // ---- Dock tab ----

    /**
     * 底部那条 Dock:摆什么、什么时候露面。
     *
     * 这一页连同画中画开关、音乐源那几项,2026-08-16 从「桌面设置」那个独立 Activity
     * 搬过来的 —— 那个页面第一条就是"点我去外观面板",自己在往这边指;两处都在管
     * 「桌面长什么样、怎么用」,分开只是让人多记一个地方。设置页已删。
     *
     * **Dock 里摆什么不在这儿做**:增删排序是在真 Dock 上直接拖的(长按设置键进编辑态),
     * 这里只给一条看得见的入口 —— 那条路原先只有"长按"这一个暗示。
     */
    private void buildDockTab() {
        Context ctx = getContext();
        ScrollView scroll = new ScrollView(ctx);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        col.addView(sectionLabel(ctx, "内容"));
        col.addView(settingRow(ctx, "编辑 Dock", null,
                () -> "去编辑 ›", () -> false, v -> {
                    if (listener != null) listener.onEditDock();
                    close();   // 编辑对象就是下面那条 Dock,面板得让开
                }));

        col.addView(sectionLabel(ctx, "什么时候露面"));

        // Dock 常驻。悬浮窗挂不上时如实说"不可用",别让开关看着是开的却没效果
        col.addView(settingRow(ctx, "Dock 常驻", "浮在所有应用之上",
                () -> {
                    if (!DockHost.get(getContext()).attached()) return "需悬浮窗权限";
                    return DockHost.alwaysOn(getContext()) ? "常驻" : "仅桌面";
                },
                () -> DockHost.alwaysOn(getContext()) && DockHost.get(getContext()).attached(),
                v -> {
                    Context c = getContext();
                    boolean on = !DockHost.alwaysOn(c);
                    // 关掉不需要权限,开启才要。**没提权就是真开不了**,那种情况下别把开关拨过去
                    // —— 界面显示"常驻"而实际没有,比不给这个开关还糟。
                    if (on && !DockHost.get(c).attach()) {
                        if (!Shell.ready()) {
                            Launchers.toast(c, "常驻需要提权,或手动授予悬浮窗权限");
                            return;
                        }
                        Launchers.toast(c, "正在开启常驻…");
                    }
                    DockHost.setAlwaysOn(c, on);
                }));

        col.addView(sectionLabel(ctx, "顶上那条"));
        // 原厂状态栏:留白自己的页面默认压掉它,这里给个口子露出来(时间/车内外温度/信号都在上面)。
        // 别的 app 一直都有那条栏,这个开关只管留白自己的页面;游戏页永远全屏,不受它管
        col.addView(settingRow(ctx, "原厂状态栏", "在留白的页面上露出来,内容往下让一条",
                () -> Immersive.oemStatusBar(getContext()) ? "显示" : "隐藏",
                () -> Immersive.oemStatusBar(getContext()),
                v -> {
                    Context c = getContext();
                    Immersive.setOemStatusBar(c, !Immersive.oemStatusBar(c));
                    // 系统栏认的是当前焦点窗口的 flag,得在桌面的 DecorView 上重设一次;
                    // 顶部那段让位跟着变,同顶栏开关那行
                    Immersive.apply(getRootView());
                    StatusBarHost.applyTopSpace(getRootView());
                    switchTab(Tab.DOCK);
                }));

        col.addView(sectionLabel(ctx, "这些应用打开时收起 Dock"));
        // 让位名单要靠前台包名判定,没有前台监听就整块不生效 —— 如实说,
        // 别让用户以为加进去了就会生效
        col.addView(appList(ctx,
                ForegroundWatch.running() ? "默认不隐藏任何应用,要让位的自己加"
                                          : "需要「前台监听」才生效,当前未启用",
                blacklist.load(), Pick.DOCK_HIDE, pkg -> blacklist.remove(pkg)));

        ModeApplier.apply(col);
    }

    // ---- 顶栏 tab ----

    /**
     * 顶栏:要不要这条栏 + 上面放哪几项。
     *
     * <h3>为什么编排只能在这儿做</h3>
     *
     * 顶栏窗口带 {@code FLAG_NOT_TOUCHABLE}(见 {@link StatusBarHost} 的类注释:它压在
     * 第三方 app 的顶部操作区上,吃了触摸就是把人家的按钮点死),所以**没法像 Dock 那样
     * 在栏上直接拖**。DiStyle 也是这么分的 —— 它的顶栏编排在设置里的
     * {@code CustomStatusBarFragment},不在栏上。
     *
     * <h3>怎么编排</h3>
     *
     * 预览条上按住拖 = 换顺序、× = 拿掉;候选区按住拖到预览条上 = 插到指定那一格、
     * 点一下 = 加到末尾(见 {@link StatusBarEditor})。**中间那段永远空着**,真车顶部
     * 正中是语音球的开孔(见 {@link com.liubai.blank.data.StatusBarStore})。
     */
    private void buildTopBarTab() {
        Context ctx = getContext();
        ScrollView scroll = new ScrollView(ctx);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        col.addView(sectionLabel(ctx, "要不要这条栏"));
        col.addView(settingRow(ctx, "顶部状态栏", "关掉就看原厂那条(留白从不压它)",
                () -> {
                    Context c = getContext();
                    if (!StatusBarHost.enabled(c)) return "不显示";
                    return StatusBarHost.get(c).attached() ? "显示" : "需悬浮窗权限";
                },
                () -> StatusBarHost.enabled(getContext())
                        && StatusBarHost.get(getContext()).attached(),
                v -> {
                    Context c = getContext();
                    StatusBarHost.setEnabled(c, !StatusBarHost.enabled(c));
                    // 页面顶部那段让位跟着变 —— 顶栏不在还留着,页面顶上就白挂一条空白。
                    // 只能在这儿补:barHeight 变了没有任何广播,各页面靠自己 onResume 兜
                    StatusBarHost.applyTopSpace(getRootView());
                    switchTab(Tab.TOPBAR);
                }));

        // 编排:预览条上按住拖着换顺序、× 拿掉,下面点一下加(见 StatusBarEditor)
        StatusBarEditor editor = new StatusBarEditor(ctx);
        editor.setListener(() -> StatusBarHost.get(getContext()).onItemsChanged());
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.topMargin = Launchers.dp(ctx, 6);
        col.addView(editor, elp);

        col.addView(settingRow(ctx, "恢复默认编排", "时间 · 日期 · 撑开 · 车内温度 · 蓝牙 · Wi-Fi",
                () -> "恢复 ›", () -> false, v -> {
                    editor.reset();
                    StatusBarHost.get(getContext()).onItemsChanged();
                }));

        ModeApplier.apply(col);
    }

    // ---- 权限 tab ----

    /**
     * 一键授权。
     *
     * 2026-08-18 加的,同时删掉了桌面上那条"留白还不是默认桌面 · 点此设为默认"的提示丸 ——
     * 在壁纸上常驻一条自我推销不是解决办法,把该授的收进一处、一次点完才是。
     *
     * 页面就两件事:**顶上一个按钮**把缺的全授掉,**下面逐项列真实状态**。
     * 每一项都能单独再点一次(补授那一项),因为整趟里可能只差一项,而重写全局设置
     * 是有代价的(无障碍/通知那两份名单是全车共享的,见 Grant)。
     *
     * 提权没就绪时**不藏按钮也不假装成功**:按下去会如实说"提权未就绪",
     * 底下那行也写着为什么 —— 通道自己授不了自己(见 Grant 的类注释)。
     */
    private void buildGrantTab() {
        Context ctx = getContext();
        ScrollView scroll = new ScrollView(ctx);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        col.addView(sectionLabel(ctx, "一次授完"));
        col.addView(settingRow(ctx, "一键授权",
                "下面缺的几项一次点完,不用去翻系统设置",
                () -> {
                    if (granting) return "授权中…";
                    List<Grant.Item> miss = Grant.missing(getContext());
                    if (miss.isEmpty()) return "都齐了";
                    return "缺 " + miss.size() + " 项 ›";
                },
                () -> Grant.missing(getContext()).isEmpty(),
                v -> grantAll()));

        col.addView(sectionLabel(ctx, "提权通道"));
        // 通道是所有项的前提,单独列一行:授不动的时候人得知道卡在哪一环
        col.addView(settingRow(ctx, "提权",
                Shell.ready() ? "内置自连 adb 优先,装了 Shizuku 也能用"
                        : "连不上:车机的无线调试没打开,或 Shizuku 没启动",
                () -> Shell.channel(),
                () -> Shell.ready(),
                // 点它只是就地刷新一遍状态(settingRow 点完会自己重画)。
                // 不弹 toast:连不上的原因写在这一行的说明里,弹一条压在 Dock 上更碍眼
                v -> { }));

        col.addView(sectionLabel(ctx, "逐项"));
        for (final Grant.Item item : Grant.Item.values()) {
            col.addView(settingRow(ctx, item.title, item.why,
                    () -> Grant.granted(getContext(), item) ? "已授权" : "未授权",
                    () -> Grant.granted(getContext(), item),
                    v -> grantOne(item)));
        }

        ModeApplier.apply(col);
    }

    // ---- 关于 tab ----

    /** 「检查更新」那一行右边显示什么。查之前是"检查 ›",查完换成结果。 */
    private String updateState = "检查 ›";
    /** 查到的新版本。非 null 时再点那一行就是"去下载",不再重查。 */
    private AppUpdater.Info pendingUpdate;
    private boolean checkingUpdate;

    /**
     * 关于:版本号 + 在线更新。
     *
     * 更新走的是「查到新版 → 自带浏览器下载 → 用户自己点安装」,留白不自己装
     * (见 {@link AppUpdater} 类注释)。
     */
    private void buildAboutTab() {
        Context ctx = getContext();
        ScrollView scroll = new ScrollView(ctx);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        col.addView(sectionLabel(ctx, "版本"));
        col.addView(settingRow(ctx, "留白",
                "括号里那个数是打包时的提交数 —— 报问题时带上它就能定位到具体是哪一版",
                () -> AppUpdater.localVersion(),
                () -> true,
                v -> { }));

        col.addView(sectionLabel(ctx, "在线更新"));
        col.addView(settingRow(ctx, "检查更新",
                "有新版就用自带浏览器下载,下完在浏览器底部点「安装」",
                () -> checkingUpdate ? "检查中…" : updateState,
                () -> pendingUpdate != null,
                v -> checkUpdate()));

        ModeApplier.apply(col);
    }

    /**
     * 点「检查更新」。已经查到新版了就直接把下载弹层再叫出来 —— 那时候这一行写的是
     * "有新版 v… ›",人点它是想去下载,不是想再查一遍。
     */
    private void checkUpdate() {
        if (pendingUpdate != null) {
            AppUpdater.prompt(getContext(), pendingUpdate);
            return;
        }
        if (checkingUpdate) return;
        checkingUpdate = true;
        if (isOpen() && tab == Tab.ABOUT) switchTab(Tab.ABOUT);   // 先把"检查中…"贴上去
        AppUpdater.check(getContext(), (newer, netErr) -> {
            checkingUpdate = false;
            pendingUpdate = newer;
            updateState = netErr ? "连不上" : newer == null ? "已是最新" : "有新版 v" + newer.versionName + " ›";
            if (isOpen() && tab == Tab.ABOUT) switchTab(Tab.ABOUT);
            // 查到了就顺手把下载弹层推到面前:人刚点完「检查更新」,正等着一个结果
            if (newer != null && isOpen()) AppUpdater.prompt(getContext(), newer);
        });
    }

    /**
     * 正在授权。只为把「一键授权」那一行的状态换成"授权中…" ——
     * 整趟几百毫秒,不给反馈就像点了没反应。
     */
    private boolean granting;

    /**
     * 一键:授完把这一页重建一次,每行的状态都是现查的(见 settingRow 的 State)。
     *
     * **一句 toast 都不弹**:反馈就在眼前那几行里 —— 哪项授上了,右边那个标就亮了。
     * 而 toast 在 Android 12 以后一律压在屏幕底部、setGravity 被忽略,正好糊在 Dock 上
     * (2026-08-18 用户当场看见,原话是"怎么还有提示在 dock 的位置")。
     */
    private void grantAll() {
        if (granting) return;
        granting = true;
        if (isOpen() && tab == Tab.GRANT) switchTab(Tab.GRANT);   // 先把"授权中…"贴上去
        Grant.all(getContext(), new Grant.Progress() {
            @Override public void onItem(Grant.Item item, boolean ok) { }

            @Override public void onDone(List<Grant.Item> missing) {
                post(() -> {
                    granting = false;
                    // 面板可能已经被关掉了(整趟要几百毫秒),开着才重建
                    if (isOpen() && tab == Tab.GRANT) switchTab(Tab.GRANT);
                    // 只差默认桌面:有的 ROM 连 shell 也拒(见 Listener.onSetDefaultHome),
                    // 把系统那个选择页推到人面前,别让他自己去找
                    if (missing.size() == 1 && missing.get(0) == Grant.Item.DEFAULT_HOME
                            && listener != null) {
                        listener.onSetDefaultHome();
                    }
                });
            }
        });
    }

    /** 补授一项。同样不弹 toast,结果看那一行自己。 */
    private void grantOne(final Grant.Item item) {
        if (granting || Grant.granted(getContext(), item)) return;
        Grant.one(getContext(), item, ok -> post(() -> {
            if (isOpen() && tab == Tab.GRANT) switchTab(Tab.GRANT);
            // 默认桌面授不上就退回系统页面(理由见 Listener.onSetDefaultHome)
            if (!ok && item == Grant.Item.DEFAULT_HOME && listener != null) {
                listener.onSetDefaultHome();
            }
        }));
    }

    // ---- 搬自设置页的通用件 ----

    /** 状态文字由调用方现算 —— 权限、提权这些随时会变,不能在建行的时候定死。 */
    private interface State { String text(); }
    /** 这一行现在算不算"开着"(决定状态标高不高亮)。 */
    private interface Lit { boolean on(); }
    private interface OnRemove { void remove(String pkg); }

    /**
     * 一行设置:名字 + 说明 + 右边一个状态标。整行可点,不用去够那个标。
     *
     * 样式跟 {@link #cardRow} 一套 —— **全项目没有 Switch 控件**,行车中一眼认字
     * 比认滑块的位置快。点完就地重刷这一行,不重建整页(重建会把滚动位置弹回顶上)。
     */
    private View settingRow(Context ctx, String title, String desc,
                            final State state, final Lit lit, final View.OnClickListener onClick) {
        final LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int ph = Launchers.dp(ctx, 14), pv = Launchers.dp(ctx, 12);
        row.setPadding(ph, pv, ph, pv);

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        final TextView tv = new TextView(ctx);
        tv.setText(title);
        tv.setTextSize(16f);
        texts.addView(tv);
        if (desc != null) {
            TextView dv = new TextView(ctx);
            dv.setText(desc);
            dv.setTextSize(12f);
            dv.setTextColor(ModeManager.get().muted());
            texts.addView(dv);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView sv = new TextView(ctx);
        sv.setTextSize(14f);
        int sh = Launchers.dp(ctx, 14), svp = Launchers.dp(ctx, 5);
        sv.setPadding(sh, svp, sh, svp);
        row.addView(sv);

        final Runnable paint = () -> {
            ModeManager tm = ModeManager.get();
            boolean on = lit.on();
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(ModeManager.get().dimPx(ctx, Dim.RADIUS_TILE));
            bg.setColor(tm.tile());
            bg.setStroke(Launchers.dp(ctx, 1), on ? tm.accent() : tm.line());
            row.setBackground(bg);
            tv.setTextColor(on ? tm.ink() : tm.muted());

            sv.setText(state.text());
            sv.setTextColor(on ? tm.accentText() : tm.muted());
            GradientDrawable sbg = new GradientDrawable();
            sbg.setCornerRadius(Launchers.dp(ctx, 20));
            sbg.setColor(on ? tm.accentSoft() : tm.tile());
            sbg.setStroke(Launchers.dp(ctx, 1), on ? tm.accent() : tm.line());
            sv.setBackground(sbg);
        };
        paint.run();

        row.setClickable(true);
        Motion.press(row);
        row.setOnClickListener(v -> {
            onClick.onClick(v);
            paint.run();
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Launchers.dp(ctx, 10);
        row.setLayoutParams(lp);
        return row;
    }

    /**
     * 一张 app 名单:一句说明 + 「+ 添加」+ 一排图标(长按移除)。
     *
     * 音乐源和 Dock 让位名单是同一种形态,所以合成一个 —— 两处各写一遍的话,
     * 以后改样式要改两个地方(设置页时代就是这么共用的)。
     */
    private View appList(Context ctx, String hint, final List<String> pkgs,
                         final Pick what, final OnRemove onRemove) {
        final LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Launchers.dp(ctx, 14);
        box.setPadding(p, p, p, p);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(ModeManager.get().dimPx(ctx, Dim.RADIUS_TILE));
        bg.setColor(ModeManager.get().tile());
        bg.setStroke(Launchers.dp(ctx, 1), ModeManager.get().line());
        box.setBackground(bg);

        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView hv = new TextView(ctx);
        hv.setText(hint);
        hv.setTextSize(12f);
        hv.setTextColor(ModeManager.get().muted());
        head.addView(hv, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView add = new TextView(ctx);
        add.setText("+ 添加");
        add.setTextSize(14f);
        int ah = Launchers.dp(ctx, 14), av = Launchers.dp(ctx, 5);
        add.setPadding(ah, av, ah, av);
        add.setTextColor(ModeManager.get().accentText());
        GradientDrawable abg = new GradientDrawable();
        abg.setCornerRadius(Launchers.dp(ctx, 20));
        abg.setColor(ModeManager.get().accentSoft());
        abg.setStroke(Launchers.dp(ctx, 1), ModeManager.get().accent());
        add.setBackground(abg);
        Motion.press(add);
        add.setOnClickListener(v -> {
            if (listener != null) listener.onPickAppFor(what);
        });
        head.addView(add);
        box.addView(head);

        if (!pkgs.isEmpty()) {
            LinearLayout strip = new LinearLayout(ctx);
            strip.setOrientation(LinearLayout.HORIZONTAL);
            for (final String pkg : pkgs) {
                strip.addView(appChip(ctx, pkg, onRemove));
            }
            HorizontalScrollView hs = new HorizontalScrollView(ctx);
            hs.setHorizontalScrollBarEnabled(false);
            hs.addView(strip);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = Launchers.dp(ctx, 12);
            box.addView(hs, slp);

            TextView tip = new TextView(ctx);
            tip.setText("长按图标移除");
            tip.setTextSize(11f);
            tip.setTextColor(ModeManager.get().faint());
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.topMargin = Launchers.dp(ctx, 8);
            box.addView(tip, tlp);
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Launchers.dp(ctx, 10);
        box.setLayoutParams(lp);
        return box;
    }

    private View appChip(Context ctx, final String pkg, final OnRemove onRemove) {
        LinearLayout item = new LinearLayout(ctx);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);

        final String label = appLabel(ctx, pkg);
        ImageView icon = new ImageView(ctx);
        icon.setImageDrawable(icons.iconFor(new AppRef(pkg, "", label)));
        int is = Launchers.dp(ctx, 40);
        item.addView(icon, new LinearLayout.LayoutParams(is, is));

        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextColor(ModeManager.get().muted());
        tv.setTextSize(12f);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                Launchers.dp(ctx, 64), ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = Launchers.dp(ctx, 4);
        item.addView(tv, tlp);

        item.setOnLongClickListener(v -> {
            onRemove.remove(pkg);
            Launchers.toast(getContext(), "已移除 " + label);
            switchTab(tab);       // 就地重建当前页,名单少一个
            return true;
        });

        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.setMarginEnd(Launchers.dp(ctx, 16));
        item.setLayoutParams(ilp);
        return item;
    }

    /** 字节数说成人话。 */
    private static String mb(long bytes) {
        return bytes < 1048576 ? Math.max(1, bytes / 1024) + "KB"
                               : String.format("%.0fMB", bytes / 1048576f);
    }

    private static String appLabel(Context ctx, String pkg) {
        try {
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    // ---- 功能 tab ----

    /**
     * 功能开关。两个都是全局单值,settingRow 点完会自己重刷那一行,这里只管改值。
     */
    private void buildFeatureTab() {
        Context ctx = getContext();
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);

        col.addView(settingRow(ctx, "画中画", null,
                () -> {
                    Context c = getContext();
                    if (!PipCard.available(c)) return "需提权";
                    return PipCard.enabled(c) ? "已开" : "已关";
                },
                () -> PipCard.available(getContext()) && PipCard.enabled(getContext()),
                v -> {
                    Context c = getContext();
                    if (!PipCard.available(c)) {
                        // 没提权就别让开关看着能开却没效果——说清楚缺什么
                        Launchers.toast(c, "需要先激活 Shizuku 提权");
                        return;
                    }
                    PipCard.setEnabled(c, !PipCard.enabled(c));
                }));

        col.addView(settingRow(ctx, "动效", null,
                () -> ModeManager.motionOn(getContext()) ? "已开" : "已关",
                () -> ModeManager.motionOn(getContext()),
                v -> {
                    Context c = getContext();
                    ModeManager.setMotionOn(c, !ModeManager.motionOn(c));
                    // 动效是各个 view 建的时候读进去的,改完要让桌面把卡片和 Dock 重挂一遍
                    if (listener != null) listener.onMotionChanged();
                }));

        ScrollView page = new ScrollView(ctx);
        page.setVerticalScrollBarEnabled(false);
        page.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(page, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ModeApplier.apply(body);
    }

    /**
     * 全屏面板的底。铺满整屏,不留圆角描边 —— 那是半屏面板才需要的"浮起来"的暗示。
     * **实色**,不叠面板不透明度:理由见构造函数里那段 setTag。
     */
    private GradientDrawable sheetBg() {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(ModeManager.get().surface());
        return gd;
    }
}
