package com.liubai.blank.ui;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.ImageView;

import com.liubai.blank.data.WallpaperStore;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 在桌面空白处左右滑,切上一张 / 下一张壁纸。
 *
 * <h3>为什么不用 ViewPager2</h3>
 *
 * 迪友那套滑动壁纸就是 ViewPager2 + PageTransformer(反编译 3.0.1,`n4/b0.java`:
 * pager 铺在最底层、起始索引 50000 取模做无限循环、四种 transformer 可选)。
 * 留白这边不行 —— **视频壁纸是 TextureView + MediaPlayer**,放进 pager 会被
 * 回收重建,那正是 activity_home.xml 里写明不用 ViewPager2 的原因。
 *
 * 所以自己接手势:两张 ImageView 跟手平移,松手用弹簧落定。
 *
 * <h3>丝滑靠这三件</h3>
 * <ol>
 *   <li><b>跟手</b>:手指移多少,壁纸走多少,不等松手才动;</li>
 *   <li><b>邻张预解</b>:滑动一开始就在后台把左右两张解出来(**半分辨率** —— 跟手途中
 *       是动的,看不出差别,内存也只有全解的四分之一)。不预解的话,第一帧要在主线程
 *       解一张全屏图,几十毫秒的卡顿正好落在手指刚动的那一刻,最难受;</li>
 *   <li><b>松手看速度不只看距离</b>:快速轻扫也该翻页,不然得拖过半屏才认,手感很钝。</li>
 * </ol>
 *
 * <h3>滑动途中一律是静态图</h3>
 *
 * 视频壁纸用它的首帧({@link WallpaperStore#load} 对视频 id 返回的就是首帧,和
 * {@code applyWallpaper} 里垫底那张同一个来源)。落定之后由宿主重新铺一次壁纸,
 * 那时才起播放器。跟手阶段要实时解两路视频的话,谈不上丝滑。
 */
public final class WallpaperSwiper {

    public interface Host {
        /** 滑到新的一张了。宿主负责落盘 + 重铺(视频壁纸在这一步才起播放器)。 */
        void onWallpaperPicked(String id);

        /**
         * 把刚滑进来的那张图**递过去**,别让宿主再解一遍。
         *
         * 这张图是本类在后台线程解好的(见 {@code decode}),而宿主重铺视频壁纸时
         * 还要一张同样的图垫底 —— 视频起播前的那几百毫秒由它顶着。不递的话宿主会
         * 在主线程重解一次:**视频壁纸没有现成封面时要抽帧,真车实测 350~450ms**,
         * 正好压在翻页动画结束的那一帧上(2026-08-23 分段计时:总 458ms 里首帧图占 452ms)。
         */
        void onWallpaperPoster(String id, android.graphics.Bitmap poster);
    }

    /** 翻页门槛:横向拖过屏幕宽度的这个比例就算翻。 */
    private static final float DIST_RATIO = 0.28f;
    /** 或者甩得够快也算翻(px/s),距离不够时用它兜 —— 轻扫比拖半屏自然。 */
    private static final float FLING_VELOCITY = 900f;

    private final View current;      // 当前壁纸那张(和视频首帧垫底共用)
    /**
     * 视频壁纸那层。**要跟着一起位移** —— 当前壁纸是视频时,画面是 TextureView 在出,
     * 只动底下那张首帧图的话,视频会原地不动、首帧图从它下面滑走,穿帮得很难看。
     *
     * 能这么跟是因为壁纸视频用的是 TextureView:它老老实实参与 View 树绘制,
     * translationX 对它有效(换成 SurfaceView 就不行了 —— 那是独立窗口层,
     * 见 activity_home.xml 里选型那段注释)。
     */
    private final View video;
    private final ImageView next;    // 将要滑进来的那张
    private final WallpaperStore store;
    private final Host host;

    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "blank-wallpaper-swipe");
        t.setDaemon(true);
        return t;
    });
    private final Handler main = new Handler(Looper.getMainLooper());

    private VelocityTracker tracker;
    private float downX, downY;
    private boolean sliding;         // 已经认定这一下是横滑
    private boolean rejected;        // 这一轮不是横滑(竖着划的/多指),别再判了
    private int width;

    /** 滑动期间的候选:上一张 / 下一张的 id 和解好的图。 */
    private List<String> ring;
    private int index = -1;
    private String prevId, nextId;
    private Bitmap prevBmp, nextBmp;
    /**
     * 那一侧解码回来了没有。**不能靠 bitmap 是不是 null 判断** —— 解不出来的图回来的也是 null,
     * 而这两种局面对挂起的翻页意义相反:一个该继续等,一个该放弃动画直接换。
     */
    private boolean prevDone, nextDone;
    /** 最后一次跟手的位移。邻张是异步解出来的,解好时手指可能已经不动了 —— 拿它补画一次。 */
    private float lastDx;

    /**
     * 松手时已经拍板要翻、但那张图还没解出来。图一到就补跑翻页动画(见 {@link #onDecoded})。
     *
     * **快扫本来就等不到图**:实测一次快扫全程 130~170ms,而解一张邻图要 60~200ms
     * (个人视频没有现成封面、得真去抽帧的话到 200ms,冷启动第一次更是上千毫秒)。
     * 所以"图解出来了"绝不能当成翻页的前提 —— 那样快扫十次有八次会白弹回来。
     * 翻不翻只看手势,图只决定**用哪种动画**。
     */
    private String pendingId;
    /** 挂起的那次翻页往哪边落定(±width)。负数=向左翻(切到下一张)。 */
    private float pendingTo;
    /**
     * 第几轮滑动。异步解码回来时拿它对一下 —— 上一轮的迟到回调不能写进这一轮的候选图,
     * 否则会把过期的邻张画上去(手指刚起又落,两轮只隔一百多毫秒,撞得上)。
     */
    private int gen;

    public WallpaperSwiper(View current, View video, ImageView next,
                           WallpaperStore store, Host host) {
        this.current = current;
        this.video = video;
        this.next = next;
        this.store = store;
        this.host = host;
    }

    /**
     * 这一轮别当横滑判了 —— 交给别人。
     *
     * 给**比自己更晚收到事件**的手势用:本类挂在 Activity.dispatchTouchEvent 上,
     * 比整棵 view 树先拿到事件,view 里的 {@code requestDisallowInterceptTouchEvent}
     * 对它是无效的。目前的调用方是画中画那根握把(横向拖,和切壁纸正面撞车,
     * 见 HomeActivity 的 onResizeStart)。
     *
     * 时序上够用:DOWN 先经过本类(把 rejected 清掉)才下发到 view,view 在自己的
     * DOWN 里调这里设回 true,后续 MOVE 一进来就被挡掉。
     */
    public void reject() {
        rejected = true;
        if (sliding) settle(0f);   // 已经滑出去一点了就归位,别停在半路
    }

    /**
     * 喂触摸事件。**返回 true 表示这一下被滑动吃了**,宿主据此把事件挡在别处之外
     * (别让长按外观弹层跟着触发)。
     */
    public boolean onTouch(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // 上一次快扫已经拍了板、还在等图 —— 手指又落下来了,不能再等:
                // 这一轮 begin() 要读"当前是哪张",不先把上次落实,读到的就是过期的那张,
                // 而且迟到的解码回调还会跟这一轮抢着画
                if (pendingId != null) {
                    String pick = pendingId;
                    pendingId = null;
                    commit(pick);
                }
                downX = ev.getRawX();
                downY = ev.getRawY();
                sliding = false;
                rejected = false;
                width = Math.max(1, current.getWidth());
                if (tracker != null) tracker.recycle();
                tracker = VelocityTracker.obtain();
                tracker.addMovement(ev);
                return false;

            case MotionEvent.ACTION_POINTER_DOWN:
                // 多指是别的手势(双指合拢/张开进出编辑态),让给它
                rejected = true;
                if (sliding) settle(0f);
                return false;

            case MotionEvent.ACTION_MOVE: {
                if (rejected) return false;
                if (tracker != null) tracker.addMovement(ev);
                float dx = ev.getRawX() - downX, dy = ev.getRawY() - downY;
                if (!sliding) {
                    int slop = ViewConfiguration.get(current.getContext()).getScaledTouchSlop();
                    if (Math.abs(dx) < slop) return false;
                    // 横向要明显多于纵向才算 —— 斜着划的多半是想拖卡片或者手抖
                    if (Math.abs(dx) < Math.abs(dy) * 1.4f) {
                        rejected = true;
                        return false;
                    }
                    if (!begin(dx)) {   // 只有一张壁纸,没得滑
                        rejected = true;
                        return false;
                    }
                    sliding = true;
                }
                drag(dx);
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean was = sliding;
                if (sliding) {
                    float vx = 0f;
                    if (tracker != null) {
                        // **抬手这一下也要喂进去**:快扫的位移有不小一段落在最后一个 MOVE 到
                        // 抬手之间,漏掉它算出来的速度会偏低 —— 甚至整个算成 0,
                        // 那就退化成只认距离(得拖过大半屏),快扫全白扫
                        tracker.addMovement(ev);
                        tracker.computeCurrentVelocity(1000);
                        vx = tracker.getXVelocity();
                    }
                    settle(ev.getActionMasked() == MotionEvent.ACTION_UP ? vx : 0f);
                }
                if (tracker != null) {
                    tracker.recycle();
                    tracker = null;
                }
                sliding = false;
                return was;
            }
            default:
                return sliding;
        }
    }

    /**
     * 开滑:把候选列表和左右两张图准备好。只有一张壁纸就返回 false。
     *
     * {@code dx} 是刚过判定门槛时的位移,**只用来决定先解哪张** —— 手指冲着哪边,
     * 哪张就先解。两张是同一个单线程队列串着解的,先解用不上的那张等于把等待翻倍。
     */
    private boolean begin(float dx) {
        String cur = store.current();
        // 交互壁纸不是划着换的:手势整个让给它(见 WallpaperStore.slideRing)。
        // **这一句要排在 slideRing() 前面** —— 那个方法要扫三个目录,而交互壁纸的
        // 交互本来就是拖动,每拖一次过阈值都扫一遍盘,会在主线程上卡出来。
        if (WallpaperStore.isWebId(cur)) return false;

        ring = store.slideRing();
        index = ring.indexOf(cur);
        if (ring.size() < 2 || index < 0) return false;

        prevId = ring.get((index - 1 + ring.size()) % ring.size());
        nextId = ring.get((index + 1) % ring.size());
        prevBmp = null;
        nextBmp = null;
        prevDone = false;
        nextDone = false;

        // 半分辨率解:跟手途中图是动的,看不出差别,内存只有全解的四分之一
        DisplayMetrics dm = current.getResources().getDisplayMetrics();
        final int w = dm.widthPixels / 2, h = dm.heightPixels / 2;
        final boolean nextFirst = dx < 0;
        final String first = nextFirst ? nextId : prevId;
        final String second = nextFirst ? prevId : nextId;
        final int myGen = ++gen;
        io.execute(() -> {
            // **一张一回,不等两张都解完**:等着用的就是先解那张,让它早几十毫秒到位
            final Bitmap fb = safeLoad(first, w, h);
            main.post(() -> onDecoded(myGen, nextFirst, fb));
            final Bitmap sb = safeLoad(second, w, h);
            main.post(() -> onDecoded(myGen, !nextFirst, sb));
        });
        return true;
    }

    /**
     * 预解好的邻图。**只在 {@code io} 那条单线程上读写**(LinkedHashMap 不是线程安全的,
     * 而且 accessOrder 的 get 也会改结构)。
     *
     * 为什么要有它:视频壁纸没有现成封面时解一张要抽帧 **350~450ms**(真车实测),
     * 而一次快扫全程才 130~170ms —— 等手指按下才开始解,拖出去旁边是空的、
     * 松手还要"原地冻住等"。所以切完一张就为下一次把左右两张备好。
     *
     * 只留 3 张:当前的左右两张,加上刚切走的那张(往回滑立刻用得上)。
     * 半屏 960×540 一张约 2MB。
     */
    private final java.util.LinkedHashMap<String, Bitmap> warm =
            new java.util.LinkedHashMap<String, Bitmap>(4, 0.75f, true) {
                @Override protected boolean removeEldestEntry(
                        java.util.Map.Entry<String, Bitmap> eldest) {
                    return size() > 3;
                }
            };

    /** 解不出来就当没有 —— 这条链子断了的话挂起的那次翻页就再也等不到回调了。 */
    private Bitmap safeLoad(String id, int w, int h) {
        Bitmap hit = warm.get(id);
        if (hit != null && !hit.isRecycled()) return hit;
        try {
            Bitmap b = store.load(id, w, h);
            if (b != null) warm.put(id, b);
            return b;
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /**
     * 提前把左右两张备好。**切完一张就为下一次准备** —— 连着滑好几张是常态。
     *
     * 整个扔后台:{@code slideRing()} 要扫三个目录(它自己的注释里就写着"在主线程上
     * 会卡出来"),解图更是几百毫秒,两样都不能碰主线程。
     *
     * 走的是和滑动同一条单线程队列:真滑起来时那边的解码排在这边后面,不会互相抢。
     */
    public void preload() {
        io.execute(() -> {
            String cur = store.current();
            if (cur.isEmpty() || WallpaperStore.isWebId(cur)) return;
            List<String> r = store.slideRing();
            int i = r.indexOf(cur);
            if (r.size() < 2 || i < 0) return;
            DisplayMetrics dm = current.getResources().getDisplayMetrics();
            int w = dm.widthPixels / 2, h = dm.heightPixels / 2;
            safeLoad(r.get((i + 1) % r.size()), w, h);
            safeLoad(r.get((i - 1 + r.size()) % r.size()), w, h);
        });
    }

    /**
     * 一张邻图解好了({@code bmp} 为 null 表示这张解不出来)。
     *
     * 两种局面:手指还在滑 → 补画一次(不补的话邻张要等下一个 MOVE 才出现,中间边上是空的);
     * 已经松手拍了板 → 等的就是这张,直接翻。
     */
    private void onDecoded(int g, boolean isNext, Bitmap bmp) {
        if (g != gen) return;      // 上一轮的迟到回调,这一轮不认
        if (isNext) {
            nextBmp = bmp;
            nextDone = true;
        } else {
            prevBmp = bmp;
            prevDone = true;
        }

        if (pendingId != null) {
            if ((pendingTo < 0) == isNext) flip(bmp);   // 等的正是这张
            return;
        }
        if (sliding) drag(lastDx);
    }

    /** 跟手:当前那张跟着走,邻张贴着它的边跟进来。 */
    private void drag(float dx) {
        lastDx = dx;
        current.setTranslationX(dx);
        if (video != null) video.setTranslationX(dx);
        Bitmap bmp = dx < 0 ? nextBmp : prevBmp;
        if (bmp == null) {
            next.setVisibility(View.GONE);   // 还没解出来,先只动当前这张
            return;
        }
        next.setImageBitmap(bmp);
        next.setVisibility(View.VISIBLE);
        // 贴在当前这张的左边或右边,整体跟着一起走
        next.setTranslationX(dx + (dx < 0 ? width : -width));
    }

    /**
     * 松手:够不够翻页。
     *
     * 距离和速度**任一达标就翻** —— 只看距离的话轻扫不认(手感钝),只看速度的话
     * 慢慢拖过大半屏反而不认(更奇怪)。
     */
    private void settle(float vx) {
        float dx = current.getTranslationX();
        boolean far = Math.abs(dx) > width * DIST_RATIO;
        boolean fast = Math.abs(vx) > FLING_VELOCITY && Math.signum(vx) == Math.signum(dx);
        String target = dx < 0 ? nextId : prevId;

        if ((far || fast) && target != null) {
            // **拍板只看手势**,不看图解出来没有(为什么见 pendingId 那段说明)
            pendingId = target;
            pendingTo = dx < 0 ? -width : width;
            flip(dx < 0 ? nextBmp : prevBmp);
            return;
        }
        // 不够,弹回去
        if (video != null) Motion.slideX(video, 0f);
        Motion.slideX(current, 0f);
        Motion.slideX(next, dx < 0 ? width : -width, () -> {
            next.setVisibility(View.GONE);
            next.setImageDrawable(null);
        });
    }

    /**
     * 把拍好板的那次翻页做掉。{@code bmp} 是新那张的图:
     *
     * <ul>
     *   <li>还没解出来(null 且回调没到) → **原地冻住等**,图一到 {@link #onDecoded} 再叫回来。
     *       冻的这几十毫秒画面停在手指松开的位置,比"弹回去装作没发生"诚实得多;</li>
     *   <li>解不出来(回调到了但是 null) → 没得演,直接换,至少切得动;</li>
     *   <li>有图 → 两张一起滑到位。</li>
     * </ul>
     */
    private void flip(Bitmap bmp) {
        final String picked = pendingId;
        if (picked == null) return;
        // 图还在路上:先什么都不做,视图停在原处(onDecoded 会带着图再来一次)
        if (bmp == null && !decoded(pendingTo < 0)) return;

        final float to = pendingTo;
        pendingId = null;
        if (bmp == null) {   // 这张就是解不出来,跳过过场动画
            commit(picked);
            return;
        }
        // 邻张贴在当前这张旁边起步 —— 挂起期间它可能压根没露过面(见 drag 里 bmp==null 那支)
        next.setImageBitmap(bmp);
        next.setVisibility(View.VISIBLE);
        next.setTranslationX(current.getTranslationX() + (to < 0 ? width : -width));
        Motion.slideX(next, 0f);
        if (video != null) Motion.slideX(video, to);
        // 这张图**现在**就递过去:宿主在 commit 里重铺壁纸时直接拿来垫底,
        // 省掉主线程上那次抽帧(见 Host.onWallpaperPoster)
        if (host != null) host.onWallpaperPoster(picked, bmp);
        Motion.slideX(current, to, () -> commit(picked));
    }

    /** 那一侧的解码回调到底来过没有 —— 用来区分"还没解出来"和"解出来是 null"。 */
    private boolean decoded(boolean isNext) {
        return isNext ? nextDone : prevDone;
    }

    /**
     * 落定。**顺序是有讲究的**,反了就闪一下上一张:
     *
     * current 这时候显示的还是**旧图**,只是被推到了屏幕外(translationX=±width)。
     * 要是先把它归零再通知宿主换内容,旧图会当场跳回屏幕中央,而 applyWallpaper
     * 解新图是主线程的几十毫秒 —— 那几十毫秒露的正是上一张。
     *
     * 所以:**先换内容(此时 current 还在屏幕外,换的是看不见的那张),再归位**。
     * 归零的一刻它已经是新图了。中间画面一直由 next 顶着(它已经滑到了屏幕中央、
     * 显示的就是新图),换完再隐藏 next 露出 current,两张同图叠着切,看不出接缝。
     */
    private void commit(String picked) {
        if (host != null) host.onWallpaperPicked(picked);
        current.setTranslationX(0f);
        if (video != null) video.setTranslationX(0f);
        next.setTranslationX(0f);
        next.setVisibility(View.GONE);
        next.setImageDrawable(null);
        preload();   // 位置变了,为下一次滑动备好新的左右两张
    }
}
