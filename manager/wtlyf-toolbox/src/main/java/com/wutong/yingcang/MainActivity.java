package com.wutong.yingcang;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
    private static final String PUBLIC_KEY_BASE64 =
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAxPm4IldYf9tF/Y0UWLi+2EbCMqSexmTpOHitEFtkCzLydcBguhgXg1qjapu1SqkmF2HEkD7xKl9zDRqu0b9ExK2YwSwmuPJIOjli+5il0Vc9/2CYKcLU3htMd8juCT7e6mVz31mJ6llf42yM+iCCPQ+JvQer5uCACyLGy8A1ArF9IKt8IZFlsb9r09/WZcdbLv1p0ASFRBLzVwv3JgT13oSQp0x1I63pZ/eeJzcjCzmmrDPsgsIXBXsKJxLyJFAzTWL7Xj0fZS8TkI1awyIUTMgNi+XO3Tn3y9cWxu1JG5niwAQVp1bjM9olG9tYDEvNAO5WXRGsRHI3keJWGs/xfQIDAQAB";

    private static final ModuleItem[] MODULES = {
        new ModuleItem("TEESimulator-RS", "v6.0.0-162", "tee-simulator.zip"),
        new ModuleItem("Tricky Addon", "v5.0-beta.1", "tricky-addon.zip"),
        new ModuleItem("TrickyStore自动添加应用", "v1.1", "tricky-auto-add.zip")
    };

    private final AtomicBoolean installing = new AtomicBoolean(false);
    private TextView log;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        if (isActivated()) showToolbox(); else showActivation();
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
        setContentView(scroll(root));
    }

    private void showToolbox() {
        LinearLayout root = rootLayout();
        root.addView(title("wtlyf", 32));
        root.addView(label("KernelSU 环境部署工具箱", 16, MUTED));
        addSpace(root, 24);

        Button all = button("部署");
        all.setTextSize(18);
        all.setMinHeight(dp(58));
        all.setBackgroundColor(Color.rgb(73, 92, 205));
        all.setOnClickListener(v -> confirmInstall(MODULES));
        root.addView(all, wide());
        addSpace(root, 16);
        root.addView(label("安装调用 KernelSU 的 ksud module install；请先在 Night 面具中授予本应用 root 权限。", 13, MUTED));
        addSpace(root, 12);

        log = label("等待部署…", 13, Color.rgb(201, 232, 218));
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextIsSelectable(true);
        log.setMovementMethod(new ScrollingMovementMethod());
        log.setMinHeight(dp(220));
        log.setPadding(dp(14), dp(14), dp(14), dp(14));
        log.setBackgroundColor(Color.BLACK);
        root.addView(log, wide());
        setContentView(scroll(root));
    }

    private void confirmInstall(ModuleItem[] items) {
        if (installing.get()) {
            Toast.makeText(this, "已有部署任务正在执行", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("确认部署")
            .setMessage("将通过 KernelSU 执行内置环境部署。完成后通常需要重启设备。")
            .setNegativeButton("取消", null)
            .setPositiveButton("开始部署", (dialog, which) -> install(items))
            .show();
    }

    private void install(ModuleItem[] items) {
        if (!installing.compareAndSet(false, true)) return;
        log.setText("");
        new Thread(() -> {
            boolean success = true;
            try {
                for (int index = 0; index < items.length; index++) {
                    ModuleItem item = items[index];
                    appendLog("\n== 部署步骤 " + (index + 1) + "/" + items.length + " ==\n");
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
                runOnUiThread(() -> Toast.makeText(this, result ? "部署完成，请重启设备" : "部署失败，请查看日志", Toast.LENGTH_LONG).show());
            }
        }, "wtlyf-module-installer").start();
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
        layout.setBackgroundColor(BG);
        return layout;
    }

    private ScrollView scroll(View child) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        scroll.addView(child, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private LinearLayout card() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(18), dp(16), dp(18), dp(16));
        layout.setBackgroundColor(CARD);
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
        button.setBackgroundColor(Color.rgb(41, 91, 153));
        return button;
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
