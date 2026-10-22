package com.liubai.blank.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 文件夹:名字 + 大/小形态 + 内含 app 列表。
 * big=true 为大文件夹(内联铺开、直接点);big=false 为小文件夹(2×2 缩略、点开弹层)。
 */
public final class Folder {

    public String name;
    public boolean big;
    public final List<AppRef> apps = new ArrayList<>();

    public Folder(String name, boolean big) {
        this.name = name == null ? "" : name;
        this.big = big;
    }

    public int size() {
        return apps.size();
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("name", name);
        o.put("big", big);
        JSONArray arr = new JSONArray();
        for (AppRef a : apps) arr.put(a.toJson());
        o.put("apps", arr);
        return o;
    }

    public static Folder fromJson(JSONObject o) {
        Folder f = new Folder(o.optString("name"), o.optBoolean("big"));
        JSONArray arr = o.optJSONArray("apps");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject ao = arr.optJSONObject(i);
                if (ao != null) f.apps.add(AppRef.fromJson(ao));
            }
        }
        return f;
    }
}
