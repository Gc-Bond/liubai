package com.liubai.blank.data;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 两张画中画卡的宽度分配 + 持久化。
 * 与页面结构(怎么摆)/ 模式(长啥样)平行、正交。
 *
 * 存的是**左卡占可用宽度的比例**。早先存的是"相对基准宽度的倍数",
 * 基准按卡片自身形状算(页面高 × 0.85,略窄于正方形)——那是给音乐卡定的:
 * 封面主导的卡片要接近正方形才好看,右边剩多少归网格。
 * 现在两边都是 app 界面,这个前提没了,而按形状算出来的三档恰好一档都不对称
 * (实测 41/55/69,默认那档左边只占 41%),所以改回最直白的比例分配。
 */
public final class CardSizeStore {

    /**
     * 档位:左卡占可用宽度(总宽减去握把)的比例。按从小到大排,拖拽吸附要靠这个顺序。
     *
     * 对称居中,两侧各偏一档——点一下的循环是 50 → 65 → 35 → 50,
     * 想看左边和想看右边都有档位可用。
     */
    public static final float[] STOPS = { 0.35f, 0.5f, 0.65f };

    /** 默认对称。两个 app 并排时没有理由偏袒哪一边。 */
    public static final float DEFAULT = 0.5f;

    private static final String PREFS = "card_size";
    /** 换了新键:旧值存的是倍数(1.0~1.7),拿去当比例用会让左卡直接撑满。 */
    private static final String KEY = "ratio";

    private final SharedPreferences prefs;

    public CardSizeStore(Context ctx) {
        this.prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public float scale() {
        return clamp(prefs.getFloat(KEY, DEFAULT));
    }

    public void set(float s) {
        prefs.edit().putFloat(KEY, clamp(s)).apply();
    }

    /**
     * 下一档,到头绕回第一档。
     *
     * 给"点一下握把就换一档"用:车上手指粗、车还在动,拖着精调不现实,
     * 点一下跳一档才是能在行车中完成的操作。
     */
    public static float next(float s) {
        float cur = snap(s);
        for (int i = 0; i < STOPS.length; i++) {
            if (STOPS[i] == cur) return STOPS[(i + 1) % STOPS.length];
        }
        return DEFAULT;
    }

    /** 吸附到最近档位。 */
    public static float snap(float s) {
        float best = STOPS[0];
        float bestDist = Float.MAX_VALUE;
        for (float stop : STOPS) {
            float d = Math.abs(stop - s);
            if (d < bestDist) { bestDist = d; best = stop; }
        }
        return best;
    }

    /** 夹在首末档之间:拖过头也不会把任何一张卡挤没。 */
    public static float clamp(float s) {
        return Math.max(STOPS[0], Math.min(STOPS[STOPS.length - 1], s));
    }
}
