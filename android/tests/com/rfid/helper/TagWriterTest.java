package com.rfid.helper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class TagWriterTest {
    private static final byte[] ZERO = new byte[4];
    private static final String OLD_EPC = "E20000112233445566778899";
    private static int checks;
    private static final TagWriter.Progress IGNORE = text -> {};

    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
        checks++;
    }

    private static byte[] hex(String s) { return RfidProto.unhex(s); }
    private static TagWriter.Item item(int bank, String s) { return new TagWriter.Item(bank, hex(s)); }
    private static void invalid(Runnable op, String name) {
        try { op.run(); } catch (IllegalArgumentException e) { checks++; return; }
        throw new AssertionError(name);
    }

    private static class Card implements TagWriter.Transport {
        final byte[][] memory = {new byte[8], new byte[66], hex("E28278022000000011223344"), new byte[96]};
        final List<Integer> writes = new ArrayList<>();
        List<String> tags;
        boolean online = true;
        boolean denyTid, falseSuccess, lostAck, shortResponse, writeException;
        int maxWriteBytes;
        int reads;
        int swapAtRead = -1;

        Card() {
            memory[1][0] = (byte) 0xAB;
            memory[1][1] = (byte) 0xCD;
            memory[1][2] = 0x34; // 6 EPC words, preserve non-length PC bit 0x0400
            memory[1][3] = 0;
            System.arraycopy(hex(OLD_EPC), 0, memory[1], 4, 12);
        }
        String epc() {
            int words = (memory[1][2] & 0xFF) >>> 3;
            return RfidProto.hex(Arrays.copyOfRange(memory[1], 4, 4 + words * 2));
        }
        byte[] password() { return Arrays.copyOfRange(memory[0], 4, 8); }
        public boolean isConnected() { return online; }
        public List<String> inventory() { return tags == null ? Collections.singletonList(epc()) : tags; }
        public int[] read(int bank, int address, int words, byte[] pwd) {
            reads++;
            if (reads == swapAtRead) memory[1][5] ^= 1;
            if (!online) return null;
            if (!Arrays.equals(pwd, password())) return new int[]{0x81, 0x16};
            if ((address + words) * 2 > memory[bank].length) return new int[]{0x81, 0x43};
            byte[] data = Arrays.copyOfRange(memory[bank], address * 2, (address + words) * 2);
            if (shortResponse) data = new byte[0];
            return response(epc(), data);
        }
        public int[] write(int bank, int address, byte[] data, byte[] pwd) {
            writes.add(bank);
            maxWriteBytes = Math.max(maxWriteBytes, data.length);
            if (writeException) throw new IllegalStateException("USB removed");
            if (!Arrays.equals(pwd, password())) return new int[]{0x82, 0x16};
            if (denyTid && bank == 2) return new int[]{0x82, 0x44};
            if (!falseSuccess) System.arraycopy(data, 0, memory[bank], address * 2, data.length);
            return lostAck ? null : new int[]{0x82, 0};
        }
    }

    private static int[] response(String epc, byte[] data) {
        byte[] id = hex(epc);
        int[] frame = new int[12 + id.length + data.length];
        frame[0] = 0x81;
        frame[4] = (id.length / 2) << 3;
        for (int i = 0; i < id.length; i++) frame[6 + i] = id[i] & 0xFF;
        for (int i = 0; i < data.length; i++) frame[8 + id.length + i] = data[i] & 0xFF;
        frame[frame.length - 4] = data.length >> 8;
        frame[frame.length - 3] = data.length & 0xFF;
        frame[frame.length - 2] = frame[frame.length - 1] = 1;
        return frame;
    }

    public static void main(String[] args) {
        check(Arrays.equals(TagWriter.parseHex("ab cd\nEF 01", "数据"), hex("ABCDEF01")), "strict hex accepts whitespace");
        for (String s : new String[]{"", "123", "GG11", "00-11", "0xAABB"})
            invalid(() -> TagWriter.parseHex(s, "数据"), "reject malformed HEX");
        invalid(() -> TagWriter.validate(Collections.emptyList(), ZERO), "no selection");
        invalid(() -> TagWriter.validate(Arrays.asList(item(1, "1234")), new byte[3]), "password length");
        invalid(() -> TagWriter.validate(Arrays.asList(item(0, "1234")), ZERO), "reserved exact length");
        invalid(() -> TagWriter.validate(Arrays.asList(item(3, "123456")), ZERO), "word alignment");
        invalid(() -> TagWriter.validate(Arrays.asList(new TagWriter.Item(1, new byte[64])), ZERO), "EPC limit");
        invalid(() -> TagWriter.validate(Arrays.asList(item(2, "1234"), item(2, "5678")), ZERO), "duplicate bank");

        byte[] userData = new byte[70];
        for (int i = 0; i < userData.length; i++) userData[i] = (byte) (i + 1);
        TagWriter.Item[] all = {
                item(0, "1122334455667788"), item(1, "0011223344556677"),
                item(2, "E2801105200010A407C5D190"), new TagWriter.Item(3, userData)};
        for (int mask = 1; mask < 16; mask++) {
            Card card = new Card();
            byte[][] before = new byte[4][];
            List<TagWriter.Item> selected = new ArrayList<>();
            for (int bank = 0; bank < 4; bank++) {
                before[bank] = card.memory[bank].clone();
                if ((mask & (1 << bank)) != 0) selected.add(all[bank]);
            }
            TagWriter.Report report = new TagWriter(card).run(selected, ZERO, false, IGNORE);
            check(report.passed(), "write subset " + mask + " " + report.text(false));
            for (int bank = 0; bank < 4; bank++) {
                if ((mask & (1 << bank)) == 0) check(Arrays.equals(before[bank], card.memory[bank]), "unselected bank untouched");
                else check(Arrays.equals(all[bank].data, Arrays.copyOfRange(card.memory[bank], all[bank].address() * 2,
                        all[bank].address() * 2 + all[bank].data.length)), "selected range written");
            }
            if ((mask & 2) != 0) {
                check(card.memory[1][0] == (byte) 0xAB && card.memory[1][1] == (byte) 0xCD, "CRC not written");
                check(card.memory[1][2] == 0x24 && card.memory[1][3] == 0, "PC length updated preserving flags");
                check(card.epc().equals("0011223344556677"), "inventory EPC length changed");
            }
            if ((mask & 1) != 0) check(card.writes.get(card.writes.size() - 1) == 0, "password bank written last");
            int count = card.writes.size();
            check(new TagWriter(card).run(selected, card.password(), true, IGNORE).passed(), "check all selected with current password");
            check(card.writes.size() == count, "check-only performs zero writes");
            check(new TagWriter(card).run(selected, card.password(), false, IGNORE).passed(), "repeat same content succeeds");
            check(card.writes.size() == count + ((mask & 1) != 0 ? 1 : 0), "skip unchanged data except potentially masked passwords");
        }

        Card readOnly = new Card();
        readOnly.denyTid = true;
        TagWriter.Report locked = new TagWriter(readOnly).run(Arrays.asList(all[2], all[1]), ZERO, false, IGNORE);
        check(!locked.passed() && readOnly.writes.equals(Arrays.asList(2)), "TID rejected, later EPC not written");
        check(locked.results.get(0).message.contains("0x44") && locked.results.get(1).message.contains("未执行"), "failure and skipped bank reported");

        Card tooSmall = new Card();
        TagWriter.Report capacity = new TagWriter(tooSmall).run(Arrays.asList(all[1], new TagWriter.Item(3, new byte[98])), ZERO, false, IGNORE);
        check(!capacity.passed() && tooSmall.writes.isEmpty(), "capacity error before any write");
        check(capacity.problem.contains("尚未写入"), "preflight result is explicit");

        Card wrongPassword = new Card();
        check(!new TagWriter(wrongPassword).run(Arrays.asList(all[2]), hex("12345678"), false, IGNORE).passed()
                && wrongPassword.writes.isEmpty(), "wrong password fails preflight");

        Card liar = new Card();
        liar.falseSuccess = true;
        TagWriter.Report falseSuccess = new TagWriter(liar).run(Arrays.asList(all[3], all[0]), ZERO, false, IGNORE);
        check(!falseSuccess.passed() && liar.writes.equals(Arrays.asList(3)), "ACK without data change cannot pass");
        check(falseSuccess.results.get(0).message.contains("回读不一致"), "first mismatch location reported");

        Card missingAck = new Card();
        missingAck.lostAck = true;
        check(new TagWriter(missingAck).run(Arrays.asList(all[1]), ZERO, false, IGNORE).passed(), "lost ACK with exact readback passes");
        check(missingAck.writes.size() == 1, "no blind repeated writes after missing ACK");

        Card many = new Card();
        many.tags = Arrays.asList(OLD_EPC, "00112233");
        check(!new TagWriter(many).run(Arrays.asList(all[1]), ZERO, false, IGNORE).passed() && many.writes.isEmpty(), "multiple EPCs reject write");
        many.tags = Collections.emptyList();
        check(!new TagWriter(many).run(Arrays.asList(all[1]), ZERO, false, IGNORE).passed(), "no card rejects write");

        Card switched = new Card();
        switched.swapAtRead = 2;
        check(!new TagWriter(switched).run(Arrays.asList(all[2]), ZERO, false, IGNORE).passed()
                && switched.writes.isEmpty(), "tag change immediately before write stops operation");

        Card shortReply = new Card();
        shortReply.shortResponse = true;
        check(!new TagWriter(shortReply).run(Arrays.asList(all[2]), ZERO, false, IGNORE).passed()
                && shortReply.writes.isEmpty(), "short read cannot pass preflight");

        Card unplugged = new Card();
        unplugged.writeException = true;
        TagWriter.Report unplug = new TagWriter(unplugged).run(Arrays.asList(all[2], all[1]), ZERO, false, IGNORE);
        check(!unplug.passed() && unplug.results.get(0).message.contains("可能有部分数据改变")
                && unplugged.writes.size() == 1, "disconnect during write reports possible partial change");

        Card comparison = new Card();
        TagWriter.Report mismatch = new TagWriter(comparison).run(Arrays.asList(item(1, "E200001122334455667788FF")), ZERO, true, IGNORE);
        check(!mismatch.passed() && mismatch.results.get(0).message.contains("字地址 7 / 字节 1") && comparison.writes.isEmpty(), "read-only mismatch word and byte offset");
        TagWriter.Report prefix = new TagWriter(comparison).run(Arrays.asList(item(1, "E2000011")), ZERO, true, IGNORE);
        check(!prefix.passed() && prefix.results.get(0).message.contains("长度"), "EPC prefix alone cannot pass length check");

        Card longer = new Card();
        byte[] longEpc = new byte[62];
        Arrays.fill(longEpc, (byte) 0x12);
        check(new TagWriter(longer).run(Arrays.asList(new TagWriter.Item(1, longEpc)), ZERO, false, IGNORE).passed(), "maximum EPC length readback spans chunks");
        check(longer.maxWriteBytes == 64 && longer.writes.size() == 1, "PC and EPC use one bounded command");

        System.out.println("PASS: " + checks + " writer checks");
    }
}
