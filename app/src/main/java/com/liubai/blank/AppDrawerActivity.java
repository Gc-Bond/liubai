package com.liubai.blank;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.liubai.blank.icon.IconEngine;
import com.liubai.blank.model.AppRef;
import com.liubai.blank.mode.ModeApplier;
import com.liubai.blank.mode.ModeManager;
import com.liubai.blank.ui.ConfirmPopup;
import com.liubai.blank.ui.DockHost;
import com.liubai.blank.util.Immersive;
import com.liubai.blank.util.Launchers;

import java.text.Collator;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 应用抽屉:列出全部可启动应用,点击回传给调用方。
 * 图标走 {@link IconEngine}——这里是全 app 里图标最密集的一屏,
 * 早先直接用 ri.loadIcon(pm) 等于把它排除在模式之外(实测一屏原生彩图,
 * 圆的方的满铺的都有,和桌面完全两个调性)。
 */
public class AppDrawerActivity extends AppCompatActivity {

    /**
     * 当前开着的那份抽屉实例。抽屉没有返回按钮了,关闭靠 Dock 上"全部应用"
     * 的再点一下(见 DockHost.onOpenDrawer);悬浮窗拿不到 Activity 的引用,
     * 只能通过这儿转一手。弱引用 + 生命周期清空,不拖 Activity 的后腿。
     */
    private static WeakReference<AppDrawerActivity> sOpen;

    /**
     * 「这一趟是用户要**开一个应用**」,而不是给某个位置挑一个。
     *
     * 抽屉服务四种调用:开应用(桌面 onOpenDrawer)、挑 Dock 应用位、挑地图/音乐源、
     * 挑外观面板那两张名单。只有第一种是启动语义 —— 也只有它该看见留白自己的
     * 内部页面(浏览器、游戏中心,见 {@link #injectInternal()})。
     *
     * 不区分的话,浏览器会出现在「音乐源」候选里、游戏中心能被摆进画中画卡位,
     * 而它们既不是音乐也不是地图,选中只会得到一个坏掉的卡位。
     */
    public static final String EXTRA_FOR_LAUNCH = "for_launch";

    /** 顶栏左侧的三颗筛选按钮:全部应用 / 本机应用 / 下载应用,单选。 */
    private enum FilterMode { ALL, SYSTEM, USER }

    private final List<ResolveInfo> apps = new ArrayList<>();
    private final List<ResolveInfo> filtered = new ArrayList<>();
    private FilterMode filterMode = FilterMode.ALL;
    /** 这一趟是不是「开应用」,见 {@link #EXTRA_FOR_LAUNCH}。 */
    private boolean forLaunch;
    /**
     * 刚交给系统卸载页的那个包。卸不卸得成由用户在系统页上定,
     * 回到抽屉时查一下它还在不在,没了才重建列表(见 {@link #onResume()})。
     */
    private String pendingUninstall;
    private PackageManager pm;
    private IconEngine icons;
    private TextView filterAll;
    private TextView filterSystem;
    private TextView filterUser;
    private AppAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_drawer);
        // 顶部让位给常驻顶栏(悬浮窗不占布局空间,见 StatusBarHost.applyTopSpace)
        com.liubai.blank.ui.StatusBarHost.applyTopSpace(findViewById(android.R.id.content));
        pm = getPackageManager();
        icons = new IconEngine(this);
        forLaunch = getIntent() != null && getIntent().getBooleanExtra(EXTRA_FOR_LAUNCH, false);

        loadApps();

        GridView grid = findViewById(R.id.app_grid);
        // 列数按屏宽算,不写死 —— 6 列在 1920 宽的横屏上每列近 200dp,图标才 52dp,
        // 左右空一大片(用户说的"左右间隔太大");宽屏多给几列、间距 6dp,上下保持原样
        int sp = Launchers.dp(this, 6);
        int avail = getResources().getDisplayMetrics().widthPixels
                - Launchers.dp(this, 56) + sp;   // 56 = 页面左右 padding 28dp*2
        grid.setNumColumns(Math.max(6, Math.round(avail / Launchers.dp(this, 141f))));
        adapter = new AppAdapter();
        grid.setAdapter(adapter);
        // 两种模式都只是"把选中的 app 回传给调用方",区别在调用方拿它做什么:
        // 桌面拿去决定全屏还是画中画,设置页拿去当音乐源。抽屉自己不启动 app——
        // 它不知道两张卡的状态,自作主张启动就绕过了桌面的决策。
        grid.setOnItemClickListener((parent, view, position, id) -> returnApp(filtered.get(position)));
        // 长按开菜单只给「开应用」这一趟(跟 injectInternal 同一道门):挑 Dock 位、
        // 挑地图/音乐源那几趟是在选一个值,长按卸载没道理。
        // **必须 return true**,否则点击那条跟着触发,app 被回传出去、抽屉当场关掉。
        if (forLaunch) {
            grid.setOnItemLongClickListener((parent, view, position, id) -> {
                showAppMenu(filtered.get(position));
                return true;
            });
        }

        filterAll = findViewById(R.id.filter_all);
        filterSystem = findViewById(R.id.filter_system);
        filterUser = findViewById(R.id.filter_user);
        filterAll.setOnClickListener(v -> setFilterMode(FilterMode.ALL));
        filterSystem.setOnClickListener(v -> setFilterMode(FilterMode.SYSTEM));
        filterUser.setOnClickListener(v -> setFilterMode(FilterMode.USER));
        applyFilter();

        ModeApplier.apply(findViewById(android.R.id.content));
    }

    /** 关掉当前开着的抽屉;没开就不动作。给 Dock 的"再点一下关闭"用。 */
    public static void closeOpen() {
        AppDrawerActivity a = sOpen == null ? null : sOpen.get();
        if (a != null) a.finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        sOpen = new WeakReference<>(this);
        refreshAfterUninstall();
        DockHost.get(this).onDrawerResumed(true);   // 见 DockHost.drawerResumed 的说明
        // 跟 HomeActivity 一样压住系统导航栏(见 Immersive 类注释:每个 Activity
        // 独立生效),不然应用抽屉这页会露出车机自带的那条系统栏。
        getWindow().getDecorView().post(() -> Immersive.apply(this));
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sOpen != null && sOpen.get() == this) sOpen = null;
        DockHost.get(this).onDrawerResumed(false);
    }

    @Override
    protected void onDestroy() {
        if (sOpen != null && sOpen.get() == this) sOpen = null;
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Immersive.apply(this);
    }

    private void loadApps() {
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = pm.queryIntentActivities(main, 0);
        // 只排除桌面自己(HomeActivity)——不是排除整个包名。
        String homeClass = HomeActivity.class.getName();
        apps.clear();
        for (ResolveInfo ri : list) {
            if (ri.activityInfo == null || homeClass.equals(ri.activityInfo.name)) continue;
            apps.add(ri);
        }
        if (forLaunch) {
            injectInternal();
        }
        sortByLabel();
    }

    /**
     * 把留白自己的内部页面补进列表。
     *
     * 浏览器和游戏中心刻意没声明 LAUNCHER(否则原厂 DiLink 桌面也会长出图标,
     * 见 AndroidManifest 里那两条注释),所以上面那句 queryIntentActivities 查不到它们,
     * 得自己包一条 ResolveInfo 塞进来 —— 包好之后跟外部 app 走的是同一套代码:
     * 一起排序、一起显示、选中一样回传 pkg+cls。
     */
    private void injectInternal() {
        Class<?>[] internal = { BrowserActivity.class, com.liubai.blank.game.GameActivity.class };
        for (Class<?> c : internal) {
            try {
                ResolveInfo ri = new ResolveInfo();
                ri.activityInfo = pm.getActivityInfo(new ComponentName(this, c), 0);
                apps.add(ri);
            } catch (PackageManager.NameNotFoundException e) {
                // 编进来的类不可能查不到,真查不到就是打包出了问题,少一条比崩了强
            }
        }
    }

    private void sortByLabel() {
        final Collator collator = Collator.getInstance(Locale.CHINA);
        Collections.sort(apps, new Comparator<ResolveInfo>() {
            @Override public int compare(ResolveInfo a, ResolveInfo b) {
                return collator.compare(a.loadLabel(pm).toString(), b.loadLabel(pm).toString());
            }
        });
    }

    /** 点某颗筛选按钮切过去;重复点当前那颗不动作。 */
    private void setFilterMode(FilterMode mode) {
        if (filterMode == mode) return;
        filterMode = mode;
        applyFilter();
    }

    /**
     * 按当前筛选重建 {@link #filtered} 并刷新网格和 pill 外观。
     * 全量 {@link #apps} 不动——切回「全部应用」时还要原样拿回来,
     * 别在筛选里把数据弄丢了。
     */
    private void applyFilter() {
        filtered.clear();
        for (ResolveInfo ri : apps) {
            boolean system = isSystemApp(ri);
            if (filterMode == FilterMode.ALL
                    || (filterMode == FilterMode.SYSTEM && system)
                    || (filterMode == FilterMode.USER && !system)) {
                filtered.add(ri);
            }
        }
        adapter.notifyDataSetChanged();

        styleFilter(filterAll, filterMode == FilterMode.ALL);
        styleFilter(filterSystem, filterMode == FilterMode.SYSTEM);
        styleFilter(filterUser, filterMode == FilterMode.USER);
    }

    /** 选中的那颗 accent 高亮,没选中的 muted 描边。 */
    private void styleFilter(TextView btn, boolean active) {
        btn.setTextColor(active ? ModeManager.get().accent() : ModeManager.get().muted());
        btn.setBackgroundResource(active ? R.drawable.pill_accent : R.drawable.pill_line);
        btn.setTag(active ? "fg:accent;fill:accentSoft;stroke:accent" : "fg:muted;stroke:line");
        ModeApplier.apply(btn);
    }

    /**
     * 本机自带 = 出厂就在系统里(含系统更新过的),不是用户装的。
     * {@link ApplicationInfo#FLAG_SYSTEM} 之外的
     * {@link ApplicationInfo#FLAG_UPDATED_SYSTEM_APP} 是出厂应用后来走系统更新
     * 升过级的,也算自带;只查 FLAG_SYSTEM 会把它们误判成用户下载的。
     */
    private boolean isSystemApp(ResolveInfo ri) {
        try {
            ApplicationInfo ai = pm.getApplicationInfo(ri.activityInfo.packageName, 0);
            return (ai.flags & (ApplicationInfo.FLAG_SYSTEM
                    | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * 长按某个应用:开菜单(应用信息 / 卸载),不直接卸载。
     *
     * 卸载这一步不自己再加二次确认 —— 系统卸载页本身就是一次确认,
     * 连着问两遍只是多点一下。
     */
    private void showAppMenu(ResolveInfo ri) {
        final String pkg = ri.activityInfo.packageName;
        String label = ri.loadLabel(pm).toString();
        ConfirmPopup.OnConfirm appInfo = () -> Launchers.start(this,
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + pkg)),
                "无法打开应用信息");
        // 卸不掉的(留白自己、出厂系统应用)干脆不给这个按钮,而不是点了才说不行。
        // 更不用 toast:Android 12 起 toast 一律压在 Dock 上,桌面这边的操作看不见反馈。
        if (!canUninstall(pkg)) {
            ConfirmPopup.show(this, label, pkg, "应用信息", appInfo);
            return;
        }
        ConfirmPopup.show(this, label, pkg, "卸载", () -> {
            pendingUninstall = pkg;
            Launchers.start(this, new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg)),
                    "无法卸载该应用");
        }, "应用信息", appInfo, null);
    }

    /**
     * 这个包能不能卸。
     *
     * 出厂就在系统里的卸不掉(系统卸载页会直接拒),但**升过级的出厂应用可以** ——
     * 那一趟卸的是更新、退回出厂版本,系统认这个操作。所以这儿不能直接复用
     * {@link #isSystemApp(ResolveInfo)}:它把「升过级的出厂应用」也算作系统自带,
     * 那是给筛选按钮分类用的口径,拿来判能不能卸会多挡掉一批。
     */
    private boolean canUninstall(String pkg) {
        if (getPackageName().equals(pkg)) return false;   // 留白自己(抽屉里那两个内部页面)
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            boolean updated = (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
            return !system || updated;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * 从系统卸载页回来:那个包真没了才重建列表。
     * 用户在系统页上点了取消、或者卸的只是「更新」(包还在)的话,列表原样不动 ——
     * 无条件重载等于每次回前台都把全量 queryIntentActivities 加排序再跑一遍。
     */
    private void refreshAfterUninstall() {
        if (pendingUninstall == null) return;
        String pkg = pendingUninstall;
        pendingUninstall = null;
        try {
            pm.getPackageInfo(pkg, 0);
        } catch (PackageManager.NameNotFoundException e) {
            loadApps();
            applyFilter();
        }
    }

    /** 把选中的 app 回传给调用方,自己退场。 */
    private void returnApp(ResolveInfo ri) {
        String pkg = ri.activityInfo.packageName;
        String cls = ri.activityInfo.name;
        String label = ri.loadLabel(pm).toString();
        Intent data = new Intent();
        data.putExtra("pkg", pkg);
        data.putExtra("cls", cls);
        data.putExtra("label", label);
        setResult(RESULT_OK, data);
        finish();
    }

    private class AppAdapter extends BaseAdapter {
        private final LayoutInflater inflater = LayoutInflater.from(AppDrawerActivity.this);

        @Override public int getCount() { return filtered.size(); }
        @Override public Object getItem(int position) { return filtered.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView : inflater.inflate(R.layout.item_app, parent, false);
            ResolveInfo ri = filtered.get(position);
            String label = ri.loadLabel(pm).toString();
            AppRef app = new AppRef(ri.activityInfo.packageName, ri.activityInfo.name, label);
            ((ImageView) v.findViewById(R.id.app_icon)).setImageDrawable(icons.iconFor(app));
            ((TextView) v.findViewById(R.id.app_label)).setText(label);
            ModeApplier.apply(v);
            return v;
        }
    }
}
