package com.liubai.blank.ui;

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.text.format.DateFormat;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.liubai.blank.R;
import com.liubai.blank.data.StatusBarStore;
import com.liubai.blank.mode.ModeManager;
import com.liubai.blank.model.StatusItem;
import com.liubai.blank.privileged.AirCon;
import com.liubai.blank.privileged.CarStatus;
import com.liubai.blank.privileged.Shell;
import com.liubai.blank.util.Launchers;

import java.util.Calendar;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 常驻顶栏的内容。窗口那一半在 {@link StatusBarHost}。
 *
 * <h3>项是编排出来的,不是写死的</h3>
 *
 * 放哪几项、在左边还是右边,存在 {@link StatusBarStore} 里,车主在外观面板 › 顶栏页改。
 * 照 DiStyle 那套(它的顶栏就是一条 RecyclerView + {@code CustomStatusBarItemAdapter},
 * 配 {@code CustomStatusBarFragment} 编辑页)。
 *
 * **中间那段永远空着**:真车顶部正中是语音球的开孔,项只能分在左右两串里(见
 * {@link StatusBarStore} 的说明)。
 *
 * <h3>刷新靠广播,不靠定时器</h3>
 *
 * 时间走 {@link Intent#ACTION_TIME_TICK}(系统每分钟发一次,**只能动态注册**),
 * 不自己起 Handler 轮询:轮询要么慢半拍(整点跳字晚几秒),要么为了准点而空转。
 * 蓝牙和 Wi-Fi 各自有状态广播,变了当场刷。
 *
 * 车内/车外温度、车况那几项搭在分钟这一拍上读 —— 它们走 native bus,跟车控是同一条
 * (见 {@link AirCon#readInsideTemp}),读得太密会跟车控互相抢帧。
 */
public final class StatusBarView extends FrameLayout {

    /** 项的容器。一条有序列表,左右分布靠里面的占位项(见 StatusBarStore)。 */
    private LinearLayout box;

    /** 已经铺上去的项 → 它那个 view。刷新时按项找,不用再遍历子节点。 */
    private final Map<StatusItem, View> views = new EnumMap<>(StatusItem.class);

    private StatusBarStore store;

    /** 注册着的那份广播接收器;没挂在窗口上时是 null。 */
    private BroadcastReceiver watcher;

    public StatusBarView(Context c) { this(c, null); }
    public StatusBarView(Context c, AttributeSet a) { super(c, a); }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        box = findViewById(R.id.status_items);
        store = new StatusBarStore(getContext());
        rebuild();
    }

    /**
     * 按存档把项重新铺一遍。编排改完要调一次(见 {@link StatusBarHost#onItemsChanged})。
     *
     * 整段重铺而不是增量:顶栏一共就几项、重铺一次是微秒级的,而增量要处理"挪了位置"
     * "换了边"这些情况,不值当。
     */
    public void rebuild() {
        if (box == null) return;
        box.removeAllViews();
        views.clear();
        Context ctx = getContext();
        List<StatusItem> items = store.items();
        for (StatusItem item : items) {
            if (item.isSpacer()) {
                box.addView(spacerFor(ctx, item));
                continue;
            }
            View v = item.isIcon() ? iconFor(ctx, item) : textFor(ctx, item);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.CENTER_VERTICAL;
            // 项与项之间留一道。**紧跟在占位后面的不留** —— 那一段本来就是特意空出来的,
            // 再加一道就跟用户排的间距对不上了
            boolean afterSpacer = box.getChildCount() > 0
                    && box.getChildAt(box.getChildCount() - 1) instanceof android.widget.Space;
            if (box.getChildCount() > 0 && !afterSpacer) {
                lp.setMarginStart(Launchers.dp(ctx, 10));
            }
            box.addView(v, lp);
            views.put(item, v);
        }
        refresh();
    }

    /** 占位:固定几档宽度,或者把剩下的全吃掉(弹性那档)。 */
    private View spacerFor(Context ctx, StatusItem item) {
        android.widget.Space sp = new android.widget.Space(ctx);
        LinearLayout.LayoutParams lp;
        if (item == StatusItem.SPACER_FLEX) {
            lp = new LinearLayout.LayoutParams(0, 1, 1f);
        } else {
            lp = new LinearLayout.LayoutParams(Launchers.dp(ctx, item.spacerDp()), 1);
        }
        sp.setLayoutParams(lp);
        return sp;
    }

    private View iconFor(Context ctx, StatusItem item) {
        ImageView iv = new ImageView(ctx);
        iv.setImageResource(item == StatusItem.BLUETOOTH
                ? R.drawable.ic_status_bluetooth : R.drawable.ic_status_wifi);
        // **要打 tag**:项是运行时 new 出来的,不在 XML 里 —— 不打 tag 的话
        // ModeApplier 换肤时扫不到它,换配色后顶栏会留在旧那档(见 HomeActivity.reskin)
        iv.setTag("tint:ink");
        iv.setColorFilter(ModeManager.get().ink());
        iv.setLayoutParams(new LinearLayout.LayoutParams(
                Launchers.dp(ctx, 18), Launchers.dp(ctx, 18)));
        iv.setContentDescription(item.title);
        return iv;
    }

    private View textFor(Context ctx, StatusItem item) {
        TextView tv = new TextView(ctx);
        tv.setIncludeFontPadding(false);
        // 时间是顶栏的主角:大一号、medium 字重、ink 色;别的都是配角,退到 muted
        boolean lead = item == StatusItem.TIME;
        tv.setTextSize(lead ? 16f : 12f);
        tv.setTag(lead ? "fg:ink" : "fg:muted");   // 换肤要靠它,理由同 iconFor
        tv.setTextColor(lead ? ModeManager.get().ink() : ModeManager.get().muted());
        if (lead) Launchers.medium(tv);
        // 等宽数字:读数每分钟/每几秒换一次字形,不定宽的话整块会左右抽动
        tv.setFontFeatureSettings("tnum");
        tv.setContentDescription(item.title);
        return tv;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);          // 手动改时间/对时
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        f.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        watcher = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { refresh(); }
        };
        getContext().registerReceiver(watcher, f);
        refresh();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (watcher != null) {
            try {
                getContext().unregisterReceiver(watcher);
            } catch (IllegalArgumentException ignored) {
                // 没注册上过(注册那次抛了),反注册就会抛这个,不是错误
            }
            watcher = null;
        }
    }

    /** 那道下边线的画笔。颜色现取(见 {@link #dispatchDraw}),这里只留一支。 */
    private final Paint edge = new Paint();

    /**
     * 顶栏那块"面"的下边线。
     *
     * **自己画而不是走 shape 的 stroke**:shape 描边是四边一起的,上/左/右那三条正好
     * 压在屏幕边缘上,看出来就是"顶栏没和屏幕边框贴合、四周镶了道边"(2026-08-18
     * 平板上实测)。只有下边这条是真分界 —— 它下面才是壁纸或别的 app。
     *
     * 颜色现取而不缓存:换模式、换壁纸后 line 会变,而这条线不在 ModeApplier 的
     * tag 体系里(那套只认背景 shape),缓存了就得再补一条刷新路径。Dock 那条镜像
     * 的上边线同一个写法(见 {@code DockView.drawTopEdge})。
     */
    @Override
    protected void dispatchDraw(Canvas c) {
        super.dispatchDraw(c);
        // hairline 而不是 line,**且线宽固定 1 个物理像素**(理由见 ModeManager.hairline)
        edge.setColor(ModeManager.get().hairline());
        c.drawRect(0f, getHeight() - 1f, getWidth(), getHeight(), edge);
    }

    /**
     * 全刷一遍。挂上窗口时、每分钟、蓝牙/网络状态变化时各来一次。
     *
     * **只刷铺上去的那几项**:没编排上顶栏的项压根不该去读 —— 车况那几个走 native bus,
     * 白读一趟就是跟车控抢一次帧。
     */
    public void refresh() {
        Date now = new Date();
        setText(StatusItem.TIME, DateFormat.getTimeFormat(getContext()).format(now));
        setText(StatusItem.DATE, dateText(now));
        setIconShown(StatusItem.BLUETOOTH, btOn());
        setIconShown(StatusItem.WIFI, wifiOn());
        setText(StatusItem.MEM, views.containsKey(StatusItem.MEM) ? memText() : null);
        refreshCpu();
        refreshTemp();
        refreshCarStatus();
    }

    private void setText(StatusItem item, String text) {
        View v = views.get(item);
        if (!(v instanceof TextView)) return;
        // 读不到就把这一项收起来,**不留 "--" 占位**:顶栏是常驻的,一个永远读不出数的
        // 坑位比不显示更碍眼(模拟器/平板上车况那几项就永远读不到)
        if (text == null) {
            v.setVisibility(GONE);
            return;
        }
        ((TextView) v).setText(text);
        v.setVisibility(VISIBLE);
    }

    private void setIconShown(StatusItem item, boolean on) {
        View v = views.get(item);
        if (v != null) v.setVisibility(on ? VISIBLE : GONE);
    }

    /** "8月16日 周六"。星期用中文单字,顶栏一行放得下 —— 系统给的 "Saturday" 太长。 */
    private String dateText(Date now) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(now);
        String[] week = {"日", "一", "二", "三", "四", "五", "六"};
        return (cal.get(Calendar.MONTH) + 1) + "月" + cal.get(Calendar.DAY_OF_MONTH) + "日 周"
                + week[cal.get(Calendar.DAY_OF_WEEK) - 1];
    }

    private boolean btOn() {
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        return a != null && a.isEnabled();
    }

    /**
     * 连着 Wi-Fi 没有。**问的是"连上了没",不是"开关开着没"** —— 状态栏那颗图标
     * 表达的是当前网络走的是 Wi-Fi,开着但没连上不该亮。
     */
    private boolean wifiOn() {
        ConnectivityManager cm =
                (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkInfo n = cm.getActiveNetworkInfo();
        return n != null && n.isConnected() && n.getType() == ConnectivityManager.TYPE_WIFI;
    }

    /**
     * 车内 / 车外温度。提权没就绪时先空着,连上后下一拍自己补上。
     */
    private void refreshTemp() {
        boolean wantIn = views.containsKey(StatusItem.TEMP_IN);
        boolean wantOut = views.containsKey(StatusItem.TEMP_OUT);
        if (!wantIn && !wantOut) return;
        if (!AirCon.available()) {
            setText(StatusItem.TEMP_IN, null);
            setText(StatusItem.TEMP_OUT, null);
            return;
        }
        if (wantIn) {
            AirCon.readInsideTemp((celsius, ok) ->
                    setText(StatusItem.TEMP_IN, ok ? "车内 " + celsius + "°" : null));
        }
        if (wantOut) {
            AirCon.readOutsideTemp((celsius, ok) ->
                    setText(StatusItem.TEMP_OUT, ok ? "车外 " + celsius + "°" : null));
        }
    }

    /** 距保养 / 胎压。两项共用一次读(它们在同一个 {@link CarStatus#refresh} 里)。 */
    private void refreshCarStatus() {
        if (!views.containsKey(StatusItem.MAINTENANCE) && !views.containsKey(StatusItem.TYRE)) {
            return;
        }
        CarStatus.refresh(getContext(), data -> {
            if (data == null) {
                setText(StatusItem.MAINTENANCE, null);
                setText(StatusItem.TYRE, null);
                return;
            }
            setText(StatusItem.MAINTENANCE,
                    data.maintenance == null ? null : "保养 " + data.maintenance + "km");
            // 四个轮子在顶栏放不下,报**最低的那个** —— 要看的本来就是"有没有哪个偏低"
            int low = -1;
            for (int t : data.tyre) {
                if (t < 0) continue;
                if (low < 0 || t < low) low = t;
            }
            setText(StatusItem.TYRE, low < 0 ? null : "胎压 " + low);
        });
    }

    /**
     * CPU 占用。
     *
     * <h3>为什么要走提权通道</h3>
     *
     * {@code /proc/stat} 从 Android 8 起对普通应用是禁读的(SELinux,平板实测
     * {@code Permission denied}),而占用率**只能**从它两次采样的差值算 —— 系统没给
     * 任何公开 API。所以这一项跟车控一样走 {@link Shell}:提权没就绪就显示不出来,
     * 那时这一项自己收起来(不留 "--" 占位)。
     *
     * 频率跟着顶栏的分钟拍走,一分钟一次 shell,代价可以忽略。
     */
    private long lastCpuIdle, lastCpuTotal;
    private int cpuPercent = -1;

    private static final java.util.concurrent.ExecutorService CPU_IO =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "blank-statusbar-cpu");
                t.setDaemon(true);
                return t;
            });

    private void refreshCpu() {
        if (!views.containsKey(StatusItem.CPU)) return;
        if (!Shell.ready()) {
            setText(StatusItem.CPU, null);
            return;
        }
        CPU_IO.execute(() -> {
            // **只取第一行**:整份 /proc/stat 有几十行(每个核一行 + 中断统计),
            // 而我们只用汇总那行 `cpu ` —— 而且 Shell 会把命令输出打进 logcat,
            // 全量读一分钟一次就能把日志缓冲冲掉,崩溃堆栈都留不住
            Shell.Result r = Shell.exec("head -1 /proc/stat");
            final String text = cpuTextFrom(r == null ? null : r.out);
            post(() -> setText(StatusItem.CPU, text));
        });
    }

    /** 解析 {@code /proc/stat} 的第一行,和上一次的读数比出占用率。 */
    private String cpuTextFrom(String out) {
        if (out == null) return cpuPercent < 0 ? null : "CPU " + cpuPercent + "%";
        for (String line : out.split("\n")) {
            if (!line.startsWith("cpu ")) continue;
            String[] parts = line.trim().split("\s+");
            long total = 0, idle = 0;
            try {
                for (int i = 1; i < parts.length; i++) {
                    long val = Long.parseLong(parts[i]);
                    total += val;
                    if (i == 4 || i == 5) idle += val;    // idle + iowait
                }
            } catch (NumberFormatException e) {
                return null;
            }
            long dIdle = idle - lastCpuIdle, dTotal = total - lastCpuTotal;
            if (lastCpuTotal > 0 && dTotal > 0) {
                cpuPercent = (int) (100L * (dTotal - dIdle) / dTotal);
            }
            lastCpuIdle = idle;
            lastCpuTotal = total;
            break;
        }
        // 头一次只拿到基线,还算不出占用率 —— 这一拍先空着,下一分钟就有了。
        // **不补拍**:早先这里排了个 400ms 的延迟刷新,而读不到时 cpuPercent 永远是 -1,
        // 那就成了每 400ms 一次的无限刷新(2026-08-18 当场发现,白烧 CPU)
        return cpuPercent < 0 ? null : "CPU " + cpuPercent + "%";
    }

    /** 内存占用:已用 / 总量。走 ActivityManager,不读 /proc(那个有的 ROM 不给读)。 */
    private String memText() {
        android.app.ActivityManager am = (android.app.ActivityManager)
                getContext().getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return null;
        android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        if (mi.totalMem <= 0) return null;
        long used = mi.totalMem - mi.availMem;
        return "内存 " + (100L * used / mi.totalMem) + "%";
    }
}
