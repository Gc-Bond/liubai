package com.liubai.blank.ui;

import android.content.Context;
import android.view.MotionEvent;

import com.liubai.blank.util.Launchers;

/**
 * 三指在桌面上滑:**竖着调温度,横着调风量**。
 *
 * <h3>为什么是三指</h3>
 *
 * 桌面上一指两指都占满了(见 {@code HomeActivity.dispatchTouchEvent}):单指横滑切壁纸、
 * 单指竖滑收卡片、双指捏合/张开进编辑态。三指是唯一还空着的、
 * 而且**不会误触**的档 —— 车上手掌搭在屏幕上不会正好落三根手指下去。
 *
 * 和双指那套天然互斥:{@link TwoFingerPageGesture} 只在 {@code pointerCount == 2} 时干活,
 * 第三根手指一落它自己就不认了。
 *
 * <h3>跟手,但按格发</h3>
 *
 * 空调没有"设到某个值"的接口(风量除外),温度只能一格一格点过去 ——
 * 所以这里把位移换算成**几格**:每滑过 {@link #STEP_DP} 发一格,基线跟着往前挪。
 * 反过来划就往回退,不用抬手重来。
 *
 * 一格 44dp 是照 Dock 上那套长按滑动调值来的:太小会"手一抖跳三度",
 * 太大又要划半屏才动一格。
 */
public final class ThreeFingerHvacGesture {

    public interface Host {
        /** 温度加/减几格(正=升温)。 */
        void onTempStep(int steps);

        /** 风量加/减几格(正=加大)。 */
        void onFanStep(int steps);

        /** 手指抬了。宿主收气泡。 */
        void onHvacGestureEnd();
    }

    /** 滑过这么多算一格。 */
    private static final int STEP_DP = 44;
    /** 认方向的门槛:先过这个才决定这一轮是调温度还是调风量。 */
    private static final int LOCK_DP = 16;

    private final Context ctx;
    private final Host host;

    /** 三指落齐了没有。少于三指的那些事件跟这里无关。 */
    private boolean armed;
    private float startX, startY;
    /** 已经发出去多少格 —— 位移是绝对的,发过的要扣掉。 */
    private int sentSteps;
    /** 0=还没锁方向,1=温度(竖),2=风量(横)。 */
    private int axis;

    public ThreeFingerHvacGesture(Context ctx, Host host) {
        this.ctx = ctx.getApplicationContext();
        this.host = host;
    }

    /** 喂事件。返回 true = 这一下归它,宿主别再发给别人。 */
    public boolean onTouch(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_POINTER_DOWN:
                if (ev.getPointerCount() == 3) {
                    armed = true;
                    axis = 0;
                    sentSteps = 0;
                    startX = focusX(ev);
                    startY = focusY(ev);
                    return true;
                }
                return false;

            case MotionEvent.ACTION_MOVE: {
                if (!armed) return false;
                // 中途少了一根手指就作废:三指抬成两指时那两指的位移不该继续调空调
                if (ev.getPointerCount() != 3) return true;
                float dx = focusX(ev) - startX, dy = focusY(ev) - startY;
                if (axis == 0) {
                    float lock = Launchers.dp(ctx, LOCK_DP);
                    if (Math.abs(dx) < lock && Math.abs(dy) < lock) return true;
                    axis = Math.abs(dy) >= Math.abs(dx) ? 1 : 2;
                }
                float step = Launchers.dp(ctx, STEP_DP);
                // 竖着:**往上是升温**(和温度计一个方向),而屏幕坐标向下为正,所以取负
                int want = axis == 1 ? (int) (-dy / step) : (int) (dx / step);
                int delta = want - sentSteps;
                if (delta == 0) return true;
                sentSteps = want;
                if (axis == 1) host.onTempStep(delta);
                else host.onFanStep(delta);
                return true;
            }

            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                // 抬起任意一根就收工 —— 剩下两指不该接着调空调,那是别人的手势
                boolean was = armed;
                if (armed) {
                    armed = false;
                    axis = 0;
                    host.onHvacGestureEnd();
                }
                return was;
            }
            default:
                return armed;
        }
    }

    /** 三指的中心。用中心而不是某一根:手指落下有先后,盯着一根会跟着它的抖动走。 */
    private static float focusX(MotionEvent ev) {
        float sum = 0;
        for (int i = 0; i < ev.getPointerCount(); i++) sum += ev.getX(i);
        return sum / ev.getPointerCount();
    }

    private static float focusY(MotionEvent ev) {
        float sum = 0;
        for (int i = 0; i < ev.getPointerCount(); i++) sum += ev.getY(i);
        return sum / ev.getPointerCount();
    }
}
