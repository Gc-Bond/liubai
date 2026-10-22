package com.liubai.blank.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.View;

import com.liubai.blank.mode.ModeManager;
import com.liubai.blank.util.Launchers;

/**
 * 两张画中画卡之间的握把:按住拖改分配比例(松手吸附到档位),
 * **点一下则直接切到下一档**——车上拖着精调不现实,点一下换一档才用得起来。
 *
 * 横屏是两卡之间的竖条(左右拖),竖屏是上下之间的横条(上下拖);
 * 方向不由外部传入,握把按自己的宽高判断(见 dragsVertically)。
 *
 * 为什么单独做一个 View,而不是在卡片边缘划一块热区:画中画运行时
 * PipHost.attachTouch 给卡片里的 TextureView 挂了监听并 return true,
 * 把触摸全部转发给虚拟屏——做在卡片内的热区根本收不到事件。
 * 握把必须待在卡片外面(占掉两卡之间那道间隙)。
 */
public class ResizeHandle extends View {

    public interface Listener {
        void onResizeStart();
        /** @param delta 相对按下点的**主轴**位移(px):横屏是水平、右为正,竖屏是垂直、下为正 */
        void onResize(float delta);
        /**
         * @param dragged 真的拖动过(超出 touchSlop)才是 true;
         *                false 表示只是点了一下,调用方据此切到下一档
         */
        void onResizeEnd(boolean dragged);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bar = new RectF();

    private Listener listener;
    /** 按下时的主轴坐标(横屏取 rawX,竖屏取 rawY),见 dragsVertically。 */
    private float startRaw;
    private boolean dragging;
    /** 这一次按下有没有真的拖动过。没拖=点击,交给调用方按档位切换。 */
    private boolean moved;
    private int touchSlop;

    public ResizeHandle(Context c) { super(c); setup(); }
    public ResizeHandle(Context c, AttributeSet a) { super(c, a); setup(); }

    private void setup() {
        setClickable(true);
        setFocusable(true);
        touchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
    }

    public void setListener(Listener l) { this.listener = l; }

    /**
     * 握把横跨着(宽 &gt; 高)就是竖屏那根横条,拖它改的是上下分配;
     * 竖立着则是横屏那根竖条,拖它改左右。方向不写死,跟着自己的形状走。
     */
    private boolean dragsVertically() {
        return getWidth() > getHeight();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // 一条居中的圆角短线,按下时点亮为强调色——留白的调性,不做花哨握把。
        // 线的朝向跟着握把走:竖立的握把画竖线,横跨的画横线。
        float thick = Launchers.dp(getContext(), 3);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        if (dragsVertically()) {
            float len = Math.min(getWidth() * 0.16f, Launchers.dp(getContext(), 56));
            bar.set(cx - len / 2f, cy - thick / 2f, cx + len / 2f, cy + thick / 2f);
        } else {
            float len = Math.min(getHeight() * 0.16f, Launchers.dp(getContext(), 56));
            bar.set(cx - thick / 2f, cy - len / 2f, cx + thick / 2f, cy + len / 2f);
        }

        ModeManager tm = ModeManager.get();
        // 用 ink 不用 line:这条线画在**壁纸**上(两卡之间的间隙),
        // line 是 20% alpha 的分隔线色,压在花壁纸上基本看不见
        paint.setColor(dragging ? tm.accent() : tm.ink());
        canvas.drawRoundRect(bar, thick / 2f, thick / 2f, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // 外面套着 ScrollView(单指纵向)和 ViewPager2,不抢下来这次横向拖就会被它们吃掉。
                // 同样的处理见 DesktopView 的长按(那里是为了不让滚动打断长按计时)。
                getParent().requestDisallowInterceptTouchEvent(true);
                // 用 raw 坐标:握把自身会被变大的卡片推着走,event.getX() 是相对本 View 的,
                // 一移动就自我反馈,拖起来会震荡。
                startRaw = dragsVertically() ? event.getRawY() : event.getRawX();
                dragging = true;
                moved = false;
                invalidate();
                if (listener != null) listener.onResizeStart();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (!dragging) return true;
                float now = dragsVertically() ? event.getRawY() : event.getRawX();
                float delta = now - startRaw;
                // 超出 touchSlop 才算拖动:手指按上去总会有几像素的抖,
                // 不设这道门槛的话轻轻一点也会让卡片微微一跳,而且再也判不出"这是点击"
                if (!moved && Math.abs(delta) > touchSlop) moved = true;
                if (moved && listener != null) listener.onResize(delta);
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                if (dragging) {
                    dragging = false;
                    invalidate();
                    if (listener != null) listener.onResizeEnd(moved);
                }
                return true;
        }
        return super.onTouchEvent(event);
    }
}
