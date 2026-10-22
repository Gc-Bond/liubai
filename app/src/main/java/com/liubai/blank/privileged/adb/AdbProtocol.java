package com.liubai.blank.privileged.adb;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * adb 有线协议的报文编解码。
 *
 * 每个报文 = 24 字节定长头 + payload,全部小端:
 *   command(4) arg0(4) arg1(4) data_length(4) data_crc32(4) magic(4)
 *
 * 两个地方错一个字节设备就直接断连、且不给任何错误提示,调起来很痛苦:
 *   1. magic 必须严格等于 command ^ 0xFFFFFFFF
 *   2. data_crc32 是 payload 每个字节按**无符号**累加(不是 CRC32,名字是历史遗留)
 */
public final class AdbProtocol {

    // 命令常量,值就是 4 个 ASCII 字符的小端拼装("CNXN"/"AUTH"/...)
    public static final int CMD_SYNC = 0x434e5953;
    public static final int CMD_CNXN = 0x4e584e43;
    public static final int CMD_AUTH = 0x48545541;
    public static final int CMD_OPEN = 0x4e45504f;
    public static final int CMD_OKAY = 0x59414b4f;
    public static final int CMD_CLSE = 0x45534c43;
    public static final int CMD_WRTE = 0x45545257;

    // AUTH 的 arg0:设备发 TOKEN,我们回 SIGNATURE;签名不被认时再发 RSAPUBLICKEY 触发授权弹框
    public static final int AUTH_TOKEN = 1;
    public static final int AUTH_SIGNATURE = 2;
    public static final int AUTH_RSAPUBLICKEY = 3;

    /** 协议版本。设备会在 CNXN 回包里带自己的版本,取双方较小的。 */
    public static final int VERSION = 0x01000000;
    /** 单个报文 payload 上限。老设备是 4096,握手后按设备回报的值收窄。 */
    public static final int MAX_PAYLOAD = 256 * 1024;

    private static final int HEADER_LENGTH = 24;

    private AdbProtocol() {}

    /** 一个收到的报文。 */
    public static final class Message {
        public final int command, arg0, arg1;
        public final byte[] payload;

        Message(int command, int arg0, int arg1, byte[] payload) {
            this.command = command;
            this.arg0 = arg0;
            this.arg1 = arg1;
            this.payload = payload;
        }

        /** payload 当字符串读(shell 输出、CNXN 的设备标识都是文本)。 */
        public String text() {
            return payload == null ? "" : new String(payload);
        }
    }

    public static byte[] generate(int command, int arg0, int arg1, byte[] payload) {
        int len = payload == null ? 0 : payload.length;
        ByteBuffer buf = ByteBuffer.allocate(HEADER_LENGTH + len).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(command);
        buf.putInt(arg0);
        buf.putInt(arg1);
        buf.putInt(len);
        buf.putInt(checksum(payload));
        buf.putInt(~command);          // magic = command ^ 0xFFFFFFFF
        if (len > 0) buf.put(payload);
        return buf.array();
    }

    /**
     * 握手包。
     *
     * "host::" 后面是特性列表,可以为空;设备靠这个判断对端能力。
     * 身份必须是 host——我们扮演的是「电脑」那一端,不是被连的设备。
     */
    public static byte[] connect() {
        return generate(CMD_CNXN, VERSION, MAX_PAYLOAD, "host::features=shell_v2,cmd\0".getBytes());
    }

    public static byte[] auth(int type, byte[] data) {
        return generate(CMD_AUTH, type, 0, data);
    }

    /**
     * 打开一条流。destination 形如 "shell:ls -l"(带命令则执行完就关)
     * 或 "shell:"(交互式)。结尾的 \0 不能少。
     */
    public static byte[] open(int localId, String destination) {
        return generate(CMD_OPEN, localId, 0, (destination + "\0").getBytes());
    }

    public static byte[] ready(int localId, int remoteId) {
        return generate(CMD_OKAY, localId, remoteId, null);
    }

    public static byte[] write(int localId, int remoteId, byte[] data) {
        return generate(CMD_WRTE, localId, remoteId, data);
    }

    public static byte[] close(int localId, int remoteId) {
        return generate(CMD_CLSE, localId, remoteId, null);
    }

    /** 读一个完整报文,读不到就抛 IOException(连接断了)。 */
    public static Message read(InputStream in) throws IOException {
        byte[] header = readFully(in, HEADER_LENGTH);
        ByteBuffer buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);

        int command = buf.getInt();
        int arg0 = buf.getInt();
        int arg1 = buf.getInt();
        int length = buf.getInt();
        buf.getInt();                  // data_check,设备发来的不用校验
        int magic = buf.getInt();

        if (magic != ~command) {
            throw new IOException("报文头损坏:magic 对不上 command(流可能已错位)");
        }
        if (length < 0 || length > MAX_PAYLOAD) {
            throw new IOException("报文长度异常: " + length);
        }

        byte[] payload = length > 0 ? readFully(in, length) : new byte[0];
        return new Message(command, arg0, arg1, payload);
    }

    private static byte[] readFully(InputStream in, int size) throws IOException {
        byte[] out = new byte[size];
        int off = 0;
        while (off < size) {
            int n = in.read(out, off, size - off);
            if (n < 0) throw new IOException("连接已关闭(还差 " + (size - off) + " 字节)");
            off += n;
        }
        return out;
    }

    private static int checksum(byte[] payload) {
        if (payload == null) return 0;
        int sum = 0;
        for (byte b : payload) sum += (b & 0xFF);   // 必须按无符号累加
        return sum;
    }
}
