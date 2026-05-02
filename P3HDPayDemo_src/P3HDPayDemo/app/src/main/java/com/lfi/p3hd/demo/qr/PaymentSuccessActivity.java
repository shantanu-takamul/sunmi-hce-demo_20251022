package com.lfi.p3hd.demo.qr;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.lfi.p3hd.demo.BaseAppCompatActivity;
import com.lfi.p3hd.demo.MyApplication;
import com.lfi.p3hd.demo.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class PaymentSuccessActivity extends BaseAppCompatActivity {
    public static final String EXTRA_AMOUNT_AED  = "amountAed";
    public static final String EXTRA_REQUEST_ID  = "requestId";
    public static final String EXTRA_EMV_PAYLOAD = "emvPayload";  // kept for caller compatibility

    private String amountAed;
    private String requestId;
    private String dateTimeStr;
    private final OkHttpClient httpClient = new OkHttpClient();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_payment_success);

        amountAed   = getIntent().getStringExtra(EXTRA_AMOUNT_AED);
        requestId   = getIntent().getStringExtra(EXTRA_REQUEST_ID);
        dateTimeStr = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date());

        initView();
    }

    private void initView() {
        initActionbar(R.string.payment_success_title);
        ((TextView) findViewById(R.id.tv_amount)).setText("AED " + amountAed);
        String shortRef = requestId != null && requestId.length() >= 8
            ? requestId.substring(requestId.length() - 8) : requestId;
        ((TextView) findViewById(R.id.tv_ref)).setText("Ref: " + shortRef);
        ((TextView) findViewById(R.id.tv_date_time)).setText(dateTimeStr);
        findViewById(R.id.btn_print).setOnClickListener(v -> new Thread(this::printReceipt).start());
        findViewById(R.id.btn_return).setOnClickListener(v -> finish());
    }

    private void printReceipt() {
        // Step 1: fetch full transaction details
        JSONObject tx = null;
        try {
            String url = QRConfig.getBaseUrl() + QRConfig.QR_STATUS_ENDPOINT + "?requestId=" + requestId;
            Request req = new Request.Builder()
                .url(url)
                .addHeader("X-LFI-ID", QRConfig.X_LFI_ID)
                .get()
                .build();
            Response response = httpClient.newCall(req).execute();
            String body = response.body().string();
            Log.d(TAG, "fetchTx: " + body);
            JSONObject json = new JSONObject(body);
            JSONArray transactions = json.optJSONArray("transactions");
            if (transactions != null && transactions.length() > 0) {
                tx = transactions.getJSONObject(0);
            }
        } catch (Exception e) {
            Log.e(TAG, "fetchTx failed, printing with available data", e);
        }

        // Step 2: print
        try {
            com.sunmi.pay.hardware.aidlv2.print.PrinterOptV2 printer = MyApplication.app.printerOptV2;
            if (printer == null) {
                showToast("Printer not connected");
                return;
            }

            // Must open BEFORE any printer call — getPrinterStatus() returns -1707 if called without open
            int openCode = printer.printOpen();
            if (openCode < 0) {
                showToast("Printer error: " + openCode);
                return;
            }

            try {
                final int PAPER_WIDTH = 384;
                Bitmap receiptBmp = buildReceiptBitmap(PAPER_WIDTH, tx);
                if (receiptBmp == null) {
                    showToast("Failed to render receipt");
                    return;
                }

                int rows = receiptBmp.getHeight();
                byte[] rowData = new byte[PAPER_WIDTH / 8];
                for (int y = 0; y < rows; y++) {
                    for (int byteIdx = 0; byteIdx < rowData.length; byteIdx++) {
                        byte b = 0;
                        for (int bit = 0; bit < 8; bit++) {
                            int x = byteIdx * 8 + bit;
                            int pixel = receiptBmp.getPixel(x, y);
                            if (Color.red(pixel) < 128) {
                                b |= (byte) (0x80 >> bit);
                            }
                        }
                        rowData[byteIdx] = b;
                    }
                    printer.printPointLine(rowData);
                }
                printer.printFeedPaper(60);
                showToast("Printed!");
            } finally {
                printer.printClose();
            }
        } catch (Exception e) {
            Log.e(TAG, "printReceipt error", e);
            showToast("Print failed: " + e.getMessage());
        }
    }

    private Bitmap buildReceiptBitmap(int width, JSONObject tx) {
        int estimatedHeight = 600;
        Bitmap bmp = Bitmap.createBitmap(width, estimatedHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.WHITE);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.BLACK);
        int y = 20;

        // Header — bold, centered
        paint.setTextSize(32);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("LFI Payment", width / 2f, y, paint);
        y += 40;
        paint.setTextSize(26);
        canvas.drawText("Payment Receipt", width / 2f, y, paint);
        y += 36;

        // Divider
        paint.setStrokeWidth(2);
        canvas.drawLine(10, y, width - 10, y, paint);
        y += 20;

        // Fields — normal, left-aligned
        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextSize(24);
        paint.setTextAlign(Paint.Align.LEFT);

        // Amount — prefer API fils value converted to AED, fallback to passed string
        String displayAmount = amountAed;
        if (tx != null && tx.has("amount")) {
            try {
                long fils = tx.getLong("amount");
                displayAmount = String.format(Locale.US, "%.2f", fils / 100.0);
            } catch (Exception ignored) {}
        }
        canvas.drawText("Amount:  AED " + displayAmount, 16, y, paint); y += 36;

        // Currency
        if (tx != null) {
            String currency = tx.optString("currency", "");
            if (!currency.isEmpty()) {
                canvas.drawText("Currency: " + currency, 16, y, paint); y += 36;
            }
        }

        // Ref / Transaction ID
        String displayRef = requestId != null && requestId.length() >= 8
            ? requestId.substring(requestId.length() - 8) : requestId;
        if (tx != null) {
            String apiId = tx.optString("transactionId", tx.optString("id", ""));
            if (!apiId.isEmpty()) displayRef = apiId;
        }
        canvas.drawText("Ref:     " + displayRef, 16, y, paint); y += 36;

        // Date — prefer API timestamp, fallback to local
        String displayDate = dateTimeStr;
        if (tx != null) {
            String apiDate = tx.optString("createdAt",
                             tx.optString("updatedAt",
                             tx.optString("transactionDate", "")));
            if (!apiDate.isEmpty()) {
                displayDate = apiDate.length() > 16
                    ? apiDate.substring(0, 16).replace("T", " ")
                    : apiDate;
            }
        }
        canvas.drawText("Date:    " + displayDate, 16, y, paint); y += 36;

        // Status
        String displayStatus = "PAID";
        if (tx != null) {
            String apiStatus = tx.optString("transactionStatus", "");
            if (!apiStatus.isEmpty()) displayStatus = apiStatus;
        }
        canvas.drawText("Status:  " + displayStatus, 16, y, paint); y += 36;

        // Payer wallet — only if present in API response
        if (tx != null) {
            String payer = tx.optString("senderWalletId",
                           tx.optString("payerWalletId", ""));
            if (!payer.isEmpty()) {
                canvas.drawText("Payer:   " + payer, 16, y, paint); y += 36;
            }
        }

        // Divider
        canvas.drawLine(10, y, width - 10, y, paint);
        y += 20;

        // Footer
        paint.setTextSize(20);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("Thank you!", width / 2f, y, paint);
        y += 28;

        return Bitmap.createBitmap(bmp, 0, 0, width, Math.min(y + 20, estimatedHeight));
    }
}
