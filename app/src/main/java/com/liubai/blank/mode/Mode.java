package com.liubai.blank.mode;

import android.graphics.Color;

import org.json.JSONObject;

/**
 * 一个模式(assets/modes/*.json)。它有两部分:
 *
 *   长什么样 —— 12 个语义配色 token(与旧 colors.xml 一一对应),外加几何 token;
 *   带什么能力 —— 画中画、动效、彩蛋这些,见 {@link Capabilities}。
 *
 * 存的是解析好的 int(ARGB)颜色,视图直接拿来用,不再经过静态资源。
 */
public final class Mode {

    public final String id;
    public final String name;
    // 图标策略(iconPolicy)已经去掉:app 图标一律用原生的,模式不再插手,见 IconEngine。

    /** 这个模式带哪些能力(画中画/四固定应用/彩蛋/车控交互),见 {@link Capabilities}。 */
    public final Capabilities caps;

    /** 几何 token(dp):圆角这些。和配色平行的另一半外观,见 {@link Dim}。 */
    public final java.util.Map<String, Float> dims;

    /**
     * 这个模式配套的壁纸(assets/wallpapers/ 下的文件名),空串表示没有。
     *
     * 只用于「模式」tab 里模式卡的**预览配图**(见 AppearancePopup.modeCard),让人一眼看出
     * 这个模式的调性。桌面实际铺哪张与模式无关,由用户全局选(见 WallpaperStore)。
     */
    public final String wallpaper;

    // 语义配色 token(int ARGB)。命名对齐旧 colors.xml,便于 A2 迁移时逐一映射。
    public final int bg;          // 主背景(近黑)
    public final int surface;     // 卡片 / 抬升面
    public final int tile;        // 磁贴
    public final int tile2;       // 磁贴(更亮一档)
    public final int line;        // 分隔线
    public final int ink;         // 主文字
    public final int muted;       // 次文字(冷灰)
    public final int faint;       // 更弱文字
    public final int accent;      // 全局唯一强调色(琥珀)
    public final int accentSoft;  // 强调色柔和底
    public final int onAccent;    // 压在强调色上的文字
    public final int glyph;       // 统一图标字形灰(线性图标 / 首字母占位)

    public Mode(String id, String name,
                 int bg, int surface, int tile, int tile2, int line,
                 int ink, int muted, int faint,
                 int accent, int accentSoft, int onAccent, int glyph,
                 Capabilities caps, java.util.Map<String, Float> dims, String wallpaper) {
        this.id = id;
        this.name = name;
        this.caps = caps;
        this.dims = dims;
        this.wallpaper = wallpaper == null ? "" : wallpaper;
        this.bg = bg;
        this.surface = surface;
        this.tile = tile;
        this.tile2 = tile2;
        this.line = line;
        this.ink = ink;
        this.muted = muted;
        this.faint = faint;
        this.accent = accent;
        this.accentSoft = accentSoft;
        this.onAccent = onAccent;
        this.glyph = glyph;
    }

    /**
     * 从 mode.json 解析。colors 段每个 key 是 #RRGGBB 或 #AARRGGBB 字符串
     * (半透明用在前景和描边上,见 mobai.json 的 _colors_note);任一缺失/非法回退到
     * 下面写死的那组 —— 取的是装机默认那套(墨白),和 res/values/colors.xml 一致,
     * 这样兜底路径和正常路径不会长得不一样。
     *
     * capabilities 段声明这个模式带哪些能力,**整段可以不写**——不写就是全套完整能力,
     * 所以老模式文件原样加载、行为不变。以后加画中画/四固定应用/彩蛋的开关也加在这一段。
     */
    public static Mode fromJson(JSONObject root) {
        String id = root.optString("id", "fallback");
        String name = root.optString("name", "默认");
        JSONObject c = root.optJSONObject("colors");
        if (c == null) c = new JSONObject();
        Capabilities caps = Capabilities.fromJson(root.optJSONObject("capabilities"));
        java.util.Map<String, Float> dims = Dim.fromJson(root.optJSONObject("dims"));
        return new Mode(id, name,
                color(c, "bg",          0xFF18181A),
                color(c, "surface",     0xFF202224),
                color(c, "tile",        0xFF141516),
                color(c, "tile2",       0xFF2E3134),
                color(c, "line",        0x33FFFFFF),
                color(c, "ink",         0xDBFFFFFF),
                color(c, "muted",       0x99FFFFFF),
                color(c, "faint",       0x66FFFFFF),
                color(c, "accent",      0xFF006CDE),
                color(c, "accent_soft", 0xFF132A45),
                color(c, "on_accent",   0xFFFFFFFF),
                color(c, "glyph",       0x99FFFFFF),
                caps, dims, root.optString("wallpaper", ""));
    }

    /** 代码内置兜底(assets 缺失或解析失败时用):一组中性深色,保证不崩。 */
    public static Mode fallback() {
        return fromJson(new JSONObject());
    }

    private static int color(JSONObject c, String key, int def) {
        String hex = c.optString(key, null);
        if (hex == null || hex.isEmpty()) return def;
        try {
            return Color.parseColor(hex);
        } catch (IllegalArgumentException e) {
            return def;
        }
    }
}
