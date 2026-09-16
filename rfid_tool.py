#!/usr/bin/env python3
"""
915MHz UHF RFID 读写头控制工具（macOS / Linux）
Wire protocol reverse-engineered from the vendor Android SDK + verified on hardware

实测帧格式（两个方向一致，响应 CMD 不带 0x80 标志位）：
    A0 | LEN | ADDR | CMD | DATA... | SUM
    LEN = 3 + len(DATA)（含自身/ADDR/CMD）；SUM = 8位二补数校验和
    标签类指令 DATA 首字节为状态码：0x10=成功 0x11=命令失败
    READ(0x81) 为两帧式：先回 ACK 帧，成功(0x10)后紧跟数据帧

硬件连接：USB（CH340 转串口）→ 115200 8N1
    A) 免驱路径：python3 ch340_usb.py 同目录（需 DYLD_LIBRARY_PATH=/opt/homebrew/lib）
    B) 串口路径：装了 WCH/macOS 驱动后 /dev/tty.usbserial-*

用法：
    python3 rfid_tool.py --port USB info|inventory|read|write|selftest
    --port USB 表示走免驱 USB 传输
"""
import argparse

BAUD = 115200
ADDR = 0x00  # 默认读写器地址（Config.makeDefault: setAddress(0)）

# 指令表（CommandConst.java）
CMD_RESET            = 0x70
CMD_SET_UART_BAUD    = 0x71
CMD_GET_FW_VERSION   = 0x72
CMD_SET_READER_ADDR  = 0x73
CMD_SET_WORK_ANT     = 0x74
CMD_GET_WORK_ANT     = 0x75
CMD_SET_OUTPUT_POWER = 0x76
CMD_GET_OUTPUT_POWER = 0x77
CMD_SET_FREQ_REGION  = 0x78
CMD_GET_FREQ_REGION  = 0x79
CMD_INVENTORY        = 0x80
CMD_READ             = 0x81
CMD_WRITE            = 0x82
CMD_LOCK             = 0x83
CMD_KILL             = 0x84
CMD_SET_EPC_MATCH    = 0x85
CMD_REAL_TIME_INV    = 0x89
CMD_CUSTOM_INV       = 0x8A
CMD_CUS_SESSION_INV  = 0x8B
CMD_STOP_INV         = 0x8C

BANKS = {"RESERVED": 0, "EPC": 1, "TID": 2, "USER": 3}

# ReturnCodeConst.java
OK = 0x10  # COMMAND_SUCCESS
STATUS = {
    0x10: "成功",
    0x11: "命令执行失败/场内无标签",
    0x20: "CPU复位错误",
    0x21: "CW错误",
    0x23: "收发缓存溢出",
    0x24: "设置频段失败",
    0x50: "获取RN16失败",
    0x54: "无法达到期望输出功率",
    0x55: "版权认证失败",
}


def checksum(frame: bytes) -> int:
    """HexUtils.checkSum: 累加取二补数 (sum ^ -1) + 1"""
    return (-sum(frame)) & 0xFF


def encode(addr: int, cmd: int, data: bytes = b"") -> bytes:
    """RfidEncode.encode: A0 | LEN | ADDR | CMD | DATA | SUM"""
    body = bytes([0xA0, len(data) + 3, addr & 0xFF, cmd & 0xFF]) + data
    return body + bytes([checksum(body)])


def decode(frame: bytes):
    """定位 0xA0 帧头并校验，返回 (cmd, data) 或 None。
    帧总长 = LEN + 2；校验对象为前 LEN+1 字节。
    本固件响应 CMD 不带 0x80 标志位，原样返回；比较时用 cmd_eq() 兼容两种固件。"""
    i = frame.find(b"\xA0")
    while i >= 0:
        f = frame[i:]
        ln = f[1]
        if len(f) < ln + 2:
            return None
        if checksum(f[:ln + 1]) == f[ln + 1]:
            return f[3], f[4:ln + 1]
        i = frame.find(b"\xA0", i + 1)
    return None


def cmd_eq(recv_cmd: int, want_cmd: int) -> bool:
    """兼容两种固件：响应 CMD 等于请求 CMD，或（请求<0x80 时）请求|0x80"""
    return recv_cmd == want_cmd or (want_cmd < 0x80 and recv_cmd == (want_cmd | 0x80))


def pop_frame(buf: bytearray):
    """从缓冲弹出一帧，返回 (cmd, data, rest)；无完整有效帧返回 None"""
    r = decode(bytes(buf))
    if r is None:
        return None
    cmd, _ = r
    start = buf.find(b"\xA0")
    ln = buf[start + 1]
    rest = buf[start + ln + 2:]
    return cmd, r[1], rest


class Reader:
    """串口/pyserial 兼容传输。GUI/USB 免驱场景用 ch340_usb.open_reader() 构造同类对象"""

    def __init__(self, port: str):
        import serial  # pip3 install pyserial
        self.ser = serial.Serial(port, BAUD, timeout=1.0)
        self.usb = None

    def send(self, cmd: int, data: bytes = b""):
        self.ser.reset_input_buffer()
        self.ser.write(encode(ADDR, cmd, data))
        self.ser.flush()

    def _collect(self, seconds: float, want_cmd: int = None):
        """收帧；若指定 want_cmd 则只统计该指令的响应帧（忽略残留盘存帧等干扰）"""
        import time
        buf = bytearray()
        frames = []
        hard_deadline = time.time() + seconds + 2.0  # 续期上限，防流式模式死循环
        deadline = time.time() + seconds
        while time.time() < deadline:
            n = getattr(self.ser, "in_waiting", 0)
            if n:
                buf += self.ser.read(n)
            while True:
                got = pop_frame(buf)
                if got is None:
                    break
                cmd, data, buf = got[0], got[1], bytearray(got[2])
                if want_cmd is not None and not cmd_eq(cmd, want_cmd):
                    continue  # 干扰帧（如残留盘存流），丢弃
                frames.append((cmd, data))
                if len(frames) >= 2:
                    return frames
                deadline = min(time.time() + 0.3, hard_deadline)  # 收帧后稍等后续帧
            time.sleep(0.01)
        return frames

    def cmd(self, cmd: int, data: bytes = b"", timeout: float = 1.2):
        """发送指令并等待响应帧（可能两帧：ACK+数据），返回该指令的帧列表"""
        self.send(cmd, data)
        return self._collect(timeout, cmd)

    def close(self):
        self.ser.close()


# ---------- 高层操作 ----------

def parse_inventory(data: bytes):
    """盘存标签帧 DATA: [antenna, PC×2, EPC..., RSSI×4, freq×3]"""
    if len(data) <= 11:
        return None
    return {
        "antenna": data[0],
        "pc": data[1:3].hex().upper(),
        "epc": data[3:len(data) - 7].hex().upper(),   # EPC [3, len-7)
        "freq_khz": int.from_bytes(data[-3:], "big"),
    }


def build_read(bank: str, addr: int, word_cnt: int, pwd: str = "00000000") -> bytes:
    """READ(0x81) 线格式（IDA 逆向 libnative-lib.so rfid_encode case129 确认）：
    {memBank, wordAddr×4, wordCnt×2, password×4}，LEN=0x0E"""
    return bytes([BANKS[bank]]) + addr.to_bytes(4, "big") + word_cnt.to_bytes(2, "big") + bytes.fromhex(pwd)


# 遍历用的布局变体：confirmed 为 IDA 逆向确认格式，其余为常见厂商变体
def build_read_layout(layout: str, bank: str, addr: int, word_cnt: int, pwd: str = "00000000") -> bytes:
    mb, pw = BANKS[bank], bytes.fromhex(pwd)
    a4, c2 = addr.to_bytes(4, "big"), word_cnt.to_bytes(2, "big")
    return {
        "confirmed": bytes([mb]) + a4 + c2 + pw,          # {mb,addr×4,cnt×2,pwd×4}
        "sdk_order": bytes([mb, 0]) + c2 + a4 + pw,        # {mb,0,cnt×2,addr×4,pwd×4}
        "classic":   pw + bytes([mb, addr & 0xFF, word_cnt & 0xFF]),  # {pwd×4,mb,addr1,cnt1}
        "addr2":     bytes([mb]) + a4[2:] + c2 + pw,       # {mb,addr×2,cnt×2,pwd×4}
        "pwd_first": pw + bytes([mb]) + a4 + c2,           # {pwd×4,mb,addr×4,cnt×2}
    }[layout]


def build_write(bank: str, addr: int, word_cnt: int, pwd: str = "00000000", data: bytes = b"") -> bytes:
    """WRITE(0x82) 线格式（rfid_encode case130 确认，与 READ 顺序不同！）：
    {password×4, memBank, wordAddr×4, wordCnt×2, data...}，LEN=2*wordCnt+0x0E"""
    return bytes.fromhex(pwd) + bytes([BANKS[bank]]) + addr.to_bytes(4, "big") \
           + word_cnt.to_bytes(2, "big") + data


def build_rw(bank: str, addr: int, word_cnt: int, pwd: str = "00000000", data: bytes = b"") -> bytes:
    """兼容入口：无 data 走 READ 布局，有 data 走 WRITE 布局"""
    return build_write(bank, addr, word_cnt, pwd, data) if data else build_read(bank, addr, word_cnt, pwd)


def _status_of(frames, want_cmd):
    """标签类指令：状态码在 DATA 首字节。
    READ 为双帧式（先 0x10 ACK 帧、再 0x10+数据 帧），取数据最长的帧。"""
    best_d = None
    for c, d in frames:
        if cmd_eq(c, want_cmd) and d and (best_d is None or len(d) > len(best_d)):
            best_d = d
    return (best_d[0], best_d[1:]) if best_d else (None, b"")


def _data_of(frames, want_cmd):
    """配置类查询：DATA 即数值（无状态字节）"""
    for c, d in frames:
        if cmd_eq(c, want_cmd) and d:
            return d
    return b""


def op_info(r: Reader):
    d = _data_of(r.cmd(CMD_GET_FW_VERSION), CMD_GET_FW_VERSION)
    print(f"固件版本 : V{'.'.join(str(b) for b in d)}" if d else "固件版本 : ?")
    d = _data_of(r.cmd(CMD_GET_OUTPUT_POWER), CMD_GET_OUTPUT_POWER)
    print(f"输出功率 : {d[0]} dBm" if d else "输出功率 : ?")
    d = _data_of(r.cmd(CMD_GET_WORK_ANT), CMD_GET_WORK_ANT)
    print(f"工作天线 : {d[0]}" if d else "工作天线 : ?")


def op_inventory(r: Reader, seconds: float = 10.0, antenna: int = 0):
    """先设天线再实时盘存"""
    import time
    r.cmd(CMD_SET_WORK_ANT, bytes([antenna]))
    print(f"开始实时盘存（天线{antenna}），Ctrl-C 停止…")
    seen = {}
    r.send(CMD_REAL_TIME_INV, bytes([antenna]))
    deadline = time.time() + seconds
    buf = bytearray()
    try:
        while time.time() < deadline:
            n = getattr(r.ser, "in_waiting", 0)
            if n:
                buf += r.ser.read(n)
                while True:
                    got = pop_frame(buf)
                    if got is None:
                        break
                    cmd, data, buf = got[0], got[1], bytearray(got[2])
                    if cmd_eq(cmd, CMD_REAL_TIME_INV) or cmd_eq(cmd, CMD_CUSTOM_INV):
                        t = parse_inventory(data)
                        if t and t["epc"] not in seen:
                            seen[t["epc"]] = t
                            print(f"  EPC={t['epc']}  PC={t['pc']}  ANT={t['antenna']}  {t['freq_khz']}kHz")
                    elif len(data) == 1 and data[0] != OK:
                        print(f"  盘存失败: 0x{data[0]:02X} {STATUS.get(data[0], '')}")
            else:
                time.sleep(0.02)
    except KeyboardInterrupt:
        pass
    r.cmd(CMD_STOP_INV)
    print(f"共读到 {len(seen)} 张标签")
    return seen


def parse_read_data(d: bytes):
    """READ 数据帧 DATA 结构（真机 V2.4.1 实测）：
    [状态0x00=成功][天线][信号][PC×2][EPC×12][CRC×2][读出数据×N][N×2(BE)][01 01]
    返回 (状态, 读出数据字节)"""
    if len(d) < 5:
        return (d[0] if d else None), b""
    dl = int.from_bytes(d[-4:-2], "big")
    data = d[-4 - dl:-4] if 0 < dl <= len(d) - 4 else b""
    return d[0], data


def op_read(r: Reader, bank: str, addr: int, length_words: int, pwd: str = "00000000", retries: int = 3):
    import time
    for i in range(retries):
        frames = r.cmd(CMD_READ, build_rw(bank, addr, length_words, pwd))
        raw = None  # 取最长帧的原始 DATA（勿二次剥离状态字节）
        for c, d in frames:
            if cmd_eq(c, CMD_READ) and d and (raw is None or len(d) > len(raw)):
                raw = d
        if raw:
            st, data = parse_read_data(raw)
            if st == 0x00 and data:
                print(f"{bank} 区 @字{addr} × {length_words}字 = {data.hex().upper()}" + (f"（第{i+1}次）" if i else ""))
                return data
            last = (st, [(c, dd.hex()) for c, dd in frames])
        else:
            last = (None, [(c, dd.hex()) for c, dd in frames])
        time.sleep(0.15)
    st, frames = last
    if not frames:
        print("无响应"); return None
    print(f"读失败: status=0x{st:02X} {STATUS.get(st, '')}" if st is not None else f"异常响应: {frames}")
    return None


# 一键遍历组合：布局 × 存储区/地址/长度
SCAN_COMBOS = [
    ("TID", 0, 4), ("TID", 0, 6),
    ("EPC", 2, 6), ("EPC", 0, 8),
    ("USER", 0, 4), ("RESERVED", 0, 2),
]


def op_scan(r: Reader, on_hit=None, on_progress=None, timeout=0.8):
    """遍历所有可能性：布局×存储区×地址×长度。on_hit(bank, addr, cnt, data, layout)"""
    hits = []
    combos = [(lay, b, a, c) for lay in ("confirmed", "sdk_order", "classic", "addr2", "pwd_first")
              for (b, a, c) in SCAN_COMBOS]
    for i, (lay, bank, addr, cnt) in enumerate(combos):
        frames = r.cmd(CMD_READ, build_read_layout(lay, bank, addr, cnt), timeout)
        st, d = _status_of(frames, CMD_READ)
        tag = f"[{i+1}/{len(combos)}] {lay:9s} {bank}@{addr}×{cnt}"
        if frames and st == OK and d:
            print(f"{tag} → ✔ {d.hex().upper()}")
            hits.append((bank, addr, cnt, d, lay))
            if on_hit:
                on_hit(bank, addr, cnt, d, lay)
        elif on_progress:
            on_progress(f"{tag} → {'0x%02X %s' % (st, STATUS.get(st, '')) if frames else '静默'}")
    if not hits:
        print("全部组合无数据（确认标签已放在读写头上）")
    else:
        print(f"\n共 {len(hits)} 个组合命中，最优布局: {hits[0][4]}")
    return hits


def op_write(r: Reader, bank: str, addr: int, data_hex: str, pwd: str = "00000000", retries: int = 3):
    import time
    d = bytes.fromhex(data_hex)
    assert len(d) % 2 == 0, "写入数据须为偶数字节(字对齐)"
    for i in range(retries):
        frames = r.cmd(CMD_WRITE, build_rw(bank, addr, len(d) // 2, pwd, d))
        raw = None
        for c, fr in frames:
            if cmd_eq(c, CMD_WRITE) and fr and (raw is None or len(fr) > len(raw)):
                raw = fr
        if raw and raw[0] == 0x00:  # 本固件标签类指令成功码 = 0x00
            print(f"写入成功（{bank} @字{addr}，{len(d)//2}字）" + (f"（第{i+1}次）" if i else ""))
            return True
        time.sleep(0.15)
    print(f"写失败: {frames if frames else '无响应'}")
    return False


def selftest():
    """协议自检（无需硬件）"""
    assert checksum(bytes([0xA0, 0x03, 0x00, 0x72])) == 0xEB  # 0xA0+0x03+0x00+0x72=0x115 → 二补数 0xEB
    assert encode(0x00, CMD_GET_FW_VERSION) == bytes([0xA0, 0x03, 0x00, 0x72, 0xEB])
    f = encode(0x00, CMD_REAL_TIME_INV, b"\x00")
    assert f[:4] == bytes([0xA0, 0x04, 0x00, 0x89]) and checksum(f[:-1]) == f[-1]
    # 真机实测响应帧: A0 06 00 72 02 04 01 E1（固件 V2.04.01，CMD 不带 0x80）
    cmd, data = decode(bytes.fromhex("A0060072020401E1"))
    assert cmd == 0x72 and data == b"\x02\x04\x01", (cmd, data)
    # 真机实测: A0 04 00 77 14 D1 → 功率 20dBm
    cmd, data = decode(bytes.fromhex("A004007714D1"))
    assert cmd == 0x77 and data[0] == 20
    # 盘存标签帧解析
    t = parse_inventory(bytes([0, 0x30, 0x00]) + bytes.fromhex("E20034120136A00012") + bytes(4) + bytes([0x01, 0x6C, 0xB0]))
    assert t["epc"] == "E20034120136A00012", t
    # READ 双帧
    buf = bytearray(bytes.fromhex("A004008110C6") + bytes.fromhex("A0 0A 00 81 10 30 00 E2 00 34 12".replace(" ", "")))
    print("✔ 协议自检通过（含真机实测帧样本）")


def open_port(port: str) -> Reader:
    if port.upper() == "USB":
        from ch340_usb import open_reader
        return open_reader(BAUD)
    return Reader(port)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port", help="串口设备，或 USB 表示免驱直连（无需驱动）")
    sub = ap.add_subparsers(dest="op", required=True)
    sub.add_parser("info")
    sub.add_parser("inventory")
    sub.add_parser("scan", help="一键遍历: 布局×存储区×地址×长度")
    p = sub.add_parser("read");  p.add_argument("--bank", default="EPC", choices=BANKS)
    p.add_argument("--addr", type=int, default=2); p.add_argument("--len", type=int, default=6, dest="length")
    p.add_argument("--pwd", default="00000000")
    p = sub.add_parser("write"); p.add_argument("--bank", default="EPC", choices=BANKS)
    p.add_argument("--addr", type=int, default=2); p.add_argument("--data", required=True)
    p.add_argument("--pwd", default="00000000")
    sub.add_parser("selftest")
    a = ap.parse_args()

    if a.op == "selftest":
        return selftest()
    if not a.port:
        ap.error("需要 --port（USB 免驱，或 ls /dev/tty.* | grep -i usb）")
    r = open_port(a.port)
    try:
        if a.op == "info":
            op_info(r)
        elif a.op == "inventory":
            op_inventory(r)
        elif a.op == "scan":
            op_scan(r)
        elif a.op == "read":
            op_read(r, a.bank, a.addr, a.length, a.pwd)
        elif a.op == "write":
            op_write(r, a.bank, a.addr, a.data, a.pwd)
    finally:
        (r.usb or r.ser).close() if getattr(r, "usb", None) else r.ser.close()


if __name__ == "__main__":
    main()
