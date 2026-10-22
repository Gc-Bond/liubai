package com.liubai.blank.game;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

/**
 * 把核心吐的音频送进 AudioTrack。
 *
 * 单独一条线程,从 native 的环形缓冲里取 —— 模拟线程只管往缓冲里写,写满就丢,
 * **绝不会被音频阻塞**。这跟纯 Java 那版正好相反:那边是靠音频阻塞来控速的,
 * 结果我传了空实现,节流器就没了。这里控速交给主循环的 clock_nanosleep,音频只管播。
 */
public class AudioOut {

    private static final String TAG = "NativeTest";

    private AudioTrack track;
    private Thread thread;
    private volatile boolean running;
    private volatile String status = "(未启动)";
    private volatile boolean muted;

    public String status() { return status + (muted ? " · 静音" : ""); }

    public boolean toggleMute() { muted = !muted; return muted; }

    public boolean muted() { return muted; }

    public void start() {
        if (running) return;

        int rate = Retro.nativeSampleRate();
        // mGBA 报的是 131072 Hz 这种非常规值。AudioTrack 允许 4000–192000,
        // 设备顶不住的话会自己重采样;真拿不到就退到 48k。
        int min = AudioTrack.getMinBufferSize(rate,
                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) {
            Log.w(TAG, "采样率 " + rate + " 不被支持，退到 48000");
            rate = 48000;
            min = AudioTrack.getMinBufferSize(rate,
                    AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        }
        // 缓冲给宽一点:车机上偶尔的调度抖动不至于立刻爆音
        final int bufBytes = Math.max(min * 4, 8192);

        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build())
                    .setBufferSizeInBytes(bufBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            track.play();
        } catch (Exception e) {
            status = "AudioTrack 建失败: " + e;
            Log.e(TAG, status, e);
            return;
        }

        running = true;
        final int hz = rate;
        thread = new Thread(() -> loop(hz, bufBytes), "audio-out");
        thread.setPriority(Thread.MAX_PRIORITY);   // 音频欠载比掉一帧画面刺耳得多
        thread.start();
        status = hz + " Hz";
        Log.i(TAG, "音频已启动 " + hz + " Hz, 缓冲 " + bufBytes + " B");
    }

    private void loop(int rate, int bufBytes) {
        short[] buf = new short[2048];
        long wrote = 0;
        while (running) {
            int n = Retro.nativeReadAudio(buf);
            if (n <= 0) {
                // 缓冲空了就短睡一下，别空转烧 CPU
                try { Thread.sleep(2); } catch (InterruptedException e) { return; }
                continue;
            }
            // 静音就把这批样本清零再写 —— 不能直接不写,否则 AudioTrack 会欠载爆音
            if (muted) java.util.Arrays.fill(buf, 0, n, (short) 0);
            track.write(buf, 0, n);
            wrote += n;
            if ((wrote & 0xFFFF) < n) {
                status = rate + " Hz  已播 " + (wrote / 2 / rate) + " s"
                        + "  丢 " + Retro.nativeAudioDropped();
            }
        }
    }

    public void stop() {
        running = false;
        if (thread != null) {
            try { thread.join(500); } catch (InterruptedException ignored) { }
            thread = null;
        }
        if (track != null) {
            try { track.pause(); track.flush(); track.release(); } catch (Exception ignored) { }
            track = null;
        }
    }
}
