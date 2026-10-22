package com.liubai.blank.ui;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.liubai.blank.R;
import com.liubai.blank.icon.IconEngine;
import com.liubai.blank.model.AppRef;
import com.liubai.blank.model.Folder;
import com.liubai.blank.mode.ModeApplier;
import com.liubai.blank.mode.Dim;
import com.liubai.blank.mode.ModeManager;
import com.liubai.blank.util.Immersive;
import com.liubai.blank.util.Launchers;

/**
 * 小文件夹弹层:铺开内含 app,点击启动;底部「展开为大文件夹」把它变成常驻大文件夹。
 * 纯代码构建,不依赖 XML,风格与 DesktopView 一致。
 */
public final class FolderPopup {

    public interface Callback {
        void onLaunch(AppRef app);
        void onExpand(int index);
    }

    private FolderPopup() {}

    public static void show(final Context ctx, final int index, final Folder folder,
                            IconEngine icons, final Callback cb) {
        final Dialog dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        int pad = Launchers.dp(ctx, 22);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.popup_bg);
        root.setTag("fill:tile2;stroke:line;panel");
        root.setPadding(pad, pad, pad, pad);

        // 头部
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(ctx);
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        gd.setColor(ModeManager.get().accent());
        dot.setBackground(gd);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(Launchers.dp(ctx, 7), Launchers.dp(ctx, 7));
        dlp.setMarginEnd(Launchers.dp(ctx, 8));
        head.addView(dot, dlp);
        TextView title = new TextView(ctx);
        title.setText(folder.name);
        title.setTextColor(ModeManager.get().ink());
        title.setTextSize(20f);
        Launchers.medium(title);
        head.addView(title);
        TextView cnt = new TextView(ctx);
        cnt.setText(folder.apps.size() + " 个应用");
        cnt.setTextColor(ModeManager.get().muted());
        cnt.setTextSize(12f);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMarginStart(Launchers.dp(ctx, 8));
        head.addView(cnt, clp);
        root.addView(head);

        // 应用网格 4 列
        GridLayout grid = new GridLayout(ctx);
        int cols = 4;
        grid.setColumnCount(cols);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.topMargin = Launchers.dp(ctx, 16);
        root.addView(grid, glp);

        int tile = Launchers.dp(ctx, 56);
        for (int k = 0; k < folder.apps.size(); k++) {
            final AppRef a = folder.apps.get(k);
            LinearLayout box = new LinearLayout(ctx);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            int bp = Launchers.dp(ctx, 6);
            box.setPadding(bp, bp, bp, bp);

            FrameLayout mg = new FrameLayout(ctx);
            mg.setBackgroundResource(R.drawable.mini_cell_bg);
            mg.setTag("fill:tile2");
            ImageView mi = new ImageView(ctx);
            mi.setImageDrawable(icons.iconFor(a));
            int ms = Math.round(tile * 0.52f);
            FrameLayout.LayoutParams milp = new FrameLayout.LayoutParams(ms, ms);
            milp.gravity = Gravity.CENTER;
            mg.addView(mi, milp);
            box.addView(mg, new LinearLayout.LayoutParams(tile, tile));

            TextView ml = new TextView(ctx);
            ml.setText(a.label);
            ml.setTextColor(ModeManager.get().muted());
            ml.setTextSize(12f);
            ml.setSingleLine(true);
            ml.setEllipsize(android.text.TextUtils.TruncateAt.END);
            ml.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams mllp = new LinearLayout.LayoutParams(
                    tile + Launchers.dp(ctx, 6), ViewGroup.LayoutParams.WRAP_CONTENT);
            mllp.topMargin = Launchers.dp(ctx, 4);
            box.addView(ml, mllp);

            GridLayout.LayoutParams cl = new GridLayout.LayoutParams(
                    GridLayout.spec(k / cols), GridLayout.spec(k % cols));
            cl.setMargins(Launchers.dp(ctx, 4), Launchers.dp(ctx, 4), Launchers.dp(ctx, 4), Launchers.dp(ctx, 4));
            ripple(ctx, box);
            box.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { cb.onLaunch(a); dialog.dismiss(); }
            });
            grid.addView(box, cl);
        }

        // 底部:展开为大文件夹 / 关闭
        LinearLayout foot = new LinearLayout(ctx);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.topMargin = Launchers.dp(ctx, 18);
        root.addView(foot, flp);

        MaterialButton expand = new MaterialButton(ctx);   // 默认填充式:自动取 colorPrimary(琥珀)+ 水波纹
        expand.setText("展开为大文件夹");
        expand.setTextSize(14f);
        expand.setCornerRadius(Math.round(ModeManager.get().dimPx(ctx, Dim.RADIUS_KEY)));
        expand.setBackgroundTintList(ColorStateList.valueOf(ModeManager.get().accent()));
        expand.setTextColor(ModeManager.get().onAccent());
        expand.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { cb.onExpand(index); dialog.dismiss(); }
        });
        foot.addView(expand);

        View spacer = new View(ctx);
        foot.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        MaterialButton close = new MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        close.setText("关闭");
        close.setTextSize(14f);
        close.setCornerRadius(Math.round(ModeManager.get().dimPx(ctx, Dim.RADIUS_KEY)));
        close.setTextColor(ModeManager.get().muted());
        close.setStrokeColor(ColorStateList.valueOf(ModeManager.get().line()));
        close.setRippleColor(ColorStateList.valueOf(0x33FFFFFF));
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dialog.dismiss(); }
        });
        foot.addView(close);

        ModeApplier.apply(root);
        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            int width = Math.min(Math.round(dm.widthPixels * 0.62f), Launchers.dp(ctx, 620));
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        Immersive.show(dialog);   // 不走裸 show:Dialog 收焦点会把系统栏带出来
    }

    /** 模式水波纹前景。 */
    private static void ripple(Context ctx, View v) {
        TypedValue tv = new TypedValue();
        ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        if (tv.resourceId != 0) v.setForeground(ctx.getDrawable(tv.resourceId));
    }
}
