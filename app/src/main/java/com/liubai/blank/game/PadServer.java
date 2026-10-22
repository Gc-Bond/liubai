package com.liubai.blank.game;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 手机手柄的本机服务:一个 HTTP 端口既发手柄页、又升级成 WebSocket 收按键。
 *
 * 手写而不是引 NanoHTTPD —— 这个库连 adb 协议都是手写的,而这里要的东西极少:
 * 一个静态页 + 一条单客户端的文本帧通道。
 *
 * 协议只有两个字:按下 "D<键号>"、抬起 "U<键号>",键号就是 RETRO_DEVICE_ID_JOYPAD_*。
 * 收到就往 {@link Retro#nativeKey} 写 —— libretro 的输入是**轮询**模型,
 * 核心每帧自己来问,所以这里只是改个状态,没有事件注入、没有时序问题。
 */
public class PadServer {

    private static final String TAG = "PadServer";
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final Context ctx;
    private final int port;
    private ServerSocket server;
    private volatile boolean running;
    private volatile String clientInfo = "(手机未连接)";
    /** 在线连接数上限 —— 手柄页断线会自动重连,没有闸门的话重连风暴会把线程堆爆。 */
    private static final int MAX_CONN = 8;
    private final AtomicInteger conns = new AtomicInteger();
    /** 当前活着的连接。stop() 要关它们 —— 不关的话 pump 线程会一直阻塞在 read 上。 */
    private final List<Socket> clients = new ArrayList<>();
    private final Object clientsLock = new Object();

    public PadServer(Context ctx, int port) {
        this.ctx = ctx.getApplicationContext();
        this.port = port;
    }

    public String clientInfo() { return clientInfo; }

    /**
     * 手柄页该摆哪套键位。{@code "pad"} = 掌机两颗(A/B),{@code "arcade"} = 街机六颗
     * (A/B/C/D + E/F,投币另算)。
     *
     * <b>同一个键号在两台机器上叫的名字不一样</b>:任天堂那边 id 0 是 B、id 8 是 A;
     * 街机那边 id 0 是 A、id 8 是 B(FBNeo 的 retropad 映射)。所以键位不能写死在
     * 手机页里,得由车机按当前游戏告诉它。
     */
    private volatile String layout = "pad";

    /**
     * 换游戏了,把新键位推给已经连着的手柄。
     *
     * 不推的话手机上还举着上一台机器的键 —— 从拳皇切到魂斗罗,C/D 两颗按了没反应,
     * 而 A/B 的字还是反的。
     */
    public void setLayout(String name) {
        if (name == null || name.equals(layout)) return;
        layout = name;
        // **写 socket 必须离开主线程。** 这个方法是换游戏时从主线程调的
        // (见 GameActivity.play),直接写会吃一个 NetworkOnMainThreadException ——
        // 而它长得跟"没连手柄"一模一样:键位悄悄没换成,界面上一点动静都没有。
        // 换游戏才发一次,起个短命线程最省事
        new Thread(() -> {
            int sent = 0;
            for (Object o : owner) {
                if (!(o instanceof Socket)) continue;
                try {
                    sendText(((Socket) o).getOutputStream(), "L" + name);
                    sent++;
                } catch (Exception e) {
                    Log.w(TAG, "推键位失败: " + e);
                }
            }
            Log.i(TAG, "键位换成 " + name + ",推给了 " + sent + " 个手柄");
        }, "pad-layout").start();
    }

    public void start() {
        if (running) return;
        running = true;
        // IP 在后台取一次就存着,主线程只读缓存(见 localIp 的注释)
        new Thread(() -> {
            long t0 = System.currentTimeMillis();
            cachedIp = queryLocalIp();
            ipCostMs = System.currentTimeMillis() - t0;
            Log.i(TAG, "取本机 IP 耗时 " + ipCostMs + " ms -> " + cachedIp);
        }, "pad-ip").start();
        new Thread(this::accept, "pad-accept").start();
    }

    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        // 已建立的连接不跟着关的话,pump 线程会一直阻塞在 read 上等手机端断开 ——
        // 每次进出游戏中心都遗留一个僵尸线程,8 个槽被占满后新手机就再也连不上。
        // 统一关掉后阻塞中的 read 立刻返回,serve 的 finally 会收尾。
        List<Socket> stale;
        synchronized (clientsLock) { stale = new ArrayList<>(clients); }
        for (Socket s : stale) {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    private void accept() {
        try {
            server = new ServerSocket(port);
            Log.i(TAG, "监听 " + port);
            while (running) {
                Socket s = server.accept();
                if (conns.get() >= MAX_CONN) {     // 挡住重连风暴,别把线程堆爆
                    Log.w(TAG, "连接数超限,拒绝 " + s.getInetAddress());
                    try { s.close(); } catch (IOException ignored) { }
                    continue;
                }
                // 每个连接一个线程。手柄场景就一个客户端,不值得上线程池。
                conns.incrementAndGet();
                new Thread(() -> {
                    try { serve(s); } finally { conns.decrementAndGet(); }
                }, "pad-conn").start();
            }
        } catch (IOException e) {
            if (running) Log.e(TAG, "accept 失败", e);
        }
    }

    private void serve(Socket sock) {
        synchronized (clientsLock) { clients.add(sock); }
        try {
            sock.setTcpNoDelay(true);       // 手柄是小包高频,Nagle 会把延迟拖上去
            InputStream in = sock.getInputStream();
            OutputStream out = sock.getOutputStream();

            String reqLine = null, wsKey = null, line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                if (reqLine == null) reqLine = line;
                int c = line.indexOf(':');
                if (c > 0 && "sec-websocket-key".equalsIgnoreCase(line.substring(0, c).trim())) {
                    wsKey = line.substring(c + 1).trim();
                }
            }
            if (reqLine == null) return;

            if (wsKey != null) {
                // 玩家号从请求行里的 ?p=N 取:扫哪个二维码就是哪号玩家。
                // 不用"先到先得" —— 四个人围着车机屏,谁是 P1 该他们自己挑,不是看谁手快
                int port = portOf(reqLine);
                handshake(out, wsKey);
                // 一连上就告诉它该摆哪套键位 —— 手机页自己不知道车机上开的是哪台机器
                sendText(out, "L" + layout);
                owner[port] = sock;          // 认人:这个口现在归这条连接
                online[port] = true;
                notifyPlayers();
                Log.i(TAG, "P" + (port + 1) + " 连上了 " + sock.getInetAddress().getHostAddress());
                try {
                    pump(in, out, port);
                } finally {
                    // **只有还是这个口的当前占用者才收拾**。断线重连时旧连接的这段
                    // 可能跑在新连接建立**之后** —— 无脑清会把刚连上的那个标成离线,
                    // 还顺手松掉他正按着的键(实测见过 P1 明明连着却显示灰的)
                    if (owner[port] == sock) {
                        owner[port] = null;
                        online[port] = false;
                        Retro.nativeReleaseAll(port);
                        notifyPlayers();
                        Log.i(TAG, "P" + (port + 1) + " 断开");
                    } else {
                        Log.i(TAG, "P" + (port + 1) + " 的旧连接退场(已被新连接接管)");
                    }
                }
            } else {
                serveHtml(out);
            }
        } catch (Exception e) {
            Log.w(TAG, "连接处理结束: " + e);
        } finally {
            synchronized (clientsLock) { clients.remove(sock); }
            try { sock.close(); } catch (IOException ignored) { }
        }
    }

    private void handshake(OutputStream out, String key) throws Exception {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] digest = sha1.digest((key + WS_GUID).getBytes("UTF-8"));
        String accept = android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP);
        out.write(("HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes("UTF-8"));
        out.flush();
    }

    /** 读帧循环。只认文本帧,收到就解析成按键状态。 */
    private void pump(InputStream in, OutputStream out, int port) throws IOException {
        while (running) {
            int b0 = in.read();
            if (b0 < 0) return;
            int opcode = b0 & 0x0F;
            int b1 = in.read();
            if (b1 < 0) return;
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7F;
            if (len == 126) {
                len = ((long) in.read() << 8) | in.read();
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) len = (len << 8) | in.read();
            }
            byte[] mask = new byte[4];
            if (masked) readFully(in, mask, 4);
            byte[] payload = new byte[(int) len];
            readFully(in, payload, (int) len);
            if (masked) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];

            if (opcode == 0x8) return;                    // close
            if (opcode != 0x1) continue;                  // 只处理文本
            onMessage(new String(payload, "UTF-8"), out, port);
        }
    }

    /**
     * 协议一共这几个字:
     *   "D3" 按下 START、"U3" 抬起 —— 键号就是 RETRO_DEVICE_ID_JOYPAD_*
     *   "F0" 连发按下、"f0" 连发抬起 —— 那颗键交给车机每 6 帧翻一次(见 Retro.nativeTurbo)
     *   "P<毫秒>" 探测延迟,原样回显,由手机自己算往返
     *
     * 每个键各发各的,**天然支持同时按住多个** —— 组合键(A+B 必杀)在手机那侧
     * 就是一颗键连着发两条 D,这边不用知道有"组合"这回事。
     */
    private void onMessage(String msg, OutputStream out, int port) {
        if (msg.length() < 2) return;
        char c = msg.charAt(0);
        if (c == 'P') {
            try { sendText(out, msg); } catch (IOException ignored) { }
            return;
        }
        if (c == 'C') {                       // 功能键:暂停/存档/金手指…
            CommandListener l = commandListener;
            if (l != null) l.onCommand(msg.charAt(1), msg.substring(2));
            return;
        }
        if (c != 'D' && c != 'U' && c != 'F' && c != 'f') return;
        try {
            int id = Integer.parseInt(msg.substring(1).trim());
            if (c == 'F' || c == 'f') Retro.nativeTurbo(port, id, c == 'F');
            else Retro.nativeKey(port, id, c == 'D');
        } catch (NumberFormatException ignored) { }
    }

    /**
     * 从 {@code GET /?p=2 HTTP/1.1} 里取玩家号,取不到算 P1。
     *
     * 越界的一律夹回 0~3:这个值直接当数组下标用,而它来自网络。
     */
    private static int portOf(String requestLine) {
        try {
            int q = requestLine.indexOf("p=");
            if (q < 0) return 0;
            int end = q + 2;
            while (end < requestLine.length() && Character.isDigit(requestLine.charAt(end))) end++;
            int n = Integer.parseInt(requestLine.substring(q + 2, end));
            return Math.max(0, Math.min(3, n - 1));
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 手机按了功能键(暂停/存档/金手指…)。
     *
     * **只带一个字母,不带参数** —— 具体做什么由车机那侧决定(见 GameActivity.onPadCommand)。
     * 金手指那条例外,后面跟一个下标。
     */
    public interface CommandListener { void onCommand(char cmd, String arg); }
    private volatile CommandListener commandListener;
    public void setCommandListener(CommandListener l) { commandListener = l; }

    /**
     * 给所有连着的手柄推一条文本(状态、金手指表)。
     *
     * **必须离开主线程**:调用方多半是主线程(按键回调、状态同步),直接写 socket
     * 会吃 NetworkOnMainThreadException,而它长得跟"没连手柄"一模一样 —— setLayout
     * 那儿踩过一次,这里照抄短命线程的做法。
     */
    public void broadcast(String text) {
        if (text == null || text.isEmpty()) return;
        final Object[] snapshot = owner.clone();
        new Thread(() -> {
            for (Object o : snapshot) {
                if (!(o instanceof Socket)) continue;
                try {
                    sendText(((Socket) o).getOutputStream(), text);
                } catch (Exception ignored) { }
            }
        }, "pad-cast").start();
    }

    /** 哪几个玩家在线。UI 那边靠它把二维码格子点亮。 */
    private final boolean[] online = new boolean[4];
    /** 每个口当前归哪条连接 —— 断线重连时用它分辨"是不是我自己该退场"。 */
    private final Object[] owner = new Object[4];

    public boolean[] online() { return online; }

    public interface PlayersListener { void onPlayersChanged(boolean[] online); }

    private volatile PlayersListener playersListener;

    public void setPlayersListener(PlayersListener l) { playersListener = l; }

    private void notifyPlayers() {
        PlayersListener l = playersListener;
        if (l != null) l.onPlayersChanged(online);
    }

    /** 服务端发文本帧:不掩码,长度只可能是小包。 */
    private static synchronized void sendText(OutputStream out, String s) throws IOException {
        byte[] data = s.getBytes("UTF-8");
        out.write(0x81);
        if (data.length < 126) {
            out.write(data.length);
        } else {
            out.write(126);
            out.write((data.length >> 8) & 0xFF);
            out.write(data.length & 0xFF);
        }
        out.write(data);
        out.flush();
    }

    private void serveHtml(OutputStream out) throws IOException {
        byte[] body = readAsset("game/pad.html");
        out.write(("HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n\r\n").getBytes("UTF-8"));
        out.write(body);
        out.flush();
    }

    private byte[] readAsset(String name) throws IOException {
        try (InputStream is = ctx.getAssets().open(name)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    private static void readFully(InputStream in, byte[] buf, int len) throws IOException {
        int done = 0;
        while (done < len) {
            int n = in.read(buf, done, len - done);
            if (n < 0) throw new IOException("流提前结束");
            done += n;
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
        }
        return (c < 0 && sb.length() == 0) ? null : sb.toString();
    }

    /**
     * 车机自己的局域网地址 —— 手机要访问的就是它。
     * 车机连手机热点时是 192.168.43.x 这种,得跳过回环和未启用的接口。
     *
     * **必须缓存,绝不能每帧/每秒调。** 这个调用要枚举网络接口(走 netlink 问内核),
     * 真车上慢到能把主线程堵死 —— 我把它放进每秒一次的 UI 刷新里,直接卡死了车机。
     * IP 在一次会话里不会变,取一次就够。
     */
    private static volatile String cachedIp;
    /** 取 IP 花了多久 —— 这正是把主线程堵死的那个调用,量出来才好确认根因。 */
    public static volatile long ipCostMs = -1;

    /** 非阻塞:没取到就先返回占位,绝不在调用方(可能是主线程)上做枚举。 */
    public static String localIp() {
        String ip = cachedIp;
        return ip != null ? ip : "获取中…";
    }

    private static String queryLocalIp() {
        try {
            for (Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();
                 e.hasMoreElements(); ) {
                NetworkInterface ni = e.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (Enumeration<InetAddress> a = ni.getInetAddresses(); a.hasMoreElements(); ) {
                    InetAddress addr = a.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "取本机 IP 失败", e);
        }
        return "?";
    }

    public String url() {
        return String.format(Locale.US, "http://%s:%d", localIp(), port);
    }
}
