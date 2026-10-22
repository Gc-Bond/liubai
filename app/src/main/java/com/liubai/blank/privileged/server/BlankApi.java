package com.liubai.blank.privileged.server;

import android.content.Context;
import android.os.IBinder;

/**
 * app 与内置特权服务之间的协议常量,两端共用。
 *
 * 没用 AIDL:AGP 8 起 aidl 编译默认关闭,而这套接口只有一个方法,
 * 手写 onTransact/transact 反而少一个构建开关要维护。
 */
public final class BlankApi {

    /** binder 接口标识,两端必须一致——writeInterfaceToken 会校验它。 */
    public static final String DESCRIPTOR = "com.liubai.blank.IBlankService";

    /** 注入一个触摸事件。用 oneway 发,不等回执,要的就是跟手。 */
    public static final int TX_INJECT_TOUCH = IBinder.FIRST_CALL_TRANSACTION;

    /** 探活,同步调用,返回一个 int(约定为 server 的 uid)。 */
    public static final int TX_PING = IBinder.FIRST_CALL_TRANSACTION + 1;

    /**
     * 走 BYDAutoManager native bus 写车控,绕开各设备"友好封装"类里
     * enforceCallingOrSelfPermission 的权限墙(真车实测验证过,见 AirCon 类头注释)。
     * 不止空调——设备号是参数,座椅加热/通风(BYDAUTO_DEVICE_BODYWORK=1001)走的
     * 也是这条通道,只是 feature id 不同。
     * data: int deviceType + String[] featureNames(对应 BYDAutoFeatureIds 字段名)
     * + int[] values,长度为 1 时对应 BYDAutoManager.setInt,否则对应
     * setIntArray——要跟各设备"友好封装"类里对应方法本身的调用形状对上
     * (比如 AC_MAX_COOLING_STATE_SET 是单值,大多数空调开关是数组)。
     * reply: int 返回码(0 = BYDAUTO_COMMAND_RESULT_SUCCESS)。
     */
    public static final int TX_NATIVE_SET = IBinder.FIRST_CALL_TRANSACTION + 2;

    /**
     * 走 BYDAutoManager native bus 读车控,配 {@link #TX_NATIVE_SET} 用——
     * 座椅加热/通风没有 IAirConditioningService 那样现成能读的旧通道,
     * 只能也走 native bus 读当前档位(读侧字段名通常是写侧去掉 "_SET" 后缀,
     * 如 "SET_DRIVER_SEAT_HEATING_STATE" 对应写侧 "…_STATE_SET")。
     * data: int deviceType + String featureName。reply: int 值
     * (Integer.MIN_VALUE = 本地异常)。
     */
    public static final int TX_NATIVE_GET = IBinder.FIRST_CALL_TRANSACTION + 3;

    /**
     * 配 {@link #TX_NATIVE_GET} 一个模子,读的字段类型不同:车速、电量百分比这类
     * 在总线上是 float(BYDAutoManager.getDouble),不是 int——连 BYD 官方
     * "友好封装"类(BYDAutoSpeedDevice.getCurrentSpeed、
     * BYDAutoStatisticDevice.getElecPercentageValue)自己都是走 getDouble,
     * 不是我们绕的路。reply 里服务端直接四舍五入成 int 传回,不为这类字段
     * 单独铺一条 float 序列化的通道——目前接进来的两个字段(车速、电量%)
     * 精度到整数都够用,真要小数位再改。
     * data: int deviceType + String featureName,跟 TX_NATIVE_GET 一样。
     * reply: int(已四舍五入),Integer.MIN_VALUE = 本地异常。
     */
    public static final int TX_NATIVE_GET_FLOAT = IBinder.FIRST_CALL_TRANSACTION + 4;

    /** ContentProvider 的 call() 方法名:server 靠它把 binder 递进来。 */
    public static final String METHOD_SEND_BINDER = "sendBinder";

    /** call() 的 extras 里装 binder 的键。 */
    public static final String KEY_BINDER = "binder";

    /**
     * provider 的 authority。
     *
     * 必须动态拼:debug 构建的 applicationId 带 .debug 后缀,写死会导致
     * server 在 debug 包上找不到 provider(表现为 getContentProviderExternal 返回 null)。
     * server 进程里没有 Context,所以由 app 侧算好、通过命令行参数传过去。
     */
    public static String authority(Context ctx) {
        return ctx.getPackageName() + ".privileged";
    }

    private BlankApi() {}
}
