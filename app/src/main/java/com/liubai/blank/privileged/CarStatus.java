package com.liubai.blank.privileged;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 车况读取:续航、保养里程、胎压。**读车况是免费的**,和车控完全两回事。
 *
 * <h3>两个来源</h3>
 *
 * <b>① 续航 / 保养里程 —— ContentProvider,不需要提权</b>
 *
 * {@code content://com.byd.carStatusProvider/car_status},是张 key-value 表
 * (列名就叫 {@code key} / {@code value},见反编译的
 * {@code carsnapshot/framework/.../android/provider/CarStatusSetting.java})。
 *
 * 权限 {@code com.byd.permission.carstatus.READ_PROVIDER} 是 **normal 级**
 * (AutoPermission.apk 里 protectionLevel=0x00)—— 在 manifest 里声明一下就自动获得,
 * 不需要签名、不需要提权、不弹授权框。
 *
 * ⚠️ 有个坑别照抄:{@code adb shell content query} 读这个 provider 会被拒,那**不是**
 * 提权不够,是 {@code content} 命令自己没声明那条权限。app 声明了就能读。
 *
 * <b>② 胎压 —— native bus,和车控同一条</b>
 *
 * 胎压不在上面那张表里,它在 {@code BYDAutoTyreDevice}(设备号 1016)。走
 * {@link CarCtrl#get} 那条,所以**胎压要提权就绪才读得到**,续航里程则不用。
 *
 * <h3>⚠️ 模拟器上读不到</h3>
 *
 * {@code com.byd.providers.carstatus} 是车机预置的,模拟器没有(carsnapshot/packages.txt
 * 里有、AVD 里没有)。所以这张卡在模拟器上只能验"读不到时长什么样",数值对不对**只能真车看**。
 */
public final class CarStatus {

    private static final String TAG = "BlankCarStatus";

    private static final Uri CONTENT_URI =
            Uri.parse("content://com.byd.carStatusProvider/car_status");
    private static final String COL_KEY = "key";
    private static final String COL_VALUE = "value";

    // 字段名来自 CarStatusSetting.TravelInfoData
    private static final String K_MAINT = "car_status_maintenance_mile";  // 距下次保养

    /** 胎压:四个轮子各一个字段(BYDAutoFeatureIds),设备号 TYRE。 */
    private static final int DEV_TYRE = 1016;
    private static final String[] TYRE_FIELDS = {
            "TYRE_PRESSURE_VALUE_LEFT_FRONT",  "TYRE_PRESSURE_VALUE_RIGHT_FRONT",
            "TYRE_PRESSURE_VALUE_LEFT_REAR",   "TYRE_PRESSURE_VALUE_RIGHT_REAR" };

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "blank-carstatus");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private CarStatus() {}

    /**
     * 一次读到的车况。读不到的字段是 null / -1,**别拿 0 当默认值** —— 0 是个合法读数。
     *
     * <h3>这里没有「当前续航」,别再找了</h3>
     *
     * 真车上把整张表 dump 过(17 行,全部字段见下),**没有任何一项是当前续航**:
     * {@code travel_points_elec} / {@code travel_points_fuel} 名字里的 points 是实话 ——
     * 值是 150 多个 `#` 分隔的点(实测 953 字符),是仪表能耗统计页那条曲线的数据,
     * 还各自带 {@code _one} / {@code _two} 两个变体。早先当成"车端给什么显示什么"的
     * 单值直接铺到卡片上,于是屏幕中间糊了一大串 `48#85#99#…`,还把后面几格挤出了屏幕。
     *
     * framework 的 {@code BYDAutoFeatureIds} 里也搜不到 RANGE / ENDURANCE / DTE /
     * REMAIN 这类字段 —— 续航多半是仪表自己算的,没往外给。所以这张卡只留读得到的:
     * 距保养(表里的 {@code car_status_maintenance_mile},实测 2400)和四轮胎压。
     */
    public static final class Data {
        /** 距下次保养还有多少公里。 */
        public final String maintenance;
        /**
         * 四轮胎压,顺序:左前 / 右前 / 左后 / 右后。-1 = 读不到。
         *
         * **不做换算**:framework 里 {@code getTyrePressureValue} 返回的就是 0~4094 的
         * 原始值,没有换算公式;迪友也是 {@code String.valueOf()} 原样显示
         * (反编译 3.0.1,a4/n.java:55)。真车上对一眼原车仪表就知道单位了。
         */
        public final int[] tyre;

        Data(String maintenance, int[] tyre) {
            this.maintenance = maintenance; this.tyre = tyre;
        }

        /** 一个都没读到 = 这台车/这个环境根本没有车况源,卡片该整张收起来。 */
        public boolean empty() {
            if (maintenance != null) return false;
            for (int t : tyre) {
                if (t >= 0) return false;
            }
            return true;
        }
    }

    public interface Listener {
        /** 主线程回调。 */
        void onStatus(Data d);
    }

    /** 读一次。provider 查询和 native 读都不能占主线程。 */
    public static void refresh(final Context ctx, final Listener l) {
        final Context app = ctx.getApplicationContext();
        IO.execute(() -> {
            String maint = query(app, K_MAINT);

            int[] tyre = {-1, -1, -1, -1};
            // 胎压走 native,要提权就绪;续航那几个不用,所以分开判
            if (CarCtrl.available()) {
                for (int i = 0; i < TYRE_FIELDS.length; i++) {
                    int v = CarCtrl.get(DEV_TYRE, TYRE_FIELDS[i]);
                    // framework 里 >4094 是无效标记,负数是错误码,一律当读不到
                    tyre[i] = (v < 0 || v > 4094) ? -1 : v;
                }
            }
            final Data d = new Data(maint, tyre);
            if (l != null) MAIN.post(() -> l.onStatus(d));
        });
    }

    /**
     * 查一个 key。这张表是 key-value 结构,所以是「按 key 过滤、取 value 列」,
     * 不是把列名当字段查。
     */
    private static String query(Context ctx, String key) {
        Cursor c = null;
        try {
            ContentResolver r = ctx.getContentResolver();
            c = r.query(CONTENT_URI, new String[]{COL_VALUE},
                    COL_KEY + "=?", new String[]{key}, null);
            if (c != null && c.moveToFirst()) {
                String v = c.getString(0);
                return (v == null || v.trim().isEmpty()) ? null : v.trim();
            }
        } catch (Throwable t) {
            // provider 不在(模拟器)、没权限、表结构变了 —— 都当读不到,不让卡片崩
            Log.i(TAG, "读 " + key + " 失败: " + t);
        } finally {
            if (c != null) c.close();
        }
        return null;
    }
}
