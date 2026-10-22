package com.liubai.blank.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.liubai.blank.model.CarCtrlItem;

import java.util.ArrayList;
import java.util.List;

/**
 * 车控卡片上摆了哪几项、什么顺序。与 {@link DockStore} 同构,只是简单得多 ——
 * 这里没有空格、没有多格项,就是一串 key。
 *
 * 存成 `;` 分隔的字符串而不是 JSON:一项只有 key 一个字段,JSON 那套架子撑不出信息。
 * (迪友那张卡也是这么存的,反编译 3.0.1 `h4/d.java` 的
 * `KEY_DEFAULT_DESKTOP_CARD_CAR_CONTROL_CARD_DEFAULT_INFO`。)
 */
public final class CarCtrlStore {

    private static final String PREFS = "car_ctrl_card";
    private static final String KEY = "items";
    private static final String KEY_VER = "ver";
    /** 用户自己排过了。置位后默认项升级不再覆盖他的排法(同 DockStore.KEY_DIRTY)。 */
    private static final String KEY_DIRTY = "user_edited";
    private static final String SEP = ";";

    /**
     * 默认项的版本。**每次改 {@link #buildDefault} 就要 +1**,否则老设备上存着的旧配置
     * 永远不会更新(load 只在没数据/版本落后时才落默认),新加的项谁也看不见。
     */
    private static final int VER = 3;

    private final SharedPreferences prefs;

    public CarCtrlStore(Context ctx) {
        this.prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * 默认摆什么。
     *
     * **不默认全摆**:枚举里十几项,全铺开会横穿半个桌面,而且其中只有座椅四项是真车
     * 验过的(见 {@link CarCtrlItem} 类注释)。默认给验过的四项加两个最常用的车窗动作,
     * 其余留在配置面板里由车主自己挑 —— 挑的时候他也就看见"未验证"那行提示了。
     */
    private static List<CarCtrlItem> buildDefault() {
        List<CarCtrlItem> out = new ArrayList<>();
        out.add(CarCtrlItem.SEAT_VENT_L);
        out.add(CarCtrlItem.SEAT_HEAT_L);
        out.add(CarCtrlItem.SEAT_VENT_R);
        out.add(CarCtrlItem.SEAT_HEAT_R);
        // 座椅记忆:2026-08-21 集成,默认摆上。它一张卡弹菜单选「恢复/保存 + 位置1/2」,
        // 未真车验证(CAN 侧可能丢帧),但原厂「主驾位置」就是这么走的,先给常用入口。
        out.add(CarCtrlItem.SEAT_MEMORY);
        out.add(CarCtrlItem.WINDOW_CLOSE);
        out.add(CarCtrlItem.WINDOW_BREATH);
        return out;
    }

    /** 配置面板用:全部可选项,按枚举声明顺序(座椅 → 开关 → 动作)。 */
    public static List<CarCtrlItem> all() {
        List<CarCtrlItem> out = new ArrayList<>();
        for (CarCtrlItem it : CarCtrlItem.values()) out.add(it);
        return out;
    }

    public List<CarCtrlItem> load() {
        String raw = prefs.getString(KEY, null);
        boolean userEdited = prefs.getBoolean(KEY_DIRTY, false);
        // 没数据、或者默认项升级了而用户又没动过 → 落一份新默认
        if (raw == null || (!userEdited && prefs.getInt(KEY_VER, 0) < VER)) {
            List<CarCtrlItem> def = buildDefault();
            save(def);
            return def;
        }
        List<CarCtrlItem> out = new ArrayList<>();
        for (String k : raw.split(SEP)) {
            // 认不出的 key 直接跳过:降过级、或者项被改名。丢一项也比整张卡崩掉强
            CarCtrlItem it = CarCtrlItem.of(k.trim());
            if (it != null && !out.contains(it)) out.add(it);
        }
        return out;
    }

    public void save(List<CarCtrlItem> items) {
        List<String> keys = new ArrayList<>();
        if (items != null) {
            for (CarCtrlItem it : items) {
                if (it != null) keys.add(it.key);
            }
        }
        prefs.edit()
                .putString(KEY, TextUtils.join(SEP, keys))
                .putInt(KEY_VER, VER)
                .apply();
    }

    /** 用户亲手排过了:以后升级默认项不再覆盖他的顺序。 */
    public void markUserEdited() {
        prefs.edit().putBoolean(KEY_DIRTY, true).apply();
    }

    /** 恢复默认,并清掉「用户排过」的标记。 */
    public List<CarCtrlItem> reset() {
        List<CarCtrlItem> def = buildDefault();
        save(def);
        prefs.edit().putBoolean(KEY_DIRTY, false).apply();
        return def;
    }
}
