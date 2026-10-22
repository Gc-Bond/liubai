package com.liubai.blank.data;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

/**
 * 认出「这是地图 / 这是音乐」——**靠系统级声明,不猜名字**。
 *
 * `HomeActivity.slotFor` 那条老注释说「探测出来的"这看着像个地图"经常是错的」,
 * 指的是按包名/应用名猜,那确实不可靠。这里用的是两条 **app 自己写在 manifest 里
 * 向系统声明的能力**:
 *
 *   音乐 —— 声明了 `MediaBrowserService`。这是 Android 媒体框架发现播放器的标准入口,
 *           车机音乐 app 基本都有(否则上不了车机媒体中心/方控)。
 *   地图 —— 能处理 `geo:` scheme。导航类 app 不声明这个,别人就没法把坐标丢给它。
 *
 * 声明是 app 自己给的,不是我们猜的,误报率低得多;真漏了还有手动添加兜底
 * (候选列表是「手动配的 ∪ 自动识别的」,见 HomeActivity.slotCandidates)。
 *
 * targetSdk 29,不受 API 30 起的 package visibility 限制,能直接查全量,
 * 不需要 `<queries>` 或 QUERY_ALL_PACKAGES。
 */
public final class AppTypes {

    /** 车机音乐 app 的标准声明:媒体框架靠它发现播放器。 */
    private static final String MEDIA_BROWSER = "android.media.browse.MediaBrowserService";

    /**
     * 包名关键词 —— 标准声明的兜底,**在 BYD 车机上它才是主力**。
     *
     * 护卫舰07(DiLink4.0)实测:`geo:`、`CATEGORY_APP_MAPS`、`CATEGORY_APP_MUSIC`
     * 一个都查不到,`MediaBrowserService` 只有 com.android.bluetooth —— 车上真装着的
     * com.byd.automap / com.tencent.qqmusiccar / cn.kuwo.kwmusiccar 一个都认不出来。
     * 车机 app 普遍不声明这些标准入口(它们不需要被别的 app 发现)。
     *
     * 所以只能回到按包名认。这正是 `HomeActivity.slotFor` 那条老注释说"经常是错的"
     * 的做法,区别在于**这里的误判代价很小**:认错了只是选择器里多一格,用户点旁边
     * 那个就行,而不是像当年那样拿去决定"点开这个 app 该落哪儿"。真漏了还有
     * 「全部应用」那一格。
     *
     * 配合"必须有启动入口"的过滤,实测那三个干扰项(com.baidu.map.location、
     * com.example.amapservice、com.byd.musicwidget)都被挡掉了 —— 它们都没有 launcher 入口。
     */
    private static final String[] MAP_HINTS = {
            "map", "nav", "amap", "autonavi", "gaode", "didi",
    };
    private static final String[] MUSIC_HINTS = {
            "music", "audio", "kuwo", "kugou", "netease", "ximalaya", "qingting", "radio",
    };

    /**
     * 声明了 MediaBrowserService、但**不是播放器**的,一律排掉。
     *
     * 那个服务的语义是"能被媒体框架发现",语音助手(要用嘴控制播放)、蓝牙媒体代理
     * (转发手机的音频)都会注册它,可它们自己放不了歌 —— 选成音乐源只会得到一个
     * 点了没反应的卡位。模拟器的 Google 镜像上尤其明显,Google 助手直接混进了候选。
     *
     * 只拦这条标准声明的路径,不拦包名关键词那条:关键词命中的本来就是冲着"music"
     * 这类词去的,不会误伤。
     */
    private static final String[] NOT_PLAYER = {
            "quicksearchbox", "assistant", "bluetooth", "tts", "speech", "carservice",
    };

    private AppTypes() {}

    /** 装了哪些音乐类 app。顺序按系统返回,调用方自己决定怎么排。 */
    public static List<String> music(Context ctx) {
        List<String> out = new ArrayList<>();
        PackageManager pm = ctx.getPackageManager();
        try {
            for (ResolveInfo ri : pm.queryIntentServices(new Intent(MEDIA_BROWSER), 0)) {
                if (ri.serviceInfo == null) continue;
                String pkg = ri.serviceInfo.packageName;
                if (hits(pkg, NOT_PLAYER)) continue;   // 助手/蓝牙代理不是播放器
                add(ctx, out, pkg);
            }
        } catch (Exception ignored) {
            // 查询失败就返回已收集到的,识别不出来还有手动添加那条路
        }
        scanByHints(ctx, out, MUSIC_HINTS);
        return out;
    }

    /** 装了哪些地图/导航类 app。 */
    public static List<String> maps(Context ctx) {
        List<String> out = new ArrayList<>();
        PackageManager pm = ctx.getPackageManager();
        try {
            Intent geo = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0"));
            for (ResolveInfo ri : pm.queryIntentActivities(geo, 0)) {
                if (ri.activityInfo != null) add(ctx, out, ri.activityInfo.packageName);
            }
        } catch (Exception ignored) {
        }
        scanByHints(ctx, out, MAP_HINTS);
        return out;
    }

    /**
     * 扫一遍**有启动入口**的 app,包名命中关键词就算。
     *
     * 用 ACTION_MAIN + CATEGORY_LAUNCHER 查,而不是 getInstalledApplications 再逐个
     * 问入口 —— 后者要对几百个包各查一次 PackageManager,在车机上肉眼可见地卡。
     */
    private static void scanByHints(Context ctx, List<String> out, String[] hints) {
        try {
            Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            for (ResolveInfo ri : ctx.getPackageManager().queryIntentActivities(main, 0)) {
                if (ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if (hits(pkg, hints)) add(ctx, out, pkg);
            }
        } catch (Exception ignored) {
        }
    }

    /** 包名(小写)里有没有这几个词。 */
    private static boolean hits(String pkg, String[] words) {
        String lower = pkg.toLowerCase(java.util.Locale.ROOT);
        for (String w : words) {
            if (lower.contains(w)) return true;
        }
        return false;
    }

    /** app 的显示名。取不到就退回包名 —— 宁可显示个丑名字,也别显示空白。 */
    public static String label(Context ctx, String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    /**
     * 去重、排掉自己、排掉打不开的。
     *
     * 没有启动入口的照样可能声明 geo:/MediaBrowserService(纯后台的媒体服务、
     * 系统组件),识别到了也塞不进画中画 —— 那是给用户一个点了没反应的选项。
     */
    private static void add(Context ctx, List<String> out, String pkg) {
        if (pkg == null || pkg.isEmpty() || out.contains(pkg)) return;
        if (pkg.equals(ctx.getPackageName())) return;
        if (ctx.getPackageManager().getLaunchIntentForPackage(pkg) == null) return;
        out.add(pkg);
    }
}
