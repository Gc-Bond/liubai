package com.liubai.blank;

import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.text.TextUtils;
import android.util.Patterns;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.liubai.blank.data.BookmarkStore;
import com.liubai.blank.model.Bookmark;
import com.liubai.blank.mode.ModeApplier;
import com.liubai.blank.mode.ModeManager;
import com.liubai.blank.ui.DockHost;
import com.liubai.blank.util.Launchers;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * 极简浏览器:独立的可启动条目,不接三层桌面结构、不进 Dock ——
 * 就是「全部应用」抽屉里一颗普通图标。
 *
 * Manifest 里**刻意没声明 LAUNCHER**(那会让原厂 DiLink 桌面也长出一个图标),
 * 所以抽屉不是查出来的、是 AppDrawerActivity.injectInternal 塞进去的。
 *
 * 布局里 paddingBottom="@dimen/dock_space":留白自己的包名恒在 Immersive 的全局
 * 沉浸式白名单里(见 Immersive.applyGlobal),不管这个 Activity 自己调不调
 * Immersive.apply(),系统导航栏都不存在——常驻 Dock 默认「浮在任何应用之上」
 * (见 DockHost 的 always_on),不留这块空间,底部工具条会被整条盖住。
 * 跟 AppDrawerActivity 用的是同一个道理。
 *
 * 多标签只在内存里活:每个 Tab 一份独立 WebView,切标签只是换绑 web_container
 * 的子 view,不做序列化持久化——杀进程即清空。书签是唯一落盘的状态(见 BookmarkStore)。
 */
public class BrowserActivity extends AppCompatActivity {

    /**
     * 让别处把一个网址甩进来直接打开(在线更新用它下 apk,见 {@code AppUpdater})。
     *
     * 不带这个 extra 就还是原来的样子:开一个空白起始页。
     */
    public static final String EXTRA_URL = "url";

    private ImageView btnBack, btnForward, btnRefresh, btnStar, btnHome, btnNewTab, btnDownloads;
    private ProgressBar progress;
    private LinearLayout tabContainer;
    private FrameLayout webContainer;
    private LinearLayout downloadPanel;
    private LinearLayout downloadList;
    private TextView downloadEmpty;
    private View startPage;
    private View downloadBar;
    private TextView downloadText;
    private TextView downloadAction;
    private EditText startSearchBar;
    private GridView startBookmarkGrid;
    private TextView startGridTitle;
    private TextView startEmpty;

    private BookmarkStore store;
    private List<Bookmark> bookmarks;
    private BookmarkTileAdapter tileAdapter;
    private final List<Tab> tabs = new ArrayList<>();
    private Tab active;

    /** 当前盯着的那一单下载(DownloadManager 的 id)。-1 = 没有。 */
    private long downloadId = -1;
    private final android.os.Handler downloadPoll = new android.os.Handler(android.os.Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        com.liubai.blank.util.Immersive.windowBg(this);   // 窗口底跟模式走,否则转场露白(见那个方法的说明)
        setContentView(R.layout.activity_browser);
        // 顶部让位给常驻顶栏(悬浮窗不占布局空间,见 StatusBarHost.applyTopSpace)
        com.liubai.blank.ui.StatusBarHost.applyTopSpace(findViewById(android.R.id.content));

        store = new BookmarkStore(this);
        bookmarks = store.load();

        btnBack = findViewById(R.id.btn_back);
        btnForward = findViewById(R.id.btn_forward);
        btnRefresh = findViewById(R.id.btn_refresh);
        btnStar = findViewById(R.id.btn_star);
        btnHome = findViewById(R.id.btn_home);
        btnNewTab = findViewById(R.id.btn_new_tab);
        progress = findViewById(R.id.progress);
        tabContainer = findViewById(R.id.tab_container);
        webContainer = findViewById(R.id.web_container);
        btnDownloads = findViewById(R.id.btn_downloads);
        downloadPanel = findViewById(R.id.download_panel);
        downloadList = findViewById(R.id.download_list);
        downloadEmpty = findViewById(R.id.download_empty);
        startPage = findViewById(R.id.start_page);
        downloadBar = findViewById(R.id.download_bar);
        downloadText = findViewById(R.id.download_text);
        downloadAction = findViewById(R.id.download_action);
        findViewById(R.id.download_close).setOnClickListener(v -> hideDownload());
        startSearchBar = findViewById(R.id.start_search_bar);
        startBookmarkGrid = findViewById(R.id.start_bookmark_grid);
        startGridTitle = findViewById(R.id.start_grid_title);
        startEmpty = findViewById(R.id.start_empty);

        btnBack.setOnClickListener(v -> { if (active != null && active.web.canGoBack()) active.web.goBack(); });
        btnForward.setOnClickListener(v -> { if (active != null && active.web.canGoForward()) active.web.goForward(); });
        btnRefresh.setOnClickListener(v -> { if (active != null) active.web.reload(); });
        btnNewTab.setOnClickListener(v -> newTab());
        btnStar.setOnClickListener(v -> toggleBookmark());
        btnHome.setOnClickListener(v -> goHome());
        btnDownloads.setOnClickListener(v -> toggleDownloadPanel());

        wireSearchField(startSearchBar);
        // 占位字要自己上色:**ModeApplier 管不到 textColorHint**(它只改 textColor),
        // XML 里写的是浅色模式那个近黑的 muted —— 到了深色模式就是黑底黑字,
        // 搜索框看着像一条空槽,提示语根本读不出来。
        startSearchBar.setHintTextColor(ModeManager.get().muted());

        startBookmarkGrid.setAdapter(tileAdapter = new BookmarkTileAdapter());
        startBookmarkGrid.setOnItemClickListener((parent, v, position, id) -> {
            if (active != null) active.web.loadUrl(bookmarks.get(position).url);
        });
        startBookmarkGrid.setOnItemLongClickListener((parent, v, position, id) -> {
            Bookmark removed = bookmarks.remove(position);
            store.save(bookmarks);
            renderStartBookmarks();
            updateStar();
            Launchers.toast(this, "已删除 " + removed.title);
            return true;
        });

        newTab();
        openFromIntent(getIntent());

        ModeApplier.apply(findViewById(android.R.id.content));
    }

    /**
     * singleTask 的浏览器已经开着时,再被甩一个网址进来走的是这条,不是 onCreate。
     * 不 setIntent 的话 getIntent() 永远是最初那一份,下次再来同一个地址会被当成没变化。
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        openFromIntent(intent);
    }

    /**
     * 外部指定的网址:**另开一个标签**,不覆盖用户正看着的那页。
     * 用完即清(removeExtra),否则转屏之类的重建会把同一个地址再打开一遍。
     */
    private void openFromIntent(Intent i) {
        if (i == null) return;
        String url = i.getStringExtra(EXTRA_URL);
        if (url == null || url.trim().isEmpty()) return;
        i.removeExtra(EXTRA_URL);
        if (active != null && !active.url.isEmpty()) newTab();
        go(url);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (active != null) active.web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (active != null) active.web.onResume();
        applyDockSpace();
    }

    /**
     * 底部给 Dock 让的那一段,按**实际压进来多少**算,不用固定的 dock_space
     * (见 {@link DockHost#overlapInto})。
     *
     * 算两次:冷启动时 Dock 悬浮窗是跟着进程一起挂的,挂上到布局完中间隔着一帧 ——
     * 头一次去问它高度还是 0(实测两者只差 6ms),那一趟会算出"不用让",于是底栏被
     * Dock 压住一截。隔一小会儿补算一次就对了;日常进出浏览器时 Dock 早就在了,
     * 第一次就是对的,第二次只是白算一遍。
     */
    private void applyDockSpace() {
        final View space = findViewById(R.id.dock_space);
        final View root = findViewById(android.R.id.content);
        if (space == null || root == null) return;
        root.post(() -> setDockSpace(space, root));
        root.postDelayed(() -> setDockSpace(space, root), 400);
    }

    private void setDockSpace(View space, View root) {
        if (isFinishing() || isDestroyed()) return;
        int h = DockHost.overlapInto(root);
        if (space.getLayoutParams().height != h) {
            space.getLayoutParams().height = h;
            space.requestLayout();
        }
    }

    @Override
    protected void onDestroy() {
        downloadPoll.removeCallbacksAndMessages(null);
        for (Tab t : tabs) {
            webContainer.removeView(t.web);
            t.web.destroy();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (active != null && active.web.canGoBack()) {
            active.web.goBack();
            return;
        }
        super.onBackPressed();
    }

    // ---- 导航 ----

    private void go(String raw) {
        String url = normalize(raw);
        if (url == null || active == null) return;
        active.web.loadUrl(url);
    }

    /** 看着像网址就当网址(补全协议头),不然当搜索词交给百度——车机场景默认这个更常用。 */
    private static String normalize(String raw) {
        String q = raw == null ? "" : raw.trim();
        if (q.isEmpty()) return null;
        boolean looksUrl = !q.contains(" ")
                && (q.contains("://") || (q.contains(".") && Patterns.WEB_URL.matcher(q).matches()));
        if (looksUrl) return q.contains("://") ? q : "https://" + q;
        try {
            return "https://www.baidu.com/s?wd=" + URLEncoder.encode(q, "UTF-8");
        } catch (Exception e) {
            return "https://www.baidu.com/s?wd=" + q;
        }
    }

    private void updateNavButtons() {
        boolean back = active != null && active.web.canGoBack();
        boolean fwd = active != null && active.web.canGoForward();
        btnBack.setAlpha(back ? 1f : 0.35f);
        btnForward.setAlpha(fwd ? 1f : 0.35f);
    }

    // ---- 标签页 ----

    /**
     * 开一个新标签页,停在起始页(搜索框 + 常用网址)。
     *
     * **不自动弹键盘**:车机上新开一页多半是去点常用网址,自动弹出来的键盘要占掉半屏、
     * 还得先收掉它才看得见下面的格子(2026-09-12 用户问"添加新标签页怎么默认拉起搜索")。
     * 要输网址就点一下那个搜索框,一步而已。
     */
    private void newTab() {
        Tab t = new Tab();
        tabs.add(t);
        tabContainer.addView(t.chip);
        switchTo(t);
        startSearchBar.setText("");
    }

    private void switchTo(Tab t) {
        if (t == active) return;
        // 只摘掉旧标签那一个 WebView——不能用 removeAllViews(),容器里还挂着
        // start_page 那个静态子 view(见布局),removeAllViews 会把它一并摘掉。
        if (active != null) webContainer.removeView(active.web);
        active = t;
        webContainer.addView(t.web, 0, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        progress.setVisibility(View.GONE);
        updateChipStyles();
        updateNavButtons();
        updateStar();
        updateStartPage();
    }

    private void closeTab(Tab t) {
        int idx = tabs.indexOf(t);
        if (idx < 0) return;
        tabs.remove(idx);
        tabContainer.removeView(t.chip);
        boolean wasActive = t == active;
        if (wasActive) webContainer.removeView(t.web);
        t.web.destroy();
        if (tabs.isEmpty()) {
            // 关掉最后一个标签 = 回到空白起始页,**不是退出浏览器**。
            // 原来这儿直接 finish:人只想关掉手上这一页,结果整个浏览器没了、被丢回桌面
            // (2026-09-12 用户当场撞到)。手机浏览器也都是补一个新标签页,不是退出。
            active = null;
            newTab();
            return;
        }
        if (wasActive) {
            active = null; // 强制 switchTo 重新挂载,不能被"和 active 相同"的早退挡住
            switchTo(tabs.get(Math.max(0, idx - 1)));
        }
    }

    /**
     * 当前标签高亮(accent 底 + onAccent 字),其余是普通 tile 块。
     *
     * **tag 必须跟着换**:光 setBackgroundResource / setTextColor 是不够的 ——
     * 下一次 {@link ModeApplier#apply} 扫到这棵树,会照着 tag 里写死的 fill:tile / fg:ink
     * 把刚设好的高亮刷回去。onCreate 末尾那句 apply 正好在建第一个标签之后,
     * 于是**第一个标签永远不高亮**(2026-09-12 截图里就是这个:整条底栏只有一个标签,
     * 却是灰的),而后开的标签因为 apply 已经跑完,反倒是对的。
     */
    private void updateChipStyles() {
        for (Tab t : tabs) {
            boolean isActive = t == active;
            t.chip.setBackgroundResource(isActive ? R.drawable.tile_bg_active : R.drawable.tile_bg);
            t.chip.setTag(isActive ? "fill:accent;radius:radius_tile" : "fill:tile;radius:radius_tile");
            t.chipTitle.setTag(isActive ? "fg:onAccent" : "fg:ink");
            // **换完背景必须重新 apply**:setBackgroundResource 拿的是 XML 里那份原始
            // drawable(浅色模式的色值),会把上一次 apply 刷进去的颜色一起换掉 ——
            // 只改 tag 不 apply,深色模式下就成了浅灰底配白字,一个字都看不清
            // (2026-09-12 用户截图圈出来的就是这个)。
            ModeApplier.apply(t.chip);
        }
    }

    // ---- 书签 ----

    private void toggleBookmark() {
        if (active == null || TextUtils.isEmpty(active.url)) return;
        String url = active.url;
        boolean removed = bookmarks.removeIf(b -> b.url.equals(url));
        if (!removed) {
            String title = active.chipTitle.getText() == null ? url : active.chipTitle.getText().toString();
            bookmarks.add(0, new Bookmark(TextUtils.isEmpty(title) ? url : title, url));
        }
        store.save(bookmarks);
        updateStar();
        renderStartBookmarks();
    }

    private void updateStar() {
        boolean marked = active != null && !TextUtils.isEmpty(active.url)
                && store.isBookmarked(bookmarks, active.url);
        btnStar.setImageResource(marked ? R.drawable.ic_star_fill : R.drawable.ic_star);
        btnStar.setColorFilter(ModeManager.get().token(marked ? "accent" : "muted"));
    }

    /**
     * 回起始页:当前标签退回"还没导航过"的样子(搜索框 + 常用网址),不新开标签。
     *
     * 走 {@code about:blank} 而不是只把 start_page 盖上去 —— 盖上去的话 WebView 里
     * 那一页还在后台活着(视频接着播、脚本接着跑),人以为已经离开了。
     * onPageStarted 那边把 about:blank 归一成空串,起始页才认得出"这是空标签"。
     */
    private void goHome() {
        if (active == null) return;
        active.web.stopLoading();
        active.web.loadUrl("about:blank");
    }

    // ---- 下载内容 ----

    private void toggleDownloadPanel() {
        if (downloadPanel.getVisibility() == View.VISIBLE) {
            closeDownloadPanel();
            return;
        }
        downloadPanel.setVisibility(View.VISIBLE);
        renderDownloadList();
    }

    private void closeDownloadPanel() {
        downloadPanel.setVisibility(View.GONE);
    }

    /**
     * 下载过的东西,新的在上。数据直接问 {@link DownloadManager} 要 —— 它本来就替每个
     * app 记着自己那些下载,再自己存一份账只会和它对不上。
     *
     * apk 点一下直接调安装器(在线更新就靠这一下把包装上);别的文件交给系统去开。
     */
    private void renderDownloadList() {
        downloadList.removeAllViews();
        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        if (dm == null) { downloadEmpty.setVisibility(View.VISIBLE); return; }
        LayoutInflater inflater = LayoutInflater.from(this);
        int shown = 0;
        android.database.Cursor c = null;
        try {
            c = dm.query(new DownloadManager.Query());
            // 游标是按加入顺序给的,最新的一条在最后 —— 从后往前铺,最近下的排最上面
            if (c != null && c.moveToLast()) {
                do {
                    final long id = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID));
                    String name = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE));
                    int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    long done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    long total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                    if (name == null || name.isEmpty()) continue;
                    final boolean apk = name.toLowerCase(java.util.Locale.US).endsWith(".apk");
                    final boolean ok = status == DownloadManager.STATUS_SUCCESSFUL;
                    String state;
                    if (ok) state = sizeText(total > 0 ? total : done) + (apk ? " · 点这里安装" : " · 已完成");
                    else if (status == DownloadManager.STATUS_FAILED) state = "下载失败";
                    else state = total > 0 ? "正在下载 " + (done * 100 / total) + "%" : "正在下载";

                    View row = inflater.inflate(R.layout.item_bookmark_row, downloadList, false);
                    ((TextView) row.findViewById(R.id.bm_title)).setText(name);
                    ((TextView) row.findViewById(R.id.bm_url)).setText(state);
                    row.setOnClickListener(v -> {
                        if (!ok) return;                       // 还没下完/失败的,点了也没意义
                        closeDownloadPanel();
                        if (apk) installDownloaded(id);
                        else openDownloaded(id);
                    });
                    row.findViewById(R.id.bm_delete).setOnClickListener(v -> {
                        dm.remove(id);                         // 连文件一起删
                        renderDownloadList();
                    });
                    ModeApplier.apply(row);
                    downloadList.addView(row);
                    if (++shown >= 15) break;                  // 列太长没意义,车上也翻不动
                } while (c.moveToPrevious());
            }
        } catch (Exception ignored) {
            // 查不动就当没有记录,别为一个面板把浏览器带崩
        } finally {
            if (c != null) c.close();
        }
        downloadEmpty.setVisibility(shown == 0 ? View.VISIBLE : View.GONE);
    }

    /** 非 apk 的下载:交给系统里认这个类型的 app 去开。 */
    private void openDownloaded(long id) {
        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        if (dm == null) return;
        Uri uri = dm.getUriForDownloadedFile(id);
        if (uri == null) { showDownload("找不到下载的文件", false); return; }
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, dm.getMimeTypeForDownloadedFile(id));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        Launchers.start(this, i, "没有能打开这个文件的应用");
    }

    private static String sizeText(long bytes) {
        if (bytes >= 1048576) return String.format(java.util.Locale.CHINA, "%.1fMB", bytes / 1048576f);
        if (bytes >= 1024) return (bytes / 1024) + "KB";
        return Math.max(bytes, 0) + "B";
    }

    /** 新标签页的起始态:tab.url 还是空(没导航过)才露出来,点收藏直接跳转。 */
    private void updateStartPage() {
        boolean show = active != null && TextUtils.isEmpty(active.url);
        startPage.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) renderStartBookmarks();
    }

    /** 新标签页起始态:图标网格,点格子直接跳转,长按删除(跟桌面卡片同一套手势)。 */
    private void renderStartBookmarks() {
        // 没收藏就把「常用网址」标题和网格**整个收起**,只留一句提示:
        // 空标题底下吊着一个空网格,占半屏却什么都没有,是这一页最难看的地方
        boolean empty = bookmarks.isEmpty();
        startEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        startGridTitle.setVisibility(empty ? View.GONE : View.VISIBLE);
        startBookmarkGrid.setVisibility(empty ? View.GONE : View.VISIBLE);
        tileAdapter.notifyDataSetChanged();
    }

    /** 按标题/网址哈希取一个饱和度适中的固定色,书签格子没有真图标时靠颜色+首字互相区分。 */
    private static int tileColor(String seed) {
        int hash = seed == null ? 0 : seed.hashCode();
        float hue = Math.abs(hash) % 360;
        return Color.HSVToColor(new float[]{hue, 0.5f, 0.75f});
    }

    private static String firstGlyph(String label) {
        if (label == null || label.isEmpty()) return "·";
        return new String(Character.toChars(label.codePointAt(0)));
    }

    private final class BookmarkTileAdapter extends BaseAdapter {
        private final LayoutInflater inflater = LayoutInflater.from(BrowserActivity.this);

        @Override public int getCount() { return bookmarks.size(); }
        @Override public Object getItem(int position) { return bookmarks.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView : inflater.inflate(R.layout.item_bookmark_tile, parent, false);
            Bookmark b = bookmarks.get(position);
            TextView glyph = v.findViewById(R.id.tile_glyph);
            glyph.setText(firstGlyph(b.title));
            ((GradientDrawable) glyph.getBackground().mutate())
                    .setColor(tileColor(TextUtils.isEmpty(b.title) ? b.url : b.title));
            ((TextView) v.findViewById(R.id.tile_label)).setText(b.title);
            ModeApplier.apply(v);
            return v;
        }
    }

    // ---- 下载 ----

    /**
     * 交给系统 DownloadManager,存进公共 Downloads 目录、走系统通知栏——不需要
     * WRITE_EXTERNAL_STORAGE:写公共 Downloads 目录这件事本来就是 DownloadManager
     * 自己的进程在做,不是本 app 直接写文件,这条权限管不到它。
     */
    private void startDownload(String url, String userAgent, String contentDisposition, String mimeType) {
        try {
            String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.addRequestHeader("User-Agent", userAgent);
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) req.addRequestHeader("Cookie", cookie);
            req.setMimeType(mimeType);
            req.setTitle(fileName);
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (dm == null) { showDownload("下载失败", false); return; }
            downloadId = dm.enqueue(req);
            showDownload("正在下载 " + fileName, false);
            pollDownload();
        } catch (Exception e) {
            showDownload("下载失败", false);
        }
    }

    // ---- 下载条 ----

    /**
     * 下载进展摆在界面上,**不走 toast**:Android 12 起 toast 一律压在屏幕底部,
     * 而留白的常驻 Dock 正好铺在那儿 —— 等于什么也没提示。原来点了下载跳过来是一张
     * 空白起始页,人只会以为没下上。
     *
     * @param installable 下完了没有:是就把「安装」露出来
     */
    private void showDownload(String text, boolean installable) {
        downloadBar.setVisibility(View.VISIBLE);
        downloadText.setText(text);
        downloadAction.setVisibility(installable ? View.VISIBLE : View.GONE);
        ModeApplier.apply(downloadBar);
    }

    private void hideDownload() {
        downloadPoll.removeCallbacksAndMessages(null);
        downloadBar.setVisibility(View.GONE);
        downloadId = -1;
    }

    /** 每 600ms 问一次 DownloadManager。**下完/失败就停** —— 别让它一直空转。 */
    private void pollDownload() {
        downloadPoll.removeCallbacksAndMessages(null);
        downloadPoll.postDelayed(new Runnable() {
            @Override public void run() {
                if (downloadId < 0) return;
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                if (dm == null) return;
                android.database.Cursor c = null;
                try {
                    c = dm.query(new DownloadManager.Query().setFilterById(downloadId));
                    if (c == null || !c.moveToFirst()) return;
                    int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    long done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    long total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                    String name = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE));
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        // apk 才给「安装」:别的文件(图片、pdf)点了也没处去
                        boolean apk = name != null && name.toLowerCase(java.util.Locale.US).endsWith(".apk");
                        showDownload("下载完成 " + name, apk);
                        if (apk) downloadAction.setOnClickListener(v -> installDownloaded(downloadId));
                        return;                       // 到头了,不再排下一次
                    }
                    if (status == DownloadManager.STATUS_FAILED) {
                        showDownload("下载失败 " + (name == null ? "" : name), false);
                        return;
                    }
                    // 总大小服务端没给就是 -1,那时候只报已下多少,别显示一个假的百分比
                    String prog = total > 0
                            ? " " + (done * 100 / total) + "%"
                            : " " + (done / 1048576) + "MB";
                    showDownload("正在下载 " + name + prog, false);
                    pollDownload();
                } catch (Exception ignored) {
                    // 查不到就当这一轮没发生,下一轮再说
                    pollDownload();
                } finally {
                    if (c != null) c.close();
                }
            }
        }, 600);
    }

    /**
     * 点「安装」:把下好的包交给系统安装器。
     *
     * {@code getUriForDownloadedFile} 给的是 DownloadManager 自己的 content:// ——
     * 用它就不必为 apk 再配一个 FileProvider。
     *
     * Android 8 起装包还要「允许安装未知应用」这个开关,没开就先把那个设置页推到人面前:
     * 不引导的话安装器只会闪一下就没了,看着像点了没反应。
     */
    private void installDownloaded(long id) {
        if (id < 0) return;
        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        if (dm == null) return;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                && !getPackageManager().canRequestPackageInstalls()) {
            showDownload("先允许「安装未知应用」,再回来点安装", true);
            Intent grant = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName()));
            Launchers.start(this, grant, "请到系统设置里允许本应用安装未知应用");
            return;
        }
        Uri uri = dm.getUriForDownloadedFile(id);
        if (uri == null) { showDownload("找不到下载的文件", false); return; }
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        Launchers.start(this, i, "无法调起安装器,去「文件管理 → Downloads」里点它");
    }

    // ---- 键盘 ----

    /** 唯一的输入口——没有常驻地址栏,想换网址就得先回到起始页(见 newTab)。 */
    private void wireSearchField(EditText field) {
        field.setOnEditorActionListener((v, actionId, event) -> {
            // 只认 IME_ACTION_GO 是不够的:车机自带输入法的回车常常发的是
            // ACTION_UNSPECIFIED,甚至就是一个 KEYCODE_ENTER —— 那时候这一条整个不触发,
            // 按了回车什么也不发生。
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (!enter
                    && actionId != EditorInfo.IME_ACTION_GO
                    && actionId != EditorInfo.IME_ACTION_SEARCH
                    && actionId != EditorInfo.IME_ACTION_DONE
                    && actionId != EditorInfo.IME_ACTION_UNSPECIFIED) {
                return false;
            }
            // **先收键盘,再清焦点**:hideKeyboard 靠当前焦点那个 view 拿 windowToken,
            // 反过来写的话 getCurrentFocus() 已经是 null,键盘就留在屏幕上下不去了
            // (2026-09-12 用户撞到的"按了回车键盘还不下去")。
            hideKeyboard(v);
            v.clearFocus();
            go(field.getText().toString());
            return true;
        });
    }

    private void hideKeyboard() {
        hideKeyboard(null);
    }

    /** @param anchor 拿 windowToken 用的 view;传 null 才退回当前焦点 */
    private void hideKeyboard(View anchor) {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        View v = anchor != null ? anchor : getCurrentFocus();
        if (imm != null && v != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    // ---- 一个标签页的状态 ----

    private final class Tab {
        final WebView web;
        final View chip;
        final TextView chipTitle;
        String url = "";

        @SuppressLint("SetJavaScriptEnabled")
        Tab() {
            web = new WebView(BrowserActivity.this);
            WebSettings s = web.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setSupportZoom(true);
            s.setBuiltInZoomControls(true);
            s.setDisplayZoomControls(false);
            s.setLoadWithOverviewMode(true);
            s.setUseWideViewPort(true);
            web.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) ->
                    startDownload(url, userAgent, contentDisposition, mimeType));
            web.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    String scheme = request.getUrl().getScheme();
                    if ("http".equals(scheme) || "https".equals(scheme)) return false;
                    // 非网页协议(intent:/tel: 之类)交给系统,接不住就悄悄丢掉,别让网页内容把浏览器崩了
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, request.getUrl()));
                    } catch (Exception ignored) { }
                    return true;
                }

                @Override
                public void onPageStarted(WebView view, String url, Bitmap favicon) {
                    // about:blank 是「回起始页」自己发起的(见 goHome),不算导航过 ——
                    // 记成空串,start_page 才会重新盖上来
                    Tab.this.url = "about:blank".equals(url) ? "" : url;
                    if (Tab.this == active) {
                        progress.setProgress(0);
                        progress.setVisibility(View.VISIBLE);
                        updateStartPage();
                    }
                }

                @Override
                public void onPageFinished(WebView view, String finishedUrl) {
                    url = finishedUrl;
                    if (Tab.this == active) {
                        progress.setVisibility(View.GONE);
                        updateNavButtons();
                        updateStar();
                    }
                }
            });
            web.setWebChromeClient(new WebChromeClient() {
                @Override
                public void onProgressChanged(WebView view, int newProgress) {
                    if (Tab.this == active) progress.setProgress(newProgress);
                }

                @Override
                public void onReceivedTitle(WebView view, String title) {
                    chipTitle.setText(TextUtils.isEmpty(title) ? "新标签页" : title);
                }
            });

            chip = LayoutInflater.from(BrowserActivity.this)
                    .inflate(R.layout.item_browser_tab, tabContainer, false);
            chipTitle = chip.findViewById(R.id.tab_title);
            chip.setOnClickListener(v -> switchTo(Tab.this));
            chip.findViewById(R.id.tab_close).setOnClickListener(v -> closeTab(Tab.this));
            ModeApplier.apply(chip);
        }
    }
}
