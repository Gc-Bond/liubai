package com.liubai.blank.game;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 在线游戏库:游戏列表和 ROM 都在 yikm 站上,车机直接拉,不经中转。
 *
 * <h3>为什么要在线</h3>
 * 站上有约两万个 FC 条目、十几个分类,全量下载十几个 GB 又慢又占车机存储;
 * 而车机真正玩到的就是那么百八十个。改成**列表在线、ROM 按需下载**:
 * 列表只有文字(几 KB),进游戏中心顺手拉完;点哪个游戏才下哪个(一个 FC ROM
 * 几十到几百 KB,3Mbps 车机网几秒到几十秒)。下载完落本地,之后离线也能玩。
 *
 * <h3>两步走:列表 → 详情</h3>
 * 列表页只有 id、名字、缩略图;ROM 在 CDN 上的路径、街机的 romset/parent 要再开一次
 * play 页才有。所以详情**点下载时才拉**({@link #resolve}),列表加载不背这些请求。
 * ROM/封面直连 CDN,带 Referer(play 页地址)。
 */
public final class OnlineStore {

    private static final String TAG = "Blank/Online";

    private static final String HOME_URL = "https://www.yikm.net/";
    private static final String LIST_URL = "https://www.yikm.net/nes?page=%d&tag=%d";
    private static final String PLAY_URL = "https://www.yikm.net/play?id=%s";
    private static final String SEARCH_URL = "https://www.yikm.net/search?name=%s";
    private static final String ROM_HOST = "https://file.1990i.com";
    private static final String PIC_HOST = "https://img.1990i.com";

    private static final int CONNECT_TIMEOUT = 10000;
    private static final int READ_TIMEOUT = 30000;

    private static final ExecutorService IO = Executors.newFixedThreadPool(3);
    /**
     * 列表缩略图单独一条道:不能塞进 {@link #IO},ROM 下载(街机一个 20 MB)会把它
     * 堵死,翻页时封面就永远等不到。
     */
    private static final ExecutorService THUMBS = Executors.newFixedThreadPool(4);

    public static ExecutorService io() { return IO; }
    public static ExecutorService thumbs() { return THUMBS; }

    public interface Progress { void onProgress(long done, long total); }

    /**
     * 站上一个分类的描述。
     *
     * 只放"怎么认出这个分类"的最小信息:yikm 的 tag 参数决定列表内容,
     * 后缀决定落盘后的文件名(核心按后缀认平台)。
     */
    public static final class Category {
        /** 左栏/卡片上给人看的平台名,如 "红白机"。 */
        public final String label;
        /** 列表 URL 的 tag 参数。红白机的 0 只用来拿题材导航,列表另走题材(见 {@link #fetchFcList})。 */
        public final int tag;
        /** ROM 落地后缀(带点),核心按它认平台。 */
        public final String suffix;
        /** 没封面时卡片的底色,复用 GameLibrary 平台色。 */
        public final int color;

        public Category(String label, int tag, String suffix, int color) {
            this.label = label;
            this.tag = tag;
            this.suffix = suffix;
            this.color = color;
        }
    }

    /**
     * 站上一个游戏条目。
     *
     * 列表/搜索/热门出来的只有 id、名字、缩略图,下载相关的字段都是空的;
     * {@link #resolve} 抓过详情后返回一份填满的。
     */
    public static final class Remote {
        public final String id;
        public final String name;
        public final Category cat;
        /** 封面 URL(完整)。没有为空串。 */
        public final String pic;
        /** ROM 直连 URL。街机 clone 是**主驱动**那一份($ 前那段)。未 resolve 为空串。 */
        public final String romUrl;
        /**
         * 街机 clone 的 parent 直连 URL(可能多个),要和主驱动下到**同一目录**核心才认
         * ({@code samsh5sph.zip} 只有改动的芯片,其余在 {@code samsh5sp.zip} 里)。
         * FC/GBA 恒空。CDN 上是分开的文件,整串 {@code a.zip$b.zip} 拼路径必 404(实测)。
         */
        public final List<String> extraUrls;
        /** 下载 ROM/封面要带的 Referer(CDN 防盗链)。 */
        public final String referer;
        /**
         * 原始 romset 名(街机核心按它认驱动)。FC/GBA 是带路径的文件名,街机可能是
         * {@code dino.zip} 或 {@code kof97h.zip$kof97.zip}($ 拼 clone$parent)。未 resolve 为空串。
         */
        public final String romset;

        Remote(String id, String name, Category cat, String pic) {
            this(id, name, cat, pic, "", "", "", null);
        }

        Remote(String id, String name, Category cat, String pic, String romUrl,
               String referer, String romset, List<String> extraUrls) {
            this.id = id;
            this.name = name;
            this.cat = cat;
            this.pic = pic == null ? "" : pic;
            this.romUrl = romUrl == null ? "" : romUrl;
            this.referer = referer == null ? "" : referer;
            this.romset = romset == null ? "" : romset;
            this.extraUrls = extraUrls == null
                    ? Collections.emptyList() : Collections.unmodifiableList(extraUrls);
        }

        /** 下载后 ROM 落盘的文件名:中文名 + 平台后缀。 */
        public String romFileName() {
            return name.replace('/', '／').trim() + cat.suffix;
        }
    }

    private static final Pattern CARD = Pattern.compile(
            "<div class=\"card card-blog\">(.*?)(?=<div class=\"card card-blog\">|$)",
            Pattern.DOTALL);
    private static final Pattern TITLE = Pattern.compile(
            "<a\\s+href=\"/play\\?id=(\\d+)\">([^<]+)</a>");
    /** 列表卡片上的缩略图:<img class="img img-raised" src="..."> */
    private static final Pattern THUMB = Pattern.compile("<img[^>]*src=\"([^\"]+)\"");
    private static final Pattern LABEL = Pattern.compile("label[^>]*>([^<]+)</span>");
    private static final Pattern ROM = Pattern.compile("gromname=\"([^\"]+)\"");
    /**
     * 站方给个别游戏打的**补丁行**:
     * <pre>
     *   var gromname="kovplus2020tx.zip$kovplus.zip", gsystem="PGM", …;
     *   if(gameid=='9543')gromname="kovplus20tx.zip$kovplus.zip";   ← 这个才是对的
     * </pre>
     *
     * 前面那个名字是错的(站方自己知道,所以才补这一行)。而 {@link #ROM} 用 find()
     * 取的是**第一个**匹配 —— 正好是被覆盖掉的那个,下下来必然起不来。
     *
     * <b>必须校验 if 里的 id 等于当前请求的 id。</b>这段 JS 是模板,**每一页都带着
     * 这行**(恐龙快打那页也有 {@code if(gameid=='9543')}) —— 不校验的话所有游戏
     * 都会去下三国战纪那份 romset。
     */
    private static final Pattern ROM_PATCH = Pattern.compile(
            "if\\s*\\(\\s*gameid\\s*==\\s*'(\\d+)'\\s*\\)\\s*gromname\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern GSYS = Pattern.compile("gsystem=\"([^\"]+)\"");
    private static final Pattern PIC = Pattern.compile("gpic=\"([^\"]+)\"");

    /**
     * 列表的一页:这一页的条目 + 站上还有没有下一页。
     *
     * {@link #hasNext} 是**唯一**的到底判据,别拿条目数去猜 —— 详见
     * {@link #NEXT_PAGE}。
     */
    public static final class Page {
        public final List<Remote> items;
        public final boolean hasNext;

        Page(List<Remote> items, boolean hasNext) {
            this.items = items;
            this.hasNext = hasNext;
        }

        static Page empty() {
            return new Page(Collections.emptyList(), false);
        }
    }

    /**
     * 底部翻页条里的「下一页」按钮,判断站上还有没有下一页**只认这个**。
     *
     * <pre>
     *   还有:  &lt;li&gt;&lt;a style="…" href="/nes?page=2&amp;tag=9&amp;e="&gt;下一页…
     *   到底:  &lt;li class="disabled"&gt;&lt;a href="#"&gt;下一页…
     * </pre>
     *
     * <b>为什么不能拿「这页返回 0 条」当到底</b>:站方对超界页**不返回空,而是
     * 钳到最后一页**。实测街机 page=49/50/999 三个响应逐字节完全相同 —— 按空页
     * 判定永远等不到空,只会把最后一页反复灌进列表变成重复条目。
     *
     * <b>为什么锚在文字上而不是解析页码</b>:页码上千后站方带千分位逗号
     * (FC 末页是 {@code <a >1,031</a>},href 里也是 {@code page=1,030}),
     * 用 {@code \d+} 会截成 1。判到底根本用不着数字,整个绕开。
     *
     * 注意第 1 页的「上一页」也是 disabled,所以必须锚在「下一页」三个字上。
     * 繁简都收:{@link #open} 固定发 {@code Accept-Language: zh-CN} 拿到的是简体,
     * 万一站方改了口径也不至于当场瞎判。
     */
    private static final Pattern NEXT_PAGE = Pattern.compile(
            "<li([^>]*)>\\s*<a[^>]*>\\s*下一[页頁]");

    /**
     * 站上还有没有下一页。
     *
     * <b>解析不出来一律返回 false(停)。</b>不能默认 true —— 上面说的钳位行为
     * 让"假阳性"的代价是无限拉同一页,而"假阴性"只是少几页,后者便宜得多。
     */
    private static boolean hasNextPage(String html) {
        Matcher m = NEXT_PAGE.matcher(html);
        return m.find() && !m.group(1).contains("disabled");
    }

    /**
     * 拉一个分类的某一页列表。**阻塞,在 {@link #io()} 上调。** 拉不到抛
     * {@link UncheckedIOException},调用方不推进页码、下次重拉这一页。
     *
     * 页面是 HTML,不是 JSON —— 解析就按站点的卡片结构来。一次只拉一页(桌面 UA
     * 每页 20 个,见 {@link #open}),由调用方按需翻页。翻到哪儿算完由
     * {@link Page#hasNext} 说了算。
     */
    public Page fetchList(Category cat, int page) {
        if (cat.tag == 0) return fetchFcList(cat, page);
        String html = getPage(String.format(LIST_URL, page, cat.tag));
        List<Remote> out = parseCards(html, cat);
        boolean more = hasNextPage(html);
        Log.i(TAG, "列表第 " + page + " 页: " + cat.label + " " + out.size()
                + " 条, 还有下一页=" + more);
        return new Page(out, more);
    }

    // ---- 红白机:按题材依次翻 ----

    /**
     * 红白机第 n 页对应到哪个题材的第几页({@code {题材下标, 题材内页码}})。
     *
     * <b>红白机没有自己的总列表。</b>tag=0 是**全部平台**混排(2026-10 实测:第 50 页起
     * 混进 GBA/街机,250 页往后整页是 Java/NDS/DOS),只有题材子 tag(动作冒险、射击…,
     * e=0 那排)是纯红白机。所以平台列表 = 各题材首尾相接:一个题材翻到底接下一个题材的第 1 页。
     *
     * 第 n 页的落点要等第 n-1 页拉回来、知道它到没到底才定得下来,所以顺着翻页一格格往后记。
     * 调用方本来就是按页顺序拉的(见 GameActivity.loadMoreOnline),失败重拉同一页也查得到。
     */
    private static final Map<Integer, int[]> fcCursor = new HashMap<>();
    private static List<Genre> fcGenres;

    private Page fetchFcList(Category cat, int page) {
        List<Genre> genres;
        int[] at;
        synchronized (fcCursor) {
            if (fcGenres == null) {
                List<Genre> g = fetchGenres(0);
                if (g.isEmpty()) throw new UncheckedIOException(new IOException("红白机题材导航为空"));
                fcGenres = g;
                fcCursor.put(1, new int[] { 0, 1 });
            }
            genres = fcGenres;
            at = fcCursor.get(page);
        }
        if (at == null) {
            Log.w(TAG, "红白机第 " + page + " 页没有落点(没按顺序翻页)");
            return Page.empty();
        }
        Genre g = genres.get(at[0]);
        Page p = fetchGenreList(g, cat, at[1]);
        boolean lastGenre = at[0] + 1 >= genres.size();
        synchronized (fcCursor) {
            if (p.hasNext) fcCursor.put(page + 1, new int[] { at[0], at[1] + 1 });
            else if (!lastGenre) fcCursor.put(page + 1, new int[] { at[0] + 1, 1 });
        }
        return new Page(p.items, p.hasNext || !lastGenre);
    }

    /** 一个类型分类(网站首页/分类页那排「动作冒险、格斗…」)。 */
    public static final class Genre {
        /** 显示名,如「动作冒险」。 */
        public final String label;
        /** 列表 URL 的 tag 参数,FC 是数字 id(4),街机是文字(格斗)。 */
        public final String tag;
        /** 平台参数 e:0=FC、1=街机、2=GBA。 */
        public final int e;
        Genre(String label, String tag, int e) {
            this.label = label;
            this.tag = tag;
            this.e = e;
        }
    }

    private static final Pattern GENRE = Pattern.compile(
            "href=\"(?:https://www\\.yikm\\.net)?/nes\\?tag=([^&\"]+)&e=(\\d+)\""
                    + "[^>]*class=\"fa fa-tag tag-type\">([^<]+)</a>");

    /**
     * 抓一个平台的类型导航。**阻塞,在 {@link #io()} 上调。**
     *
     * 平台列表页(tag=0 FC / tag=9 街机)顶部那排 `tag-type` 链接就是类型,
     * 直接解析页面,不写死 —— 网站加了新类型我们跟着就有。
     */
    public List<Genre> fetchGenres(int platformTag) {
        List<Genre> out = new ArrayList<>();
        String html = getString(String.format(LIST_URL, 1, platformTag));
        if (html == null) return out;
        Matcher m = GENRE.matcher(html);
        while (m.find()) {
            out.add(new Genre(m.group(3).trim(), m.group(1), Integer.parseInt(m.group(2))));
        }
        Log.i(TAG, "平台 tag=" + platformTag + " 类型 " + out.size() + " 个");
        return out;
    }

    /**
     * 拉一个类型的某一页。**阻塞,在 {@link #io()} 上调。** 拉不到抛 {@link UncheckedIOException}。
     *
     * 类型页和平台页是同一套翻页条,到底判据同样是 {@link Page#hasNext}
     * (实测「格斗」只有 5 页,超界照样钳回第 5 页)。
     */
    public Page fetchGenreList(Genre g, Category cat, int page) {
        String html = getPage("https://www.yikm.net/nes?page=" + page
                + "&tag=" + encode(g.tag) + "&e=" + g.e);
        List<Remote> out = parseCards(html, cat);
        boolean more = hasNextPage(html);
        Log.i(TAG, "类型「" + g.label + "」第 " + page + " 页: " + out.size()
                + " 条, 还有下一页=" + more);
        return new Page(out, more);
    }

    // ---- 首页热门 ----

    /**
     * 首页热门板块的一项。
     *
     * 两种形态,照搬网站:**可玩的游戏**(点了进游戏/下载),和**系列聚合**
     * (「三国战纪系列」「合金弹头系列」「拳皇系列」)。系列在网站上点进去
     * 也是筛选列表而不是某一作,街机那块八个里有三个是这种。
     */
    public static final class Hot {
        /**
         * 画卡片用:封面和名字都从这儿取。
         *
         * **系列项也用它承载**(id 记成 {@code tag:三国战纪}),这样缩略图的下载和
         * 落盘缓存({@link #downloadThumb})不必为系列再开一份;前缀也保证它
         * 不会跟真游戏的数字 id 撞上。
         */
        public final Remote card;
        /** 非 null = 这是个系列,点了走 {@link #fetchGenreList};null = 可玩的游戏。 */
        public final Genre series;

        Hot(Remote card, Genre series) {
            this.card = card;
            this.series = series;
        }
    }

    /** 首页那三块热门。一次请求解析出来,别为每块各发一次。 */
    public static final class HotSections {
        public final List<Hot> fc = new ArrayList<>();
        public final List<Hot> arcade = new ArrayList<>();
        public final List<Hot> gba = new ArrayList<>();
    }

    /**
     * 热门板块的一条:{@code <li class="hot-li…"><a href=…><img src=…></img> 名字 </a>}。
     *
     * {@code <a\s+href} 的 {@code \s+} 不能省 —— 站上这处源码是 {@code <a  href=}
     * (**两个空格**),写死单空格一条都匹配不到,而板块切得出来、只是空手而归,
     * 看着像"网站没这块"。列表页的 TITLE 正则早踩过同一个坑。
     */
    private static final Pattern HOT_ITEM = Pattern.compile(
            "<li class=\"hot-li[^\"]*\">\\s*<a\\s+href=\"([^\"]+)\">\\s*"
                    + "<img[^>]*src=\"([^\"]+)\"[^>]*>\\s*(?:</img>)?\\s*([^<]+?)\\s*</a>",
            Pattern.DOTALL);
    private static final Pattern HOT_PLAY = Pattern.compile("/play\\?id=(\\d+)");
    private static final Pattern HOT_SERIES = Pattern.compile("/nes\\?tag=([^&\"]+)&e=(\\d+)");

    /**
     * 抓首页的三块热门(FC / 街机 / GBA)。**阻塞,在 {@link #io()} 上调。**
     *
     * 热门是站方**单独策展**的一份清单,跟列表页第一页不是一回事 —— 列表页把同一个
     * 游戏的各个版本平铺开(魂斗罗美版 / 魂斗罗美版4合1 / 激龟快打欧版 + 美版…),
     * 前十条里一半是重复作品;热门那八条是一作一条的精选,超级马里奥、雪人兄弟、
     * 坦克大战都只在这儿有。所以「热门」只能抓这几块,拿列表前十冒充跟网站永远对不上。
     *
     * 几块在同一张首页里,一次 GET 解析完。拉失败返回空,由调用方决定怎么退。
     */
    public HotSections fetchHot(Category fcCat, Category arcadeCat, Category gbaCat) {
        HotSections out = new HotSections();
        String html = getString(HOME_URL);
        if (html == null) return out;
        parseHotSection(html, "game-hot", fcCat, out.fc);
        parseHotSection(html, "game-arcade-hot", arcadeCat, out.arcade);
        parseHotSection(html, "game-gba-hot", gbaCat, out.gba);
        Log.i(TAG, "首页热门:FC " + out.fc.size() + " 条,街机 " + out.arcade.size()
                + " 条,GBA " + out.gba.size() + " 条");
        return out;
    }

    /** 按 div 的 id 切出一块热门,解析里头的 li。板块之间靠 {@code </ul>} 收边。 */
    private void parseHotSection(String html, String sectionId, Category cat, List<Hot> out) {
        int i = html.indexOf("id=\"" + sectionId + "\"");
        if (i < 0) {
            Log.w(TAG, "首页没有 " + sectionId + " 这块,站点改版了?");
            return;
        }
        int j = html.indexOf("</ul>", i);
        if (j < 0) return;
        int before = out.size();
        Matcher m = HOT_ITEM.matcher(html.substring(i, j));
        while (m.find()) {
            String href = m.group(1), pic = m.group(2), name = m.group(3).trim();
            if (name.isEmpty()) continue;
            Matcher pm = HOT_PLAY.matcher(href);
            if (pm.find()) {
                out.add(new Hot(new Remote(pm.group(1), name, cat, pic), null));
                continue;
            }
            Matcher sm = HOT_SERIES.matcher(href);
            if (sm.find()) {
                String tag = urlDecode(sm.group(1));
                out.add(new Hot(new Remote("tag:" + tag, name, cat, pic),
                        new Genre(name, tag, Integer.parseInt(sm.group(2)))));
            }
        }
        // 板块在、条目 0:多半是卡片的 HTML 结构改了(见 HOT_ITEM 那个双空格的坑)。
        // 不报的话首页那块只是空着,看上去跟"没网"一模一样
        if (out.size() == before) Log.w(TAG, sectionId + " 一条都没解析出来,卡片结构变了?");
    }

    /** 系列链接的 tag 是转义过的(`%E4%B8%89…`),要还原成中文再拿去拼筛选 URL。 */
    private static String urlDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    /**
     * 解析一页卡片。列表页和类型页都是同一套卡片结构。
     *
     * 题材页偶尔混进别的平台(红白机「格斗」第 1 页就有两张 GBA,实测),卡片第一个 label
     * 写着平台名的,和 {@code cat} 对不上就丢。漏网的到 {@link #resolve} 按 ROM 路径再拦一道。
     */
    private List<Remote> parseCards(String html, Category cat) {
        List<Remote> out = new ArrayList<>();
        Matcher cm = CARD.matcher(html);
        while (cm.find()) {
            Matcher tm = TITLE.matcher(cm.group(1));
            // 卡片里标题链接在图片链接之后,取最后一个匹配(名字那个)
            String id = null, name = null;
            while (tm.find()) { id = tm.group(1); name = tm.group(2).trim(); }
            if (id == null || name.isEmpty()) continue;
            String labelSuffix = suffixOfLabel(firstLabel(cm.group(1)));
            if (labelSuffix != null && !labelSuffix.equals(cat.suffix)) continue;
            // 缩略图取卡片第一张 img —— 列表页就这么一张。
            // **nopic 要当成没有图**:那是站方的占位图(一张"玩游戏的大脸"),
            // 当真图下下来的话整页卡片长得一模一样,还不如按平台画色块
            // (GBA 列表整页都是 nopic,实测)
            String pic = "";
            Matcher im = THUMB.matcher(cm.group(1));
            if (im.find()) pic = im.group(1);
            if (pic.contains("nopic")) pic = "";
            out.add(new Remote(id, name, cat, pic));
        }
        return out;
    }

    /**
     * 站内搜索,跟网站搜索框同一个接口(/search?name=)。**阻塞,在 {@link #io()} 上调。**
     *
     * 网站索引支持中文名、英文 romset 名(「snowbros」→ 雪人兄弟)和数字,
     * 返回跨平台结果。这里只收车机能玩的:
     * 街机(label 自带「街机」)、GBA(label 自带「GBA」,mGBA 能跑)和 FC
     * (搜索结果卡片上没有平台 label,靠封面图路径 /fcpic/ 认);MD/SFC 等
     * 没有核心,直接扔掉。搜索索引本身很弱(很多 FC 游戏搜不到),那是
     * 网站的问题 —— 本地已下载的由调用方合并进来(见 GameActivity.searchOnline)。
     */
    public List<Remote> search(String q) {
        List<Remote> out = new ArrayList<>();
        if (q == null || q.trim().isEmpty()) return out;
        String html = getString(String.format(SEARCH_URL, encode(q.trim())));
        if (html == null) return out;
        boolean sawUnknown = false;
        Matcher cm = CARD.matcher(html);
        while (cm.find()) {
            Matcher tm = TITLE.matcher(cm.group(1));
            String id = null, name = null;
            while (tm.find()) { id = tm.group(1); name = tm.group(2).trim(); }
            if (id == null || name.isEmpty()) continue;
            String pic = "";
            Matcher im = THUMB.matcher(cm.group(1));
            if (im.find()) pic = im.group(1);
            Category cat = categoryOf(firstLabel(cm.group(1)), pic);
            if (cat == null) {
                // 不支持的平台(MD/SFC…)也数一下,日志里能看出"网站有结果但车机
                // 玩不了"和"网站没结果"的区别,排查时不用抓包
                sawUnknown = true;
                continue;
            }
            out.add(new Remote(id, name, cat, pic));
        }
        Log.i(TAG, "搜索「" + q + "」: " + out.size() + " 条(车机可玩)"
                + (sawUnknown ? ",另有不支持平台的结果被过滤" : ""));
        return out;
    }

    /** 卡片里第一个 label 的文字,搜索页是类型/系列(「街机」「GBA」「策略」…)。 */
    private static String firstLabel(String cardHtml) {
        Matcher m = LABEL.matcher(cardHtml);
        return m.find() ? m.group(1).trim() : "";
    }

    /** label 写着的平台对应的后缀;是题材(「射击」「格斗」)不是平台的返回 null。 */
    private static String suffixOfLabel(String label) {
        if (label.contains("街机")) return ".zip";
        if (label.contains("GBA")) return ".gba";
        return null;
    }

    /**
     * 搜索结果卡片 → Category。
     *
     * 搜索页不像列表页那样标平台:街机/GBA 游戏第一个 label 就写着,
     * FC 游戏只有类型 label,但封面图在 /fcpic/ 目录下(MD 是 /mdpic/、
     * SFC 是 /sfcpic/),按图路径兜底认。nopic 是占位图,所有平台共用,
     * 认不出来就丢(没有可靠信号,别猜)。
     */
    private static Category categoryOf(String label, String pic) {
        if (label.contains("街机")) return new Category("街机", 9, ".zip", 0xFFE0A34A);
        if (label.contains("GBA")) return new Category("GBA", 11, ".gba", 0xFF6B8FE0);
        if (pic.contains("/fcpic/") && !pic.contains("nopic"))
            return new Category("红白机", 0, ".nes", 0xFFE05B4A);
        return null;
    }

    // ---- 详情 ----

    /**
     * 按 gromname 的路径判平台后缀,判不出(MD /mdrom/、SFC、红白机 HD 包 .zip 等)返回 null。
     * 红白机全是 /fcrom/…/*.nes,街机是不带斜杠的 fbneo 驱动名,GBA 是 /gbarom/NNNN.zip
     * (GameLibrary 按内容识别 zip,落盘名才叫 .gba)。
     */
    private static String suffixOfRom(String romPath) {
        String low = romPath.split("\\$", 2)[0].toLowerCase(java.util.Locale.US);
        if (low.startsWith("/fcrom/") && low.endsWith(".nes")) return ".nes";
        if (low.startsWith("/gbarom/") && low.endsWith(".zip")) return ".gba";
        if (!low.startsWith("/") && low.endsWith(".zip")) return ".zip";
        return null;
    }

    /**
     * 抓 play 页把下载要的都拼好,返回一份填满的 {@link Remote}。**阻塞,在 {@link #io()} 上调。**
     *
     * 列表页只有 id 和名字;ROM 在 CDN 上的路径要从 play 页的 {@code gromname} 拿,
     * 街机还要 {@code gsystem}(机器型号,如 PGM / capcom-cps-1):街机的 gromname 只有
     * 裸文件名,CDN 上按 {@code /roms/fbneo/{gsystem}/} 分目录(浏览器抓包确认)。
     *
     * @return 抓不到、或 ROM 路径判出来的平台和 {@code r.cat} 对不上(题材页混进来的)返回 null
     */
    public Remote resolve(Remote r) {
        if (!r.romUrl.isEmpty()) return r;
        String referer = String.format(PLAY_URL, r.id);
        String html = getString(referer);
        if (html == null) return null;
        Matcher rm = ROM.matcher(html);
        if (!rm.find()) return null;
        String rom = rm.group(1);
        // 站方后面可能用 if(gameid=='…') 把上面那个名字覆盖掉(见 ROM_PATCH)。
        // 只认打给**这个 id** 的那条 —— 每页都带着别人的补丁行
        Matcher patch = ROM_PATCH.matcher(html);
        while (patch.find()) {
            if (!r.id.equals(patch.group(1))) continue;
            Log.i(TAG, "站方给 " + r.id + " 打了补丁:" + rom + " → " + patch.group(2));
            rom = patch.group(2);
            break;
        }
        if (!r.cat.suffix.equals(suffixOfRom(rom))) {
            Log.w(TAG, r.name + " 的 ROM 不是" + r.cat.label + ": " + rom);
            return null;
        }
        String gsys = "";
        Matcher gm = GSYS.matcher(html);
        if (gm.find()) gsys = gm.group(1);
        String pic = r.pic;
        Matcher pm = PIC.matcher(html);
        if (pm.find()) pic = PIC_HOST + encode(pm.group(1));
        // 街机 clone 的 gromname 是 `clone.zip$parent.zip`(可能不止一段):CDN 上是**分开的文件**,
        // 整串拼进路径必 404。第一段是主驱动,其余是 parent,得下到同目录核心才找得到芯片
        List<String> extras = new ArrayList<>();
        String main = null;
        for (String seg : rom.split("\\$")) {
            if (seg.isEmpty()) continue;
            String url = romUrl(seg, gsys);
            if (main == null) main = url;
            else extras.add(url);
        }
        return new Remote(r.id, r.name, r.cat, pic, main, referer, rom, extras);
    }

    /** FC/GBA 的 gromname 自带完整路径;街机只有裸文件名,CDN 按机器型号分目录。 */
    private String romUrl(String seg, String gsys) {
        String path = seg.startsWith("/") ? seg : "/roms/fbneo/" + gsys + "/" + seg;
        return ROM_HOST + encode(path);
    }

    // ---- 下载(直连 CDN) ----

    /**
     * 下 ROM(和封面)到本地,直连 CDN(带 Referer)。{@code r} 要先过 {@link #resolve}。
     * **阻塞,在 {@link #io()} 上调。** ROM 已存在当成功(幂等)。
     *
     * <h3>街机 clone 的 parent</h3>
     * clone 的 romset 形如 {@code samsh5sph.zip$samsh5sp.zip}:clone 包里只有改动过的芯片,
     * 其余在 parent 包里,两份都躺在同一目录核心才加载得起来。{@link Remote#extraUrls} 就是那些
     * parent。**parent 先下、主驱动最后落地** —— 主文件在场即代表"这局全下齐了",
     * 开头那句幂等判断才站得住;反过来先下主的,parent 断在半路,下次进来直接 return true,
     * parent 就永远补不上了。parent 单个可达 80 MB,已在场的直接跳过,不重下。
     */
    public boolean download(Remote r, File romOut, File picOut, Progress p) {
        if (romOut.isFile() && romOut.length() > 0) return true;
        if (r.romUrl.isEmpty()) return false;
        int parts = r.extraUrls.size() + 1;      // 进度按「第几个文件 + 该文件的进度」分段算
        File dir = romOut.getParentFile();
        for (int i = 0; i < r.extraUrls.size(); i++) {
            String url = r.extraUrls.get(i);
            File dst = new File(dir, fileNameOf(url));
            if (dst.isFile() && dst.length() > 0) continue;
            if (!fetchPart(url, r.referer, dst, slice(p, i, parts))) {
                Log.w(TAG, "下 parent 失败 " + r.name + ": " + url);
                return false;                    // 缺 parent 的 clone 进游戏必是一屏错误,不算下好
            }
            Log.i(TAG, "parent 下好了 " + dst.getName() + " " + dst.length() + " 字节");
        }
        if (!fetchPart(r.romUrl, r.referer, romOut, slice(p, parts - 1, parts))) {
            Log.w(TAG, "下 ROM 失败 " + r.name);
            return false;
        }
        Log.i(TAG, "ROM 下好了 " + r.name + " " + romOut.length() + " 字节");
        // 封面:失败不影响玩
        if (picOut != null && !r.pic.isEmpty() && !(picOut.isFile() && picOut.length() > 0)) {
            File pp = new File(picOut.getAbsolutePath() + ".part");
            try {
                if (fetchTo(r.pic, r.referer, pp, null)) pp.renameTo(picOut);
                else pp.delete();
            } catch (IOException e) {
                pp.delete();
            }
        }
        return true;
    }

    /**
     * clone 的 parent 落地后按 CDN 原名叫 {@code samsho.zip},扫描时会自成一张英文名卡片
     * (它本身就是能玩的正经 romset)。拿 romset 名去站内搜(站方索引认英文 romset 名),
     * 搜到的街机条目抓详情、主驱动对得上就把中文名认回来。
     * 返回 {@code romset基名 → 中文名},调用方写进 {@code names.txt}。查不到就空着,
     * 卡片显示英文名照样能玩。
     *
     * <p>**阻塞**,在 {@link #io()} 上调;FC/GBA 恒空。
     */
    public Map<String, String> parentNames(Remote r) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (String u : r.extraUrls) {
            String file = fileNameOf(u);
            String base = file.replaceAll("(?i)\\.zip$", "");
            int tried = 0;
            for (Remote hit : search(base)) {
                if (!".zip".equals(hit.cat.suffix)) continue;
                if (++tried > 3) break;          // 每个候选一次详情请求,前几条对不上就算了
                Remote full = resolve(hit);
                if (full != null && file.equalsIgnoreCase(mainRomsetFile(full.romset))) {
                    out.put(base, full.name);
                    break;
                }
            }
        }
        return out;
    }

    /** romset 的主驱动文件名:去掉 {@code $parent} 和路径,如 {@code samsho.zip}。 */
    private static String mainRomsetFile(String romset) {
        if (romset.isEmpty()) return "";
        String main = romset.split("\\$")[0];
        int slash = main.lastIndexOf('/');
        return slash >= 0 ? main.substring(slash + 1) : main;
    }

    /**
     * 下一个文件到 {@code dst}:先写 {@code .part} 再改名,断在半路不会留半个 ROM 冒充能玩。
     * 失败会把残片删干净。
     */
    private boolean fetchPart(String url, String referer, File dst, Progress p) {
        File part = new File(dst.getAbsolutePath() + ".part");
        try {
            if (!fetchTo(url, referer, part, p) || !part.renameTo(dst)) {
                part.delete();
                return false;
            }
            return true;
        } catch (IOException e) {
            Log.w(TAG, "下载出错 " + url + ": " + e);
            part.delete();
            return false;
        }
    }

    /**
     * 把一个文件的进度折进「第 {@code i}/{@code n} 个文件」这一段。
     * clone 的主驱动 2 MB、parent 80 MB,不分段的话卡片会先冲到 100% 再干等几分钟。
     */
    private static Progress slice(Progress p, int i, int n) {
        if (p == null) return null;
        return (done, total) -> {
            long unit = 1000L;                      // 千分比记账,避免整数除法把小段抹平
            long inner = total > 0 ? done * unit / total : 0;
            p.onProgress(i * unit + inner, (long) n * unit);
        };
    }

    /** 从 URL 取落盘文件名(parent 按 CDN 上的原名落地,核心就按这个名字找 romset)。 */
    private static String fileNameOf(String url) {
        String path = url;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        try {
            name = java.net.URLDecoder.decode(name, "UTF-8");
        } catch (Exception ignored) {
            // 解不开就用原样,街机 romset 名本来就是 ASCII
        }
        return name;
    }

    /** 列表缩略图(封面)。**阻塞,在 {@link #thumbs()} 上调。** */
    public boolean downloadThumb(Remote r, File dst) {
        if (r.pic.isEmpty()) return false;
        File part = new File(dst.getAbsolutePath() + ".part");
        try {
            if (!fetchTo(r.pic, null, part, null)) { part.delete(); return false; }
            if (!part.renameTo(dst)) { part.delete(); return false; }
            return true;
        } catch (IOException e) {
            Log.w(TAG, "下缩略图失败 " + r.name + ": " + e);
            part.delete();
            return false;
        }
    }

    // ---- HTTP ----

    private String encode(String path) {
        // 中文、空格、括号都要转义,直接拼进 URL 会 404(Mac 抓包踩过)。
        // 只放行路径里必须原样的字符:字母数字和 / - _ . 以及几个常见符号
        StringBuilder sb = new StringBuilder();
        for (byte b : path.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '/' || c == '-' || c == '_'
                    || c == '.' || c == '(' || c == ')' || c == '[' || c == ']'
                    || c == '&' || c == '!' || c == '\'' || c == '+' || c == ',') {
                sb.append((char) c);
            } else {
                sb.append('%').append(String.format("%02X", c));
            }
        }
        return sb.toString();
    }

    /** 列表页:拉不到就抛,让调用方知道"没拉到"而不是"到底了"。 */
    private String getPage(String url) {
        String html = getString(url);
        if (html == null) throw new UncheckedIOException(new IOException("GET 失败 " + url));
        return html;
    }

    private String getString(String url) {
        try {
            HttpURLConnection c = open(url, null);
            try {
                int code = c.getResponseCode();
                if (code != 200) throw new IOException("HTTP " + code);
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                try (InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                }
                return bo.toString("UTF-8");
            } finally {
                c.disconnect();
            }
        } catch (IOException e) {
            Log.w(TAG, "GET 失败 " + url + ": " + e);
            return null;
        }
    }

    private boolean fetchTo(String url, String referer, File dst, Progress p) throws IOException {
        HttpURLConnection c = open(url, referer);
        try {
            int code = c.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "下载 " + code + ": " + url);
                return false;
            }
            long total = c.getContentLength();
            long done = 0;
            // 父目录不存在就先建 —— 云端缩略图落 sshots/,而这个目录只有下载过整包游戏才会
            // 被建出来。没下过游戏时直接写 sshots/x.png.part 会 ENOENT,封面全拉不出来(实测)。
            File parent = dst.getParentFile();
            if (parent != null && !parent.isDirectory()) parent.mkdirs();
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    if (p != null) p.onProgress(done, total);
                }
            }
            return true;
        } finally {
            c.disconnect();
        }
    }

    private HttpURLConnection open(String url, String referer) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT);
        c.setReadTimeout(READ_TIMEOUT);
        // 站上按 UA 给页大小:桌面 UA 每页 20 个,手机 UA 只有 10 个
        // (实测:同一个 tag 页,桌面 20 卡、Android/iPhone 各 10 卡)。
        // 列表和下载全走桌面 UA,同样的翻页次数能多拉一倍
        c.setRequestProperty("User-Agent", "Mozilla/5.0");
        // 不带这个头站上发的是**繁体**:首页热门板块的条目名是「三國戰紀系列」
        // 「三國志」,而列表页/搜索页给的是简体。两边名字对不上,mergeOnline 按名字
        // 反查本地、addSearchHit 按名字去重就全失效,同一个游戏会在列表里出现两遍。
        // 带上之后整站统一简体(实测)。URL 参数里的 tag 一直是简体,不受影响。
        c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        if (referer != null) c.setRequestProperty("Referer", referer);
        c.setRequestProperty("Accept-Encoding", "identity");   // rom 是二进制,不需要 gzip
        c.setInstanceFollowRedirects(true);
        return c;
    }
}
