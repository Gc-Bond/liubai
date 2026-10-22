package com.liubai.blank.ui;

import android.content.Context;
import android.view.MotionEvent;

import com.liubai.blank.util.Launchers;

/**
 * 在桌面空白处单指往上一划,把桌面上那些卡片收走、露出整片壁纸;往下一划放回来。
 *
 * **两条常驻栏(Dock、顶栏)不在收起范围里** —— 它们是功能条,想看壁纸不等于不要它们
 * (2026-08-18 一开始连它们一起收,用户当场问回来)。落地在
 * {@code DeskCardHost.setHidden}。
 *
 * <h3>不跟手,只认一下</h3>
 *
 * 跟手要一路把十几张卡的位移算出来,而收起本身是个"到了就走完"的动作 —— 所以这里
 * 只判"够不够算一次划",够了就让宿主用自己的动画收/放。既然不跟手,门槛就可以定得大
 * ({@link #DIST_DP} 96dp) —— 这反而是好事:
 *
 * <ul>
 *   <li><b>不和双指手势抢</b>:双指合拢/张开进出编辑态(见 {@link TwoFingerPageGesture})的第一根
 *       手指落下之后、第二根落下之前那几十毫秒里也在动,门槛小了会被这里先吃掉;</li>
 *   <li><b>不和拖卡片抢</b>:编辑态是按下即拖(见 {@link DeskCard}),竖着拖一张卡很容易
 *       走过一小段 —— 那种局面下宿主压根不喂事件给这里,门槛只是第二道保险。</li>
 * </ul>
 *
 * <h3>和左右滑切壁纸怎么分</h3>
 *
 * 两个都挂在 {@code Activity.dispatchTouchEvent} 上,各按方向比认领:横滑要
 * {@code |dx| > |dy|·1.4},这里要 {@code |dy| > |dx|·1.4},斜着划的两边都不认。
 * 顺序上壁纸在前(它要跟手,得早一点拿到 MOVE)。
 */
public final class BarSwipeGesture {

    public interface Host {
        /** 收起({@code true}) / 放回桌面卡片。 */
        void onBarsHidden(boolean hidden);

        /** 现在收着没有。已经收着了再往上划没有意义,反过来也一样。 */
        boolean barsHidden();
    }

    /** 认领门槛:竖着划过这么多才算一下。不跟手,所以可以定大,理由见类注释。 */
    private static final int DIST_DP = 96;

    private final Context ctx;
    private final Host host;

    private float downX, downY;
    /** 见过 DOWN 没有。宿主会按状态停喂事件(编辑态),半路接上的 MOVE 不能拿旧坐标去判。 */
    private boolean armed;
    /** 这一轮已经认领过了 —— 一次手势只收/放一次,别来回抽。 */
    private boolean fired;
    /** 这一轮不是竖划(横着划的/多指/被别人叫停),别再判了。 */
    private boolean rejected;

    public BarSwipeGesture(Context ctx, Host host) {
        this.ctx = ctx.getApplicationContext();
        this.host = host;
    }

    /**
     * 这一轮别当竖划判了 —— 交给别人。
     *
     * 和 {@code WallpaperSwiper.reject} 同一个用法:给**比自己更晚收到事件**的东西用
     * (画中画那根握把、拖卡片)。本类挂在 Activity.dispatchTouchEvent 上,比整棵 view 树
     * 先拿到事件,view 里的 {@code requestDisallowInterceptTouchEvent} 对它无效。
     */
    public void reject() {
        rejected = true;
    }

    /** 喂触摸事件。返回 true 表示这一下被它吃了,宿主据此把事件挡在别处之外。 */
    public boolean onTouch(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getRawX();
                downY = ev.getRawY();
                armed = true;
                fired = false;
                rejected = false;
                return false;

            case MotionEvent.ACTION_POINTER_DOWN:
                // 多指是别人的(双指合拢/张开进出编辑态)
                rejected = true;
                return false;

            case MotionEvent.ACTION_MOVE: {
                // 认领过了:剩下的 MOVE 一路吞掉。和 WallpaperSwiper 一个契约 ——
                // 下面那些 view 已经吃过我们补的 CANCEL,再喂它们后半段没有好处
                if (fired) return true;
                if (!armed || rejected) return false;
                float dx = ev.getRawX() - downX, dy = ev.getRawY() - downY;
                if (Math.abs(dy) < Launchers.dp(ctx, DIST_DP)) return false;
                // 竖向要明显多于横向才算 —— 斜着划的多半是想切壁纸
                if (Math.abs(dy) < Math.abs(dx) * 1.4f) {
                    rejected = true;
                    return false;
                }
                boolean hide = dy < 0;
                fired = true;
                // 已经是那个状态了就不折腾窗口,但这一下还是算被吃掉(手指划了这么长一段,
                // 底下的东西不该跟着响应)
                if (host.barsHidden() != hide) host.onBarsHidden(hide);
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean was = fired;
                armed = false;
                fired = false;
                return was;
            }
            default:
                return fired;
        }
    }
}
