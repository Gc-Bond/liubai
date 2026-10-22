package com.liubai.blank.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 在线壁纸库:橱窗目录、封面、正片。
 *
 * 素材是 Dynamic Wallpaper(macOS 应用)公开 CDN 上的视频,车机直连下载,没有任何中转服务器。
 *
 * <h3>目录:直接用源站的清单</h3>
 * 清单就挂在同一个 CDN 上({@code WallpaperEngine/wallpaperenginelist<N>.cxy}),是
 * RNCryptor v3 加密的 JSON 数组(口令写死在那个应用里)。客户端自己下、自己解,解出来缓存在本地,
 * 一周刷一次(见 {@link #refresh})。{@code <N>} 随应用版本往上涨、旧号会被删,所以记住上次能用的号、
 * 每次刷新往后探(见 {@link #downloadList})。
 *
 * <h3>只收车机放得了的</h3>
 * 清单里没有分辨率/体积,只有一个 {@code is_four_k} 标志。它不全准,但够用:用 2110 条实测核过,
 * 标 0 的 96% 是 ≤1080p、标 1 的只有 6% 是 —— 所以只收标 0 的。漏网的 4K 在下载前读一次
 * 元数据挡住(见 {@link #playable}),挡下的记下来,下次橱窗里就不再摆。
 *
 * 超时给死、失败不抛给 UI、桌面铺壁纸那条路上绝不联网(那条只读本地,见
 * {@link WallpaperStore#onlineDownloaded()})。
 *
 * <h3>源站在 Cloudflare 后面</h3>
 * 不带浏览器 UA 直接 403(Python/ffmpeg 的默认 UA 实测都被拦),所以每个请求都带上,见 {@link #openConn}。
 *
 * <h3>封面</h3>
 * 源站没有封面图,只有 {@code 00_preview/} 下 720x480 的预览小视频(约 1MB)。封面从它抽一帧
 * (见 {@link #fetchCover}):分辨率低,不会碰上 4K 实时抽帧撕裂/真车绿条纹那个坑。
 *
 * <h3>落地的文件名</h3>
 * 正片存成 {@code {key}.mp4}、封面存成 {@code {key}.mp4.cover.jpg} —— 后者正是
 * {@link WallpaperStore#COVER_SUFFIX} 的约定,于是"有封面就不抽帧"那条自动生效,
 * 在线壁纸的缩略图一律走图片解码,不占视频解码器。
 */
public final class OnlineWallpapers {

    private static final String TAG = "Blank/Online";

    private static final String CDN = "https://cdn.dynamicwallpaperengine.com/";
    /** 清单文件名前缀和起始版本号(25.5 版应用里是 247)。 */
    private static final String LIST_PREFIX = "WallpaperEngine/wallpaperenginelist";
    private static final int LIST_VER_MIN = 247;
    /** 往后最多探几个版本号:一周一刷,中间应用发不了这么多版。 */
    private static final int LIST_VER_PROBE = 10;
    /** 清单的解密口令,写死在源应用里。 */
    private static final String LIST_PASSWORD = "wen53656418";
    /** 解出来的清单缓存(原样的 JSON 数组)和它的刷新周期。 */
    private static final String LIST_CACHE = "catalog.json";
    private static final long LIST_TTL_MS = 7L * 24 * 3600 * 1000;
    /** 源站在 Cloudflare 后面,不带浏览器 UA 直接 403。 */
    private static final String UA = "Mozilla/5.0";
    /** 车机(骁龙 690)放得了的上限。 */
    private static final int MAX_W = 1920, MAX_H = 1080;
    /** 封面尺寸。橱窗一格才 600px 宽;正片 16:9。 */
    private static final int COVER_W = 960, COVER_H = 540;
    /** 从预览第几微秒抽帧:首帧常是黑场。 */
    private static final long COVER_AT_US = 1_000_000L;

    private static final String PREFS = "online_wallpapers";
    private static final String KEY_LIST_VER = "list_ver";
    /** 下载前查出来放不了的(漏网的 4K),橱窗里不再摆。 */
    private static final String KEY_UNPLAYABLE = "unplayable";

    private static final int CONNECT_TIMEOUT = 10000;
    /**
     * 读超时。**给得比常识大**:限速 250KB/s 下最大那张要两分多钟,而这个超时算的是
     * "两次读之间"的间隔,不是总时长,所以 30 秒足够宽松又不至于卡死。
     */
    private static final int READ_TIMEOUT = 30000;

    /**
     * **用户点的下载**走这条。单线程是有意的:3Mbps 的出口,并发下载只会互相抢,
     * 每一张都变慢、总时间还是那么多,不如一张张来、先点的先下完。
     */
    private static final ExecutorService IO = single("blank-online");

    /**
     * 刷清单和预取封面走**另一条**线程。
     *
     * 和上面分开是必须的:一屏封面要抽一会儿,挤在同一条线程上的话,用户这时候点下载
     * 就得排在所有封面后面 —— 界面上看着就是"点了没反应"。用户点的那一下永远不该等后台预取。
     */
    private static final ExecutorService PREFETCH = single("blank-online-pre");

    private static ExecutorService single(String name) {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
    }

    /** 橱窗里的一项(清单视角)。已下载与否由 {@link #isDownloaded} 现查本地。 */
    public static final class Remote {
        public final String key, name, kind, url, previewUrl;

        Remote(JSONObject o) {
            String file = o.optString("video_name");
            String path = o.optString("video_path_hangzhou");
            key = keyOf(file);
            name = nameOf(o);
            kind = "video";
            url = CDN + path + file;
            previewUrl = CDN + path + "00_preview/" + file;
        }

        /** 落到本地的文件名。和 WallpaperStore 的来源前缀无关 —— 那是 id,这是文件名。 */
        public String fileName() {
            return key + (".image".equals(kind) ? ".jpg" : ".mp4");
        }

        public String coverName() {
            return fileName() + WallpaperStore.COVER_SUFFIX;
        }

        /** 存进 prefs / 面板选中态用的 id。 */
        public String id() {
            return WallpaperStore.Source.ONLINE.prefix + fileName();
        }
    }

    public interface Progress {
        /** total 为 0 表示源站没给长度。回调在 IO 线程上,要动 UI 自己 post 回去。 */
        void onProgress(long done, long total);
    }

    private final Context app;
    private final WallpaperStore store;

    public OnlineWallpapers(Context ctx) {
        this.app = ctx.getApplicationContext();
        this.store = new WallpaperStore(ctx);
    }

    /** 用户点的下载。 */
    public static ExecutorService io() { return IO; }

    /** 后台刷清单 / 预取封面。别把用户的操作排在它后面。 */
    public static ExecutorService prefetch() { return PREFETCH; }

    // ---- 橱窗 ----

    /** 稳定标识 = 文件名去扩展名。按 {@code key + ".mp4"} 落盘,所以不能带扩展名。 */
    private static String keyOf(String videoName) {
        int dot = videoName.lastIndexOf('.');
        String stem = dot > 0 ? videoName.substring(0, dot) : videoName;
        StringBuilder sb = new StringBuilder();
        for (char c : stem.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-') sb.append(c);
        }
        return sb.length() > 0 ? sb.toString() : "wp";
    }

    /** 展示名:清单没有标题字段,取中文标签第一段(如「博丽灵梦」)。 */
    private static String nameOf(JSONObject o) {
        String tag = o.optString("china_tag").split(",")[0].trim();
        return tag.isEmpty() ? "壁纸" : tag;
    }

    /** 只读本地缓存的清单,不联网。没缓存过返回空表。面板打开时先用它铺。 */
    public List<Remote> cached() {
        File f = new File(store.onlineDir(), LIST_CACHE);
        if (!f.isFile()) return new ArrayList<>();
        try {
            return parse(readAll(new java.io.FileInputStream(f)));
        } catch (Exception e) {
            Log.w(TAG, "本地清单缓存解不开", e);
            return new ArrayList<>();
        }
    }

    /**
     * 缓存过期(或没有)就从源站下一份新清单。**阻塞,在 {@link #prefetch()} 上调。**
     *
     * @return 下到了新的就返回新橱窗;缓存还新鲜、或者没下成返回 null(调用方接着用 {@link #cached})
     */
    public List<Remote> refresh() {
        File f = new File(store.onlineDir(), LIST_CACHE);
        if (f.isFile() && System.currentTimeMillis() - f.lastModified() < LIST_TTL_MS) return null;
        try {
            String json = downloadList();
            List<Remote> out = parse(json);
            try (FileOutputStream fo = new FileOutputStream(f)) {
                fo.write(json.getBytes(StandardCharsets.UTF_8));
            }
            Log.i(TAG, "清单刷新,橱窗 " + out.size() + " 张");
            return out;
        } catch (Exception e) {
            Log.w(TAG, "刷清单失败,接着用缓存: " + e);
            return null;
        }
    }

    /** 清单 → 橱窗:去掉源应用自己下架的、标了 4K 的、下载前查出放不了的。 */
    private List<Remote> parse(String json) throws Exception {
        Set<String> bad = prefs().getStringSet(KEY_UNPLAYABLE, Collections.emptySet());
        List<Remote> out = new ArrayList<>();
        JSONArray arr = new JSONArray(json);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null || o.optInt("is_hidden") == 1 || o.optInt("is_four_k") == 1) continue;
            Remote r = new Remote(o);
            if (!bad.contains(r.key)) out.add(r);
        }
        return out;
    }

    /**
     * 下清单并解密。版本号从上次能用的那个起,往后探到最新的一个(HEAD,一次几百字节);
     * 记下的号要是已经被删了,也是往后探,探不到就算失败。
     */
    private String downloadList() throws Exception {
        int ver = Math.max(LIST_VER_MIN, prefs().getInt(KEY_LIST_VER, LIST_VER_MIN));
        boolean found = exists(listUrl(ver));
        for (int v = ver + 1; v <= ver + LIST_VER_PROBE; v++) {
            if (exists(listUrl(v))) { ver = v; found = true; }
            else if (found) break;              // 已经有能用的了,下一个号不在就是到头了
        }
        if (!found) throw new IOException("清单 " + ver + "~" + (ver + LIST_VER_PROBE) + " 都不在");
        byte[] blob = getBytes(listUrl(ver));
        prefs().edit().putInt(KEY_LIST_VER, ver).apply();
        return new String(decrypt(blob, LIST_PASSWORD), StandardCharsets.UTF_8);
    }

    private static String listUrl(int ver) {
        return CDN + LIST_PREFIX + ver + ".cxy";
    }

    /**
     * RNCryptor v3(口令模式)解密。格式:
     * {@code 03 01 | 加密盐 8 | HMAC 盐 8 | IV 16 | 密文(AES-256-CBC, PKCS7) | HMAC-SHA256 32},
     * 两把钥匙都是 PBKDF2-HMAC-SHA1(口令, 盐, 10000 次, 32 字节),HMAC 覆盖除最后 32 字节外的全部。
     */
    static byte[] decrypt(byte[] d, String password) throws Exception {
        if (d.length < 66 || d[0] != 3 || d[1] != 1) throw new IOException("不是 RNCryptor v3 口令格式");
        byte[] encSalt = Arrays.copyOfRange(d, 2, 10);
        byte[] hmacSalt = Arrays.copyOfRange(d, 10, 18);
        byte[] iv = Arrays.copyOfRange(d, 18, 34);
        int macAt = d.length - 32;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(pbkdf2(password, hmacSalt), "HmacSHA256"));
        mac.update(d, 0, macAt);
        if (!MessageDigest.isEqual(mac.doFinal(), Arrays.copyOfRange(d, macAt, d.length))) {
            throw new IOException("清单 HMAC 对不上(口令变了或文件坏了)");
        }
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(pbkdf2(password, encSalt), "AES"),
                new IvParameterSpec(iv));
        return c.doFinal(d, 34, macAt - 34);
    }

    private static byte[] pbkdf2(String password, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 10000, 256);
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).getEncoded();
    }

    private SharedPreferences prefs() {
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---- 下载 ----

    public boolean isDownloaded(Remote r) {
        return new File(store.onlineDir(), r.fileName()).isFile();
    }

    public boolean hasCover(Remote r) {
        return new File(store.onlineDir(), r.coverName()).isFile();
    }

    /**
     * 从预览视频抽一帧当封面。**阻塞,在 {@link #prefetch()} 上调。**
     *
     * 预览是 720x480,但正片是 16:9 —— 像素不是方的(SAR 非 1:1),直接存会被压扁。
     * 所以不认预览自己的宽高,一律缩成 {@link #COVER_W}x{@link #COVER_H}。
     * MediaMetadataRetriever 读远端只按需拉数据,一张预览约 1MB 封顶。
     */
    public boolean fetchCover(Remote r) {
        if (hasCover(r)) return true;
        File dst = new File(store.onlineDir(), r.coverName());
        File part = new File(store.onlineDir(), r.coverName() + ".part");
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(r.previewUrl, Collections.singletonMap("User-Agent", UA));
            Bitmap frame = mmr.getFrameAtTime(COVER_AT_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) return false;
            Bitmap scaled = Bitmap.createScaledBitmap(frame, COVER_W, COVER_H, true);
            if (scaled != frame) frame.recycle();
            try (FileOutputStream out = new FileOutputStream(part)) {
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, out);
            } finally {
                scaled.recycle();
            }
            return part.renameTo(dst);
        } catch (Exception e) {
            Log.w(TAG, "抽封面失败 " + r.key + ": " + e);
            return false;
        } finally {
            part.delete();
            try { mmr.release(); } catch (Exception ignored) { }
        }
    }

    /**
     * 车机放不放得了这张:读一次正片的元数据(只拉文件头),超过 {@link #MAX_W}x{@link #MAX_H}
     * 就记进黑名单、返回 false,橱窗下次不再摆它。**阻塞,在 {@link #io()} 上调。**
     * 读不出来(网断了)不算放不了 —— 交给下载自己失败,别冤枉了好素材。
     */
    public boolean playable(Remote r) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(r.url, Collections.singletonMap("User-Agent", UA));
            int w = parseInt(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int h = parseInt(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            if (Math.max(w, h) <= MAX_W && Math.min(w, h) <= MAX_H) return true;
            Log.i(TAG, r.key + " 是 " + w + "x" + h + ",车机放不了");
            Set<String> bad = new HashSet<>(prefs().getStringSet(KEY_UNPLAYABLE, Collections.emptySet()));
            bad.add(r.key);
            prefs().edit().putStringSet(KEY_UNPLAYABLE, bad).apply();
            return false;
        } catch (Exception e) {
            Log.w(TAG, "读不出规格 " + r.key + ": " + e);
            return true;
        } finally {
            try { mmr.release(); } catch (Exception ignored) { }
        }
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }

    /**
     * 下正片。**阻塞,在 {@link #io()} 上调。**
     *
     * 先写 {@code .part},下完核对长度(源站给的 Content-Length)才 rename ——
     * 不能把半个文件留在那儿当壁纸、铺上去黑屏。
     *
     * @param cancel 用户中途取消的开关,可为 null。**每读一段查一次**(64KB,限速下约 0.25 秒),
     *               置位后删掉 {@code .part} 直接返回 false —— 调用方自己按这个开关区分
     *               "取消"和"失败",取消不该弹失败提示。注意最长要等一次 read 返回
     *               ({@link #READ_TIMEOUT}),网络卡死时不是立刻让出串行队列的。
     */
    public boolean download(Remote r, Progress p, AtomicBoolean cancel) {
        if (isDownloaded(r)) return true;
        File dst = new File(store.onlineDir(), r.fileName());
        File part = new File(store.onlineDir(), r.fileName() + ".part");

        try {
            fetchTo(r.url, part, p, cancel);
            part.renameTo(dst);
            Log.i(TAG, "下好了 " + r.key + " " + dst.length() + " 字节");
            return true;
        } catch (Cancelled e) {
            // 必须排在 Exception 前面(它也是 IOException)。取消不算失败
            part.delete();
            Log.i(TAG, "取消下载 " + r.key);
            return false;
        } catch (Exception e) {
            Log.w(TAG, "下正片失败 " + r.key + ": " + e);
            part.delete();
            return false;
        }
    }

    /** 删掉已下载的正片(封面留着,橱窗还要显示)。 */
    public boolean remove(Remote r) {
        return new File(store.onlineDir(), r.fileName()).delete();
    }

    /**
     * 删掉下载来的正片,**当前正用着的那一张除外**。
     *
     * 不做自动淘汰、只在设置页给一个手动入口:车机存储不紧张,而自动删掉用户正用的壁纸
     * 比占几百 MB 糟糕得多。**封面留着**(总共才 1.6MB),橱窗还要靠它出图。
     *
     * @return 腾出来的字节数
     */
    public long clearDownloaded() {
        String keep = "";
        String cur = store.current();
        if (cur.startsWith(WallpaperStore.Source.ONLINE.prefix)) {
            keep = cur.substring(WallpaperStore.Source.ONLINE.prefix.length());
        }
        long freed = 0;
        File[] fs = store.onlineDir().listFiles();
        if (fs != null) {
            for (File f : fs) {
                String n = f.getName();
                if (!f.isFile() || n.endsWith(WallpaperStore.COVER_SUFFIX) || n.equals(LIST_CACHE)) continue;
                if (n.equals(keep)) continue;
                long len = f.length();
                if (f.delete()) freed += len;
            }
        }
        Log.i(TAG, "清理在线壁纸,腾出 " + freed + " 字节" + (keep.isEmpty() ? "" : ",保留在用的 " + keep));
        return freed;
    }

    /** 已下载正片占了多少字节(不含封面和清单)。设置页显示用。 */
    public long downloadedBytes() {
        long sum = 0;
        File[] fs = store.onlineDir().listFiles();
        if (fs != null) {
            for (File f : fs) {
                if (f.isFile() && !f.getName().endsWith(WallpaperStore.COVER_SUFFIX)
                        && !f.getName().equals(LIST_CACHE)) {
                    sum += f.length();
                }
            }
        }
        return sum;
    }

    // ---- HTTP ----

    /** 用户中途取消。同样单独一个类型 —— 它要走的收尾和"下失败了"完全不同。 */
    private static final class Cancelled extends IOException {
        Cancelled() { super("用户取消"); }
    }

    private HttpURLConnection openConn(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT);
        c.setReadTimeout(READ_TIMEOUT);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept-Encoding", "identity");   // 别压缩,视频压了也白压
        c.setInstanceFollowRedirects(true);
        return c;
    }

    private boolean exists(String url) throws IOException {
        HttpURLConnection c = openConn(url);
        try {
            c.setRequestMethod("HEAD");
            return c.getResponseCode() == 200;
        } finally {
            c.disconnect();
        }
    }

    private byte[] getBytes(String url) throws IOException {
        HttpURLConnection c = openConn(url);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                return bo.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    /** 下到文件。每读一段查一次取消。 */
    private void fetchTo(String url, File dst, Progress p, AtomicBoolean cancel) throws IOException {
        HttpURLConnection c = openConn(url);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            long total = c.getContentLength();
            long done = 0;
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    // 查在写之前:取消之后一个字节都不再落盘,.part 交给 download 删
                    if (cancel != null && cancel.get()) throw new Cancelled();
                    out.write(buf, 0, n);
                    done += n;
                    if (p != null) p.onProgress(done, total);
                }
            }
            if (total > 0 && done != total) throw new IOException("长度对不上 " + done + "/" + total);
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream i = in) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = i.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        }
    }
}
