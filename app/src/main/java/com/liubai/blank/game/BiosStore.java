package com.liubai.blank.game;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 街机 BIOS:**用户自己放**,放进 roms/ 或 system/ 任一处,另一处就地补一份。
 *
 * <h3>为什么要有这个</h3>
 * 有些街机基板光有 ROM 跑不起来:NeoGeo 那一挂(侍魂、拳皇、合金弹头)要
 * {@code neogeo.zip},IGS PGM(三国战纪)要 {@code pgm.zip}。缺了核心会**照样返回
 * 加载成功**,然后自己往画布上画一屏英文错误页(见 {@code bridge.c} 的 g_core_err)。
 *
 * BIOS 是基板厂商的版权固件,开源版不打包,也没有可靠的在线源(yikm 不单独提供;
 * archive.org 车机连不上)。所以只能用户自己放。
 *
 * <h3>落两个地方</h3>
 * {@code roms/} 和 {@code system/} 各一份:{@code fbalpha2012} 把 BIOS 当普通 romset、
 * 只在 roms/ 找;FBNeo 两个目录都找。同一件事两套规矩,用户放哪儿都行,这里补齐另一处。
 */
public final class BiosStore {

    private static final String TAG = "Blank/Bios";

    /** 这份 BIOS 两个目录是不是都有了。 */
    public static boolean installed(String biosFile, File romDir, File systemDir) {
        return has(new File(romDir, biosFile)) && has(new File(systemDir, biosFile));
    }

    private static boolean has(File f) { return f.isFile() && f.length() > 0; }

    /**
     * 用户放了的那份拷到另一个目录。**阻塞**(本地拷贝,几十毫秒)。
     *
     * @return 两个目录都有了返回 true;哪儿都没有(用户还没放)返回 false,交回上层去说实话
     */
    public static boolean install(String biosFile, File romDir, File systemDir) {
        File a = new File(romDir, biosFile), b = new File(systemDir, biosFile);
        if (has(a) && has(b)) return true;
        if (!has(a) && !has(b)) return false;
        File dst = has(a) ? b : a;
        File dir = dst.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return false;
        boolean ok = copy(has(a) ? a : b, dst);
        if (ok) Log.i(TAG, "BIOS 补齐 " + biosFile + " → " + dst.getParent());
        return ok;
    }

    /** 先写 .part 再改名,中途断了不会留半个文件冒充 BIOS。 */
    private static boolean copy(File src, File dst) {
        File part = new File(dst.getAbsolutePath() + ".part");
        try (InputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(part)) {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (Exception e) {
            Log.w(TAG, "拷 " + src + " 失败: " + e);
            part.delete();
            return false;
        }
        if (part.renameTo(dst)) return true;
        part.delete();
        return false;
    }

    private BiosStore() { }
}
