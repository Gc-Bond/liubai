package com.liubai.blank.ui;

import android.content.Context;
import android.view.MotionEvent;

/**
 * 双指手势三件事:**上下滑翻页**、**合拢**、**张开**。
 *
 * 合拢/张开是一对(留白拿它进/出桌面编辑态,见 {@code DeskCardHost.setEditing})。
 *
 * 为什么不用系统的手势检测:ViewPager2 自身的滑动被关掉了(单指要留给网格滚动),
 * 这里要的是"两指一起平移"这个特定动作,GestureDetector 给不了。
 *
 * 仍然校验"间距几乎没变"才算翻页:两指一边张一边挪是很常见的手抖,
 * 不设这道门槛的话,任何双指动作都会被当成翻页。合拢那条走另一道门槛
 * ({@link #PINCH_RATIO}),两者中间留着空档,不会互相抢。
 */
public final class TwoFingerPageGesture {

    private static final String TAG = "Blank/2Finger";

    /** 翻页动作的落点。由宿主决定当前页码、总页数和怎么翻。 */
    public interface Pager {
        int pageCount();
        int currentPage();
        void goToPage(int index);
    }

    /**
     * 两指合拢到这个比例就算捏合(和按下那一刻比)。
     *
     * 0.72 和平移那道门槛(0.78~1.22)之间留了一段空档:手抖式的轻微收拢落在空档里,
     * 两边都不触发 —— 这个动作会把一整张面板叫出来,宁可让它难触发一点。
     */
    private static final float PINCH_RATIO = 0.72f;

    /**
     * 两指张开到这个比例算"张开"。取 1.38 是为了和合拢侧对称(0.72 的倒数),
     * 而且**必须大于平移那道门槛的上沿 1.22**,否则一边张一边滑就会被判成张开。
     */
    private static final float SPREAD_RATIO = 1.38f;

    /**
     * 间距变化小于这个比例才可能是平移(而不是捏合)。
     *
     * **10% 太严,车上滑不出来**(2026-08-15 放宽到 22%):这个比例是拿当前间距和
     * **按下那一刻**比的,不是逐帧比。车机屏大、手指跨度大,两指往下滑 86px 的过程里
     * 自然张开一成半是常事 —— 一旦张开后保持住,ratio 就一直超标,这一轮再也触发不了,
     * 得松手重来。而留白根本没有捏合手势,这道门槛只要挡住"明显在张合"就够了。
     */
    private static final float PAN_SPAN_TOLERANCE = 0.22f;

    private final Context ctx;
    private final Pager pager;
    /** 双指合拢。宿主不给就当没这个手势。 */
    private Runnable onPinchIn;
    /** 双指张开。同上。 */
    private Runnable onPinchOut;

    private float startSpan, startFocusY;
    private boolean handled;   // 本次双指手势已消费,松手前不再重复触发

    public TwoFingerPageGesture(Context ctx, Pager pager) {
        this.ctx = ctx;
        this.pager = pager;
    }

    public void setOnPinchIn(Runnable r) { this.onPinchIn = r; }

    public void setOnPinchOut(Runnable r) { this.onPinchOut = r; }

    /** 在 dispatchTouchEvent 里无条件喂给它;只观察,不拦截事件。 */
    public void onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_POINTER_DOWN:
                if (ev.getPointerCount() == 2) {
                    startSpan = span(ev);
                    startFocusY = focusY(ev);
                    handled = false;
                    android.util.Log.i(TAG, "双指按下 span=" + (int) startSpan
                            + " 页数=" + pager.pageCount() + " 当前页=" + pager.currentPage());
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (ev.getPointerCount() != 2 || handled || startSpan <= 0) break;
                float ratio = span(ev) / startSpan;
                float dy = focusY(ev) - startFocusY;
                if (ratio < PINCH_RATIO && onPinchIn != null) {
                    // 合拢。不看位移 —— 捏的时候手往哪偏都算数
                    android.util.Log.i(TAG, "合拢 ratio=" + Math.round(ratio * 100) + "%");
                    onPinchIn.run();
                    handled = true;
                    break;
                }
                if (ratio > SPREAD_RATIO && onPinchOut != null) {
                    android.util.Log.i(TAG, "张开 ratio=" + Math.round(ratio * 100) + "%");
                    onPinchOut.run();
                    handled = true;
                    break;
                }
                boolean spanOk = Math.abs(ratio - 1f) < PAN_SPAN_TOLERANCE;
                boolean panOk = Math.abs(dy) > panThreshold();
                if (spanOk && panOk) {
                    // 平移:向上滑看下一页,向下滑看上一页
                    int next = pager.currentPage() + (dy < 0 ? 1 : -1);
                    boolean inRange = next >= 0 && next < pager.pageCount();
                    android.util.Log.i(TAG, "判定通过 dy=" + (int) dy + " 目标页=" + next
                            + (inRange ? " → 翻页" : " → **越界,不动**(页数=" + pager.pageCount() + ")"));
                    if (inRange) pager.goToPage(next);
                    handled = true;
                } else {
                    android.util.Log.d(TAG, "没通过 间距变化=" + (int) (Math.abs(ratio - 1f) * 100)
                            + "%(需<" + (int) (PAN_SPAN_TOLERANCE * 100) + ") 位移=" + (int) Math.abs(dy)
                            + "(需>" + (int) panThreshold() + ")");
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                startSpan = 0;
                handled = false;
                break;
        }
    }

    /**
     * 翻页所需的焦点位移:横屏高度有限,取屏高的 6%(1080 上约 65px)。
     * 每次实时取而不是构造时算死 —— 转屏后屏高会变。
     *
     * 原来是 8%(86px),和过严的间距门槛叠在一起就是"划半天没反应"。
     * 这个动作只有一种结果(收起/展开画中画层),误触发的代价很小,宁可灵敏些。
     */
    private float panThreshold() {
        return ctx.getResources().getDisplayMetrics().heightPixels * 0.06f;
    }

    private static float span(MotionEvent ev) {
        float dx = ev.getX(0) - ev.getX(1);
        float dy = ev.getY(0) - ev.getY(1);
        return (float) Math.hypot(dx, dy);
    }

    private static float focusY(MotionEvent ev) {
        return (ev.getY(0) + ev.getY(1)) / 2f;
    }
}
