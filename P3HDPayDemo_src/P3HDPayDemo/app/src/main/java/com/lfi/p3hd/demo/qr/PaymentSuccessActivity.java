package com.lfi.p3hd.demo.qr;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.lfi.p3hd.demo.BaseAppCompatActivity;
import com.lfi.p3hd.demo.MyApplication;
import com.lfi.p3hd.demo.R;
import com.lfi.p3hd.demo.utils.PreferencesUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class PaymentSuccessActivity extends BaseAppCompatActivity {
    public static final String EXTRA_AMOUNT_AED  = "amountAed";
    public static final String EXTRA_REQUEST_ID  = "requestId";
    public static final String EXTRA_EMV_PAYLOAD = "emvPayload";  // kept for caller compatibility

    private static final int PAPER_WIDTH_DOTS = 384;
    private static final long MIN_PRINT_ANIMATION_MS = 4200L;
    private static final long MAX_PRINT_ANIMATION_MS = 8500L;

    private String amountAed;
    private String requestId;
    private String dateTimeStr;

    private View layoutSuccessSummary;
    private View layoutPrintedSuccess;
    private View cardReceipt;
    private View receiptContent;
    private ProgressBar progressDetails;
    private ScrollView scrollReceipt;
    private MaterialButton primaryButton;
    private MaterialButton returnButton;

    private ReceiptDetails receiptDetails;
    private boolean detailsLoading;
    private boolean printInProgress;
    private boolean printJobDone;
    private boolean printJobSucceeded;
    private boolean printAnimationDone;
    private boolean printAnimationCancelled;
    private ValueAnimator receiptAnimator;

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

        layoutSuccessSummary = findViewById(R.id.layout_success_summary);
        layoutPrintedSuccess = findViewById(R.id.layout_printed_success);
        cardReceipt = findViewById(R.id.card_receipt);
        receiptContent = findViewById(R.id.layout_receipt_content);
        progressDetails = findViewById(R.id.progress_details);
        scrollReceipt = findViewById(R.id.scroll_receipt);
        primaryButton = findViewById(R.id.btn_primary);
        returnButton = findViewById(R.id.btn_return);

        ((TextView) findViewById(R.id.tv_amount)).setText("AED " + safeText(amountAed));
        ((TextView) findViewById(R.id.tv_ref)).setText("Ref: " + getShortRef(requestId));
        ((TextView) findViewById(R.id.tv_date_time)).setText(dateTimeStr);

        primaryButton.setOnClickListener(v -> {
            if (receiptDetails == null) {
                loadTransactionDetails();
            } else {
                printReceiptWithAnimation();
            }
        });
        returnButton.setOnClickListener(v -> finish());
    }

    private void loadTransactionDetails() {
        if (detailsLoading) return;

        detailsLoading = true;
        progressDetails.setVisibility(View.VISIBLE);
        primaryButton.setEnabled(false);
        primaryButton.setText(R.string.payment_success_loading_details);

        new Thread(() -> {
            try {
                JSONObject tx = fetchTransactionDetails();
                ReceiptDetails details = buildReceiptDetails(tx);
                runOnUiThread(() -> showReceiptDetails(details));
            } catch (Exception e) {
                Log.e(TAG, "loadTransactionDetails failed", e);
                runOnUiThread(() -> {
                    detailsLoading = false;
                    progressDetails.setVisibility(View.GONE);
                    primaryButton.setEnabled(true);
                    primaryButton.setText(R.string.payment_success_view_details);
                    showToast(R.string.payment_success_details_failed);
                });
            }
        }).start();
    }

    private JSONObject fetchTransactionDetails() throws Exception {
        HttpUrl baseUrl = HttpUrl.parse(QRConfig.getBaseUrl() + QRConfig.QR_STATUS_ENDPOINT);
        if (baseUrl == null) {
            throw new IOException("Invalid transaction URL");
        }

        HttpUrl url = baseUrl.newBuilder()
            .addQueryParameter("requestId", requestId)
            .build();

        Request.Builder reqBuilder = new Request.Builder()
            .url(url)
            .addHeader("X-LFI-ID", QRConfig.getXLfiId())
            .get();
        String apiKey = PreferencesUtil.getLfiApiKey();
        if (!apiKey.isEmpty()) reqBuilder.addHeader("X-LFI-API-KEY", apiKey);

        try (Response response = httpClient.newCall(reqBuilder.build()).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            Log.d(TAG, "fetchTransactionDetails [" + response.code() + "]: " + body);
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code());
            }

            JSONObject json = new JSONObject(body);
            JSONObject tx = extractTransaction(json);
            if (tx == null) {
                throw new IOException("No transaction details found");
            }
            return tx;
        }
    }

    private JSONObject extractTransaction(JSONObject json) {
        JSONArray transactions = json.optJSONArray("transactions");
        if (transactions != null && transactions.length() > 0) {
            return transactions.optJSONObject(0);
        }

        JSONObject transaction = json.optJSONObject("transaction");
        if (transaction != null) {
            return transaction;
        }

        JSONObject data = json.optJSONObject("data");
        if (data != null) {
            transactions = data.optJSONArray("transactions");
            if (transactions != null && transactions.length() > 0) {
                return transactions.optJSONObject(0);
            }
            transaction = data.optJSONObject("transaction");
            if (transaction != null) {
                return transaction;
            }
            if (data.has("transactionStatus") || data.has("amount")) {
                return data;
            }
        }

        if (json.has("transactionStatus") || json.has("amount")) {
            return json;
        }
        return null;
    }

    private ReceiptDetails buildReceiptDetails(JSONObject tx) {
        String currency = firstNonEmpty(tx, "currency");
        if (currency.isEmpty()) currency = QRConfig.CURRENCY;

        String transactionId = firstNonEmpty(tx,
            "transactionId", "transactionID", "txnId", "id", "referenceNumber");
        String apiRequestId = firstNonEmpty(tx, "requestId", "requestID");
        if (apiRequestId.isEmpty()) apiRequestId = requestId;

        String status = firstNonEmpty(tx, "transactionStatus", "status");
        if (status.isEmpty()) status = "SUCCESS";

        String date = firstNonEmpty(tx,
            "createdAt", "updatedAt", "transactionDate", "timestamp", "dateTime");
        date = formatDisplayDate(date);
        if (date.isEmpty()) date = dateTimeStr;

        String payer = firstNonEmpty(tx,
            "senderWalletId", "payerWalletId", "payerWallet", "sourceWalletId", "fromWalletId");
        String terminal = firstNonEmpty(tx, "terminalId", "terminalID");
        String merchant = firstNonEmpty(tx,
            "merchantName", "merchantId", "merchantWalletId", "receiverWalletId", "payeeWalletId", "walletId");

        return new ReceiptDetails(
            currency + " " + resolveAmount(tx),
            status,
            transactionId,
            apiRequestId,
            date,
            currency,
            payer,
            terminal,
            merchant
        );
    }

    private void showReceiptDetails(ReceiptDetails details) {
        detailsLoading = false;
        receiptDetails = details;

        progressDetails.setVisibility(View.GONE);
        layoutSuccessSummary.setVisibility(View.GONE);
        layoutPrintedSuccess.setVisibility(View.GONE);
        cardReceipt.setAlpha(1f);
        cardReceipt.setTranslationY(0f);
        cardReceipt.setVisibility(View.VISIBLE);

        ((TextView) findViewById(R.id.tv_receipt_amount)).setText(details.amount);
        ((TextView) findViewById(R.id.tv_receipt_status)).setText(details.status);
        setReceiptRow(R.id.row_transaction_id, R.id.tv_receipt_transaction_id, details.transactionId);
        setReceiptRow(R.id.row_request_id, R.id.tv_receipt_request_id, details.requestId);
        setReceiptRow(R.id.row_date, R.id.tv_receipt_date, details.date);
        setReceiptRow(R.id.row_currency, R.id.tv_receipt_currency, details.currency);
        setReceiptRow(R.id.row_payer, R.id.tv_receipt_payer, details.payer);
        setReceiptRow(R.id.row_terminal, R.id.tv_receipt_terminal, details.terminal);
        setReceiptRow(R.id.row_merchant, R.id.tv_receipt_merchant, details.merchant);

        primaryButton.setEnabled(true);
        primaryButton.setText(R.string.payment_success_print);
        scrollReceipt.post(() -> scrollReceipt.smoothScrollTo(0, 0));
    }

    private void setReceiptRow(int rowId, int valueId, String value) {
        View row = findViewById(rowId);
        TextView valueView = findViewById(valueId);
        if (isEmpty(value)) {
            row.setVisibility(View.GONE);
        } else {
            row.setVisibility(View.VISIBLE);
            valueView.setText(value);
        }
    }

    private void printReceiptWithAnimation() {
        if (printInProgress || receiptDetails == null) return;

        Bitmap receiptBitmap = buildReceiptBitmapFromView();
        if (receiptBitmap == null) {
            showToast("Failed to render receipt");
            return;
        }

        printInProgress = true;
        printJobDone = false;
        printJobSucceeded = false;
        printAnimationDone = false;
        primaryButton.setEnabled(false);
        primaryButton.setText(R.string.payment_success_printing);
        returnButton.setEnabled(false);
        layoutPrintedSuccess.setVisibility(View.GONE);

        startReceiptPrintAnimation(receiptBitmap.getHeight());
        new Thread(() -> printReceiptBitmap(receiptBitmap)).start();
    }

    private Bitmap buildReceiptBitmapFromView() {
        if (receiptContent.getWidth() <= 0 || receiptContent.getHeight() <= 0) {
            return null;
        }

        Bitmap source = Bitmap.createBitmap(
            receiptContent.getWidth(),
            receiptContent.getHeight(),
            Bitmap.Config.ARGB_8888
        );
        Canvas canvas = new Canvas(source);
        canvas.drawColor(Color.WHITE);
        receiptContent.draw(canvas);

        if (source.getWidth() == PAPER_WIDTH_DOTS) {
            return source;
        }

        int scaledHeight = Math.max(
            1,
            Math.round(source.getHeight() * (PAPER_WIDTH_DOTS / (float) source.getWidth()))
        );
        Bitmap scaled = Bitmap.createScaledBitmap(source, PAPER_WIDTH_DOTS, scaledHeight, true);
        source.recycle();
        return scaled;
    }

    private void startReceiptPrintAnimation(int bitmapHeight) {
        if (receiptAnimator != null) {
            receiptAnimator.cancel();
        }

        printAnimationCancelled = false;
        scrollReceipt.smoothScrollTo(0, 0);
        cardReceipt.setVisibility(View.VISIBLE);
        cardReceipt.setAlpha(1f);
        cardReceipt.setTranslationY(0f);

        float distance = Math.max(cardReceipt.getHeight(), scrollReceipt.getHeight()) + 48f;
        receiptAnimator = ValueAnimator.ofFloat(0f, 1f);
        receiptAnimator.setDuration(calculatePrintAnimationDurationMs(bitmapHeight));
        receiptAnimator.setInterpolator(new LinearInterpolator());
        receiptAnimator.addUpdateListener(animation -> {
            float progress = (float) animation.getAnimatedValue();
            cardReceipt.setTranslationY(-distance * progress);

            float fadeStart = 0.9f;
            if (progress <= fadeStart) {
                cardReceipt.setAlpha(1f);
            } else {
                cardReceipt.setAlpha(Math.max(0f, 1f - ((progress - fadeStart) / (1f - fadeStart))));
            }
        });
        receiptAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(Animator animation) {
                printAnimationCancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (printAnimationCancelled) return;
                printAnimationDone = true;
                maybeFinishPrintFlow();
            }
        });
        receiptAnimator.start();
    }

    private long calculatePrintAnimationDurationMs(int bitmapHeight) {
        long heightBasedDuration = 1800L + Math.round(bitmapHeight * 5.5f);
        return Math.max(MIN_PRINT_ANIMATION_MS, Math.min(MAX_PRINT_ANIMATION_MS, heightBasedDuration));
    }

    private void printReceiptBitmap(Bitmap receiptBmp) {
        boolean printed = false;
        try {
            com.sunmi.pay.hardware.aidlv2.print.PrinterOptV2 printer = MyApplication.app.printerOptV2;
            if (printer == null) {
                showToast("Printer not connected");
                return;
            }

            int openCode = printer.printOpen();
            if (openCode < 0) {
                showToast("Printer error: " + openCode);
                return;
            }

            try {
                byte[] rowData = new byte[PAPER_WIDTH_DOTS / 8];
                for (int y = 0; y < receiptBmp.getHeight(); y++) {
                    for (int byteIdx = 0; byteIdx < rowData.length; byteIdx++) {
                        byte b = 0;
                        for (int bit = 0; bit < 8; bit++) {
                            int x = byteIdx * 8 + bit;
                            if (shouldPrintPixel(receiptBmp.getPixel(x, y))) {
                                b |= (byte) (0x80 >> bit);
                            }
                        }
                        rowData[byteIdx] = b;
                    }
                    printer.printPointLine(rowData);
                }
                printer.printFeedPaper(60);
                printed = true;
            } finally {
                printer.printClose();
            }
        } catch (Exception e) {
            Log.e(TAG, "printReceiptBitmap error", e);
            showToast("Print failed: " + e.getMessage());
        } finally {
            receiptBmp.recycle();
            boolean success = printed;
            runOnUiThread(() -> onPrintJobFinished(success));
        }
    }

    private void onPrintJobFinished(boolean success) {
        printJobDone = true;
        printJobSucceeded = success;
        maybeFinishPrintFlow();
    }

    private void maybeFinishPrintFlow() {
        if (!printInProgress || !printJobDone) return;

        if (!printJobSucceeded) {
            restoreReceiptAfterPrintFailure();
            return;
        }

        if (printAnimationDone) {
            showPrintedSuccessState();
        }
    }

    private void restoreReceiptAfterPrintFailure() {
        if (receiptAnimator != null) {
            receiptAnimator.cancel();
        }
        printInProgress = false;
        printJobDone = false;
        printAnimationDone = false;
        cardReceipt.setVisibility(View.VISIBLE);
        cardReceipt.setTranslationY(0f);
        cardReceipt.setAlpha(1f);
        primaryButton.setEnabled(true);
        primaryButton.setText(R.string.payment_success_print);
        returnButton.setEnabled(true);
    }

    private void showPrintedSuccessState() {
        printInProgress = false;
        cardReceipt.setVisibility(View.GONE);
        cardReceipt.setTranslationY(0f);
        cardReceipt.setAlpha(1f);
        layoutPrintedSuccess.setAlpha(0f);
        layoutPrintedSuccess.setScaleX(0.96f);
        layoutPrintedSuccess.setScaleY(0.96f);
        layoutPrintedSuccess.setVisibility(View.VISIBLE);
        layoutPrintedSuccess.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(220L)
            .start();

        primaryButton.setEnabled(false);
        primaryButton.setText(R.string.payment_success_receipt_printed);
        returnButton.setEnabled(true);
        returnButton.setText(R.string.payment_success_return_qr_pay);
        scrollReceipt.post(() -> scrollReceipt.smoothScrollTo(0, 0));
    }

    private boolean shouldPrintPixel(int pixel) {
        if (Color.alpha(pixel) < 128) return false;
        int luminance = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3;
        return luminance < 210;
    }

    private String resolveAmount(JSONObject tx) {
        Object rawAmount = tx.opt("amount");
        if (rawAmount instanceof Number) {
            return String.format(Locale.US, "%.2f", ((Number) rawAmount).doubleValue() / 100.0);
        }

        String amount = rawAmount == null ? "" : String.valueOf(rawAmount).trim();
        if (!amount.isEmpty() && !"null".equalsIgnoreCase(amount)) {
            try {
                if (amount.contains(".")) {
                    return String.format(Locale.US, "%.2f", Double.parseDouble(amount));
                }
                return String.format(Locale.US, "%.2f", Long.parseLong(amount) / 100.0);
            } catch (NumberFormatException ignored) {
                return amount;
            }
        }

        try {
            return String.format(Locale.US, "%.2f", Double.parseDouble(safeText(amountAed)));
        } catch (NumberFormatException ignored) {
            return safeText(amountAed);
        }
    }

    private String formatDisplayDate(String value) {
        if (isEmpty(value)) return "";
        String date = value.replace("T", " ").replace("Z", "").trim();
        int millisIndex = date.indexOf('.');
        if (millisIndex > 0) {
            date = date.substring(0, millisIndex);
        }
        return date.length() > 16 ? date.substring(0, 16) : date;
    }

    private String firstNonEmpty(JSONObject json, String... keys) {
        for (String key : keys) {
            String value = json.optString(key, "").trim();
            if (!isEmpty(value)) return value;
        }
        return "";
    }

    private String getShortRef(String value) {
        if (isEmpty(value)) return "";
        return value.length() >= 8 ? value.substring(value.length() - 8) : value;
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }

    private boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty() || "null".equalsIgnoreCase(value.trim());
    }

    private static class ReceiptDetails {
        final String amount;
        final String status;
        final String transactionId;
        final String requestId;
        final String date;
        final String currency;
        final String payer;
        final String terminal;
        final String merchant;

        ReceiptDetails(
            String amount,
            String status,
            String transactionId,
            String requestId,
            String date,
            String currency,
            String payer,
            String terminal,
            String merchant
        ) {
            this.amount = amount;
            this.status = status;
            this.transactionId = transactionId;
            this.requestId = requestId;
            this.date = date;
            this.currency = currency;
            this.payer = payer;
            this.terminal = terminal;
            this.merchant = merchant;
        }
    }
}
