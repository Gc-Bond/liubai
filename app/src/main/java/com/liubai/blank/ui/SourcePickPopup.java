package com.liubai.blank.ui;

import android.app.Dialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.liubai.blank.R;
import com.liubai.blank.icon.IconEngine;
import com.liubai.blank.model.AppRef;
import com.liubai.blank.mode.ModeApplier;
import com.liubai.blank.mode.ModeManager;
import com.liubai.blank.util.Immersive;
import com.liubai.blank.util.Launchers;

import java.util.List;

/**
 * 长按地图/音乐键弹出的「换一个」列表。
 *
 * 为什么不进设置页:换地图和换音乐是当场就想切的事(这条路不好用、换一个试试),
 * 跑一趟设置页再回来太远。和长按设置键就地改 Dock 是同一条规矩——改什么就在哪儿改。
 *
 * 结构照 {@link ConfirmPopup} 来,保持弹窗风格一致(纯代码构建、popup_bg、54dp 行高)。
 */
public final class SourcePickPopup {

    public interface Callback {
        /** 选了候选里的某个。 */
        void onPick(String pkg);
        /** 候选里没有想要的,去「全部应用」里挑。 */
        void onPickFromAll();
    }

    /**
     * 「全部应用」那一格显不显示。**暂时关掉**(2026-07-31,用户拍板)——
     * 自动识别(见 {@link com.liubai.blank.data.AppTypes})已经能把车上的地图/音乐
     * 都列出来,再挂一格入口反而是噪声。
     *
     * 留开关不删代码:识别靠的是包名关键词,换个车型可能就漏(自研包名、
     * 冷门 app),那时手动挑这条路还得放回来。{@link #fromAllTile} 一并留着,
     * 打开这个开关就能恢复,不用重写。
     */
    private static final boolean SHOW_FROM_ALL = false;

    private SourcePickPopup() {}

    public static void show(final Context ctx, String title, List<String> candidates,
                            String current, IconEngine icons, final Callback cb) {
        final Dialog dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        int pad = Launchers.dp(ctx, 18);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.popup_bg);
        root.setTag("fill:tile2;stroke:line;panel");
        root.setPadding(pad, pad, pad, Launchers.dp(ctx, 10));

        TextView head = new TextView(ctx);
        head.setText(title);
        head.setTag("fg:ink");
        head.setTextColor(ModeManager.get().ink());
        head.setTextSize(18f);
        Launchers.medium(head);
        int hp = Launchers.dp(ctx, 6);
        head.setPadding(hp, 0, hp, Launchers.dp(ctx, 12));
        root.addView(head);

        View line = new View(ctx);
        line.setBackgroundColor(ModeManager.get().line());
        root.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Launchers.dp(ctx, 0.7f))));

        // 横着排、可左右滑 —— 跟 Dock 上调风量那条一个手势模型:同类项一字排开,
        // 扫一眼就看完,不用竖着读一串文字。车上一只手能覆盖的范围本来也是横向的。
        HorizontalScrollView scroll = new HorizontalScrollView(ctx);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFillViewport(true);   // 候选少时让 strip 撑满,好居中

        LinearLayout strip = new LinearLayout(ctx);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setPadding(0, Launchers.dp(ctx, 6), 0, 0);
        strip.setGravity(Gravity.CENTER);

        for (final String pkg : candidates) {
            strip.addView(tile(ctx, icons, pkg, label(ctx, pkg), pkg.equals(current),
                    new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            dialog.dismiss();
                            cb.onPick(pkg);
                        }
                    }));
        }

        if (SHOW_FROM_ALL) {
            strip.addView(fromAllTile(ctx, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    dialog.dismiss();
                    cb.onPickFromAll();
                }
            }));
        }

        scroll.addView(strip, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ModeApplier.apply(root);
        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            // 横排比竖列表宽:放得下五六格,再多就左右滑
            int width = Math.min(Math.round(dm.widthPixels * 0.62f), Launchers.dp(ctx, 620));
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            // Dock 在屏幕下缘,弹窗贴着它上方出来,视线不用跑
            w.setGravity(Gravity.BOTTOM);
            android.view.WindowManager.LayoutParams lp = w.getAttributes();
            lp.y = Launchers.dockSpace(ctx);   // 和空调面板、气泡同一条线
            w.setAttributes(lp);
        }
        Immersive.show(dialog);   // 不走裸 show:Dialog 收焦点会把系统栏带出来
    }

    /** 横排一格的宽度。放得下两行中文 app 名,又能在一屏里露出五六个。 */
    private static final int TILE_W_DP = 88;

    /** 一格候选:图标在上、名字在下,当前用的那个用 accent 标出来。 */
    private static View tile(Context ctx, IconEngine icons, String pkg, String label,
                             boolean current, View.OnClickListener onClick) {
        LinearLayout t = new LinearLayout(ctx);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        int p = Launchers.dp(ctx, 10);
        t.setPadding(p, p, p, Launchers.dp(ctx, 8));

        ImageView ic = new ImageView(ctx);
        ic.setImageDrawable(icons.iconFor(new AppRef(pkg, "", label)));
        int is = Launchers.dp(ctx, 42);
        t.addView(ic, new LinearLayout.LayoutParams(is, is));

        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTag(current ? "fg:accent" : "fg:ink");
        tv.setTextColor(current ? ModeManager.get().accentText() : ModeManager.get().ink());
        tv.setTextSize(12f);
        tv.setGravity(Gravity.CENTER);
        tv.setMaxLines(2);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = Launchers.dp(ctx, 8);
        t.addView(tv, tlp);

        ripple(ctx, t);
        t.setOnClickListener(onClick);
        t.setLayoutParams(new LinearLayout.LayoutParams(
                Launchers.dp(ctx, TILE_W_DP), ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    /** 横排最后一格:去「全部应用」里挑。 */
    private static View fromAllTile(Context ctx, View.OnClickListener onClick) {
        LinearLayout t = new LinearLayout(ctx);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        int p = Launchers.dp(ctx, 10);
        t.setPadding(p, p, p, Launchers.dp(ctx, 8));

        ImageView ic = new ImageView(ctx);
        ic.setImageResource(R.drawable.ic_apps);
        ic.setTag("tint:muted");
        ic.setColorFilter(ModeManager.get().muted());
        int is = Launchers.dp(ctx, 42);
        int ip = Launchers.dp(ctx, 9);   // 这个图标是线性图形,不像 app 图标自带留白
        ic.setPadding(ip, ip, ip, ip);
        t.addView(ic, new LinearLayout.LayoutParams(is, is));

        TextView tv = new TextView(ctx);
        tv.setText("全部应用");
        tv.setTag("fg:muted");
        tv.setTextColor(ModeManager.get().muted());
        tv.setTextSize(12f);
        tv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = Launchers.dp(ctx, 8);
        t.addView(tv, tlp);

        ripple(ctx, t);
        t.setOnClickListener(onClick);
        t.setLayoutParams(new LinearLayout.LayoutParams(
                Launchers.dp(ctx, TILE_W_DP), ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    private static void ripple(Context ctx, View row) {
        TypedValue attr = new TypedValue();
        ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, attr, true);
        if (attr.resourceId != 0) row.setForeground(ctx.getDrawable(attr.resourceId));
    }

    private static String label(Context ctx, String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }
}
