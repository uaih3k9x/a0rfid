package com.rfid.helper;

import java.util.ArrayList;
import java.util.List;

/**
 * R2000 协议（UCCHIP 桌面发卡器，固件 V2.4.1 真机验证）
 * 帧: A0 | LEN | ADDR | CMD | DATA... | SUM   （SUM = 8位二补数校验和）
 * READ(0x81):  {mb, addr×4, cnt×2, pwd×4}                — IDA 逆向 libnative-lib.so case129
 * WRITE(0x82): {pwd×4, mb, addr×4, cnt×2, data}          — case130
 * 响应 CMD 不带 0x80 位；本固件 READ/WRITE 状态 0x00 成功，控制指令 0x10 成功。
 */
public final class RfidProto {
    public static final byte ADDR = 0x00;
    public static final int BAUD = 115200;

    public static final int CMD_RESET            = 0x70;
    public static final int CMD_GET_FW_VERSION   = 0x72;
    public static final int CMD_SET_WORK_ANT     = 0x74;
    public static final int CMD_GET_WORK_ANT     = 0x75;
    public static final int CMD_SET_OUTPUT_POWER = 0x76;
    public static final int CMD_GET_OUTPUT_POWER = 0x77;
    public static final int CMD_GET_FREQ_REGION  = 0x79;
    public static final int CMD_READ             = 0x81;
    public static final int CMD_WRITE            = 0x82;
    public static final int CMD_LOCK             = 0x83;
    public static final int CMD_KILL             = 0x84;
    public static final int CMD_REAL_TIME_INV    = 0x89;
    public static final int CMD_STOP_INV         = 0x8C;

    public static final int OK = 0x10;
    public static final String[] BANKS = {"RESERVED", "EPC", "TID", "USER"};

    private RfidProto() {}

    public static int checksum(byte[] f, int len) {
        int s = 0;
        for (int i = 0; i < len; i++) s += (f[i] & 0xFF);
        return (-s) & 0xFF;
    }

    public static byte[] encode(int cmd, byte[] data) {
        byte[] f = new byte[data.length + 5];
        f[0] = (byte) 0xA0;
        f[1] = (byte) (data.length + 3);
        f[2] = ADDR;
        f[3] = (byte) cmd;
        System.arraycopy(data, 0, f, 4, data.length);
        f[f.length - 1] = (byte) checksum(f, f.length - 1);
        return f;
    }

    /** 单帧解析：校验失败/不完整返回 null */
    public static int[] parseFrame(byte[] buf, int off, int len) {
        int i = frameStart(buf, off, len);
        if (i < 0) return null;
        int ln = buf[i + 1] & 0xFF;
        int[] out = new int[ln - 2];
        out[0] = buf[i + 3] & 0xFF;
        for (int j = 0; j < ln - 3; j++) out[1 + j] = buf[i + 4 + j] & 0xFF;
        return out;
    }

    private static int frameStart(byte[] buf, int off, int len) {
        for (int i = off; i < len; i++) {
            if (buf[i] != (byte) 0xA0) continue;
            if (i + 1 >= len) return -1;
            int ln = (buf[i + 1] & 0xFF);
            if (ln < 3) continue;
            if (i + ln + 2 > len) return -1;
            int sum = 0;
            for (int j = i; j < i + ln + 2; j++) sum += buf[j] & 0xFF;
            if ((sum & 0xFF) == 0) return i;
        }
        return -1;
    }

    /** 帧总长（用于消费缓冲） */
    public static int frameLen(byte[] buf, int off) {
        int start = frameStart(buf, off, buf.length);
        return start < 0 ? 0 : start - off + (buf[start + 1] & 0xFF) + 2;
    }

    public static byte[] buildRead(int bank, int addr, int wordCnt, byte[] pwd) {
        byte[] d = new byte[11];
        d[0] = (byte) bank;
        d[1] = (byte) (addr >> 24); d[2] = (byte) (addr >> 16); d[3] = (byte) (addr >> 8); d[4] = (byte) addr;
        d[5] = (byte) (wordCnt >> 8); d[6] = (byte) wordCnt;
        System.arraycopy(pwd, 0, d, 7, 4);
        return d;
    }

    public static byte[] buildWrite(int bank, int addr, int wordCnt, byte[] pwd, byte[] data) {
        byte[] d = new byte[11 + data.length];
        System.arraycopy(pwd, 0, d, 0, 4);
        d[4] = (byte) bank;
        d[5] = (byte) (addr >> 24); d[6] = (byte) (addr >> 16); d[7] = (byte) (addr >> 8); d[8] = (byte) addr;
        d[9] = (byte) (wordCnt >> 8); d[10] = (byte) wordCnt;
        System.arraycopy(data, 0, d, 11, data.length);
        return d;
    }

    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X", x));
        return sb.toString();
    }

    public static String hex(int[] b, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) sb.append(String.format("%02X", b[i] & 0xFF));
        return sb.toString();
    }

    public static byte[] unhex(String s) {
        s = s.replaceAll("[^0-9a-fA-F]", "");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++)
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    /** READ 数据帧（真机 V2.4.1 实测）解析。
     * fr = {cmd, 状态, 天线, 信号, PC×2, EPC×12, CRC×2, 读出数据×N, N×2(BE), 01 01}
     * 状态 0x00=成功；返回 null 表示失败，成功返回读出数据 */
    public static byte[] parseReadData(int[] fr) {
        if (fr == null || fr.length < 12 || fr[0] != CMD_READ || fr[1] != 0x00) return null;
        int n = fr.length;
        int dl = (fr[n - 4] << 8) | fr[n - 3];                     // 尾部长度字段
        int epcBytes = (((fr[4] << 8) | fr[5]) >>> 11) * 2;
        if (n - 4 - dl != 8 + epcBytes || (dl & 1) != 0) return null;
        byte[] out = new byte[dl];
        for (int i = 0; i < dl; i++) out[i] = (byte) fr[n - 4 - dl + i];
        return out;
    }

    public static String parseReadEpc(int[] fr) {
        if (parseReadData(fr) == null) return null;
        int epcBytes = (((fr[4] << 8) | fr[5]) >>> 11) * 2;
        return hex(fr, 6, 6 + epcBytes);
    }

    static boolean isReadAck(int[] fr) {
        return fr != null && fr.length == 2 && fr[0] == CMD_READ
                && (fr[1] == 0x00 || fr[1] == OK);
    }

    public static String statusText(int st) {
        switch (st) {
            case 0x00: return "成功";
            case 0x10: return "成功";
            case 0x11: return "命令失败/场内无标签";
            case 0x20: return "CPU复位错误";
            case 0x23: return "收发缓存溢出";
            case 0x24: return "设置频段失败";
            case 0x43: return "存储区不存在或地址越界";
            case 0x50: return "获取RN16失败";
            default: return "0x" + Integer.toHexString(st);
        }
    }

    /** 盘存标签帧 data: [antenna, PC×2, EPC..., ×4, freq×3] */
    public static class Tag {
        public String epc, pc;
        public int antenna, freqKhz, count = 1;
    }

    public static Tag parseTag(int[] data) {
        if (data.length < 13) return null;
        Tag t = new Tag();
        int n = data.length;                 // data[0]=cmd; data[1]=antenna; [2,4)=PC; [4,n-7)=EPC; 尾3字节=频率
        t.antenna = data[1];
        t.pc = hex(data, 2, 4);
        t.epc = hex(data, 4, n - 7);
        t.freqKhz = (data[n - 3] << 16) | (data[n - 2] << 8) | data[n - 1];
        return t;
    }
}
