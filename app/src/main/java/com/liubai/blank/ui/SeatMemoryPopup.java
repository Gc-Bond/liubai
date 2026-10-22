package com.liubai.blank.ui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.liubai.blank.R;
import com.liubai.blank.mode.ModeApplier;
import com.liubai.blank.mode.ModeManager;
import com.liubai.blank.privileged.CarCtrl;
import com.liubai.blank.util.Immersive;
import com.liubai.blank.util.Launchers;

/**
 * 座椅记忆面板:方向调节 + 位置记忆。
 *
 * 照原厂「主驾位置」弹窗搬:上半是座椅示意图(座椅/座垫/靠背的方向箭头),
 * 下半是位置1/2 的记忆(短按恢复、长按保存,这里拆成「恢复/保存」两个按钮)。
 * **不带头枕、不带腿托**:护卫舰07 这两样都没装电动调节(头枕见配置
 * SETTING_LF_HEADREST_CONFIG 为 0;腿托 2026-08-22 车主确认车上没有),原厂也都不显示。
 * 两个字段 {@link CarCtrl#F_HEADREST}、{@link CarCtrl#F_LEG_VERT} 先留着,
 * 别的车型要用再加回 —— `BYDAutoSettingDevice` 里有这个字段不代表本车装了。
 *
 * **方向调节是"按住动、松开停"**:按住期间每 300ms 循环发一次方向值(1/2),
 * 松开发 0(停止)。和原厂 {@code CustomAdjustImageView} 的 onClick/onCancel 循环一致 ——
 * 方向是连续动作,循环发能兜住 CAN 侧偶发丢帧。
 *
 * 结构照 {@link ConfirmPopup}(纯代码构建、popup_bg),不用系统 AlertDialog
 * (它配 {@link Immersive#show} 之后按钮点不动,见 ConfirmPopup 类注释)。
 */
public final class SeatMemoryPopup {

    private SeatMemoryPopup() {}

    public static void show(final Context ctx) {
        final Dialog dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        int pad = Launchers.dp(ctx, 20);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.popup_bg);
        root.setTag("fill:tile2;stroke:line;panel");
        root.setPadding(pad, pad, pad, Launchers.dp(ctx, 12));
        // 同 ConfirmPopup:Immersive.show 会带出清不干净的 NOT_TOUCH_MODAL,
        // 根容器自己吃掉触摸,免得穿到背后的界面上。
        root.setClickable(true);
        root.setFocusable(true);

        // ---- 标题 + 关闭 ----
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(ctx);
        title.setText("主驾位置");
        title.setTextSize(20f);
        Launchers.medium(title);
        title.setTextColor(ModeManager.get().ink());
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = new TextView(ctx);
        close.setText("✕");
        close.setTextSize(20f);
        close.setGravity(Gravity.CENTER);
        close.setMinimumWidth(Launchers.dp(ctx, 40));
        close.setMinimumHeight(Launchers.dp(ctx, 40));
        close.setTextColor(ModeManager.get().muted());
        close.setTag("fg:muted");
        close.setOnClickListener(v -> dialog.dismiss());
        head.addView(close);
        root.addView(head);

        // ---- 方向控制区(左选部件 / 中间十字键 / 右座椅图,按住动松开停) ----
        SeatDiagramView diagram = new SeatDiagramView(ctx);
        root.addView(diagram, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 记忆区:两个位置,**长按存、短按取**(照原厂) ----
        //
        // 早先是四个键(恢复1/2 + 保存1/2)。四个键把"存"和"取"摊平在一排,看着
        // 是更直白,但按错一个就把记忆位置覆盖了 —— 原厂把保存藏在长按后面,
        // 正是因为误触的代价不对等:取错了再取一次就好,存错了原来那个位置没了。
        root.addView(divider(ctx));
        root.addView(memRow(ctx, dialog));
        root.addView(footHint(ctx));

        ModeApplier.apply(root);
        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            int width = Math.min(Math.round(dm.widthPixels * 0.72f), Launchers.dp(ctx, 760));
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        Immersive.show(dialog);   // 不走裸 show:Dialog 收焦点会把系统栏带出来
    }

    /** 记忆按钮一行:位置 1 / 位置 2。 */
    private static View memRow(Context ctx, Dialog dialog) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = Launchers.dp(ctx, 14);
        row.setLayoutParams(rlp);
        row.addView(memButton(ctx, dialog, 1));
        row.addView(memButton(ctx, dialog, 2));
        return row;
    }

    /** 一个位置键:**短按恢复、长按保存**。 */
    private static View memButton(Context ctx, final Dialog dialog, final int pos) {
        TextView b = new TextView(ctx);
        b.setText("位置" + pos);
        b.setTextSize(15f);
        b.setGravity(Gravity.CENTER);
        b.setMinimumHeight(Launchers.dp(ctx, 46));
        b.setMinimumWidth(Launchers.dp(ctx, 150));
        b.setPadding(Launchers.dp(ctx, 18), 0, Launchers.dp(ctx, 18), 0);
        b.setTextColor(ModeManager.get().accent());
        b.setBackgroundResource(R.drawable.pill_line);
        b.setTag("fg:accent;stroke:line");
        b.setClickable(true);
        b.setFocusable(true);
        Motion.press(b);
        b.setOnClickListener(v -> fire(ctx, dialog, false, pos));
        b.setOnLongClickListener(v -> { fire(ctx, dialog, true, pos); return true; });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(Launchers.dp(ctx, 10));
        lp.setMarginEnd(Launchers.dp(ctx, 10));
        b.setLayoutParams(lp);
        return b;
    }

    private static void fire(Context ctx, Dialog dialog, boolean save, int pos) {
        dialog.dismiss();
        CarCtrl.seatMemory(save, pos, (ok, x) -> {
            if (!ok) Launchers.toast(ctx, "座椅记忆" + (save ? "保存" : "恢复") + "位置" + pos + " 没发出去");
        });
    }

    /** 底部说明。长按/短按这层语义不写出来没人猜得到,原厂也在这儿写了一行。 */
    private static View footHint(Context ctx) {
        TextView t = new TextView(ctx);
        t.setText("按住方向键调节，松开停止；短按位置键恢复，长按保存当前位置");
        t.setTextSize(12f);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(ModeManager.get().muted());
        t.setTag("fg:muted");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Launchers.dp(ctx, 12);
        t.setLayoutParams(lp);
        return t;
    }

    private static View divider(Context ctx) {
        View line = new View(ctx);
        line.setBackgroundColor(ModeManager.get().line());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Launchers.dp(ctx, 0.7f)));
        lp.topMargin = Launchers.dp(ctx, 14);
        line.setLayoutParams(lp);
        return line;
    }
}
