#!/usr/bin/env python3
"""无硬件离线测试：用真机实测/协议还原的帧格式模拟读写头行为
运行：python3 test_offline.py"""
import rfid_tool as P

passed = 0


def check(name, cond):
    global passed
    assert cond, f"✘ {name}"
    print(f"✔ {name}")
    passed += 1


# ── 1. 校验和与帧构造（真机实测样本）─────────────────────
check("校验和 0x115→0xEB", P.checksum(bytes([0xA0, 0x03, 0x00, 0x72])) == 0xEB)
check("固件版本帧 == 真机帧", P.encode(0, P.CMD_GET_FW_VERSION) == bytes.fromhex("A0030072EB"))

# 真机实测响应帧（固件 V2.4.1）
cmd, data = P.decode(bytes.fromhex("A0060072020401E1"))
check("响应帧解析: 固件版本(无0x80位)", cmd == 0x72 and data == b"\x02\x04\x01")
cmd, data = P.decode(bytes.fromhex("A004007714D1"))
check("响应帧解析: 功率20dBm", cmd == 0x77 and data[0] == 20)
cmd, data = P.decode(bytes.fromhex("A004008911C2"))
check("响应帧解析: 盘存失败0x11", cmd == 0x89 and data[0] == 0x11)

# ── 2. READ 响应解析（真实结构样本帧，EPC 为示例值）──────────
EPC = "E20034120136A000"
GOLD = P.encode(0, 0x81, bytes([0x00, 0x01, 0x1C]) + bytes.fromhex("3000" + EPC + "3142")
               + bytes.fromhex(EPC) + bytes([0x00, len(bytes.fromhex(EPC)), 0x01, 0x01]))
cmd, data = P.decode(GOLD)
st, rd = P.parse_read_data(data)
check("READ样本帧: 状态0x00+数据提取", cmd == 0x81 and st == 0x00 and rd.hex().upper() == EPC)

# ── 2b. READ 双帧式解析 ──────────────────────────────────
tid_hex = "E2801105200010A407C5D190"
frames = [P.encode(0, 0x81, b"\x10"),                # ACK 帧
          P.encode(0, 0x81, b"\x10" + bytes.fromhex(tid_hex))]  # 数据帧
st, d = P._status_of([(0x81, f[4:-1]) for f in frames], P.CMD_READ)
check("READ 双帧: 取数据帧(非ACK)", st == P.OK and d.hex().upper() == tid_hex)
st, _ = P._status_of([(0x81, b"\x11")], P.CMD_READ)
check("READ 失败帧: 0x11", st == 0x11)

# 带流式缓冲的完整链路（pop_frame 连续切帧）
buf = bytearray(b"".join(frames) + P.encode(0, P.CMD_STOP_INV, b"\x10"))
out = []
while True:
    got = P.pop_frame(buf)
    if got is None:
        break
    out.append((got[0], got[1]))
    buf = bytearray(got[2])
check("流式切帧: 3帧全部弹出", len(out) == 3 and out[0] == (0x81, b"\x10")
      and out[1] == (0x81, b"\x10" + bytes.fromhex(tid_hex)) and out[2][0] == P.CMD_STOP_INV)

# ── 3. 盘存标签帧解析 ────────────────────────────────────
inv_data = bytes([0, 0x30, 0x00]) + bytes.fromhex("E20034120136A00012") + bytes(4) + bytes([0x01, 0x6C, 0xB0])
t = P.parse_inventory(inv_data)
check("盘存帧: EPC/PC/频率", t["epc"] == "E20034120136A00012" and t["pc"] == "3000" and t["freq_khz"] == 93360)

# ── 4. TID 读写帧构造（IDA 逆向 rfid_encode case129/130 确认的线格式）──
f = P.encode(P.ADDR, P.CMD_READ, P.build_rw("TID", 0, 6))
# READ: {mb, addr×4, cnt×2, pwd×4}, LEN=0x0E
check("TID 读帧结构", f[:4] == bytes([0xA0, 0x0E, 0x00, 0x81]) and f[4] == 0x02
      and f[5:9] == b"\x00" * 4 and f[9:11] == b"\x00\x06" and f[11:15] == b"\x00" * 4)
fw = P.encode(P.ADDR, P.CMD_WRITE, P.build_rw("TID", 0, 2, data=bytes.fromhex("E2801105")))
# WRITE: {pwd×4, mb, addr×4, cnt×2, data}, LEN=2*2+0x0E=0x12
check("TID 写帧结构", fw[4:8] == b"\x00" * 4 and fw[8] == 0x02 and fw[9:13] == b"\x00" * 4
      and fw[13:15] == b"\x00\x02" and fw[15:19] == bytes.fromhex("E2801105") and fw[1] == 0x12)

# ── 5. EPC 读写帧（PDF 官方操作: EPC区@2×6字）────────────
f = P.encode(P.ADDR, P.CMD_READ, P.build_rw("EPC", 2, 6))
check("EPC 读帧结构", f.hex().upper() == "A00E00810100000002000600000000C8")
f = P.encode(P.ADDR, P.CMD_WRITE, P.build_rw("EPC", 2, 4, data=bytes.fromhex("E20034120136A000")))
check("EPC 写帧结构", f[1] == 0x16 and f[4:8] == b"\x00" * 4 and f[8] == 0x01
      and f[13:15] == b"\x00\x04" and f[-9:-1].hex().upper() == "E20034120136A000")

# ── 6. cmd_eq 兼容两种固件 ───────────────────────────────
check("cmd_eq: 本固件(0x81)", P.cmd_eq(0x81, P.CMD_READ))
check("cmd_eq: 0x80位固件(0xF2↔0x72)", P.cmd_eq(0xF2, 0x72))
check("cmd_eq: 不匹配为假", not P.cmd_eq(0x8B, P.CMD_READ))

print(f"\n全部通过：{passed} 项（无需硬件）")
