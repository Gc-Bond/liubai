package com.liubai.blank.game;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 模拟器核心的装配器:哪些核心要装、从哪儿铺出来。
 *
 * <h3>核心打进 apk(2026-09-06 改)</h3>
 * 7 个核心的 .so.zip 全放进 {@code assets/cores/}(共 <b>27 MB</b>,zip 已压不再二压)。
 * 曾经是"点谁下谁"从国外 buildbot 拉 —— 街机 FBNeo 那包 16 MB,车机连国外慢到离谱,
 * 于是改成打进包、本地铺。启动时 {@link #seedFromAssets} 把它们解到内部目录,
 * 之后点游戏零网络、秒开。
 *
 * <h3>从哪铺</h3>
 * {@code assets/cores/{key}_libretro_android.so.zip},zip 里就是单个
 * {@code *_libretro_android.so},解出改名 lib*.so 即可 dlopen。
 * buildbot 那条网络下载留作兜底(assets 缺/坏时,见 {@link #download}),正常走不到。
 *
 * <h3>只能落在内部目录</h3>
 * 核心必须写进 {@link Context#getFilesDir()}。<b>不能放 /sdcard</b>:
 * 外部存储是 {@code noexec} 挂的(mount 输出实测),dlopen 一定失败。
 * ROM 反过来 —— 它只是数据,放外面方便用户自己拷。
 */
public final class CoreStore {

    private static final String TAG = "Blank/Core";

    /** libretro 官方 nightly 的 Android arm64 核心包。zip 内文件名是 {key}_libretro_android.so。 */
    private static final String CORE_URL =
            "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/%s_libretro_android.so.zip";

    private static final int CONNECT_TIMEOUT = 10000;
    /** 读超时算的是"两次读之间"的间隔,不是总时长 —— 68 MB 在 3Mbps 下要三分钟,但每一块都不慢。 */
    private static final int READ_TIMEOUT = 30000;

    /** 下载单线程:出口就 3Mbps,并发只会互相抢,先点的先下完反而快。 */
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "blank-core");
        t.setDaemon(true);
        return t;
    });

    public static ExecutorService io() { return IO; }

    public interface Progress { void onProgress(long done, long total); }

    /** 一个可下载的核心。 */
    public static final class Remote {
        /** buildbot 包名前缀,如 "fbneo"。 */
        public final String key;
        /** 落地文件名,必须和 {@link GameActivity} 里那张后缀表对得上,如 "libfbneo_libretro.so"。 */
        public final String file;
        /** 给人看的名字,如 "街机 · FinalBurn Neo"。 */
        public final String name;
        /** 下载地址(buildbot 的 .so.zip)。 */
        public final String url;

        Remote(String key, String file, String name) {
            this.key = key;
            this.file = file;
            this.name = name;
            this.url = String.format(Locale.US, CORE_URL, key);
        }
    }

    /** 核心文件名(libxxx_libretro.so) → buildbot 包名(xxx),如 "libfbneo_libretro.so" → "fbneo"。 */
    private static String keyOf(String fileName) {
        String k = fileName;
        if (k.startsWith("lib")) k = k.substring(3);
        if (k.endsWith("_libretro.so")) k = k.substring(0, k.length() - "_libretro.so".length());
        return k;
    }

    /** 给人看的核心名:buildbot 包名 → 中文。认不出的原样显示。 */
    private static String displayName(String key) {
        switch (key) {
            case "fbneo": return "街机 · FinalBurn Neo";
            case "mame2003_plus": return "街机 · MAME 2003-Plus";
            case "fbalpha2012_neogeo": return "街机 · FB Alpha 2012 NeoGeo";
            case "fbalpha2012_cps1": return "街机 · FB Alpha 2012 CPS1";
            case "fbalpha2012_cps2": return "街机 · FB Alpha 2012 CPS2";
            case "fceumm": return "FC/NES · FCEUmm";
            case "mgba": return "GB/GBA · mGBA";
            default: return key;
        }
    }

    /**
     * 按落地文件名找可下载条目。**不联网**,URL 是固定的官方源,
     * 下不下的来由 {@link #download} 的 HTTP 结果说话。
     */
    public Remote resolve(String fileName) {
        return new Remote(keyOf(fileName), fileName, displayName(keyOf(fileName)));
    }

    private final Context ctx;

    public CoreStore(Context c) { this.ctx = c.getApplicationContext(); }

    /** 下载来的核心放这儿。只能是内部目录,理由见类注释。 */
    public File dir() {
        File d = new File(ctx.getFilesDir(), "cores");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public boolean installed(String fileName) {
        File f = new File(dir(), fileName);
        return f.isFile() && f.length() > 0;
    }

    /** 已经装下来的核心文件名。 */
    public List<String> installed() {
        List<String> out = new ArrayList<>();
        File[] fs = dir().listFiles();
        if (fs == null) return out;
        for (File f : fs) if (f.isFile() && f.getName().endsWith(".so")) out.add(f.getName());
        return out;
    }

    /** 删一个核心,腾地方。 */
    public boolean remove(String fileName) {
        return new File(dir(), fileName).delete();
    }

    /** 用户自己放核心的地方,跟 ROM 挨着 —— 外部存储,adb push 和文件管理器都进得来。 */
    public File dropDir() {
        File d = ctx.getExternalFilesDir("cores");
        if (d != null && !d.exists()) d.mkdirs();
        return d;
    }

    /**
     * 把用户丢在外部 {@link #dropDir()} 里的核心搬进内部目录。**阻塞,别在主线程调。**
     *
     * 为什么要搬而不是直接用:外部存储是 {@code noexec} 挂的,在那儿 dlopen 一定失败。
     * 为什么不让用户直接放内部目录:那是 app 私有目录,release 包连 {@code run-as} 都用不了,
     * 真车上根本放不进去。
     *
     * 所以「本地装核心」这条路是必须的 —— 车里没网的时候,这是唯一能装上的办法。
     * 搬完**保留原文件**,免得用户以为丢了。
     *
     * @return 这次真正搬进来的文件名
     */
    public List<String> importDropped() {
        List<String> done = new ArrayList<>();
        File src = dropDir();
        File[] fs = src == null ? null : src.listFiles();
        if (fs == null) return done;
        for (File f : fs) {
            String n = f.getName();
            if (!f.isFile() || !n.startsWith("lib") || !n.endsWith(".so")) continue;
            File dst = new File(dir(), n);
            // 同名同大小就当已经搬过了。不校 sha256:这是用户自己放的文件,
            // 没有权威摘要可比,而且 68 MB 每次启动都算一遍纯属浪费
            if (dst.isFile() && dst.length() == f.length()) continue;
            File part = new File(dir(), n + ".part");
            try (InputStream in = new FileInputStream(f);
                 FileOutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[256 * 1024];
                int k;
                while ((k = in.read(buf)) > 0) out.write(buf, 0, k);
            } catch (Exception e) {
                Log.w(TAG, "搬核心失败 " + n + ": " + e);
                part.delete();
                continue;
            }
            if (part.renameTo(dst)) {
                dst.setReadable(true, true);
                done.add(n);
                Log.i(TAG, "装上本地核心 " + n + " " + dst.length() + " 字节");
            } else {
                part.delete();
            }
        }
        return done;
    }

    /**
     * 装一个核心到内部目录(dlopen 只能读内部)。**阻塞,在 {@link #io()} 上调。**
     *
     * **先用打进 apk 的那份**(assets/cores,见 {@link #seedFromAssets}),没有才退回从
     * buildbot 下。buildbot 发的是 .so.zip,解出里头唯一的 .so、改名成 lib*.so 再落盘。
     * 7 个核心都打进包了,正常走不到网络分支——留着只当兜底(assets 缺/坏时)。
     */
    public boolean download(Remote r, Progress p) {
        if (installed(r.file)) return true;
        File dst = new File(dir(), r.file);
        // 优先打进包的那份:启动铺核心还没跑完时点了游戏,也能就地解出,零网络
        try (InputStream in = ctx.getAssets().open(assetOf(r.key))) {
            if (extractSoZip(in, dst, r.key)) return true;
        } catch (IOException ignored) { /* assets 里没有这个核心,退回网络 */ }
        // 兜底:从官方 buildbot 下。先写 .part,断在半路不会留半个核心冒充装好了
        File zipPart = new File(dir(), r.key + "_libretro_android.so.zip.part");
        try {
            if (!fetchTo(r.url, zipPart, p)) { zipPart.delete(); return false; }
            boolean ok;
            try (InputStream in = new FileInputStream(zipPart)) {
                ok = extractSoZip(in, dst, r.key);
            }
            zipPart.delete();
            if (ok) Log.i(TAG, "核心下好了 " + r.key + " " + dst.length() + " 字节");
            return ok;
        } catch (Exception e) {
            Log.w(TAG, "下核心失败 " + r.key + ": " + e);
            zipPart.delete();
            return false;
        }
    }

    /** 把 .so.zip 流里唯一的那个 .so 条目解到 {@code dst}:先写 .tmp 再改名,dlopen 要可读。 */
    private boolean extractSoZip(InputStream zipStream, File dst, String key) {
        File tmp = new File(dir(), dst.getName() + ".tmp");
        try (ZipInputStream zis = new ZipInputStream(zipStream)) {
            ZipEntry e;
            boolean wrote = false;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[256 * 1024];
                    int n;
                    while ((n = zis.read(buf)) > 0) out.write(buf, 0, n);
                }
                wrote = true;
                break;
            }
            if (!wrote) { Log.w(TAG, "核心包里没有 .so " + key); tmp.delete(); return false; }
        } catch (Exception e) {
            Log.w(TAG, "解核心失败 " + key + ": " + e);
            tmp.delete();
            return false;
        }
        if (!tmp.renameTo(dst)) { Log.w(TAG, "改名失败 " + key); tmp.delete(); return false; }
        // dlopen 要能读到。Java 写出来的文件默认 rw-------,给自己留读权限就够
        dst.setReadable(true, true);
        return true;
    }

    /** 打进 apk 的核心 —— assets/cores/&lt;key&gt;_libretro_android.so.zip。 */
    public static final String[] BUNDLED = {
            "fceumm", "mgba", "fbneo",
            "fbalpha2012_neogeo", "fbalpha2012_cps1", "fbalpha2012_cps2", "mame2003_plus",
    };

    private static String assetOf(String key) { return "cores/" + key + "_libretro_android.so.zip"; }
    private static String fileOf(String key) { return "lib" + key + "_libretro.so"; }

    /**
     * 把打进 apk 的核心铺到内部目录(dlopen 只能读内部,外部 noexec)。**阻塞,别在主线程调。**
     * 已铺过的按 {@link #installed} 跳过——只有全新安装的头一回真解压。
     */
    public void seedFromAssets() {
        for (String key : BUNDLED) {
            if (installed(fileOf(key))) continue;
            try (InputStream in = ctx.getAssets().open(assetOf(key))) {
                if (extractSoZip(in, new File(dir(), fileOf(key)), key)) Log.i(TAG, "核心铺好 " + key);
            } catch (IOException e) {
                Log.w(TAG, "铺核心失败(assets 缺?) " + key + ": " + e);
            }
        }
    }

    // ---- HTTP ----

    private HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT);
        c.setReadTimeout(READ_TIMEOUT);
        c.setRequestProperty("Accept-Encoding", "identity");   // .so 已经压过,再压白费
        c.setInstanceFollowRedirects(true);
        return c;
    }

    /** 下载到文件。返回是否拿到 200 的完整文件。 */
    private boolean fetchTo(String url, File dst, Progress p) throws IOException {
        HttpURLConnection c = open(url);
        try {
            int code = c.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "核心下载 HTTP " + code + ": " + url);
                return false;
            }
            long total = c.getContentLength();
            long done = 0;
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
}
