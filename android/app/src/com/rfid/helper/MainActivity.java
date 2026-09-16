package com.rfid.helper;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int BG = 0xFF10151C, CARD = 0xFF1A2230, ACCENT = 0xFF00C2A8, TXT = 0xFFE8EDF4, DIM = 0xFF7B8794;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Ch340Usb ch340;
    private TextView tvState, tvInfo, tvCount;
    private Button btnScan, btnSettings;
    private ListView list;
    private final Map<String, RfidProto.Tag> tags = new LinkedHashMap<>();
    private ArrayAdapter<String> adapter;
    private volatile boolean invOn = false, connected = false;
    private Thread invThread;
    private final Object lock = new Object();
    private int powerNow = 20, antennaNow = 0;

    // ── 生命周期 ─────────────────────────────────────────
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();
        IntentFilter f = new IntentFilter();
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        f.addAction("com.rfid.helper.USB_PERMISSION");
        registerReceiver(usbRx, f, null, ui);   // API 33+ RECEIVER_NOT_EXPORTED 未用：侧载场景 targetSdk 28
    }

    @Override protected void onDestroy() {
        unregisterReceiver(usbRx);
        closeDevice();
        super.onDestroy();
    }

    @Override protected void onResume() {
        super.onResume();
        tryConnect();
    }

    private final BroadcastReceiver usbRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(a)) tryConnect();
            else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a)) closeDevice();
            else if ("com.rfid.helper.USB_PERMISSION".equals(a)) tryConnect();
        }
    };

    // ── 连接 ─────────────────────────────────────────────
    private void tryConnect() {
        if (connected) return;
        UsbManager mgr = (UsbManager) getSystemService(USB_SERVICE);
        UsbDevice dev = Ch340Usb.find(mgr);
        if (dev == null) { setState("● 未连接（插 USB）", DIM); return; }
        if (!mgr.hasPermission(dev)) {
            if (ch340 == null) ch340 = new Ch340Usb(this);
            ch340.requestPermission(dev);
            setState("● 等待 USB 授权…", 0xFFF5A623);
            return;
        }
        if (ch340 == null) ch340 = new Ch340Usb(this);
        if (ch340.open(dev, RfidProto.BAUD)) {
            connected = true;
            setState("● 已连接", ACCENT);
            new Thread(this::queryInfo).start();
            startInventory();
        } else {
            setState("● 连接失败: " + ch340.error, Color.RED);
        }
    }

    private void closeDevice() {
        invOn = false;
        connected = false;
        if (ch340 != null) ch340.close();
        setState("● 未连接（插 USB）", DIM);
        ui.post(() -> tvInfo.setText(""));
    }

    private void queryInfo() {
        int[][] rs = {cmd(RfidProto.CMD_GET_FW_VERSION, new byte[0]), cmd(RfidProto.CMD_GET_OUTPUT_POWER, new byte[0])};
        final String s;
        if (rs[0] != null && rs[0].length > 1 && rs[1] != null && rs[1].length > 1) {
            powerNow = rs[1][1];
            s = String.format("固件 V%d.%d.%d   功率 %d dBm", rs[0][1], rs[0][2], rs[0][3], rs[1][1]);
        } else s = "设备无响应（可能死机，重新插拔 USB）";
        ui.post(() -> tvInfo.setText(s));
    }

    // ── 核心指令（同步，需在后台线程调用）────────────────
    /** 返回匹配 cmd 的最长帧 {cmd,data...}，超时返回 null */
    private int[] cmd(int cmd, byte[] dataArea) {
        return cmd(cmd, dataArea, 1200);
    }

    private int[] cmd(int cmd, byte[] dataArea, int timeoutMs) {
        synchronized (lock) {
            if (ch340 == null || !connected) return null;
            byte[] out = new byte[0];
            ch340.write(RfidProto.encode(cmd, dataArea), 1000);
            long deadline = System.currentTimeMillis() + timeoutMs + 800;
            int[] best = null;
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            while (System.currentTimeMillis() < deadline) {
                byte[] chunk = ch340.read(256, 60);
                if (chunk.length > 0) buf.write(chunk, 0, chunk.length);
                byte[] b = buf.toByteArray();
                int off = 0;
                while (true) {
                    int[] fr = RfidProto.parseFrame(b, off, b.length);
                    if (fr == null) break;
                    int len = RfidProto.frameLen(b, off);
                    off += len;
                    if ((fr[0] & 0x7F) == (cmd & 0x7F) || (cmd < 0x80 && fr[0] == ((cmd | 0x80) & 0xFF))) {
                        if (best == null || fr.length > best.length) best = fr;
                        if (fr.length > 1) return best;     // 数据帧到齐
                    }
                }
                if (off > 0 && off >= b.length) buf.reset();
                else if (off > 0) { byte[] rest = new byte[b.length - off]; System.arraycopy(b, off, rest, 0, rest.length); buf.reset(); buf.write(rest, 0, rest.length); }
            }
            return best;
        }
    }

    // ── 盘存 ─────────────────────────────────────────────
    private void startInventory() {
        if (!connected || (invThread != null && invThread.isAlive())) return;
        invOn = true;
        setScanBtn();
        invThread = new Thread(() -> {
            synchronized (lock) {
                if (ch340 != null && connected) {
                    ch340.write(RfidProto.encode(RfidProto.CMD_SET_WORK_ANT, new byte[]{(byte) antennaNow}), 500);
                    try { Thread.sleep(60); } catch (InterruptedException ignored) {}
                    ch340.write(RfidProto.encode(RfidProto.CMD_REAL_TIME_INV, new byte[]{(byte) antennaNow}), 500);
                }
            }
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            int empty = 0;
            while (invOn && connected) {
                byte[] chunk;
                synchronized (lock) { chunk = ch340 != null ? ch340.read(512, 80) : new byte[0]; }
                if (chunk.length == 0) { if (++empty > 400) break; continue; }
                empty = 0;
                buf.write(chunk, 0, chunk.length);
                byte[] b = buf.toByteArray();
                int off = 0;
                while (true) {
                    int[] fr = RfidProto.parseFrame(b, off, b.length);
                    if (fr == null) break;
                    off += RfidProto.frameLen(b, off);
                    if ((fr[0] & 0x7F) == (RfidProto.CMD_REAL_TIME_INV & 0x7F)) {
                        RfidProto.Tag t = RfidProto.parseTag(fr);
                        if (t != null) onTag(t);
                    }
                }
                if (off >= b.length) buf.reset();
                else if (off > 0) { byte[] rest = new byte[b.length - off]; System.arraycopy(b, off, rest, 0, rest.length); buf.reset(); buf.write(rest, 0, rest.length); }
            }
            synchronized (lock) {
                if (ch340 != null && connected)
                    ch340.write(RfidProto.encode(RfidProto.CMD_STOP_INV, new byte[0]), 500);
            }
        }, "inventory");
        invThread.start();
    }

    private void stopInventoryAndWait() {
        invOn = false;
        setScanBtn();
        if (invThread != null) {
            try { invThread.join(2500); } catch (InterruptedException ignored) {}
            invThread = null;
        }
    }

    private void onTag(RfidProto.Tag t) {
        ui.post(() -> {
            RfidProto.Tag old = tags.get(t.epc);
            if (old != null) { old.count++; refreshList(); return; }
            tags.put(t.epc, t);
            refreshList();
            Toast.makeText(this, "新标签 " + t.epc, Toast.LENGTH_SHORT).show();
        });
    }

    private void refreshList() {
        List<String> rows = new ArrayList<>();
        for (RfidProto.Tag t : tags.values())
            rows.add(String.format("%s\n×%d  PC:%s  %d kHz", t.epc, t.count, t.pc, t.freqKhz));
        adapter.clear();
        adapter.addAll(rows);
        adapter.notifyDataSetChanged();
        tvCount.setText("标签: " + tags.size());
    }

    // ── 标签操作 ─────────────────────────────────────────
    private void pauseForOp(Runnable op) {
        new Thread(() -> { stopInventoryAndWait(); op.run(); startInventory(); }).start();
    }

    private void readAll(final String epc) {
        pauseForOp(() -> {
            StringBuilder sb = new StringBuilder();
            byte[] pwd = new byte[4];
            int[][] tries = {
                    {RfidProto.CMD_READ, 2, 0, 6},   // TID@0×6
                    {RfidProto.CMD_READ, 2, 0, 4},   // TID@0×4 兜底
                    {RfidProto.CMD_READ, 1, 1, 7},   // EPC@1×7 (PC+全EPC)
                    {RfidProto.CMD_READ, 1, 2, 6},   // EPC@2×6 (纯EPC)
                    {RfidProto.CMD_READ, 3, 0, 8},   // USER@0×8
                    {RfidProto.CMD_READ, 3, 0, 4},   // USER@0×4 兜底
                    {RfidProto.CMD_READ, 0, 0, 4},   // RESERVED@0×4
            };
            String[] names = {"TID", "TID", "EPC(PC+EPC)", "EPC(纯)", "USER", "USER", "RESERVED(密钥区)"};
            for (int i = 0; i < tries.length; i++) {
                int[] tr = tries[i];
                String name = names[i];
                if (i % 2 == 1 && sb.indexOf(names[i - 1] + ":") >= 0) continue;  // 主行成功则跳过兜底行
                int[] fr = null;
                for (int att = 0; att < 3 && fr == null; att++)
                    fr = cmd(tr[0], RfidProto.buildRead(tr[1], tr[2], tr[3], pwd), 900);
                byte[] data = fr == null ? null : RfidProto.parseReadData(fr);
                if (data == null) {
                    String st = fr != null && fr.length > 1 ? RfidProto.statusText(fr[1]) : "无响应";
                    if (i % 2 == 0 || sb.indexOf(name + ":") < 0) sb.append(name).append(": ").append(st).append("\n");
                    continue;
                }
                String hexData = RfidProto.hex(data);
                sb.append(name).append(": ").append(hexData).append("\n");
                if (name.startsWith("EPC") && hexData.length() >= epc.length()
                        && !hexData.toUpperCase().contains(epc))
                    sb.append("  ⚠ 与盘存 EPC 不一致\n");
            }
            final String out = sb.toString();
            ui.post(() -> new AlertDialog.Builder(this)
                    .setTitle("读取结果")
                    .setMessage(out)
                    .setPositiveButton("关闭", null).show());
        });
    }

    private void writeDialog(final String presetEpc) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(40, 30, 40, 10);
        TextView tip = tv(presetEpc == null ? "输入要写入的 EPC（HEX），写 EPC 区从字 2 开始" : "把【新标签】放到读写头上，然后确认写入", DIM, 14);
        final EditText input = new EditText(this);
        input.setText(presetEpc == null ? "" : presetEpc);
        input.setTextColor(TXT);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        box.addView(tip); box.addView(input);
        new AlertDialog.Builder(this)
                .setTitle(presetEpc == null ? "写标签 EPC" : "复制 EPC 到新标签")
                .setView(box)
                .setPositiveButton("写入", (d, w) -> {
                    String hexStr = input.getText().toString().trim();
                    final byte[] data = RfidProto.unhex(hexStr);
                    if (data.length < 2 || data.length % 2 != 0 || data.length > 60) {
                        toast("HEX 长度无效（须偶数字节）"); return;
                    }
                    pauseForOp(() -> {
                        String result = null;
                        for (int attempt = 1; attempt <= 3 && result == null; attempt++) {
                            int[] fr = cmd(RfidProto.CMD_WRITE,
                                    RfidProto.buildWrite(1, 2, data.length / 2, new byte[4], data), 1500);
                            if (fr != null && fr.length > 1 && fr[1] == 0x00) {   // 0x00=成功（真机验证）
                                int[] chk = cmd(RfidProto.CMD_READ, RfidProto.buildRead(1, 2, data.length / 2, new byte[4]), 1000);
                                byte[] rd = chk == null ? null : RfidProto.parseReadData(chk);
                                String rdHex = rd == null ? "" : RfidProto.hex(rd);
                                result = rdHex.equalsIgnoreCase(RfidProto.hex(data))
                                        ? "✔ 写入成功并校验一致"
                                        : "✔ 已写入（校验读到: " + rdHex + "）";
                            } else {
                                String st = (fr != null && fr.length > 1) ? RfidProto.statusText(fr[1]) : "无响应";
                                if (attempt == 3) result = "✘ 写入失败: " + st;
                            }
                        }
                        final String r = result;
                        ui.post(() -> new AlertDialog.Builder(this).setTitle("写标签").setMessage(r)
                                .setPositiveButton("好", null).show());
                    });
                })
                .setNegativeButton("取消", null).show();
    }

    private void settingsDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(40, 30, 40, 10);
        TextView t1 = tv("发射功率 dBm（上限 20）", DIM, 14);
        final EditText inPower = new EditText(this);
        inPower.setText(String.valueOf(powerNow));
        inPower.setInputType(InputType.TYPE_CLASS_NUMBER);
        inPower.setTextColor(TXT);
        TextView t2 = tv("工作天线（0 或 1）", DIM, 14);
        final EditText inAnt = new EditText(this);
        inAnt.setText(String.valueOf(antennaNow));
        inAnt.setInputType(InputType.TYPE_CLASS_NUMBER);
        inAnt.setTextColor(TXT);
        box.addView(t1); box.addView(inPower); box.addView(t2); box.addView(inAnt);
        new AlertDialog.Builder(this)
                .setTitle("设备设置")
                .setView(box)
                .setPositiveButton("应用", (d, w) -> {
                    final int p = Math.min(20, Math.max(0, parseInt(inPower.getText().toString(), powerNow)));
                    final int a = parseInt(inAnt.getText().toString(), antennaNow) & 1;
                    pauseForOp(() -> {
                        int[] r1 = cmd(RfidProto.CMD_SET_OUTPUT_POWER, new byte[]{(byte) p});
                        int[] r2 = cmd(RfidProto.CMD_SET_WORK_ANT, new byte[]{(byte) a});
                        final boolean okP = r1 != null && r1.length > 1 && r1[1] == RfidProto.OK;
                        final boolean okA = r2 != null && r2.length > 1 && r2[1] == RfidProto.OK;
                        if (okP) powerNow = p;
                        if (okA) antennaNow = a;
                        ui.post(() -> toast((okP ? "功率已设 " + p + "dBm " : "功率设置失败 ")
                                + (okA ? "天线已设 " + a : "天线设置失败")));
                    });
                })
                .setNegativeButton("取消", null).show();
    }

    // ── UI 构建 ──────────────────────────────────────────
    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(36, 50, 36, 36);
        scroll.addView(root);

        TextView title = tv("RFID 助手", TXT, 26);
        title.setGravity(Gravity.CENTER);
        root.addView(title);
        tvState = tv("● 未连接（插 USB）", DIM, 15);
        tvState.setGravity(Gravity.CENTER);
        root.addView(tvState);
        tvInfo = tv("", DIM, 13);
        tvInfo.setGravity(Gravity.CENTER);
        root.addView(tvInfo);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(0, 24, 0, 8);
        tvCount = tv("标签: 0", ACCENT, 16);
        btnSettings = mkBtn("⚙ 设置", CARD);
        LinearLayout.LayoutParams lp0 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tvCount.setLayoutParams(lp0);
        btnSettings.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        bar.addView(tvCount); bar.addView(btnSettings);
        root.addView(bar);

        btnScan = mkBtn("▶ 开始盘存", ACCENT);
        btnScan.setTextColor(0xFF06231E);
        root.addView(btnScan, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        list = new ListView(this);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        list.setAdapter(adapter);
        list.setDivider(new android.graphics.drawable.ColorDrawable(0xFF232D3D));
        list.setDividerHeight(2);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        btnScan.setOnClickListener(v -> {
            if (!connected) { toast("未连接设备"); return; }
            if (invOn) stopInventoryAndWait(); else startInventory();
        });
        btnSettings.setOnClickListener(v -> settingsDialog());
        list.setOnItemClickListener((p, v, pos, id) -> {
            final String epc = new ArrayList<>(tags.keySet()).get(pos);
            new AlertDialog.Builder(this)
                    .setTitle("标签操作")
                    .setMessage("EPC: " + epc + "\n次数: " + ((RfidProto.Tag) tags.values().toArray()[pos]).count)
                    .setPositiveButton("📖 读全部数据", (d, w) -> readAll(epc))
                    .setNeutralButton("✍ 复制到新标签", (d, w) -> writeDialog(epc))
                    .setNegativeButton("关闭", null).show();
        });

        setContentView(scroll);
    }

    private TextView tv(String s, int color, float size) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextColor(color); t.setTextSize(size);
        t.setPadding(0, 8, 0, 8);
        return t;
    }

    private Button mkBtn(String s, int bg) {
        Button b = new Button(this, null, 0);
        b.setText(s); b.setTextColor(TXT); b.setTextSize(17);
        b.setPadding(0, 30, 0, 30);
        b.setBackground(new android.graphics.drawable.GradientDrawable() {{
            setColor(bg);
            setCornerRadius(22);
        }});
        b.setOnTouchListener((v, e) -> false);
        return b;
    }

    private void setScanBtn() {
        ui.post(() -> btnScan.setText(invOn ? "■ 停止盘存" : "▶ 开始盘存"));
    }

    private void setState(String s, int color) { ui.post(() -> { tvState.setText(s); tvState.setTextColor(color); }); }
    private void toast(String s) { ui.post(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show()); }
    private static int parseInt(String s, int dft) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return dft; } }
}
