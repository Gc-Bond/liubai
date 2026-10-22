package com.liubai.blank.ui;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.PixelCopy;
import android.view.View;

import com.liubai.blank.model.CarCtrlItem;
import com.liubai.blank.model.DockItem;

import java.util.ArrayList;
import java.util.List;

/**
 * 技巧页的底:**真桌面**的一张位图,加上手势落点在这张图里的坐标。
 *
 * <h3>为什么抓真桌面</h3>
 * 线框微缩屏看着糙(2026-09-12 反馈"有点 low")。用户自己的壁纸、自己排的卡片和 Dock
 * 当底,手指落在真实的键上,一眼就认得出"哦是那个键"。零素材,界面改了自动同步。
 *
 * <h3>怎么抓</h3>
 * 桌面本身走 {@link PixelCopy}:它复制的是窗口合成后的像素,视频壁纸(TextureView)、
 * 网页壁纸(WebView)都在里面,View.draw 画不出这两样。**Dock 是另一个悬浮窗**,
 * PixelCopy 抓不到,用 View.draw 按它在屏幕上的位置补画进去。顶栏也是悬浮窗,
 * 但整个功能还关着(StatusBarHost.FEATURE_ON),不画。
 *
 * <h3>什么时候抓</h3>
 * 面板 open() 时、还没 VISIBLE 之前 —— 那一刻屏幕上就是干净的桌面。异步回调,
 * 技巧页建好前后都可能到,两头都接(见 AppearancePopup.buildTipsTab)。
 *
 * <h3>坐标</h3>
 * 全部换算到位图像素。卡片在本窗口里,取 getLocationInWindow;Dock 的键在别的窗口,
 * 取屏幕坐标再减掉本窗口的屏幕偏移。
 */
public final class DeskSnapshot {

    private static final String TAG = "Blank/Snap";
    /** 位图 = 屏幕的一半:演示区才 400dp 宽,再大是浪费。 */
    private static final float SCALE = .5f;

    public interface Callback { void onSnapshot(DeskSnapshot s); }
    /** 谁来抓:HomeActivity 实现(它手里有窗口、卡片宿主和 Dock)。 */
    public interface Source { void capture(Callback cb); }

    public final Bitmap bitmap;
    /** 桌面卡片(位图坐标)。 */
    public final List<RectF> cards;
    /** WLAN 卡,没摆这张卡时 null。 */
    public final RectF wifiCard;
    /** Dock 上渲染出来的全部键(编辑态挂 × 的那些位置)。 */
    public final List<RectF> keys;
    /** 几个技巧点名的键,Dock 上没这个键时为 null:主页 / 全部应用 / 扇叶 / 应用位 / 温度 / 温度右箭头 / 设置。 */
    public final RectF home, apps, fan, slot, temp, tempRight, settings;
    /** Dock 条的上沿(位图坐标)。 */
    public final float dockTop;
    /** 桌面空白处的一个点。 */
    public final float blankX, blankY;

    private DeskSnapshot(Bitmap bitmap, List<RectF> cards, RectF wifiCard, List<RectF> keys,
                         RectF home, RectF apps, RectF fan, RectF slot, RectF temp, RectF tempRight,
                         RectF settings, float dockTop, float blankX, float blankY) {
        this.bitmap = bitmap; this.cards = cards; this.wifiCard = wifiCard; this.keys = keys;
        this.home = home; this.apps = apps; this.fan = fan; this.slot = slot; this.temp = temp;
        this.tempRight = tempRight; this.settings = settings; this.dockTop = dockTop;
        this.blankX = blankX; this.blankY = blankY;
    }

    /**
     * 抓一张。回调在主线程;抓不到(窗口还没画过、PixelCopy 失败)回 null,技巧页退回线框。
     */
    public static void capture(final Activity act, final DeskCardHost desk, final DockView dock,
                               final Callback cb) {
        final View decor = act.getWindow().getDecorView();
        final int w = decor.getWidth(), h = decor.getHeight();
        if (w <= 0 || h <= 0) { cb.onSnapshot(null); return; }
        final Bitmap bmp = Bitmap.createBitmap(Math.round(w * SCALE), Math.round(h * SCALE),
                Bitmap.Config.ARGB_8888);
        try {
            PixelCopy.request(act.getWindow(), bmp, result -> {
                if (result != PixelCopy.SUCCESS) {
                    Log.w(TAG, "PixelCopy 失败 " + result);
                    cb.onSnapshot(null);
                    return;
                }
                cb.onSnapshot(compose(decor, bmp, desk, dock));
            }, new Handler(Looper.getMainLooper()));
        } catch (IllegalArgumentException e) {   // 窗口没 surface(还没显示过)
            Log.w(TAG, "PixelCopy 起不来", e);
            cb.onSnapshot(null);
        }
    }

    private static DeskSnapshot compose(View decor, Bitmap bmp, DeskCardHost desk, DockView dock) {
        int[] dloc = new int[2];
        decor.getLocationOnScreen(dloc);

        // Dock:另一个窗口,按屏幕位置补画进来
        float dockTop = bmp.getHeight();
        if (dock != null && dock.isAttachedToWindow()) {
            View root = dock.getRootView();
            int[] loc = new int[2];
            root.getLocationOnScreen(loc);
            Canvas c = new Canvas(bmp);
            c.scale(SCALE, SCALE);
            c.translate(loc[0] - dloc[0], loc[1] - dloc[1]);
            try {
                root.draw(c);
            } catch (RuntimeException e) {
                Log.w(TAG, "Dock 画不进快照", e);
            }
            int[] dl = new int[2];
            dock.getLocationOnScreen(dl);
            dockTop = (dl[1] - dloc[1]) * SCALE;
        }

        List<RectF> cards = new ArrayList<>();
        RectF wifi = null;
        if (desk != null) {
            String wifiId = CarCtrlTile.ID_PREFIX + CarCtrlItem.WIFI.key;
            for (DeskCard card : desk.cards()) {
                if (card.getVisibility() != View.VISIBLE || card.getWidth() == 0) continue;
                RectF r = inWindow(card);
                cards.add(r);
                if (wifiId.equals(card.id())) wifi = r;
            }
        }

        List<RectF> keys = new ArrayList<>();
        RectF home = null, apps = null, fan = null, slot = null, temp = null, tempRight = null, settings = null;
        if (dock != null && dock.isAttachedToWindow()) {
            for (View v : dock.keyViews()) keys.add(onScreen(v, dloc));
            home = rect(dock.viewOf(DockItem.Kind.HOME, null), dloc);
            apps = rect(dock.viewOf(DockItem.Kind.DRAWER, null), dloc);
            fan = rect(dock.viewOf(DockItem.Kind.AC, "fan"), dloc);
            slot = rect(dock.viewOf(DockItem.Kind.RUNNING, null), dloc);
            if (slot == null) slot = rect(dock.viewOf(DockItem.Kind.MAP, null), dloc);
            temp = rect(dock.viewOf(DockItem.Kind.TEMP, null), dloc);
            tempRight = rect(dock.tempStep(1), dloc);
            settings = rect(dock.viewOf(DockItem.Kind.SETTINGS, null), dloc);
        }

        // 空白处:从几个候选点里挑第一个不压在卡片上、又在 Dock 上方的
        float W = bmp.getWidth(), H = bmp.getHeight();
        float[][] cand = { {.64f, .42f}, {.5f, .5f}, {.78f, .3f}, {.3f, .72f}, {.5f, .3f} };
        float bx = W * .64f, by = H * .42f;
        for (float[] p : cand) {
            float x = W * p[0], y = H * p[1];
            if (y > dockTop - .06f * H) continue;
            boolean clear = true;
            for (RectF r : cards) {
                if (x > r.left - .03f * W && x < r.right + .03f * W
                        && y > r.top - .03f * W && y < r.bottom + .03f * W) { clear = false; break; }
            }
            if (clear) { bx = x; by = y; break; }
        }
        return new DeskSnapshot(bmp, cards, wifi, keys, home, apps, fan, slot, temp, tempRight,
                settings, dockTop, bx, by);
    }

    private static RectF rect(View v, int[] dloc) {
        return v == null || !v.isAttachedToWindow() || v.getWidth() == 0 ? null : onScreen(v, dloc);
    }

    /** 本窗口里的 view → 位图坐标。 */
    private static RectF inWindow(View v) {
        int[] loc = new int[2];
        v.getLocationInWindow(loc);
        return new RectF(loc[0] * SCALE, loc[1] * SCALE,
                (loc[0] + v.getWidth()) * SCALE, (loc[1] + v.getHeight()) * SCALE);
    }

    /** 别的窗口里的 view → 位图坐标(屏幕坐标减掉本窗口偏移)。 */
    private static RectF onScreen(View v, int[] dloc) {
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        float x = loc[0] - dloc[0], y = loc[1] - dloc[1];
        return new RectF(x * SCALE, y * SCALE, (x + v.getWidth()) * SCALE, (y + v.getHeight()) * SCALE);
    }
}
