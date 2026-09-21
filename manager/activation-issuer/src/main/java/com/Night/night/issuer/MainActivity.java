package com.Night.night.issuer;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
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
        Button selectKey = new Button(this);
        selectKey.setText("选择 Night 激活私钥（PEM / PK8）");
        selectKey.setOnClickListener(v -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 100));
        root.addView(selectKey, wide());
        keyStatus = text("尚未选择私钥", 13, Color.rgb(160, 174, 200));
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
    }

    private void issue() {
        try {
            String id = androidId.getText().toString().trim().toLowerCase(Locale.ROOT);
            if (id.isEmpty()) throw new IllegalArgumentException("Android ID 不能为空");
            if (privateKeyDer == null) throw new IllegalStateException("请先选择激活私钥");
            PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(privateKeyDer));
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(key);
            signer.update(("Night|1|" + id).getBytes(StandardCharsets.UTF_8));
            activationCode.setText("N1." + Base64.encodeToString(signer.sign(), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING));
        } catch (Throwable error) {
            activationCode.setText("签发失败：" + error.getMessage());
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != 100 || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            byte[] raw;
            try (java.io.InputStream input = getContentResolver().openInputStream(uri);
                 java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                for (int read; (read = input.read(buffer)) >= 0;) output.write(buffer, 0, read);
                raw = output.toByteArray();
            }
            String text = new String(raw, StandardCharsets.US_ASCII);
            if (text.contains("BEGIN PRIVATE KEY")) {
                text = text.replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
                raw = Base64.decode(text, Base64.DEFAULT);
            }
            KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(raw));
            privateKeyDer = raw;
            keyStatus.setText("私钥已载入（仅保存在本次运行内存中）");
        } catch (Throwable error) {
            privateKeyDer = null;
            keyStatus.setText("私钥读取失败：" + error.getMessage());
        }
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
