package com.rfid.helper;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/** Bounded, read-only dump of the four Gen2 memory banks. */
final class TagMemoryReader {
    static final int MAX_WORDS = 2048;
    private static final long BANK_TIMEOUT_NANOS = 30_000_000_000L;

    interface Transport {
        int[] read(int bank, int address, int words);
        boolean isConnected();
    }

    interface Progress {
        void update(int bank, int bytes);
    }

    enum End { COMPLETE, ERROR, NO_RESPONSE, TAG_CHANGED, INVALID_RESPONSE, LIMIT, CANCELLED }

    static final class Bank {
        final int index;
        final byte[] data;
        final End end;
        final int status;

        Bank(int index, byte[] data, End end, int status) {
            this.index = index;
            this.data = data;
            this.end = end;
            this.status = status;
        }
    }

    private final Transport transport;

    TagMemoryReader(Transport transport) {
        this.transport = transport;
    }

    List<Bank> readAll(String epc, Progress progress) {
        List<Bank> banks = new ArrayList<>();
        End stopped = null;
        for (int bank : new int[]{1, 2, 3, 0}) {
            Bank result = stopped == null ? readBank(epc, bank, progress)
                    : new Bank(bank, new byte[0], stopped, -1);
            banks.add(result);
            if (result.end == End.TAG_CHANGED || result.end == End.CANCELLED)
                stopped = result.end;
        }
        return banks;
    }

    Bank readBank(String epc, int bank, Progress progress) {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int limit = bank == 0 ? 4 : MAX_WORDS;
        int address = 0;
        int chunkWords = Math.min(16, limit);
        long deadline = System.nanoTime() + BANK_TIMEOUT_NANOS;
        while (address < limit) {
            if (!transport.isConnected()) return result(bank, data, End.CANCELLED, -1);
            if (System.nanoTime() >= deadline) return result(bank, data, End.LIMIT, -1);
            progress.update(bank, data.size());
            int words = Math.min(chunkWords, limit - address);
            int[] frame = null;
            for (int attempt = 0; attempt < 2 && transport.isConnected(); attempt++) {
                frame = transport.read(bank, address, words);
                if (frame != null && frame.length >= 2 && frame[1] != 0x11
                        && !RfidProto.isReadAck(frame)) break;
            }
            if (!transport.isConnected()) return result(bank, data, End.CANCELLED, -1);
            if (frame == null || RfidProto.isReadAck(frame)) {
                // Some tags stay silent on an oversized read instead of returning 0x43.
                if (words > 1) { chunkWords = Math.max(1, words / 2); continue; }
                return result(bank, data, End.NO_RESPONSE, -1);
            }
            if (frame.length < 2 || frame[0] != RfidProto.CMD_READ)
                return result(bank, data, End.INVALID_RESPONSE, -1);
            if (frame[1] == 0x43) {
                // An oversized read fails entirely; narrow it to recover the final words.
                if (words > 1) { chunkWords = Math.max(1, words / 2); continue; }
                return result(bank, data, End.COMPLETE, 0x43);
            }
            if (frame[1] != 0x00) return result(bank, data, End.ERROR, frame[1]);
            byte[] part = RfidProto.parseReadData(frame);
            if (part == null || part.length != words * 2)
                return result(bank, data, End.INVALID_RESPONSE, -1);
            if (!epc.equalsIgnoreCase(RfidProto.parseReadEpc(frame)))
                return result(bank, data, End.TAG_CHANGED, -1);
            data.write(part, 0, part.length);
            address += words;
        }
        return result(bank, data, bank == 0 ? End.COMPLETE : End.LIMIT, -1);
    }

    private static Bank result(int bank, ByteArrayOutputStream data, End end, int status) {
        return new Bank(bank, data.toByteArray(), end, status);
    }
}
