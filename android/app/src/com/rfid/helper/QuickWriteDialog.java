package com.rfid.helper;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Keeps the template open so the same selections can be used for the next card. */
final class QuickWriteDialog {
    interface Callback {
        void progress(String text);
        void finished(String text);
    }
    interface Runner {
        void run(List<TagWriter.Item> items, byte[] password, boolean checkOnly, Callback callback);
    }

    private final Activity activity;
    private final Runner runner;
    private final CheckBox[] selected = new CheckBox[4];
    private final EditText[] values = new EditText[4];
    private final TextView[] sourceNotes = new TextView[4];
    private final List<View> inputs = new ArrayList<>();
    private EditText password;
    private TextView output;
    private AlertDialog dialog;
    private boolean busy;

    QuickWriteDialog(Activity activity, Runner runner) {
        this.activity = activity;
        this.runner = runner;
    }

    void show(Map<Integer, String> initial, Map<Integer, String> notes, Runnable onClose) {
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setPadding(dp(16), dp(4), dp(16), 0);
        ScrollView formScroll = new ScrollView(activity);
        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(0, 0, dp(12), dp(8));
        form.addView(label("只放一张目标卡。勾选要写入或检查的区，内容可编辑。", 14));
        form.addView(label("目标卡当前访问密码（8 位 HEX）", 13));
        password = edit("00000000");
        password.setSingleLine(true);
        form.addView(password);
        String[] hints = {"Kill 4 字节 + Access 4 字节；改后再检查同一卡须填新 Access 密码", "只填 EPC 编号，自动更新长度", "字 0 起；普通卡的 TID 通常只读", "字 0 起；按填写长度操作"};
        for (int bank : new int[]{2, 1, 3, 0}) {
            CheckBox check = new CheckBox(activity);
            check.setText(RfidProto.BANKS[bank]);
            check.setTextColor(0xFFE8EDF4);
            check.setChecked(bank == 1);
            selected[bank] = check;
            inputs.add(check);
            form.addView(check);
            form.addView(label(hints[bank], 13));
            TextView note = label(notes.containsKey(bank) ? notes.get(bank) : "", 12);
            note.setVisibility(notes.containsKey(bank) ? View.VISIBLE : View.GONE);
            sourceNotes[bank] = note;
            form.addView(note);
            EditText input = edit(initial.containsKey(bank) ? initial.get(bank) : "");
            input.setHint("HEX 数据");
            input.setMinLines(1);
            input.setMaxLines(3);
            input.setEnabled(check.isChecked());
            values[bank] = input;
            check.setOnCheckedChangeListener((button, checked) -> input.setEnabled(checked && !busy));
            form.addView(input);
        }
        formScroll.addView(form);
        body.addView(formScroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.15f));

        LinearLayout resultPanel = new LinearLayout(activity);
        resultPanel.setOrientation(LinearLayout.VERTICAL);
        Button importButton = new Button(activity);
        importButton.setText("从剪贴板导入");
        importButton.setOnClickListener(v -> importClipboard());
        inputs.add(importButton);
        resultPanel.addView(importButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        ScrollView resultScroll = new ScrollView(activity);
        output = label("检查：按所选区域和填写长度对比。\n\n写入并检查：先检查可读范围，再写入并回读。\n\n数据和勾选项会保留在本窗口，可换下一张卡继续。", 14);
        output.setTextColor(0xFFE8EDF4);
        output.setTextIsSelectable(true);
        output.setPadding(dp(12), dp(8), dp(8), dp(8));
        resultScroll.setBackgroundColor(0xFF10151C);
        resultScroll.addView(output);
        resultPanel.addView(resultScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        body.addView(resultPanel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));

        dialog = new AlertDialog.Builder(activity).setTitle("快速写卡")
                .setView(body).setPositiveButton("写入并检查", null)
                .setNeutralButton("只检查", null).setNegativeButton("关闭", null).create();
        dialog.setOnDismissListener(d -> onClose.run());
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> execute(false));
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> execute(true));
            int width = activity.getResources().getDisplayMetrics().widthPixels;
            int height = activity.getResources().getDisplayMetrics().heightPixels;
            dialog.getWindow().setLayout((int) (width * .96), (int) (height * .9));
        });
        dialog.show();
    }

    private void importClipboard() {
        if (busy) return;
        try {
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0)
                throw new IllegalArgumentException("剪贴板为空，请先在读取结果中点“复制内容”");
            CharSequence text = clip.getItemAt(0).getText();
            if (text == null) throw new IllegalArgumentException("剪贴板不是文本，请复制标签读取结果");
            TagClipboard.Import imported = TagClipboard.parse(text.toString());
            // Apply only after all sections validate, and clear missing banks to avoid stale data.
            for (int bank = 0; bank < 4; bank++) {
                String value = imported.values.get(bank);
                values[bank].setText(value == null ? "" : value);
                if (value == null) selected[bank].setChecked(false);
                sourceNotes[bank].setText(imported.notes.get(bank));
                sourceNotes[bank].setVisibility(View.VISIBLE);
            }
            output.setText(imported.summary());
        } catch (IllegalArgumentException | SecurityException e) {
            output.setText("导入失败: " + e.getMessage());
        }
    }

    private void execute(boolean checkOnly) {
        if (busy) return;
        try {
            byte[] pwd = TagWriter.parseHex(password.getText().toString(), "访问密码");
            List<TagWriter.Item> plan = new ArrayList<>();
            for (int bank = 0; bank < 4; bank++) if (selected[bank].isChecked())
                plan.add(new TagWriter.Item(bank, TagWriter.parseHex(values[bank].getText().toString(), RfidProto.BANKS[bank])));
            TagWriter.validate(plan, pwd);
            setBusy(true);
            output.setText(checkOnly ? "正在检查…" : "正在写入并检查…");
            runner.run(plan, pwd, checkOnly, new Callback() {
                public void progress(String text) {
                    if (!activity.isDestroyed() && dialog.isShowing()) output.setText(text);
                }
                public void finished(String text) {
                    if (!activity.isDestroyed() && dialog.isShowing()) {
                        output.setText(text);
                        setBusy(false);
                    }
                }
            });
        } catch (IllegalArgumentException e) {
            output.setText(e.getMessage());
            if (busy) setBusy(false);
        }
    }

    private void setBusy(boolean value) {
        busy = value;
        dialog.setCancelable(!value);
        dialog.setCanceledOnTouchOutside(false);
        for (int button : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_NEGATIVE})
            dialog.getButton(button).setEnabled(!value);
        for (View input : inputs) input.setEnabled(!value);
        for (int bank = 0; bank < 4; bank++) values[bank].setEnabled(!value && selected[bank].isChecked());
    }

    private EditText edit(String text) {
        EditText input = new EditText(activity);
        input.setTextColor(0xFFE8EDF4);
        input.setHintTextColor(0xFF7B8794);
        input.setTextSize(14);
        input.setTypeface(Typeface.MONOSPACE);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setGravity(Gravity.TOP);
        input.setText(text);
        inputs.add(input);
        return input;
    }

    private TextView label(String text, int size) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(0xFF9BA7B4);
        return view;
    }

    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
