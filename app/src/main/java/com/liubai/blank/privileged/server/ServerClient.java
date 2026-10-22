package com.liubai.blank.privileged.server;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;
import android.view.MotionEvent;

/**
 * app 侧持有的特权服务代理。
 *
 * 注意方向:不是我们去「连」服务,而是 server 起来后通过 ContentProvider
 * 把 binder 递进来(见 PrivilegedProvider)。原因见 BlankServer 的注释——
 * app 主动连 shell 进程的 socket 会被 SELinux 静默拒绝,binder 是唯一通路。
 *
 * 注入走 oneway,不等回执,可以直接在触摸回调里同步调:一次 oneway transact
 * 就是把数据丢进内核队列,微秒级,不阻塞主线程。
 */
public final class ServerClient {

    private static final String TAG = "Blank/SrvClient";

    private static volatile IBinder service;

    private ServerClient() {}

    public static boolean isConnected() {
        IBinder s = service;
        return s != null && s.isBinderAlive();
    }

    /** provider 收到 server 递来的 binder 时回调进来。 */
    public static synchronized void onBinderReceived(IBinder binder) {
        if (binder == null || binder == service) return;
        try {
            binder.linkToDeath(DEATH, 0);
        } catch (RemoteException e) {
            Log.w(TAG, "递过来的 binder 已经死了,忽略");
            return;
        }
        service = binder;
        Log.i(TAG, "已收到特权服务 binder,触摸走 binder 直注");
    }

    /** server 进程没了(被系统杀、adb 流断开)要立刻放手,否则后续注入全部超时。 */
    private static final IBinder.DeathRecipient DEATH = new IBinder.DeathRecipient() {
        @Override public void binderDied() {
            Log.w(TAG, "特权服务已退出,触摸退回 input 命令");
            service = null;
        }
    };

    /**
     * 把一个触摸事件送到指定虚拟屏。
     *
     * 坐标在这边就换算好(预览 View → 虚拟屏),server 那边只管注入。
     */
    public static boolean sendTouch(MotionEvent src, int displayId, float scaleX, float scaleY) {
        IBinder s = service;
        if (s == null) return false;
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(BlankApi.DESCRIPTOR);
            data.writeInt(displayId);
            data.writeInt(src.getActionMasked());
            data.writeFloat(src.getX() * scaleX);
            data.writeFloat(src.getY() * scaleY);
            data.writeLong(src.getDownTime());
            data.writeLong(src.getEventTime());
            s.transact(BlankApi.TX_INJECT_TOUCH, data, null, IBinder.FLAG_ONEWAY);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "注入失败,连接可能已断", t);
            service = null;
            return false;
        } finally {
            data.recycle();
        }
    }

    /**
     * 探活,同步调用。
     * @return server 进程的 uid(应为 2000),不可用时返回 -1
     */
    public static int ping() {
        IBinder s = service;
        if (s == null) return -1;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(BlankApi.DESCRIPTOR);
            s.transact(BlankApi.TX_PING, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } catch (Throwable t) {
            Log.w(TAG, "探活失败", t);
            return -1;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /**
     * 走 BYDAutoManager native bus 写车控,绕开各设备"友好封装"类的权限墙
     * (真车实测验证过,见 {@link com.liubai.blank.privileged.AirCon} 类头注释)。
     *
     * @param deviceType   BYDAutoConstants.BYDAUTO_DEVICE_*(空调=1000,座椅所在的
     *                     车身控制=1001…)
     * @param featureNames BYDAutoFeatureIds 里的字段名,如 {"AC_WIND_LEVEL_SET","AC_CTRL_SOURCE_SET"}
     * @param values       对应值,长度要跟 featureNames 一致
     * @return BYDAutoManager 返回码(0=成功),server 不可用或调用异常时返回 Integer.MIN_VALUE
     */
    public static int nativeSet(int deviceType, String[] featureNames, int[] values) {
        IBinder s = service;
        if (s == null) return Integer.MIN_VALUE;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(BlankApi.DESCRIPTOR);
            data.writeInt(deviceType);
            data.writeStringArray(featureNames);
            data.writeIntArray(values);
            s.transact(BlankApi.TX_NATIVE_SET, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } catch (Throwable t) {
            Log.w(TAG, "native set 调用失败", t);
            return Integer.MIN_VALUE;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /** 配 {@link #nativeSet},读侧对称实现。@return 读到的值,不可用/异常时返回 Integer.MIN_VALUE */
    public static int nativeGet(int deviceType, String featureName) {
        IBinder s = service;
        if (s == null) return Integer.MIN_VALUE;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(BlankApi.DESCRIPTOR);
            data.writeInt(deviceType);
            data.writeString(featureName);
            s.transact(BlankApi.TX_NATIVE_GET, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } catch (Throwable t) {
            Log.w(TAG, "native get 调用失败", t);
            return Integer.MIN_VALUE;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /**
     * 配 {@link #nativeGet},读的是 float 字段(车速、电量% 这类),已在 server 侧
     * 四舍五入成 int。配 {@link BlankApi#TX_NATIVE_GET_FLOAT}。
     * @return 读到的值,不可用/异常时返回 Integer.MIN_VALUE
     */
    public static int nativeGetFloat(int deviceType, String featureName) {
        IBinder s = service;
        if (s == null) return Integer.MIN_VALUE;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(BlankApi.DESCRIPTOR);
            data.writeInt(deviceType);
            data.writeString(featureName);
            s.transact(BlankApi.TX_NATIVE_GET_FLOAT, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } catch (Throwable t) {
            Log.w(TAG, "native get float 调用失败", t);
            return Integer.MIN_VALUE;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    public static synchronized void close() {
        IBinder s = service;
        service = null;
        if (s != null) {
            try { s.unlinkToDeath(DEATH, 0); } catch (Throwable ignored) { }
        }
    }
}
