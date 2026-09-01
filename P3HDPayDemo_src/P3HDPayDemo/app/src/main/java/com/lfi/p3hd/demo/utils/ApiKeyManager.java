package com.lfi.p3hd.demo.utils;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.lfi.p3hd.demo.net.HttpClients;
import com.lfi.p3hd.demo.qr.QRConfig;

import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Manages the LFI API key lifecycle so the terminal can run unattended.
 *
 * Two credentials are involved and must not be confused:
 *   1. An operator JWT (short-lived) proving we may administer the LFI.
 *   2. The X-LFI-API-KEY (long-lived) used on every /lfi-gateway call.
 * (1) is used once, only to mint (2).
 *
 * State machine:
 *   IDLE ──► FETCHING ──► READY
 *                    └──► FAILED
 *
 * All callbacks are delivered on the main thread. Callers arriving while a
 * fetch is in flight are queued and notified together when it settles.
 */
public class ApiKeyManager {

    public interface OnReadyCallback {
        void onReady();
    }

    public interface OnErrorCallback {
        void onError(String message);
    }

    private enum State { IDLE, FETCHING, READY, FAILED }

    private static final String TAG = "ApiKeyManager";

    // -------------------------------------------------------------------------
    // Operator identity
    //
    // TODO(auth): this is a Central Bank realm account — its authority spans
    // every LFI on the platform, not just ours, and it ships as plaintext in the
    // APK. Acceptable for a QA box behind Cloudflare Access; NOT acceptable for
    // anything a merchant can reach.
    //
    // The replacement is a realm-scoped POS user, which was verified to work on
    // QA (MSP_ADMIN in realm acq-NI can regenerate; it gets 404 on other LFIs):
    //   POST https://keycloak.takamul.cc/realms/{lfi}/protocol/openid-connect/token
    //   grant_type=password&client_id=mithril-portal&username=..&password=..
    // Direct Access Grants is enabled and mithril-portal is a public client, so
    // no client secret is needed. Blocked only on a POS user being created.
    // Swapping over means replacing requestOperatorToken() and nothing else.
    // -------------------------------------------------------------------------
    private static final String LFI_AUTH_USERNAME = "test-global-admin";
    private static final String LFI_AUTH_PASSWORD = "12345";
    private static final String LFI_AUTH_REALM    = "cbuae";

    /**
     * The terminal owns the SECONDARY key slot; PRIMARY is left alone for the
     * Business Portal and any other consumer. Regenerating a slot immediately
     * invalidates whoever held it, so the two sides must not share one.
     */
    private static final String LFI_KEY_TYPE       = "SECONDARY";
    private static final int    LFI_KEY_EXPIRY_DAYS = 365;

    /** Renew once the stored key is within this window of expiring. */
    private static final long RENEW_BEFORE_MS = 7L * 24 * 60 * 60 * 1000;

    private static final ApiKeyManager INSTANCE = new ApiKeyManager();

    public static ApiKeyManager get() {
        return INSTANCE;
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private State state = State.IDLE;
    private String lastError = "";
    private final List<OnReadyCallback> pendingReady = new ArrayList<>();
    private final List<OnErrorCallback> pendingError = new ArrayList<>();

    private ApiKeyManager() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Ensure a usable API key is available, then call onReady (main thread).
     *
     * A key that is merely close to expiry is still valid, so a failed renewal
     * falls back to it rather than blocking a sale.
     */
    public synchronized void ensureReady(OnReadyCallback onReady, OnErrorCallback onError) {
        boolean haveKey = !PreferencesUtil.getLfiApiKey().isEmpty();

        // A key pasted in Settings is the operator's deliberate choice and may be
        // the only one that works on this env (some envs 403 the regenerate call).
        // Never mint over it.
        if (haveKey && PreferencesUtil.isLfiApiKeyManual()) {
            state = State.READY;
            mainHandler.post(onReady::onReady);
            return;
        }

        if (haveKey && !isExpiringSoon()) {
            state = State.READY;
            mainHandler.post(onReady::onReady);
            return;
        }

        if (state == State.FAILED) {
            // Never cache a failure across user-initiated taps.
            state = State.IDLE;
            lastError = "";
        }

        OnErrorCallback effectiveError = onError;
        if (haveKey) {
            effectiveError = message -> {
                Log.w(TAG, "renewal failed, continuing on the existing key: " + message);
                onReady.onReady();
            };
        }

        pendingReady.add(onReady);
        pendingError.add(effectiveError);

        if (state == State.FETCHING) return;

        state = State.FETCHING;
        startLogin();
    }

    /**
     * Force a fresh mint regardless of current state — used after an HTTP 401
     * and by Settings.
     *
     * The stored key is deliberately NOT cleared up front: if the mint fails the
     * terminal would be left with no credential at all. It is overwritten only
     * once a replacement actually arrives.
     */
    public synchronized void fetch(OnReadyCallback onReady, OnErrorCallback onError) {
        // Settings clears the manual flag before an explicit fetch, so arriving
        // here with it still set means this is an automatic 401 recovery. Minting
        // would throw away the operator's key and, on envs where regenerate is
        // forbidden, leave the terminal with nothing. Report the real problem: the
        // key that was entered by hand is the one the gateway just rejected.
        if (PreferencesUtil.isLfiApiKeyManual() && !PreferencesUtil.getLfiApiKey().isEmpty()) {
            mainHandler.post(() -> onError.onError(
                "The API key entered in Settings was rejected — update it in Settings."));
            return;
        }

        pendingReady.clear();
        pendingError.clear();
        pendingReady.add(onReady);
        pendingError.add(onError);
        lastError = "";

        if (state == State.FETCHING) return;

        state = State.FETCHING;
        startLogin();
    }

    /**
     * Blocking variant of {@link #fetch} for callers already on a background
     * thread. Must never be called from the main thread — callbacks are
     * delivered there, so doing so would deadlock.
     *
     * @return true if a new key was stored.
     */
    public boolean fetchBlocking(long timeoutMs) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("fetchBlocking() called on the main thread");
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] ok = {false};
        fetch(
            () -> { ok[0] = true;  latch.countDown(); },
            message -> { ok[0] = false; latch.countDown(); }
        );
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return ok[0];
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    /**
     * The shared client for the active environment.
     *
     * This used to be a {@code final} field. On a static singleton that means the
     * client was built at class load and never again — an environment switch in
     * Settings changed every other caller's client and not this one, so key minting
     * kept whatever TLS and connection configuration the very first environment had.
     * Resolve it per call instead.
     */
    private OkHttpClient httpClient() {
        return HttpClients.forCurrentEnv();
    }

    /**
     * True when the stored key is close enough to expiry to be worth renewing.
     * An unknown or unparseable expiry returns false: a key pasted in Settings
     * carries no expiry and must not trigger churn on every call.
     */
    private boolean isExpiringSoon() {
        String expiry = PreferencesUtil.getLfiApiKeyExpiry();
        if (expiry.isEmpty()) return false;
        long expiresAt = QRConfig.parseIsoUtc(expiry);
        if (expiresAt <= 0) return false;
        return expiresAt - System.currentTimeMillis() < RENEW_BEFORE_MS;
    }

    private void startLogin() {
        requestOperatorToken(new TokenCallback() {
            @Override
            public void onToken(String accessToken) {
                startRegen(accessToken);
            }

            @Override
            public void onFailure(String message) {
                deliverError(message);
            }
        });
    }

    private interface TokenCallback {
        void onToken(String accessToken);
        void onFailure(String message);
    }

    /**
     * Obtains the short-lived operator JWT used to mint an API key.
     * This is the single seam to replace when moving to a realm-scoped POS user
     * (see the TODO on the credential constants above).
     */
    private void requestOperatorToken(TokenCallback callback) {
        try {
            JSONObject body = new JSONObject();
            body.put("username", LFI_AUTH_USERNAME);
            body.put("password", LFI_AUTH_PASSWORD);
            body.put("realm",    LFI_AUTH_REALM);

            Request req = new Request.Builder()
                .url(QRConfig.getBaseUrl() + QRConfig.AUTH_ENDPOINT)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(MediaType.parse("application/json"), body.toString()))
                .build();

            httpClient().newCall(req).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    callback.onFailure("No connection to payment gateway");
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String respBody = response.body() == null ? "" : response.body().string();
                    Log.d(TAG, "login [" + response.code() + "]");
                    if (!response.isSuccessful()) {
                        callback.onFailure("Gateway auth failed: "
                            + QRConfig.errorTextOf(respBody, response.code()));
                        return;
                    }
                    try {
                        callback.onToken(new JSONObject(respBody).getString("access_token"));
                    } catch (Exception e) {
                        callback.onFailure("Gateway auth response invalid");
                    }
                }
            });
        } catch (Exception e) {
            callback.onFailure("Failed to reach payment gateway: " + e.getMessage());
        }
    }

    private void startRegen(String accessToken) {
        try {
            JSONObject body = new JSONObject();
            body.put("keyType",    LFI_KEY_TYPE);
            body.put("expiryDays", LFI_KEY_EXPIRY_DAYS);

            Request req = new Request.Builder()
                .url(QRConfig.getBaseUrl() + QRConfig.getRegenEndpoint())
                .addHeader("Authorization",     "Bearer " + accessToken)
                .addHeader("X-LFI-ID",          QRConfig.getXLfiId())
                .addHeader("X-Idempotency-Key", UUID.randomUUID().toString())
                .addHeader("Content-Type",      "application/json")
                .post(RequestBody.create(MediaType.parse("application/json"), body.toString()))
                .build();

            httpClient().newCall(req).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    deliverError("No connection to payment gateway");
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String respBody = response.body() == null ? "" : response.body().string();
                    Log.d(TAG, "regen [" + response.code() + "] keyType=" + LFI_KEY_TYPE);
                    if (!response.isSuccessful()) {
                        deliverError("Gateway key setup failed: "
                            + QRConfig.errorTextOf(respBody, response.code()));
                        return;
                    }
                    try {
                        JSONObject json = new JSONObject(respBody);
                        PreferencesUtil.setLfiApiKey(json.getString("apiKey"));
                        PreferencesUtil.setLfiApiKeyExpiry(json.optString("expiresAt", ""));
                        PreferencesUtil.setLfiApiKeyManual(false);
                        deliverReady();
                    } catch (Exception e) {
                        deliverError("Gateway key setup response invalid");
                    }
                }
            });
        } catch (Exception e) {
            deliverError("Failed to reach payment gateway: " + e.getMessage());
        }
    }

    private synchronized void deliverReady() {
        state = State.READY;
        List<OnReadyCallback> callbacks = new ArrayList<>(pendingReady);
        pendingReady.clear();
        pendingError.clear();
        for (OnReadyCallback cb : callbacks) {
            mainHandler.post(cb::onReady);
        }
    }

    private synchronized void deliverError(String message) {
        state = State.FAILED;
        lastError = message;
        List<OnErrorCallback> callbacks = new ArrayList<>(pendingError);
        pendingReady.clear();
        pendingError.clear();
        for (OnErrorCallback cb : callbacks) {
            mainHandler.post(() -> cb.onError(message));
        }
    }
}
