package com.lfi.p3hd.demo.qr;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.lfi.p3hd.demo.BaseAppCompatActivity;
import com.lfi.p3hd.demo.R;
import com.lfi.p3hd.demo.utils.PreferencesUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class TransactionHistoryActivity extends BaseAppCompatActivity {

    private ListView listTransactions;
    private View layoutLoading;
    private View layoutEmpty;
    private final OkHttpClient httpClient = new OkHttpClient();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_transaction_history);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        listTransactions = findViewById(R.id.list_transactions);
        layoutLoading = findViewById(R.id.layout_loading);
        layoutEmpty = findViewById(R.id.layout_empty);

        loadTransactions();
    }

    private void loadTransactions() {
        layoutLoading.setVisibility(View.VISIBLE);
        layoutEmpty.setVisibility(View.GONE);
        listTransactions.setVisibility(View.GONE);

        new Thread(() -> {
            try {
                List<Transaction> transactions = fetchTransactions();
                if (!isFinishing()) {
                    runOnUiThread(() -> showTransactions(transactions));
                }
            } catch (Exception e) {
                Log.e(TAG, "loadTransactions failed", e);
                if (!isFinishing()) {
                    runOnUiThread(() -> {
                        layoutLoading.setVisibility(View.GONE);
                        layoutEmpty.setVisibility(View.VISIBLE);
                        showToast("Failed to load: " + e.getMessage());
                    });
                }
            }
        }).start();
    }

    private List<Transaction> fetchTransactions() throws Exception {
        String walletId = PreferencesUtil.getWalletId();
        if (walletId.isEmpty()) walletId = QRConfig.getDefaultWalletId();

        SimpleDateFormat isoFmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        isoFmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        Date now = new Date();
        Date thirtyDaysAgo = new Date(now.getTime() - 30L * 24 * 60 * 60 * 1000);

        HttpUrl url = HttpUrl.parse(QRConfig.getBaseUrl() + QRConfig.QR_STATUS_ENDPOINT)
                .newBuilder()
                .addQueryParameter("walletId", walletId)
                .addQueryParameter("startDate", isoFmt.format(thirtyDaysAgo))
                .addQueryParameter("endDate", isoFmt.format(now))
                .addQueryParameter("limit", "50")
                .build();

        Request.Builder reqBuilder = new Request.Builder()
                .url(url)
                .addHeader("X-LFI-ID", QRConfig.getXLfiId())
                .get();
        String apiKey = PreferencesUtil.getLfiApiKey();
        if (!apiKey.isEmpty()) reqBuilder.addHeader("X-LFI-API-KEY", apiKey);

        try (Response response = httpClient.newCall(reqBuilder.build()).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            Log.d(TAG, "fetchTransactions [" + response.code() + "]: "
                    + body.substring(0, Math.min(300, body.length())));
            if (!response.isSuccessful()) {
                throw new Exception("HTTP " + response.code());
            }
            return parseTransactions(new JSONObject(body));
        }
    }

    private List<Transaction> parseTransactions(JSONObject json) {
        List<Transaction> list = new ArrayList<>();
        JSONArray arr = json.optJSONArray("transactions");
        if (arr == null) return list;

        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.optJSONObject(i);
            if (obj == null) continue;

            Transaction tx = new Transaction();
            tx.transactionId     = obj.optString("transactionId", "");
            tx.transactionType   = obj.optString("transactionType", "");
            tx.transactionStatus = obj.optString("transactionStatus", "");
            tx.payerWalletId     = obj.optString("payerWalletId", "");
            tx.payeeWalletId     = obj.optString("payeeWalletId", "");
            tx.amountFils        = obj.optLong("amount", 0);
            tx.currency          = obj.optString("currency", QRConfig.CURRENCY);
            tx.requestId         = obj.optString("requestId", "");
            tx.createdAt         = obj.optString("createdAt", "");

            JSONArray refunds = obj.optJSONArray("refunds");
            tx.hasRefunds = refunds != null && refunds.length() > 0;

            list.add(tx);
        }
        return list;
    }

    private void showTransactions(List<Transaction> transactions) {
        layoutLoading.setVisibility(View.GONE);
        if (transactions.isEmpty()) {
            layoutEmpty.setVisibility(View.VISIBLE);
            listTransactions.setVisibility(View.GONE);
            return;
        }
        layoutEmpty.setVisibility(View.GONE);
        listTransactions.setVisibility(View.VISIBLE);
        listTransactions.setAdapter(new TransactionAdapter(this, transactions));
    }

    private void showDetailDialog(Transaction txn) {
        String amountAed = String.format(Locale.US, "%.2f", txn.amountFils / 100.0);
        String date = formatDisplayDate(txn.createdAt);

        String message = "Transaction ID:\n" + txn.transactionId
                + "\n\nStatus: " + txn.transactionStatus
                + "\nType: " + txn.transactionType
                + "\nAmount: " + txn.currency + " " + amountAed
                + "\nPayer: " + (txn.payerWalletId.isEmpty() ? "—" : txn.payerWalletId)
                + "\nPayee: " + (txn.payeeWalletId.isEmpty() ? "—" : txn.payeeWalletId)
                + "\nDate: " + (date.isEmpty() ? "—" : date)
                + (txn.requestId.isEmpty() ? "" : "\nRequest ID:\n" + txn.requestId)
                + (txn.hasRefunds ? "\n\n[Already refunded]" : "");

        new AlertDialog.Builder(this)
                .setTitle("Transaction Details")
                .setMessage(message)
                .setPositiveButton("Close", null)
                .show();
    }

    private void showRefundDialog(Transaction txn) {
        if (txn.hasRefunds) {
            showToast("This transaction has already been refunded");
            return;
        }
        if (!"SUCCESS".equals(txn.transactionStatus)) {
            showToast("Only successful transactions can be refunded");
            return;
        }
        if (txn.amountFils <= 0) {
            showToast("Cannot refund: amount is zero");
            return;
        }

        String amountAed = String.format(Locale.US, "%.2f", txn.amountFils / 100.0);
        String shortId = txn.transactionId.length() > 8
                ? "..." + txn.transactionId.substring(txn.transactionId.length() - 8)
                : txn.transactionId;

        String message = "Refund " + txn.currency + " " + amountAed + " to customer?\n\n"
                + "Transaction: " + shortId + "\n\nThis action cannot be undone.";

        new AlertDialog.Builder(this)
                .setTitle("Confirm Refund")
                .setMessage(message)
                .setPositiveButton("Refund", (d, w) -> executeRefund(txn))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void executeRefund(Transaction txn) {
        showToast("Processing refund…");
        new Thread(() -> {
            try {
                String result = postRefund(txn);
                if (!isFinishing()) {
                    runOnUiThread(() -> {
                        showToast(result);
                        loadTransactions();
                    });
                }
            } catch (SocketTimeoutException e) {
                // Sanctions screening can outlast OkHttp's read window even at 90 s.
                // The server may still complete the refund — do not retry automatically.
                Log.e(TAG, "executeRefund timed out", e);
                if (!isFinishing()) {
                    runOnUiThread(() -> showToast(
                            "Refund is taking longer than expected. " +
                            "Refresh the list in a moment — it may still complete."));
                }
            } catch (Exception e) {
                Log.e(TAG, "executeRefund failed", e);
                if (!isFinishing()) {
                    runOnUiThread(() -> showToast("Refund failed: " + e.getMessage()));
                }
            }
        }).start();
    }

    private String postRefund(Transaction txn) throws Exception {
        String url = QRConfig.getBaseUrl() + QRConfig.RETURN_ENDPOINT + txn.transactionId;

        JSONObject body = new JSONObject();
        body.put("originalTransactionId", txn.transactionId);
        // In a refund the merchant (original payee) sends money back to the customer (original payer)
        body.put("payerWalletId", txn.payeeWalletId);
        body.put("payeeWalletId", txn.payerWalletId);
        body.put("type", "REFUND");
        body.put("amount", txn.amountFils);
        body.put("reference", "REFUND-" + txn.transactionId.substring(
                Math.max(0, txn.transactionId.length() - 8)));

        Request.Builder reqBuilder = new Request.Builder()
                .url(url)
                .addHeader("X-LFI-ID", QRConfig.getXLfiId())
                .addHeader("Content-Type", "application/json")
                .addHeader("X-Idempotency-Key", UUID.randomUUID().toString())
                .post(RequestBody.create(MediaType.parse("application/json"), body.toString()));

        String apiKey = PreferencesUtil.getLfiApiKey();
        if (!apiKey.isEmpty()) reqBuilder.addHeader("X-LFI-API-KEY", apiKey);

        // REFUND requires server-side sanctions screening which can take 30–60 s.
        // Use a dedicated client with a longer read timeout so we don't time out early.
        OkHttpClient refundClient = httpClient.newBuilder()
                .readTimeout(90, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();

        try (Response response = refundClient.newCall(reqBuilder.build()).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            Log.d(TAG, "postRefund [" + response.code() + "]: " + responseBody);

            JSONObject json = new JSONObject(responseBody);

            // 409 means the server already has a live or completed request for this transactionId
            String errorCode = json.optString("errorCode", "");
            if ("IDEMPOTENCY_CONCURRENT_REQUEST".equals(errorCode)) {
                return "A refund is already in progress for this transaction. " +
                       "Refresh the list in a moment to see the outcome.";
            }

            String status  = json.optString("status", "");
            String message = json.optString("message", "");

            if ("SUCCESS".equals(status)) {
                return "Refund successful";
            } else if (!response.isSuccessful()) {
                throw new Exception("HTTP " + response.code()
                        + (message.isEmpty() ? "" : ": " + message));
            } else if (!message.isEmpty()) {
                return "Refund " + status + ": " + message;
            } else {
                return "Refund status: " + (status.isEmpty() ? "HTTP " + response.code() : status);
            }
        }
    }

    private String formatDisplayDate(String value) {
        if (value == null || value.isEmpty()) return "";
        String date = value.replace("T", " ").replace("Z", "").trim();
        int dotIdx = date.indexOf('.');
        if (dotIdx > 0) date = date.substring(0, dotIdx);
        return date.length() > 16 ? date.substring(0, 16) : date;
    }

    // --- Data model ---

    static class Transaction {
        String transactionId     = "";
        String transactionType   = "";
        String transactionStatus = "";
        String payerWalletId     = "";
        String payeeWalletId     = "";
        long   amountFils        = 0;
        String currency          = "AED";
        String requestId         = "";
        String createdAt         = "";
        boolean hasRefunds       = false;
    }

    // --- List adapter ---

    private class TransactionAdapter extends ArrayAdapter<Transaction> {
        private final LayoutInflater inflater;

        TransactionAdapter(Context ctx, List<Transaction> items) {
            super(ctx, 0, items);
            inflater = LayoutInflater.from(ctx);
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            if (convertView == null) {
                convertView = inflater.inflate(R.layout.item_transaction, parent, false);
            }

            Transaction txn = getItem(position);
            if (txn == null) return convertView;

            String amountAed = String.format(Locale.US, "%.2f", txn.amountFils / 100.0);
            ((TextView) convertView.findViewById(R.id.tv_tx_amount))
                    .setText(txn.currency + " " + amountAed);

            TextView tvStatus = convertView.findViewById(R.id.tv_tx_status);
            tvStatus.setText(txn.transactionStatus);
            tvStatus.setBackgroundColor(statusBgColor(txn.transactionStatus));
            tvStatus.setTextColor(statusTextColor(txn.transactionStatus));

            ((TextView) convertView.findViewById(R.id.tv_tx_date))
                    .setText(formatDisplayDate(txn.createdAt));

            String shortId = txn.transactionId.length() > 10
                    ? "…" + txn.transactionId.substring(txn.transactionId.length() - 10)
                    : txn.transactionId;
            ((TextView) convertView.findViewById(R.id.tv_tx_id)).setText(shortId);

            String payer = txn.payerWalletId.isEmpty() ? "—" : txn.payerWalletId;
            ((TextView) convertView.findViewById(R.id.tv_tx_payer)).setText("From: " + payer);

            convertView.findViewById(R.id.btn_view)
                    .setOnClickListener(v -> showDetailDialog(txn));

            View btnRefund = convertView.findViewById(R.id.btn_refund);
            boolean canRefund = "SUCCESS".equals(txn.transactionStatus) && !txn.hasRefunds;
            btnRefund.setEnabled(canRefund);
            btnRefund.setAlpha(canRefund ? 1f : 0.35f);
            btnRefund.setOnClickListener(v -> showRefundDialog(txn));

            // Show "Refunded" label if already refunded
            TextView tvRefundedBadge = convertView.findViewById(R.id.tv_refunded_badge);
            tvRefundedBadge.setVisibility(txn.hasRefunds ? View.VISIBLE : View.GONE);

            return convertView;
        }

        private int statusBgColor(String status) {
            switch (status) {
                case "SUCCESS": return getColor(R.color.success_soft);
                case "FAILED":  return getColor(R.color.error_soft);
                case "PENDING": return getColor(R.color.warning_soft);
                default:        return getColor(R.color.surface_subtle);
            }
        }

        private int statusTextColor(String status) {
            switch (status) {
                case "SUCCESS": return getColor(R.color.success);
                case "FAILED":  return getColor(R.color.error);
                case "PENDING": return getColor(R.color.warning);
                default:        return getColor(R.color.text_secondary);
            }
        }
    }
}
