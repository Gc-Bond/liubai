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
import com.liubai.blank.util.Immersive;
import com.liubai.blank.util.Launchers;

/**
 * 一句话确认弹层:标题 + 说明 + 「取消 / 确定」。
 *
 * 纯代码构建,风格与 {@link FolderPopup} 一致。
 *
 * **不用系统 AlertDialog**:实测它配 {@link Immersive#show} 之后按钮点不动 ——
 * 那套先 NOT_FOCUSABLE 显示、再 clearFlags 的做法对自绘 Dialog 好使,
 * 但 AlertDialog 的按钮在 clearFlags 之后收不到触摸(点下去零反应、零日志)。
 * 而 Immersive.show 这一步又省不掉,不走它系统栏就会被弹层带出来盖在桌面上。
 */
public final class ConfirmPopup {

    public interface OnConfirm {
        void run();
    }

    private ConfirmPopup() {}

    public static void show(final Context ctx, String title, String message,
                            String confirmText, final OnConfirm onConfirm) {
        show(ctx, title, message, confirmText, onConfirm, null, null, null);
    }

    /**
     * 取消也要做点什么的版本。
     *
     * **取消不总是"什么也没发生"** —— 进门的安全确认就是:不点"车已停好"就该退出去,
     * 而不是留在原地(见 {@code GameActivity.confirmSafety})。顺带能改取消按钮的字,
     * 那种场合写「退出」比「取消」准确。
     *
     * @param cancelText 取消按钮写什么,null 用默认的「取消」
     * @param onCancel   点取消(或按返回键关掉)之后做什么,可为 null
     */
    public static void show(final Context ctx, String title, String message,
                            String confirmText, final OnConfirm onConfirm,
                            String cancelText, final OnConfirm onCancel) {
        show(ctx, title, message, confirmText, onConfirm, null, null, null,
                cancelText, onCancel);
    }

    /**
     * 带第二个动作的版本:[次动作] [主动作] [取消]。
     *
     * 给"换模式时要不要一并用它自带的壁纸"这类问题用 —— 那不是"确定/取消",
     * 是两条都成立的路(一并应用 / 只换配色),用两个动作按钮说清楚,比塞个勾选框强:
     * 车上手指粗、还在动,勾选框那么小点不准,而且勾了没勾一眼看不出来。
     *
     * @param altText 次动作的文字;传 null 就退化成普通的确定/取消
     * @param preview 夹在正文和按钮之间的一块自定义视图(比如壁纸缩略图),可为 null
     */
    public static void show(final Context ctx, String title, String message,
                            String confirmText, final OnConfirm onConfirm,
                            String altText, final OnConfirm onAlt, View preview) {
        show(ctx, title, message, confirmText, onConfirm, altText, onAlt, preview, null, null);
    }

    private static void show(final Context ctx, String title, String message,
                             String confirmText, final OnConfirm onConfirm,
                             String altText, final OnConfirm onAlt, View preview,
                             String cancelText, final OnConfirm onCancel) {
        final Dialog dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        int pad = Launchers.dp(ctx, 20);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.popup_bg);
        root.setTag("fill:tile2;stroke:line;panel");
        root.setPadding(pad, pad, pad, Launchers.dp(ctx, 12));
        // **根容器必须自己吃掉触摸**:Immersive.show 先挂 FLAG_NOT_FOCUSABLE 再清,
        // 而这个 flag 会隐含带上 NOT_TOUCH_MODAL 且清不干净 —— 落在弹层上却没人消费的
        // 触摸会直接穿到下面的界面去。实测就穿过去点着了弹层背后的另一张模式卡:
        // 第一个确认框被换掉、又弹出一个问你换不换另一套的框。
        root.setClickable(true);
        root.setFocusable(true);

        TextView t = new TextView(ctx);
        t.setText(title);
        t.setTextSize(20f);
        Launchers.medium(t);
        t.setTextColor(ModeManager.get().ink());
        root.addView(t);

        TextView m = new TextView(ctx);
        m.setText(message);
        m.setTextSize(14f);
        m.setLineSpacing(Launchers.dp(ctx, 4), 1f);
        m.setTextColor(ModeManager.get().muted());
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = Launchers.dp(ctx, 10);
        root.addView(m, mlp);

        if (preview != null) {
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            plp.topMargin = Launchers.dp(ctx, 14);
            root.addView(preview, plp);
        }

        // 按钮行:取消在左、确定在右,右下角是拇指最顺手的位置
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = Launchers.dp(ctx, 18);
        row.setLayoutParams(rlp);

        // 取消这条:点按钮和按返回键走同一个回调 —— 安全确认那种场合,
        // 返回键要是能绕过去(留在页面里),二次确认就白做了
        final boolean[] confirmed = { false };
        row.addView(button(ctx, cancelText != null ? cancelText : "取消", false,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { dialog.dismiss(); }
                }));
        if (onCancel != null) {
            dialog.setOnDismissListener(d -> { if (!confirmed[0]) onCancel.run(); });
        }
        if (altText != null && onAlt != null) {
            row.addView(button(ctx, altText, false, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    dialog.dismiss();
                    onAlt.run();
                }
            }));
        }
        row.addView(button(ctx, confirmText, true, new View.OnClickListener() {
            @Override public void onClick(View v) {
                confirmed[0] = true;
                dialog.dismiss();
                onConfirm.run();
            }
        }));
        root.addView(row);

        ModeApplier.apply(root);
        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            int width = Math.min(Math.round(dm.widthPixels * 0.42f), Launchers.dp(ctx, 460));
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            // preview 里塞了输入框的话,弹层要往上挪。
            // **居中会被软键盘吃掉下半截** —— 键盘占掉下半屏,而按钮行正好在弹层底部,
            // 于是「确定」按钮藏在键盘底下,点下去点的是键盘(2026-08-29 模拟器实测撞到)。
            // ADJUST_PAN 只保证「焦点那一行」可见,救不了它下面的按钮,所以直接顶到上方去。
            if (hasEditText(preview)) {
                w.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
                android.view.WindowManager.LayoutParams lp = w.getAttributes();
                lp.y = Launchers.dp(ctx, 48);
                w.setAttributes(lp);
            }
        }
        Immersive.show(dialog);   // 不走裸 show:Dialog 收焦点会把系统栏带出来
    }

    /** preview 里有没有输入框(可能被包在容器里)。有的话弹层要避开软键盘,见调用处。 */
    private static boolean hasEditText(View v) {
        if (v == null) return false;
        if (v instanceof android.widget.EditText) return true;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (hasEditText(g.getChildAt(i))) return true;
            }
        }
        return false;
    }

    /** 一个按钮。车机手指操作,高度给到 48dp、左右留够。 */
    private static View button(Context ctx, String text, boolean primary,
                               View.OnClickListener onClick) {
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
        b.setFocusable(true);
        Motion.press(b);
        b.setOnClickListener(onClick);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(Launchers.dp(ctx, 12));
        b.setLayoutParams(lp);
        return b;
    }
}
