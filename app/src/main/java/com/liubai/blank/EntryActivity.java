package com.liubai.blank;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import com.liubai.blank.util.DefaultHome;

/**
 * 抽屉里那个「留白」图标,唯一常亮的入口。**它本身没有界面**,只做两件事:
 * 打开 HOME 入口({@link DefaultHome#enableHomeEntry}) → 跳进 {@link HomeActivity}。
 *
 * <p><b>为什么要它</b>:{@code HomeActivity} 在 manifest 里是 {@code enabled="false"} 的
 * —— 车机 PackageInstaller 拒装带 HOME 的 apk,理由见 {@link DefaultHome#enableHomeEntry}。
 * 关着的组件既不长图标、也不会被系统当桌面拉起,所以必须有个常亮的入口把它打开。
 *
 * <p><b>为什么 HomeActivity 不再声明 LAUNCHER</b>:图标归这里独一份。留在那边的话,
 * HOME 一启用抽屉里就成了两个「留白」。
 *
 * <p><b>为什么用完不把自己关掉</b>:关了图标就消失,万一用户没把留白设成默认桌面,
 * 就再也找不到入口了(留白没有开机自启)。留着它,任何时候都能从抽屉回来。
 */
public class EntryActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DefaultHome.enableHomeEntry(this);
        try {
            Intent it = new Intent(this, HomeActivity.class);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(it);
        } catch (Exception e) {
            // 组件刚启用、系统还没认到的极端情况:说一声,再点一次就好
            Toast.makeText(this, "正在初始化,请再点一次", Toast.LENGTH_SHORT).show();
        }
        finish();
    }
}
