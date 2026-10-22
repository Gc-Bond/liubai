package com.liubai.blank.icon;

import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

import com.liubai.blank.model.AppRef;
import com.liubai.blank.mode.ModeManager;

/**
 * app 图标:直接用系统给的原生图标,不做任何加工。
 *
 * 早先这里是一套"统一图标"引擎:已知包名换成自绘的线性图标,未收录的拿原生图标去色、
 * 缩放、套一块圆角底板收编进来(icon pack 那套 iconback + iconmask + scale)。整屏调性
 * 是统一了,代价是每个 app 都不像它自己 —— 用户认图标是靠颜色和轮廓的,去完色、换成
 * 灰线条之后,盲操时反而要一个个看标签。这套已经整个下线。
 *
 * 现在只剩一条兜底:连原生图标都拿不到时(包被卸了/查询失败)用首字母占位。
 */
public final class IconEngine {

    private final Context ctx;

    public IconEngine(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /**
     * app 原生图标(activity 图标优先,退到应用图标),都拿不到才用首字母占位。
     *
     * mutate:PackageManager 给的是共享实例,宿主给它设 alpha/滤镜(按压动画就会)
     * 会污染系统别处用的同一张图。
     */
    public Drawable iconFor(AppRef app) {
        Drawable real = realIcon(app);
        if (real != null) {
            real = real.mutate();
            // **自家的内部页面(浏览器、游戏中心)要染色。**
            //
            // 它们的图标是单色描边矢量图,颜色写死 #FFFFFF、指望运行时 tint 上色
            // (整套图标都是这个规矩,见《图标统一》第五节)。而这条路径拿到的是
            // PackageManager 给的原始 Drawable,没经过 ModeApplier —— 不染的话
            // 浅色模式下就是**白底白线**,那一格看着是空的(实测:抽屉里
            // 「浏览器」和「游戏」两格只有文字没有图)。
            //
            // 只染自己的包:外部 app 的图标是人家的彩色美术,染了就毁了。
            if (ctx.getPackageName().equals(app.pkg)) {
                real.setTint(ModeManager.get().glyph());
            }
            return real;
        }
        return new LetterIcon(firstGlyph(app.label), ModeManager.get().glyph());
    }

    private Drawable realIcon(AppRef app) {
        PackageManager pm = ctx.getPackageManager();
        if (app.cls != null && !app.cls.isEmpty()) {
            try {
                return pm.getActivityIcon(new ComponentName(app.pkg, app.cls));
            } catch (Exception ignored) { }
        }
        try {
            return pm.getApplicationIcon(app.pkg);
        } catch (Exception ignored) { }
        return null;
    }

    private static String firstGlyph(String label) {
        if (label == null || label.isEmpty()) return "·";
        int cp = label.codePointAt(0);
        return new String(Character.toChars(cp));
    }

    /** 首字母占位:透明底 + 居中单字。 */
    static final class LetterIcon extends Drawable {
        private final String glyph;
        private final Paint paint;

        LetterIcon(String glyph, int color) {
            this.glyph = glyph;
            this.paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(color);
            paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
            paint.setTextAlign(Paint.Align.CENTER);
        }

        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (b.isEmpty()) return;
            paint.setTextSize(b.height() * 0.56f);
            Paint.FontMetrics fm = paint.getFontMetrics();
            float y = b.centerY() - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(glyph, b.centerX(), y, paint);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) { paint.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
