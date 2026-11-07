package com.HxovoSU.yydsHx.issuer;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.util.Base64;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * XhovoSU 的 14 位卡密签发器。
 *
 * 卡密 = 8 字符载荷（流水号 16 + 到期日 12 + 设备标签 12）+ 6 字符校验
 * （前 40 bit 的 HMAC-SHA256 截断）。整个签发过程离线完成，不联网。
 *
 * 布局必须与 CardKeyApi.kt 保持一致。
 */
public final class MainActivity extends Activity {
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final int CODE_LENGTH = 14;
    private static final int PAYLOAD_CHARS = 8;
    private static final int CHECK_CHARS = 6;
    private static final long DAY_EPOCH = 1704067200L;
    private static final int MAX_COUNT = 100;

    private EditText fingerprintInput;
    private EditText days;
    private EditText count;
    private TextView keyStatus;
    private TextView output;
    private byte[] secret;
    private String keyFingerprint = "unknown";

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        float density = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(pad(density, 24), pad(density, 40), pad(density, 24), pad(density, 24));
        root.setBackgroundColor(Color.rgb(7, 7, 10));

        root.addView(text("XHOVOSU / CARD KEY ISSUER", 20, Color.rgb(232, 236, 245)));
        root.addView(text("离线签发 14 位卡密，不联网", 13, Color.rgb(150, 156, 169)));

        root.addView(label("目标设备指纹（留空则签发通用卡）", density));
        fingerprintInput = new EditText(this);
        fingerprintInput.setHint("粘贴卡密页复制的 64 位指纹；留空=不绑定");
        fingerprintInput.setTextColor(Color.WHITE);
        fingerprintInput.setHintTextColor(Color.GRAY);
        fingerprintInput.setInputType(InputType.TYPE_CLASS_TEXT);
        root.addView(fingerprintInput, wide());

        root.addView(label("有效天数（0 = 永久）", density));
        days = new EditText(this);
        days.setHint("30");
        days.setText("30");
        days.setTextColor(Color.WHITE);
        days.setHintTextColor(Color.GRAY);
        days.setInputType(InputType.TYPE_CLASS_NUMBER);
        root.addView(days, wide());

        root.addView(label("签发数量（1-" + MAX_COUNT + "）", density));
        count = new EditText(this);
        count.setHint("1");
        count.setText("1");
        count.setTextColor(Color.WHITE);
        count.setHintTextColor(Color.GRAY);
        count.setInputType(InputType.TYPE_CLASS_NUMBER);
        root.addView(count, wide());

        keyStatus = text("正在载入内置卡密密钥…", 12, Color.rgb(150, 156, 169));
        keyStatus.setTextIsSelectable(true);
        root.addView(keyStatus, wide());

        Button issue = new Button(this);
        issue.setText("签发卡密");
        issue.setOnClickListener(v -> issue());
        root.addView(issue, wide());

        output = text("", 16, Color.rgb(232, 236, 245));
        output.setTextIsSelectable(true);
        output.setMovementMethod(new ScrollingMovementMethod());
        output.setPadding(0, pad(density, 12), 0, pad(density, 12));
        root.addView(output, wide());

        Button copy = new Button(this);
        copy.setText("复制全部结果");
        copy.setOnClickListener(v -> {
            if (output.getText().length() == 0) return;
            getSystemService(ClipboardManager.class)
                .setPrimaryClip(ClipData.newPlainText("XhovoSU card key", output.getText()));
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
        });
        root.addView(copy, wide());

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(7, 7, 10));
        scroll.addView(root);
        setContentView(scroll);

        loadBundledSecret();
    }

    private void issue() {
        try {
            if (secret == null) throw new IllegalStateException("内置卡密密钥不可用");
            int requestCount = parseCount(count.getText().toString());
            long validDays = parseDays(days.getText().toString());
            String fingerprint = normalizeFingerprint(fingerprintInput.getText().toString());
            boolean bound = !fingerprint.isEmpty();
            int tag = 0;
            if (bound) {
                if (fingerprint.length() < 3) throw new IllegalArgumentException("设备指纹至少需要 3 位十六进制字符");
                tag = Integer.parseInt(fingerprint.substring(0, 3), 16) & 0xFFF;
            }

            int dayCode = validDays == 0 ? 0 : dayCodeFor(validDays);
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < requestCount; i++) {
                if (i > 0) result.append('\n');
                result.append(issueOne(randomSerial(), dayCode, tag));
            }
            output.setText(result);
            String scope = bound ? "设备专属（" + String.format(Locale.ROOT, "%03x", tag) + "）" : "通用（不绑定设备）";
            String expiry = validDays == 0 ? "永久有效" : validDays + " 天";
            keyStatus.setText("签发成功 · " + scope + " · " + expiry + " · 密钥指纹 " + keyFingerprint);
        } catch (Throwable error) {
            output.setText("");
            keyStatus.setText("签发失败：" + describe(error));
        }
    }

    private String issueOne(int serial, int dayCode, int tag) throws Exception {
        long payload = ((serial & 0xFFFFL) << 24) | ((dayCode & 0xFFFL) << 12) | (tag & 0xFFFL);
        long checksum = mac(payload);
        String body = encode(payload, 40) + encode(checksum, 30);
        return body.substring(0, 4) + "-" + body.substring(4, 8) + "-"
            + body.substring(8, 12) + "-" + body.substring(12, CODE_LENGTH);
    }

    private long mac(long payload) throws Exception {
        byte[] input = new byte[5];
        input[0] = (byte) ((payload >>> 32) & 0xFF);
        input[1] = (byte) ((payload >>> 24) & 0xFF);
        input[2] = (byte) ((payload >>> 16) & 0xFF);
        input[3] = (byte) ((payload >>> 8) & 0xFF);
        input[4] = (byte) (payload & 0xFF);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        byte[] digest = mac.doFinal(input);
        int head = ((digest[0] & 0xFF) << 24) | ((digest[1] & 0xFF) << 16)
            | ((digest[2] & 0xFF) << 8) | (digest[3] & 0xFF);
        return (head >>> 2) & 0x3FFFFFFFL;
    }

    private static String encode(long value, int bits) {
        int chars = bits / 5;
        StringBuilder builder = new StringBuilder(chars);
        long remaining = value;
        for (int i = chars - 1; i >= 0; i--) {
            long weight = pow32(i);
            builder.append(ALPHABET.charAt((int) ((remaining / weight) % 32)));
            remaining %= weight;
        }
        return builder.toString();
    }

    private static long pow32(int exponent) {
        long value = 1;
        for (int i = 0; i < exponent; i++) value *= 32;
        return value;
    }

    private int dayCodeFor(long validDays) {
        long notAfter = (System.currentTimeMillis() / 1000L) + validDays * 86400L;
        long code = (notAfter - DAY_EPOCH + 86399L) / 86400L;
        if (code < 1 || code > 0xFFF) throw new IllegalArgumentException("有效期超出 12 位天数范围（最长约 11 年）");
        return (int) code;
    }

    private int randomSerial() {
        int value = new java.security.SecureRandom().nextInt(0xFFFF);
        return value == 0 ? 1 : value;
    }

    private void loadBundledSecret() {
        try {
            byte[] raw = readAsset("xhovo_cardkey_secret.bin");
            byte[] candidate = raw.length >= 16 ? raw : decodeSecretText(raw);
            if (candidate.length < 16) throw new IllegalStateException("密钥长度不足");
            secret = candidate;
            keyFingerprint = fingerprintOf(candidate);
            keyStatus.setText("卡密密钥已载入 · 指纹 " + keyFingerprint);
        } catch (Throwable error) {
            secret = null;
            keyStatus.setText("密钥载入失败：" + describe(error));
        }
    }

    private byte[] decodeSecretText(byte[] raw) {
        String text = new String(raw, StandardCharsets.US_ASCII).trim();
        return Base64.decode(text, Base64.DEFAULT);
    }

    private String fingerprintOf(byte[] candidate) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(candidate);
        StringBuilder builder = new StringBuilder();
        for (byte value : digest) builder.append(String.format(Locale.ROOT, "%02x", value & 0xFF));
        return builder.substring(0, 16).toUpperCase(Locale.ROOT);
    }

    private byte[] readAsset(String name) throws Exception {
        try (java.io.InputStream input = getAssets().open(name);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) >= 0;) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private int parseCount(String value) {
        int parsed = Integer.parseInt(value.trim().isEmpty() ? "1" : value.trim());
        if (parsed < 1 || parsed > MAX_COUNT) throw new IllegalArgumentException("数量必须在 1 到 " + MAX_COUNT + " 之间");
        return parsed;
    }

    private long parseDays(String value) {
        long parsed = Long.parseLong(value.trim().isEmpty() ? "0" : value.trim());
        if (parsed < 0 || parsed > 4000) throw new IllegalArgumentException("有效天数必须在 0 到 4000 之间");
        return parsed;
    }

    private String normalizeFingerprint(String value) {
        return value.trim().replaceAll("[^0-9a-fA-F]", "").toLowerCase(Locale.ROOT);
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message;
    }

    private int pad(float density, int dp) {
        return (int) (density * dp);
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setPadding(0, pad(getResources().getDisplayMetrics().density, 8), 0,
            pad(getResources().getDisplayMetrics().density, 8));
        return view;
    }

    private TextView label(String value, float density) {
        TextView view = text(value, 13, Color.rgb(150, 156, 169));
        view.setPadding(0, pad(density, 14), 0, 0);
        return view;
    }

    private ViewGroup.LayoutParams wide() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
