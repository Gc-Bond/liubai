package com.liubai.blank.privileged;

import android.content.Context;

/**
 * 提权能力的统一入口(「能不能干特权的事」),与具体怎么提权解耦。
 *
 * 第一阶段:底下是官方 Shizuku(用户装 Shizuku app + ADB 激活),用来快速验证
 * 「提权之后到底能拿到什么」——车况(BYD 私有 SDK)和画中画(VirtualDisplay)。
 * 第二阶段:换成内置一键授权(连本机无线 ADB 自启提权进程,迪粉路线),
 * **只替换本包内的实现,上层调用方不动**。
 */
public interface Privileged {

    enum State {
        /** 提权服务不可用(未安装 / 未启动) */
        UNAVAILABLE,
        /** 服务在,但还没授权给我们 */
        UNAUTHORIZED,
        /** 可用 */
        READY
    }

    State state(Context ctx);

    /** 请求授权。异步,结果通过 state() 复查。 */
    void requestPermission(Context ctx);

    /**
     * 提权就绪时回调(已就绪则立即执行)。
     *
     * 必须有这个:提权服务的 binder 是**异步**连上的,app 冷启动时界面都搭完了它还没就位。
     * 这时候用 state() 判一次就下结论,会把"还没连上"当成"没提权"而永久降级。
     */
    void onReady(Runnable action);

    /** 人话描述当前状态,用于设置页显示。 */
    String describe(Context ctx);
}
