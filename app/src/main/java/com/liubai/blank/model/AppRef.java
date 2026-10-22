package com.liubai.blank.model;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 一个可启动应用的引用:包名 + 入口类 + 显示名(缓存,避免反复 loadLabel)。
 * 桌面布局里存的是它,而非重量级的 ResolveInfo。
 */
public final class AppRef {

    public final String pkg;
    public final String cls;
    public final String label;

    public AppRef(String pkg, String cls, String label) {
        this.pkg = pkg;
        this.cls = cls == null ? "" : cls;
        this.label = label == null ? pkg : label;
    }

    /** 组件是否相同(判重、判是否已在某文件夹里)。 */
    public boolean sameComponent(AppRef o) {
        return o != null && pkg.equals(o.pkg) && cls.equals(o.cls);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("pkg", pkg);
        o.put("cls", cls);
        o.put("label", label);
        return o;
    }

    public static AppRef fromJson(JSONObject o) {
        return new AppRef(o.optString("pkg"), o.optString("cls"), o.optString("label"));
    }
}
