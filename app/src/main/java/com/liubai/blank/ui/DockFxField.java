package com.liubai.blank.ui;

import android.graphics.Canvas;

/**
 * 整条 Dock 上的一层效果场:彩蛋粒子画在这儿(风扇把雪花横向吹过整条 Dock)。
 *
 * 为什么要有这一层:粒子原先被关在温度键内部 —— {@link ThermalFx} 就是那个键本身,
 * 画在自己的画布里,飘不出格子,"风吹雪"没有发生的地方。
 * 效果场是整条 Dock 一层,粒子才跨得过键与键之间。
 *
 * **和 ThermalFx 互不相扰。** 温度键上那两片雪花是**功能语义**(告诉你现在在制冷),
 * 任何模式都必须有、不能关;这里画的是"世界观"那一层,归 {@code Capabilities.particles} 管。
 *
 * **这是宿主内部结构,不是对外接口。** 模式不下发代码 —— 彩蛋 = 宿主引擎 + 模式 json 参数。接口现在长成这样只是因为效果只有一个(风吹雪),
 * 按单例定形必过拟合,第二个效果用例出现之前不谈定稿、也不写对外文档。
 *
 * 帧循环**不在实现类里**:不许自己持 Choreographer,起停一律由 DockView 管
 * —— 性能是宿主的责任,引擎将来写得再差也得停得下来。
 */
public interface DockFxField {

    /** 风量档位 0~7。**值变了**就该吹一阵(谁改的都算:用户滑动 / AUTO 自调 / 副驾实体键)。 */
    String SIG_FAN_LEVEL = "fan_level";

    /** 冷暖。取值直接用 {@link ThermalFx} 的 NONE/COOL/HEAT,不另立第二套常量。 */
    String SIG_AC_MODE = "ac_mode";

    /**
     * 发射源的 x(px,DockView 坐标系):**温度键的中心**。
     *
     * 冷气热气从"温度"那颗键被风吹出来 —— 有源头效果才成立,没有源头的话粒子就只是
     * 一层背景动画。
     */
    String SIG_ORIGIN_X = "origin_x";

    /**
     * 发射源的**宽度**(px):温度键有多宽。
     *
     * 有了宽度,粒子才能从**键的范围里**陆续被吹出来,而不是从一个点凭空冒出一批 ——
     * 看着要像"温度键上飘着的那些雪花被风吹走了",那才叫联动。
     */
    String SIG_ORIGIN_W = "origin_w";

    /**
     * 往哪边吹:+1 往右 / -1 往左。**由温度键在风量键的哪一侧决定**,
     * 不是写死的 —— Dock 是用户自己排的,把温度键拖到风量键左边,风就该往左吹。
     */
    String SIG_DIR = "dir";

    /**
     * 画一帧。位置是 **Dock 背景之后、所有键之前**(见 DockView.dispatchDraw),
     * 和 ThermalFx"背景之后、内容之前"同一层级语言。
     *
     * 画在键**下面**而不是上面:Dock 的键全是幽灵键(无底色无边框),画在下面照样从
     * 键缝和字形背后透出来,又不用靠压透明度去平衡"会不会糊住读数"——那个风险直接归零。
     *
     * @param w 、h DockView 自己的尺寸;粒子的活动范围就是整条 Dock
     */
    void draw(Canvas c, int w, int h, long frameTimeNs);

    /** 还要不要下一帧。触发式效果衰减完就该归 false,让 DockView 把帧循环停掉。 */
    boolean animating();

    /** 宿主喂**语义**信号(不是原始事件),见上面的 SIG_*。不认识的 kind 直接忽略。 */
    void onSignal(String kind, int value);
}
