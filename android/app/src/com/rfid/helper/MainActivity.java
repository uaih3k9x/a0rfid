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
    private TextView tvState, tvInfo, tvCount, tvProgress;
    private Button btnScan, btnSettings, btnQuickWrite;
    private ListView list;
    private final Map<String, RfidProto.Tag> tags = new LinkedHashMap<>();
    private final Map<String, List<TagMemoryReader.Bank>> readResults = new LinkedHashMap<>();
    private ArrayAdapter<String> adapter;
    private volatile boolean invOn = false, connected = false;
    private volatile boolean operationBusy, destroyed, readCancelled;
    private String readingEpc;
    private String lastReadEpc;
    private boolean quickWriteOpen;
    private int scanGeneration;
    private volatile Thread invThread;
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
        destroyed = true;
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
        if (connected || operationBusy || destroyed) return;
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
            pauseForOp(this::queryInfo, !quickWriteOpen);
        } else {
            setState("● 连接失败: " + ch340.error, Color.RED);
        }
    }

    private void closeDevice() {
        invOn = false;
        connected = false;
        scanGeneration++;
        synchronized (lock) { if (ch340 != null) ch340.close(); }
        setScanBtn();
        setState("● 未连接（插 USB）", DIM);
        ui.post(() -> tvInfo.setText(""));
    }

    private void queryInfo() {
        int[][] rs = {cmd(RfidProto.CMD_GET_FW_VERSION, new byte[0]), cmd(RfidProto.CMD_GET_OUTPUT_POWER, new byte[0])};
        final String s;
        if (rs[0] != null && rs[0].length >= 4 && rs[1] != null && rs[1].length > 1) {
            powerNow = rs[1][1];
            s = String.format("固件 V%d.%d.%d   功率 %d dBm", rs[0][1], rs[0][2], rs[0][3], rs[1][1]);
        } else s = "设备无响应（可能死机，重新插拔 USB）";
        ui.post(() -> tvInfo.setText(s));
    }

    // ── 核心指令（同步，需在后台线程调用）────────────────
    /** 返回匹配的响应 {cmd,data...}；READ 跳过独立 ACK 等待数据。 */
    private int[] cmd(int cmd, byte[] dataArea) {
        return cmd(cmd, dataArea, 1200);
    }

    private int[] cmd(int cmd, byte[] dataArea, int timeoutMs) {
        synchronized (lock) {
            if (ch340 == null || !connected) return null;
            long drainDeadline = System.currentTimeMillis() + 150;
            while (connected && System.currentTimeMillis() < drainDeadline && ch340.read(512, 50).length > 0) {}
            if (!connected) return null;
            ch340.write(RfidProto.encode(cmd, dataArea), 1000);
            long deadline = System.currentTimeMillis() + timeoutMs;
            int[] best = null;
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            while (connected && System.currentTimeMillis() < deadline) {
                byte[] chunk = ch340.read(256, 60);
                if (chunk.length > 0) buf.write(chunk, 0, chunk.length);
                byte[] b = buf.toByteArray();
                int off = 0;
                while (true) {
                    int[] fr = RfidProto.parseFrame(b, off, b.length);
                    if (fr == null) break;
                    int len = RfidProto.frameLen(b, off);
                    off += len;
                    if (fr[0] == cmd || (cmd < 0x80 && fr[0] == (cmd | 0x80))) {
                        if (best == null || fr.length > best.length) best = fr;
                        if (fr.length > 1 && !RfidProto.isReadAck(fr)) return best;
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
        if (!connected || operationBusy || destroyed || (invThread != null && invThread.isAlive())) return;
        final int generation = ++scanGeneration;
        invOn = true;
        tvProgress.setText("扫描中");
        setScanBtn();
        invThread = new Thread(() -> {
            try {
                synchronized (lock) {
                    if (ch340 != null && connected) {
                        ch340.write(RfidProto.encode(RfidProto.CMD_SET_WORK_ANT, new byte[]{(byte) antennaNow}), 500);
                        try { Thread.sleep(60); } catch (InterruptedException ignored) {}
                        ch340.write(RfidProto.encode(RfidProto.CMD_REAL_TIME_INV, new byte[]{(byte) antennaNow}), 500);
                    }
                }
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                long nextProbe = System.currentTimeMillis() + 1500;
                while (invOn && connected) {
                    byte[] chunk;
                    synchronized (lock) { chunk = ch340 != null && connected ? ch340.read(512, 80) : new byte[0]; }
                    if (chunk.length == 0) {
                        if (System.currentTimeMillis() >= nextProbe) {
                            synchronized (lock) {
                                if (invOn && connected && ch340 != null)
                                    ch340.write(RfidProto.encode(RfidProto.CMD_REAL_TIME_INV, new byte[]{(byte) antennaNow}), 500);
                            }
                            nextProbe = System.currentTimeMillis() + 1500;
                        }
                        continue;
                    }
                    nextProbe = System.currentTimeMillis() + 1500;
                    buf.write(chunk, 0, chunk.length);
                    byte[] b = buf.toByteArray();
                    int off = 0;
                    while (true) {
                        int[] fr = RfidProto.parseFrame(b, off, b.length);
                        if (fr == null) break;
                        off += RfidProto.frameLen(b, off);
                        if (fr[0] == RfidProto.CMD_REAL_TIME_INV) {
                            RfidProto.Tag t = RfidProto.parseTag(fr);
                            if (t != null) {
                                invOn = false;
                                onTag(t, generation);
                                break;
                            }
                        }
                    }
                    if (off >= b.length) buf.reset();
                    else if (off > 0) { byte[] rest = new byte[b.length - off]; System.arraycopy(b, off, rest, 0, rest.length); buf.reset(); buf.write(rest, 0, rest.length); }
                }
            } catch (Exception e) {
                toast("扫描失败: " + e.getMessage());
            } finally {
                synchronized (lock) {
                    if (ch340 != null && connected)
                        ch340.write(RfidProto.encode(RfidProto.CMD_STOP_INV, new byte[0]), 500);
                }
                invOn = false;
                setScanBtn();
                ui.post(() -> {
                    if (generation == scanGeneration && !operationBusy && !destroyed)
                        tvProgress.setText("扫描已停止");
                });
            }
        }, "inventory");
        invThread.start();
    }

    private void stopInventoryAndWait() {
        invOn = false;
        setScanBtn();
        if (invThread != null) {
            try { invThread.join(2500); } catch (InterruptedException ignored) {}
            if (invThread.isAlive()) throw new IllegalStateException("盘存线程未停止");
            invThread = null;
        }
        synchronized (lock) {
            // Discard the stop ACK and the tail of the inventory stream before READ.
            if (ch340 != null && connected) {
                long deadline = System.currentTimeMillis() + 500;
                while (System.currentTimeMillis() < deadline && ch340.read(512, 50).length > 0) {}
            }
        }
    }

    private void onTag(RfidProto.Tag t, int generation) {
        ui.post(() -> {
            if (destroyed || !connected || generation != scanGeneration) return;
            tags.put(t.epc, t);
            refreshList();
            readAll(t.epc);
        });
    }

    private void refreshList() {
        List<String> rows = new ArrayList<>();
        for (RfidProto.Tag t : tags.values()) {
            String state = t.epc.equals(readingEpc) ? "读取中" :
                    (readResults.containsKey(t.epc) ? "读取已结束" : "已识别");
            rows.add(String.format("%s\n%s   PC:%s   %d kHz", t.epc, state, t.pc, t.freqKhz));
        }
        adapter.clear();
        adapter.addAll(rows);
        adapter.notifyDataSetChanged();
        tvCount.setText("标签: " + tags.size());
    }

    // ── 标签操作 ─────────────────────────────────────────
    private void pauseForOp(Runnable op) {
        pauseForOp(op, invOn);
    }

    private void pauseForOp(Runnable op, boolean resumeInventory) {
        pauseForOp(op, resumeInventory, null);
    }

    private void pauseForOp(Runnable op, boolean resumeInventory, java.util.function.Consumer<String> onError) {
        if (operationBusy || destroyed) { toast("设备忙"); return; }
        if (!connected) { toast("未连接设备"); return; }
        operationBusy = true;
        scanGeneration++;
        invOn = false;
        setScanBtn();
        new Thread(() -> {
            try {
                stopInventoryAndWait();
                op.run();
            } catch (Exception e) {
                toast("操作失败: " + e.getMessage());
                ui.post(() -> {
                    tvProgress.setText("操作失败，扫描已暂停");
                    if (onError != null) onError.accept(e.getMessage());
                });
            } finally {
                ui.post(() -> {
                    operationBusy = false;
                    readingEpc = null;
                    if (destroyed) return;
                    refreshList();
                    if (resumeInventory && connected) startInventory();
                    setScanBtn();
                    if (!connected) tryConnect();
                });
            }
        }, "rfid-operation").start();
    }

    private void readAll(final String epc) {
        if (operationBusy || !connected) { toast("设备忙或未连接"); return; }
        readingEpc = epc;
        readCancelled = false;
        readResults.remove(epc);
        refreshList();
        tvProgress.setText("正在读取 " + epc);
        pauseForOp(() -> {
            TagMemoryReader reader = new TagMemoryReader(new TagMemoryReader.Transport() {
                public int[] read(int bank, int address, int words) {
                    return cmd(RfidProto.CMD_READ, RfidProto.buildRead(bank, address, words, new byte[4]), 900);
                }
                public boolean isConnected() { return connected && !readCancelled; }
            });
            List<TagMemoryReader.Bank> result = reader.readAll(epc, (bank, bytes) ->
                    ui.post(() -> tvProgress.setText("正在读取 " + RfidProto.BANKS[bank] + "  " + bytes + " 字节")));
            ui.post(() -> {
                if (destroyed) return;
                readResults.put(epc, result);
                lastReadEpc = epc;
                readingEpc = null;
                tvProgress.setText("读取已结束，扫描已暂停");
                refreshList();
                showReadResult(epc);
            });
        }, false);
    }

    private String bankState(TagMemoryReader.Bank bank) {
        switch (bank.end) {
            case COMPLETE: return bank.data.length == 0 ? "无可读空间 (0x43)" : "读取完成";
            case ERROR: return "读取失败: " + RfidProto.statusText(bank.status);
            case NO_RESPONSE: return "无响应，未读完整";
            case TAG_CHANGED: return "响应来自其他 EPC，已中止";
            case INVALID_RESPONSE: return "响应格式或长度异常，未读完整";
            case LIMIT: return "达到读取上限（4096 字节 / 30 秒），未确认读完整";
            default: return "读取已取消或连接断开";
        }
    }

    private void showReadResult(String epc) {
        List<TagMemoryReader.Bank> result = readResults.get(epc);
        if (result == null) return;
        StringBuilder text = new StringBuilder("EPC: ").append(epc).append('\n');
        for (TagMemoryReader.Bank bank : result) {
            text.append('\n').append(RfidProto.BANKS[bank.index]).append("  ")
                    .append(bank.data.length).append(" 字节  ").append(bankState(bank)).append('\n');
            String hex = RfidProto.hex(bank.data);
            for (int i = 0; i < hex.length(); i += 32)
                text.append(String.format("%04X: %s\n", i / 4, hex.substring(i, Math.min(i + 32, hex.length()))));
        }
        TextView body = tv(text.toString(), TXT, 15);
        body.setTypeface(android.graphics.Typeface.MONOSPACE);
        body.setTextIsSelectable(true);
        body.setPadding(dp(16), dp(8), dp(16), dp(8));
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.addView(body);
        new AlertDialog.Builder(this).setTitle("标签读取结果").setView(scroll)
                .setPositiveButton("关闭", null)
                .setNeutralButton("复制内容", (d, w) -> {
                    android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("RFID", text.toString()));
                    toast("已复制");
                })
                .setNegativeButton("重新读取", (d, w) -> readAll(epc)).show();
    }

    private void writeDialog(final String presetEpc) {
        if (operationBusy) { toast("设备忙，请先结束当前操作"); return; }
        if (invOn || (invThread != null && invThread.isAlive())) {
            pauseForOp(() -> ui.post(() -> showQuickWrite(presetEpc)), false);
        } else showQuickWrite(presetEpc);
    }

    private void showQuickWrite(String epc) {
        if (destroyed) return;
        quickWriteOpen = true;
        Map<Integer, String> initial = new LinkedHashMap<>();
        Map<Integer, String> notes = new LinkedHashMap<>();
        if (epc != null) initial.put(1, epc);
        List<TagMemoryReader.Bank> result = readResults.get(epc);
        if (result != null) for (TagMemoryReader.Bank bank : result) {
            // Inventory EPC is the number; the memory dump also includes CRC, PC and unused capacity.
            if (bank.index != 1 && bank.data.length > 0) {
                initial.put(bank.index, RfidProto.hex(bank.data));
                notes.put(bank.index, "带入 " + bank.data.length + " 字节；" + bankState(bank));
            }
        }
        new QuickWriteDialog(this, this::runQuickWrite).show(initial, notes, () -> quickWriteOpen = false);
    }

    private void runQuickWrite(List<TagWriter.Item> items, byte[] password, boolean checkOnly, QuickWriteDialog.Callback callback) {
        if (!connected || operationBusy) { callback.finished("读写头未连接或设备忙，请稍后重试"); return; }
        pauseForOp(() -> {
            try {
                TagWriter writer = new TagWriter(new TagWriter.Transport() {
                    public List<String> inventory() { return inventoryForWrite(); }
                    public boolean isConnected() { return connected && !destroyed; }
                    public int[] read(int bank, int address, int words, byte[] pwd) {
                        return cmd(RfidProto.CMD_READ, RfidProto.buildRead(bank, address, words, pwd), 1000);
                    }
                    public int[] write(int bank, int address, byte[] data, byte[] pwd) {
                        return cmd(RfidProto.CMD_WRITE, RfidProto.buildWrite(bank, address, data.length / 2, pwd, data), 1800);
                    }
                });
                TagWriter.Report report = writer.run(items, password, checkOnly, message -> ui.post(() -> {
                    tvProgress.setText(message);
                    callback.progress(message);
                }));
                ui.post(() -> {
                    if (destroyed) return;
                    tvProgress.setText(report.passed() ? "检查通过，扫描已暂停" : "操作结束，请查看检查结果");
                    callback.finished(report.text(checkOnly));
                });
            } catch (Exception e) {
                ui.post(() -> callback.finished("操作异常: " + e.getMessage() + (checkOnly ? "" : "\n若已开始写入，可能部分改变，请执行只检查确认")));
            }
        }, false, message -> callback.finished("未能开始操作: " + message));
    }

    /** Collect multiple inventory replies before choosing a target for a manual operation. */
    private List<String> inventoryForWrite() {
        java.util.Set<String> found = new java.util.LinkedHashSet<>();
        synchronized (lock) {
            if (!connected || ch340 == null) return new ArrayList<>();
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            long deadline = System.currentTimeMillis() + 900;
            ch340.write(RfidProto.encode(RfidProto.CMD_REAL_TIME_INV, new byte[]{(byte) antennaNow}), 500);
            try {
                while (connected && System.currentTimeMillis() < deadline) {
                    byte[] chunk = ch340.read(512, 50);
                    buffer.write(chunk, 0, chunk.length);
                    byte[] bytes = buffer.toByteArray();
                    int off = 0;
                    int[] frame;
                    while ((frame = RfidProto.parseFrame(bytes, off, bytes.length)) != null) {
                        off += RfidProto.frameLen(bytes, off);
                        if (frame[0] == RfidProto.CMD_REAL_TIME_INV) {
                            RfidProto.Tag tag = RfidProto.parseTag(frame);
                            if (tag != null) found.add(tag.epc);
                        }
                    }
                    if (off > 0) { buffer.reset(); buffer.write(bytes, off, bytes.length - off); }
                }
            } finally {
                if (connected) ch340.write(RfidProto.encode(RfidProto.CMD_STOP_INV, new byte[0]), 500);
            }
        }
        return new ArrayList<>(found);
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
        LinearLayout root = new LinearLayout(this);
        root.setBackgroundColor(BG);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(8), dp(16), dp(8));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = tv("RFID 助手", TXT, 20);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        tvState = tv("● 未连接（插 USB）", DIM, 15);
        header.addView(tvState);
        root.addView(header);
        tvInfo = tv("", DIM, 13);
        root.addView(tvInfo);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, 0, 0, dp(8));
        tvCount = tv("标签: 0", ACCENT, 16);
        btnSettings = mkBtn("⚙ 设置", CARD);
        btnQuickWrite = mkBtn("快速写卡", CARD);
        btnScan = mkBtn("扫描标签", ACCENT);
        btnScan.setTextColor(0xFF06231E);
        bar.addView(tvCount, new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams scanParams = new LinearLayout.LayoutParams(dp(144), dp(48));
        scanParams.setMargins(0, 0, dp(8), 0);
        bar.addView(btnScan, scanParams);
        LinearLayout.LayoutParams writeParams = new LinearLayout.LayoutParams(dp(128), dp(48));
        writeParams.setMargins(0, 0, dp(8), 0);
        bar.addView(btnQuickWrite, writeParams);
        bar.addView(btnSettings, new LinearLayout.LayoutParams(dp(96), dp(48)));
        root.addView(bar);
        tvProgress = tv("", DIM, 14);
        root.addView(tvProgress);

        list = new ListView(this);
        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, new ArrayList<>()) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView row = (TextView) super.getView(position, convertView, parent);
                row.setTextColor(TXT);
                row.setTextSize(15);
                row.setSingleLine(false);
                return row;
            }
        };
        list.setAdapter(adapter);
        list.setDivider(new android.graphics.drawable.ColorDrawable(0xFF232D3D));
        list.setDividerHeight(2);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        btnScan.setOnClickListener(v -> {
            if (operationBusy) {
                if (readingEpc != null) {
                    readCancelled = true;
                    tvProgress.setText("正在停止读取");
                }
                return;
            }
            if (!connected) { toast("未连接设备"); return; }
            if (invOn || (invThread != null && invThread.isAlive())) {
                scanGeneration++;
                pauseForOp(() -> {}, false);
                tvProgress.setText("扫描已暂停");
            } else startInventory();
        });
        btnSettings.setOnClickListener(v -> settingsDialog());
        btnQuickWrite.setOnClickListener(v -> writeDialog(lastReadEpc));
        list.setOnItemClickListener((p, v, pos, id) -> {
            final String epc = new ArrayList<>(tags.keySet()).get(pos);
            new AlertDialog.Builder(this)
                    .setTitle("标签操作")
                    .setMessage("EPC: " + epc)
                    .setPositiveButton(readResults.containsKey(epc) ? "查看读取结果" : "读取数据", (d, w) -> {
                        if (readResults.containsKey(epc)) showReadResult(epc); else readAll(epc);
                    })
                    .setNeutralButton("快速写卡 / 检查", (d, w) -> writeDialog(epc))
                    .setNegativeButton("关闭", null).show();
        });

        setContentView(root);
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
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(8), 0, dp(8), 0);
        b.setBackground(new android.graphics.drawable.GradientDrawable() {{
            setColor(bg);
            setCornerRadius(dp(4));
        }});
        b.setOnTouchListener((v, e) -> false);
        return b;
    }

    private void setScanBtn() {
        ui.post(() -> {
            btnScan.setText(operationBusy ? (readingEpc != null ? "停止读取" : "处理中") :
                    (invOn ? "停止扫描" : "扫描标签"));
            btnScan.setEnabled(!operationBusy || readingEpc != null);
            btnSettings.setEnabled(!operationBusy);
            btnQuickWrite.setEnabled(!operationBusy);
        });
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void setState(String s, int color) { ui.post(() -> { tvState.setText(s); tvState.setTextColor(color); }); }
    private void toast(String s) { ui.post(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show()); }
    private static int parseInt(String s, int dft) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return dft; } }
}
