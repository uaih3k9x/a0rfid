package com.rfid.helper;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the text produced by the read-result Copy button, before changing any fields. */
final class TagClipboard {
    private static final Pattern EPC = Pattern.compile("^EPC\\s*[:：]\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern BANK = Pattern.compile("^(EPC|TID|USER|RESERVED|RESERVE)\\s+(\\d+)\\s*字节(?:\\s+(.*))?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ROW = Pattern.compile("^([0-9A-Fa-f]{4,8})\\s*[:：]\\s*([0-9A-Fa-f\\s]+)$");

    static final class Import {
        final Map<Integer, String> values = new LinkedHashMap<>();
        final Map<Integer, String> notes = new LinkedHashMap<>();

        String summary() {
            StringBuilder out = new StringBuilder("已从剪贴板导入\n");
            for (int bank : new int[]{2, 1, 3, 0}) {
                out.append('\n').append(RfidProto.BANKS[bank]).append(": ");
                if (values.containsKey(bank)) out.append(values.get(bank).length() / 2).append(" 字节；");
                out.append(notes.get(bank)).append('\n');
            }
            return out.append("\n请勾选需要操作的区域，再点“只检查”或“写入并检查”。").toString();
        }
    }

    private static final class Section {
        final int bank, size;
        final String state;
        final ByteArrayOutputStream data = new ByteArrayOutputStream();

        Section(int bank, int size, String state) {
            this.bank = bank;
            this.size = size;
            this.state = state == null || state.isEmpty() ? "来源未标记读取状态" : state;
        }
    }

    static Import parse(String text) {
        if (text == null || text.trim().isEmpty()) throw new IllegalArgumentException("剪贴板为空，请先在读取结果中点“复制内容”");
        if (text.length() > 100_000) throw new IllegalArgumentException("剪贴板内容过长，请复制单张标签的读取结果");
        Map<Integer, Section> sections = new LinkedHashMap<>();
        String epc = null;
        Section current = null;
        for (String raw : text.replace("\uFEFF", "").split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            Matcher header = EPC.matcher(line);
            if (header.matches()) {
                if (epc != null || !sections.isEmpty()) throw new IllegalArgumentException("包含重复标签或 EPC 标题，请一次只导入一张标签");
                byte[] bytes = TagWriter.parseHex(header.group(1), "EPC");
                if ((bytes.length & 1) != 0 || bytes.length > 62) throw new IllegalArgumentException("EPC 编号长度无效");
                epc = RfidProto.hex(bytes);
                continue;
            }
            Matcher bank = BANK.matcher(line);
            if (bank.matches()) {
                int index = bankIndex(bank.group(1));
                if (sections.containsKey(index)) throw new IllegalArgumentException("存储区重复: " + RfidProto.BANKS[index]);
                int size;
                try { size = Integer.parseInt(bank.group(2)); }
                catch (NumberFormatException e) { throw new IllegalArgumentException("字节数无效"); }
                if (size < 0 || size > 4096 || (size & 1) != 0) throw new IllegalArgumentException("存储区字节数无效");
                current = new Section(index, size, bank.group(3));
                sections.put(index, current);
                continue;
            }
            Matcher row = ROW.matcher(line);
            if (current == null || !row.matches())
                throw new IllegalArgumentException("内容不是完整的标签读取结果，请使用读取结果中的“复制内容”");
            long address = Long.parseLong(row.group(1), 16);
            if (address != current.data.size() / 2)
                throw new IllegalArgumentException(RfidProto.BANKS[current.bank] + " 数据地址不连续或重复");
            byte[] data = TagWriter.parseHex(row.group(2), RfidProto.BANKS[current.bank]);
            if ((data.length & 1) != 0 || current.data.size() + data.length > current.size)
                throw new IllegalArgumentException(RfidProto.BANKS[current.bank] + " 数据长度与字节数不符");
            current.data.write(data, 0, data.length);
        }

        Import result = new Import();
        for (int bank = 0; bank < 4; bank++) result.notes.put(bank, "未包含数据");
        for (Section section : sections.values()) {
            if (section.data.size() != section.size)
                throw new IllegalArgumentException(RfidProto.BANKS[section.bank] + " 数据被截断，与声明的字节数不符");
            byte[] data = section.data.toByteArray();
            result.notes.put(section.bank, "剪贴板：" + section.state);
            if (data.length == 0) continue;
            if (section.bank == 1) {
                // Word 0 is CRC, word 1 is PC; only the PC-declared EPC belongs in the form.
                if (data.length < 4) {
                    if (epc == null) throw new IllegalArgumentException("EPC 区不完整，无法提取编号");
                    continue;
                }
                int length = ((((data[2] & 0xFF) << 8) | (data[3] & 0xFF)) >>> 11) * 2;
                if (length == 0) throw new IllegalArgumentException("EPC 区 PC 长度为零，无法导入编号");
                if (data.length < length + 4 && epc == null)
                    throw new IllegalArgumentException("EPC 区不完整，且没有盘存编号");
                byte[] prefix = Arrays.copyOfRange(data, 4, Math.min(data.length, length + 4));
                if (epc != null && (epc.length() != length * 2 || !epc.startsWith(RfidProto.hex(prefix))))
                    throw new IllegalArgumentException("盘存 EPC 与存储区 EPC 不一致，请重新读取后复制");
                if (epc == null) epc = RfidProto.hex(prefix);
            } else if (section.bank == 0 && data.length != 8) {
                result.notes.put(0, "剪贴板密钥区不足或超过 8 字节，未导入；" + section.state);
            } else {
                result.values.put(section.bank, RfidProto.hex(data));
            }
        }
        if (epc != null) {
            result.values.put(1, epc);
            result.notes.put(1, "已提取 EPC 编号");
        }
        if (result.values.isEmpty()) throw new IllegalArgumentException("剪贴板中没有可导入的标签数据");
        return result;
    }

    private static int bankIndex(String name) {
        switch (name.toUpperCase(Locale.ROOT)) {
            case "EPC": return 1;
            case "TID": return 2;
            case "USER": return 3;
            default: return 0;
        }
    }
}
