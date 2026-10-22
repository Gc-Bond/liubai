package com.liubai.blank.game;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 游戏库:有哪些游戏、归哪台机器、叫什么名字、上次玩到哪个。
 *
 * <h3>按平台分,不按核心分</h3>
 * 用户认的是「红白机」「GBA」,不是「FCEUmm」「mGBA」—— 核心是实现细节。
 * 而且 mGBA 一个顶 GB/GBC/GBA 三个平台,按核心分反倒把三台机器揉成一堆。
 * 核心只在一个地方露面:**这台机器的模拟器还没装,要下多少 MB**。
 */
public final class GameLibrary {

    private static final String TAG = "Blank/Game";

    /** 扫多深。整包游戏拷进来就是一个文件夹,得往下翻;封顶防的是有人把整块存储卡指过来。 */
    private static final int MAX_DEPTH = 3;

    /** 一台机器。 */
    public enum Platform {
        // .unf 是 UNIF 格式,FCEUmm 一样认 —— 老合集里混着几个
        NES   ("红白机",  0xFFE05B4A, 2, ".nes", ".unf"),
        GB    ("GameBoy", 0xFF7BC96F, 1, ".gb", ".gbc"),
        GBA   ("GBA",     0xFF6B8FE0, 1, ".gba"),
        ARCADE("街机",    0xFFE0A34A, 4, ".zip");

        public final String label;
        /** 没封面时卡片的底色 —— 一台机器一个色,一眼分得出这是哪家的游戏。 */
        public final int color;
        /**
         * 最多几个人同时玩。
         *
         * **GB 系是 1**:那机器根本没有第二个手柄口(真机联机要靠链接线,不是我们这条路)。
         * NES 两个手柄口。街机按 4 算 —— 一般是 2 人,但恐龙快打、TMNT 那种能上 4 个,
         * 而我们没法从 ROM 里读出这一局到底支持几个,给上限不吃亏(多出来的位子不显示)。
         */
        public final int maxPlayers;
        public final String[] suffixes;

        Platform(String label, int color, int maxPlayers, String... suffixes) {
            this.label = label;
            this.color = color;
            this.maxPlayers = maxPlayers;
            this.suffixes = suffixes;
        }

        /** 按后缀认。裸文件用这个,zip 得另外开箱看(见 {@link #of(File)})。 */
        static Platform bySuffix(String fileName) {
            String lower = fileName.toLowerCase(Locale.US);
            for (Platform p : values()) {
                if (p == ARCADE) continue;          // 街机就是 .zip,这里不该命中
                for (String s : p.suffixes) if (lower.endsWith(s)) return p;
            }
            return lower.endsWith(".zip") ? ARCADE : null;
        }

        /**
         * 认一个文件。**{@code .zip} 不能一律当街机** —— 大量 ROM 合集是
         * "一个 zip 包一个 .nes"(实测某 1286 个的 FC 合集全是这个形状),
         * 按后缀判会把一千多个红白机游戏整个错认成街机、去下 68 MB 的 FBNeo。
         *
         * 所以 zip 要开箱看:里面是掌机 ROM 就归那台机器,是一堆芯片转储才是街机。
         * 开箱只读中央目录,1285 个 zip 在 Mac 上 101 ms,车机按四倍算也就四百毫秒。
         */
        /**
         * 街机 BIOS,不是游戏。
         *
         * 它们长得跟街机 romset 一模一样(一堆芯片转储打成 zip),开箱也认不出来,
         * 只能按名字挡。不挡的话 BIOS 会以游戏身份出现在列表里,点进去就是一屏错误。
         *
         * 而且**它必须和游戏放同一个目录** —— 至少 fbalpha2012 那一挂是把 BIOS
         * 当普通 romset 去 roms/ 里找的(实测日志:{@code Failed to find archive: .../roms/neogeo}),
         * 压根不看 system/。所以躲不开,只能在这儿认名字。
         */
        private static final String[] BIOS_NAMES = {
                "neogeo.zip",       // NeoGeo:拳皇、合金弹头、侍魂那一挂全靠它
                "pgm.zip",          // IGS PGM:三国战纪
                "decocass.zip",
                "skns.zip",
                "isgsm.zip",
        };

        static Platform of(File f) {
            String lower = f.getName().toLowerCase(Locale.US);
            for (String b : BIOS_NAMES) if (lower.equals(b)) return null;
            Platform p = bySuffix(f.getName());
            if (p != ARCADE) return p;              // null 或掌机,都不用开箱
            return peekZip(f);
        }

        private static Platform peekZip(File f) {
            try (java.util.zip.ZipFile z =
                         // 用 ISO-8859-1 读条目名:老合集里的名字多是 GBK,按 UTF-8 解析会抛异常。
                         // 我们只看后缀(纯 ASCII),名字乱码无所谓
                         new java.util.zip.ZipFile(f, java.nio.charset.StandardCharsets.ISO_8859_1)) {
                int count = 0;
                java.util.Enumeration<? extends java.util.zip.ZipEntry> en = z.entries();
                while (en.hasMoreElements()) {
                    java.util.zip.ZipEntry e = en.nextElement();
                    if (e.isDirectory()) continue;
                    count++;
                    Platform inner = bySuffix(e.getName());
                    if (inner != null && inner != ARCADE) return inner;
                }
                // 里面不是掌机 ROM:多个文件才像街机(街机 zip 是几十个芯片转储);
                // 只有一个又不认识的(比如合集里混进来的 .exe),不当游戏
                return count > 1 ? ARCADE : null;
            } catch (Exception e) {
                Log.w(TAG, "打不开 " + f.getName() + ": " + e);
                return null;
            }
        }
    }

    /** 一个游戏。 */
    public static final class Game {
        /**
         * ROM 文件。本地条目扫描时生成;云端条目下载前是"目标路径"。
         * 街机下载完成后会从中文显示名改成 romset 驱动名(见
         * {@link GameActivity#downloadRemoteRom})—— 模拟器按 romset 名认游戏,
         * 中文名落盘必失败(dino.zip 能玩、「恐龙快打.zip」找不到驱动)。
         */
        public File file;
        public final Platform platform;
        /**
         * 云端条目:列表来自 yikm 在线站,ROM 还没下载。
         * file 指向下载后的目标路径(可能还不存在)。下载完成后
         * {@link #markDownloaded()} 就地转正,不用重扫重排。
         */
        public boolean cloud;
        /**
         * 云端条目对应的在线站信息,本地条目为 null。
         * 下载完成后仍保留:列表卡片的封面缩略图、重下都还要用它。
         */
        public OnlineStore.Remote online;
        /**
         * 洗干净的名字:去掉扩展名和「01-02 」这种前导编号。
         * 若 roms/names.txt 里有映射(街机合集的中文名表),扫描时会被替换成中文名。
         */
        public String name;
        /** 原文件名里的组号(「01-02」里的 01),没有就是空串。整包 ROM 常自带分组。 */
        public final String group;

        /**
         * 游戏类型,从文件名里的 {@code [ACT]} 这类标记解析。没有就是空串。
         *
         * 这是中文 ROM 合集的通行命名法(实测某 1286 个的 FC 合集全带),
         * 白给的分类信息 —— 一千多个游戏堆在一起,能按"动作/角色/射击"分开找,
         * 比只按平台分有用得多(它们全是红白机,平台那一维在这儿是废的)。
         */
        public final String genre;

        /** 三字母代码 → 中文。合集里出现过的都收了,认不出的原样显示。 */
        private static String genreName(String code) {
            switch (code) {
                case "ACT": return "动作";
                case "RPG": return "角色";
                case "STG": return "射击";
                case "SLG": return "策略";
                case "PUZ": return "益智";
                case "TAB": return "桌面";
                case "SPG": return "体育";
                case "FTG": return "格斗";
                case "RAC": return "竞速";
                case "AVG": return "冒险";
                case "ETC": return "其他";
                default:    return code;
            }
        }

        Game(File f, Platform p) {
            this(f, p, false, null);
        }

        /** 云端条目的构造:名字直接用站上的,不走"洗文件名"那套。 */
        Game(File f, Platform p, OnlineStore.Remote r) {
            this(f, p, true, r);
        }

        private Game(File f, Platform p, boolean cloud, OnlineStore.Remote r) {
            file = f;
            platform = p;
            this.cloud = cloud;
            this.online = r;
            String n = f.getName();
            int dot = n.lastIndexOf('.');
            if (dot > 0) n = n.substring(0, dot);

            // 先把类型标记捞出来再洗名字 —— 洗完就没了
            String gen = "";
            java.util.regex.Matcher gm =
                    java.util.regex.Pattern.compile("\\[([A-Z]{3})\\]").matcher(n);
            if (gm.find()) gen = genreName(gm.group(1));
            genre = gen;

            // **洗掉括号里的元数据**:「1942(简)[6502](JU)[STG](0.62Mb)」→「1942」。
            // 汉化组、地区、类型、大小对玩的人没有意义,还会把名字搅成
            // 1942(j)[6502](ju)[stg] —— 搜索直接废掉
            n = n.replaceAll("[\\(（\\[【][^\\)）\\]】]*[\\)）\\]】]", " ");
            n = n.replaceAll("\\s+", " ").trim();

            String g = "";
            // 「01-02 冒险岛」→ 组号 01、名字 冒险岛。整包 ROM 十有八九是这个格式,
            // 编号是给文件系统排序用的,不该出现在卡片上
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("^(\\d{1,3})[-_.]?(\\d{1,3})?[\\s_-]+(.+)$").matcher(n);
            if (m.matches()) {
                g = m.group(1);
                n = m.group(3);
            }
            name = n.trim();
            group = g;
            if (cloud && r != null) name = r.name;
        }

        /**
         * 下载完成后就地转正:还站在云端区原位置,但不再显示「云端」角标,
         * 再点一次就能进游戏。online 信息留着,封面缩略图还要用。
         */
        public void markDownloaded() {
            cloud = false;
        }

        /**
         * 配套封面图。扫描时按同名匹配到的(见 {@link GameLibrary#artIndex}),没有就是 null。
         *
         * **模拟器前端的封面从来不是抓帧来的** —— WinKawaks 有 sshots/、
         * RetroArch 有 thumbnails 库,都是配套的图片包,一个游戏一张、文件名就是 romset 名。
         * 有现成的当然用现成的:不用先玩一遍、不会抓到黑屏、更不会把核心的错误页存成封面。
         */
        File art;

        /** 封面文件。没有就是 null —— 卡片那时显示平台色块。 */
        public File cover() {
            return art != null && art.isFile() ? art : null;
        }

        public String key() { return file.getAbsolutePath(); }

        /**
         * 匹配一个搜索词。三条路都通:原名、平台名、类型。
         *
         * 不做拼音匹配 —— 网站搜索不支持拼音,本地做了对不上,反而不一致。
         * 平台名也算 —— 打「街机」就把街机游戏全筛出来,跟左栏点一下等价,
         * 但手已经在输入框上时不用再挪。
         */
        boolean matches(String q) {
            if (q.isEmpty()) return true;
            return name.toLowerCase(Locale.US).contains(q)
                    || platform.label.contains(q)
                    || genre.contains(q);      // 打「动作」就把动作游戏全筛出来
        }
    }

    /**
     * 真正要喂给核心的文件。
     *
     * zip 包着的掌机 ROM 得先解出来:libretro 的约定是**前端负责解压**,
     * 核心拿到的应该是 ROM 本身。街机反过来 —— 那个 zip 就是它认的东西
     * (里面几十个芯片转储,核心自己按名字和 CRC 去挑),原样把路径给它。
     *
     * 解出来的放 cache,同一个游戏第二次开直接复用;系统清缓存了也只是再解一次。
     *
     * <b>判据是文件内容,不是文件名后缀。</b> 站上 GBA 那批的 ROM 是 zip 包着的,而落盘名
     * 按分类拼成了 {@code 龙战士.gba} —— 只看后缀的话这一层直接跳过,原样喂给 mGBA,
     * 结果是 {@code retro_load_game 返回 false}(实测),而错误信息里完全看不出是压缩包。
     */
    public File romFor(Game g) {
        if (g.platform == Platform.ARCADE) return g.file;
        if (!looksZip(g.file)) return g.file;

        File out = new File(extractDir(), Integer.toHexString(g.key().hashCode()) + suffixOf(g));
        if (out.isFile() && out.length() > 0) return out;

        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(
                g.file, java.nio.charset.StandardCharsets.ISO_8859_1)) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                java.util.zip.ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                if (Platform.bySuffix(e.getName()) != g.platform) continue;
                File part = new File(out.getAbsolutePath() + ".part");
                try (java.io.InputStream in = z.getInputStream(e);
                     java.io.FileOutputStream fo = new java.io.FileOutputStream(part)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
                }
                if (part.renameTo(out)) {
                    Log.i(TAG, "解出 " + g.name + " → " + out.length() + " 字节");
                    return out;
                }
                part.delete();
                break;
            }
        } catch (Exception e) {
            Log.w(TAG, "解压失败 " + g.file.getName() + ": " + e);
        }
        return g.file;      // 解不出来就原样给核心,让它自己去报错
    }

    /** 前四个字节是不是 {@code PK\x03\x04}(zip 的魔数)。文件不存在/读不了都算不是。 */
    private static boolean looksZip(File f) {
        if (!f.isFile() || f.length() < 4) return false;
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] h = new byte[4];
            if (in.read(h) != 4) return false;
            return h[0] == 'P' && h[1] == 'K' && h[2] == 3 && h[3] == 4;
        } catch (Exception e) {
            return false;
        }
    }

    /** 这个游戏该用什么后缀落地 —— 核心有时按后缀认平台。 */
    private static String suffixOf(Game g) {
        return g.platform.suffixes.length > 0 ? g.platform.suffixes[0] : ".bin";
    }

    private File extractDir() {
        File d = new File(ctx.getCacheDir(), "romcache");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static final String PREFS = "gamecenter";
    /**
     * 最近玩过。**只剩这一份了** —— 原先还存着 `last_played` / `last_name` 两个键,
     * 那是给桌面卡片"点一下直接进上一局"用的,那条路撤了(见 {@link
     * com.liubai.blank.ui.GameCard}),键和读它们的方法一起删干净。
     */
    private static final String KEY_RECENT = "recent";

    private final Context ctx;
    private final List<Game> all = new ArrayList<>();

    public GameLibrary(Context c) { this.ctx = c.getApplicationContext(); }

    /** ROM 放这儿。不需要任何权限,adb push 和车机文件管理器都进得来。 */
    public File romDir() {
        File d = ctx.getExternalFilesDir("roms");
        if (d != null && !d.exists()) d.mkdirs();
        return d;
    }

    /** 中文名映射文件,放 roms/ 根目录。每行 「romset名<TAB>显示名」。 */
    private static final String NAMES_FILE = "names.txt";

    /**
     * 最近并入的云端列表。
     *
     * scan() 会清空 all 重建,**云端条目不是本地文件,重建后必须重新并入** ——
     * 不然下完一个游戏刷新列表,其余云端条目全消失(实测踩过)。
     */
    private List<OnlineStore.Remote> onlineCache = new java.util.ArrayList<>();

    /** romset 名(文件名去扩展名,小写) → 显示名。没映射到的游戏用洗干净的文件名。 */
    private final java.util.Map<String, String> names = new java.util.HashMap<>();

    /** 重扫。**阻塞**,150 个文件在车机上也就几十毫秒,但别在动画里调。 */
    /**
     * 图片索引:文件名(不含扩展名,小写) → 图片文件。
     *
     * 配套封面放哪儿都行 —— 跟 ROM 同目录、或者整个 sshots/ 目录拷进来都认,
     * 因为这里是**按名字配对**而不是按路径。KAWAKS 那套 sshots 就是
     * "一个游戏一张 Kof98.bmp",拷进 roms/ 下面直接就能用上。
     */
    private final java.util.Map<String, File> artIndex = new java.util.HashMap<>();

    public void scan() {
        all.clear();
        artIndex.clear();
        names.clear();
        loadNames(romDir());
        collect(romDir(), 0);
        // 中文名覆盖文件名,在配对封面之前做 —— 覆盖后搜索按中文名走
        for (Game g : all) {
            String n = names.get(baseName(g.file.getName()));
            if (n != null && !n.isEmpty()) g.name = n;
        }
        // 游戏和图片是一趟扫出来的,图片可能排在游戏后面,所以配对放到最后统一做
        int hit = 0;
        for (Game g : all) {
            File a = artIndex.get(baseName(g.file.getName()));
            if (a != null) { g.art = a; hit++; }
        }
        if (hit > 0) Log.i(TAG, "配套封面命中 " + hit + " / " + all.size());
        // 先按组号再按名字 —— 整包 ROM 的编号本身就是它的分类,别把它打乱
        Collections.sort(all, (a, b) -> {
            int c = a.group.compareTo(b.group);
            return c != 0 ? c : a.name.compareToIgnoreCase(b.name);
        });
        // 云端条目不是本地文件,scan 重建后要重新并回来(见 onlineCache 注释)
        mergeOnline(onlineCache);
        Log.i(TAG, "扫到 " + all.size() + " 个游戏于 " + romDir());
    }

    /** 读 names.txt。UTF-8,每行 「romset名<TAB>显示名」,空行/坏行跳过。 */
    private void loadNames(File dir) {
        File f = new File(dir, NAMES_FILE);
        if (!f.isFile()) return;
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(
                        new java.io.FileInputStream(f), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                int t = line.indexOf('\t');
                if (t <= 0) continue;
                String key = line.substring(0, t).trim().toLowerCase(Locale.US);
                String val = line.substring(t + 1).trim();
                if (!key.isEmpty() && !val.isEmpty()) names.put(key, val);
            }
        } catch (Exception e) {
            Log.w(TAG, "读 " + NAMES_FILE + " 失败: " + e);
        }
    }

    /**
     * 记一条 「romset名<TAB>显示名」 映射,追加进 names.txt。
     *
     * 街机下载落地后调:文件按 romset 名存(dino.zip),显示名还得是站上的
     * 中文名 —— 靠这条映射在 scan 时换回来。追加不覆盖用户已有内容,
     * 同 key 后写的覆盖先写的(loadNames 就是按行覆盖),值相同无妨。
     * **阻塞,I/O 很小(一次 append),别在动画里调。**
     */
    public void addNameMapping(String romset, String display) {
        if (romset == null || romset.isEmpty() || display == null || display.isEmpty()) return;
        names.put(romset.toLowerCase(Locale.US), display);
        File f = new File(romDir(), NAMES_FILE);
        try (java.io.FileWriter w = new java.io.FileWriter(f, true)) {
            w.write(romset + "\t" + display + "\n");
        } catch (Exception e) {
            Log.w(TAG, "写 " + NAMES_FILE + " 失败: " + e);
        }
    }

    private void collect(File dir, int depth) {
        if (dir == null || depth > MAX_DEPTH) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) { collect(f, depth + 1); continue; }
            if (isArt(f.getName())) { artIndex.put(baseName(f.getName()), f); continue; }
            Platform p = Platform.of(f);
            // 单文件 zip 会被 peekZip 的防误判挡掉(那是为了防"一个 zip 一个 .nes"的掌机合集)。
            // 但依赖 parent 的街机 clone 也是单文件(其余芯片在 parent zip 里,如 kov115),
            // 名字表里查得到就放行 —— 表只有正经 romset,不会放掌机合集进来
            if (p == null && names.containsKey(baseName(f.getName()))
                    && f.getName().toLowerCase(Locale.US).endsWith(".zip")) {
                p = Platform.ARCADE;
            }
            if (p != null) all.add(new Game(f, p));
        }
    }

    /** 认得的封面格式。bmp 是 KAWAKS 那套 sshots 的格式,BitmapFactory 直接能解。 */
    private static boolean isArt(String name) {
        String n = name.toLowerCase(Locale.US);
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".bmp");
    }

    /** 去掉扩展名的文件名,转小写。存档文件名也按它算(见 GameActivity.sramPath)。 */
    static String baseName(String name) {
        int dot = name.lastIndexOf('.');
        return (dot > 0 ? name.substring(0, dot) : name).toLowerCase(Locale.US);
    }

    public List<Game> all() { return all; }

    public List<Game> of(Platform p) {
        List<Game> out = new ArrayList<>();
        for (Game g : all) if (g.platform == p) out.add(g);
        return out;
    }

    /**
     * 搜。{@code p} 为 null 表示不限平台。
     *
     * 查询词统一转小写,人打字大小写随手。
     */
    public List<Game> search(Platform p, String query) {
        return search(p, false, query);
    }

    /**
     * 搜。{@code p} 为 null 表示不限平台;{@code localOnly} 为 true 只算本地
     * (云端条目是"还没下载"的,不该混进"本地游戏"目录)。
     */
    public List<Game> search(Platform p, boolean localOnly, String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.US);
        List<Game> out = new ArrayList<>();
        for (Game g : all) {
            if (localOnly && g.cloud) continue;
            if (p != null && g.platform != p) continue;
            if (g.matches(q)) out.add(g);
        }
        return out;
    }

    /**
     * 按显示名找本地已下载的游戏。网站搜索结果用它判断"这个已经下了"——
     * 找到了就直接用本地条目(点一次进游戏),没有的才建云端条目。
     *
     * <b>必须连平台一起比。</b>只比名字的话 FC 热门里的「雪人兄弟」会撞上本地的街机版
     * 雪人兄弟,首页那张 FC 卡就变成「街机 · 已下载」,点开跑的是街机(模拟器实测)。
     * 首页热门用的是站上的短名,同名跨平台在所难免。
     */
    public Game findLocal(String name, Platform p) {
        for (Game g : all) {
            if (!g.cloud && g.platform == p && g.name.equals(name)) return g;
        }
        return null;
    }

    /**
     * 云端条目 → 列表项。类型列表、首页热门都靠它把 {@link OnlineStore.Remote} 摆进列表。
     *
     * 三步走:①已下载的用本地条目(补上 online 来源,卡片显示「已下载」、缩略图照铺);
     * ②主列表里已经有同 id 的云端条目就**复用同一个对象** —— 几处列表必须共用一份,
     * 否则在其中一处下载完,另一处那张卡还停在「云端」(转正和改名只发生在被点的那个
     * 副本上,实测踩过);③都没有才新建。
     *
     * 平台认不出来(站上有我们没核心的机型)返回 null,调用方跳过。
     */
    public Game gameFor(OnlineStore.Remote r) {
        Platform p = Platform.bySuffix(r.cat.suffix);
        if (p == null) return null;
        Game local = findLocal(r.name, p);
        if (local != null) {
            local.online = r;
            return local;
        }
        for (Game x : all) {
            if (x.online != null && x.online.id.equals(r.id)) return x;
        }
        return new Game(new File(romDir(), r.romFileName()), p, r);
    }

    /**
     * 删掉一个本地游戏连同它的附属文件。**阻塞**(纯本地 I/O,很快)。
     *
     * 删三样:ROM 本身、下载来的封面、zip 掌机 ROM 的解压缓存。
     *
     * <b>几样不删。</b>{@code names.txt} 里的 romset→中文名映射留着 —— 那是
     * "这个 romset 叫什么"的知识,重新下载还要用它把中文名显示回来。街机的
     * parent romset(如 {@code kof97.zip})也不碰:别的 clone 还依赖着它,
     * 删一个能连累一片(见 {@link GameActivity#downloadRemoteRom} 里的 $ 拆分)。
     * 配套图库那种封面({@code art})是用户自己拷进来的,同样不动。
     *
     * <b>存档也不删</b>({@code system/} 下的 .srm / .state / 核心自己写的 .fs):
     * 同 names.txt 的道理 —— 重新下回来还接着玩,而攒下的进度删了就真没了。
     *
     * @return 释放了多少字节
     */
    public long delete(Game g) {
        long freed = drop(g.file);
        // 下载来的封面在 sshots/。街机按 romset 名存、掌机按中文名存
        // (见 downloadRemoteRom 里的 picName),两个名字都试一遍
        File sshots = new File(romDir(), "sshots");
        freed += drop(new File(sshots, g.name.replace('/', '／').trim() + ".png"));
        freed += drop(new File(sshots, baseName(g.file.getName()) + ".png"));
        // zip 包着的掌机 ROM 解出来的那份,命名见 romFor
        freed += drop(new File(extractDir(),
                Integer.toHexString(g.key().hashCode()) + suffixOf(g)));
        Log.i(TAG, "删了 " + g.name + ",释放 " + humanSize(freed));
        return freed;
    }

    /** 删一个文件,返回它原来占的字节数(文件不在或删不掉都是 0)。 */
    private static long drop(File f) {
        if (f == null || !f.isFile()) return 0;
        long n = f.length();
        return f.delete() ? n : 0;
    }

    /** 人看的大小。1 MB 以下给 KB —— FC 的 ROM 常常只有几十 KB,写 0.0 MB 等于没说。 */
    public static String humanSize(long bytes) {
        return bytes >= 1048576
                ? String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
                : String.format(Locale.US, "%d KB", bytes / 1024);
    }

    /** 本地(已下载)游戏有多少。左栏「本地」那一格的数字。 */
    public int localCount() {
        int n = 0;
        for (Game g : all) if (!g.cloud) n++;
        return n;
    }

    public int count(Platform p) {
        int n = 0;
        for (Game g : all) if (g.platform == p) n++;
        return n;
    }

    /**
     * 并入在线站的列表。
     *
     * 本地已下载的同名 ROM 直接跳过(本地优先,不重复收);没下载的
     * 以「云端」条目进列表,点了再去下。**必须在 {@link #scan()} 之后调**,
     * 因为要复用 scan 建好的本地索引判断重名。
     */
    public void mergeOnline(List<OnlineStore.Remote> list) {
        if (list == null || list.isEmpty()) return;
        onlineCache = new java.util.ArrayList<>(list);
        // 只把"还没下载的云端条目"(cloud=true)摘掉,按最新拉到的网站顺序重摆;
        // **已下载的必须留在 rest 里** —— 它们 cloud=false、只是 online 标记补了
        // 来源,不留在 rest 的话,下面按文件名/names.txt 反查匹配不到,会被当成
        // 云端条目重建(角标变回「云端」,街机点一下还重新下载,实测踩过)。
        // 纯本地(网站库里没有的)同样留着,按名字排在后段。
        java.util.List<Game> rest = new java.util.ArrayList<>();
        for (Game g : all) if (!g.cloud) rest.add(g);
        all.clear();

        for (OnlineStore.Remote r : list) {
            Platform p = Platform.bySuffix(r.cat.suffix);
            if (p == null) continue;
            File target = new File(romDir(), r.romFileName());
            // 先找本地已经下载的:同名 rom(手工推的包可能在子目录),
            // 或街机靠 names.txt 的 romset→显示名 映射反查是不是同一个。
            // 找到就补 online 标记,不新建条目 —— 文件还在原地,只是
            // 回到网站位置显示「已下载」,再点一次直接进游戏
            Game local = null;
            int li = -1;
            for (int i = 0; i < rest.size(); i++) {
                Game g = rest.get(i);
                if (g.file.getName().equals(target.getName())) { local = g; li = i; break; }
                if (r.cat.suffix.equals(".zip")) {
                    String display = names.get(baseName(g.file.getName()));
                    if (display != null && display.equals(r.name)) { local = g; li = i; break; }
                }
            }
            if (local != null) {
                rest.remove(li);
                local.online = r;
                all.add(local);
                continue;
            }
            // 云端还没下载:同一个 id 只收一次
            boolean have = false;
            for (Game g : all) {
                if (g.online != null && g.online.id.equals(r.id)) { have = true; break; }
            }
            if (have) continue;
            all.add(new Game(target, p, r));
        }

        // 纯本地按名字排在后段
        Collections.sort(rest, (a, b) -> {
            int c = a.group.compareTo(b.group);
            return c != 0 ? c : a.name.compareToIgnoreCase(b.name);
        });
        all.addAll(rest);
        Log.i(TAG, "并入云端 " + list.size() + " 条,纯本地 " + rest.size() + " 个");
    }

    /** 有游戏的平台。一台机器一个游戏都没有就别在左栏占位置。 */
    public List<Platform> platforms() {
        List<Platform> out = new ArrayList<>();
        for (Platform p : Platform.values()) if (count(p) > 0) out.add(p);
        return out;
    }

    // ---- 上次玩的 ----

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 最近玩过留几个。右侧那条栏放得下四五个,多了也没人往下翻。 */
    private static final int RECENT_MAX = 6;

    /**
     * 记一笔"刚玩了这个"。
     *
     * 存的是绝对路径,用 {@code \n} 串起来 —— 一个 SharedPreferences 的字符串就够了,
     * 不值得为六条记录起个数据库。同一个游戏再玩会挪到队首而不是重复。
     */
    public void remember(Game g) {
        List<String> keys = new ArrayList<>();
        keys.add(g.key());
        for (String k : recentKeys()) {
            if (!k.equals(g.key())) keys.add(k);
            if (keys.size() >= RECENT_MAX) break;
        }
        prefs().edit()
                .putString(KEY_RECENT, android.text.TextUtils.join("\n", keys))
                .apply();
    }

    private List<String> recentKeys() {
        String s = prefs().getString(KEY_RECENT, "");
        List<String> out = new ArrayList<>();
        if (s.isEmpty()) return out;
        for (String k : s.split("\n")) if (!k.isEmpty()) out.add(k);
        return out;
    }

    /**
     * 最近玩过的,新的在前。文件被删了或换了目录的自动跳过 —— 不清理存档,
     * 因为 ROM 可能只是暂时没插 U 盘,下次又回来了。
     */
    public List<Game> recent() {
        List<Game> out = new ArrayList<>();
        for (String k : recentKeys()) {
            for (Game g : all) {
                if (g.key().equals(k)) { out.add(g); break; }
            }
        }
        return out;
    }

}
