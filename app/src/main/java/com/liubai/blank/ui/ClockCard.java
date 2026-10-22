package com.liubai.blank.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.liubai.blank.data.DeskCardStore;
import com.liubai.blank.mode.ModeManager;
import com.liubai.blank.util.Launchers;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 时间日期卡。桌面主页层上最省的一张 —— 没有任何车端依赖,系统 API 就够。
 *
 * 底、圆角、半透明、拖拽摆位都在 {@link DeskCard} 里,这里只管内容。
 *
 * <h3>刷新走广播,不开定时器</h3>
 *
 * 用 {@code ACTION_TIME_TICK}(系统每分钟发一次)而不是自己 postDelayed 轮询:
 * 后者要么飘(睡眠时不走,醒来差好几分钟),要么就得把间隔压到秒级空转。
 * 顺带收 {@code TIME_CHANGED}/{@code TIMEZONE_CHANGED} —— 改时间/改时区要当场跟上。
 *
 * **这个广播只能动态注册**(Android 8 起静态注册收不到),所以绑在 view 的
 * attach/detach 上:卡片没挂在树上就不该还占着一个接收器。
 */
public class ClockCard extends DeskCard {

    private final TextView time;
    private final TextView date;

    /**
     * 秒级刷新:时间带秒后,分钟广播(ACTION_TIME_TICK)追不上,得自己每秒对一次。
     * 每次对齐到下一个整秒再排下一发,不按固定 1000ms 空转 —— 那样会越走越漂。
     */
    private final Runnable secondTick = new Runnable() {
        @Override public void run() {
            render();
            postDelayed(this, 1000 - System.currentTimeMillis() % 1000);
        }
    };

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { render(); }
    };

    public ClockCard(Context ctx) {
        super(ctx, DeskCardStore.CLOCK);

        // 时间在上、日期在下,竖排居中。两格宽主要是给时间腾字号 —— 一个格子
        // (约 72dp)塞 40sp 的时间还勉强,加上日期就顶满了(用户嫌挤)。
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);

        time = new TextView(ctx);
        time.setTextSize(40f);
        // **去掉字体上下那圈额外留白**。40sp + 14sp 两行叠起来比一格(89dp)高 13px,
        // 桌面上就是"时钟卡比同排高出一截"(2026-08-23 真车,自检日志报的)。
        // 压字号会伤主角,而这圈 padding 本来就是字形之外的空 —— 去掉两行正好省下那 13px,
        // 字还是 40sp。数字和汉字都不会被裁(裁的是留白,不是字身)
        time.setIncludeFontPadding(false);
        time.setTypeface(android.graphics.Typeface.create(
                android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.NORMAL));
        time.setFontFeatureSettings("tnum");   // 等宽数字:秒数跳动不抖字宽
        time.setGravity(Gravity.CENTER);
        // 竖排 LinearLayout 的默认子宽是 MATCH_PARENT(横排才是 WRAP_CONTENT):
        // 不显式给 WRAP_CONTENT,time 会撑满 col 宽(= 日期那行 215px),
        // 而 40sp 的 8 字符时间要 234px,最后一位被裁成 20:32:0(2026-08-20 真机)。
        col.addView(time, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        date = new TextView(ctx);
        date.setTextSize(14f);
        date.setIncludeFontPadding(false);   // 同上,两行一起才够那 13px
        date.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = Launchers.dp(ctx, 4);
        col.addView(date, dlp);

        // 卡片占两格、内容按内容宽,FrameLayout 默认把子 view 摆在左上角 ——
        // 不显式居中,文字就贴着卡片左边,看起来像没对齐(2026-08-20 真机反馈)。
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.CENTER;
        addView(col, clp);

        render();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        post(secondTick);
        // 时间+日期一行摊开,一格(约 72dp)根本放不下 —— 内容要几格就占几格,
        // 由基类按格子算(见 DeskCard.snapWidthToCells),这里不再自己算宽度。
        //
        // 这段原先是 `max(两格, 内容宽)`:40sp 的 "20:32:00" 八个字符比两格还宽,
        // 只按两格定宽会把秒的最后一位裁掉(2026-08-20 真机:显示成 20:32:0)。
        // 现在向上取整到整数格,既不会裁字,右边缘也仍然落在格线上 —— 那一版是
        // "宽了就不对齐格子",和车况卡超出四格是同一类问题。
        post(this::snapWidthToCells);
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        getContext().registerReceiver(tick, f);
        render();   // 挂上来先对一次时,别等下一个整分
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(secondTick);
        try {
            getContext().unregisterReceiver(tick);
        } catch (IllegalArgumentException ignored) {
            // 没注册上过(attach 那次就失败了),解注册会抛,忽略即可
        }
    }

    /** 颜色烧在 TextView 上,换肤要重取。 */
    @Override protected void onReskin() {
        render();
    }

    private void render() {
        Date now = new Date();
        // 固定 24 小时制:12 小时制不带 AM/PM,晚上 20 点显示成"8:25:25",
        // 用户看着像时间错了(2026-08-20 反馈)。车机时钟本来就是 24 小时习惯。
        // 走 SimpleDateFormat 而不是 android.text.format.DateFormat:后者的 "ss"
        // 不保证补零,秒是个位数时显示成 "20:29:1"(2026-08-20 真机截图反馈)。
        time.setText(new SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(now));
        // 日期固定中文格式:车机 locale 可能是英文(模拟器就是),EEE/EEEE 会跟着
        // 变成 Thursday,用户要的是"2026年8月20日 星期四"这种完整读法
        date.setText(new SimpleDateFormat("yyyy年M月d日 EEEE", Locale.CHINA).format(now));
        time.setTextColor(ModeManager.get().ink());
        date.setTextColor(ModeManager.get().muted());
    }
}
