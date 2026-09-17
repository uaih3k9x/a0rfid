package com.rfid.helper;

public final class TagClipboardTest {
    private static final String EPC = "E20000112233445566778899";
    private static final String READ_RESULT = "EPC: " + EPC + "\n\n"
            + "EPC  16 字节  读取完成\n0000: 59113000" + EPC + "\n\n"
            + "TID  12 字节  读取完成\n0000: " + EPC + "\n\n"
            + "USER  0 字节  无可读空间 (0x43)\n\n"
            + "RESERVED  0 字节  读取失败: 0x41\n";
    private static int checks;

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        checks++;
    }

    private static void invalid(String text, String name) {
        try { TagClipboard.parse(text); }
        catch (IllegalArgumentException e) { checks++; return; }
        throw new AssertionError(name);
    }

    public static void main(String[] args) {
        TagClipboard.Import actual = TagClipboard.parse(READ_RESULT);
        check(EPC.equals(actual.values.get(1)), "copied EPC imports number only");
        check(EPC.equals(actual.values.get(2)), "copied TID");
        check(!actual.values.containsKey(3) && !actual.values.containsKey(0), "empty failed banks not populated");
        check(actual.notes.get(0).contains("0x41"), "original error retained");
        String complete = READ_RESULT.replace("USER  0 字节  无可读空间 (0x43)",
                "USER  20 字节  读取完成\n0000: 00112233445566778899AABBCCDDEEFF\n0008: 10203040")
                .replace("RESERVED  0 字节  读取失败: 0x41", "RESERVED  8 字节  读取完成\n0000: 1122334455667788");
        actual = TagClipboard.parse(complete);
        check(actual.values.size() == 4, "four banks imported");
        check("00112233445566778899AABBCCDDEEFF10203040".equals(actual.values.get(3)), "multiline USER, word addresses");
        check("1122334455667788".equals(actual.values.get(0)), "reserved retained exactly");
        check(TagClipboard.parse(complete.replace("RESERVED", "RESERVE")).values.equals(actual.values), "RESERVE alias");
        check(TagClipboard.parse("\uFEFF" + complete.replace("\n", "\r\n").replace(":", "：")).values.equals(actual.values), "CRLF BOM and fullwidth colon");
        check(TagClipboard.parse(READ_RESULT.toLowerCase()).values.get(1).equals(EPC), "lowercase data normalized");
        check(TagClipboard.parse("EPC: " + EPC).values.get(1).equals(EPC), "inventory EPC without memory dump");
        check(TagClipboard.parse(READ_RESULT.substring(READ_RESULT.indexOf("EPC  "))).values.get(1).equals(EPC), "extract EPC from PC when header missing");
        check(TagClipboard.parse(READ_RESULT.replace("EPC  16", "EPC  20").replace("59113000" + EPC, "59113000" + EPC + "FFFF0000"))
                .values.get(1).equals(EPC), "exclude unused EPC capacity");
        String partial = "EPC: " + EPC + "\nTID  4 字节  无响应，未读完整\n0000: E2000011\n";
        actual = TagClipboard.parse(partial);
        check(actual.values.get(2).equals("E2000011") && actual.notes.get(2).contains("未读完整"), "partial read is explicitly labelled");
        actual = TagClipboard.parse("EPC: " + EPC + "\nRESERVED 4 字节 无响应，未读完整\n0000: 11223344");
        check(!actual.values.containsKey(0) && actual.notes.get(0).contains("未导入"), "partial password bank skipped");
        actual = TagClipboard.parse("EPC: " + EPC + "\nEPC 8 字节 无响应，未读完整\n0000: 59113000E2000011");
        check(actual.values.get(1).equals(EPC), "inventory header supplies missing EPC suffix");
        invalid(null, "empty clipboard");
        invalid("文字 ABCDEF1234", "unrecognized text not treated as raw HEX");
        invalid(READ_RESULT + READ_RESULT, "multiple tags");
        invalid(READ_RESULT.replace("TID  12", "TID  14"), "truncated section");
        invalid(READ_RESULT.replace("TID  12", "TID  10"), "excess section bytes");
        invalid(complete.replace("0008:", "0009:"), "noncontiguous address");
        invalid(complete.replace("0008:", "0000:"), "duplicate address");
        invalid(READ_RESULT.replace("59113000" + EPC, "59113000" + "E200001122334455667788FF"), "mismatched EPC header and dump");
        invalid(READ_RESULT.replace("59113000", "59112000"), "PC length mismatch");
        invalid(READ_RESULT.replace("59113000", "59110000"), "zero PC length");
        invalid(READ_RESULT.replace("59113000", "59113G00"), "invalid hex byte");
        invalid(READ_RESULT + "TID 0 字节 无响应\n", "duplicate bank");
        invalid("TID 0 字节 无响应\n", "no usable data");
        invalid("TID 999999999999999999999 字节 读取完成\n", "oversized declaration");
        invalid("EPC 8 字节 无响应\n0000: 59113000E2000011", "incomplete EPC without inventory header");
        invalid("EPC: 123", "odd hex nibble");
        invalid("EPC: 123456", "odd byte count");
        System.out.println("PASS: " + checks + " clipboard checks");
    }
}
