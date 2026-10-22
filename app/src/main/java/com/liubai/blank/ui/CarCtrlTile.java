package com.liubai.blank.ui;

import android.content.Context;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.liubai.blank.model.CarCtrlItem;
import com.liubai.blank.privileged.AirCon;
import com.liubai.blank.mode.ModeManager;
import com.liubai.blank.util.Launchers;

/**
 * 一项车控 = 桌面上一张能自己摆位置的小卡。
 *
 * <h3>为什么一项一张卡,而不是一条横卡装七项</h3>
 *
 * 横条那种是迪友的做法(反编译 3.0.1,`desktop_card_car_control_layout.xml`:车 logo +
 * 一排 36dp 的按钮),它整条只能作为一个整体摆 —— 因为它那批卡片本来就没有自由坐标,
 * 是按顺序落进固定槽位的。
 *
 * 留白的卡片是**自由摆位**的(见 {@link DeskCard}),那就没有理由把七项焊死在一条里:
 * 主驾通风摆左上、车窗全关摆右下,各摆各的。摆位、吸附、位置持久化都是地基给的,
 * 一项一张卡不比一条横卡多写什么。
 *
 * 位置按 {@code cc_<项的 key>} 分键存,所以**每一项记住自己在哪儿**。
 *
 * <h3>四类项的显示不一样</h3>
 * <ul>
 *   <li>座椅:两根档位条,亮几根是几档;</li>
 *   <li>开关:亮 = 开,灰 = 关;</li>
 *   <li>数值档位(SOC 保电):开着时**标题位置**写百分比(关着写回名字)。
 *       不为它多加一行 —— 那会让这张卡比同排的高一截、标题错位;</li>
 *   <li>动作(车窗全开这种):**没有状态可显示**,常灰,点一下闪一下表示发出去了。
 *       不能画成"开着",那是在骗人 —— 车窗现在什么位置这张卡并不知道。</li>
 * </ul>
 */
public class CarCtrlTile extends DeskCard {

    public interface Listener {
        /** 点了。提权检查、档位拦截和下发都归宿主。 */
        void onCarCtrlTap(CarCtrlItem item);
    }

    /** 位置存储键的前缀。加前缀是为了和别的卡片(clock / car_info)分开。 */
    public static final String ID_PREFIX = "cc_";

    // **这几个数是"桌面上一格"的定义**,别的卡照着它长(见 GameCard)——
    // 所以是包内可见的,不是 private。改这里等于改整屏的格子。
    static final int W_DP = 72, ICON_DP = 38;
    /** 档位条本体高 + 它上面那道间距。没有条的卡要把这段**空着**,见构造器。 */
    static final int BARS_DP = 4, BARS_GAP_DP = 6;
    static final int LABEL_GAP_DP = 4;
    /**
     * 标题位字号。**"连的是谁"单独小一档** —— 项名都是两三个字("WLAN"、"主驾加热"),
     * 12sp 在 72dp 的卡里正好;WiFi 名和蓝牙设备名是别人起的,十几个字符是常态,
     * 同样字号下十有八九被截成"XXX…"。降到 10sp 能多塞下三四个字符。
     *
     * <p>只动字号不动别的:字小了卡片不会变高(见《车控卡片不许变高》那条),
     * 截尾兜底也照旧留着 —— 再长的名字总有截的时候。
     */
    private static final float LABEL_SP = 12f, NET_NAME_SP = 10f;

    private final CarCtrlItem item;
    private final ImageView icon;
    private final StepBars bars;
    private final TextView label;
    private final LinearLayout col;
    private Listener listener;

    /**
     * 座椅是档位 0/1/2;开关是 0/1;**数值档位直接存那个数**(SOC 保电 = 0/25/50/70,
     * 0 = 关);动作恒 0(它没有状态)。-1 = 没装配。
     */
    private int level;

    /**
     * WLAN / 蓝牙卡:连上时标题位显示"连的是谁"(WiFi 名 / 蓝牙设备名)。
     * null = 显示回项名("WLAN" / "蓝牙")。
     */
    private String netName;

    public CarCtrlTile(Context ctx, CarCtrlItem item) {
        super(ctx, ID_PREFIX + item.key);
        this.item = item;
        // 座椅要等 AirCon 读回来才知道装没装,先按"没装"藏着;其余项直接就位
        this.level = item.kind == CarCtrlItem.Kind.SEAT ? -1 : 0;

        col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setContentDescription(item.title);

        icon = new ImageView(ctx);
        // 38dp:和 Dock 上那些车控键、空调面板的座椅一个量级
        col.addView(icon, new LinearLayout.LayoutParams(
                Launchers.dp(ctx, ICON_DP), Launchers.dp(ctx, ICON_DP)));

        // **只有座椅有档位**(0/1/2)。开关类只有开关、动作类连状态都没有,
        // 给它们画一排永远不亮的格子,等于在每张卡下面挂两个没意义的点
        if (item.kind == CarCtrlItem.Kind.SEAT) {
            bars = new StepBars(ctx, 2);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    Launchers.dp(ctx, 24), Launchers.dp(ctx, BARS_DP));
            blp.topMargin = Launchers.dp(ctx, BARS_GAP_DP);
            col.addView(bars, blp);
        } else {
            bars = null;
        }

        label = new TextView(ctx);
        label.setText(item.title);
        label.setTextSize(LABEL_SP);
        label.setGravity(Gravity.CENTER);
        // 一行封死:名字要是折成两行,这张卡就比同排高一截(和档位条那笔账一样)
        label.setMaxLines(1);
        // WLAN 连上会把标题换成 SSID,那名字通常比 72dp 的卡宽 —— 超出就截尾省略,
        // 不许撑破卡宽。要 ellipsize 生效,label 的宽必须是有界的(下面 MATCH_PARENT
        // = 卡宽),WRAP_CONTENT 下它会照名字长度无限撑开、根本不截。
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        // **没有档位条的卡要把条那段空着**:卡片高度是 WRAP_CONTENT,少这一段
        // 座椅四项就比别人高 10dp —— 卡片参差不齐,而且 snapCell() 拿第一张车控卡的
        // 实测高当行距,第一张是哪种卡决定整屏行距(2026-08-18 平板实测)。
        // 空着而不是压缩:图标和文字都跟同排对齐,座椅只是在留出的空里多两根条
        llp.topMargin = Launchers.dp(ctx, bars != null ? LABEL_GAP_DP
                : LABEL_GAP_DP + BARS_GAP_DP + BARS_DP);
        col.addView(label, llp);

        // 居中:这张卡的内容**定义**了一格多高(snapCell 量的就是它),所以居不居中
        // 眼下看不出差别 —— 写上是为了和别的卡一个规矩(见 DeskCard.fitCell)
        LayoutParams clp = new LayoutParams(Launchers.dp(ctx, W_DP), LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.CENTER;
        addView(col, clp);

        // 点击挂在**内容**上而不是卡片本身:卡片要留给 DeskCard 的长按摆位,
        // 两者靠"长按之后手指走不走"分岔(见 DeskCard.onTouchEvent)。
        // 内容是 clickable 的,DOWN 有人消费,后续 MOVE 才走得到 intercept
        if (!still) Motion.press(col);
        col.setOnClickListener(v -> tap());

        render();
    }

    public void setListener(Listener l) { this.listener = l; }

    public CarCtrlItem item() { return item; }

    public int level() { return level; }

    /** 铺档位。-1 = 没装配,整张卡不显示。 */
    public void setLevel(int lv) {
        level = lv;
        render();
    }

    /**
     * WLAN / 蓝牙卡:连上时把标题位换成"连的是谁",null/空 = 显示回项名。
     *
     * 只有这两张认它 —— 别的卡就算被误传也不该改样子(防呆)。
     */
    public void setNetName(String name) {
        if (!showsNetName()) return;
        this.netName = (name == null || name.isEmpty()) ? null : name;
        render();
    }

    /** 这张卡的标题位会不会被"连的是谁"顶掉。 */
    private boolean showsNetName() {
        return item == CarCtrlItem.WIFI || item == CarCtrlItem.BLUETOOTH;
    }

    /** 座椅项:从空调状态里取自己那一档。非座椅项不受影响。 */
    public void apply(AirCon.State s) {
        if (item.kind != CarCtrlItem.Kind.SEAT) return;
        // **只认全量读,而且 null 一律不动**:轻量读除温度风量外都是默认值,
        // null 更是什么都不知道 —— 拿它们去铺会算出档位 -1,而 -1 的意思是
        // "这台车没装配"、整张卡就藏起来了。2026-08-17 平板上实测:重挂卡片时
        // 拿还没读到的缓存(null)铺了一遍,座椅四项集体消失
        if (s == null || !s.full) return;
        setLevel(item.level(s));
    }

    @Override protected void onReskin() {
        render();
    }

    /**
     * 点一下。
     *
     * 座椅和开关**先乐观改、再发命令**:写操作要回读确认才返回(座椅真车实测少说
     * 150ms、重试满 450ms),等回来再变色的话点下去有小半秒没反应。发出去的那次回读
     * 会覆盖这里,猜错了自己弹回去。
     *
     * 动作类不乐观 —— 它没有"改成什么"可猜,只闪一下表示收到了。
     * 座椅的互斥(同座不能又吹又烤)跨卡片了,归宿主处理。
     */
    private void tap() {
        if (level < 0) return;      // 没装配的项本来就不显示,防呆
        switch (item.kind) {
            case SEAT:   setLevel((level + 1) % 3); break;   // 真车只有两档,到顶回 0
            // 系统开关(WLAN/热点)和车控开关在这一层没区别:都是乐观翻面、发下去、回读弹回
            case TOGGLE:
            case SYSTEM: setLevel(level > 0 ? 0 : 1); break;
            case LEVEL:  setLevel(item.nextPct(level)); break;
            case ACTION: break;                              // 无状态,不改样子
        }
        // 弹变化的那个:座椅是档位条,数值档位是标题(百分比写在那儿),
        // 开关和动作是图标(状态画在图标颜色上)。bars 对非座椅项是 null,见构造器
        if (!still) Motion.pop(bars != null ? bars
                : item.kind == CarCtrlItem.Kind.LEVEL ? label : icon);
        if (listener != null) listener.onCarCtrlTap(item);
    }

    private void render() {
        setVisibility(level < 0 ? GONE : VISIBLE);
        if (level < 0) return;
        boolean on = level > 0;
        // 座椅分冷暖,其余跟模式强调色(accent() 给 0 就是"跟模式")
        int accent = item.accent() != 0 ? item.accent() : ModeManager.get().accent();
        icon.setImageResource(item.icon(level));
        icon.setScaleX(item.mirrored() ? -1f : 1f);   // 副驾座椅朝向要翻过来
        icon.setColorFilter(on ? accent : ModeManager.get().glyph());
        // 标题位显示什么,见 labelText。**都是同一行,不加行** —— 多一行这张卡就比
        // 同排高一截、标题跟着错位(2026-08-17 真车上就这么翻车的)
        boolean showingNetName = showsNetName() && on && netName != null;
        label.setText(labelText(on));
        label.setTextSize(showingNetName ? NET_NAME_SP : LABEL_SP);
        label.setTextColor(on ? accent : ModeManager.get().muted());
        // 只有座椅那几项有条(建的时候就决定了,见构造器)
        if (bars != null) bars.set(level, accent);
    }

    /**
     * 标题位显示什么。三种覆盖,其余一律项名:
     *   数值档位(SOC 保电)开着 → 百分比;
     *   WLAN / 蓝牙连上(有名字) → 连的是谁;
     *   关着 / 读不到 → 回项名。
     */
    private CharSequence labelText(boolean on) {
        if (item.kind == CarCtrlItem.Kind.LEVEL && on) return level + "%";
        if (showsNetName() && on && netName != null) return netName;
        return item.title;
    }
}
