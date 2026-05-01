package com.lfi.p3hd.demo.qr;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.lfi.p3hd.demo.BaseAppCompatActivity;
import com.lfi.p3hd.demo.R;
import com.lfi.p3hd.demo.nfc.NFCPayActivity;
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

public class QRPayActivity extends BaseAppCompatActivity {
    private LinearLayout layoutInput;
    private LinearLayout layoutLoading;
    private TextView tvAmountInput;

    private final StringBuilder amountBuilder = new StringBuilder("0");
    private String lastRequestId;
    private final OkHttpClient httpClient = new OkHttpClient();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr_pay);
        initView();
    }

    private void initView() {
        initActionbar(R.string.qr_pay);
        layoutInput = findViewById(R.id.layout_input);
        layoutLoading = findViewById(R.id.layout_loading);
        tvAmountInput = findViewById(R.id.tv_amount_input);

        int[] digitIds = {
            R.id.btn_1, R.id.btn_2, R.id.btn_3,
            R.id.btn_4, R.id.btn_5, R.id.btn_6,
            R.id.btn_7, R.id.btn_8, R.id.btn_9,
            R.id.btn_0
        };
        for (int id : digitIds) {
            TextView btn = findViewById(id);
            btn.setOnClickListener(v -> appendDigit(((TextView) v).getText().toString()));
        }
        findViewById(R.id.btn_dot).setOnClickListener(v -> appendDot());
        findViewById(R.id.btn_backspace).setOnClickListener(v -> backspace());
        findViewById(R.id.btn_generate).setOnClickListener(v -> onGenerateClicked());
        findViewById(R.id.btn_nfc_pay).setOnClickListener(v -> onNfcPayClicked());
    }

    @Override
    protected void onResume() {
        super.onResume();
        showInputState();
    }

    private void showInputState() {
        layoutInput.setVisibility(View.VISIBLE);
        layoutLoading.setVisibility(View.GONE);
    }

    private void showLoadingState() {
        layoutInput.setVisibility(View.GONE);
        layoutLoading.setVisibility(View.VISIBLE);
    }

    private void appendDigit(String digit) {
        int dotIdx = amountBuilder.indexOf(".");
        if (dotIdx >= 0 && (amountBuilder.length() - dotIdx) > 2) return;
        if (amountBuilder.toString().equals("0") && !digit.equals(".")) {
            amountBuilder.setLength(0);
        }
        amountBuilder.append(digit);
        tvAmountInput.setText(amountBuilder.toString());
    }

    private void appendDot() {
        if (amountBuilder.indexOf(".") >= 0) return;
        amountBuilder.append(".");
        tvAmountInput.setText(amountBuilder.toString());
    }

    private void backspace() {
        if (amountBuilder.length() > 1) {
            amountBuilder.deleteCharAt(amountBuilder.length() - 1);
        } else {
            amountBuilder.replace(0, amountBuilder.length(), "0");
        }
        tvAmountInput.setText(amountBuilder.toString());
    }

    private void onGenerateClicked() {
        try {
            String amountStr = amountBuilder.toString();
            if (amountStr.endsWith(".")) amountStr = amountStr.substring(0, amountStr.length() - 1);
            double amountAed = Double.parseDouble(amountStr);
            if (amountAed <= 0) throw new NumberFormatException("zero");
            long amountFils = Math.round(amountAed * 100);
            String requestId = UUID.randomUUID().toString();
            lastRequestId = requestId;
            showLoadingState();
            generateQR(amountFils, amountStr, requestId);
        } catch (NumberFormatException e) {
            showToast(R.string.qr_pay_amount_error);
        }
    }

    private void onNfcPayClicked() {
        try {
            String amountStr = amountBuilder.toString();
            if (amountStr.endsWith(".")) amountStr = amountStr.substring(0, amountStr.length() - 1);
            double amountAed = Double.parseDouble(amountStr);
            if (amountAed <= 0) throw new NumberFormatException("zero");
            // NFC pay writes wallet info directly to HCE — no API call needed.
            // The iOS ddwallet app reads walletId + amount from the tag and
            // initiates the P2M transfer entirely on the phone side.
            Intent intent = new Intent(this, NFCPayActivity.class);
            intent.putExtra(NFCPayActivity.EXTRA_AMOUNT_AED, amountStr);
            startActivity(intent);
        } catch (NumberFormatException e) {
            showToast(R.string.qr_pay_amount_error);
        }
    }

    private void generateQR(long amountFils, String amountDisplayAed, String requestId) {
        try {
            JSONObject body = new JSONObject();
            String walletId = PreferencesUtil.getWalletId();
            body.put("qrType", QRConfig.QR_TYPE);
            body.put("walletId", walletId.isEmpty() ? QRConfig.WALLET_ID : walletId);
            body.put("amount", amountFils);
            body.put("currency", QRConfig.CURRENCY);
            body.put("terminalId", QRConfig.TERMINAL_ID);
            body.put("tradingLicenseNumber", QRConfig.TRADING_LICENSE_NUMBER);
            body.put("merchantCategoryCode", QRConfig.MERCHANT_CATEGORY_CODE);

            String url = QRConfig.getBaseUrl() + QRConfig.QR_ENDPOINT + "?requestId=" + requestId;
            Request.Builder reqBuilder = new Request.Builder()
                .url(url)
                .addHeader("X-LFI-ID", QRConfig.X_LFI_ID)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(MediaType.parse("application/json"), body.toString()));

            String apiKey = PreferencesUtil.getLfiApiKey();
            if (!apiKey.isEmpty()) {
                reqBuilder.addHeader("X-LFI-API-KEY", apiKey);
            }

            httpClient.newCall(reqBuilder.build()).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    runOnUiThread(() -> {
                        showInputState();
                        showToast(getString(R.string.qr_pay_error_network) + ": " + e.getMessage());
                    });
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String responseBody = response.body().string();
                    Log.d(TAG, "[" + response.code() + "] " + responseBody);
                    if (!response.isSuccessful()) {
                        runOnUiThread(() -> {
                            showInputState();
                            showToast(getString(R.string.qr_pay_error_network) + " (HTTP " + response.code() + ")");
                        });
                        return;
                    }
                    try {
                        JSONObject json = new JSONObject(responseBody);
                        String emvPayload = json.getString("emvPayload");
                        runOnUiThread(() -> {
                            Intent intent = new Intent(QRPayActivity.this, QRDisplayActivity.class);
                            intent.putExtra(QRDisplayActivity.EXTRA_EMV_PAYLOAD, emvPayload);
                            intent.putExtra(QRDisplayActivity.EXTRA_AMOUNT_AED, amountDisplayAed);
                            intent.putExtra(QRDisplayActivity.EXTRA_REQUEST_ID, requestId);
                            startActivity(intent);
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> {
                            showInputState();
                            showToast(getString(R.string.qr_pay_error_parse));
                        });
                    }
                }
            });
        } catch (Exception e) {
            runOnUiThread(() -> {
                showInputState();
                showToast(getString(R.string.qr_pay_error_network));
            });
        }
    }
}
