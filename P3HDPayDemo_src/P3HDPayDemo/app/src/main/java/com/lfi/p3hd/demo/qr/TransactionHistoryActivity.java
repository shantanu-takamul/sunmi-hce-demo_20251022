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
import com.lfi.p3hd.demo.net.HttpClients;
import com.lfi.p3hd.demo.utils.ApiKeyManager;
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

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class TransactionHistoryActivity extends BaseAppCompatActivity {

    private ListView listTransactions;
    private View layoutLoading;
    private View layoutEmpty;
    /**
     * The shared client for the active environment.
     *
     * Resolved per use rather than held in a field: a field is frozen at
     * construction, so an environment switch never reaches it.
     */
    private OkHttpClient httpClient() {
        return HttpClients.forCurrentEnv();
    }

    // GAP 5: one idempotency key per original transaction ID, reused on retry to prevent double-refund
    private final Map<String, String> idempotencyKeys = new HashMap<>();

    // Tracks transactions where a refund is in-flight (CONCURRENT_REQUEST / KEY_CONFLICT)
    // so the UI can show "Refund Pending" badge even before the history API reflects it.
    private final Set<String> pendingRefunds = Collections.synchronizedSet(new HashSet<>());

    private String getOrCreateIdempotencyKey(String txId) {
        return idempotencyKeys.computeIfAbsent(txId, k -> UUID.randomUUID().toString());
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
        return fetchTransactions(false);
    }

    private List<Transaction> fetchTransactions(boolean isRetry) throws Exception {
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

        int code;
        String body;
        String location;
        try (Response response = httpClient().newCall(reqBuilder.build()).execute()) {
            code = response.code();
            body = response.body() == null ? "" : response.body().string();
            // Read before the response closes: a 3xx names the intermediary that
            // answered, which is the whole diagnosis on an unreachable gateway.
            location = response.header("Location");
            Log.d(TAG, "fetchTransactions [" + code + "]: "
                    + body.substring(0, Math.min(300, body.length())));
        }

        // Stored key expired — mint a fresh one and retry once. Safe to block:
        // this method already runs on its own thread.
        if (code == 401 && !isRetry) {
            Log.w(TAG, "fetchTransactions: 401 — renewing API key");
            if (ApiKeyManager.get().fetchBlocking(30_000)) {
                return fetchTransactions(true);
            }
            throw new Exception("API key expired and could not be renewed");
        }
        if (code < 200 || code >= 300) {
            throw new Exception(QRConfig.errorTextOf(body, code, location));
        }
        return parseTransactions(new JSONObject(body));
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
            // QA nests these in movements[]; demo returns them flat. See QRConfig.
            tx.payerWalletId     = QRConfig.walletFrom(obj, "payerWalletId");
            tx.payeeWalletId     = QRConfig.walletFrom(obj, "payeeWalletId");
            tx.amountFils        = obj.optLong("amount", 0);
            tx.currency          = obj.optString("currency", QRConfig.CURRENCY);
            tx.requestId              = obj.optString("requestId", "");
            tx.createdAt              = obj.optString("createdAt", "");
            tx.originalTransactionId  = obj.optString("originalTransactionId", ""); // GAP 6
            tx.reference              = obj.optString("reference", "");             // GAP 6
            tx.updatedAt              = obj.optString("updatedAt", "");             // GAP 6
            tx.failureCode            = obj.optString("failureCode", "");           // GAP 6
            tx.failureReason          = obj.optString("failureReason", "");         // GAP 6

            // The commission the sale was booked with, so a refund reverses the same
            // split rather than assuming this terminal's zero. Only present once the
            // sale has settled; 0 otherwise. See QRConfig#commissionFrom.
            tx.commissionFils = QRConfig.commissionFrom(obj);

            // GAP 2: classify each return entry — SUCCESS blocks retry; FAILED allows retry;
            // anything else (PENDING / SCREENING_*) marks the transaction as "in progress".
            //
            // The array holds CANCEL rows as well as REFUND rows, and the two have to
            // be told apart: a cancelled sale must not then offer a refund, and it
            // should not read as "Refunded" either.
            JSONArray returns = QRConfig.returnsOf(obj);
            tx.hasRefunds = returns != null && returns.length() > 0;
            if (returns != null) {
                for (int j = 0; j < returns.length(); j++) {
                    JSONObject r = returns.optJSONObject(j);
                    if (r == null) continue;
                    String status     = QRConfig.returnStatusOf(r);
                    boolean isCancel  = ReturnApi.TYPE_CANCEL.equals(r.optString("type", ""));
                    if ("SUCCESS".equals(status)) {
                        if (isCancel) {
                            tx.hasSuccessCancel = true;
                        } else {
                            tx.hasSuccessRefund = true;
                        }
                        tx.refundTxId = QRConfig.returnTxIdOf(r);
                    } else if (!"FAILED".equals(status) && !status.isEmpty()) {
                        // PENDING, SCREENING_REQUESTED, CBDC_PENDING, etc. — in progress.
                        // A refund is asynchronous now, so this is the state every
                        // refund passes through before its callback lands.
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
            if (pendingRefunds.contains(tx.transactionId) && !tx.isReturned()) {
                tx.hasPendingRefund = true;
            }
            // Once the API shows a settled return, remove from pending set.
            if (tx.isReturned()) {
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
                + (txn.commissionFils > 0
                    ? "\nCommission: " + txn.currency + " "
                        + QRConfig.filsToAedText(txn.commissionFils)
                    : "")
                + (txn.hasSuccessCancel ? "\n\nCancelled — ID:\n" + txn.refundTxId
                    : txn.hasSuccessRefund ? "\n\nRefunded — ID:\n" + txn.refundTxId : "");

        new AlertDialog.Builder(this)
                .setTitle("Transaction Details")
                .setMessage(message)
                .setPositiveButton("Close", null)
                .show();
    }

    private void showRefundDialog(Transaction txn) {
        if (txn.hasSuccessCancel) {
            showToast("This payment was cancelled — there is nothing to refund");
            return;
        }
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
        // A refund swaps payer and payee, so both must be known. If the history
        // response carried neither the flat wallets nor a movements[] entry we
        // would post empty wallet ids and the gateway would reject it with a
        // validation error that says nothing useful to the cashier.
        if (txn.payerWalletId.isEmpty() || txn.payeeWalletId.isEmpty()) {
            showToast("Cannot refund: this transaction has no payer/payee wallet details");
            return;
        }

        String amountAed = String.format(Locale.US, "%.2f", txn.amountFils / 100.0);
        String shortId = txn.transactionId.length() > 8
                ? "..." + txn.transactionId.substring(txn.transactionId.length() - 8)
                : txn.transactionId;

        // A refund is full-amount only and is now processed asynchronously — the
        // gateway accepts it and screens it out of band — so the dialog promises
        // submission, not completion.
        String message = "Refund " + txn.currency + " " + amountAed + " to customer?\n\n"
                + "Transaction: " + shortId
                + (txn.commissionFils > 0
                    ? "\nCommission reversed: " + txn.currency + " "
                        + QRConfig.filsToAedText(txn.commissionFils)
                    : "")
                + "\n\nThis action cannot be undone. The refund is processed in the "
                + "background — refresh to see the outcome.";

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
        ReturnApi.Call call = new ReturnApi.Call();
        call.transactionId  = txn.transactionId;
        call.type           = ReturnApi.TYPE_REFUND;
        call.amountFils     = txn.amountFils;
        // Reverse exactly the commission the sale was booked with. 0 when the sale
        // has not settled yet, which is also the right figure for every QR this
        // terminal generates — see QRConfig#QR_COMMISSION_AMOUNT.
        call.commissionFils = txn.commissionFils;
        // A refund reverses the sale, so the merchant (the sale's payee) pays and
        // the customer (the sale's payer) receives.
        call.payerWalletId  = txn.payeeWalletId;
        call.payeeWalletId  = txn.payerWalletId;
        // GAP 5: reuse the same key on retry so server deduplicates and prevents double-refund
        call.idempotencyKey = getOrCreateIdempotencyKey(txn.transactionId);

        ReturnApi.Result result = ReturnApi.execute(call);

        if (result.succeeded) {
            // GAP 5: clear the stored key — this transaction is fully settled
            idempotencyKeys.remove(txn.transactionId);
            pendingRefunds.remove(txn.transactionId);
        }
        return new RefundResult(result.message, result.pending);
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
        // Commission the sale was booked with, reversed as-is by a refund
        long   commissionFils        = 0;
        // GAP 2: track whether any refund attempt succeeded (vs just attempted)
        boolean hasRefunds           = false;
        boolean hasSuccessRefund     = false;
        boolean hasSuccessCancel     = false;  // voided — blocks refund, and is not a refund
        boolean hasPendingRefund     = false;  // refund in-flight (screening / CBDC pending)
        // GAP 3: refund transaction ID from a successful refund, for display/lookup
        String  refundTxId           = "";

        /** True when this sale has already been returned and must not be returned again. */
        boolean isReturned() {
            return hasSuccessRefund || hasSuccessCancel;
        }
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
            // Disable if: not a payment, not successful, already refunded or cancelled,
            // or a return is still in flight
            boolean canRefund = isMerchantPayment
                    && "SUCCESS".equals(txn.transactionStatus)
                    && !txn.isReturned()
                    && !txn.hasPendingRefund;
            btnRefund.setVisibility(isMerchantPayment ? View.VISIBLE : View.GONE);
            btnRefund.setEnabled(canRefund);
            btnRefund.setAlpha(canRefund ? 1f : 0.35f);
            btnRefund.setOnClickListener(v -> showRefundDialog(txn));

            // Settled-return badge. A cancelled sale reads "Cancelled", not "Refunded":
            // the money never left the customer, so calling it a refund misstates
            // what happened on a row the operator may have to explain.
            TextView tvRefundedBadge = convertView.findViewById(R.id.tv_refunded_badge);
            tvRefundedBadge.setVisibility(txn.isReturned() ? View.VISIBLE : View.GONE);
            tvRefundedBadge.setText(txn.hasSuccessCancel
                    ? R.string.tx_already_cancelled
                    : R.string.tx_already_refunded);

            // "Refund Pending" badge — shown when refund is in-flight (sanctions screening etc.)
            TextView tvRefundPendingBadge = convertView.findViewById(R.id.tv_refund_pending_badge);
            tvRefundPendingBadge.setVisibility(
                    (txn.hasPendingRefund && !txn.isReturned()) ? View.VISIBLE : View.GONE);

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
