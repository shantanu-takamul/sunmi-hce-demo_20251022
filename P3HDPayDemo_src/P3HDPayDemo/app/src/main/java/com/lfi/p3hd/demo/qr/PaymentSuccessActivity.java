package com.lfi.p3hd.demo.qr;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.lfi.p3hd.demo.BaseAppCompatActivity;
import com.lfi.p3hd.demo.MyApplication;
import com.lfi.p3hd.demo.R;
import com.lfi.p3hd.demo.net.HttpClients;
import com.lfi.p3hd.demo.print.ReceiptPrinter;
import com.lfi.p3hd.demo.utils.ApiKeyManager;
import com.lfi.p3hd.demo.utils.PreferencesUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class PaymentSuccessActivity extends BaseAppCompatActivity {
    public static final String EXTRA_AMOUNT_AED  = "amountAed";
    public static final String EXTRA_REQUEST_ID  = "requestId";
    public static final String EXTRA_EMV_PAYLOAD = "emvPayload";  // kept for caller compatibility

    // -------------------------------------------------------------------------
    // Return extras
    //
    // Everything needed to void or refund the sale without a lookup first. The
    // caller already holds all of it: the polling loop that detected SUCCESS read
    // the transaction, so passing it here is free, whereas re-fetching would put a
    // network round trip inside a ten-second window.
    //
    // All optional. A caller that supplies no transaction id gets the screen
    // exactly as it behaved before — no return actions at all.
    // -------------------------------------------------------------------------

    public static final String EXTRA_TRANSACTION_ID   = "transactionId";
    /** Original amount in fils. Both CANCEL and REFUND are full-amount only. */
    public static final String EXTRA_AMOUNT_FILS      = "amountFils";
    /** Commission the QR was generated with, in fils — reversed as-is. */
    public static final String EXTRA_COMMISSION_FILS  = "commissionFils";
    /** Payer as recorded on the ORIGINAL sale — the customer. Reversed on a return. */
    public static final String EXTRA_PAYER_WALLET     = "payerWalletId";
    /** Payee as recorded on the ORIGINAL sale — the merchant. Reversed on a return. */
    public static final String EXTRA_PAYEE_WALLET     = "payeeWalletId";
    /**
     * {@link SystemClock#elapsedRealtime()} at which CANCEL stops being offered,
     * or 0/absent to not offer it at all.
     *
     * The caller computes this via QRConfig#cancelDeadlineElapsed because only the
     * caller holds the transaction, and the deadline has to come from the
     * gateway's own createdAt and settlementStatus rather than from when this
     * screen opened. Passing a deadline rather than a start instant also means
     * navigating away and back cannot extend the window.
     *
     * elapsedRealtime, not wall clock, so a clock correction cannot move it.
     */
    public static final String EXTRA_CANCEL_DEADLINE  = "cancelDeadlineElapsedMs";

    private static final long MIN_PRINT_ANIMATION_MS = 4200L;
    private static final long MAX_PRINT_ANIMATION_MS = 8500L;

    private String amountAed;
    private String requestId;
    private String dateTimeStr;

    private String transactionId  = "";
    private long   amountFils;
    private long   commissionFils;
    private String payerWalletId  = "";
    private String payeeWalletId  = "";
    /** elapsedRealtime deadline for CANCEL; 0 means never offer it. */
    private long   cancelDeadline;

    private View layoutSuccessSummary;
    private View layoutPrintedSuccess;
    private View cardReceipt;
    private ProgressBar progressDetails;
    private ScrollView scrollReceipt;
    private MaterialButton primaryButton;
    private MaterialButton returnButton;

    private View          layoutReturnAction;
    private TextView      tvReturnStatus;
    private MaterialButton btnCancelPayment;
    private MaterialButton btnRefundPayment;

    private View     frameStatusIcon;
    private ImageView ivStatusIcon;
    private TextView tvStatusHeading;
    private TextView tvStatusSubtext;
    private TextView tvAmount;

    /** Drives the void-window countdown on the Cancel button. */
    private final Handler returnHandler = new Handler(Looper.getMainLooper());
    private final Runnable cancelWindowTick = this::refreshReturnAction;

    /**
     * One idempotency key per operation, so a retry of the same operation
     * deduplicates server-side while a refund after a failed cancel does not
     * collide with it under a shared key.
     */
    private final Map<String, String> returnKeys = new HashMap<>();

    private boolean returnInFlight;
    /** A return has been accepted — do not offer another for this sale. */
    private boolean returnSettled;
    /** A CANCEL came back SUCCESS: the sale no longer stands. */
    private boolean paymentVoided;

    private ReceiptDetails receiptDetails;
    private boolean detailsLoading;
    private boolean printInProgress;
    private boolean printJobDone;
    private boolean printJobSucceeded;
    private boolean printAnimationDone;
    private boolean printAnimationCancelled;
    private ValueAnimator receiptAnimator;

    /**
     * The shared client for the active environment.
     *
     * Resolved per use rather than held in a field: a field is frozen at
     * construction, so an environment switch never reaches it.
     */
    private OkHttpClient httpClient() {
        return HttpClients.forCurrentEnv();
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_payment_success);

        amountAed   = getIntent().getStringExtra(EXTRA_AMOUNT_AED);
        requestId   = getIntent().getStringExtra(EXTRA_REQUEST_ID);
        dateTimeStr = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date());

        transactionId   = safeText(getIntent().getStringExtra(EXTRA_TRANSACTION_ID));
        amountFils      = getIntent().getLongExtra(EXTRA_AMOUNT_FILS, 0L);
        commissionFils  = getIntent().getLongExtra(EXTRA_COMMISSION_FILS, QRConfig.QR_COMMISSION_AMOUNT);
        payerWalletId   = safeText(getIntent().getStringExtra(EXTRA_PAYER_WALLET));
        payeeWalletId   = safeText(getIntent().getStringExtra(EXTRA_PAYEE_WALLET));
        // 0 means the caller found no cancellable window — already settled, or the
        // transaction's age could not be established. Either way the operator gets
        // Refund and no false hope of a void.
        cancelDeadline  = getIntent().getLongExtra(EXTRA_CANCEL_DEADLINE, 0L);

        initView();
    }

    private void initView() {
        initActionbar(R.string.payment_success_title);

        layoutSuccessSummary = findViewById(R.id.layout_success_summary);
        layoutPrintedSuccess = findViewById(R.id.layout_printed_success);
        cardReceipt = findViewById(R.id.card_receipt);
        progressDetails = findViewById(R.id.progress_details);
        scrollReceipt = findViewById(R.id.scroll_receipt);
        primaryButton = findViewById(R.id.btn_primary);
        returnButton = findViewById(R.id.btn_return);

        layoutReturnAction = findViewById(R.id.layout_return_action);
        tvReturnStatus     = findViewById(R.id.tv_return_status);
        btnCancelPayment   = findViewById(R.id.btn_cancel_payment);
        btnRefundPayment   = findViewById(R.id.btn_refund_payment);
        btnCancelPayment.setOnClickListener(v -> confirmReturn(ReturnApi.TYPE_CANCEL));
        btnRefundPayment.setOnClickListener(v -> confirmReturn(ReturnApi.TYPE_REFUND));
        refreshReturnAction();

        frameStatusIcon = findViewById(R.id.frame_status_icon);
        ivStatusIcon    = findViewById(R.id.iv_status_icon);
        tvStatusHeading = findViewById(R.id.tv_status_heading);
        tvStatusSubtext = findViewById(R.id.tv_status_subtext);

        tvAmount = findViewById(R.id.tv_amount);
        tvAmount.setText("AED " + safeText(amountAed));
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

    @Override
    protected void onResume() {
        super.onResume();
        // The void window keeps running while this screen is not in front, so it may
        // have closed since onPause. Re-derive from the clock rather than resuming a
        // paused countdown.
        refreshReturnAction();
    }

    @Override
    protected void onPause() {
        super.onPause();
        returnHandler.removeCallbacks(cancelWindowTick);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        returnHandler.removeCallbacks(cancelWindowTick);
        if (receiptAnimator != null) receiptAnimator.cancel();
    }

    // -------------------------------------------------------------------------
    // Cancel / refund
    //
    // One slot, two operations, split by time. While the sale is still unsettled the
    // operator gets CANCEL, which is what actually fits the situation: the customer
    // is at the counter, the money is released synchronously and nothing has to be
    // explained afterwards. Once settlement closes that door the only honest option
    // left is REFUND — asynchronous, screened, and a permanent pair of entries in
    // the merchant's history.
    //
    // Showing both at once would be worse than showing one: they are near-identical
    // in outcome to an operator under time pressure, and picking REFUND while a void
    // is still possible is a strictly worse trade for everyone. So the window
    // chooses, and only one button is ever on screen.
    //
    // The window is NOT a POS preference — it is the gateway's pre-settlement window,
    // roughly ten seconds from the payment, computed from the transaction's own
    // createdAt by QRConfig#cancelDeadlineElapsed. This screen only counts down to
    // the deadline it was handed; it never decides one.
    // -------------------------------------------------------------------------

    /** Milliseconds of void window left, 0 once CANCEL is no longer offered. */
    private long cancelMsRemaining() {
        if (cancelDeadline <= 0L) return 0L;
        return Math.max(0L, cancelDeadline - SystemClock.elapsedRealtime());
    }

    /**
     * Puts the correct button in the return slot and keeps the countdown running.
     *
     * Re-entrant and driven by the clock rather than by accumulated state, so it is
     * also the right thing to call after a resume: an activity that spent eight
     * seconds in the background comes back showing REFUND, not a stale Cancel.
     */
    private void refreshReturnAction() {
        // Nothing to return, or no id to return it against.
        if (transactionId.isEmpty()) {
            layoutReturnAction.setVisibility(View.GONE);
            return;
        }
        layoutReturnAction.setVisibility(View.VISIBLE);

        // Mid-flight the buttons carry progress text — leave them alone, and in
        // particular do not let the countdown swap CANCEL out from under a call
        // that is already on the wire.
        if (returnInFlight) return;

        if (returnSettled) {
            returnHandler.removeCallbacks(cancelWindowTick);
            btnCancelPayment.setVisibility(View.GONE);
            btnRefundPayment.setVisibility(View.GONE);
            // With both buttons gone and the hero carrying the message, the slot has
            // nothing in it — collapse it rather than leave a padded empty strip.
            if (tvReturnStatus.getVisibility() != View.VISIBLE) {
                layoutReturnAction.setVisibility(View.GONE);
            }
            return;
        }

        long remaining = cancelMsRemaining();
        if (remaining > 0L) {
            btnRefundPayment.setVisibility(View.GONE);
            btnCancelPayment.setVisibility(View.VISIBLE);
            btnCancelPayment.setEnabled(true);
            int secondsLeft = (int) Math.ceil(remaining / 1000.0);
            btnCancelPayment.setText(
                getString(R.string.payment_success_cancel_countdown, secondsLeft));
            // Wake exactly when the displayed second changes, and again at expiry.
            long delay = remaining % 1000L;
            returnHandler.removeCallbacks(cancelWindowTick);
            returnHandler.postDelayed(cancelWindowTick, delay == 0L ? 1000L : delay);
        } else {
            returnHandler.removeCallbacks(cancelWindowTick);
            btnCancelPayment.setVisibility(View.GONE);
            btnRefundPayment.setVisibility(View.VISIBLE);
            btnRefundPayment.setEnabled(true);
            btnRefundPayment.setText(R.string.payment_success_refund);
        }
    }

    private void confirmReturn(String type) {
        if (returnInFlight || returnSettled) return;
        // A receipt is physically emerging from the printer; let it finish rather
        // than void the sale it describes halfway through.
        if (printInProgress) {
            showToast(R.string.payment_success_printing);
            return;
        }

        boolean isCancel = ReturnApi.TYPE_CANCEL.equals(type);
        String amount = QRConfig.CURRENCY + " " + displayAmount();
        String shortId = transactionId.length() > 8
                ? "…" + transactionId.substring(transactionId.length() - 8)
                : transactionId;

        String message = isCancel
            ? "Void " + amount + " and release the customer's funds?\n\n"
                + "Transaction: " + shortId + "\n\n"
                + "The customer is not charged. This cannot be undone."
            : "Refund " + amount + " to the customer?\n\n"
                + "Transaction: " + shortId + "\n\n"
                + "This cannot be undone. The refund is processed in the background — "
                + "check Transaction History for the outcome.";
        if (commissionFils > 0) {
            message += "\n\nCommission reversed: " + QRConfig.CURRENCY + " "
                    + QRConfig.filsToAedText(commissionFils);
        }

        // The countdown is parked while the dialog is up so the button underneath
        // cannot flip to Refund mid-decision. If the operator sits on the dialog
        // long enough for the gateway's own (much wider) cancel window to close, the
        // gateway rejects it with CANCEL_WINDOW_EXPIRED and says to use Refund —
        // that call is the gateway's to make, not the terminal's.
        returnHandler.removeCallbacks(cancelWindowTick);

        new AlertDialog.Builder(this)
            .setTitle(isCancel
                ? R.string.payment_success_cancel_confirm_title
                : R.string.payment_success_refund_confirm_title)
            .setMessage(message)
            .setPositiveButton(isCancel
                ? R.string.payment_success_cancel_confirm_action
                : R.string.payment_success_refund_confirm_action,
                (d, w) -> executeReturn(type))
            .setNegativeButton(R.string.payment_success_return_dismiss, null)
            .setOnDismissListener(d -> refreshReturnAction())
            .show();
    }

    private void executeReturn(String type) {
        if (returnInFlight || returnSettled) return;
        returnInFlight = true;
        returnHandler.removeCallbacks(cancelWindowTick);

        boolean isCancel = ReturnApi.TYPE_CANCEL.equals(type);
        MaterialButton active = isCancel ? btnCancelPayment : btnRefundPayment;
        active.setEnabled(false);
        active.setText(isCancel
            ? R.string.payment_success_cancelling
            : R.string.payment_success_refunding);
        // Printing a receipt for a sale that may be about to be voided would put a
        // contradictory slip in the customer's hand.
        primaryButton.setEnabled(false);
        returnButton.setEnabled(false);

        final ReturnApi.Call call = new ReturnApi.Call();
        call.transactionId  = transactionId;
        call.type           = type;
        call.amountFils     = amountFils;
        call.commissionFils = commissionFils;
        // The extras hold the ORIGINAL sale's wallets; a return reverses them, so
        // the merchant pays and the customer receives.
        call.payerWalletId  = payeeWalletId;
        call.payeeWalletId  = payerWalletId;
        call.idempotencyKey = returnKeys.computeIfAbsent(type, k -> UUID.randomUUID().toString());

        new Thread(() -> {
            try {
                ReturnApi.Result result = ReturnApi.execute(call);
                if (isFinishing()) return;
                runOnUiThread(() -> onReturnFinished(type, result));
            } catch (Exception e) {
                Log.e(TAG, "executeReturn " + type + " failed", e);
                if (isFinishing()) return;
                boolean windowClosed = e instanceof ReturnApi.Failure
                        && ((ReturnApi.Failure) e).cancelWindowClosed;
                runOnUiThread(() -> onReturnFailed(e.getMessage(), windowClosed));
            }
        }).start();
    }

    private void onReturnFinished(String type, ReturnApi.Result result) {
        returnInFlight = false;
        // Accepted either way: a pending return is still a return in progress, and
        // firing a second one would only create a duplicate to reconcile.
        returnSettled  = true;
        boolean isCancel = ReturnApi.TYPE_CANCEL.equals(type);
        paymentVoided = isCancel && result.succeeded;

        showToast(result.message);
        returnButton.setEnabled(true);

        if (paymentVoided) {
            // The sale no longer exists, so the whole screen has to say so. A note
            // under a green "Payment Successful!" tick is a contradiction, and the
            // hero is the one thing an operator reliably reads.
            showVoidedHero();
            refreshReturnAction();
            return;
        }

        primaryButton.setEnabled(true);
        // Amber rather than green for anything unresolved: a cancel that did not
        // come back SUCCESS, and a refund that is still being processed, are both
        // genuinely unknown outcomes at this point.
        if (isCancel) {
            showReturnStatus(result.message, R.color.warning);
        } else {
            showReturnStatus(getString(R.string.payment_success_refund_submitted),
                result.succeeded ? R.color.success : R.color.warning);
        }
        refreshReturnAction();
    }

    /**
     * Turns the success screen into a cancellation screen.
     *
     * Everything that asserted a completed sale has to stop asserting it: the tick,
     * the heading, and the amount. The amount stays visible but struck through — it
     * is still the figure the operator quoted, it simply no longer stands, and
     * removing it outright would leave them unable to confirm which sale was voided.
     */
    private void showVoidedHero() {
        // A receipt card may be open from View Details, and a print animation may be
        // mid-flight. Put the summary back in front so the void is the headline.
        if (receiptAnimator != null) receiptAnimator.cancel();
        cardReceipt.setVisibility(View.GONE);
        layoutPrintedSuccess.setVisibility(View.GONE);
        progressDetails.setVisibility(View.GONE);
        layoutSuccessSummary.setVisibility(View.VISIBLE);

        frameStatusIcon.setBackgroundResource(R.drawable.bg_soft_error_circle);
        ivStatusIcon.setImageResource(R.drawable.ic_cancelled);
        tvStatusHeading.setText(R.string.payment_cancelled_heading);
        tvStatusSubtext.setText(
            getString(R.string.payment_cancelled_subtext, "AED " + displayAmount()));
        tvStatusSubtext.setVisibility(View.VISIBLE);

        tvAmount.setPaintFlags(tvAmount.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        tvAmount.setTextColor(getColor(R.color.text_secondary));

        // No sale left to print. Hiding the action beats leaving a dead button
        // captioned "Payment cancelled", which reads as something still pressable.
        // The sibling weight makes Return take the full row on its own.
        primaryButton.setVisibility(View.GONE);
        returnButton.setText(R.string.payment_cancelled_done);

        // The hero now carries the message, so the small status line under the
        // buttons would only repeat it.
        tvReturnStatus.setVisibility(View.GONE);
        scrollReceipt.post(() -> scrollReceipt.smoothScrollTo(0, 0));
    }

    private void onReturnFailed(String reason, boolean cancelWindowClosed) {
        returnInFlight = false;
        showToast(reason == null ? getString(R.string.payment_success_details_failed) : reason);
        primaryButton.setEnabled(true);
        returnButton.setEnabled(true);

        // The gateway settled the sale sooner than the POS predicted. Retiring the
        // deadline here is what stops the countdown re-offering a button that can
        // only fail again; the operator gets Refund, which will work.
        if (cancelWindowClosed) {
            cancelDeadline = 0L;
            showReturnStatus(getString(R.string.payment_success_cancel_unavailable),
                R.color.warning);
        }

        // Not settled as a return: the operator may retry, and refreshReturnAction
        // re-chooses the button from the deadline.
        refreshReturnAction();
    }

    private void showReturnStatus(String text, int colorRes) {
        tvReturnStatus.setText(text);
        tvReturnStatus.setTextColor(getColor(colorRes));
        tvReturnStatus.setVisibility(View.VISIBLE);
    }

    /** Amount for operator-facing text — the fils figure when we have it. */
    private String displayAmount() {
        if (amountFils > 0) return QRConfig.filsToAedText(amountFils);
        return safeText(amountAed);
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
        return fetchTransactionDetails(false);
    }

    private JSONObject fetchTransactionDetails(boolean isRetry) throws Exception {
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

        int code;
        String body;
        try (Response response = httpClient().newCall(reqBuilder.build()).execute()) {
            code = response.code();
            body = response.body() == null ? "" : response.body().string();
            Log.d(TAG, "fetchTransactionDetails [" + code + "]: " + body);
        }

        // The payment has already succeeded by the time this screen opens, so a key
        // that expired during the sale must not be what stops the receipt printing.
        // Safe to block: loadTransactionDetails() runs this on its own thread.
        if (code == 401 && !isRetry) {
            Log.w(TAG, "fetchTransactionDetails: 401 — renewing API key");
            if (ApiKeyManager.get().fetchBlocking(30_000)) {
                return fetchTransactionDetails(true);
            }
            throw new IOException("API key expired and could not be renewed");
        }
        if (code < 200 || code >= 300) {
            throw new IOException(QRConfig.errorTextOf(body, code));
        }

        JSONObject tx = extractTransaction(new JSONObject(body));
        if (tx == null) {
            throw new IOException("No transaction details found");
        }
        return tx;
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

        // On QA the wallets are not flat on the transaction — they sit in
        // movements[]. Without this the Payer and Merchant rows are simply hidden
        // on every real receipt. Only consulted when the flat lookup found nothing,
        // so demo's shape keeps winning.
        if (payer.isEmpty())    payer    = QRConfig.walletFrom(tx, "payerWalletId");
        if (merchant.isEmpty()) merchant = QRConfig.walletFrom(tx, "payeeWalletId");

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

        Bitmap receiptBitmap = buildReceiptBitmap();
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

        startReceiptPrintAnimation(ReceiptPrinter.totalDotRows(receiptBitmap.getHeight()));
        new Thread(() -> printReceiptBitmap(receiptBitmap)).start();
    }

    /**
     * Renders the receipt at the paper's own resolution.
     *
     * The card on screen is not the thing rasterised — the same document is laid
     * out again at 384 dots by view_print_receipt.xml. Drawing the on-screen card
     * and scaling it is where the old receipts lost their legibility: that view is
     * 666 px wide on the P3H, and the 0.58 downscale leaves 13sp text as a grey
     * smear that a black/white threshold can only guess at.
     *
     * Row visibility is taken from the same rule the screen uses, so paper and
     * screen carry the same rows and never disagree about what the sale was.
     */
    private Bitmap buildReceiptBitmap() {
        if (receiptDetails == null) return null;

        View printView = LayoutInflater.from(this)
            .inflate(R.layout.view_print_receipt, null, false);

        ((TextView) printView.findViewById(R.id.print_amount)).setText(receiptDetails.amount);
        ((TextView) printView.findViewById(R.id.print_status)).setText(receiptDetails.status);
        setPrintRow(printView, R.id.print_row_transaction_id, R.id.print_transaction_id,
            receiptDetails.transactionId);
        setPrintRow(printView, R.id.print_row_request_id, R.id.print_request_id,
            receiptDetails.requestId);
        setPrintRow(printView, R.id.print_row_date, R.id.print_date, receiptDetails.date);
        setPrintRow(printView, R.id.print_row_currency, R.id.print_currency,
            receiptDetails.currency);
        setPrintRow(printView, R.id.print_row_payer, R.id.print_payer, receiptDetails.payer);
        setPrintRow(printView, R.id.print_row_terminal, R.id.print_terminal,
            receiptDetails.terminal);
        setPrintRow(printView, R.id.print_row_merchant, R.id.print_merchant,
            receiptDetails.merchant);

        return ReceiptPrinter.rasterize(printView);
    }

    private void setPrintRow(View root, int rowId, int valueId, String value) {
        View row = root.findViewById(rowId);
        if (isEmpty(value)) {
            row.setVisibility(View.GONE);
        } else {
            row.setVisibility(View.VISIBLE);
            ((TextView) root.findViewById(valueId)).setText(value);
        }
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
            ReceiptPrinter.print(MyApplication.app.printerOptV2, receiptBmp);
            printed = true;
        } catch (ReceiptPrinter.PrintException e) {
            Log.e(TAG, "printReceiptBitmap error", e);
            showToast(e.getMessage());
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
