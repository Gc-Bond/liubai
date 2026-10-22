package com.liubai.blank.media;

import android.app.Notification;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

/**
 * 通知监听。两个职责:
 *
 * 1. 存在本身 —— MediaSessionManager.getActiveSessions() 必须传一个已授权的
 *    NotificationListenerService 组件名,才能读到别家 app 的播放会话。
 *
 * 2. 抓歌词 —— 国内音乐 app 走「魅族 Flyme 状态栏歌词」事实标准:
 *    逐行用 Notification.setTicker(歌词) 推送,并打上私有 flag。
 *    歌词不在 MediaMetadata 里,只在通知的 tickerText 里。
 */
public class BlankNotificationListener extends NotificationListenerService {

    /** Flyme 私有 flag:常驻显示 ticker。带这个标志的通知基本可判定是歌词推送。 */
    private static final int FLAG_ALWAYS_SHOW_TICKER = 0x1000000;
    /** Flyme 私有 flag:只更新 ticker 文本,不动通知其他部分。 */
    private static final int FLAG_ONLY_UPDATE_TICKER = 0x2000000;

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        Notification n = sbn.getNotification();
        if (n == null || n.tickerText == null) return;

        // 只认带 Flyme 歌词 flag 的通知:普通通知也可能有 tickerText(如"正在播放 xxx"),
        // 不加这层判断会把标题当歌词显示。
        boolean isLyric = (n.flags & FLAG_ALWAYS_SHOW_TICKER) != 0
                || (n.flags & FLAG_ONLY_UPDATE_TICKER) != 0;
        if (!isLyric) return;

        String text = n.tickerText.toString().trim();
        if (TextUtils.isEmpty(text)) return;

        LyricHub.update(sbn.getPackageName(), text);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn != null) LyricHub.clear(sbn.getPackageName());
    }
}
