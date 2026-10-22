package com.liubai.blank.game;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 右栏那面码墙:<b>每个玩家一张二维码,常驻</b>,点一张放大(见 {@link PadPopup})。
 *
 * <h3>为什么摆在右栏、而且常驻</h3>
 * 画面按比例放大后左右各剩一条空栏,左边摆功能键,右边这条本来是空着的 —— 而
 * 扫码是上车玩的第一件事。码摆在那儿它自己会说话:谁还没连、连了几个,抬眼就看见,
 * 不用点、也不用靠"一进游戏先弹一次窗"去提醒(那条自动弹窗因此撤了)。
 *
 * <h3>小码 + 点开放大,而不是只给小码</h3>
 * 这条栏 190dp 宽,四个人时一张码只剩 110dp(165px)—— 副驾伸手扫得到,后排隔着
 * 一米多就够呛。所以小码管"看状态和随手扫",点一下弹到 300dp 管"扫得吃力的时候"。
 *
 * <h3>已经连上的那张会淡下去</h3>
 * 连上之后那张码对本人再没用了,但**不能撤掉** —— 位子的数量是这台机器的属性
 * (GB 就一个口),撤了会让人以为少了一个人。淡化 + 绿标是"这个位子有人了"最省事的说法。
 */
public class PlayerPanel extends LinearLayout {

    // 配色走模式系统(同 GameListView 那段说明)。**二维码是唯一的例外** ——
    // 它必须黑白,染上主题色就扫不出来了(见 PadPopup.encode)
    private static int INK()   { return com.liubai.blank.mode.ModeManager.get().ink(); }
    private static int MUTED() { return com.liubai.blank.mode.ModeManager.get().muted(); }
    /** 「已连接」那一档绿:这是**状态色**不是主题色,连没连上要一眼分得出,不跟着换肤走。 */
    private static final int ON = 0xFF7BE38A;

    private String baseUrl = "";
    private int players = 1;
    private final boolean[] online = new boolean[4];
    /** 开着的那个放大弹层。关掉后置空 —— 地址/连接状态只往开着的那个转发。 */
    private PadPopup popup;

    public PlayerPanel(Context ctx) {
        super(ctx);
        setOrientation(VERTICAL);
        rebuild();
    }

    /** 手柄服务的地址。IP 是后台线程取的,取到之前是占位串,所以这里会被调不止一次。 */
    public void setBaseUrl(String url) {
        if (url == null) url = "";
        if (url.equals(baseUrl)) return;
        baseUrl = url;
        rebuild();                                    // 码是按地址生成的,地址变了整面墙重画
        if (popup != null && popup.isShowing()) popup.setBaseUrl(baseUrl);
    }

    /** 当前游戏最多几个人。GB 只有一个手柄口,那就只摆一张码。 */
    public void setPlayers(int n) {
        n = Math.max(1, Math.min(4, n));
        if (n == players) return;
        players = n;
        rebuild();
    }

    /** 换肤:整块重建 —— 颜色是建 view 时取的(见配色那段说明)。 */
    public void reskin() { rebuild(); }

    /** 某个玩家连上没有。由手柄服务回调(PadServer.setPlayersListener)。 */
    public void setOnline(int index, boolean on) {
        if (index < 0 || index >= 4 || online[index] == on) return;
        online[index] = on;
        if (popup != null && popup.isShowing()) popup.setOnline(index, on);
        rebuild();
    }

    /** 页面要没了,别留着弹层(WindowLeaked)。 */
    public void close() {
        if (popup != null) { popup.dismiss(); popup = null; }
    }

    private void open(int index) {
        if (popup != null && popup.isShowing()) return;
        popup = PadPopup.show(getContext(), baseUrl, players, online, index);
    }

    /**
     * 码多大。**按剩下的地方算,不写死** —— 一个人玩时没理由空着大半条栏,
     * 四个人时写死一个值又会从屏幕底下顶出去(这条栏还要给 Dock 让位)。
     *
     * 上限卡在栏宽:190dp 减两边 16dp 的 padding 再减白框,只剩 146dp。
     * 下限 90dp(135px)是二维码本身的底线 —— 再小手机就对不上焦了。
     */
    private int qrDp() {
        float d = getResources().getDisplayMetrics().density;
        int screenDp = Math.round(getResources().getDisplayMetrics().heightPixels / d);
        int dockDp = Math.round(getResources().getDimensionPixelSize(
                com.liubai.blank.R.dimen.dock_space) / d);
        int avail = screenDp - dockDp - 32;              // 栏自己上下各 16dp
        // 一格里除了码还有:白框 6×2、标签那行、格间距 12。标签按最坏情况留
        // (车机字体缩放不一定是 1.0,那一行会比 13sp 算出来的更高)
        int qr = avail / Math.max(1, players) - 48;
        return Math.max(90, Math.min(146, qr));
    }

    private void rebuild() {
        removeAllViews();
        for (int i = 0; i < players; i++) addView(tile(i));
    }

    /** 一个位子:白底二维码 + 一行标签。整块都能点。 */
    private LinearLayout tile(final int index) {
        Context ctx = getContext();
        boolean connected = online[index];

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setClickable(true);
        col.setOnClickListener(v -> open(index));
        com.liubai.blank.ui.Motion.press(col);

        int qrPx = dp(qrDp());
        int frame = dp(6);
        LinearLayout board = new LinearLayout(ctx);
        board.setGravity(Gravity.CENTER);
        board.setPadding(frame, frame, frame, frame);
        GradientDrawable bg = new GradientDrawable();
        // 码底必须白:模式是深色时直接贴上去,四周会被面板底色吃掉定位角
        bg.setColor(0xFFFFFFFF);
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(2), connected ? ON : 0x00000000);
        board.setBackground(bg);

        Bitmap code = PadPopup.encode(PadPopup.playerUrl(baseUrl, index), qrPx);
        if (code != null) {
            ImageView qr = new ImageView(ctx);
            qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
            qr.setImageBitmap(code);
            board.addView(qr, new LayoutParams(qrPx, qrPx));
            // 连上的那张淡下去:位子还在,但这张码已经没人需要扫了
            board.setAlpha(connected ? 0.35f : 1f);
        } else {
            // IP 是后台线程两秒一轮枚举网卡取的(见 GameActivity.padTick),
            // **开页那一刻多半还没有** —— 空着一块白比什么都不说要糟
            TextView wait = new TextView(ctx);
            wait.setText("正在找\n车机地址…");
            wait.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            wait.setGravity(Gravity.CENTER);
            wait.setTextColor(0xFF666666);
            board.addView(wait, new LayoutParams(qrPx, qrPx));
        }
        col.addView(board, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        TextView label = new TextView(ctx);
        // 一个人玩时不写「P1」—— 没有第二个位子,标号是废话;写清楚这码是干嘛的更有用
        label.setText(players == 1
                ? (connected ? "手柄已连上" : "扫码 · 手机当手柄")
                : ("P" + (index + 1) + (connected ? " · 已连上" : " · 扫码")));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, players == 1 ? 12 : 13);
        label.setTextColor(connected ? ON : (players == 1 ? MUTED() : INK()));
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, dp(5), 0, 0);
        col.addView(label, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        col.setLayoutParams(lp);
        return col;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
