package com.wutong.yingcang;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.res.ColorStateList;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Looper;
import android.provider.Settings;
import android.provider.OpenableColumns;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(7, 11, 20);
    private static final int CARD = Color.rgb(24, 33, 51);
    private static final int TEXT = Color.rgb(238, 244, 255);
    private static final int MUTED = Color.rgb(152, 166, 194);
    private static final int ACCENT = Color.rgb(135, 185, 255);
    private static final String PREFS = "wtlyf_activation";
    private static final String KEY_CODE = "activation_code";
    private static final String KEY_LAST_PUSH = "last_push_id";
    private static final String PUSH_BASE_URL = "https://wtlyf-night-license.pages.dev";
    private static final String MODULE_BASE_URL = PUSH_BASE_URL + "/api/v1/toolbox/modules/";
    private static final String[] LICENSE_BASE_URLS = {
        "https://wtlyf-night-license.pages.dev",
        "https://wtlyf-license-center.wtlyf-night.workers.dev",
        "https://wtlyf-license-center.creamy-bowl-8571.chatgpt.site"
    };
    private static final int REQUEST_BOOT_IMAGE = 5001;

    private static final ModuleItem ALWAYS_STRONG =
        new ModuleItem("AlwaysStrong", "v1.0.3", "always-strong.zip", "tricky_store", "6F669A7F4DD438DF3EDE42C001CF24B24E78B42E85ED21F78FC842D25737A774");
    private static final ModuleItem SOTER_KEY =
        new ModuleItem("Soter Key Fixer", "v1.2", "soterkey.zip", "SoterFix", "D2737CB9683489CED3D59E823D18D09B4117C6D17A6C68D1C30C5D047A00D3C3");
    private static final ModuleItem JAILBREAK_TOLERANCE =
        new ModuleItem("隐藏越狱模式", "v1.1", "jailbreak-tolerance.zip", "JailNotBreak", "C70256086D8CC029CBDFC370D3FE2598D2EEF857B7ECBEAA5A2F6E09F3E930D9");
    private static final ModuleItem TEE_SIMULATOR =
        new ModuleItem("TEESimulator-RS", "v6.0.1-324", "tee-simulator-v6.0.1-324.zip", "tricky_store", "1420DA7883C3754B039E7825A194B621231FF3239DB9C97E326743D41D024F39");
    private static final ModuleItem TRICKY_ADDON =
        new ModuleItem("Tricky Addon", "v4.3", "tricky-addon-v4.3.zip", "TA_utl", "6930657DF71410C348FE81C1FEB77792C4C0783902A2E5E863117CA2C66C839A");
    private static final ModuleItem TRICKY_AUTO_ADD =
        new ModuleItem("TrickyStore 自动添加应用", "v1.1", "tricky-auto-add-v1.1.zip", "trickystore_auto_add_app", "79CA804E2790C5D04720CD1374AD10D9B8194AF326F643A8C0D38C612874C620");
    private static final ModuleItem[] SCHEME_TWO = {
        TEE_SIMULATOR, TRICKY_ADDON, TRICKY_AUTO_ADD
    };
    private static final ModuleItem[] PATH_MASKS = {
        new ModuleItem("Android 12 / 5.10 PathMask", "v2.2.7", "pathmask-android12-5.10.zip", "pathmask", "0CC4DB7855B9A5A02BE1DE544C5682CD74E202B3426AFF353D399C62ED0C5C13"),
        new ModuleItem("Android 13 / 5.10 PathMask", "v2.2.7", "pathmask-android13-5.10.zip", "pathmask", "DD4CE018280FBF41F27005F67162CEEC499B30F5526E00EC7FE15BE731659DB9"),
        new ModuleItem("Android 13 / 5.15 PathMask", "v2.2.7", "pathmask-android13-5.15.zip", "pathmask", "3185F11BDC3D2982BA1A25C1811BBC853AC1C4D5DCE8774E763E260F4CD28759"),
        new ModuleItem("Android 14 / 5.15 PathMask", "v2.2.7", "pathmask-android14-5.15.zip", "pathmask", "0EDAD8FF137D73C704F7F75628A0643744976C6D45D26F6D12D93122C8880E51"),
        new ModuleItem("Android 14 / 6.1 PathMask", "v2.2.7", "pathmask-android14-6.1.zip", "pathmask", "56235B11F8677AED555B372915CBB8487AAA12E68E6E4BD623BD1427917ADAC3"),
        new ModuleItem("Android 15 / 6.6 PathMask", "v2.2.7", "pathmask-android15-6.6.zip", "pathmask", "8608EDC9D3EF58C758820F0EC08F20AF5C3F7C220B182CD9433214C357433844"),
        new ModuleItem("Android 16 / 6.12 PathMask", "v2.2.7", "pathmask-android16-6.12.zip", "pathmask", "4E7000E8477652957FF6B1772222F1B55CD43AE32E941B289952445C8741FEFE")
    };

    private final AtomicBoolean installing = new AtomicBoolean(false);
    private final AtomicBoolean rootChecking = new AtomicBoolean(false);
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
        String savedCode = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_CODE, null);
        if (savedCode == null || savedCode.isBlank()) showActivation(); else verifyLicenseAndOpen(savedCode, true);
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
        root.addView(label("卡密验证", 20, TEXT));
        addSpace(root, 22);

        LinearLayout card = card();
        EditText code = new EditText(this);
        code.setHint("输入 WTLYF 卡密");
        code.setHintTextColor(MUTED);
        code.setTextColor(TEXT);
        code.setMinLines(3);
        card.addView(code, wide());
        Button activate = button("激活并进入");
        activate.setOnClickListener(v -> {
            String value = code.getText().toString().trim().replaceAll("\\s+", "");
            if (value.isEmpty() || value.length() > 128) { code.setError("请输入 1 到 128 个字符的卡密"); return; }
            activate.setEnabled(false);
            activate.setText("正在验证…");
            verifyLicenseAndOpen(value, false);
        });
        card.addView(activate, wide());
        root.addView(card, wide());
        addSpace(root, 14);
        root.addView(label("一台设备绑定一张卡密。仅上传不可逆设备指纹，不上传明文 Android ID。", 13, MUTED));
        setAnimatedContent(scroll(root));
    }

    private void verifyLicenseAndOpen(String code, boolean stored) {
        if (stored) showLicenseGate("正在验证已绑定卡密…");
        new Thread(() -> {
            try {
                JSONObject request = new JSONObject().put("code", code).put("deviceHash", deviceFingerprint());
                JSONObject response = postJson(stored ? "/api/v1/check" : "/api/v1/activate", request);
                if (response.optBoolean("ok")) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_CODE, code).apply();
                    runOnUiThread(this::verifyRootAndOpen);
                    return;
                }
                String error = response.optString("error");
                String message = response.optString("message", "卡密验证失败");
                if ("code_in_use".equals(error)) message = "卡密已被登录";
                if (stored && ("code_in_use".equals(error) || "invalid_code".equals(error))) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_CODE).apply();
                }
                String finalMessage = message;
                runOnUiThread(() -> { showActivation(); Toast.makeText(this, finalMessage, Toast.LENGTH_LONG).show(); });
            } catch (Throwable error) {
                String message = "无法连接卡密服务器，请检查网络后重试：" + error.getMessage();
                runOnUiThread(() -> { showActivation(); Toast.makeText(this, message, Toast.LENGTH_LONG).show(); });
            }
        }, "wtlyf-license-verifier").start();
    }

    private void showLicenseGate(String message) {
        LinearLayout root = rootLayout(); root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title("wtlyf", 34)); root.addView(label("卡密验证", 18, MUTED)); addSpace(root, 28);
        LinearLayout panel = card(); TextView heading = label(message, 21, TEXT); heading.setGravity(Gravity.CENTER); panel.addView(heading, wide());
        panel.addView(label("正在通过加密连接核对当前设备绑定状态", 14, MUTED), wide()); root.addView(panel, wide());
        setAnimatedContent(scroll(root));
    }

    private JSONObject postJson(String path, JSONObject body) throws Exception {
        Exception lastError = null;
        for (String baseUrl : LICENSE_BASE_URLS) {
            try {
                return postJsonHttp(baseUrl, path, body);
            } catch (NonJsonResponse error) {
                try {
                    return postJsonWebView(baseUrl, path, body);
                } catch (Exception fallbackError) {
                    lastError = fallbackError;
                }
            } catch (Exception error) {
                lastError = error;
            }
        }
        throw new Exception("所有卡密服务地址均连接失败" + (lastError == null ? "" : "：" + lastError.getMessage()));
    }

    private JSONObject postJsonHttp(String baseUrl, String path, JSONObject body) throws Exception {
        URL current = new URL(baseUrl + path);
        for (int redirect = 0; redirect < 4; redirect++) {
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000); connection.setReadTimeout(15000); connection.setRequestMethod("POST");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "wtlyf-android/1.6.2");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8"); connection.setDoOutput(true);
            try (OutputStream output = connection.getOutputStream()) { output.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            int status = connection.getResponseCode();
            if (status == 301 || status == 302 || status == 307 || status == 308) {
                String location = connection.getHeaderField("Location"); connection.disconnect();
                if (location == null || location.isBlank()) throw new NonJsonResponse("服务器重定向缺少目标地址");
                current = new URL(current, location); continue;
            }
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            StringBuilder text = new StringBuilder();
            if (stream != null) try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) { for (String line; (line = reader.readLine()) != null;) text.append(line); }
            String contentType = connection.getHeaderField("Content-Type"); connection.disconnect();
            String response = text.toString().trim();
            if ((contentType == null || !contentType.toLowerCase(Locale.ROOT).contains("json")) || response.startsWith("<")) {
                throw new NonJsonResponse("系统网络组件返回了网页内容");
            }
            return new JSONObject(response.length() == 0 ? "{}" : response);
        }
        throw new NonJsonResponse("服务器重定向次数过多");
    }

    private JSONObject postJsonWebView(String baseUrl, String path, JSONObject body) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        AtomicReference<String> failure = new AtomicReference<>();
        runOnUiThread(() -> {
            WebView webView = new WebView(this);
            webView.getSettings().setJavaScriptEnabled(true);
            webView.getSettings().setDomStorageEnabled(false);
            webView.setWebViewClient(new WebViewClient());
            webView.addJavascriptInterface(new Object() {
                @JavascriptInterface public void complete(String value) {
                    result.set(value); latch.countDown(); runOnUiThread(webView::destroy);
                }
                @JavascriptInterface public void fail(String value) {
                    failure.set(value); latch.countDown(); runOnUiThread(webView::destroy);
                }
            }, "WtlyfBridge");
            String payload = JSONObject.quote(body.toString());
            String html = "<!doctype html><meta charset=utf-8><script>"
                + "fetch(" + JSONObject.quote(path) + ",{method:'POST',headers:{'Accept':'application/json','Content-Type':'application/json'},body:" + payload + "})"
                + ".then(async r=>WtlyfBridge.complete(JSON.stringify({status:r.status,type:r.headers.get('content-type')||'',body:await r.text()})))"
                + ".catch(e=>WtlyfBridge.fail(String(e)))</script>";
            webView.loadDataWithBaseURL(baseUrl + "/", html, "text/html", "UTF-8", null);
        });
        if (!latch.await(25, TimeUnit.SECONDS)) throw new Exception("浏览器网络验证超时");
        if (failure.get() != null) throw new Exception("浏览器网络验证失败：" + failure.get());
        JSONObject envelope = new JSONObject(result.get() == null ? "{}" : result.get());
        String response = envelope.optString("body", "").trim();
        String type = envelope.optString("type", "");
        if (!type.toLowerCase(Locale.ROOT).contains("json") || response.startsWith("<")) {
            throw new Exception("当前网络仍将卡密接口替换为网页，请关闭代理、VPN 或切换网络后重试");
        }
        return new JSONObject(response.length() == 0 ? "{}" : response);
    }

    private static final class NonJsonResponse extends Exception {
        NonJsonResponse(String message) { super(message); }
    }

    private String deviceFingerprint() throws Exception {
        String androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
        byte[] cert = info.signingInfo.getApkContentsSigners()[0].toByteArray();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update("wtlyf-device-v1|".getBytes(StandardCharsets.UTF_8));
        digest.update((androidId == null ? "" : androidId).getBytes(StandardCharsets.UTF_8)); digest.update(cert);
        StringBuilder hex = new StringBuilder(); for (byte value : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", value)); return hex.toString();
    }

    private void verifyRootAndOpen() {
        if (!rootChecking.compareAndSet(false, true)) return;
        showRootGate("正在向 KernelSU 请求 Root 权限…", true);
        new Thread(() -> {
            boolean granted = false;
            String detail = "未授予 Root 权限，工具箱无法使用。";
            Process process = null;
            try {
                process = new ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start();
                boolean finished = process.waitFor(20, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    detail = "Root 授权等待超时，请在 KernelSU 中允许 wtlyf 后重试。";
                } else {
                    StringBuilder output = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) output.append(line).append('\n');
                    }
                    granted = process.exitValue() == 0 && output.toString().contains("uid=0");
                    if (!granted) detail = "KernelSU 未授予 wtlyf Root 权限，请授权后重新检测。";
                }
            } catch (Throwable error) {
                detail = "无法取得 Root 权限：" + error.getMessage();
            } finally {
                rootChecking.set(false);
                boolean result = granted;
                String message = detail;
                runOnUiThread(() -> {
                    if (result) showToolbox(); else showRootGate(message, false);
                });
            }
        }, "wtlyf-root-verifier").start();
    }

    private void showRootGate(String status, boolean checking) {
        LinearLayout root = rootLayout();
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title("wtlyf", 34));
        root.addView(label("Root 权限验证", 18, MUTED));
        addSpace(root, 28);

        LinearLayout panel = card();
        TextView icon = label(checking ? "ROOT …" : "ROOT ×", 30,
            checking ? Color.rgb(151, 206, 255) : Color.rgb(255, 164, 186));
        icon.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        icon.setGravity(Gravity.CENTER);
        panel.addView(icon, wide());
        TextView heading = label(checking ? "正在验证 Root 权限" : "未给予 Root 权限，无法使用", 21, TEXT);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setGravity(Gravity.CENTER);
        panel.addView(heading, wide());
        TextView description = label(status + "\n\n请在 Night / KernelSU 管理器中为 wtlyf 开启永久 Root 授权，然后返回重新检测。", 14, MUTED);
        description.setGravity(Gravity.CENTER);
        description.setLineSpacing(0f, 1.18f);
        panel.addView(description, wide());

        Button retry = primaryButton(checking ? "正在等待授权…" : "申请 / 重新检测 Root 权限");
        retry.setEnabled(!checking);
        retry.setAlpha(checking ? 0.58f : 1f);
        retry.setOnClickListener(v -> verifyRootAndOpen());
        panel.addView(retry, wide());
        root.addView(panel, wide());
        addSpace(root, 16);
        root.addView(label("未通过 Root 验证时不会加载部署、刷写或清理功能。", 13, MUTED));
        setAnimatedContent(scroll(root));
    }

    private void showToolbox() {
        LinearLayout root = rootLayout();
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView toolboxTitle = title("wtlyf", 32);
        header.addView(toolboxTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button reboot = button("重启");
        reboot.setTextSize(14);
        reboot.setMinHeight(dp(44));
        reboot.setPadding(dp(18), dp(8), dp(18), dp(8));
        reboot.setOnClickListener(v -> confirmReboot());
        header.addView(reboot, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(header, wide());
        root.addView(label("KernelSU 环境部署工具箱", 16, MUTED));
        root.addView(label("卡密已绑定当前设备", 12, Color.rgb(135, 205, 255)));
        addSpace(root, 24);

        Button alwaysStrong = primaryButton("方案1");
        alwaysStrong.setOnClickListener(v -> confirmInstall(
            "部署方案1",
            "将从服务器下载并通过 KernelSU 安装 AlwaysStrong v1.0.3。安装成功后会导入 keybox.xml，并自动按预设开启指纹、Keybox、状态指示和屏蔽 ROM 伪装，检查间隔设为 5 分钟，关闭自定义 Keybox。",
            new ModuleItem[] { ALWAYS_STRONG },
            this::applyAlwaysStrongProfile
        ));
        root.addView(alwaysStrong, wide());

        Button schemeTwo = primaryButton("方案2");
        schemeTwo.setOnClickListener(v -> confirmInstall(
            "部署方案2",
            "将从服务器依次下载并安装 TEESimulator-RS v6.0.1-324、Tricky Addon 和 TrickyStore 自动添加应用。三个模块全部成功后，才会替换 /data/adb/tricky_store/keybox.xml。",
            SCHEME_TWO,
            this::applySchemeTwoConfig
        ));
        root.addView(schemeTwo, wide());

        addSpace(root, 14);
        root.addView(label("骁龙 · 必须先选择 PathMask", 17, TEXT));
        root.addView(label("请选择与你设备 Android 版本及内核版本完全一致的选项。点击后会先安装 PathMask，再安装 Soter Key Fixer。", 13, MUTED));
        String[] pathMaskLabels = {
            "请选择 PathMask 版本",
            "Android 12 / Kernel 5.10",
            "Android 13 / Kernel 5.10",
            "Android 13 / Kernel 5.15",
            "Android 14 / Kernel 5.15",
            "Android 14 / Kernel 6.1",
            "Android 15 / Kernel 6.6",
            "Android 16 / Kernel 6.12"
        };
        pathMaskSpinner = spinner(pathMaskLabels);
        root.addView(pathMaskSpinner, wide());

        Button xiaolong = primaryButton("骁龙点我");
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
        root.addView(label("模块由服务器按需下载并校验 SHA-256，再调用 KernelSU 的 ksud module install；请保持网络连接并授予本应用 root 权限。", 13, MUTED));
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
        checkRemotePush();
    }

    private void checkRemotePush() {
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(PUSH_BASE_URL + "/api/v1/push/latest").openConnection();
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(12000);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("User-Agent", "wtlyf-android/1.6.3");
                if (connection.getResponseCode() != 200) return;
                StringBuilder text = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    for (String line; (line = reader.readLine()) != null;) text.append(line);
                }
                JSONObject response = new JSONObject(text.toString());
                JSONObject push = response.optJSONObject("push");
                if (push == null) return;
                int minimumVersion = push.optInt("minVersionCode", 0);
                if (minimumVersion > 0 && minimumVersion <= currentVersionCode()) return;
                String id = push.optString("id");
                if (id.isBlank() || id.equals(getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_LAST_PUSH, ""))) return;
                runOnUiThread(() -> showRemotePush(push));
            } catch (Throwable ignored) {
            } finally {
                if (connection != null) connection.disconnect();
            }
        }, "wtlyf-push-checker").start();
    }

    private void showRemotePush(JSONObject push) {
        String id = push.optString("id");
        String title = push.optString("title", "WTLYF 通知");
        String message = push.optString("message", "");
        String fileName = push.optString("fileName", "");
        String downloadUrl = push.optString("downloadUrl", "");
        boolean forceUpdate = push.optBoolean("forceUpdate", false)
            && push.optInt("minVersionCode", 0) > currentVersionCode();
        String body = message;
        if (!fileName.isBlank()) body += (body.isBlank() ? "" : "\n\n") + "附件：" + fileName;
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(body.isBlank() ? "收到一条新的文件推送" : body);
        if (!forceUpdate) builder.setNegativeButton("稍后", null);
        if (!fileName.isBlank() && !downloadUrl.isBlank()) {
            builder.setPositiveButton("下载文件", (ignored, which) -> {
                if (!forceUpdate) markPushSeen(id);
                downloadRemoteFile(downloadUrl, fileName, push.optString("contentType", "application/octet-stream"));
            });
        } else {
            builder.setPositiveButton("知道了", (ignored, which) -> markPushSeen(id));
        }
        AlertDialog dialog = builder.create();
        dialog.setCancelable(!forceUpdate);
        dialog.setCanceledOnTouchOutside(!forceUpdate);
        showGlassDialog(dialog, false);
        if (forceUpdate && !fileName.isBlank() && !downloadUrl.isBlank()) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText("立即下载更新");
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view ->
                downloadRemoteFile(downloadUrl, fileName, push.optString("contentType", "application/vnd.android.package-archive"))
            );
        }
    }

    private void markPushSeen(String id) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_LAST_PUSH, id).apply();
    }

    private int currentVersionCode() {
        try {
            long version = getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode();
            return version > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) version;
        } catch (Throwable ignored) {
            // A failed local version lookup must never turn an ordinary notification into
            // a permanent update loop.
            return Integer.MAX_VALUE;
        }
    }

    private void downloadRemoteFile(String path, String requestedName, String contentType) {
        try {
            String fileName = requestedName.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
            if (fileName.isBlank()) fileName = "wtlyf-push.bin";
            Uri uri = Uri.parse(path.startsWith("http") ? path : PUSH_BASE_URL + path);
            DownloadManager.Request request = new DownloadManager.Request(uri)
                .setTitle(fileName)
                .setDescription("WTLYF 推送文件")
                .setMimeType(contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            manager.enqueue(request);
            Toast.makeText(this, "已加入系统下载任务", Toast.LENGTH_LONG).show();
        } catch (Throwable error) {
            Toast.makeText(this, "无法启动下载：" + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void confirmReboot() {
        if (installing.get()) {
            Toast.makeText(this, "任务执行中，暂时不能重启", Toast.LENGTH_SHORT).show();
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("重启设备")
            .setMessage("确定现在重启设备吗？请先确认部署或刷写任务已经完成。")
            .setNegativeButton("取消", null)
            .setPositiveButton("立即重启", (ignoredDialog, which) -> rebootDevice())
            .create();
        showGlassDialog(dialog, true);
    }

    private void rebootDevice() {
        Toast.makeText(this, "正在请求 Root 重启…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                Process process = new ProcessBuilder("su", "-c", "reboot").redirectErrorStream(true).start();
                int code = process.waitFor();
                if (code != 0) {
                    runOnUiThread(() -> Toast.makeText(this, "重启失败，请检查 Root 权限", Toast.LENGTH_LONG).show());
                }
            } catch (Throwable error) {
                runOnUiThread(() -> Toast.makeText(this, "重启失败：" + error.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "wtlyf-reboot").start();
    }

    private Spinner spinner(String[] items) {
        Spinner spinner = new Spinner(this, Spinner.MODE_DROPDOWN);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                return spinnerRow(position, convertView, false);
            }

            @Override public View getDropDownView(int position, View convertView, ViewGroup parent) {
                return spinnerRow(position, convertView, true);
            }

            private TextView spinnerRow(int position, View convertView, boolean dropdown) {
                TextView view = convertView instanceof TextView ? (TextView) convertView : new TextView(MainActivity.this);
                view.setText(getItem(position));
                view.setTextColor(TEXT);
                view.setTextSize(dropdown ? 17 : 16);
                view.setGravity(Gravity.CENTER_VERTICAL);
                view.setMinHeight(dp(dropdown ? 58 : 54));
                view.setPadding(dp(18), dp(12), dp(18), dp(12));
                view.setBackground(dropdown
                    ? glassDrawable(Color.argb(218, 17, 32, 61), 14, Color.argb(105, 155, 215, 255))
                    : null);
                return view;
            }
        };
        spinner.setAdapter(adapter);
        spinner.setBackground(glassDrawable(
            Color.argb(188, 15, 31, 60), 18, Color.argb(150, 166, 220, 255)
        ));
        spinner.setPopupBackgroundDrawable(glassDrawable(
            Color.argb(238, 8, 20, 43), 22, Color.argb(175, 158, 218, 255)
        ));
        spinner.setDropDownVerticalOffset(dp(6));
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
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("高风险操作：刷写 " + partition + "_" + slot)
            .setMessage("即将把 " + displayName(bootImageUri) + " 写入 " + partition + "_" + slot + "。镜像或槽位选择错误可能导致设备无法启动，确认继续？")
            .setNegativeButton("取消", null)
            .setPositiveButton("确认刷写", (ignoredDialog, which) -> flashImage(partition, slot))
            .create();
        showGlassDialog(dialog, true);
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
            "骁龙部署确认",
            "将先安装 " + pathMask.name + "，成功后再安装 Soter Key Fixer。两个模块全部成功后，将 PathMask 设为全局并把开机等待时间改为 5 秒。PathMask 选错版本可能导致设备异常，请确认选择正确。",
            new ModuleItem[] { pathMask, SOTER_KEY },
            this::applyPathMaskProfile
        );
    }

    private void confirmInstall(String title, String message, ModuleItem[] items) {
        confirmInstall(title, message, items, null);
    }

    private void confirmInstall(String title, String message, ModuleItem[] items, PostInstallAction postInstallAction) {
        if (installing.get()) {
            Toast.makeText(this, "已有部署任务正在执行", Toast.LENGTH_SHORT).show();
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton("开始部署", null)
            .create();
        showGlassDialog(dialog, false);
        Button start = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (start != null) {
            start.setOnClickListener(view -> {
                if (installing.get()) {
                    Toast.makeText(this, "已有部署任务正在执行", Toast.LENGTH_SHORT).show();
                    return;
                }
                start.setEnabled(false);
                start.setText("正在启动…");
                dialog.dismiss();
                install(items, postInstallAction);
            });
        }
    }

    private void install(ModuleItem[] items) {
        install(items, null);
    }

    private void install(ModuleItem[] items, PostInstallAction postInstallAction) {
        if (!installing.compareAndSet(false, true)) return;
        showOperationPage("模块部署", "正在调用 KernelSU 安装模块，完成后可返回工具箱。");
        new Thread(() -> {
            boolean success = true;
            try {
                for (int index = 0; index < items.length; index++) {
                    ModuleItem item = items[index];
                    appendLog("\n== 部署步骤 " + (index + 1) + "/" + items.length + " ==\n");
                    appendLog(item.name + " " + item.version + "\n");
                    appendLog("正在从服务器下载模块…\n");
                    File zip = downloadModule(item);
                    int code = installModuleWithTimeout(zip);
                    appendLog("退出码：" + code + "\n");
                    zip.delete();
                    if (code != 0) {
                        if (waitForModuleInstalled(item.id)) {
                            appendLog("安装器返回非零退出码，但已检测到模块 " + item.id + " 成功落盘，按部署成功继续。\n");
                        } else {
                            success = false;
                            appendLog("部署失败，且未检测到模块落盘，已停止后续模块。\n");
                            break;
                        }
                    }
                }
                if (success && postInstallAction != null) {
                    appendLog("\n== 正在执行安装后配置 ==\n");
                    success = postInstallAction.run();
                    if (!success) appendLog("安装后配置失败。\n");
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

    private boolean applySchemeTwoConfig() throws Exception {
        File script = copyAsset("scheme2-post-install.sh");
        File keybox = copyAsset("keybox.xml");
        try {
            return runRootCommand(
                "sh " + shellQuote(script.getAbsolutePath()) + " " + shellQuote(keybox.getAbsolutePath())
            );
        } finally {
            script.delete();
            keybox.delete();
        }
    }

    private boolean applyAlwaysStrongProfile() throws Exception {
        File script = copyAsset("always-strong-profile.sh");
        try {
            boolean scriptSucceeded = runRootCommand("sh " + shellQuote(script.getAbsolutePath()));
            boolean keyboxSucceeded = applySchemeTwoConfig();
            if (scriptSucceeded && keyboxSucceeded) return true;

            appendLog("配置脚本返回非零退出码，正在核对 AlwaysStrong 实际配置…\n");
            boolean verified = verifyAlwaysStrongProfile() && keyboxSucceeded;
            if (verified) {
                appendLog("已确认模块和配置均已正确落盘，按部署成功处理。\n");
            }
            return verified;
        } finally {
            script.delete();
        }
    }

    private boolean verifyAlwaysStrongProfile() {
        String config = "/data/adb/tricky_store";
        String command = "([ -d /data/adb/modules_update/tricky_store ] || [ -d /data/adb/modules/tricky_store ])"
            + " && [ \"$(cat " + config + "/hourly_interval_sec 2>/dev/null)\" = 300 ]"
            + " && [ ! -e " + config + "/no_auto_fp ]"
            + " && [ ! -e " + config + "/no_auto_keybox ]"
            + " && [ ! -e " + config + "/no_auto_indicator ]"
            + " && [ ! -e " + config + "/no_rom_spoof_block ]"
            + " && [ ! -e " + config + "/custom_keybox ]";
        try {
            Process process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
            return process.waitFor() == 0;
        } catch (Throwable error) {
            appendLog("核对 AlwaysStrong 配置失败：" + error.getMessage() + "\n");
            return false;
        }
    }

    private boolean applyPathMaskProfile() throws Exception {
        File script = copyAsset("pathmask-profile.sh");
        try {
            boolean scriptSucceeded = runRootCommand("sh " + shellQuote(script.getAbsolutePath()));
            if (scriptSucceeded) return true;

            appendLog("PathMask 配置脚本返回非零退出码，正在核对实际安装结果…\n");
            boolean verified = verifyPathMaskDeployment();
            if (verified) {
                appendLog("已确认 PathMask、SoterFix、全局模式与 5 秒等待配置均已落盘，按部署成功处理。\n");
                return true;
            }
            boolean modulesInstalled = waitForModuleInstalled("pathmask") && waitForModuleInstalled("SoterFix");
            if (modulesInstalled) {
                appendLog("两个模块均已安装成功；后置配置未能完全核对，仅作为警告，不再误报部署失败。\n");
                return true;
            }
            return false;
        } finally {
            script.delete();
        }
    }

    private boolean verifyPathMaskDeployment() {
        if (!isModuleInstalled("pathmask") || !isModuleInstalled("SoterFix")) return false;
        String command = "[ \"$(cat /data/adb/pathmask/scope_mode.conf 2>/dev/null)\" = global ]"
            + " && [ \"$(cat /data/adb/pathmask/wait_seconds.conf 2>/dev/null)\" = 5 ]"
            + " && { [ ! -d /data/adb/modules_update/pathmask ]"
            + " || { [ \"$(cat /data/adb/modules_update/pathmask/scope_mode.conf 2>/dev/null)\" = global ]"
            + " && [ \"$(cat /data/adb/modules_update/pathmask/wait_seconds.conf 2>/dev/null)\" = 5 ]; }; }"
            + " && { [ ! -d /data/adb/modules/pathmask ]"
            + " || { [ \"$(cat /data/adb/modules/pathmask/scope_mode.conf 2>/dev/null)\" = global ]"
            + " && [ \"$(cat /data/adb/modules/pathmask/wait_seconds.conf 2>/dev/null)\" = 5 ]; }; }";
        try {
            Process process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
            return process.waitFor() == 0;
        } catch (Throwable error) {
            appendLog("核对骁龙部署结果失败：" + error.getMessage() + "\n");
            return false;
        }
    }

    private boolean runRootCommand(String command) throws Exception {
        Process process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) appendLog(line + "\n");
        }
        int code = process.waitFor();
        appendLog("后置配置退出码：" + code + "\n");
        return code == 0;
    }

    private boolean isModuleInstalled(String moduleId) {
        try {
            String quotedId = shellQuote(moduleId);
            String expected = shellQuote("id=" + moduleId);
            String command = "[ -d /data/adb/modules_update/" + quotedId
                + " ] || [ -d /data/adb/modules/" + quotedId + " ]"
                + " || { for PROP in /data/adb/modules_update/*/module.prop /data/adb/modules/*/module.prop; do"
                + " [ -f \"$PROP\" ] && grep -Fqx " + expected + " \"$PROP\" && exit 0;"
                + " done; exit 1; }";
            Process process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
            return process.waitFor() == 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean waitForModuleInstalled(String moduleId) {
        for (int attempt = 0; attempt < 10; attempt++) {
            if (isModuleInstalled(moduleId)) return true;
            try { Thread.sleep(300L); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
        }
        return false;
    }

    private int installModuleWithTimeout(File zip) throws Exception {
        String path = shellQuote(zip.getAbsolutePath());
        String command = "ZIP=" + path + "; KSUD=''; "
            + "for CANDIDATE in /data/adb/ksud /data/adb/ksu/bin/ksud /data/adb/ksu/ksud /system/bin/ksud; do "
            + "[ -x \"$CANDIDATE\" ] && { KSUD=\"$CANDIDATE\"; break; }; done; "
            + "if [ -z \"$KSUD\" ]; then KSUD=$(command -v ksud 2>/dev/null || true); fi; "
            + "if [ -z \"$KSUD\" ]; then echo '未找到 KernelSU 模块安装器 ksud' >&2; exit 127; fi; "
            + "echo \"使用安装器：$KSUD\"; exec \"$KSUD\" module install \"$ZIP\"";
        Process process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
        Thread outputReader = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) appendLog(line + "\n");
            } catch (Throwable error) {
                appendLog("读取安装日志失败：" + error.getMessage() + "\n");
            }
        }, "wtlyf-ksud-output");
        outputReader.start();
        boolean finished = process.waitFor(180, TimeUnit.SECONDS);
        if (!finished) {
            process.destroy();
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
            outputReader.join(3000L);
            throw new Exception("模块安装等待超过 3 分钟，已停止任务；请检查 KernelSU 授权和模块兼容性");
        }
        outputReader.join(3000L);
        return process.exitValue();
    }

    private void showOperationPage(String heading, String subtitle) {
        Runnable render = () -> {
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
        };
        if (Looper.myLooper() == Looper.getMainLooper()) render.run(); else runOnUiThread(render);
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
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("危险：清空 /data/adb/")
            .setMessage("这会删除全部 KernelSU 模块、授权、配置和其他 root 数据，且无法恢复。设备重启后 root 环境可能需要重新配置。")
            .setNegativeButton("取消", null)
            .setPositiveButton("确认清理", (ignoredDialog, which) -> clearDataAdb())
            .create();
        showGlassDialog(dialog, true);
    }

    private void showGlassDialog(AlertDialog dialog, boolean danger) {
        dialog.show();
        styleGlassDialog(dialog, danger);
    }

    private void styleGlassDialog(AlertDialog dialog, boolean danger) {
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawable(glassDrawable(
            danger ? Color.argb(238, 48, 15, 39) : Color.argb(235, 11, 27, 55),
            26,
            danger ? Color.argb(190, 255, 138, 173) : Color.argb(185, 153, 216, 255)
        ));
        WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.dimAmount = 0.68f;
        window.setAttributes(attributes);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);

        int accent = danger ? Color.rgb(255, 155, 180) : Color.rgb(151, 206, 255);
        TextView title = dialog.findViewById(getResources().getIdentifier("alertTitle", "id", "android"));
        TextView message = dialog.findViewById(android.R.id.message);
        if (title != null) {
            title.setTextColor(accent);
            title.setTextSize(21);
        }
        if (message != null) {
            message.setTextColor(TEXT);
            message.setTextSize(16);
            message.setLineSpacing(0f, 1.16f);
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.rgb(184, 211, 255));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(accent);

        View panel = window.getDecorView();
        panel.setAlpha(0f);
        panel.setScaleX(0.92f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(dp(18));
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setDuration(240).start();
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

    private File downloadModule(ModuleItem item) throws Exception {
        File partial = new File(getCacheDir(), item.fileName + ".part");
        File target = new File(getCacheDir(), item.fileName);
        partial.delete();
        target.delete();
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(MODULE_BASE_URL + item.fileName).openConnection();
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(120000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "application/zip,application/octet-stream");
            connection.setRequestProperty("User-Agent", "wtlyf-toolbox/1.6.5 Android");
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) throw new Exception("模块下载失败（HTTP " + status + "）");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(partial)) {
                byte[] buffer = new byte[64 * 1024];
                for (int count; (count = input.read(buffer)) != -1;) {
                    output.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                    total += count;
                }
                output.getFD().sync();
            }
            StringBuilder actual = new StringBuilder(64);
            for (byte value : digest.digest()) actual.append(String.format(Locale.ROOT, "%02X", value));
            if (!item.sha256.equals(actual.toString())) throw new Exception("模块校验失败，请重试");
            if (total < 1 || !partial.renameTo(target)) throw new Exception("无法保存下载的模块");
            appendLog("下载完成：" + total + " 字节，SHA-256 校验通过\n");
            return target;
        } finally {
            if (connection != null) connection.disconnect();
            if (!target.exists()) partial.delete();
        }
    }

    private void appendLog(String text) {
        runOnUiThread(() -> {
            log.append(text);
            View parent = (View) log.getParent();
            if (parent != null) parent.post(() -> parent.scrollTo(0, log.getBottom()));
        });
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
        final String fileName;
        final String id;
        final String sha256;
        ModuleItem(String name, String version, String fileName, String id, String sha256) {
            this.name = name;
            this.version = version;
            this.fileName = fileName;
            this.id = id;
            this.sha256 = sha256;
        }
    }

    private interface PostInstallAction {
        boolean run() throws Exception;
    }
}
