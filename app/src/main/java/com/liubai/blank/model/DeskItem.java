package com.liubai.blank.model;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 桌面上的一项:要么是一个 app,要么是一个文件夹。
 * 用一个类统一承载,方便 DesktopView 顺序排布与 LayoutStore 序列化。
 */
public final class DeskItem {

    public enum Kind { APP, FOLDER }

    public final Kind kind;
    public final AppRef app;       // kind==APP 时有效
    public final Folder folder;    // kind==FOLDER 时有效

    private DeskItem(Kind kind, AppRef app, Folder folder) {
        this.kind = kind;
        this.app = app;
        this.folder = folder;
    }

    public static DeskItem app(AppRef a) {
        return new DeskItem(Kind.APP, a, null);
    }

    public static DeskItem folder(Folder f) {
        return new DeskItem(Kind.FOLDER, null, f);
    }

    /** 是否占 2×2(大文件夹)。 */
    public boolean isLarge() {
        return kind == Kind.FOLDER && folder.big;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("kind", kind.name());
        if (kind == Kind.APP) o.put("app", app.toJson());
        else o.put("folder", folder.toJson());
        return o;
    }

    public static DeskItem fromJson(JSONObject o) {
        Kind k = "FOLDER".equals(o.optString("kind")) ? Kind.FOLDER : Kind.APP;
        if (k == Kind.FOLDER) {
            JSONObject fo = o.optJSONObject("folder");
            return folder(fo != null ? Folder.fromJson(fo) : new Folder("", false));
        }
        JSONObject ao = o.optJSONObject("app");
        return app(ao != null ? AppRef.fromJson(ao) : new AppRef("", "", ""));
    }
}
