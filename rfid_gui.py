#!/usr/bin/env python3
"""915MHz UHF RFID 读写头 GUI（macOS / Linux）
依赖：pip3 install pyusb pyserial ；libusb 由 homebrew 提供（libusb 已装）
运行：DYLD_LIBRARY_PATH=/opt/homebrew/lib python3 rfid_gui.py
    串口选 "USB(免驱CH340)" 时不需要装任何内核驱动
"""
import glob
import queue
import threading
import time
import tkinter as tk
from tkinter import ttk, messagebox

import rfid_tool as P

USB_PORT_LABEL = "USB(免驱CH340)"


def scan_ports():
    pts = set(glob.glob("/dev/tty.usb*") + glob.glob("/dev/tty.wch*") + glob.glob("/dev/tty.SLAB*") +
              glob.glob("/dev/ttyUSB*") + glob.glob("/dev/ttyACM*"))
    return [USB_PORT_LABEL] + sorted(pts)


class App:
    def __init__(self, root: tk.Tk):
        self.root = root
        root.title("915MHz UHF RFID 读写工具  ·  R2000 协议 115200-8N1")
        root.geometry("900x580")
        self.reader = None
        self.inv_thread = None
        self.scan_thread = None
        self.inv_stop = threading.Event()
        self.events = queue.Queue()   # 工作线程 → UI

        # ── 连接区 ────────────────────────────────────────────
        top = ttk.Frame(root, padding=8)
        top.pack(fill="x")
        ttk.Label(top, text="串口:").pack(side="left")
        self.port_var = tk.StringVar(value=USB_PORT_LABEL)
        self.port_box = ttk.Combobox(top, textvariable=self.port_var, values=scan_ports(), width=28)
        self.port_box.pack(side="left", padx=4)
        ttk.Button(top, text="刷新", command=self.refresh_ports, width=6).pack(side="left")
        ttk.Label(top, text="  波特率: 115200").pack(side="left")
        self.btn_conn = ttk.Button(top, text="连接", command=self.toggle_conn)
        self.btn_conn.pack(side="left", padx=8)
        self.lbl_state = ttk.Label(top, text="● 未连接", foreground="gray")
        self.lbl_state.pack(side="left")
        self.lbl_info = ttk.Label(top, text="", foreground="#357")
        self.lbl_info.pack(side="right")

        # ── 主体：左盘存 / 右操作 ──────────────────────────────
        body = ttk.Frame(root, padding=(8, 0))
        body.pack(fill="both", expand=True)
        body.columnconfigure(0, weight=3)
        body.columnconfigure(1, weight=2)
        body.rowconfigure(0, weight=1)

        # 左：实时盘存
        left = ttk.LabelFrame(body, text=" 实时盘存（Inventory）", padding=6)
        left.grid(row=0, column=0, sticky="nsew", padx=(0, 6))
        bar = ttk.Frame(left); bar.pack(fill="x")
        self.btn_inv = ttk.Button(bar, text="开始盘存", command=self.toggle_inv, state="disabled")
        self.btn_inv.pack(side="left")
        self.lbl_cnt = ttk.Label(bar, text="标签: 0"); self.lbl_cnt.pack(side="right")
        cols = ("epc", "pc", "ant", "freq", "n")
        self.tree = ttk.Treeview(left, columns=cols, show="headings", height=16)
        for c, t, w in zip(cols, ("EPC", "PC", "天线", "频率kHz", "次数"), (230, 80, 50, 80, 50)):
            self.tree.heading(c, text=t); self.tree.column(c, width=w, anchor="w")
        self.tree.pack(fill="both", expand=True)
        self.tree.bind("<Button-1>", self.on_tag_click)
        self.tags = {}  # epc -> item id

        # 右：标签读写
        right = ttk.LabelFrame(body, text=" 标签读写", padding=8)
        right.grid(row=0, column=1, sticky="nsew")
        frm = ttk.Frame(right); frm.pack(fill="x")
        ttk.Label(frm, text="存储区:").grid(row=0, column=0, sticky="w", pady=2)
        self.bank_var = tk.StringVar(value="EPC")
        ttk.Combobox(frm, textvariable=self.bank_var, values=list(P.BANKS), width=8, state="readonly").grid(row=0, column=1, sticky="w")
        ttk.Label(frm, text="起始地址(字):").grid(row=1, column=0, sticky="w", pady=2)
        self.addr_var = tk.StringVar(value="2")
        ttk.Entry(frm, textvariable=self.addr_var, width=10).grid(row=1, column=1, sticky="w")
        ttk.Label(frm, text="长度(字):").grid(row=2, column=0, sticky="w", pady=2)
        self.len_var = tk.StringVar(value="6")
        ttk.Entry(frm, textvariable=self.len_var, width=10).grid(row=2, column=1, sticky="w")
        ttk.Label(frm, text="访问密码:").grid(row=3, column=0, sticky="w", pady=2)
        self.pwd_var = tk.StringVar(value="00000000")
        ttk.Entry(frm, textvariable=self.pwd_var, width=12).grid(row=3, column=1, sticky="w")
        ttk.Button(right, text="🔍 一键遍历所有可能性", command=self.do_scan).pack(fill="x", pady=(10, 3))
        ttk.Button(right, text="读标签", command=self.do_read).pack(fill="x", pady=(3, 3))
        self.read_out = tk.StringVar(value="（读结果）")
        ttk.Label(right, textvariable=self.read_out, wraplength=240, foreground="#253",
                  font=("Menlo", 12)).pack(fill="x")
        ttk.Separator(right).pack(fill="x", pady=8)
        ttk.Label(right, text="写入数据(HEX, 字对齐):").pack(anchor="w")
        self.write_var = tk.StringVar()
        ttk.Entry(right, textvariable=self.write_var, font=("Menlo", 12)).pack(fill="x", pady=3)
        ttk.Button(right, text="写入标签", command=self.do_write).pack(fill="x")
        ttk.Button(right, text="写完校验(重读)", command=self.do_read).pack(fill="x", pady=(3, 0))
        ttk.Label(right, text="提示：写失败时多试几次；写 EPC 区从地址 2 开始（前 2 字是 PC）",
                  foreground="#965", wraplength=240).pack(fill="x", pady=4)

        self.log = tk.Text(root, height=6, state="disabled")
        self.log.pack(fill="x", padx=8, pady=(4, 8))

        root.after(100, self.pump_events)

    # ── 基础 ───────────────────────────────────────────────
    def logline(self, s):
        self.log.configure(state="normal")
        self.log.insert("end", time.strftime("%H:%M:%S ") + s + "\n")
        self.log.see("end")
        self.log.configure(state="disabled")

    def refresh_ports(self):
        self.port_box.configure(values=scan_ports())

    def connected(self):
        return self.reader is not None

    def toggle_conn(self):
        if self.connected():
            self.inv_stop.set()
            try:
                (self.reader.usb or self.reader.ser).close()
            except Exception:
                pass
            self.reader = None
            self.btn_conn.configure(text="连接")
            self.lbl_state.configure(text="● 未连接", foreground="gray")
            self.btn_inv.configure(text="开始盘存", state="disabled")
            self.lbl_info.configure(text="")
            return
        try:
            self.reader = P.open_port(self.port_var.get().strip())
        except Exception as e:
            messagebox.showerror("连接失败", f"{e}\n\nUSB 免驱模式请用以下命令启动本程序：\n"
                                "DYLD_LIBRARY_PATH=/opt/homebrew/lib python3 rfid_gui.py")
            self.reader = None
            return
        self.btn_conn.configure(text="断开")
        self.lbl_state.configure(text="● 已连接", foreground="green")
        self.btn_inv.configure(state="normal")
        threading.Thread(target=self.query_info, daemon=True).start()

    def query_info(self):
        try:
            fw = P._data_of(self.reader.cmd(P.CMD_GET_FW_VERSION), P.CMD_GET_FW_VERSION)
            pw = P._data_of(self.reader.cmd(P.CMD_GET_OUTPUT_POWER), P.CMD_GET_OUTPUT_POWER)
            ver = ".".join(str(b) for b in fw) if fw else "?"
            pwr = f"{pw[0]}dBm" if pw else "?"
            self.events.put(("info", f"固件 V{ver} · 功率 {pwr}"))
        except Exception as e:
            self.events.put(("info", f"查询失败: {e}"))

    # ── 盘存 ───────────────────────────────────────────────
    def toggle_inv(self):
        if self.inv_thread and self.inv_thread.is_alive():
            self.inv_stop.set()
            self.btn_inv.configure(text="开始盘存")
            return
        if not self.connected():
            return
        self.inv_stop.clear()
        self.tags.clear()
        for it in self.tree.get_children():
            self.tree.delete(it)
        self.btn_inv.configure(text="停止盘存")
        self.inv_thread = threading.Thread(target=self.run_inventory, daemon=True)
        self.inv_thread.start()

    def run_inventory(self):
        r = self.reader
        buf = bytearray()
        try:
            r.cmd(P.CMD_SET_WORK_ANT, b"\x00")
            r.send(P.CMD_REAL_TIME_INV, b"\x00")
            while not self.inv_stop.is_set():
                n = getattr(r.ser, "in_waiting", 0)
                if n:
                    buf += r.ser.read(n)
                    while True:
                        got = P.pop_frame(buf)
                        if got is None:
                            break
                        cmd, data, buf = got[0], got[1], bytearray(got[2])
                        if P.cmd_eq(cmd, P.CMD_REAL_TIME_INV) or P.cmd_eq(cmd, P.CMD_CUSTOM_INV):
                            t = P.parse_inventory(data)
                            if t:
                                self.events.put(("tag", t))
                        elif len(data) == 1 and data[0] != P.OK:
                            self.events.put(("log", f"盘存失败: 0x{data[0]:02X} {P.STATUS.get(data[0], '')}"))
                else:
                    time.sleep(0.02)
        except Exception as e:
            self.events.put(("log", f"盘存中断: {e}"))
        finally:
            try:
                r.cmd(P.CMD_STOP_INV)
            except Exception:
                pass

    def on_tag_click(self, _event):
        # 点击标签行自动把 EPC 填进写入框（便于改写）
        sel = self.tree.selection()
        if sel:
            self.write_var.set(self.tree.set(sel[0], "epc"))

    # ── 读写 ───────────────────────────────────────────────
    def do_scan(self):
        if not self.connected():
            return
        if self.scan_thread and self.scan_thread.is_alive():
            return
        self.events.put(("log", "开始遍历: 布局×存储区×地址×长度（确认标签已放好）…"))

        def work():
            try:
                P.op_scan(
                    self.reader,
                    on_hit=lambda b, a, c, d, lay: self.events.put(
                        ("log", f"✔ 命中 {lay} {b}@{a}×{c}字 = {d.hex().upper()}")),
                )
            except Exception as e:
                self.events.put(("log", f"遍历中断: {e}"))
        self.scan_thread = threading.Thread(target=work, daemon=True)
        self.scan_thread.start()

    def do_read(self):
        if not self.connected():
            return
        bank = self.bank_var.get()
        addr = int(self.addr_var.get())
        length = int(self.len_var.get())
        pwd = self.pwd_var.get().strip()
        self.read_out.set("读取中…")

        def work():
            try:
                frames = self.reader.cmd(P.CMD_READ, P.build_rw(bank, addr, length, pwd))
                raw = None
                for c, d in frames:
                    if P.cmd_eq(c, P.CMD_READ) and d and (raw is None or len(d) > len(raw)):
                        raw = d
                if raw:
                    st, data = P.parse_read_data(raw)
                    if st == 0x00 and data:
                        out = f"{bank}@{addr}×{length}字: {data.hex().upper()}"
                    else:
                        out = f"读失败 status=0x{st:02X} {P.STATUS.get(st, '')}"
                else:
                    out = "无响应（确认标签已放在读写头上）"
            except Exception as e:
                out = f"异常: {e}"
            self.events.put(("read", out))
        threading.Thread(target=work, daemon=True).start()

    def do_write(self):
        if not self.connected():
            return
        bank = self.bank_var.get()
        addr = int(self.addr_var.get())
        pwd = self.pwd_var.get().strip()
        data = self.write_var.get().strip()
        try:
            d = bytes.fromhex(data)
        except ValueError:
            messagebox.showerror("格式错误", "写入数据必须是合法 HEX 字符串")
            return
        if len(d) % 2:
            messagebox.showerror("格式错误", "写入数据须为偶数字节（字对齐）")
            return

        def work():
            try:
                frames = self.reader.cmd(P.CMD_WRITE, P.build_rw(bank, addr, len(d) // 2, pwd, d))
                raw = None
                for c, fr in frames:
                    if P.cmd_eq(c, P.CMD_WRITE) and fr and (raw is None or len(fr) > len(raw)):
                        raw = fr
                if raw and raw[0] == 0x00:
                    out = "✔ 写入成功"
                elif raw:
                    out = f"✘ 写失败 status=0x{raw[0]:02X} {P.STATUS.get(raw[0], '')}"
                else:
                    out = "✘ 无响应"
            except Exception as e:
                out = f"✘ 异常: {e}"
            self.events.put(("log", out))
        threading.Thread(target=work, daemon=True).start()

    # ── UI 事件泵 ──────────────────────────────────────────
    def pump_events(self):
        try:
            while True:
                kind, val = self.events.get_nowait()
                if kind == "info":
                    self.lbl_info.configure(text=val)
                    self.logline("设备信息: " + val)
                elif kind == "tag":
                    epc = val["epc"]
                    if epc in self.tags:
                        iid = self.tags[epc]
                        self.tree.set(iid, "n", int(self.tree.set(iid, "n")) + 1)
                    else:
                        iid = self.tree.insert("", "end", values=(epc, val["pc"], val["antenna"], val["freq_khz"], 1))
                        self.tags[epc] = iid
                        self.tree.selection_set(iid)
                    self.lbl_cnt.configure(text=f"标签: {len(self.tags)}")
                elif kind == "read":
                    self.read_out.set(val)
                elif kind == "log":
                    self.logline(val)
        except queue.Empty:
            pass
        self.root.after(100, self.pump_events)


if __name__ == "__main__":
    root = tk.Tk()
    try:
        ttk.Style().theme_use("aqua")
    except tk.TclError:
        pass
    App(root)
    root.mainloop()
