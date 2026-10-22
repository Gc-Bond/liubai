package com.liubai.blank.game;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.liubai.blank.mode.ModeManager;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 游戏中心的选游戏页。
 *
 * <h3>骨架照外观面板</h3>
 * 全屏 + 左侧竖排导航 + 网格。车机是 16:9 横屏,横向宽得富余、竖向只有 720dp ——
 * 导航排左边,竖向空间全留给内容。左栏每条 52dp 通栏,车上要的是一巴掌拍得中的靶子。
 *
 * <h3>和壁纸网格的两点不同</h3>
 * <b>四列不是三列</b>:壁纸格子要看清效果所以给得大,游戏卡片主要是认名字,小一档能多摆几个
 * —— 一包 ROM 动辄一百多个,每屏多四个就少翻四分之一。
 * <b>4:3 不是 16:9</b>:封面是游戏画面,老游戏都是 4:3(NES 256×240、街机 4:3)。
 *
 * <h3>没封面就是纯色块</h3>
 * ROM 不像壁纸自带缩略图,一张图都没有。封面有两个来源:**配套图库**(KAWAKS 那套
 * sshots,按名字配对不看路径)和**在线缩略图**(yikm 列表页自带,三五 KB)。
 * 两样都没有的就是色块,色按平台分,一台机器一个色。
 *
 * 曾经还有第三条路 —— 玩过一次就把画面首帧存下来当封面。**那条已经废掉**:
 * 游戏刚起来那几秒多半是黑的,核心缺 BIOS 时还会把那屏英文错误页固化成封面,
 * 而现成图库一张不落(KAWAKS 那套街机命中 91/91)。
 */
public class GameListView extends FrameLayout {

    public interface Listener {
        void onPick(GameLibrary.Game g);
        /** 这台机器的模拟器没装,用户点了"去装"。 */
        void onNeedCore(GameLibrary.Platform p);
        /**
         * 再给一页。
         *
         * 在线列表是分页的,跟网站上一样 —— 一次只拉 10 个,底部「加载更多」按钮
         * 或滚到底触发这个回调去拉下一页。本地列表没有这个回调
         * (本地有多少就是多少,一次全摆)。
         */
        void onLoadMore();
        /**
         * 搜索框有字了(云端列表):去网站搜,结果回填 {@link #setSearchHits}。
         * 空串/本地列表不发 —— 本地就本地过滤,网站搜不到手工推的包。
         */
        void onSearch(String q);
        /** 点了类型分类(「动作冒险」「格斗」…)。g 为 null = 回全部。 */
        void onGenre(OnlineStore.Genre g);
        /** 切到了非本地平台(FC/街机):去抓该平台的类型分类。 */
        void onTab(GameLibrary.Platform p);
        /** 「本地」页选中了几个游戏要删。删之前该问一句,由外面弹确认框。 */
        void onDelete(List<GameLibrary.Game> games);
    }

    /** 左栏宽度。放得下四个中文加一个数字。 */
    private static final int RAIL_DP = 132;
    /**
     * 五列。四列时一屏只摆得下 12 个,一包 ROM 一百五十个要翻十二屏;
     * 六列的格子又小到看不清名字。五列每格约 207dp,一屏 20 个,翻七屏到底。
     */
    private static final int COLS = 5;

    // ---- 配色一律走模式系统,别写死 ----
    //
    // 这一屏原先是一整套硬编码的深紫(BG()=ON_ACCENT() 那些),于是浅色模式下整个
    // 游戏中心还是深的,和桌面、面板、弹窗全不搭 —— 尤其确认弹窗是跟着模式走的,
    // 白框压在深紫底上,一眼就看出是两拨人做的。
    //
    // 语义对应:整页底=bg、左栏=surface(它就是一块面板)、选中态=accentSoft、
    // 输入框和按钮底=tile、描边=line、主/次文字=ink/muted。
    // **每次 refresh 都重建 view 树**,所以取值方法就够了,不用额外的换肤钩子。
    private static int BG()      { return ModeManager.get().bg(); }
    private static int RAIL_BG() { return ModeManager.get().surface(); }
    private static int INK()     { return ModeManager.get().ink(); }
    private static int MUTED()   { return ModeManager.get().muted(); }
    private static int SEL()     { return ModeManager.get().accentSoft(); }
    private static int TILE()    { return ModeManager.get().tile(); }
    private static int LINE()    { return ModeManager.get().line(); }
    private static int ACCENT()  { return ModeManager.get().accent(); }
    /** 强调色底上的字(选中的类型胶囊、角标)。 */
    private static int ON_ACCENT() { return ModeManager.get().onAccent(); }
    /**
     * **压在封面图上的字**(卡片上的游戏名、体积):固定白 + 一道压暗渐变,
     * 不跟模式走 —— 图片本身不会因为换了模式就变亮变暗,跟着走的话浅色模式下
     * 就是深色字压深色封面,整屏名字全看不见(实测)。
     */
    private static final int ON_ART = 0xFFFFFFFF, ON_ART_SUB = 0xCCFFFFFF;

    private final GameLibrary lib;
    private Listener listener;
    /** null = 「全部」。 */
    private GameLibrary.Platform tab = GameLibrary.Platform.NES;
    /** 只看本地(已下载),不混云端条目。「本地」那一格。 */
    private boolean localOnly;
    /**
     * 在线列表翻到最后一页了(网站数据拉完)。
     *
     * 翻页模式下,数据没拉完时「下一页」翻到末尾还能再点 —— 点了去拉网站
     * 下一页数据(见 {@link Listener#onLoadMore});拉完了才禁掉。
     */
    private boolean onlineEnd;
    /**
     * 网站搜索结果,非 null 表示在搜索模式(列表只显示搜索结果)。
     * 由 {@link Listener#onSearch} 异步拉回来回填,清空搜索框时置回 null。
     */
    private List<GameLibrary.Game> searchHits;
    /** 网站搜索的防抖 Runnable,后输入的会 cancel 掉前一个。 */
    private Runnable searchDebounce;
    /** 当前平台的类型分类,由 GameActivity 抓回来。null = 还没抓到。 */
    private List<OnlineStore.Genre> genres;
    /** 当前选中的类型 tag。null = 全部。 */
    private String genreTag;
    /** 类型模式的列表数据(该类型已拉到的全部条目)。 */
    private List<GameLibrary.Game> genreItems = new ArrayList<>();
    /** 首页模式(左栏第一项):搜索框 + 最近玩过 + 热门。默认打开就是它。 */
    private boolean home = true;
    /** 网站首页那两块热门(站方策展的精选)。拉不到就是空的,首页那两块不画。 */
    private OnlineStore.HotSections hot = new OnlineStore.HotSections();
    /**
     * 多选删除态里选中的游戏 key。**null = 不在多选态**(空集合表示在多选态但一个没选)。
     *
     * 只有「本地」页进得去 —— 已下载的卡在 FC/街机列表和首页热门里也有,那几处
     * 是浏览,点了就该开游戏;管理归管理,免得翻列表时误删。
     */
    private Set<String> selected;
    private final android.os.Handler handler = new android.os.Handler(
            android.os.Looper.getMainLooper());
    /**
     * 一页摆多少个:5 列 × 4 行,正好一屏。
     *
     * 列表是**翻页**不是滚动 —— FC 有两万个游戏,全量塞进视图树
     * (哪怕只是构建 ScrollView 的子节点)车机直接卡死。每页只建 20 张卡,
     * 翻页重建,上万条也只是一页一页地看。
     */
    private static final int PAGE_SIZE = 20;
    /** 当前第几页,从 1 数。切平台、搜索都会回到第 1 页。 */
    private int page = 1;
    /**
     * 用户点了「下一页」但数据还没拉到(在线列表没拉完),记着他要去哪一页。
     * 数据拉回来 refresh 后自动跳过去,人不用再点一次。
     */
    private int pendingPage = 0;
    /**
     * 正在下载的云端条目 key → 角标 TextView。
     *
     * 进度直接改角标文字,不整页重建 —— 下载几十秒内进度回调几十次,
     * 每回调一次就重摆一百多张卡片,车机那点性能扛不住。
     */
    private final Map<String, TextView> cloudTags = new HashMap<>();
    /** 正在下载的云端条目 key。null = 没有在下的。 */
    private String downloadingKey;
    /** 正在下载的最新进度百分比,-1 = 还没拿到总量(小文件常有)。 */
    private int downloadingPct = -1;
    /** 上次下载失败的那条 key(角标变「下载失败」,再点一次重试)。 */
    private String downloadErrorKey;
    /**
     * 缩略图下载中的云端条目 id → 等着这张图的卡片。
     *
     * 同一个 id 可能在同一屏出现两次(首页热门里一张、搜索结果里又一张),
     * 所以不能只记"在下了"就把后来的卡丢掉 —— 那张卡会永远空着。
     * 图下完一次,队列里还活着的卡全铺上。
     */
    private final Map<String, List<ThumbWait>> thumbWaiting = new HashMap<>();

    /** 一张等着缩略图的卡片。宽高各卡不同(首页热门的格子和网格不一样大),要各自记。 */
    private static final class ThumbWait {
        final FrameLayout card;
        final int w, h;
        ThumbWait(FrameLayout card, int w, int h) { this.card = card; this.w = w; this.h = h; }
    }

    /** 这台机器的核心装没装 —— 左栏要标「需下载」,内容区要给个大按钮。 */
    private CoreChecker coreChecker;
    /**
     * 列表数据在途。
     *
     * 空列表时该说「正在加载…」而不是「没有匹配」—— 点一个类型分类的瞬间
     * 旧数据先清空、新数据还在路上,那几百毫秒屏幕上写着"没有匹配「」的游戏",
     * 看着像报错(实测)。
     */
    private boolean loading;
    /** 在线库拉不到(车里没网)。空态得说实话,别让人以为是自己没放 ROM。 */
    private boolean onlineFailed;
    /** 上一次铺出来的是不是空态。只有空着才值得为一句文案重画(见 {@link #setLoading})。 */
    private boolean lastEmpty;
    /**
     * 被这一页盖住、还装在核心里的那个游戏名。null = 没有。
     *
     * 左栏最上面据此摆一条「继续玩」—— 没有它的话进了列表就再也回不去,
     * 只能重新点那张卡,而那是整个重载 ROM,正在打的一局就没了。
     */
    private String resumeName;
    private Runnable resumeAction;

    public interface CoreChecker { boolean ready(GameLibrary.Platform p); }

    private LinearLayout rail;
    private FrameLayout body;
    /** 网格挂这儿。搜索时只换它,输入框不动 —— 否则焦点和光标当场丢。 */
    private FrameLayout gridHost;
    private EditText search;
    private TextView count;
    /** 当前搜索词。切平台时**不清空** —— 人多半是想在另一台机器里找同一个东西。 */
    private String query = "";

    public GameListView(Context ctx, GameLibrary lib) {
        super(ctx);
        this.lib = lib;
        setBackgroundColor(BG());
        setClickable(true);      // 吃掉触摸,别漏到底下的游戏画面上

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.HORIZONTAL);
        addView(root, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        rail = new LinearLayout(ctx);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setBackgroundColor(RAIL_BG());
        rail.setPadding(0, dp(24), 0, dp(24));
        root.addView(rail, new LinearLayout.LayoutParams(dp(RAIL_DP), LayoutParams.MATCH_PARENT));

        body = new FrameLayout(ctx);
        root.addView(body, new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));
    }

    public void setListener(Listener l) { this.listener = l; }

    public void setCoreChecker(CoreChecker c) { this.coreChecker = c; }

    /**
     * 有个游戏还装在核心里(被这一页盖着,暂停中)。左栏顶部据此摆「继续玩」。
     *
     * **只记状态不刷新** —— 调用方紧接着就要 {@link #refresh()},别刷两遍。
     *
     * @param name   游戏名,null = 核心里没装东西,那一条不摆
     * @param action 点了做什么(回游戏页)
     */
    public void setResume(String name, Runnable action) {
        resumeName = name;
        resumeAction = action;
    }

    /**
     * 列表数据在不在途。空列表时的文案要跟着变(见 {@link #emptyText}）。
     *
     * 只有当前正显示着空态才重画 —— 为一句文案把整页网格拆了重建不值当,
     * 而且会把滚动位置甩没。
     */
    public void setLoading(boolean b) {
        if (b == loading) return;
        loading = b;
        if (lastEmpty && gridHost != null) buildGrid();
    }

    /** 在线库连不上。同 {@link #setLoading},只影响空态那句话。 */
    public void setOnlineFailed(boolean b) {
        if (b == onlineFailed) return;
        onlineFailed = b;
        if (lastEmpty && gridHost != null) buildGrid();
    }


    /**
     * 返回键:回首页。
     *
     * 不加这一级的话,从首页热门点「拳皇系列」钻进筛选列表后按返回是**直接退出
     * 游戏中心** —— 车上退出就得从抽屉里重进一遍。
     *
     * @return 消费了没有。已经在首页、也没搜索词,才返回 false 让外面退出去
     */
    public boolean goHome() {
        if (home && query.isEmpty() && searchHits == null && genreTag == null) return false;
        if (searchDebounce != null) handler.removeCallbacks(searchDebounce);
        home = true;
        tab = GameLibrary.Platform.NES;
        localOnly = false;
        genreTag = null;
        genreItems = new ArrayList<>();
        searchHits = null;
        query = "";
        page = 1;
        pendingPage = 0;
        loading = false;
        refresh();
        return true;
    }

    /**
     * 页内提示,两秒自散。
     *
     * **不用 Toast**:Android 12+ 的 toast 钉在屏幕底部,而 Dock 是浮在所有页面
     * 之上的悬浮窗,提示正好被它压住(桌面那边早有这笔账)。摆顶部 —— 玩游戏时
     * 顶栏是收起的,那一条空着。
     */
    public void flash(String msg) {
        handler.removeCallbacks(flashHide);
        if (flashView == null) {
            flashView = new TextView(getContext());
            flashView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            flashView.setTextColor(INK());
            flashView.setGravity(Gravity.CENTER);
            flashView.setPadding(dp(20), dp(10), dp(20), dp(10));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(20));
            bg.setColor(0xF2241D3C);
            bg.setStroke(dp(1), LINE());
            flashView.setBackground(bg);
            LayoutParams lp = new LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            lp.topMargin = dp(24);
            addView(flashView, lp);
        }
        flashView.setText(msg);
        handler.postDelayed(flashHide, 2200);
    }

    private TextView flashView;
    private final Runnable flashHide = new Runnable() {
        @Override public void run() {
            if (flashView != null) { removeView(flashView); flashView = null; }
        }
    };

    /** 在线列表到顶了。之后刷新不再显示「加载更多」。 */
    public void setOnlineEnd(boolean end) { this.onlineEnd = end; }

    /** 开始下载:这张云端卡的角标变「下载中」,防连点。 */
    public void setDownloading(String key) {
        downloadingKey = key;
        downloadingPct = -1;
        downloadErrorKey = null;
        refresh();
    }

    /** 下载进度:只改角标文字,不整页重建。 */
    public void setDownloadProgress(String key, int pct) {
        if (!key.equals(downloadingKey)) return;
        downloadingPct = pct;
        TextView tag = cloudTags.get(key);
        if (tag != null) tag.setText(pct >= 0 ? "下载中 " + pct + "%" : "下载中…");
    }

    /** 下载成功:清掉下载态。卡片变本地由外部 scan + refresh 完成。 */
    public void clearDownloading() {
        downloadingKey = null;
        downloadingPct = -1;
        cloudTags.clear();
    }

    /** 下载失败:角标变「下载失败」,再点一次重试。 */
    public void setDownloadFailed(String key) {
        downloadingKey = null;
        downloadingPct = -1;
        downloadErrorKey = key;
        refresh();
    }

    /** 重建整页。扫描完、下载完核心、玩完一局回来都要调。 */
    public void refresh() {
        buildRail();
        buildBody();
    }

    private void buildRail() {
        rail.removeAllViews();
        // 有游戏正被这一页盖着(核心里装着、暂停中):第一件事是让人回得去。
        // 摆在最上面而不是塞进某个目录 —— 它跟目录不是一回事,是"你还有一局没打完"
        if (resumeName != null) rail.addView(resumeItem());
        // 首页在最上面,打开游戏中心先见它(搜索框 + 最近玩过 + 热门)。
        // 机器分类不带数量(FC/街机里一半是没下的云端条目,数字没意义),
        // 只有「本地」数已下载的 —— 那是用户自己攒下的家当
        rail.addView(railItem(true, null, false, "首页", 0, false));
        rail.addView(railItem(false, GameLibrary.Platform.NES, false, "FC", 0, false));
        rail.addView(railItem(false, GameLibrary.Platform.ARCADE, false, "街机", 0, false));
        // GBA 跟前两个同一条路(站上 tag=11):列表、类型栏、首页热门都有,
        // 而 mGBA 那个核心本来就装着(GB/GBC 也归它)
        rail.addView(railItem(false, GameLibrary.Platform.GBA, false, "GBA", 0, false));
        rail.addView(railItem(false, null, true, "本地", lib.localCount(), true));
    }

    /**
     * 左栏顶部那条「继续玩」。
     *
     * 比目录项高一档(58dp 两行),因为它要写下游戏名;颜色也和目录分开 ——
     * 它不是"去哪儿看",是"回去接着打"。
     */
    private View resumeItem() {
        Context ctx = getContext();
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), 0, dp(12), 0);
        row.setBackgroundColor(SEL());

        TextView t = new TextView(ctx);
        t.setText("▶ 继续玩");
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTextColor(ACCENT());
        row.addView(t);

        TextView n = new TextView(ctx);
        n.setText(resumeName);
        n.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        n.setTextColor(INK());
        n.setMaxLines(1);
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(n);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(58));
        lp.bottomMargin = dp(10);
        row.setLayoutParams(lp);
        row.setOnClickListener(v -> { if (resumeAction != null) resumeAction.run(); });
        return row;
    }

    private View railItem(boolean homeItem, GameLibrary.Platform p, boolean local,
                          String label, int count, boolean showCount) {
        Context ctx = getContext();
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), 0, dp(12), 0);
        boolean on = homeItem ? home : (local ? localOnly : (p == tab && !localOnly));
        row.setBackgroundColor(on ? SEL() : Color.TRANSPARENT);

        TextView t = new TextView(ctx);
        t.setText(showCount ? label + "  " + count : label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTextColor(on ? INK() : MUTED());
        row.addView(t);

        // 核心没装不在这儿说 —— 左栏是目录,一句橙字挂在那儿像报错,还没点进去
        // 就先劝退。说明留给内容区那条 needCoreBanner,那时人已经决定要看街机了
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(52));
        lp.bottomMargin = dp(4);
        row.setLayoutParams(lp);
        row.setOnClickListener(v -> {
            home = homeItem;
            tab = p;
            localOnly = local;
            genreTag = null;             // 换平台了,类型选中态作废
            genreItems = new ArrayList<>();
            genres = null;               // 类型栏跟着平台走,新平台的类型还没抓回来
            selected = null;             // 换目录就退出多选,勾着的东西不该跨目录留着
            if (search != null && !query.isEmpty()) {
                query = "";
                search.setText("");      // 切目录清搜索,免得上一屏的关键词带过来
            } else {
                searchHits = null;
            }
            page = 1;        // 换个目录从头看
            pendingPage = 0;
            refresh();
            if (!home && p != null && listener != null) listener.onTab(p);
        });
        return row;
    }

    private void buildBody() {
        Context ctx = getContext();
        body.removeAllViews();

        // 库是空的也照常铺搜索框和目录 —— 早退那一版会在进门头一两秒(在线列表
        // 还没回来)糊一屏"把 ROM 放进 /storage/…",既误导又把人往文件系统上引。
        // 空着说什么交给 emptyText 分情况讲
        LinearLayout col2 = new LinearLayout(ctx);
        col2.setOrientation(LinearLayout.VERTICAL);
        body.addView(col2, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        col2.addView(searchBar());

        // 类型二级目录:只有云端列表(FC/街机)有,首页/本地页不摆
        if (!home && genres != null && !genres.isEmpty() && !localOnly) {
            col2.addView(genreBar());
        }

        gridHost = new FrameLayout(ctx);
        col2.addView(gridHost, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, 0, 1f));
        buildGrid();
    }

    /**
     * 搜索框。
     *
     * 网站搜索不支持拼音,本地也不做拼音匹配 —— 两边对得上,输什么搜什么。
     */
    private View searchBar() {
        Context ctx = getContext();
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(24), dp(20), dp(24), dp(8));

        search = new EditText(ctx);
        // 首页是门面,搜索框给大一点、提示语像网站那样直接举例
        search.setHint(home ? "搜游戏 · 超级马里奥、拳皇、三国战纪" : "搜游戏 · 名字");
        search.setHintTextColor(MUTED());
        search.setTextColor(INK());
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, home ? 18 : 15);
        search.setSingleLine(true);
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        int ph = home ? dp(18) : dp(12);
        search.setPadding(dp(20), ph, dp(20), ph);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(home ? 14 : 10));
        bg.setColor(TILE());
        search.setBackground(bg);
        search.setText(query);
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable e) {
                query = e.toString();
                // 搜的是另一批东西了,回到第一页
                page = 1;
                pendingPage = 0;
                // 云端列表:按网站的搜索来 —— 输入停下来 300ms 再发请求,
                // 不然每个字都打一发,车机网扛不住
                if (!localOnly) {
                    if (searchDebounce != null) handler.removeCallbacks(searchDebounce);
                    if (query.trim().isEmpty()) {
                        setSearchHits(null);
                    } else {
                        // 输入搜索词 = 退出类型模式:网站搜索是跨类型的,
                        // 类型和搜索互斥,不然搜出来的结果会被类型列表挡着
                        genreTag = null;
                        genreItems = new ArrayList<>();
                        final String q = query.trim();
                        searchDebounce = () -> {
                            loading = true;      // 结果回来之前空着的话,该说"正在加载"
                            if (listener != null) listener.onSearch(q);
                        };
                        handler.postDelayed(searchDebounce, 300);
                    }
                } else {
                    setSearchHits(null);
                    // **只重建网格,不重建整页** —— 整页重建会把输入框本身也换掉,
                    // 焦点和光标当场丢,打第二个字得再点一次
                    buildGrid();
                }
            }
        });
        bar.addView(search, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        // **总是建**,内容由 buildGrid 填(首页热门那屏没有"共 N 个"可数,填空串)。
        // 以前按"当前是不是首页"决定建不建,而建这一行的时刻搜索结果必然还没回来,
        // 于是首页搜出来的结果永远不显示条数 —— 而且 count 还留着上一轮已经
        // detach 的那个 TextView 的死引用
        count = new TextView(ctx);
        count.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        count.setTextColor(MUTED());
        count.setPadding(dp(12), 0, 0, 0);
        bar.addView(count);

        // 「本地」页才有管理入口:云端条目没什么可删的,删的是攒下来的家当
        if (localOnly) {
            if (selected == null) {
                bar.addView(barBtn("管理", TILE(), INK(), v -> {
                    selected = new HashSet<>();
                    refresh();
                }));
            } else {
                bar.addView(barBtn("取消", TILE(), INK(), v -> exitSelectMode()));
                // 一个没选时删除键是灰的 —— 点了什么也不会发生,不如直接看出来不能点
                boolean any = !selected.isEmpty();
                bar.addView(barBtn(any ? "删除 " + selected.size() + " 个" : "删除",
                        any ? 0xFFD1504F : TILE(), any ? 0xFFFFFFFF : MUTED(),
                        any ? v -> { if (listener != null) listener.onDelete(selectedGames()); }
                            : null));
            }
        }
        return bar;
    }

    /** 搜索框右边那排小按钮(管理/取消/删除)。onClick 为 null = 不可点。 */
    private TextView barBtn(String label, int bg, int fg, OnClickListener onClick) {
        TextView b = new TextView(getContext());
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTextColor(fg);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(18), dp(10), dp(18), dp(10));
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(10));
        d.setColor(bg);
        b.setBackground(d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(dp(12));
        b.setLayoutParams(lp);
        if (onClick != null) b.setOnClickListener(onClick);
        return b;
    }

    /** 选中的那些游戏(按当前本地列表的顺序)。 */
    private List<GameLibrary.Game> selectedGames() {
        List<GameLibrary.Game> out = new ArrayList<>();
        if (selected == null) return out;
        for (GameLibrary.Game g : lib.search(null, true, "")) {
            if (selected.contains(g.key())) out.add(g);
        }
        return out;
    }

    /**
     * 退出多选态。返回键也走这儿(见 GameActivity.onBackPressed)——
     * 多选态下按返回该先退出选择,而不是直接退回桌面。
     *
     * @return 之前是不是在多选态。false 表示这次返回键没被消费,该由外面接着处理
     */
    public boolean exitSelectMode() {
        if (selected == null) return false;
        selected = null;
        refresh();
        return true;
    }

    /** 平台的类型分类回填。 */
    public void setGenres(List<OnlineStore.Genre> list) {
        genres = list;
        buildBody();
    }

    /**
     * 类型二级目录:「全部」+ 网站那排类型(动作冒险、格斗…)。
     *
     * 只有云端列表(FC/街机)有 —— 网站按类型分目录,车机照着摆;
     * 本地页不摆,本地就按平台/名字找。一排横滚的胶囊按钮,放搜索框下面:
     * 类型多(FC 八个、街机七个)一行放不下,横向滑,不占竖向空间。
     */
    private View genreBar() {
        Context ctx = getContext();
        HorizontalScrollView hsv = new HorizontalScrollView(ctx);
        hsv.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(24), dp(0), dp(24), dp(12));
        row.addView(genreBtn("全部", genreTag == null, null));
        if (genres != null) {
            for (OnlineStore.Genre g : genres) {
                row.addView(genreBtn(g.label, g.tag.equals(genreTag), g));
            }
        }
        hsv.addView(row, new FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        return hsv;
    }

    /** 一个类型按钮。on = 当前选中。g 为 null = 「全部」。 */
    private TextView genreBtn(String label, boolean on, OnlineStore.Genre g) {
        Context ctx = getContext();
        TextView b = new TextView(ctx);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTextColor(on ? ON_ACCENT() : INK());
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(14), dp(7), dp(14), dp(7));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(15));
        bg.setColor(on ? ACCENT() : TILE());
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(8));
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> selectGenre(g));
        return b;
    }

    /**
     * 点了一个类型(或「全部」)。
     *
     * 类型和搜索是两套列表,互斥:点类型先清掉搜索词(输入框一起清),
     * 回到第一页;数据由 GameActivity 异步拉回(见 {@link Listener#onGenre}),
     * 回来走 {@link #setGenreItems} 填。数据没到之前先空着,不显示旧平台的货。
     */
    private void selectGenre(OnlineStore.Genre g) {
        genreTag = g == null ? null : g.tag;
        genreItems = new ArrayList<>();
        // 数据在路上。不标这一下,清空到回填之间那几百毫秒屏幕上写的是
        // "没有匹配「」的游戏"(query 是空的),看着像点坏了
        loading = g != null;
        if (search != null && !query.isEmpty()) {
            query = "";
            search.setText("");      // 触发 TextWatcher,把搜索态一并清掉
        } else {
            searchHits = null;
            page = 1;
            pendingPage = 0;
            buildGrid();
        }
        if (listener != null) listener.onGenre(g);
    }

    /** 类型模式的列表数据回填(该类型已拉到的全部,跨页累积)。 */
    public void setGenreItems(List<GameLibrary.Game> items) {
        genreItems = items == null ? new ArrayList<>() : items;
        loading = false;      // 直接赋值不走 setLoading —— 下面那句 buildGrid 一起画了
        // 不重置页码:点类型时已回第 1 页;翻页拉数据回来要落到 pendingPage
        // 指的那页(见 buildGrid 的跳转),重置了就把用户又拽回第一页
        buildGrid();
    }

    /**
     * 网站首页两块热门的回填(见 {@link OnlineStore#fetchHot})。
     *
     * 异步拉回来的,那时首页多半已经铺完了(热门那两块还空着)—— 在首页就重建一次
     * 把它们补上;人已经翻到别的目录了就只存着,下次回首页自然用上。
     */
    public void setHot(OnlineStore.HotSections sections) {
        hot = sections;
        if (home) refresh();
    }

    /** 网站搜索结果回填。hits 为 null = 退出搜索模式,回正常列表。 */
    public void setSearchHits(List<GameLibrary.Game> hits) {
        searchHits = hits;
        loading = false;
        if (hits != null) {
            page = 1;        // 搜的是另一批东西了,从第一页看
            pendingPage = 0;
        }
        buildGrid();
    }

    /**
     * 首页内容:最近玩过 + FC 热门 + 街机热门 + GBA 热门。
     *
     * 跟网站首页一个思路 —— 搜索框在最上面(searchBar),下面直接摆"最近在玩"
     * 和热门的游戏,坐下来一眼看到能玩什么。热门就是网站首页那两块
     * (见 {@link OnlineStore#fetchHot}),还没拉到时那两块空着。
     */
    private void buildHome() {
        Context ctx = getContext();
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(24), dp(4), dp(24), dp(8));

        int avail = getResources().getDisplayMetrics().widthPixels - dp(RAIL_DP) - dp(48);
        int gap = dp(16);
        int cellW = (avail - gap * (COLS - 1)) / COLS;
        int cellH = cellW * 3 / 4;

        // 最近玩过:横排小卡,坐上车常是接着上次玩
        java.util.List<GameLibrary.Game> recent = lib.recent();
        if (!recent.isEmpty()) {
            homeTitle(col, "最近玩过");
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            int rw = (avail - gap * 5) / 6;
            int rh = rw * 3 / 4;
            for (GameLibrary.Game g : recent) {
                row.addView(cell(g, rw, rh, gap), cellLp(rw, rh, gap, false));
            }
            col.addView(row);
        }

        // 两台机器的热门,数据就是网站首页那两块(站方一作一条的精选)。
        // 没拉到就不画 —— 拿本地列表的前几个冒充热门跟网站对不上,那正是这块
        // 以前的毛病
        addHotGrid(col, hot.fc, "FC 热门", cellW, cellH, gap);
        addHotGrid(col, hot.arcade, "街机热门", cellW, cellH, gap);
        addHotGrid(col, hot.gba, "GBA 热门", cellW, cellH, gap);

        // 三块全空:进门头一两秒(热门还在路上)或者车里没网。总得说一句,
        // 不然首页就是一片纯黑,看着像打不开
        lastEmpty = recent.isEmpty() && hot.fc.isEmpty() && hot.arcade.isEmpty()
                && hot.gba.isEmpty();
        if (lastEmpty) {
            TextView t = new TextView(ctx);
            t.setText(onlineFailed
                    ? "在线游戏库连不上\n\n检查一下车机网络。\n左边「本地」里是已经下好的"
                    : loading ? "正在加载…"
                    : "在线库这会儿没给出内容\n\n左边 FC / 街机 / GBA 里照样能翻");
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            t.setTextColor(MUTED());
            t.setGravity(Gravity.CENTER);
            t.setLineSpacing(0, 1.5f);
            t.setPadding(0, dp(120), 0, 0);
            col.addView(t, new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        }

        ScrollView scroll = new ScrollView(ctx);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(col, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        gridHost.addView(scroll, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    /** 首页区块标题,如「最近玩过」「FC 热门」。 */
    private void homeTitle(LinearLayout parent, String text) {
        TextView t = new TextView(getContext());
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTextColor(MUTED());
        t.setPadding(0, dp(14), 0, dp(8));
        parent.addView(t);
    }

    /**
     * 网站首页的一块热门。游戏卡和系列卡混排,顺序照抄网站。
     *
     * 街机那块八条里有三条是**系列**(「三国战纪系列」「合金弹头系列」「拳皇系列」),
     * 在网站上点进去也是筛选列表而不是某一作 —— 这里照搬,见 {@link #seriesCell}。
     */
    private void addHotGrid(LinearLayout col, List<OnlineStore.Hot> items,
                            String title, int w, int h, int gap) {
        if (items.isEmpty()) return;
        homeTitle(col, title);
        int i = 0;
        LinearLayout row = null;
        for (OnlineStore.Hot t : items) {
            View v;
            if (t.series != null) {
                v = seriesCell(t, w, h);
            } else {
                // 已下载的用本地条目、主列表有同 id 的复用同一个对象(见 gameFor):
                // 热门卡和平台列表里那张必须是同一个 Game,否则在首页下载完,
                // 切到 FC 列表那条还停在「云端」
                GameLibrary.Game g = lib.gameFor(t.card);
                if (g == null) continue;      // 站上有我们没核心的机型
                // 名字用热门板块那个短名(「激龟快打」而不是列表页的
                // 「激龟快打(忍者神龟格斗)欧版」),理由见 cell 的 displayName
                v = cell(g, w, h, gap, t.card.name);
            }
            if (i % COLS == 0) row = newRow(col, gap);
            row.addView(v, cellLp(w, h, gap, i % COLS == COLS - 1));
            i++;
        }
    }

    /**
     * 系列卡:点了进这个系列的筛选列表,不是开游戏。
     *
     * 走的是类型分类那条现成的路({@link Listener#onGenre} → fetchGenreList),
     * 因为系列在网站上就是一个 tag 筛选({@code /nes?tag=三国战纪&e=1}),
     * 跟「动作」「格斗」同一套参数。角标写「系列」把它跟游戏卡区分开 ——
     * 不然点下去以为要开游戏,结果跳到一屏列表。
     */
    private View seriesCell(OnlineStore.Hot t, int w, int h) {
        Context ctx = getContext();
        FrameLayout card = new FrameLayout(ctx);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(SEL());
        card.setBackground(bg);
        card.setClipToOutline(true);

        applyThumb(card, t.card, w, h);

        TextView tag = new TextView(ctx);
        tag.setText("系列");
        tag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        tag.setTextColor(ON_ACCENT());
        tag.setGravity(Gravity.CENTER);
        GradientDrawable tagBg = new GradientDrawable();
        tagBg.setCornerRadius(dp(6));
        tagBg.setColor(ACCENT());
        tag.setBackground(tagBg);
        tag.setPadding(dp(6), dp(2), dp(6), dp(2));
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        tlp.setMargins(0, dp(8), dp(8), 0);
        card.addView(tag, tlp);

        TextView name = new TextView(ctx);
        name.setText(t.card.name);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        name.setTextColor(ON_ART);
        name.setMaxLines(2);
        name.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.addView(name, new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        card.setOnClickListener(v -> {
            // 切到这个系列所属的机器(e=1 是街机,其余按 FC),再按 tag 拉列表。
            // 顺序照 railItem:先把目录状态摆好,再让外面去抓数据
            home = false;
            tab = t.series.e == 1 ? GameLibrary.Platform.ARCADE
                    : t.series.e == 2 ? GameLibrary.Platform.GBA
                    : GameLibrary.Platform.NES;
            localOnly = false;
            genres = null;               // 类型栏跟着平台走,新平台的还没抓回来
            genreTag = t.series.tag;
            genreItems = new ArrayList<>();
            searchHits = null;
            page = 1;
            pendingPage = 0;
            refresh();
            if (listener != null) {
                listener.onTab(tab);          // 顺手把这个平台的类型栏抓回来
                listener.onGenre(t.series);   // 拉这个系列的第一页
            }
        });
        return card;
    }

    private void buildGrid() {
        Context ctx = getContext();
        // 上一轮建出来的角标全是死引用(整页重建了),作废重记
        cloudTags.clear();
        gridHost.removeAllViews();

        // 首页没在搜索时不是网格,是「最近玩过 + 热门」那一屏
        if (home && searchHits == null) {
            if (count != null) count.setText("");    // 那一屏没有"共 N 个"可数
            buildHome();
            return;
        }

        List<GameLibrary.Game> items = genreTag != null
                ? genreItems
                : (searchHits != null ? searchHits : lib.search(tab, localOnly, query));
        if (count != null) count.setText(items.isEmpty() ? "" : "共 " + items.size() + " 个");

        lastEmpty = items.isEmpty();
        if (lastEmpty) {
            gridHost.addView(centerHint(emptyText()));
            return;
        }

        // 这一页摆哪一段。pendingPage 是用户从「下一页」翻出去的,
        // 数据拉回来后落到他想要的那页,不用再点一次
        int totalPages = (items.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        if (pendingPage > 0) {
            if (pendingPage <= totalPages) page = pendingPage;
            pendingPage = 0;
        }
        if (page > totalPages) page = totalPages;
        int from = (page - 1) * PAGE_SIZE;
        int to = Math.min(items.size(), from + PAGE_SIZE);
        List<GameLibrary.Game> pageItems = items.subList(from, to);

        // 内容区拆成上下两段:卡片滚动区(占满)+ 底部固定翻页条。
        // 翻页条不跟着内容滚 —— 它是高频操作,滚没了一页页找回去太蠢
        LinearLayout gridCol = new LinearLayout(ctx);
        gridCol.setOrientation(LinearLayout.VERTICAL);
        gridHost.addView(gridCol, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        ScrollView scroll = new ScrollView(ctx);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setPadding(dp(24), dp(24), dp(24), dp(8));
        scroll.setClipToPadding(false);
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(col, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        gridCol.addView(scroll, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, 0, 1f));

        // 这台机器的模拟器没装:先摆一条通栏说清楚,游戏照常列出来(点了会去下)。
        // 只在第一页摆,翻页后不占地方;搜索模式不摆,占地方又没人看
        if (page == 1 && genreTag == null && searchHits == null && tab != null
                && coreChecker != null && !coreChecker.ready(tab)) {
            col.addView(needCoreBanner(tab));
        }

        int gap = dp(16);
        int avail = getResources().getDisplayMetrics().widthPixels - dp(RAIL_DP) - dp(48);
        int cellW = (avail - gap * (COLS - 1)) / COLS;
        int cellH = cellW * 3 / 4;      // 老游戏都是 4:3

        LinearLayout row = null;
        int i = 0;
        for (GameLibrary.Game g : pageItems) {
            if (i % COLS == 0) row = newRow(col, gap);
            boolean lastInRow = (i % COLS == COLS - 1);
            row.addView(cell(g, cellW, cellH, gap), cellLp(cellW, cellH, gap, lastInRow));
            i++;
        }

        gridCol.addView(pager(items.size(), totalPages));
    }

    /**
     * 列表空着时说什么。**这是四件不同的事**,以前混成一句"没有匹配「」的游戏":
     *
     * 数据还在路上、车里没网连不上在线库、搜不到、真的一个游戏都没有 ——
     * 一律引导去云端下载,不对外露 ROM 目录路径(那是给自己 adb push 用的,不摆给用户)。
     */
    private String emptyText() {
        if (loading) return "正在加载…";
        if (!query.isEmpty()) {
            return localOnly
                    ? "本地没有匹配「" + query + "」的游戏"
                    : "没有找到「" + query + "」\n\n试试英文名或 romset 名,\n或直接去 FC / 街机 / GBA 分类里翻";
        }
        if (localOnly) return "本地还没有游戏\n\n点开云端游戏下载,下完自动到这儿";
        if (onlineFailed) return "在线游戏库连不上\n\n检查一下车机网络";
        return "这儿还没有游戏\n\n点开云端游戏下载,下完自动到这儿";
    }

    /**
     * 底部翻页条:「‹ 上一页」 第 X / Y 页 「下一页 ›」。
     *
     * 在线列表数据没拉完时,翻到最后一页「下一页」还能点 —— 点了去拉网站
     * 下一页数据(见 {@link Listener#onLoadMore}),拉完自动落到下一页。
     * 数据全拉完了(或本地目录)才在最后一页禁用。
     */
    private View pager(int total, int totalPages) {
        Context ctx = getContext();
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        // 底部让出 Dock 那一条:它是悬浮窗、浮在本页之上,不留白按钮就被压掉半截
        int dockH = getResources().getDimensionPixelSize(
                com.liubai.blank.R.dimen.dock_space);
        bar.setPadding(dp(16), dp(10), dp(16), dockH + dp(14));

        TextView prev = pageBtn("‹ 上一页");
        prev.setEnabled(page > 1);
        prev.setOnClickListener(v -> { page--; buildGrid(); });
        bar.addView(prev);

        TextView no = new TextView(ctx);
        // 只报页码,不报总数 —— 「第 1 / 2 页 · 21 个」会让人以为第一页摆了 21 个
        no.setText("第 " + page + " / " + totalPages + " 页");
        no.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        no.setTextColor(ACCENT());
        no.setGravity(Gravity.CENTER);
        // 按钮别左右分家:车机 1920 宽,上一页贴最左、下一页贴最右,
        // 驾驶位上伸手够不着另一头。三个挤在中间,一臂之内都点得到
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        nlp.leftMargin = dp(16);
        nlp.rightMargin = dp(16);
        bar.addView(no, nlp);

        // 云端的货还没拉完时,「下一页」除了翻页还兼着"拉更多":
        // 下一站若是"半页"(本地游戏混进来把 20 个的格子挤掉了),先把网站
        // 下一页数据拉回来再翻 —— 不然翻过去只看到孤零零一个游戏(实测 21 个
        // 时第 2 页就 1 个魂斗罗力量),用户还以为列表坏了
        boolean moreOnline = !localOnly && !onlineEnd && searchHits == null
                && (genreTag == null || !genreItems.isEmpty());
        TextView next = pageBtn("下一页 ›");
        next.setEnabled(page < totalPages || moreOnline);
        next.setOnClickListener(v -> {
            // 下一站是完整一页:直接翻。半页(总条数不是 20 的倍数)说明
            // 数据还没拉完,下一站其实是临时的 —— 先拉数据填满再过去
            boolean nextIsFullPage = (page + 1 < totalPages)
                    || (total % PAGE_SIZE == 0)
                    || !moreOnline;
            if (page < totalPages && nextIsFullPage) {
                page++;
                buildGrid();
            } else if (moreOnline) {
                pendingPage = page + 1;      // 数据没到,拉回来自动落到那一页
                if (listener != null) listener.onLoadMore();
            }
        });
        bar.addView(next);
        return bar;
    }

    private TextView pageBtn(String text) {
        Context ctx = getContext();
        TextView b = new TextView(ctx);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        // 禁用时变灰,一眼看出这头翻不动了
        b.setTextColor(new android.content.res.ColorStateList(
                new int[][]{ { -android.R.attr.state_enabled }, { } },
                new int[]{ 0x44FFFFFF, 0xFFE6E1F5 }));
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(TILE());
        bg.setStroke(dp(1), LINE());
        b.setBackground(bg);
        return b;
    }

    private LinearLayout newRow(LinearLayout parent, int gap) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = gap;
        parent.addView(row, rlp);
        return row;
    }

    private LinearLayout.LayoutParams cellLp(int w, int h, int gap, boolean last) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        if (!last) lp.setMarginEnd(gap);
        return lp;
    }

    /** 一张游戏卡。有封面铺封面,没有就是平台色块 + 名字。 */
    private View cell(GameLibrary.Game g, int w, int h, int gap) {
        return cell(g, w, h, gap, null);
    }

    /**
     * @param displayName 卡片上写哪个名字,null = 用 {@code g.name}。
     *
     * 首页热门要用**热门板块给的名字**:同一个 id,那儿叫「激龟快打」「冒险岛」,
     * 列表页叫「激龟快打(忍者神龟格斗)欧版」「冒险岛(日版)」。Game 对象是几处列表
     * 共用的一份(见 {@link GameLibrary#gameFor}),不能为了好看去改它的 name ——
     * ROM 落盘文件名就是从 name 来的({@link OnlineStore.Remote#romFileName}),
     * 改了下载会落到另一个文件、跟本地反查也对不上。所以只覆盖这一张卡的显示。
     */
    private View cell(GameLibrary.Game g, int w, int h, int gap, String displayName) {
        Context ctx = getContext();
        FrameLayout card = new FrameLayout(ctx);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        // 没封面时用平台色压暗当底 —— 直接铺原色太跳,一屏四个能亮瞎眼
        bg.setColor(tint(g));
        card.setBackground(bg);
        card.setClipToOutline(true);

        Bitmap cover = loadCover(g, w);
        if (cover != null) addCover(card, cover, h);

        // 云端条目:ROM 还没下,但列表页自带缩略图(三五 KB),拉一张铺上,
        // 别让整屏都是干巴巴的色块。图存进 sshots/。下载完成后这张卡
        // 不重排、仍在原位置(见 Game.markDownloaded),online 信息还在,
        // 封面照样从同一个缩略图文件铺
        applyThumb(card, g.online, w, h);

        // 云端列表(非「本地」页)右上角钉状态标记:
        // 在下的显示「下载中 xx%」(蓝)、下失败的「下载失败」(红,再点重试)、
        // 文件已经在了的显示「已下载」(蓝)、还没下的显示「云端」(橙)。
        // 本地页全是已下载的,不需要标
        if (!localOnly) {
            String label = null;
            int color = 0;
            if (g.key().equals(downloadErrorKey)) {
                label = "下载失败";
                color = 0xFFD1504F;
            } else if (g.key().equals(downloadingKey)) {
                label = downloadingPct >= 0 ? "下载中 " + downloadingPct + "%" : "下载中…";
                color = 0xFF4B9BD1;
            } else if (g.file.isFile() && g.file.length() > 0) {
                label = "已下载";
                color = 0xFF4B9BD1;
            } else {
                label = "云端";
                color = 0xFFE0A34A;
            }
            if (label != null) {
                TextView cloudTag = new TextView(ctx);
                cloudTag.setText(label);
                cloudTag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
                cloudTag.setTextColor(ON_ACCENT());
                cloudTag.setGravity(Gravity.CENTER);
                GradientDrawable tagBg = new GradientDrawable();
                tagBg.setCornerRadius(dp(6));
                tagBg.setColor(color);
                cloudTag.setBackground(tagBg);
                int pad = dp(6);
                cloudTag.setPadding(pad, dp(2), pad, dp(2));
                FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                        LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                        Gravity.TOP | Gravity.END);
                tlp.setMargins(0, dp(8), dp(8), 0);
                card.addView(cloudTag, tlp);
                if (g.key().equals(downloadingKey)) cloudTags.put(g.key(), cloudTag);
            }
        }

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(12), dp(10), dp(12), dp(10));
        TextView name = new TextView(ctx);
        name.setText(displayName != null ? displayName : g.name);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        name.setTextColor(ON_ART);
        name.setMaxLines(2);
        texts.addView(name);

        // 云端条目右上角那个「云端」角标就是全部说明,下面只留名字;
        // 本地的才摆平台/类型(有类型就显示类型,一千多个游戏全是红白机时
        // "红白机"这三个字说了等于没说),后面接上占多大 —— 删的时候要按这个挑,
        // **拼在同一行不另起一行**:卡片高度是按 4:3 算死的,多一行就压到封面上
        if (!g.cloud) {
            TextView plat = new TextView(ctx);
            String meta = g.genre.isEmpty() ? g.platform.label : g.genre;
            // 云端条目没有大小可显示 —— 列表页不带体积,要知道得对每张卡的 CDN
            // 各发一次请求,一百多张卡不值当。已下载的直接读文件
            long size = g.file.isFile() ? g.file.length() : 0;
            if (size > 0) meta += " · " + GameLibrary.humanSize(size);
            plat.setText(meta);
            plat.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            plat.setTextColor(ON_ART_SUB);
            texts.addView(plat);
        }

        card.addView(texts, new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        // 多选态:点卡片是勾选,不是开游戏。选中的整张压一层色 + 左上角打勾,
        // 一眼数得出选了几个(右上角让给「已下载」那类角标)
        if (selected != null) {
            boolean on = selected.contains(g.key());
            if (on) {
                View mask = new View(ctx);
                mask.setBackgroundColor(0x66B9AEE8);
                card.addView(mask, new LayoutParams(
                        LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
                TextView check = new TextView(ctx);
                check.setText("✓");
                check.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                check.setTextColor(ON_ACCENT());
                check.setGravity(Gravity.CENTER);
                GradientDrawable cd = new GradientDrawable();
                cd.setShape(GradientDrawable.OVAL);
                cd.setColor(ACCENT());
                check.setBackground(cd);
                FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                        dp(30), dp(30), Gravity.TOP | Gravity.START);
                clp.setMargins(dp(8), dp(8), 0, 0);
                card.addView(check, clp);
            }
            card.setOnClickListener(v -> {
                if (on) selected.remove(g.key()); else selected.add(g.key());
                refresh();      // 顶栏那个「删除 N 个」要跟着变,整页一起重建
            });
            return card;
        }

        card.setOnClickListener(v -> { if (listener != null) listener.onPick(g); });
        return card;
    }

    /**
     * 没封面时的卡片底色:平台色压暗,再按游戏名微调一档明暗。
     *
     * 只用平台色的话,一屏全是同一台机器的游戏 = 一整片同色方块,格子之间分不出彼此,
     * 眼睛没有着落点。按名字 hash 抖动 ±12%,同色系里就有了深浅,一眼能数清有几个,
     * 而且同一个游戏每次进来颜色都一样(hash 是稳定的)。
     */
    private static int tint(GameLibrary.Game g) {
        int color = g.platform.color;
        int r = (color >> 16) & 0xFF, gg = (color >> 8) & 0xFF, b = color & 0xFF;
        int h = g.name.hashCode();
        int k = 45 + (Math.abs(h) % 25);        // 45%~69% 明度
        r = Math.min(255, r * k / 100);
        gg = Math.min(255, gg * k / 100);
        b = Math.min(255, b * k / 100);
        return 0xFF000000 | (r << 16) | (gg << 8) | b;
    }

    /** 往卡片上铺封面 + 底部压暗,本地/云端共用。 */
    private void addCover(FrameLayout card, Bitmap cover, int h) {
        Context ctx = getContext();
        ImageView img = new ImageView(ctx);
        img.setImageBitmap(cover);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        // 底部压一道渐变,不然名字压在亮画面上读不出来
        View scrim = new View(ctx);
        GradientDrawable sd = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[]{ 0x00000000, 0xCC000000 });
        scrim.setBackground(sd);
        // 封面和压暗层**插到最底下**。名字、角标必须是卡片里最上面的几层:
        // 同步路径它们后加、天然靠前;但云端的缩略图是**异步**铺上来的,
        // 那时名字已经加好了 —— 用 addView(view, 0) 把封面插到 0 号位,
        // 无论同步异步,文字都在封面之上,不会出现"封面盖住游戏名"
        card.addView(scrim, 0, new LayoutParams(LayoutParams.MATCH_PARENT, h / 2, Gravity.BOTTOM));
        card.addView(img, 0, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    /** 配套图库那张封面。按卡片宽度降采样 —— 一页二十张全按原尺寸解码,内存会炸。 */
    private Bitmap loadCover(GameLibrary.Game g, int targetW) {
        return decode(g.cover(), targetW);
    }

    /** 解码一张图,按目标宽度降采样。文件不存在/解不开返回 null。 */
    private Bitmap decode(File f, int targetW) {
        if (f == null || !f.isFile()) return null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int sample = 1;
            while (o.outWidth / (sample * 2) >= targetW) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 云端条目缩略图落盘位置:sshots/ 下,跟 ROM 下载时的详情封面同一个文件。
     *
     * 按 {@link OnlineStore.Remote} 而不是 Game 取 —— 首页热门里的**系列卡**
     * (「三国战纪系列」)没有对应的 Game,但一样要铺封面。
     */
    private File thumbFile(OnlineStore.Remote r) {
        return new File(new File(lib.romDir(), "sshots"),
                r.name.replace('/', '／').trim() + ".png");
    }

    /**
     * 异步拉云端缩略图,好了往卡片上铺。
     *
     * 下载走 {@link OnlineStore#thumbs()} 独立线程 —— 不能占 ROM 下载那条
     * 串行道,不然街机 20 MB 一下,封面全等着。同一张图只下一次,等它的卡片
     * 排进队里(见 {@link #thumbWaiting});回调时卡片可能已经被翻页翻走了,
     * isAttachedToWindow 挡一下。
     */
    private void loadThumb(FrameLayout card, OnlineStore.Remote r, File thumb, int w, int h) {
        List<ThumbWait> q = thumbWaiting.get(r.id);
        if (q != null) {
            // 这张图已经在下了。**把卡片排进队,不能直接丢掉** —— 同一个游戏在同一屏
            // 出现两次是常事(首页热门一张、搜索结果又一张),丢掉的那张会永远空着
            q.add(new ThumbWait(card, w, h));
            return;
        }
        q = new ArrayList<>();
        q.add(new ThumbWait(card, w, h));
        thumbWaiting.put(r.id, q);
        OnlineStore store = new OnlineStore();
        OnlineStore.thumbs().execute(() -> {
            boolean ok = store.downloadThumb(r, thumb);
            post(() -> {
                List<ThumbWait> waiting = thumbWaiting.remove(r.id);
                if (!ok || waiting == null) return;
                for (ThumbWait t : waiting) {
                    // 回调时卡片可能已经被翻页翻走了
                    if (!t.card.isAttachedToWindow()) continue;
                    Bitmap b = decode(thumb, t.w);
                    if (b != null) addCover(t.card, b, t.h);
                }
            });
        });
    }

    /** 把云端缩略图铺到卡片上:已下载的直接解码,没有的异步拉。游戏卡和系列卡共用。 */
    private void applyThumb(FrameLayout card, OnlineStore.Remote r, int w, int h) {
        if (r == null || r.pic.isEmpty()) return;
        File thumb = thumbFile(r);
        if (thumb.isFile()) {
            Bitmap b = decode(thumb, w);
            if (b != null) addCover(card, b, h);
        } else {
            loadThumb(card, r, thumb, w, h);
        }
    }

    private View needCoreBanner(GameLibrary.Platform p) {
        Context ctx = getContext();
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setPadding(dp(20), dp(16), dp(20), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(TILE());
        bg.setStroke(dp(1), LINE());
        bar.setBackground(bg);

        TextView t = new TextView(ctx);
        t.setText(p.label + "的模拟器还没装");
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTextColor(INK());
        bar.addView(t);

        TextView sub = new TextView(ctx);
        sub.setText("点任意一个游戏,自动下载模拟器,下完直接开");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        sub.setTextColor(MUTED());
        bar.addView(sub);

        bar.setOnClickListener(v -> { if (listener != null) listener.onNeedCore(p); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(16);
        bar.setLayoutParams(lp);
        return bar;
    }

    private View centerHint(String text) {
        TextView t = new TextView(getContext());
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTextColor(MUTED());
        t.setGravity(Gravity.CENTER);
        t.setLineSpacing(0, 1.5f);
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        t.setLayoutParams(lp);
        return t;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
