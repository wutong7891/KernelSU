package com.wutong.yingcang;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.provider.OpenableColumns;
import android.text.method.ScrollingMovementMethod;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(7, 11, 20);
    private static final int CARD = Color.rgb(24, 33, 51);
    private static final int TEXT = Color.rgb(238, 244, 255);
    private static final int MUTED = Color.rgb(152, 166, 194);
    private static final int ACCENT = Color.rgb(135, 185, 255);
    private static final String PREFS = "wtlyf_activation";
    private static final String KEY_CODE = "activation_code";
    private static final String PREFIX = "N1.";
    private static final int REQUEST_BOOT_IMAGE = 5001;
    private static final String PUBLIC_KEY_BASE64 =
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAxPm4IldYf9tF/Y0UWLi+2EbCMqSexmTpOHitEFtkCzLydcBguhgXg1qjapu1SqkmF2HEkD7xKl9zDRqu0b9ExK2YwSwmuPJIOjli+5il0Vc9/2CYKcLU3htMd8juCT7e6mVz31mJ6llf42yM+iCCPQ+JvQer5uCACyLGy8A1ArF9IKt8IZFlsb9r09/WZcdbLv1p0ASFRBLzVwv3JgT13oSQp0x1I63pZ/eeJzcjCzmmrDPsgsIXBXsKJxLyJFAzTWL7Xj0fZS8TkI1awyIUTMgNi+XO3Tn3y9cWxu1JG5niwAQVp1bjM9olG9tYDEvNAO5WXRGsRHI3keJWGs/xfQIDAQAB";

    private static final ModuleItem ALWAYS_STRONG =
        new ModuleItem("AlwaysStrong", "v1.0.3", "always-strong.zip");
    private static final ModuleItem SOTER_KEY =
        new ModuleItem("Soter Key Fixer", "v1.2", "soterkey.zip");
    private static final ModuleItem JAILBREAK_TOLERANCE =
        new ModuleItem("隐藏越狱模式", "v1.1", "jailbreak-tolerance.zip");
    private static final ModuleItem[] PATH_MASKS = {
        new ModuleItem("Android 12 / 5.10 PathMask", "v2.3.3", "pathmask-android12-5.10.zip"),
        new ModuleItem("Android 13 / 5.10 PathMask", "v2.3.3", "pathmask-android13-5.10.zip"),
        new ModuleItem("Android 13 / 5.15 PathMask", "v2.3.3", "pathmask-android13-5.15.zip"),
        new ModuleItem("Android 14 / 6.1 PathMask", "v2.3.3", "pathmask-android14-6.1.zip"),
        new ModuleItem("Android 15 / 6.6 PathMask", "v2.3.3", "pathmask-android15-6.6.zip")
    };

    private final AtomicBoolean installing = new AtomicBoolean(false);
    private TextView log;
    private Spinner pathMaskSpinner;
    private Spinner partitionSpinner;
    private Spinner slotSpinner;
    private TextView imageStatus;
    private Button operationBack;
    private Uri bootImageUri;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        if (isActivated()) showToolbox(); else showActivation();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_BOOT_IMAGE || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        bootImageUri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(bootImageUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignored) {
        }
        if (imageStatus != null) imageStatus.setText("已选择：" + displayName(bootImageUri));
    }

    private void showActivation() {
        LinearLayout root = rootLayout();
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title("wtlyf", 34));
        root.addView(label("设备激活", 20, TEXT));
        addSpace(root, 22);

        LinearLayout card = card();
        String id = androidId();
        card.addView(label("Android ID", 13, MUTED));
        TextView idView = label(id, 15, TEXT);
        idView.setTypeface(Typeface.MONOSPACE);
        card.addView(idView, wide());
        Button copy = button("复制设备 ID");
        copy.setOnClickListener(v -> {
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Android ID", id));
            Toast.makeText(this, "Android ID 已复制", Toast.LENGTH_SHORT).show();
        });
        card.addView(copy, wide());

        EditText code = new EditText(this);
        code.setHint("输入 N1 激活码");
        code.setHintTextColor(MUTED);
        code.setTextColor(TEXT);
        code.setMinLines(3);
        card.addView(code, wide());
        Button activate = button("激活并进入");
        activate.setOnClickListener(v -> {
            String value = code.getText().toString().trim();
            if (verify(id, value)) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_CODE, value).apply();
                showToolbox();
            } else {
                code.setError("激活码与本机 Android ID 不匹配");
            }
        });
        card.addView(activate, wide());
        root.addView(card, wide());
        addSpace(root, 14);
        root.addView(label("激活仅在本机离线验证，不上传 Android ID。", 13, MUTED));
        setAnimatedContent(scroll(root));
    }

    private void showToolbox() {
        LinearLayout root = rootLayout();
        root.addView(title("wtlyf", 32));
        root.addView(label("KernelSU 环境部署工具箱", 16, MUTED));
        root.addView(label("已通过 Android ID 验证：" + androidId(), 12, Color.rgb(135, 205, 255)));
        addSpace(root, 24);

        Button alwaysStrong = primaryButton("部署 AlwaysStrong");
        alwaysStrong.setOnClickListener(v -> confirmInstall(
            "部署 AlwaysStrong",
            "将通过 KernelSU 安装 AlwaysStrong v1.0.3。完成后需要重启设备。",
            new ModuleItem[] { ALWAYS_STRONG }
        ));
        root.addView(alwaysStrong, wide());

        addSpace(root, 14);
        root.addView(label("晓龙 · 必须先选择 PathMask", 17, TEXT));
        root.addView(label("请选择与你设备 Android 版本及内核版本完全一致的选项。点击后会先安装 PathMask，再安装 Soter Key Fixer。", 13, MUTED));
        pathMaskSpinner = new Spinner(this);
        String[] pathMaskLabels = {
            "请选择 PathMask 版本",
            "Android 12 / Kernel 5.10",
            "Android 13 / Kernel 5.10",
            "Android 13 / Kernel 5.15",
            "Android 14 / Kernel 6.1",
            "Android 15 / Kernel 6.6"
        };
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(
            this, android.R.layout.simple_spinner_dropdown_item, pathMaskLabels
        );
        pathMaskSpinner.setAdapter(adapter);
        pathMaskSpinner.setBackground(glassDrawable(Color.argb(205, 16, 30, 55), 16, Color.argb(140, 150, 215, 255)));
        root.addView(pathMaskSpinner, wide());

        Button xiaolong = primaryButton("晓龙");
        xiaolong.setOnClickListener(v -> installXiaolong());
        root.addView(xiaolong, wide());

        addSpace(root, 14);
        Button jailbreak = primaryButton("越狱宽容");
        jailbreak.setOnClickListener(v -> confirmInstall(
            "部署越狱宽容",
            "将通过 KernelSU 安装隐藏越狱模式模块。完成后需要重启设备。",
            new ModuleItem[] { JAILBREAK_TOLERANCE }
        ));
        root.addView(jailbreak, wide());

        addSpace(root, 24);
        root.addView(label("BOOT / INIT_BOOT 刷写", 18, TEXT));
        root.addView(label("当前活动槽位：" + currentSlot().toUpperCase(Locale.ROOT), 13, Color.rgb(135, 205, 255)));
        root.addView(label("请明确选择目标分区与 A/B 槽位；刷错镜像或槽位可能导致设备无法启动。", 13, MUTED));
        partitionSpinner = spinner(new String[] { "boot", "init_boot" });
        root.addView(partitionSpinner, wide());
        slotSpinner = spinner(new String[] { "A 槽位", "B 槽位" });
        root.addView(slotSpinner, wide());
        imageStatus = label("尚未选择 .img 镜像", 13, MUTED);
        root.addView(imageStatus, wide());
        Button chooseImage = button("选择 boot / init_boot 镜像");
        chooseImage.setOnClickListener(v -> chooseBootImage());
        root.addView(chooseImage, wide());
        Button flashImage = primaryButton("确认并刷写所选分区");
        flashImage.setBackground(rippleButton(
            new int[] { Color.rgb(185, 54, 87), Color.rgb(107, 35, 91) },
            Color.argb(140, 255, 220, 225)
        ));
        flashImage.setOnClickListener(v -> confirmFlash());
        root.addView(flashImage, wide());

        addSpace(root, 24);
        LinearLayout danger = card();
        danger.addView(label("危险区域", 18, Color.rgb(255, 185, 195)));
        danger.addView(label("彻底清空 /data/adb/ 下的所有文件，包括 KernelSU 模块、授权与配置。操作不可恢复，重启后可能需要重新配置 root。", 13, Color.rgb(255, 205, 211)));
        Button clearAdb = dangerButton("清理 /data/adb/ 全部内容");
        clearAdb.setOnClickListener(v -> confirmClearDataAdb());
        danger.addView(clearAdb, wide());
        root.addView(danger, wide());

        addSpace(root, 16);
        root.addView(label("安装调用 KernelSU 的 ksud module install；请先在 Night 面具中授予本应用 root 权限。", 13, MUTED));
        addSpace(root, 12);

        log = label("等待部署…", 13, Color.rgb(201, 232, 218));
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextIsSelectable(true);
        log.setMovementMethod(new ScrollingMovementMethod());
        log.setMinHeight(dp(220));
        log.setPadding(dp(14), dp(14), dp(14), dp(14));
        log.setBackground(glassDrawable(Color.argb(205, 3, 8, 18), 18, Color.argb(135, 115, 190, 255)));
        root.addView(log, wide());
        setAnimatedContent(scroll(root));
    }

    private Spinner spinner(String[] items) {
        Spinner spinner = new Spinner(this);
        spinner.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, items));
        spinner.setBackgroundColor(CARD);
        return spinner;
    }

    private void chooseBootImage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_BOOT_IMAGE);
    }

    private void confirmFlash() {
        if (installing.get()) {
            Toast.makeText(this, "已有任务正在执行", Toast.LENGTH_SHORT).show();
            return;
        }
        if (bootImageUri == null) {
            Toast.makeText(this, "请先选择镜像", Toast.LENGTH_SHORT).show();
            return;
        }
        String partition = String.valueOf(partitionSpinner.getSelectedItem());
        String slot = slotSpinner.getSelectedItemPosition() == 0 ? "a" : "b";
        new AlertDialog.Builder(this)
            .setTitle("高风险操作：刷写 " + partition + "_" + slot)
            .setMessage("即将把 " + displayName(bootImageUri) + " 写入 " + partition + "_" + slot + "。镜像或槽位选择错误可能导致设备无法启动，确认继续？")
            .setNegativeButton("取消", null)
            .setPositiveButton("确认刷写", (dialog, which) -> flashImage(partition, slot))
            .show();
    }

    private void flashImage(String partition, String slot) {
        if (!installing.compareAndSet(false, true)) return;
        showOperationPage("正在刷写 " + partition + "_" + slot, "请勿关闭应用或重启设备。完成后可返回工具箱。");
        new Thread(() -> {
            File image = new File(getCacheDir(), "wtlyf-" + partition + "_" + slot + ".img");
            boolean success = false;
            try (InputStream input = getContentResolver().openInputStream(bootImageUri);
                 FileOutputStream output = new FileOutputStream(image)) {
                if (input == null) throw new IllegalStateException("无法读取所选镜像");
                byte[] buffer = new byte[1024 * 1024];
                for (int count; (count = input.read(buffer)) >= 0;) output.write(buffer, 0, count);
                output.getFD().sync();

                String blockName = partition + "_" + slot;
                String command = "set -e; image=" + shellQuote(image.getAbsolutePath()) + "; target=''; "
                    + "for candidate in /dev/block/by-name/" + blockName + " /dev/block/bootdevice/by-name/" + blockName + " /dev/block/platform/*/by-name/" + blockName + "; do "
                    + "[ -e \"$candidate\" ] && { target=\"$candidate\"; break; }; done; "
                    + "[ -n \"$target\" ] || { echo '找不到分区 " + blockName + "' >&2; exit 20; }; "
                    + "image_size=$(stat -c '%s' \"$image\"); block_size=$(blockdev --getsize64 \"$target\"); "
                    + "[ \"$image_size\" -gt 0 ] || { echo '镜像为空' >&2; exit 21; }; "
                    + "[ \"$image_size\" -le \"$block_size\" ] || { echo \"镜像大于目标分区: $image_size > $block_size\" >&2; exit 22; }; "
                    + "echo \"写入 $target ($image_size / $block_size bytes)\"; "
                    + "dd if=\"$image\" of=\"$target\" bs=4M conv=fsync; sync; echo '刷写完成，请确认后再重启设备'";
                Process process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) appendLog(line + "\n");
                }
                int code = process.waitFor();
                appendLog("退出码：" + code + "\n");
                success = code == 0;
            } catch (Throwable error) {
                appendLog("刷写错误：" + error.getMessage() + "\n");
            } finally {
                image.delete();
                installing.set(false);
                boolean result = success;
                runOnUiThread(() -> completeOperation(
                    result ? "刷写完成，请谨慎重启" : "刷写失败，请查看日志",
                    result
                ));
            }
        }, "wtlyf-partition-flasher").start();
    }

    private String currentSlot() {
        String suffix = readCommand("getprop", "ro.boot.slot_suffix").trim().replace("_", "");
        if ("a".equals(suffix) || "b".equals(suffix)) return suffix;
        String slot = readCommand("getprop", "ro.boot.slot").trim().replace("_", "");
        return slot.isEmpty() ? "未知" : slot;
    }

    private String readCommand(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                process.waitFor();
                return line == null ? "" : line;
            }
        } catch (Throwable ignored) {
            return "";
        }
    }

    private String displayName(Uri uri) {
        try (android.database.Cursor cursor = getContentResolver().query(uri, new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (Throwable ignored) {
        }
        String last = uri.getLastPathSegment();
        return last == null ? "image.img" : last;
    }

    private void installXiaolong() {
        int selected = pathMaskSpinner.getSelectedItemPosition();
        if (selected <= 0) {
            Toast.makeText(this, "必须先选择一个 PathMask 版本", Toast.LENGTH_LONG).show();
            return;
        }
        ModuleItem pathMask = PATH_MASKS[selected - 1];
        confirmInstall(
            "晓龙部署确认",
            "将先安装 " + pathMask.name + "，成功后再安装 Soter Key Fixer。PathMask 选错版本可能导致设备异常，请确认选择正确。",
            new ModuleItem[] { pathMask, SOTER_KEY }
        );
    }

    private void confirmInstall(String title, String message, ModuleItem[] items) {
        if (installing.get()) {
            Toast.makeText(this, "已有部署任务正在执行", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton("开始部署", (dialog, which) -> install(items))
            .show();
    }

    private void install(ModuleItem[] items) {
        if (!installing.compareAndSet(false, true)) return;
        showOperationPage("模块部署", "正在调用 KernelSU 安装模块，完成后可返回工具箱。");
        new Thread(() -> {
            boolean success = true;
            try {
                for (int index = 0; index < items.length; index++) {
                    ModuleItem item = items[index];
                    appendLog("\n== 部署步骤 " + (index + 1) + "/" + items.length + " ==\n");
                    appendLog(item.name + " " + item.version + "\n");
                    File zip = copyAsset(item.asset);
                    Process process = new ProcessBuilder(
                        "su", "-c", "/data/adb/ksud module install " + shellQuote(zip.getAbsolutePath())
                    ).redirectErrorStream(true).start();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) appendLog(line + "\n");
                    }
                    int code = process.waitFor();
                    appendLog("退出码：" + code + "\n");
                    zip.delete();
                    if (code != 0) {
                        success = false;
                        appendLog("部署失败，已停止后续模块。\n");
                        break;
                    }
                }
            } catch (Throwable error) {
                success = false;
                appendLog("错误：" + error.getMessage() + "\n");
            } finally {
                installing.set(false);
                boolean result = success;
                runOnUiThread(() -> completeOperation(
                    result ? "部署完成，请重启设备" : "部署失败，请查看日志",
                    result
                ));
            }
        }, "wtlyf-module-installer").start();
    }

    private void showOperationPage(String heading, String subtitle) {
        runOnUiThread(() -> {
            LinearLayout root = rootLayout();
            root.addView(title(heading, 28));
            root.addView(label(subtitle, 14, MUTED));
            addSpace(root, 18);

            LinearLayout panel = card();
            log = label("准备执行…\n", 13, Color.rgb(201, 232, 218));
            log.setTypeface(Typeface.MONOSPACE);
            log.setTextIsSelectable(true);
            log.setMovementMethod(new ScrollingMovementMethod());
            log.setMinHeight(dp(360));
            log.setPadding(dp(14), dp(14), dp(14), dp(14));
            log.setBackground(glassDrawable(Color.argb(205, 3, 8, 18), 18, Color.argb(150, 128, 202, 255)));
            panel.addView(log, wide());

            operationBack = button("任务执行中…");
            operationBack.setEnabled(false);
            operationBack.setAlpha(0.55f);
            operationBack.setOnClickListener(v -> showToolbox());
            panel.addView(operationBack, wide());
            root.addView(panel, wide());
            setAnimatedContent(scroll(root));
        });
    }

    private void completeOperation(String message, boolean success) {
        appendLog("\n" + message + "\n");
        if (operationBack != null) {
            operationBack.setText("← 返回工具箱");
            operationBack.setEnabled(true);
            operationBack.setAlpha(1f);
            operationBack.animate().scaleX(1.04f).scaleY(1.04f).setDuration(180)
                .withEndAction(() -> operationBack.animate().scaleX(1f).scaleY(1f).setDuration(160).start())
                .start();
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void confirmClearDataAdb() {
        if (installing.get()) {
            Toast.makeText(this, "已有任务正在执行", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("危险：清空 /data/adb/")
            .setMessage("这会删除全部 KernelSU 模块、授权、配置和其他 root 数据，且无法恢复。设备重启后 root 环境可能需要重新配置。")
            .setNegativeButton("取消", null)
            .setPositiveButton("我了解风险，继续", (dialog, which) -> showClearConfirmation())
            .show();
    }

    private void showClearConfirmation() {
        EditText confirmation = new EditText(this);
        confirmation.setHint("输入：清空全部数据");
        confirmation.setSingleLine(true);
        confirmation.setTextColor(TEXT);
        confirmation.setHintTextColor(MUTED);
        int padding = dp(20);
        FrameLayout wrapper = new FrameLayout(this);
        wrapper.setPadding(padding, 0, padding, 0);
        wrapper.addView(confirmation, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("最终确认")
            .setMessage("请输入“清空全部数据”后才能执行。执行后请勿立刻重启，先查看日志。")
            .setView(wrapper)
            .setNegativeButton("取消", null)
            .setPositiveButton("永久删除", null)
            .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!"清空全部数据".equals(confirmation.getText().toString().trim())) {
                confirmation.setError("确认文字不正确");
                return;
            }
            dialog.dismiss();
            clearDataAdb();
        }));
        dialog.show();
    }

    private void clearDataAdb() {
        if (!installing.compareAndSet(false, true)) return;
        showOperationPage("清理 /data/adb/", "正在执行不可恢复的数据清理，请勿重启设备。");
        new Thread(() -> {
            boolean success = false;
            try {
                String command = "set -e; [ -d /data/adb ] || { echo '/data/adb 不存在' >&2; exit 30; }; "
                    + "echo '即将删除：'; ls -la /data/adb; "
                    + "for item in /data/adb/* /data/adb/.[!.]* /data/adb/..?*; do "
                    + "[ -e \"$item\" ] || continue; echo \"删除 $item\"; rm -rf -- \"$item\"; done; "
                    + "sync; echo '/data/adb/ 已清空'";
                Process process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) appendLog(line + "\n");
                }
                int code = process.waitFor();
                appendLog("退出码：" + code + "\n");
                success = code == 0;
            } catch (Throwable error) {
                appendLog("清理错误：" + error.getMessage() + "\n");
            } finally {
                installing.set(false);
                boolean result = success;
                runOnUiThread(() -> completeOperation(
                    result ? "清理完成，请按需重新配置 root 环境" : "清理失败，请查看日志",
                    result
                ));
            }
        }, "wtlyf-data-adb-cleaner").start();
    }

    private File copyAsset(String name) throws Exception {
        File file = new File(getCacheDir(), name);
        try (InputStream input = getAssets().open(name); FileOutputStream output = new FileOutputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            for (int count; (count = input.read(buffer)) >= 0;) output.write(buffer, 0, count);
        }
        return file;
    }

    private void appendLog(String text) {
        runOnUiThread(() -> {
            log.append(text);
            View parent = (View) log.getParent();
            if (parent != null) parent.post(() -> parent.scrollTo(0, log.getBottom()));
        });
    }

    private boolean isActivated() {
        String code = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_CODE, null);
        return code != null && verify(androidId(), code);
    }

    private String androidId() {
        String id = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
    }

    private boolean verify(String androidId, String code) {
        try {
            String normalized = code.trim().replace("\n", "").replace("\r", "");
            if (!normalized.startsWith(PREFIX)) return false;
            byte[] signature = Base64.decode(normalized.substring(PREFIX.length()), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            byte[] keyBytes = Base64.decode(PUBLIC_KEY_BASE64, Base64.DEFAULT);
            java.security.PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(keyBytes));
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(key);
            verifier.update(("Night|1|" + androidId.trim().toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
            return verifier.verify(signature);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private LinearLayout rootLayout() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(32), dp(20), dp(32));
        layout.setBackgroundColor(Color.TRANSPARENT);
        return layout;
    }

    private View scroll(View child) {
        FrameLayout scene = new FrameLayout(this);
        ImageView background = new ImageView(this);
        background.setImageResource(R.drawable.wtlyf_background);
        background.setScaleType(ImageView.ScaleType.CENTER_CROP);
        scene.addView(background, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ));

        View shade = new View(this);
        shade.setBackgroundColor(Color.argb(108, 2, 10, 24));
        scene.addView(shade, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.TRANSPARENT);
        scroll.setClipToPadding(false);
        scroll.addView(child, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scene.addView(scroll, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ));
        return scene;
    }

    private LinearLayout card() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(18), dp(16), dp(18), dp(16));
        layout.setElevation(dp(8));
        layout.setBackground(glassDrawable(Color.argb(195, 13, 25, 48), 24, Color.argb(145, 160, 220, 255)));
        return layout;
    }

    private TextView title(String text, int sp) {
        TextView view = label(text, sp, ACCENT);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private TextView label(String text, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setPadding(0, dp(5), 0, dp(5));
        return view;
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setPadding(dp(18), dp(12), dp(18), dp(12));
        button.setElevation(dp(4));
        button.setBackground(rippleButton(
            new int[] { Color.rgb(47, 112, 194), Color.rgb(70, 80, 186) },
            Color.argb(110, 210, 240, 255)
        ));
        button.setOnTouchListener((view, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                view.animate().scaleX(0.975f).scaleY(0.975f).setDuration(90).start();
            } else if (event.getAction() == android.view.MotionEvent.ACTION_UP
                || event.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
                view.animate().scaleX(1f).scaleY(1f).setDuration(130).start();
            }
            return false;
        });
        return button;
    }

    private Button primaryButton(String text) {
        Button button = button(text);
        button.setTextSize(18);
        button.setMinHeight(dp(58));
        button.setBackground(rippleButton(
            new int[] { Color.rgb(45, 165, 242), Color.rgb(101, 83, 224) },
            Color.argb(130, 230, 245, 255)
        ));
        return button;
    }

    private Button dangerButton(String text) {
        Button button = button(text);
        button.setTextSize(16);
        button.setMinHeight(dp(54));
        button.setBackground(rippleButton(
            new int[] { Color.rgb(190, 49, 79), Color.rgb(111, 25, 69) },
            Color.argb(140, 255, 220, 225)
        ));
        return button;
    }

    private GradientDrawable glassDrawable(int color, int radiusDp, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private RippleDrawable rippleButton(int[] colors, int rippleColor) {
        GradientDrawable content = new GradientDrawable(
            GradientDrawable.Orientation.TL_BR, colors
        );
        content.setCornerRadius(dp(20));
        content.setStroke(dp(1), Color.argb(155, 190, 230, 255));
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, null);
    }

    private void setAnimatedContent(View content) {
        content.setAlpha(0f);
        content.setTranslationY(dp(24));
        setContentView(content);
        content.animate().alpha(1f).translationY(0f).setDuration(320).start();
    }

    private LinearLayout.LayoutParams wide() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(9);
        return params;
    }

    private void addSpace(LinearLayout layout, int dp) {
        View space = new View(this);
        layout.addView(space, new LinearLayout.LayoutParams(1, dp(dp)));
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static final class ModuleItem {
        final String name;
        final String version;
        final String asset;
        ModuleItem(String name, String version, String asset) {
            this.name = name;
            this.version = version;
            this.asset = asset;
        }
    }
}

