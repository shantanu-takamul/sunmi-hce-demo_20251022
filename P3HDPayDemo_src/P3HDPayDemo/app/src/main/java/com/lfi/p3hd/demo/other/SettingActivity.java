package com.lfi.p3hd.demo.other;

import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.lfi.p3hd.demo.BaseAppCompatActivity;
import com.lfi.p3hd.demo.R;
import com.lfi.p3hd.demo.qr.QRConfig;
import com.lfi.p3hd.demo.utils.ApiKeyManager;
import com.lfi.p3hd.demo.utils.PreferencesUtil;

public class SettingActivity extends BaseAppCompatActivity {

    private TextView tvEnvValue;
    private TextView tvApiKeyValue;
    private TextView tvWalletIdValue;
    private TextView tvNfcWalletIdValue;
    private TextView tvNfcMerchantNameValue;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setting);
        initView();
    }

    private void initView() {
        setupHeader(R.string.setting_header_label, R.string.setting_header_title, true);
        tvEnvValue            = findViewById(R.id.tv_env_value);
        tvApiKeyValue         = findViewById(R.id.tv_api_key_value);
        tvWalletIdValue       = findViewById(R.id.tv_wallet_id_value);
        tvNfcWalletIdValue    = findViewById(R.id.tv_nfc_wallet_id_value);
        tvNfcMerchantNameValue = findViewById(R.id.tv_nfc_merchant_name_value);

        updateDisplay();

        findViewById(R.id.btn_change_env).setOnClickListener(v -> showEnvPicker());
        findViewById(R.id.btn_refresh_key).setOnClickListener(v -> fetchApiKey());
        findViewById(R.id.btn_edit_nfc_wallet).setOnClickListener(v -> showNfcWalletIdInput());
        findViewById(R.id.btn_edit_nfc_merchant).setOnClickListener(v -> showNfcMerchantNameInput());
    }

    private void updateDisplay() {
        tvEnvValue.setText(PreferencesUtil.getEnv());
        String apiKey = PreferencesUtil.getLfiApiKey();
        tvApiKeyValue.setText(apiKey.isEmpty() ? "Not set" : maskApiKey(apiKey));
        String walletId = PreferencesUtil.getWalletId();
        tvWalletIdValue.setText(walletId.isEmpty() ? QRConfig.getDefaultWalletId() + " (default)" : walletId);
        String nfcWalletId = PreferencesUtil.getNfcWalletId();
        tvNfcWalletIdValue.setText(nfcWalletId.isEmpty() ? QRConfig.getDefaultWalletId() + " (default)" : nfcWalletId);
        String nfcMerchantName = PreferencesUtil.getNfcMerchantName();
        tvNfcMerchantNameValue.setText(nfcMerchantName.isEmpty() ? "CBDC Merchant (default)" : nfcMerchantName);
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
        String envDefault = QRConfig.getDefaultWalletId(selectedEnv);
        input.setHint(envDefault);
        input.setText(envDefault);

        new AlertDialog.Builder(this)
            .setTitle(R.string.setting_wallet_id_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                String walletId = input.getText().toString().trim();
                PreferencesUtil.setEnv(selectedEnv);
                PreferencesUtil.setWalletId(walletId.isEmpty() ? envDefault : walletId);
                updateDisplay();
                fetchApiKey();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showNfcWalletIdInput() {
        EditText input = new EditText(this);
        String current = PreferencesUtil.getNfcWalletId();
        String envDefault = QRConfig.getDefaultWalletId();
        input.setHint(envDefault);
        input.setText(current.isEmpty() ? envDefault : current);

        new AlertDialog.Builder(this)
            .setTitle(R.string.setting_nfc_wallet_id_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                String value = input.getText().toString().trim();
                PreferencesUtil.setNfcWalletId(value.isEmpty() ? envDefault : value);
                updateDisplay();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showNfcMerchantNameInput() {
        EditText input = new EditText(this);
        String current = PreferencesUtil.getNfcMerchantName();
        input.setHint("CBDC Merchant");
        input.setText(current.isEmpty() ? "CBDC Merchant" : current);

        new AlertDialog.Builder(this)
            .setTitle(R.string.setting_nfc_merchant_name_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                String value = input.getText().toString().trim();
                PreferencesUtil.setNfcMerchantName(value.isEmpty() ? "CBDC Merchant" : value);
                updateDisplay();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void fetchApiKey() {
        showToast("Fetching API key...");
        ApiKeyManager.get().fetch(
            () -> {
                if (isFinishing()) return;
                updateDisplay();
                showToast("API key ready (" + PreferencesUtil.getEnv() + ")");
            },
            errorMsg -> {
                if (isFinishing()) return;
                showToast(errorMsg);
            }
        );
    }
}
