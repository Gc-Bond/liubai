package com.liubai.blank.game;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * 金手指。**两台机器两条完全不同的路,但界面上是同一张表**(见 {@link Cheat})。
 *
 * <h3>FC:自己往 RAM 里写</h3>
 * 码从 yikm 的 {@code /cheat?id=<游戏id>} 拿,一行纯文本、逗号分条、{@code $} 前码后名:
 * <pre>0032-01-64$1P生命数,00B0-01-FF$1P金身无敌,00AA-01-11$M弹</pre>
 * 码是 {@code 地址-字节数-值}(十六进制)。生效靠 native 每帧把值按回去
 * (见 {@code bridge.c} 的 apply_cheats)——「无限命」不是改一次命数,
 * 是游戏每次扣完我们再盖回去。
 *
 * <h3>街机:交给核心自己的 cheat 引擎</h3>
 * 站上街机那份只有名字和选项、**没有地址**(数据在模拟器自带的 cheat 库里),所以这条
 * 不走 yikm。FBNeo 核心本来就有完整的 cheat 引擎:它在装游戏时读
 * {@code <system>/fbneo/cheats/<romset>.ini},把每条 cheat 变成一个
 * {@code fbneo-cheat-<n>-<romset>-<名字>} 核心选项报给我们,我们回答哪个值,
 * 它就 CheatEnable 哪条 —— 多 CPU、指针型那些我们自己做不了的花样全在核心里。
 *
 * ini 来自 FBNeo 官方 cheat 仓库(finalburnneo/FBNeo-cheats),一个 romset 一个文件、
 * 几 KB,按需下载。**必须在装游戏之前放好**:那张选项表是 load_game 时报的。
 */
public final class CheatStore {

    private static final String TAG = "Blank/Cheat";
    private static final String URL_FMT = "https://www.yikm.net/cheat?id=%s";
    /** FBNeo 官方 cheat 包,一个 romset 一个 ini。 */
    private static final String ARCADE_URL_FMT =
            "https://raw.githubusercontent.com/finalburnneo/FBNeo-cheats/master/cheats/%s.ini";

    /**
     * 一条金手指。**两种形态共用**,界面只认 {@link #options} 和 {@link #sel}:
     * 点一下就往下轮一格,轮回 0 就是关掉。
     *
     * FC 那种是两档(关 / 开);街机可能有好几档("Select Starting Episode" 有 8 关可选),
     * 所以不能做成布尔开关。
     */
    public static final class Cheat {
        /** 显示名。街机的已经去掉了 {@code [Cheat][dino.ini]} 那层前缀。 */
        public final String name;
        /** 每一档的显示名,{@code options[0]} 一律是"关"。 */
        public final String[] options;
        /** 现在是第几档。0 = 关。 */
        public int sel;

        // ---- 直写型(FC)----
        final int addr, len, val;
        // ---- 核心选项型(街机)----
        final String key;
        /** 核心认的原始值串(如 {@code "1 - Enabled"}),**差一个字都不认**。 */
        final String[] raw;

        /** FC:地址-长度-值,两档。 */
        Cheat(String name, int addr, int len, int val) {
            this.name = name; this.addr = addr; this.len = len; this.val = val;
            this.key = null; this.raw = null;
            this.options = new String[]{ "关", "开" };
        }

        /** 街机:核心选项。 */
        Cheat(String name, String key, String[] raw) {
            this.name = name; this.key = key; this.raw = raw;
            this.addr = this.len = this.val = 0;
            this.options = new String[raw.length];
            for (int i = 0; i < raw.length; i++) options[i] = shortValue(raw[i], i);
        }

        public boolean isOption() { return key != null; }
        public boolean on() { return sel > 0; }
        /** 当前这档在界面上写什么。 */
        public String label() { return options[Math.max(0, Math.min(sel, options.length - 1))]; }
    }

    private CheatStore() {}

    // ---- FC:yikm 的 RAM 码 ----

    /** 解析站上那行文本。认不出的条目直接跳过 —— 一条坏码不该让整张表打不开。 */
    public static List<Cheat> parse(String text) {
        List<Cheat> out = new ArrayList<>();
        if (text == null) return out;
        for (String item : text.split(",")) {
            int d = item.indexOf('$');
            if (d <= 0) continue;
            String code = item.substring(0, d).trim();
            String name = item.substring(d + 1).trim();
            String[] p = code.split("-");
            if (p.length != 3 || name.isEmpty()) continue;
            try {
                int addr = Integer.parseInt(p[0].trim(), 16);
                int len  = Integer.parseInt(p[1].trim(), 16);
                int val  = Integer.parseInt(p[2].trim(), 16);
                if (len < 1 || len > 4) continue;
                out.add(new Cheat(name, addr, len, val));
            } catch (NumberFormatException e) {
                // 街机那种「第1敌将掉物$箭#白色弹珠#…」会落到这儿:它压根不是码
            }
        }
        return out;
    }

    /**
     * 取一个 FC 游戏的金手指。**先联网,拉不到用缓存**(车里常没网)。
     *
     * 缓存按站上的 id 落在 {@code system/cheats/<id>.txt},几百字节。拉回来是空的也存 ——
     * 「这个游戏站上就没有金手指」同样是个结论,不必每次进游戏都再问一遍。
     *
     * <b>要在后台线程调</b>(联网 + 文件)。
     */
    public static List<Cheat> load(File cacheDir, String id) {
        File f = new File(cacheDir, id + ".txt");
        String net = fetch(id);
        if (net != null) {
            write(f, net);
            return parse(net);
        }
        String cached = read(f);
        if (cached != null) {
            Log.i(TAG, "拉不到,用缓存的金手指 " + id);
            return parse(cached);
        }
        return new ArrayList<>();
    }

    /** 开着的那些 → native 要的三元组(**只有直写型**)。全关就返回空数组。 */
    public static int[] triples(List<Cheat> list) {
        int n = 0;
        for (Cheat c : list) if (!c.isOption() && c.on()) n++;
        int[] out = new int[n * 3];
        int i = 0;
        for (Cheat c : list) {
            if (c.isOption() || !c.on()) continue;
            out[i++] = c.addr;
            out[i++] = c.len;
            out[i++] = c.val;
        }
        return out;
    }

    // ---- 街机:核心选项 ----

    /**
     * 把 ini 下到 {@code <system>/fbneo/cheats/<romset>.ini}。
     *
     * **必须在装游戏之前**:那张选项表是核心 load_game 时读 ini 生成的,
     * 游戏都跑起来了再放,这一局也不会有。
     *
     * 超时给得短(4 秒):它挡在"点了游戏"和"游戏出画面"之间,车里网差的时候
     * 宁可这一局没有金手指,也不能让人对着黑屏等。
     */
    public static boolean downloadArcadeIni(String romset, File out) {
        HttpURLConnection conn = null;
        try {
            URL u = new URL(String.format(ARCADE_URL_FMT, romset));
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            if (conn.getResponseCode() != 200) {
                Log.i(TAG, "官方 cheat 包里没有 " + romset + ".ini(" + conn.getResponseCode() + ")");
                return false;
            }
            File dir = out.getParentFile();
            if (dir != null && !dir.isDirectory()) dir.mkdirs();
            // 先写 .part 再改名:下到一半断了,留个半截 ini 会让核心读出半张表
            File part = new File(out.getPath() + ".part");
            InputStream in = conn.getInputStream();
            FileOutputStream fo = new FileOutputStream(part);
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) { fo.write(buf, 0, n); total += n; }
            fo.close();
            in.close();
            if (out.exists()) out.delete();
            boolean ok = part.renameTo(out);
            Log.i(TAG, "取到 " + romset + ".ini," + total + " 字节,落盘=" + ok);
            return ok;
        } catch (Exception e) {
            Log.w(TAG, "取 " + romset + ".ini 失败: " + e);
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 从核心报的选项表里挑出金手指(见 {@link Retro#nativeVariables})。
     *
     * 每行 {@code key\tdesc\tvals\tcur};只要 key 以 {@code fbneo-cheat-} 开头的那些。
     * 名字要去掉核心加的 {@code [Cheat][dino.ini] } 前缀 —— 那是给 RetroArch 的
     * 选项菜单分组用的,在我们这张表里每一行都一样,纯占地方。
     */
    public static List<Cheat> fromVariables(String dump) {
        List<Cheat> out = new ArrayList<>();
        if (dump == null || dump.isEmpty()) return out;
        for (String line : dump.split("\n")) {
            String[] f = line.split("\t");
            if (f.length < 4 || !f[0].startsWith("fbneo-cheat-")) continue;
            String[] vals = f[2].split("\\|");
            if (vals.length < 2) continue;
            Cheat c = new Cheat(zhName(cleanName(f[1])), f[0], vals);
            // 核心报的 cur 就是这一局的现值(默认那档),对上是第几档
            for (int i = 0; i < vals.length; i++) if (vals[i].equals(f[3])) c.sel = i;
            out.add(c);
        }
        return out;
    }

    /**
     * 去掉核心加的 {@code [Cheat][dino.ini] } 前缀,以及 ini 里那些**分组标题**。
     *
     * FBNeo 会把分组标题拼在条目名前面(`create_variables_from_cheats` 里
     * {@code heading_name + option_name}),于是名字长成
     * {@code -= 'Advanced' Mode Cheats =-Infinite Time} 或 {@code [   Jubei   ]无敌}。
     * 那层在 RetroArch 的选项菜单里是用来分组的,在我们这张一屏就看完的表里纯占地方。
     */
    private static String cleanName(String desc) {
        String s = desc;
        while (s.startsWith("[")) {
            int e = s.indexOf(']');
            if (e < 0) break;
            s = s.substring(e + 1).trim();
        }
        // ini 里的分组标题:`-= xxx =-`、`>>> xxx <<<`
        s = s.replaceAll("-=[^=]*=-", "").replaceAll(">>>[^<]*<<<", "").trim();
        return s.isEmpty() ? desc : s;
    }

    /**
     * 街机金手指的名字**尽量说中文**。
     *
     * <h4>为什么街机是英文而 FC 是中文</h4>
     * 两台机器的金手指来自两个源:FC 那份是 yikm 写的(中文),街机这份是 FBNeo 官方
     * cheat 包(社区维护,英文)。**站上街机那份用不了** —— 它只有名字和选项、没有地址
     * (整整 38 KB 的返回里一个地址都没有,实测),而且跟 FBNeo 的条目根本对不上:
     * 同一个三国战纪,yikm 180 条、FBNeo 65 条,顺序和内容都不是一回事,连按序号映射
     * 都做不到。
     *
     * <h4>所以只能自己译</h4>
     * 好在这些名字是**规则化**的,不是随口写的散文。拆成四块处理:
     * <pre>
     *   Get Sun Now! (Elf) PL1
     *   └─主体─┘└Now!┘└括号┘└PLn┘   →  马上拿到太阳 (Elf) 1P
     * </pre>
     * ① {@code PLn} 后缀 → 「1P」;② 尾部括号里多是角色名/类型,查小表,**查不到原样保留**
     * (专有名词本来就不该硬译);③ {@code Now!} 结尾 = 立刻执行一次 → 「马上…」;
     * ④ 主体整条查表(大小写不敏感),另认 {@code Select 3rd Item} 这类序数、
     * {@code Rapid Fire - Normal} 这类破折号修饰。
     *
     * <b>主体查不到就整条留英文。</b> 试过逐词替换那一版:17% 全中文,但会出现
     * 「连发 - Unlimited 2P」「Select 第7个道具 4P」这种半截货,比整条英文更难读。
     *
     * 抽样 115 个 romset、1724 条 cheat 实测:**按出现次数盖住 61%**,而且盖住的正是
     * 最常用那批(无限命 / 无敌 / 无限体力 / 连发 / 选人物 / 选关)。剩下的长尾是游戏
     * 专有词(角色名 Cody、招式名 Soukeiha、大金刚的 barrels/pies),硬译反而不准。
     */
    private static String zhName(String name) {
        String s = name.trim();
        String pl = "", paren = "";
        // PLn 可能在括号前,也可能在后(`Quick Soukeiha PL1 (LINN Only)`),所以转两圈
        for (int i = 0; i < 2; i++) {
            java.util.regex.Matcher m = P_PL.matcher(s);
            if (m.matches()) { s = m.group(1).trim(); pl = " " + m.group(2) + "P"; continue; }
            java.util.regex.Matcher p = P_PAREN.matcher(s);
            if (p.matches() && paren.isEmpty()) {
                // 整条(连括号)本身就在表里的话别拆(`Invincibility (Buggy)`)
                if (ZH.containsKey(s.toLowerCase(java.util.Locale.US))) break;
                String inner = p.group(2).trim();
                String t = PAREN.get(inner);
                s = p.group(1).trim();
                paren = " (" + (t != null ? t : inner) + ")";
                continue;
            }
            break;
        }

        String now = "", body = s;
        if (!ZH.containsKey(body.toLowerCase(java.util.Locale.US)) && P_NOW.matcher(body).find()) {
            body = P_NOW.matcher(body).replaceAll("").trim();
            now = "马上";
        }

        // `Kill Vice T. with 1 hit Now!` —— 中间那截是 Boss 的名字,原样留着。
        // 这条不加「马上」前缀:一击必杀本来就是当场的事,"马上一下打死"读着别扭
        java.util.regex.Matcher k = P_KILL.matcher(body);
        if (k.matches()) return "一下打死 " + k.group(1).trim() + paren + pl;

        String zh = ZH.get(body.toLowerCase(java.util.Locale.US));
        if (zh == null) {
            java.util.regex.Matcher m = P_ITEM.matcher(body);
            if (m.matches()) {
                zh = ("select".equalsIgnoreCase(m.group(1)) ? "选第" : "无限第")
                        + m.group(2) + "个道具";
            }
        }
        if (zh == null && body.contains(" - ")) {
            int d = body.indexOf(" - ");
            String head = ZH.get(body.substring(0, d).trim().toLowerCase(java.util.Locale.US));
            String tail = DASH.get(body.substring(d + 3).trim());
            // 两截都认识才拼,否则宁可整条留英文
            if (head != null && tail != null) zh = head + " " + tail;
        }
        if (zh == null) return name;
        return now + zh + paren + pl;
    }

    private static final java.util.regex.Pattern P_PL =
            java.util.regex.Pattern.compile("^(.*?)\\s*PL([1-4])\\s*$");
    private static final java.util.regex.Pattern P_PAREN =
            java.util.regex.Pattern.compile("^(.*?)\\s*\\(([^()]*)\\)\\s*$");
    private static final java.util.regex.Pattern P_NOW =
            java.util.regex.Pattern.compile("(?i)\\s*Now!$");
    private static final java.util.regex.Pattern P_KILL =
            java.util.regex.Pattern.compile("(?i)^Kill\\s+(.+?)\\s+with\\s+(?:1|one)\\s+hit$");
    private static final java.util.regex.Pattern P_ITEM =
            java.util.regex.Pattern.compile("(?i)^(Select|Infinite)\\s+(\\d)(?:st|nd|rd|th)\\s+Item$");

    /** 主体对照表,**key 一律小写**(cheat 包里同一个词大小写不统一,`Drain All` / `Drain all` 都有)。 */
    private static final java.util.Map<String, String> ZH = new java.util.HashMap<>();
    /** 破折号后面那截修饰词。**查不到就整条不译** —— 否则出「连发 - Unlimited」这种半截货。 */
    private static final java.util.Map<String, String> DASH = new java.util.HashMap<>();
    /** 括号里的词。多是角色名,查不到**原样保留**。 */
    private static final java.util.Map<String, String> PAREN = new java.util.HashMap<>();
    static {
        ZH.put("infinite time", "无限时间");
        ZH.put("infinite boss time", "打 Boss 无限时间");
        ZH.put("infinite character select time", "选人物时无限时间");
        ZH.put("infinite shop time", "商店无限时间");
        ZH.put("infinite time bonus levels", "奖励关无限时间");
        ZH.put("infinite credits", "无限投币");
        ZH.put("infinite continues", "无限续关");
        ZH.put("infinite enemy count", "敌人数不减");
        ZH.put("infinite energy", "无限体力");
        ZH.put("infinite health", "无限血");
        ZH.put("infinite lives", "无限命");
        ZH.put("infinite power", "无限气");
        ZH.put("infinite special", "无限必杀");
        ZH.put("infinite super", "无限超必杀");
        ZH.put("infinite ammo", "无限弹药");
        ZH.put("infinite bombs", "无限炸弹");
        ZH.put("infinite keys", "无限钥匙");
        ZH.put("infinite potions", "无限药水");
        ZH.put("infinite jumps", "无限跳跃");
        ZH.put("infinite tank gas", "油不减");
        ZH.put("infinite magic", "无限魔法");
        ZH.put("infinite money", "无限金钱");
        ZH.put("infinite bullets", "无限子弹");
        ZH.put("invincibility", "无敌");
        ZH.put("invincibility (buggy)", "无敌(不太稳)");
        ZH.put("transparency", "透明");
        ZH.put("never get dizzied", "不会被打晕");
        ZH.put("every hit will dizzy", "一打就晕");
        ZH.put("never get guard crushed", "防御不会被破");
        ZH.put("every block will guard crush", "一防就破防");
        ZH.put("keep barrier after death", "死后保留护罩");
        ZH.put("freeze all enemies", "冻住所有敌人");
        ZH.put("never have keys", "没有钥匙");
        ZH.put("rapid fire", "连发");
        ZH.put("action speed up", "出招加速");
        ZH.put("quick motion", "出招加速");
        ZH.put("maximum fire power", "火力全开");
        ZH.put("maximum bubbles power", "泡泡威力最大");
        ZH.put("always max power", "气一直是满的");
        ZH.put("always have minimum power", "气保持最低");
        ZH.put("always have speed up", "一直加速");
        ZH.put("always have no power-up", "一直不强化");
        ZH.put("always have ultimate shot", "一直有终极射击");
        ZH.put("always half size", "一直是小人");
        ZH.put("always drive at maximum speed", "一直全速开");
        ZH.put("always ride at faster speed", "一直骑快些");
        ZH.put("always ride at fastest speed", "一直骑最快");
        ZH.put("max level", "等级拉满");
        ZH.put("endless grab attack", "抓取攻击不断");
        ZH.put("punch only combo", "只出拳的连招");
        ZH.put("bodyblow only combo", "只用身体撞的连招");
        ZH.put("one hit kill", "一下打死");
        ZH.put("one hit to kill goons", "一下打死小兵");
        ZH.put("one hit for boss", "一下打死 Boss");
        ZH.put("always 1 hit to kill bosses", "一下打死 Boss");
        ZH.put("kill boss with one hit", "一下打死 Boss");
        ZH.put("kill boss faster", "更快打死 Boss");
        ZH.put("kill all enemies on this level", "清掉本关所有敌人");
        ZH.put("kill all on-screen enemies", "清掉屏幕上的敌人");
        ZH.put("kill all enemies", "清掉所有敌人");
        ZH.put("drain all energy", "把体力清空");
        ZH.put("drain all power", "把气清空");
        ZH.put("lose a life", "掉一条命");
        ZH.put("finish this fight", "这场直接打完");
        ZH.put("finish this round", "这回合直接打完");
        ZH.put("finish this scene", "这一幕直接过");
        ZH.put("finish this stage", "这一关直接过");
        ZH.put("finish this level", "这一关直接过");
        ZH.put("clear this scene", "这一幕直接清");
        ZH.put("change next round", "换下一回合");
        ZH.put("goto ending next", "下一幕直接到结局");
        ZH.put("goto boss fight screen", "直接跳到 Boss 战");
        ZH.put("goto next stage", "直接进下一关");
        ZH.put("go to final stage", "直接到最终关");
        ZH.put("go to last level", "直接到最后一关");
        ZH.put("open all doors", "打开所有门");
        ZH.put("turn walls to exits", "墙变成出口");
        ZH.put("warp door always open", "传送门一直开");
        ZH.put("warp door never open", "传送门一直关");
        ZH.put("open warp door", "打开传送门");
        ZH.put("shut warp door", "关上传送门");
        ZH.put("don't die when ball is out", "球掉了也不死");
        ZH.put("get sun", "拿到太阳");
        ZH.put("get extra magic power", "加魔力");
        ZH.put("get extra shot speed", "加射速");
        ZH.put("get extra shot power", "加火力");
        ZH.put("get extra speed", "加速度");
        ZH.put("get extra shield", "加护盾");
        ZH.put("get maximum power", "力量拉满");
        ZH.put("get partial invisibility", "半隐身");
        ZH.put("get maximum speed boost", "加速拉满");
        ZH.put("select starting episode", "选章节");
        ZH.put("select starting stage", "选关");
        ZH.put("select starting level", "选关");
        ZH.put("select current stage", "选当前关");
        ZH.put("select next level", "选下一关");
        ZH.put("stage select", "选关");
        ZH.put("level select", "选关");
        ZH.put("select character", "选人物");
        ZH.put("select first character", "选第一个人物");
        ZH.put("select second character", "选第二个人物");
        ZH.put("select third character", "选第三个人物");
        ZH.put("select cartridge/neogeo type", "选机型");
        ZH.put("select weapon", "选武器");
        ZH.put("select shot equipment", "选射击装备");
        ZH.put("select region", "选地区版本");
        ZH.put("select colour", "选配色");
        ZH.put("select color", "选配色");
        ZH.put("select ending", "选结局");
        ZH.put("select background", "选背景");
        ZH.put("select rounds lost", "设定已输回合");
        ZH.put("select difficulty", "选难度");
        ZH.put("select speed", "选速度");
        ZH.put("select robot level", "选机器人等级");
        ZH.put("select lives", "选命数");
        ZH.put("select stage", "选关");
        ZH.put("sound test", "音效测试");
        ZH.put("sound test timer stop", "音效测试不计时");
        ZH.put("infinite bonus time", "奖励时间无限");
        ZH.put("select character colour", "选人物配色");
        ZH.put("select character color", "选人物配色");
        ZH.put("select rounds won", "设定已胜回合");
        ZH.put("select backdrop", "选背景");
        ZH.put("enable survival mode", "开生存模式");
        ZH.put("always climb", "一直能爬");

        DASH.put("Normal", "普通");
        DASH.put("Weapon", "武器");
        DASH.put("Unlimited", "无限");
        DASH.put("Robot", "机器人");
        DASH.put("Bubbles", "泡泡");
        DASH.put("Energy", "能量");
        DASH.put("Bomb", "炸弹");
        DASH.put("Time", "时间");

        PAREN.put("Buggy", "不太稳");
        PAREN.put("Bomb", "炸弹");
        PAREN.put("Energy", "能量");
        PAREN.put("Stealth type", "隐身型");
        PAREN.put("Bomb type", "炸弹型");
        PAREN.put("Both", "两个都");
        PAREN.put("All", "全部");
    }

    /**
     * 核心的值串 {@code "1 - Enabled"} → 界面上的 {@code "Enabled"};第 0 档一律写「关」。
     *
     * 那个序号前缀是核心自己加的(官方 cheat 包里有同名的两档,不加序号会选错),
     * 对人没有意义。
     */
    private static String shortValue(String raw, int index) {
        String s = raw;
        int dash = s.indexOf(" - ");
        if (dash >= 0) s = s.substring(dash + 3).trim();
        if (index == 0) return "关";
        // 绝大多数 cheat 就是两档,第二档一律叫 Enabled —— 那一档写「开」
        if ("Enabled".equalsIgnoreCase(s) || "On".equalsIgnoreCase(s)) return "开";
        if ("Disabled".equalsIgnoreCase(s) || "Off".equalsIgnoreCase(s)) return "关";
        return s.isEmpty() ? String.valueOf(index) : s;
    }

    /** 把选项型的当前档下发给核心。核心下一帧自己会重读、重新应用。 */
    public static void pushOptions(List<Cheat> list) {
        for (Cheat c : list) {
            if (!c.isOption()) continue;
            int i = Math.max(0, Math.min(c.sel, c.raw.length - 1));
            Retro.nativeSetVariable(c.key, c.raw[i]);
        }
    }

    // ---- HTTP ----

    private static String fetch(String id) {
        HttpURLConnection conn = null;
        try {
            URL u = new URL(String.format(URL_FMT, id));
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            // 站上按 Referer 挡直连,照播放页那套带上(同 OnlineStore 下载 ROM)
            conn.setRequestProperty("Referer", "https://www.yikm.net/play?id=" + id);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/120.0 Safari/537.36");
            if (conn.getResponseCode() != 200) return null;
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            Log.w(TAG, "拉金手指失败 " + id + ": " + e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void write(File f, String text) {
        try {
            File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory()) dir.mkdirs();
            FileOutputStream fo = new FileOutputStream(f);
            fo.write(text.getBytes("UTF-8"));
            fo.close();
        } catch (Exception e) {
            Log.w(TAG, "金手指缓存写不了: " + e);
        }
    }

    private static String read(File f) {
        if (!f.isFile()) return null;
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }
}
