package com.lfi.p3hd.demo.nfc;

import android.content.Intent;
import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.lfi.p3hd.demo.BaseAppCompatActivity;
import com.lfi.p3hd.demo.MyApplication;
import com.lfi.p3hd.demo.R;
import com.lfi.p3hd.demo.qr.PaymentSuccessActivity;
import com.lfi.p3hd.demo.qr.QRConfig;
import com.lfi.p3hd.demo.qr.QRExpiredActivity;
import com.lfi.p3hd.demo.utils.ApiKeyManager;
import com.lfi.p3hd.demo.utils.PreferencesUtil;
import com.sunmi.pay.hardware.aidl.AidlConstants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Locale;
import java.util.UUID;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * NFC payment screen — mirrors the QR payment flow but uses HCE instead of a QR bitmap.
 *
 * Flow:
 *  1. onCreate  → showLoadingState → ApiKeyManager.ensureReady
 *  2. ensureReady callback → call POST /qr/generate (same as QR flow) to get a
 *     tracked requestId + emvPayload (+ optional qrCodeId)
 *  3. On API success → openHceWithPayload (enriched ddwallet:// URI written to NFC tag)
 *                    → showReadyState (timer + "tap your phone" UI)
 *                    → startCountdown + startPolling
 *  4. Polling detects SUCCESS → PaymentSuccessActivity (with requestId for receipt)
 *  5. Timeout (5 min) → QRExpiredActivity
 *  6. onDestroy → stopPolling + cancelTimer + closeHce
 *
 * NFC URL written to tag:
 *   ddwallet://nfc?walletId=X&merchantName=Y&walletType=MICRO_MERCHANT&amount=Z
 *     &requestId=UUID        ← links this tap to the QR entry on the LFI Gateway
 *     &qrCodeId=QR-ID        ← present when /qr/generate returns it; enables DYNAMIC tracking
 *     &emvPayload=ENCODED    ← full EMV payload; mobile calls validateQR() with this
 *                               as fallback to resolve qrCodeId when not directly present
 */
public class NFCPayActivity extends BaseAppCompatActivity {
    public static final String EXTRA_AMOUNT_AED = "amountAed";

    private static final String DDWALLET_SCHEME       = "ddwallet";
    private static final String DDWALLET_HOST         = "nfc";
    private static final String PARAM_WALLET_ID       = "walletId";
    private static final String PARAM_MERCHANT_NAME   = "merchantName";
    private static final String PARAM_WALLET_TYPE     = "walletType";
    private static final String PARAM_AMOUNT          = "amount";
    private static final String PARAM_REQUEST_ID      = "requestId";
    private static final String PARAM_QR_CODE_ID      = "qrCodeId";
    private static final String PARAM_EMV_PAYLOAD     = "emvPayload";
    private static final String DEFAULT_MERCHANT_NAME  = "CBDC Merchant";
    private static final String DEFAULT_WALLET_TYPE    = "MERCHANT_COLLECTION";

    private String amountAed;
    private long   amountFils;
    private String requestId;   // generated once in onCreate, reused on 401 retry

    private View     layoutLoading;
    private View     layoutReady;
    private TextView tvTimer;
    private TextView tvLoadingStatus;

    private CountDownTimer countDownTimer;
    private final Handler     pollHandler = new Handler(Looper.getMainLooper());
    private final OkHttpClient httpClient = new OkHttpClient();
    private boolean finished = false;

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            checkPaymentStatus();
            pollHandler.postDelayed(this, QRConfig.POLL_INTERVAL_MS);
        }
    };

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_nfc_pay);

        amountAed = getIntent().getStringExtra(EXTRA_AMOUNT_AED);
        try {
            amountFils = Math.round(Double.parseDouble(amountAed) * 100);
        } catch (NumberFormatException e) {
            amountFils = 0;
        }

        // Generate the requestId once here so it is stable across 401 retries.
        requestId = UUID.randomUUID().toString();

        initView();
        startPaymentFlow();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPolling();
        if (countDownTimer != null) countDownTimer.cancel();
        closeHce();
    }

    // ─── View setup ───────────────────────────────────────────────────────────

    private void initView() {
        layoutLoading   = findViewById(R.id.layout_loading);
        layoutReady     = findViewById(R.id.layout_ready);
        tvTimer         = findViewById(R.id.tv_timer);
        tvLoadingStatus = findViewById(R.id.tv_loading_status);

        ((TextView) findViewById(R.id.tv_amount)).setText("AED " + amountAed);

        showLoadingState(getString(R.string.qr_pay_status_preparing));

        View.OnClickListener cancelListener = v -> {
            finished = true;
            closeHce();
            finish();
        };
        findViewById(R.id.btn_cancel).setOnClickListener(cancelListener);
        findViewById(R.id.btn_back).setOnClickListener(cancelListener);
    }

    private void showLoadingState(String message) {
        tvLoadingStatus.setText(message);
        layoutLoading.setVisibility(View.VISIBLE);
        layoutReady.setVisibility(View.GONE);
    }

    private void showReadyState() {
        layoutLoading.setVisibility(View.GONE);
        layoutReady.setVisibility(View.VISIBLE);
    }

    // ─── Payment flow ─────────────────────────────────────────────────────────

    private void startPaymentFlow() {
        if (!MyApplication.app.isConnectPaySDK()) {
            showToast(R.string.sdk_not_connected);
            return;
        }

        ApiKeyManager.get().ensureReady(
            () -> {
                if (isFinishing()) return;
                showLoadingState(getString(R.string.nfc_pay_status_generating));
                generateQRAndOpenHce(false);
            },
            errorMsg -> {
                if (isFinishing()) return;
                showToast(errorMsg);
                finish();
            }
        );
    }

    /**
     * Calls POST /qr/generate with the same parameters as the QR flow.
     * On success, writes an enriched ddwallet:// URI to the HCE tag and starts
     * polling for payment completion.
     *
     * The requestId used here is generated once in onCreate(), so an automatic
     * 401 retry (isRetry=true) reuses the same UUID — idempotency is preserved.
     */
    private void generateQRAndOpenHce(boolean isRetry) {
        try {
            // Use the QR wallet (same LFI merchant) for the generate API call.
            // The NFC-specific wallet setting was for the old bare-URL approach;
            // the gateway enforces that walletId belongs to the X-LFI-ID header,
            // so using a mismatched NFC wallet causes AUTHZ_LFI_MISMATCH (HTTP 403).
            String walletId = PreferencesUtil.getWalletId();
            if (walletId.isEmpty()) walletId = QRConfig.getDefaultWalletId();
            final String resolvedWalletId = walletId;

            String merchantName = PreferencesUtil.getNfcMerchantName();
            if (merchantName.isEmpty()) merchantName = DEFAULT_MERCHANT_NAME;

            String walletType = PreferencesUtil.getNfcWalletType();
            if (walletType.isEmpty()) walletType = DEFAULT_WALLET_TYPE;
            final String resolvedWalletType = walletType;
            final String resolvedMerchantName = merchantName;

            JSONObject body = new JSONObject();
            body.put("qrType",                QRConfig.QR_TYPE);
            body.put("walletId",              resolvedWalletId);
            body.put("amount",                amountFils);
            body.put("currency",              QRConfig.CURRENCY);
            body.put("terminalId",            QRConfig.TERMINAL_ID);
            body.put("tradingLicenseNumber",  QRConfig.TRADING_LICENSE_NUMBER);
            body.put("merchantCategoryCode",  QRConfig.MERCHANT_CATEGORY_CODE);

            String url = QRConfig.getBaseUrl() + QRConfig.QR_ENDPOINT + "?requestId=" + requestId;
            Request.Builder reqBuilder = new Request.Builder()
                .url(url)
                .addHeader("X-LFI-ID",       QRConfig.getXLfiId())
                .addHeader("Content-Type",    "application/json")
                .post(RequestBody.create(MediaType.parse("application/json"), body.toString()));

            String apiKey = PreferencesUtil.getLfiApiKey();
            if (!apiKey.isEmpty()) reqBuilder.addHeader("X-LFI-API-KEY", apiKey);

            httpClient.newCall(reqBuilder.build()).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    runOnUiThread(() -> {
                        if (isFinishing()) return;
                        showToast(getString(R.string.qr_pay_error_network) + ": " + e.getMessage());
                        finish();
                    });
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String responseBody = response.body().string();
                    Log.d(TAG, "generateQR [" + response.code() + "]: " + responseBody);

                    if (response.code() == 401 && !isRetry) {
                        // Stored key expired — refresh and retry once with the same requestId.
                        runOnUiThread(() -> {
                            if (isFinishing()) return;
                            showLoadingState(getString(R.string.qr_pay_status_preparing));
                            ApiKeyManager.get().fetch(
                                () -> {
                                    if (isFinishing()) return;
                                    showLoadingState(getString(R.string.nfc_pay_status_generating));
                                    generateQRAndOpenHce(true);
                                },
                                errorMsg -> {
                                    if (isFinishing()) return;
                                    showToast(errorMsg);
                                    finish();
                                }
                            );
                        });
                        return;
                    }

                    if (!response.isSuccessful()) {
                        runOnUiThread(() -> {
                            if (isFinishing()) return;
                            showToast(getString(R.string.qr_pay_error_network) + " (HTTP " + response.code() + ")");
                            finish();
                        });
                        return;
                    }

                    try {
                        JSONObject json = new JSONObject(responseBody);
                        String emvPayload = json.getString("emvPayload");
                        // qrCodeId may or may not be present depending on gateway version
                        String qrCodeId = json.optString("qrCodeId", "");

                        runOnUiThread(() -> {
                            if (isFinishing()) return;
                            openHceWithPayload(resolvedWalletId, resolvedMerchantName, resolvedWalletType, emvPayload, qrCodeId);
                            showReadyState();
                            startCountdown();
                            startPolling();
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> {
                            if (isFinishing()) return;
                            showToast(getString(R.string.qr_pay_error_parse));
                            finish();
                        });
                    }
                }
            });
        } catch (Exception e) {
            runOnUiThread(() -> {
                if (isFinishing()) return;
                showToast(getString(R.string.qr_pay_error_network));
                finish();
            });
        }
    }

    // ─── HCE ──────────────────────────────────────────────────────────────────

    /**
     * Writes the enriched ddwallet:// URI to the HCE tag.
     *
     * New params vs the old static URI:
     *   &requestId=UUID        — ties this NFC tap to the QR entry on LFI Gateway
     *   &qrCodeId=QR-ID        — present when gateway returns it; mobile uses directly
     *   &emvPayload=...        — full EMV string; mobile calls validateQR() with this
     *                            to resolve qrCodeId when it is absent from the URL
     */
    private void openHceWithPayload(String walletId, String merchantName, String walletType,
                                    String emvPayload, String qrCodeId) {
        try {
            Uri.Builder uriBuilder = new Uri.Builder()
                .scheme(DDWALLET_SCHEME)
                .authority(DDWALLET_HOST)
                .appendQueryParameter(PARAM_WALLET_ID,     walletId)
                .appendQueryParameter(PARAM_MERCHANT_NAME, merchantName)
                .appendQueryParameter(PARAM_WALLET_TYPE,   walletType)
                .appendQueryParameter(PARAM_AMOUNT,        amountAed)
                .appendQueryParameter(PARAM_REQUEST_ID,    requestId)
                .appendQueryParameter(PARAM_EMV_PAYLOAD,   emvPayload);

            if (!qrCodeId.isEmpty()) {
                uriBuilder.appendQueryParameter(PARAM_QR_CODE_ID, qrCodeId);
            }

            Uri uri = uriBuilder.build();
            int cardType = AidlConstants.CardType.NFC.getValue();
            MyApplication.app.hceManagerV2.hceOpen(cardType, null);
            NdefRecord record = NdefRecord.createUri(uri);
            MyApplication.app.hceManagerV2.hceNdefWrite(new NdefMessage(record));
            Log.d(TAG, "HCE opened with URL: " + uri);
        } catch (Exception e) {
            Log.e(TAG, "openHceWithPayload failed", e);
            showToast("Failed to open NFC: " + e.getMessage());
            finish();
        }
    }

    private void closeHce() {
        try {
            if (MyApplication.app.hceManagerV2 != null) {
                MyApplication.app.hceManagerV2.hceClose();
            }
        } catch (Exception e) {
            Log.e(TAG, "closeHce failed", e);
        }
    }

    // ─── Countdown ────────────────────────────────────────────────────────────

    private void startCountdown() {
        countDownTimer = new CountDownTimer(QRConfig.QR_TIMEOUT_MS, 1000) {
            @Override
            public void onTick(long ms) {
                long m = ms / 60_000;
                long s = (ms % 60_000) / 1_000;
                tvTimer.setText(String.format(Locale.US, "%02d:%02d", m, s));
            }

            @Override
            public void onFinish() {
                tvTimer.setText("00:00");
                stopPolling();
                if (!finished) {
                    finished = true;
                    startActivity(new Intent(NFCPayActivity.this, QRExpiredActivity.class));
                    finish();
                }
            }
        }.start();
    }

    // ─── Polling ──────────────────────────────────────────────────────────────

    private void startPolling() {
        pollHandler.postDelayed(pollRunnable, QRConfig.POLL_INTERVAL_MS);
    }

    private void stopPolling() {
        pollHandler.removeCallbacks(pollRunnable);
    }

    private void checkPaymentStatus() {
        String url = QRConfig.getBaseUrl() + QRConfig.QR_STATUS_ENDPOINT + "?requestId=" + requestId;
        Request.Builder reqBuilder = new Request.Builder()
            .url(url)
            .addHeader("X-LFI-ID", QRConfig.getXLfiId())
            .get();
        String apiKey = PreferencesUtil.getLfiApiKey();
        if (!apiKey.isEmpty()) reqBuilder.addHeader("X-LFI-API-KEY", apiKey);

        httpClient.newCall(reqBuilder.build()).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "checkPaymentStatus: request failed", e);
                // Non-fatal — keep polling; will retry on next interval
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String responseBody = response.body().string();
                try {
                    JSONObject json = new JSONObject(responseBody);
                    JSONArray transactions = json.optJSONArray("transactions");
                    if (transactions == null || transactions.length() == 0) return;

                    JSONObject tx = transactions.getJSONObject(0);
                    String status = tx.getString("transactionStatus");

                    switch (status) {
                        case "SUCCESS":
                            stopPolling();
                            if (countDownTimer != null) countDownTimer.cancel();
                            runOnUiThread(() -> {
                                if (!finished) {
                                    finished = true;
                                    Intent intent = new Intent(NFCPayActivity.this, PaymentSuccessActivity.class);
                                    intent.putExtra(PaymentSuccessActivity.EXTRA_AMOUNT_AED, amountAed);
                                    intent.putExtra(PaymentSuccessActivity.EXTRA_REQUEST_ID, requestId);
                                    startActivity(intent);
                                    finish();
                                }
                            });
                            break;

                        case "FAILED":
                            stopPolling();
                            if (countDownTimer != null) countDownTimer.cancel();
                            runOnUiThread(() -> {
                                if (!finished) {
                                    finished = true;
                                    showToast(R.string.qr_pay_failed);
                                    finish();
                                }
                            });
                            break;

                        default:
                            // PENDING or unknown — keep polling
                            break;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "checkPaymentStatus: parse error", e);
                }
            }
        });
    }
}
