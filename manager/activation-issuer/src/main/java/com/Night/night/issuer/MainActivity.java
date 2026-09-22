package com.Night.night.issuer;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Base64;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Locale;

public final class MainActivity extends Activity {
    private EditText androidId;
    private TextView activationCode;
    private TextView keyStatus;
    private byte[] privateKeyDer;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        float density = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding((int)(24*density), (int)(48*density), (int)(24*density), (int)(24*density));
        root.setBackgroundColor(Color.rgb(7, 11, 20));

        TextView title = text("NIGHT / ACTIVATION ISSUER", 24, Color.rgb(153, 211, 255));
        root.addView(title);
        root.addView(text("输入目标设备 Android ID，离线签发专属激活码", 14, Color.rgb(160, 174, 200)));
        androidId = new EditText(this);
        androidId.setHint("Android ID");
        androidId.setTextColor(Color.WHITE);
        androidId.setHintTextColor(Color.GRAY);
        root.addView(androidId, wide());
        keyStatus = text("正在载入内置 Night 私钥…", 13, Color.rgb(160, 174, 200));
        root.addView(keyStatus, wide());
        Button issue = new Button(this);
        issue.setText("签发激活码");
        issue.setOnClickListener(v -> issue());
        root.addView(issue, wide());
        activationCode = text("", 13, Color.rgb(216, 255, 225));
        activationCode.setTextIsSelectable(true);
        activationCode.setPadding(0, (int)(18*density), 0, (int)(18*density));
        root.addView(activationCode, wide());
        Button copy = new Button(this);
        copy.setText("复制激活码");
        copy.setOnClickListener(v -> {
            if (activationCode.getText().length() == 0) return;
            getSystemService(ClipboardManager.class).setPrimaryClip(
                ClipData.newPlainText("Night activation code", activationCode.getText()));
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
        });
        root.addView(copy, wide());
        setContentView(root);
        loadBundledPrivateKey();
    }

    private void issue() {
        try {
            String id = androidId.getText().toString().trim().toLowerCase(Locale.ROOT);
            if (id.isEmpty()) throw new IllegalArgumentException("Android ID 不能为空");
            if (privateKeyDer == null) throw new IllegalStateException("内置激活私钥不可用");
            PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(privateKeyDer));
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(key);
            signer.update(("Night|1|" + id).getBytes(StandardCharsets.UTF_8));
            activationCode.setText("N1." + Base64.encodeToString(signer.sign(), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING));
        } catch (Throwable error) {
            activationCode.setText("签发失败：" + error.getMessage());
        }
    }

    private void loadBundledPrivateKey() {
        try {
            Throwable firstError = null;
            for (String asset : new String[] {
                "night_activation_private_key.pk8",
                "night_activation_private_key.pem"
            }) {
                try {
                    byte[] raw = readAsset(asset);
                    privateKeyDer = normalizePrivateKey(raw);
                    keyStatus.setText("内置 Night 私钥已载入（PEM / PK8）");
                    return;
                } catch (Throwable error) {
                    if (firstError == null) firstError = error;
                }
            }
            throw new IllegalStateException(firstError == null ? "密钥资源不存在" : firstError.getMessage());
        } catch (Throwable error) {
            privateKeyDer = null;
            keyStatus.setText("内置私钥载入失败：" + error.getMessage());
        }
    }

    private byte[] readAsset(String name) throws Exception {
        try (java.io.InputStream input = getAssets().open(name);
             java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) >= 0;) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private byte[] normalizePrivateKey(byte[] raw) throws Exception {
        String text = new String(raw, StandardCharsets.US_ASCII);
        if (text.contains("BEGIN PRIVATE KEY")) {
            text = text.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
            raw = Base64.decode(text, Base64.DEFAULT);
        }
        KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(raw));
        return raw;
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(sp); view.setTextColor(color); view.setPadding(0, 8, 0, 8);
        return view;
    }

    private ViewGroup.LayoutParams wide() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
