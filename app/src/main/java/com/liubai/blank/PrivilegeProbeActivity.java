package com.liubai.blank;

import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.liubai.blank.privileged.PipHost;
import com.liubai.blank.privileged.Privileged;
import com.liubai.blank.privileged.SelfAdbPrivileged;
import com.liubai.blank.privileged.Shell;
import com.liubai.blank.privileged.ShizukuPrivileged;
import com.liubai.blank.util.Launchers;
import com.liubai.blank.mode.ModeManager;

/**
 * 提权能力探测页(开发期工具,不进正式 UI)。
 *
 * 目的:在真车上一次性问清楚——提权到底能不能用、提权之后画中画能不能跑起来。
 * 这两件事是后面所有规划的前提,靠猜没意义。
 */
public class PrivilegeProbeActivity extends AppCompatActivity {

    private static final String PIP_TARGET = "com.tencent.qqmusiccar";

    private final StringBuilder log = new StringBuilder();
    private TextView out;
    private TextureView preview;
    private PipHost pip;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        int pad = Launchers.dp(this, 16);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(ModeManager.get().bg());

        // 左:日志
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        out = new TextView(this);
        out.setTextSize(12f);
        out.setTextColor(ModeManager.get().ink());
        out.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(this);
        sv.addView(out);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.addView(button("检测提权", v -> checkPrivilege()));
        btns.addView(button("内置提权", v -> connectSelfAdb()));
        btns.addView(button("Shizuku授权", v -> {
            ShizukuPrivileged.get().requestPermission(this);
            out("已发起授权请求,授权后再点「检测提权」");
        }));
        btns.addView(button("启动画中画", v -> startPip()));
        btns.addView(button("停止", v -> { if (pip != null) pip.stop(); out("已停止画中画"); }));

        left.addView(btns, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        left.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(left, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1.1f));

        // 右:画中画预览区
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        TextView cap = new TextView(this);
        cap.setText("画中画预览(" + PIP_TARGET + ")");
        cap.setTextColor(ModeManager.get().muted());
        cap.setTextSize(12f);
        right.addView(cap);
        preview = new TextureView(this);
        right.addView(preview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(right, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        setContentView(root);

        pip = new PipHost(this);
        checkPrivilege();
    }

    private View button(String text, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(ModeManager.get().onAccent());
        t.setTextSize(14f);
        t.setBackgroundColor(ModeManager.get().accent());
        int p = Launchers.dp(this, 10);
        t.setPadding(p, p, p, p);
        t.setGravity(Gravity.CENTER);
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(Launchers.dp(this, 8));
        t.setLayoutParams(lp);
        return t;
    }

    private void checkPrivilege() {
        out("── 提权状态 ──");
        out("  当前生效通道: " + Shell.channel());

        Privileged self = SelfAdbPrivileged.get();
        out("  [内置自连] " + self.state(this) + " — " + self.describe(this));

        Privileged sz = ShizukuPrivileged.get();
        out("  [Shizuku ] " + sz.state(this) + " — " + sz.describe(this));

        if (Shell.ready()) {
            // 真正确认身份:shell 是 uid 2000,app 自己是 10xxx。
            // 只看「状态 READY」不够——拿到授权不等于调用真的有特权身份。
            out("  验证身份: " + Shell.exec("id -u").toString());
        }
    }

    /**
     * 内置提权:连本机 adb,不依赖 Shizuku app。
     * 认证要等用户在车机上点「允许」,所以是异步的,结果走 onReady 回调。
     */
    private void connectSelfAdb() {
        out("── 内置提权(自连 127.0.0.1:5555) ──");
        out("  前提:车机设置里已打开无线调试");
        out("  首次会弹「允许 USB 调试吗?」,点允许并勾「一律允许」");

        SelfAdbPrivileged self = SelfAdbPrivileged.get();
        self.onReady(() -> {
            out("  ✓ 内置提权已就绪,通道=" + Shell.channel());
            out("  验证身份: " + Shell.exec("id -u").toString());
        });
        self.requestPermission(this);
        out("  连接中…几秒后点「检测提权」看结果");
    }

    private void startPip() {
        out("── 启动画中画 ──");
        SurfaceTexture st = preview.getSurfaceTexture();
        if (st == null) { out("  预览区还没准备好,稍等再点"); return; }

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = Math.max(preview.getWidth(), 640);
        int h = Math.max(preview.getHeight(), 480);
        out("  尺寸 " + w + "x" + h + " dpi=" + dm.densityDpi);

        boolean ok = pip.start(st, w, h, dm.densityDpi, PIP_TARGET);
        out(ok ? "  ✓ 已请求;若预览区出现 app 界面即成功"
               : "  ✗ 失败 —— 多半是没提权(见 logcat Blank/Pip)");
        if (ok) {
            out("  displayId=" + pip.displayId());
            pip.attachTouch(preview);
            out("  触摸已接管,直接点预览区试试");
        }
    }

    private void out(String s) {
        log.append(s).append('\n');
        runOnUiThread(() -> out.setText(log.toString()));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (pip != null) pip.stop();
    }
}
