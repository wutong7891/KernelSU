package com.Night.night.issuer;

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
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Locale;

/**
 * Offline Night licence issuer.
 *
 * Licences are ECDSA P-256 tokens signed here and verified inside the Manager.
 * The private key is bundled as a PKCS#8 asset by CI and never leaves the
 * issuer; the Manager ships the public half only. Everything happens offline:
 * no account, no server, no network permission needed.
 *
 * Token layout must stay byte-identical to NightActivation.kt.
 */
public final class MainActivity extends Activity {
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final byte VERSION = 0x02;
    private static final byte FLAG_DEVICE_BOUND = 0x01;
    private static final byte FLAG_PERMANENT = 0x02;
    private static final int MAX_COUNT = 100;

    private final SecureRandom random = new SecureRandom();

    private EditText androidId;
    private EditText days;
    private EditText count;
    private TextView keyStatus;
    private TextView output;
    private PrivateKey privateKey;
    private String publicKeyFingerprint = "unknown";

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        float density = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(pad(density, 24), pad(density, 40), pad(density, 24), pad(density, 24));
        root.setBackgroundColor(Color.rgb(7, 11, 20));

        root.addView(text("NIGHT / OFFLINE ISSUER", 22, Color.rgb(153, 211, 255)));
        root.addView(text("离线签发 ECDSA P-256 卡密，不联网", 13, Color.rgb(160, 174, 200)));

        root.addView(label("目标设备指纹（留空则签发通用卡）", density));
        androidId = new EditText(this);
        androidId.setHint("设备指纹，例如 3f9a2b…；留空=不绑定设备");
        androidId.setTextColor(Color.WHITE);
        androidId.setHintTextColor(Color.GRAY);
        androidId.setInputType(InputType.TYPE_CLASS_TEXT);
        root.addView(androidId, wide());

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

        keyStatus = text("正在载入内置 Night 签发私钥…", 12, Color.rgb(160, 174, 200));
        keyStatus.setTextIsSelectable(true);
        root.addView(keyStatus, wide());

        Button issue = new Button(this);
        issue.setText("签发卡密");
        issue.setOnClickListener(v -> issue());
        root.addView(issue, wide());

        output = text("", 12, Color.rgb(216, 255, 225));
        output.setTextIsSelectable(true);
        output.setMovementMethod(new ScrollingMovementMethod());
        output.setPadding(0, pad(density, 12), 0, pad(density, 12));
        root.addView(output, wide());

        Button copy = new Button(this);
        copy.setText("复制全部结果");
        copy.setOnClickListener(v -> {
            if (output.getText().length() == 0) return;
            getSystemService(ClipboardManager.class)
                .setPrimaryClip(ClipData.newPlainText("Night licence", output.getText()));
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
        });
        root.addView(copy, wide());

        setContentView(rememberScroll(root, density));
        loadBundledPrivateKey();
    }

    private void issue() {
        try {
            if (privateKey == null) throw new IllegalStateException("内置签发私钥不可用：" + keyStatus.getText());
            int requestCount = parseCount(count.getText().toString());
            long validDays = parseDays(days.getText().toString());
            String fingerprint = normalizeFingerprint(androidId.getText().toString());

            boolean bound = !fingerprint.isEmpty();
            byte deviceTag[] = new byte[3];
            if (bound) {
                byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(("night-device-v1:" + fingerprint).getBytes(StandardCharsets.UTF_8));
                System.arraycopy(hash, 0, deviceTag, 0, 3);
            }

            long notAfter = validDays == 0
                ? 0L
                : (System.currentTimeMillis() / 1000L) + validDays * 86400L;

            StringBuilder result = new StringBuilder();
            for (int i = 0; i < requestCount; i++) {
                if (i > 0) result.append('\n');
                result.append(issueOne(deviceTag, bound, notAfter));
            }
            output.setText(result);
            String scope = bound ? "设备专属（" + hex(deviceTag) + "）" : "通用（不绑定设备）";
            String expiry = validDays == 0 ? "永久有效" : validDays + " 天";
            keyStatus.setText("签发成功 · " + scope + " · " + expiry + " · 公钥指纹 " + publicKeyFingerprint);
        } catch (Throwable error) {
            output.setText("");
            keyStatus.setText("签发失败：" + describe(error));
        }
    }

    private String issueOne(byte[] deviceTag, boolean bound, long notAfter) throws Exception {
        byte flags = bound ? FLAG_DEVICE_BOUND : 0;
        if (notAfter == 0L) flags |= FLAG_PERMANENT;

        int serial = random.nextInt(0xFFFEFF) + 1;
        byte[] payload = new byte[12];
        payload[0] = VERSION;
        payload[1] = flags;
        payload[2] = (byte) ((serial >>> 16) & 0xFF);
        payload[3] = (byte) ((serial >>> 8) & 0xFF);
        payload[4] = (byte) (serial & 0xFF);
        payload[5] = (byte) ((notAfter >>> 24) & 0xFF);
        payload[6] = (byte) ((notAfter >>> 16) & 0xFF);
        payload[7] = (byte) ((notAfter >>> 8) & 0xFF);
        payload[8] = (byte) (notAfter & 0xFF);
        payload[9] = deviceTag[0];
        payload[10] = deviceTag[1];
        payload[11] = deviceTag[2];

        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(privateKey);
        signer.update(payload);
        byte[] rawSignature = derToRs(signer.sign());

        byte[] token = new byte[payload.length + rawSignature.length];
        System.arraycopy(payload, 0, token, 0, payload.length);
        System.arraycopy(rawSignature, 0, token, payload.length, rawSignature.length);
        return group(encodeBase32(token));
    }

    private void loadBundledPrivateKey() {
        publicKeyFingerprint = loadPublicKeyFingerprint();
        try {
            byte[] raw = readAsset("night_license_private_key.pk8");
            byte[] der = normalizePrivateKey(raw);
            privateKey = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
            keyStatus.setText("签发私钥已载入 · 公钥指纹 " + publicKeyFingerprint);
        } catch (Throwable error) {
            privateKey = null;
            keyStatus.setText("私钥载入失败：" + describe(error));
        }
    }

    /**
     * Reads the matching public key so the issuer can show the same short
     * fingerprint the Manager displays; equal fingerprints prove the two were
     * built from the same key pair.
     */
    private String loadPublicKeyFingerprint() {
        try {
            byte[] raw = readAsset("night_license_public_key.der");
            byte[] der = raw.length > 0 && (raw[0] & 0xFF) == 0x30 ? raw : Base64.decode(raw, Base64.DEFAULT);
            return fingerprintOf(der);
        } catch (Throwable error) {
            return "unknown";
        }
    }

    private static String fingerprintOf(byte[] publicKeyDer) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(publicKeyDer);
        return hex(digest).substring(0, 16).toUpperCase(Locale.ROOT);
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

    /** Accepts either a raw PKCS#8 DER file or a PEM/base64 text file. */
    private byte[] normalizePrivateKey(byte[] raw) {
        String text = new String(raw, StandardCharsets.US_ASCII);
        if (text.contains("BEGIN PRIVATE KEY")) {
            text = text.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
            return Base64.decode(text, Base64.DEFAULT);
        }
        if (!text.contains("\u0000") && text.trim().matches("^[A-Za-z0-9+/=\\s]+$")) {
            return Base64.decode(text.trim(), Base64.DEFAULT);
        }
        return raw;
    }

    private int parseCount(String value) {
        int parsed = Integer.parseInt(value.trim().isEmpty() ? "1" : value.trim());
        if (parsed < 1 || parsed > MAX_COUNT) throw new IllegalArgumentException("数量必须在 1 到 " + MAX_COUNT + " 之间");
        return parsed;
    }

    private long parseDays(String value) {
        long parsed = Long.parseLong(value.trim().isEmpty() ? "0" : value.trim());
        if (parsed < 0 || parsed > 36500) throw new IllegalArgumentException("有效天数必须在 0 到 36500 之间");
        return parsed;
    }

    private String normalizeFingerprint(String value) {
        return value.trim().replaceAll("[^0-9a-fA-F]", "").toLowerCase(Locale.ROOT);
    }

    private static String encodeBase32(byte[] data) {
        StringBuilder builder = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte value : data) {
            buffer = (buffer << 8) | (value & 0xFF);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                builder.append(ALPHABET.charAt((buffer >> bits) & 0x1F));
            }
        }
        if (bits > 0) builder.append(ALPHABET.charAt((buffer << (5 - bits)) & 0x1F));
        return builder.toString();
    }

    private static String group(String body) {
        StringBuilder builder = new StringBuilder("NIGHT-");
        for (int i = 0; i < body.length(); i++) {
            if (i > 0 && i % 5 == 0) builder.append('-');
            builder.append(body.charAt(i));
        }
        return builder.toString();
    }

    /** Converts a DER ECDSA signature into the fixed-width r || s form used by the token. */
    private static byte[] derToRs(byte[] der) {
        int[] cursor = {0};
        expectTag(der, cursor, 0x30);
        int sequenceLength = readLength(der, cursor);
        int end = cursor[0] + sequenceLength;
        byte[] r = readInteger(der, cursor);
        byte[] s = readInteger(der, cursor);
        if (cursor[0] != end) throw new IllegalArgumentException("DER 签名长度不一致");
        byte[] out = new byte[64];
        System.arraycopy(fitScalar(r), 0, out, 0, 32);
        System.arraycopy(fitScalar(s), 0, out, 32, 32);
        return out;
    }

    private static byte[] fitScalar(byte[] value) {
        byte[] out = new byte[32];
        int start = 0;
        while (start < value.length - 1 && value[start] == 0) start++;
        int length = Math.min(32, value.length - start);
        System.arraycopy(value, value.length - length, out, 32 - length, length);
        return out;
    }

    private static void expectTag(byte[] der, int[] cursor, int tag) {
        if ((der[cursor[0]++] & 0xFF) != tag) throw new IllegalArgumentException("DER 标签错误");
    }

    private static int readLength(byte[] der, int[] cursor) {
        int first = der[cursor[0]++] & 0xFF;
        if ((first & 0x80) == 0) return first;
        int bytes = first & 0x7F;
        int value = 0;
        for (int i = 0; i < bytes; i++) value = (value << 8) | (der[cursor[0]++] & 0xFF);
        return value;
    }

    private static byte[] readInteger(byte[] der, int[] cursor) {
        expectTag(der, cursor, 0x02);
        int length = readLength(der, cursor);
        byte[] value = new byte[length];
        System.arraycopy(der, cursor[0], value, 0, length);
        cursor[0] += length;
        return value;
    }

    private static String hex(byte[] data) {
        StringBuilder builder = new StringBuilder(data.length * 2);
        for (byte value : data) builder.append(String.format(Locale.ROOT, "%02x", value));
        return builder.toString();
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
        TextView view = text(value, 13, Color.rgb(143, 174, 207));
        view.setPadding(0, pad(density, 14), 0, 0);
        return view;
    }

    private ViewGroup.LayoutParams wide() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private ViewGroup rememberScroll(LinearLayout root, float density) {
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(7, 11, 20));
        scroll.setPadding(0, 0, 0, pad(density, 24));
        scroll.addView(root);
        return scroll;
    }
}
