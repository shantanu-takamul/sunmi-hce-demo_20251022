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
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

    // GAP 5: one idempotency key per original transaction ID, reused on retry to prevent double-refund
    private final Map<String, String> idempotencyKeys = new HashMap<>();

    // Tracks transactions where a refund is in-flight (CONCURRENT_REQUEST / KEY_CONFLICT)
    // so the UI can show "Refund Pending" badge even before the history API reflects it.
    private final Set<String> pendingRefunds = Collections.synchronizedSet(new HashSet<>());

    private String getOrCreateIdempotencyKey(String txId) {
        return idempotencyKeys.computeIfAbsent(txId, k -> UUID.randomUUID().toString());
    }

    /** Maps raw server error codes and fields to a single human-readable string. */
    private String friendlyError(JSONObject json, int httpCode) {
        String errorCode    = json.optString("errorCode", "");
        String message      = json.optString("message", "");
        String failureCode  = json.optString("failureCode", "");
        String failureReason = json.optString("failureReason", "");
        JSONObject details  = json.optJSONObject("details");

        switch (errorCode) {
            case "IDEMPOTENCY_KEY_CONFLICT":
                return "A refund has already been queued for this transaction — refresh to see the status.";
            case "IDEMPOTENCY_CONCURRENT_REQUEST":
                return "A refund is already in progress — refresh the list in a moment to see the outcome.";
            case "CANCEL_WINDOW_EXPIRED":
                return "Cancel window expired — voids are only allowed within 60 minutes of the original payment.";
            case "VALIDATION_ERROR":
                if (details != null && details.length() > 0) {
                    return "Validation error: " + details.toString();
                }
                return "Validation error — check refund details and try again.";
            case "TRANSACTION_NOT_FOUND":
                return "Transaction not found on the server.";
            case "REFUND_NOT_ALLOWED":
                return "Refund is not allowed for this transaction type.";
            case "INSUFFICIENT_FUNDS":
                return "Insufficient funds in the merchant wallet to process refund.";
            case "SANCTIONS_SCREENING_FAILED":
                return "Refund blocked — sanctions screening did not pass.";
            case "AUTH_TOKEN_MISSING":
                return "API key missing — go to Settings to configure it.";
            case "AUTH_TOKEN_INVALID":
                return "API key invalid or expired — go to Settings to refresh it.";
            case "DATE_RANGE_TOO_WIDE":
                return "Date range too wide — maximum allowed is 90 days.";
            default:
                if (!failureReason.isEmpty()) return failureReason;
                if (!failureCode.isEmpty())   return failureCode;
                if (!message.isEmpty())       return message;
                return "Unexpected error (HTTP " + httpCode + ")";
        }
    }

    /** Carries both the user-facing message and whether the refund is now in a pending state. */
    private static class RefundResult {
        final String  message;
        final boolean isPending;   // true → show "Refund Pending" badge on this transaction
        RefundResult(String message, boolean isPending) {
            this.message   = message;
            this.isPending = isPending;
        }
    }

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
            tx.requestId              = obj.optString("requestId", "");
            tx.createdAt              = obj.optString("createdAt", "");
            tx.originalTransactionId  = obj.optString("originalTransactionId", ""); // GAP 6
            tx.reference              = obj.optString("reference", "");             // GAP 6
            tx.updatedAt              = obj.optString("updatedAt", "");             // GAP 6
            tx.failureCode            = obj.optString("failureCode", "");           // GAP 6
            tx.failureReason          = obj.optString("failureReason", "");         // GAP 6

            // GAP 2: classify each refund entry — SUCCESS blocks retry; FAILED allows retry;
            // anything else (PENDING / SCREENING_*) marks the transaction as "refund in progress"
            JSONArray refunds = obj.optJSONArray("refunds");
            tx.hasRefunds = refunds != null && refunds.length() > 0;
            if (refunds != null) {
                for (int j = 0; j < refunds.length(); j++) {
                    JSONObject r = refunds.optJSONObject(j);
                    if (r == null) continue;
                    String refundStatus = r.optString("refundStatus", "");
                    if ("SUCCESS".equals(refundStatus)) {
                        tx.hasSuccessRefund = true;
                        tx.refundTxId = r.optString("refundTransactionId", "");
                    } else if (!"FAILED".equals(refundStatus) && !refundStatus.isEmpty()) {
                        // PENDING, SCREENING_REQUESTED, CBDC_PENDING, etc. — in progress
                        tx.hasPendingRefund = true;
                    }
                }
            }

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
        // Apply in-session pending state: if postRefund returned CONCURRENT or CONFLICT this
        // session, mark the transaction pending even if the history API doesn't reflect it yet.
        for (Transaction tx : transactions) {
            if (pendingRefunds.contains(tx.transactionId) && !tx.hasSuccessRefund) {
                tx.hasPendingRefund = true;
            }
            // Once the API shows a success refund, remove from pending set — it's settled.
            if (tx.hasSuccessRefund) {
                pendingRefunds.remove(tx.transactionId);
            }
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
                + (txn.reference.isEmpty() ? "" : "\nReference: " + txn.reference)
                + (txn.failureCode.isEmpty() ? "" : "\nFailure Code: " + txn.failureCode)
                + (txn.failureReason.isEmpty() ? "" : "\nFailure Reason: " + txn.failureReason)
                + (txn.hasSuccessRefund ? "\n\nRefunded — ID:\n" + txn.refundTxId : "");

        new AlertDialog.Builder(this)
                .setTitle("Transaction Details")
                .setMessage(message)
                .setPositiveButton("Close", null)
                .show();
    }

    private void showRefundDialog(Transaction txn) {
        if (txn.hasSuccessRefund) {
            showToast("This transaction has already been refunded");
            return;
        }
        if (txn.hasPendingRefund) {
            showToast("A refund is already in progress — refresh to check the status");
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
                RefundResult result = postRefund(txn);
                if (!isFinishing()) {
                    runOnUiThread(() -> {
                        if (result.isPending) pendingRefunds.add(txn.transactionId);
                        showToast(result.message);
                        loadTransactions();
                    });
                }
            } catch (SocketTimeoutException e) {
                // Sanctions screening can outlast OkHttp's read window even at 90 s.
                // Mark pending so the badge appears immediately; server may still complete.
                Log.e(TAG, "executeRefund timed out", e);
                if (!isFinishing()) {
                    runOnUiThread(() -> {
                        pendingRefunds.add(txn.transactionId);
                        showToast("Refund is taking longer than expected — " +
                                "it's queued on the server. Refresh to check the status.");
                        loadTransactions();
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "executeRefund failed", e);
                if (!isFinishing()) {
                    runOnUiThread(() -> showToast(e.getMessage()));
                }
            }
        }).start();
    }

    private RefundResult postRefund(Transaction txn) throws Exception {
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
                // GAP 5: reuse the same key on retry so server deduplicates and prevents double-refund
                .addHeader("X-Idempotency-Key", getOrCreateIdempotencyKey(txn.transactionId))
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

            JSONObject json = responseBody.isEmpty() ? new JSONObject() : new JSONObject(responseBody);
            String errorCode = json.optString("errorCode", "");

            // In-flight / queued: mark as pending so the badge appears immediately
            if ("IDEMPOTENCY_CONCURRENT_REQUEST".equals(errorCode)
                    || "IDEMPOTENCY_KEY_CONFLICT".equals(errorCode)) {
                return new RefundResult(friendlyError(json, response.code()), true);
            }

            // Any other non-success error code → human-readable failure, allow retry
            if (!errorCode.isEmpty() && !response.isSuccessful()) {
                throw new Exception(friendlyError(json, response.code()));
            }

            String status     = json.optString("status", "");
            // GAP 3: capture the new refund transaction ID for reference
            String refundTxId = json.optString("transactionId", "");

            if ("SUCCESS".equals(status)) {
                // GAP 5: clear the stored key — this transaction is fully settled
                idempotencyKeys.remove(txn.transactionId);
                pendingRefunds.remove(txn.transactionId);
                String idSuffix = refundTxId.length() > 8
                        ? "…" + refundTxId.substring(refundTxId.length() - 8)
                        : refundTxId;
                return new RefundResult(
                        "Refund successful" + (idSuffix.isEmpty() ? "" : "\nRefund ID: " + idSuffix),
                        false);
            }

            if ("FAILED".equals(status)) {
                throw new Exception(friendlyError(json, response.code()));
            }

            // Intermediate status (PENDING, SCREENING_*) — mark as pending
            return new RefundResult(
                    "Refund submitted — status: " + status + ". Refresh to see the outcome.",
                    true);
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
        String transactionId         = "";
        String transactionType       = "";
        String transactionStatus     = "";
        String payerWalletId         = "";
        String payeeWalletId         = "";
        long   amountFils            = 0;
        String currency              = "AED";
        String requestId             = "";
        String createdAt             = "";
        // GAP 6: fields present in history response but previously unparsed
        String originalTransactionId = "";
        String reference             = "";
        String updatedAt             = "";
        String failureCode           = "";
        String failureReason         = "";
        // GAP 2: track whether any refund attempt succeeded (vs just attempted)
        boolean hasRefunds           = false;
        boolean hasSuccessRefund     = false;
        boolean hasPendingRefund     = false;  // refund in-flight (screening / CBDC pending)
        // GAP 3: refund transaction ID from a successful refund, for display/lookup
        String  refundTxId           = "";
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

            // GAP 4: show type so merchant can distinguish payments from refund/cancel rows
            ((TextView) convertView.findViewById(R.id.tv_tx_type))
                    .setText(txn.transactionType);

            convertView.findViewById(R.id.btn_view)
                    .setOnClickListener(v -> showDetailDialog(txn));

            // GAP 4: only PAY_TO_MERCHANT rows can be refunded; hide button for refund/cancel rows
            View btnRefund = convertView.findViewById(R.id.btn_refund);
            boolean isMerchantPayment = "PAY_TO_MERCHANT".equals(txn.transactionType);
            // Disable if: not a payment, not successful, already successfully refunded, or pending
            boolean canRefund = isMerchantPayment
                    && "SUCCESS".equals(txn.transactionStatus)
                    && !txn.hasSuccessRefund
                    && !txn.hasPendingRefund;
            btnRefund.setVisibility(isMerchantPayment ? View.VISIBLE : View.GONE);
            btnRefund.setEnabled(canRefund);
            btnRefund.setAlpha(canRefund ? 1f : 0.35f);
            btnRefund.setOnClickListener(v -> showRefundDialog(txn));

            // "Refunded" badge — only when a SUCCESS refund exists
            TextView tvRefundedBadge = convertView.findViewById(R.id.tv_refunded_badge);
            tvRefundedBadge.setVisibility(txn.hasSuccessRefund ? View.VISIBLE : View.GONE);

            // "Refund Pending" badge — shown when refund is in-flight (sanctions screening etc.)
            TextView tvRefundPendingBadge = convertView.findViewById(R.id.tv_refund_pending_badge);
            tvRefundPendingBadge.setVisibility(
                    (txn.hasPendingRefund && !txn.hasSuccessRefund) ? View.VISIBLE : View.GONE);

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
