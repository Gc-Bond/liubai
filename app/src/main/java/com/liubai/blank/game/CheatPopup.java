package com.liubai.blank.game;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.liubai.blank.R;
import com.liubai.blank.mode.ModeApplier;
import com.liubai.blank.mode.ModeManager;
import com.liubai.blank.util.Immersive;
import com.liubai.blank.util.Launchers;

import java.util.List;

/**
 * 金手指面板:一行一条,**点一下往下轮一格**,轮回「关」就是关掉。
 *
 * 不做成布尔开关是因为街机那边不止两档 —— FBNeo 的 cheat 一条可以带好几个选项
 * (「Select Starting Episode」有 8 关可挑)。两台机器共用这一套(见 CheatStore.Cheat):
 * FC 那种就是「关 / 开」两档,轮起来和开关一模一样。
 *
 * <h3>点了就生效,没有「确定」</h3>
 * 金手指本来就是**开着看看效果、不对再关掉**的东西 —— 中间隔一个确认按钮,人就得
 * 关掉面板才知道有没有用,不对了还要再开一遍。所以每点一下当场重下发整张表
 * (见 {@link Listener#onChanged}),面板开着也能看见画面变化。
 *
 * <h3>为什么不做「自定义金手指」</h3>
 * 手输地址和值要一个十六进制键盘,而车上没人会对着中控屏敲 {@code 00B0-01-FF}。
 * 站上那份现成的够用了(魂斗罗 12 条,无敌、命数、四种枪都在)。
 */
public final class CheatPopup {

    public interface Listener {
        /** 有开关变动了:整张表重新下发(开着的那些)。 */
        void onChanged();
    }

    private CheatPopup() {}

    public static void show(Context ctx, String gameName,
                            final List<CheatStore.Cheat> cheats, final Listener listener) {
        show(ctx, gameName, cheats, listener, null);
    }

    /**
     * @param onClose 面板关掉时叫一声(不管点「知道了」还是按返回键)——
     *                调用方拿它恢复暂停,见 GameActivity.cheatPanelOpen
     */
    public static void show(Context ctx, String gameName,
                            final List<CheatStore.Cheat> cheats, final Listener listener,
                            final Listener onClose) {
        final Dialog dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        int pad = Launchers.dp(ctx, 20);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.popup_bg);
        root.setTag("fill:tile2;stroke:line;panel");
        root.setPadding(pad, pad, pad, Launchers.dp(ctx, 12));
        // 根容器自己吃触摸,否则会穿到弹层背后(见 ConfirmPopup 那段实测)
        root.setClickable(true);
        root.setFocusable(true);

        TextView title = new TextView(ctx);
        title.setText("金手指");
        title.setTextSize(20f);
        Launchers.medium(title);
        title.setTextColor(ModeManager.get().ink());
        title.setTag("fg:ink");
        root.addView(title);

        TextView sub = new TextView(ctx);
        sub.setText(gameName + " · 点一下开,再点一下关");
        sub.setTextSize(13f);
        sub.setTextColor(ModeManager.get().muted());
        sub.setTag("fg:muted");
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = Launchers.dp(ctx, 6);
        root.addView(sub, slp);

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        for (final CheatStore.Cheat c : cheats) col.addView(row(ctx, c, listener));

        // 条数不定(有的游戏两条,有的十几条),超过半屏就滚 —— 弹层本身不能长到出屏
        ScrollView sv = new ScrollView(ctx);
        sv.addView(col);
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.topMargin = Launchers.dp(ctx, 12);
        sv.setLayoutParams(vlp);
        root.addView(sv);
        sv.getLayoutParams().height = Math.min(
                Launchers.dp(ctx, 56) * Math.max(1, cheats.size()) + Launchers.dp(ctx, 8),
                Math.round(dm.heightPixels * 0.5f));

        LinearLayout btns = new LinearLayout(ctx);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Launchers.dp(ctx, 14);
        btns.setLayoutParams(blp);

        // 「全关」单独给一颗:开了七八条之后一条条点回去太费事,而"不对劲了先全关"
        // 是玩金手指最常做的一个动作
        TextView off = button(ctx, "全部关掉", false);
        off.setOnClickListener(v -> {
            for (CheatStore.Cheat c : cheats) c.sel = 0;
            for (int i = 0; i < col.getChildCount(); i++) {
                View row = col.getChildAt(i);
                paint(row, (CheatStore.Cheat) row.getTag());
            }
            if (listener != null) listener.onChanged();
        });
        btns.addView(off);

        TextView ok = button(ctx, "知道了", true);
        ok.setOnClickListener(v -> dialog.dismiss());
        btns.addView(ok);
        root.addView(btns);

        ModeApplier.apply(root);
        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = Math.min(Math.round(dm.widthPixels * 0.46f), Launchers.dp(ctx, 460));
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        if (onClose != null) dialog.setOnDismissListener(d -> onClose.onChanged());
        Immersive.show(dialog);   // 不走裸 show:Dialog 收焦点会把系统栏带出来
    }

    /** 一条金手指:名字 + 右边一个开关圆点。整行都能点,车上手指粗。 */
    private static View row(Context ctx, final CheatStore.Cheat c, final Listener listener) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Launchers.dp(ctx, 14), 0, Launchers.dp(ctx, 14), 0);
        row.setMinimumHeight(Launchers.dp(ctx, 48));
        row.setClickable(true);
        // 直接挂对象:ModeApplier 只认 String 类型的 tag,别的它看一眼就走(见 applyOne)
        row.setTag(c);

        TextView name = new TextView(ctx);
        name.setText(c.name);
        name.setTextSize(15f);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(name, nlp);

        TextView mark = new TextView(ctx);
        mark.setTextSize(13f);
        mark.setGravity(Gravity.CENTER);
        mark.setMinimumWidth(Launchers.dp(ctx, 52));
        mark.setPadding(Launchers.dp(ctx, 10), Launchers.dp(ctx, 4),
                Launchers.dp(ctx, 10), Launchers.dp(ctx, 4));
        row.addView(mark);

        com.liubai.blank.ui.Motion.press(row);
        row.setOnClickListener(v -> {
            // 往下轮一格,到头回「关」—— 两档的就是开关,多档的能挑
            c.sel = (c.sel + 1) % c.options.length;
            paint(row, c);
            if (listener != null) listener.onChanged();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Launchers.dp(ctx, 8);
        row.setLayoutParams(lp);
        paint(row, c);
        return row;
    }

    /** 按开关状态上色。开着的整行换成 accentSoft 底,一眼看得出哪几条在生效。 */
    private static void paint(View row, CheatStore.Cheat c) {
        Context ctx = row.getContext();
        LinearLayout r = (LinearLayout) row;
        TextView name = (TextView) r.getChildAt(0);
        TextView mark = (TextView) r.getChildAt(1);

        boolean on = c.on();
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Launchers.dp(ctx, 10));
        bg.setColor(on ? ModeManager.get().accentSoft() : ModeManager.get().tile());
        r.setBackground(bg);
        name.setTextColor(on ? ModeManager.get().accent() : ModeManager.get().ink());
        // 写的是**当前这一档的名字**,不是"开/关" —— 多档的那些("第 3 关"「无限时间」)
        // 光说"开着"等于没说
        mark.setText(c.label());
        mark.setTextColor(on ? ModeManager.get().accent() : ModeManager.get().muted());
    }

    private static TextView button(Context ctx, String text, boolean primary) {
        TextView b = new TextView(ctx);
        b.setText(text);
        b.setTextSize(16f);
        b.setGravity(Gravity.CENTER);
        b.setMinimumHeight(Launchers.dp(ctx, 48));
        b.setPadding(Launchers.dp(ctx, 22), 0, Launchers.dp(ctx, 22), 0);
        b.setTextColor(primary ? ModeManager.get().onAccent() : ModeManager.get().muted());
        b.setBackgroundResource(primary ? R.drawable.pill_accent : R.drawable.pill_line);
        b.setTag(primary ? "fg:onAccent;fill:accent" : "fg:muted;stroke:line");
        b.setClickable(true);
        com.liubai.blank.ui.Motion.press(b);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(Launchers.dp(ctx, 12));
        b.setLayoutParams(lp);
        return b;
    }
}
