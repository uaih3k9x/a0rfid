package com.rfid.helper;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;

import java.util.HashMap;

/** CH340/CH341 USB 转串口用户态驱动（Android USB Host）
 *  波特率算法：Linux 内核 ch341.c  baud = 48e6 / (2^(12-3*ps-fact) * div) */
public class Ch340Usb {
    public static final int VID = 0x1A86, PID = 0x5523;
    private static final int CLK = 48_000_000;

    private final UsbManager mgr;
    private final Context ctx;
    private UsbDeviceConnection conn;
    private UsbEndpoint epIn, epOut;
    private int version = 0;
    public String error = "";

    public Ch340Usb(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        mgr = (UsbManager) this.ctx.getSystemService(Context.USB_SERVICE);
    }

    public static UsbDevice find(UsbManager mgr) {
        HashMap<String, UsbDevice> list = mgr.getDeviceList();
        for (UsbDevice d : list.values())
            if (d.getVendorId() == VID && d.getProductId() == PID) return d;
        return null;
    }

    /** 请求 USB 权限（异步，结果经 ACTION_USB_PERMISSION 广播） */
    public void requestPermission(UsbDevice dev) {
        int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        PendingIntent pi = PendingIntent.getBroadcast(
                ctx, 0, new Intent("com.rfid.helper.USB_PERMISSION"), flags);
        mgr.requestPermission(dev, pi);
    }

    public boolean open(UsbDevice dev, int baud) {
        try {
            conn = mgr.openDevice(dev);
            if (conn == null) { error = "openDevice 失败"; return false; }
            UsbInterface itf = dev.getInterface(0);
            if (!conn.claimInterface(itf, true)) { error = "claimInterface 失败"; return false; }
            for (int i = 0; i < itf.getEndpointCount(); i++) {
                UsbEndpoint e = itf.getEndpoint(i);
                if (e.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (e.getDirection() == UsbConstants.USB_DIR_IN) epIn = e; else epOut = e;
                }
            }
            byte[] ver = ctrlIn(0x5F, 0, 0, 2);
            if (ver != null && ver.length >= 1) version = ver[0] & 0xFF;
            ctrlOut(0xA1, 0, 0);                                   // SERIAL_INIT
            int val = divisor(baud);
            if (version > 0x27) val |= 0x80;
            ctrlOut(0x9A, (0x13 << 8) | 0x12, val);                // 波特率
            ctrlOut(0x9A, (0x25 << 8) | 0x18, 0xC3);               // LCR: 8N1 收发使能
            ctrlOut(0xA4, (~(0x40 | 0x20)) & 0xFF3F, 0);           // RTS|DTR
            return true;
        } catch (Exception e) {
            error = e.getMessage();
            return false;
        }
    }

    /** ch341.c 搜索最优 ps/fact/div */
    static int divisor(int baud) {
        int bestVal = 0;
        double bestErr = Double.MAX_VALUE;
        for (int fact = 0; fact < 2; fact++)
            for (int ps = 3; ps >= 0; ps--) {
                long clkDiv = 1L << (12 - 3 * ps - fact);
                long div = Math.round((double) CLK / (clkDiv * baud));
                long lo = fact == 1 ? 9 : 2, hi = 256;
                if (div < lo || div > hi) continue;
                double err = Math.abs((double) CLK / (clkDiv * div) - baud);
                if (err < bestErr) { bestErr = err; bestVal = (int) (((0x100 - div) << 8) | (fact << 2) | ps); }
            }
        return bestVal;
    }

    private void ctrlOut(int req, int val, int idx) {
        conn.controlTransfer(0x40, req, val, idx, null, 0, 1000);
    }

    private byte[] ctrlIn(int req, int val, int idx, int len) {
        byte[] buf = new byte[len];
        int n = conn.controlTransfer(0xC0, req, val, idx, buf, len, 1000);
        if (n < 0) return null;
        byte[] out = new byte[Math.max(n, 0)];
        System.arraycopy(buf, 0, out, 0, out.length);
        return out;
    }

    public int write(byte[] data, int timeoutMs) {
        return conn.bulkTransfer(epOut, data, data.length, timeoutMs);
    }

    /** 尽量读满 size 字节；超时返回已收到的部分（可能为空数组） */
    public byte[] read(int size, int timeoutMs) {
        byte[] part = new byte[size];
        int n = conn.bulkTransfer(epIn, part, size, Math.min(timeoutMs, 50));
        if (n <= 0) return new byte[0];
        byte[] out = new byte[n];
        System.arraycopy(part, 0, out, 0, n);
        return out;
    }

    public boolean isOpen() { return conn != null; }

    public void close() {
        try { if (conn != null) conn.close(); } catch (Exception ignored) {}
        conn = null;
    }
}
