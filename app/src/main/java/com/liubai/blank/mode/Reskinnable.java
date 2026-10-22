package com.liubai.blank.mode;

/**
 * 自绘 view 的换肤钩子。
 *
 * {@link ModeApplier} 的 tag DSL 只够得着两类东西:标准控件属性(文字色 / 图标 tint)和
 * shape 背景(GradientDrawable 的 fill / stroke / radius)。而留白有一批自绘 view 把模式色
 * **烧在 Paint 里**(数字、刻度、座椅曲线、气泡的渐变面……),那些 tag 一个也刷不到。
 *
 * 以前这不成问题:换配色走的是 recreate,整个 Activity 重建、构造器重跑一遍,颜色自然是新的。
 * 2026-08-02 的 47a1ace 把它改成 {@code HomeActivity.reskin()} 就地换肤之后
 * (理由是 recreate 会整屏闪、弹层消失,用户正一个个试颜色时尤其晃眼),这批 view 就**冻结**
 * 在第一次创建时的颜色上了 —— 表现是同一个界面上一半新色一半旧色,最扎眼的是调节气泡:
 * 它是复用实例、尺寸不变就不再走 onSizeChanged,渐变面能一直停在用户最早那套配色上。
 *
 * 所以约定:**颜色缓存在 Paint / 字段里的 view,自己实现这个接口**,在里面重新取一遍色。
 * {@link ModeApplier#apply} 遍历树时会调到,和 tag 走同一趟 —— 调用方不必知道有这回事。
 *
 * 两条实现要求:
 * <ul>
 *   <li><b>构造器自己也调 reskin()</b>,别把同一段取色抄两遍 —— 抄两遍就一定会改漏一处;
 *   <li><b>reskin() 里要 invalidate()</b>,自绘的东西改了 Paint 不会自动重画。
 * </ul>
 */
public interface Reskinnable {
    void reskin();
}
