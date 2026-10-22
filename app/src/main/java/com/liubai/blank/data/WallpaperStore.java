package com.liubai.blank.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.os.Environment;
import android.util.Log;


import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 桌面壁纸:有哪些、选了哪张、怎么把它取出来。
 *
 * 壁纸分三个来源、两种形态:
 *
 *   内置(o:)  assets/wallpapers/ 里预置的,跟着 APK 走,规格由 tools/wallpaper-video.sh 压过
 *   我的(p:)  用户自己往「内部存储/留白壁纸/」里放的,app 只读不管
 *   在线(n:)  从源站下下来的,落在私有目录 {@link #ONLINE_DIR_NAME}(见 OnlineWallpapers)
 *
 * **来源决定怎么取,形态决定怎么画**,两件事互不相干:
 *   - 内置走 AssetManager(fd / InputStream),我的和在线走普通文件路径 —— 这层差异由本类吃掉,
 *     对外统一成 {@link #load} 和 {@link #attach},调用方不用关心壁纸放在哪
 *   - 图片进 ImageView、视频进 TextureView + MediaPlayer —— 这层差异在桌面那边分支
 *     (见 HomeActivity.applyWallpaper),因为那是两种绘制机制,合不了
 *
 * 官方素材不放 res/drawable:那边会按 dpi 分桶缩放,车机是 240dpi,一张 1920×1080 的图
 * 丢进 drawable/ 会被当成 mdpi 资源放大成 3840×2160 再解码 —— 内存翻四倍还更糊。
 *
 * 走的是 app 自绘背景,不是系统壁纸(WallpaperManager):车机 ROM 砍掉壁纸服务的情况
 * 不少,自绘这条不依赖 ROM,视频壁纸也不必去求系统的 LiveWallpaper 支持。
 */
public final class WallpaperStore {

    private static final String TAG = "Blank/Wallpaper";

    /** 官方素材在 assets 下的目录名。 */
    public static final String ASSET_DIR = "wallpapers";

    /**
     * 个人素材的目录名,挂在内部存储根下 —— 用户在车机文件管理器里一眼能看到,
     * 拷文件进去就是全部操作。不用 getExternalFilesDir:那个路径是
     * Android/data/包名/files/…,车机的文件管理器多半进不去,也不该让用户去背。
     */
    public static final String PERSONAL_DIR_NAME = "留白壁纸";

    /**
     * 在线壁纸下下来放哪。**私有目录**(getExternalFilesDir/wallpapers_online),不跟"我的"混:
     * 那个目录是用户的地盘,下载物混进去就没法做缓存清理了;而私有目录零权限、卸载即清。
     */
    public static final String ONLINE_DIR_NAME = "wallpapers_online";

    private static final String PREFS = "wallpaper";
    /** 全局选中的壁纸 —— 一张走所有模式,切模式只切能力、不动壁纸。 */
    private static final String KEY_SELECTED = "selected";
    /**
     * 没选过时铺的默认壁纸。
     *
     * 2026-08-17 起内置壁纸只剩这一张:原先四张里最大的那个视频单独就占 12.7 MB,
     * 四张加起来 20.9 MB —— 比整个游戏中心(两个模拟核心)还大四倍,而壁纸本来就是
     * 用户要自己换的东西,内置一张够铺开屏幕即可,其余走在线壁纸。
     */
    private static final String DEFAULT_WALLPAPER = "黑夜猫.mp4";

    /** 没选壁纸。存空串而不是删键 —— "主动选了不要壁纸"和"还没选过"要能区分。 */
    public static final String NONE = "";

    /**
     * 壁纸来源。选中项的存法 = **来源前缀 + 文件名**:三个来源可能有重名文件,
     * 只存文件名分不出是哪张。前缀固定两个字符({@link #nameOf} 按这个长度切)。
     */
    public enum Source {
        OFFICIAL("o:"), PERSONAL("p:"), ONLINE("n:");
        public final String prefix;
        Source(String p) { this.prefix = p; }
    }

    private static final List<String> IMAGE_EXT = Arrays.asList(".jpg", ".jpeg", ".png", ".webp");
    private static final List<String> VIDEO_EXT = Arrays.asList(".mp4", ".webm", ".mkv", ".3gp");

    /**
     * 网页壁纸的名字尾巴。图和视频都是**一个文件**,网页壁纸是**一整个目录**
     * (入口 html + 一堆 js/贴图),这个尾巴就是两者在 id 里的分界:
     * {@code p:星空/} 是网页壁纸,{@code p:星空.mp4} 是视频。
     *
     * 为什么用尾巴而不是去 stat 文件系统:{@link #isVideoId} 这类判断在绘制路径上被反复调用
     * (每次 applyWallpaper、每次滑动预览),纯字符串判断才不会把磁盘 IO 带进主线程。
     */
    private static final String WEB_MARK = "/";

    /**
     * 网页壁纸的清单文件,**两家格式都认**:
     *   Wallpaper Engine —— {@code project.json},入口在 {@code file}
     *   Lively           —— {@code LivelyInfo.json},入口在 {@code FileName}
     *
     * 入口 html **不一定**叫 index.html(实测有叫 wallpaper.html、main.html 的),
     * 所以要读清单。两份都没有、或者读不出来,才退回 {@link #WEB_FALLBACK_ENTRY}。
     *
     * 顺序上先 WE 后 Lively:有的壁纸两份都带,而 project.json 是给引擎读的正本,
     * LivelyInfo.json 更像是打包时补的。
     */
    private static final String[][] WEB_MANIFESTS = {
            // { 文件名, 入口字段, 封面字段, 备用封面字段 }
            {"project.json",    "file",     "preview", ""},
            {"LivelyInfo.json", "FileName", "Preview", "Thumbnail"},
    };
    /** {@link #WEB_MANIFESTS} 每行的列号。封面从 M_COVER 起,后面几列都是候选。 */
    private static final int M_NAME = 0, M_ENTRY = 1, M_COVER = 2;
    private static final String WEB_FALLBACK_ENTRY = "index.html";

    /**
     * 清单里没写封面时的候选文件名。都找不到就没有封面,面板里显示成一块空底 ——
     * 不去实时渲染一张,那要起 WebView,代价比抽视频帧还大。
     */
    private static final List<String> WEB_PREVIEW = Arrays.asList(
            "preview.jpg", "preview.jpeg", "preview.png", "preview.gif", "preview.webp",
            "thumbnail.jpg", "thumbnail.png");

    /**
     * 视频封面文件的后缀:「视频原名 + 这个」。由 tools/wallpaper-video.sh 在转码时生成,
     * **改这里就要改那个脚本**。
     *
     * 有封面就当普通图片解,不碰视频解码器 —— 运行期抽帧要占一路解码器,而桌面上的视频
     * 壁纸本来就占着一路,一起抢会吐出半解码的绿色条纹帧(实测模式卡预览稳定坏)。
     */
    public static final String COVER_SUFFIX = ".cover.jpg";

    /** 视频首帧的统一尺寸。只做预览,不用比这更大;高度按原始比例算,不拉伸。 */
    private static final int FRAME_W = 480, FRAME_H = 270;
    /**
     * 首帧缓存,进程内共享。放静态是有意的:设置页、外观弹层、模式卡各建各的 WallpaperStore,
     * 挂在实例上等于没缓存。12 张 480×270 的图约 6MB,够用又不至于占太多。
     */
    private static final android.util.LruCache<String, Bitmap> FRAME_CACHE =
            new android.util.LruCache<>(12);

    private final Context app;
    private final SharedPreferences prefs;

    public WallpaperStore(Context ctx) {
        this.app = ctx.getApplicationContext();
        this.prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---- 一张壁纸 ----

    /** 一张壁纸。id 是存进 prefs 的那个带前缀的串,列表和选中态都认它。 */
    public static final class Item {
        public final Source source;
        public final String name;
        public final boolean video;
        public final boolean web;
        /** 在线壁纸的展示名(目录里的中文名);其余来源为空,UI 就用文件名。 */
        public final String label;

        Item(Source source, String name) { this(source, name, ""); }

        Item(Source source, String name, String label) {
            this.source = source;
            this.name = name;
            this.label = label == null ? "" : label;
            this.video = isVideo(name);
            this.web = isWeb(name);
        }

        public String id() {
            return source.prefix + name;
        }

        /**
         * 给用户看的名字。
         *
         * 网页壁纸摘掉那条标记用的尾巴,别让「/」露到界面上;图和视频**摘掉扩展名** ——
         * 「大树下的阳光」比「大树下的阳光.mp4」好看,而且形态由格子左上角那个小标去说,
         * 不必让扩展名再说一遍。
         */
        public String display() {
            if (!label.isEmpty()) return label;
            if (web) return name.substring(0, name.length() - WEB_MARK.length());
            int dot = name.lastIndexOf('.');
            return dot > 0 ? name.substring(0, dot) : name;
        }
    }

    public static boolean isVideo(String name) { return hasExt(name, VIDEO_EXT); }

    /** 是不是网页壁纸(一整个目录)。见 {@link #WEB_MARK}。 */
    public static boolean isWeb(String name) { return name != null && name.endsWith(WEB_MARK); }

    /** 封面不算壁纸:它是给视频当预览图的附属文件,不该自己出现在「静态」那一组里。 */
    public static boolean isImage(String name) {
        return hasExt(name, IMAGE_EXT) && !name.endsWith(COVER_SUFFIX);
    }

    private static boolean hasExt(String name, List<String> exts) {
        if (name == null) return false;
        String n = name.toLowerCase();
        for (String ext : exts) {
            if (n.endsWith(ext)) return true;
        }
        return false;
    }

    /** id 是哪个来源的。认不出前缀就当内置。 */
    private static Source sourceOf(String id) {
        if (id != null) {
            for (Source s : Source.values()) {
                if (id.startsWith(s.prefix)) return s;
            }
        }
        return Source.OFFICIAL;
    }

    /** 从 id 里取回文件名。 */
    private static String nameOf(String id) {
        if (id == null) return "";
        for (Source s : Source.values()) {
            if (id.startsWith(s.prefix)) return id.substring(s.prefix.length());
        }
        return id;
    }

    /** 选中的是不是视频。桌面靠它决定走 ImageView 还是 TextureView。 */
    public static boolean isVideoId(String id) {
        return isVideo(nameOf(id));
    }

    /** 选中的是不是网页壁纸。三选一里的第三个,走 WebView。 */
    public static boolean isWebId(String id) {
        return isWeb(nameOf(id));
    }

    /**
     * 网页壁纸的入口 html。
     *
     * 两家清单挨个试(见 {@link #WEB_MANIFESTS}),都读不出来才退回
     * {@link #WEB_FALLBACK_ENTRY}。
     *
     * @return 入口文件;不是网页壁纸、目录不在、连兜底的 index.html 都没有,返回 null
     */
    public File webEntry(String id) {
        File dir = webDir(id);
        if (dir == null) return null;

        for (String[] m : WEB_MANIFESTS) {
            // 清单里的路径可能带子目录(如 "html/index.html"),照原样拼
            File f = manifestFile(dir, m[M_NAME], m[M_ENTRY]);
            if (f != null) return f;
        }
        File fallback = new File(dir, WEB_FALLBACK_ENTRY);
        return fallback.isFile() ? fallback : null;
    }

    /** 从某份清单里读一个字符串字段。没有清单、没有这个字段、读不懂,都返回空串。 */
    private static String manifestString(File dir, String manifest, String field) {
        File mf = new File(dir, manifest);
        if (!mf.isFile()) return "";
        try {
            return new org.json.JSONObject(readText(mf)).optString(field, "");
        } catch (Exception e) {
            Log.w(TAG, "读不懂 " + manifest + ":" + dir.getName(), e);
            return "";
        }
    }

    /**
     * 从某份清单里读一个字段,当成目录内的相对路径取文件。
     *
     * @return 字段有值、指向的文件也在,才返回它;否则 null(交给调用方接着试下一个)
     */
    private static File manifestFile(File dir, String manifest, String field) {
        String v = manifestString(dir, manifest, field);
        if (v.isEmpty()) return null;
        File f = new File(dir, v);
        if (f.isFile()) return f;
        Log.w(TAG, manifest + " 的 " + field + " 指向的文件不存在:" + v);
        return null;
    }

    /**
     * 网页壁纸所在的目录。内置来源(assets)没有目录,不支持网页壁纸。
     *
     * 公开是因为桌面要拿它当**站点根**:网页壁纸整个目录会被挂成一个假的 https 域,
     * 入口和它引的资源都从这个根下面取(见 HomeActivity.showWebWallpaper)。
     */
    public File webDir(String id) {
        if (!isWebId(id)) return null;
        File base = dirFor(sourceOf(id));
        if (base == null) return null;
        String name = nameOf(id);
        File dir = new File(base, name.substring(0, name.length() - WEB_MARK.length()));
        return dir.isDirectory() ? dir : null;
    }

    private static String readText(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            for (int n; (n = in.read(chunk)) > 0; ) buf.write(chunk, 0, n);
            return buf.toString("UTF-8");
        }
    }

    // ---- 列表 ----

    /**
     * 官方素材(assets/wallpapers/)。
     *
     * 按文件名排序,保证每次列出来的顺序一样 —— AssetManager.list() 的顺序没有承诺,
     * 不排的话设置页里的缩略图会来回换位置。
     */
    public List<Item> official() {
        List<Item> out = new ArrayList<>();
        try {
            String[] files = app.getAssets().list(ASSET_DIR);
            if (files != null) {
                for (String f : files) {
                    if (isImage(f) || isVideo(f)) out.add(new Item(Source.OFFICIAL, f));
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "读不到 assets/" + ASSET_DIR, e);
        }
        sort(out);
        return out;
    }

    /** 个人素材(内部存储/留白壁纸/)。没权限、目录不存在都返回空 —— 由 UI 去分辨是哪种。 */
    public List<Item> personal() {
        return scan(personalDir(), Source.PERSONAL);
    }

    /**
     * **已经下下来的**在线壁纸。只扫本地目录,不联网 —— 桌面铺壁纸这条路上不能有网络调用。
     * 橱窗里那些"还没下的"由 {@link OnlineWallpapers} 单独管,两者在面板里合并展示。
     */
    public List<Item> onlineDownloaded() {
        return scan(onlineDir(), Source.ONLINE);
    }

    private List<Item> scan(File dir, Source source) {
        List<Item> out = new ArrayList<>();
        if (dir == null || !dir.isDirectory()) return out;
        File[] files = dir.listFiles();
        if (files == null) return out;      // 没权限时就是 null,不是空数组
        for (File f : files) {
            String n = f.getName();
            if (f.isDirectory()) {
                // 网页壁纸:一个目录算一张。判据是"里面有入口 html" —— 用户往壁纸目录里
                // 随手建的普通文件夹不该冒出来当壁纸。
                // 展示名就用**目录名** —— 那是用户自己起的。清单里那个 Title 不用:
                // 它是原作者写的,基本都是英文(MIDNIGHT、Koi Pond),摆在中文界面里
                // 还不如用户拷进来时随手起的名字。
                if (isWebDir(f)) out.add(new Item(source, n + WEB_MARK));
            } else if (isImage(n) || isVideo(n)) {
                out.add(new Item(source, n));
            }
        }
        sort(out);
        return out;
    }

    /** 这个目录是不是一张网页壁纸:带着任一家的清单、或者至少有个 index.html。 */
    private static boolean isWebDir(File dir) {
        for (String[] m : WEB_MANIFESTS) {
            if (new File(dir, m[M_NAME]).isFile()) return true;
        }
        return new File(dir, WEB_FALLBACK_ENTRY).isFile();
    }

    private static void sort(List<Item> items) {
        Collections.sort(items, new Comparator<Item>() {
            // 按**显示名**排,不按文件名 —— 网页壁纸显示的是清单里的作者原名,
            // 按文件名排的话界面上的顺序看着是乱的
            @Override public int compare(Item a, Item b) {
                return a.display().compareToIgnoreCase(b.display());
            }
        });
    }

    /** 个人壁纸目录。不主动创建 —— 建目录要写权限,而读列表只要读权限,别把门槛抬高。 */
    public static File personalDir() {
        return new File(Environment.getExternalStorageDirectory(), PERSONAL_DIR_NAME);
    }

    /** 给用户看的路径。真实路径 /storage/emulated/0/… 对用户没有意义。 */
    public static String personalDirLabel() {
        return "内部存储/" + PERSONAL_DIR_NAME + "/";
    }

    /** 在线壁纸的落脚点(私有目录,零权限)。目录不存在就建 —— 这个是 app 自己写的,不像个人目录。 */
    public File onlineDir() {
        File dir = new File(app.getExternalFilesDir(null), ONLINE_DIR_NAME);
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    /** 这个来源的素材躺在哪个目录。内置在 assets 里,没有目录,返回 null。 */
    private File dirFor(Source s) {
        if (s == Source.PERSONAL) return personalDir();
        if (s == Source.ONLINE) return onlineDir();
        return null;
    }

    public boolean canReadPersonal() {
        return app.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    // ---- 选中 ----

    /**
     * 桌面铺哪张。全局只此一张,两级回退:用户选过的(含主动选「无」)→ 固定默认 → 不铺。
     */
    public String current() {
        String v = prefs.getString(KEY_SELECTED, null);
        if (v != null) return v;                        // 用户表过态(包括主动选"无")
        return Source.OFFICIAL.prefix + DEFAULT_WALLPAPER;
    }

    public void set(String id) {
        prefs.edit().putString(KEY_SELECTED, id == null ? NONE : id).apply();
    }

    /**
     * 桌面上左右滑能切到的那一圈壁纸(见 {@link com.liubai.blank.ui.WallpaperSwiper})。
     *
     * 内置 + 我的 + **已经下下来的**在线壁纸,顺序和外观面板里看到的一致 ——
     * 滑动切出来的顺序要能和面板里那份对上,不然人会觉得"怎么跳来跳去的"。
     *
     * **只收下载好的**:桌面左右滑不该滑出一个"正在下载"或者一片空白。没下的只在面板里出现。
     *
     * **交互壁纸整个不参与**,它不是划着换的东西:
     *   一是手势冲突 —— 它自己要吃触摸(拖动就是它的交互),横滑会把手势整个抢走,
     *     那些靠拖动玩的壁纸就永远拖不动;
     *   二是没有能代表它的那张图 —— 滑动途中一律拿静态图跟手(视频也退化成首帧),
     *     而交互壁纸只有一张封面,滑起来是死的,松手才活过来,看着像卡了。
     * 摘出去之后还顺带解决了"当前铺着交互壁纸时不该开滑":它不在环里,
     * {@code ring.indexOf(cur)} 返回 -1,WallpaperSwiper.begin 现成那条 index<0 就挡住了。
     * 换交互壁纸走长按 → 外观面板。
     */
    public List<String> slideRing() {
        List<String> out = new ArrayList<>();
        for (Item it : official()) if (!it.web) out.add(it.id());
        for (Item it : personal()) if (!it.web) out.add(it.id());
        for (Item it : onlineDownloaded()) if (!it.web) out.add(it.id());
        return out;
    }


    // ---- 取素材 ----

    /**
     * 把数据源交给播放器:内置走 assets 的 fd,我的和在线走文件路径。
     *
     * 这个差异只在这儿存在一次 —— 桌面那边拿到的是"能不能播"这一个结果,
     * 不用再为三个来源各写一遍 setDataSource。
     *
     * @return 交上去了就返回 true;文件没了 / 读不到返回 false,调用方按"放不了"处理
     */
    public boolean attach(MediaPlayer mp, String id) {
        String name = nameOf(id);
        if (name.isEmpty()) return false;
        File dir = dirFor(sourceOf(id));
        if (dir != null) {
            File f = new File(dir, name);
            if (!f.isFile()) return false;
            try {
                mp.setDataSource(f.getAbsolutePath());
                return true;
            } catch (Exception e) {
                Log.w(TAG, "视频喂不进播放器 " + name, e);
                return false;
            }
        }
        AssetFileDescriptor fd = openAsset(name);
        if (fd == null) return false;
        try {
            mp.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
            return true;
        } catch (Exception e) {
            Log.w(TAG, "内置视频喂不进播放器 " + name, e);
            return false;
        } finally {
            // setDataSource 内部把 fd dup 了一份,这里可以直接关
            try { fd.close(); } catch (IOException ignored) { }
        }
    }

    private AssetFileDescriptor openAsset(String name) {
        try {
            return app.getAssets().openFd(ASSET_DIR + "/" + name);
        } catch (IOException e) {
            Log.w(TAG, "打不开 assets/" + ASSET_DIR + "/" + name, e);
            return null;
        }
    }

    /**
     * 一张能直接显示的位图:图片就是它本身,视频取第一帧。
     *
     * 设置页的缩略图、桌面的静态壁纸、视频壁纸的垫底图都走这里,区别只在请求尺寸。
     *
     * @return 取不出来返回 null(文件被删了 / 格式不认 / 没权限),调用方按"没有壁纸"处理
     */
    public Bitmap load(String id, int reqW, int reqH) {
        String name = nameOf(id);
        if (name.isEmpty()) return null;
        Source src = sourceOf(id);
        if (isWeb(name)) {
            String preview = webPreview(id);
            return preview == null ? null : decodeImage(src, preview, reqW, reqH);
        }
        return isVideo(name) ? firstFrame(src, name, reqW, reqH)
                             : decodeImage(src, name, reqW, reqH);
    }

    /**
     * 网页壁纸的预览图,返回**相对来源目录**的路径(decodeImage 那条路就认这个)。
     * 没有预览图就返回 null —— 不去起 WebView 现渲一张,那比抽视频帧还贵。
     */
    private String webPreview(String id) {
        File dir = webDir(id);
        if (dir == null) return null;
        // 清单里写了的优先 —— 比按文件名猜准。Lively 那份分 Preview(动图)和
        // Thumbnail(静图)两个字段,动图取第一帧就是张静图,先用它。
        for (String[] m : WEB_MANIFESTS) {
            for (int i = M_COVER; i < m.length; i++) {
                if (m[i].isEmpty()) continue;
                File f = manifestFile(dir, m[M_NAME], m[i]);
                if (f != null) return dir.getName() + "/" + f.getName();
            }
        }
        for (String p : WEB_PREVIEW) {
            if (new File(dir, p).isFile()) return dir.getName() + "/" + p;
        }
        return null;
    }

    /** 这个文件在不在。内置只能试着打开 assets,其余直接 stat 文件系统。 */
    private boolean exists(Source src, String name) {
        File dir = dirFor(src);
        if (dir != null) return new File(dir, name).isFile();
        try (InputStream ignored = app.getAssets().open(ASSET_DIR + "/" + name)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** 打开素材字节流。内置在 assets 里,其余是普通文件 —— 解码那两条路只认流,不关心来源。 */
    private InputStream open(Source src, String name) throws IOException {
        File dir = dirFor(src);
        if (dir != null) return new FileInputStream(new File(dir, name));
        return app.getAssets().open(ASSET_DIR + "/" + name);
    }

    /**
     * 解码图片,按目标尺寸降采样。
     *
     * "我的"素材是用户随手拷进来的,可能是几千像素宽的原图:全尺寸解码一张 4000×3000
     * 就是 48MB,几张缩略图排一起足够把设置页 OOM 掉。inSampleSize 只按 2 的幂降,
     * 所以解出来通常仍略大于目标尺寸,交给 ImageView 的 centerCrop 收尾。
     */
    private Bitmap decodeImage(Source src, String name, int reqW, int reqH) {
        int sample = 1;
        if (reqW > 0 && reqH > 0) {
            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;    // 只读图的宽高,不真解码、不占内存
            try (InputStream in = open(src, name)) {
                BitmapFactory.decodeStream(in, null, probe);
            } catch (IOException e) {
                Log.w(TAG, "打不开 " + name, e);
                return null;
            }
            sample = sampleFor(probe.outWidth, probe.outHeight, reqW, reqH);
        }

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        try (InputStream in = open(src, name)) {
            return BitmapFactory.decodeStream(in, null, opts);
        } catch (IOException | OutOfMemoryError e) {
            Log.w(TAG, "解不开 " + name, e);
            return null;
        }
    }

    /**
     * 视频的第一帧,当缩略图和垫底图用。**抽出来就缓存,一个视频只抽一次。**
     *
     * 缓存不是为了快,是为了**正确**:抽帧要占一路解码器,而桌面上的视频壁纸本来就占着一路。
     * 一口气抽三四个视频时解码器不够用,getFrameAtTime 不报错、也不返回 null,而是吐出
     * 一张半解码的绿色条纹帧 —— 实测模式卡上两张预览同时坏掉、还都串成了同一个视频的画面。
     * 少解几次,这件事就基本不发生了。
     *
     * 同一个视频在不同地方要的尺寸不一样(缩略图 120dp、模式卡预览 226dp),所以统一按
     * {@link #FRAME_W}×{@link #FRAME_H} 抽一张存着,谁要谁自己缩 —— 按各自尺寸各抽一次
     * 就等于没缓存。这张图只做预览,480×270 够用了。
     *
     * 用 OPTION_CLOSEST_SYNC 取 0ms 处的关键帧:精确抽帧(OPTION_CLOSEST)要往前解一串帧,
     * 一个列表里有几个视频就卡几下,而封面差几帧根本看不出来。
     *
     * 返回的位图是**共享**的,调用方只读、不要 recycle。
     */
    private Bitmap firstFrame(Source src, String name, int reqW, int reqH) {
        // 有现成封面就走图片这条路,一路视频解码器都不用占。内置素材的封面由
        // tools/wallpaper-video.sh 转码时生成,在线素材的封面跟着下载一起下来;
        // 只有"我的"是用户自己拷进来的、可能没有封面,才退回下面真去抽帧。
        String cover = name + COVER_SUFFIX;
        if (exists(src, cover)) {
            Bitmap b = decodeImage(src, cover, reqW, reqH);
            if (b != null) return b;
        }

        // 只有预览尺寸才走缓存。视频壁纸的垫底图要铺满整屏(见 HomeActivity.applyWallpaper),
        // 拿 480 宽的缓存去拉满 1920 会糊得很明显;而它是切换壁纸时单抽一张,
        // 不像列表那样一口气抽好几个,不会引起上面说的解码器打架。
        boolean cacheable = reqW <= FRAME_W;
        String key = src.prefix + name;
        if (cacheable) {
            Bitmap hit = FRAME_CACHE.get(key);
            if (hit != null && !hit.isRecycled()) return hit;
        }

        File dir = dirFor(src);
        // 磁盘缓存:只给预览尺寸、且只给文件型来源(内置的都带封面,走不到这儿)。
        // 铺满整屏的垫底图不走,那个要的比 FRAME_W 大,拿它去拉满会糊。
        File disk = (cacheable && dir != null) ? frameCacheFile(dir, name) : null;
        if (disk != null && disk.isFile()) {
            Bitmap b = decodeFile(disk, reqW, reqH);
            if (b != null) {
                FRAME_CACHE.put(key, b);
                return b;
            }
        }

        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        AssetFileDescriptor fd = null;
        try {
            if (dir != null) {
                File f = new File(dir, name);
                if (!f.isFile()) return null;
                mmr.setDataSource(f.getAbsolutePath());
            } else {
                fd = openAsset(name);
                if (fd == null) return null;
                mmr.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
            }
            int want = cacheable ? FRAME_W : Math.max(reqW, 1);
            /*
             * **让解码器直接吐缩好的帧**(API 27+)。getFrameAtTime 给的是原始分辨率,
             * 4K 素材就是一张 3840×2160、33MB 的位图 —— 又费内存,又是撕裂坏帧的重灾区
             * (模拟器上实拍到 4K 那张缩略图撕成两半、混着别的画面;真车上表现为绿条纹)。
             * 要 480 宽就只解 480 宽,省下的不只是内存。
             */
            Bitmap full = null;
            if (android.os.Build.VERSION.SDK_INT >= 27) {
                full = mmr.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        want, Math.max(1, want * 9 / 16));
            }
            if (full == null) full = mmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (full == null) return null;
            Bitmap out = full;
            if (full.getWidth() > want) {
                int h = Math.max(1, full.getHeight() * want / full.getWidth());
                out = Bitmap.createScaledBitmap(full, want, h, true);
                if (out != full) full.recycle();
            }
            if (cacheable) FRAME_CACHE.put(key, out);
            if (disk != null) saveJpg(out, disk);
            return out;
        } catch (Exception | OutOfMemoryError e) {
            Log.w(TAG, "抽不出封面 " + name, e);
            return null;
        } finally {
            try { mmr.release(); } catch (Exception ignored) { }
            if (fd != null) {
                try { fd.close(); } catch (IOException ignored) { }
            }
        }
    }

    /**
     * 抽出来的首帧在私有缓存里的落脚点。**一个视频这辈子只抽一次**。
     *
     * 内存那份({@link #FRAME_CACHE})只活一个进程,面板关掉再开、桌面被系统回收过就没了,
     * 于是每次都要重抽 —— 而抽帧正是最容易出事的那一步(解码器被抢时吐半解码的坏帧,
     * 见本类 firstFrame 的说明)。外观面板改成一屏九张大图之后,这件事更容易撞上。
     *
     * 文件名带上大小和改动时间:用户把同名文件换成另一个视频,缓存自动失效。
     */
    private File frameCacheFile(File dir, String name) {
        File src = new File(dir, name);
        if (!src.isFile()) return null;
        File cacheDir = new File(app.getCacheDir(), "wpframe");
        if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) return null;
        String stamp = name + "_" + src.length() + "_" + src.lastModified();
        return new File(cacheDir, Integer.toHexString(stamp.hashCode()) + ".jpg");
    }

    private Bitmap decodeFile(File f, int reqW, int reqH) {
        BitmapFactory.Options probe = new BitmapFactory.Options();
        probe.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), probe);
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sampleFor(probe.outWidth, probe.outHeight,
                Math.max(reqW, 1), Math.max(reqH, 1));
        try {
            return BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    /** 写不下就算了 —— 缓存是加速,不是功能,下次照样能抽。 */
    private void saveJpg(Bitmap b, File to) {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(to)) {
            b.compress(Bitmap.CompressFormat.JPEG, 88, out);
        } catch (Exception e) {
            Log.w(TAG, "首帧缓存写不下 " + to.getName(), e);
        }
    }

    /**
     * 壁纸**底部那一条**的主色。Dock 正压在那儿,取这块才谈得上"融进去" ——
     * 取整张图的平均色会被天空、主体一起拉平,和 Dock 实际盖住的位置对不上。
     *
     * 拿到的色不能直接用:壁纸底部可能很亮(白天的天空)也可能很暗,直接铺上去图标就压不住。
     * 所以只**取色相和饱和度,明度按当前配色的深浅重设** —— 深色配色压到很暗、浅色提到很亮,
     * 于是 Dock 既带着壁纸的调子,又始终保证图标读得清。
     *
     * @param darkBase 当前配色是不是深色底(由调用方按 bg 的亮度判断)
     * @return 0 表示取不出来(没壁纸 / 解不开),调用方回退到模式的 surface
     */
    public int bottomTint(String id, boolean darkBase) {
        int avg = sampleBottomColor(id);
        if (avg == 0) return 0;

        float[] hsl = new float[3];
        androidx.core.graphics.ColorUtils.colorToHSL(avg, hsl);
        /*
         * 饱和度压到**只剩一丝调子**(0.05~0.14)。
         *
         * 原来是 0.18~0.45,理由是"太低就成了一坨死灰,看不出跟壁纸有什么关系"。
         * 但这个值出来是要当**面板 / 弹窗 / Dock 的底**的,而配色骨架的第一条就是
         * 「背景是不透明的中性灰阶,没有色相」(鸿蒙 base/element/color.json)。0.18 的下限在草地壁纸上直接兑出一块暗橄榄绿,
         * 摆进一屏中性灰里就是一块脏斑 —— 一整个面板都那个颜色时更明显。
         *
         * 现在这档的观感是"暖壁纸偏暖灰、冷壁纸偏冷灰",跟得出壁纸的调子,
         * 又不至于自己变成一个颜色。**上限比下限重要**:下限只是别算成绝对零饱和。
         */
        hsl[1] = Math.min(0.14f, Math.max(hsl[1], 0.05f));
        hsl[2] = darkBase ? 0.13f : 0.88f;
        return androidx.core.graphics.ColorUtils.HSLToColor(hsl);
    }

    /**
     * 壁纸底部那条本身是亮是暗 —— 采样得到的真实亮度,不经过 {@link #bottomTint} 那道
     * "按 darkBase 钉死明度"的改写。「跟随壁纸」选深色套还是浅色套要问的是这个,
     * 不能反过来读 bottomTint 的结果(那已经是深是浅由当前配色决定的,问了等于白问)。
     *
     * @return 取不出来(没壁纸 / 解不开)时退回 true,和原来"没有壁纸就当深色"的默认一致
     */
    public boolean isDark(String id) {
        int avg = sampleBottomColor(id);
        if (avg == 0) return true;
        return androidx.core.graphics.ColorUtils.calculateLuminance(avg) < 0.5;
    }

    /** {@link #bottomTint} 和 {@link #isDark} 共用的采样:壁纸底部 1/6 的平均色,0 表示取不出来。 */
    private int sampleBottomColor(String id) {
        Bitmap b = load(id, FRAME_W, FRAME_H);
        if (b == null) return 0;

        int w = b.getWidth(), h = b.getHeight();
        if (w <= 0 || h <= 0) return 0;
        int from = Math.max(0, h - Math.max(1, h / 6));   // 底部 1/6

        // 步长采样,不逐像素:480×45 也有两万多个点,而我们只要一个平均值
        long r = 0, g = 0, bl = 0;
        int n = 0;
        for (int y = from; y < h; y += 2) {
            for (int x = 0; x < w; x += 4) {
                int c = b.getPixel(x, y);
                r += (c >> 16) & 0xFF;
                g += (c >> 8) & 0xFF;
                bl += c & 0xFF;
                n++;
            }
        }
        if (n == 0) return 0;
        return android.graphics.Color.rgb((int) (r / n), (int) (g / n), (int) (bl / n));
    }

    /**
     * 这个视频对车机来说是不是过量。
     *
     * 官方素材由 tools/wallpaper-video.sh 压过,个人素材是用户随手拷进来的 ——
     * 4K / 60fps 的片子扔进来,解码器扛不了几秒就报错冻住(真实现象见那个脚本的注释)。
     * app 里没有转码能力,能做的是**先说清楚**,别让用户以为是桌面坏了。
     *
     * 判据和脚本里的 check 保持一致:像素多过屏幕、帧率高过 30、码率高过 5Mbps。
     */
    public boolean isOversized(String id) {
        String name = nameOf(id);
        if (!isVideo(name)) return false;
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        AssetFileDescriptor fd = null;
        try {
            File dir = dirFor(sourceOf(id));
            if (dir != null) {
                File f = new File(dir, name);
                if (!f.isFile()) return false;
                mmr.setDataSource(f.getAbsolutePath());
            } else {
                fd = openAsset(name);
                if (fd == null) return false;
                mmr.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
            }
            int w = intMeta(mmr, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            int h = intMeta(mmr, MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            int bitrate = intMeta(mmr, MediaMetadataRetriever.METADATA_KEY_BITRATE);
            return (long) w * h > 1920L * 1080L || bitrate > 5_000_000;
        } catch (Exception e) {
            return false;
        } finally {
            try { mmr.release(); } catch (Exception ignored) { }
            if (fd != null) {
                try { fd.close(); } catch (IOException ignored) { }
            }
        }
    }

    private static int intMeta(MediaMetadataRetriever mmr, int key) {
        try {
            String v = mmr.extractMetadata(key);
            return v == null ? 0 : Integer.parseInt(v);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 最大的、仍能盖满目标尺寸的 2 的幂。盖满而不是塞进去 —— centerCrop 要的是不留边。 */
    private static int sampleFor(int w, int h, int reqW, int reqH) {
        if (w <= 0 || h <= 0) return 1;
        int sample = 1;
        while (w / (sample * 2) >= reqW && h / (sample * 2) >= reqH) sample *= 2;
        return sample;
    }
}
