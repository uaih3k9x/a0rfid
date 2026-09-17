package com.rfid.helper;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;

public final class TagMemoryReaderTest {
    private static final String EPC = "E20000112233445566778899";
    private static int checks;

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        checks++;
    }

    private static int[] response(String epc, byte[] data) {
        byte[] id = RfidProto.unhex(epc);
        int[] frame = new int[12 + id.length + data.length];
        frame[0] = RfidProto.CMD_READ;
        frame[2] = 1;
        frame[3] = 28;
        frame[4] = (id.length / 2) << 3;
        for (int i = 0; i < id.length; i++) frame[6 + i] = id[i] & 0xFF;
        for (int i = 0; i < data.length; i++) frame[8 + id.length + i] = data[i] & 0xFF;
        frame[frame.length - 4] = data.length >> 8;
        frame[frame.length - 3] = data.length & 0xFF;
        frame[frame.length - 2] = 1;
        frame[frame.length - 1] = 1;
        return frame;
    }

    private static class Memory implements TagMemoryReader.Transport {
        final byte[][] banks = new byte[4][];
        int calls;
        boolean connected = true;

        Memory(int reserved, int epc, int tid, int user) {
            int[] words = {reserved, epc, tid, user};
            for (int bank = 0; bank < 4; bank++) {
                banks[bank] = new byte[words[bank] * 2];
                for (int i = 0; i < banks[bank].length; i++) banks[bank][i] = (byte) (i + bank * 37);
            }
        }

        public int[] read(int bank, int address, int words) {
            calls++;
            if ((address + words) * 2 > banks[bank].length) return new int[]{0x81, 0x43};
            return response(EPC, Arrays.copyOfRange(banks[bank], address * 2, (address + words) * 2));
        }

        public boolean isConnected() { return connected; }
    }

    private static final TagMemoryReader.Progress IGNORE = (bank, bytes) -> {};

    public static void main(String[] args) {
        byte[] gold = RfidProto.unhex("A026008100011C3000E200001122334455667788995911E20000112233445566778899000C010136");
        int[] frame = RfidProto.parseFrame(gold, 0, gold.length);
        check(EPC.equals(RfidProto.hex(RfidProto.parseReadData(frame))), "reader frame layout with synthetic EPC");
        check(EPC.equals(RfidProto.parseReadEpc(frame)), "synthetic response identity");

        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        byte[] noise = {0x55, 0x33, (byte) 0xA0, 0, 0};
        stream.write(noise, 0, noise.length);
        byte[] ack = RfidProto.encode(0x81, new byte[]{0x10});
        stream.write(ack, 0, ack.length);
        stream.write(gold, 0, gold.length);
        byte[] bytes = stream.toByteArray();
        check(RfidProto.isReadAck(RfidProto.parseFrame(bytes, 0, bytes.length)), "ACK follows noise");
        int consumed = RfidProto.frameLen(bytes, 0);
        check(consumed == noise.length + ack.length, "consume noise along with first frame");
        check(EPC.equals(RfidProto.parseReadEpc(RfidProto.parseFrame(bytes, consumed, bytes.length))), "data follows ACK");
        check(RfidProto.parseFrame(gold, 0, gold.length - 1) == null, "fragment waits for checksum");
        byte[] corrupt = gold.clone();
        corrupt[10] ^= 1;
        check(RfidProto.parseFrame(corrupt, 0, corrupt.length) == null, "reject corrupt checksum");
        int[] malformed = frame.clone();
        malformed[malformed.length - 3] = 13;
        check(RfidProto.parseReadData(malformed) == null, "reject invalid data length");

        Memory memory = new Memory(4, 10, 6, 37);
        TagMemoryReader reader = new TagMemoryReader(memory);
        List<TagMemoryReader.Bank> results = reader.readAll(EPC, IGNORE);
        for (TagMemoryReader.Bank bank : results) {
            check(bank.end == TagMemoryReader.End.COMPLETE, "bank boundary " + bank.index);
            check(Arrays.equals(memory.banks[bank.index], bank.data), "entire bank " + bank.index);
        }
        check(memory.calls < 60, "bounded boundary probes");
        List<TagMemoryReader.Bank> again = reader.readAll(EPC, IGNORE);
        check(Arrays.equals(results.get(1).data, again.get(1).data), "same EPC can be read again");

        Memory noUser = new Memory(4, 8, 6, 0);
        TagMemoryReader.Bank empty = new TagMemoryReader(noUser).readBank(EPC, 3, IGNORE);
        check(empty.data.length == 0 && empty.end == TagMemoryReader.End.COMPLETE, "absent USER bank");

        Memory locked = new Memory(4, 8, 6, 0) {
            @Override public int[] read(int bank, int address, int words) {
                return bank == 0 ? new int[]{0x81, 0x44} : super.read(bank, address, words);
            }
        };
        results = new TagMemoryReader(locked).readAll(EPC, IGNORE);
        check(results.get(3).end == TagMemoryReader.End.ERROR && results.get(3).status == 0x44,
                "protected bank preserves error code");
        check(results.get(1).data.length == 12, "protected bank does not lose TID");

        Memory swapped = new Memory(4, 64, 6, 0) {
            @Override public int[] read(int bank, int address, int words) {
                if (address > 0) return response("E200001122334455667788AA", new byte[words * 2]);
                return super.read(bank, address, words);
            }
        };
        results = new TagMemoryReader(swapped).readAll(EPC, IGNORE);
        check(results.get(0).end == TagMemoryReader.End.TAG_CHANGED && results.get(0).data.length == 32,
                "discard data from a different EPC midway");
        check(results.get(1).data.length == 0 && results.get(1).end == TagMemoryReader.End.TAG_CHANGED,
                "stop remaining banks on tag change");

        Memory shortReply = new Memory(4, 8, 6, 0) {
            @Override public int[] read(int bank, int address, int words) {
                return response(EPC, new byte[2]);
            }
        };
        check(new TagMemoryReader(shortReply).readBank(EPC, 2, IGNORE).end == TagMemoryReader.End.INVALID_RESPONSE,
                "reject short successful response");

        Memory ackOnly = new Memory(4, 8, 6, 0) {
            @Override public int[] read(int bank, int address, int words) {
                calls++;
                return new int[]{0x81, 0x10};
            }
        };
        check(new TagMemoryReader(ackOnly).readBank(EPC, 2, IGNORE).end == TagMemoryReader.End.NO_RESPONSE
                && ackOnly.calls == 10, "ACK alone cannot complete a read");

        Memory timeout = new Memory(4, 8, 6, 0) {
            @Override public int[] read(int bank, int address, int words) { calls++; return null; }
        };
        check(new TagMemoryReader(timeout).readBank(EPC, 2, IGNORE).end == TagMemoryReader.End.NO_RESPONSE
                && timeout.calls == 10, "bounded timeout retries and smaller probes");

        Memory silentBoundary = new Memory(4, 8, 6, 35) {
            @Override public int[] read(int bank, int address, int words) {
                if ((address + words) * 2 > banks[bank].length) return null;
                return super.read(bank, address, words);
            }
        };
        TagMemoryReader.Bank silent = new TagMemoryReader(silentBoundary).readBank(EPC, 3, IGNORE);
        check(Arrays.equals(silent.data, silentBoundary.banks[3]), "recover tail after a silent oversized read");
        check(silent.end == TagMemoryReader.End.NO_RESPONSE, "silence cannot prove the end of memory");

        Memory unplugged = new Memory(4, 8, 6, 0) {
            @Override public int[] read(int bank, int address, int words) { connected = false; return null; }
        };
        check(new TagMemoryReader(unplugged).readBank(EPC, 2, IGNORE).end == TagMemoryReader.End.CANCELLED,
                "USB disconnect cancels read");

        Memory cancelled = new Memory(4, 64, 6, 0);
        TagMemoryReader.Bank partial = new TagMemoryReader(cancelled).readBank(EPC, 1, (bank, count) -> {
            if (count >= 32) cancelled.connected = false;
        });
        check(partial.end == TagMemoryReader.End.CANCELLED && partial.data.length == 32, "cancel preserves completed chunks");

        Memory huge = new Memory(4, TagMemoryReader.MAX_WORDS + 16, 6, 0);
        TagMemoryReader.Bank bounded = new TagMemoryReader(huge).readBank(EPC, 1, IGNORE);
        check(bounded.end == TagMemoryReader.End.LIMIT && bounded.data.length == TagMemoryReader.MAX_WORDS * 2,
                "size cap is reported as partial, not complete");
        System.out.println("PASS: " + checks + " checks");
    }
}
