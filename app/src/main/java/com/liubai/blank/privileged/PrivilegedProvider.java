package com.liubai.blank.privileged;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import com.liubai.blank.privileged.server.BlankApi;
import com.liubai.blank.privileged.server.ServerClient;

/**
 * 接收内置特权服务(BlankServer)递过来的 binder。
 *
 * 这个 provider 不存任何数据,它存在的唯一理由是:app 和 shell 进程之间
 * <b>只有 binder 走得通</b>(unix socket 被 SELinux 静默拒绝),而系统里
 * 能把 binder 从别的进程送进 app 的现成通道,就只有 ContentProvider.call。
 * 方向也是固定的——拿 provider 需要 signature 级权限,app 自己没有,
 * 所以必须由 server 那边主动发起。
 *
 * 必须 exported,否则 shell 进程调不到。安全靠调用方 uid 校验:
 * 只认 shell(2000) 和 root(0),别的 app 塞进来的 binder 一律丢弃。
 */
public final class PrivilegedProvider extends ContentProvider {

    private static final String TAG = "Blank/Provider";

    /** 只有这两个身份能给我们递 binder。 */
    private static final int UID_SHELL = 2000;
    private static final int UID_ROOT = 0;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!BlankApi.METHOD_SEND_BINDER.equals(method)) return null;

        int uid = Binder.getCallingUid();
        if (uid != UID_SHELL && uid != UID_ROOT) {
            Log.w(TAG, "拒绝来路不明的 binder,调用方 uid=" + uid);
            return null;
        }
        IBinder binder = extras == null ? null : extras.getBinder(BlankApi.KEY_BINDER);
        if (binder == null) {
            Log.w(TAG, "调用没带 binder");
            return null;
        }
        ServerClient.onBinderReceived(binder);
        return new Bundle();
    }

    // 以下都不支持:这个 provider 只是 binder 的收件口,没有数据面

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) { return null; }

    @Override public String getType(Uri uri) { return null; }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }

    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] selectionArgs) { return 0; }
}
