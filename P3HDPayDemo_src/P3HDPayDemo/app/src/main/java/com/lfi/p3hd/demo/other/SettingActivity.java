package com.lfi.p3hd.demo.other;

import android.app.AlertDialog;
import android.os.Bundle;
import android.util.Log;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.lfi.p3hd.demo.BaseAppCompatActivity;
import com.lfi.p3hd.demo.R;
import com.lfi.p3hd.demo.qr.QRConfig;
import com.lfi.p3hd.demo.utils.PreferencesUtil;

import org.json.JSONObject;

import java.io.IOException;
import java.util.UUID;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class SettingActivity extends BaseAppCompatActivity {
    private static final String LFI_AUTH_USERNAME = "test-global-admin";
    private static final String LFI_AUTH_PASSWORD = "12345";
    private static final String LFI_AUTH_REALM    = "cbuae";

    private TextView tvEnvValue;
    private TextView tvApiKeyValue;
    private TextView tvWalletIdValue;
    private final OkHttpClient httpClient = new OkHttpClient();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setting);
        initView();
    }

    private void initView() {
        setupHeader(R.string.setting_header_label, R.string.setting_header_title, true);
        tvEnvValue      = findViewById(R.id.tv_env_value);
        tvApiKeyValue   = findViewById(R.id.tv_api_key_value);
        tvWalletIdValue = findViewById(R.id.tv_wallet_id_value);

        updateDisplay();

        findViewById(R.id.btn_change_env).setOnClickListener(v -> showEnvPicker());
        findViewById(R.id.btn_refresh_key).setOnClickListener(v -> fetchLfiApiKey());
    }

    private void updateDisplay() {
        tvEnvValue.setText(PreferencesUtil.getEnv());
        String apiKey = PreferencesUtil.getLfiApiKey();
        tvApiKeyValue.setText(apiKey.isEmpty() ? "Not set" : maskApiKey(apiKey));
        String walletId = PreferencesUtil.getWalletId();
        tvWalletIdValue.setText(walletId.isEmpty() ? QRConfig.WALLET_ID + " (default)" : walletId);
    }

    private String maskApiKey(String key) {
        if (key.length() <= 8) return "****";
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }

    private void showEnvPicker() {
        String[] envs = {"staging", "dev", "qa", "demo", "local"};
        String currentEnv = PreferencesUtil.getEnv();
        int currentIdx = 0;
        for (int i = 0; i < envs.length; i++) {
            if (envs[i].equals(currentEnv)) { currentIdx = i; break; }
        }
        final int[] selected = {currentIdx};

        new AlertDialog.Builder(this)
            .setTitle(R.string.setting_env_title)
            .setSingleChoiceItems(envs, currentIdx, (d, which) -> selected[0] = which)
            .setPositiveButton(android.R.string.ok, (d, w) -> showWalletIdInput(envs[selected[0]]))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showWalletIdInput(String selectedEnv) {
        EditText input = new EditText(this);
        String current = PreferencesUtil.getWalletId();
        input.setHint(QRConfig.WALLET_ID);
        input.setText(current.isEmpty() ? QRConfig.WALLET_ID : current);

        new AlertDialog.Builder(this)
            .setTitle(R.string.setting_wallet_id_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                String walletId = input.getText().toString().trim();
                PreferencesUtil.setEnv(selectedEnv);
                PreferencesUtil.setWalletId(walletId.isEmpty() ? QRConfig.WALLET_ID : walletId);
                PreferencesUtil.setLfiApiKey("");
                updateDisplay();
                fetchLfiApiKey();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void fetchLfiApiKey() {
        showToast("Fetching API key...");
        try {
            JSONObject loginBody = new JSONObject();
            loginBody.put("username", LFI_AUTH_USERNAME);
            loginBody.put("password", LFI_AUTH_PASSWORD);
            loginBody.put("realm", LFI_AUTH_REALM);

            String loginUrl = QRConfig.getBaseUrl() + QRConfig.AUTH_ENDPOINT;
            Request loginReq = new Request.Builder()
                .url(loginUrl)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(MediaType.parse("application/json"), loginBody.toString()))
                .build();

            httpClient.newCall(loginReq).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    showToast("Auth failed: " + e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String body = response.body().string();
                    Log.d(TAG, "Auth response: " + body);
                    if (!response.isSuccessful()) {
                        showToast("Auth failed (HTTP " + response.code() + ")");
                        return;
                    }
                    try {
                        String accessToken = new JSONObject(body).getString("access_token");
                        regenApiKey(accessToken);
                    } catch (Exception e) {
                        showToast("Auth parse error");
                    }
                }
            });
        } catch (Exception e) {
            showToast("fetchLfiApiKey error: " + e.getMessage());
        }
    }

    private void regenApiKey(String accessToken) {
        try {
            JSONObject regenBody = new JSONObject();
            regenBody.put("keyType", "PRIMARY");
            regenBody.put("expiryDays", 90);

            String regenUrl = QRConfig.getBaseUrl() + QRConfig.REGEN_ENDPOINT;
            Request regenReq = new Request.Builder()
                .url(regenUrl)
                .addHeader("Authorization", "Bearer " + accessToken)
                .addHeader("X-LFI-ID", QRConfig.X_LFI_ID)
                .addHeader("X-Idempotency-Key", UUID.randomUUID().toString())
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(MediaType.parse("application/json"), regenBody.toString()))
                .build();

            httpClient.newCall(regenReq).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    showToast("Key regen failed: " + e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String body = response.body().string();
                    Log.d(TAG, "Regen response: " + body);
                    if (!response.isSuccessful()) {
                        showToast("Key regen failed (HTTP " + response.code() + ")");
                        return;
                    }
                    try {
                        JSONObject json = new JSONObject(body);
                        String apiKey   = json.getString("apiKey");
                        String expiresAt = json.optString("expiresAt", "");
                        PreferencesUtil.setLfiApiKey(apiKey);
                        PreferencesUtil.setLfiApiKeyExpiry(expiresAt);
                        runOnUiThread(() -> {
                            updateDisplay();
                            showToast("API key ready (" + PreferencesUtil.getEnv() + ")");
                        });
                    } catch (Exception e) {
                        showToast("Key regen parse error");
                    }
                }
            });
        } catch (Exception e) {
            showToast("regenApiKey error: " + e.getMessage());
        }
    }
}
