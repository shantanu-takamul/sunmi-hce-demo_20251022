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
import com.lfi.p3hd.demo.net.HttpClients;
import com.lfi.p3hd.demo.nfc.NFCPayActivity;
import com.lfi.p3hd.demo.utils.ApiKeyManager;
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
    private TextView tvLoadingStatus;

    private final StringBuilder amountBuilder = new StringBuilder("0");
    private String lastRequestId;
    /**
     * The shared client for the active environment.
     *
     * Resolved per use rather than held in a field: a field is frozen at
     * construction, so an environment switch never reaches it.
     */
    private OkHttpClient httpClient() {
        return HttpClients.forCurrentEnv();
    }

    /**
     * True from the moment a sale is confirmed until the terminal is back at the
     * keypad.
     *
     * Every tap of Generate mints a NEW requestId, so the gateway's duplicate
     * rule — which only rejects a *reused* requestId — cannot protect against a
     * double-tap. Two taps would produce two independently payable QR codes for
     * one sale. This flag is the only thing preventing that, so it must be
     * cleared exactly where the operator regains the keypad: showInputState().
     */
    private boolean saleInFlight;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr_pay);
        initView();
    }

    private void initView() {
        boolean nfcMode = "nfc".equals(getIntent().getStringExtra("mode"));
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        layoutInput = findViewById(R.id.layout_input);
        layoutLoading = findViewById(R.id.layout_loading);
        tvAmountInput = findViewById(R.id.tv_amount_input);
        tvLoadingStatus = findViewById(R.id.tv_loading_status);

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

        View btnGenerate = findViewById(R.id.btn_generate);
        View btnNfcPay = findViewById(R.id.btn_nfc_pay);
        if (nfcMode) {
            btnGenerate.setVisibility(View.GONE);
            btnNfcPay.setOnClickListener(v -> onNfcPayClicked());
        } else {
            btnNfcPay.setVisibility(View.GONE);
            btnGenerate.setOnClickListener(v -> onGenerateClicked());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Returning here always means a new sale — whether the last QR was paid,
        // cancelled or expired. Carrying the previous amount over would leave the
        // operator looking at stale data from a finished transaction.
        resetAmount();
        showInputState();
    }

    private void showInputState() {
        saleInFlight = false;
        layoutInput.setVisibility(View.VISIBLE);
        layoutLoading.setVisibility(View.GONE);
    }

    private void resetAmount() {
        amountBuilder.replace(0, amountBuilder.length(), "0");
        tvAmountInput.setText(amountBuilder.toString());
    }

    private void showLoadingState(String message) {
        tvLoadingStatus.setText(message);
        layoutInput.setVisibility(View.GONE);
        layoutLoading.setVisibility(View.VISIBLE);
    }

    private void appendDigit(String digit) {
        int dotIdx = amountBuilder.indexOf(".");
        if (dotIdx >= 0 && (amountBuilder.length() - dotIdx) > 2) return;
        boolean replacingZero = amountBuilder.toString().equals("0");
        // Cap the whole-AED part. Without this an operator can type a value long
        // enough that amountAed * 100 loses integer precision as a double, so the
        // fils figure sent to the gateway would no longer match what is on screen.
        if (dotIdx < 0 && !replacingZero && amountBuilder.length() >= QRConfig.MAX_INTEGER_DIGITS) {
            return;
        }
        if (replacingZero) {
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

    /** Keypad buffer without a dangling decimal point, e.g. "12." becomes "12". */
    private String amountText() {
        String amountStr = amountBuilder.toString();
        if (amountStr.endsWith(".")) amountStr = amountStr.substring(0, amountStr.length() - 1);
        return amountStr;
    }

    /**
     * Validates the keypad buffer and converts it to fils, reporting the reason
     * to the operator if it cannot be sent.
     *
     * @return the amount in fils, or -1 when no QR must be generated.
     */
    private long resolveAmountFils() {
        double amountAed;
        try {
            amountAed = Double.parseDouble(amountText());
        } catch (NumberFormatException e) {
            showToast(R.string.qr_pay_amount_error);
            return -1;
        }
        if (amountAed <= 0) {
            showToast(R.string.qr_pay_amount_error);
            return -1;
        }
        long amountFils = Math.round(amountAed * 100);
        if (amountFils < QRConfig.MIN_AMOUNT_FILS) {
            showToast(getString(R.string.qr_pay_amount_below_min,
                QRConfig.filsToAedText(QRConfig.MIN_AMOUNT_FILS)));
            return -1;
        }
        if (amountFils > QRConfig.MAX_AMOUNT_FILS) {
            showToast(getString(R.string.qr_pay_amount_above_max,
                QRConfig.filsToAedText(QRConfig.MAX_AMOUNT_FILS)));
            return -1;
        }
        return amountFils;
    }

    private void onGenerateClicked() {
        if (saleInFlight) return;
        long amountFils = resolveAmountFils();
        if (amountFils < 0) return;
        saleInFlight = true;

        final String amountDisplay = amountText();
        final String requestId = UUID.randomUUID().toString();
        lastRequestId = requestId;

        showLoadingState(getString(R.string.qr_pay_status_preparing));

        ApiKeyManager.get().ensureReady(
            () -> {
                if (isFinishing()) return;
                tvLoadingStatus.setText(R.string.qr_pay_status_generating);
                generateQR(amountFils, amountDisplay, requestId);
            },
            errorMsg -> {
                if (isFinishing()) return;
                showInputState();
                showToast(errorMsg);
            }
        );
    }

    private void onNfcPayClicked() {
        if (saleInFlight) return;
        if (resolveAmountFils() < 0) return;
        saleInFlight = true;
        // NFC pay: calls /qr/generate to get emvPayload, writes ddwallet://nfc?emvPayload=...
        // to the HCE tag. The wallet app reads the tag and calls validateQR(emvPayload)
        // to get all payment details, then completes P2M payment autonomously.
        Intent intent = new Intent(this, NFCPayActivity.class);
        intent.putExtra(NFCPayActivity.EXTRA_AMOUNT_AED, amountText());
        startActivity(intent);
    }

    private void generateQR(long amountFils, String amountDisplayAed, String requestId) {
        generateQR(amountFils, amountDisplayAed, requestId, false);
    }

    private void generateQR(long amountFils, String amountDisplayAed, String requestId, boolean isRetry) {
        try {
            JSONObject body = new JSONObject();
            String walletId = PreferencesUtil.getWalletId();
            body.put("messageTypeId", QRConfig.QR_MESSAGE_TYPE_ID);
            body.put("qrType", QRConfig.QR_TYPE);
            body.put("walletId", walletId.isEmpty() ? QRConfig.getDefaultWalletId() : walletId);
            body.put("amount", amountFils);
            // Stated explicitly even at zero, so the QR carries a commission split
            // that any later refund can reverse without guessing.
            body.put("commissionAmount", QRConfig.QR_COMMISSION_AMOUNT);
            body.put("currency", QRConfig.CURRENCY);
            body.put("terminalId", QRConfig.TERMINAL_ID);
            body.put("tradingLicenseNumber", QRConfig.TRADING_LICENSE_NUMBER);
            body.put("merchantCategoryCode", QRConfig.MERCHANT_CATEGORY_CODE);

            String url = QRConfig.getBaseUrl() + QRConfig.QR_ENDPOINT + "?requestId=" + requestId;
            Request.Builder reqBuilder = new Request.Builder()
                .url(url)
                .addHeader("X-LFI-ID", QRConfig.getXLfiId())
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(MediaType.parse("application/json"), body.toString()));

            String apiKey = PreferencesUtil.getLfiApiKey();
            if (!apiKey.isEmpty()) {
                reqBuilder.addHeader("X-LFI-API-KEY", apiKey);
            }

            httpClient().newCall(reqBuilder.build()).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    // AC-7: a generation attempt that never reached the gateway must
                    // still be traceable, and requestId is what correlates it.
                    Log.e(TAG, "generateQR failed, requestId=" + requestId, e);
                    runOnUiThread(() -> {
                        showInputState();
                        showToast(getString(R.string.qr_pay_error_network) + ": " + e.getMessage());
                    });
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String responseBody = response.body().string();
                    Log.d(TAG, "[" + response.code() + "] " + responseBody);
                    if (response.code() == 401 && !isRetry) {
                        // Stored key is expired — fetch a fresh one and retry once.
                        runOnUiThread(() -> {
                            if (isFinishing()) return;
                            tvLoadingStatus.setText(R.string.qr_pay_status_preparing);
                            ApiKeyManager.get().fetch(
                                () -> {
                                    if (isFinishing()) return;
                                    tvLoadingStatus.setText(R.string.qr_pay_status_generating);
                                    generateQR(amountFils, amountDisplayAed, requestId, true);
                                },
                                errorMsg -> {
                                    if (isFinishing()) return;
                                    showInputState();
                                    showToast(errorMsg);
                                }
                            );
                        });
                        return;
                    }
                    if (!response.isSuccessful()) {
                        runOnUiThread(() -> {
                            showInputState();
                            showToast(getString(R.string.qr_pay_error_network) + ": "
                                + QRConfig.errorTextOf(responseBody, response));
                        });
                        return;
                    }
                    try {
                        JSONObject json = new JSONObject(responseBody);
                        String emvPayload = QRConfig.emvFrom(json);
                        long ttlMs = QRConfig.ttlMsFrom(json);
                        runOnUiThread(() -> {
                            Intent intent = new Intent(QRPayActivity.this, QRDisplayActivity.class);
                            intent.putExtra(QRDisplayActivity.EXTRA_EMV_PAYLOAD, emvPayload);
                            intent.putExtra(QRDisplayActivity.EXTRA_AMOUNT_AED, amountDisplayAed);
                            intent.putExtra(QRDisplayActivity.EXTRA_REQUEST_ID, requestId);
                            intent.putExtra(QRDisplayActivity.EXTRA_TTL_MS, ttlMs);
                            intent.putExtra(QRDisplayActivity.EXTRA_COMMISSION_FILS,
                                QRConfig.QR_COMMISSION_AMOUNT);
                            startActivity(intent);
                        });
                    } catch (Exception e) {
                        // A 2xx that isn't parseable gateway JSON is almost always the
                        // Cloudflare Access page, not a malformed response.
                        String reason = QRConfig.looksLikeHtmlPage(responseBody)
                            ? QRConfig.errorTextOf(responseBody, response)
                            : getString(R.string.qr_pay_error_parse);
                        runOnUiThread(() -> {
                            showInputState();
                            showToast(reason);
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
