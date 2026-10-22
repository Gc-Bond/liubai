// libretro 核心的最小宿主 —— 只为回答一个问题:
// 原生核心能不能在车机同架构(arm64)的 Android 上跑起来并把画面推上屏。
//
// 刻意不做的:音频(给空实现)、存档、核心选项、多核心管理。
// 那些都是常规活,不是风险点。

#include <jni.h>
#include <dlfcn.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <stdarg.h>
#include <pthread.h>
#include <time.h>
#include <android/log.h>
#include <android/native_window_jni.h>

#include "libretro.h"

#define TAG "RetroBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ---- 核心的函数指针 ----
static void *g_lib;
/**
 * ROM 数据。核心 need_fullpath=false 时要我们把整个文件读进内存喂给它,
 * 而 libretro 允许核心**一直持有这个指针到 retro_unload_game**,
 * 所以只能在 nativeShutdown 里等 unload 完再 free —— 谁读的谁释放,核心不管这块。
 */
static void *g_rom;
/**
 * retro_load_game 成功了没有。**不能拿 g_lib 当判据** —— ROM 坏掉时 dlopen 照样成功,
 * 只是核心里没装进游戏,这时候放线程去跑 retro_run 就是拿空指针开火:
 * 实测一个 0 字节的 .nes 就能让 FCEUmm 在 retro_run 里 SIGSEGV,
 * 而这里是桌面进程,一崩系统就弹「选择主屏幕应用」。
 */
static int g_game_ok;
static void     (*p_init)(void);
static void     (*p_deinit)(void);
static unsigned (*p_api_version)(void);
static void     (*p_get_system_info)(struct retro_system_info *);
static void     (*p_get_system_av_info)(struct retro_system_av_info *);
static void     (*p_set_environment)(retro_environment_t);
static void     (*p_set_video_refresh)(retro_video_refresh_t);
static void     (*p_set_audio_sample)(retro_audio_sample_t);
static void     (*p_set_audio_sample_batch)(retro_audio_sample_batch_t);
static void     (*p_set_input_poll)(retro_input_poll_t);
static void     (*p_set_input_state)(retro_input_state_t);
static void     (*p_set_controller_port_device)(unsigned, unsigned);
static bool     (*p_load_game)(const struct retro_game_info *);
static void     (*p_unload_game)(void);
static void     (*p_run)(void);
static void     (*p_reset)(void);
/**
 * 电池存档(SRAM)。卡带上那块带电池的内存 —— RPG 的存档、街机的高分榜、
 * 游戏自己的设置都写在这儿,**核心不会自己落盘,libretro 的规矩是前端负责**。
 *
 * 不接的后果不是"少个功能",是**存档在假装能用**:游戏里存了档,退出就没了。
 *
 * 按规范这两个符号是必需导出的,但缺了也只是没有电池存档而已,
 * 不该让整个核心加载不了 —— 所以走可选 dlsym,不进 SYM 宏。
 */
static void    *(*p_get_memory_data)(unsigned);
static size_t   (*p_get_memory_size)(unsigned);
/**
 * 即时存档(快照):把整台机器这一瞬间的状态整个拍下来,读回去接着打。
 *
 * 和电池存档是两回事 —— 电池存档是卡带上那块内存(游戏自己写的),
 * 快照是**整个模拟器的状态**(CPU 寄存器、显存、音频芯片全在里面),
 * 所以能停在 BOSS 面前存一下,输了再读回来。
 *
 * 同样走可选 dlsym:不支持快照的核心照样能玩,只是那两颗键该灰着。
 */
static size_t   (*p_serialize_size)(void);
static bool     (*p_serialize)(void *, size_t);
static bool     (*p_unserialize)(const void *, size_t);

// ---- 运行时状态 ----
static ANativeWindow *g_win;
static pthread_mutex_t g_win_lock = PTHREAD_MUTEX_INITIALIZER;
static enum retro_pixel_format g_fmt = RETRO_PIXEL_FORMAT_0RGB1555;  // 规范规定的默认值
static struct retro_system_av_info g_av;
static volatile int  g_running;
static volatile int  g_paused;
static pthread_t     g_thread;
static char          g_sysdir[512];

// ---- 电池存档 ----
/** .srm 落在哪。空串 = 这一局不管存档(批量诊断走的就是这条)。 */
static char          g_srampath[512];
/**
 * 上次落盘的那一份。用来判断"到底变没变" —— 六十秒一次全量写外部存储,
 * 而绝大多数时候一个字节都没动过。
 */
static void         *g_sram_last;
static size_t        g_sram_last_size;
/** 请模拟线程在**帧边界**落一次盘(见 run_loop)。中途 memcpy 会读到半新半旧的存档。 */
static volatile int  g_sram_flush_req;
/**
 * 回灌做完了没有。**没做完绝不许往回写。**
 *
 * 加载失败那条路(缺 BIOS、romset 不全)也会走到 unload_all,那时核心里的
 * SRAM 是它自己初始化的空白 —— 照着写回去,用户攒了几十小时的存档就没了。
 */
static volatile int  g_sram_ready;
/** 模拟线程确认停在暂停分支上了。同步落盘要等它,理由见 nativeFlushSram。 */
static volatile int  g_in_pause;

/**
 * 快进倍数(×10,所以 10 = 原速、30 = 三倍速)。
 *
 * 只改**节流**,不改模拟本身 —— 每帧照常跑完整的 retro_run,只是不等那么久
 * 再跑下一帧。所以画面和逻辑都是对的,单纯变快。
 *
 * 车机上余量足够(最紧的 GBA 也有 11.4 倍),三倍速是稳的。
 */
static volatile int  g_speed10 = 10;

// 诊断计数:核心吐了多少帧 / 真正上屏多少次 / 收到多少个"重复帧"(data==NULL)
static volatile long g_frames, g_blits, g_dupes;
static volatile int  g_last_w, g_last_h;

// 按键延迟测量:记下按键到达的时刻,等下一帧真正推上屏时算差值。
// 量的是"按键进入本机 → 画面推出去"这一段 —— 网络那半由手机侧的 ping 负责,
// 合成到亮屏那半是系统开销,测不到。三段加起来才是你手指感觉到的延迟。
static volatile long long g_key_ts;        // 0 = 当前没有待测的按键
static volatile long g_key_lat_us, g_key_lat_max_us;

static long long now_ns(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (long long) t.tv_sec * 1000000000LL + t.tv_nsec;
}

/** 同时支持几个手柄口。街机的恐龙快打、TMNT 能上四个,NES 两个,GB 系只有一个。 */
#define MAX_PORTS 4

// 手柄状态。第一维是玩家(port),第二维的索引就是 RETRO_DEVICE_ID_JOYPAD_*,
// 核心每帧按 port 来问。手机手柄那条 WebSocket 收到什么就往这里写什么 —— 没有事件注入。
static volatile int16_t g_pad[MAX_PORTS][16];

/**
 * 连发(自动连点)按住状态。国行红白机手柄那两颗「连A/连B」干的事:
 * 手指按着不动,手柄自己反复按 —— 魂斗罗、沙罗曼蛇、1942 那类射击游戏的刚需。
 *
 * <b>必须在这一侧做,不能让手机去定时发包。</b>手机到车机往返 17 ms,
 * 定时器会被网络抖动带偏,连发节奏时快时慢;而且包量翻十几倍。
 * 放这儿是按**帧**翻转的,精确、零额外流量 —— RetroArch 也是分在输入层做的。
 */
static volatile int16_t g_turbo[MAX_PORTS][16];
/** 帧号,run_loop 每跑一帧 +1。连发按它算相位。 */
static volatile long g_tick;

/**
 * 连发的节奏:**6 帧一个周期,前 3 帧按下**。
 *
 * 60 fps 下就是每秒 10 次、占空比 50% —— 直接用 RetroArch 的默认值
 * (Turbo Period 6 / Turbo Duty Cycle 3),那是被无数人调过的数。
 * 不做成设置项:车上不该让人调这种参数。
 */
#define TURBO_PERIOD 6
#define TURBO_DUTY   3

// ---- libretro 回调 ----

/**
 * 核心自己的日志。**必须实现,不能只返回 false。**
 *
 * libretro 的规矩是核心先问 GET_LOG_INTERFACE、拿不到就该自己降级,但有的核心不检查
 * 返回值,直接就用结构体里那个指针 —— 而我们没填,它是 0,于是 retro_init 里
 * 一个 pc=0 的跳转把整个进程带走(MAME 2003 Plus 实测)。
 *
 * 接上之后还白捡一件事:街机核心加载失败时的原因(缺哪个 ROM 文件、romset 版本对不对)
 * 全从这儿出来 —— 否则只能看到一句干巴巴的 "Romset is unknown"。
 */
/**
 * 只在**加载阶段**放核心的絮叨过去,跑起来就闭嘴。
 *
 * 不能只降级到 VERBOSE 了事:VERBOSE 照样写进 logcat 环形缓冲,而 MAME 把每一帧的
 * CPU 中断都当 INFO 打,一秒几百行,几秒钟就把缓冲冲爆 —— 自己的日志全被挤掉,
 * 排查时反而什么都看不见(实测)。
 *
 * 而加载阶段的絮叨恰恰是金矿:街机核心为什么加载失败(缺哪个 ROM 文件、
 * romset 版本对不对)全在那几行里。
 */
static volatile int g_core_log_on = 1;

/**
 * 核心加载期报出来的**硬伤原文**(缺 BIOS、romset 不全)。空 = 这一局没报。
 *
 * 为什么要留这么一个东西:FBNeo 遇到缺文件时 <b>retro_load_game 照样返回 true</b>,
 * 然后自己往画布上画一屏英文错误页("Verify the following romsets : kov2 pgm")。
 * 从返回值和 av_info 看,一切正常 —— 于是我们照常进游戏页,用户对着一屏英文,
 * 界面上一个字都不说,看着像程序坏了。真话只在核心的日志里:
 *   [FBNeo] ROM at index 128 with name pgm_t01s.rom and CRC 0x1a7123a0 is required
 * 把这行截下来,{@code nativeInit} 才能报出"缺 pgm.zip"而不是一句 OK。
 */
static char g_core_err[256];

static void core_log(enum retro_log_level level, const char *fmt, ...) {
    // 缺文件那几行是 ERROR 级,只记头一条(后面几条是同一件事的清单)
    if (level >= RETRO_LOG_ERROR && !g_core_err[0]
            && (strstr(fmt, "is required") || strstr(fmt, "Verify the following"))) {
        va_list cp;
        va_start(cp, fmt);
        vsnprintf(g_core_err, sizeof(g_core_err), fmt, cp);
        va_end(cp);
    }
    if (level < RETRO_LOG_WARN && !g_core_log_on) return;
    va_list ap;
    va_start(ap, fmt);
    int prio = level == RETRO_LOG_ERROR ? ANDROID_LOG_ERROR
             : level == RETRO_LOG_WARN  ? ANDROID_LOG_WARN
             : ANDROID_LOG_INFO;
    __android_log_vprint(prio, "RetroCore", fmt, ap);
    va_end(ap);
}

// ---- 核心选项(libretro variables) ----
//
// 核心用 SET_VARIABLES 把自己认的开关一次性报上来,格式是
// {key, "描述; 默认值|值2|值3"};之后它随时会 GET_VARIABLE 来问某个 key 现在是什么。
//
// **这层原先没做**(只硬编码回答了两个 mGBA 的 key),而街机的金手指恰恰全在这儿:
// FBNeo 在 load_game 里读 `<system>/fbneo/cheats/<romset>.ini`,把每条 cheat 变成
// 一个 `fbneo-cheat-<n>-<romset>-<名字>` 选项报上来,再按我们回答的值调 CheatEnable
// (它自己每帧写内存,含多 CPU、指针型那些我们做不了的花样)。所以街机金手指不用
// 我们解析 ini,只要把这层协议接通。
//
// **故意不响应 GET_CORE_OPTIONS_VERSION** —— 那会让核心改用 SET_CORE_OPTIONS_V2
// 那套带分类、带 intl 的结构;不答它就退回最老也最简单的 SET_VARIABLES,
// 一个字符串拆一下就完事。

#define MAX_VARS 1024

struct core_var {
    char *key;      // "fbneo-cheat-3-dino-Infinite_Time"
    char *desc;     // "[Cheat][dino.ini] Infinite Time"
    char *vals;     // "0 - Disabled|1 - Enabled"
    char *cur;      // 当前值,一开始是默认值(vals 的第一段)
    /**
     * 这个 key 我们**答不答**。只有 Java 明确设过的才答(见 nativeSetVariable)。
     *
     * 收着答是有实测教训的:一开始是"表里有就答默认值",结果 FCEUmm 的画面从
     * 256×240 变成了 256×224 —— 它声明的第一个值恰好是"裁掉 overscan",而在这之前
     * 我们对未知 key 一律不答、核心用的是自己那套默认。**我们要的只是金手指,
     * 不是顺手改掉每个核心的默认行为。**
     */
    int answer;
};
static struct core_var g_vars[MAX_VARS];
static int g_var_n;
/** 值被改过了。核心每帧问一次 GET_VARIABLE_UPDATE,答 true 它就重新读一遍并重新应用。 */
static volatile int g_var_dirty;

static char *dup_n(const char *s, size_t n) {
    char *p = (char *) malloc(n + 1);
    if (!p) return NULL;
    memcpy(p, s, n);
    p[n] = 0;
    return p;
}

static void vars_clear(void) {
    for (int i = 0; i < g_var_n; i++) {
        free(g_vars[i].key); free(g_vars[i].desc);
        free(g_vars[i].vals); free(g_vars[i].cur);
    }
    memset(g_vars, 0, sizeof(g_vars));
    g_var_n = 0;
    g_var_dirty = 0;
}

/** 收下核心报的整张选项表。会被调用不止一次(装游戏时会再报一次,那次才有金手指)。 */
static void vars_set(const struct retro_variable *list) {
    vars_clear();
    if (!list) return;
    for (; list->key && list->value && g_var_n < MAX_VARS; list++) {
        const char *v = list->value;
        const char *semi = strchr(v, ';');
        struct core_var *cv = &g_vars[g_var_n];
        cv->key = strdup(list->key);
        if (semi) {
            cv->desc = dup_n(v, (size_t) (semi - v));
            const char *rest = semi + 1;
            while (*rest == ' ') rest++;
            cv->vals = strdup(rest);
        } else {
            cv->desc = strdup(v);
            cv->vals = strdup("");
        }
        // 默认值 = 第一段。核心自己就是这么摆的(见 FBNeo retro_common.cpp:
        // "Default value goes first")
        const char *bar = strchr(cv->vals, '|');
        cv->cur = bar ? dup_n(cv->vals, (size_t) (bar - cv->vals)) : strdup(cv->vals);
        if (!cv->key || !cv->desc || !cv->vals || !cv->cur) { vars_clear(); return; }
        g_var_n++;
    }
    LOGI("核心报了 %d 个选项", g_var_n);
}

static struct core_var *var_find(const char *key) {
    for (int i = 0; i < g_var_n; i++) {
        if (g_vars[i].key && strcmp(g_vars[i].key, key) == 0) return &g_vars[i];
    }
    return NULL;
}

static bool env_cb(unsigned cmd, void *data) {
    switch (cmd) {
        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE: {
            struct retro_log_callback *cb = (struct retro_log_callback *) data;
            if (!cb) return false;
            cb->log = core_log;
            return true;
        }

        case RETRO_ENVIRONMENT_GET_CAN_DUPE:
            *(bool *) data = true;
            return true;

        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
            enum retro_pixel_format f = *(const enum retro_pixel_format *) data;
            // 只认这三种,其中 XRGB8888 和 RGB565 是实际会遇到的
            if (f != RETRO_PIXEL_FORMAT_0RGB1555 &&
                f != RETRO_PIXEL_FORMAT_XRGB8888 &&
                f != RETRO_PIXEL_FORMAT_RGB565) return false;
            g_fmt = f;
            LOGI("核心要求像素格式 = %d (0=0RGB1555 1=XRGB8888 2=RGB565)", f);
            return true;
        }

        // 核心要拿 BIOS / 存档目录。给了它才不会在需要时直接失败。
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
            *(const char **) data = g_sysdir;
            return true;

        case RETRO_ENVIRONMENT_SET_VARIABLES:
            vars_set((const struct retro_variable *) data);
            return true;

        case RETRO_ENVIRONMENT_GET_VARIABLE: {
            struct retro_variable *var = (struct retro_variable *) data;
            if (!var || !var->key) return false;
            // 两个写死的:mGBA 的默认值会给 GB 游戏套一圈 SGB 边框 —— 画布从
            // 160×144 撑到 256×224,屏幕上一大半是 mGBA 那个 logo 花边,游戏本体
            // 反而缩成中间一小块。写死在这儿而不是塞进表里:它跟用户无关,
            // 是我们自己的显示要求,不该出现在任何界面上
            if (strcmp(var->key, "mgba_sgb_borders") == 0) { var->value = "OFF"; return true; }
            if (strcmp(var->key, "mgba_frameskip")   == 0) { var->value = "disabled"; return true; }
            struct core_var *cv = var_find(var->key);
            if (cv && cv->answer) { var->value = cv->cur; return true; }
            var->value = NULL;      // 没人动过就不答,核心用它自己的默认值
            return false;
        }
        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
            // 答过一次就清掉 —— 核心据此决定要不要重新读一遍所有选项、
            // 重新应用金手指和 dipswitch(见 FBNeo retro_run)
            *(bool *) data = g_var_dirty ? true : false;
            g_var_dirty = 0;
            return true;

        default:
            return false;   // 其余一律不支持,核心会自己降级
    }
}

// ---- 帧缓冲:模拟与渲染的交接点 ----
//
// **模拟绝不能等屏幕。** 这块车机屏是 55 Hz(VSYNC 18.18ms),而 GBA 要 61.17 fps ——
// 原先 video_cb 里直接 ANativeWindow_lock 等 vsync,等于把模拟线程按在屏幕节奏上,
// 游戏和音乐整整慢 10%。现在 video_cb 只做一次 memcpy 就返回,
// 由独立的渲染线程去等 vsync、丢帧上屏。画面还是 55(屏幕上限),但游戏速度正常了。
/**
 * 帧缓冲。**按核心自报的 max_width×max_height 分配,不能写死。**
 *
 * 原先是个 256×224×4 的静态数组 —— 那是掌机的天花板(NES 256×240、GBA 240×160
 * 都在里面),接街机立刻炸:FBNeo 是 640×480,一帧 1.23 MB。当时拷贝那步有 clamp
 * 所以没写越界,但 blit 仍按完整的 h×pitch 逐行**读**,读到缓冲区外面 → SIGSEGV。
 * 掌机时代这个雷一直埋着,只是分辨率恰好没超。
 */
static uint8_t *g_fbuf;
static uint8_t *g_fbuf_local;      // 渲染线程的副本,同样大小
static size_t   g_fbuf_cap;
static unsigned g_fw, g_fh;
static size_t   g_fpitch;
static size_t g_fbytes;                      // 当前帧实际拷进来多少字节
static unsigned long g_fseq;                 // 帧序号,渲染线程靠它判断有没有新帧
static pthread_mutex_t g_frame_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t  g_frame_cond = PTHREAD_COND_INITIALIZER;
static pthread_t g_render_thread;

static void video_cb(const void *data, unsigned w, unsigned h, size_t pitch) {
    g_frames++;
    g_last_w = (int) w;
    g_last_h = (int) h;

    if (!data || !g_fbuf) { g_dupes++; return; }

    size_t bytes = (size_t) h * pitch;
    if (bytes > g_fbuf_cap) bytes = g_fbuf_cap;

    pthread_mutex_lock(&g_frame_lock);
    memcpy(g_fbuf, data, bytes);             // 只拷贝,不碰屏幕 —— 这一步必须快
    g_fbytes = bytes;                        // 实际拷了多少,渲染那头不能读超过这个数
    g_fw = w; g_fh = h; g_fpitch = pitch;
    g_fseq++;
    pthread_cond_signal(&g_frame_cond);
    pthread_mutex_unlock(&g_frame_lock);
}

/** 把一帧真正推上屏。跑在渲染线程上,这里面等 vsync 不影响模拟。 */
static void blit(const uint8_t *src_base, unsigned w, unsigned h, size_t pitch) {
    pthread_mutex_lock(&g_win_lock);
    if (!g_win) { pthread_mutex_unlock(&g_win_lock); return; }

    ANativeWindow_Buffer buf;
    if (ANativeWindow_lock(g_win, &buf, NULL) != 0) {
        pthread_mutex_unlock(&g_win_lock);
        return;
    }
    const void *data = src_base;

    // 缓冲按核心的分辨率申请(见 setBuffersGeometry),SurfaceView 负责放大到全屏 ——
    // 缩放交给合成器做,这里只管逐行拷贝 160×144 这种小图,开销可以忽略。
    unsigned rows = h < (unsigned) buf.height ? h : (unsigned) buf.height;
    unsigned cols = w < (unsigned) buf.width  ? w : (unsigned) buf.width;

    for (unsigned y = 0; y < rows; y++) {
        uint32_t *dst = (uint32_t *) buf.bits + (size_t) y * buf.stride;
        const uint8_t *src_row = (const uint8_t *) data + (size_t) y * pitch;

        if (g_fmt == RETRO_PIXEL_FORMAT_XRGB8888) {
            const uint32_t *src = (const uint32_t *) src_row;
            for (unsigned x = 0; x < cols; x++) {
                uint32_t p = src[x];                       // 0xXXRRGGBB
                uint32_t r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                dst[x] = 0xFF000000u | (b << 16) | (g << 8) | r;   // -> RGBA_8888
            }
        } else if (g_fmt == RETRO_PIXEL_FORMAT_RGB565) {
            const uint16_t *src = (const uint16_t *) src_row;
            for (unsigned x = 0; x < cols; x++) {
                uint16_t p = src[x];
                uint32_t r = ((p >> 11) & 0x1F) * 255 / 31;
                uint32_t g = ((p >> 5)  & 0x3F) * 255 / 63;
                uint32_t b = ( p        & 0x1F) * 255 / 31;
                dst[x] = 0xFF000000u | (b << 16) | (g << 8) | r;
            }
        } else {   // 0RGB1555
            const uint16_t *src = (const uint16_t *) src_row;
            for (unsigned x = 0; x < cols; x++) {
                uint16_t p = src[x];
                uint32_t r = ((p >> 10) & 0x1F) * 255 / 31;
                uint32_t g = ((p >> 5)  & 0x1F) * 255 / 31;
                uint32_t b = ( p        & 0x1F) * 255 / 31;
                dst[x] = 0xFF000000u | (b << 16) | (g << 8) | r;
            }
        }
    }

    ANativeWindow_unlockAndPost(g_win);
    g_blits++;

    // 这一帧推出去了 —— 如果有按键在等,现在结算它的延迟
    long long ts = g_key_ts;
    if (ts) {
        long us = (long) ((now_ns() - ts) / 1000);
        g_key_lat_us = us;
        if (us > g_key_lat_max_us) g_key_lat_max_us = us;
        g_key_ts = 0;
    }
    pthread_mutex_unlock(&g_win_lock);
}

// ---- 音频:环形缓冲 ----
//
// 核心在模拟线程上吐音频,AudioTrack 在另一条线程上取。中间垫个环形缓冲把两边解耦 ——
// **绝不能让写音频这一步阻塞模拟线程**,那是纯 Java 那版把车机拖卡的同一类错误。
// 缓冲满了宁可丢新数据(下面直接 return),也不等。
#define AUDIO_CAP (131072 * 2)         // 样本数(交错立体声)。131072 Hz 下约 0.5 秒 ——
                                       // 解耦后模拟跑满 61.17,产出更快,0.25 秒会周转不过来
static int16_t g_audio[AUDIO_CAP];
static volatile size_t g_awr, g_ard;   // 写/读游标,单写单读所以不用锁
static volatile long g_adrop;          // 丢弃的样本数,用来判断缓冲是不是设小了

static void push_audio(const int16_t *data, size_t frames) {
    size_t n = frames * 2;             // 立体声
    size_t wr = g_awr, rd = g_ard;
    size_t used = (wr - rd) & (AUDIO_CAP - 1);
    if (used + n >= AUDIO_CAP) { g_adrop += n; return; }   // 满了就丢,绝不阻塞
    for (size_t i = 0; i < n; i++) g_audio[(wr + i) & (AUDIO_CAP - 1)] = data[i];
    g_awr = (wr + n) & (AUDIO_CAP - 1);
}

/**
 * 快进时把声音**整个丢掉**,不是让它加速播。
 *
 * 核心这时候每秒吐三倍的样本,而 AudioTrack 还按原采样率吃 —— 缓冲一满就丢,
 * 出来是断断续续的怪叫,比没声音难受得多(这也是别的模拟器一律快进静音的原因)。
 */
static int audio_muted_by_speed(void) { return g_speed10 > 10; }

static void audio_sample_cb(int16_t l, int16_t r) {
    if (audio_muted_by_speed()) return;
    int16_t pair[2] = { l, r };
    push_audio(pair, 1);
}

static size_t audio_batch_cb(const int16_t *d, size_t frames) {
    if (d && !audio_muted_by_speed()) push_audio(d, frames);
    return frames;
}
static void input_poll_cb(void) { }

/**
 * 这次按下被核心读到过没有(读到就 +1,按下时清零)。
 *
 * **搓招全靠它。** 核心是每帧问一次输入(60fps = 16.7ms 一次),而手指从「↓」划到
 * 「↘」再到「→」,中间那个「↘」可能只存在几毫秒 —— 不跨帧边界的话核心一次都读不到,
 * 波动拳的指令序列就断了,表现就是"技能搓不出来"。见 settle_pending_ups。
 */
static unsigned char g_pad_seen[MAX_PORTS][16];
/** 抬起请求被押后了:这次按下核心还没读到过,松早了等于没按。 */
static unsigned char g_pad_up_pending[MAX_PORTS][16];

static int16_t input_state_cb(unsigned port, unsigned device, unsigned index, unsigned id) {
    (void) index;
    if (port >= MAX_PORTS || device != RETRO_DEVICE_JOYPAD || id >= 16) return 0;
    // 真按着的优先:同时按住「A」和「连A」时,A 该是一直按下,不该被连发打断
    if (g_pad[port][id]) {
        if (g_pad_seen[port][id] < 255) g_pad_seen[port][id]++;   // 这一帧核心看见了
        return 1;
    }
    if (g_turbo[port][id]) return (g_tick % TURBO_PERIOD) < TURBO_DUTY ? 1 : 0;
    return 0;
}

/**
 * 帧末结算那些被押后的抬起:核心已经读到过这次按下了,现在可以松。
 *
 * 代价是**最多晚一帧松手**(16.7ms),换来的是"按了一定算数"。反过来的代价大得多 ——
 * 划得快的那几下全丢,而搓招恰恰就是划得快。
 */
static void settle_pending_ups(void) {
    for (int p = 0; p < MAX_PORTS; p++) {
        for (int i = 0; i < 16; i++) {
            if (g_pad_up_pending[p][i] && g_pad_seen[p][i]) {
                g_pad[p][i] = 0;
                g_pad_up_pending[p][i] = 0;
            }
        }
    }
}

/**
 * 渲染线程:等新帧 → 推上屏(这里才等 vsync)。
 *
 * 屏幕 55 Hz 而游戏 61.17 fps,注定有帧显示不出来 —— 那就丢掉,
 * **丢的是显示,不是模拟**。人眼分不出 55 和 61,但游戏慢 10% 是听得出来的。
 */
static void *render_loop(void *arg) {
    (void) arg;
    unsigned long seen = 0;

    while (g_running) {
        unsigned w, h;
        size_t pitch;

        pthread_mutex_lock(&g_frame_lock);
        while (g_running && g_fseq == seen) {
            // 等新帧。用条件变量而不是轮询,免得白烧一个核心。
            struct timespec ts;
            clock_gettime(CLOCK_REALTIME, &ts);
            ts.tv_nsec += 50000000;                       // 50ms 兜底,别永久阻塞
            if (ts.tv_nsec >= 1000000000L) { ts.tv_nsec -= 1000000000L; ts.tv_sec++; }
            pthread_cond_timedwait(&g_frame_cond, &g_frame_lock, &ts);
        }
        if (!g_running) { pthread_mutex_unlock(&g_frame_lock); break; }
        seen = g_fseq;
        w = g_fw; h = g_fh; pitch = g_fpitch;
        size_t bytes = g_fbytes;                          // 拷了多少就读多少,别按 h×pitch 反推
        if (!g_fbuf || !g_fbuf_local) { pthread_mutex_unlock(&g_frame_lock); continue; }
        memcpy(g_fbuf_local, g_fbuf, bytes);              // 拷出来再慢慢画,不占着锁
        pthread_mutex_unlock(&g_frame_lock);

        // 帧被截断过就少画几行,总比按完整高度去读越界强(正常情况 bytes == h*pitch)
        unsigned safe_h = pitch ? (unsigned) (bytes / pitch) : 0;
        if (safe_h > h) safe_h = h;
        if (safe_h) blit(g_fbuf_local, w, safe_h, pitch);
    }
    return NULL;
}

// ---- 金手指 ----

/**
 * 金手指就是**每帧往游戏内存的固定地址写一个固定值**。
 *
 * 「无限命」不是改了一次命数,是把那个字节按住 —— 游戏自己每次扣命都写回去,
 * 我们下一帧再盖回来,于是它永远是满的。所以这件事必须在模拟线程的帧边界做
 * (见 run_loop 里 p_run 之后那一发),而不是从别的线程随手写。
 *
 * 码的来源和格式见 CheatStore:`0032-01-64` = 地址 0x0032、1 字节、值 0x64。
 * 这是 **RAM 直写**型,和 Game Genie 那种改 ROM 的码不是一回事 —— 后者要走
 * retro_cheat_set 由核心自己解析,而各核心认的字符串格式还不一样。直写这条
 * 只依赖 RETRO_MEMORY_SYSTEM_RAM,任何导出这块内存的核心都通用。
 */
#define MAX_CHEATS 64
struct cheat { unsigned addr, len, val; };
static struct cheat g_cheats[MAX_CHEATS];
/**
 * 生效条数。**换表时先清零再填、最后写回条数**(见 nativeSetCheats)——
 * 模拟线程只读这一个 int,中间态最多是"这一帧少按住几条",下一帧就齐了。
 * 为几个字节的写入上锁不值当,而锁在帧循环里是要还的。
 */
static volatile int g_cheat_n = 0;

/** 这一局有没有 SYSTEM_RAM 可写 —— 没有的话金手指那颗键该灰着,而不是点了没反应。 */
static int cheat_ram(unsigned char **out, size_t *n) {
    *out = NULL; *n = 0;
    if (!g_game_ok || !p_get_memory_data || !p_get_memory_size) return 0;
    size_t sz = p_get_memory_size(RETRO_MEMORY_SYSTEM_RAM);
    unsigned char *ram = sz ? (unsigned char *) p_get_memory_data(RETRO_MEMORY_SYSTEM_RAM) : NULL;
    if (!ram || !sz) return 0;
    *out = ram; *n = sz;
    return 1;
}

/**
 * 把开着的金手指按住。每帧一发,开销是几十次字节写,可以忽略。
 *
 * <b>两块内存都要认。</b> 绝大多数码写的是主机内部 RAM(FC 是 0x0000-0x07FF),
 * 但有一小撮落在**卡带上的电池 RAM**(0x6000-0x7FFF)—— 抽样 111 条 yikm 的 FC 码,
 * 有 8 条在那儿(带存档的 RPG 尤其多,进度、金钱都存那)。只认 SYSTEM_RAM 的话
 * 这些码会被越界检查默默跳过:**开关点得动、也标着"开着",就是没有任何效果**,
 * 而这是最差的一种失败。
 */
static void apply_cheats(void) {
    int n = g_cheat_n;
    if (n <= 0) return;
    unsigned char *ram; size_t sz;
    if (!cheat_ram(&ram, &sz)) return;
    // 卡带电池 RAM(可能没有:多数卡带不带电池)
    unsigned char *save = NULL;
    size_t save_sz = 0;
    if (p_get_memory_data && p_get_memory_size) {
        save_sz = p_get_memory_size(RETRO_MEMORY_SAVE_RAM);
        save = save_sz ? (unsigned char *) p_get_memory_data(RETRO_MEMORY_SAVE_RAM) : NULL;
        if (!save) save_sz = 0;
    }

    for (int i = 0; i < n && i < MAX_CHEATS; i++) {
        unsigned a = g_cheats[i].addr, l = g_cheats[i].len, v = g_cheats[i].val;
        if (l < 1 || l > 4) continue;
        unsigned char *mem = NULL;
        unsigned off = 0;
        if (a + l <= sz) {
            mem = ram; off = a;
        } else if (a >= 0x6000 && a + l <= 0x8000 && save_sz) {
            // 0x6000 是卡带 RAM 在 6502 地址空间里的起点,核心导出的那块从 0 开始
            unsigned rel = a - 0x6000;
            if (rel + l <= save_sz) { mem = save; off = rel; }
        }
        if (!mem) continue;      // 两块都放不下:码跟这个核心对不上,别踩别人的内存
        // 低位在前:6502/68000 这些码本来就是按小端写的
        for (unsigned b = 0; b < l; b++) mem[off + b] = (unsigned char) ((v >> (8 * b)) & 0xFF);
    }
}

// ---- 电池存档 ----

/**
 * 核心这一局的电池内存。没有电池的游戏(多数街机、纯 ROM 卡带)返回 0 大小。
 *
 * @param out_n 回填字节数
 * @return 内存地址,没有就是 NULL
 */
static void *sram_mem(size_t *out_n) {
    *out_n = 0;
    if (!g_game_ok || !p_get_memory_data || !p_get_memory_size) return NULL;
    size_t n = p_get_memory_size(RETRO_MEMORY_SAVE_RAM);
    if (!n) return NULL;
    void *mem = p_get_memory_data(RETRO_MEMORY_SAVE_RAM);
    if (!mem) return NULL;
    *out_n = n;
    return mem;
}

/**
 * 把电池存档写出去。**只能在帧边界或模拟停着的时候调**(见 run_loop / nativeFlushSram)。
 *
 * 先写 .part 再改名:车上熄火是常态,直接往正式文件上写,断在半路就是
 * 一个既不是旧存档也不是新存档的残片 —— 而存档没有第二份。
 *
 * @return 真写了返回 1;没变、没电池、还没回灌完都返回 0
 */
static int sram_save(void) {
    if (!g_sram_ready || !g_srampath[0]) return 0;
    size_t n;
    void *mem = sram_mem(&n);
    if (!mem) return 0;
    // 一个字节都没动就别写。六十秒一次全量写外部存储,而多数时候游戏根本没存过档
    if (g_sram_last && g_sram_last_size == n && memcmp(g_sram_last, mem, n) == 0) return 0;

    char part[sizeof(g_srampath) + 8];
    snprintf(part, sizeof(part), "%s.part", g_srampath);
    FILE *f = fopen(part, "wb");
    if (!f) { LOGE("存档写不了 %s", part); return 0; }
    size_t wrote = fwrite(mem, 1, n, f);
    int flushed = (fflush(f) == 0);
    fclose(f);
    if (wrote != n || !flushed) { LOGE("存档只写了 %zu/%zu 字节", wrote, n); remove(part); return 0; }
    if (rename(part, g_srampath) != 0) { LOGE("存档改名失败 %s", g_srampath); remove(part); return 0; }

    // 记下这一份,下次好比对
    if (g_sram_last_size != n) {
        free(g_sram_last);
        g_sram_last = malloc(n);
        g_sram_last_size = g_sram_last ? n : 0;
    }
    if (g_sram_last) memcpy(g_sram_last, mem, n);
    LOGI("电池存档已写 %zu 字节 → %s", n, g_srampath);
    return 1;
}

/**
 * 把上次的电池存档灌回核心。**必须在 retro_load_game 之后** —— 在那之前
 * 那块内存还不存在。文件不在很正常(头一次玩这个游戏)。
 *
 * 存档文件比核心要的短(换了核心版本)也照灌,少的那截保持核心自己初始化的值 ——
 * 总比一句"存档不兼容"然后全丢了强。
 */
static void sram_load(void) {
    g_sram_ready = 1;          // 走到这儿就算完成,之后才允许往回写(见 g_sram_ready)
    free(g_sram_last);
    g_sram_last = NULL;
    g_sram_last_size = 0;
    if (!g_srampath[0]) return;
    size_t n;
    void *mem = sram_mem(&n);
    // 多数街机和早期卡带根本没有电池(靠密码续关),这不是错。
    // 但"没电池"和"核心不支持"表现一模一样(都是没存档),分不清就没法排查
    if (!mem) {
        LOGI("这一局没有电池存档可存(%s)",
             (p_get_memory_data && p_get_memory_size) ? "游戏本身没电池" : "核心没导出接口");
        return;
    }

    FILE *f = fopen(g_srampath, "rb");
    if (!f) { LOGI("没有存档档案(头一次玩) %s", g_srampath); return; }
    size_t got = fread(mem, 1, n, f);
    fclose(f);
    LOGI("电池存档回灌 %zu / %zu 字节 ← %s", got, n, g_srampath);

    // 刚灌进去的这份就是"当前已落盘的内容",记下来 —— 不记的话下一次
    // flush 会把它原样再写一遍(白写一次外部存储)
    g_sram_last = malloc(n);
    if (g_sram_last) { memcpy(g_sram_last, mem, n); g_sram_last_size = n; }
}

// ---- 主循环 ----

static void *run_loop(void *arg) {
    (void) arg;
    double fps = (g_av.timing.fps > 1.0) ? g_av.timing.fps : 60.0;
    long period = (long) (1000000000.0 / fps);

    struct timespec next;
    clock_gettime(CLOCK_MONOTONIC, &next);
    long long fps_t0 = 0;      // 快进时的帧率采样窗口起点,0 = 还没开始采
    long fps_n = 0;

    while (g_running) {
        if (g_paused) {
            // 停稳了。同步落盘的那条路等的就是这个标志(见 nativeFlushSram)
            g_in_pause = 1;
            // 暂停时别空转烧核心;也别推进模拟,画面停在最后一帧
            struct timespec nap = { 0, 20000000 };   // 20ms
            nanosleep(&nap, NULL);
            clock_gettime(CLOCK_MONOTONIC, &next);   // 恢复时重新对表,免得疯狂追帧
            if (g_sram_flush_req) { g_sram_flush_req = 0; sram_save(); }
            continue;
        }
        g_in_pause = 0;
        g_tick++;               // 连发按帧算相位,得在核心来问输入之前先走一格
        p_run();
        // 金手指按在帧尾:游戏刚在这一帧里把命数扣掉,我们随手写回去,
        // 下一帧渲染出来的就是满的(见 apply_cheats)
        apply_cheats();
        // 这一帧核心已经把输入读完了,可以松那些押后的抬起(见 settle_pending_ups)
        settle_pending_ups();
        // 快进就是把"每帧该等多久"按倍数缩短。**放在这儿逐帧读**,不是循环外算一次 ——
        // 用户按下去要当场变快,松手要当场回原速
        int sp = g_speed10;
        long step = (sp > 10) ? period * 10 / sp : period;

        // 快进时每两秒报一次实测帧率。设成三倍速到底有没有跑到三倍,车机上
        // 只有这个数说了算(肉眼看"变快了"分不出 2.4 倍和 3 倍)。
        // 原速时一行不打 —— 正常玩的时候不该往 logcat 里灌东西
        if (sp > 10) {
            if (!fps_t0) { fps_t0 = now_ns(); fps_n = 0; }
            if (++fps_n >= 120) {
                double dt = (now_ns() - fps_t0) / 1e9;
                LOGI("快进 ×%.1f:实测 %.1f fps(原速 %.1f,目标 %.1f)",
                     sp / 10.0, fps_n / dt, fps, fps * sp / 10.0);
                fps_t0 = now_ns();
                fps_n = 0;
            }
        } else {
            fps_t0 = 0;
        }
        // **落盘只在帧边界做。** 核心跑一帧的中途那块电池内存随时在变,
        // 从别的线程直接 memcpy 会拷到半新半旧的存档(读回去多半是坏档)。
        // 写文件几十 KB,掉一两帧无所谓 —— 存档那一刻慢一帧没人察觉
        if (g_sram_flush_req) { g_sram_flush_req = 0; sram_save(); }
        // 绝对时间唤醒:误差不累积,不会越跑越慢
        next.tv_nsec += step;
        while (next.tv_nsec >= 1000000000L) { next.tv_nsec -= 1000000000L; next.tv_sec++; }

        // 已经落后超过 100ms(被调度抢了、或刚从后台回来)就重新对表 ——
        // 否则它会拼命追帧,画面和声音一起加速,比慢更难受。
        struct timespec now;
        clock_gettime(CLOCK_MONOTONIC, &now);
        long long behind = ((long long) now.tv_sec - next.tv_sec) * 1000000000LL
                         + (now.tv_nsec - next.tv_nsec);
        if (behind > 100000000LL) next = now;
        else clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &next, NULL);
    }
    return NULL;
}

// ---- JNI ----

#define SYM(name) do {                                                   \
        *(void **) (&p_##name) = dlsym(g_lib, "retro_" #name);           \
        if (!p_##name) { snprintf(out, outlen,                           \
            "核心缺少符号 retro_" #name); return -1; }                    \
    } while (0)

/**
 * 把核心整个还回去:停线程 → 卸游戏 → deinit → dlclose → 指针和缓冲全清。
 *
 * <b>顺序不能乱。</b>线程还在跑的时候 dlclose,下一帧就跳进已卸载的代码段。
 * ROM 缓冲要等 unload_game 之后才能 free —— libretro 允许核心一直持有到那时候。
 *
 * <b>最后那步"p_* 全置 NULL"不是洁癖。</b>代码段一旦 unmap,留着的函数指针就是
 * 一堆指向空地的地址;下次谁再顺手 {@code if (p_deinit) p_deinit();} 就是 SIGSEGV。
 * 换核心和加载失败两条路都得走这儿,少走一条就漏一次(实测崩过)。
 */
static void unload_all(void) {
    if (g_running) {
        g_running = 0;
        g_paused = 0;
        pthread_cond_broadcast(&g_frame_cond);
        pthread_join(g_thread, NULL);
        pthread_join(g_render_thread, NULL);
    }
    // 电池存档落盘。**必须在 unload_game 之前** —— 之后那块内存就不归我们了。
    // 线程已经停在上面了,这会儿没人碰核心内存,直接写是安全的。
    // 加载失败那条路也走这儿,靠 g_sram_ready 挡住(见它的注释)
    sram_save();
    // 游戏没装进去就别 unload —— 核心对"没 load 过就 unload"没有义务保持体面
    if (g_game_ok && p_unload_game) p_unload_game();
    g_game_ok = 0;
    g_speed10 = 10;          // 上一局的快进不能带到下一局
    g_cheat_n = 0;           // 金手指也一样:上一局的地址在下一局是别人的内存
    vars_clear();            // 选项表是**每局重报**的(街机的金手指就在里头)
    g_sram_ready = 0;
    g_sram_flush_req = 0;
    g_srampath[0] = 0;
    free(g_sram_last);
    g_sram_last = NULL;
    g_sram_last_size = 0;
    if (p_deinit) p_deinit();
    if (g_lib) { dlclose(g_lib); g_lib = NULL; }
    free(g_rom);
    g_rom = NULL;
    // 帧缓冲是按上一个核心的分辨率配的,下一个多半不一样,一并还掉重配
    free(g_fbuf); free(g_fbuf_local);
    g_fbuf = g_fbuf_local = NULL;
    g_fbuf_cap = g_fbytes = 0;

    p_init = NULL; p_deinit = NULL; p_api_version = NULL;
    p_get_system_info = NULL; p_get_system_av_info = NULL;
    p_set_environment = NULL; p_set_video_refresh = NULL;
    p_set_audio_sample = NULL; p_set_audio_sample_batch = NULL;
    p_set_input_poll = NULL; p_set_input_state = NULL;
    p_set_controller_port_device = NULL;
    p_load_game = NULL; p_unload_game = NULL; p_run = NULL; p_reset = NULL;
    p_get_memory_data = NULL; p_get_memory_size = NULL;
    p_serialize_size = NULL; p_serialize = NULL; p_unserialize = NULL;
}

static int load_core(const char *core_path, char *out, size_t outlen) {
    g_core_log_on = 1;      // 加载期间放核心说话,失败原因全在那几行里
    g_lib = dlopen(core_path, RTLD_NOW | RTLD_LOCAL);
    if (!g_lib) { snprintf(out, outlen, "dlopen 失败: %s", dlerror()); return -1; }

    SYM(init); SYM(deinit); SYM(api_version);
    SYM(get_system_info); SYM(get_system_av_info);
    SYM(set_environment); SYM(set_video_refresh);
    SYM(set_audio_sample); SYM(set_audio_sample_batch);
    SYM(set_input_poll); SYM(set_input_state);
    SYM(set_controller_port_device);
    SYM(load_game); SYM(unload_game); SYM(run); SYM(reset);
    // 电池存档那两个**不进 SYM**:缺了只是没有存档,不该让核心整个加载不了
    *(void **) (&p_get_memory_data) = dlsym(g_lib, "retro_get_memory_data");
    *(void **) (&p_get_memory_size) = dlsym(g_lib, "retro_get_memory_size");
    if (!p_get_memory_data || !p_get_memory_size) LOGI("这个核心不支持电池存档");
    // 快照那三个同理:缺了只是没有即时存档,核心照样能玩
    *(void **) (&p_serialize_size) = dlsym(g_lib, "retro_serialize_size");
    *(void **) (&p_serialize)      = dlsym(g_lib, "retro_serialize");
    *(void **) (&p_unserialize)    = dlsym(g_lib, "retro_unserialize");
    if (!p_serialize_size || !p_serialize || !p_unserialize) LOGI("这个核心不支持即时存档");
    return 0;
}

JNIEXPORT jstring JNICALL
Java_com_liubai_blank_game_Retro_nativeInit(JNIEnv *env, jclass clazz,
                                            jstring jcore, jstring jrom, jstring jsys,
                                            jstring jsram) {
    char msg[1024];
    int ok = 0;   // 只在全部装好后才置 1;失败路径要卸掉半截的核心(见 done)
    const char *core_path = (*env)->GetStringUTFChars(env, jcore, NULL);
    const char *rom_path  = (*env)->GetStringUTFChars(env, jrom,  NULL);
    const char *sys_dir   = (*env)->GetStringUTFChars(env, jsys,  NULL);
    const char *sram_path = jsram ? (*env)->GetStringUTFChars(env, jsram, NULL) : NULL;
    snprintf(g_sysdir, sizeof(g_sysdir), "%s", sys_dir);
    // 空串 = 不管存档(批量诊断那条路走的就是这个,别让它在 system/ 下拉一堆空 .srm)
    snprintf(g_srampath, sizeof(g_srampath), "%s", sram_path ? sram_path : "");
    g_core_err[0] = 0;   // 上一局的硬伤不能赖到这一局头上

    if (load_core(core_path, msg, sizeof(msg)) != 0) goto done;

    unsigned api = p_api_version();
    struct retro_system_info si;
    memset(&si, 0, sizeof(si));
    p_get_system_info(&si);
    LOGI("核心: %s %s  (libretro API %u)", si.library_name, si.library_version, api);

    // 顺序有讲究:environment 必须在 init 之前设,核心在 init 里就会回调它
    p_set_environment(env_cb);
    p_init();
    p_set_video_refresh(video_cb);
    p_set_audio_sample(audio_sample_cb);
    p_set_audio_sample_batch(audio_batch_cb);
    p_set_input_poll(input_poll_cb);
    p_set_input_state(input_state_cb);

    // 读 ROM 进内存。mGBA 的 need_fullpath=false,可以直接喂数据。
    FILE *f = fopen(rom_path, "rb");
    if (!f) { snprintf(msg, sizeof(msg), "ROM 打不开: %s", rom_path); goto done; }
    fseek(f, 0, SEEK_END);
    long size = ftell(f);
    fseek(f, 0, SEEK_SET);
    // 上一颗要是还留着(没按协议先 nativeShutdown 就又 init 了),先还掉再要新的,
    // 否则指针一覆盖那块就永远找不回来了 —— GBA 一颗 16 MB,漏一次就是一次
    free(g_rom);
    g_rom = malloc((size_t) size);
    void *rom = g_rom;
    size_t got = fread(rom, 1, (size_t) size, f);
    fclose(f);
    if (got != (size_t) size) { snprintf(msg, sizeof(msg), "ROM 读取不完整"); goto done; }

    struct retro_game_info gi;
    memset(&gi, 0, sizeof(gi));
    gi.path = rom_path;
    gi.data = rom;
    gi.size = (size_t) size;

    if (!p_load_game(&gi)) { snprintf(msg, sizeof(msg), "retro_load_game 返回 false"); goto done; }
    g_game_ok = 1;

    // **返回 true 不代表能玩。** FBNeo 缺 BIOS 时照样返回 true,自己画一屏英文
    // 错误页 —— 只有日志里那几行说了实话(见 g_core_err)。这里把它当失败处理:
    // 进游戏页看一屏看不懂的英文,不如直接说缺什么。
    if (g_core_err[0]) { snprintf(msg, sizeof(msg), "缺文件|%s", g_core_err); goto done; }

    memset(&g_av, 0, sizeof(g_av));
    p_get_system_av_info(&g_av);
    // **每个口都要单独声明**。只声明 0 号的话,核心根本不会来问 1~3 号口的状态,
    // 第二个玩家按什么都没反应 —— 不是按键没送到,是核心压根没问
    for (unsigned port = 0; port < MAX_PORTS; port++) {
        p_set_controller_port_device(port, RETRO_DEVICE_JOYPAD);
    }

    // 上次的电池存档灌回去。**位置有讲究**:必须在 load_game 之后(那块内存
    // 那时才存在)、又必须在缺文件那道检查之后 —— 加载失败的局不该碰存档,
    // 更不该把核心的空白 SRAM 写回去覆盖掉用户的档(见 g_sram_ready)
    sram_load();

    // 帧缓冲按这个核心的上限现配。×4 是按最宽的像素格式(XRGB8888)算的,
    // RGB565 只用一半也无所谓 —— 这块内存一局游戏只分配一次。
    {
        size_t mw = g_av.geometry.max_width  ? g_av.geometry.max_width  : 640;
        size_t mh = g_av.geometry.max_height ? g_av.geometry.max_height : 480;
        size_t cap = mw * mh * 4;
        free(g_fbuf); free(g_fbuf_local);
        g_fbuf = malloc(cap);
        g_fbuf_local = malloc(cap);
        g_fbuf_cap = (g_fbuf && g_fbuf_local) ? cap : 0;
    if (!g_fbuf_cap) { snprintf(msg, sizeof(msg), "帧缓冲分配不下 %zu 字节", cap); goto done; }
        LOGI("帧缓冲 %zux%zu → %zu KB ×2", mw, mh, cap / 1024);
    }

    ok = 1;
    // **宽高比要单独回传,不能拿 base_width/base_height 算。**
    //
    // 老机器的像素不是方的:FC 输出 256×240 却是给 4:3 的 CRT 看的,像素宽高比 8:7,
    // 按方形像素画出来比当年窄 25%(人物偏瘦)。街机更乱 —— 竖屏那些(1943、雷电)
    // 报的是 3:4,方形像素会把它画成横的。核心自己知道该多宽,这里原样传出去。
    //
    // libretro 的约定:报 0 表示"没什么特别的,按方形像素画"。Java 侧照此退回。
    LOGI("核心报的宽高比 %.4f (0 = 按方形像素画)", g_av.geometry.aspect_ratio);
    snprintf(msg, sizeof(msg),
             "OK|%s %s|API %u|%ux%u (max %ux%u)|%.4f fps|%.0f Hz|ROM %ld B|fmt %d|ar %.4f",
             si.library_name ? si.library_name : "?",
             si.library_version ? si.library_version : "?",
             api,
             g_av.geometry.base_width, g_av.geometry.base_height,
             g_av.geometry.max_width, g_av.geometry.max_height,
             g_av.timing.fps, g_av.timing.sample_rate,
             size, g_fmt, g_av.geometry.aspect_ratio);

done:
    // 失败时核心可能已经 dlopen 了一半(load_core 在 dlsym 中途失败、ROM 坏了
    // p_load_game 返回 false、或者缺 BIOS)。不卸掉的话这颗核心和 ROM 缓冲会一直
    // 占着内存,反复点一个起不来的游戏就反复泄漏。
    //
    // **必须走 unload_all,不能只 dlclose。** 这儿曾经是一段自己写的清理,少了
    // 最后"把 p_* 全置 NULL"那一步 —— 于是核心的代码段已经 unmap、函数指针却还
    // 指着它,下一次 nativeShutdown 里那句 {@code if (p_deinit) p_deinit();}
    // 就跳进空地址,SIGSEGV。触发路径:加载失败 → 再点一个游戏(自动补 BIOS 之后
    // 重试是必现的)。同一件事写两遍迟早漏一步,合并成一个函数。
    if (!ok) unload_all();
    (*env)->ReleaseStringUTFChars(env, jcore, core_path);
    (*env)->ReleaseStringUTFChars(env, jrom,  rom_path);
    (*env)->ReleaseStringUTFChars(env, jsys,  sys_dir);
    if (sram_path) (*env)->ReleaseStringUTFChars(env, jsram, sram_path);
    return (*env)->NewStringUTF(env, msg);
}

JNIEXPORT void JNICALL
Java_com_liubai_blank_game_Retro_nativeSetSurface(JNIEnv *env, jclass clazz, jobject surface) {
    pthread_mutex_lock(&g_win_lock);
    if (g_win) { ANativeWindow_release(g_win); g_win = NULL; }
    if (surface) {
        g_win = ANativeWindow_fromSurface(env, surface);
        if (g_win) {
            // 按核心分辨率申请缓冲,放大交给 SurfaceView/合成器 —— 等于白拿硬件缩放
            unsigned w = g_av.geometry.base_width  ? g_av.geometry.base_width  : 160;
            unsigned h = g_av.geometry.base_height ? g_av.geometry.base_height : 144;
            ANativeWindow_setBuffersGeometry(g_win, (int32_t) w, (int32_t) h,
                                             WINDOW_FORMAT_RGBA_8888);
            LOGI("surface 就绪, 缓冲几何 = %ux%u", w, h);
        }
    }
    pthread_mutex_unlock(&g_win_lock);
}

JNIEXPORT void JNICALL
Java_com_liubai_blank_game_Retro_nativeStart(JNIEnv *env, jclass clazz) {
    // 游戏没装进核心就别开线程,判据见 g_game_ok(不是 g_lib)
    if (!g_game_ok) return;
    if (g_running) return;
    g_core_log_on = 0;      // 加载阶段过去了,核心该闭嘴了(见 core_log)
    g_running = 1;
    pthread_create(&g_thread, NULL, run_loop, NULL);
    pthread_create(&g_render_thread, NULL, render_loop, NULL);
}

JNIEXPORT void JNICALL
Java_com_liubai_blank_game_Retro_nativeStop(JNIEnv *env, jclass clazz) {
    if (!g_running) return;
    g_running = 0;
    pthread_cond_broadcast(&g_frame_cond);      // 把等在条件变量上的渲染线程叫醒
    pthread_join(g_thread, NULL);
    pthread_join(g_render_thread, NULL);
}

JNIEXPORT void JNICALL
Java_com_liubai_blank_game_Retro_nativeKey(JNIEnv *env, jclass clazz,
                                           jint port, jint id, jboolean down) {
    if (port < 0 || port >= MAX_PORTS || id < 0 || id >= 16) return;
    // 只在"按下"时打点,而且只认第一个未结算的 —— 连按时不会互相覆盖。
    // 延迟只按 P1 量:四个人的手速各不相同,混在一起这个数就没意义了
    if (down && port == 0 && !g_key_ts) g_key_ts = now_ns();
    if (down) {
        g_pad[port][id] = 1;
        g_pad_seen[port][id] = 0;          // 重新计:这是新的一次按下
        g_pad_up_pending[port][id] = 0;
    } else if (g_pad_seen[port][id]) {
        g_pad[port][id] = 0;               // 核心已经读到过,正常松
    } else {
        g_pad_up_pending[port][id] = 1;    // 还没读到过,押到帧末再松
    }
}

/** 某个玩家断线了:把他按着的键全松开,免得角色一直朝一个方向跑。 */

JNIEXPORT void JNICALL
Java_com_liubai_blank_game_Retro_nativeReleaseAll(JNIEnv *env, jclass clazz, jint port) {
    if (port < 0 || port >= MAX_PORTS) return;
    // 连发也要一起松:断线时留着的话,那个键会一直自己突突,人还找不着是谁在按
    for (int i = 0; i < 16; i++) { g_pad[port][i] = 0; g_turbo[port][i] = 0; }
}

/**
 * 连发键按下/抬起。按住期间这个键由 {@link #input_state_cb} 每 6 帧翻转一次。
 *
 * 和 {@code nativeKey} 分开走:真按着的那条优先级更高(见 input_state_cb),
 * 所以同一颗键既能普通按也能连发按,互不干扰。
 */
JNIEXPORT void JNICALL
Java_com_liubai_blank_game_Retro_nativeTurbo(JNIEnv *env, jclass clazz,
                                             jint port, jint id, jboolean down) {
    if (port < 0 || port >= MAX_PORTS || id < 0 || id >= 16) return;
    g_turbo[port][id] = down ? 1 : 0;
}

/**
 * 核心裸速度:全速跑 seconds 秒,不限速、不上屏,只数帧。
 *
 * 正常模式下 run_loop 是按 av_info.timing.fps 用 clock_nanosleep 自己控速的
 * (原生路线本来就该这样,不靠 vsync 挡),所以量上限得另开这条路径。
 * 调用方要在后台线程调 —— 这函数是阻塞的。
 */
JNIEXPORT jstring JNICALL
Java_com_liubai_blank_game_Retro_nativeBench(JNIEnv *env, jclass clazz, jint seconds) {
    char buf[160];
    if (!p_run) return (*env)->NewStringUTF(env, "核心没加载");

    long f0 = g_frames;
    struct timespec t0, now;
    clock_gettime(CLOCK_MONOTONIC, &t0);

    double elapsed = 0;
    long spin = 0;
    while (elapsed < (double) seconds) {
        p_run();
        // 每 64 帧查一次时间就够,免得 clock_gettime 本身成为开销
        if ((++spin & 63) == 0) {
            clock_gettime(CLOCK_MONOTONIC, &now);
            elapsed = (now.tv_sec - t0.tv_sec) + (now.tv_nsec - t0.tv_nsec) / 1e9;
        }
    }
    clock_gettime(CLOCK_MONOTONIC, &now);
    elapsed = (now.tv_sec - t0.tv_sec) + (now.tv_nsec - t0.tv_nsec) / 1e9;

    // bench 是全速跑的(40 倍速),这期间核心吐的音频没人消费、全被丢掉。
    // 那个丢弃数不代表正常播放的健康度,统计前必须清零、缓冲也倒空。
    g_ard = g_awr;
    g_adrop = 0;

    long frames = g_frames - f0;
    double fps = frames / elapsed;
    double target = (g_av.timing.fps > 1.0) ? g_av.timing.fps : 59.73;
    snprintf(buf, sizeof(buf), "裸速度 %.1f fps  =  原速的 %.1f 倍", fps, fps / target);
    LOGI("BENCH %s (%ld 帧 / %.1f 秒)", buf, frames, elapsed);
    return (*env)->NewStringUTF(env, buf);
}

/** 取音频给 AudioTrack。返回实际取到的样本数(交错立体声),没有就返回 0。 */
JNIEXPORT jint JNICALL
Java_com_liubai_blank_game_Retro_nativeReadAudio(JNIEnv *env, jclass clazz, jshortArray outArr) {
    jsize cap = (*env)->GetArrayLength(env, outArr);
    size_t wr = g_awr, rd = g_ard;
    size_t used = (wr - rd) & (AUDIO_CAP - 1);
    if (used == 0) return 0;
    size_t n = used < (size_t) cap ? used : (size_t) cap;

    jshort *out = (*env)->GetShortArrayElements(env, outArr, NULL);
    for (size_t i = 0; i < n; i++) out[i] = g_audio[(rd + i) & (AUDIO_CAP - 1)];
    (*env)->ReleaseShortArrayElements(env, outArr, out, 0);

    g_ard = (rd + n) & (AUDIO_CAP - 1);
    return (jint) n;
}

/** 核心要求的采样率 —— mGBA 报的不是 44100 这种常见值,得照它的来。 */
JNIEXPORT jint JNICALL
Java_com_liubai_blank_game_Retro_nativeSampleRate(JNIEnv *env, jclass clazz) {
    return (jint) (g_av.timing.sample_rate > 0 ? g_av.timing.sample_rate : 48000);
}

JNIEXPORT jlong JNICALL
Java_com_liubai_blank_game_Retro_nativeAudioDropped(JNIEnv *env, jclass clazz) {
    return (jlong) g_adrop;
}

JNIEXPORT void JNICALL
Java_com_liubai_blank_game_Retro_nativeShutdown(JNIEnv *env, jclass clazz) {
    unload_all();

    g_frames = g_blits = g_dupes = 0;
    g_key_lat_us = g_key_lat_max_us = g_key_ts = 0;
    g_adrop = 0; g_awr = g_ard = 0; g_fseq = 0;
    g_fmt = RETRO_PIXEL_FORMAT_0RGB1555;
    memset(&g_av, 0, sizeof(g_av));
    LOGI("核心已卸载");
}

JNIEXPORT void JNICALL
Java_com_liubai_blank_game_Retro_nativePause(JNIEnv *env, jclass clazz, jboolean paused) {
    g_paused = paused ? 1 : 0;
}

/**
 * 把电池存档写出去。
 *
 * @param now 等不等得起。
 *
 * <b>false(定时那条路)</b>:只挂个请求,模拟线程下一个帧边界自己写。不打扰任何人。
 *
 * <b>true(切后台/回列表)</b>:那是最后的机会 —— 挂个请求就返回的话,进程下一刻
 * 被冻住(车机上切走的 app 说停就停),模拟线程根本走不到检查点,这次存档就丢了。
 * 所以这条路要当场写完再返回。
 *
 * 但 {@code nativePause(true)} 只是"请它停",调用的这一刻模拟线程可能正在
 * retro_run 中间 —— 这时候 memcpy 会拷到半新半旧的存档。所以先等它真正停在
 * 暂停分支上({@code g_in_pause}),一帧最多二十毫秒,给一百毫秒足够;
 * 真没等到就退化成挂请求 —— 存档晚一点写,总比写进去一份坏档强。
 *
 * @return 这次真写了没有
 */
JNIEXPORT jboolean JNICALL
Java_com_liubai_blank_game_Retro_nativeFlushSram(JNIEnv *env, jclass clazz, jboolean now) {
    if (!g_sram_ready) return JNI_FALSE;
    if (now) {
        int waited = 0;
        for (; waited < 50 && g_running && !g_in_pause; waited++) {
            struct timespec t = { 0, 2000000 };   // 2ms
            nanosleep(&t, NULL);
        }
        if (!g_running || g_in_pause) {
            int wrote = sram_save();
            LOGI("存档同步落盘(等了 %d ms):%s", waited * 2, wrote ? "写了" : "没变/无电池");
            return wrote ? JNI_TRUE : JNI_FALSE;
        }
        LOGE("等不到模拟线程停下(running=%d paused=%d),退回帧边界落盘", g_running, g_paused);
    }
    g_sram_flush_req = 1;
    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_liubai_blank_game_Retro_nativeIsPaused(JNIEnv *env, jclass clazz) {
    return g_paused ? JNI_TRUE : JNI_FALSE;
}

/**
 * 快进倍数(×10:10 = 原速,30 = 三倍)。
 *
 * 只放宽节流,不动模拟本身 —— 每帧照样完整跑,画面和逻辑都是对的。
 * 快进期间声音丢掉(见 audio_muted_by_speed)。
 */
JNIEXPORT void JNICALL
Java_com_liubai_blank_game_Retro_nativeSetSpeed(JNIEnv *env, jclass clazz, jint speed10) {
    if (speed10 < 10) speed10 = 10;
    if (speed10 > 80) speed10 = 80;      // 再快也没意义,而且车机上会开始掉帧
    g_speed10 = speed10;
}

JNIEXPORT jint JNICALL
Java_com_liubai_blank_game_Retro_nativeGetSpeed(JNIEnv *env, jclass clazz) {
    return (jint) g_speed10;
}

/**
 * 把模拟线程按停,等它真的停稳。做快照必须在**一致的状态**上:
 * retro_run 跑到一半时序列化,拍下来的是半帧,读回去多半直接崩在核心里。
 *
 * @param was 回填"原先是不是就暂停着的",做完好还原
 * @return 停稳了没有
 */
static int hold_emulation(int *was) {
    *was = g_paused;
    if (!g_running) return 1;              // 线程没跑,本来就是停的
    g_paused = 1;
    for (int i = 0; i < 100 && !g_in_pause; i++) {
        struct timespec t = { 0, 2000000 };   // 2ms,一帧最多二十毫秒
        nanosleep(&t, NULL);
    }
    return g_in_pause;
}

/** 还原到调用 hold_emulation 之前的暂停位。 */
static void release_emulation(int was) {
    g_paused = was;
}

/**
 * 即时存档:把整台机器这一瞬间拍下来写文件。
 *
 * 同 SRAM,先写 .part 再改名 —— 存档写到一半断电,留下的残片会让下次读档
 * 直接把核心送走,而这时候用户以为自己有存档。
 */
JNIEXPORT jboolean JNICALL
Java_com_liubai_blank_game_Retro_nativeSaveState(JNIEnv *env, jclass clazz, jstring jpath) {
    if (!g_game_ok || !p_serialize_size || !p_serialize) return JNI_FALSE;
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    int ok = 0, was = 0;

    size_t n = p_serialize_size();
    if (!n) { LOGE("核心说快照大小是 0,存不了"); goto out; }
    void *buf = malloc(n);
    if (!buf) { LOGE("快照要 %zu 字节,分配不下", n); goto out; }

    if (!hold_emulation(&was)) {
        LOGE("模拟线程停不下来,这次不存 —— 半帧的快照读回去要崩");
        free(buf);
        goto out;
    }
    ok = p_serialize(buf, n) ? 1 : 0;
    release_emulation(was);

    if (ok) {
        char part[1024];
        snprintf(part, sizeof(part), "%s.part", path);
        FILE *f = fopen(part, "wb");
        if (f) {
            size_t wrote = fwrite(buf, 1, n, f);
            int flushed = (fflush(f) == 0);
            fclose(f);
            ok = (wrote == n && flushed && rename(part, path) == 0);
            if (!ok) remove(part);
        } else {
            ok = 0;
        }
        LOGI("即时存档 %s %zu 字节 → %s", ok ? "已写" : "写失败", n, path);
    } else {
        LOGE("retro_serialize 返回 false");
    }
    free(buf);

out:
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    return ok ? JNI_TRUE : JNI_FALSE;
}

/** 读档:把快照灌回核心,当场接着跑。 */
JNIEXPORT jboolean JNICALL
Java_com_liubai_blank_game_Retro_nativeLoadState(JNIEnv *env, jclass clazz, jstring jpath) {
    if (!g_game_ok || !p_serialize_size || !p_unserialize) return JNI_FALSE;
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    int ok = 0, was = 0;

    FILE *f = fopen(path, "rb");
    if (!f) { LOGI("没有存档可读 %s", path); goto out; }
    fseek(f, 0, SEEK_END);
    long len = ftell(f);
    fseek(f, 0, SEEK_SET);
    if (len <= 0) { fclose(f); goto out; }
    void *buf = malloc((size_t) len);
    if (!buf) { fclose(f); goto out; }
    size_t got = fread(buf, 1, (size_t) len, f);
    fclose(f);
    if (got != (size_t) len) { free(buf); LOGE("存档读不全 %zu/%ld", got, len); goto out; }

    // 大小对不上多半是换了核心版本。**不硬塞** —— 核心拿到尺寸不符的快照
    // 行为没有保证,轻则花屏重则崩在里面
    size_t want = p_serialize_size();
    if (want && (size_t) len != want) {
        LOGE("存档尺寸对不上(存档 %ld,核心要 %zu),多半是换过核心版本,不读", len, want);
        free(buf);
        goto out;
    }
    if (!hold_emulation(&was)) {
        LOGE("模拟线程停不下来,这次不读");
        free(buf);
        goto out;
    }
    ok = p_unserialize(buf, (size_t) len) ? 1 : 0;
    release_emulation(was);
    free(buf);
    LOGI("即时读档 %s %ld 字节 ← %s", ok ? "成功" : "被核心拒绝", len, path);

out:
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    return ok ? JNI_TRUE : JNI_FALSE;
}

/** 这个核心支不支持即时存档 —— 不支持的话那两颗键该灰着,而不是点了没反应。 */
JNIEXPORT jboolean JNICALL
Java_com_liubai_blank_game_Retro_nativeCanState(JNIEnv *env, jclass clazz) {
    if (!g_game_ok || !p_serialize_size || !p_serialize || !p_unserialize) return JNI_FALSE;
    return p_serialize_size() > 0 ? JNI_TRUE : JNI_FALSE;
}

/**
 * 换一整张金手指表。传的是三个一组的 {地址, 字节数, 值},只传**开着**的那些。
 *
 * 整表替换而不是逐条开关:开关状态在 Java 侧那张列表里,这边只要一份最终结果 ——
 * 少一套要同步的状态。清空(传 null 或空数组)就是全关。
 */
JNIEXPORT void JNICALL
Java_com_liubai_blank_game_Retro_nativeSetCheats(JNIEnv *env, jclass clazz, jintArray triples) {
    g_cheat_n = 0;             // 先停,再改表(见 g_cheat_n 那段)
    jsize n = triples ? (*env)->GetArrayLength(env, triples) / 3 : 0;
    if (n <= 0) { LOGI("金手指:全部撤掉"); return; }
    if (n > MAX_CHEATS) n = MAX_CHEATS;
    jint *p = (*env)->GetIntArrayElements(env, triples, NULL);
    if (!p) return;
    for (jsize i = 0; i < n; i++) {
        g_cheats[i].addr = (unsigned) p[i * 3];
        g_cheats[i].len  = (unsigned) p[i * 3 + 1];
        g_cheats[i].val  = (unsigned) p[i * 3 + 2];
    }
    (*env)->ReleaseIntArrayElements(env, triples, p, JNI_ABORT);
    g_cheat_n = (int) n;
    LOGI("金手指:%d 条生效", (int) n);
}

/** 这一局能不能用金手指(核心导没导出 SYSTEM_RAM)。不能就把那颗键灰掉。 */
JNIEXPORT jboolean JNICALL
Java_com_liubai_blank_game_Retro_nativeCanCheat(JNIEnv *env, jclass clazz) {
    unsigned char *ram; size_t sz;
    return cheat_ram(&ram, &sz) ? JNI_TRUE : JNI_FALSE;
}

/**
 * 核心报上来的整张选项表,拍平成一个字符串给 Java:每行 {@code key\tdesc\tvals\tcur},
 * 行间 {@code \n}。
 *
 * 拍平而不是建一堆 Java 对象:JNI 这边每建一个对象都要 FindClass + NewObject,
 * 而这张表一局只读一次,拆字符串是 Java 那边最不费事的一件事。
 */
JNIEXPORT jstring JNICALL
Java_com_liubai_blank_game_Retro_nativeVariables(JNIEnv *env, jclass clazz) {
    size_t cap = 4096, len = 0;
    char *buf = (char *) malloc(cap);
    if (!buf) return (*env)->NewStringUTF(env, "");
    buf[0] = 0;
    for (int i = 0; i < g_var_n; i++) {
        struct core_var *cv = &g_vars[i];
        if (!cv->key) continue;
        size_t need = strlen(cv->key) + strlen(cv->desc) + strlen(cv->vals)
                    + strlen(cv->cur) + 8;
        if (len + need > cap) {
            while (len + need > cap) cap *= 2;
            char *nb = (char *) realloc(buf, cap);
            if (!nb) { free(buf); return (*env)->NewStringUTF(env, ""); }
            buf = nb;
        }
        len += (size_t) snprintf(buf + len, cap - len, "%s\t%s\t%s\t%s\n",
                                 cv->key, cv->desc, cv->vals, cv->cur);
    }
    jstring s = (*env)->NewStringUTF(env, buf);
    free(buf);
    return s;
}

/**
 * 改一个选项的值。核心下一帧就会来问 GET_VARIABLE_UPDATE,自己重新读、重新应用。
 *
 * 值必须是核心报的那几个之一(整段字符串,如 {@code "1 - Enabled"})——
 * FBNeo 是拿它和自己那份选项名逐个 strcmp 的,差一个字都不认。
 */
JNIEXPORT jboolean JNICALL
Java_com_liubai_blank_game_Retro_nativeSetVariable(JNIEnv *env, jclass clazz,
                                                   jstring jkey, jstring jval) {
    if (!jkey || !jval) return JNI_FALSE;
    const char *key = (*env)->GetStringUTFChars(env, jkey, NULL);
    const char *val = (*env)->GetStringUTFChars(env, jval, NULL);
    jboolean ok = JNI_FALSE;
    struct core_var *cv = key ? var_find(key) : NULL;
    if (cv && val) {
        char *nv = strdup(val);
        if (nv) {
            free(cv->cur);
            cv->cur = nv;
            cv->answer = 1;         // 从这一刻起这个 key 由我们说了算
            g_var_dirty = 1;
            ok = JNI_TRUE;
            LOGI("选项 %s = %s", key, val);
        }
    }
    if (key) (*env)->ReleaseStringUTFChars(env, jkey, key);
    if (val) (*env)->ReleaseStringUTFChars(env, jval, val);
    return ok;
}

/** 软复位 —— 相当于按掌机的电源重开,存档不受影响。 */
JNIEXPORT void JNICALL
Java_com_liubai_blank_game_Retro_nativeReset(JNIEnv *env, jclass clazz) {
    // 同 nativeStart:判据是 g_game_ok 不是 p_reset。ROM 坏掉时 dlopen 成功、
    // 符号都在,但核心里没游戏 —— 而这时「重置」键还点得动
    if (!g_game_ok) return;
    p_reset();
}

JNIEXPORT jstring JNICALL
Java_com_liubai_blank_game_Retro_nativeStats(JNIEnv *env, jclass clazz) {
    char buf[256];
    snprintf(buf, sizeof(buf), "%ld|%ld|%ld|%dx%d|%.1f|%.1f",
             g_frames, g_blits, g_dupes, g_last_w, g_last_h,
             g_key_lat_us / 1000.0, g_key_lat_max_us / 1000.0);
    return (*env)->NewStringUTF(env, buf);
}
