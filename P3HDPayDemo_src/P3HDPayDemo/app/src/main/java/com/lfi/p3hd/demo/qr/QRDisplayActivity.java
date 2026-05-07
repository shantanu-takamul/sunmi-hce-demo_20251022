package com.lfi.p3hd.demo.qr;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.lfi.p3hd.demo.BaseAppCompatActivity;
import com.lfi.p3hd.demo.R;
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

    private String emvPayload;
    private String amountAed;
    private String requestId;

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
        setContentView(R.layout.activity_qr_display);

        emvPayload = getIntent().getStringExtra(EXTRA_EMV_PAYLOAD);
        amountAed  = getIntent().getStringExtra(EXTRA_AMOUNT_AED);
        requestId  = getIntent().getStringExtra(EXTRA_REQUEST_ID);

        initView();
        startPolling();
        startCountdown();
    }

    private void initView() {
        tvTimer = findViewById(R.id.tv_timer);
        TextView tvAmount = findViewById(R.id.tv_amount);
        tvAmount.setText("AED " + amountAed);
        ImageView ivQr = findViewById(R.id.iv_qr_code);
        Bitmap qrBmp = buildQRBitmap(emvPayload, 600, 600);
        if (qrBmp != null) ivQr.setImageBitmap(qrBmp);
        findViewById(R.id.btn_cancel).setOnClickListener(v -> finish());
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
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

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "QRStatus: request failed", e);
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
                            runOnUiThread(() -> {
                                if (!finished) {
                                    finished = true;
                                    Intent intent = new Intent(QRDisplayActivity.this, PaymentSuccessActivity.class);
                                    intent.putExtra(PaymentSuccessActivity.EXTRA_AMOUNT_AED,  amountAed);
                                    intent.putExtra(PaymentSuccessActivity.EXTRA_REQUEST_ID,  requestId);
                                    intent.putExtra(PaymentSuccessActivity.EXTRA_EMV_PAYLOAD, emvPayload);
                                    startActivity(intent);
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
                }
            }
        });
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
