package com.liubai.blank.game;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import com.liubai.blank.R;
import com.liubai.blank.mode.ModeApplier;
import com.liubai.blank.mode.ModeManager;
import com.liubai.blank.util.Immersive;
import com.liubai.blank.util.Launchers;

import java.util.HashMap;
import java.util.Map;

/**
 * 扫码连手柄的弹层:<b>一次只显示一个玩家的二维码,但显示得足够大</b>。
 *
 * <h3>它是"放大镜",不是唯一入口</h3>
 * 码平时就常驻在右栏那面墙上(见 {@link PlayerPanel}),四个人时一张 116dp ——
 * 副驾伸手能扫,后排隔着一米多就够呛。点某一张才弹这层,码给到 300dp(450px),
 * 隔着驾驶位也扫得到。
 *
 * 一次只显示一个:扫码本来就是一个一个来的,而这层的**全部价值就是大**,
 * 同时铺四个等于又回到那面小码墙。多人靠上面那排 P1/P2 切。
 *
 * <h3>连上了自己往下走</h3>
 * P1 扫完,弹层自动翻到下一个还没连的玩家({@link #setOnline}) —— 传手机的那一路
 * 不用有人回车机屏上再点一下。全连上就报一句然后自己关掉。
 *
 * 风格照 {@link com.liubai.blank.ui.ConfirmPopup} 那套机制走(popup_bg + panel tag +
 * 根容器吃触摸 + ModeApplier + Immersive.show),几条缺一不可的理由见那边的注释。
 */
public final class PadPopup {

    /** 二维码边长。左栏那版才 213px,这里给到 300dp(450px),隔着驾驶位也扫得到。 */
    private static final int QR_DP = 300;
    /** 「已连接」那一档绿:状态色,不跟着模式换(同 PlayerPanel)。 */
    private static final int ON = 0xFF7BE38A;

    private final Context ctx;
    private final Dialog dialog;
    private final int players;
    private final boolean[] online;

    private final ImageView qr;
    private final TextView addr, tip;
    private final LinearLayout chipRow;
    private final TextView[] chips;

    private String baseUrl;
    /** 当前显示的是第几号玩家(0 起)。 */
    private int showing;

    private PadPopup(Context ctx, String baseUrl, int players, boolean[] online) {
        this.ctx = ctx;
        this.baseUrl = baseUrl == null ? "" : baseUrl;
        this.players = Math.max(1, Math.min(4, players));
        this.online = new boolean[4];
        System.arraycopy(online, 0, this.online, 0, Math.min(online.length, 4));
        this.chips = new TextView[this.players];
        this.showing = firstOffline();

        dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        int pad = Launchers.dp(ctx, 20);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.popup_bg);
        root.setTag("fill:tile2;stroke:line;panel");
        root.setPadding(pad, pad, pad, Launchers.dp(ctx, 12));
        // 根容器必须自己吃掉触摸,否则会穿到弹层背后去(见 ConfirmPopup 那段实测)
        root.setClickable(true);
        root.setFocusable(true);

        TextView title = new TextView(ctx);
        title.setText("手机扫一下,就是手柄");
        title.setTextSize(20f);
        Launchers.medium(title);
        title.setTextColor(ModeManager.get().ink());
        title.setTag("fg:ink");
        root.addView(title);

        TextView desc = new TextView(ctx);
        desc.setText("手机连车机同一个 Wi-Fi(或车机热点),扫完直接在浏览器里玩,不用装东西。");
        desc.setTextSize(14f);
        desc.setLineSpacing(Launchers.dp(ctx, 4), 1f);
        desc.setTextColor(ModeManager.get().muted());
        desc.setTag("fg:muted");
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = Launchers.dp(ctx, 8);
        root.addView(desc, dlp);

        // 玩家切换。**一个人的机器不摆这排** —— GB 根本没有第二个手柄口,
        // 摆一颗孤零零的「P1」只是让人多看一眼
        chipRow = new LinearLayout(ctx);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = Launchers.dp(ctx, 16);
        root.addView(chipRow, clp);
        for (int i = 0; i < this.players; i++) {
            final int idx = i;
            TextView c = chip("P" + (i + 1));
            c.setOnClickListener(v -> { showing = idx; refresh(); });
            chips[i] = c;
            chipRow.addView(c);
        }
        chipRow.setVisibility(this.players > 1 ? View.VISIBLE : View.GONE);

        // 二维码压一块白底 —— 码必须黑白(染色就扫不出),而模式是深色时
        // 直接贴上去四周会被面板底色吃掉定位角
        int qrPx = Launchers.dp(ctx, QR_DP);
        int frame = Launchers.dp(ctx, 10);
        LinearLayout board = new LinearLayout(ctx);
        board.setGravity(Gravity.CENTER);
        board.setPadding(frame, frame, frame, frame);
        GradientDrawable bd = new GradientDrawable();
        bd.setColor(0xFFFFFFFF);
        bd.setCornerRadius(Launchers.dp(ctx, 12));
        board.setBackground(bd);
        qr = new ImageView(ctx);
        qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        board.addView(qr, new LinearLayout.LayoutParams(qrPx, qrPx));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Launchers.dp(ctx, 16);
        blp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(board, blp);

        // 地址明写一行:极少数手机扫不出码(镜头脏、角度太斜),还能照着敲
        addr = new TextView(ctx);
        addr.setTextSize(12f);
        addr.setGravity(Gravity.CENTER);
        addr.setTextColor(ModeManager.get().muted());
        addr.setTag("fg:muted");
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = Launchers.dp(ctx, 10);
        root.addView(addr, alp);

        tip = new TextView(ctx);
        tip.setTextSize(14f);
        tip.setGravity(Gravity.CENTER);
        tip.setTextColor(ON);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = Launchers.dp(ctx, 6);
        root.addView(tip, tlp);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = Launchers.dp(ctx, 12);
        row.setLayoutParams(rlp);
        TextView ok = new TextView(ctx);
        ok.setText("知道了");
        ok.setTextSize(16f);
        ok.setGravity(Gravity.CENTER);
        ok.setMinimumHeight(Launchers.dp(ctx, 48));
        ok.setPadding(Launchers.dp(ctx, 22), 0, Launchers.dp(ctx, 22), 0);
        ok.setTextColor(ModeManager.get().onAccent());
        ok.setBackgroundResource(R.drawable.pill_accent);
        ok.setTag("fg:onAccent;fill:accent");
        ok.setClickable(true);
        com.liubai.blank.ui.Motion.press(ok);
        ok.setOnClickListener(v -> dialog.dismiss());
        row.addView(ok);
        root.addView(row);

        refresh();

        ModeApplier.apply(root);
        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            int width = Math.min(Math.round(dm.widthPixels * 0.46f), Launchers.dp(ctx, 420));
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    /** 弹出来。Dialog 收焦点会把系统栏带出来,所以走 Immersive 不走裸 show。 */
    public static PadPopup show(Context ctx, String baseUrl, int players, boolean[] online) {
        return show(ctx, baseUrl, players, online, -1);
    }

    /**
     * @param startAt 开着就先显示第几号玩家(0 起)。传 -1 = 自己挑第一个还没连上的。
     *                右栏那面码墙点哪张就该放大哪张,不能替人跳到别人那儿去。
     */
    public static PadPopup show(Context ctx, String baseUrl, int players, boolean[] online,
                                int startAt) {
        PadPopup p = new PadPopup(ctx, baseUrl, players, online);
        if (startAt >= 0 && startAt < p.players) { p.showing = startAt; p.refresh(); }
        Immersive.show(p.dialog);
        return p;
    }

    public boolean isShowing() {
        return dialog.isShowing();
    }

    public void dismiss() {
        if (dialog.isShowing()) dialog.dismiss();
    }

    /**
     * 地址来了(或变了)。
     *
     * IP 是后台线程枚举网卡取的、两秒一轮(见 GameActivity.padTick),所以
     * <b>弹层先开、地址后到这条路一定会走到</b> —— 开的那一刻二维码可能还是空的。
     */
    public void setBaseUrl(String url) {
        if (url == null) url = "";
        if (url.equals(baseUrl)) return;
        baseUrl = url;
        refresh();
    }

    /**
     * 某个玩家连上/断开了。
     *
     * <b>连上的正是眼下显示的这个 → 自动翻到下一个还没连的</b>:多人是轮流扫的,
     * 传手机那一路不该还要有人回车机屏上点一下 P2。都连齐了就报一句、自己关掉 ——
     * 这块弹层此刻已经没有任何用处,还挡着游戏画面。
     */
    public void setOnline(int index, boolean on) {
        if (index < 0 || index >= 4 || online[index] == on) return;
        online[index] = on;
        if (on) {
            int next = firstOffline();
            if (next < 0) {
                refresh();
                tip.setText("都连上了,开玩吧");
                tip.postDelayed(this::dismiss, 1500);
                return;
            }
            if (index == showing) {
                showing = next;
                refresh();
                tip.setText("P" + (index + 1) + " 连上了 · 该 P" + (next + 1) + " 扫了");
                return;
            }
        }
        refresh();
    }

    /** 第一个还没连上的玩家;都连上了返回 -1。 */
    private int firstOffline() {
        for (int i = 0; i < players; i++) if (!online[i]) return i;
        return -1;
    }

    private void refresh() {
        if (showing < 0 || showing >= players) showing = 0;
        String url = playerUrl(showing);
        Bitmap code = encode(url, Launchers.dp(ctx, QR_DP));
        qr.setImageBitmap(code);
        addr.setText(url.isEmpty() ? "正在找车机的地址…" : url);
        tip.setText(online[showing] ? "P" + (showing + 1) + " 已经连上了" : "");
        for (int i = 0; i < players; i++) {
            boolean sel = i == showing;
            chips[i].setText("P" + (i + 1) + (online[i] ? " ·已连" : ""));
            chips[i].setTextColor(online[i] ? ON
                    : (sel ? ModeManager.get().ink() : ModeManager.get().muted()));
            GradientDrawable g = (GradientDrawable) chips[i].getBackground();
            g.setColor(sel ? ModeManager.get().tile() : 0x00000000);
            g.setStroke(Launchers.dp(ctx, 1),
                    sel ? ModeManager.get().accent() : ModeManager.get().line());
        }
    }

    private TextView chip(String text) {
        TextView c = new TextView(ctx);
        c.setText(text);
        c.setTextSize(14f);
        c.setGravity(Gravity.CENTER);
        c.setMinimumHeight(Launchers.dp(ctx, 40));
        c.setPadding(Launchers.dp(ctx, 16), 0, Launchers.dp(ctx, 16), 0);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(Launchers.dp(ctx, 20));
        c.setBackground(g);
        c.setClickable(true);
        com.liubai.blank.ui.Motion.press(c);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(Launchers.dp(ctx, 8));
        c.setLayoutParams(lp);
        return c;
    }

    /**
     * 每个玩家一个地址,带 {@code ?p=N}。
     *
     * 扫哪个二维码就是哪号玩家,不用"先到先得" —— 谁是 P1 谁是 P2 该由他们自己挑,
     * 而不是看谁手快。
     */
    private String playerUrl(int index) {
        return playerUrl(baseUrl, index);
    }

    /** 同上。右栏那面码墙也要拼同样的地址,所以留一份 static 的(见 PlayerPanel)。 */
    static String playerUrl(String baseUrl, int index) {
        if (baseUrl == null || baseUrl.isEmpty()) return "";
        return baseUrl + "/?p=" + (index + 1);
    }

    /** 生成二维码。URL 短(三十来个字符),纠错给到 M 就够,余量留给车里的反光和角度。 */
    static Bitmap encode(String text, int size) {
        if (text.isEmpty() || size <= 0) return null;
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            // 默认留白 4 个模块,这里外面已经有一圈白边框,再留就是白扔面积
            hints.put(EncodeHintType.MARGIN, 1);
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
            int w = m.getWidth(), h = m.getHeight();
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++) {
                int off = y * w;
                for (int x = 0; x < w; x++) {
                    // 白底黑码 —— 反过来大多数手机扫不出
                    px[off + x] = m.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
                }
            }
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bmp.setPixels(px, 0, w, 0, 0, w, h);
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }
}
