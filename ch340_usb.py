#!/usr/bin/env python3
"""CH340/CH341 用户态 USB 驱动（免内核驱动，pyusb 实现）
波特率算法来源：Linux 内核 drivers/usb/serial/ch341.c
    baud = 48000000 / (2^(12 - 3*ps - fact) * div)  ps∈0..3, fact∈0..1
    val  = (0x100 - div) << 8 | fact << 2 | ps   (版本>0x27 时 |0x80)
用法见文件底部自测。运行需：DYLD_LIBRARY_PATH=/opt/homebrew/lib（homebrew libusb）
"""
import usb.core
import usb.util

VID_CH340, PID_CH340 = 0x1A86, 0x5523
CH341_CLKRATE = 48_000_000

REQ_READ_VERSION = 0x5F
REQ_WRITE_REG    = 0x9A
REQ_READ_REG     = 0x95
REQ_SERIAL_INIT  = 0xA1
REQ_MODEM_CTRL   = 0xA4

REG_PRESCALER = 0x12
REG_DIVISOR   = 0x13
REG_LCR       = 0x18
REG_LCR2      = 0x25

LCR_CS8 = 0x03
LCR_ENABLE_RX = 0x80
LCR_ENABLE_TX = 0x40


def get_divisor(baud: int) -> int:
    """ch341.c ch341_get_divisor：fact 0..1、ps 从高到低搜索，div 取最接近值"""
    best = None
    for fact in (0, 1):
        for ps in (3, 2, 1, 0):
            clk_div = 1 << (12 - 3 * ps - fact)
            div = round(CH341_CLKRATE / (clk_div * baud))
            lo, hi = (9, 256) if fact else (2, 256)
            if not lo <= div <= hi:
                continue
            actual = CH341_CLKRATE / (clk_div * div)
            err = abs(actual - baud)
            cand = (err, fact == 0, -ps, (0x100 - div) << 8 | fact << 2 | ps)
            if best is None or cand < best:
                best = cand
    if best is None:
        raise ValueError(f"波特率 {baud} 超出 CH340 支持范围")
    return best[3]


class Ch340:
    def __init__(self, serial=None):
        self.dev = usb.core.find(idVendor=VID_CH340, idProduct=PID_CH340,
                                 custom_match=lambda d: serial in (None, usb.util.get_string(d, d.iSerialNumber) if d.iSerialNumber else None))
        if self.dev is None:
            raise IOError("未找到 CH340 设备")
        try:  # macOS 后端不支持查询内核驱动，直接尝试
            if self.dev.is_kernel_driver_active(0):
                self.dev.detach_kernel_driver(0)
        except usb.core.USBError:
            pass
        self.dev.set_configuration()
        cfg = self.dev.get_active_configuration()
        intf = cfg[(0, 0)]
        self.ep_out = usb.util.find_descriptor(intf, custom_match=lambda e: usb.util.endpoint_direction(e.bEndpointAddress) == usb.util.ENDPOINT_OUT)
        self.ep_in = usb.util.find_descriptor(intf, custom_match=lambda e: usb.util.endpoint_direction(e.bEndpointAddress) == usb.util.ENDPOINT_IN and (e.bmAttributes & 0x03) == 0x02)
        self.version = 0

    def ctrl_out(self, req, value, index=0):
        self.dev.ctrl_transfer(0x40, req, value, index, None, 1000)

    def ctrl_in(self, req, value, index=0, length=2):
        return bytes(self.dev.ctrl_transfer(0xC0, req, value, index, length, 1000))

    def open(self, baud=115200):
        ver = self.ctrl_in(REQ_READ_VERSION, 0)
        self.version = ver[0] if ver else 0
        self.ctrl_out(REQ_SERIAL_INIT, 0)              # 0xA1
        val = get_divisor(baud)
        if self.version > 0x27:
            val |= 0x80
        self.ctrl_out(REQ_WRITE_REG, REG_DIVISOR << 8 | REG_PRESCALER, val)   # 0x9A
        self.ctrl_out(REQ_WRITE_REG, REG_LCR2 << 8 | REG_LCR, LCR_ENABLE_RX | LCR_ENABLE_TX | LCR_CS8)
        self.ctrl_out(REQ_MODEM_CTRL, (~(1 << 6 | 1 << 5)) & 0xFF3F)          # RTS|DTR 有效

    def write(self, data: bytes):
        self.ep_out.write(data, 1000)

    def read(self, length=64, timeout_ms=200) -> bytes:
        try:
            return bytes(self.ep_in.read(length, timeout_ms))
        except usb.core.USBError:
            return b""

    def close(self):
        usb.util.dispose_resources(self.dev)


class Ch340SerialAdapter(Ch340):
    """pyserial 兼容接口：让 rfid_tool.Reader / rfid_gui 直接使用 USB 免驱传输"""

    def __init__(self, serial=None):
        super().__init__(serial)
        self._rbuf = b""

    def reset_input_buffer(self):
        self._rbuf = b""
        for _ in range(4):  # 封顶清空：盘存流式模式下数据无限，不能读到空为止
            if not Ch340.read(self, 64, 30):
                break

    def flush(self):
        pass

    @property
    def in_waiting(self):
        if not self._rbuf:
            self._rbuf = Ch340.read(self, 64, 20)
        return len(self._rbuf)

    def read(self, size=64, timeout_ms=200):
        """pyserial 风格 read(n)：优先消费缓冲，不足再从 USB 收"""
        import time as _t
        deadline = _t.time() + timeout_ms / 1000
        out = b""
        while len(out) < size:
            if self._rbuf:
                take, self._rbuf = self._rbuf[:size - len(out)], self._rbuf[size - len(out):]
                out += take
            else:
                chunk = Ch340.read(self, size - len(out), 50)
                if chunk:
                    out += chunk
                elif _t.time() >= deadline:
                    break
        return out


def open_reader(baud=115200):
    """返回按 rfid_tool.Reader 接口就绪的 USB 读写头连接（先停掉可能残留的盘存流）"""
    import time
    import rfid_tool as P
    ch = Ch340SerialAdapter()
    ch.open(baud)
    ch.write(P.encode(P.ADDR, P.CMD_STOP_INV))
    time.sleep(0.2)

    class _R(P.Reader):
        def __init__(self):
            self.ser = ch

    r = _R()
    r.usb = ch
    return r


if __name__ == "__main__":
    import sys
    sys.path.insert(0, ".")
    import rfid_tool as P

    ch = Ch340()
    ch.open(115200)
    print(f"CH340 版本 0x{ch.version:02X}，已按 115200-8N1 初始化")


    # 直接用协议函数测试：读固件版本
    ch.write(P.encode(P.ADDR, P.CMD_GET_FW_VERSION))
    import time
    buf = b""
    t0 = time.time()
    while time.time() - t0 < 1.5:
        buf += ch.read()
        r = P.decode(buf)
        if r:
            cmd, status, payload = r
            print(f"✔ 读写头响应! cmd=0x{cmd:02X} status=0x{status:02X} data={payload.hex().upper()}")
            print(f"  → 固件版本: {payload.hex().upper()}")
            break
    else:
        print(f"✘ 1.5 秒内无响应，收到原始字节: {buf.hex()}")
