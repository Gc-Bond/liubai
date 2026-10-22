package com.liubai.blank.data;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import com.liubai.blank.BrowserActivity;
import com.liubai.blank.game.GameActivity;
import com.liubai.blank.model.AppRef;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 应用数据源:查询全部可启动应用、判是否安装。
 * 与 AppDrawerActivity 同一套 MAIN/LAUNCHER 查询,这里抽成可复用的仓库。
 */
public final class AppRepository {

    private final Context ctx;
    private final PackageManager pm;
    private final String self;

    public AppRepository(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.pm = this.ctx.getPackageManager();
        this.self = ctx.getPackageName();
    }

    /** 全部可启动 app(排除自己),按中文名排序。 */
    public List<AppRef> queryLaunchable() {
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = pm.queryIntentActivities(main, 0);

        List<AppRef> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : list) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (self.equals(pkg)) continue;
            String cls = ri.activityInfo.name;
            String key = pkg + "/" + cls;
            if (!seen.add(key)) continue;
            out.add(new AppRef(pkg, cls, ri.loadLabel(pm).toString()));
        }

        // 内部页面(浏览器/游戏中心)刻意不声明 LAUNCHER,上面查不到 —— 但它们是
        // "全部应用"的一部分:编辑面板往应用位加、桌面往卡片池里挑,都该能选到,
        // 在这里手动补一条。**抽屉不蹭这条**:AppDrawerActivity 仍按 EXTRA_FOR_LAUNCH
        // 单独注入(见它那边的说明),挑源/挑名单的调用不能被浏览器污染。
        appendInternal(out);

        final Collator collator = Collator.getInstance(Locale.CHINA);
        Collections.sort(out, new Comparator<AppRef>() {
            @Override public int compare(AppRef a, AppRef b) {
                return collator.compare(a.label, b.label);
            }
        });
        return out;
    }

    /**
     * 补内部页面。包名是自己,上面那句 self.equals(pkg) 会把整个包都排除掉,
     * 必须显式加回来;cls 带全,图标才有得取(IconEngine 优先 activity 图标)。
     */
    private void appendInternal(List<AppRef> out) {
        Class<?>[] internal = { BrowserActivity.class, GameActivity.class };
        for (Class<?> c : internal) {
            try {
                ActivityInfo ai = pm.getActivityInfo(new ComponentName(ctx, c), 0);
                out.add(new AppRef(self, ai.name, ai.loadLabel(pm).toString()));
            } catch (PackageManager.NameNotFoundException e) {
                // 编进来的类不可能查不到,真查不到就是打包出了问题,少一条比崩了强
            }
        }
    }

    public boolean isInstalled(String pkg) {
        try {
            pm.getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
