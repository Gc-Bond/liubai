package com.liubai.blank.ui;

import android.content.Context;
import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.liubai.blank.R;
import com.liubai.blank.data.DeskCardStore;
import com.liubai.blank.game.GameActivity;
import com.liubai.blank.game.GameLibrary;
import com.liubai.blank.mode.ModeManager;
import com.liubai.blank.util.Launchers;

/**
 * 游戏中心卡:手柄图标 + 「游戏中心」,点一下进列表页。
 *
 * <h3>它只是入口,不直接开局</h3>
 *
 * 这张卡曾经写着上次玩的那个游戏名、点一下跳过列表页直接进那一局 ——
 * 拍板撤掉了(用户原话:「点击游戏中心,不用直接进入游戏画面」)。
 * 从桌面点进来的人不一定想接着打上一局,而**列表页首页第一块就是「最近玩过」**,
 * 接着玩只多一次点击;反过来,想换个游戏却被直接扔进上一局,要退两层才回得来。
 *
 * 连带撤掉的:`EXTRA_PLAY` 和 `GameActivity.playDirect`(那条 Intent 通路没有别的用户)、
 * `GameLibrary.lastPlayedName/lastPlayedKey/lastPlayed` 和 prefs 里那两个键
 * —— 「最近玩过」走的是另一份 `KEY_RECENT`,不受影响。
 */
public class GameCard extends DeskCard {

    /**
     * 规格**直接引用车控卡的常量**,不再自己抄一份数字 —— 抄过一次就漂过一次:
     * 标签上方那道间距当初只抄了 {@code LABEL_GAP_DP}(4dp),漏了车控卡"没有档位条
     * 也要把条那段空出来"的那 10dp(见 CarCtrlTile 构造器),于是这张卡比同排矮 10dp,
     * 真车上一眼看得出来。
     */
    private static final int W_DP = CarCtrlTile.W_DP;
    private static final int ICON_DP = CarCtrlTile.ICON_DP;
    private static final int LABEL_GAP_DP =
            CarCtrlTile.LABEL_GAP_DP + CarCtrlTile.BARS_GAP_DP + CarCtrlTile.BARS_DP;

    private final TextView label;
    private final ImageView icon;

    public GameCard(Context ctx) {
        super(ctx, DeskCardStore.GAME);

        // **竖排,和车控卡同构**:图标在上、一行字在下。
        // 第一版做成了横排(图标 + 两行字),那就得占两格 —— 而桌面上一格的地方
        // 本来就够:车控卡放得下 38dp 图标加四个汉字,游戏名也是这个量级。
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);

        icon = new ImageView(ctx);
        icon.setImageResource(R.drawable.ic_game_app);
        col.addView(icon, new LinearLayout.LayoutParams(
                Launchers.dp(ctx, ICON_DP), Launchers.dp(ctx, ICON_DP)));

        // **一行封顶、超了省略** —— 街机那些名字能长到
        // 「拳皇 '97 - 随机连招 (修改, 版本 2010)」,不截会把卡片撑成一条
        label = new TextView(ctx);
        label.setTextSize(12f);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(1);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        llp.topMargin = Launchers.dp(ctx, LABEL_GAP_DP);
        col.addView(label, llp);

        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                Launchers.dp(ctx, W_DP), LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.CENTER;
        addView(col, clp);

        col.setOnClickListener(v -> open());
        render();
    }

    /**
     * 打开游戏中心,停在列表页。
     *
     * 走显式 Intent:游戏中心没声明 LAUNCHER(不让原厂桌面长出图标),
     * 隐式 Intent 找不到它(同 AppDrawerActivity 那条注入的理由)。
     */
    private void open() {
        Context ctx = getContext();
        Intent i = new Intent(ctx, GameActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }

    /** 换肤:颜色不烧在构造里,和别的卡一样每次重新取(见 ClockCard.onReskin)。 */
    @Override protected void onReskin() {
        render();
    }

    /**
     * 卡面。文字是固定的,这儿只剩跟着模式走的两处颜色。
     *
     * <p><b>图标不能用 accent</b>(2026-09-05 改):这一屏里 accent 是「开着」的意思 ——
     * 车控卡开着才是 accent、关着是 glyph,HiCar 卡同理(on ? accent : muted)。
     * 游戏中心是个入口,没有开关状态,涂成 accent 就被读成"已经打开了"。
     * 用 glyph(图标的常规色),和关着的车控卡一致 —— 它本来也不该比别人显眼。
     */
    private void render() {
        label.setText("游戏中心");
        label.setTextColor(ModeManager.get().ink());
        icon.setColorFilter(ModeManager.get().glyph());
    }

    // **不设自己的宽高**,尺寸完全交给内容撑 —— 这正是车控卡的做法
    // (CarCtrlTile 也只给内容一个 72dp 的宽,自己不碰 LayoutParams)。
    //
    // 而"一格"这个尺寸本来就是车控卡定义的(DeskCardHost.snapCell 拿的是第一张
    // CarCtrlTile 的实际尺寸),所以**只要内容规格照抄它**(72dp 宽、38dp 图标、
    // 12sp 标签),这张卡自然就和一排车控卡等宽等高,不用去算格子。
    //
    // 试过反着来:按 snapCell 算出格子尺寸再 setLayoutParams。结果是卡片底整个
    // 不见了、位置也飘 —— 那两个数是**含缝的步长**,不是卡片该有的尺寸,
    // 而且和基类的 padding 打架。照抄内容规格比算格子稳。
}
