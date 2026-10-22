package com.liubai.blank.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.liubai.blank.model.StatusItem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 顶栏上放哪几项、按什么顺序。
 *
 * <h3>就是一条有序列表,没有"左边/右边"</h3>
 *
 * 照 DiStyle 那套(反编译 v2.1.6:顶栏是一条 {@code RecyclerView},编辑页
 * {@code CustomStatusBarFragment} 是个 8 列网格):项排成一条,**间距和左右分布靠往里
 * 插占位项**(它有 66/77/88 三档"占位-小/中/大",默认编排 {@code [66, 1010, 88, 77]})。
 *
 * 早先这里做成了"每项选左边还是右边",是拍脑袋定的、不是抄来的 —— 用户当场问回来
 * "你确定 DiStyle 这么做的?"。翻了它的编辑页才知道是占位项那套,已经改过来。
 *
 * 留白比它多一档{@link StatusItem#SPACER_FLEX 弹性占位}:顶栏是通栏 match_parent 的,
 * 没有它没法把一串项贴到右边缘(DiStyle 那条 RecyclerView 是 wrap 的,用不着)。
 *
 * 存的是 key 串(`time:date:sp_flex`)而不是 ordinal —— 枚举加一项就把序号全冲了。
 */
public final class StatusBarStore {

    private static final String PREFS = "status_bar";   // 和顶栏开关同一份
    private static final String KEY_ITEMS = "items";
    private static final String SEP = ":";

    /**
     * 默认编排:时间 · 日期 · 撑开 · 车内温度 · 蓝牙 · Wi-Fi。
     *
     * 就是可编排之前那条顶栏的样子 —— 中间那条"撑开"正是原来 XML 里
     * 那个 {@code weight=1} 的 Space,现在它成了一个能被挪走的项。
     */
    private static final List<StatusItem> DEFAULT = Arrays.asList(
            StatusItem.TIME, StatusItem.DATE, StatusItem.SPACER_FLEX,
            StatusItem.TEMP_IN, StatusItem.BLUETOOTH, StatusItem.WIFI);

    private final SharedPreferences prefs;

    public StatusBarStore(Context ctx) {
        this.prefs = ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 当前编排。顺序就是顶栏上从左到右的顺序。 */
    public List<StatusItem> items() {
        String raw = prefs.getString(KEY_ITEMS, null);
        if (raw == null) return new ArrayList<>(DEFAULT);   // 从没编排过 → 默认那套
        List<StatusItem> out = new ArrayList<>();
        if (TextUtils.isEmpty(raw)) return out;             // 编排过、清空了
        for (String k : raw.split(SEP)) {
            StatusItem i = StatusItem.byKey(k);
            // 认不出的键直接丢:降级装回旧版本、或者哪天删掉某一项时,存档里会留下它
            if (i == null) continue;
            // **占位项可以重复,信息项不行**:同一个读数放两遍没意义,而且车况那几项
            // 走 native bus,重复一次就是跟车控多抢一次帧
            if (!i.isSpacer() && out.contains(i)) continue;
            out.add(i);
        }
        return out;
    }

    public void save(List<StatusItem> items) {
        StringBuilder sb = new StringBuilder();
        for (StatusItem i : items) {
            if (sb.length() > 0) sb.append(SEP);
            sb.append(i.key);
        }
        prefs.edit().putString(KEY_ITEMS, sb.toString()).apply();
    }

    /** 追加到末尾。 */
    public void add(StatusItem item) {
        List<StatusItem> list = items();
        if (!item.isSpacer() && list.contains(item)) return;
        list.add(item);
        save(list);
    }

    /** 插到第 {@code at} 格(0 = 最前)。去重规则同 {@link #add},占位项可重复。 */
    public void addAt(int at, StatusItem item) {
        List<StatusItem> list = items();
        if (!item.isSpacer() && list.contains(item)) return;
        if (at < 0 || at > list.size()) at = list.size();
        list.add(at, item);
        save(list);
    }

    /** 拿掉第 {@code at} 项(按下标,因为占位项可以重复出现)。 */
    public void removeAt(int at) {
        List<StatusItem> list = items();
        if (at < 0 || at >= list.size()) return;
        list.remove(at);
        save(list);
    }

    /** 把第 {@code at} 项挪到 {@code to} 的位置。 */
    public void move(int at, int to) {
        List<StatusItem> list = items();
        if (at < 0 || at >= list.size() || to < 0 || to >= list.size() || at == to) return;
        StatusItem item = list.remove(at);
        list.add(to, item);
        save(list);
    }

    /** 还没放上去的信息项(占位项永远可以再加,所以不在这份名单里判重)。 */
    public List<StatusItem> candidates() {
        List<StatusItem> on = items();
        List<StatusItem> out = new ArrayList<>();
        for (StatusItem i : StatusItem.values()) {
            if (i.isSpacer() || !on.contains(i)) out.add(i);
        }
        return out;
    }

    /** 恢复默认编排。 */
    public void reset() {
        prefs.edit().remove(KEY_ITEMS).apply();
    }
}
