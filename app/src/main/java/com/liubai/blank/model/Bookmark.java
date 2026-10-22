package com.liubai.blank.model;

import org.json.JSONException;
import org.json.JSONObject;

/** 浏览器里存的一条常用网址:标题 + URL。 */
public final class Bookmark {

    public final String title;
    public final String url;

    public Bookmark(String title, String url) {
        this.title = title == null || title.isEmpty() ? url : title;
        this.url = url;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("title", title);
        o.put("url", url);
        return o;
    }

    public static Bookmark fromJson(JSONObject o) {
        return new Bookmark(o.optString("title"), o.optString("url"));
    }
}
