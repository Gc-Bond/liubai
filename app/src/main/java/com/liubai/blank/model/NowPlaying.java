package com.liubai.blank.model;

import android.graphics.Bitmap;

/**
 * 当前播放信息快照(来自 MediaSession)。
 * 只读值对象:视图拿到就画,不关心来源。
 */
public final class NowPlaying {

    public final String pkg;        // 正在播放的 app 包名
    public final String appLabel;   // app 名(显示在卡片上,让用户知道是哪个播放器)
    public final String title;
    public final String artist;
    public final Bitmap cover;      // 可能为 null
    public final String lyric;      // 当前歌词行,多数播放器不提供,为空则隐藏该区域
    public final boolean playing;
    public final long position;     // ms,-1 表示未知
    public final long duration;     // ms,-1 表示未知

    public NowPlaying(String pkg, String appLabel, String title, String artist,
                      Bitmap cover, String lyric, boolean playing, long position, long duration) {
        this.pkg = pkg;
        this.appLabel = appLabel;
        this.title = title;
        this.artist = artist;
        this.cover = cover;
        this.lyric = lyric;
        this.playing = playing;
        this.position = position;
        this.duration = duration;
    }

    public boolean hasProgress() {
        return duration > 0 && position >= 0;
    }
}
