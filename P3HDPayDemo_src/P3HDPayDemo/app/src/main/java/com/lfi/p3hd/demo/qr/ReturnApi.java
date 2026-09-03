package com.lfi.p3hd.demo.qr;

import android.util.Log;

import com.lfi.p3hd.demo.net.HttpClients;
import com.lfi.p3hd.demo.utils.ApiKeyManager;
import com.lfi.p3hd.demo.utils.PreferencesUtil;

import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * The acquirer return endpoint — POST /transactions/return/{transactionId}.
 *
 * One endpoint serves both operations, distinguished only by {@code type}, and they
 * behave differently enough that the difference drives the UI:
 *
 * <ul>
 *   <li><b>CANCEL</b> — synchronous, no sanctions screening, always the full
 *       original amount. Returns SUCCESS or FAILED before the call returns, so the
 *       operator learns the outcome while the customer is still at the counter.</li>
 *   <li><b>REFUND</b> — asynchronous as of Mithril 5.x. The gateway does cheap
 *       validation, returns <b>PENDING</b>, and runs screening, the dual-WSP
 *       signature gate and the value movement in MerchantRefundProcessor, with the
 *       final result delivered by callback. Full amount only.</li>
 * </ul>
 *
 * That async change matters for how a caller reads a response: for a REFUND,
 * PENDING is the <em>success</em> case, not a timeout and not an error. This POS has
 * no callback endpoint, so a pending refund is only ever resolved by re-reading the
 * transaction history.
 *
 * Both screens that can issue a return — the post-sale screen for CANCEL, the
 * history list for REFUND — go through here so the request shape and the error
 * vocabulary stay identical between them.
 *
 * Request body verified against the QA LFI Gateway (S2S) spec on 2026-08-14.
 */
public final class ReturnApi {

    private static final String TAG = "ReturnApi";

    public static final String TYPE_REFUND = "REFUND";
    public static final String TYPE_CANCEL = "CANCEL";

    private ReturnApi() { }

    /** One return request. Callers set what they know; the rest keeps its default. */
    public static final class Call {
        /** Transaction being returned — path segment and {@code originalTransactionId}. */
        public String transactionId = "";
        /** {@link #TYPE_REFUND} or {@link #TYPE_CANCEL}. */
        public String type = TYPE_REFUND;
        /**
         * Full original amount in fils. REFUND only — a CANCEL must name no figure
         * at all, so this is ignored for one and rejected if sent.
         *
         * Still worth setting on a cancel: callers use it for the operator-facing
         * confirmation, it just never reaches the wire.
         */
        public long   amountFils = 0L;
        /**
         * Acquirer commission being reversed, in fils. REFUND only, as above.
         *
         * Must match what the QR was generated with, or the merchant is credited
         * the wrong net. Always sent on a refund, including as an explicit 0 — see
         * {@link QRConfig#QR_COMMISSION_AMOUNT}.
         */
        public long   commissionFils = 0L;
        /** Reuse across retries of one logical return so the gateway deduplicates. */
        public String idempotencyKey = "";

        /**
         * The return movement's own payer and payee, only for the older gateway.
         *
         * QA's ReturnRequest dropped both fields — it derives the movement from
         * {@code originalTransactionId} — but demo still requires them, and one
         * build has to work against both. The gateway ignores unknown properties
         * (it already tolerates the {@code tradingLicenseNumber} and
         * {@code merchantCategoryCode} this app sends on generate), so sending them
         * where they are no longer read costs nothing.
         *
         * These describe the RETURN, so they are the original sale's pair reversed:
         * money flows back from the merchant, who was the sale's payee, to the
         * customer, who was its payer. Callers holding an original transaction must
         * swap before assigning — filling these straight from a sale record sends
         * the refund in the direction of the sale.
         */
        /** Funds come FROM here — the merchant, i.e. the original sale's payee. */
        public String payerWalletId = "";
        /** Funds go TO here — the customer, i.e. the original sale's payer. */
        public String payeeWalletId = "";
    }

    /**
     * A return the gateway definitively refused.
     *
     * Carries whether a void is now impossible, which the caller cannot recover
     * from the message alone but needs in order to stop offering CANCEL. The POS
     * predicts the settlement cutoff from one observed sample, so it can be wrong
     * in the unsafe direction; this is the gateway correcting it.
     */
    public static final class Failure extends Exception {
        /** Cancel is no longer possible for this transaction — only refund remains. */
        public final boolean cancelWindowClosed;

        Failure(String message, boolean cancelWindowClosed) {
            super(message);
            this.cancelWindowClosed = cancelWindowClosed;
        }
    }

    /**
     * Classifies a refusal as "the void window is gone" versus anything else.
     *
     * Two shapes mean the same thing. The documented one is
     * {@code CANCEL_WINDOW_EXPIRED}; the one QA actually returned on 2026-08-14 was
     * a generic {@code VALIDATION_ERROR} against {@code originalTransactionId} whose
     * message read "CANCEL is only allowed within the pre-settlement cancel window
     * of the original transaction". Matching the code alone would miss the real one,
     * so the message is inspected too.
     */
    private static Failure failureFor(JSONObject json, int httpCode, String type) {
        String text = friendlyError(json, httpCode, type);
        String errorCode = json.optString("errorCode", "");
        String raw = json.optString("message", "").toLowerCase(Locale.US);
        boolean windowGone = "CANCEL_WINDOW_EXPIRED".equals(errorCode)
                || (raw.contains("cancel")
                    && (raw.contains("window") || raw.contains("settle")));
        return new Failure(text, windowGone);
    }

    /** A return that reached the gateway and got a verdict. */
    public static final class Result {
        /** Operator-facing text, already mapped out of gateway codes. */
        public final String  message;
        /** Gateway status as reported, e.g. SUCCESS / PENDING / FAILED. */
        public final String  status;
        /** Id of the transaction the return created, when the gateway named one. */
        public final String  returnTxId;
        /** Still in flight — settled by a later history read, never by this call. */
        public final boolean pending;
        /** Terminal success. Mutually exclusive with {@link #pending}. */
        public final boolean succeeded;

        Result(String message, String status, String returnTxId, boolean pending, boolean succeeded) {
            this.message    = message;
            this.status     = status;
            this.returnTxId = returnTxId;
            this.pending    = pending;
            this.succeeded  = succeeded;
        }
    }

    /**
     * Posts the return and maps the response to a {@link Result}.
     *
     * Blocking — call it off the main thread.
     *
     * @throws Exception with operator-facing text when the return definitively
     *         failed. A pending outcome is a {@link Result}, not an exception.
     */
    public static Result execute(Call call) throws Exception {
        return execute(call, false);
    }

    private static Result execute(Call call, boolean isRetry) throws Exception {
        // The path segment is the RETURN's OWN id, and the gateway keys idempotency on
        // it (@Idempotent PATH_VARIABLE): it must be a fresh id, distinct from the sale.
        // The original transaction id belongs only in the body's originalTransactionId.
        // Reusing the sale's id in the path mints the refund against an id that already
        // exists and the return is rejected — the failure seen on 2026-09-01. The fresh
        // id is call.idempotencyKey (both callers generate one and hold it stable across
        // the 401 retry, so the retry replays the same refund rather than issuing a
        // second one); a defensive fallback covers a caller that left it unset.
        String returnId = !call.idempotencyKey.isEmpty()
                ? call.idempotencyKey
                : java.util.UUID.randomUUID().toString();
        String url = QRConfig.getBaseUrl() + QRConfig.RETURN_ENDPOINT + returnId;

        JSONObject body = new JSONObject();
        body.put("messageTypeId", QRConfig.RETURN_MESSAGE_TYPE_ID);
        body.put("originalTransactionId", call.transactionId);
        body.put("type", call.type);
        // Both monetary fields are REFUND-only.
        //
        // A CANCEL that names any figure is rejected outright:
        //   400 VALIDATION_ERROR {"field":"amount"}
        //       "Amount must not be provided for CANCEL"
        // A void always reverses the original in full — including its commission —
        // so there is nothing for the caller to specify, and offering a number reads
        // as an attempt at a partial cancel, which is not supported.
        //
        // This rule is NOT expressed in the OpenAPI schema: ReturnRequest declares
        // amount as a plain optional int64 with no conditional validation, so the
        // contract alone suggests sending it is fine. It is not. Verified against QA
        // 2026-08-14 by a CANCEL that was rejected for exactly this.
        //
        // commissionAmount is grouped here for the same reason rather than because a
        // rejection was observed: it is a REFUND-specific figure by its own
        // description, and being a declared property it is bound and validated just
        // like amount, so a void carrying one invites the identical failure.
        if (!TYPE_CANCEL.equals(call.type)) {
            body.put("amount", call.amountFils);
            // Always present on a refund, 0 included: the gateway treats a missing
            // commission as 0, but an explicit figure documents which split is
            // being reversed.
            body.put("commissionAmount", call.commissionFils);
        }
        body.put("reference", referenceFor(call));
        // Demo-only, ignored by QA — see Call#payerWalletId.
        if (!call.payerWalletId.isEmpty()) body.put("payerWalletId", call.payerWalletId);
        if (!call.payeeWalletId.isEmpty()) body.put("payeeWalletId", call.payeeWalletId);

        // Log the request, not just the response. The gateway enforces per-type
        // field rules that its own schema does not express, so a rejection is only
        // diagnosable next to the body that caused it. Carries no secrets — the API
        // key travels as a header.
        Log.d(TAG, call.type + " -> " + url + " " + body);

        Request.Builder reqBuilder = new Request.Builder()
                .url(url)
                .addHeader("X-LFI-ID", QRConfig.getXLfiId())
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(MediaType.parse("application/json"), body.toString()));

        if (!call.idempotencyKey.isEmpty()) {
            reqBuilder.addHeader("X-Idempotency-Key", call.idempotencyKey);
        }
        String apiKey = PreferencesUtil.getLfiApiKey();
        if (!apiKey.isEmpty()) reqBuilder.addHeader("X-LFI-API-KEY", apiKey);

        // A REFUND now returns as soon as validation passes, but the older
        // synchronous gateways screen inline and can take 30-60s. Keep the long
        // read window so this build stays correct against both.
        OkHttpClient client = HttpClients.forCurrentEnv().newBuilder()
                .readTimeout(90, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();

        int code;
        String responseBody;
        try (Response response = client.newCall(reqBuilder.build()).execute()) {
            code = response.code();
            responseBody = response.body() == null ? "" : response.body().string();
            Log.d(TAG, call.type + " [" + code + "]: " + responseBody);
        }

        // The sale has already completed by the time a return is possible, so a key
        // that expired since must not be what blocks it. Safe to block: callers run
        // this off the main thread.
        if (code == 401 && !isRetry) {
            Log.w(TAG, call.type + ": 401 — renewing API key");
            if (ApiKeyManager.get().fetchBlocking(30_000)) {
                return execute(call, true);
            }
            throw new Exception("API key expired and could not be renewed");
        }
        if (QRConfig.looksLikeHtmlPage(responseBody)) {
            throw new Exception(QRConfig.errorTextOf(responseBody, code));
        }

        JSONObject json = responseBody.isEmpty() ? new JSONObject() : new JSONObject(responseBody);
        String errorCode = json.optString("errorCode", "");

        // Already queued or in flight under this idempotency key — not a failure.
        if ("IDEMPOTENCY_CONCURRENT_REQUEST".equals(errorCode)
                || "IDEMPOTENCY_KEY_CONFLICT".equals(errorCode)) {
            return new Result(friendlyError(json, code, call.type), "PENDING", "", true, false);
        }

        // Any other non-2xx is a definitive failure. Note this catches a non-2xx
        // that carries no errorCode too — previously such a response fell through
        // to the status check below and was reported as a submitted return.
        if (code < 200 || code >= 300) {
            throw failureFor(json, code, call.type);
        }

        String status     = json.optString("status", "");
        String returnTxId = json.optString("transactionId", "");

        if ("SUCCESS".equals(status)) {
            return new Result(successText(call, returnTxId), status, returnTxId, false, true);
        }
        if ("FAILED".equals(status)) {
            throw failureFor(json, code, call.type);
        }

        // PENDING and the screening states. Expected for a REFUND, which finishes
        // out of band; for a CANCEL it means the gateway did not answer
        // synchronously after all, and re-reading history is still the way out.
        return new Result(pendingText(call, status), status, returnTxId, true, false);
    }

    private static String referenceFor(Call call) {
        String suffix = call.transactionId.length() > 8
                ? call.transactionId.substring(call.transactionId.length() - 8)
                : call.transactionId;
        return (TYPE_CANCEL.equals(call.type) ? "CANCEL-" : "REFUND-") + suffix;
    }

    private static String successText(Call call, String returnTxId) {
        String head = TYPE_CANCEL.equals(call.type)
                ? "Payment cancelled"
                : "Refund successful";
        if (returnTxId.isEmpty()) return head;
        String idSuffix = returnTxId.length() > 8
                ? "…" + returnTxId.substring(returnTxId.length() - 8)
                : returnTxId;
        String label = TYPE_CANCEL.equals(call.type) ? "\nCancel ID: " : "\nRefund ID: ";
        return head + label + idSuffix;
    }

    private static String pendingText(Call call, String status) {
        if (TYPE_CANCEL.equals(call.type)) {
            return "Cancel submitted — status: " + status
                    + ". Check Transaction History for the outcome.";
        }
        // A refund reaching PENDING is the documented happy path, so say so rather
        // than describing it as an unresolved submission.
        return "Refund accepted and is being processed"
                + ("PENDING".equals(status) ? "" : " — status: " + status)
                + ".\nCheck Transaction History for the outcome.";
    }

    /**
     * Maps gateway error codes and fields to one operator-facing string.
     *
     * @param type the return type being attempted, so the wording names the actual
     *             operation instead of calling every failure a refund.
     */
    public static String friendlyError(JSONObject json, int httpCode, String type) {
        boolean isCancel     = TYPE_CANCEL.equals(type);
        String  noun         = isCancel ? "cancel" : "refund";
        String  errorCode    = json.optString("errorCode", "");
        String  message      = json.optString("message", "");
        String  failureCode  = json.optString("failureCode", "");
        String  failureReason = json.optString("failureReason", "");
        JSONObject details   = json.optJSONObject("details");

        switch (errorCode) {
            case "IDEMPOTENCY_KEY_CONFLICT":
                return "A " + noun + " has already been queued for this transaction — "
                        + "refresh to see the status.";
            case "IDEMPOTENCY_CONCURRENT_REQUEST":
                return "A " + noun + " is already in progress — refresh in a moment "
                        + "to see the outcome.";
            case "CANCEL_WINDOW_EXPIRED":
                // The gateway, not the POS, decides this. The terminal's own
                // 10-second window closes long before, so an operator only sees
                // this by cancelling from somewhere that does not gate on time.
                return "Cancel window expired on the server — use Refund instead.";
            case "VALIDATION_ERROR":
                // The gateway's own message is the useful half — "Amount must not be
                // provided for CANCEL" — while details usually carries no more than
                // {"field":"amount"}. Leading with details buried the diagnosis
                // behind a bare field name on exactly that failure, so message wins
                // and the field is appended only as context.
                String field = details == null ? "" : details.optString("field", "");
                if (!message.isEmpty()) {
                    return message + (field.isEmpty() ? "" : " (" + field + ")");
                }
                if (details != null && details.length() > 0) {
                    return "Validation error: " + details.toString();
                }
                return "Validation error — check the " + noun + " details and try again.";
            case "TRANSACTION_NOT_FOUND":
                return "Transaction not found on the server.";
            case "REFUND_NOT_ALLOWED":
                return isCancel
                        ? "Cancel is not allowed for this transaction type."
                        : "Refund is not allowed for this transaction type.";
            case "INSUFFICIENT_FUNDS":
                return "Insufficient funds in the merchant wallet to process the " + noun + ".";
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
                // Falls back to the gateway's own {code, defaultMessage} envelope,
                // which carries the reason for most 4xx responses.
                return QRConfig.errorTextOf(json.toString(), httpCode);
        }
    }
}
