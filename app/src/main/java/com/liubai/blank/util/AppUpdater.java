package com.liubai.blank.util;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.liubai.blank.BrowserActivity;
import com.liubai.blank.BuildConfig;
import com.liubai.blank.ui.ConfirmPopup;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 在线更新:查 GitHub Releases 上有没有新版,有就**用留白自带的浏览器**把 apk 下下来,装由用户自己点。
 *
 * <h3>为什么不自己下、不自己装</h3>
 * 浏览器那边早就有一整条下载通路了({@code BrowserActivity.startDownload}:DownloadListener
 * → DownloadManager → 落到公共 Downloads 目录、通知栏报完成),再写一遍只是把同样的事
 * 做第二份。装也交给系统安装器,由用户在文件里点 —— 静默安装要提权、要处理"装的是自己、
 * 命令发出去进程就没了",换来的只是省用户一次点击,不值。
 *
 * <h3>接口</h3>
 * {@code GET https://api.github.com/repos/<UPDATE_REPO>/releases/latest},取最新一个正式 Release。
 * <b>发版约定</b>:tag 末尾那段数字就是 versionCode(如 {@code v1.0.12}),附件里放一个 {@code .apk}。
 * 两样缺一样就当没有新版 —— 拿不准新旧的包,宁可不提示。
 *
 * <p>所有网络动作在后台线程,回调回主线程。
 */
public final class AppUpdater {

    private static final String TAG = "Blank/Update";

    private static final String LATEST_URL =
            "https://api.github.com/repos/" + BuildConfig.UPDATE_REPO + "/releases/latest";

    private static final int CONNECT_TIMEOUT = 8000;
    private static final int READ_TIMEOUT = 8000;

    private static final String PREFS = "blank_update";
    /** 已经主动弹过窗的那个版本号。见 {@link #autoCheck}。 */
    private static final String KEY_PROMPTED = "prompted_vc";

    /** 一个进程只自动查一次:开桌面不是"检查更新"的时机,只是顺手看一眼。 */
    private static boolean autoChecked;

    private AppUpdater() {}

    /** GitHub 上最新那一版。 */
    public static final class Info {
        public final int versionCode;
        public final String versionName;
        public final String changelog;
        public final long size;
        /** apk 附件的下载地址。 */
        public final String url;

        private Info(int versionCode, String versionName, String changelog, long size, String url) {
            this.versionCode = versionCode; this.versionName = versionName;
            this.changelog = changelog; this.size = size; this.url = url;
        }

        /** 按发版约定解析一个 Release;tag 里没有版本号、或没有 apk 附件,返回 null。 */
        static Info parse(JSONObject o) {
            String tag = o.optString("tag_name");
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)$").matcher(tag);
            if (!m.find()) return null;
            JSONArray assets = o.optJSONArray("assets");
            if (assets == null) return null;
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.optJSONObject(i);
                if (a == null || !a.optString("name").endsWith(".apk")) continue;
                String name = tag.startsWith("v") ? tag.substring(1) : tag;
                return new Info(Integer.parseInt(m.group(1)), name, o.optString("body", ""),
                        a.optLong("size"), a.optString("browser_download_url"));
            }
            return null;
        }
    }

    public interface Callback {
        /**
         * @param newer  比本机新的那一版;已是最新(或还没发过版)给 null
         * @param netErr 是不是网络原因没查成 —— 「没查成」和「已经最新」得分开说,
         *               手动点检查的人要看到的是"网络不通",不是"已是最新"
         */
        void onResult(Info newer, boolean netErr);
    }

    /** 查一次。后台线程发请求,回调回主线程。 */
    public static void check(Context ctx, final Callback cb) {
        final Handler main = new Handler(Looper.getMainLooper());
        Thread t = new Thread(() -> {
            Info info = null;
            boolean netErr = false;
            try {
                Info r = Info.parse(new JSONObject(get(LATEST_URL)));
                if (r != null && r.versionCode > BuildConfig.VERSION_CODE) info = r;
            } catch (Exception e) {
                Log.w(TAG, "查版本失败: " + e);
                netErr = true;
            }
            final Info result = info;
            final boolean err = netErr;
            main.post(() -> cb.onResult(result, err));
        }, "blank-update");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 开桌面时顺手查的那次。
     *
     * **同一个版本只弹一次**。查到新版就弹、每次开机都弹的话,不想装的人得关掉它一百遍;
     * 弹过一次就记下版本号,之后他想装自己去设置里点「检查更新」。再发一版(版本号
     * 变了)才会再弹一次。
     */
    public static void autoCheck(final Activity act) {
        if (autoChecked) return;
        autoChecked = true;
        check(act, (newer, netErr) -> {
            if (newer == null || act.isFinishing() || act.isDestroyed()) return;
            SharedPreferences sp = prefs(act);
            if (sp.getInt(KEY_PROMPTED, 0) >= newer.versionCode) return;
            sp.edit().putInt(KEY_PROMPTED, newer.versionCode).apply();
            prompt(act, newer);
        });
    }

    /**
     * 「有新版,下不下」那个弹层。确定 = 用自带浏览器打开 apk 地址。
     *
     * 不用系统 AlertDialog、不用 toast,两条都是留白踩过的坑(见 {@link ConfirmPopup} 类注释、
     * 以及 Android 12 起 toast 一律压在 Dock 上)。
     */
    public static void prompt(final Context ctx, final Info info) {
        StringBuilder msg = new StringBuilder();
        if (!info.changelog.trim().isEmpty()) msg.append(info.changelog.trim()).append("\n\n");
        if (info.size > 0) msg.append("安装包 ").append(sizeText(info.size)).append("，");
        msg.append("用留白自带的浏览器下载，下完在浏览器底部点「安装」。");
        ConfirmPopup.show(ctx, "发现新版本 v" + info.versionName, msg.toString(),
                "下载", () -> download(ctx, info), "稍后", null);
    }

    /** 把下载地址甩给自带浏览器,剩下的("下载中"通知、落盘)是它和 DownloadManager 的事。 */
    public static void download(Context ctx, Info info) {
        Intent i = new Intent(ctx, BrowserActivity.class);
        i.putExtra(BrowserActivity.EXTRA_URL, info.url);
        // 从设置面板点过来时 ctx 是 Activity;真要是拿到别的 context,少这个 flag 会直接崩
        if (!(ctx instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }

    /** 本机版本,给「关于」页显示用。 */
    public static String localVersion() {
        return BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")";
    }

    public static String sizeText(long bytes) {
        if (bytes >= 1024 * 1024) return String.format(java.util.Locale.CHINA, "%.1fMB", bytes / 1048576f);
        if (bytes >= 1024) return (bytes / 1024) + "KB";
        return bytes + "B";
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT);
        c.setReadTimeout(READ_TIMEOUT);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "liubai");
        try {
            int code = c.getResponseCode();
            // 404 = 仓库还没发过正式 Release,不是网络问题
            if (code == 404) return "{}";
            if (code != 200) throw new Exception("HTTP " + code);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } finally {
            c.disconnect();
        }
    }
}
