package com.liubai.blank.privileged.server;

import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;

import java.lang.reflect.Method;

/**
 * 特权服务的 binder 实体,活在 shell(uid 2000) 进程里。
 *
 * app 侧拿到的是它的代理,每次注入就是一次 oneway binder 调用(微秒级),
 * 这才谈得上跟手——对比 `input` 命令每次 fork 一个进程、几百毫秒。
 *
 * 手写 onTransact 而不是 AIDL:接口只有两个方法,省掉 AGP 的 aidl 开关。
 * 协议见 {@link BlankApi}。
 */
public final class BlankService extends Binder {

    /** InputManager.INJECT_INPUT_EVENT_MODE_ASYNC —— 不等派发结果。 */
    private static final int MODE_ASYNC = 0;

    private final Object inputManager;
    private final Method injectMethod;
    private final Method setDisplayId;

    /** app 真实包名(debug 包带 .debug 后缀),AC 探针的 createPackageContext 要用。 */
    private final String packageName;

    /** 全反射:IInputManager 与 setDisplayId 都是 @hide,且各版本签名有出入。 */
    public BlankService(String packageName) throws Exception {
        this.packageName = packageName;
        Class<?> sm = Class.forName("android.os.ServiceManager");
        IBinder binder = (IBinder) sm.getMethod("getService", String.class).invoke(null, "input");
        if (binder == null) throw new IllegalStateException("拿不到 input 服务");

        Class<?> stub = Class.forName("android.hardware.input.IInputManager$Stub");
        inputManager = stub.getMethod("asInterface", IBinder.class).invoke(null, binder);

        Method inject = null;
        for (Method m : inputManager.getClass().getMethods()) {
            Class<?>[] types = m.getParameterTypes();
            if ("injectInputEvent".equals(m.getName()) && types.length == 2
                    && InputEvent.class.isAssignableFrom(types[0])) {
                inject = m;
                break;
            }
        }
        if (inject == null) throw new IllegalStateException("没找到 injectInputEvent");
        injectMethod = inject;

        // 在 app 进程里这行从 Android 12 起会抛 NoSuchMethod(被 hidden API 名单拦),
        // 但本进程是 app_process 直接拉起的,不是 app,不受名单限制。
        setDisplayId = MotionEvent.class.getMethod("setDisplayId", int.class);
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        switch (code) {
            case BlankApi.TX_INJECT_TOUCH:
                data.enforceInterface(BlankApi.DESCRIPTOR);
                injectTouch(data.readInt(), data.readInt(),
                        data.readFloat(), data.readFloat(),
                        data.readLong(), data.readLong());
                return true;      // oneway,没有 reply 可写

            case BlankApi.TX_PING:
                data.enforceInterface(BlankApi.DESCRIPTOR);
                if (reply != null) {
                    reply.writeNoException();
                    reply.writeInt(Process.myUid());
                }
                return true;

            case BlankApi.TX_NATIVE_SET:
                data.enforceInterface(BlankApi.DESCRIPTOR);
                int deviceType = data.readInt();
                String[] fields = data.createStringArray();
                int[] values = data.createIntArray();
                if (reply != null) {
                    reply.writeNoException();
                    nativeSet(deviceType, fields, values, reply);
                }
                return true;

            case BlankApi.TX_NATIVE_GET:
                data.enforceInterface(BlankApi.DESCRIPTOR);
                int getDevice = data.readInt();
                String getField = data.readString();
                if (reply != null) {
                    reply.writeNoException();
                    nativeGet(getDevice, getField, reply);
                }
                return true;

            case BlankApi.TX_NATIVE_GET_FLOAT:
                data.enforceInterface(BlankApi.DESCRIPTOR);
                int getFloatDevice = data.readInt();
                String getFloatField = data.readString();
                if (reply != null) {
                    reply.writeNoException();
                    nativeGetFloat(getFloatDevice, getFloatField, reply);
                }
                return true;

            default:
                return super.onTransact(code, data, reply, flags);
        }
    }

    // ---- BYDAutoManager native bus:绕开各设备"友好封装"类的权限墙 ----
    // 详情见 AirCon 类头注释;这里只管"怎么调 native bus",具体哪个设备哪个
    // feature id、什么值,由调用方(AirCon 等)决定。

    /** 首次成功后缓存,避免每次控件操作都重走 Context 引导 + 反射解析。 */
    private Object autoManager;
    private Method setIntMethod, setIntArrayMethod, getIntMethod, getDoubleMethod;
    private Class<?> featureIdsClass;

    private synchronized void ensureAutoManager() throws Exception {
        if (autoManager != null) return;
        Context ctx = systemIgnoreSecurityContext();
        Class<?> mgrClass = Class.forName("android.hardware.BYDAutoManager");
        autoManager = mgrClass.getConstructor(Context.class).newInstance(ctx);
        setIntMethod = mgrClass.getMethod("setInt", int.class, int.class, int.class);
        setIntArrayMethod = mgrClass.getMethod("setIntArray", int.class, int[].class, int[].class);
        getIntMethod = mgrClass.getMethod("getInt", int.class, int.class);
        // 车速这类字段在总线上是 float,BYDAutoManager.getDouble() 其实返回 float
        // (方法名是历史遗留,签名是 public float getDouble(int,int))。
        getDoubleMethod = mgrClass.getMethod("getDouble", int.class, int.class);
        featureIdsClass = Class.forName("android.hardware.bydauto.BYDAutoFeatureIds");
    }

    /**
     * 按字段名反射取 {@code BYDAutoFeatureIds} 里的 feature id。
     *
     * 大部分字段在顶层,但像座椅记忆 {@code SET_LF_MEMORY_LOCATION_WAKE_SET} 只在
     * 嵌套类 {@code BYDAutoFeatureIds.Setting} 里 —— {@code Class.getField} 只查顶层,
     * 直接反射会 NoSuchField。先试顶层,找不到再遍历全部嵌套类兜底。
     * 顶层优先,保证与旧字段(日行灯/空调等)行为一致;嵌套类只作补充。
     */
    private int featureIdOf(String fieldName) throws Exception {
        try {
            return featureIdsClass.getField(fieldName).getInt(null);
        } catch (NoSuchFieldException top) {
            for (Class<?> inner : featureIdsClass.getDeclaredClasses()) {
                try {
                    return inner.getField(fieldName).getInt(null);
                } catch (NoSuchFieldException ignored) {
                    // 这个嵌套类没有该字段,继续找下一个
                }
            }
            throw top;
        }
    }

    /**
     * deviceType 是 BYDAutoConstants.BYDAUTO_DEVICE_*(空调=1000,座椅所在的车身
     * 控制=1001…)。fieldNames 是 BYDAutoFeatureIds 里的字段名(如
     * "AC_WIND_LEVEL_SET"),长度 1 时走 setInt,否则走 setIntArray——要跟
     * 对应设备"友好封装"类里各方法本身的调用形状对上(比如 AC_MAX_COOLING_STATE_SET
     * 是单值,大多数空调开关是"目标值 + AC_CTRL_SOURCE_SET"成对)。
     * 返回码写进 reply:0 = 成功,负值见 BYDAutoManager.BYDAUTO_COMMAND_RESULT_*,
     * Integer.MIN_VALUE = 本地异常(反射/Context 失败,不是车身拒绝)。
     */
    private void nativeSet(int deviceType, String[] fieldNames, int[] values, Parcel reply) {
        // BYDAutoManager 内部要建 Handler,而 onTransact 跑在 binder 线程池、没有
        // Looper.prepare() 过。丢到 main() 里已经 prepareMainLooper() 的主线程上跑,
        // binder 线程阻塞等结果——这条本来就不是 oneway,调用方本就在等。
        final int[] out = {Integer.MIN_VALUE};
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
                ensureAutoManager();
                int[] featureIds = new int[fieldNames.length];
                for (int i = 0; i < fieldNames.length; i++) {
                    featureIds[i] = featureIdOf(fieldNames[i]);
                }
                if (featureIds.length == 1) {
                    out[0] = (Integer) setIntMethod.invoke(autoManager, deviceType, featureIds[0], values[0]);
                } else {
                    out[0] = (Integer) setIntArrayMethod.invoke(autoManager, deviceType, featureIds, values);
                }
                BlankServer.log("native set device=" + deviceType + " " + java.util.Arrays.toString(fieldNames)
                        + "=" + java.util.Arrays.toString(values) + " -> " + out[0]);
            } catch (Throwable t) {
                // 反射 invoke 抛出的是 InvocationTargetException,真正原因在 cause 里。
                Throwable real = t;
                while (real.getCause() != null && real != real.getCause()) real = real.getCause();
                BlankServer.log("native set device=" + deviceType + " "
                        + java.util.Arrays.toString(fieldNames) + " 失败: " + real);
            } finally {
                latch.countDown();
            }
        });

        try {
            latch.await(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        reply.writeInt(out[0]);
    }

    /** 配 {@link #nativeSet},读侧对称实现。返回码写进 reply,Integer.MIN_VALUE = 本地异常。 */
    private void nativeGet(int deviceType, String fieldName, Parcel reply) {
        final int[] out = {Integer.MIN_VALUE};
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
                ensureAutoManager();
                int featureId = featureIdOf(fieldName);
                out[0] = (Integer) getIntMethod.invoke(autoManager, deviceType, featureId);
            } catch (Throwable t) {
                Throwable real = t;
                while (real.getCause() != null && real != real.getCause()) real = real.getCause();
                BlankServer.log("native get device=" + deviceType + " " + fieldName + " 失败: " + real);
            } finally {
                latch.countDown();
            }
        });

        try {
            latch.await(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        reply.writeInt(out[0]);
    }

    /**
     * 配 {@link BlankApi#TX_NATIVE_GET_FLOAT},读侧对称实现,只是走 getDouble
     * 不是 getInt(见 ensureAutoManager 的注释)。四舍五入成 int 写回——目前接进来的
     * 字段(车速、电量%)精度到整数都够用,不为这类字段单独铺一条 float 序列化的通道。
     */
    private void nativeGetFloat(int deviceType, String fieldName, Parcel reply) {
        final int[] out = {Integer.MIN_VALUE};
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
                ensureAutoManager();
                int featureId = featureIdOf(fieldName);
                float value = (Float) getDoubleMethod.invoke(autoManager, deviceType, featureId);
                out[0] = Math.round(value);
            } catch (Throwable t) {
                Throwable real = t;
                while (real.getCause() != null && real != real.getCause()) real = real.getCause();
                BlankServer.log("native get float device=" + deviceType + " " + fieldName + " 失败: " + real);
            } finally {
                latch.countDown();
            }
        });

        try {
            latch.await(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        reply.writeInt(out[0]);
    }

    /**
     * ActivityThread.systemMain()/getSystemContext() 是 @hide,但本进程不是 app、
     * 不受 hidden API 名单限制(同 setDisplayId 那行注释)。createPackageContext
     * 本身是公开 API,CONTEXT_IGNORE_SECURITY 让拿到的 Context 内部权限自检
     * (BYDAutoAcDevice 那层用的 enforceCallingOrSelfPermission)直接短路放行——
     * 这条路径是从 DiStyle(第三方车机桌面)反编译出来的真实实现,真车验证过有效。
     */
    private Context systemIgnoreSecurityContext() throws Exception {
        Class<?> atClass = Class.forName("android.app.ActivityThread");
        Method systemMain = atClass.getDeclaredMethod("systemMain");
        systemMain.setAccessible(true);
        Object activityThread = systemMain.invoke(null);
        Method getSystemContext = atClass.getDeclaredMethod("getSystemContext");
        getSystemContext.setAccessible(true);
        Context sysContext = (Context) getSystemContext.invoke(activityThread);
        return sysContext.createPackageContext(packageName,
                Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
    }

    private void injectTouch(int displayId, int action,
                             float x, float y, long downTime, long eventTime) {
        // 必须新造事件而不是复制 app 传来的:原事件带着主屏物理触摸屏的 deviceId,
        // InputDispatcher 按设备所属的屏路由,设了 displayId 也会被忽略。
        // 这里 deviceId 默认 0,才认 displayId。
        MotionEvent e = MotionEvent.obtain(downTime, eventTime, action, x, y, 0);
        try {
            e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            setDisplayId.invoke(e, displayId);
            injectMethod.invoke(inputManager, e, MODE_ASYNC);
        } catch (Throwable t) {
            BlankServer.log("注入失败 display=" + displayId + " action=" + action + " -> " + t);
        } finally {
            e.recycle();
        }
    }
}
