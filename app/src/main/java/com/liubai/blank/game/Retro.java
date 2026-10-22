package com.liubai.blank.game;

import android.view.Surface;

/**
 * libretro 核心的 JNI 门面。
 *
 * 方法都是 static:同一时刻只有一个核心加载着。换游戏靠 {@link #nativeShutdown()}
 * 卸掉再 {@link #nativeInit} 装新的(运行时 dlclose + dlopen,不用重启)。
 */
public final class Retro {

    static { System.loadLibrary("retrobridge"); }

    /** RETRO_DEVICE_ID_JOYPAD_*，跟 libretro.h 里的编号一致。 */
    public static final int B = 0, Y = 1, SELECT = 2, START = 3,
            UP = 4, DOWN = 5, LEFT = 6, RIGHT = 7, A = 8, X = 9,
            L = 10, R = 11;

    /**
     * @param sramPath 电池存档(.srm)落在哪。**空串 = 这一局不管存档**,
     *                 批量诊断走的就是这条,免得在 system/ 下拉一堆空档案。
     *                 路径要按**原始 ROM 文件**的名字算 —— zip 掌机 ROM 解压后
     *                 是一串 hash 名,拿那个当存档名,用户换台机器就对不上了。
     * @return "OK|核心名 版本|API n|分辨率|fps|采样率|ROM 大小|像素格式"，失败则是错误原因
     */
    public static native String nativeInit(String corePath, String romPath, String sysDir,
                                           String sramPath);

    public static native void nativeSetSurface(Surface surface);

    public static native void nativeStart();

    public static native void nativeStop();

    /**
     * 写一个玩家的按键。
     *
     * @param port 玩家号,0 = P1。**每个口都在 nativeInit 里声明过 JOYPAD**,
     *             否则核心根本不会来问那个口的状态 —— 第二个人按什么都没反应,
     *             而且不是"按键没送到",是核心压根没问。
     */
    public static native void nativeKey(int port, int joypadId, boolean down);

    /** 某个玩家断线:把他按着的键全松开(含连发),免得角色一直朝一个方向跑。 */
    public static native void nativeReleaseAll(int port);

    /**
     * 连发键按下/抬起 —— 国行红白机手柄那两颗「连A/连B」。
     *
     * 按住期间这个键由 native 每 6 帧翻转一次(每秒 10 下,RetroArch 的默认节奏)。
     * <b>做在车机侧而不是手机侧</b>:手机到车机往返 17 ms,让手机定时发包的话
     * 连发节奏会被网络抖动带偏,包量还翻十几倍。
     *
     * 和 {@link #nativeKey} 各走各的:真按着的优先,所以同一颗键既能普通按也能连发按。
     */
    public static native void nativeTurbo(int port, int id, boolean down);

    /** 卸载当前核心，为换另一个核心做准备。 */
    public static native void nativeShutdown();

    /** 暂停/继续模拟。暂停时画面停在最后一帧，音频自然静默。 */
    public static native void nativePause(boolean paused);

    public static native boolean nativeIsPaused();

    /** 软复位，相当于重开机；存档不受影响。 */
    public static native void nativeReset();

    /**
     * 把电池存档(SRAM)写出去。没变、没电池、还没回灌完都不写。
     *
     * <b>卡带上那块带电池的内存 —— RPG 的存档、街机的高分榜都在这儿,
     * 核心不会自己落盘,libretro 的规矩是前端负责。</b>
     *
     * @param now 切后台/回列表这种"最后的机会"传 true:当场写完再返回。
     *            定时那条传 false,只挂个请求,模拟线程在下一个帧边界自己写
     *            —— 中途拷会拿到半新半旧的存档。
     * @return 这次真写了没有
     */
    public static native boolean nativeFlushSram(boolean now);

    /**
     * 即时存档:把整台机器这一瞬间拍下来。
     *
     * 和电池存档是两回事 —— 电池存档是卡带上那块内存(游戏自己写的、有电池的卡带才有),
     * 快照是**整个模拟器状态**,所以能停在 BOSS 面前存一下、输了再读回来,
     * 而且任何游戏都能用。
     *
     * 会短暂按停模拟(几十毫秒)再放开:retro_run 跑到一半时拍下来的是半帧,
     * 读回去多半直接崩在核心里。
     */
    public static native boolean nativeSaveState(String path);

    /** 读档。存档尺寸和当前核心对不上(换过核心版本)时**不读**,返回 false。 */
    public static native boolean nativeLoadState(String path);

    /** 这个核心支不支持即时存档。不支持时那两颗键该灰着,而不是点了没反应。 */
    public static native boolean nativeCanState();

    /**
     * 换一整张金手指表:三个一组的 {地址, 字节数, 值},**只传开着的那些**。
     *
     * 传 null 或空数组 = 全关。native 侧每帧把这些值按回去(见 bridge.c 的 apply_cheats)——
     * 「无限命」就是这么来的:游戏扣一次,我们写回去一次。
     */
    public static native void nativeSetCheats(int[] triples);

    /** 这一局能不能用金手指(核心导没导出 SYSTEM_RAM)。不能就把那颗键灰掉。 */
    public static native boolean nativeCanCheat();

    /**
     * 核心报上来的选项表,拍平成 {@code key\tdesc\tvals\tcur} 每行一条。
     *
     * **街机的金手指就在这里头** —— FBNeo 把 cheats/<romset>.ini 里的每条 cheat
     * 变成一个 {@code fbneo-cheat-*} 选项(见 CheatStore.fromVariables)。
     * 装完游戏才有值:那张表是 load_game 的时候才报的。
     */
    public static native String nativeVariables();

    /**
     * 改一个选项的值。值要用核心报的原串(如 {@code "1 - Enabled"}),差一个字都不认。
     *
     * 改完核心下一帧自己会重读、重新应用,不用别的动作。
     */
    public static native boolean nativeSetVariable(String key, String value);

    /**
     * 快进倍数,**×10**(10 = 原速,20 = 两倍,30 = 三倍)。
     *
     * 只放宽节流,每帧照样完整跑 —— 画面和逻辑都是对的,单纯变快。
     * 快进期间**声音丢掉**:核心这时候吐三倍的样本,照原速播是断续的怪叫。
     */
    public static native void nativeSetSpeed(int speed10);

    public static native int nativeGetSpeed();

    /** 取音频给 AudioTrack，返回样本数（交错立体声）。 */
    public static native int nativeReadAudio(short[] out);

    /** 核心要求的采样率。 */
    public static native int nativeSampleRate();

    /** 因缓冲满而丢弃的样本数 —— 一直涨说明缓冲小了。 */
    public static native long nativeAudioDropped();

    /**
     * 全速跑若干秒量核心上限（阻塞，要在后台线程调）。
     *
     * 界面上没有入口 —— 它会抢满一个核心,不该让用户误触。留着是因为换核心、
     * 换车型之后"这台机器还跑得动吗"只能靠它现场量,Mac 上的数推不出车机的
     * (实测差近 4 倍)。要用就临时接根线调它。
     */
    public static native String nativeBench(int seconds);

    /** @return "核心吐帧数|上屏次数|重复帧数|当前分辨率" */
    public static native String nativeStats();

    private Retro() { }
}
