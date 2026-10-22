package com.liubai.blank.media;

import android.os.SystemClock;
import android.text.TextUtils;

/**
 * 当前歌词行的存放处(进程内单例)。
 *
 * 歌词来源是「魅族 Flyme 状态栏歌词」协议——国内音乐 app 的事实标准:
 * 播放器逐行用 Notification.setTicker(歌词) 推送,配合两个私有 flag
 * (FLAG_ALWAYS_SHOW_TICKER=0x1000000 / FLAG_ONLY_UPDATE_TICKER=0x2000000)。
 * 我们在 BlankNotificationListener 里读 tickerText 拿到,存进这里供卡片消费。
 *
 * 前提:用户要在音乐 app 里打开「桌面歌词 / 状态栏歌词」开关,播放器才会推。
 */
public final class LyricHub {

    /** 超过这么久没有新歌词就算过期,避免停播后残留上一句。 */
    private static final long STALE_MS = 20_000L;

    private static volatile String line;
    private static volatile String pkg;
    private static volatile long updatedAt;

    private LyricHub() {}

    public static void update(String fromPkg, String text) {
        pkg = fromPkg;
        line = text;
        updatedAt = SystemClock.elapsedRealtime();
    }

    /** 清掉指定 app 的歌词(通知被移除时调)。 */
    public static void clear(String fromPkg) {
        if (fromPkg != null && fromPkg.equals(pkg)) {
            line = null;
            pkg = null;
        }
    }

    /**
     * 取当前歌词。只有来源包名与正在播放的 app 一致才返回——
     * 否则会把 A 播放器的歌词配到 B 播放器的歌上。
     */
    public static String lineFor(String playingPkg) {
        if (TextUtils.isEmpty(line) || pkg == null) return null;
        if (playingPkg != null && !pkg.equals(playingPkg)) return null;
        if (SystemClock.elapsedRealtime() - updatedAt > STALE_MS) return null;
        return line;
    }
}
