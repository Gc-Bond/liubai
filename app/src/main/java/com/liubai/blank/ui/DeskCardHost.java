package com.liubai.blank.ui;

import android.app.Activity;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import com.liubai.blank.data.CarCtrlStore;
import com.liubai.blank.data.DeskCardStore;
import com.liubai.blank.model.CarCtrlItem;
import com.liubai.blank.privileged.AirCon;
import com.liubai.blank.privileged.CarCtrl;
import com.liubai.blank.privileged.NetSwitch;
import com.liubai.blank.util.Launchers;
import com.liubai.blank.util.ViewTx;

import java.util.ArrayList;
import java.util.List;

/**
 * 桌面卡片宿主:主页层上的卡片(车控一项一张、时钟、游戏中心)的挂载、摆位、
 * 编辑态、候选区、位置持久化、车控状态回读,全收在这一处。
 *
 * 2026-08-19 从 {@code HomeActivity} 整块搬出(Stage 3),方法体原样。宿主引用统一换成
 * 构造收进来的 {@code act} / {@code canvas} / {@code stage};两个外部依赖
 * (空调全量读 refreshAc、竖滑收栏手势 barSwipe)用 Runnable 注入,不反向依赖 HomeActivity。
 */
public final class DeskCardHost implements DeskCard.Host, DeskCardPicker.Listener {

    private static final String TAG = "Blank/DeskCardHost";

    /**
     * 桌面卡片一行摆几个。**四个** —— 车控得伸手够得着,主驾这边是左半屏,
     * 一行四张(约 340dp)正好落在左半侧,右边整片留给壁纸。
     */
    private static final int COLS = 4;

    private final Activity act;
    private final ViewGroup canvas;   // 卡片挂在哪个容器上(现在的 homeLayer)
    private final ViewGroup stage;    // 候选区要挂的层(content root,见 ensurePicker)
    private final CarCtrlTile.Listener tileListener;
    /** 全量重挂后补一次空调全量读(原 setupCards 里的 refreshAc)。 */
    private final Runnable onRefreshAc;
    /** 拖卡开始时通知宿主(HomeActivity 拿去拦竖滑收栏手势)。 */
    private final Runnable onDragStart;

    /**
     * 车控:**一项一张小卡**,各自摆位(见 CarCtrlTile)。摆哪几项由 CarCtrlStore 定。
     * 整组的显隐归 DeskCardStore.CAR_CTRL 那一行管。
     */
    private final java.util.List<CarCtrlTile> ctrlTiles = new java.util.ArrayList<>();
    private CarCtrlStore carCtrlStore;
    /** 主页层上挂着的卡片。摆位和显隐都归 DeskCardStore。 */
    private final java.util.List<DeskCard> cards = new java.util.ArrayList<>();
    private DeskCardStore deskCards;
    /** 拖卡片时的落点虚框。 */
    private DropHintView dropHint;
    /** 桌面编辑态:双指合拢进、张开/点空白/返回键出。见 {@link #setEditing}。 */
    private boolean deskEditing;
    /** 编辑态右侧那条候选区("还能加什么"),第一次进编辑态才建。 */
    private DeskCardPicker picker;
    /** 正在处理一次落位。见 {@link #resolveDrop} 里那段"不许级联"。 */
    private boolean resolvingDrop;
    /**
     * 候选区重铺的 ViewTx 去重 token —— 连点几下只跑最后那一次。
     *
     * **一个实例一个,不能 static**:切模式会 recreate,时序是"新 Activity onCreate →
     * 旧 Activity onDestroy"。共用一个 token 的话,旧的那句 {@code ViewTx.cancel}
     * 会把新 Activity 刚排上的重铺一并撤掉。
     */
    private final Object tokenPicker = new Object();

    /** @param canvas       卡片挂在哪个容器上(现在的 homeLayer)
     *  @param stage        候选区要挂的层(传 activity 的 content root)
     *  @param tileListener 车控卡的点击回调,原样转交(见"结二")
     *  @param onRefreshAc  全量重挂后补一次空调全量读(原 setupCards 里的 refreshAc)
     *  @param onDragStart  拖卡开始时通知宿主(原 onCardDrag 里的 barSwipe.reject) */
    public DeskCardHost(Activity act, ViewGroup canvas, ViewGroup stage,
                        CarCtrlTile.Listener tileListener, Runnable onRefreshAc, Runnable onDragStart) {
        this.act = act;
        this.canvas = canvas;
        this.stage = stage;
        this.tileListener = tileListener;
        this.onRefreshAc = onRefreshAc;
        this.onDragStart = onDragStart;
        this.carCtrlStore = new CarCtrlStore(act);
        this.deskCards = new DeskCardStore(act);
    }

    public boolean editing() { return deskEditing; }

    /** barSwipe 的 barsHidden() 要用(见 HomeActivity 的接线)。 */
    public boolean hidden() { return deskHidden; }

    /** 现在挂在桌面上的卡(只读)。技巧页照它们量手指落点,见 DeskSnapshot。 */
    public java.util.List<DeskCard> cards() { return java.util.Collections.unmodifiableList(cards); }

    /** DebugGuard 状态描述用。 */
    public int cardCount() { return cards.size(); }

    /** DebugGuard 状态描述用。 */
    public boolean pickerOpen() { return picker != null && picker.isOpen(); }

    /** 按项找到它那张卡。 */
    public CarCtrlTile tileOf(CarCtrlItem item) {
        if (item == null) return null;
        for (CarCtrlTile t : ctrlTiles) {
            if (t.item() == item) return t;
        }
        return null;
    }

    /** 宿主销毁:候选区重铺排在 ViewTx 的静态 Handler 上,走的时候没跑完的得撤掉。 */
    public void onDestroy() {
        ViewTx.cancel(tokenPicker);
    }

    /** 卡片正被上滑收着(只剩壁纸)。**不落盘**,离开桌面就还原(见 HomeActivity.onPause)。 */
    private boolean deskHidden;

    /**
     * 上滑收起 / 下滑放回**桌面上那些卡片**,露出整片壁纸。
     *
     * **只收卡片,两条常驻栏不动**:Dock 和顶栏是功能条(车控、时间、车内温度),
     * 想看壁纸不等于不要它们 —— 而且 Dock 常驻是留白的立身之本。
     * 2026-08-18 一开始做成了"三样一起收",用户当场问回来:"为什么要隐藏 dock 和
     * 顶部状态栏?"。想永久去掉顶栏,外观面板 › Dock 页有正经开关
     * (见 {@link StatusBarHost#enabled})。
     *
     * 画中画层也不归这里管,它靠层指示器收起/展开(见 HomeActivity.setPipCollapsed)。
     *
     * 卡片走的是**淡出到 INVISIBLE**,不是 GONE:
     * <ul>
     *   <li>INVISIBLE 仍占位,{@code placeCards} / {@code spotTaken} 那几处按 GONE 判
     *       "这张卡不存在",用 GONE 会让别的卡趁机占掉它的格子,放回来就乱了;</li>
     *   <li>光把 alpha 调 0 不行 —— 透明的 view 照样吃触摸,看不见却点得动。</li>
     * </ul>
     */
    public void setHidden(boolean hidden) {
        if (deskHidden == hidden) return;
        deskHidden = hidden;
        Log.i(TAG, "桌面卡片 " + (hidden ? "收起(只剩壁纸)" : "放回"));
        for (final DeskCard c : cards) {
            // 没装配的项本来就不该露面(座椅档位 -1),别把它淡回来
            if (c.getVisibility() == View.GONE) continue;
            if (!hidden) c.setVisibility(View.VISIBLE);
            c.animate().cancel();
            c.animate().alpha(hidden ? 0f : 1f).setDuration(180L)
                    .withEndAction(() -> {
                        if (hidden) c.setVisibility(View.INVISIBLE);
                    }).start();
        }
        // 编辑态的落点格子跟着走:桌面都收起来了,还铺着一片虚框很怪
        if (dropHint != null && hidden) dropHint.setShown(false);
    }

    /**
     * 把主页层上的卡片**全部**挂一遍。
     *
     * 卡片不写在 XML 里:它们能自己摆位置(见 {@link DeskCard}),摆哪儿、显不显示都存在
     * {@link DeskCardStore} 里,只能按存档一张张挂。**新加一种卡就在这儿多 new 一个** ——
     * 摆位、吸附、位置持久化都是地基管的,新卡不用自己实现一遍。
     *
     * **只在"一次改一批"的时候用它**(开机、切模式、配置面板批量勾选完)。
     * 编辑态里删一张、加一张走 {@link #removeCard} / {@link #addCard} 的增量路径 ——
     * 全量重挂会把所有卡片换成新实例:状态归零(座椅按"没装配"藏起来)、编辑态和位置
     * 都得再贴一遍,而且整片闪一下。2026-08-17 就是这么翻的车。
     */
    public void setup() {
        // 重挂一遍(改了显隐会走这条):主页层上除了卡片没别的,清干净重来。
        // 引用也要一起清 —— 留着旧实例的话,状态回调会铺到一个已经摘掉的 view 上
        canvas.removeAllViews();
        // 落点虚框垫在最底下,卡片都浮在它上面
        dropHint = new DropHintView(act);
        canvas.addView(dropHint, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        cards.clear();
        ctrlTiles.clear();

        if (deskCards.visible(DeskCardStore.CAR_CTRL)) {
            // 一项一张卡:每张自己记住摆在哪儿(位置键是 cc_<项的 key>)
            for (CarCtrlItem it : carCtrlStore.load()) {
                CarCtrlTile t = new CarCtrlTile(act, it);
                t.setListener(tileListener);
                ctrlTiles.add(t);
                cards.add(t);
            }
        }
        if (deskCards.visible(DeskCardStore.CLOCK)) {
            cards.add(new ClockCard(act));
        }
        if (deskCards.visible(DeskCardStore.GAME)) {
            cards.add(new GameCard(act));
        }

        for (DeskCard c : cards) attachCard(c, canvas);

        // 车况读一次。续航那几项不要提权(normal 权限的 provider),胎压要 ——
        // 所以提权就绪后还得再读一遍,补上胎压那几格

        // **重挂之后必须把状态补回来**:卡片是新实例,座椅的档位初始是 -1(= 没装配、
        // 整张卡藏着),开关类初始是 0。开机那次不出问题是因为 onCreate 后面紧跟着
        // refreshAc();但从"删一张卡""改配置"这些路径重挂就没人补了 ——
        // 2026-08-17 实测:存储里 15 项,桌面上只剩 11 张,座椅四项集体消失。
        // 缓存里有全量读就先铺一遍(不闪),没有就靠下面这次现读 —— 光靠缓存不行:
        // 冷启动时它还是 null,而 apply(null) 什么都不做(见 CarCtrlTile.apply)
        for (CarCtrlTile t : ctrlTiles) t.apply(AirCon.last());
        onRefreshAc.run();
        refreshCtrlStates();

        // 位置存的是**比例**,得等主页层和卡片都量出来才换算得回 px ——
        // 所以挂到 post 上,不能在这儿直接铺(那时两边宽高都还是 0)
        placeCards(canvas);
    }

    /**
     * 把卡片摆到各自的位置上。
     *
     * **位置存的是比例**,要用容器和卡片的实际尺寸换算({@link DeskCard#applyPos}),
     * 所以尺寸没量出来之前铺不了。这里踩过两次:
     *
     * <ol>
     *   <li>先用 {@code canvas.post()} —— 那个 Runnable 在下一轮消息里跑,而**布局也是
     *       消息驱动的、顺序没保证**:实测跑在 layout 之前,容器高度还是 0,可移动范围
     *       算出来是 0,所有卡片一律贴左上角,存的位置像是丢了。</li>
     *   <li>改成监听 layout、量到高度就铺一次然后摘掉监听 —— 还是不稳:**第一次量到的
     *       不一定是最终尺寸**(车控卡的座椅项要等 AirCon 读回来才显示,卡片会再长高一次),
     *       按中间态尺寸算出来的位置是偏的。</li>
     * </ol>
     *
     * 现在**不摘监听,每次布局都按存档重铺一遍**。这是幂等的(读的是同一份比例),尺寸
     * 什么时候稳定下来,位置就什么时候跟着对。正被拖着的那张要跳过 —— 拖动只改
     * translation、不触发布局,但别的东西引起的布局会把用户手上那张抢回存档位置。
     */
    private void placeCards(ViewGroup canvas) {
        // 先摘旧的再挂:`setup` 有多个调用点,不摘的话每个都累积一个监听器,
        // 每次布局全量跑一遍(2026-08-19 修掉的泄漏,见施工单 Stage 3)
        canvas.getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);
        canvas.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
    }

    /**
     * placeCards 的布局监听本体。**内部逻辑一个字都不要改** —— 那三段注释是
     * 三次真机踩坑的结论(座椅卡尺寸晚到、fresh 用 subList 避免 CME、落位后立刻
     * persistPos)。
     *
     * 提成方法而不是字段内 lambda:监听器要引用构造器里赋值的 canvas,直接写在
     * 字段初始化器里会触发 javac 的"可能尚未初始化"检查,方法引用绕开它。
     */
    private final ViewTreeObserver.OnGlobalLayoutListener layoutListener = this::onLayoutCards;

    private void onLayoutCards() {
        if (canvas.getHeight() <= 0) return;   // 还没量出来,等下一轮
        // 没摆过的卡片**一行四个,从左上角起排**。
        //
        // 四个而不是铺满一行:车控要伸手够得着,主驾这边是左半屏 ——
        // 一行四张(约 340dp)正好落在左半侧,右边留给壁纸。
        // 全都用同一个默认位置的话,十来张小卡会精确地叠在左上角同一处,
        // 看着像只有一张、底下那些还点不着。
        //
        // 行列步长取的是 snapCell() 那套格子,和拖动时的落点、松手的吸附是同一份 ——
        // 所以默认摆出来的就已经在格位上,用户拖一张走再拖回来,还落回原处
        int[] cell = snapCell();
        // **所有卡垫到一格高**。高度原先是各卡自己内容撑的、谁都没管,靠新卡作者
        // "照抄车控卡规格" —— 抄一次漂一次(游戏卡矮 10dp、胎压卡矮十几 dp)。
        // 格子量的就是车控卡(见 snapCell),这里把它变成对所有卡的硬约束。
        // 值没变时 fitCell 自己会跳过,不会在布局回调里反复 requestLayout。
        for (DeskCard c : cards) c.fitCell(cell[1] - cardGap());
        // 先把有存档的铺回去,没存档的攒起来 —— 后者得等**别人都就位**才排得准
        List<DeskCard> fresh = new ArrayList<>();
        for (DeskCard c : cards) {
            if (c.getVisibility() == View.GONE) continue;   // 没读到数据的卡不占位
            if (c.dragging()) continue;                     // 用户手上那张别动
            if (deskCards.hasPos(c.cardId())) {
                c.applyPos(deskCards.posX(c.cardId()), deskCards.posY(c.cardId()));
            } else {
                fresh.add(c);
            }
        }
        /*
         * 没摆过的一张张往网格里落,**跳过已经有人的格子**(见 firstFreeCell)。
         *
         * 不能像早先那样"自己数 col/row 顺序排" —— 那样只在"所有卡都是新的"时候对。
         * 真实时序不是那样:座椅四项要等 AirCon 回读才 VISIBLE,那时别人已经各自
         * 有存档位置、直接 applyPos 走掉、压根不参与数格子,于是这四张从 (0,0) 起排、
         * 全撞在第一行上,再被 unstack 一张张挤到边上去(2026-08-18 平板 pm clear
         * 实测:副驾加热被挤出第 4 列、整片看着散的)。
         *
         * 落好就 persistPos 存下来:下一趟布局它就走 applyPos 那条路,不再参与排位。
         */
        for (int i = 0; i < fresh.size(); i++) {
            DeskCard c = fresh.get(i);
            // 传"后面还没排到的那截"给它避让。**用 subList 而不是边遍历边 remove** ——
            // 那样就是在 foreach 里改集合(ConcurrentModificationException,实测崩过)
            float[] spot = firstFreeCell(c, cell, fresh.subList(i + 1, fresh.size()));
            c.setTranslationX(spot[0]);
            c.setTranslationY(spot[1]);
            c.persistPos();
        }
        unstack();
        auditCellFit(cell);
    }

    /**
     * 「所有卡一样大」这条规矩,**自己报自己**。
     *
     * 这条规矩以前只写在注释里(「照抄车控卡的规格」),破了没人知道 —— 靠的是有人
     * 在真车上用肉眼看出某张卡矮了一截,前后破过至少两次(游戏卡矮 10dp、
     * 胎压卡矮十几 dp)。{@link DeskCard#fitCell} 管住了"比一格矮"这一半;
     * 另一半(比一格高、又不是整数格)压不了 —— 内容真有那么高,压了就裁字。
     * 那就让它出声:高度不落在整数格上直接打 W,logcat 里一眼看见,不用等人发现。
     *
     * 允许占好几格(画中画那种),所以判的是"是不是 1/2/3… 格",不是"是不是一格"。
     */
    private void auditCellFit(int[] cell) {
        int tol = Launchers.dp(act, 2);     // 2dp 以内算齐(字体行高凑不出整数)
        for (DeskCard c : cards) {
            if (c.getVisibility() == View.GONE) continue;
            audit1(c, "高", c.getHeight(), cell[1], tol);
            audit1(c, "宽", c.getWidth(), cell[0], tol);
        }
    }

    /** 一个方向上比一次。{@code step} 是含缝步长,一格实际大小要减掉那道缝。 */
    private void audit1(DeskCard c, String dir, int got, int step, int tol) {
        int one = step - cardGap();
        if (one <= 0 || got <= 0) return;
        int n = Math.max(0, Math.round((got - one) / (float) step));
        int expect = one + n * step;
        if (Math.abs(got - expect) <= tol) return;
        Log.w(TAG, "卡片没落在格子上:" + c.cardId() + " " + dir + " " + got + "px"
                + ",一格 " + one + "px、" + (n + 1) + " 格 " + expect + "px"
                + ",差 " + (got - expect) + "px");
    }

    /**
     * 错开叠在一起的卡片。
     *
     * **必须等上面那趟全铺完再做** —— 边铺边判的话,判到前面那张时后面的还没铺到位,
     * 看到的是过期位置,于是"错开"反而错到别人身上去(2026-08-17 实测:主驾通风被错开
     * 到天窗开那一格上)。
     *
     * 重叠是从哪来的:早先落位不做占位检测,两张卡都存了同一个位置(比如都在 0,0),
     * 上面那张把下面那张整个盖住 —— 看起来就是"少了一张"。
     * 错开之后新位置会存下去,所以这条是**一次性的**:存过就不再挪,不会来回抖。
     */
    private void unstack() {
        for (DeskCard c : cards) {
            if (c.getVisibility() == View.GONE || c.dragging()) continue;
            if (c.getWidth() <= 0) continue;
            if (cardAt(c, c.getTranslationX(), c.getTranslationY(),
                    c.getWidth(), c.getHeight()) == null) continue;
            float[] spot = freeSpotNear(c, c.getTranslationX(), c.getTranslationY());
            Log.i(TAG, "叠上了," + c.cardId() + " 从 (" + (int) c.getTranslationX() + ","
                    + (int) c.getTranslationY() + ") 错开到 ("
                    + (int) spot[0] + "," + (int) spot[1] + ")");
            c.dropAt(spot[0], spot[1]);
        }
    }

    // ---- DeskCard.Host:所有卡片共用 ----

    /** 哪张卡被摆到新位置了。存比例,换个分辨率的车机也落得回原处。 */
    @Override public void onCardMoved(String cardId, float xRatio, float yRatio) {
        deskCards.setPos(cardId, xRatio, yRatio);
    }

    /**
     * 长按卡片原地松手 = **进编辑态**。
     *
     * 原来这里弹的是一张勾选式的配置面板(CarCtrlEditPanel),编辑态做出来之后它就是
     * 同一件事的第二套交互了(而且是模态的、盖住桌面、要点"完成"),整个删掉。
     * 长按是 launcher 通用手势,留它当编辑态的入口比双指合拢那个隐藏手势好发现。
     */
    /**
     * 长按一张卡 = **这张卡自己的动作**。
     *
     * <p>2026-09-05 前这里是 {@code setEditing(true)},于是长按同时是"进编辑态"的入口
     * 和"搬卡片"的起手式,按住之后手指稍一动就在搬卡片。现在拆开:
     * 进编辑态只认双指捏(见《桌面手势全表》),长按留给卡片。
     *
     * <p>眼下只有 WLAN 卡认领了这个动作,别的卡长按不做事。
     */
    @Override public void onCardHold(String cardId) {
        String[] page = systemPageOf(cardId);
        if (page != null) openSystemPage(page[0], page[1]);
    }

    /**
     * 这张卡长按该拉哪个系统页:{@code {标准 action, 这台车上的组件}}。不是这三张就返回 null。
     *
     * <p><b>都是车机自带的弹窗,留白不自己做</b> —— 扫描、配对、输密码、加密方式,
     * 系统页全都有;自己重造一遍要先过提权身份那道坎(uid 2000 的 opPackageName
     * 对不上 AppOpsManager.checkPackage),还要自己处理 WPA3,换来的是同一件事。
     *
     * <p><b>为什么每条都得配一个写死的组件兜底</b>(2026-09-05 真车实测):
     * <ul>
     *   <li>WLAN:标准 action 直接能用;</li>
     *   <li>蓝牙:{@code ACTION_BLUETOOTH_SETTINGS} 起不来 ——
     *       {@code am start} 报 {@code unable to resolve Intent},那个 Activity 的
     *       intent-filter 多半没带 CATEGORY_DEFAULT。**注意 {@code cmd package
     *       resolve-activity} 查得到它,别被骗了**:它的匹配规则比 startActivity 松;</li>
     *   <li>热点:标准 action 压根不存在({@code TETHER_SETTINGS} /
     *       {@code WIFI_AP_SETTINGS} 都是 No activity found),第一个位置放的就是
     *       BYD 自己声明的那个非标准 action。</li>
     * </ul>
     * 所以顺序是「先隐式、失败再显式」:换 ROM 时隐式那条还有机会活,写死的那条至少
     * 在这台车上是准的。两条都倒了只记日志、不崩。
     */
    private String[] systemPageOf(String cardId) {
        if ((CarCtrlTile.ID_PREFIX + CarCtrlItem.WIFI.key).equals(cardId)) {
            return new String[]{android.provider.Settings.ACTION_WIFI_SETTINGS,
                    "com.byd.systemsettings/.wifi.WIFISettings"};
        }
        if ((CarCtrlTile.ID_PREFIX + CarCtrlItem.HOTSPOT.key).equals(cardId)) {
            return new String[]{"com.android.settings.WIFI_TETHER_SETTINGS",
                    "com.byd.systemsettings/.hotspot.PerHotspotActivity"};
        }
        if ((CarCtrlTile.ID_PREFIX + CarCtrlItem.BLUETOOTH.key).equals(cardId)) {
            return new String[]{android.provider.Settings.ACTION_BLUETOOTH_SETTINGS,
                    "com.byd.btsetting/.BluetoothManagerDialog"};
        }
        return null;
    }

    /** 先按 action 隐式拉,不行再按组件显式拉。两条都倒了只记日志。 */
    private void openSystemPage(String action, String component) {
        try {
            android.content.Intent i = new android.content.Intent(action);
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
            return;
        } catch (Throwable t) {
            Log.i(TAG, "隐式拉不起 " + action + ",改用组件 " + component);
        }
        try {
            android.content.Intent i = new android.content.Intent();
            i.setComponent(android.content.ComponentName.unflattenFromString(component));
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
        } catch (Throwable t) {
            Log.w(TAG, "系统页打不开:" + action + " / " + component, t);
        }
    }

    /**
     * 把动 view 树的活儿挪到下一帧。
     *
     * **在触摸事件分发的中途 addView / removeView,而且动的正是手指底下那棵子树** ——
     * 这是留白连着崩了好几次的同一个根因:系统随后那趟 `performTraversals` 会撞到
     * 子节点数组里的 null,崩在 `ViewGroup.dispatchCollectViewAttributes` 或
     * `resetSubtreeAccessibilityStateChanged`,栈里一帧自己的代码都没有。
     *
     * onClick 也算"事件分发中途"(`performClick` 是在 UP 里调的)。同样的处理见
     * {@code StatusBarEditor.apply} 和 {@link #refreshPickerLater}。
     */
    private void laterOnUi(Runnable r) {
        // 收口到 ViewTx:排到下一帧、同一 token 只跑最后一次(这里 token 传 null,
        // 保持原来的行为 —— 两个调用点都只 post 一次,不需要去重)
        ViewTx.post(stage, null, r);
    }

    /**
     * 编辑态里点了某张卡的 ×。
     *
     * 车控项和整种卡片存在**两份不同的存储**里:一项一张卡的车控走 {@link CarCtrlStore}
     * (删的是"这一项"),时钟/车况那种整种卡走 {@link DeskCardStore} 的显隐。
     * 卡片自己不知道该改哪份,所以这事归宿主(见 {@link DeskCard.Host#onCardRemove})。
     */
    @Override public void onCardRemove(String cardId) {
        if (cardId == null) return;
        if (cardId.startsWith(CarCtrlTile.ID_PREFIX)) {
            String key = cardId.substring(CarCtrlTile.ID_PREFIX.length());
            List<CarCtrlItem> keep = new ArrayList<>();
            for (CarCtrlItem it : carCtrlStore.load()) {
                if (!it.key.equals(key)) keep.add(it);
            }
            carCtrlStore.save(keep);
            carCtrlStore.markUserEdited();
        } else {
            deskCards.setVisible(cardId, false);
        }
        // 同 onCardHold:× 是这张卡的子 view,而 removeCard 要把整张卡从画布上摘掉 ——
        // 不能在点击回调里当场做
        laterOnUi(() -> removeCard(cardId));
    }

    /**
     * 摘掉一张卡:**只动它自己**。
     *
     * 别的卡片留在原地、状态和位置都不碰 —— 删一张卡本来就没有理由让整片桌面重来。
     */
    private void removeCard(String cardId) {
        DeskCard gone = null;
        for (DeskCard c : cards) {
            if (cardId.equals(c.cardId())) { gone = c; break; }
        }
        if (gone == null) return;
        canvas.removeView(gone);
        cards.remove(gone);
        if (gone instanceof CarCtrlTile) ctrlTiles.remove((CarCtrlTile) gone);
        refreshPickerLater();
    }

    /**
     * 让候选区重铺一遍 —— **排到下一帧,不在当前这趟事件里做**。
     *
     * `picker.refresh` 会 `removeAllViews()` 把整列候选拆了重建,而叫它的两处
     * (× 掉一张卡、从候选区拖一张出来)都还在**触摸事件分发的中途**:手指正按着的那条
     * 被连根拆掉,之后系统那趟 traversal 就崩在 `ViewGroup.dispatchCollectViewAttributes`
     * (`NullPointerException: mViewFlags`,子节点数组里出现 null)。
     *
     * 2026-08-18 在顶栏编排那条预览条上稳定复现了同一个崩溃(见 StatusBarEditor.apply),
     * 用户更早报的"一拖动卡片就闪退"是同一个根因的这一处。
     */
    private void refreshPickerLater() {
        if (picker == null) return;
        // ViewTx 按 tokenPicker 去重:连点几下(× 掉一张、再从候选区拖一张)只重铺一次,
        // 不再每个都排一遍
        ViewTx.post(picker, tokenPicker, () -> {
            if (picker != null && picker.isOpen()) {
                picker.refresh(carCtrlStore.load(), deskCards);
            }
        });
    }

    /**
     * 加一张卡:**只挂它自己**,并把它该有的状态立刻补上。
     *
     * 位置不用管 —— 新卡没存过位置,{@code placeCards} 那个布局监听会把它排到
     * 左上起第一个空位(一行四个),和第一次开一种卡走的是同一条路。
     */
    private void addCard(DeskCard c) {
        if (c == null) return;
        cards.add(c);
        if (c instanceof CarCtrlTile) {
            CarCtrlTile t = (CarCtrlTile) c;
            t.setListener(tileListener);
            ctrlTiles.add(t);
            t.apply(AirCon.last());   // 缓存里有全量读就立刻铺(座椅项靠它才露脸)
        }
        attachCard(c, canvas);
        c.setEditing(deskEditing);    // 加进来的时候还在编辑态,它也得挂上 ×
        refreshCtrlStates();          // 开关/数值档位现读一次,别停在"关"
        refreshPickerLater();
    }

    // ---- 候选区拖放:造卡 / 找卡 / 屏幕坐标换算 / 存储的写与撤 ----

    /** 按候选项造一张卡。车控项给 item,整种卡给 kindId(车控那一整种不走拖放,返回 null)。 */
    private DeskCard newCardFor(CarCtrlItem item, String kindId) {
        if (item != null) return new CarCtrlTile(act, item);
        if (DeskCardStore.CLOCK.equals(kindId)) return new ClockCard(act);
        if (DeskCardStore.GAME.equals(kindId)) return new GameCard(act);
        return null;
    }

    private DeskCard cardById(String cardId) {
        if (cardId == null) return null;
        for (DeskCard c : cards) {
            if (cardId.equals(c.cardId())) return c;
        }
        return null;
    }

    /** 拖出来就先落存储:中途松手反悔了再撤(见 {@link #undoPicked})。 */
    private void writePicked(CarCtrlItem item, String kindId) {
        if (item != null) {
            List<CarCtrlItem> items = new ArrayList<>(carCtrlStore.load());
            if (!items.contains(item)) {
                items.add(item);
                carCtrlStore.save(items);
                carCtrlStore.markUserEdited();
            }
        } else if (kindId != null) {
            deskCards.setVisible(kindId, true);
        }
    }

    private void undoPicked(String cardId) {
        if (cardId.startsWith(CarCtrlTile.ID_PREFIX)) {
            String key = cardId.substring(CarCtrlTile.ID_PREFIX.length());
            List<CarCtrlItem> keep = new ArrayList<>();
            for (CarCtrlItem it : carCtrlStore.load()) {
                if (!it.key.equals(key)) keep.add(it);
            }
            carCtrlStore.save(keep);
        } else {
            deskCards.setVisible(cardId, false);
        }
    }

    /**
     * 屏幕坐标 → 这张卡在主页层里该有的 translation(手指落在卡片正中)。
     *
     * 要减掉三样:主页层在屏幕上的位置(顶栏让位那条也算在里面)、卡片自己的 layout 位置
     * (挂上去时那 6dp 边距)、以及卡片一半的宽高(手指指的是中心不是左上角)。
     */
    private float[] toCanvasTranslation(DeskCard c, float rawX, float rawY) {
        int[] loc = new int[2];
        canvas.getLocationOnScreen(loc);
        float tx = rawX - loc[0] - c.getLeft() - c.getWidth() / 2f;
        float ty = rawY - loc[1] - c.getTop() - c.getHeight() / 2f;
        Log.i(TAG, "落位换算 raw=(" + (int) rawX + "," + (int) rawY + ") 层位置=("
                + loc[0] + "," + loc[1] + ") 卡LT=(" + c.getLeft() + "," + c.getTop()
                + ") 卡WH=" + c.getWidth() + "x" + c.getHeight()
                + " → tx,ty=(" + (int) tx + "," + (int) ty + ")");
        return new float[]{tx, ty};
    }

    /**
     * 松手落位:**拖到哪格就落哪格**,那格有人就跟他换。
     *
     * 换的方向:原住户挪到我刚离开的那格({@code fromTx/fromTy})。我是从候选区新拖进来的
     * (那两个是 NaN、没有老位置)时,把原住户挪到最近的空格。
     *
     * 桌面内部拖动和候选区拖入都从这儿过(见 {@link DeskCard.Host#resolveDrop})。
     */
    @Override public float[] resolveDrop(String cardId, float tx, float ty,
                                         float fromTx, float fromTy, int w, int h) {
        DeskCard self = cardById(cardId);
        if (self == null) return null;
        /*
         * 交换**不许级联**:让位的那张走 dropAt,而 dropAt 里又会回到这儿(见
         * DeskCard.snapAndSave) —— 而这一刻我自己的 translation 还没更新(setTranslation
         * 排在 resolveDrop 之后),它看到我还占着老位置,于是又要跟我换,来回无穷
         * (2026-08-17 平板实测:StackOverflowError,一整片卡片交换时必崩)。
         *
         * 换位是两方之间的一件事,原住户拿到的位置就是最终位置,不该再引发第三方挪动。
         */
        if (resolvingDrop) return new float[]{tx, ty};
        DeskCard occupant = cardAt(self, tx, ty, w, h);
        if (occupant == null) return new float[]{tx, ty};

        resolvingDrop = true;
        try {
            return swap(self, cardId, occupant, tx, ty, fromTx, fromTy);
        } finally {
            resolvingDrop = false;
        }
    }

    /** 把原住户挪走,自己占那一格。返回自己的最终位置。 */
    private float[] swap(DeskCard self, String cardId, DeskCard occupant,
                         float tx, float ty, float fromTx, float fromTy) {
        if (Float.isNaN(fromTx) || Float.isNaN(fromTy)) {
            // 新来的没有老位置可让 —— 把原住户挪到最近的空格
            float[] spot = freeSpotNear(occupant, occupant.getTranslationX(),
                    occupant.getTranslationY());
            Log.i(TAG, "落 " + cardId + " 到 (" + (int) tx + "," + (int) ty + "),原住户 "
                    + occupant.cardId() + " 挪到 (" + (int) spot[0] + "," + (int) spot[1] + ")");
            occupant.dropAt(spot[0], spot[1]);
        } else {
            Log.i(TAG, "换位 " + cardId + " → (" + (int) tx + "," + (int) ty + "),"
                    + occupant.cardId() + " → (" + (int) fromTx + "," + (int) fromTy + ")");
            occupant.dropAt(fromTx, fromTy);
        }
        return new float[]{tx, ty};
    }

    /** 谁占着这块地方(自己不算)。没人返回 null。 */
    private DeskCard cardAt(DeskCard self, float tx, float ty, int w, int h) {
        float x = self.getLeft() + tx, y = self.getTop() + ty;
        for (DeskCard c : cards) {
            if (c == self || c.getVisibility() == View.GONE || c.getWidth() <= 0) continue;
            float cx = c.getLeft() + c.getTranslationX(), cy = c.getTop() + c.getTranslationY();
            boolean sep = x + w <= cx || cx + c.getWidth() <= x
                    || y + h <= cy || cy + c.getHeight() <= y;
            if (!sep) return c;
        }
        return null;
    }

    /**
     * 找一个**不和别人重叠**的格位:先看手指指的那格,占了就按格子一圈圈往外挪。
     *
     * 没有这道检查的话,两张卡会落在同一格上叠成一坨(2026-08-17 实测:拖出来的卡
     * 和时钟叠在左上角)。吸附的格子尺寸取 {@link #snapCell},和拖动时的落点提示同一份。
     */
    private float[] freeSpotNear(DeskCard self, float tx, float ty) {
        int[] cell = snapCell();
        if (cell[0] <= 0 || cell[1] <= 0) return new float[]{tx, ty};
        // 手指那格(吸附后)
        float baseX = cell[2] + Math.round((tx - cell[2]) / (float) cell[0]) * (float) cell[0];
        float baseY = cell[3] + Math.round((ty - cell[3]) / (float) cell[1]) * (float) cell[1];
        int w = self.getWidth() > 0 ? self.getWidth() : cell[0];
        int h = self.getHeight() > 0 ? self.getHeight() : cell[1];
        // ring=0 就是手指那格;占了就一圈圈往外找,7 圈够覆盖一屏
        for (int ring = 0; ring < 8; ring++) {
            for (int dy = -ring; dy <= ring; dy++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    if (ring > 0 && Math.abs(dx) != ring && Math.abs(dy) != ring) continue;
                    float x = baseX + dx * cell[0], y = baseY + dy * cell[1];
                    if (x < 0 || y < 0) continue;
                    if (!spotTaken(self, x, y, w, h)) return new float[]{x, y};
                }
            }
        }
        return new float[]{baseX, baseY};
    }

    /**
     * 这块地方被别的卡占着没有。
     *
     * 传进来的是 **translation**,而别人的位置要用 layout + translation 才是屏幕上的实际位置 ——
     * 两边都换算到同一个坐标系再比,不然差一个 layout 偏移(那 6dp 边距)。
     */
    private boolean spotTaken(DeskCard self, float tx, float ty, int w, int h) {
        return spotTaken(self, tx, ty, w, h, null);
    }

    /**
     * 同上,但 {@code ignore} 里的卡不参与判定。
     *
     * 给默认排位用(见 {@link #firstFreeCell}):那批卡还没排到,translation 全是 0、
     * 一起叠在左上角 —— 算它们的话第一格永远"有人",一张也落不下去。
     */
    private boolean spotTaken(DeskCard self, float tx, float ty, int w, int h,
                              List<DeskCard> ignore) {
        float x = self.getLeft() + tx, y = self.getTop() + ty;
        for (DeskCard c : cards) {
            if (c == self || c.getVisibility() == View.GONE) continue;
            if (c.getWidth() <= 0) continue;   // 还没量出来的不参与判断
            if (ignore != null && ignore.contains(c)) continue;
            float cx = c.getLeft() + c.getTranslationX(), cy = c.getTop() + c.getTranslationY();
            boolean sep = x + w <= cx || cx + c.getWidth() <= x
                    || y + h <= cy || cy + c.getHeight() <= y;
            if (!sep) return true;
        }
        return false;
    }

    /**
     * 默认排位:从左上角起、一行 {@link #COLS} 个,扫**第一个没人的格子**。
     *
     * 一行四个而不是铺满一行:车控要伸手够得着,主驾这边是左半屏 —— 一行四张
     * (约 340dp)正好落在左半侧,右边留给壁纸。格子尺寸取 {@link #snapCell} 那一份,
     * 和拖动时的落点、松手的吸附同源,所以默认摆出来的就已经在格位上。
     *
     * {@code pending} 是这一趟**还没排到**的卡,不参与占位判定(理由见 spotTaken 的重载);
     * 已经排好的那些照常算,所以后面的卡会绕开前面的。
     */
    private float[] firstFreeCell(DeskCard self, int[] cell, List<DeskCard> pending) {
        int w = self.getWidth() > 0 ? self.getWidth() : cell[0];
        int h = self.getHeight() > 0 ? self.getHeight() : cell[1];
        // 比一格宽的卡(时钟、车况)要连着占几列,别横跨到行外
        int span = Math.max(1, (int) Math.ceil(w / (float) cell[0]));
        for (int row = 0; row < 16; row++) {
            for (int col = 0; col + span <= COLS; col++) {
                float x = cell[2] + col * (float) cell[0];
                float y = cell[3] + row * (float) cell[1];
                if (!spotTaken(self, x, y, w, h, pending)) return new float[]{x, y};
            }
        }
        return new float[]{cell[2], cell[3]};   // 一屏都满了,落回左上角(unstack 会兜)
    }

    /** 把一张卡挂到主页层上。位置交给 {@code placeCards} 的布局监听铺。 */
    private void attachCard(DeskCard c, ViewGroup canvas) {
        c.setHost(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.START | Gravity.TOP;
        // 没摆过的卡片落在左上角,留一点边距别贴着屏幕角
        lp.leftMargin = Launchers.dp(act, 6);
        lp.topMargin = Launchers.dp(act, 6);
        canvas.addView(c, lp);
    }

    // ---- 桌面编辑态(双指合拢进 / 张开出) ----

    /**
     * 进出编辑态。规矩照 Dock 那条({@code DockView.setEditing}):按下即拖、点按让位、
     * 每张挂 ×、落点格子常驻。
     */
    public void setEditing(boolean on) {
        if (deskEditing == on) return;
        deskEditing = on;
        Log.i(TAG, "桌面编辑态 " + (on ? "进" : "出"));
        applyDeskEditing();
    }

    /** 把编辑态贴到当前这批卡片上。重挂卡片之后也要调一次(新实例不带状态)。 */
    private void applyDeskEditing() {
        for (DeskCard c : cards) c.setEditing(deskEditing);
        if (dropHint != null) {
            if (deskEditing) {
                int[] cell = snapCell();
                dropHint.setCell(cell[0], cell[1], cell[2], cell[3]);
            }
            dropHint.setShown(deskEditing);
        }
        if (deskEditing) {
            ensurePicker();
            picker.open(carCtrlStore.load(), deskCards);
        } else if (picker != null) {
            picker.close();
        }
    }

    /**
     * 候选区。挂在**内容根**上而不是主页层里:主页层是卡片的画布(卡片按它的尺寸算
     * 可移动范围,见 {@link DeskCard#applyPos}),往里加一条竖栏会把那个范围算歪。
     */
    private void ensurePicker() {
        if (picker != null) return;
        DeskCardPicker p = new DeskCardPicker(act);
        p.setListener(this);
        ((ViewGroup) stage).addView(p, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        picker = p;
    }

    /** 拖起来了/松手了:把落点虚框显出来/收回去。 */
    @Override public void onCardDrag(boolean dragging) {
        // 非编辑态也能拖(长按之后手指走),那一轮别再当"竖滑收栏"判 —— 竖着挪一张卡
        // 很容易走过那 96dp。编辑态整个不喂事件,这里管的是长按拖那条路
        if (dragging) onDragStart.run();
        if (dropHint == null) return;
        if (dragging) {
            int[] cell = snapCell();
            dropHint.setCell(cell[0], cell[1], cell[2], cell[3]);
        }
        // 编辑态里格子**常驻** —— 松手不收,进来就看得见能往哪儿放
        dropHint.setShown(dragging || deskEditing);
    }

    /** 拖动中的落点:虚框跟着走,松手就落这儿。 */
    @Override public void onCardDragSpot(float x, float y, float w, float h) {
        if (dropHint != null) dropHint.setSpot(x, y, w, h);
    }

    /**
     * 对齐格子:**取一张车控卡片的实测尺寸**。
     *
     * 桌面上最小的一种卡就是它,拿它当格子,大一号的卡正好占整数格、边缘还是对齐的。
     * 原点取卡片挂上去时那 6dp 边距(见 setup)—— 格子从第一张卡的落点起算,
     * 不是从屏幕角起算,否则线和卡永远差那 6dp。
     *
     * 车控卡一张都没挂(用户关掉了)时退回一个正方形格子,别让吸附失灵。
     */
    /** 格与格之间那道缝。**snapCell / fitCell / snapWidthToCells 都用这一个数**,分开写迟早对不上。 */
    @Override public int cardGap() { return Launchers.dp(act, 12); }

    @Override public int[] snapCell() {
        // 原点是 0:格子长在 **translation 坐标**上,而 translation 的 0 就是卡片
        // 挂上去的位置(setup 里那 6dp 边距)。这儿再加一次 6dp 就重了
        int gap = cardGap();                    // 格与格之间留一道,卡片才不挨成一片
        for (CarCtrlTile t : ctrlTiles) {
            if (t.getWidth() > 0 && t.getHeight() > 0) {
                return new int[]{t.getWidth() + gap, t.getHeight() + gap, 0, 0};
            }
        }
        int f = Launchers.dp(act, 88) + gap;
        return new int[]{f, f, 0, 0};
    }

    // ---- DeskCardPicker.Listener:候选区的回调 ----

    @Override public void onPickCtrl(CarCtrlItem item) {
        List<CarCtrlItem> items = new ArrayList<>(carCtrlStore.load());
        if (items.contains(item)) return;
        items.add(item);
        carCtrlStore.save(items);
        carCtrlStore.markUserEdited();
        addCard(new CarCtrlTile(act, item));
    }

    @Override public void onPickKind(String cardId) {
        deskCards.setVisible(cardId, true);
        if (DeskCardStore.CLOCK.equals(cardId)) {
            addCard(new ClockCard(act));
        } else {
            // 车控那一整种:要造十几张卡(每项一张),这一种全量重挂才划算
            setup();
            applyDeskEditing();
        }
    }

    @Override public String onCandidateDragStart(CarCtrlItem item, String kindId) {
        DeskCard c = newCardFor(item, kindId);
        if (c == null) return null;
        writePicked(item, kindId);
        addCard(c);
        // **候选区滑走让位**:它占着右侧,不让开的话卡片拖到那边就被它盖住,
        // 而且右侧那几格永远放不了东西
        if (picker != null) picker.close();
        // 立刻进"跟手"的样子:抬起来、格子亮着,和手指拖桌面上的卡一模一样
        onCardDrag(true);
        return c.cardId();
    }

    @Override public void onCandidateDragMove(String cardId, float rawX, float rawY) {
        DeskCard c = cardById(cardId);
        if (c == null) return;
        float[] p = toCanvasTranslation(c, rawX, rawY);
        c.followFinger(p[0], p[1]);
        onCardDragSpot(c.getLeft() + p[0], c.getTop() + p[1], c.getWidth(), c.getHeight());
    }

    @Override public void onCandidateDrop(String cardId, float rawX, float rawY) {
        DeskCard c = cardById(cardId);
        onCardDrag(false);
        if (c == null) return;
        float[] p = toCanvasTranslation(c, rawX, rawY);
        // 换位/落位在 dropAt 里统一做(它走 snapAndSave → resolveDrop),这儿不重复
        c.dropAt(p[0], p[1]);
        // 候选区滑回来,并去掉刚摆上的那一条
        if (picker != null) picker.open(carCtrlStore.load(), deskCards);
    }

    @Override public void onCandidateDropCancelled(String cardId) {
        // 这一次拖放作废了。**卡片留在手指最后到过的地方就位**(跟手那一路已经
        // 铺好了),只把它落到格位上 —— 不能拿 CANCEL 的坐标去换算,那是 (0,0),
        // 卡片会被扔到左上角(见 DeskCardPicker 里那段说明)
        DeskCard c = cardById(cardId);
        onCardDrag(false);
        if (c == null) return;
        c.dropAt(c.getTranslationX(), c.getTranslationY());
        if (picker != null) picker.open(carCtrlStore.load(), deskCards);
    }

    // ---- HVAC 侧车控状态(遍历 ctrlTiles 那部分,原 HomeActivity 的) ----

    /**
     * 把开关类和数值档位的当前状态读回来。一项一次 native 读,项不多,扔给 CarCtrl 的
     * 单线程队列排着走。读不到就保持灰的 —— 不猜。
     *
     * 座椅不在这儿(它跟着 {@code AirCon} 的全量读走),动作类没有状态可读。
     */
    public void refreshCtrlStates() {
        // 系统开关(WLAN/热点)先铺:它们**不依赖车控通道**,读的是 Android 自己的状态。
        // 放在 CarCtrl.available() 那道门后面的话,没提权的车上这两张卡会永远灰着
        for (CarCtrlTile t : ctrlTiles) {
            if (t.item().kind != CarCtrlItem.Kind.SYSTEM) continue;
            boolean on = netOn(t.item());
            t.setLevel(on ? 1 : 0);
            // WLAN 开着就把连的是哪个网读回来显示在卡上(提权 dumpsys,异步回主线程);
            // 关着 / 是热点卡都不读。读不到 currentSsid 回 null,卡片自己显示回"WLAN"
            if (t.item() == CarCtrlItem.WIFI) {
                if (on) NetSwitch.currentSsid(act, t::setNetName);
                else t.setNetName(null);
            } else if (t.item() == CarCtrlItem.BLUETOOTH) {
                // 蓝牙同理:开着就把连的是哪台设备读回来顶在标题位
                if (on) NetSwitch.connectedBtName(act, t::setNetName);
                else t.setNetName(null);
            }
        }
        if (!CarCtrl.available()) return;
        for (CarCtrlTile t : ctrlTiles) {
            final CarCtrlTile tile = t;
            CarCtrlItem it = t.item();
            if (it.kind == CarCtrlItem.Kind.LEVEL) {
                // SOC 保电要读两个字段(开关 + 目标值),归 CarCtrl 拼
                CarCtrl.readSocSave((ok, v) -> {
                    if (ok) tile.setLevel(v);
                });
                continue;
            }
            if (it.kind != CarCtrlItem.Kind.TOGGLE) continue;
            CarCtrl.read(it.device(), it.readField(),
                    (ok, v) -> tile.setLevel(ok && v == it.onValue() ? 1 : 0));
        }
    }

    /**
     * WLAN 连上/断开、蓝牙开关时把卡片跟着刷新。
     *
     * <p><b>为什么非要监听</b>:卡片上的状态原来只在几个时机读 —— 桌面 onResume、
     * 提权就绪、重挂卡片。而用户是在**系统弹窗里**连的 WiFi(长按 WLAN 卡拉起来的那个),
     * 那会儿桌面在弹窗底下,不走 onResume,于是连上了名字也不变,得关掉弹窗才更新。
     *
     * <p>只挂系统广播、不轮询:这几条状态变化都是有事件的,轮询纯属白烧电。
     */
    private final android.content.BroadcastReceiver netWatch = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context c, android.content.Intent i) {
            refreshCtrlStates();
        }
    };

    private boolean netWatching;

    /** 桌面可见期间才听。见 {@link #netWatch}。 */
    public void startNetWatch() {
        if (netWatching) return;
        android.content.IntentFilter f = new android.content.IntentFilter();
        // 连上/断开某个网络(SSID 变化走这条),以及 WLAN 开关本身
        f.addAction(android.net.wifi.WifiManager.NETWORK_STATE_CHANGED_ACTION);
        f.addAction(android.net.wifi.WifiManager.WIFI_STATE_CHANGED_ACTION);
        f.addAction(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED);
        act.registerReceiver(netWatch, f);
        netWatching = true;
    }

    public void stopNetWatch() {
        if (!netWatching) return;
        netWatching = false;
        try {
            act.unregisterReceiver(netWatch);
        } catch (Throwable ignored) {
            // 没注册上过就注销会抛,吞掉 —— 这条路上没有值得中断的错误
        }
    }

    /**
     * 系统开关此刻开着没有。
     *
     * 两个读都是一次 binder(公开 API / 反射各一),量级和顶栏那颗 WiFi 图标的
     * {@code StatusBarView.wifiOn()} 一样,主线程直接调就行 —— 不像车控那边
     * 每一项都要过一趟 native,那才需要 {@link CarCtrl} 的单线程队列。
     */
    private boolean netOn(CarCtrlItem it) {
        if (it == CarCtrlItem.HOTSPOT) return NetSwitch.hotspotOn(act);
        if (it == CarCtrlItem.BLUETOOTH) return NetSwitch.bluetoothOn(act);
        return NetSwitch.wifiOn(act);
    }

    /** 发不出去时把卡片弹回真实状态。 */
    public void revertCarCtrl() {
        for (CarCtrlTile t : ctrlTiles) t.apply(AirCon.last());
        refreshCtrlStates();
    }

    /** 空调状态铺到车控卡上。轻量读会被卡片自己挡掉,这里无条件转发。 */
    public void applyAcState(AirCon.State s) {
        // 车控卡片自己也认这个标记(轻量读会被它挡掉),这里无条件转发就行。
        // 面板开着的时候也要转:两处显示的是同一个档位,不同步就是各说各话
        for (CarCtrlTile t : ctrlTiles) t.apply(s);
    }
}
