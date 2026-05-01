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
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.lfi.p3hd.demo.BaseAppCompatActivity;
import com.lfi.p3hd.demo.MyApplication;
import com.lfi.p3hd.demo.R;
import com.lfi.p3hd.demo.qr.PaymentSuccessActivity;
import com.lfi.p3hd.demo.qr.QRConfig;
import com.lfi.p3hd.demo.qr.QRExpiredActivity;
import com.sunmi.pay.hardware.aidl.AidlConstants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class NFCPayActivity extends BaseAppCompatActivity {
    public static final String EXTRA_AMOUNT_AED   = "amountAed";
    public static final String EXTRA_REQUEST_ID   = "requestId";
    public static final String EXTRA_PAYMENT_URL  = "payment_url";

    private String amountAed;
    private String requestId;
    private String paymentUrl;

    private TextView tvTimer;
    private CountDownTimer countDownTimer;
    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private final OkHttpClient httpClient = new OkHttpClient();
    private boolean finished = false;

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            checkPaymentStatus(requestId);
            pollHandler.postDelayed(this, QRConfig.POLL_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_nfc_pay);

        amountAed  = getIntent().getStringExtra(EXTRA_AMOUNT_AED);
        requestId  = getIntent().getStringExtra(EXTRA_REQUEST_ID);
        paymentUrl = getIntent().getStringExtra(EXTRA_PAYMENT_URL);

        initView();
        openHce();
        startPolling();
        startCountdown();
    }

    private void initView() {
        initActionbar(R.string.nfc_pay_title);
        tvTimer = findViewById(R.id.tv_timer);
        TextView tvAmount = findViewById(R.id.tv_amount);
        tvAmount.setText("AED " + amountAed);
        findViewById(R.id.btn_cancel).setOnClickListener(v -> {
            closeHce();
            finish();
        });
    }

    private void openHce() {
        if (!MyApplication.app.isConnectPaySDK()) {
            showToast(R.string.sdk_not_connected);
            return;
        }
        try {
            int cardType = AidlConstants.CardType.NFC.getValue();
            MyApplication.app.hceManagerV2.hceOpen(cardType, null);
            Uri uri = Uri.parse(paymentUrl);
            NdefRecord record = NdefRecord.createUri(uri);
            MyApplication.app.hceManagerV2.hceNdefWrite(new NdefMessage(record));
            Log.d(TAG, "HCE opened with URL: " + paymentUrl);
        } catch (Exception e) {
            Log.e(TAG, "openHce failed", e);
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
                closeHce();
                if (!finished) {
                    finished = true;
                    startActivity(new Intent(NFCPayActivity.this, QRExpiredActivity.class));
                    finish();
                }
            }
        }.start();
    }

    private void startPolling() {
        pollHandler.postDelayed(pollRunnable, QRConfig.POLL_INTERVAL_MS);
    }

    private void stopPolling() {
        pollHandler.removeCallbacks(pollRunnable);
    }

    private void checkPaymentStatus(String reqId) {
        String url = QRConfig.getBaseUrl() + QRConfig.QR_STATUS_ENDPOINT + "?requestId=" + reqId;
        Request request = new Request.Builder()
            .url(url)
            .addHeader("X-LFI-ID", QRConfig.X_LFI_ID)
            .get()
            .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "NFCPay status: request failed", e);
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
                            countDownTimer.cancel();
                            closeHce();
                            runOnUiThread(() -> {
                                if (!finished) {
                                    finished = true;
                                    Intent intent = new Intent(NFCPayActivity.this, PaymentSuccessActivity.class);
                                    intent.putExtra(PaymentSuccessActivity.EXTRA_AMOUNT_AED, amountAed);
                                    intent.putExtra(PaymentSuccessActivity.EXTRA_REQUEST_ID, requestId);
                                    intent.putExtra(PaymentSuccessActivity.EXTRA_EMV_PAYLOAD, "");
                                    startActivity(intent);
                                    finish();
                                }
                            });
                            break;
                        case "FAILED":
                            stopPolling();
                            countDownTimer.cancel();
                            closeHce();
                            runOnUiThread(() -> {
                                if (!finished) {
                                    finished = true;
                                    showToast(R.string.qr_pay_failed);
                                    finish();
                                }
                            });
                            break;
                        default:
                            break;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "NFCPay status: parse error", e);
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPolling();
        if (countDownTimer != null) countDownTimer.cancel();
        closeHce();
    }
}
