package com.liubai.blank.mode;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 模式层唯一真相源(「长什么样」+「带什么能力」),与 LayoutStore(怎么放)/AppRepository(放什么)平行。
 * 全局单例;由 BlankApp.onCreate() 调 init(),早于任何视图 inflate。
 *
 * **模式是能力,配色是可换的槽位。** 这两件事在这里分开存:
 *
 *   {@link #current} —— 当前模式。带什么能力(画中画/彩蛋/车控交互)由它说了算。
 *                       开源版只有一个模式(full),能力全开,不再切换
 *   {@link #palette} —— 配色从哪个模式来。默认是 normal.json 那身浅色(它只剩配色来源这一个
 *                       用途),用户可以在配色页换成 full.json 的深色。壁纸同理,存在 WallpaperStore 里。
 *
 * 于是取值分两路:**颜色和几何走 palette,能力走 current**。视图侧不用关心这件事,
 * 它们只调 accent()/caps() 这些方法。
 *
 * <h3>删掉的:日夜自动(2026-08-02)</h3>
 * 原来有个开关,按时段把配色切成昼白(6:00-18:00)或素白(那套同日也删了)。删掉的理由是它和"配色是用户
 * 自己挑的槽位"这件事**从根上冲突**:两个 id 写死在代码里,任何第三套配色(比如完整模式)
 * 只要开着它就活不过一个整点。
 *
 * 当时是靠"手动选配色就自动关掉日夜自动"这个补丁兜着的 —— 也就是说,只要用户动过一次
 * 配色,这个功能就永久关闭。一个碰了自定义就必须让路的功能,不如没有。
 */
public final class ModeManager {

    private static final String TAG = "Blank/Mode";

    /** 唯一的模式。开源版不分档,能力全开。 */
    private static final String MODE = "full";
    /**
     * 没选过配色时用哪套:浅色(normal.json)。res 里的兜底色(colors.xml / themes.xml 的窗口底)
     * 照它定的,两边不一致冷启动会先闪一帧别的颜色。
     */
    private static final String DEFAULT_PALETTE = "normal";

    private static final String PREFS = "mode_prefs";
    private static final String KEY_PALETTE = "palette_id";
    // 日夜自动(day_night_auto)已删除,见类注释。prefs 里的旧键不再读,留着不管。
    /**
     * 自定义强调色。**全局一份,不按底色分。**
     *
     * 原来是按配色来源存的(键名拼 palette id),理由是"换了底色再用旧强调色未必还搭"。
     * 但那让用户换一次底色,之前调的强调色就凭空消失 —— 他并不知道这个值是绑在底色上的,
     * 看到的只是"我调的颜色没了"。配色现在是一整套全局设置(见 {@link #paletteMode}),
     * 强调色也就跟着全局。搭不搭由用户自己看着办,不替他决定。
     */
    private static final String KEY_ACCENT = "accent";
    /**
     * 面板底色(面板 / 弹窗 / Dock 共用的 surface)。同上,全局一份。
     * 取值只有 {@link #SURFACE_MODE} 和 {@link #SURFACE_WALLPAPER} 两个。
     */
    private static final String KEY_SURFACE = "surface_mode";
    /** 面板 / 弹窗底的不透明度(0~100)。跟上面两个一样,全局一份。 */
    private static final String KEY_PANEL_OPACITY = "panel_opacity";
    private static final String KEY_MOTION = "motion_on";

    /** 面板底色跟模式走(默认)。 */
    public static final int SURFACE_MODE = 0;
    /** 面板底色跟壁纸走 —— 从壁纸底部取色,见 {@link #setWallpaperTint}。 */
    public static final int SURFACE_WALLPAPER = 1;

    private static ModeManager instance;

    private final Context appCtx;
    /** 当前模式:能力和身份的来源。 */
    private Mode current;
    /** 配色来源。多数时候 == current,用户单独换过才不同。 */
    private Mode palette;
    /** 用户自定义的强调色;0 = 没改过。全局一份,换底色不受影响。 */
    private int accentOverride;
    /** 面板底色设置:SURFACE_MODE 或 SURFACE_WALLPAPER。 */
    private int surfaceMode = SURFACE_MODE;
    /** 壁纸底部取出来的色,由外部注入(见 setWallpaperTint)。 */
    private int wallpaperTint;
    /** 面板/弹窗底的不透明度(0~100),100 = 实色。缓存在内存里 —— 自绘的气泡每帧都要读它。 */
    private int panelOpacity = 100;

    private ModeManager(Context appCtx, Mode t, Mode palette) {
        this.appCtx = appCtx;
        this.current = t;
        this.palette = palette != null ? palette : t;
        loadAccentOverride();
        android.content.SharedPreferences p = prefs();
        if (p != null) panelOpacity = p.getInt(KEY_PANEL_OPACITY, 100);
    }

    /** 读自定义项(强调色 / 面板底色)。全局一份,换底色不用重读 —— 换了也还是这两个值。 */
    private void loadAccentOverride() {
        android.content.SharedPreferences p = prefs();
        accentOverride = p == null ? 0 : p.getInt(KEY_ACCENT, 0);
        int s = p == null ? SURFACE_MODE : p.getInt(KEY_SURFACE, SURFACE_MODE);
        surfaceMode = s == SURFACE_WALLPAPER ? SURFACE_WALLPAPER : SURFACE_MODE;
    }

    /** app 启动时调用一次。恢复上次选的模式与配色;加载失败回退内置兜底,保证不崩。 */
    public static void init(Context ctx) {
        if (instance != null) return;
        Context app = ctx.getApplicationContext();
        android.content.SharedPreferences p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String id = MODE;

        Mode t = loadMode(app, id);
        if (t == null) {
            Log.w(TAG, "mode asset load failed, using built-in fallback");
            t = Mode.fallback();
        }
        // 配色可以来自另一个模式(见类注释)。没设过用 DEFAULT_PALETTE;那个模式已经不在了就用模式自己的
        String paletteId = p.getString(KEY_PALETTE, DEFAULT_PALETTE);
        Mode pal = (paletteId == null || paletteId.equals(t.id)) ? t : loadMode(app, paletteId);
        if (pal == null) {
            /*
             * 配色指向的那个模式已经不在了(08-02 删素白就撞上了:prefs 里还留着
             * palette_id=subai,而 subai.json 连 assets 带 filesDir 都没了)。
             *
             * **回写**而不是只在内存里让路:不回写的话每次冷启动都要白跑一趟必然失败的
             * loadMode。回写之后状态是稳定的、和外观弹层里显示的"当前底色"对得上。
             *
             * 强调色和面板底色不受影响 —— 它们现在是全局单值,跟这里指向谁没关系。
             */
            Log.w(TAG, "配色来源 " + paletteId + " 已不存在,回退到 " + t.id);
            pal = t;
            p.edit().putString(KEY_PALETTE, t.id).apply();
        }

        Log.i(TAG, "mode loaded: " + t.id + " palette=" + pal.id
                + " accent=" + String.format("#%06X", 0xFFFFFF & pal.accent));
        instance = new ModeManager(app, t, pal);
    }

    public static ModeManager get() {
        // 正常路径下 init 已在 Application 里跑过;防御性兜底,避免早期调用 NPE。
        // 兜底路径没有 Context,appCtx 为 null,此时不做持久化(见 prefs())
        if (instance == null) instance = new ModeManager(null, Mode.fallback(), null);
        return instance;
    }

    /** 当前模式(能力和身份的来源)。要取颜色请用 accent() 那组方法,别直接读这个的字段。 */
    public Mode mode() {
        return current;
    }

    /** 配色现在来自哪个模式。多数时候就是 {@link #mode()},用户单独换过才不同。 */
    public Mode paletteMode() {
        return palette;
    }

    /** 单独换配色来源(不动能力)。 */
    public void setPalette(Context ctx, String id) {
        Mode t = loadMode(ctx.getApplicationContext(), id);
        if (t == null) return;
        palette = t;
        android.content.SharedPreferences p = prefs();
        if (p != null) p.edit().putString(KEY_PALETTE, t.id).apply();
        loadAccentOverride();   // 自定义强调色是按配色存的,换了配色要重读
    }

    /** 兜底构造(无 Context)时返回 null,调用方须判空。 */
    private android.content.SharedPreferences prefs() {
        return appCtx == null ? null : appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---- 便捷 token getter(A2 视图消费用)。一律读 palette:配色是可以单独换的槽位 ----
    public int bg()         { return palette.bg; }
    public int line()       { return palette.line; }
    public int ink()        { return palette.ink; }
    public int muted()      { return palette.muted; }
    public int faint()      { return palette.faint; }
    public int glyph()      { return palette.glyph; }

    /**
     * 通栏那道分界线:**比 {@link #line} 再淡一半**。顶栏的下边、Dock 的上边用它。
     *
     * 那两条不是"边框",是一块面的边缘 —— 栏底色和壁纸本来就有对比(真车实测
     * 栏底亮度 31、壁纸 8,差 4 倍),线只是把边界点一下。用 line 那一档(20% 白)
     * 画出来比栏底还亮 2.45 倍、比壁纸亮 9.5 倍,在暗壁纸上就是横一道亮杠,
     * 用户的原话是「太显眼了,感觉有割裂感」(2026-08-23)。
     *
     * 配合**固定 1 个物理像素**的线宽一起用:`dp(1)` 在 1.5 密度下是 1.5px,
     * 渲染时摊成两行,比想象中粗一倍。
     */
    public int hairline() {
        int c = palette.line;
        return (c & 0x00FFFFFF) | ((((c >>> 24) & 0xFF) / 2) << 24);
    }

    // ---- 面板底色:面板 / 弹窗 / Dock 共用这一个 ----

    /**
     * 抬升面的底色。**Dock、空调面板、各种弹窗都是它**,所以这三样在设置里是一件事 ——
     * 分开改会配出"弹窗和它背后的面板不同色"那种割裂。
     *
     * 两种来源:配色自带,或跟着壁纸走(见 {@link #setWallpaperTint})。**不开放挑具体色号** ——
     * 理由见 {@link com.liubai.blank.ui.AppearancePopup} 里那格 chip 的说明。
     */
    public int surface() {
        int o = surfaceOverride();
        return o != 0 ? o : palette.surface;
    }

    /**
     * 磁贴底。**跟着面板底色派生** —— 磁贴几乎都长在面板/弹窗里(设置页那一行行、
     * 外观弹层的 chip、确认框的按钮底),面板跟壁纸取了个偏色、磁贴还留着配色自带的那档,
     * 一眼就是两套东西拼起来的。
     *
     * 派生方向和 {@link #tile2} 相反:tile 比面板底**暗**一档(沉进去),tile2 亮一档
     * (浮起来)。两套内置配色里 tile &lt; surface &lt; tile2 都成立,这里只是把这层关系
     * 在面板底色跟着壁纸走时也维持住。
     *
     * 桌面上那些卡片不走这个,走 {@link #deskTile} —— 它们浮在壁纸上,不是面板的一部分。
     */
    public int tile() {
        int o = surfaceOverride();
        if (o == 0) return palette.tile;
        float[] hsl = new float[3];
        androidx.core.graphics.ColorUtils.colorToHSL(o, hsl);
        hsl[2] = luminance(o) < 0.5 ? Math.max(0f, hsl[2] - 0.05f) : Math.min(1f, hsl[2] + 0.06f);
        return androidx.core.graphics.ColorUtils.HSLToColor(hsl);
    }

    /**
     * 桌面卡片的底。**不跟面板底色走。**
     *
     * 「面板底色」那个设置项管的是面板 / 弹窗 / Dock,桌面卡片不在其中:它浮在壁纸上,
     * 是内容层。跟着改的话,那个设置项的名字就不成立了 —— 而且卡片和面板不同时露面
     * (面板弹出来就盖住桌面),两处色不同也撞不上。
     *
     * **取 tile2 不取 tile**(2026-08-14 改):这两个方向是相反的 —— tile 比面板底**暗**
     * 一档(磁贴长在面板里,要沉进去),而桌面卡片的衬底是壁纸,要**浮起来**。原来共用 tile,
     * 在中性深色配色下算出来比 bg 还暗,卡片看着像在壁纸上抠了个洞。
     */
    public int deskTile()   { return palette.tile2; }

    /**
     * 比 surface 亮一档的面(弹窗内层、磁贴选中态)。
     *
     * 面板底色跟壁纸走时它也跟着**派生**,不单独开放 —— 两个只差一档的颜色让人分别挑,
     * 挑出来的多半是层次糊在一起或者对比刺眼,而这一档差正是"浮起来"的观感来源。
     */
    public int tile2() {
        int o = surfaceOverride();
        if (o == 0) return palette.tile2;
        // 深底往上提、浅底往下压,都是提高一档对比
        float[] hsl = new float[3];
        androidx.core.graphics.ColorUtils.colorToHSL(o, hsl);
        hsl[2] = luminance(o) < 0.5 ? Math.min(1f, hsl[2] + 0.06f) : Math.max(0f, hsl[2] - 0.05f);
        return androidx.core.graphics.ColorUtils.HSLToColor(hsl);
    }

    /** 当前的面板底色设置:{@link #SURFACE_MODE} 或 {@link #SURFACE_WALLPAPER}。 */
    public int surfaceMode() { return surfaceMode; }

    public void setSurfaceMode(int mode) {
        surfaceMode = mode == SURFACE_WALLPAPER ? SURFACE_WALLPAPER : SURFACE_MODE;
        android.content.SharedPreferences p = prefs();
        if (p == null) return;
        if (surfaceMode == SURFACE_MODE) p.edit().remove(KEY_SURFACE).apply();
        else p.edit().putInt(KEY_SURFACE, surfaceMode).apply();
    }

    /**
     * 面板 / 弹窗底的不透明度(0~100),100 = 实色。
     *
     * **和 Dock 那档是分开的**(Dock 的在 {@link com.liubai.blank.ui.DockHost#opacity})。
     * 底色是一件事,透明度不是:Dock 背后是壁纸,透了看到的是干净的画;面板和弹窗背后是
     * 桌面内容 —— 画中画卡、图标、Dock 本身,同一档透明度在两处的观感差得远。
     *
     * 好在这些面板底下都先垫着一层 scrim(空调面板 45% 黑、Dock 编辑 35%、外观弹层 20%),
     * 透出来的桌面已经被压暗过一道,所以低档位也不至于糊住读数。
     */
    public int panelOpacity() { return panelOpacity; }

    public void setPanelOpacity(int percent) {
        panelOpacity = Math.max(0, Math.min(100, percent));
        android.content.SharedPreferences p = prefs();
        if (p != null) p.edit().putInt(KEY_PANEL_OPACITY, panelOpacity).apply();
    }

    /** 同上,换算成 0~255 的 alpha。自绘的面(气泡)和 Drawable.setAlpha 直接用这个。 */
    public int panelAlpha() { return Math.round(panelOpacity * 2.55f); }

    /**
     * 壁纸底部那条的主色,由桌面铺完壁纸后注入(见 HomeActivity.applyWallpaper)。
     *
     * **不在这里自己算**:取色要读壁纸文件,那是 WallpaperStore 的事,而它依赖本类 ——
     * 反过来依赖会成环。所以这里只收结果。
     */
    public void setWallpaperTint(int color) { wallpaperTint = color; }

    /**
     * 壁纸取出来的那个色,0 = 还没注入(没壁纸,或桌面还没铺完)。
     *
     * 给外观弹层预览「跟随壁纸」那格用 —— 那格得**始终**显示壁纸色,不能读
     * {@link #surface()}:没选中它时 surface() 返回的是配色自带的,两格会长得一模一样。
     */
    public int wallpaperTint() { return wallpaperTint; }

    /** 0 = 用配色自带的 surface。 */
    private int surfaceOverride() {
        // 跟随壁纸:tint 还没注入时是 0,自动回退配色自带的
        return surfaceMode == SURFACE_WALLPAPER ? wallpaperTint : 0;
    }

    // ---- 强调色:12 个 token 里唯一开放给用户改的 ----

    /**
     * 全局唯一的强调色。用户改过就用他的,否则用配色自带的。
     *
     * **只开放这一个**是有意的。12 个 token 里大部分是派生关系(surface/tile/tile2/line 是
     * bg 的逐级抬升,muted/faint/glyph 是 ink 的逐级弱化),逐个放开让人改,很容易配出读不了的
     * 界面 —— 字和底同色、对比度不足。而 accent 是全局唯一的强调色,改它整个桌面的性格就变了,
     * 覆盖绝大多数"想换个颜色"的需求。想换底色深浅,那是换配色来源(在外观弹层里挑另一个模式的配色),不是调色。
     */
    public int accent() {
        return accentOverride != 0 ? accentOverride : palette.accent;
    }

    /**
     * 强调色的柔和底(选中态背景)。自定义强调色时按"强调色掺进底色"算出来 ——
     * 用户只挑一个颜色,配套的两个跟着走,不然选中态的底还是旧色、整个界面是花的。
     */
    public int accentSoft() {
        return accentOverride != 0 ? mix(accentOverride, palette.bg, 0.22f) : palette.accentSoft;
    }

    /**
     * **强调色画在底色上当文字时**用这一档,必要时自动提亮。
     *
     * 和 {@link #onAccent()} 是两件事:那个是"压在强调色**上面**的字",这个是
     * "用强调色**本身**当字色"(设置页的当前值、提示条文字、激活态读数)。
     *
     * 为什么要分:鸿蒙自己就分了两档 —— 面用 {@code #006CDE}、字用 {@code #3F97E9}。
     * 深色底上 {@code #006CDE} 对 {@code #18181A} 的对比度只有 3.55:1,低于小字可读线
     * (WCAG AA 要 4.5:1),大色块没问题、一画成 13sp 的字就发糊。
     *
     * 走**派生**不新开 token:模式作者只填一个 accent,用户在外观里换了强调色也自动跟上,
     * 不用谁记得同时改两个值。提亮沿 HSL 明度走,保住色相 —— 提出来的还是"那个颜色"。
     */
    public int accentText() {
        int a = accent();
        // 够读了就别动:多调一档只会失真
        if (contrast(a, palette.bg) >= 4.5) return a;
        /*
         * **方向取决于底是深是浅**:深底往亮里提,浅底往暗里压。
         * 一律提亮是错的 —— 浅色模式(昼白 bg #F1F3F5)上把蓝提亮,对比度只会越来越差,
         * 一路顶到封顶还是读不清。模拟器上实测过:设置页右侧那些强调色文字全成了淡蓝雾。
         */
        boolean darkBg = luminance(palette.bg) < 0.5;
        float[] hsl = new float[3];
        androidx.core.graphics.ColorUtils.colorToHSL(a, hsl);
        // 两头都留余量:再往外就洗成白 / 糊成黑,那就不是这个强调色了
        final float limit = darkBg ? 0.85f : 0.18f;
        while (darkBg ? hsl[2] < limit : hsl[2] > limit) {
            hsl[2] = darkBg ? Math.min(limit, hsl[2] + 0.04f)
                            : Math.max(limit, hsl[2] - 0.04f);
            int t = androidx.core.graphics.ColorUtils.HSLToColor(hsl);
            if (contrast(t, palette.bg) >= 4.5) return t;
        }
        return androidx.core.graphics.ColorUtils.HSLToColor(hsl);
    }

    /** WCAG 对比度。 */
    private static double contrast(int a, int b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /**
     * 压在强调色上面的文字色。按强调色的**相对亮度**自动选深或浅 ——
     * 这一项绝不能让用户自己挑:挑错了字就直接消失在按钮里。
     */
    public int onAccent() {
        return accentOverride != 0 ? readableOn(accentOverride) : palette.onAccent;
    }

    /** 用户自定义的强调色;0 表示没改过,用配色自带的。 */
    public int accentOverride() { return accentOverride; }

    /** 改强调色。传 0 恢复成配色自带的那个。 */
    public void setAccentOverride(int color) {
        accentOverride = color;
        android.content.SharedPreferences p = prefs();
        if (p == null) return;
        if (color == 0) p.edit().remove(KEY_ACCENT).apply();
        else p.edit().putInt(KEY_ACCENT, color).apply();
    }

    /** 按 t 的比例把 a 掺进 b。 */
    private static int mix(int a, int b, float t) {
        int r = Math.round(android.graphics.Color.red(b)   + t * (android.graphics.Color.red(a)   - android.graphics.Color.red(b)));
        int g = Math.round(android.graphics.Color.green(b) + t * (android.graphics.Color.green(a) - android.graphics.Color.green(b)));
        int bl = Math.round(android.graphics.Color.blue(b) + t * (android.graphics.Color.blue(a)  - android.graphics.Color.blue(b)));
        return android.graphics.Color.rgb(r, g, bl);
    }

    /**
     * 压在这个颜色上面能看清的文字色:亮底给一个同色相的极深色(比纯黑柔和,也更像一套),
     * 暗底直接给白。阈值 0.4 是照着三套内置模式的既有取值定的 ——
     * 琥珀(亮度 .25)配白字、冷蓝(.47)和薄荷(.53)配深字,和它们 json 里手写的值一致。
     */
    private static int readableOn(int c) {
        if (luminance(c) <= 0.4) return 0xFFFFFFFF;
        float[] hsl = new float[3];
        androidx.core.graphics.ColorUtils.colorToHSL(c, hsl);
        hsl[2] = 0.075f;   // 保住色相和饱和度,只把明度压到底
        return androidx.core.graphics.ColorUtils.HSLToColor(hsl);
    }

    /** WCAG 相对亮度。 */
    private static double luminance(int c) {
        double[] v = new double[3];
        int[] ch = { android.graphics.Color.red(c), android.graphics.Color.green(c), android.graphics.Color.blue(c) };
        for (int i = 0; i < 3; i++) {
            double s = ch[i] / 255.0;
            v[i] = s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2];
    }

    // ---- 能力查询(这个模式带什么,见 Capabilities) ----

    /**
     * 车控是不是静止画(不跑动效)。模式不给动效、或用户在设置里关了动效,都是 true。
     */
    public boolean hvacBasic() {
        // 模式压根不给动效,或者给了但用户自己关了 —— 两种都按"静止"画
        return current.caps.hvacBasic || !motionOn(appCtx);
    }

    /**
     * 动效总开关(用户级,**全局单值**,不跟模式走)。
     *
     * 模式声明的 {@code capabilities.hvac} 说的是"这个模式配不配有动效",这里说的是
     * "有了我要不要"。两件事叠加:模式不给,开关拨了也没用;模式给了,用户仍可以关掉 ——
     * 车上晃眼、或者单纯不喜欢,不该逼人去换一个能力更少的模式。
     *
     * 关掉的是**动**:扇叶转、温度键下雪、气流线流动、座椅气流曲线(见 HvacPanel.still /
     * DeskCard.still / DockView)。控件一个不少,读数照常更新。
     *
     * **默认关,想要的自己开**(2026-09-06)。真车实测:空调开着时温度键的粒子和扇叶旋转
     * 各自每个 vsync 逼出一帧,Dock 是常驻悬浮窗,被任何 app 盖住也照烧 —— 关空调 A/B
     * 是 34% 一核对约 1%。开着导航跑一路,这笔电和热不该默认替用户付。
     */
    public static boolean motionOn(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_MOTION, false);
    }

    public static void setMotionOn(Context ctx, boolean on) {
        ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_MOTION, on).apply();
    }

    /** 当前模式的完整能力声明(四项都已接线),见 {@link Capabilities}。 */
    public Capabilities caps() { return current.caps; }

    // ---- 几何 token(见 Dim) ----

    /** 按名取几何值,单位 **dp**。模式没声明就回退默认,所以老模式外观不变。 */
    public float dim(String name) {
        Float v = palette.dims.get(name);   // 圆角属于外观,跟着配色走
        return v != null ? v : Dim.fallback(name);
    }

    /** 同上,直接给 px —— 视图侧要的都是 px。 */
    public float dimPx(Context ctx, String name) {
        return dim(name) * ctx.getResources().getDisplayMetrics().density;
    }

    /** 按 token 名取色(ModeApplier 的 tag DSL 用)。未知名回退主文字色。 */
    public int token(String name) {
        switch (name) {
            case "bg":         return palette.bg;
            // 这四个都可能跟着用户改过的面板底色派生,一律走方法别读字段
            case "surface":    return surface();
            case "tile":       return tile();
            case "tile2":      return tile2();
            case "deskTile":   return deskTile();
            case "line":       return palette.line;
            case "ink":        return palette.ink;
            case "muted":      return palette.muted;
            case "faint":      return palette.faint;
            // 走方法不走字段:强调色这三个可能被用户改过(见 accent())
            case "accent":     return accent();
            case "accentText": return accentText();
            case "accentSoft": return accentSoft();
            case "onAccent":   return onAccent();
            case "glyph":      return palette.glyph;
            default:           return palette.ink;
        }
    }

    // ---- 列表 ----

    /**
     * 列出这台机器上所有可用的模式(内置 assets + 下发 filesDir,同 id 以下发的为准)。
     *
     * 模式中心靠它出列表 —— **不能再像以前那样把模式写死在界面里**(设置页曾经是
     * 两个硬编码按钮,加一个模式得改 XML 加 Java)。加一个模式就该只是往目录里丢个 json,
     * 哪怕现在总共只有两个。
     *
     * 内置的排在前,下发的追加在后;解析失败的整条跳过,不让一个坏文件毁掉整个列表。
     */
    public static java.util.List<Mode> listAll(Context ctx) {
        Context app = ctx.getApplicationContext();
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        try {
            String[] names = app.getAssets().list("modes");
            if (names != null) for (String n : names) {
                if (n.endsWith(".json")) ids.add(n.substring(0, n.length() - 5));
            }
        } catch (java.io.IOException e) {
            Log.w(TAG, "列内置模式失败: " + e);
        }
        java.io.File dir = new java.io.File(app.getFilesDir(), "modes");
        java.io.File[] fs = dir.listFiles();
        if (fs != null) for (java.io.File f : fs) {
            String n = f.getName();
            if (n.endsWith(".json")) ids.add(n.substring(0, n.length() - 5));
        }
        java.util.List<Mode> out = new java.util.ArrayList<>();
        for (String id : ids) {
            Mode t = loadMode(app, id);
            if (t != null) out.add(t);
            else Log.w(TAG, "模式解析失败,跳过: " + id);
        }
        return out;
    }

    // ---- 加载 ----

    /** 加载模式:优先下发目录(filesDir/modes/{id}.json,模拟云端下发落地),回退内置 assets。 */
    private static Mode loadMode(Context ctx, String id) {
        java.io.File ext = new java.io.File(ctx.getFilesDir(), "modes/" + id + ".json");
        if (ext.exists()) {
            Mode t = loadFile(ext);
            if (t != null) {
                Log.i(TAG, "mode from filesDir(下发): " + id);
                return t;
            }
        }
        return loadAsset(ctx, "modes/" + id + ".json");
    }

    private static Mode loadFile(java.io.File f) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            return Mode.fromJson(new JSONObject(readAll(in)));
        } catch (Exception e) {
            return null;
        }
    }

    private static Mode loadAsset(Context ctx, String path) {
        try (InputStream in = ctx.getAssets().open(path)) {
            return Mode.fromJson(new JSONObject(readAll(in)));
        } catch (Exception e) {
            return null;
        }
    }

    private static String readAll(InputStream in) throws java.io.IOException {
        StringBuilder sb = new StringBuilder();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        return sb.toString();
    }
}
