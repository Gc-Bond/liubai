package com.liubai.blank.privileged.adb;

import android.util.Log;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 车机连自己的 adb —— 提权的入口。
 *
 * 我们扮演「电脑」那一端,连本机 127.0.0.1:5555。认证通过后开出来的 shell 流
 * 就是 shell(uid 2000) 身份,拿到的正是画中画跨屏启动、事件注入所需要的那些权限。
 * 全程不 root、不改系统分区。
 *
 * 认证的关键顺序(错了就永远连不上,且设备不给任何提示):
 *   1. 发 CNXN
 *   2. 设备回 AUTH(TOKEN) + 20 字节随机数 → 用私钥签,回 AUTH(SIGNATURE)
 *   3. 设备**再发一次** AUTH(TOKEN) = 上一步的签名它不认(没见过这把钥匙)
 *      → 这时才发 AUTH(RSAPUBLICKEY),设备弹出「允许 USB 调试吗?」
 *   4. 用户点允许 → **设备不会在这条连接上回任何东西**,得重连
 *
 * 第 4 步是真车上踩出来的(2026-08-03):AdbDebuggingManager 在用户点「允许」之后
 * 只把公钥写进 adb_keys 并让 adbd 重载,那条已经在等的旧连接它不管 —— adb 客户端
 * 本来就是断开重连的。实测用户在第 3 秒就点了允许,连接却一直阻塞到 120 秒超时,
 * 白等两分钟,全靠上层重试才连上。所以公钥发出后**只短等一下就让出去重连**,
 * 见 {@link #AUTH_PENDING_WAIT_MS}。
 */
public final class AdbConnection implements Closeable {

    private static final String TAG = "Blank/Adb";

    /**
     * 认证等待上限。**只覆盖网络往返**(发 CNXN → 收 TOKEN → 回签名 → 收结果),
     * 本机 loopback 上这几个来回是毫秒级的,10 秒已经很宽。
     *
     * 早先是 120 秒,那是按"要等用户看到弹框并点允许"估的 —— 而用户点完这条连接
     * 根本等不到东西(见类注释第 4 步),那两分钟纯属空耗。等授权改由
     * {@link #AUTH_PENDING_WAIT_MS} + 上层重连来覆盖。
     */
    public static final long AUTH_TIMEOUT_MS = 10_000;

    /**
     * 公钥发出之后再等多久就放弃、交给上层重连。
     *
     * 绝大多数情况这一等是等不到回包的(设备在等用户点按钮,而且点完也不回这条连接),
     * 所以给得很短 —— 用户点「允许」到 Dock 浮出来的时间,基本就等于上层的重连间隔。
     * 留 3 秒而不是 0,是因为**少数 ROM 确实会在旧连接上补一个 CNXN**,
     * 那种设备上这一等就直接连上了,不必多跑一轮重连。
     */
    private static final long AUTH_PENDING_WAIT_MS = 3_000;

    private static final int CONNECT_TIMEOUT_MS = 3_000;

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    private final Map<Integer, AdbStream> streams = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);

    private volatile boolean running;
    private volatile int maxData = AdbProtocol.MAX_PAYLOAD;
    private Thread reader;

    private AdbConnection(Socket socket) throws IOException {
        this.socket = socket;
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
    }

    /**
     * 连上并完成认证。公钥该不该发由调用方定,见
     * {@link #connect(String, int, AdbCrypto, long, boolean)}。
     */
    public static AdbConnection connect(String host, int port, AdbCrypto crypto, long timeoutMs)
            throws Exception {
        return connect(host, port, crypto, timeoutMs, true);
    }

    /**
     * 连上并完成认证。
     *
     * @param sendPublicKey 签名不被接受时,要不要发公钥(= 让设备弹授权框)。
     *
     * <p><b>探测模式(false)是"点完立刻生效"的关键。</b>弹框已经摆在用户面前之后,
     * 再连只是想知道"他点了没",而这件事只看签名认不认:
     * <ul>
     *   <li>签名被接受 → 直接回 CNXN → 用户已经点了,当场就绪;</li>
     *   <li>又要我发公钥 → 还没点 → <b>立刻返回</b>,不发、不等。</li>
     * </ul>
     * 一轮探测就是一次本机 loopback 往返(几十毫秒),所以可以几百毫秒轮一次,
     * 用户点下去到 Dock 浮出来基本无感。而且不发公钥就<b>不会再弹一次框</b>。
     *
     * <p>要是每轮都照常发公钥,就得为"设备可能回 CNXN"再等满
     * {@link #AUTH_PENDING_WAIT_MS},一轮下来好几秒 —— 那才是之前那种"点完还要干等"。
     *
     * @throws AuthPendingException 设备在等用户授权(探测模式下:还没点)
     */
    public static AdbConnection connect(String host, int port, AdbCrypto crypto, long timeoutMs,
                                        boolean sendPublicKey) throws Exception {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        socket.setTcpNoDelay(true);

        AdbConnection conn = new AdbConnection(socket);
        try {
            conn.authenticate(crypto, timeoutMs, sendPublicKey);
            conn.startReader();
            return conn;
        } catch (Exception e) {
            conn.closeQuietly();
            throw e;
        }
    }

    /** 公钥已送达、就等用户在车机上点允许。 */
    public static final class AuthPendingException extends IOException {
        public AuthPendingException(String msg) { super(msg); }
    }

    private void authenticate(AdbCrypto crypto, long timeoutMs, boolean sendPublicKey)
            throws Exception {
        out.write(AdbProtocol.connect());
        out.flush();

        long deadline = System.currentTimeMillis() + timeoutMs;
        /** 签名已发出。再收到 TOKEN 就说明这把钥匙设备不认,该发公钥了。 */
        boolean signatureSent = false;
        /** 公钥已发出 —— 设备正在弹框等用户。和上面那个是两回事,合用一个标志会把两种超时混成一种。 */
        boolean publicKeySent = false;

        while (true) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) throw authTimeout(publicKeySent);
            // 剩余时间即读超时,免得卡死在 read 上
            socket.setSoTimeout((int) Math.min(left, Integer.MAX_VALUE));

            AdbProtocol.Message m;
            try {
                m = AdbProtocol.read(in);
            } catch (java.net.SocketTimeoutException e) {
                /*
                 * read 自己超时时直接抛出来,**回不到上面那个 left <= 0 的分支** ——
                 * 所以在这儿翻译一次,否则"在等用户授权"会被当成普通网络故障:
                 * AuthPendingException 因此一直是死代码,车主看到的是
                 * "连接失败: 等车机上的授权弹窗超时",而他明明已经点过了。
                 */
                throw authTimeout(publicKeySent);
            }

            if (m.command == AdbProtocol.CMD_CNXN) {
                maxData = m.arg1 > 0 ? Math.min(m.arg1, AdbProtocol.MAX_PAYLOAD) : 4096;
                Log.i(TAG, "adb 已连接: " + m.text().trim() + " maxData=" + maxData);
                socket.setSoTimeout(0);   // 之后是长连接,不再设读超时
                return;
            }

            if (m.command == AdbProtocol.CMD_AUTH && m.arg0 == AdbProtocol.AUTH_TOKEN) {
                if (!signatureSent) {
                    // 先试已有的钥匙。设备记得这把就直接过,用户完全无感。
                    byte[] signed = crypto.signToken(m.payload);
                    out.write(AdbProtocol.auth(AdbProtocol.AUTH_SIGNATURE, signed));
                    out.flush();
                    signatureSent = true;   // 下一次再收到 TOKEN 就说明签名没被接受
                } else if (sendPublicKey) {
                    // 签名不被认 = 这是第一次授权。发公钥,设备弹框给用户看指纹。
                    Log.i(TAG, "首次授权,发送公钥,等待车机上确认");
                    out.write(AdbProtocol.auth(AdbProtocol.AUTH_RSAPUBLICKEY, crypto.toAdbPublicKey()));
                    out.flush();
                    publicKeySent = true;
                    // 从这儿起就别再赖着了:点完也不会回这条连接,短等一下交给上层重连
                    deadline = System.currentTimeMillis() + AUTH_PENDING_WAIT_MS;
                } else {
                    // 探测模式:公钥早发过了(框正开着),签名还是不认就说明用户还没点。
                    // 立刻回去,别等 —— 这一轮的意义就是"问一句",几十毫秒的事
                    throw new AuthPendingException("用户尚未确认");
                }
                continue;
            }

            Log.w(TAG, "认证期间收到意外报文: " + Integer.toHexString(m.command));
        }
    }

    /**
     * 认证超时分两种,调用方要靠它们区别对待:公钥已送达是"等用户点"(上层短间隔重连即可),
     * 没送达才是真的连不通(该退避)。
     */
    private static Exception authTimeout(boolean publicKeySent) {
        return publicKeySent
                ? new AuthPendingException("公钥已送达,等车机上点「允许」——点完由重连接上")
                : new IOException("adb 认证超时");
    }

    private void startReader() {
        running = true;
        reader = new Thread(new Runnable() {
            @Override public void run() { readLoop(); }
        }, "blank-adb-reader");
        reader.setDaemon(true);
        reader.start();
    }

    /** 唯一的读线程:把报文按 id 分发给各条流。 */
    private void readLoop() {
        try {
            while (running) {
                AdbProtocol.Message m = AdbProtocol.read(in);
                // 对端发来的报文里,arg1 是我们这边的 localId
                AdbStream s = streams.get(m.arg1);

                switch (m.command) {
                    case AdbProtocol.CMD_OKAY:
                        if (s != null) s.onReady(m.arg0);
                        break;
                    case AdbProtocol.CMD_WRTE:
                        if (s != null) s.feed(m.payload);
                        // OKAY 必须回,**哪怕这条流本地已经不在了**。
                        //
                        // 流是我们主动 close 掉的(readAll 读够了就关),但 adbd 那边
                        // 可能还有没发完的输出——长输出的命令(am stack list 好几 KB)必然如此。
                        // 那些 WRTE 落到这里时 s 已经是 null;早先的写法就此不回 OKAY,
                        // adbd 就一直等我们确认,直到它自己超时:
                        //   adbd: timeout expired while flushing socket, closing
                        // **整条 adb 连接被关掉**,之后所有 exec 全部报"打开流失败",
                        // 而且要等下一次重连才恢复——表现为提权功能时灵时不灵。
                        sendRaw(AdbProtocol.ready(m.arg1, m.arg0));
                        break;
                    case AdbProtocol.CMD_CLSE:
                        if (s != null) {
                            s.onClosed();
                            streams.remove(m.arg1);
                            // 被动关闭要回一个 CLSE 把握手做完。不回的话 adbd 那边的
                            // local socket 一直半开着等确认,攒到一定程度整个 transport
                            // 的写队列就堵死——症状是新开流的 OPEN 再也等不到 OKAY,
                            // exec 全部报"打开流失败",而 socket 本身还连着(isConnected
                            // 仍为 true),所以连重连都不会触发。
                            //
                            // s == null 说明是我们自己先 close() 的(那时已 removeStream),
                            // 这条 CLSE 是对端的回执,不用再回,否则两边来回弹。
                            sendRaw(AdbProtocol.close(m.arg1, m.arg0));
                        }
                        break;
                    default:
                        break;
                }
            }
        } catch (Exception e) {
            if (running) Log.w(TAG, "adb 读线程结束: " + e.getMessage());
        } finally {
            running = false;
            for (AdbStream s : streams.values()) s.onClosed();
            streams.clear();
        }
    }

    /** 开流要排队,见 open() 里的说明。 */
    private final Object openLock = new Object();

    /**
     * 开一条流。destination 例如 "shell:am start --display 5 -n pkg/cls"。
     * 带命令的 shell 执行完会自动关流;"shell:" 则是交互式,一直开着。
     *
     * **一次只开一条**。连接本身是多路复用的,开着的流可以有很多条,但"开"这个
     * 动作并发做会被 adbd 拿 CLSE 直接回绝(实测:主线程正给画中画验落点,后台线程
     * 同时在探前台 app,两条 OPEN 撞一起,4ms 就被回绝,连重试也照撞)。
     * 排队的代价很小——这些都是跑完就退的短命令。
     */
    public AdbStream open(String destination) throws IOException, InterruptedException {
        synchronized (openLock) {
            if (!running) throw new IOException("adb 未连接");

            int id = nextId.getAndIncrement();
            AdbStream stream = new AdbStream(this, id);
            streams.put(id, stream);

            sendRaw(AdbProtocol.open(id, destination));

            // 等对端的 OKAY(带回 remoteId)才算开成功
            long deadline = System.currentTimeMillis() + 5_000;
            while (!stream.isClosed() && System.currentTimeMillis() < deadline) {
                if (stream.ready()) return stream;
                Thread.sleep(10);
            }
            streams.remove(id);
            throw new IOException("打开流失败: " + destination);
        }
    }

    /** 执行一条命令,拿它的全部输出。 */
    public String exec(String command, long timeoutMs) throws IOException, InterruptedException {
        AdbStream s;
        try {
            s = open("shell:" + command);
        } catch (IOException first) {
            // adbd 偶尔会直接拿 CLSE 回绝一次 OPEN(实测在并发开流、它自己正忙于
            // pkill/app_process 时会撞上,4ms 就失败了,不是超时)。隔一下重来基本就成——
            // 比让上层拿着空结果去做决策强:查不到前台 app 只会静默退化成全屏打开,
            // 用户看到的是"这次怎么没进画中画",很难联想到是通道抖了一下。
            Log.w(TAG, "开流被回绝,重试一次: " + command);
            Thread.sleep(150);
            s = open("shell:" + command);
        }
        try {
            return s.readAll(timeoutMs);
        } finally {
            s.close();
        }
    }

    public boolean isConnected() { return running && !socket.isClosed(); }

    public int maxData() { return maxData; }

    synchronized void sendRaw(byte[] packet) throws IOException {
        out.write(packet);
        out.flush();
    }

    void removeStream(int localId) {
        streams.remove(localId);
    }

    @Override
    public void close() {
        running = false;
        closeQuietly();
    }

    private void closeQuietly() {
        try { socket.close(); } catch (Exception ignored) { }
    }
}
