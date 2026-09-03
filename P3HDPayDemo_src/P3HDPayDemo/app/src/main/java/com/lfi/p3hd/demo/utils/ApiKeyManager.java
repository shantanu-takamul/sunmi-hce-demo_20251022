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
    // *** These constants are for the cloud environments ONLY. ***
    //
    // They must never be sent to a CBUAE on-premise box. Sending a shared,
    // platform-wide Central Bank credential — one that ships in plaintext in every
    // copy of this APK — to a real Central Bank server is a credential disclosure,
    // and it would happen for no better reason than an operator changing a dropdown.
    // credentialsFor() is the single place that decides, and it returns null rather
    // than these values on an on-prem env; every caller treats null as "ask the
    // operator", never as "fall back to the defaults".
    //
    // The replacement is a realm-scoped POS user, which was verified to work on
    // QA (MSP_ADMIN in realm acq-NI can regenerate; it gets 404 on other LFIs):
    //   POST https://keycloak.takamul.cc/realms/{lfi}/protocol/openid-connect/token
    //   grant_type=password&client_id=mithril-portal&username=..&password=..
    // Direct Access Grants is enabled and mithril-portal is a public client, so
    // no client secret is needed. Blocked only on a POS user being created.
    // Swapping over means replacing requestOperatorToken() and nothing else.
    // -------------------------------------------------------------------------
    private static final String CLOUD_AUTH_USERNAME = "test-global-admin";
    private static final String CLOUD_AUTH_PASSWORD = "12345";
    private static final String LFI_AUTH_REALM      = "cbuae";

    /**
     * Which key slot this terminal owns, per environment family.
     *
     * On the cloud environments the POS has always taken SECONDARY, leaving PRIMARY
     * to the Business Portal; changing that now would invalidate whatever holds it.
     *
     * On-prem the convention is the other way round and is deliberate: the POS fleet
     * owns PRIMARY and the in-cluster lfi-reference owns SECONDARY. Regenerating a
     * slot immediately invalidates its previous holder, the plaintext is shown once
     * and cannot be recovered, and the backend caches key config for about five
     * minutes — so a collision looks like an intermittent fault half an hour later.
     * The split is what keeps a POS rotation from breaking screening. It matches
     * scripts/pos-fetch-key.sh and POS_BOOTSTRAP_BRAIN.md section 6.3.
     */
    private static final String KEY_SLOT_CLOUD   = "SECONDARY";
    private static final String KEY_SLOT_ON_PREM = "PRIMARY";

    private static final int    LFI_KEY_EXPIRY_DAYS = 365;

    /** Portal login for an environment, or null when the operator must supply one. */
    private static Credentials credentialsFor(String env) {
        if (!QRConfig.isOnPrem(env)) {
            return new Credentials(CLOUD_AUTH_USERNAME, CLOUD_AUTH_PASSWORD);
        }
        if (!PreferencesUtil.hasPortalCredentials(env)) return null;
        return new Credentials(
            PreferencesUtil.getPortalUsername(env),
            PreferencesUtil.getPortalPassword(env));
    }

    private static final class Credentials {
        final String username;
        final String password;
        Credentials(String username, String password) {
            this.username = username;
            this.password = password;
        }
    }

    /** The slot this terminal mints into on the given environment. */
    private static String keySlotFor(String env) {
        return QRConfig.isOnPrem(env) ? KEY_SLOT_ON_PREM : KEY_SLOT_CLOUD;
    }

    /** Shown wherever the terminal cannot proceed without the operator. */
    private static final String NEEDS_OPERATOR_SETUP =
        "Enter an API key or portal credentials in Settings";

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
        String env = PreferencesUtil.getEnv();
        boolean haveKey = !PreferencesUtil.getLfiApiKey().isEmpty();

        // On-prem: a stored key is final, whatever its origin and whatever its
        // expiry says. Minting there rotates a slot on a live Central Bank box,
        // invalidates whoever else held it, and cannot be undone — the plaintext of
        // the old key is gone. "Fetch if absent" means exactly that, and absent is
        // the only condition under which this class will mint on those environments.
        if (haveKey && QRConfig.isOnPrem(env)) {
            Log.i(TAG, "apikey: ready env=" + env + " source=stored (on-prem, never re-minted)");
            state = State.READY;
            mainHandler.post(onReady::onReady);
            return;
        }

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

        // Nothing stored, and on-prem there is nothing to mint with unless the
        // operator has supplied a portal login. Say so instead of failing at the
        // network layer with something that reads like an outage.
        if (credentialsFor(env) == null) {
            Log.w(TAG, "apikey: blocked env=" + env + " reason=no-key-no-credentials");
            state = State.FAILED;
            mainHandler.post(() -> onError.onError(NEEDS_OPERATOR_SETUP));
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
        fetch(false, onReady, onError);
    }

    /**
     * As {@link #fetch(OnReadyCallback, OnErrorCallback)}, distinguishing an operator
     * who asked for a new key from the app recovering by itself.
     *
     * The two must not behave the same on an on-prem environment. Automatic recovery
     * there must never rotate: a 401 on a stored key means the key is wrong, and
     * minting a replacement would invalidate whatever else holds that slot — most
     * likely the in-cluster lfi-reference, whose screening leg then fails, roughly
     * five minutes later, looking like something else entirely. An operator standing
     * in front of the terminal choosing "Fetch from portal" is a different act, and
     * is allowed.
     *
     * @param operatorInitiated true only from a deliberate action in Settings
     */
    public synchronized void fetch(boolean operatorInitiated,
                                   OnReadyCallback onReady,
                                   OnErrorCallback onError) {
        String env = PreferencesUtil.getEnv();
        boolean haveKey = !PreferencesUtil.getLfiApiKey().isEmpty();

        // Settings clears the manual flag before an explicit fetch, so arriving
        // here with it still set means this is an automatic 401 recovery. Minting
        // would throw away the operator's key and, on envs where regenerate is
        // forbidden, leave the terminal with nothing. Report the real problem: the
        // key that was entered by hand is the one the gateway just rejected.
        if (PreferencesUtil.isLfiApiKeyManual() && haveKey) {
            mainHandler.post(() -> onError.onError(
                "The API key entered in Settings was rejected — update it in Settings."));
            return;
        }

        if (QRConfig.isOnPrem(env) && haveKey && !operatorInitiated) {
            Log.w(TAG, "apikey: refused env=" + env + " reason=stored-key-rejected-no-auto-rotation");
            mainHandler.post(() -> onError.onError(
                "API key rejected — verify or re-enter it in Settings."));
            return;
        }

        if (credentialsFor(env) == null) {
            Log.w(TAG, "apikey: blocked env=" + env + " reason=no-credentials");
            mainHandler.post(() -> onError.onError(NEEDS_OPERATOR_SETUP));
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
     * Startup hook: mint a key once, if and only if the terminal needs one and can.
     *
     * The point is that an on-prem terminal should be usable the moment it is handed
     * to a merchant with credentials already configured, rather than failing the
     * first sale and sending someone into Settings. Every condition is a refusal:
     *
     *   not on-prem      -> the cloud path already self-heals, leave it alone
     *   key stored       -> never re-mint, whatever its origin (see ensureReady)
     *   no credentials   -> nothing to mint with; the operator will be told when
     *                       a sale is attempted, which is a better moment than a
     *                       toast at launch
     *
     * Runs on whatever thread calls it; the underlying request is asynchronous.
     */
    public void mintOnStartupIfNeeded() {
        String env = PreferencesUtil.getEnv();
        if (!QRConfig.isOnPrem(env)) return;
        if (!PreferencesUtil.getLfiApiKey().isEmpty()) {
            Log.i(TAG, "apikey: startup env=" + env + " outcome=key-already-stored");
            return;
        }
        if (!PreferencesUtil.hasPortalCredentials(env)) {
            Log.i(TAG, "apikey: startup env=" + env + " outcome=no-credentials-configured");
            return;
        }

        Log.i(TAG, "apikey: startup env=" + env + " outcome=minting slot=" + keySlotFor(env));
        fetch(
            () -> Log.i(TAG, "apikey: startup env=" + env + " outcome=minted"),
            message -> Log.w(TAG, "apikey: startup env=" + env + " outcome=failed " + message)
        );
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
            String env = PreferencesUtil.getEnv();
            Credentials credentials = credentialsFor(env);
            if (credentials == null) {
                // Belt and braces. Both callers check first; this makes it impossible
                // for a future one to reach the network with the cloud constants on an
                // on-prem environment by forgetting to.
                callback.onFailure(NEEDS_OPERATOR_SETUP);
                return;
            }

            JSONObject body = new JSONObject();
            body.put("username", credentials.username);
            body.put("password", credentials.password);
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
                            + QRConfig.errorTextOf(respBody, response));
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
            final String env  = PreferencesUtil.getEnv();
            final String slot = keySlotFor(env);

            JSONObject body = new JSONObject();
            body.put("keyType",    slot);
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
                    Log.d(TAG, "regen [" + response.code() + "] keyType=" + slot);
                    if (!response.isSuccessful()) {
                        Log.w(TAG, "apikey: mint-failed env=" + env + " slot=" + slot
                            + " http=" + response.code());
                        deliverError("Gateway key setup failed: "
                            + QRConfig.errorTextOf(respBody, response));
                        return;
                    }
                    try {
                        JSONObject json = new JSONObject(respBody);
                        PreferencesUtil.setLfiApiKey(json.getString("apiKey"));
                        PreferencesUtil.setLfiApiKeyExpiry(json.optString("expiresAt", ""));
                        PreferencesUtil.setLfiApiKeyManual(false);
                        Log.i(TAG, "apikey: minted env=" + env + " slot=" + slot
                            + " lfi=" + QRConfig.getXLfiId(env)
                            + " expires=" + json.optString("expiresAt", "?"));
                        deliverReady();
                    } catch (Exception e) {
                        Log.w(TAG, "apikey: mint-failed env=" + env + " slot=" + slot
                            + " reason=unparseable-response");
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
