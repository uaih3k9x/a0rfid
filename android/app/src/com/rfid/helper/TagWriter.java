package com.rfid.helper;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Writes only the requested ranges and reports success only after readback. */
final class TagWriter {
    interface Transport {
        List<String> inventory();
        int[] read(int bank, int address, int words, byte[] password);
        int[] write(int bank, int address, byte[] data, byte[] password);
        boolean isConnected();
    }

    interface Progress { void update(String message); }

    static final class Item {
        final int bank;
        final byte[] data;
        Item(int bank, byte[] data) {
            this.bank = bank;
            this.data = data.clone();
        }
        int address() { return bank == 1 ? 2 : 0; }
    }

    static final class Result {
        final int bank;
        final boolean passed;
        final String message;
        Result(int bank, boolean passed, String message) {
            this.bank = bank;
            this.passed = passed;
            this.message = message;
        }
    }

    static final class Report {
        String target = "";
        String problem;
        final List<Result> results = new ArrayList<>();
        boolean passed() {
            if (problem != null || results.isEmpty()) return false;
            for (Result result : results) if (!result.passed) return false;
            return true;
        }
        String text(boolean checkOnly) {
            StringBuilder text = new StringBuilder(passed() ? (checkOnly ? "检查通过" : "写入及检查通过") : "未全部通过");
            if (!target.isEmpty()) text.append("\n目标 EPC: ").append(target);
            if (problem != null) text.append('\n').append(problem);
            for (Result result : results) text.append("\n\n").append(RfidProto.BANKS[result.bank])
                    .append(result.passed ? " ✓\n" : " ✕\n").append(result.message);
            return text.toString();
        }
    }

    static byte[] parseHex(String value, String label) {
        String hex = value.replaceAll("\\s+", "");
        if (hex.isEmpty() || !hex.matches("[0-9a-fA-F]+") || (hex.length() & 1) != 0)
            throw new IllegalArgumentException(label + " 须为完整 HEX 字节，可用空格或换行分隔");
        return RfidProto.unhex(hex);
    }

    static void validate(List<Item> items, byte[] password) {
        if (password == null || password.length != 4) throw new IllegalArgumentException("访问密码须为 8 位 HEX");
        if (items.isEmpty()) throw new IllegalArgumentException("请至少勾选一个存储区");
        Set<Integer> seen = new HashSet<>();
        for (Item item : items) {
            if (item.bank < 0 || item.bank > 3 || !seen.add(item.bank))
                throw new IllegalArgumentException("存储区无效或重复");
            int size = item.data.length;
            String name = RfidProto.BANKS[item.bank];
            if (size == 0 || (size & 1) != 0 || size > 4096)
                throw new IllegalArgumentException(name + " 须为偶数字节，最多 4096 字节");
            if (item.bank == 1 && size > 62) throw new IllegalArgumentException("EPC 最多 62 字节");
            if (item.bank == 0 && size != 8)
                throw new IllegalArgumentException("RESERVED 须为 8 字节：Kill 密码 4 字节 + Access 密码 4 字节");
        }
    }

    private static final class ReadFailure extends Exception {
        ReadFailure(String message) { super(message); }
    }

    private final Transport transport;
    TagWriter(Transport transport) { this.transport = transport; }

    Report run(List<Item> items, byte[] password, boolean checkOnly, Progress progress) {
        validate(items, password);
        Report report = new Report();
        if (!transport.isConnected()) { report.problem = "读写头未连接"; return report; }
        progress.update("正在确认目标标签，请保持只放一张卡");
        Set<String> detected;
        try { detected = new HashSet<>(transport.inventory()); }
        catch (RuntimeException e) { report.problem = "识别失败: " + e.getMessage(); return report; }
        if (detected.size() != 1) {
            report.problem = detected.isEmpty() ? "未发现标签，请放卡后重试" : "发现多张标签，请只保留目标卡";
            return report;
        }
        report.target = detected.iterator().next();
        String target = report.target;
        List<Item> ordered = new ArrayList<>();
        for (int bank : new int[]{2, 3, 1, 0})
            for (Item item : items) if (item.bank == bank) ordered.add(item);

        if (checkOnly) {
            for (Item item : ordered) {
                progress.update("检查 " + RfidProto.BANKS[item.bank]);
                try {
                    byte[] actual = read(item.bank, item.address(), item.data.length, password, target);
                    String diff = difference(item, actual);
                    // EPC must also have the right advertised length, even when its prefix matches.
                    if (diff == null && item.bank == 1 && !RfidProto.hex(item.data).equalsIgnoreCase(target))
                        diff = "EPC 数据前缀一致，但标签当前编号或长度与填写内容不符";
                    report.results.add(new Result(item.bank, diff == null, diff == null ? "内容一致（" + actual.length + " 字节）" : diff));
                } catch (ReadFailure e) {
                    report.results.add(new Result(item.bank, false, "无法检查: " + e.getMessage()));
                }
            }
            return report;
        }

        // Read every selected range first: capacity/authentication failures must precede writes.
        int pc = 0;
        try {
            for (Item item : ordered) {
                progress.update("写前检查 " + RfidProto.BANKS[item.bank]);
                read(item.bank, item.address(), item.data.length, password, target);
                if (item.bank == 1) {
                    byte[] oldPc = read(1, 1, 2, password, target);
                    pc = ((oldPc[0] & 0xFF) << 8) | (oldPc[1] & 0xFF);
                }
            }
        } catch (ReadFailure e) {
            report.problem = "写前检查失败，尚未写入: " + e.getMessage();
            return report;
        }

        boolean stopped = false;
        for (Item item : ordered) {
            if (stopped) {
                report.results.add(new Result(item.bank, false, "前一区未通过，未执行"));
                continue;
            }
            boolean changed = false;
            try {
                byte[] expected = item.data;
                int start = item.address();
                String nextTarget = target;
                if (item.bank == 1) {
                    // Preserve the target's non-length PC flags; CRC is maintained by the chip.
                    int newPc = (pc & 0x07FF) | ((item.data.length / 2) << 11);
                    expected = new byte[item.data.length + 2];
                    expected[0] = (byte) (newPc >> 8);
                    expected[1] = (byte) newPc;
                    System.arraycopy(item.data, 0, expected, 2, item.data.length);
                    start = 1;
                    nextTarget = RfidProto.hex(item.data);
                }
                int chunkSize = item.bank == 1 || item.bank == 0 ? expected.length : 32;
                boolean lostAck = false;
                for (int off = 0; off < expected.length; off += chunkSize) {
                    if (!transport.isConnected()) throw new ReadFailure("连接已断开");
                    int size = Math.min(chunkSize, expected.length - off);
                    int address = start + off / 2;
                    progress.update("写入并检查 " + RfidProto.BANKS[item.bank] + "  " + off + "/" + expected.length + " 字节");
                    // Check the responding EPC immediately before each write as well as afterwards.
                    byte[] part = Arrays.copyOfRange(expected, off, off + size);
                    byte[] before = read(item.bank, address, size, password, target);
                    // Some tags mask password reads with zeros; do not infer password equality.
                    if (item.bank != 0 && Arrays.equals(part, before)) continue;
                    changed = true;
                    int[] ack;
                    try { ack = transport.write(item.bank, address, part, password); }
                    catch (RuntimeException e) { throw new ReadFailure("写入通信异常: " + e.getMessage()); }
                    boolean ackOk = ack != null && ack.length > 1 && ack[0] == RfidProto.CMD_WRITE && ack[1] == 0;
                    if (ack != null && ack.length > 1 && ack[0] == RfidProto.CMD_WRITE
                            && ack[1] != 0 && ack[1] != RfidProto.OK)
                        throw new ReadFailure("标签拒绝写入: " + writeStatus(ack));
                    byte[] verifyPassword = item.bank == 0 ? Arrays.copyOfRange(item.data, 4, 8) : password;
                    byte[] actual;
                    try {
                        actual = read(item.bank, address, size, verifyPassword, nextTarget);
                    } catch (ReadFailure e) {
                        throw new ReadFailure("写入响应 " + writeStatus(ack) + "；回读失败: " + e.getMessage());
                    }
                    if (!Arrays.equals(part, actual)) {
                        int first = 0;
                        while (first < size && part[first] == actual[first]) first++;
                        throw new ReadFailure(String.format("回读不一致，字地址 %d / 字节 %d，期望 %02X，实际 %02X；响应 %s",
                                address + first / 2, first % 2, part[first] & 0xFF, actual[first] & 0xFF, writeStatus(ack)));
                    }
                    lostAck |= !ackOk;
                    if (item.bank == 1) target = nextTarget;
                    if (item.bank == 0) password = verifyPassword;
                }
                report.results.add(new Result(item.bank, true, (changed ? "回读一致（" : "内容已一致，跳过写入（") + item.data.length + " 字节" +
                        (item.bank == 1 ? " + PC 长度" : "") + "）" + (lostAck ? "；写入响应异常，已由回读确认内容" : "") +
                        (item.bank == 0 ? "\n密码区若返回隐藏值，无法据此确认真实密码" : "")));
            } catch (ReadFailure e) {
                report.results.add(new Result(item.bank, false, e.getMessage() + (changed ? "\n已尝试写入，可能有部分数据改变" : "\n本区未写入")));
                stopped = true;
            }
        }
        return report;
    }

    private byte[] read(int bank, int address, int byteCount, byte[] password, String target) throws ReadFailure {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        while (output.size() < byteCount) {
            int size = Math.min(32, byteCount - output.size());
            int[] frame = null;
            for (int attempt = 0; attempt < 2; attempt++) {
                if (!transport.isConnected()) throw new ReadFailure("连接已断开");
                try { frame = transport.read(bank, address + output.size() / 2, size / 2, password); }
                catch (RuntimeException e) { throw new ReadFailure("读取通信异常: " + e.getMessage()); }
                if (frame != null && frame.length > 1 && frame[1] != 0x11 && !RfidProto.isReadAck(frame)) break;
            }
            byte[] data = RfidProto.parseReadData(frame);
            if (data == null || data.length != size) {
                String reason = frame == null || RfidProto.isReadAck(frame) ? "无数据响应" :
                        (frame.length > 1 && frame[1] != 0 ? RfidProto.statusText(frame[1]) : "响应格式或长度异常");
                throw new ReadFailure(RfidProto.BANKS[bank] + " @字" + (address + output.size() / 2) + ": " + reason);
            }
            if (!target.equalsIgnoreCase(RfidProto.parseReadEpc(frame))) throw new ReadFailure("响应来自另一 EPC，已中止");
            output.write(data, 0, data.length);
        }
        return output.toByteArray();
    }

    private static String difference(Item item, byte[] actual) {
        for (int i = 0; i < actual.length; i++) if (actual[i] != item.data[i])
            return String.format("不一致，字地址 %d / 字节 %d\n期望: %02X  实际: %02X",
                    item.address() + i / 2, i % 2, item.data[i] & 0xFF, actual[i] & 0xFF);
        return null;
    }

    private static String writeStatus(int[] frame) {
        if (frame == null) return "无响应";
        if (frame.length < 2 || frame[0] != RfidProto.CMD_WRITE) return "异常";
        return String.format("0x%02X (%s)", frame[1], RfidProto.statusText(frame[1]));
    }
}
