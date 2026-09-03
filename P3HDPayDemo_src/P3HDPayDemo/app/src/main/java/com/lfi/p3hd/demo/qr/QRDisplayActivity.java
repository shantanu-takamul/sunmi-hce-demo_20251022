package com.lfi.p3hd.demo.qr;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.lfi.p3hd.demo.BaseAppCompatActivity;
import com.lfi.p3hd.demo.R;
import com.lfi.p3hd.demo.net.HttpClients;
import com.lfi.p3hd.demo.net.NetDiagnostics;
import com.lfi.p3hd.demo.utils.ApiKeyManager;
import com.lfi.p3hd.demo.utils.PreferencesUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class QRDisplayActivity extends BaseAppCompatActivity {
    public static final String EXTRA_EMV_PAYLOAD = "emvPayload";
    public static final String EXTRA_AMOUNT_AED  = "amountAed";
    public static final String EXTRA_REQUEST_ID  = "requestId";
    public static final String EXTRA_TTL_MS      = "ttlMs";
    /**
     * Commission the QR was generated with, in fils.
     *
     * Carried rather than re-derived so that a later return reverses exactly the
     * split the sale was created with. See QRConfig#QR_COMMISSION_AMOUNT.
     */
    public static final String EXTRA_COMMISSION_FILS = "commissionFils";

    private String emvPayload;
    private String amountAed;
    private String requestId;
    private long ttlMs;
    private long commissionFils;

    private TextView tvTimer;
    private CountDownTimer countDownTimer;
    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private boolean finished = false;
    /** One key refresh per QR — guards against a refresh loop on every 5s tick. */
    private boolean keyRefreshed = false;

    /**
     * Consecutive failed polls, and the reason the last one failed.
     *
     * A single miss is normal — a dropped packet, a hiccup at the gateway — and
     * putting an error on screen for it would train the operator to ignore the line.
     * Three in a row (six seconds at POLL_INTERVAL_MS) is a real fault.
     */
    private int consecutivePollFailures = 0;
    private TextView tvPollStatus;

    private static final int POLL_FAILURES_BEFORE_WARNING = 3;

    /**
     * The shared client for the active environment.
     *
     * Resolved per use rather than held in a field: a field is frozen at
     * construction, so an environment switch never reaches it.
     */
    private OkHttpClient httpClient() {
        return HttpClients.forCurrentEnv();
    }

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
        setContentView(R.layout.activity_qr_display);

        emvPayload = getIntent().getStringExtra(EXTRA_EMV_PAYLOAD);
        amountAed  = getIntent().getStringExtra(EXTRA_AMOUNT_AED);
        requestId  = getIntent().getStringExtra(EXTRA_REQUEST_ID);
        // Count down the window the gateway actually granted, not a local guess.
        ttlMs      = getIntent().getLongExtra(EXTRA_TTL_MS, QRConfig.QR_TIMEOUT_MS);
        commissionFils = getIntent().getLongExtra(
            EXTRA_COMMISSION_FILS, QRConfig.QR_COMMISSION_AMOUNT);

        initView();
        startPolling();
        startCountdown();
    }

    private void initView() {
        tvTimer = findViewById(R.id.tv_timer);
        tvPollStatus = findViewById(R.id.tv_poll_status);
        TextView tvAmount = findViewById(R.id.tv_amount);
        tvAmount.setText("AED " + amountAed);
        ImageView ivQr = findViewById(R.id.iv_qr_code);
        Bitmap qrBmp = buildQRBitmap(emvPayload, 600, 600);
        if (qrBmp != null) ivQr.setImageBitmap(qrBmp);
        findViewById(R.id.btn_cancel).setOnClickListener(v -> finish());
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
    }

    private void startCountdown() {
        countDownTimer = new CountDownTimer(ttlMs, 1000) {
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
                    startActivity(new Intent(QRDisplayActivity.this, QRExpiredActivity.class));
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
        Request.Builder reqBuilder = new Request.Builder()
            .url(url)
            .addHeader("X-LFI-ID", QRConfig.getXLfiId())
            .get();
        String apiKey = PreferencesUtil.getLfiApiKey();
        if (!apiKey.isEmpty()) reqBuilder.addHeader("X-LFI-API-KEY", apiKey);
        Request request = reqBuilder.build();

        httpClient().newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "QRStatus: request failed", e);
                notePollFailure(NetDiagnostics.classify(e));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String responseBody = response.body().string();
                if (response.code() == 401) {
                    notePollFailure(NetDiagnostics.classifyHttp(401));
                    // The key died mid-QR. Without this the countdown keeps
                    // running while polling never succeeds again, so the QR
                    // looks alive but can never report payment.
                    if (!keyRefreshed) {
                        keyRefreshed = true;
                        Log.w(TAG, "QRStatus: 401 — refreshing API key");
                        runOnUiThread(() -> ApiKeyManager.get().fetch(
                            () -> Log.d(TAG, "QRStatus: key refreshed, polling resumes"),
                            message -> Log.e(TAG, "QRStatus: key refresh failed: " + message)
                        ));
                    }
                    return;   // next tick retries with whatever key is stored
                }
                if (!response.isSuccessful()) {
                    notePollFailure(QRConfig.looksLikeHtmlPage(responseBody)
                        ? QRConfig.errorTextOf(responseBody, response)
                        : NetDiagnostics.classifyHttp(response.code()));
                    return;
                }

                try {
                    JSONObject json = new JSONObject(responseBody);
                    JSONArray transactions = json.optJSONArray("transactions");

                    // Reaching here means the gateway answered with parseable JSON,
                    // which is the definition of a healthy poll. An empty list is a
                    // customer who has not paid yet, not a fault.
                    notePollSuccess();

                    if (transactions == null || transactions.length() == 0) return;

                    JSONObject tx = transactions.getJSONObject(0);
                    String status = tx.getString("transactionStatus");

                    switch (status) {
                        case "SUCCESS":
                            stopPolling();
                            countDownTimer.cancel();
                            // Everything the success screen needs to void or refund
                            // this sale, read from the poll response that just proved
                            // it was paid. Stamped now: the operator's cancel window
                            // starts the moment the terminal knows.
                            Intent success = successIntent(tx, SystemClock.elapsedRealtime());
                            runOnUiThread(() -> {
                                if (!finished) {
                                    finished = true;
                                    startActivity(success);
                                    finish();
                                }
                            });
                            break;
                        case "FAILED":
                            stopPolling();
                            countDownTimer.cancel();
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
                    Log.e(TAG, "QRStatus: parse error", e);
                    notePollFailure("gateway sent a response the terminal could not read");
                }
            }
        });
    }

    /**
     * Records a failed poll and, once they stop looking like noise, says so on screen.
     *
     * Called from OkHttp's callback thread, so the UI touch is posted.
     */
    private void notePollFailure(String reason) {
        consecutivePollFailures++;
        Log.w(TAG, "QRStatus: poll failure " + consecutivePollFailures + " — " + reason);
        if (consecutivePollFailures < POLL_FAILURES_BEFORE_WARNING) return;
        runOnUiThread(() -> {
            if (finished || tvPollStatus == null) return;
            tvPollStatus.setText(reason);
            tvPollStatus.setVisibility(View.VISIBLE);
        });
    }

    /** Clears the warning: whatever was wrong has stopped being wrong. */
    private void notePollSuccess() {
        if (consecutivePollFailures == 0) return;
        consecutivePollFailures = 0;
        runOnUiThread(() -> {
            if (tvPollStatus != null) tvPollStatus.setVisibility(View.GONE);
        });
    }

    /**
     * Builds the hand-off to the success screen from the paid transaction.
     *
     * @param tx         the history record that reported SUCCESS
     * @param nowElapsed {@link SystemClock#elapsedRealtime()} sampled at detection,
     *                   paired with the record's createdAt to place the cancel
     *                   deadline on the monotonic clock
     */
    private Intent successIntent(JSONObject tx, long nowElapsed) {
        // The gateway's own figure wins once settlement has produced one; before
        // that the commission this QR was generated with is the only truth there is.
        long settledCommission = QRConfig.commissionFrom(tx);
        long commission = settledCommission > 0 ? settledCommission : commissionFils;

        Intent intent = new Intent(QRDisplayActivity.this, PaymentSuccessActivity.class);
        intent.putExtra(PaymentSuccessActivity.EXTRA_AMOUNT_AED,  amountAed);
        intent.putExtra(PaymentSuccessActivity.EXTRA_REQUEST_ID,  requestId);
        intent.putExtra(PaymentSuccessActivity.EXTRA_EMV_PAYLOAD, emvPayload);
        intent.putExtra(PaymentSuccessActivity.EXTRA_TRANSACTION_ID,
            tx.optString("transactionId", ""));
        intent.putExtra(PaymentSuccessActivity.EXTRA_AMOUNT_FILS, tx.optLong("amount", 0L));
        intent.putExtra(PaymentSuccessActivity.EXTRA_COMMISSION_FILS, commission);
        // QA nests the wallets in movements[]; demo returns them flat. Only the
        // older gateway still wants them on a return, but it wants them required.
        intent.putExtra(PaymentSuccessActivity.EXTRA_PAYER_WALLET,
            QRConfig.walletFrom(tx, "payerWalletId"));
        intent.putExtra(PaymentSuccessActivity.EXTRA_PAYEE_WALLET,
            QRConfig.walletFrom(tx, "payeeWalletId"));
        intent.putExtra(PaymentSuccessActivity.EXTRA_CANCEL_DEADLINE,
            QRConfig.cancelDeadlineElapsed(tx, nowElapsed));
        return intent;
    }

    public static Bitmap buildQRBitmap(String content, int width, int height) {
        if (content == null || content.isEmpty()) return null;
        try {
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, width, height);
            Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    bmp.setPixel(x, y, matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
                }
            }
            return bmp;
        } catch (WriterException e) {
            return null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPolling();
        if (countDownTimer != null) countDownTimer.cancel();
    }
}
