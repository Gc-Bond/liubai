package com.liubai.blank.privileged.adb;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 一条 adb 流,对应一次 "shell:xxx" 之类的服务调用。
 *
 * 数据由 AdbConnection 的读线程投递进来(feed),使用方在自己的线程上阻塞取。
 * adb 是全双工多路复用的:同一个 socket 上可以同时开多条流,靠 localId/remoteId 区分。
 */
public final class AdbStream implements Closeable {

    private final AdbConnection conn;
    private final int localId;
    private volatile int remoteId;

    private final LinkedBlockingQueue<byte[]> incoming = new LinkedBlockingQueue<>();
    private final Object writeLock = new Object();

    private volatile boolean closed;
    /** 对端是否已确认可以继续发(adb 是发一包等一个 OKAY 的节奏)。 */
    private volatile boolean writeReady;

    AdbStream(AdbConnection conn, int localId) {
        this.conn = conn;
        this.localId = localId;
    }

    int localId() { return localId; }

    /** 对端已回 OKAY、remoteId 到手,流才算真开成功。adb 的 id 从 1 起,0 即未就绪。 */
    boolean ready() { return remoteId != 0; }

    void onReady(int remoteId) {
        this.remoteId = remoteId;
        synchronized (writeLock) {
            writeReady = true;
            writeLock.notifyAll();
        }
    }

    void feed(byte[] data) {
        if (data != null && data.length > 0) incoming.offer(data);
    }

    void onClosed() {
        closed = true;
        incoming.offer(new byte[0]);   // 叫醒可能正卡在 read 上的线程
        synchronized (writeLock) { writeLock.notifyAll(); }
    }

    public boolean isClosed() { return closed; }

    /**
     * 读一段输出。返回 null 表示流已结束。
     *
     * @param timeoutMs 等待上限;超时返回长度为 0 的数组,调用方可据此决定继续等还是放弃
     */
    public byte[] read(long timeoutMs) throws InterruptedException {
        byte[] data = incoming.poll(timeoutMs, TimeUnit.MILLISECONDS);
        if (data == null) return new byte[0];
        if (data.length == 0 && closed) return null;
        return data;
    }

    /** 一直读到流关闭,把全部输出拼起来——执行一条命令拿结果就用这个。 */
    public String readAll(long timeoutMs) throws InterruptedException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) break;
            byte[] chunk = read(left);
            if (chunk == null) break;              // 正常结束
            if (chunk.length > 0) buf.write(chunk, 0, chunk.length);
        }
        return new String(buf.toByteArray());
    }

    /** 往流里写(交互式 shell 的输入)。会等对端 OKAY,这是 adb 的流控。 */
    public void write(byte[] data) throws IOException, InterruptedException {
        if (closed) throw new IOException("流已关闭");
        synchronized (writeLock) {
            while (!writeReady && !closed) writeLock.wait(5000);
            if (closed) throw new IOException("流已关闭");
            writeReady = false;
        }
        conn.sendRaw(AdbProtocol.write(localId, remoteId, data));
    }

    public void write(String s) throws IOException, InterruptedException {
        write(s.getBytes());
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            conn.sendRaw(AdbProtocol.close(localId, remoteId));
        } catch (Exception ignored) {
            // 连接本来就断了,没必要再吵
        }
        conn.removeStream(localId);
    }
}
