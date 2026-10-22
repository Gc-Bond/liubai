package com.liubai.blank.ui;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewStub;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;

import androidx.webkit.WebViewAssetLoader;

import com.liubai.blank.BuildConfig;
import com.liubai.blank.R;
import com.liubai.blank.data.WallpaperStore;
import com.liubai.blank.media.MediaHub;
import com.liubai.blank.mode.ModeManager;
import com.liubai.blank.model.NowPlaying;
import com.liubai.blank.privileged.CarCtrl;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * 壁纸宿主:图 / 视频 / 网页三种壁纸的铺放、取色、喂触摸、生命周期全收在这一处。
 *
 * 2026-08-19 从 {@code HomeActivity} 整块搬出(Stage 2),方法体原样,只改了宿主引用
 * (findViewById / getResources / DockHost.get 都换成构造收进来的 Activity),
 * 以及 handler 换成自己的实例 —— HomeActivity 那个还在别处用,不能带过来。
 */
public final class WallpaperHost {

    private static final String TAG = "Blank/Wallpaper";

    /**
     * 网页壁纸推迟多久再起 WebView。
     *
     * 不是随便定的:这段时间要够系统把无障碍服务的零件装上(那一步也占主线程,被挤掉就
     * 永久卡死)。半秒是权衡 —— 再短怕错不开,再长人就看得出壁纸"愣了一下"才动。
     * 见 {@link #apply} 里 isWebId 那段。
     */
    private static final long WEB_START_DELAY_MS = 500;

    private final Activity act;
    private final View root;
    /** 壁纸:选了哪张 + 从 assets 解码(见 apply)。 */
    private WallpaperStore wallpapers;
    /** 当前铺着的是哪张。重复 apply 时靠它跳过重来 —— onResume 每次都解一遍太重。 */
    private String wallpaperShown;
    /** 视频壁纸的播放器。只在选了视频时才有;桌面不在前台就暂停,退出要 release。 */
    private MediaPlayer wallpaperPlayer;
    /**
     * 画中画层把壁纸盖住了(层上有内容且展开着)。那时壁纸只露四边一圈,视频却还在
     * 解码、每帧重绘整个桌面窗口 —— 2026-09-06 真车:地图开在画中画里,桌面窗口 4 秒
     * 130 多帧、播放器活着。由 HomeActivity 按画中画层的状态同步(见 {@link #setCovered})。
     */
    private boolean covered;
    /** 桌面可见(onStart 到 onStop 之间)。画中画层在桌面看不见时也会变(里面的 app 被外部杀掉),那时不该把网页壁纸解冻。 */
    private boolean started;
    /** 网页壁纸铺着没有。WebView 是布局里常驻的,只有这个标记能说明它现在有没有在跑。 */
    private boolean webWallpaperOn;
    /** 给网页壁纸喂"正在播放"用(见 pushTrackToWeb)。懒建:没铺网页壁纸就不碰。 */
    private MediaHub webMedia;
    /** 上一次喂过去的是哪首。没换歌就不重喂 —— 封面是几十 KB 的 base64。 */
    private String webTrackKey = "";
    /** 上一次喂给壁纸的鱼游速度。车速没跨整数 km/h 时算出来的值不变,不用重喂。 */
    private double lastFishSpeed = -1;
    /** 上一次喂给壁纸的鱼数。-1 = 还没喂过,强制下一拍推一次。 */
    private int lastFishCount = -1;
    /** 上一次喂给壁纸的充电状态。-1 = 还没喂过,0/1 = 上次喂的不在充/在充。 */
    private int lastCharging = -1;
    /** 壁纸自己的消息队列:喂歌轮询、揭幕延时都挂它。 */
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** @param act  宿主 Activity(要用它 findViewById、取 DisplayMetrics)
     *  @param root 壁纸三个 view 所在的根(传 activity 的 content root 即可) */
    public WallpaperHost(Activity act, View root) {
        this.act = act;
        this.root = root;
        this.wallpapers = new WallpaperStore(act);
    }

    /** 给 WallpaperSwiper 和 AppearancePopup 用。 */
    public WallpaperStore store() { return wallpapers; }

    /**
     * 把选中的壁纸铺到最底下那层(在所有内容之下)。
     *
     * 静态图和视频在这里分岔:图片塞进 ImageView,视频交给 MediaPlayer 渲染到
     * TextureView 上。选择/存取那一半是共用的(见 {@link WallpaperStore}),
     * 只有"怎么画"这一步分两支 —— 图片和视频在 Android 上就是两种绘制机制。
     *
     * 每次回到桌面都调一次 —— 在设置里换完壁纸就是靠这一下生效的。所以先比一下现在
     * 铺的是不是同一张:不比的话每次 onResume 都要重来一遍(重解全屏图、重开播放器),
     * 从 app 退回桌面就会卡一下。
     */
    public void apply() {
        ImageView imageView = root.findViewById(R.id.wallpaper);
        final TextureView videoView = root.findViewById(R.id.wallpaper_video);
        if (imageView == null || videoView == null || wallpapers == null) return;

        String want = wallpapers.current();
        if (want.equals(wallpaperShown)) return;
        final long t0 = android.os.SystemClock.uptimeMillis();

        // 先清场:上一张可能是视频(播放器要收、监听要摘 —— 留着它还会去开上一个视频),
        // 也可能是图(位图要放开引用),还可能是网页(WebView 要停,否则它在背后接着跑)
        releaseVideoPlayer();
        releaseWebWallpaper();
        videoView.setSurfaceTextureListener(null);
        videoView.setVisibility(View.GONE);
        imageView.setImageDrawable(null);
        imageView.setVisibility(View.GONE);
        wallpaperShown = WallpaperStore.NONE;

        if (want.isEmpty()) {
            final long tRelease = android.os.SystemClock.uptimeMillis();
            pushTint(want);   // 没壁纸了,面板底色退回模式自带的
            // 早退这条路也要计时:2026-08-25 查启动耗时,壁纸段占了 270ms 却一条日志都没有,
            // 就是因为没设壁纸时从这儿返回、什么也不打,只能靠猜(猜错两次)
            Log.i(TAG, "无壁纸 总" + (android.os.SystemClock.uptimeMillis() - t0) + "ms"
                    + " = 取值+清场" + (tRelease - t0)
                    + " + 底色" + (android.os.SystemClock.uptimeMillis() - tRelease));
            return;
        }

        if (WallpaperStore.isWebId(want)) {
            /*
             * **先垫预览图,页面延后加载** —— 跟视频壁纸那条路一个道理(见下面 isVideoId 分支)。
             *
             * 起 WebView 要把整个 Chromium 拉起来(还会多起一个 sandboxed_process 进程),
             * 再加载页面。2026-08-25 真车实测:锦鲤池这张,壁纸段占掉 558ms,而整个
             * onCreate 才 1119ms —— 一多半是它。
             *
             * 这段时间是**主线程**,而系统给无障碍服务装零件也要用主线程。十次观察下来
             * 界线很干净:主线程占用 900ms 以内 8 次全绑上,1335/1450ms 那两次全卡死,
             * 卡死之后再也救不回来(见 Overlay.ensureAccessibilityBound)。
             * 所以这半秒必须从启动那条 message 里挪走。
             *
             * 延后到 WEB_START_DELAY_MS 而不是 post 一帧:post 只是排到队尾,绑定的消息
             * 要是还没进队列,照样被我们挤在后面。隔开半秒是给它留出发起的窗口。
             * 代价是交互壁纸晚半秒开始动 —— 垫着预览图,看上去就是"静止的壁纸忽然活了",
             * 视频壁纸一直是这个观感。
             */
            Bitmap poster = wallpapers.load(want,
                    act.getResources().getDisplayMetrics().widthPixels,
                    act.getResources().getDisplayMetrics().heightPixels);
            if (poster != null) {
                imageView.setImageBitmap(poster);
                imageView.setVisibility(View.VISIBLE);
            }
            wallpaperShown = want;
            pushTint(want);
            handler.postDelayed(() -> {
                // 这半秒里人可能又换了一张,那这次加载就作废
                if (wallpapers == null || !want.equals(wallpapers.current())) return;
                if (showWebWallpaper(want)) {
                    // 页面就位,撤掉垫图(留着它会一直盖在网页上)
                    imageView.setVisibility(View.GONE);
                    imageView.setImageDrawable(null);
                } else {
                    // 目录还在但入口 html 没了(用户删了半个目录)。当没选过 —— 跟静态图
                    // 解不出来时一个处理法,留着这个 id 每次进桌面都要白试一次。
                    wallpapers.set(WallpaperStore.NONE);
                }
            }, WEB_START_DELAY_MS);
            return;
        }

        DisplayMetrics dm = act.getResources().getDisplayMetrics();

        if (WallpaperStore.isVideoId(want)) {
            // 底下垫一张首帧静态图。播放器在离开桌面时是**整个收掉**的(见 onStop),
            // 回来重新 prepare 要一两百毫秒,这期间 TextureView 是空的 —— 没有垫底就
            // 黑一下再亮。垫上之后,回桌面看到的是"静止的壁纸忽然动起来"。
            // 按半个屏幕解就够:它只在那一两百毫秒里露脸,省一半内存。
            final long tClear = android.os.SystemClock.uptimeMillis();
            // 优先用滑动那边已经解好的那张(见 presetPoster)。**这一步是这轮最大的一笔** ——
            // 视频壁纸没有现成封面时 load() 要抽帧,真车实测 350~450ms,而滑动切壁纸时
            // 它正压在翻页动画结束的那一帧上(分段计时:总 458ms 里首帧图占 452ms)。
            Bitmap poster = takePreset(want);
            if (poster != null) {
                imageView.setImageBitmap(poster);
                imageView.setVisibility(View.VISIBLE);
            } else {
                // 没有现成的(从外观面板里选、或者冷启动):**扔后台解**,解完再贴。
                // 它的活儿是"顶住视频起播前那几百毫秒",而起播本来就是异步的 ——
                // 两件事并行跑,谁先好谁先露面,都不该占着主线程。
                final String posterFor = want;
                POSTER_IO.execute(() -> {
                    final Bitmap b = wallpapers.load(
                            posterFor, dm.widthPixels / 2, dm.heightPixels / 2);
                    if (b == null) return;
                    handler.post(() -> {
                        // 解这几百毫秒里可能又切了一张 —— 那这张就不该再贴上去
                        if (!posterFor.equals(wallpaperShown)) return;
                        imageView.setImageBitmap(b);
                        imageView.setVisibility(View.VISIBLE);
                    });
                });
            }
            final long tPoster = android.os.SystemClock.uptimeMillis();
            // **换一块新画布**。同一个 SurfaceTexture 一直复用的话,上一个视频的内容还躺在
            // buffer 里,而新视频分辨率不同时(内置素材都是 1920×1080,个人素材可能是 4K)
            // 这块 buffer 会按**新的宽度**重新解释旧数据 —— 每个 buffer 行塞进两行视频,
            // 于是画面水平出现两份、垂直压掉一半、UV 平面错位而褪成灰白,下半截还露着
            // 没被覆盖的上一张。真车硬解上实拍到过;模拟器是软解、每次切源都干净地重分配,
            // 复现不出来,别照着模拟器的表现判断这段有没有用。
            //
            // detach 再 attach 就会逼 TextureView 把 SurfaceTexture 销毁重建。重建之后
            // isAvailable() 变 false,ensureVideoWallpaper 会早退,改由
            // onSurfaceTextureAvailable 回调来起播放器 —— 这条路本来就在。
            // 加 isAvailable 守卫:只有真有残留画布时才折腾,冷启动和从静态图切过来时白跑。
            ViewGroup canvasParent = (ViewGroup) videoView.getParent();
            if (videoView.isAvailable() && canvasParent != null) {
                // **必须插回原来的位置**:层序是 壁纸 → 待滑入的那张 → 视频 → 内容层,
                // 索引错了视频就会盖到卡片和时间上面去
                int at = canvasParent.indexOfChild(videoView);
                canvasParent.removeView(videoView);
                canvasParent.addView(videoView, at);
            }

            // **再把这块画布蒙上**。上面那步只保证画布是新的,不保证**马上有画面**:
            // 新播放器 prepare 要几十到几百毫秒(实测模拟器上 156ms,真车按视频体积和
            // 解码速度放大),这段时间视频层是空的,而它又盖在垫底首帧图之上。
            // 不蒙的话,从静态图切过来、或者画布没被重建的那些路径上,露的就是上一张 ——
            // 看着就是切换时"闪一下上一个壁纸"。
            // 蒙住期间由底下那张**新的**首帧图顶着,等真出了帧再揭开(见 openVideoWallpaper)。
            // 两件事是互补的:换画布杀掉**旧内容**,蒙布填住**还没有新内容**的那段空窗。
            videoView.setAlpha(0f);
            videoView.setVisibility(View.VISIBLE);
            videoView.setSurfaceTextureListener(videoSurfaceListener(videoView));
            wallpaperShown = want;
            final long tCanvas = android.os.SystemClock.uptimeMillis();
            pushTint(want);
            final long tTint = android.os.SystemClock.uptimeMillis();
            ensureVideoWallpaper();
            long tEnd = android.os.SystemClock.uptimeMillis();
            // 换壁纸卡不卡就看这个数:**主线程**上从"开始换"到"交出去"的时间。
            // 分段打是因为总数说明不了问题 —— 起播是异步的、收旧播放器也挪到后台了,
            // 剩下这几段里到底谁贵,只能量。滑动切壁纸时这段正压在翻页动画的最后一帧上。
            Log.i(TAG, "换视频壁纸 总" + (tEnd - t0) + "ms"
                    + " = 清场" + (tClear - t0)
                    + " + 首帧图" + (tPoster - tClear)
                    + " + 换画布" + (tCanvas - tPoster)
                    + " + 取色" + (tTint - tCanvas)
                    + " + 起播" + (tEnd - tTint));
            return;
        }

        // 静态图。按屏幕尺寸降采样:这张图只用来铺满整屏,解得比屏幕还大是纯浪费。
        // 放在主线程解是有意的 —— 壁纸得赶在首帧之前就位,扔到后台线程去,启动时会
        // 先黑一下再"啪"地出图。降采样已经把这件事压到几十毫秒。
        Bitmap bmp = wallpapers.load(want, dm.widthPixels, dm.heightPixels);
        if (bmp == null) {
            // 解不出来(文件被删了 / assets 换过)就当没选过,把选择也清掉 ——
            // 留着一个指向空文件的名字,下次进来还会再白解一次
            wallpapers.set(WallpaperStore.NONE);
            return;
        }
        imageView.setImageBitmap(bmp);
        imageView.setVisibility(View.VISIBLE);
        wallpaperShown = want;
        pushTint(want);
    }

    /**
     * 把壁纸底部那条的主色喂给模式层。
     *
     * 面板底色设成「跟随壁纸」时,面板/弹窗/Dock 都用它(见 ModeManager.surface())。
     * 取色要读壁纸文件、是 WallpaperStore 的活,而 ModeManager 又被 WallpaperStore 依赖,
     * 所以由桌面在这里牵一下线,避免两个包互相依赖成环。
     *
     * **两个时机都要调:换壁纸,和换配色。** 取出来的色亮度是按当前配色的明暗钉死的
     * (深 0.13 / 浅 0.88,见 WallpaperStore.bottomTint),所以配色一换这个值就过期了。
     */
    public void pushTint(String id) {
        ModeManager tm = ModeManager.get();
        boolean dark = androidx.core.graphics.ColorUtils.calculateLuminance(tm.paletteMode().bg) < 0.5;
        tm.setWallpaperTint(id.isEmpty() ? 0 : wallpapers.bottomTint(id, dark));
        // Dock 是悬浮窗,不跟着 Activity 重建,得单独刷一次。
        // 走单例而不是 host 字段:壁纸要赶在首帧之前铺好,apply 排在 onCreate 很靠前的
        // 位置,那时 host 还没赋值 —— 用字段会 NPE,桌面直接起不来
        DockHost.get(act).onWallpaperChanged();
        StatusBarHost.get(act).onWallpaperChanged();
    }

    /**
     * 该播就播。三个条件缺一不可:当前壁纸是视频、SurfaceTexture 已就绪、播放器还没开着。
     *
     * 走到这儿的路有三条 —— 选中一张视频壁纸、桌面重新可见(onStart)、SurfaceTexture
     * 就绪。它们谁先谁后不一定(冷启动时视图还没布局完,从别的 app 回来时视图早就绪了),
     * 所以不各写一遍,统一在这里用条件收口。
     */
    private void ensureVideoWallpaper() {
        if (wallpaperPlayer != null || wallpapers == null) return;
        TextureView view = root.findViewById(R.id.wallpaper_video);
        if (view == null) return;
        String id = wallpapers.current();
        if (!WallpaperStore.isVideoId(id)) return;
        if (!view.isAvailable()) return;    // 等 onSurfaceTextureAvailable 回来再说
        openVideoWallpaper(view, new Surface(view.getSurfaceTexture()), id);
    }

    private TextureView.SurfaceTextureListener videoSurfaceListener(final TextureView view) {
        return new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                ensureVideoWallpaper();
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                if (wallpaperPlayer != null) {
                    cropVideoWallpaper(view, wallpaperPlayer.getVideoWidth(), wallpaperPlayer.getVideoHeight());
                }
            }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                // 画布没了,播放器留着也没处画。返回 true = 交给系统释放这个 SurfaceTexture
                releaseVideoPlayer();
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }
        };
    }

    private void openVideoWallpaper(final TextureView view, Surface surface, String id) {
        MediaPlayer mp = new MediaPlayer();
        try {
            // 官方在 assets 里、个人是普通文件,这一步的差异由 WallpaperStore 吃掉
            if (!wallpapers.attach(mp, id)) {
                mp.release();
                return;
            }
            mp.setSurface(surface);
            mp.setLooping(true);
            // 壁纸不该出声。素材里带着音轨也照样静音 —— 桌面背景发出声音是纯粹的骚扰,
            // 而且会去抢音频焦点,正在放的音乐会被压掉一截
            mp.setVolume(0f, 0f);
            mp.setOnPreparedListener(p -> {
                cropVideoWallpaper(view, p.getVideoWidth(), p.getVideoHeight());
                // 画中画层盖着:先不起,幕也不揭(底下垫着首帧图)。让开时 setCovered 再放
                if (covered) return;
                p.start();
                // 兜底揭幕:**不是每个平台都发 RENDERING_START**,等不到的话壁纸会一直停在
                // 那张静止的首帧图上。守卫 wallpaperPlayer == mp —— 这 400ms 里要是又切了
                // 一张,这一发就不能去揭新那层的幕(否则新的还没出帧就被掀开,照样闪)
                handler.postDelayed(() -> {
                    if (wallpaperPlayer == mp) view.setAlpha(1f);
                }, 400);
            });
            // 揭幕:**只认播放器自己报的"开始出画面"**。不能拿 onSurfaceTextureUpdated 当信号 ——
            // 那个回调分不清新旧帧(旧 buffer 被 latch 一次也会响),会在旧帧上把幕掀开。
            // 这个事件由播放器侧发出,和这层 view 画不画无关,alpha=0 堵不住它。
            mp.setOnInfoListener((p, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) view.setAlpha(1f);
                return false;
            });
            mp.setOnErrorListener((p, what, extra) -> {
                // 放不了就收掉,画面退回垫底的首帧图 —— 比黑屏体面,也别一直重试
                Log.w(TAG, "视频壁纸播放失败 what=" + what + " extra=" + extra);
                releaseVideoPlayer();
                return true;
            });
            mp.prepareAsync();
            wallpaperPlayer = mp;
        } catch (Exception e) {
            Log.w(TAG, "开不了视频壁纸 " + id, e);
            mp.release();
        }
    }

    /**
     * 视频按 centerCrop 铺满,不拉伸变形。
     *
     * TextureView 默认把画面硬拉到自己的尺寸,视频和屏幕比例不一致时人脸就横了。
     * 这里在"已经被拉满"的基础上再缩回正确比例:按较大的那个缩放系数放大,让短边
     * 先铺满、长边溢出被裁掉 —— 就是 ImageView 的 centerCrop。
     */
    private void cropVideoWallpaper(TextureView view, int videoW, int videoH) {
        int w = view.getWidth(), h = view.getHeight();
        if (videoW <= 0 || videoH <= 0 || w <= 0 || h <= 0) return;
        float scale = Math.max(w / (float) videoW, h / (float) videoH);
        Matrix m = new Matrix();
        m.setScale(videoW * scale / w, videoH * scale / h, w / 2f, h / 2f);
        view.setTransform(m);
    }

    /**
     * 收掉播放器,但不动视图 —— 视频壁纸仍然是"当前选中的壁纸",只是这会儿不播。
     *
     * 收的是硬解码器实例:MediaPlayer 一 prepare 就占着一路,**pause 也照占**。
     * 车机上这东西路数有限,画中画那两路虚拟屏还要抢,所以离开桌面时是 release
     * 而不是 pause(见 onStop)。回来重开的代价是一两百毫秒,由垫底的首帧图接住。
     */
    private void releaseVideoPlayer() {
        final MediaPlayer old = wallpaperPlayer;
        if (old == null) return;
        // **先摘牌再收**:字段立刻置空,后面那些 `wallpaperPlayer == mp` 的守卫
        // (揭幕、兜底揭幕)当场就认得出"这个播放器已经不作数了"
        wallpaperPlayer = null;
        // **收在后台线程**。stop/release 是同步 IPC 到 mediaserver、要等它真的把那一路
        // 硬解码器放掉 —— 摆在主线程上就是切壁纸时卡的那一下(尤其滑动切:它正好落在
        // 翻页动画结束的那一帧里,一卡就是"动画走完了画面顿一下")。
        // 收掉一个已经摘牌的播放器不需要任何人等它,这一步天然可以异步。
        RELEASE_IO.execute(() -> {
            try {
                old.stop();
            } catch (Exception ignored) {   // 没 prepare 完就 stop 会抛,不影响 release
            }
            old.release();
        });
    }

    /** 滑动那边解好的首帧图,和它对应的壁纸 id。见 {@link #presetPoster}。 */
    private String presetPosterId;
    private Bitmap presetPoster;

    /**
     * 滑动切壁纸时,{@link WallpaperSwiper} 把它在后台解好的那张图递过来。
     *
     * 这张图和重铺时要垫底的那张是同一张 —— 不接的话 {@link #apply} 会在主线程重解一次,
     * 而视频壁纸没有现成封面时那是一次抽帧(真车 350~450ms),正好压在翻页动画的最后一帧上。
     */
    public void presetPoster(String id, Bitmap bmp) {
        presetPosterId = id;
        presetPoster = bmp;
    }

    /** 取走预置的那张(**只认同一个 id,而且只用一次**),没有就返回 null。 */
    private Bitmap takePreset(String want) {
        Bitmap b = presetPoster;
        boolean hit = b != null && !b.isRecycled() && want.equals(presetPosterId);
        presetPoster = null;
        presetPosterId = null;
        return hit ? b : null;
    }

    /**
     * 解首帧图的后台线程。单线程:连着滑几张时后面的没必要和前面的抢,
     * 而且**排在后面的那次多半已经过期**(壁纸又换了),白解一张不如让它排队。
     */
    private static final java.util.concurrent.ExecutorService POSTER_IO =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "blank-wp-poster");
                t.setDaemon(true);
                return t;
            });

    /**
     * 专门用来收播放器的单线程。
     *
     * 单线程而不是线程池:**收的顺序要和开的顺序一致** —— 车机上硬解码器路数有限,
     * 两个 release 并发跑没有任何好处,反而可能和新播放器的 prepare 抢。
     */
    private static final java.util.concurrent.ExecutorService RELEASE_IO =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "blank-wp-release");
                t.setDaemon(true);
                return t;
            });

    /**
     * 铺网页壁纸。
     *
     * 这是第三种壁纸,和图、视频三选一。它存在的理由是 Wallpaper Engine 的 **web 类**壁纸
     * 就是一份标准的 HTML + JS(Canvas/WebGL),整个目录拷进「留白壁纸」就能跑 ——
     * 素材现成、而且**本来就是照着交互写的**(点一下有反应,鼠标/触摸都吃)。
     * 它那个 scene 类(.pkg/.mpkg)进不来:私有二进制,解析器在人家自己的 native 引擎里。
     *
     * @return 真的铺上了才返回 true;找不到入口 html 返回 false,由调用方按"没这张"处理
     */
    private boolean showWebWallpaper(String id) {
        WebView web = inflateWebIfNeeded();
        if (web == null || wallpapers == null) return false;

        File dir = wallpapers.webDir(id);
        File entry = wallpapers.webEntry(id);
        if (dir == null || entry == null) return false;

        // 入口相对站点根的路径。project.json 的 file 字段可能带子目录(html/index.html),
        // 所以不能想当然拼 index.html
        String rel = entry.getAbsolutePath().substring(dir.getAbsolutePath().length() + 1);

        // 把壁纸目录挂成一个**假的 https 站点**,不直接 file://。
        //
        // file:// 下每个文件各算一个源 —— index.html 引同目录的 script.js 都算跨源,
        // 会被静静拦掉(画面空白,一条错都不报)。想放行就只有
        // setAllowFileAccessFromFileURLs 这一个开关,而它不是"只放行同目录",
        // 是整个 file:// 一起放行:壁纸的脚本从此能 XHR 读设备上任何本 app 读得到的文件。
        // 壁纸是第三方代码(而且以后可能是下载来的),不该给这个权限。
        //
        // 挂成 https 之后,同源策略自己就对了:相对路径天然同源,而 https 页面本来
        // 就读不到 file:// —— 边界由浏览器保证,不靠我们记得别开哪个开关。
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/", new WallpaperFiles(dir))
                .build();
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                return loader.shouldInterceptRequest(req.getUrl());
            }
        });

        // 调壁纸联动的映射曲线不用每次改完都重新编译部署:开着 USB 调试口,
        // chrome://inspect 直接连上这个 WebView 的 DevTools console,现改现看。
        // 只在 debug 包开——这个口子对壁纸这种第三方代码来说不是能留给 release 的东西。
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);           // 壁纸的动画全在 JS 里,关了就是一张白纸
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);            // 走 https 了,一点 file:// 都不需要
        s.setAllowFileAccessFromFileURLs(false);
        s.setMediaPlaybackRequiresUserGesture(false);   // 壁纸里的视频/音频要能自己播

        // 壁纸不是网页:不许滚、不许缩放、不许长按选中文字,更不许有白底。
        // 白底尤其要紧 —— WebView 默认不透明白,加载那一瞬间会**白闪一下**,
        // 在一块常驻的车机屏上格外扎眼。
        web.setBackgroundColor(Color.TRANSPARENT);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setLongClickable(false);

        web.loadUrl(WEB_WALLPAPER_ORIGIN + rel);
        web.setVisibility(View.VISIBLE);
        web.onResume();
        webWallpaperOn = true;
        webTrackKey = "";               // 换了张壁纸,新页面还什么都不知道,下一拍重喂
        lastFishSpeed = -1;             // 同理,新页面的 fishSpeed 也要重喂一次
        lastFishCount = -1;
        lastCharging = -1;
        handler.removeCallbacks(webTrackTick);
        handler.postDelayed(webTrackTick, TRACK_FIRST_DELAY_MS);
        return true;
    }

    /** 页面加载完之前喂进去也是白喂(那时候 livelyCurrentTrack 还没定义)。 */
    private static final long TRACK_FIRST_DELAY_MS = 1500;
    private static final long TRACK_TICK_MS = 2000;

    /**
     * 把「正在播放」喂给网页壁纸,每两秒一拍。
     *
     * 这是 Lively 那套壁纸接口里的一个:壁纸声明
     * {@code window.livelyCurrentTrack = function(data){...}},由引擎在换歌时调它。
     * 留白就是这里的"引擎"。**壁纸作者不需要为留白做任何事** —— 它照着 Lively 写的,
     * 我们照着 Lively 喂。
     *
     * MediaHub 是快照式的(没有换歌回调),所以只能轮询。开销压在两处:
     * 只在铺着网页壁纸时跑,以及歌没换就不发(封面是几十 KB 的 base64,每两秒发一次
     * 纯属浪费)。
     */
    private final Runnable webTrackTick = new Runnable() {
        @Override public void run() {
            if (!webWallpaperOn) return;
            pushTrackToWeb();
            pushSpeedToWeb();
            pushBatteryToWeb();
            pushChargingToWeb();
            handler.postDelayed(this, TRACK_TICK_MS);
        }
    };

    private void pushTrackToWeb() {
        WebView web = root.findViewById(R.id.wallpaper_web);
        if (web == null || !webWallpaperOn) return;

        if (webMedia == null) webMedia = new MediaHub(act);
        // 没给通知使用权就读不到任何会话,current() 一直是 null —— 那就一直喂空,
        // 壁纸自己会退回它的装饰性歌单
        NowPlaying np = webMedia.hasPermission() ? webMedia.current() : null;

        String key = np == null ? "" : np.title + '\u0001' + np.artist;
        if (key.equals(webTrackKey)) return;     // 没换歌
        webTrackKey = key;

        JSONObject o = new JSONObject();
        try {
            if (np != null) {
                o.put("Title", np.title == null ? "" : np.title);
                o.put("Artist", np.artist == null ? "" : np.artist);
                String art = coverDataUri(np.cover);
                if (art != null) o.put("Thumbnail", art);
            }
        } catch (JSONException e) {
            return;     // 拼不出来就这一拍不喂,下次换歌再说
        }
        // 空对象 = 什么都没在放,壁纸那边会退回自己的装饰歌单(它自己判的)
        //
        // JSONObject 不转义 U+2028/U+2029,而这两个在 JS 里是**换行**,
        // 歌名里带上一个就能把这行注入的代码劈成两半 —— 歌名是第三方 app 给的,
        // 不能假设它干净。
        String json = o.toString().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
        web.evaluateJavascript(
                "if(window.livelyCurrentTrack)window.livelyCurrentTrack(" + json + ");", null);
    }

    /**
     * 把车速映射成鱼游速度喂给网页壁纸,跟 {@link #pushTrackToWeb} 同一拍。
     *
     * 用的是 Lively 那套接口里的另一个:{@code window.livelyPropertyListener(name, value)}。
     * 只有锦鲤池这张壁纸声明了 fishSpeed 这个属性,但不区分具体是哪张 web 壁纸都喂——
     * 跟 livelyCurrentTrack 一个取舍,没监听的壁纸这行 JS 判空就是安全的空喂。
     *
     * 车速读取要走提权 shell、走一趟 binder,不能堵在主线程上,用 CarCtrl 的异步版本。
     */
    private void pushSpeedToWeb() {
        if (!webWallpaperOn || !CarCtrl.available()) return;
        CarCtrl.readSpeed((ok, kmh) -> {
            if (!ok || !webWallpaperOn) return;
            WebView web = root.findViewById(R.id.wallpaper_web);
            if (web == null) return;

            // 用 tools/wallpaper-speed-tuner.html 实测调出来的曲线,不是
            // LivelyProperties.json 声明的 0.5~3 那个滑块范围——那只是壁纸自带设置
            // 面板给人手动拖的范围,fishSpeed 这个量本身在 JS 里没有二次 clamp,
            // 超出去照样是有效值,车速快起来鱼确实能游得更疯。
            double fishSpeed = Math.max(1.0, Math.min(10.0, 1.0 + kmh / 30.0));
            if (fishSpeed == lastFishSpeed) return;     // 没跨整数 km/h,值没变
            lastFishSpeed = fishSpeed;
            pushLivelyProperty(web, "fishSpeed", String.valueOf(fishSpeed));
        });
    }

    /**
     * 把电量百分比映射成鱼数喂给网页壁纸,跟 {@link #pushTrackToWeb} 同一拍。
     *
     * 0~100% 线性映射到 5~20 条鱼——LivelyProperties.json 声明的上限是 50,但那是
     * 壁纸作者按桌面端 GPU 给的余量,车机上封顶到 20,别为了好看烧性能。电量高时
     * 塘里挤,电量低时稀但不至于看不出是个鱼塘。新鱼从画面外游进来、多的游出去
     * (见 tools/wallpaper-speed-tuner.html 那次顺带改的 syncKois/entering/leaving),
     * 这里只管报目标数量,进出场动画是壁纸自己的事。
     */
    private void pushBatteryToWeb() {
        if (!webWallpaperOn || !CarCtrl.available()) return;
        CarCtrl.readBatteryPercent((ok, percent) -> {
            if (!ok || !webWallpaperOn) return;
            WebView web = root.findViewById(R.id.wallpaper_web);
            if (web == null) return;

            int fishCount = Math.max(5, Math.min(20, 5 + percent * 15 / 100));
            if (fishCount == lastFishCount) return;
            lastFishCount = fishCount;
            pushLivelyProperty(web, "fishCount", String.valueOf(fishCount));
        });
    }

    /**
     * 把"是不是正在充电"喂给网页壁纸,跟 {@link #pushTrackToWeb} 同一拍——充电时
     * 壁纸自己会每隔几秒撒一次食,不用等人去点屏幕(车停着充电本来就没人戳它)。
     *
     * 判据是充电桩工作状态 == start,不是"插上枪"就算:插着但暂停中(比如到了
     * 保电上限)不该一直撒食,那不是"正在充"。
     */
    private void pushChargingToWeb() {
        if (!webWallpaperOn || !CarCtrl.available()) return;
        CarCtrl.read(CarCtrl.DEV_CHARGING, CarCtrl.F_CHARGING_WORK_STATE, (ok, state) -> {
            if (!ok || !webWallpaperOn) return;
            WebView web = root.findViewById(R.id.wallpaper_web);
            if (web == null) return;

            int charging = state == CarCtrl.CHARGING_WORK_STATE_START ? 1 : 0;
            if (charging == lastCharging) return;
            lastCharging = charging;
            pushLivelyProperty(web, "charging", String.valueOf(charging));
        });
    }

    /** {@link #pushSpeedToWeb}/{@link #pushBatteryToWeb}/{@link #pushChargingToWeb} 共用的注入。 */
    private void pushLivelyProperty(WebView web, String name, String jsValue) {
        web.evaluateJavascript("if(window.livelyPropertyListener)"
                + "window.livelyPropertyListener('" + name + "'," + jsValue + ");", null);
    }

    /**
     * 专辑封面转成 data URI。
     *
     * 要先缩:播放器给的原图常有 1000 px 见方,转成 base64 是几百 KB,
     * 而壁纸上那个封面只有指甲盖大。缩到 256 再压 JPEG,一般十几 KB。
     */
    private static String coverDataUri(Bitmap cover) {
        if (cover == null || cover.isRecycled()) return null;
        try {
            int max = Math.max(cover.getWidth(), cover.getHeight());
            Bitmap small = max <= 256 ? cover : Bitmap.createScaledBitmap(cover,
                    cover.getWidth() * 256 / max, cover.getHeight() * 256 / max, true);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            small.compress(Bitmap.CompressFormat.JPEG, 80, bos);
            if (small != cover) small.recycle();
            return "data:image/jpeg;base64,"
                    + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            Log.w(TAG, "封面转 data URI 失败", e);
            return null;
        }
    }

    /**
     * 网页壁纸这个假站点的地址。域名用 WebViewAssetLoader 的默认值 ——
     * 那是 Google 专门留给这个用途的,不会和真实域名撞上。
     */
    private static final String WEB_WALLPAPER_ORIGIN =
            "https://appassets.androidplatform.net/";

    /**
     * 把一个壁纸目录当站点根来供文件。
     *
     * 自带的 InternalStoragePathHandler 用不了 —— 它只认 app 内部存储,
     * 而壁纸在外部存储(内部存储/留白壁纸/)。这个接口本身就一个方法,自己实现更省事。
     */
    private static final class WallpaperFiles implements WebViewAssetLoader.PathHandler {
        private final File root;

        WallpaperFiles(File root) { this.root = root; }

        @Override
        public WebResourceResponse handle(String path) {
            try {
                File f = new File(root, path);
                // 指到目录就取里面的 index.html —— 正经 web 服务器都这么干。
                // 壁纸里 <a href="子目录/"> 或者脚本里 location="子目录/" 都会走到这,
                // 不认的话页面直接 ERR_CONNECTION_CLOSED(第 25 小时那张就栽在这)。
                if (f.isDirectory()) f = new File(f, "index.html");

                // **规范化之后必须还在根底下**。壁纸是第三方代码,页面里写一个
                // ../../../ 就能把这个 handler 当成读任意文件的通道 ——
                // 正是换掉 file:// 想堵的那个洞,别在这儿又开一个。
                String canon = f.getCanonicalPath();
                String base = root.getCanonicalPath() + File.separator;
                if (!canon.startsWith(base) || !f.isFile()) return null;

                String mime = mimeOf(f.getName());
                // 文本类必须显式给 utf-8:不给的话按 latin-1 解,页面里的中文全是乱码。
                // 二进制给了反而多余,传 null。
                String enc = mime.startsWith("text/") || mime.endsWith("javascript")
                        || mime.endsWith("json") ? "utf-8" : null;
                return new WebResourceResponse(mime, enc, new FileInputStream(f));
            } catch (IOException e) {
                return null;    // 返回 null = 交回给 WebView 按 404 处理
            }
        }

        /**
         * 关键几个类型自己认,别全交给 MimeTypeMap —— 它对 .js 在有些机器上返回 null,
         * 而 MIME 不对浏览器就不执行脚本,壁纸直接是张白纸。
         */
        private static String mimeOf(String name) {
            String n = name.toLowerCase();
            if (n.endsWith(".html") || n.endsWith(".htm")) return "text/html";
            if (n.endsWith(".js") || n.endsWith(".mjs")) return "text/javascript";
            if (n.endsWith(".css")) return "text/css";
            if (n.endsWith(".json")) return "application/json";
            if (n.endsWith(".svg")) return "image/svg+xml";
            int dot = n.lastIndexOf('.');
            String guess = dot < 0 ? null : MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(n.substring(dot + 1));
            return guess == null ? "application/octet-stream" : guess;
        }
    }

    /**
     * 收掉网页壁纸。
     *
     * 光 GONE 不管用 —— WebView 看不见照样在跑 requestAnimationFrame,那是一份白烧的
     * GPU/CPU,和视频壁纸占着解码器是同一类账(见 releaseVideoPlayer)。所以要
     * onPause + 把页面换成空白,让里面的脚本真正停下来。
     */
    /**
     * 按需把网页壁纸那块 WebView 铺出来(布局里躺着的是 ViewStub)。
     *
     * **不能写死在 activity_home.xml 里**:WebView 一被 inflate 就会拉起整个 Chromium,
     * 2026-08-25 真车实测 setContentView 因此要 244ms(整个 onCreate 才 595ms),而绝大多数
     * 人根本没选网页壁纸。那段主线程占用正是无障碍绑定撞不上窗口的主因 —— 系统要在主线程
     * 建 AccessibilityService,撞不上就永久卡死(见 Overlay.ensureAccessibilityBound)。
     *
     * inflate 过一次之后 stub 就没了,findViewById 直接命中,所以这里可以反复调。
     */
    private WebView inflateWebIfNeeded() {
        WebView web = root.findViewById(R.id.wallpaper_web);
        if (web != null) return web;
        View stub = root.findViewById(R.id.wallpaper_web_stub);
        if (!(stub instanceof ViewStub)) return null;
        ((ViewStub) stub).inflate();
        return root.findViewById(R.id.wallpaper_web);
    }

    /** 收网页壁纸。**这里故意不 inflate** —— 没铺过就没什么可收的,别为了收一下反而把它建出来。 */
    private void releaseWebWallpaper() {
        WebView web = root.findViewById(R.id.wallpaper_web);
        if (web == null) return;
        if (webWallpaperOn) {
            web.onPause();
            web.loadUrl("about:blank");
        }
        web.setVisibility(View.GONE);
        webWallpaperOn = false;
        handler.removeCallbacks(webTrackTick);
    }

    /**
     * 把触摸**抄送**一份给网页壁纸。
     *
     * 为什么要抄送、而不是让它自己在 view 树里收:壁纸在最底层,上面压着主页层
     * (clickable,要接长按开外观)、卡片、Dock —— 事件在上面就被吃光了,永远轮不到它。
     * 而网页壁纸的意义有一半在"点一下有反应",收不到触摸就只是张会动的图。
     *
     * 抄送是**旁路**:原来那条链路一点不动,桌面手势、卡片点击的行为跟没有壁纸时一样。
     * 代价是壁纸会收到一些"其实是给桌面的"触摸(比如按在卡片上),这反而符合直觉 ——
     * 真手指确实落在那儿了。但**手势一旦被桌面认领就得告诉它作废**(cancel=true),
     * 不然横滑切壁纸时,壁纸里那条涟漪会一直跟着手指画到底。
     *
     * 坐标要换算:事件是相对 DecorView 的,WebView 未必贴着 DecorView 左上角。
     */
    public void feedTouch(MotionEvent ev, boolean cancel) {
        if (!webWallpaperOn) return;
        WebView web = root.findViewById(R.id.wallpaper_web);
        if (web == null || web.getVisibility() != View.VISIBLE) return;

        int[] at = new int[2];
        web.getLocationOnScreen(at);
        MotionEvent copy = MotionEvent.obtain(ev);
        if (cancel) copy.setAction(MotionEvent.ACTION_CANCEL);
        // 整体平移(不是 setLocation):多指时每个指针都要跟着挪
        copy.offsetLocation(ev.getRawX() - at[0] - ev.getX(), ev.getRawY() - at[1] - ev.getY());
        web.dispatchTouchEvent(copy);
        copy.recycle();
    }

    /**
     * 桌面看不见时把网页壁纸冻住,回来再解冻。跟视频壁纸同一笔账 —— 看不见的动画
     * 不该接着烧 GPU,而网页壁纸里跑的多半是 requestAnimationFrame,不停它就一直画。
     *
     * 只用实例级的 {@link WebView#onPause()},**不用 pauseTimers()** ——
     * 那个是进程全局的,会把 BrowserActivity 里正开着的网页一起冻住。
     *
     * 也不像视频那样整个释放:网页壁纸重来一次要重新加载 + 重跑初始化脚本,
     * 比 MediaPlayer 重新 prepare 贵得多,而它并不占硬解码器那种稀缺资源。
     */
    private void setWebWallpaperRunning(boolean running) {
        if (!webWallpaperOn) return;
        WebView web = root.findViewById(R.id.wallpaper_web);
        if (web == null) return;
        if (running) web.onResume(); else web.onPause();

        // 喂歌那一拍跟着停 —— 桌面都看不见了,轮询播放状态没有意义。
        // 回来时重置 webTrackKey:冻住这段时间里歌可能换过好几首,不重置就一直不喂。
        handler.removeCallbacks(webTrackTick);
        if (running) {
            webTrackKey = "";
            handler.postDelayed(webTrackTick, TRACK_TICK_MS);
        }
    }

    /**
     * 画中画层盖住 / 让开壁纸。
     *
     * 盖住时**暂停**,不像 onStop 那样整个 release:收起/展开画中画层是高频动作,重新 prepare
     * 要一两百毫秒、还得靠首帧图垫着过渡,露着的那圈边会"跳一下";暂停则原帧停在那,
     * 让开时接着放。代价是暂停期间仍占着一路解码器 —— 这一层盖着的时候画中画里跑的是
     * 地图/音乐,不抢这个。桌面整个看不见了照旧走 onStop 释放。
     */
    public void setCovered(boolean covered) {
        if (this.covered == covered) return;
        this.covered = covered;
        final MediaPlayer mp = wallpaperPlayer;
        if (mp != null) {
            try {
                if (covered) {
                    mp.pause();
                } else {
                    mp.start();
                    // 盖着期间 prepare 完的播放器还没揭过幕,这里补 onPrepared 里那条兜底揭幕
                    final TextureView view = root.findViewById(R.id.wallpaper_video);
                    handler.postDelayed(() -> {
                        if (wallpaperPlayer == mp && !this.covered && view != null) view.setAlpha(1f);
                    }, 400);
                }
            } catch (IllegalStateException ignored) {
                // 还没 prepare 完(或压根没起过就 pause):onPrepared 会按 covered 决定起不起
            }
        }
        setWebWallpaperRunning(started && !covered);
    }

    /** 桌面重新可见:视频壁纸要重开 —— 上次不可见时把播放器整个交出去了(见 onStop)。 */
    public void onStart() {
        started = true;
        ensureVideoWallpaper();
        setWebWallpaperRunning(!covered);
    }

    /** 桌面完全看不见了:把解码器整个交出去,不是暂停(同 HomeActivity 原来的 onStop)。 */
    public void onStop() {
        started = false;
        releaseVideoPlayer();
        setWebWallpaperRunning(false);
    }

    /** 桌面销毁:播放器占着一路硬解码器,网页壁纸的脚本跟着 WebView 一起漏在那。 */
    public void onDestroy() {
        releaseVideoPlayer();
        releaseWebWallpaper();
    }
}
