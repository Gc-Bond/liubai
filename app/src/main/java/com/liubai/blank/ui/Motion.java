package com.liubai.blank.ui;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.liubai.blank.R;

/**
 * 全 app 一套的动效参数与工具。丝滑感一半来自"处处同一套物理":
 * 按压、出入场、数值 pop 都用这里的刚度/阻尼,颜色过渡共用同一时长。
 *
 * 两条铁律(车机 GPU 弱,这是满帧的前提):
 *  1. 只动 transform/alpha,不在动画里触发 layout;
 *  2. 弹簧实例挂在 view 的 tag 上复用 —— animateToFinalPosition 可以在
 *     动画进行中带着当前速度改目标,手感"全程可打断",这正是 iOS 的关键。
 */
public final class Motion {

    // ---- 弹簧参数(对齐原型里的几条过冲贝塞尔曲线) ----
    private static final float ST_PRESS   = 1400f;  // 按下:快、不回弹
    private static final float ST_RELEASE = 420f;   // 松手:轻微过冲
    private static final float DP_RELEASE = 0.55f;
    private static final float ST_ENTER   = 330f;   // 出场 ≈ cubic-bezier(.25,1.4,.4,1)
    private static final float DP_ENTER   = 0.62f;
    private static final float ST_POP     = 1100f;  // 数值变化的弹一下
    private static final float DP_POP     = 0.5f;

    private static final float PRESS_SCALE = 0.9f;  // 原型 :active{scale(.88)},留一点余量

    /** 自绘控件里的几何量(如风量柱高度)弹起来:比入场硬一点,过冲看得见。 */
    static final float ST_BAR = 520f;
    static final float DP_BAR = 0.52f;

    /** 颜色过渡统一时长(ms)。 */
    static final int COLOR_MS = 260;

    private Motion() {}

    // ---- 按压反馈 ----

    /**
     * 给"纯点击"的 view 装 iOS 式按压:手指落下缩到 0.9,松手弹回。
     * OnTouchListener 返回 false,点击/长按照常走 —— 只旁听,不抢事件。
     * 自己管触摸的 view(如 Dock 的滑动键)别用这个,在手势里手动调 pressIn/pressOut。
     */
    public static void press(View v) {
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:   pressIn(view);  break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: pressOut(view); break;
            }
            return false;
        });
    }

    static void pressIn(View v) {
        scaleTo(v, PRESS_SCALE, ST_PRESS, 1f);
    }

    static void pressOut(View v) {
        scaleTo(v, 1f, ST_RELEASE, DP_RELEASE);
    }

    /** 数值变化时弹一下:先顶到 1.12 再弹回 1(原型 @keyframes pop)。 */
    static void pop(View v) {
        v.setScaleX(1.12f);
        v.setScaleY(1.12f);
        scaleTo(v, 1f, ST_POP, DP_POP);
    }

    // ---- 出入场 ----

    /**
     * 弹簧入场:从下方 dyPx、缩到 0.94、全透明,弹到原位。
     * alpha 不走弹簧(过冲超过 1 没意义),用短淡入。
     */
    static void enter(View v, float dyPx) {
        v.animate().cancel();
        v.setTranslationY(dyPx);
        v.setScaleX(0.94f);
        v.setScaleY(0.94f);
        v.setAlpha(0f);
        v.animate().alpha(1f).setDuration(150).start();
        spring(v, DynamicAnimation.TRANSLATION_Y, R.id.motion_ty, ST_ENTER, DP_ENTER)
                .animateToFinalPosition(0f);
        scaleTo(v, 1f, ST_ENTER, DP_ENTER);
    }

    /** 退场:快速下沉淡出 —— 收要比出干脆,不拖泥带水。 */
    static void exit(View v, float dyPx, Runnable end) {
        cancelSpring(v, R.id.motion_ty);
        cancelSpring(v, R.id.motion_sx);
        cancelSpring(v, R.id.motion_sy);
        v.animate().cancel();
        v.animate().alpha(0f).translationY(dyPx).scaleX(0.96f).scaleY(0.96f)
                .setDuration(170)
                .withEndAction(end)
                .start();
    }

    // ---- 颜色 ----

    interface ColorSink { void apply(int color); }

    /**
     * 颜色渐变。返回启动了的 animator,调用方自持引用,重入前 cancel 旧的。
     */
    static ValueAnimator color(int from, int to, ColorSink sink) {
        final ArgbEvaluator eval = new ArgbEvaluator();
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(COLOR_MS);
        a.addUpdateListener(anim ->
                sink.apply((int) eval.evaluate(anim.getAnimatedFraction(), from, to)));
        a.start();
        return a;
    }

    // ---- 风扇转速 ----

    /**
     * 档位 → 角速度(度/秒)。对齐原型 2.5-0.28*fan 秒一圈。
     *
     * 0 档和"读不到"要分开:0 是风机真的停了,得停转;-1 是没读到档位,
     * 给个慢速怠转 —— 那种情况下僵着不动看起来像坏了。
     */
    static float fanSpeed(int level) {
        if (level == 0) return 0f;
        if (level < 0) return 144f;
        return 360f / (2.5f - 0.28f * Math.min(7, level));
    }

    /**
     * 持续旋转驱动器:Choreographer 逐帧推 rotation,转速朝目标平滑过渡,
     * 起停都是缓的 —— 风扇"越拧越快"的感觉全在这。只动 rotation,零 layout。
     */
    static final class Spinner implements Choreographer.FrameCallback {
        private final View v;
        private float speed, target;   // 度/秒
        private long last;
        private boolean running;

        Spinner(View v) { this.v = v; }

        void speedTo(float degPerSec) {
            target = degPerSec;
            if (!running && target > 0f) {
                running = true;
                last = 0;
                Choreographer.getInstance().postFrameCallback(this);
            }
        }

        void cancel() {
            running = false;
            speed = 0f;
            target = 0f;
            Choreographer.getInstance().removeFrameCallback(this);
        }

        @Override public void doFrame(long now) {
            if (!running) return;
            if (last != 0) {
                float dt = Math.min(0.1f, (now - last) / 1_000_000_000f);
                speed += (target - speed) * Math.min(1f, dt * 4f);
                if (target <= 0f && speed < 6f) {   // 缓停到几乎不动就真停,别空烧帧
                    running = false;
                    speed = 0f;
                    return;
                }
                v.setRotation((v.getRotation() + speed * dt) % 360f);
            }
            last = now;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    // ---- 整层位移(画中画层的收起/展开) ----

    /**
     * 把一整层弹到目标 translationY。用的还是出场那套刚度/阻尼 —— 收起画中画和
     * 面板出场是同一种"一大块东西进出画面"的动作,手感必须一致。
     *
     * 只动 transform,不触发 layout(铁律 1):层里那两张卡的尺寸从头到尾不变,
     * 所以 TextureView 不会收到尺寸回调,虚拟屏也就不会被判定成"尺寸变了"而重建。
     * 这正是三层结构比翻页安全的地方。
     */
    /**
     * 横向滑到某个位移。给滑动切壁纸用(见 WallpaperSwiper):跟手结束后,
     * 要么弹回原位、要么弹到"整屏宽"完成翻页,两种都是同一条弹簧中途重定向。
     */
    public static void slideX(View v, float targetX) {
        // 走带回调那条(传 null):**摘旧回调这件事无回调版也得做** —— 翻页动画还没停就被
        // 一次"没滑够、弹回去"接管的话,弹簧停在原位时旧回调照样会响,
        // 把壁纸换成那次已经放弃掉的目标
        slideX(v, targetX, null);
    }

    /**
     * 横向弹簧,带落定回调 —— 翻页要在动画停下来那一刻才换真正的壁纸。
     *
     * **挂新回调前先把旧的摘掉**。弹簧是按 view 复用的(见 spring),同一条弹簧被重定向时
     * 上一次挂的回调既不会触发、也不会自己摘 —— 一路挂下去,弹簧最终停住那一刻会把攒下的
     * 全部叫一遍。壁纸连着快扫两下就撞得上:两个回调各自带着**各自那一轮**的目标,
     * 先跑的那个把壁纸换成上一轮的,白铺一次再被覆盖。一条弹簧只认最后一次的落定。
     */
    public static void slideX(View v, float targetX, Runnable end) {
        SpringAnimation s = spring(v, DynamicAnimation.TRANSLATION_X,
                R.id.motion_tx, ST_ENTER, DP_ENTER);
        Object old = v.getTag(R.id.motion_tx_end);
        if (old instanceof DynamicAnimation.OnAnimationEndListener) {
            s.removeEndListener((DynamicAnimation.OnAnimationEndListener) old);
            v.setTag(R.id.motion_tx_end, null);
        }
        if (end != null) {
            DynamicAnimation.OnAnimationEndListener l =
                    new DynamicAnimation.OnAnimationEndListener() {
                        @Override public void onAnimationEnd(DynamicAnimation a, boolean canceled,
                                                            float value, float velocity) {
                            a.removeEndListener(this);
                            v.setTag(R.id.motion_tx_end, null);
                            if (!canceled) end.run();
                        }
                    };
            v.setTag(R.id.motion_tx_end, l);
            s.addEndListener(l);
        }
        s.animateToFinalPosition(targetX);
    }

    public static void slideTo(View v, float targetY) {
        spring(v, DynamicAnimation.TRANSLATION_Y, R.id.motion_ty, ST_ENTER, DP_ENTER)
                .animateToFinalPosition(targetY);
    }

    // ---- 自绘控件用的弹簧 ----

    /**
     * 驱动一个裸 float 的弹簧,不经过 View 属性 —— 自绘控件内部的几何量
     * (如 {@link FanStage} 那七根柱子的高度)走这条,和 View 那边共用同一套物理。
     *
     * 实例由调用方自持并反复 animateToFinalPosition:滑动中档位在连续变,
     * 弹簧被打断后能带着当前速度改道,这正是类头第 2 条铁律说的"全程可打断"。
     */
    static SpringAnimation valueSpring(FloatValueHolder holder, float stiffness, float damping,
                                       DynamicAnimation.OnAnimationUpdateListener onUpdate) {
        SpringAnimation s = new SpringAnimation(holder);
        s.setSpring(new SpringForce().setStiffness(stiffness).setDampingRatio(damping));
        if (onUpdate != null) s.addUpdateListener(onUpdate);
        return s;
    }

    // ---- 内部 ----

    private static void scaleTo(View v, float target, float stiffness, float damping) {
        spring(v, DynamicAnimation.SCALE_X, R.id.motion_sx, stiffness, damping)
                .animateToFinalPosition(target);
        spring(v, DynamicAnimation.SCALE_Y, R.id.motion_sy, stiffness, damping)
                .animateToFinalPosition(target);
    }

    private static SpringAnimation spring(View v, DynamicAnimation.ViewProperty prop,
                                          int tagId, float stiffness, float damping) {
        Object t = v.getTag(tagId);
        SpringAnimation s;
        if (t instanceof SpringAnimation) {
            s = (SpringAnimation) t;
        } else {
            s = new SpringAnimation(v, prop);
            s.setSpring(new SpringForce());
            v.setTag(tagId, s);
        }
        s.getSpring().setStiffness(stiffness).setDampingRatio(damping);
        return s;
    }

    private static void cancelSpring(View v, int tagId) {
        Object t = v.getTag(tagId);
        if (t instanceof SpringAnimation) ((SpringAnimation) t).cancel();
    }
}
